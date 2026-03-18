package io.github.rocketbunny727.swiftmapper.annotations.validation;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PastOrPresent {
    String message() default "Date must be in the past or present";
}
