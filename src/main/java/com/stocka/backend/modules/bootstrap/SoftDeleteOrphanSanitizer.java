package com.stocka.backend.modules.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent startup task that sanitises rows orphaned by soft-deleted parents.
 *
 * <p>Many entities carry {@code @SQLRestriction("deleted_at IS NULL")}; a child
 * entity holding an EAGER {@code @ManyToOne} reference to a soft-deleted parent
 * will fail to hydrate with {@code ObjectNotFoundException}. The fix is to mark
 * the dependent rows as soft-deleted at the moment the parent is removed; this
 * runner sanitises rows that were created (or left behind) before that cascade
 * logic existed.
 *
 * <p>Every statement filters by {@code WHERE child.deleted_at IS NULL} (or by
 * primary key for hard-deletes) so re-running the task is a no-op. Counts are
 * logged at INFO so operators can confirm the result during a deploy.
 *
 * <p>Runs after the seeders ({@code @Order(10)}) using raw SQL via
 * {@link JdbcTemplate} so the {@code @SQLRestriction} filters of the JPA layer
 * do not hide the very rows we want to mark. SQL is written with portable
 * {@code WHERE EXISTS (...)} subqueries so the same statements work against
 * MariaDB (prod/dev) and H2 (integration tests) — MariaDB-only multi-table
 * UPDATE syntax is reserved for the Flyway migration that runs on real DBs.
 *
 * @since v6 — soft-delete cascade audit (Stocka)
 */
@Component
@Order(10)
public class SoftDeleteOrphanSanitizer implements ApplicationListener<ContextRefreshedEvent> {
    private static final Logger log = LoggerFactory.getLogger(SoftDeleteOrphanSanitizer.class);

    private final JdbcTemplate jdbc;

    public SoftDeleteOrphanSanitizer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        log.info("soft_delete_orphan_sanitizer_started");

        int invitationsByOrg = softDeleteWhereParentDeleted(
                "organization_invitations", "organization_id", "organizations");
        int invitationsByInviter = softDeleteWhereParentDeleted(
                "organization_invitations", "invited_by_user_id", "users");

        int pieces = softDeleteWhereParentDeleted("pieces", "organization_id", "organizations");
        int locations = softDeleteWhereParentDeleted("locations", "organization_id", "organizations");
        int pieceTypes = softDeleteWhereParentDeleted("piece_types", "organization_id", "organizations");
        int orgPieceAttributes = softDeleteWhereParentDeleted(
                "organization_piece_attributes", "organization_id", "organizations");

        int pieceTypeAttributesByOrg = jdbc.update(
                """
                UPDATE piece_type_attributes
                   SET deleted_at = CURRENT_TIMESTAMP
                 WHERE deleted_at IS NULL
                   AND EXISTS (
                       SELECT 1 FROM piece_types t
                        JOIN organizations o ON o.id = t.organization_id
                        WHERE t.id = piece_type_attributes.piece_type_id
                          AND o.deleted_at IS NOT NULL
                   )
                """);
        int pieceTypeAttributesByType = softDeleteWhereParentDeleted(
                "piece_type_attributes", "piece_type_id", "piece_types");

        int attachmentsByPiece = softDeleteWhereParentDeleted(
                "piece_attachments", "piece_id", "pieces");

        // Hard-deletes — entities without their own deleted_at column.
        int attributeValuesByPiece = deleteWhereParentDeleted(
                "piece_attribute_values", "piece_id", "pieces");
        int orgAttributeValuesByPiece = deleteWhereParentDeleted(
                "piece_organization_attribute_values", "piece_id", "pieces");
        int emailTokens = deleteWhereParentDeleted(
                "email_verification_tokens", "user_id", "users");
        int passwordResetTokens = deleteWhereParentDeleted(
                "password_reset_tokens", "user_id", "users");

        int notifPrefsByUser = softDeleteWhereParentDeleted(
                "notification_preferences", "user_id", "users");
        int notifPrefsByOrg = softDeleteWhereParentDeleted(
                "notification_preferences", "organization_id", "organizations");

        log.info(
                "soft_delete_orphan_sanitizer_finished"
                        + " invitations_by_org={} invitations_by_inviter={}"
                        + " pieces={} locations={} piece_types={} org_piece_attributes={}"
                        + " piece_type_attributes_by_org={} piece_type_attributes_by_type={}"
                        + " attachments_by_piece={} attribute_values_by_piece={}"
                        + " org_attribute_values_by_piece={}"
                        + " email_tokens={} password_reset_tokens={}"
                        + " notif_prefs_by_user={} notif_prefs_by_org={}",
                invitationsByOrg, invitationsByInviter,
                pieces, locations, pieceTypes, orgPieceAttributes,
                pieceTypeAttributesByOrg, pieceTypeAttributesByType,
                attachmentsByPiece, attributeValuesByPiece,
                orgAttributeValuesByPiece,
                emailTokens, passwordResetTokens,
                notifPrefsByUser, notifPrefsByOrg);
    }

    /**
     * Soft-deletes rows in {@code childTable} whose {@code childFk} points to a row in
     * {@code parentTable} that is already soft-deleted. Idempotent: skips rows that already
     * carry {@code deleted_at}. Table and column names are baked into the SQL so they MUST
     * NOT be derived from user input.
     *
     * @param childTable  table whose rows may be orphaned
     * @param childFk     FK column in {@code childTable} pointing to {@code parentTable.id}
     * @param parentTable parent table (must have an {@code id} column and a {@code deleted_at} column)
     * @return number of rows updated by this call
     */
    private int softDeleteWhereParentDeleted(String childTable, String childFk, String parentTable) {
        return jdbc.update(
                "UPDATE " + childTable
                        + "    SET deleted_at = CURRENT_TIMESTAMP"
                        + "  WHERE deleted_at IS NULL"
                        + "    AND EXISTS ("
                        + "        SELECT 1 FROM " + parentTable + " p"
                        + "         WHERE p.id = " + childTable + "." + childFk
                        + "           AND p.deleted_at IS NOT NULL"
                        + "    )");
    }

    /**
     * Hard-deletes rows in {@code childTable} whose {@code childFk} points to a soft-deleted
     * row in {@code parentTable}. For tables that intentionally do not have their own
     * {@code deleted_at} column (e.g. attribute value rows, single-use tokens).
     */
    private int deleteWhereParentDeleted(String childTable, String childFk, String parentTable) {
        return jdbc.update(
                "DELETE FROM " + childTable
                        + "  WHERE EXISTS ("
                        + "        SELECT 1 FROM " + parentTable + " p"
                        + "         WHERE p.id = " + childTable + "." + childFk
                        + "           AND p.deleted_at IS NOT NULL"
                        + "    )");
    }
}
