package io.github.rocketbunny727.swiftmapper.autoconfigure;

import io.github.rocketbunny727.swiftmapper.annotations.repository.SwiftRepository;
import io.github.rocketbunny727.swiftmapper.repository.Repository;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.List;

public class SwiftRepositoryRegistrar
        implements ImportBeanDefinitionRegistrar, BeanFactoryAware {

    private static final SwiftLogger log =
            SwiftLogger.getLogger(SwiftRepositoryRegistrar.class);

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
                                        BeanDefinitionRegistry registry) {
        List<String> basePackages = resolveBasePackages();
        if (basePackages.isEmpty()) {
            log.warn("No base packages found for @SwiftRepository scanning. " +
                    "Ensure @SpringBootApplication is present.");
            return;
        }

        List<Class<?>> repositoryInterfaces = scanRepositoryInterfaces(basePackages);

        for (Class<?> iface : repositoryInterfaces) {
            registerRepositoryBean(registry, iface);
        }

        if (!repositoryInterfaces.isEmpty()) {
            log.info("Registered {} @SwiftRepository bean(s): {}",
                    repositoryInterfaces.size(),
                    repositoryInterfaces.stream().map(Class::getSimpleName).toList());
        }
    }

    private List<String> resolveBasePackages() {
        try {
            if (beanFactory instanceof ListableBeanFactory lbf) {
                return AutoConfigurationPackages.get(lbf);
            }
        } catch (IllegalStateException e) {
            log.debug("AutoConfigurationPackages not available: {}", e.getMessage());
        }
        return List.of();
    }

    private List<Class<?>> scanRepositoryInterfaces(List<String> basePackages) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition bd) {
                        return bd.getMetadata().isInterface();
                    }
                };

        scanner.addIncludeFilter(new AnnotationTypeFilter(SwiftRepository.class));

        List<Class<?>> found = new ArrayList<>();

        for (String pkg : basePackages) {
            for (BeanDefinition bd : scanner.findCandidateComponents(pkg)) {
                String className = bd.getBeanClassName();
                try {
                    Class<?> iface = Class.forName(className);
                    if (iface.isInterface() && Repository.class.isAssignableFrom(iface)) {
                        found.add(iface);
                        log.debug("Found @SwiftRepository: {}", className);
                    } else {
                        log.warn("@SwiftRepository on '{}' ignored — must be an interface " +
                                "extending Repository<T, ID>", className);
                    }
                } catch (ClassNotFoundException e) {
                    log.warn("Could not load @SwiftRepository class: {}", className);
                }
            }
        }

        return found;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerRepositoryBean(BeanDefinitionRegistry registry, Class<?> iface) {
        String beanName = buildBeanName(iface);

        if (registry.containsBeanDefinition(beanName)) {
            log.debug("Bean '{}' already registered, skipping", beanName);
            return;
        }

        BeanDefinition bd = BeanDefinitionBuilder
                .genericBeanDefinition(SwiftRepositoryFactoryBean.class)
                .addConstructorArgValue(iface)
                .addConstructorArgReference(SwiftMapperAutoConfiguration.CONTEXT_BEAN_NAME)
                .setLazyInit(false)
                .getBeanDefinition();

        registry.registerBeanDefinition(beanName, bd);
        log.debug("Registered repository bean '{}' -> {}", beanName, iface.getName());
    }

    private String buildBeanName(Class<?> iface) {
        String simpleName = iface.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}