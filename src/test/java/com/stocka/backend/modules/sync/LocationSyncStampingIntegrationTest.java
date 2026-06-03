package com.stocka.backend.modules.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.stocka.backend.modules.locations.dto.CreateLocationDto;
import com.stocka.backend.modules.locations.dto.UpdateLocationDto;
import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.locations.service.LocationService;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.sync.repository.OrgChangeSequenceRepository;

/**
 * Verifies that location writes stamp synchronization metadata: every create/update/soft-delete
 * advances the organization's change sequence ({@code rev}) and a stable {@code syncId} is assigned
 * (Workstream A, risks R2/D1/D2).
 */
@SpringBootTest
@DisplayName("Location sync stamping (rev + syncId)")
class LocationSyncStampingIntegrationTest {

    @Autowired
    private LocationService locationService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrgChangeSequenceRepository changeSequenceRepository;

    private Integer orgId;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(new Organization()
                .setName("Sync Org")
                .setSlug("sync-org-" + UUID.randomUUID()));
        orgId = org.getId();
    }

    @Test
    @DisplayName("should stamp a unique syncId and a strictly increasing rev on every write")
    void should_stamp_syncId_and_monotonic_rev() {
        // Arrange + Act: two creates in the same organization.
        Location first = locationService.create(orgId, new CreateLocationDto().setName("Warehouse"));
        Location second = locationService.create(orgId, new CreateLocationDto().setName("Office"));

        // Assert: stable, unique sync ids.
        assertThat(first.getSyncId()).isNotNull().hasSize(36);
        assertThat(second.getSyncId()).isNotNull().hasSize(36);
        assertThat(first.getSyncId()).isNotEqualTo(second.getSyncId());

        // Assert: rev is assigned monotonically per organization.
        assertThat(first.getRev()).isEqualTo(1L);
        assertThat(second.getRev()).isEqualTo(2L);

        // Act: an effective update bumps the rev again.
        Location renamed = locationService.update(
                orgId, first.getId(), new UpdateLocationDto().setName("Main warehouse"));
        assertThat(renamed.getRev()).isEqualTo(3L);

        // Act: a soft-delete also bumps the rev (so the tombstone is pull-visible).
        locationService.softDelete(orgId, second.getId());

        // Assert: the organization's change sequence reflects all four writes.
        assertThat(changeSequenceRepository.currentValue(orgId)).contains(4L);
    }

    @Test
    @DisplayName("should keep rev sequences independent across organizations")
    void should_keep_rev_independent_per_org() {
        Organization other = organizationRepository.save(new Organization()
                .setName("Other Org")
                .setSlug("other-org-" + UUID.randomUUID()));

        Location a = locationService.create(orgId, new CreateLocationDto().setName("A"));
        Location b = locationService.create(other.getId(), new CreateLocationDto().setName("B"));

        // Each organization starts its own sequence at 1.
        assertThat(a.getRev()).isEqualTo(1L);
        assertThat(b.getRev()).isEqualTo(1L);
    }
}
