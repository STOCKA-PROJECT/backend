package com.stocka.backend.modules.contract;

import java.util.List;
import java.util.Map;

/**
 * One LED animation accepted by the {@code animate} action, with its own parameters.
 *
 * @param name        animation identifier ({@code fade}, {@code fire}, ...)
 * @param params      animation-specific parameters by name
 * @param unsupported controller parameters the contract deliberately does not expose
 */
public record CatalogAnimation(String name, Map<String, CatalogParam> params, List<String> unsupported) {
}
