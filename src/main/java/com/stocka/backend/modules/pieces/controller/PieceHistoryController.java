package com.stocka.backend.modules.pieces.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.pieces.dto.PieceHistoryItemDto;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceHistory;
import com.stocka.backend.modules.pieces.repository.PieceHistoryRepository;
import com.stocka.backend.modules.pieces.service.PieceService;

@RestController
@RequestMapping("/organizations/{orgSlug}/pieces/{pieceId}/history")
public class PieceHistoryController {
    private final PieceService pieceService;
    private final PieceHistoryRepository historyRepository;
    private final OrganizationResolver orgResolver;

    public PieceHistoryController(
            PieceService pieceService,
            PieceHistoryRepository historyRepository,
            OrganizationResolver orgResolver
    ) {
        this.pieceService = pieceService;
        this.historyRepository = historyRepository;
        this.orgResolver = orgResolver;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<Page<PieceHistoryItemDto>> list(
            @PathVariable String orgSlug,
            @PathVariable Integer pieceId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Piece piece = pieceService.findInOrg(orgId, pieceId);
        Page<PieceHistory> page = historyRepository.findByPieceOrderByCreatedAtDescIdDesc(piece, pageable);
        return ResponseEntity.ok(page.map(PieceHistoryItemDto::from));
    }
}
