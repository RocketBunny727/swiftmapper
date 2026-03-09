package com.rocketbunny.swiftmapper.annotations.validation;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Pattern {
    String regexp();
    String message() default "Value does not match pattern";
}