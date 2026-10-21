package com.stocka.backend.modules.ports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeRepository;
import com.stocka.backend.modules.piecetypes.service.ActionParametersJsonCodec;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.ports.dto.CreatePortDto;
import com.stocka.backend.modules.ports.dto.PortResponseDto;
import com.stocka.backend.modules.ports.dto.UpdatePortDto;
import com.stocka.backend.modules.ports.entity.Port;
import com.stocka.backend.modules.ports.repository.PortRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PortService")
class PortServiceTest {

    private static final Integer ORG_ID = 1;
    private static final Integer RELAY_TYPE_ID = 5;
    private static final Integer SEGMENT_TYPE_ID = 6;
    private static final Integer UNTYPED_TYPE_ID = 7;
    private static final Integer HEAD_TYPE_ID = 8;
    private static final Integer MOTOR_TYPE_ID = 9;

    @Mock private OrganizationService organizationService;
    @Mock private PortRepository portRepository;
    @Mock private PieceTypeService pieceTypeService;
    @Mock private PieceTypeRepository pieceTypeRepository;

    private final OutputCatalog catalog = new OutputCatalog();
    private final PortConfigJsonCodec configCodec = new PortConfigJsonCodec();

    private PortService sut;
    private Organization org;
    private PieceType relayType;
    private PieceType segmentType;
    private PieceType untypedType;
    private PieceType headType;
    private PieceType motorType;

    @BeforeEach
    void setUp() {
        sut = new PortService(organizationService, portRepository, new ActionParametersJsonCodec(),
                pieceTypeService, pieceTypeRepository, catalog, new PortConfigValidator(catalog), configCodec);
        org = new Organization().setId(ORG_ID);
        relayType = new PieceType().setId(RELAY_TYPE_ID).setName("Figura amb relé").setKind(OutputKind.RELAY);
        segmentType = new PieceType().setId(SEGMENT_TYPE_ID).setName("Edifici amb llum").setKind(OutputKind.LED_SEGMENT);
        untypedType = new PieceType().setId(UNTYPED_TYPE_ID).setName("Caixa");
        headType = new PieceType().setId(HEAD_TYPE_ID).setName("Cabezal").setKind(OutputKind.MOVING_HEAD);
        motorType = new PieceType().setId(MOTOR_TYPE_ID).setName("Àngel").setKind(OutputKind.MOTOR);
        lenient().when(organizationService.findById(ORG_ID)).thenReturn(org);
        lenient().when(portRepository.saveAndFlush(any(Port.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(portRepository.save(any(Port.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(portRepository.findByOrganizationOrderByPositionAscIdAsc(org)).thenReturn(List.of());
        lenient().when(portRepository.findByOrganizationAndName(eq(org), anyString())).thenReturn(Optional.empty());
        lenient().when(pieceTypeService.findInOrg(ORG_ID, RELAY_TYPE_ID)).thenReturn(relayType);
        lenient().when(pieceTypeService.findInOrg(ORG_ID, SEGMENT_TYPE_ID)).thenReturn(segmentType);
        lenient().when(pieceTypeService.findInOrg(ORG_ID, UNTYPED_TYPE_ID)).thenReturn(untypedType);
        lenient().when(pieceTypeService.findInOrg(ORG_ID, HEAD_TYPE_ID)).thenReturn(headType);
        lenient().when(pieceTypeService.findInOrg(ORG_ID, MOTOR_TYPE_ID)).thenReturn(motorType);
    }

    // ------------------------------------------------------------------ fixtures

    private static CreatePortDto relayDto(String name, Integer pin) {
        return new CreatePortDto().setName(name).setKind("relay").setPieceTypeId(RELAY_TYPE_ID).setPin(pin);
    }

    private static CreatePortDto stripDto(String name, Integer pin, int dma, int channel, int length) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("dma", dma);
        cfg.put("channel", channel);
        cfg.put("length", length);
        return new CreatePortDto().setName(name).setKind("led_strip").setPin(pin).setConfig(cfg);
    }

    private static CreatePortDto segmentDto(String name, String strip, int first, int last) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("strip", strip);
        cfg.put("first", first);
        cfg.put("last", last);
        return new CreatePortDto().setName(name).setKind("led_segment").setPieceTypeId(SEGMENT_TYPE_ID).setConfig(cfg);
    }

    private static Map<String, Object> motorConfig(int pinForward, int pinReverse) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("pinForward", pinForward);
        cfg.put("pinReverse", pinReverse);
        cfg.put("maxTimeForward", 20);
        cfg.put("maxTimeReverse", 15);
        return cfg;
    }

    private static Map<String, Object> expanderMotorConfig(String expander, int channelForward, int channelReverse) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("expander", expander);
        cfg.put("channelForward", channelForward);
        cfg.put("channelReverse", channelReverse);
        cfg.put("maxTimeForward", 60);
        cfg.put("maxTimeReverse", 60);
        return cfg;
    }

    private static CreatePortDto motorDto(String name, Map<String, Object> config) {
        return new CreatePortDto().setName(name).setKind("motor").setPieceTypeId(MOTOR_TYPE_ID).setConfig(config);
    }

    private static CreatePortDto sensorDto(String name, Integer pin) {
        return new CreatePortDto().setName(name).setKind("sensor").setPin(pin).setConfig(Map.of("onActive", "none"));
    }

    private static CreatePortDto expanderSensorDto(String name, String expander, int channel) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("expander", expander);
        cfg.put("channel", channel);
        return new CreatePortDto().setName(name).setKind("sensor").setConfig(cfg);
    }

    private static CreatePortDto expanderDto(String name, int address) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("chip", "mcp23017");
        cfg.put("bus", 1);
        cfg.put("address", address);
        cfg.put("activeLow", true);
        return new CreatePortDto().setName(name).setKind("gpio_expander").setConfig(cfg);
    }

    private static CreatePortDto expanderRelayDto(String name, String expander, int channel) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("expander", expander);
        cfg.put("channel", channel);
        return new CreatePortDto().setName(name).setKind("relay").setPieceTypeId(RELAY_TYPE_ID).setConfig(cfg);
    }

    private static CreatePortDto headDto(String name, int address) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("profile", "varytec-hero-wash-712z");
        cfg.put("address", address);
        return new CreatePortDto().setName(name).setKind("moving_head").setPieceTypeId(HEAD_TYPE_ID).setConfig(cfg);
    }

    private Port expanderPort(int id, String name, int address) {
        return new Port().setId(id).setName(name).setKind(OutputKind.GPIO_EXPANDER).setOrganization(org)
                .setConfigJson(configCodec.serialize(expanderDto(name, address).getConfig()));
    }

    private Port expanderRelayPort(int id, String name, String expander, int channel) {
        return new Port().setId(id).setName(name).setKind(OutputKind.RELAY).setPieceTypeId(RELAY_TYPE_ID).setOrganization(org)
                .setConfigJson(configCodec.serialize(expanderRelayDto(name, expander, channel).getConfig()));
    }

    private Port headPort(int id, String name, int address) {
        return new Port().setId(id).setName(name).setKind(OutputKind.MOVING_HEAD).setPieceTypeId(HEAD_TYPE_ID).setOrganization(org)
                .setConfigJson(configCodec.serialize(headDto(name, address).getConfig()));
    }

    private Port relayPort(int id, String name, Integer pin) {
        return new Port().setId(id).setName(name).setKind(OutputKind.RELAY).setPin(pin)
                .setPieceTypeId(RELAY_TYPE_ID).setOrganization(org).setConfigJson("{}");
    }

    private Port stripPort(int id, String name, int pin, int length) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("dma", 10);
        cfg.put("channel", catalog.board("rpi4b").orElseThrow().ws281xChannel().get(pin));
        cfg.put("length", length);
        return new Port().setId(id).setName(name).setKind(OutputKind.LED_STRIP).setPin(pin)
                .setOrganization(org).setConfigJson(configCodec.serialize(cfg));
    }

    private Port segmentPort(int id, String name, String strip, int first, int last) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("strip", strip);
        cfg.put("first", first);
        cfg.put("last", last);
        return new Port().setId(id).setName(name).setKind(OutputKind.LED_SEGMENT).setPieceTypeId(SEGMENT_TYPE_ID)
                .setOrganization(org).setConfigJson(configCodec.serialize(cfg));
    }

    private Port motorPort(int id, String name, int pinForward, int pinReverse) {
        return motorPort(id, name, motorConfig(pinForward, pinReverse));
    }

    private Port motorPort(int id, String name, Map<String, Object> config) {
        return new Port().setId(id).setName(name).setKind(OutputKind.MOTOR).setPieceTypeId(MOTOR_TYPE_ID).setOrganization(org)
                .setConfigJson(configCodec.serialize(config));
    }

    private Port sensorPort(int id, String name, Integer pin) {
        return new Port().setId(id).setName(name).setKind(OutputKind.SENSOR).setPin(pin).setOrganization(org).setConfigJson("{}");
    }

    private Port expanderSensorPort(int id, String name, String expander, int channel) {
        return new Port().setId(id).setName(name).setKind(OutputKind.SENSOR).setOrganization(org)
                .setConfigJson(configCodec.serialize(expanderSensorDto(name, expander, channel).getConfig()));
    }

    /** Makes the given ports the organization's current ports, by list and by name. */
    private void existingPorts(Port... ports) {
        lenient().when(portRepository.findByOrganizationOrderByPositionAscIdAsc(org)).thenReturn(List.of(ports));
        for (Port p : ports) {
            lenient().when(portRepository.findByOrganizationAndName(org, p.getName())).thenReturn(Optional.of(p));
            lenient().when(portRepository.findById(p.getId())).thenReturn(Optional.of(p));
        }
    }

    private static void assertApi(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }

    private static void assertApi(ThrowingCallable call, HttpStatus status, String code, String paramKey, Object paramValue) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
            assertThat(ex.getParams()).containsEntry(paramKey, paramValue);
        });
    }

    // ================================================================== create

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("persists a relay with its kind, piece type, pin, empty config and a sequential position")
        void should_persistRelay() {
            existingPorts(relayPort(1, "Focos", 27), relayPort(2, "Sembra", 26));

            Port port = sut.create(ORG_ID, relayDto("LlumsE", 18));

            assertThat(port.getKind()).isEqualTo(OutputKind.RELAY);
            assertThat(port.getPieceTypeId()).isEqualTo(RELAY_TYPE_ID);
            assertThat(port.getPin()).isEqualTo(18);
            assertThat(port.getPosition()).isEqualTo(2);
            assertThat(port.getOrganization()).isSameAs(org);
            assertThat(sut.configOf(port)).isEmpty();
            assertThat(sut.parametersOf(port)).isEmpty();
        }

        @Test
        @DisplayName("persists a strip without piece type, with its config normalized")
        void should_persistStrip() {
            Port port = sut.create(ORG_ID, stripDto("LedStrip3", 13, 10, 1, 300));

            assertThat(port.getKind()).isEqualTo(OutputKind.LED_STRIP);
            assertThat(port.getPieceTypeId()).isNull();
            assertThat(port.getPin()).isEqualTo(13);
            assertThat(sut.configOf(port)).containsExactly(
                    Map.entry("dma", 10), Map.entry("channel", 1), Map.entry("length", 300));
            verify(pieceTypeService, never()).findInOrg(any(), any());
        }

        @Test
        @DisplayName("persists a segment that fits inside an existing strip")
        void should_persistSegment() {
            existingPorts(stripPort(1, "LedStrip3", 13, 300));

            Port port = sut.create(ORG_ID, segmentDto("Fondo1", "LedStrip3", 0, 148));

            assertThat(port.getKind()).isEqualTo(OutputKind.LED_SEGMENT);
            assertThat(port.getPin()).isNull();
            assertThat(sut.configOf(port)).containsEntry("strip", "LedStrip3").containsEntry("last", 148);
        }

        @Test
        @DisplayName("persists a motor with its three pins in the config and no Port.pin")
        void should_persistMotor() {

            Port port = sut.create(ORG_ID, new CreatePortDto().setName("Angel").setKind("motor").setPieceTypeId(9)
                    .setConfig(motorConfig(3, 4)));

            assertThat(port.getPin()).isNull();
            assertThat(sut.configOf(port)).containsEntry("pinForward", 3).containsEntry("pinReverse", 4).containsEntry("maxTimeReverse", 15);
        }

        @Test
        @DisplayName("persists audio and video ports with no pin and no config")
        void should_persistAudioAndVideo() {
            PieceType audioType = new PieceType().setId(10).setKind(OutputKind.AUDIO).setName("Altaveus");
            when(pieceTypeService.findInOrg(ORG_ID, 10)).thenReturn(audioType);

            Port port = sut.create(ORG_ID, new CreatePortDto().setName("Audio").setKind("audio").setPieceTypeId(10));

            assertThat(port.getKind()).isEqualTo(OutputKind.AUDIO);
            assertThat(port.getPin()).isNull();
            assertThat(port.getConfigJson()).isEqualTo("{}");
        }

        @Test
        @DisplayName("serializes the legacy parameter list and defaults required and display name")
        void should_keepLegacyParameters() {
            CreatePortDto dto = relayDto("Focos", 27).setParameters(List.of(
                    new ActionParameterDto().setName("channel").setType(AttributeType.INTEGER).setDynamic(true)));

            Port port = sut.create(ORG_ID, dto);

            List<ActionParameterDto> params = sut.parametersOf(port);
            assertThat(params).hasSize(1);
            assertThat(params.get(0).getRequired()).isTrue();
            assertThat(params.get(0).getDisplayName()).isEqualTo("channel");
            assertThat(params.get(0).getPosition()).isZero();
        }

        @Nested
        @DisplayName("kind rules")
        class KindRules {

            @Test
            @DisplayName("rejects a missing or blank kind with ports.kind_required")
            void should_requireKind() {
                assertApi(() -> sut.create(ORG_ID, relayDto("Focos", 27).setKind(null)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_KIND_REQUIRED);
                assertApi(() -> sut.create(ORG_ID, relayDto("Focos", 27).setKind("  ")), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_KIND_REQUIRED);
                verify(portRepository, never()).saveAndFlush(any());
            }

            @Test
            @DisplayName("rejects an unknown kind with ports.kind_invalid and echoes it")
            void should_rejectUnknownKind() {
                assertApi(() -> sut.create(ORG_ID, relayDto("Focos", 27).setKind("laser")),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_KIND_INVALID, "kind", "laser");
            }

            @Test
            @DisplayName("requires a piece type for non-infrastructure kinds with ports.piece_type_required")
            void should_requirePieceTypeForPieceKinds() {
                assertApi(() -> sut.create(ORG_ID, relayDto("Focos", 27).setPieceTypeId(null)),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIECE_TYPE_REQUIRED);
                assertApi(() -> sut.create(ORG_ID, segmentDto("Fondo1", "LedStrip3", 0, 1).setPieceTypeId(null)),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIECE_TYPE_REQUIRED);
            }

            @Test
            @DisplayName("accepts infrastructure kinds without a piece type and with a matching one")
            void should_allowInfrastructureWithOrWithoutType() {
                assertThat(sut.create(ORG_ID, new CreatePortDto().setName("Pulsador").setKind("sensor").setPin(20)).getPieceTypeId()).isNull();
                PieceType stripType = new PieceType().setId(11).setKind(OutputKind.LED_STRIP).setName("Tira");
                when(pieceTypeService.findInOrg(ORG_ID, 11)).thenReturn(stripType);
                assertThat(sut.create(ORG_ID, stripDto("LedStrip2", 12, 10, 0, 150).setPieceTypeId(11)).getPieceTypeId()).isEqualTo(11);
            }

            @Test
            @DisplayName("rejects a piece type without kind with ports.piece_type_kind_mismatch")
            void should_rejectUntypedPieceType() {
                assertThatThrownBy(() -> sut.create(ORG_ID, relayDto("Focos", 27).setPieceTypeId(UNTYPED_TYPE_ID)))
                        .isInstanceOfSatisfying(ApiException.class, ex -> {
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_PIECE_TYPE_KIND_MISMATCH);
                            assertThat(ex.getParams()).containsEntry("type", "Caixa").containsEntry("typeKind", "-").containsEntry("portKind", "relay");
                        });
            }

            @Test
            @DisplayName("rejects a piece type of another kind with ports.piece_type_kind_mismatch")
            void should_rejectOtherKindPieceType() {
                assertThatThrownBy(() -> sut.create(ORG_ID, relayDto("Focos", 27).setPieceTypeId(SEGMENT_TYPE_ID)))
                        .isInstanceOfSatisfying(ApiException.class, ex -> {
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_PIECE_TYPE_KIND_MISMATCH);
                            assertThat(ex.getParams()).containsEntry("typeKind", "led_segment").containsEntry("portKind", "relay");
                        });
            }

            @Test
            @DisplayName("propagates 404 when the piece type does not exist in the organization")
            void should_propagateMissingPieceType() {
                when(pieceTypeService.findInOrg(ORG_ID, 999)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.PIECE_TYPES_NOT_FOUND));
                assertApi(() -> sut.create(ORG_ID, relayDto("Focos", 27).setPieceTypeId(999)), HttpStatus.NOT_FOUND, ErrorCodes.PIECE_TYPES_NOT_FOUND);
            }
        }

        @Nested
        @DisplayName("pin rules")
        class PinRules {

            @Test
            @DisplayName("requires a pin for kinds that have one, within the board range")
            void should_requireValidPin() {
                assertApi(() -> sut.create(ORG_ID, relayDto("Focos", null)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_OR_EXPANDER, "reason", "none");
                assertApi(() -> sut.create(ORG_ID, relayDto("Focos", -1)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_INVALID);
                assertApi(() -> sut.create(ORG_ID, relayDto("Focos", 28)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_INVALID);
                assertApi(() -> sut.create(ORG_ID, stripDto("LedStrip3", null, 10, 1, 300)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_INVALID);
            }

            @Test
            @DisplayName("rejects a reserved pin with ports.pin_reserved")
            void should_rejectReservedPin() {
                assertApi(() -> sut.create(ORG_ID, relayDto("Focos", 0)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_RESERVED, "pin", 0);
                assertApi(() -> sut.create(ORG_ID, relayDto("Focos", 1)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_RESERVED, "pin", 1);
            }

            @Test
            @DisplayName("rejects a pin on kinds that have none with ports.pin_not_allowed")
            void should_rejectPinOnPinlessKinds() {
                existingPorts(stripPort(1, "LedStrip3", 13, 300));
                assertApi(() -> sut.create(ORG_ID, segmentDto("Fondo1", "LedStrip3", 0, 1).setPin(5)),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_NOT_ALLOWED, "kind", "led_segment");
                PieceType audioType = new PieceType().setId(10).setKind(OutputKind.AUDIO).setName("Altaveus");
                when(pieceTypeService.findInOrg(ORG_ID, 10)).thenReturn(audioType);
                assertApi(() -> sut.create(ORG_ID, new CreatePortDto().setName("Audio").setKind("audio").setPieceTypeId(10).setPin(5)),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_NOT_ALLOWED, "kind", "audio");
            }

            @Test
            @DisplayName("a strip needs a WS281x-capable pin and a channel that matches it")
            void should_checkStripPinAndChannel() {
                assertApi(() -> sut.create(ORG_ID, stripDto("LedStrip3", 17, 10, 0, 300)),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_STRIP_PIN_UNSUPPORTED, "pin", 17);
                assertThatThrownBy(() -> sut.create(ORG_ID, stripDto("LedStrip3", 13, 10, 0, 300)))
                        .isInstanceOfSatisfying(ApiException.class, ex -> {
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_STRIP_CHANNEL_MISMATCH);
                            assertThat(ex.getParams()).containsEntry("pin", 13).containsEntry("channel", 1);
                        });
                assertThat(sut.create(ORG_ID, stripDto("LedStrip3", 13, 10, 1, 300)).getPin()).isEqualTo(13);
                assertThat(sut.create(ORG_ID, stripDto("LedStrip4", 10, 8, 0, 44)).getPin()).isEqualTo(10);
            }

            @Test
            @DisplayName("motor pins in the config must be inside the board and not reserved")
            void should_checkMotorConfigPins() {
                CreatePortDto outOfBoard = new CreatePortDto().setName("Angel").setKind("motor").setPieceTypeId(9).setConfig(motorConfig(3, 28));
                assertApi(() -> sut.create(ORG_ID, outOfBoard), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
                CreatePortDto reserved = new CreatePortDto().setName("Angel").setKind("motor").setPieceTypeId(9).setConfig(motorConfig(0, 4));
                assertApi(() -> sut.create(ORG_ID, reserved), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_RESERVED, "pin", 0);
            }
        }

        @Nested
        @DisplayName("pin conflicts")
        class PinConflicts {

            @Test
            @DisplayName("rejects a pin already used by another port with ports.pin_conflict")
            void should_rejectUsedPin() {
                existingPorts(relayPort(1, "Focos", 27));
                assertApi(() -> sut.create(ORG_ID, relayDto("Sembra", 27)), HttpStatus.CONFLICT, ErrorCodes.PORTS_PIN_CONFLICT);
                verify(portRepository, never()).saveAndFlush(any());
            }

            @Test
            @DisplayName("counts the pins a motor keeps in its config")
            void should_rejectPinUsedByMotorConfig() {
                existingPorts(motorPort(1, "Angel", 3, 4));
                assertApi(() -> sut.create(ORG_ID, relayDto("Sembra", 4)), HttpStatus.CONFLICT, ErrorCodes.PORTS_PIN_CONFLICT);
            }

            @Test
            @DisplayName("rejects a motor whose config pins collide with an existing port")
            void should_rejectMotorCollidingWithRelay() {
                existingPorts(relayPort(1, "Focos", 27));
                CreatePortDto dto = new CreatePortDto().setName("Angel").setKind("motor").setPieceTypeId(9).setConfig(motorConfig(3, 27));
                assertApi(() -> sut.create(ORG_ID, dto), HttpStatus.CONFLICT, ErrorCodes.PORTS_PIN_CONFLICT);
            }

            @Test
            @DisplayName("accepts pins that are free")
            void should_acceptFreePins() {
                existingPorts(relayPort(1, "Focos", 27), motorPort(2, "Angel", 3, 4), stripPort(3, "LedStrip3", 13, 300));
                assertThat(sut.create(ORG_ID, relayDto("Sembra", 26)).getPin()).isEqualTo(26);
            }
        }

        @Nested
        @DisplayName("expander rules (contract v3)")
        class ExpanderRules {

            @Test
            @DisplayName("persists an expander (infrastructure, no pin) and a relay on one of its channels")
            void should_persistExpanderAndRelay() {
                Port exp = sut.create(ORG_ID, expanderDto("exp1", 32));
                assertThat(exp.getKind()).isEqualTo(OutputKind.GPIO_EXPANDER);
                assertThat(exp.getPin()).isNull();
                assertThat(exp.getPieceTypeId()).isNull();
                existingPorts(expanderPort(1, "exp1", 32));
                Port relay = sut.create(ORG_ID, expanderRelayDto("Estable", "exp1", 3));
                assertThat(relay.getPin()).isNull();
                assertThat(sut.configOf(relay)).containsExactly(Map.entry("expander", "exp1"), Map.entry("channel", 3));
            }

            @Test
            @DisplayName("a relay takes a pin or an expander, never both nor neither")
            void should_requirePinXorExpander() {
                existingPorts(expanderPort(1, "exp1", 32));
                assertApi(() -> sut.create(ORG_ID, expanderRelayDto("Estable", "exp1", 3).setPin(26)),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_OR_EXPANDER, "reason", "both");
                assertApi(() -> sut.create(ORG_ID, relayDto("Sembra", null)),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_OR_EXPANDER, "reason", "none");
            }

            @Test
            @DisplayName("the expander must exist as a gpio_expander of the organization")
            void should_requireExistingExpander() {
                existingPorts(relayPort(1, "Focos", 27));
                assertApi(() -> sut.create(ORG_ID, expanderRelayDto("Estable", "exp9", 3)),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_EXPANDER_NOT_FOUND, "expander", "exp9");
                assertApi(() -> sut.create(ORG_ID, expanderRelayDto("Estable", "Focos", 3)),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_EXPANDER_NOT_FOUND, "expander", "Focos");
            }

            @Test
            @DisplayName("each channel of an expander takes one relay")
            void should_rejectChannelConflict() {
                existingPorts(expanderPort(1, "exp1", 32), expanderRelayPort(2, "Estable", "exp1", 3));
                assertApi(() -> sut.create(ORG_ID, expanderRelayDto("Pou", "exp1", 3)),
                        HttpStatus.CONFLICT, ErrorCodes.PORTS_EXPANDER_CHANNEL_CONFLICT, "port", "Estable");
                assertThat(sut.create(ORG_ID, expanderRelayDto("Pou", "exp1", 4)).getName()).isEqualTo("Pou");
            }

            @Test
            @DisplayName("each I2C address takes one expander per bus")
            void should_rejectAddressConflict() {
                existingPorts(expanderPort(1, "exp1", 32));
                assertApi(() -> sut.create(ORG_ID, expanderDto("exp2", 32)),
                        HttpStatus.CONFLICT, ErrorCodes.PORTS_EXPANDER_ADDRESS_CONFLICT, "port", "exp1");
                assertThat(sut.create(ORG_ID, expanderDto("exp2", 33)).getName()).isEqualTo("exp2");
                CreatePortDto otherBus = expanderDto("exp3", 32);
                otherBus.getConfig().put("bus", 0);
                assertThat(sut.create(ORG_ID, otherBus).getName()).isEqualTo("exp3");
            }

            @Test
            @DisplayName("GPIO 2 and 3 are reserved for I2C while an expander exists, in both directions")
            void should_reserveI2cPins() {
                existingPorts(expanderPort(1, "exp1", 32));
                assertApi(() -> sut.create(ORG_ID, relayDto("Sembra", 2)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_I2C_PIN_RESERVED, "pin", 2);
                CreatePortDto motor = new CreatePortDto().setName("Angel").setKind("motor").setPieceTypeId(9).setConfig(motorConfig(3, 4));
                assertApi(() -> sut.create(ORG_ID, motor), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_I2C_PIN_RESERVED, "pin", 3);
                existingPorts(relayPort(1, "Sembra", 3));
                assertApi(() -> sut.create(ORG_ID, expanderDto("exp2", 32)), HttpStatus.CONFLICT, ErrorCodes.PORTS_I2C_PIN_RESERVED, "port", "Sembra");
                existingPorts(relayPort(1, "Sembra", 26));
                assertThat(sut.create(ORG_ID, expanderDto("exp3", 32)).getName()).isEqualTo("exp3");
            }

            @Test
            @DisplayName("without an expander GPIO 2 and 3 stay usable")
            void should_allowI2cPinsWithoutExpander() {
                assertThat(sut.create(ORG_ID, relayDto("Sembra", 2)).getPin()).isEqualTo(2);
            }

            @Test
            @DisplayName("deleting an expander with relays is refused and renaming it cascades")
            void should_protectAndCascade() {
                Port exp = expanderPort(1, "exp1", 32);
                Port relay = expanderRelayPort(2, "Estable", "exp1", 3);
                existingPorts(exp, relay);
                assertApi(() -> sut.softDelete(ORG_ID, 1), HttpStatus.CONFLICT, ErrorCodes.PORTS_EXPANDER_IN_USE, "relays", "Estable");
                sut.update(ORG_ID, 1, new UpdatePortDto().setName("expansor"));
                assertThat(exp.getName()).isEqualTo("expansor");
                assertThat(sut.configOf(relay)).containsEntry("expander", "expansor");
                existingPorts(exp);
                sut.softDelete(ORG_ID, 1);
                assertThat(exp.getDeletedAt()).isNotNull();
            }

            @Test
            @DisplayName("updating a relay can move it between a GPIO and an expander")
            void should_moveRelayBetweenGpioAndExpander() {
                Port relay = relayPort(2, "Sembra", 26);
                existingPorts(expanderPort(1, "exp1", 32), relay);
                Map<String, Object> onExpander = new LinkedHashMap<>();
                onExpander.put("expander", "exp1");
                onExpander.put("channel", 5);
                sut.update(ORG_ID, 2, new UpdatePortDto().setConfig(onExpander));
                assertThat(relay.getPin()).isNull();
                assertThat(sut.configOf(relay)).containsEntry("channel", 5);
                assertApi(() -> sut.update(ORG_ID, 2, new UpdatePortDto().setConfig(Map.of())),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_OR_EXPANDER, "reason", "none");
                sut.update(ORG_ID, 2, new UpdatePortDto().setConfig(Map.of()).setPin(24));
                assertThat(relay.getPin()).isEqualTo(24);
                assertThat(sut.configOf(relay)).isEmpty();
                assertApi(() -> sut.update(ORG_ID, 2, new UpdatePortDto().setPin(3)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_I2C_PIN_RESERVED, "pin", 3);
            }
        }

        @Nested
        @DisplayName("moving head rules (contract v3)")
        class MovingHeadRules {

            @Test
            @DisplayName("persists a head with profile and address, without pin")
            void should_persistHead() {
                Port head = sut.create(ORG_ID, headDto("Foco", 1));
                assertThat(head.getKind()).isEqualTo(OutputKind.MOVING_HEAD);
                assertThat(head.getPin()).isNull();
                assertThat(sut.configOf(head)).containsEntry("address", 1);
                assertApi(() -> sut.create(ORG_ID, headDto("Foco2", 16).setPin(5)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_NOT_ALLOWED, "kind", "moving_head");
            }

            @Test
            @DisplayName("the fixture must fit the 512-channel universe")
            void should_rejectOutOfUniverse() {
                assertThatThrownBy(() -> sut.create(ORG_ID, headDto("Foco", 500)))
                        .isInstanceOfSatisfying(ApiException.class, ex -> {
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_DMX_ADDRESS_RANGE);
                            assertThat(ex.getParams()).containsEntry("address", 500).containsEntry("channels", 15).containsEntry("last", 514);
                        });
                assertThat(sut.create(ORG_ID, headDto("Foco", 498)).getName()).isEqualTo("Foco");
            }

            @Test
            @DisplayName("two heads cannot overlap channels")
            void should_rejectOverlap() {
                existingPorts(headPort(1, "Foco", 1));
                assertApi(() -> sut.create(ORG_ID, headDto("Foco2", 15)), HttpStatus.CONFLICT, ErrorCodes.PORTS_DMX_ADDRESS_CONFLICT, "port", "Foco");
                assertThat(sut.create(ORG_ID, headDto("Foco2", 16)).getName()).isEqualTo("Foco2");
                Port second = headPort(2, "Foco2", 16);
                existingPorts(headPort(1, "Foco", 1), second);
                Map<String, Object> moved = new LinkedHashMap<>(sut.configOf(second));
                moved.put("address", 10);
                assertApi(() -> sut.update(ORG_ID, 2, new UpdatePortDto().setConfig(moved)), HttpStatus.CONFLICT, ErrorCodes.PORTS_DMX_ADDRESS_CONFLICT, "port", "Foco");
                moved.put("address", 31);
                sut.update(ORG_ID, 2, new UpdatePortDto().setConfig(moved));
                assertThat(sut.configOf(second)).containsEntry("address", 31);
            }
        }

        @Nested
        @DisplayName("name rules")
        class NameRules {

            @Test
            @DisplayName("rejects a blank name with ports.name_required")
            void should_rejectBlankName() {
                assertApi(() -> sut.create(ORG_ID, relayDto("   ", 27)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_NAME_REQUIRED);
                assertApi(() -> sut.create(ORG_ID, relayDto(null, 27)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_NAME_REQUIRED);
            }

            @Test
            @DisplayName("rejects a duplicate name with ports.name_conflict")
            void should_rejectDuplicateName() {
                existingPorts(relayPort(1, "Focos", 27));
                assertApi(() -> sut.create(ORG_ID, relayDto("Focos", 26)), HttpStatus.CONFLICT, ErrorCodes.PORTS_NAME_CONFLICT);
            }

            @Test
            @DisplayName("trims the name and rejects names over 160 characters")
            void should_trimAndBoundName() {
                assertThat(sut.create(ORG_ID, relayDto("  Focos ", 27)).getName()).isEqualTo("Focos");
                assertThatThrownBy(() -> sut.create(ORG_ID, relayDto("x".repeat(161), 27)))
                        .isInstanceOf(ResponseStatusException.class);
            }
        }

        @Nested
        @DisplayName("segment rules")
        class SegmentRules {

            @Test
            @DisplayName("rejects a strip that does not exist with ports.segment_strip_not_found")
            void should_rejectMissingStrip() {
                assertApi(() -> sut.create(ORG_ID, segmentDto("Fondo1", "LedStrip9", 0, 10)),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_SEGMENT_STRIP_NOT_FOUND, "strip", "LedStrip9");
            }

            @Test
            @DisplayName("rejects a port that exists but is not a strip")
            void should_rejectNonStripReference() {
                existingPorts(relayPort(1, "Focos", 27));
                assertApi(() -> sut.create(ORG_ID, segmentDto("Fondo1", "Focos", 0, 10)),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_SEGMENT_STRIP_NOT_FOUND, "strip", "Focos");
            }

            @Test
            @DisplayName("rejects a range outside the strip with ports.segment_out_of_range")
            void should_rejectOutOfRange() {
                existingPorts(stripPort(1, "LedStrip4", 10, 44));
                assertThatThrownBy(() -> sut.create(ORG_ID, segmentDto("CasaDoble", "LedStrip4", 16, 44)))
                        .isInstanceOfSatisfying(ApiException.class, ex -> {
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_SEGMENT_OUT_OF_RANGE);
                            assertThat(ex.getParams()).containsEntry("first", 16).containsEntry("last", 44).containsEntry("max", 43);
                        });
            }

            @Test
            @DisplayName("accepts the last LED of the strip and reversed ranges")
            void should_acceptBoundaryAndReversed() {
                existingPorts(stripPort(1, "LedStrip3", 13, 300));
                assertThat(sut.create(ORG_ID, segmentDto("Fondo1", "LedStrip3", 0, 299)).getName()).isEqualTo("Fondo1");
                assertThat(sut.configOf(sut.create(ORG_ID, segmentDto("Fondo2", "LedStrip3", 298, 149)))).containsEntry("first", 298);
            }
        }

        @Nested
        @DisplayName("config rules")
        class ConfigRules {

            @Test
            @DisplayName("delegates config validation to PortConfigValidator")
            void should_delegateConfigValidation() {
                assertApi(() -> sut.create(ORG_ID, relayDto("Focos", 27).setConfig(Map.of("dma", 10))),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_CONFIG_UNKNOWN_KEY, "key", "dma");
                assertApi(() -> sut.create(ORG_ID, stripDto("LedStrip3", 13, 10, 1, 0)),
                        HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE, "key", "length");
            }
        }

        @Nested
        @DisplayName("legacy parameters")
        class LegacyParameters {

            @Test
            @DisplayName("rejects duplicated parameter names")
            void should_rejectDuplicateNames() {
                CreatePortDto dto = relayDto("Focos", 27).setParameters(List.of(
                        new ActionParameterDto().setName("a").setType(AttributeType.INTEGER).setDynamic(true),
                        new ActionParameterDto().setName("a").setType(AttributeType.INTEGER).setDynamic(true)));
                assertThatThrownBy(() -> sut.create(ORG_ID, dto)).isInstanceOf(ResponseStatusException.class);
            }

            @Test
            @DisplayName("rejects a parameter without type and an invalid technical name")
            void should_rejectBadParameters() {
                assertThatThrownBy(() -> sut.create(ORG_ID, relayDto("Focos", 27).setParameters(List.of(
                        new ActionParameterDto().setName("a"))))).isInstanceOf(ResponseStatusException.class);
                assertThatThrownBy(() -> sut.create(ORG_ID, relayDto("Focos", 27).setParameters(List.of(
                        new ActionParameterDto().setName("Bad Name").setType(AttributeType.INTEGER)))))
                        .isInstanceOf(ApiException.class)
                        .extracting(e -> ((ApiException) e).getCode())
                        .isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_NAME_INVALID);
            }

            @Test
            @DisplayName("accepts controller-style parameter names with inner capitals")
            void should_acceptCamelCaseParameterName() {
                Port port = sut.create(ORG_ID, relayDto("Focos", 27).setParameters(List.of(
                        new ActionParameterDto().setName("holdTime").setType(AttributeType.INTEGER).setDynamic(true))));
                assertThat(port.getParametersJson()).contains("holdTime");
            }

            @Test
            @DisplayName("requires a static value for a required static parameter")
            void should_requireStaticValue() {
                assertThatThrownBy(() -> sut.create(ORG_ID, relayDto("Focos", 27).setParameters(List.of(
                        new ActionParameterDto().setName("a").setType(AttributeType.INTEGER).setRequired(true)))))
                        .isInstanceOf(ResponseStatusException.class);
            }
        }
    }

    // ================================================================== update

    @Nested
    @DisplayName("motors and sensors (contract v3)")
    class MotorsAndSensors {

        @Test
        @DisplayName("persists a native sensor and one on an expander channel, both without piece type")
        void should_persistSensors() {
            Port gpio = sut.create(ORG_ID, sensorDto("Seta", 23));
            assertThat(gpio.getKind()).isEqualTo(OutputKind.SENSOR);
            assertThat(gpio.getPin()).isEqualTo(23);
            assertThat(gpio.getPieceTypeId()).isNull();
            existingPorts(expanderPort(1, "exp1", 32));
            Port onExpander = sut.create(ORG_ID, expanderSensorDto("PortaCaixa", "exp1", 15));
            assertThat(onExpander.getPin()).isNull();
            assertThat(sut.configOf(onExpander)).containsEntry("channel", 15);
        }

        @Test
        @DisplayName("a sensor takes a pin or an expander, never both nor neither")
        void should_requireSensorPinXorExpander() {
            existingPorts(expanderPort(1, "exp1", 32));
            assertApi(() -> sut.create(ORG_ID, expanderSensorDto("PortaCaixa", "exp1", 15).setPin(23)),
                    HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_OR_EXPANDER, "reason", "both");
            assertApi(() -> sut.create(ORG_ID, sensorDto("Seta", null)),
                    HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_OR_EXPANDER, "reason", "none");
        }

        @Test
        @DisplayName("a sensor pin collides with motor pins like any other GPIO")
        void should_checkSensorPins() {
            existingPorts(motorPort(1, "Angel", 27, 17));
            assertApi(() -> sut.create(ORG_ID, sensorDto("AngelDalt", 17)), HttpStatus.CONFLICT, ErrorCodes.PORTS_PIN_CONFLICT);
            assertThat(sut.create(ORG_ID, sensorDto("AngelDalt", 22)).getPin()).isEqualTo(22);
        }

        @Test
        @DisplayName("a motor on an expander has no pin and occupies two channels")
        void should_persistExpanderMotor() {
            existingPorts(expanderPort(1, "exp1", 32));
            Port motor = sut.create(ORG_ID, motorDto("Noria", expanderMotorConfig("exp1", 6, 7)));
            assertThat(motor.getPin()).isNull();
            assertThat(sut.configOf(motor)).containsEntry("channelForward", 6).containsEntry("channelReverse", 7);
        }

        @Test
        @DisplayName("relays, motors and sensors share the 16 channels of an expander")
        void should_shareExpanderChannels() {
            existingPorts(expanderPort(1, "exp1", 32), motorPort(2, "Noria", expanderMotorConfig("exp1", 6, 7)));
            assertApi(() -> sut.create(ORG_ID, expanderRelayDto("Estable", "exp1", 7)),
                    HttpStatus.CONFLICT, ErrorCodes.PORTS_EXPANDER_CHANNEL_CONFLICT, "port", "Noria");
            assertApi(() -> sut.create(ORG_ID, expanderSensorDto("PortaCaixa", "exp1", 6)),
                    HttpStatus.CONFLICT, ErrorCodes.PORTS_EXPANDER_CHANNEL_CONFLICT, "port", "Noria");
            existingPorts(expanderPort(1, "exp1", 32), expanderSensorPort(2, "PortaCaixa", "exp1", 15));
            assertApi(() -> sut.create(ORG_ID, motorDto("Riu", expanderMotorConfig("exp1", 14, 15))),
                    HttpStatus.CONFLICT, ErrorCodes.PORTS_EXPANDER_CHANNEL_CONFLICT, "port", "PortaCaixa");
            assertThat(sut.create(ORG_ID, motorDto("Riu", expanderMotorConfig("exp1", 6, 7))).getName()).isEqualTo("Riu");
        }

        @Test
        @DisplayName("an expander used by a motor or a sensor cannot be deleted")
        void should_protectExpanderUsedByMotorOrSensor() {
            existingPorts(expanderPort(1, "exp1", 32), motorPort(2, "Noria", expanderMotorConfig("exp1", 6, 7)),
                    expanderSensorPort(3, "PortaCaixa", "exp1", 15));
            assertApi(() -> sut.softDelete(ORG_ID, 1), HttpStatus.CONFLICT, ErrorCodes.PORTS_EXPANDER_IN_USE, "relays", "Noria, PortaCaixa");
        }

        @Test
        @DisplayName("renaming an expander cascades to motors and sensors on it")
        void should_cascadeExpanderRenameToMotorsAndSensors() {
            Port motor = motorPort(2, "Noria", expanderMotorConfig("exp1", 6, 7));
            Port sensor = expanderSensorPort(3, "PortaCaixa", "exp1", 15);
            existingPorts(expanderPort(1, "exp1", 32), motor, sensor);
            sut.update(ORG_ID, 1, new UpdatePortDto().setName("expansor"));
            assertThat(sut.configOf(motor)).containsEntry("expander", "expansor");
            assertThat(sut.configOf(sensor)).containsEntry("expander", "expansor");
        }

        @Test
        @DisplayName("a motor limit must name a sensor port of the organization")
        void should_requireExistingLimitSensor() {
            existingPorts(relayPort(1, "Focos", 26), sensorPort(2, "AngelDalt", 22));
            Map<String, Object> missing = motorConfig(27, 17);
            missing.put("limitForward", "AngelBaix");
            assertThatThrownBy(() -> sut.create(ORG_ID, motorDto("Angel", missing)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_SENSOR_NOT_FOUND);
                        assertThat(ex.getParams()).containsEntry("key", "limitForward").containsEntry("sensor", "AngelBaix");
                    });
            Map<String, Object> notASensor = motorConfig(27, 17);
            notASensor.put("limitReverse", "Focos");
            assertApi(() -> sut.create(ORG_ID, motorDto("Angel", notASensor)), HttpStatus.BAD_REQUEST,
                    ErrorCodes.PORTS_SENSOR_NOT_FOUND, "key", "limitReverse");
            Map<String, Object> ok = motorConfig(27, 17);
            ok.put("limitForward", "AngelDalt");
            ok.put("lockOnTimeout", true);
            assertThat(sut.configOf(sut.create(ORG_ID, motorDto("Angel", ok)))).containsEntry("limitForward", "AngelDalt");
        }

        @Test
        @DisplayName("an update re-checks the motor limits")
        void should_recheckLimitsOnUpdate() {
            existingPorts(motorPort(1, "Angel", 27, 17));
            Map<String, Object> cfg = motorConfig(27, 17);
            cfg.put("limitForward", "Fantasma");
            assertApi(() -> sut.update(ORG_ID, 1, new UpdatePortDto().setConfig(cfg)), HttpStatus.BAD_REQUEST,
                    ErrorCodes.PORTS_SENSOR_NOT_FOUND, "sensor", "Fantasma");
        }

        @Test
        @DisplayName("deleting a sensor used as a limit is refused; an unused one is deleted")
        void should_protectSensorInUse() {
            Map<String, Object> cfg = motorConfig(27, 17);
            cfg.put("limitForward", "AngelDalt");
            Port used = sensorPort(2, "AngelDalt", 22);
            Port unused = sensorPort(3, "Seta", 23);
            existingPorts(motorPort(1, "Angel", cfg), used, unused);
            assertApi(() -> sut.softDelete(ORG_ID, 2), HttpStatus.CONFLICT, ErrorCodes.PORTS_SENSOR_IN_USE, "motors", "Angel");
            assertThat(used.getDeletedAt()).isNull();
            sut.softDelete(ORG_ID, 3);
            assertThat(unused.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("renaming a sensor cascades to both limits of every motor using it")
        void should_cascadeSensorRename() {
            Map<String, Object> cfg = motorConfig(27, 17);
            cfg.put("limitForward", "Final");
            cfg.put("limitReverse", "Final");
            Port motor = motorPort(1, "Angel", cfg);
            Port other = motorPort(3, "Noria", motorConfig(5, 6));
            existingPorts(motor, sensorPort(2, "Final", 22), other);
            sut.update(ORG_ID, 2, new UpdatePortDto().setName("FinalDalt"));
            assertThat(sut.configOf(motor)).containsEntry("limitForward", "FinalDalt").containsEntry("limitReverse", "FinalDalt");
            assertThat(sut.configOf(other)).doesNotContainKey("limitForward");
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("renames the port after checking the new name is free")
        void should_rename() {
            existingPorts(relayPort(1, "Focos", 27));
            Port updated = sut.update(ORG_ID, 1, new UpdatePortDto().setName("Focus"));
            assertThat(updated.getName()).isEqualTo("Focus");
        }

        @Test
        @DisplayName("rejects a name held by another port with ports.name_conflict")
        void should_rejectTakenName() {
            existingPorts(relayPort(1, "Focos", 27), relayPort(2, "Sembra", 26));
            assertApi(() -> sut.update(ORG_ID, 1, new UpdatePortDto().setName("Sembra")), HttpStatus.CONFLICT, ErrorCodes.PORTS_NAME_CONFLICT);
        }

        @Test
        @DisplayName("accepts the current kind and rejects any other with ports.kind_immutable")
        void should_keepKindImmutable() {
            existingPorts(relayPort(1, "Focos", 27));
            assertThat(sut.update(ORG_ID, 1, new UpdatePortDto().setKind("relay")).getKind()).isEqualTo(OutputKind.RELAY);
            assertApi(() -> sut.update(ORG_ID, 1, new UpdatePortDto().setKind("led_strip")), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_KIND_IMMUTABLE);
            assertApi(() -> sut.update(ORG_ID, 1, new UpdatePortDto().setKind("laser")), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_KIND_INVALID);
        }

        @Test
        @DisplayName("renaming a strip rewrites the strip reference of its segments")
        void should_cascadeStripRename() {
            Port strip = stripPort(1, "LedStrip3", 13, 300);
            Port fondo1 = segmentPort(2, "Fondo1", "LedStrip3", 0, 148);
            Port other = segmentPort(3, "CasaSimple", "LedStrip4", 0, 15);
            existingPorts(strip, fondo1, other);

            sut.update(ORG_ID, 1, new UpdatePortDto().setName("TiraFons"));

            assertThat(sut.configOf(fondo1)).containsEntry("strip", "TiraFons");
            assertThat(sut.configOf(other)).containsEntry("strip", "LedStrip4");
            verify(portRepository).save(fondo1);
            verify(portRepository, never()).save(other);
        }

        @Test
        @DisplayName("changes the piece type only to one of the same kind")
        void should_checkPieceTypeKindOnChange() {
            existingPorts(relayPort(1, "Focos", 27));
            PieceType otherRelayType = new PieceType().setId(12).setKind(OutputKind.RELAY).setName("Llum");
            when(pieceTypeService.findInOrg(ORG_ID, 12)).thenReturn(otherRelayType);
            assertThat(sut.update(ORG_ID, 1, new UpdatePortDto().setPieceTypeId(12)).getPieceTypeId()).isEqualTo(12);
            assertApi(() -> sut.update(ORG_ID, 1, new UpdatePortDto().setPieceTypeId(SEGMENT_TYPE_ID)),
                    HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIECE_TYPE_KIND_MISMATCH);
        }

        @Test
        @DisplayName("changes the pin when free and rejects a used, reserved or invalid one")
        void should_changePin() {
            existingPorts(relayPort(1, "Focos", 27), relayPort(2, "Sembra", 26), motorPort(3, "Angel", 3, 4));
            assertThat(sut.update(ORG_ID, 1, new UpdatePortDto().setPin(22)).getPin()).isEqualTo(22);
            assertApi(() -> sut.update(ORG_ID, 1, new UpdatePortDto().setPin(26)), HttpStatus.CONFLICT, ErrorCodes.PORTS_PIN_CONFLICT);
            assertApi(() -> sut.update(ORG_ID, 1, new UpdatePortDto().setPin(4)), HttpStatus.CONFLICT, ErrorCodes.PORTS_PIN_CONFLICT);
            assertApi(() -> sut.update(ORG_ID, 1, new UpdatePortDto().setPin(0)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_RESERVED);
            assertApi(() -> sut.update(ORG_ID, 1, new UpdatePortDto().setPin(40)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_INVALID);
        }

        @Test
        @DisplayName("keeping the same pin is not a conflict with itself")
        void should_allowSamePin() {
            existingPorts(relayPort(1, "Focos", 27));
            assertThat(sut.update(ORG_ID, 1, new UpdatePortDto().setPin(27)).getPin()).isEqualTo(27);
        }

        @Test
        @DisplayName("rejects a pin on a pinless kind")
        void should_rejectPinOnSegment() {
            existingPorts(stripPort(1, "LedStrip3", 13, 300), segmentPort(2, "Fondo1", "LedStrip3", 0, 148));
            assertApi(() -> sut.update(ORG_ID, 2, new UpdatePortDto().setPin(5)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_NOT_ALLOWED);
        }

        @Test
        @DisplayName("replaces and re-validates the config")
        void should_replaceConfig() {
            existingPorts(stripPort(1, "LedStrip3", 13, 300), segmentPort(2, "Fondo1", "LedStrip3", 0, 148));
            Map<String, Object> cfg = new LinkedHashMap<>(Map.of("strip", "LedStrip3", "first", 10, "last", 20));
            assertThat(sut.configOf(sut.update(ORG_ID, 2, new UpdatePortDto().setConfig(cfg)))).containsEntry("first", 10);
            Map<String, Object> outOfRange = new LinkedHashMap<>(Map.of("strip", "LedStrip3", "first", 10, "last", 300));
            assertApi(() -> sut.update(ORG_ID, 2, new UpdatePortDto().setConfig(outOfRange)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_SEGMENT_OUT_OF_RANGE);
            Map<String, Object> badKey = new LinkedHashMap<>(Map.of("strip", "LedStrip3", "first", 10, "last", 20, "color", "red"));
            assertApi(() -> sut.update(ORG_ID, 2, new UpdatePortDto().setConfig(badKey)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_CONFIG_UNKNOWN_KEY);
        }

        @Test
        @DisplayName("a strip can grow but cannot shrink below one of its segments")
        void should_guardStripLength() {
            existingPorts(stripPort(1, "LedStrip3", 13, 300), segmentPort(2, "Fondo2", "LedStrip3", 298, 149));
            Map<String, Object> grow = new LinkedHashMap<>(Map.of("dma", 10, "channel", 1, "length", 400));
            assertThat(sut.configOf(sut.update(ORG_ID, 1, new UpdatePortDto().setConfig(grow)))).containsEntry("length", 400);
            Map<String, Object> shrink = new LinkedHashMap<>(Map.of("dma", 10, "channel", 1, "length", 200));
            assertThatThrownBy(() -> sut.update(ORG_ID, 1, new UpdatePortDto().setConfig(shrink)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_STRIP_SHRINK_BREAKS_SEGMENT);
                        assertThat(ex.getParams()).containsEntry("segment", "Fondo2").containsEntry("first", 298).containsEntry("last", 149);
                    });
        }

        @Test
        @DisplayName("re-checks the strip channel when the pin or the config change")
        void should_recheckStripChannel() {
            existingPorts(stripPort(1, "LedStrip3", 13, 300));
            assertApi(() -> sut.update(ORG_ID, 1, new UpdatePortDto().setPin(12)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_STRIP_CHANNEL_MISMATCH);
            Map<String, Object> cfg = new LinkedHashMap<>(Map.of("dma", 10, "channel", 0, "length", 300));
            assertApi(() -> sut.update(ORG_ID, 1, new UpdatePortDto().setConfig(cfg)), HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_STRIP_CHANNEL_MISMATCH);
            Map<String, Object> ok = new LinkedHashMap<>(Map.of("dma", 10, "channel", 0, "length", 300));
            assertThat(sut.update(ORG_ID, 1, new UpdatePortDto().setPin(12).setConfig(ok)).getPin()).isEqualTo(12);
        }

        @Test
        @DisplayName("a motor config change re-checks pin collisions")
        void should_recheckMotorPins() {
            existingPorts(relayPort(1, "Focos", 27), motorPort(2, "Angel", 3, 4));
            assertApi(() -> sut.update(ORG_ID, 2, new UpdatePortDto().setConfig(motorConfig(3, 27))), HttpStatus.CONFLICT, ErrorCodes.PORTS_PIN_CONFLICT);
            assertThat(sut.configOf(sut.update(ORG_ID, 2, new UpdatePortDto().setConfig(motorConfig(3, 5))))).containsEntry("pinReverse", 5);
        }

        @Test
        @DisplayName("replaces the legacy parameter list when provided")
        void should_replaceParameters() {
            existingPorts(relayPort(1, "Focos", 27));
            Port updated = sut.update(ORG_ID, 1, new UpdatePortDto().setParameters(List.of(
                    new ActionParameterDto().setName("hold").setType(AttributeType.DECIMAL).setDynamic(true))));
            assertThat(sut.parametersOf(updated)).extracting(ActionParameterDto::getName).containsExactly("hold");
        }

        @Test
        @DisplayName("leaves everything unchanged with an empty payload")
        void should_keepUnchanged() {
            Port port = relayPort(1, "Focos", 27);
            existingPorts(port);
            Port updated = sut.update(ORG_ID, 1, new UpdatePortDto());
            assertThat(updated.getName()).isEqualTo("Focos");
            assertThat(updated.getPin()).isEqualTo(27);
            assertThat(updated.getKind()).isEqualTo(OutputKind.RELAY);
        }

        @Test
        @DisplayName("throws 404 when the port is missing or belongs to another organization")
        void should_throwNotFound() {
            when(portRepository.findById(99)).thenReturn(Optional.empty());
            assertApi(() -> sut.update(ORG_ID, 99, new UpdatePortDto()), HttpStatus.NOT_FOUND, ErrorCodes.PORTS_NOT_FOUND);
            Port foreign = relayPort(5, "X", 5).setOrganization(new Organization().setId(2));
            when(portRepository.findById(5)).thenReturn(Optional.of(foreign));
            assertApi(() -> sut.update(ORG_ID, 5, new UpdatePortDto()), HttpStatus.NOT_FOUND, ErrorCodes.PORTS_NOT_FOUND);
        }
    }

    // ================================================================== softDelete

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {

        @Test
        @DisplayName("mangles the name, nulls the pin and stamps deletedAt to free both slots")
        void should_freeSlots() {
            Port port = relayPort(1, "Focos", 27);
            existingPorts(port);

            sut.softDelete(ORG_ID, 1);

            assertThat(port.getName()).isEqualTo("Focos::deleted::1");
            assertThat(port.getPin()).isNull();
            assertThat(port.getDeletedAt()).isNotNull();
            verify(portRepository).save(port);
        }

        @Test
        @DisplayName("refuses to delete a strip that still has segments with ports.strip_in_use")
        void should_refuseStripInUse() {
            Port strip = stripPort(1, "LedStrip3", 13, 300);
            existingPorts(strip, segmentPort(2, "Fondo1", "LedStrip3", 0, 148), segmentPort(3, "Fondo2", "LedStrip3", 298, 149));

            assertThatThrownBy(() -> sut.softDelete(ORG_ID, 1))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_STRIP_IN_USE);
                        assertThat(ex.getParams()).containsEntry("segments", "Fondo1, Fondo2");
                    });
            assertThat(strip.getDeletedAt()).isNull();
            verify(portRepository, never()).save(any());
        }

        @Test
        @DisplayName("deletes a strip without segments")
        void should_deleteUnusedStrip() {
            Port strip = stripPort(1, "LedStrip3", 13, 300);
            existingPorts(strip, segmentPort(2, "CasaSimple", "LedStrip4", 0, 15));
            sut.softDelete(ORG_ID, 1);
            assertThat(strip.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws 404 when the port does not exist")
        void should_throwNotFound() {
            when(portRepository.findById(99)).thenReturn(Optional.empty());
            assertApi(() -> sut.softDelete(ORG_ID, 99), HttpStatus.NOT_FOUND, ErrorCodes.PORTS_NOT_FOUND);
        }
    }

    // ================================================================== findInOrg / listAll / responses

    @Nested
    @DisplayName("findInOrg and listAll")
    class FindAndList {

        @Test
        @DisplayName("returns the port when it belongs to the organization")
        void should_returnOwnPort() {
            Port port = relayPort(1, "Focos", 27);
            existingPorts(port);
            assertThat(sut.findInOrg(ORG_ID, 1)).isSameAs(port);
        }

        @Test
        @DisplayName("throws 404 for another organization's port or a missing one")
        void should_throwNotFound() {
            when(portRepository.findById(5)).thenReturn(Optional.of(relayPort(5, "X", 5).setOrganization(new Organization().setId(2))));
            assertApi(() -> sut.findInOrg(ORG_ID, 5), HttpStatus.NOT_FOUND, ErrorCodes.PORTS_NOT_FOUND);
            when(portRepository.findById(99)).thenReturn(Optional.empty());
            assertApi(() -> sut.findInOrg(ORG_ID, 99), HttpStatus.NOT_FOUND, ErrorCodes.PORTS_NOT_FOUND);
        }

        @Test
        @DisplayName("lists the organization's ports in repository order")
        void should_listPorts() {
            Port a = relayPort(1, "Focos", 27);
            Port b = stripPort(2, "LedStrip3", 13, 300);
            existingPorts(a, b);
            assertThat(sut.listAll(ORG_ID)).containsExactly(a, b);
        }

        @Test
        @DisplayName("lists the segments that depend on a strip")
        void should_listSegmentsOfStrip() {
            List<Port> ports = new ArrayList<>(List.of(stripPort(1, "LedStrip3", 13, 300),
                    segmentPort(2, "Fondo1", "LedStrip3", 0, 148), segmentPort(3, "CasaSimple", "LedStrip4", 0, 15), relayPort(4, "Focos", 27)));
            existingPorts(ports.toArray(Port[]::new));
            assertThat(sut.segmentsOfStrip(org, "LedStrip3")).extracting(Port::getName).containsExactly("Fondo1");
            assertThat(sut.segmentsOfStrip(org, "LedStrip9")).isEmpty();
        }
    }

    @Nested
    @DisplayName("responses")
    class Responses {

        @Test
        @DisplayName("exposes the kind id, the config without pin and a null piece type for infrastructure")
        void should_buildResponse() {
            Port strip = stripPort(1, "LedStrip3", 13, 300);
            PortResponseDto dto = PortResponseDto.from(strip, sut.configOf(strip), sut.parametersOf(strip), null);
            assertThat(dto.kind()).isEqualTo("led_strip");
            assertThat(dto.pin()).isEqualTo(13);
            assertThat(dto.config()).containsEntry("length", 300).doesNotContainKey("pin");
            assertThat(dto.pieceTypeId()).isNull();
            assertThat(dto.pieceTypeName()).isNull();
            assertThat(dto.parameters()).isEmpty();
        }

        @Test
        @DisplayName("resolves the piece type name, or null when absent or deleted")
        void should_resolvePieceTypeName() {
            when(pieceTypeRepository.findById(RELAY_TYPE_ID)).thenReturn(Optional.of(relayType));
            when(pieceTypeRepository.findById(99)).thenReturn(Optional.empty());
            assertThat(sut.pieceTypeNameOf(RELAY_TYPE_ID)).isEqualTo("Figura amb relé");
            assertThat(sut.pieceTypeNameOf(99)).isNull();
            assertThat(sut.pieceTypeNameOf(null)).isNull();
        }

        @Test
        @DisplayName("configOf tolerates a null blob")
        void should_tolerateNullConfig() {
            assertThat(sut.configOf(new Port())).isEmpty();
        }

        @Test
        @DisplayName("the saved entity carries the serialized config, captured on save")
        void should_serializeConfigOnSave() {
            existingPorts(stripPort(1, "LedStrip3", 13, 300));
            sut.create(ORG_ID, segmentDto("Fondo1", "LedStrip3", 0, 148));
            ArgumentCaptor<Port> captor = ArgumentCaptor.forClass(Port.class);
            verify(portRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getConfigJson()).isEqualTo("{\"strip\":\"LedStrip3\",\"first\":0,\"last\":148}");
        }
    }
}
