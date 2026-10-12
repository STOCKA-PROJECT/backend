package com.stocka.backend.modules.contract;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

/**
 * Serves the output catalog so the frontend can render kind, config and action forms from the
 * same file the backend validates against. Any authenticated user may read it: it holds no
 * organization data, only the hardware vocabulary.
 */
@RestController
@RequestMapping("/contract")
public class CatalogController {
    private final OutputCatalog catalog;

    public CatalogController(OutputCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Returns the catalog exactly as shipped in {@code contract/catalog.json}.
     *
     * @return the raw catalog document
     */
    @GetMapping("/catalog")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<JsonNode> catalog() {
        return ResponseEntity.ok(catalog.raw());
    }
}
