package com.stocka.backend.modules.pieces.service;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.locations.service.LocationContentChecker;
import com.stocka.backend.modules.pieces.repository.PieceRepository;

/** Vetoes deletion of a location that still contains pieces. */
@Component
public class LocationContentCheckerAdapter implements LocationContentChecker {
    private final PieceRepository pieceRepository;

    public LocationContentCheckerAdapter(PieceRepository pieceRepository) {
        this.pieceRepository = pieceRepository;
    }

    @Override
    public long countContent(Location location) {
        return pieceRepository.countByLocation(location);
    }

    @Override
    public String contentLabel() {
        return "artículos";
    }
}
