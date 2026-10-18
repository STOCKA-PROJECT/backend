package com.stocka.backend.modules.mcp.tool;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * JSON Schema for tool inputs and outputs, generated from Java records.
 *
 * <p>Deliberately small and predictable so what the model sees is exactly what the code declares:
 * record components become properties, {@link Param} gives the description and whether the property
 * is required, scalars map to the obvious JSON types, enums list their values, lists become arrays,
 * maps and {@link JsonNode} become free-form objects, nested records recurse. Unknown types fall
 * back to an unconstrained schema rather than failing.
 */
public final class ToolSchemas {

    private static final int MAX_DEPTH = 6;

    private ToolSchemas() {
    }

    /** @return the object schema of {@code type} (a record), with {@code additionalProperties: false} */
    public static Map<String, Object> forRecord(Class<?> type) {
        return objectSchema(type, 0);
    }

    private static Map<String, Object> objectSchema(Class<?> type, int depth) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (type.isRecord() && depth < MAX_DEPTH) {
            for (RecordComponent component : type.getRecordComponents()) {
                Param param = annotation(type, component);
                Map<String, Object> property = schemaFor(component.getGenericType(), depth + 1);
                if (param != null && !param.value().isBlank()) {
                    property.put("description", param.value());
                }
                properties.put(component.getName(), property);
                if (param != null && param.required()) {
                    required.add(component.getName());
                }
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Param annotation(Class<?> owner, RecordComponent component) {
        Param param = component.getAnnotation(Param.class);
        if (param != null) return param;
        param = component.getAccessor().getAnnotation(Param.class);
        if (param != null) return param;
        try {
            return owner.getDeclaredField(component.getName()).getAnnotation(Param.class);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    static Map<String, Object> schemaFor(Type type, int depth) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw instanceof Class<?> rawClass) {
                if (Collection.class.isAssignableFrom(rawClass)) {
                    schema.put("type", "array");
                    schema.put("items", schemaFor(unwrap(parameterized.getActualTypeArguments()[0]), depth));
                    return schema;
                }
                if (Map.class.isAssignableFrom(rawClass)) {
                    schema.put("type", "object");
                    schema.put("additionalProperties", true);
                    return schema;
                }
                return schemaFor(rawClass, depth);
            }
        }
        if (!(type instanceof Class<?> clazz)) {
            return schema;
        }
        if (clazz == String.class || clazz == CharSequence.class) {
            schema.put("type", "string");
        } else if (clazz == Integer.class || clazz == int.class || clazz == Long.class || clazz == long.class
                || clazz == Short.class || clazz == short.class) {
            schema.put("type", "integer");
        } else if (clazz == Double.class || clazz == double.class || clazz == Float.class || clazz == float.class
                || clazz == BigDecimal.class || Number.class.isAssignableFrom(clazz)) {
            schema.put("type", "number");
        } else if (clazz == Boolean.class || clazz == boolean.class) {
            schema.put("type", "boolean");
        } else if (clazz.isEnum()) {
            schema.put("type", "string");
            List<String> values = new ArrayList<>();
            for (Object constant : clazz.getEnumConstants()) {
                values.add(constant.toString());
            }
            schema.put("enum", values);
        } else if (clazz == Instant.class || clazz == Date.class || clazz == LocalDateTime.class) {
            schema.put("type", "string");
            schema.put("format", "date-time");
        } else if (clazz == LocalDate.class) {
            schema.put("type", "string");
            schema.put("format", "date");
        } else if (clazz.isArray()) {
            schema.put("type", "array");
            schema.put("items", schemaFor(clazz.getComponentType(), depth));
        } else if (Collection.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {
            schema.put("type", "array");
        } else if (Map.class.isAssignableFrom(clazz) || JsonNode.class.isAssignableFrom(clazz) || clazz == Object.class) {
            schema.put("type", "object");
            schema.put("additionalProperties", true);
        } else if (clazz.isRecord()) {
            return objectSchema(clazz, depth);
        }
        return schema;
    }

    private static Type unwrap(Type type) {
        if (type instanceof WildcardType wildcard && wildcard.getUpperBounds().length > 0) {
            return wildcard.getUpperBounds()[0];
        }
        return type;
    }
}
