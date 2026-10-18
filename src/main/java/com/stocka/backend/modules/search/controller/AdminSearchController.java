package com.stocka.backend.modules.search.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.search.dto.SearchStatusDto;
import com.stocka.backend.modules.search.service.SemanticSearchService;

/** Global-admin maintenance of the semantic index: backfill every organization at once. */
@RestController
@RequestMapping("/admin/search")
public class AdminSearchController {

    private final SemanticSearchService service;

    public AdminSearchController(SemanticSearchService service) {
        this.service = service;
    }

    @PostMapping("/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SearchStatusDto>> reindexAll() {
        return ResponseEntity.ok(service.reindexAll());
    }
}
