package com.rocketbunny.swiftmapper.proxy;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;
import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProxyFactory {
    private static final SwiftLogger log = SwiftLogger.getLogger(ProxyFactory.class);

    @SuppressWarnings("unchecked")
    public static <T> T createEntityProxy(Class<T> entityClass, Callable<Object> loader) {
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
        private final Callable<Object> loader;
        private final AtomicBoolean loaded = new AtomicBoolean(false);
        private final Object lock = new Object();

        public LazyInterceptor(Callable<Object> loader) {
            this.loader = loader;
        }

        @RuntimeType
        public Object intercept(
                @This Object self,
                @Origin Method method,
                @AllArguments Object[] args,
                @SuperCall Callable<?> superCall) throws Exception {

            String methodName = method.getName();

            if (!loaded.get() && isGetter(methodName)) {
                synchronized (lock) {
                    if (!loaded.get()) {
                        log.debug("Lazy loading triggered for {}.{}",
                                method.getDeclaringClass().getSimpleName(), methodName);
                        try {
                            Object loadedEntity = loader.call();
                            if (loadedEntity != null) {
                                Class<?> clazz = loadedEntity.getClass();
                                while (clazz != null && clazz != Object.class) {
                                    for (Field field : clazz.getDeclaredFields()) {
                                        field.setAccessible(true);
                                        field.set(self, field.get(loadedEntity));
                                    }
                                    clazz = clazz.getSuperclass();
                                }
                            }
                        } catch (Exception e) {
                            log.error("Failed to load lazy entity", e);
                        }
                        loaded.set(true);
                    }
                }
            }

            return superCall.call();
        }

        private boolean isGetter(String methodName) {
            return (methodName.startsWith("get") && methodName.length() > 3) ||
                    (methodName.startsWith("is") && methodName.length() > 2);
        }
    }
}