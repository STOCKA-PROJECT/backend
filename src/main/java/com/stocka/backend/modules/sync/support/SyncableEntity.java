package com.stocka.backend.modules.sync.support;

/**
 * Marker contract for domain entities that participate in offline synchronization. Each such
 * entity carries a client-stable {@code syncId} (a UUID, the identity offline clients use to
 * correlate their local document with the server row) and a {@code rev} (the per-organization
 * change sequence value stamped on every write, used as the pull cursor).
 *
 * <p>Implementations expose the owning organization id so the stamper can advance the right
 * {@link com.stocka.backend.modules.sync.entity.OrgChangeSequence change sequence}.
 *
 * @since 0.2.0
 */
public interface SyncableEntity {

    /**
     * Returns the client-stable synchronization id (UUID) of this entity.
     *
     * @return the sync id, or {@code null} before it has been assigned
     */
    String getSyncId();

    /**
     * Assigns the client-stable synchronization id (UUID).
     *
     * @param syncId the UUID to assign
     */
    void setSyncId(String syncId);

    /**
     * Returns the last revision stamped on this entity.
     *
     * @return the revision, or {@code null} before the first stamped write
     */
    Long getRev();

    /**
     * Stamps the revision assigned by the organization change sequence.
     *
     * @param rev the revision to assign
     */
    void setRev(Long rev);

    /**
     * Returns the id of the organization that owns this entity, used to advance the correct
     * per-organization change sequence.
     *
     * @return the owning organization id
     */
    Integer getSyncOrganizationId();
}
