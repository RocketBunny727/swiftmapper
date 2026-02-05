package ru.nsu.swiftmapper.annotations.relationship;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface JoinColumn {
    String name() default "";
    String referencedColumnName() default "id";
    boolean nullable() default true;
    boolean unique() default false;
}
