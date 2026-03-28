package io.github.rocketbunny727.swiftmapper.autoconfigure;

import io.github.rocketbunny727.swiftmapper.core.SwiftMapperContext;
import io.github.rocketbunny727.swiftmapper.repository.SwiftRepositoryPattern;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;

public class SwiftRepositoryFactoryBean<T extends SwiftRepositoryPattern<?, ?>>
        implements FactoryBean<T>, BeanFactoryAware {

    private final Class<T> repositoryInterface;
    private BeanFactory beanFactory;

    public SwiftRepositoryFactoryBean(Class<T> repositoryInterface) {
        this.repositoryInterface = repositoryInterface;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public T getObject() {
        // SwiftMapperContext запрашивается только в момент первого использования репозитория
        SwiftMapperContext ctx = beanFactory.getBean(SwiftMapperContext.class);
        return (T) ctx.getRepository((Class) repositoryInterface);
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