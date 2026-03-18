package io.github.rocketbunny727.swiftmapper.annotations.validation;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Range {
    long min() default 0;
    long max() default Long.MAX_VALUE;
    String message() default "Value must be between {min} and {max}";
}
