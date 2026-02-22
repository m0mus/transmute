package io.transmute.skill.annotation;

import java.lang.annotation.*;

/**
 * Container annotation for repeatable {@link Trigger} annotations.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Triggers {
    Trigger[] value();
}
