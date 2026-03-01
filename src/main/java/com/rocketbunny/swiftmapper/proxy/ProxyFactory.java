package com.rocketbunny.swiftmapper.proxy;

import com.rocketbunny.swiftmapper.exception.LazyLoadingException;
import com.rocketbunny.swiftmapper.exception.ProxyCreationException;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;
import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class ProxyFactory {
    private static final SwiftLogger log = SwiftLogger.getLogger(ProxyFactory.class);

    @SuppressWarnings("unchecked")
    public static <T> T createEntityProxy(Class<T> entityClass, Callable<Object> loader) {
        Objects.requireNonNull(entityClass, "Entity class cannot be null");
        Objects.requireNonNull(loader, "Loader cannot be null");

        try {
            return (T) new ByteBuddy()
                    .subclass(entityClass)
                    .method(ElementMatchers.any()
                            .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class)))
                            .and(ElementMatchers.not(ElementMatchers.isFinal()))
                            .and(ElementMatchers.not(ElementMatchers.isStatic()))
                            .and(ElementMatchers.not(ElementMatchers.named("equals")))
                            .and(ElementMatchers.not(ElementMatchers.named("hashCode")))
                            .and(ElementMatchers.not(ElementMatchers.named("toString")))
                            .and(ElementMatchers.not(ElementMatchers.named("clone")))
                            .and(ElementMatchers.not(ElementMatchers.named("finalize")))
                            .and(ElementMatchers.not(ElementMatchers.named("wait")))
                            .and(ElementMatchers.not(ElementMatchers.named("notify")))
                            .and(ElementMatchers.not(ElementMatchers.named("notifyAll"))))
                    .intercept(MethodDelegation.to(new LazyInterceptor(loader, entityClass)))
                    .make()
                    .load(entityClass.getClassLoader())
                    .getLoaded()
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (Exception e) {
            throw new ProxyCreationException("Failed to create proxy for " + entityClass, e);
        }
    }

    public static <E> LazyList<E> createLazyList(Runnable loader) {
        return new LazyList<>(loader);
    }

    public static class LazyInterceptor {
        private final Callable<Object> loader;
        private final Class<?> entityClass;
        private volatile State state = State.UNINITIALIZED;
        private volatile Object loadedEntity;

        private static final AtomicReferenceFieldUpdater<LazyInterceptor, State> STATE_UPDATER =
                AtomicReferenceFieldUpdater.newUpdater(LazyInterceptor.class, State.class, "state");

        private enum State {
            UNINITIALIZED, LOADING, LOADED, FAILED
        }

        public LazyInterceptor(Callable<Object> loader, Class<?> entityClass) {
            this.loader = loader;
            this.entityClass = entityClass;
        }

        @RuntimeType
        public Object intercept(
                @This Object self,
                @Origin Method method,
                @AllArguments Object[] args,
                @SuperCall Callable<?> superCall) throws Exception {

            String methodName = method.getName();

            if (state == State.LOADED) {
                return invokeLoaded(method, args, superCall, self);
            }

            if (state == State.FAILED) {
                throw new LazyLoadingException("Entity failed to load previously, cannot invoke " + methodName);
            }

            if (!isLazyLoadingTrigger(method)) {
                return superCall.call();
            }

            if (state == State.UNINITIALIZED) {
                if (STATE_UPDATER.compareAndSet(this, State.UNINITIALIZED, State.LOADING)) {
                    try {
                        loadedEntity = loader.call();
                        state = State.LOADED;
                        log.debug("Lazy loading completed for {}.{}", entityClass.getSimpleName(), methodName);
                    } catch (Exception e) {
                        state = State.FAILED;
                        log.error("Lazy loading failed for {}.{}", e, entityClass.getSimpleName(), methodName);
                        throw new LazyLoadingException("Failed to load lazy entity " + entityClass.getSimpleName(), e);
                    }
                } else {
                    while (state == State.LOADING) {
                        Thread.yield();
                    }
                    if (state == State.FAILED) {
                        throw new LazyLoadingException("Concurrent loading failed for " + entityClass.getSimpleName());
                    }
                }
            }

            return invokeLoaded(method, args, superCall, self);
        }

        private Object invokeLoaded(Method method, Object[] args, Callable<?> superCall, Object self) throws Exception {
            if (loadedEntity == null) {
                return superCall.call();
            }

            try {
                Method loadedMethod = findCompatibleMethod(loadedEntity.getClass(), method);
                loadedMethod.setAccessible(true);
                return loadedMethod.invoke(loadedEntity, args);
            } catch (NoSuchMethodException e) {
                copyFieldsToProxy(self);
                return superCall.call();
            }
        }

        private Method findCompatibleMethod(Class<?> clazz, Method targetMethod) throws NoSuchMethodException {
            try {
                return clazz.getMethod(targetMethod.getName(), targetMethod.getParameterTypes());
            } catch (NoSuchMethodException e) {
                return clazz.getDeclaredMethod(targetMethod.getName(), targetMethod.getParameterTypes());
            }
        }

        private void copyFieldsToProxy(Object proxy) {
            if (loadedEntity == null) return;

            try {
                Class<?> sourceClass = loadedEntity.getClass();
                Class<?> targetClass = proxy.getClass();

                while (sourceClass != null && sourceClass != Object.class) {
                    for (Field sourceField : sourceClass.getDeclaredFields()) {
                        sourceField.setAccessible(true);
                        Object value = sourceField.get(loadedEntity);

                        try {
                            Field targetField = findFieldInHierarchy(targetClass, sourceField.getName());
                            if (targetField != null) {
                                targetField.setAccessible(true);
                                targetField.set(proxy, value);
                            }
                        } catch (NoSuchFieldException e) {
                            log.debug("Field {} not found in proxy, skipping", sourceField.getName());
                        }
                    }
                    sourceClass = sourceClass.getSuperclass();
                }
            } catch (Exception e) {
                log.error("Failed to copy fields to proxy", e);
            }
        }

        private Field findFieldInHierarchy(Class<?> clazz, String fieldName) throws NoSuchFieldException {
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                try {
                    return current.getDeclaredField(fieldName);
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
            throw new NoSuchFieldException(fieldName);
        }

        private boolean isLazyLoadingTrigger(Method method) {
            String name = method.getName();
            return (name.startsWith("get") && name.length() > 3) ||
                    (name.startsWith("is") && name.length() > 2) ||
                    (name.startsWith("set") && name.length() > 3);
        }
    }
}