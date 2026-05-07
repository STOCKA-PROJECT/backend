package com.stocka.backend.modules.organizations.service;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;

/**
 * Hook the pieces module implements so the organizations module can:
 * <ul>
 *   <li>Drop every {@code piece_organization_attribute_values} row referencing a freshly
 *       soft-deleted attribute via {@link #removeValuesForAttribute(OrganizationPieceAttribute)}.
 *       <em>Currently a no-op</em> — soft-delete keeps values around so a future "restore" stays
 *       lossless. Kept as part of the interface to mirror
 *       {@link com.stocka.backend.modules.piecetypes.service.PieceTypeUsage} and to give us a
 *       single seam if the policy later changes.</li>
 *   <li>Trigger a status recalculation across every piece of an organization when an
 *       attribute's {@code required} flag, validators or lifecycle change
 *       ({@link #recalcStatusForOrganization(Organization)}).</li>
 * </ul>
 *
 * Wired as an {@code Optional} dependency so the organizations module can be tested in isolation.
 */
public interface OrganizationPieceAttributeUsage {

    /**
     * Recomputes {@code status} (ACTIVE/PENDING) for every non-deleted piece of {@code organization}
     * and persists changes. Records {@code STATUS_CHANGED} entries in piece history when the status
     * actually moves.
     */
    void recalcStatusForOrganization(Organization organization);

    /**
     * Drops every value row referencing {@code attribute} so the attribute itself can be removed
     * without violating FKs. Implementations may choose to keep values (soft-delete model) — in
     * that case this method should be a no-op.
     */
    void removeValuesForAttribute(OrganizationPieceAttribute attribute);
}
