package io.github.rocketbunny727.swiftmapper.annotations.validation;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Digits {
    int integer();
    int fraction() default 0;
    String message() default "Numeric value out of bounds (integer digits: {integer}, fraction digits: {fraction})";
}
