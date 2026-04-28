package com.stocka.backend.modules.pieces.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.pieces.repository.PieceAttributeValueRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.service.PieceTypeUsage;

/**
 * Implementation of the {@link PieceTypeUsage} port the piece-types module exposes. Bridges
 * the type/attribute lifecycle ({@code addAttribute}, {@code update(required)},
 * {@code deleteAttribute}, {@code deleteType}) with the piece status recalc and value cleanup
 * that live in the pieces module.
 */
@Component
public class PieceTypeUsageAdapter implements PieceTypeUsage {
    private final PieceRepository pieceRepository;
    private final PieceAttributeValueRepository valueRepository;
    private final PieceService pieceService;

    public PieceTypeUsageAdapter(
            PieceRepository pieceRepository,
            PieceAttributeValueRepository valueRepository,
            @Lazy PieceService pieceService
    ) {
        this.pieceRepository = pieceRepository;
        this.valueRepository = valueRepository;
        this.pieceService = pieceService;
    }

    @Override
    public long countPiecesOfType(PieceType pieceType) {
        return pieceRepository.countByPieceType(pieceType);
    }

    @Override
    @Transactional
    public void recalcStatusForType(PieceType pieceType) {
        pieceService.recalcStatusForType(pieceType);
    }

    @Override
    @Transactional
    public void removeValuesForAttribute(PieceTypeAttribute attribute) {
        valueRepository.deleteByAttribute(attribute);
    }
}
