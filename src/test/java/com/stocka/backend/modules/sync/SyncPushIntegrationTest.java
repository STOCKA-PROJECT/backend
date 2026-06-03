package com.stocka.backend.modules.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.sync.dto.LocationSyncDto;
import com.stocka.backend.modules.sync.dto.OrgAttributeSyncDto;
import com.stocka.backend.modules.sync.dto.PieceTypeSyncDto;
import com.stocka.backend.modules.sync.dto.SyncMutationRequest;
import com.stocka.backend.modules.sync.dto.SyncMutationsResponse;
import com.stocka.backend.modules.sync.dto.SyncMutationsResponse.Result;
import com.stocka.backend.modules.sync.service.SyncPushService;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * Verifies the sync push endpoint for locations: idempotency, last-write-wins with conflict
 * reporting, and sticky deletes (no resurrection, R7). Authenticates as the seeded global admin
 * (which bypasses org-role checks) to exercise the persistence path.
 */
@SpringBootTest
@DisplayName("Sync push (mutations: upsert/delete, idempotency, LWW, sticky delete)")
class SyncPushIntegrationTest {

    @Autowired
    private SyncPushService pushService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${app.admin.bootstrap.email}")
    private String adminEmail;

    private final ObjectMapper om = new ObjectMapper();

    private Integer orgId;
    private String orgSlug;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(new Organization()
                .setName("Push Org")
                .setSlug("push-org-" + UUID.randomUUID()));
        orgId = org.getId();
        orgSlug = org.getSlug();

        User admin = userRepository.findByEmail(adminEmail).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("applies create/update, dedups retries, reports LWW conflicts and sticky deletes")
    void should_apply_upsert_delete_with_idempotency_and_conflicts() {
        String syncId = UUID.randomUUID().toString();

        // 1) Create.
        Result create = pushOne(upsert("m1", syncId, null, "Warehouse"));
        assertThat(create.status()).isEqualTo(SyncMutationsResponse.STATUS_APPLIED);
        long rev1 = ((LocationSyncDto) create.serverDoc()).rev();
        assertThat(rev1).isPositive();

        // 2) Idempotent retry of the same mutation id.
        Result retry = pushOne(upsert("m1", syncId, null, "Warehouse"));
        assertThat(retry.status()).isEqualTo(SyncMutationsResponse.STATUS_DUPLICATE);

        // 3) Update on top of the current rev: applied, rev advances.
        Result update = pushOne(upsert("m2", syncId, rev1, "Main warehouse"));
        assertThat(update.status()).isEqualTo(SyncMutationsResponse.STATUS_APPLIED);
        long rev2 = ((LocationSyncDto) update.serverDoc()).rev();
        assertThat(rev2).isGreaterThan(rev1);
        assertThat(((LocationSyncDto) update.serverDoc()).name()).isEqualTo("Main warehouse");

        // 4) Stale write (baseRev behind current): LWW applies but is reported as conflict.
        Result conflict = pushOne(upsert("m3", syncId, rev1, "Stale name"));
        assertThat(conflict.status()).isEqualTo(SyncMutationsResponse.STATUS_CONFLICT);

        // 5) Delete: applied, tombstone.
        Result delete = pushOne(delete("m4", syncId));
        assertThat(delete.status()).isEqualTo(SyncMutationsResponse.STATUS_APPLIED);
        assertThat(((LocationSyncDto) delete.serverDoc()).deletedAt()).isNotNull();

        // 6) Sticky delete: an upsert over a tombstone is rejected, never resurrected (R7).
        Result resurrect = pushOne(upsert("m5", syncId, rev1, "Back from the dead"));
        assertThat(resurrect.status()).isEqualTo(SyncMutationsResponse.STATUS_REJECTED);
        assertThat(resurrect.errorCode()).isEqualTo("deleted_upstream");
    }

    @Test
    @DisplayName("rejects a child upsert whose parent sync id is unknown")
    void should_reject_when_parent_missing() {
        String syncId = UUID.randomUUID().toString();
        ObjectNode doc = om.createObjectNode();
        doc.put("name", "Room");
        doc.put("parentSyncId", UUID.randomUUID().toString());
        Result r = pushOne(new SyncMutationRequest.Item("mp", "locations", "upsert", syncId, null, doc));
        assertThat(r.status()).isEqualTo(SyncMutationsResponse.STATUS_REJECTED);
        assertThat(r.errorCode()).isEqualTo("dependency_failed");
    }

    @Test
    @DisplayName("piece type push: create, name-conflict, and delete that frees the name slot")
    void should_push_piece_types_with_name_conflict_and_slot_release() {
        String t1 = UUID.randomUUID().toString();

        Result create = pushTypeUpsert("t1", t1, null, "Bolts");
        assertThat(create.status()).isEqualTo(SyncMutationsResponse.STATUS_APPLIED);

        // A different syncId with the same active name conflicts.
        Result clash = pushTypeUpsert("t2", UUID.randomUUID().toString(), null, "Bolts");
        assertThat(clash.status()).isEqualTo(SyncMutationsResponse.STATUS_REJECTED);
        assertThat(clash.errorCode()).isEqualTo("name_conflict");

        // Deleting mangles the name to free the (org, name) slot.
        Result del = pushTypeDelete("t3", t1);
        assertThat(del.status()).isEqualTo(SyncMutationsResponse.STATUS_APPLIED);
        assertThat(((PieceTypeSyncDto) del.serverDoc()).deletedAt()).isNotNull();

        // The freed name can now be reused by a brand-new type.
        Result recreate = pushTypeUpsert("t4", UUID.randomUUID().toString(), null, "Bolts");
        assertThat(recreate.status()).isEqualTo(SyncMutationsResponse.STATUS_APPLIED);
    }

    @Test
    @DisplayName("org attribute push: create with fields, type-required, name-conflict, delete")
    void should_push_org_attributes() {
        String a1 = UUID.randomUUID().toString();
        ObjectNode doc = om.createObjectNode();
        doc.put("name", "warranty");
        doc.put("displayName", "Warranty");
        doc.put("type", "TEXT");
        doc.put("required", true);
        doc.put("position", 0);
        Result create = pushOne(new SyncMutationRequest.Item("a1", "orgAttributes", "upsert", a1, null, doc));
        assertThat(create.status()).isEqualTo(SyncMutationsResponse.STATUS_APPLIED);
        OrgAttributeSyncDto dto = (OrgAttributeSyncDto) create.serverDoc();
        assertThat(dto.name()).isEqualTo("warranty");
        assertThat(dto.type()).isEqualTo("TEXT");
        assertThat(dto.required()).isTrue();

        // Type is required on create.
        ObjectNode noType = om.createObjectNode();
        noType.put("name", "color");
        Result invalid = pushOne(new SyncMutationRequest.Item(
                "a2", "orgAttributes", "upsert", UUID.randomUUID().toString(), null, noType));
        assertThat(invalid.status()).isEqualTo(SyncMutationsResponse.STATUS_REJECTED);
        assertThat(invalid.errorCode()).isEqualTo("validation_failed");

        // Duplicate active name conflicts.
        ObjectNode dup = om.createObjectNode();
        dup.put("name", "warranty");
        dup.put("type", "TEXT");
        Result clash = pushOne(new SyncMutationRequest.Item(
                "a3", "orgAttributes", "upsert", UUID.randomUUID().toString(), null, dup));
        assertThat(clash.status()).isEqualTo(SyncMutationsResponse.STATUS_REJECTED);
        assertThat(clash.errorCode()).isEqualTo("name_conflict");

        Result del = pushOne(new SyncMutationRequest.Item("a4", "orgAttributes", "delete", a1, null, null));
        assertThat(del.status()).isEqualTo(SyncMutationsResponse.STATUS_APPLIED);
        assertThat(((OrgAttributeSyncDto) del.serverDoc()).deletedAt()).isNotNull();
    }

    private Result pushTypeUpsert(String mutationId, String syncId, Long baseRev, String name) {
        ObjectNode doc = om.createObjectNode();
        doc.put("name", name);
        return pushOne(new SyncMutationRequest.Item(mutationId, "pieceTypes", "upsert", syncId, baseRev, doc));
    }

    private Result pushTypeDelete(String mutationId, String syncId) {
        return pushOne(new SyncMutationRequest.Item(mutationId, "pieceTypes", "delete", syncId, null, null));
    }

    private Result pushOne(SyncMutationRequest.Item item) {
        SyncMutationsResponse resp = pushService.push(orgId, orgSlug, new SyncMutationRequest(List.of(item)));
        return resp.results().get(0);
    }

    private SyncMutationRequest.Item upsert(String mutationId, String syncId, Long baseRev, String name) {
        ObjectNode doc = om.createObjectNode();
        doc.put("name", name);
        return new SyncMutationRequest.Item(mutationId, "locations", "upsert", syncId, baseRev, doc);
    }

    private SyncMutationRequest.Item delete(String mutationId, String syncId) {
        return new SyncMutationRequest.Item(mutationId, "locations", "delete", syncId, null, null);
    }
}
