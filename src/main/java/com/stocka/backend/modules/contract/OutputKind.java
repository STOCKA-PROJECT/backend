package com.stocka.backend.modules.contract;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Closed set of hardware output kinds shared with the Raspberry Pi player and the timeline editor.
 *
 * <p>The Java constant names are what gets persisted ({@code @Enumerated(EnumType.STRING)}), while
 * the wire format is the catalog identifier in snake_case ({@code led_strip}), which is also the key
 * used in {@code contract/catalog.json}.
 */
public enum OutputKind {
    RELAY("relay"),
    GPIO_EXPANDER("gpio_expander"),
    LED_STRIP("led_strip"),
    LED_SEGMENT("led_segment"),
    MOTOR("motor"),
    AUDIO("audio"),
    MOVING_HEAD("moving_head"),
    BUTTON("button");

    private final String catalogId;

    OutputKind(String catalogId) {
        this.catalogId = catalogId;
    }

    /**
     * Identifier used in the catalog and on the wire.
     *
     * @return the snake_case id, e.g. {@code led_segment}
     */
    @JsonValue
    public String catalogId() {
        return catalogId;
    }

    /**
     * Resolves a kind from its catalog identifier.
     *
     * @param id snake_case identifier; may be {@code null}
     * @return the matching kind, or empty when unknown or null
     */
    public static Optional<OutputKind> parse(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String trimmed = id.trim();
        for (OutputKind kind : values()) {
            if (kind.catalogId.equals(trimmed)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /**
     * Jackson factory used when deserializing JSON bodies that carry a kind.
     *
     * @param id snake_case identifier
     * @return the matching kind, or {@code null} when unknown
     */
    @JsonCreator
    public static OutputKind fromCatalogId(String id) {
        return parse(id).orElse(null);
    }
}
