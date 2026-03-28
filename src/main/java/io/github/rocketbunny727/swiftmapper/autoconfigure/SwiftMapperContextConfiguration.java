package io.github.rocketbunny727.swiftmapper.autoconfigure;

import io.github.rocketbunny727.swiftmapper.annotations.entity.Entity;
import io.github.rocketbunny727.swiftmapper.core.SwiftMapperContext;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
@ConditionalOnMissingBean(SwiftMapperContext.class)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class SwiftMapperContextConfiguration {

    public static final String CONTEXT_BEAN_NAME = "swiftMapperContext";
    private static final SwiftLogger log = SwiftLogger.getLogger(SwiftMapperContextConfiguration.class);

    @Bean(name = CONTEXT_BEAN_NAME, destroyMethod = "close")
    @ConditionalOnMissingBean
    public SwiftMapperContext swiftMapperContext(BeanFactory beanFactory) throws Exception {
        Class<?>[] entityClasses = scanEntityClasses(beanFactory);

        if (entityClasses.length == 0) {
            log.warn("No @Entity classes found. Schema will not be initialised automatically.");
        } else {
            log.info("Auto-configuring SwiftMapper schema for {} @Entity class(es): {}",
                    entityClasses.length, classNames(entityClasses));
        }

        return SwiftMapperContext.fromConfig().initSchema(entityClasses);
    }

    private Class<?>[] scanEntityClasses(BeanFactory beanFactory) {
        List<String> basePackages = resolveBasePackages(beanFactory);
        if (basePackages.isEmpty()) {
            log.warn("No base packages found. Add @SpringBootApplication to your main class.");
            return new Class[0];
        }

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<Class<?>> entities = new ArrayList<>();

        for (String pkg : basePackages) {
            for (var bd : scanner.findCandidateComponents(pkg)) {
                try {
                    entities.add(Class.forName(bd.getBeanClassName(), true,
                            Thread.currentThread().getContextClassLoader()));
                } catch (ClassNotFoundException e) {
                    log.warn("Could not load @Entity class: {}", bd.getBeanClassName());
                }
            }
        }
        return entities.toArray(new Class[0]);
    }

    private List<String> resolveBasePackages(BeanFactory beanFactory) {
        try {
            if (beanFactory instanceof ListableBeanFactory lbf) {
                return AutoConfigurationPackages.get(lbf);
            }
        } catch (IllegalStateException e) {
            log.debug("AutoConfigurationPackages not available");
        }
        return List.of();
    }

    private static List<String> classNames(Class<?>[] classes) {
        return java.util.Arrays.stream(classes).map(Class::getSimpleName).toList();
    }
}