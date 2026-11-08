package io.agenticpayments.composition.di;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ExplicitServiceCollection implements ServiceCollection {
    private static final int MAX_SERVICES = 1024;
    private static final int MAX_DECORATORS = 32;
    private static final int MAX_DEPENDENCIES = 64;
    private final Map<ServiceKey<?>, Binding> bindings = new LinkedHashMap<>();
    private final Map<ServiceKey<?>, List<Decoration>> decorations = new LinkedHashMap<>();

    @Override
    public <T> ServiceCollection add(ServiceKey<T> key, Class<? extends T> implementation,
                                     Lifetime lifetime, boolean threadSafe) {
        Constructor<?> constructor = soleConstructor(implementation);
        return register(key, new Binding(lifetime, threadSafe, Ownership.OWNED, constructor,
            defaultParameters(constructor), null, null));
    }

    @Override
    public <T> ServiceCollection add(ServiceKey<T> key, Constructor<? extends T> constructor,
                                     List<ServiceKey<?>> parameters, Lifetime lifetime, boolean threadSafe) {
        return register(key, new Binding(lifetime, threadSafe, Ownership.OWNED, constructor,
            parameters, null, null));
    }

    @Override
    public <T> ServiceCollection factory(ServiceKey<T> key, Lifetime lifetime, boolean threadSafe,
                                         Ownership ownership, List<ServiceKey<?>> dependencies,
                                         ServiceFactory<? extends T> factory) {
        return register(key, new Binding(lifetime, threadSafe, ownership, null, dependencies,
            Objects.requireNonNull(factory, "factory"), null));
    }

    @Override
    public <T> ServiceCollection instance(ServiceKey<T> key, T instance, Ownership ownership, boolean threadSafe) {
        Objects.requireNonNull(key, "key");
        if (!key.type().isInstance(instance)) throw failure("Invalid service instance");
        return register(key, new Binding(Lifetime.SINGLETON, threadSafe, ownership, null, List.of(), null, instance));
    }

    private ServiceCollection register(ServiceKey<?> key, Binding binding) {
        Objects.requireNonNull(key, "key");
        if (bindings.containsKey(key)) throw failure("Duplicate service binding");
        if (bindings.size() >= MAX_SERVICES) throw failure("Service registration limit exceeded");
        if (binding.constructor != null) validateConstructor(key, binding.constructor, binding.dependencies);
        bindings.put(key, binding);
        return this;
    }

    @Override
    public ServiceCollection replace(ServiceKey<?> key) {
        if (bindings.remove(Objects.requireNonNull(key, "key")) == null) throw failure("Missing replacement binding");
        decorations.remove(key);
        return this;
    }

    @Override
    public <T> ServiceCollection decorate(ServiceKey<T> key, Class<? extends T> implementation, int order) {
        Objects.requireNonNull(key, "key");
        Constructor<?> constructor = soleConstructor(implementation);
        List<ServiceKey<?>> parameters = new ArrayList<>(defaultParameters(constructor));
        int inner = -1;
        for (int i = 0; i < constructor.getParameterCount(); i++) {
            if (constructor.getParameterTypes()[i] == key.type()) {
                if (inner >= 0) throw failure("Ambiguous decorator inner parameter");
                inner = i;
                parameters.set(i, key);
            }
        }
        if (inner < 0) throw failure("Missing decorator inner parameter");
        validateConstructor(key, constructor, parameters);
        if (!decorations.containsKey(key) && decorations.size() >= MAX_SERVICES) {
            throw failure("Service registration limit exceeded");
        }
        List<Decoration> chain = decorations.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (chain.size() >= MAX_DECORATORS) throw failure("Decorator limit exceeded");
        if (chain.stream().anyMatch(value -> value.order == order)) throw failure("Duplicate decorator order");
        chain.add(new Decoration(constructor, List.copyOf(parameters), inner, order));
        return this;
    }

    @Override
    public ServiceProvider build() {
        Map<ServiceKey<?>, Node> roots = new LinkedHashMap<>();
        List<Node> nodes = new ArrayList<>();
        for (ServiceKey<?> key : decorations.keySet()) {
            if (!bindings.containsKey(key)) throw failure("Missing decorated service binding");
        }
        for (var entry : bindings.entrySet()) {
            ServiceKey<?> key = entry.getKey();
            Binding binding = entry.getValue();
            Node inner = new Node(key, binding, -1, null);
            nodes.add(inner);
            List<Decoration> chain = new ArrayList<>(decorations.getOrDefault(key, List.of()));
            chain.sort(Comparator.comparingInt(Decoration::order).reversed());
            for (Decoration decorator : chain) {
                Binding wrapper = new Binding(binding.lifetime, binding.threadSafe, Ownership.OWNED,
                    decorator.constructor, decorator.dependencies, null, null);
                inner = new Node(key, wrapper, decorator.inner, inner);
                nodes.add(inner);
            }
            roots.put(key, inner);
        }
        for (Node node : nodes) {
            for (int i = 0; i < node.binding.dependencies.size(); i++) {
                Node dependency = i == node.innerParameter ? node.inner : roots.get(node.binding.dependencies.get(i));
                if (dependency == null) throw failure("Missing service dependency");
                node.dependencies.add(dependency);
            }
            if (node.binding.lifetime == Lifetime.SINGLETON && !node.binding.threadSafe) {
                throw failure("Singleton requires explicit thread safety");
            }
        }
        Map<Node, Integer> states = new IdentityHashMap<>();
        for (Node node : nodes) validateGraph(node, states, 0);
        Root root = new Root(roots);
        try {
            for (Node node : roots.values()) {
                if (node.binding.lifetime == Lifetime.SINGLETON) root.resolve(node, null);
            }
            return root;
        } catch (RuntimeException | Error failure) {
            // Cleanup cannot replace the startup outcome or reveal a factory's raw exception.
            root.closeInternal();
            throw failure;
        }
    }

    private static int validateGraph(Node node, Map<Node, Integer> states, int depth) {
        if (depth > 128) throw failure("Service dependency depth limit exceeded");
        Integer state = states.get(node);
        if (state != null) {
            if (state == -1) throw failure("Service dependency cycle");
            return state;
        }
        states.put(node, -1);
        int height = 0;
        for (Node dependency : node.dependencies) {
            if (node.binding.lifetime == Lifetime.SINGLETON
                && (dependency.binding.lifetime != Lifetime.SINGLETON || !dependency.binding.threadSafe)) {
                throw failure("Singleton captures a shorter or unsafe lifetime");
            }
            height = Math.max(height, 1 + validateGraph(dependency, states, depth + 1));
        }
        if (height > 128) throw failure("Service dependency depth limit exceeded");
        states.put(node, height);
        return height;
    }

    private static Constructor<?> soleConstructor(Class<?> implementation) {
        Objects.requireNonNull(implementation, "implementation");
        Constructor<?>[] constructors = implementation.getConstructors();
        if (constructors.length != 1) throw failure("Service requires explicit constructor selection");
        return constructors[0];
    }

    private static List<ServiceKey<?>> defaultParameters(Constructor<?> constructor) {
        List<ServiceKey<?>> parameters = new ArrayList<>();
        for (Class<?> type : constructor.getParameterTypes()) parameters.add(ServiceKey.of(type));
        return parameters;
    }

    private static void validateConstructor(ServiceKey<?> key, Constructor<?> constructor, List<ServiceKey<?>> parameters) {
        Class<?> implementation = constructor.getDeclaringClass();
        if (!Modifier.isPublic(constructor.getModifiers()) || !Modifier.isPublic(implementation.getModifiers())
            || Modifier.isAbstract(implementation.getModifiers()) || !key.type().isAssignableFrom(implementation)
            || constructor.getParameterCount() != parameters.size()) throw failure("Invalid service constructor");
        for (int i = 0; i < parameters.size(); i++) {
            if (!constructor.getParameterTypes()[i].isAssignableFrom(parameters.get(i).type())) {
                throw failure("Invalid constructor parameter binding");
            }
        }
    }

    private static ServiceCompositionException failure(String message) {
        return new ServiceCompositionException(message);
    }

    private record Binding(Lifetime lifetime, boolean threadSafe, Ownership ownership,
                           Constructor<?> constructor, List<ServiceKey<?>> dependencies,
                           ServiceFactory<?> factory, Object instance) {
        Binding {
            Objects.requireNonNull(lifetime, "lifetime");
            Objects.requireNonNull(ownership, "ownership");
            dependencies = List.copyOf(dependencies);
            if (dependencies.size() > MAX_DEPENDENCIES) throw failure("Dependency limit exceeded");
        }
    }

    private record Decoration(Constructor<?> constructor, List<ServiceKey<?>> dependencies, int inner, int order) { }

    private static final class Node {
        final ServiceKey<?> key;
        final Binding binding;
        final int innerParameter;
        final Node inner;
        final List<Node> dependencies = new ArrayList<>();
        Node(ServiceKey<?> key, Binding binding, int innerParameter, Node inner) {
            this.key = key;
            this.binding = binding;
            this.innerParameter = innerParameter;
            this.inner = inner;
        }
    }

    /** Serializes construction and shutdown; service invocations never run under this monitor. */
    private static final class Root implements ServiceProvider {
        final Map<ServiceKey<?>, Node> roots;
        final Map<Node, Object> singletons = new IdentityHashMap<>();
        final List<Scope> scopes = new ArrayList<>();
        final Owner owned = new Owner();
        final Map<Object, Owner> activeOwners = new IdentityHashMap<>();
        final ReferenceQueue<Object> disposedQueue = new ReferenceQueue<>();
        final Set<IdentityReference> disposed = new HashSet<>();
        boolean closed;

        Root(Map<ServiceKey<?>, Node> roots) { this.roots = Map.copyOf(roots); }

        @Override
        public synchronized <T> T get(ServiceKey<T> key) {
            checkOpen();
            Node node = node(key);
            if (node.binding.lifetime != Lifetime.SINGLETON) throw failure("Root resolves singletons only");
            return key.type().cast(resolve(node, null));
        }

        @Override
        public synchronized ServiceScope createScope() {
            checkOpen();
            Scope scope = new Scope(this);
            scopes.add(scope);
            return scope;
        }

        private Node node(ServiceKey<?> key) {
            Node node = roots.get(Objects.requireNonNull(key, "key"));
            if (node == null) throw failure("Unregistered service");
            return node;
        }

        private void checkOpen() {
            if (closed) throw failure("Service provider is closed");
        }

        private Object resolve(Node node, Scope scope) {
            checkOpen();
            Binding binding = node.binding;
            if (binding.lifetime != Lifetime.SINGLETON && scope == null) throw failure("Service requires a scope");
            Map<Node, Object> cache = binding.lifetime == Lifetime.SINGLETON ? singletons : scope.cache;
            if (binding.lifetime != Lifetime.TRANSIENT && cache.containsKey(node)) return cache.get(node);
            Object value;
            try {
                if (binding.instance != null) {
                    value = binding.instance;
                } else if (binding.factory != null) {
                    RestrictedResolver resolver = new RestrictedResolver(this, scope, node);
                    try { value = binding.factory.create(resolver); }
                    finally { resolver.active = false; }
                } else {
                    Object[] arguments = new Object[node.dependencies.size()];
                    for (int i = 0; i < arguments.length; i++) arguments[i] = resolve(node.dependencies.get(i), scope);
                    value = binding.constructor.newInstance(arguments);
                }
            } catch (ServiceCompositionException failure) {
                throw failure;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                throw failure("Service construction failed");
            }
            if (binding.ownership == Ownership.OWNED && value instanceof AutoCloseable closeable) {
                claim(value, closeable, binding.lifetime == Lifetime.SINGLETON ? owned : scope.owned);
            }
            if (!node.key.type().isInstance(value)) throw failure("Factory returned an invalid service");
            if (binding.lifetime != Lifetime.TRANSIENT) cache.put(node, value);
            return value;
        }

        private void claim(Object value, AutoCloseable closeable, Owner owner) {
            IdentityReference expired;
            while ((expired = (IdentityReference) disposedQueue.poll()) != null) disposed.remove(expired);
            if (disposed.contains(new IdentityReference(value, null))) throw failure("Factory returned a disposed service");
            Owner existing = activeOwners.get(value);
            if (existing != null && existing != owner) throw failure("Owned service has conflicting lifetimes");
            if (existing == null) {
                activeOwners.put(value, owner);
                owner.values.add(closeable);
            }
        }

        @Override
        public synchronized void close() {
            if (closeInternal()) throw failure("Service cleanup failed");
        }

        private boolean closeInternal() {
            if (closed) return false;
            closed = true;
            boolean failed = false;
            List<Scope> openScopes = List.copyOf(scopes);
            scopes.clear();
            for (int i = openScopes.size() - 1; i >= 0; i--) failed |= openScopes.get(i).closeInternal();
            failed |= dispose(owned);
            singletons.clear();
            return failed;
        }

        private boolean dispose(Owner owner) {
            boolean failed = false;
            for (int i = owner.values.size() - 1; i >= 0; i--) {
                AutoCloseable value = owner.values.get(i);
                activeOwners.remove(value);
                disposed.add(new IdentityReference(value, disposedQueue));
                try { value.close(); }
                catch (Exception | LinkageError | AssertionError ignored) { failed = true; }
            }
            owner.values.clear();
            return failed;
        }
    }

    private static final class Scope implements ServiceScope {
        final Root root;
        final Thread thread = Thread.currentThread();
        final Map<Node, Object> cache = new IdentityHashMap<>();
        final Owner owned = new Owner();
        boolean closed;
        Scope(Root root) { this.root = root; }

        private void checkThread() {
            if (Thread.currentThread() != thread) throw failure("Service scope is thread confined");
        }

        @Override
        public <T> T get(ServiceKey<T> key) {
            synchronized (root) {
                checkThread();
                root.checkOpen();
                if (closed) throw failure("Service scope is closed");
                return key.type().cast(root.resolve(root.node(key), this));
            }
        }

        @Override
        public void close() {
            synchronized (root) {
                checkThread();
                boolean failed = closeInternal();
                root.scopes.remove(this);
                if (failed) throw failure("Service cleanup failed");
            }
        }

        private boolean closeInternal() {
            if (closed) return false;
            closed = true;
            cache.clear();
            return root.dispose(owned);
        }
    }

    private static final class RestrictedResolver implements ServiceResolver {
        final Root root;
        final Scope scope;
        final Node node;
        final Thread thread = Thread.currentThread();
        boolean active = true;
        RestrictedResolver(Root root, Scope scope, Node node) {
            this.root = root;
            this.scope = scope;
            this.node = node;
        }
        @Override
        public <T> T get(ServiceKey<T> key) {
            synchronized (root) {
                if (!active || Thread.currentThread() != thread) throw failure("Factory resolver is no longer active");
                int index = node.binding.dependencies.indexOf(key);
                if (index < 0) throw failure("Factory requested an undeclared dependency");
                return key.type().cast(root.resolve(node.dependencies.get(index), scope));
            }
        }
    }

    private static final class Owner { final List<AutoCloseable> values = new ArrayList<>(); }

    /** Weak identity tracking prevents duplicate disposal without retaining expired scope values. */
    private static final class IdentityReference extends WeakReference<Object> {
        final int hash;
        IdentityReference(Object value, ReferenceQueue<Object> queue) {
            super(value, queue);
            hash = System.identityHashCode(value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof IdentityReference reference
                && get() != null && get() == reference.get();
        }
    }
}
