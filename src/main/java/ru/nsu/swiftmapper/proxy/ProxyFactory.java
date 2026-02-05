package ru.nsu.swiftmapper.proxy;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;
import ru.nsu.swiftmapper.logger.SwiftLogger;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

public class ProxyFactory {
    private static final SwiftLogger log = SwiftLogger.getLogger(ProxyFactory.class);

    @SuppressWarnings("unchecked")
    public static <T> T createEntityProxy(Class<T> entityClass, Runnable loader) {
        try {
            return (T) new ByteBuddy()
                    .subclass(entityClass)
                    .method(ElementMatchers.any()
                            .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class)))
                            .and(ElementMatchers.not(ElementMatchers.named("equals")))
                            .and(ElementMatchers.not(ElementMatchers.named("hashCode")))
                            .and(ElementMatchers.not(ElementMatchers.named("toString")))
                            .and(ElementMatchers.not(ElementMatchers.named("clone")))
                            .and(ElementMatchers.not(ElementMatchers.named("finalize")))
                            .and(ElementMatchers.not(ElementMatchers.named("wait")))
                            .and(ElementMatchers.not(ElementMatchers.named("notify")))
                            .and(ElementMatchers.not(ElementMatchers.named("notifyAll"))))
                    .intercept(MethodDelegation.to(new LazyInterceptor(loader)))
                    .make()
                    .load(entityClass.getClassLoader())
                    .getLoaded()
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create proxy for " + entityClass, e);
        }
    }

    public static <E> LazyList<E> createLazyList(Runnable loader) {
        return new LazyList<>(loader);
    }

    public static class LazyInterceptor {
        private final Runnable loader;
        private volatile boolean loaded = false;

        public LazyInterceptor(Runnable loader) {
            this.loader = loader;
        }

        @RuntimeType
        public Object intercept(
                @This Object self,
                @Origin Method method,
                @AllArguments Object[] args,
                @SuperCall Callable<?> superCall) throws Exception {

            if (!loaded && !isSetter(method.getName())) {
                synchronized (this) {
                    if (!loaded) {
                        log.debug("Lazy loading triggered for {}.{}",
                                method.getDeclaringClass().getSimpleName(), method.getName());
                        loader.run();
                        loaded = true;
                    }
                }
            }

            return superCall.call();
        }

        private boolean isSetter(String methodName) {
            return methodName.startsWith("set") && methodName.length() > 3;
        }
    }
}