package io.github.rocketbunny727.swiftmapper.autoconfigure;

import io.github.rocketbunny727.swiftmapper.core.SwiftMapperContext;
import io.github.rocketbunny727.swiftmapper.repository.SwiftRepositoryPattern;
import org.springframework.beans.factory.FactoryBean;

public class SwiftRepositoryFactoryBean<T extends SwiftRepositoryPattern<?, ?>> implements FactoryBean<T> {

    private final Class<T> repositoryInterface;
    private final SwiftMapperContext swiftMapperContext;

    public SwiftRepositoryFactoryBean(Class<T> repositoryInterface,
                                      SwiftMapperContext swiftMapperContext) {
        this.repositoryInterface = repositoryInterface;
        this.swiftMapperContext = swiftMapperContext;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public T getObject() {
        return (T) swiftMapperContext.getRepository((Class) repositoryInterface);
    }

    @Override
    public Class<T> getObjectType() {
        return repositoryInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}