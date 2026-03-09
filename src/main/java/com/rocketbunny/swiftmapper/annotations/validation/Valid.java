package com.rocketbunny.swiftmapper.annotations.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Valid {
    int minLength() default 0;
    int maxLength() default Integer.MAX_VALUE;
    String regex() default "";
}
