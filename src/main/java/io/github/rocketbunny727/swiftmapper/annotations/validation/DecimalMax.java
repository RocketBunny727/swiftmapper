package io.github.rocketbunny727.swiftmapper.annotations.validation;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DecimalMax {
    String value();
    boolean inclusive() default true;
    String message() default "Value must be less than {value}";
}
