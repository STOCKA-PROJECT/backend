package com.stocka.backend.modules.locations.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.locations.entity.Location;

/**
 * Stateless helper that validates a proposed parent change does not introduce a cycle in the
 * location tree (a location cannot become a descendant of itself).
 */
@Component
public class LocationCycleValidator {

    /**
     * Walks ancestors of {@code candidateParent} and throws 400 if any of them — or
     * {@code candidateParent} itself — equals {@code subject}.
     *
     * @param subject         location whose parent is being changed
     * @param candidateParent the proposed new parent (may be {@code null} for root)
     * @throws ResponseStatusException 400 if the change would create a cycle
     */
    public void ensureNoCycle(Location subject, Location candidateParent) {
        if (subject == null || candidateParent == null || subject.getId() == null) {
            return;
        }
        Location cursor = candidateParent;
        while (cursor != null) {
            if (subject.getId().equals(cursor.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No se puede mover la ubicación a sí misma o a uno de sus descendientes");
            }
            cursor = cursor.getParent();
        }
    }
}
