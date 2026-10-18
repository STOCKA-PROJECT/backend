package com.stocka.backend.modules.mcp.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes one field of a tool input or output record for the JSON Schema shown to the model.
 *
 * <p>Every input component should carry it: the description is what the model reads to fill the
 * argument in. Fields without the annotation are optional and undocumented.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface Param {

    /** Human-readable description, in English. */
    String value();

    /** Whether the argument must be present. */
    boolean required() default false;
}
