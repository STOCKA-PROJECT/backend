package com.stocka.backend.modules.ports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
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
    private static final Integer PIECE_TYPE_ID = 5;

    @Mock private OrganizationService organizationService;
    @Mock private PortRepository portRepository;
    @Mock private PieceTypeService pieceTypeService;
    @Mock private PieceTypeRepository pieceTypeRepository;

    private PortService sut;
    private Organization org;
    private PieceType pieceType;

    @BeforeEach
    void setUp() {
        sut = new PortService(organizationService, portRepository, new ActionParametersJsonCodec(),
                pieceTypeService, pieceTypeRepository);
        org = new Organization().setId(ORG_ID);
        pieceType = new PieceType().setId(PIECE_TYPE_ID).setName("Tira Led");
    }

    private CreatePortDto tiraLed1() {
        return new CreatePortDto()
                .setName("Salida tira led 1")
                .setPieceTypeId(PIECE_TYPE_ID)
                .setPin(21)
                .setParameters(List.of(
                        new ActionParameterDto().setName("channel").setType(AttributeType.INTEGER).setRequired(true),
                        new ActionParameterDto().setName("dma").setType(AttributeType.INTEGER).setRequired(true)));
    }

    private Port existingPort(int id, String name, Integer pin) {
        return new Port().setId(id).setName(name).setPin(pin).setPieceTypeId(PIECE_TYPE_ID).setOrganization(org);
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("persists the port with the piece type, pin, serialized parameters and a sequential position")
        void should_persistPort() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(pieceTypeService.findInOrg(ORG_ID, PIECE_TYPE_ID)).thenReturn(pieceType);
            when(portRepository.findByOrganizationAndName(org, "Salida tira led 1")).thenReturn(Optional.empty());
            when(portRepository.findByOrganizationAndPin(org, 21)).thenReturn(Optional.empty());
            when(portRepository.findByOrganizationOrderByPositionAscIdAsc(org)).thenReturn(List.of());
            when(portRepository.saveAndFlush(any(Port.class))).thenAnswer(inv -> inv.getArgument(0));

            Port port = sut.create(ORG_ID, tiraLed1());

            assertThat(port.getName()).isEqualTo("Salida tira led 1");
            assertThat(port.getPieceTypeId()).isEqualTo(PIECE_TYPE_ID);
            assertThat(port.getPin()).isEqualTo(21);
            assertThat(port.getPosition()).isZero();
            assertThat(port.getOrganization()).isSameAs(org);

            List<ActionParameterDto> params = sut.parametersOf(port);
            assertThat(params).hasSize(2);
            assertThat(params.get(0).getName()).isEqualTo("channel");
            assertThat(params.get(0).getType()).isEqualTo(AttributeType.INTEGER);
            assertThat(params.get(0).getRequired()).isTrue();
            assertThat(params.get(0).getPosition()).isZero();
            assertThat(params.get(1).getName()).isEqualTo("dma");
            assertThat(params.get(1).getPosition()).isEqualTo(1);
        }

        @Test
        @DisplayName("assigns the position from the count of existing ports when not provided")
        void should_defaultPosition() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(pieceTypeService.findInOrg(ORG_ID, PIECE_TYPE_ID)).thenReturn(pieceType);
            when(portRepository.findByOrganizationAndName(org, "Salida tira led 1")).thenReturn(Optional.empty());
            when(portRepository.findByOrganizationAndPin(org, 21)).thenReturn(Optional.empty());
            when(portRepository.findByOrganizationOrderByPositionAscIdAsc(org))
                    .thenReturn(List.of(existingPort(1, "a", 1), existingPort(2, "b", 2)));
            when(portRepository.saveAndFlush(any(Port.class))).thenAnswer(inv -> inv.getArgument(0));

            Port port = sut.create(ORG_ID, tiraLed1());

            assertThat(port.getPosition()).isEqualTo(2);
        }

        @Test
        @DisplayName("rejects a blank name with ports.name_required")
        void should_rejectBlankName() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);

            assertThatThrownBy(() -> sut.create(ORG_ID, tiraLed1().setName("   ")))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_NAME_REQUIRED);
                    });
            verify(portRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects a null piece type with ports.piece_type_required")
        void should_rejectMissingPieceType() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);

            assertThatThrownBy(() -> sut.create(ORG_ID, tiraLed1().setPieceTypeId(null)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_PIECE_TYPE_REQUIRED);
                    });
            verify(portRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("propagates 404 when the referenced piece type does not exist in the organization")
        void should_rejectUnknownPieceType() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(pieceTypeService.findInOrg(ORG_ID, PIECE_TYPE_ID))
                    .thenThrow(new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.PIECE_TYPES_NOT_FOUND));

            assertThatThrownBy(() -> sut.create(ORG_ID, tiraLed1()))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
            verify(portRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects a null pin with ports.pin_invalid")
        void should_rejectNullPin() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);

            assertThatThrownBy(() -> sut.create(ORG_ID, tiraLed1().setPin(null)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_PIN_INVALID);
                    });
        }

        @Test
        @DisplayName("rejects a negative pin with ports.pin_invalid")
        void should_rejectNegativePin() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);

            assertThatThrownBy(() -> sut.create(ORG_ID, tiraLed1().setPin(-1)))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_PIN_INVALID));
        }

        @Test
        @DisplayName("rejects a duplicate name with ports.name_conflict")
        void should_rejectDuplicateName() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(pieceTypeService.findInOrg(ORG_ID, PIECE_TYPE_ID)).thenReturn(pieceType);
            when(portRepository.findByOrganizationAndName(org, "Salida tira led 1"))
                    .thenReturn(Optional.of(existingPort(99, "Salida tira led 1", 5)));

            assertThatThrownBy(() -> sut.create(ORG_ID, tiraLed1()))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_NAME_CONFLICT);
                    });
            verify(portRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects a duplicate pin with ports.pin_conflict")
        void should_rejectDuplicatePin() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(pieceTypeService.findInOrg(ORG_ID, PIECE_TYPE_ID)).thenReturn(pieceType);
            when(portRepository.findByOrganizationAndName(org, "Salida tira led 1")).thenReturn(Optional.empty());
            when(portRepository.findByOrganizationAndPin(org, 21))
                    .thenReturn(Optional.of(existingPort(99, "Otra salida", 21)));

            assertThatThrownBy(() -> sut.create(ORG_ID, tiraLed1()))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_PIN_CONFLICT);
                    });
            verify(portRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects duplicated parameter names")
        void should_rejectDuplicateParameterNames() {
            stubHappyLookups();

            CreatePortDto dto = tiraLed1().setParameters(List.of(
                    new ActionParameterDto().setName("channel").setType(AttributeType.INTEGER),
                    new ActionParameterDto().setName("channel").setType(AttributeType.INTEGER)));

            assertThatThrownBy(() -> sut.create(ORG_ID, dto))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("rejects a parameter without a type")
        void should_rejectParameterWithoutType() {
            stubHappyLookups();

            CreatePortDto dto = tiraLed1().setParameters(List.of(
                    new ActionParameterDto().setName("channel")));

            assertThatThrownBy(() -> sut.create(ORG_ID, dto))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("rejects an invalid technical parameter name")
        void should_rejectInvalidParameterName() {
            stubHappyLookups();

            CreatePortDto dto = tiraLed1().setParameters(List.of(
                    new ActionParameterDto().setName("Channel").setType(AttributeType.INTEGER)));

            assertThatThrownBy(() -> sut.create(ORG_ID, dto))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("defaults a parameter's required flag to true and its display name to the technical name")
        void should_defaultParameterFields() {
            stubHappyLookups();
            when(portRepository.saveAndFlush(any(Port.class))).thenAnswer(inv -> inv.getArgument(0));

            CreatePortDto dto = tiraLed1().setParameters(List.of(
                    new ActionParameterDto().setName("channel").setType(AttributeType.INTEGER)));

            Port port = sut.create(ORG_ID, dto);

            List<ActionParameterDto> params = sut.parametersOf(port);
            assertThat(params.get(0).getRequired()).isTrue();
            assertThat(params.get(0).getDisplayName()).isEqualTo("channel");
        }

        private void stubHappyLookups() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(pieceTypeService.findInOrg(ORG_ID, PIECE_TYPE_ID)).thenReturn(pieceType);
            when(portRepository.findByOrganizationAndName(org, "Salida tira led 1")).thenReturn(Optional.empty());
            when(portRepository.findByOrganizationAndPin(org, 21)).thenReturn(Optional.empty());
            when(portRepository.findByOrganizationOrderByPositionAscIdAsc(org)).thenReturn(List.of());
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("renames the port, freeing the old name slot")
        void should_rename() {
            Port port = existingPort(7, "Salida tira led 1", 21);
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.of(port));
            when(portRepository.findByOrganizationAndName(org, "Salida tira led 2")).thenReturn(Optional.empty());
            when(portRepository.saveAndFlush(any(Port.class))).thenAnswer(inv -> inv.getArgument(0));

            Port updated = sut.update(ORG_ID, 7, new UpdatePortDto().setName("Salida tira led 2"));

            assertThat(updated.getName()).isEqualTo("Salida tira led 2");
        }

        @Test
        @DisplayName("changes the related piece type when provided")
        void should_changePieceType() {
            Port port = existingPort(7, "Salida tira led 1", 21);
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.of(port));
            when(pieceTypeService.findInOrg(ORG_ID, 9)).thenReturn(new PieceType().setId(9).setName("Relé"));
            when(portRepository.saveAndFlush(any(Port.class))).thenAnswer(inv -> inv.getArgument(0));

            Port updated = sut.update(ORG_ID, 7, new UpdatePortDto().setPieceTypeId(9));

            assertThat(updated.getPieceTypeId()).isEqualTo(9);
        }

        @Test
        @DisplayName("changes the pin when there is no conflict")
        void should_changePin() {
            Port port = existingPort(7, "Salida tira led 1", 21);
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.of(port));
            when(portRepository.findByOrganizationAndPin(org, 22)).thenReturn(Optional.empty());
            when(portRepository.saveAndFlush(any(Port.class))).thenAnswer(inv -> inv.getArgument(0));

            Port updated = sut.update(ORG_ID, 7, new UpdatePortDto().setPin(22));

            assertThat(updated.getPin()).isEqualTo(22);
        }

        @Test
        @DisplayName("rejects a pin already used by another port with ports.pin_conflict")
        void should_rejectPinConflict() {
            Port port = existingPort(7, "Salida tira led 1", 21);
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.of(port));
            when(portRepository.findByOrganizationAndPin(org, 22))
                    .thenReturn(Optional.of(existingPort(8, "Otra salida", 22)));

            assertThatThrownBy(() -> sut.update(ORG_ID, 7, new UpdatePortDto().setPin(22)))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_PIN_CONFLICT));
        }

        @Test
        @DisplayName("rejects a name already used by another port with ports.name_conflict")
        void should_rejectNameConflict() {
            Port port = existingPort(7, "Salida tira led 1", 21);
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.of(port));
            when(portRepository.findByOrganizationAndName(org, "Otra salida"))
                    .thenReturn(Optional.of(existingPort(8, "Otra salida", 30)));

            assertThatThrownBy(() -> sut.update(ORG_ID, 7, new UpdatePortDto().setName("Otra salida")))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_NAME_CONFLICT));
        }

        @Test
        @DisplayName("replaces the parameter list when provided")
        void should_replaceParameters() {
            Port port = existingPort(7, "Salida tira led 1", 21);
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.of(port));
            when(portRepository.saveAndFlush(any(Port.class))).thenAnswer(inv -> inv.getArgument(0));

            Port updated = sut.update(ORG_ID, 7, new UpdatePortDto().setParameters(List.of(
                    new ActionParameterDto().setName("brightness").setType(AttributeType.DECIMAL))));

            List<ActionParameterDto> params = sut.parametersOf(updated);
            assertThat(params).hasSize(1);
            assertThat(params.get(0).getName()).isEqualTo("brightness");
            assertThat(params.get(0).getType()).isEqualTo(AttributeType.DECIMAL);
        }

        @Test
        @DisplayName("leaves all fields unchanged when the payload is empty")
        void should_noopOnEmptyPayload() {
            Port port = existingPort(7, "Salida tira led 1", 21);
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.of(port));
            when(portRepository.saveAndFlush(any(Port.class))).thenAnswer(inv -> inv.getArgument(0));

            Port updated = sut.update(ORG_ID, 7, new UpdatePortDto());

            assertThat(updated.getName()).isEqualTo("Salida tira led 1");
            assertThat(updated.getPin()).isEqualTo(21);
            assertThat(updated.getPieceTypeId()).isEqualTo(PIECE_TYPE_ID);
        }

        @Test
        @DisplayName("throws 404 when the port does not exist")
        void should_throw404() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.update(ORG_ID, 7, new UpdatePortDto().setName("x")))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {

        @Test
        @DisplayName("mangles the name, nulls the pin and stamps deletedAt to free both slots")
        void should_mangleNameNullPinStampDeletedAt() {
            Port port = existingPort(7, "Salida tira led 1", 21);
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.of(port));
            when(portRepository.save(any(Port.class))).thenAnswer(inv -> inv.getArgument(0));

            sut.softDelete(ORG_ID, 7);

            assertThat(port.getName()).isEqualTo("Salida tira led 1::deleted::7");
            assertThat(port.getPin()).isNull();
            assertThat(port.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws 404 when the port does not exist")
        void should_throw404() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.softDelete(ORG_ID, 7))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
            verify(portRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("findInOrg")
    class FindInOrg {

        @Test
        @DisplayName("returns the port when it belongs to the organization")
        void should_returnPort() {
            Port port = existingPort(7, "Salida tira led 1", 21);
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.of(port));

            assertThat(sut.findInOrg(ORG_ID, 7)).isSameAs(port);
        }

        @Test
        @DisplayName("throws 404 when the port belongs to another organization")
        void should_throw404_when_otherOrg() {
            Organization other = new Organization().setId(999);
            Port port = new Port().setId(7).setName("x").setPin(1).setPieceTypeId(PIECE_TYPE_ID).setOrganization(other);
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.of(port));

            assertThatThrownBy(() -> sut.findInOrg(ORG_ID, 7))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("throws 404 when the port is missing")
        void should_throw404_when_missing() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findById(7)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.findInOrg(ORG_ID, 7))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("listAll and response mapping")
    class ListAndResponse {

        @Test
        @DisplayName("listAll returns the organization's ports")
        void should_listAll() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            when(portRepository.findByOrganizationOrderByPositionAscIdAsc(org))
                    .thenReturn(List.of(existingPort(1, "a", 1), existingPort(2, "b", 2)));

            assertThat(sut.listAll(ORG_ID)).hasSize(2);
        }

        @Test
        @DisplayName("pieceTypeNameOf resolves the related piece type name, null when missing")
        void pieceTypeNameOf_resolvesName() {
            when(pieceTypeRepository.findById(PIECE_TYPE_ID)).thenReturn(Optional.of(pieceType));
            assertThat(sut.pieceTypeNameOf(PIECE_TYPE_ID)).isEqualTo("Tira Led");
            assertThat(sut.pieceTypeNameOf(null)).isNull();
        }

        @Test
        @DisplayName("PortResponseDto.from carries pin, piece type and the deserialized parameters")
        void responseDto_should_carryFields() {
            ActionParametersJsonCodec codec = new ActionParametersJsonCodec();
            Port port = existingPort(7, "Salida tira led 1", 21)
                    .setParametersJson(codec.serialize(List.of(
                            new ActionParameterDto().setName("channel").setType(AttributeType.INTEGER))));

            PortResponseDto dto = PortResponseDto.from(port, sut.parametersOf(port), "Tira Led");

            assertThat(dto.id()).isEqualTo(7);
            assertThat(dto.name()).isEqualTo("Salida tira led 1");
            assertThat(dto.pieceTypeId()).isEqualTo(PIECE_TYPE_ID);
            assertThat(dto.pieceTypeName()).isEqualTo("Tira Led");
            assertThat(dto.pin()).isEqualTo(21);
            assertThat(dto.parameters()).hasSize(1);
            assertThat(dto.parameters().get(0).getName()).isEqualTo("channel");
        }
    }
}
