package io.agenticpayments.composition.di;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ServiceCollectionTest {
    @Test
    void qualifiedConstructorBindingsAndExplicitSelectionAreValidated() throws Exception {
        var nativeKey = ServiceKey.of(String.class, "native");
        var externalKey = ServiceKey.of(String.class, "external");
        var holderKey = ServiceKey.of(SelectedConstructor.class);
        var services = ServiceCollection.create().instance(nativeKey, "native-value").instance(externalKey, "external-value");
        assertThrows(ServiceCompositionException.class,
            () -> services.add(holderKey, SelectedConstructor.class, Lifetime.SINGLETON, true));
        services.add(holderKey, SelectedConstructor.class.getConstructor(String.class), List.of(externalKey), Lifetime.SINGLETON, true);
        try (var root = services.build()) {
            assertEquals("external-value", root.get(holderKey).value);
            assertEquals("native-value", root.get(nativeKey));
            assertThrows(ServiceCompositionException.class, () -> root.get(ServiceKey.of(String.class)));
        }
        assertThrows(ServiceCompositionException.class, () -> ServiceCollection.create()
            .add(holderKey, SelectedConstructor.class.getConstructor(String.class), List.of(ServiceKey.of(Integer.class)), Lifetime.SINGLETON, true));
    }

    @Test
    void rejectsDuplicateMissingAndUnregisteredBindingsAndRequiresExplicitReplacement() {
        var key = ServiceKey.of(String.class);
        var services = ServiceCollection.create().instance(key, "first");
        assertThrows(ServiceCompositionException.class, () -> services.instance(key, "second"));
        services.replace(key).instance(key, "second");
        try (var root = services.build()) { assertEquals("second", root.get(key)); }
        assertThrows(ServiceCompositionException.class, () -> ServiceCollection.create()
            .add(ServiceKey.of(NeedsString.class), NeedsString.class, Lifetime.SCOPED, false).build());
    }

    @Test
    void validatesUnusedFactoryAndDecoratorCyclesBeforeAnyConstruction() {
        var calls = new AtomicInteger();
        var a = ServiceKey.of(String.class, "a");
        var b = ServiceKey.of(String.class, "b");
        var cyclic = ServiceCollection.create()
            .factory(a, Lifetime.TRANSIENT, false, Ownership.BORROWED, List.of(b), r -> { calls.incrementAndGet(); return r.get(b); })
            .factory(b, Lifetime.TRANSIENT, false, Ownership.BORROWED, List.of(a), r -> r.get(a));
        assertThrows(ServiceCompositionException.class, cyclic::build);
        assertEquals(0, calls.get());

        var decorated = ServiceCollection.create()
            .add(ServiceKey.of(Operation.class), BaseOperation.class, Lifetime.SCOPED, false)
            .add(ServiceKey.of(DecoratorDependency.class), DecoratorDependency.class, Lifetime.SCOPED, false)
            .decorate(ServiceKey.of(Operation.class), CyclicDecorator.class, 10);
        assertThrows(ServiceCompositionException.class, decorated::build);
    }

    @Test
    void rejectsCaptiveAndUnsafeSingletonsIncludingDecoratorDependencies() {
        var leaf = ServiceKey.of(String.class);
        var middle = ServiceKey.of(NeedsString.class);
        assertThrows(ServiceCompositionException.class, () -> ServiceCollection.create()
            .factory(leaf, Lifetime.TRANSIENT, false, Ownership.BORROWED, List.of(), r -> "value")
            .add(middle, NeedsString.class, Lifetime.SINGLETON, true).build());
        assertThrows(ServiceCompositionException.class, () -> ServiceCollection.create()
            .instance(leaf, "unsafe", Ownership.BORROWED, false).build());
        assertThrows(ServiceCompositionException.class, () -> ServiceCollection.create()
            .add(ServiceKey.of(Operation.class), BaseOperation.class, Lifetime.SINGLETON, true)
            .add(ServiceKey.of(DecoratorDependency.class), DecoratorDependency.class, Lifetime.SCOPED, false)
            .decorate(ServiceKey.of(Operation.class), CyclicDecorator.class, 10).build());
    }

    @Test
    void singletonIsFullyPublishedOnceToConcurrentReaders() throws Exception {
        var count = new AtomicInteger();
        var key = ServiceKey.of(ReadyValue.class);
        try (var root = ServiceCollection.create().factory(key, Lifetime.SINGLETON, true, Ownership.BORROWED,
            List.of(), r -> new ReadyValue(count.incrementAndGet())).build();
             var workers = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Callable<ReadyValue>>();
            for (int i = 0; i < 64; i++) jobs.add(() -> root.get(key));
            for (var result : workers.invokeAll(jobs)) {
                assertSame(root.get(key), result.get());
                assertEquals(1, result.get().ready);
            }
            assertEquals(1, count.get());
        }
    }

    @Test
    void scopesCacheLocallyRequireTheirOwnerThreadAndDoNotActivateUnregisteredClasses() throws Exception {
        var scopedKey = ServiceKey.of(Resource.class, "scoped");
        var transientKey = ServiceKey.of(Resource.class, "transient");
        var closed = new ArrayList<String>();
        try (var root = ServiceCollection.create()
            .factory(scopedKey, Lifetime.SCOPED, false, Ownership.OWNED, List.of(), r -> new Resource("scoped", closed))
            .factory(transientKey, Lifetime.TRANSIENT, false, Ownership.OWNED, List.of(), r -> new Resource("transient", closed)).build()) {
            assertThrows(ServiceCompositionException.class, () -> root.get(scopedKey));
            assertThrows(ServiceCompositionException.class, () -> root.get(transientKey));
            try (var first = root.createScope(); var second = root.createScope()) {
                assertSame(first.get(scopedKey), first.get(scopedKey));
                assertNotSame(first.get(scopedKey), second.get(scopedKey));
                assertNotSame(first.get(transientKey), first.get(transientKey));
                assertThrows(ServiceCompositionException.class, () -> first.get(ServiceKey.of(BaseOperation.class)));
                try (var worker = Executors.newSingleThreadExecutor()) {
                    assertTrue(worker.submit(() -> {
                        assertThrows(ServiceCompositionException.class, () -> first.get(scopedKey));
                        assertThrows(ServiceCompositionException.class, first::close);
                        return true;
                    }).get());
                }
            }
        }
        assertEquals(List.of("scoped", "transient", "transient", "scoped"), closed);
    }

    @Test
    void decoratorsUseAscendingOuterOrderAndInvokeExactlyOnce() {
        var key = ServiceKey.of(Operation.class);
        var collection = ServiceCollection.create()
            .add(key, BaseOperation.class, Lifetime.SCOPED, false)
            .decorate(key, InnerDecorator.class, 20)
            .decorate(key, OuterDecorator.class, 10);
        try (var root = collection.build(); var scope = root.createScope()) {
            var trace = new ArrayList<String>();
            scope.get(key).invoke(trace);
            assertEquals(List.of("outer-before", "inner-before", "base", "inner-after", "outer-after"), trace);
            assertSame(scope.get(key), scope.get(key));
        }
        assertThrows(ServiceCompositionException.class, () -> collection.decorate(key, OuterDecorator.class, 10));
        assertThrows(ServiceCompositionException.class, () -> ServiceCollection.create().decorate(key, MissingInner.class, 1));
        assertThrows(ServiceCompositionException.class, () -> ServiceCollection.create().decorate(key, AmbiguousInner.class, 1));
    }

    @Test
    void factoryCanOnlyResolveDeclaredDependenciesDuringItsOwnInvocation() {
        var escaped = new AtomicReference<ServiceResolver>();
        var text = ServiceKey.of(String.class);
        var value = ServiceKey.of(ReadyValue.class);
        try (var root = ServiceCollection.create().instance(text, "approved")
            .factory(value, Lifetime.SINGLETON, true, Ownership.BORROWED, List.of(text), r -> {
                escaped.set(r);
                assertEquals("approved", r.get(text));
                assertThrows(ServiceCompositionException.class, () -> r.get(ServiceKey.of(Integer.class)));
                return new ReadyValue(1);
            }).build()) {
            assertThrows(ServiceCompositionException.class, () -> escaped.get().get(text));
        }
    }

    @Test
    void borrowedValuesRemainOpenAndOwnedIdentitiesCloseOnceInReverseConstructionOrder() {
        var closed = new ArrayList<String>();
        var borrowed = new Resource("borrowed", closed);
        var shared = new Resource("shared", closed);
        var a = ServiceKey.of(Resource.class, "a");
        var b = ServiceKey.of(Resource.class, "b");
        var c = ServiceKey.of(Resource.class, "c");
        var root = ServiceCollection.create()
            .factory(a, Lifetime.SINGLETON, true, Ownership.OWNED, List.of(b), r -> {
                r.get(b);
                return new Resource("dependent", closed);
            })
            .factory(b, Lifetime.SINGLETON, true, Ownership.OWNED, List.of(), r -> shared)
            .factory(c, Lifetime.SINGLETON, true, Ownership.OWNED, List.of(), r -> shared)
            .instance(ServiceKey.of(Resource.class, "borrowed"), borrowed).build();
        root.close();
        root.close();
        assertEquals(List.of("dependent", "shared"), closed);
        assertThrows(ServiceCompositionException.class, () -> root.get(a));
        assertThrows(ServiceCompositionException.class, root::createScope);
    }

    @Test
    void startupFailureUnwindsAndCleanupFailureDoesNotReplaceIt() {
        var closed = new ArrayList<String>();
        var resource = ServiceKey.of(Resource.class);
        var failed = ServiceKey.of(String.class);
        var exception = assertThrows(ServiceCompositionException.class, () -> ServiceCollection.create()
            .factory(resource, Lifetime.SINGLETON, true, Ownership.OWNED, List.of(), r -> new Resource("throw", closed))
            .factory(failed, Lifetime.SINGLETON, true, Ownership.BORROWED, List.of(resource), r -> {
                r.get(resource);
                throw new IllegalStateException("private-factory-payload");
            }).build());
        assertEquals("Service construction failed", exception.getMessage());
        assertNull(exception.getCause());
        assertEquals(List.of("throw"), closed);
    }

    @Test
    void rootShutdownClosesOpenScopesBeforeSingletonsAndContinuesAfterCleanupFailure() {
        var closed = new ArrayList<String>();
        var singleton = ServiceKey.of(Resource.class, "singleton");
        var scoped = ServiceKey.of(Resource.class, "scope");
        var root = ServiceCollection.create()
            .factory(singleton, Lifetime.SINGLETON, true, Ownership.OWNED, List.of(), r -> new Resource("singleton", closed))
            .factory(scoped, Lifetime.SCOPED, false, Ownership.OWNED, List.of(), r -> new Resource("throw", closed)).build();
        var scope = root.createScope();
        scope.get(scoped);
        var failure = assertThrows(ServiceCompositionException.class, root::close);
        assertEquals("Service cleanup failed", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(List.of("throw", "singleton"), closed);
        scope.close();
        root.close();
        assertThrows(ServiceCompositionException.class, () -> scope.get(scoped));
    }

    @Test
    void collectionMutationAfterBuildDoesNotChangeProvider() {
        var key = ServiceKey.of(String.class);
        var services = ServiceCollection.create().instance(key, "old");
        try (var root = services.build()) {
            services.replace(key).instance(key, "new");
            assertEquals("old", root.get(key));
        }
    }

    @Test
    void everyDecoratorLayerIsOwnedAndClosesOutermostFirstWithoutClosingItsDependency() {
        var trace = new CloseTrace();
        var key = ServiceKey.of(OwnedOperation.class);
        try (var root = ServiceCollection.create().instance(ServiceKey.of(CloseTrace.class), trace)
            .add(key, OwnedBase.class, Lifetime.SCOPED, false)
            .decorate(key, OwnedInner.class, 20).decorate(key, OwnedOuter.class, 10).build()) {
            try (var scope = root.createScope()) { scope.get(key); }
            assertEquals(List.of("outer", "inner", "base"), trace.closed);
        }
    }

    @Test
    void rejectsReturningOwnedIdentitiesAcrossScopesOrAfterDisposal() {
        var closed = new ArrayList<String>();
        var resource = new Resource("shared", closed);
        var key = ServiceKey.of(Resource.class);
        try (var root = ServiceCollection.create().factory(key, Lifetime.SCOPED, false,
            Ownership.OWNED, List.of(), r -> resource).build()) {
            var first = root.createScope();
            first.get(key);
            try (var second = root.createScope()) {
                assertThrows(ServiceCompositionException.class, () -> second.get(key));
            }
            first.close();
            try (var third = root.createScope()) {
                assertThrows(ServiceCompositionException.class, () -> third.get(key));
            }
            assertEquals(List.of("shared"), closed);
        }
    }

    @Test
    void depthLimitIsValidatedEvenWhenEarlierSubgraphsHaveAlreadyBeenChecked() {
        var services = ServiceCollection.create();
        var previous = ServiceKey.of(String.class, "node0");
        services.instance(previous, "leaf");
        for (int i = 1; i < 132; i++) {
            var dependency = previous;
            var key = ServiceKey.of(String.class, "node" + i);
            services.factory(key, Lifetime.SINGLETON, true, Ownership.BORROWED,
                List.of(dependency), r -> r.get(dependency));
            previous = key;
        }
        assertThrows(ServiceCompositionException.class, services::build);
    }

    public static final class SelectedConstructor {
        final String value;
        public SelectedConstructor() { value = "default"; }
        public SelectedConstructor(String value) { this.value = value; }
    }
    public static final class NeedsString {
        public NeedsString(String value) { }
    }
    public static final class ReadyValue {
        final int ready;
        ReadyValue(int ready) { this.ready = ready; }
    }
    public interface Operation { void invoke(List<String> trace); }
    public static final class BaseOperation implements Operation {
        public BaseOperation() { }
        @Override public void invoke(List<String> trace) { trace.add("base"); }
    }
    public static final class OuterDecorator implements Operation {
        final Operation inner;
        public OuterDecorator(Operation inner) { this.inner = inner; }
        @Override public void invoke(List<String> trace) {
            trace.add("outer-before"); inner.invoke(trace); trace.add("outer-after");
        }
    }
    public static final class InnerDecorator implements Operation {
        final Operation inner;
        public InnerDecorator(Operation inner) { this.inner = inner; }
        @Override public void invoke(List<String> trace) {
            trace.add("inner-before"); inner.invoke(trace); trace.add("inner-after");
        }
    }
    public static final class DecoratorDependency {
        public DecoratorDependency(Operation operation) { }
    }
    public static final class CyclicDecorator implements Operation {
        public CyclicDecorator(Operation inner, DecoratorDependency dependency) { }
        @Override public void invoke(List<String> trace) { }
    }
    public static final class MissingInner implements Operation {
        public MissingInner() { }
        @Override public void invoke(List<String> trace) { }
    }
    public static final class AmbiguousInner implements Operation {
        public AmbiguousInner(Operation one, Operation two) { }
        @Override public void invoke(List<String> trace) { }
    }
    public static final class Resource implements AutoCloseable {
        final String name;
        final List<String> closed;
        Resource(String name, List<String> closed) { this.name = name; this.closed = closed; }
        @Override public void close() {
            closed.add(name);
            if (name.equals("throw")) throw new IllegalStateException("private-cleanup-payload");
        }
    }
    public static final class CloseTrace { final List<String> closed = new ArrayList<>(); }
    public interface OwnedOperation extends AutoCloseable { @Override void close(); }
    public static final class OwnedBase implements OwnedOperation {
        final CloseTrace trace;
        public OwnedBase(CloseTrace trace) { this.trace = trace; }
        @Override public void close() { trace.closed.add("base"); }
    }
    public static final class OwnedInner implements OwnedOperation {
        final CloseTrace trace;
        public OwnedInner(OwnedOperation inner, CloseTrace trace) { this.trace = trace; }
        @Override public void close() { trace.closed.add("inner"); }
    }
    public static final class OwnedOuter implements OwnedOperation {
        final CloseTrace trace;
        public OwnedOuter(OwnedOperation inner, CloseTrace trace) { this.trace = trace; }
        @Override public void close() { trace.closed.add("outer"); }
    }
}
