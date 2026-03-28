package io.github.rocketbunny727.swiftmapper.autoconfigure;

import io.github.rocketbunny727.swiftmapper.core.SwiftMapperContext;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;
import org.springframework.beans.factory.config.BeanDefinition;

@Configuration
@ConditionalOnBean(SwiftMapperContext.class)
@AutoConfigureAfter(SwiftMapperContextConfiguration.class)
@Import(SwiftRepositoryRegistrar.class)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class SwiftMapperAutoConfiguration {
}