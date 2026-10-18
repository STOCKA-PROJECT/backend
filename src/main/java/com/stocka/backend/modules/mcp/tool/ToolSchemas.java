package com.stocka.backend.modules.mcp.tool;

import com.stocka.backend.modules.common.schema.Param;
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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

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

    /**
     * @return the input schema of {@code type} (a record): no extra properties allowed, optional
     *         properties nullable
     */
    public static Map<String, Object> forRecord(Class<?> type) {
        return objectSchema(type, 0, true);
    }

    /**
     * @return the output schema of {@code type}: every non-primitive property nullable and extra
     *         properties tolerated, so a result never fails the host's validation
     */
    public static Map<String, Object> forOutput(Class<?> type) {
        return objectSchema(type, 0, false);
    }

    private static Map<String, Object> objectSchema(Class<?> type, int depth, boolean strict) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (type.isRecord() && depth < MAX_DEPTH) {
            for (RecordComponent component : type.getRecordComponents()) {
                Param param = annotation(type, component);
                Map<String, Object> property = schemaFor(component.getGenericType(), depth + 1, strict);
                boolean isRequired = param != null && param.required();
                if (!component.getType().isPrimitive() && !(strict && isRequired)) {
                    property = nullable(property);
                }
                if (param != null && !param.value().isBlank()) {
                    property.put("description", param.value());
                }
                properties.put(component.getName(), property);
                if (isRequired) {
                    required.add(component.getName());
                }
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", !strict);
        return schema;
    }

    /** @return {@code schema} widened to accept {@code null} */
    static Map<String, Object> nullable(Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>(schema);
        Object type = out.get("type");
        if (type instanceof String t) {
            out.put("type", List.of(t, "null"));
        } else if (out.containsKey("oneOf")) {
            @SuppressWarnings("unchecked")
            List<Object> variants = new ArrayList<>((List<Object>) out.get("oneOf"));
            Map<String, Object> nullVariant = new LinkedHashMap<>();
            nullVariant.put("type", "null");
            variants.add(nullVariant);
            out.put("oneOf", variants);
        }
        return out;
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

    static Map<String, Object> schemaFor(Type type, int depth, boolean strict) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw instanceof Class<?> rawClass) {
                if (Collection.class.isAssignableFrom(rawClass)) {
                    schema.put("type", "array");
                    schema.put("items", schemaFor(unwrap(parameterized.getActualTypeArguments()[0]), depth, strict));
                    return schema;
                }
                if (Map.class.isAssignableFrom(rawClass)) {
                    schema.put("type", "object");
                    schema.put("additionalProperties", true);
                    return schema;
                }
                return schemaFor(rawClass, depth, strict);
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
            schema.put("items", schemaFor(clazz.getComponentType(), depth, strict));
        } else if (Collection.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {
            schema.put("type", "array");
        } else if (Map.class.isAssignableFrom(clazz) || JsonNode.class.isAssignableFrom(clazz) || clazz == Object.class) {
            schema.put("type", "object");
            schema.put("additionalProperties", true);
        } else if (clazz.isRecord()) {
            return objectSchema(clazz, depth, strict);
        } else if (clazz.isInterface() && clazz.isAnnotationPresent(JsonSubTypes.class)) {
            return polymorphicSchema(clazz, depth, strict);
        }
        return schema;
    }

    /**
     * A sealed/annotated interface becomes {@code oneOf} its subtypes, each tagged with the
     * discriminator property of {@link JsonTypeInfo} as a {@code const}.
     */
    private static Map<String, Object> polymorphicSchema(Class<?> type, int depth, boolean strict) {
        JsonTypeInfo typeInfo = type.getAnnotation(JsonTypeInfo.class);
        String property = typeInfo == null || typeInfo.property().isBlank() ? "type" : typeInfo.property();
        List<Map<String, Object>> variants = new ArrayList<>();
        for (JsonSubTypes.Type subtype : type.getAnnotation(JsonSubTypes.class).value()) {
            Map<String, Object> variant = objectSchema(subtype.value(), depth, strict);
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("type", "string");
            tag.put("const", subtype.name());
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) variant.get("properties");
            Map<String, Object> ordered = new LinkedHashMap<>();
            ordered.put(property, tag);
            ordered.putAll(properties);
            variant.put("properties", ordered);
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) variant.getOrDefault("required", new ArrayList<String>());
            List<String> allRequired = new ArrayList<>();
            allRequired.add(property);
            allRequired.addAll(required);
            variant.put("required", allRequired);
            variants.add(variant);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("oneOf", variants);
        return schema;
    }

    private static Type unwrap(Type type) {
        if (type instanceof WildcardType wildcard && wildcard.getUpperBounds().length > 0) {
            return wildcard.getUpperBounds()[0];
        }
        return type;
    }
}
