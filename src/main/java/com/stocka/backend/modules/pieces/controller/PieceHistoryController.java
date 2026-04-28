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

import com.stocka.backend.modules.pieces.dto.PieceHistoryItemDto;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceHistory;
import com.stocka.backend.modules.pieces.repository.PieceHistoryRepository;
import com.stocka.backend.modules.pieces.service.PieceService;

@RestController
@RequestMapping("/organizations/{orgId}/pieces/{pieceId}/history")
public class PieceHistoryController {
    private final PieceService pieceService;
    private final PieceHistoryRepository historyRepository;

    public PieceHistoryController(PieceService pieceService, PieceHistoryRepository historyRepository) {
        this.pieceService = pieceService;
        this.historyRepository = historyRepository;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgId, principal)")
    public ResponseEntity<Page<PieceHistoryItemDto>> list(
            @PathVariable Integer orgId,
            @PathVariable Integer pieceId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Piece piece = pieceService.findInOrg(orgId, pieceId);
        Page<PieceHistory> page = historyRepository.findByPieceOrderByCreatedAtDescIdDesc(piece, pageable);
        return ResponseEntity.ok(page.map(PieceHistoryItemDto::from));
    }
}
