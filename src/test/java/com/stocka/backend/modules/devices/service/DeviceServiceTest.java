package com.stocka.backend.modules.devices.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.devices.config.DevicesProperties;
import com.stocka.backend.modules.devices.dto.DeviceAlertDto;
import com.stocka.backend.modules.devices.dto.DeviceResponseDto;
import com.stocka.backend.modules.devices.dto.HeartbeatDto;
import com.stocka.backend.modules.devices.entity.Device;
import com.stocka.backend.modules.devices.repository.DeviceRepository;
import com.stocka.backend.modules.devices.service.DeviceService.DeviceWithToken;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.users.entity.User;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceService")
class DeviceServiceTest {

    private static final Integer ORG_ID = 1;
    private static final Integer OTHER_ORG_ID = 2;
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock private OrganizationService organizationService;
    @Mock private DeviceRepository deviceRepository;

    private final DeviceTokenService tokenService = new DeviceTokenService();
    private final DeviceOverridesJsonCodec overridesCodec = new DeviceOverridesJsonCodec();
    private final DevicesProperties properties = new DevicesProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DeviceService sut;
    private Organization org;
    private Organization otherOrg;

    @BeforeEach
    void setUp() {
        sut = new DeviceService(organizationService, deviceRepository, tokenService, overridesCodec, properties, clock);
        org = new Organization().setId(ORG_ID);
        otherOrg = new Organization().setId(OTHER_ORG_ID);
        lenient().when(organizationService.findById(ORG_ID)).thenReturn(org);
        lenient().when(organizationService.findById(OTHER_ORG_ID)).thenReturn(otherOrg);
        lenient().when(deviceRepository.saveAndFlush(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(deviceRepository.findByOrganizationAndName(eq(org), anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ fixtures

    private Device device(int id, String name) {
        return new Device().setId(id).setName(name).setOrganization(org)
                .setTokenHash(tokenService.hash("bht_" + id)).setTokenPrefix("bht_" + id);
    }

    private static HeartbeatDto heartbeat(String deviceId, String state) {
        return new HeartbeatDto(deviceId, "belen", state, "sha256:abc", "Nadal 2026", 42, List.of("Angel", "Riu"),
                "1.2.3", 3600L, "ap", "belen", "192.168.4.1", null,
                List.of(new DeviceAlertDto("motor_timeout", "Angel", "L'Àngel no ha arribat a dalt", "2026-12-24T18:00:00Z")));
    }

    private static void assertApi(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(status);
                    assertThat(ex.getCode()).isEqualTo(code);
                });
    }

    private static void authenticateAs(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("should trim the name, default the board and issue a bht_ token whose hash and prefix are stored")
        void create_should_trimNameAndIssueToken() {
            DeviceWithToken out = sut.create(ORG_ID, "  Belén Martorell  ");

            Device d = out.device();
            assertThat(d.getName()).isEqualTo("Belén Martorell");
            assertThat(d.getBoard()).isEqualTo("rpi4b");
            assertThat(d.getOrganization()).isSameAs(org);
            assertThat(out.token()).startsWith("bht_");
            assertThat(d.getTokenHash()).isEqualTo(tokenService.hash(out.token())).isNotEqualTo(out.token());
            assertThat(d.getTokenPrefix()).isEqualTo(out.token().substring(0, 8));
            assertThat(d.getDeviceId()).isNull();
            assertThat(d.getLastSeenAt()).isNull();
            verify(deviceRepository).saveAndFlush(d);
        }

        @Test
        @DisplayName("should leave createdBy null when nobody is authenticated")
        void create_should_leaveCreatorNullWithoutAuthentication() {
            assertThat(sut.create(ORG_ID, "Belén").device().getCreatedBy()).isNull();
        }

        @Test
        @DisplayName("should record the authenticated user as creator")
        void create_should_recordCreator() {
            authenticateAs(new User().setId(7));
            assertThat(sut.create(ORG_ID, "Belén").device().getCreatedBy()).isEqualTo(7);
        }

        @Test
        @DisplayName("should ignore a principal that is not a User")
        void create_should_ignoreForeignPrincipal() {
            authenticateAs("anonymousUser");
            assertThat(sut.create(ORG_ID, "Belén").device().getCreatedBy()).isNull();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("should reject a blank name with devices.name_required")
        void create_should_rejectBlankName(String name) {
            assertApi(() -> sut.create(ORG_ID, name), HttpStatus.BAD_REQUEST, ErrorCodes.DEVICES_NAME_REQUIRED);
            verify(deviceRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("should reject a name longer than 80 characters after trimming")
        void create_should_rejectLongName() {
            String tooLong = "x".repeat(81);
            assertThatThrownBy(() -> sut.create(ORG_ID, tooLong))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.DEVICES_NAME_TOO_LONG);
                        assertThat(ex.getParams()).containsEntry("max", 80);
                    });
        }

        @Test
        @DisplayName("should accept exactly 80 characters once surrounding spaces are trimmed")
        void create_should_acceptBoundaryLength() {
            String exact = "y".repeat(80);
            assertThat(sut.create(ORG_ID, "  " + exact + "  ").device().getName()).isEqualTo(exact);
        }

        @Test
        @DisplayName("should reject a name already used in the organization with devices.name_taken")
        void create_should_rejectDuplicateName() {
            when(deviceRepository.findByOrganizationAndName(org, "Belén")).thenReturn(Optional.of(device(9, "Belén")));

            assertApi(() -> sut.create(ORG_ID, "Belén "), HttpStatus.CONFLICT, ErrorCodes.DEVICES_NAME_TAKEN);
            verify(deviceRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("should map a UNIQUE violation raced past the pre-check to devices.name_taken")
        void create_should_mapIntegrityViolationToConflict() {
            when(deviceRepository.saveAndFlush(any(Device.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_device_org_name"));

            assertThatThrownBy(() -> sut.create(ORG_ID, "Belén"))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.DEVICES_NAME_TAKEN);
                        assertThat(ex.getCause()).isInstanceOf(DataIntegrityViolationException.class);
                    });
        }

        @Test
        @DisplayName("should issue a different token to each device")
        void create_should_issueDistinctTokens() {
            DeviceWithToken a = sut.create(ORG_ID, "A");
            DeviceWithToken b = sut.create(ORG_ID, "B");
            assertThat(a.token()).isNotEqualTo(b.token());
            assertThat(a.device().getTokenHash()).isNotEqualTo(b.device().getTokenHash());
        }

        @Test
        @DisplayName("should propagate the organization lookup failure")
        void create_should_propagateUnknownOrganization() {
            when(organizationService.findById(99)).thenThrow(new IllegalStateException("boom"));
            assertThatThrownBy(() -> sut.create(99, "Belén")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("rename")
    class Rename {

        @Test
        @DisplayName("should trim and apply the new name")
        void rename_should_applyTrimmedName() {
            Device d = device(3, "Vell");
            when(deviceRepository.findById(3)).thenReturn(Optional.of(d));

            Device out = sut.rename(ORG_ID, 3, "  Nou  ");

            assertThat(out.getName()).isEqualTo("Nou");
            verify(deviceRepository).saveAndFlush(d);
        }

        @Test
        @DisplayName("should not consult the uniqueness index when the name is unchanged")
        void rename_should_skipUniquenessCheckWhenUnchanged() {
            Device d = device(3, "Igual");
            when(deviceRepository.findById(3)).thenReturn(Optional.of(d));

            sut.rename(ORG_ID, 3, " Igual ");

            verify(deviceRepository, never()).findByOrganizationAndName(any(), anyString());
        }

        @Test
        @DisplayName("should not conflict with itself when the index returns the same row")
        void rename_should_excludeSelf() {
            Device d = device(3, "belen");
            when(deviceRepository.findById(3)).thenReturn(Optional.of(d));
            when(deviceRepository.findByOrganizationAndName(org, "Belen")).thenReturn(Optional.of(d));

            assertThat(sut.rename(ORG_ID, 3, "Belen").getName()).isEqualTo("Belen");
        }

        @Test
        @DisplayName("should reject the name of another device with devices.name_taken")
        void rename_should_rejectTakenName() {
            Device d = device(3, "A");
            when(deviceRepository.findById(3)).thenReturn(Optional.of(d));
            when(deviceRepository.findByOrganizationAndName(org, "B")).thenReturn(Optional.of(device(4, "B")));

            assertApi(() -> sut.rename(ORG_ID, 3, "B"), HttpStatus.CONFLICT, ErrorCodes.DEVICES_NAME_TAKEN);
            assertThat(d.getName()).isEqualTo("A");
        }

        @Test
        @DisplayName("should reject a blank name")
        void rename_should_rejectBlank() {
            when(deviceRepository.findById(3)).thenReturn(Optional.of(device(3, "A")));
            assertApi(() -> sut.rename(ORG_ID, 3, " "), HttpStatus.BAD_REQUEST, ErrorCodes.DEVICES_NAME_REQUIRED);
        }

        @Test
        @DisplayName("should reject a too long name")
        void rename_should_rejectTooLong() {
            when(deviceRepository.findById(3)).thenReturn(Optional.of(device(3, "A")));
            assertApi(() -> sut.rename(ORG_ID, 3, "z".repeat(81)), HttpStatus.BAD_REQUEST, ErrorCodes.DEVICES_NAME_TOO_LONG);
        }

        @Test
        @DisplayName("should 404 when the device does not exist")
        void rename_should_404WhenMissing() {
            when(deviceRepository.findById(3)).thenReturn(Optional.empty());
            assertApi(() -> sut.rename(ORG_ID, 3, "B"), HttpStatus.NOT_FOUND, ErrorCodes.DEVICES_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("findInOrg")
    class FindInOrg {

        @Test
        @DisplayName("should return the device of the organization")
        void findInOrg_should_returnDevice() {
            Device d = device(3, "A");
            when(deviceRepository.findById(3)).thenReturn(Optional.of(d));
            assertThat(sut.findInOrg(ORG_ID, 3)).isSameAs(d);
        }

        @Test
        @DisplayName("should 404 with devices.not_found when the id is unknown")
        void findInOrg_should_404WhenUnknown() {
            when(deviceRepository.findById(3)).thenReturn(Optional.empty());
            assertApi(() -> sut.findInOrg(ORG_ID, 3), HttpStatus.NOT_FOUND, ErrorCodes.DEVICES_NOT_FOUND);
        }

        @Test
        @DisplayName("should 404 when the device belongs to another organization")
        void findInOrg_should_404WhenOtherOrg() {
            when(deviceRepository.findById(3)).thenReturn(Optional.of(device(3, "A")));
            assertApi(() -> sut.findInOrg(OTHER_ORG_ID, 3), HttpStatus.NOT_FOUND, ErrorCodes.DEVICES_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("listAll")
    class ListAll {

        @Test
        @DisplayName("should return the repository order for the organization")
        void listAll_should_delegate() {
            List<Device> devices = List.of(device(1, "A"), device(2, "B"));
            when(deviceRepository.findByOrganizationOrderByNameAscIdAsc(org)).thenReturn(devices);
            assertThat(sut.listAll(ORG_ID)).isEqualTo(devices);
        }
    }

    @Nested
    @DisplayName("isOnline")
    class IsOnline {

        @Test
        @DisplayName("should be false when the device never reported")
        void isOnline_should_beFalseWithoutHeartbeat() {
            assertThat(sut.isOnline(device(1, "A"))).isFalse();
        }

        @Test
        @DisplayName("should be true when the last heartbeat is now")
        void isOnline_should_beTrueNow() {
            assertThat(sut.isOnline(device(1, "A").setLastSeenAt(NOW_LOCAL))).isTrue();
        }

        @Test
        @DisplayName("should be true exactly at the window boundary (inclusive)")
        void isOnline_should_beTrueAtBoundary() {
            assertThat(sut.isOnline(device(1, "A").setLastSeenAt(NOW_LOCAL.minusMinutes(5)))).isTrue();
        }

        @Test
        @DisplayName("should be false one second past the window")
        void isOnline_should_beFalsePastBoundary() {
            assertThat(sut.isOnline(device(1, "A").setLastSeenAt(NOW_LOCAL.minusMinutes(5).minusSeconds(1)))).isFalse();
        }

        @Test
        @DisplayName("should honour a configured window")
        void isOnline_should_honourConfiguredWindow() {
            properties.setOnlineWindow(Duration.ofMinutes(1));
            assertThat(sut.isOnline(device(1, "A").setLastSeenAt(NOW_LOCAL.minusSeconds(59)))).isTrue();
            assertThat(sut.isOnline(device(1, "A").setLastSeenAt(NOW_LOCAL.minusSeconds(61)))).isFalse();
        }

        @Test
        @DisplayName("should tolerate a heartbeat stamped slightly in the future")
        void isOnline_should_acceptFutureStamp() {
            assertThat(sut.isOnline(device(1, "A").setLastSeenAt(NOW_LOCAL.plusSeconds(30)))).isTrue();
        }
    }

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {

        @Test
        @DisplayName("should stamp deletedAt from the clock and mangle the name with ::deleted::<id>")
        void softDelete_should_mangleNameAndStamp() {
            Device d = device(42, "Belén");
            when(deviceRepository.findById(42)).thenReturn(Optional.of(d));

            sut.softDelete(ORG_ID, 42);

            assertThat(d.getName()).isEqualTo("Belén::deleted::42");
            assertThat(d.getDeletedAt()).isEqualTo(NOW_LOCAL);
            verify(deviceRepository).save(d);
        }

        @Test
        @DisplayName("should truncate the base name so the mangled value still fits 80 characters")
        void softDelete_should_truncateLongName() {
            Device d = device(1234, "n".repeat(80));
            when(deviceRepository.findById(1234)).thenReturn(Optional.of(d));

            sut.softDelete(ORG_ID, 1234);

            assertThat(d.getName()).hasSize(80).endsWith("::deleted::1234").startsWith("nnn");
        }

        @Test
        @DisplayName("buildSoftDeletedName should use ? when the id is null")
        void buildSoftDeletedName_should_handleNullId() {
            assertThat(DeviceService.buildSoftDeletedName("A", null)).isEqualTo("A::deleted::?");
            assertThat(DeviceService.buildSoftDeletedName(null, 3)).isEqualTo("::deleted::3");
        }

        @Test
        @DisplayName("should 404 when the device is missing or in another organization")
        void softDelete_should_404() {
            when(deviceRepository.findById(5)).thenReturn(Optional.empty());
            assertApi(() -> sut.softDelete(ORG_ID, 5), HttpStatus.NOT_FOUND, ErrorCodes.DEVICES_NOT_FOUND);
            when(deviceRepository.findById(6)).thenReturn(Optional.of(device(6, "A")));
            assertApi(() -> sut.softDelete(OTHER_ORG_ID, 6), HttpStatus.NOT_FOUND, ErrorCodes.DEVICES_NOT_FOUND);
            verify(deviceRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("rotateToken")
    class RotateToken {

        @Test
        @DisplayName("should replace hash and prefix with those of a fresh token and return it once")
        void rotateToken_should_replaceCredential() {
            Device d = device(3, "A");
            String oldHash = d.getTokenHash();
            when(deviceRepository.findById(3)).thenReturn(Optional.of(d));

            DeviceWithToken out = sut.rotateToken(ORG_ID, 3);

            assertThat(out.device()).isSameAs(d);
            assertThat(out.token()).startsWith("bht_");
            assertThat(d.getTokenHash()).isEqualTo(tokenService.hash(out.token())).isNotEqualTo(oldHash);
            assertThat(d.getTokenPrefix()).isEqualTo(out.token().substring(0, 8));
            verify(deviceRepository).saveAndFlush(d);
        }

        @Test
        @DisplayName("should not touch the telemetry or the name")
        void rotateToken_should_keepOtherFields() {
            Device d = device(3, "A").setDeviceId("uuid-1").setLastState("IDLE").setLastSeenAt(NOW_LOCAL);
            when(deviceRepository.findById(3)).thenReturn(Optional.of(d));

            sut.rotateToken(ORG_ID, 3);

            assertThat(d.getName()).isEqualTo("A");
            assertThat(d.getDeviceId()).isEqualTo("uuid-1");
            assertThat(d.getLastState()).isEqualTo("IDLE");
            assertThat(d.getLastSeenAt()).isEqualTo(NOW_LOCAL);
        }

        @Test
        @DisplayName("should 404 for an unknown device")
        void rotateToken_should_404() {
            when(deviceRepository.findById(3)).thenReturn(Optional.empty());
            assertApi(() -> sut.rotateToken(ORG_ID, 3), HttpStatus.NOT_FOUND, ErrorCodes.DEVICES_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("recordHeartbeat")
    class RecordHeartbeat {

        private Device registered;
        private String token;

        @BeforeEach
        void registerDevice() {
            DeviceWithToken created = sut.create(ORG_ID, "Belén");
            registered = created.device().setId(10);
            token = created.token();
            lenient().when(deviceRepository.findByTokenHash(tokenService.hash(token))).thenReturn(Optional.of(registered));
        }

        @Test
        @DisplayName("should look the device up by the sha256 of the token")
        void recordHeartbeat_should_lookupByHash() {
            sut.recordHeartbeat(token, heartbeat("uuid-1", "IDLE"), "1.2.3.4");
            verify(deviceRepository).findByTokenHash(tokenService.hash(token));
        }

        @Test
        @DisplayName("should bind the player uuid on the first heartbeat and store every last* field")
        void recordHeartbeat_should_bindAndStore() {
            Device out = sut.recordHeartbeat(token, heartbeat("uuid-1", "PLAYING"), "203.0.113.9");

            assertThat(out).isSameAs(registered);
            assertThat(out.getDeviceId()).isEqualTo("uuid-1");
            assertThat(out.getLastState()).isEqualTo("PLAYING");
            assertThat(out.getLastShowHash()).isEqualTo("sha256:abc");
            assertThat(out.getLastShowName()).isEqualTo("Nadal 2026");
            assertThat(out.getLastOverrides()).isEqualTo("[\"Angel\",\"Riu\"]");
            assertThat(out.getLastAlerts()).contains("\"code\":\"motor_timeout\"").contains("\"port\":\"Angel\"");
            assertThat(out.getLastShowTimelineId()).isEqualTo(42);
            assertThat(out.getLastVersion()).isEqualTo("1.2.3");
            assertThat(out.getLastUptimeS()).isEqualTo(3600L);
            assertThat(out.getLastNetMode()).isEqualTo("ap");
            assertThat(out.getLastIp()).isEqualTo("203.0.113.9");
            assertThat(out.getLastError()).isNull();
            assertThat(out.getLastSeenAt()).isEqualTo(NOW_LOCAL);
            assertThat(out.getName()).isEqualTo("Belén");
            verify(deviceRepository).save(registered);
        }

        @Test
        @DisplayName("should keep the uuid when the same one reports again")
        void recordHeartbeat_should_keepSameUuid() {
            sut.recordHeartbeat(token, heartbeat("uuid-1", "IDLE"), "1.1.1.1");
            sut.recordHeartbeat(token, heartbeat("uuid-1", "PLAYING"), "1.1.1.1");
            assertThat(registered.getDeviceId()).isEqualTo("uuid-1");
            assertThat(registered.getLastState()).isEqualTo("PLAYING");
        }

        @Test
        @DisplayName("should accept and overwrite a different uuid (re-imaged card)")
        void recordHeartbeat_should_overwriteDifferentUuid() {
            sut.recordHeartbeat(token, heartbeat("uuid-1", "IDLE"), "1.1.1.1");
            sut.recordHeartbeat(token, heartbeat("uuid-2", "IDLE"), "1.1.1.1");
            assertThat(registered.getDeviceId()).isEqualTo("uuid-2");
        }

        @Test
        @DisplayName("should replace the previous report wholesale, clearing fields the new one omits")
        void recordHeartbeat_should_replaceWholesale() {
            sut.recordHeartbeat(token, heartbeat("uuid-1", "PLAYING"), "1.1.1.1");
            HeartbeatDto minimal = new HeartbeatDto("uuid-1", null, "SAFE", null, null, null, null, null, null, null, null, null,
                    "relay failed", null);

            sut.recordHeartbeat(token, minimal, "1.1.1.1");

            assertThat(registered.getLastState()).isEqualTo("SAFE");
            assertThat(registered.getLastShowHash()).isNull();
            assertThat(registered.getLastShowName()).isNull();
            assertThat(registered.getLastShowTimelineId()).isNull();
            assertThat(registered.getLastOverrides()).isEqualTo("[]");
            assertThat(registered.getLastAlerts()).isEqualTo("[]");
            assertThat(registered.getLastVersion()).isNull();
            assertThat(registered.getLastUptimeS()).isNull();
            assertThat(registered.getLastNetMode()).isNull();
            assertThat(registered.getLastError()).isEqualTo("relay failed");
        }

        @Test
        @DisplayName("should truncate an over-long client address to 45 characters")
        void recordHeartbeat_should_truncateIp() {
            sut.recordHeartbeat(token, heartbeat("uuid-1", "IDLE"), "a".repeat(60));
            assertThat(registered.getLastIp()).hasSize(45);
        }

        @Test
        @DisplayName("should keep a null client address")
        void recordHeartbeat_should_keepNullIp() {
            sut.recordHeartbeat(token, heartbeat("uuid-1", "IDLE"), null);
            assertThat(registered.getLastIp()).isNull();
        }

        @Test
        @DisplayName("should reject an unknown token with 401 devices.token_invalid")
        void recordHeartbeat_should_rejectUnknownToken() {
            when(deviceRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
            assertApi(() -> sut.recordHeartbeat("bht_nope", heartbeat("uuid-1", "IDLE"), "1.1.1.1"),
                    HttpStatus.UNAUTHORIZED, ErrorCodes.DEVICES_TOKEN_INVALID);
            verify(deviceRepository, never()).save(any());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        @DisplayName("should reject a null or blank token without hitting the repository")
        void recordHeartbeat_should_rejectBlankToken(String raw) {
            assertApi(() -> sut.recordHeartbeat(raw, heartbeat("uuid-1", "IDLE"), "1.1.1.1"),
                    HttpStatus.UNAUTHORIZED, ErrorCodes.DEVICES_TOKEN_INVALID);
            verify(deviceRepository, never()).findByTokenHash(anyString());
        }

        @Test
        @DisplayName("should not accept the previous token once rotated")
        void recordHeartbeat_should_rejectRotatedToken() {
            when(deviceRepository.findById(10)).thenReturn(Optional.of(registered));
            DeviceWithToken rotated = sut.rotateToken(ORG_ID, 10);
            when(deviceRepository.findByTokenHash(tokenService.hash(token))).thenReturn(Optional.empty());
            when(deviceRepository.findByTokenHash(tokenService.hash(rotated.token()))).thenReturn(Optional.of(registered));

            assertApi(() -> sut.recordHeartbeat(token, heartbeat("uuid-1", "IDLE"), "1.1.1.1"),
                    HttpStatus.UNAUTHORIZED, ErrorCodes.DEVICES_TOKEN_INVALID);
            assertThat(sut.recordHeartbeat(rotated.token(), heartbeat("uuid-1", "IDLE"), "1.1.1.1")).isSameAs(registered);
        }
    }

    @Nested
    @DisplayName("toResponse")
    class ToResponse {

        @Test
        @DisplayName("should expose the telemetry, the computed online flag, the overrides list and the token prefix")
        void toResponse_should_mapFields() {
            Device d = device(3, "Belén").setDeviceId("uuid-1").setLastState("PLAYING").setLastShowHash("sha256:x")
                    .setLastShowName("Nadal").setLastShowTimelineId(42).setLastOverrides("[\"Angel\"]")
                    .setLastAlerts("[{\"code\":\"sensor_panic\",\"port\":\"Seta\",\"message\":\"Seta\",\"at\":\"t\"}]").setLastVersion("1.0").setLastIp("1.2.3.4")
                    .setLastError("e").setLastSeenAt(NOW_LOCAL.minusMinutes(1));

            DeviceResponseDto dto = sut.toResponse(d);

            assertThat(dto.id()).isEqualTo(3);
            assertThat(dto.deviceId()).isEqualTo("uuid-1");
            assertThat(dto.name()).isEqualTo("Belén");
            assertThat(dto.board()).isEqualTo("rpi4b");
            assertThat(dto.online()).isTrue();
            assertThat(dto.lastSeenAt()).isEqualTo(NOW_LOCAL.minusMinutes(1));
            assertThat(dto.lastState()).isEqualTo("PLAYING");
            assertThat(dto.lastShowHash()).isEqualTo("sha256:x");
            assertThat(dto.lastShowName()).isEqualTo("Nadal");
            assertThat(dto.lastShowTimelineId()).isEqualTo(42);
            assertThat(dto.lastOverrides()).containsExactly("Angel");
            assertThat(dto.lastAlerts()).containsExactly(new DeviceAlertDto("sensor_panic", "Seta", "Seta", "t"));
            assertThat(dto.lastVersion()).isEqualTo("1.0");
            assertThat(dto.lastIp()).isEqualTo("1.2.3.4");
            assertThat(dto.lastError()).isEqualTo("e");
            assertThat(dto.tokenPrefix()).isEqualTo("bht_3");
        }

        @Test
        @DisplayName("should report offline and an empty override list for a device that never reported")
        void toResponse_should_handleFreshDevice() {
            DeviceResponseDto dto = sut.toResponse(device(3, "Belén"));
            assertThat(dto.online()).isFalse();
            assertThat(dto.lastOverrides()).isEmpty();
            assertThat(dto.lastAlerts()).isEmpty();
            assertThat(dto.lastSeenAt()).isNull();
            assertThat(dto.deviceId()).isNull();
        }

        @Test
        @DisplayName("should report offline once the window has elapsed")
        void toResponse_should_reportOfflineAfterWindow() {
            assertThat(sut.toResponse(device(3, "A").setLastSeenAt(NOW_LOCAL.minusMinutes(6))).online()).isFalse();
        }
    }

    @Nested
    @DisplayName("extractDeviceToken")
    class ExtractDeviceToken {

        @Test
        @DisplayName("should return the token of a Device header")
        void extract_should_returnDeviceToken() {
            assertThat(DeviceService.extractDeviceToken("Device bht_abc")).contains("bht_abc");
        }

        @Test
        @DisplayName("should accept the scheme case-insensitively and trim spaces")
        void extract_should_beLenientWithCaseAndSpaces() {
            assertThat(DeviceService.extractDeviceToken("device bht_abc")).contains("bht_abc");
            assertThat(DeviceService.extractDeviceToken("  DEVICE   bht_abc  ")).contains("bht_abc");
        }

        @Test
        @DisplayName("should ignore Bearer and other schemes")
        void extract_should_ignoreOtherSchemes() {
            assertThat(DeviceService.extractDeviceToken("Bearer bht_abc")).isEmpty();
            assertThat(DeviceService.extractDeviceToken("Basic dXNlcjpwYXNz")).isEmpty();
            assertThat(DeviceService.extractDeviceToken("Devices bht_abc")).isEmpty();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"Device", "Device ", "Device    ", "bht_abc"})
        @DisplayName("should be empty for a missing header, a bare scheme or a bare token")
        void extract_should_beEmptyWhenMalformed(String header) {
            assertThat(DeviceService.extractDeviceToken(header)).isEmpty();
        }
    }
}
