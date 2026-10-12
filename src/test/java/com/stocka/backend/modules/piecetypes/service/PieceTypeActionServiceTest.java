package com.stocka.backend.modules.piecetypes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;

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
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeActionDto;
import com.stocka.backend.modules.piecetypes.dto.PieceTypeActionResponseDto;
import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeActionDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAction;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeActionRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PieceTypeActionService")
class PieceTypeActionServiceTest {

    private static final Integer ORG_ID = 1;
    private static final Integer TYPE_ID = 5;

    @Mock private PieceTypeService pieceTypeService;
    @Mock private PieceTypeActionRepository actionRepository;

    private PieceTypeActionService sut;
    private PieceType type;

    @BeforeEach
    void setUp() {
        ActionParametersJsonCodec codec = new ActionParametersJsonCodec();
        sut = new PieceTypeActionService(pieceTypeService, actionRepository, codec,
                new ActionCatalogValidator(new OutputCatalog()));
        type = new PieceType().setId(TYPE_ID).setName("Pieza Movimiento");
    }

    private CreatePieceTypeActionDto encenderWithTiempo() {
        return new CreatePieceTypeActionDto()
                .setName("encender")
                .setDisplayName("Encender")
                .setParameters(List.of(new ActionParameterDto()
                        .setName("tiempo")
                        .setDisplayName("Tiempo")
                        .setType(AttributeType.INTEGER)
                        .setRequired(true)
                        // dynamic: filled per clip in the timeline, so it needs no static value.
                        .setDynamic(true)));
    }

    @Test
    @DisplayName("create should persist the action with serialized parameters and a sequential position")
    void create_should_persistActionWithSerializedParameters() {
        when(pieceTypeService.findInOrg(ORG_ID, TYPE_ID)).thenReturn(type);
        when(actionRepository.findByPieceTypeAndName(type, "encender")).thenReturn(java.util.Optional.empty());
        when(actionRepository.findByPieceTypeOrderByPositionAscIdAsc(type)).thenReturn(List.of());
        when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

        PieceTypeAction action = sut.create(ORG_ID, TYPE_ID, encenderWithTiempo());

        assertThat(action.getName()).isEqualTo("encender");
        assertThat(action.getDisplayName()).isEqualTo("Encender");
        assertThat(action.getPosition()).isZero();
        assertThat(action.getPieceType()).isSameAs(type);

        List<ActionParameterDto> params = sut.parametersOf(action);
        assertThat(params).hasSize(1);
        assertThat(params.get(0).getName()).isEqualTo("tiempo");
        assertThat(params.get(0).getType()).isEqualTo(AttributeType.INTEGER);
        assertThat(params.get(0).getRequired()).isTrue();
        assertThat(params.get(0).getPosition()).isZero();
    }

    @Test
    @DisplayName("create should reject an invalid technical action name with its own error code")
    void create_should_rejectInvalidName() {
        when(pieceTypeService.findInOrg(ORG_ID, TYPE_ID)).thenReturn(type);
        CreatePieceTypeActionDto dto = encenderWithTiempo().setName("Encender");

        assertThatThrownBy(() -> sut.create(ORG_ID, TYPE_ID, dto))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_NAME_INVALID);
    }

    @Test
    @DisplayName("create should reject an invalid parameter name with the same error code")
    void create_should_rejectInvalidParameterName() {
        when(pieceTypeService.findInOrg(ORG_ID, TYPE_ID)).thenReturn(type);
        CreatePieceTypeActionDto dto = encenderWithTiempo();
        dto.getParameters().get(0).setName("Tiempo");

        assertThatThrownBy(() -> sut.create(ORG_ID, TYPE_ID, dto))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_NAME_INVALID);
    }

    @Nested
    @DisplayName("technical names of actions and parameters")
    class ActionNames {

        @ParameterizedTest(name = "accepts \"{0}\"")
        @ValueSource(strings = {"focoOn", "focoOff", "setBrightness", "hold_time", "animation_type", "a", "a1_B2"})
        void should_acceptControllerStyleNames(String name) {
            assertThatCode(() -> PieceTypeService.validateActionName(name)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts the 80-character maximum and rejects 81")
        void should_enforceMaxLength() {
            String max = "a" + "B".repeat(79);
            assertThatCode(() -> PieceTypeService.validateActionName(max)).doesNotThrowAnyException();
            assertThatThrownBy(() -> PieceTypeService.validateActionName(max + "c")).isInstanceOf(ApiException.class);
        }

        @ParameterizedTest(name = "rejects \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "Encender", "1abc", "_abc", "a-b", "foco On", "focoOn ", "ñandu", "set.brightness"})
        void should_rejectMalformedNames(String name) {
            assertThatThrownBy(() -> PieceTypeService.validateActionName(name))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode())
                    .isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_NAME_INVALID);
        }

        @Test
        @DisplayName("attribute names keep the strict lowercase rule")
        void should_keepAttributeRuleStrict() {
            assertThatThrownBy(() -> PieceTypeService.validateAttributeName("focoOn"))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    @DisplayName("create should reject duplicated parameter names")
    void create_should_rejectDuplicateParameterNames() {
        when(pieceTypeService.findInOrg(ORG_ID, TYPE_ID)).thenReturn(type);
        when(actionRepository.findByPieceTypeAndName(type, "encender")).thenReturn(java.util.Optional.empty());
        when(actionRepository.findByPieceTypeOrderByPositionAscIdAsc(type)).thenReturn(List.of());

        CreatePieceTypeActionDto dto = new CreatePieceTypeActionDto()
                .setName("encender")
                .setParameters(List.of(
                        new ActionParameterDto().setName("tiempo").setType(AttributeType.INTEGER).setDynamic(true),
                        new ActionParameterDto().setName("tiempo").setType(AttributeType.INTEGER).setDynamic(true)));

        assertThatThrownBy(() -> sut.create(ORG_ID, TYPE_ID, dto))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("create should reject a parameter without a type")
    void create_should_rejectParameterWithoutType() {
        when(pieceTypeService.findInOrg(ORG_ID, TYPE_ID)).thenReturn(type);
        when(actionRepository.findByPieceTypeAndName(type, "encender")).thenReturn(java.util.Optional.empty());
        when(actionRepository.findByPieceTypeOrderByPositionAscIdAsc(type)).thenReturn(List.of());

        CreatePieceTypeActionDto dto = new CreatePieceTypeActionDto()
                .setName("encender")
                .setParameters(List.of(new ActionParameterDto().setName("tiempo")));

        assertThatThrownBy(() -> sut.create(ORG_ID, TYPE_ID, dto))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("create should reject a name already used by another action in the type")
    void create_should_rejectDuplicateActionName() {
        when(pieceTypeService.findInOrg(ORG_ID, TYPE_ID)).thenReturn(type);
        when(actionRepository.findByPieceTypeAndName(type, "encender"))
                .thenReturn(java.util.Optional.of(new PieceTypeAction().setId(99).setName("encender")));

        assertThatThrownBy(() -> sut.create(ORG_ID, TYPE_ID, encenderWithTiempo()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("update should replace the parameter list when provided")
    void update_should_replaceParameters() {
        PieceTypeAction existing = new PieceTypeAction().setId(9).setName("encender").setPieceType(type);
        when(pieceTypeService.findInOrg(ORG_ID, TYPE_ID)).thenReturn(type);
        when(actionRepository.findById(9)).thenReturn(java.util.Optional.of(existing));
        when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdatePieceTypeActionDto dto = new UpdatePieceTypeActionDto()
                .setParameters(List.of(new ActionParameterDto()
                        .setName("intensidad").setType(AttributeType.DECIMAL).setDynamic(true)));

        PieceTypeAction updated = sut.update(ORG_ID, TYPE_ID, 9, dto);

        List<ActionParameterDto> params = sut.parametersOf(updated);
        assertThat(params).hasSize(1);
        assertThat(params.get(0).getName()).isEqualTo("intensidad");
        assertThat(params.get(0).getType()).isEqualTo(AttributeType.DECIMAL);
    }

    @Test
    @DisplayName("softDelete should mangle the technical name and stamp deletedAt")
    void softDelete_should_mangleNameAndStampDeletedAt() {
        PieceTypeAction existing = new PieceTypeAction().setId(9).setName("encender").setPieceType(type);
        when(pieceTypeService.findInOrg(ORG_ID, TYPE_ID)).thenReturn(type);
        when(actionRepository.findById(9)).thenReturn(java.util.Optional.of(existing));
        when(actionRepository.save(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

        sut.softDelete(ORG_ID, TYPE_ID, 9);

        assertThat(existing.getDeletedAt()).isNotNull();
        assertThat(existing.getName()).contains("::deleted::9");
    }

    @Test
    @DisplayName("the response DTO carries the deserialized parameters")
    void responseDto_should_carryParameters() {
        when(pieceTypeService.findInOrg(ORG_ID, TYPE_ID)).thenReturn(type);
        when(actionRepository.findByPieceTypeAndName(type, "encender")).thenReturn(java.util.Optional.empty());
        when(actionRepository.findByPieceTypeOrderByPositionAscIdAsc(type)).thenReturn(List.of());
        when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

        PieceTypeAction action = sut.create(ORG_ID, TYPE_ID, encenderWithTiempo());
        PieceTypeActionResponseDto dto = PieceTypeActionResponseDto.from(action, sut.parametersOf(action));

        assertThat(dto.name()).isEqualTo("encender");
        assertThat(dto.parameters()).hasSize(1);
        assertThat(dto.parameters().get(0).getName()).isEqualTo("tiempo");
    }

    /**
     * Exercises the per-parameter <b>static vs dynamic</b> binding: a static parameter fixes a value
     * for every piece of the type, a dynamic one defers it to the timeline editor.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("parameter binding (static / dynamic)")
    class ParameterBinding {

        /** Stubs the lookups every {@code create} performs before it serializes parameters. */
        private void stubCreateLookups() {
            when(pieceTypeService.findInOrg(ORG_ID, TYPE_ID)).thenReturn(type);
            when(actionRepository.findByPieceTypeAndName(type, "encender")).thenReturn(java.util.Optional.empty());
            when(actionRepository.findByPieceTypeOrderByPositionAscIdAsc(type)).thenReturn(List.of());
        }

        private CreatePieceTypeActionDto encenderWith(ActionParameterDto param) {
            return new CreatePieceTypeActionDto()
                    .setName("encender")
                    .setDisplayName("Encender")
                    .setParameters(List.of(param));
        }

        @Test
        @DisplayName("a parameter defaults to static (dynamic=false) when the flag is omitted")
        void create_should_defaultToStatic() {
            stubCreateLookups();
            when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

            // Optional + no dynamic flag + no value: stays static, value absent.
            ActionParameterDto param = new ActionParameterDto()
                    .setName("intensidad").setType(AttributeType.INTEGER).setRequired(false);

            PieceTypeAction action = sut.create(ORG_ID, TYPE_ID, encenderWith(param));

            ActionParameterDto stored = sut.parametersOf(action).get(0);
            assertThat(stored.getDynamic()).isFalse();
            assertThat(stored.getStaticValue()).isNull();
        }

        @Test
        @DisplayName("a static parameter keeps its trimmed static value")
        void create_should_keepStaticValue() {
            stubCreateLookups();
            when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

            ActionParameterDto param = new ActionParameterDto()
                    .setName("intensidad").setType(AttributeType.INTEGER)
                    .setRequired(true).setDynamic(false).setStaticValue("  80  ");

            PieceTypeAction action = sut.create(ORG_ID, TYPE_ID, encenderWith(param));

            ActionParameterDto stored = sut.parametersOf(action).get(0);
            assertThat(stored.getDynamic()).isFalse();
            assertThat(stored.getStaticValue()).isEqualTo("80");
        }

        @Test
        @DisplayName("a dynamic parameter has its static value cleared even when one is sent")
        void create_should_clearStaticValueWhenDynamic() {
            stubCreateLookups();
            when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

            ActionParameterDto param = new ActionParameterDto()
                    .setName("intensidad").setType(AttributeType.INTEGER)
                    .setRequired(true).setDynamic(true).setStaticValue("80");

            PieceTypeAction action = sut.create(ORG_ID, TYPE_ID, encenderWith(param));

            ActionParameterDto stored = sut.parametersOf(action).get(0);
            assertThat(stored.getDynamic()).isTrue();
            assertThat(stored.getStaticValue()).isNull();
        }

        @Test
        @DisplayName("a blank static value is treated as absent")
        void create_should_treatBlankStaticValueAsAbsent() {
            stubCreateLookups();
            when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

            ActionParameterDto param = new ActionParameterDto()
                    .setName("nota").setType(AttributeType.TEXT)
                    .setRequired(false).setDynamic(false).setStaticValue("   ");

            PieceTypeAction action = sut.create(ORG_ID, TYPE_ID, encenderWith(param));

            assertThat(sut.parametersOf(action).get(0).getStaticValue()).isNull();
        }

        @Test
        @DisplayName("a required static parameter without a value is rejected")
        void create_should_rejectRequiredStaticWithoutValue() {
            stubCreateLookups();

            ActionParameterDto param = new ActionParameterDto()
                    .setName("intensidad").setType(AttributeType.INTEGER)
                    .setRequired(true).setDynamic(false);

            assertThatThrownBy(() -> sut.create(ORG_ID, TYPE_ID, encenderWith(param)))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("a required dynamic parameter without a value is allowed (filled in the timeline)")
        void create_should_allowRequiredDynamicWithoutValue() {
            stubCreateLookups();
            when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

            ActionParameterDto param = new ActionParameterDto()
                    .setName("intensidad").setType(AttributeType.INTEGER)
                    .setRequired(true).setDynamic(true);

            PieceTypeAction action = sut.create(ORG_ID, TYPE_ID, encenderWith(param));

            ActionParameterDto stored = sut.parametersOf(action).get(0);
            assertThat(stored.getDynamic()).isTrue();
            assertThat(stored.getStaticValue()).isNull();
        }

        @Test
        @DisplayName("an optional static parameter without a value is allowed")
        void create_should_allowOptionalStaticWithoutValue() {
            stubCreateLookups();
            when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

            ActionParameterDto param = new ActionParameterDto()
                    .setName("nota").setType(AttributeType.TEXT)
                    .setRequired(false).setDynamic(false);

            PieceTypeAction action = sut.create(ORG_ID, TYPE_ID, encenderWith(param));

            ActionParameterDto stored = sut.parametersOf(action).get(0);
            assertThat(stored.getDynamic()).isFalse();
            assertThat(stored.getStaticValue()).isNull();
            assertThat(stored.getRequired()).isFalse();
        }
    }

    /**
     * Exercises the <b>duration</b> flag: the single numeric parameter whose value drives the clip
     * length on the timeline, so the clip never carries a second, independent time.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("duration parameter")
    class DurationParameter {

        private void stubCreateLookups() {
            when(pieceTypeService.findInOrg(ORG_ID, TYPE_ID)).thenReturn(type);
            when(actionRepository.findByPieceTypeAndName(type, "encender")).thenReturn(java.util.Optional.empty());
            when(actionRepository.findByPieceTypeOrderByPositionAscIdAsc(type)).thenReturn(List.of());
        }

        private CreatePieceTypeActionDto encenderWith(ActionParameterDto... params) {
            return new CreatePieceTypeActionDto()
                    .setName("encender")
                    .setDisplayName("Encender")
                    .setParameters(List.of(params));
        }

        @Test
        @DisplayName("a dynamic numeric duration parameter is accepted")
        void create_should_acceptDynamicDuration() {
            stubCreateLookups();
            when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

            ActionParameterDto param = new ActionParameterDto()
                    .setName("tiempo").setType(AttributeType.INTEGER)
                    .setDynamic(true).setIsDuration(true);

            PieceTypeAction action = sut.create(ORG_ID, TYPE_ID, encenderWith(param));

            ActionParameterDto stored = sut.parametersOf(action).get(0);
            assertThat(stored.getIsDuration()).isTrue();
            assertThat(stored.getDynamic()).isTrue();
        }

        @Test
        @DisplayName("a static duration parameter keeps its fixed length value")
        void create_should_acceptStaticDurationWithValue() {
            stubCreateLookups();
            when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

            ActionParameterDto param = new ActionParameterDto()
                    .setName("tiempo").setType(AttributeType.DECIMAL)
                    .setDynamic(false).setIsDuration(true).setStaticValue("2.5");

            PieceTypeAction action = sut.create(ORG_ID, TYPE_ID, encenderWith(param));

            ActionParameterDto stored = sut.parametersOf(action).get(0);
            assertThat(stored.getIsDuration()).isTrue();
            assertThat(stored.getStaticValue()).isEqualTo("2.5");
        }

        @Test
        @DisplayName("a non-numeric duration parameter is rejected")
        void create_should_rejectNonNumericDuration() {
            stubCreateLookups();

            ActionParameterDto param = new ActionParameterDto()
                    .setName("tiempo").setType(AttributeType.TEXT)
                    .setDynamic(true).setIsDuration(true);

            assertThatThrownBy(() -> sut.create(ORG_ID, TYPE_ID, encenderWith(param)))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("a static duration parameter without a value is rejected")
        void create_should_rejectStaticDurationWithoutValue() {
            stubCreateLookups();

            ActionParameterDto param = new ActionParameterDto()
                    .setName("tiempo").setType(AttributeType.INTEGER)
                    .setDynamic(false).setIsDuration(true).setRequired(false);

            assertThatThrownBy(() -> sut.create(ORG_ID, TYPE_ID, encenderWith(param)))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("two duration parameters in the same action are rejected")
        void create_should_rejectTwoDurations() {
            stubCreateLookups();

            ActionParameterDto a = new ActionParameterDto()
                    .setName("tiempo").setType(AttributeType.INTEGER).setDynamic(true).setIsDuration(true);
            ActionParameterDto b = new ActionParameterDto()
                    .setName("espera").setType(AttributeType.INTEGER).setDynamic(true).setIsDuration(true);

            assertThatThrownBy(() -> sut.create(ORG_ID, TYPE_ID, encenderWith(a, b)))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("a non-duration parameter defaults isDuration to false")
        void create_should_defaultIsDurationToFalse() {
            stubCreateLookups();
            when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

            ActionParameterDto param = new ActionParameterDto()
                    .setName("tiempo").setType(AttributeType.INTEGER).setDynamic(true);

            PieceTypeAction action = sut.create(ORG_ID, TYPE_ID, encenderWith(param));

            assertThat(sut.parametersOf(action).get(0).getIsDuration()).isFalse();
        }
    }

    /**
     * When the owning type has an output kind, the action must exist in the catalog for that kind.
     * Types without kind (like {@code type} above) accept any action.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("catalog validation (typed piece types)")
    class CatalogValidation {

        private static final Integer TYPED_ID = 6;
        private PieceType relayType;

        @org.junit.jupiter.api.BeforeEach
        void setUpTyped() {
            relayType = new PieceType().setId(TYPED_ID).setName("Figura amb relé").setKind(OutputKind.RELAY);
            // Lenient: the untyped-type test below resolves another type id.
            lenient().when(pieceTypeService.findInOrg(ORG_ID, TYPED_ID)).thenReturn(relayType);
        }

        private CreatePieceTypeActionDto turnOn() {
            return new CreatePieceTypeActionDto().setName("turn_on").setDisplayName("Encendre")
                    .setParameters(List.of(new ActionParameterDto().setName("time").setType(AttributeType.DECIMAL)
                            .setRequired(false).setDynamic(true).setIsDuration(true)));
        }

        @Test
        @DisplayName("create accepts a catalog action of the type's kind")
        void create_should_acceptCatalogAction() {
            when(actionRepository.findByPieceTypeAndName(relayType, "turn_on")).thenReturn(java.util.Optional.empty());
            when(actionRepository.findByPieceTypeOrderByPositionAscIdAsc(relayType)).thenReturn(List.of());
            when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

            PieceTypeAction action = sut.create(ORG_ID, TYPED_ID, turnOn());

            assertThat(action.getName()).isEqualTo("turn_on");
            assertThat(sut.parametersOf(action).get(0).getIsDuration()).isTrue();
        }

        @Test
        @DisplayName("create rejects an action the kind does not have with piece_types.action_not_in_catalog")
        void create_should_rejectUnknownAction() {
            when(actionRepository.findByPieceTypeAndName(relayType, "encender")).thenReturn(java.util.Optional.empty());
            when(actionRepository.findByPieceTypeOrderByPositionAscIdAsc(relayType)).thenReturn(List.of());

            assertThatThrownBy(() -> sut.create(ORG_ID, TYPED_ID, encenderWithTiempo()))
                    .isInstanceOfSatisfying(ApiException.class, ex ->
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_NOT_IN_CATALOG));
            org.mockito.Mockito.verify(actionRepository, org.mockito.Mockito.never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("create rejects a parameter the catalog action does not have")
        void create_should_rejectUnknownParam() {
            when(actionRepository.findByPieceTypeAndName(relayType, "turn_on")).thenReturn(java.util.Optional.empty());
            when(actionRepository.findByPieceTypeOrderByPositionAscIdAsc(relayType)).thenReturn(List.of());
            CreatePieceTypeActionDto dto = turnOn().setParameters(List.of(
                    new ActionParameterDto().setName("tiempo").setType(AttributeType.INTEGER).setDynamic(true)));

            assertThatThrownBy(() -> sut.create(ORG_ID, TYPED_ID, dto))
                    .isInstanceOfSatisfying(ApiException.class, ex ->
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_PARAM_UNKNOWN));
        }

        @Test
        @DisplayName("update re-validates when the name changes")
        void update_should_revalidateOnRename() {
            PieceTypeAction existing = new PieceTypeAction().setId(9).setName("turn_on").setPieceType(relayType).setParametersJson("[]");
            when(actionRepository.findById(9)).thenReturn(java.util.Optional.of(existing));
            when(actionRepository.findByPieceTypeAndName(relayType, "fill")).thenReturn(java.util.Optional.empty());

            assertThatThrownBy(() -> sut.update(ORG_ID, TYPED_ID, 9, new UpdatePieceTypeActionDto().setName("fill")))
                    .isInstanceOfSatisfying(ApiException.class, ex ->
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_NOT_IN_CATALOG));
        }

        @Test
        @DisplayName("update re-validates when the parameters change")
        void update_should_revalidateOnParams() {
            PieceTypeAction existing = new PieceTypeAction().setId(9).setName("turn_on").setPieceType(relayType).setParametersJson("[]");
            when(actionRepository.findById(9)).thenReturn(java.util.Optional.of(existing));
            UpdatePieceTypeActionDto dto = new UpdatePieceTypeActionDto().setParameters(List.of(
                    new ActionParameterDto().setName("volume").setType(AttributeType.INTEGER).setDynamic(true)));

            assertThatThrownBy(() -> sut.update(ORG_ID, TYPED_ID, 9, dto))
                    .isInstanceOfSatisfying(ApiException.class, ex ->
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_PARAM_UNKNOWN));
        }

        @Test
        @DisplayName("update of display name only does not re-validate a legacy action")
        void update_should_skipValidationWithoutNameOrParams() {
            PieceTypeAction legacy = new PieceTypeAction().setId(9).setName("encender").setPieceType(relayType).setParametersJson("[]");
            when(actionRepository.findById(9)).thenReturn(java.util.Optional.of(legacy));
            when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

            PieceTypeAction updated = sut.update(ORG_ID, TYPED_ID, 9, new UpdatePieceTypeActionDto().setDisplayName("Encendre"));

            assertThat(updated.getDisplayName()).isEqualTo("Encendre");
        }

        @Test
        @DisplayName("an untyped piece type still accepts any action name")
        void create_should_skipValidationForUntypedType() {
            when(pieceTypeService.findInOrg(ORG_ID, TYPE_ID)).thenReturn(type);
            when(actionRepository.findByPieceTypeAndName(type, "encender")).thenReturn(java.util.Optional.empty());
            when(actionRepository.findByPieceTypeOrderByPositionAscIdAsc(type)).thenReturn(List.of());
            when(actionRepository.saveAndFlush(any(PieceTypeAction.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThat(sut.create(ORG_ID, TYPE_ID, encenderWithTiempo()).getName()).isEqualTo("encender");
        }
    }
}
