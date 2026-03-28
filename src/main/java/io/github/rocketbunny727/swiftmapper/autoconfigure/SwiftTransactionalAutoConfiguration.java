package io.github.rocketbunny727.swiftmapper.autoconfigure;

import io.github.rocketbunny727.swiftmapper.core.SwiftMapperContext;
import io.github.rocketbunny727.swiftmapper.transaction.SwiftTransactionalAspect;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
@ConditionalOnBean(SwiftMapperContext.class)
@AutoConfigureAfter(SwiftMapperContextConfiguration.class)
@EnableAspectJAutoProxy
public class SwiftTransactionalAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SwiftTransactionalAspect swiftTransactionalAspect(SwiftMapperContext ctx) {
        return new SwiftTransactionalAspect(ctx.getConnectionManager());
    }
}