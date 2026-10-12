package com.stocka.backend.modules.contract;

import java.util.Map;

/**
 * One kind of hardware output as described by the catalog.
 *
 * @param kind           the enum constant
 * @param controller     Python controller class name on the Raspberry Pi
 * @param infrastructure {@code true} when ports of this kind belong to no piece type (strips, button)
 * @param pin            {@code true} when the port carries a single GPIO pin in {@code Port.pin}
 * @param config         config schema by key (everything but {@code pin})
 * @param actions        show actions by name
 */
public record CatalogKind(
        OutputKind kind,
        String controller,
        boolean infrastructure,
        boolean pin,
        Map<String, CatalogParam> config,
        Map<String, CatalogAction> actions
) {
}
