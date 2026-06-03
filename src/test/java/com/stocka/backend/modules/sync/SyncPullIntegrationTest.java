package com.stocka.backend.modules.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.stocka.backend.modules.locations.dto.CreateLocationDto;
import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.locations.service.LocationService;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.sync.dto.LocationSyncDto;
import com.stocka.backend.modules.sync.dto.SyncChangesResponse;
import com.stocka.backend.modules.sync.service.SyncService;

/**
 * Verifies the sync pull feed: it returns locations changed since the checkpoint ordered by
 * {@code rev}, <strong>including tombstones</strong> (the critical R1 behavior), exposes parents by
 * {@code syncId}, and advances the checkpoint so a follow-up pull returns nothing.
 */
@SpringBootTest
@DisplayName("Sync pull feed (changes + tombstones)")
class SyncPullIntegrationTest {

    @Autowired
    private LocationService locationService;

    @Autowired
    private SyncService syncService;

    @Autowired
    private OrganizationRepository organizationRepository;

    private Integer orgId;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(new Organization()
                .setName("Pull Org")
                .setSlug("pull-org-" + UUID.randomUUID()));
        orgId = org.getId();
    }

    @Test
    @DisplayName("returns changed locations ordered by rev, includes tombstones, advances checkpoint")
    void should_return_changes_including_tombstones() {
        Location warehouse = locationService.create(orgId, new CreateLocationDto().setName("Warehouse"));
        Location office = locationService.create(orgId, new CreateLocationDto().setName("Office"));

        // Soft-delete one location: it must still appear in the pull as a tombstone.
        locationService.softDelete(orgId, office.getId());

        SyncChangesResponse first = syncService.pull(orgId, Map.of(), SyncService.DEFAULT_LIMIT);

        assertThat(first.changes().locations()).hasSize(2);
        // Ordered by rev ascending: warehouse (rev 1), then the office tombstone (rev 3).
        LocationSyncDto a = first.changes().locations().get(0);
        LocationSyncDto b = first.changes().locations().get(1);
        assertThat(a.syncId()).isEqualTo(warehouse.getSyncId());
        assertThat(a.deletedAt()).isNull();
        assertThat(b.syncId()).isEqualTo(office.getSyncId());
        assertThat(b.deletedAt()).isNotNull();
        assertThat(a.rev()).isLessThan(b.rev());

        assertThat(first.checkpoint()).containsEntry("locations", b.rev());
        assertThat(first.hasMore()).isFalse();
        assertThat(first.minClientVersion()).isEqualTo(SyncService.MIN_CLIENT_VERSION);

        // A follow-up pull from the advanced checkpoint returns nothing new.
        SyncChangesResponse second = syncService.pull(
                orgId, first.checkpoint(), SyncService.DEFAULT_LIMIT);
        assertThat(second.changes().locations()).isEmpty();
        assertThat(second.checkpoint()).containsEntry("locations", b.rev());
    }

    @Test
    @DisplayName("exposes the parent location by syncId, not numeric id")
    void should_expose_parent_by_syncId() {
        Location parent = locationService.create(orgId, new CreateLocationDto().setName("Building"));
        Location child = locationService.create(orgId,
                new CreateLocationDto().setName("Room").setParentId(parent.getId()));

        SyncChangesResponse pull = syncService.pull(orgId, Map.of(), SyncService.DEFAULT_LIMIT);

        LocationSyncDto childDto = pull.changes().locations().stream()
                .filter(l -> l.syncId().equals(child.getSyncId()))
                .findFirst()
                .orElseThrow();
        assertThat(childDto.parentSyncId()).isEqualTo(parent.getSyncId());
    }

    @Test
    @DisplayName("parses the since cursor parameter into a per-collection checkpoint")
    void should_parse_checkpoint() {
        assertThat(SyncService.parseCheckpoint("locations:7,pieces:42"))
                .containsEntry("locations", 7L)
                .containsEntry("pieces", 42L);
        assertThat(SyncService.parseCheckpoint(null)).isEmpty();
        assertThat(SyncService.parseCheckpoint("garbage")).isEmpty();
    }
}
