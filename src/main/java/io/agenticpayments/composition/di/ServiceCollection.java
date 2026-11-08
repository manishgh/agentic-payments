package io.agenticpayments.composition.di;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * Bounded explicit registrations for service-composition-0.1; no discovery or activation.
 * Singleton registrations assert that their implementation, including decorators, is thread safe.
 * Constructors and factories must only construct: host initialization and worker start are explicit.
 */
public interface ServiceCollection {
    static ServiceCollection create() { return new ExplicitServiceCollection(); }

    <T> ServiceCollection add(ServiceKey<T> key, Class<? extends T> implementation,
                             Lifetime lifetime, boolean threadSafe);
    <T> ServiceCollection add(ServiceKey<T> key, Constructor<? extends T> constructor,
                             List<ServiceKey<?>> parameterBindings, Lifetime lifetime, boolean threadSafe);
    <T> ServiceCollection factory(ServiceKey<T> key, Lifetime lifetime, boolean threadSafe,
                                 Ownership ownership, List<ServiceKey<?>> dependencies,
                                 ServiceFactory<? extends T> factory);
    /** Registers a borrowed, caller-asserted thread-safe singleton. */
    default <T> ServiceCollection instance(ServiceKey<T> key, T instance) {
        return instance(key, instance, Ownership.BORROWED, true);
    }
    <T> ServiceCollection instance(ServiceKey<T> key, T instance, Ownership ownership, boolean threadSafe);
    /** Explicitly discards an existing binding and its decorators before registering its replacement. */
    ServiceCollection replace(ServiceKey<?> key);
    /**
     * Uses the sole public constructor and exactly one same-contract inner parameter.
     * Ascending order wraps outward: order 10 wraps order 20 wraps the base service.
     * Wrappers must not close their dependencies; the container owns each layer separately.
     */
    <T> ServiceCollection decorate(ServiceKey<T> key, Class<? extends T> implementation, int order);
    /** Validates the whole graph, initializes singletons, and unwinds owned values on startup failure. */
    ServiceProvider build();
}
