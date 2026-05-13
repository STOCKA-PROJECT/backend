package com.stocka.backend.modules.notifications.preferences.entity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps a {@link Set} of {@link LifecycleAction}s to a CSV column. Sorting the
 * enum constants by their natural order before joining makes the serialized
 * form deterministic, which keeps {@code ddl-auto=update} happy and lets us
 * compare two rows trivially in tests.
 *
 * <p>A {@code null} or empty database value resolves to an empty set so the
 * application code never has to check for {@code null}.
 */
@Converter(autoApply = false)
public class LifecycleActionSetConverter implements AttributeConverter<Set<LifecycleAction>, String> {

    @Override
    public String convertToDatabaseColumn(Set<LifecycleAction> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "";
        }
        return attribute.stream()
                .sorted()
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }

    @Override
    public Set<LifecycleAction> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return EnumSet.noneOf(LifecycleAction.class);
        }
        EnumSet<LifecycleAction> result = EnumSet.noneOf(LifecycleAction.class);
        Arrays.stream(dbData.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(token -> {
                    try {
                        result.add(LifecycleAction.valueOf(token));
                    } catch (IllegalArgumentException ignore) {
                        // Silently drop unknown tokens so an enum rename never
                        // poisons reads of preexisting rows.
                    }
                });
        return result;
    }
}
