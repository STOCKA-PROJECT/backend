package com.stocka.backend.modules.pieces.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.organizations.service.OrganizationPieceAttributeUsage;

/**
 * Implementation of the {@link OrganizationPieceAttributeUsage} port the organizations module
 * exposes. Bridges the org-level attribute lifecycle with the piece status recalc and value
 * cleanup that live in the pieces module.
 *
 * <p>{@link #removeValuesForAttribute(OrganizationPieceAttribute)} is intentionally a no-op:
 * organization attributes are soft-deleted and we keep their values in the database so a future
 * restore stays lossless. The status recalc still runs to flip pieces to ACTIVE when a required
 * attribute disappears.
 */
@Component
public class OrganizationPieceAttributeUsageAdapter implements OrganizationPieceAttributeUsage {
    private final PieceService pieceService;

    public OrganizationPieceAttributeUsageAdapter(@Lazy PieceService pieceService) {
        this.pieceService = pieceService;
    }

    @Override
    @Transactional
    public void recalcStatusForOrganization(Organization organization) {
        pieceService.recalcStatusForOrganization(organization);
    }

    @Override
    public void removeValuesForAttribute(OrganizationPieceAttribute attribute) {
        // Soft-delete keeps values around; explicit cleanup is a no-op on purpose.
    }
}
