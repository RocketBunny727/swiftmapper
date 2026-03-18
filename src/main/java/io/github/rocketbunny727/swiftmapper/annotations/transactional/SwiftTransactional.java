package io.github.rocketbunny727.swiftmapper.annotations.transactional;

import io.github.rocketbunny727.swiftmapper.transaction.Propagation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Connection;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface SwiftTransactional {

    Propagation propagation() default Propagation.REQUIRED;

    int isolation() default Connection.TRANSACTION_READ_COMMITTED;

    boolean readOnly() default false;

    Class<? extends Throwable>[] rollbackFor() default {};

    Class<? extends Throwable>[] noRollbackFor() default {};
}