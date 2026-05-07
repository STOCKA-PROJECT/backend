package com.stocka.backend.modules.pieces.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.pieces.dto.CreatePieceDto;
import com.stocka.backend.modules.pieces.dto.PieceAttachmentResponseDto;
import com.stocka.backend.modules.pieces.dto.PieceAttributeValueResponseDto;
import com.stocka.backend.modules.pieces.dto.PieceListItemDto;
import com.stocka.backend.modules.pieces.dto.PieceResponseDto;
import com.stocka.backend.modules.pieces.dto.UpdatePieceDto;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.service.PieceService;

@RestController
@RequestMapping("/organizations/{orgId}/pieces")
public class PieceController {
    private final PieceService pieceService;
    private final PieceAttachmentRepository attachmentRepository;

    public PieceController(PieceService pieceService, PieceAttachmentRepository attachmentRepository) {
        this.pieceService = pieceService;
        this.attachmentRepository = attachmentRepository;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.canWritePieces(#orgId, principal)")
    public ResponseEntity<PieceResponseDto> create(
            @PathVariable Integer orgId,
            @RequestBody CreatePieceDto dto
    ) {
        Piece piece = pieceService.create(orgId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(piece));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgId, principal)")
    public ResponseEntity<Page<PieceListItemDto>> list(
            @PathVariable Integer orgId,
            @RequestParam(required = false) Integer typeId,
            @RequestParam(required = false) Integer locationId,
            @RequestParam(required = false) Integer ownerUserId,
            @RequestParam(required = false) PieceStatus status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<Piece> page = pieceService.list(orgId, typeId, locationId, ownerUserId, status, q, pageable);
        return ResponseEntity.ok(page.map(PieceListItemDto::from));
    }

    @GetMapping("/{pieceId}")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgId, principal)")
    public ResponseEntity<PieceResponseDto> getOne(
            @PathVariable Integer orgId,
            @PathVariable Integer pieceId
    ) {
        Piece piece = pieceService.findInOrg(orgId, pieceId);
        return ResponseEntity.ok(toResponse(piece));
    }

    @PatchMapping("/{pieceId}")
    @PreAuthorize("@orgSecurity.canWritePieces(#orgId, principal)")
    public ResponseEntity<PieceResponseDto> update(
            @PathVariable Integer orgId,
            @PathVariable Integer pieceId,
            @RequestBody UpdatePieceDto dto
    ) {
        Piece piece = pieceService.update(orgId, pieceId, dto);
        return ResponseEntity.ok(toResponse(piece));
    }

    @DeleteMapping("/{pieceId}")
    @PreAuthorize("@orgSecurity.canWritePieces(#orgId, principal)")
    public ResponseEntity<Void> delete(
            @PathVariable Integer orgId,
            @PathVariable Integer pieceId
    ) {
        pieceService.softDelete(orgId, pieceId);
        return ResponseEntity.noContent().build();
    }

    private PieceResponseDto toResponse(Piece piece) {
        List<PieceAttributeValueResponseDto> values = new java.util.ArrayList<>();
        pieceService.valuesOf(piece).stream()
                .map(PieceAttributeValueResponseDto::fromType)
                .forEach(values::add);
        pieceService.orgValuesOf(piece).stream()
                .map(PieceAttributeValueResponseDto::fromOrg)
                .forEach(values::add);
        List<PieceAttachmentResponseDto> attachments = attachmentRepository.findByPiece(piece).stream()
                .map(PieceAttachmentResponseDto::from)
                .toList();
        return PieceResponseDto.from(piece, values, attachments);
    }
}
