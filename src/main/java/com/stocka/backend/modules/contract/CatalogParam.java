package com.stocka.backend.modules.contract;

import java.math.BigDecimal;
import java.util.List;

/**
 * One parameter of a catalog action, or one key of a kind's config schema.
 *
 * @param name         parameter name as the controller expects it
 * @param type         value type
 * @param required     whether the show must always carry it
 * @param min          lower bound for numeric types, or {@code null}
 * @param max          upper bound for numeric types, or {@code null}
 * @param values       allowed values for {@link CatalogType#ENUM}, otherwise empty
 * @param duration     {@code true} on the single parameter that receives the clip length in seconds
 * @param asset        {@code audio} or {@code video} when the value names a media file, else {@code null}
 * @param ref          for config keys: the kind of port the value must name, else {@code null}
 * @param defaultValue raw default as text, or {@code null}
 */
public record CatalogParam(
        String name,
        CatalogType type,
        boolean required,
        BigDecimal min,
        BigDecimal max,
        List<String> values,
        boolean duration,
        String asset,
        String ref,
        String defaultValue
) {
}
