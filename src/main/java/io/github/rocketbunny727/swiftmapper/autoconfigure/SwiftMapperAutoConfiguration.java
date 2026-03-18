package io.github.rocketbunny727.swiftmapper.autoconfigure;

import io.github.rocketbunny727.swiftmapper.annotations.entity.Entity;
import io.github.rocketbunny727.swiftmapper.core.SwiftMapperContext;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.beans.factory.config.BeanDefinition;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConditionalOnMissingBean(SwiftMapperContext.class)
@AutoConfigureBefore(SwiftTransactionalAutoConfiguration.class)
@Import(SwiftRepositoryRegistrar.class)
public class SwiftMapperAutoConfiguration implements BeanFactoryAware {

    public static final String CONTEXT_BEAN_NAME = "swiftMapperContext";

    private static final SwiftLogger log =
            SwiftLogger.getLogger(SwiftMapperAutoConfiguration.class);

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Bean(name = CONTEXT_BEAN_NAME, destroyMethod = "close")
    public SwiftMapperContext swiftMapperContext() throws Exception {
        Class<?>[] entityClasses = scanEntityClasses();

        if (entityClasses.length == 0) {
            log.warn("No @Entity classes found in application packages. " +
                    "Schema will not be initialised automatically.");
        } else {
            log.info("Auto-configuring SwiftMapper schema for {} @Entity class(es): {}",
                    entityClasses.length,
                    classNames(entityClasses));
        }

        return SwiftMapperContext.fromConfig().initSchema(entityClasses);
    }

    private Class<?>[] scanEntityClasses() {
        List<String> basePackages = resolveBasePackages();
        if (basePackages.isEmpty()) {
            log.warn("No base packages found. Add @SpringBootApplication to your main class.");
            return new Class[0];
        }

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<Class<?>> entities = new ArrayList<>();

        for (String pkg : basePackages) {
            for (BeanDefinition bd : scanner.findCandidateComponents(pkg)) {
                String className = bd.getBeanClassName();
                try {
                    entities.add(Class.forName(className, true,
                            Thread.currentThread().getContextClassLoader()));
                    log.debug("Discovered @Entity: {}", className);
                } catch (ClassNotFoundException e) {
                    log.warn("Could not load @Entity class: {}", className);
                }
            }
        }

        return entities.toArray(new Class[0]);
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

    private static List<String> classNames(Class<?>[] classes) {
        List<String> names = new ArrayList<>();
        for (Class<?> c : classes) names.add(c.getSimpleName());
        return names;
    }
}