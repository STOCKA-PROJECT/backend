package com.stocka.backend.modules.organizations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.organizations.dto.CreateOrganizationPieceAttributeDto;
import com.stocka.backend.modules.organizations.dto.UpdateOrganizationPieceAttributeDto;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.organizations.repository.OrganizationPieceAttributeRepository;
import com.stocka.backend.modules.pieces.repository.PieceOrganizationAttributeValueRepository;
import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.service.ValidatorsJsonCodec;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationPieceAttributeService")
class OrganizationPieceAttributeServiceTest {

    @Mock OrganizationPieceAttributeRepository attributeRepository;
    @Mock OrganizationService organizationService;
    @Mock ValidatorsJsonCodec validatorsCodec;
    @Mock PieceOrganizationAttributeValueRepository valueRepository;
    @Mock OrganizationPieceAttributeUsage usage;

    private OrganizationPieceAttributeService sut;
    private final Organization org = new Organization().setId(7);

    @BeforeEach
    void setUp() {
        sut = new OrganizationPieceAttributeService(
                attributeRepository, organizationService, validatorsCodec, valueRepository, Optional.of(usage));
        when(organizationService.findById(7)).thenReturn(org);
    }

    private CreateOrganizationPieceAttributeDto createDto(String name, AttributeType type, Boolean required) {
        return new CreateOrganizationPieceAttributeDto()
                .setName(name)
                .setDisplayName(name.toUpperCase())
                .setType(type)
                .setRequired(required);
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("persists a new attribute and triggers recalc when required")
        void should_createAndRecalc_whenRequiredTrue() {
            CreateOrganizationPieceAttributeDto dto = createDto("proveedor", AttributeType.TEXT, true);
            when(attributeRepository.findByOrganizationAndName(org, "proveedor")).thenReturn(Optional.empty());
            when(attributeRepository.findByOrganizationOrderByPositionAscIdAsc(org)).thenReturn(List.of());
            when(attributeRepository.save(any(OrganizationPieceAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            OrganizationPieceAttribute saved = sut.create(7, dto);

            assertThat(saved.getName()).isEqualTo("proveedor");
            assertThat(saved.isRequired()).isTrue();
            assertThat(saved.getPosition()).isZero();
            verify(usage, times(1)).recalcStatusForOrganization(org);
        }

        @Test
        @DisplayName("does not trigger recalc when attribute is optional")
        void should_skipRecalc_whenOptional() {
            CreateOrganizationPieceAttributeDto dto = createDto("notas", AttributeType.TEXT, false);
            when(attributeRepository.findByOrganizationAndName(org, "notas")).thenReturn(Optional.empty());
            when(attributeRepository.findByOrganizationOrderByPositionAscIdAsc(org)).thenReturn(List.of());
            when(attributeRepository.save(any(OrganizationPieceAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            sut.create(7, dto);

            verify(usage, never()).recalcStatusForOrganization(any());
        }

        @Test
        @DisplayName("rejects creation when name already exists in the organization")
        void should_rejectDuplicateName() {
            CreateOrganizationPieceAttributeDto dto = createDto("proveedor", AttributeType.TEXT, true);
            OrganizationPieceAttribute existing = new OrganizationPieceAttribute().setId(99);
            when(attributeRepository.findByOrganizationAndName(org, "proveedor")).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> sut.create(7, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Ya existe un atributo de organización");
            verify(attributeRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects SELECT attributes without options")
        void should_rejectSelectWithoutOptions() {
            CreateOrganizationPieceAttributeDto dto = createDto("color", AttributeType.SELECT, true)
                    .setValidators(new AttributeValidatorsDto());
            when(attributeRepository.findByOrganizationAndName(org, "color")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.create(7, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("requieren una lista de opciones");
        }

        @Test
        @DisplayName("rejects malformed names")
        void should_rejectMalformedName() {
            CreateOrganizationPieceAttributeDto dto = createDto("Provider", AttributeType.TEXT, true);

            assertThatThrownBy(() -> sut.create(7, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("letra minúscula");
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("triggers recalc when required flag flips")
        void should_recalc_whenRequiredFlips() {
            OrganizationPieceAttribute existing = new OrganizationPieceAttribute()
                    .setId(11).setOrganization(org).setName("notas").setDisplayName("Notas")
                    .setType(AttributeType.TEXT).setRequired(false);
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            when(attributeRepository.save(any(OrganizationPieceAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto()
                    .setRequired(true);

            OrganizationPieceAttribute saved = sut.update(7, 11, dto);

            assertThat(saved.isRequired()).isTrue();
            verify(usage).recalcStatusForOrganization(org);
        }

        @Test
        @DisplayName("does not recalc when only display name changes")
        void should_skipRecalc_whenOnlyDisplayNameChanges() {
            OrganizationPieceAttribute existing = new OrganizationPieceAttribute()
                    .setId(11).setOrganization(org).setName("notas").setDisplayName("Notas")
                    .setType(AttributeType.TEXT).setRequired(false);
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            when(attributeRepository.save(any(OrganizationPieceAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto()
                    .setDisplayName("Notas comerciales");

            sut.update(7, 11, dto);

            verify(usage, never()).recalcStatusForOrganization(any());
        }

        @Test
        @DisplayName("rejects empty display name")
        void should_rejectEmptyDisplayName() {
            OrganizationPieceAttribute existing = new OrganizationPieceAttribute()
                    .setId(11).setOrganization(org).setName("notas").setDisplayName("Notas")
                    .setType(AttributeType.TEXT).setRequired(false);
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto()
                    .setDisplayName("   ");

            assertThatThrownBy(() -> sut.update(7, 11, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("nombre visible no puede estar vacío");
        }
    }

    @Nested
    @DisplayName("update — rename")
    class Rename {

        private OrganizationPieceAttribute existing() {
            return new OrganizationPieceAttribute()
                    .setId(11).setOrganization(org).setName("notas").setDisplayName("Notas")
                    .setType(AttributeType.TEXT).setRequired(false);
        }

        @Test
        @DisplayName("renames technical name when target is unique")
        void should_renameToUniqueName() {
            OrganizationPieceAttribute existing = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            when(attributeRepository.findByOrganizationAndName(org, "notas_internas"))
                    .thenReturn(Optional.empty());
            when(attributeRepository.save(any(OrganizationPieceAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto()
                    .setName("notas_internas");

            OrganizationPieceAttribute saved = sut.update(7, 11, dto);

            assertThat(saved.getName()).isEqualTo("notas_internas");
            verify(usage, never()).recalcStatusForOrganization(any());
        }

        @Test
        @DisplayName("rejects rename to a name already in use in the org")
        void should_rejectRenameToExistingName() {
            OrganizationPieceAttribute existing = existing();
            OrganizationPieceAttribute conflict = new OrganizationPieceAttribute()
                    .setId(99).setOrganization(org).setName("garantia");
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            when(attributeRepository.findByOrganizationAndName(org, "garantia"))
                    .thenReturn(Optional.of(conflict));
            UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto()
                    .setName("garantia");

            assertThatThrownBy(() -> sut.update(7, 11, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Ya existe un atributo de organización");
            verify(attributeRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects rename to invalid pattern")
        void should_rejectInvalidNamePattern() {
            OrganizationPieceAttribute existing = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto()
                    .setName("Invalid-Name");

            assertThatThrownBy(() -> sut.update(7, 11, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("letra minúscula");
            verify(attributeRepository, never()).save(any());
        }

        @Test
        @DisplayName("rename to same name is a no-op (no uniqueness check)")
        void should_noop_whenSameName() {
            OrganizationPieceAttribute existing = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            when(attributeRepository.save(any(OrganizationPieceAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto()
                    .setName("notas");

            OrganizationPieceAttribute saved = sut.update(7, 11, dto);

            assertThat(saved.getName()).isEqualTo("notas");
            verify(attributeRepository, never()).findByOrganizationAndName(any(), any());
        }

        @Test
        @DisplayName("rename does not trigger status recalc")
        void should_notRecalc_onRename() {
            OrganizationPieceAttribute existing = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            when(attributeRepository.findByOrganizationAndName(org, "notas_v2"))
                    .thenReturn(Optional.empty());
            when(attributeRepository.save(any(OrganizationPieceAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto()
                    .setName("notas_v2");

            sut.update(7, 11, dto);

            verify(usage, never()).recalcStatusForOrganization(any());
        }
    }

    @Nested
    @DisplayName("update — retype")
    class Retype {

        private OrganizationPieceAttribute existing() {
            return new OrganizationPieceAttribute()
                    .setId(11).setOrganization(org).setName("garantia").setDisplayName("Garantía")
                    .setType(AttributeType.TEXT).setRequired(false)
                    .setValidatorsJson("{\"maxLength\":50}");
        }

        @Test
        @DisplayName("changes type and resets validators when no values exist and validators not supplied")
        void should_changeType_andResetValidators_whenNoValues() {
            OrganizationPieceAttribute existing = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            when(valueRepository.countByAttribute(existing)).thenReturn(0L);
            when(attributeRepository.save(any(OrganizationPieceAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto()
                    .setType(AttributeType.DATE);

            OrganizationPieceAttribute saved = sut.update(7, 11, dto);

            assertThat(saved.getType()).isEqualTo(AttributeType.DATE);
            assertThat(saved.getValidatorsJson()).isNull();
            verify(usage).recalcStatusForOrganization(org);
        }

        @Test
        @DisplayName("rejects type change when active values exist")
        void should_rejectChangeType_whenValuesExist() {
            OrganizationPieceAttribute existing = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            when(valueRepository.countByAttribute(existing)).thenReturn(3L);
            UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto()
                    .setType(AttributeType.DATE);

            assertThatThrownBy(() -> sut.update(7, 11, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No se puede cambiar el tipo")
                    .hasMessageContaining("3");
            verify(attributeRepository, never()).save(any());
        }

        @Test
        @DisplayName("keeps validators when caller supplies new validators alongside the new type")
        void should_keepNewValidators_whenSuppliedWithNewType() {
            OrganizationPieceAttribute existing = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            when(valueRepository.countByAttribute(existing)).thenReturn(0L);
            when(validatorsCodec.serialize(any())).thenReturn("{\"min\":1}");
            when(attributeRepository.save(any(OrganizationPieceAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            AttributeValidatorsDto v = new AttributeValidatorsDto();
            v.setMin(java.math.BigDecimal.ONE);
            UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto()
                    .setType(AttributeType.INTEGER)
                    .setValidators(v);

            OrganizationPieceAttribute saved = sut.update(7, 11, dto);

            assertThat(saved.getType()).isEqualTo(AttributeType.INTEGER);
            assertThat(saved.getValidatorsJson()).isEqualTo("{\"min\":1}");
        }

        @Test
        @DisplayName("changing type to the same value is a no-op for the type field")
        void should_noop_whenSameType() {
            OrganizationPieceAttribute existing = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            when(attributeRepository.save(any(OrganizationPieceAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto()
                    .setType(AttributeType.TEXT);

            OrganizationPieceAttribute saved = sut.update(7, 11, dto);

            assertThat(saved.getType()).isEqualTo(AttributeType.TEXT);
            verify(valueRepository, never()).countByAttribute(any());
        }
    }

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {

        @Test
        @DisplayName("marks deletedAt, calls usage cleanup and recalc")
        void should_softDelete_andRecalc() {
            OrganizationPieceAttribute existing = new OrganizationPieceAttribute()
                    .setId(11).setOrganization(org).setName("garantia")
                    .setType(AttributeType.DATE).setRequired(true);
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));
            when(attributeRepository.save(any(OrganizationPieceAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            sut.softDelete(7, 11);

            ArgumentCaptor<OrganizationPieceAttribute> captor =
                    ArgumentCaptor.forClass(OrganizationPieceAttribute.class);
            verify(attributeRepository).save(captor.capture());
            assertThat(captor.getValue().getDeletedAt()).isNotNull();
            verify(usage).removeValuesForAttribute(existing);
            verify(usage).recalcStatusForOrganization(org);
        }

        @Test
        @DisplayName("404 when attribute is from another organization")
        void should_404_whenAttributeFromOtherOrg() {
            OrganizationPieceAttribute existing = new OrganizationPieceAttribute()
                    .setId(11).setOrganization(new Organization().setId(99));
            when(attributeRepository.findById(11)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> sut.softDelete(7, 11))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Atributo no encontrado");
            verify(attributeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("listAll")
    class ListAll {

        @Test
        @DisplayName("returns the repository ordered list verbatim")
        void should_returnOrderedList() {
            OrganizationPieceAttribute a = new OrganizationPieceAttribute().setId(1);
            OrganizationPieceAttribute b = new OrganizationPieceAttribute().setId(2);
            when(attributeRepository.findByOrganizationOrderByPositionAscIdAsc(eq(org)))
                    .thenReturn(List.of(a, b));

            List<OrganizationPieceAttribute> result = sut.listAll(7);

            assertThat(result).containsExactly(a, b);
        }
    }
}
