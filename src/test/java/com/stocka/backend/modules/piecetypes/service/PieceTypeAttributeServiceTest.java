package com.stocka.backend.modules.piecetypes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import com.stocka.backend.modules.sync.support.SyncStamper;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeAttributeDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeAttributeRepository;
import com.stocka.backend.modules.pieces.repository.PieceAttributeValueRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PieceTypeAttributeService")
class PieceTypeAttributeServiceTest {

    @Mock PieceTypeService pieceTypeService;
    @Mock PieceTypeAttributeRepository attributeRepository;
    @Mock PieceAttributeValueRepository valueRepository;
    @Mock ValidatorsJsonCodec validatorsCodec;
    @Mock PieceTypeUsage usage;

    private PieceTypeAttributeService sut;

    private final PieceType type = new PieceType();

    @Mock SyncStamper syncStamper;

    @BeforeEach
    void setUp() {
        type.setId(5);
        sut = new PieceTypeAttributeService(
                pieceTypeService, attributeRepository, valueRepository, validatorsCodec, Optional.of(usage), syncStamper);
        when(pieceTypeService.findInOrg(eq(7), eq(5))).thenReturn(type);
    }

    private PieceTypeAttribute existing() {
        PieceTypeAttribute a = new PieceTypeAttribute()
                .setName("notas")
                .setDisplayName("Notas")
                .setType(AttributeType.TEXT)
                .setRequired(false)
                .setValidatorsJson("{\"maxLength\":50}");
        a.setId(11);
        a.setPieceType(type);
        return a;
    }

    @Nested
    @DisplayName("update — rename")
    class Rename {

        @Test
        @DisplayName("renames technical name when target is unique")
        void should_renameToUniqueName() {
            PieceTypeAttribute attr = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(attr));
            when(attributeRepository.saveAndFlush(any(PieceTypeAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            UpdatePieceTypeAttributeDto dto = new UpdatePieceTypeAttributeDto()
                    .setName("notas_internas");

            PieceTypeAttribute saved = sut.update(7, 5, 11, dto);

            assertThat(saved.getName()).isEqualTo("notas_internas");
            verify(pieceTypeService).ensureUniqueAttributeName(type, "notas_internas", 11);
            verify(usage, never()).recalcStatusForType(any());
        }

        @Test
        @DisplayName("rejects rename to a name already in use within the same type")
        void should_rejectRenameToExistingName() {
            PieceTypeAttribute attr = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(attr));
            doThrow(new ApiException(HttpStatus.CONFLICT, ErrorCodes.PIECE_TYPES_ATTRIBUTE_NAME_CONFLICT))
                    .when(pieceTypeService).ensureUniqueAttributeName(type, "garantia", 11);
            UpdatePieceTypeAttributeDto dto = new UpdatePieceTypeAttributeDto()
                    .setName("garantia");

            assertThatThrownBy(() -> sut.update(7, 5, 11, dto))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ATTRIBUTE_NAME_CONFLICT);
                    });
            verify(attributeRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("translates a DB-level uk_piece_type_attr_type_name violation into a 409 ApiException")
        void should_translateDataIntegrityViolation_intoConflict() {
            PieceTypeAttribute attr = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(attr));
            // ensureUniqueAttributeName lets the rename through (race condition: the duplicate row
            // appeared between the SELECT and the flush).
            when(attributeRepository.saveAndFlush(any(PieceTypeAttribute.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate entry"));
            UpdatePieceTypeAttributeDto dto = new UpdatePieceTypeAttributeDto()
                    .setName("garantia");

            assertThatThrownBy(() -> sut.update(7, 5, 11, dto))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ATTRIBUTE_NAME_CONFLICT);
                    });
        }

        @Test
        @DisplayName("rejects rename to invalid pattern")
        void should_rejectInvalidNamePattern() {
            PieceTypeAttribute attr = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(attr));
            UpdatePieceTypeAttributeDto dto = new UpdatePieceTypeAttributeDto()
                    .setName("Invalid-Name");

            assertThatThrownBy(() -> sut.update(7, 5, 11, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("letra minúscula");
            verify(attributeRepository, never()).saveAndFlush(any());
            verify(pieceTypeService, never()).ensureUniqueAttributeName(any(), any(), anyInt());
        }

        @Test
        @DisplayName("rename to same name is a no-op (skips uniqueness check)")
        void should_noop_whenSameName() {
            PieceTypeAttribute attr = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(attr));
            when(attributeRepository.saveAndFlush(any(PieceTypeAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            UpdatePieceTypeAttributeDto dto = new UpdatePieceTypeAttributeDto()
                    .setName("notas");

            PieceTypeAttribute saved = sut.update(7, 5, 11, dto);

            assertThat(saved.getName()).isEqualTo("notas");
            verify(pieceTypeService, never()).ensureUniqueAttributeName(any(), any(), anyInt());
        }

        @Test
        @DisplayName("rename does not trigger status recalc")
        void should_notRecalc_onRename() {
            PieceTypeAttribute attr = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(attr));
            when(attributeRepository.saveAndFlush(any(PieceTypeAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            UpdatePieceTypeAttributeDto dto = new UpdatePieceTypeAttributeDto()
                    .setName("notas_v2");

            sut.update(7, 5, 11, dto);

            verify(usage, never()).recalcStatusForType(any());
        }
    }

    @Nested
    @DisplayName("update — retype")
    class Retype {

        @Test
        @DisplayName("changes type and resets validators when no values exist and validators not supplied")
        void should_changeType_andResetValidators_whenNoValues() {
            PieceTypeAttribute attr = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(attr));
            when(valueRepository.countByAttribute(attr)).thenReturn(0L);
            when(attributeRepository.saveAndFlush(any(PieceTypeAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            UpdatePieceTypeAttributeDto dto = new UpdatePieceTypeAttributeDto()
                    .setType(AttributeType.DATE);

            PieceTypeAttribute saved = sut.update(7, 5, 11, dto);

            assertThat(saved.getType()).isEqualTo(AttributeType.DATE);
            assertThat(saved.getValidatorsJson()).isNull();
            verify(usage).recalcStatusForType(type);
        }

        @Test
        @DisplayName("rejects type change when active values exist")
        void should_rejectChangeType_whenValuesExist() {
            PieceTypeAttribute attr = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(attr));
            when(valueRepository.countByAttribute(attr)).thenReturn(4L);
            UpdatePieceTypeAttributeDto dto = new UpdatePieceTypeAttributeDto()
                    .setType(AttributeType.DATE);

            assertThatThrownBy(() -> sut.update(7, 5, 11, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No se puede cambiar el tipo")
                    .hasMessageContaining("4");
            verify(attributeRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("keeps validators when caller supplies new validators alongside the new type")
        void should_keepNewValidators_whenSuppliedWithNewType() {
            PieceTypeAttribute attr = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(attr));
            when(valueRepository.countByAttribute(attr)).thenReturn(0L);
            when(validatorsCodec.serialize(any())).thenReturn("{\"min\":1}");
            when(attributeRepository.saveAndFlush(any(PieceTypeAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            AttributeValidatorsDto v = new AttributeValidatorsDto();
            v.setMin(java.math.BigDecimal.ONE);
            UpdatePieceTypeAttributeDto dto = new UpdatePieceTypeAttributeDto()
                    .setType(AttributeType.INTEGER)
                    .setValidators(v);

            PieceTypeAttribute saved = sut.update(7, 5, 11, dto);

            assertThat(saved.getType()).isEqualTo(AttributeType.INTEGER);
            assertThat(saved.getValidatorsJson()).isEqualTo("{\"min\":1}");
        }

        @Test
        @DisplayName("changing type to the same value skips count check")
        void should_noop_whenSameType() {
            PieceTypeAttribute attr = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(attr));
            when(attributeRepository.saveAndFlush(any(PieceTypeAttribute.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            UpdatePieceTypeAttributeDto dto = new UpdatePieceTypeAttributeDto()
                    .setType(AttributeType.TEXT);

            PieceTypeAttribute saved = sut.update(7, 5, 11, dto);

            assertThat(saved.getType()).isEqualTo(AttributeType.TEXT);
            verify(valueRepository, never()).countByAttribute(any());
        }
    }

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {

        @Test
        @DisplayName("renames the attribute with an id-based suffix to free the unique slot")
        void should_renameOnSoftDelete() {
            PieceTypeAttribute attr = existing();
            when(attributeRepository.findById(11)).thenReturn(Optional.of(attr));

            sut.softDelete(7, 5, 11);

            assertThat(attr.getName()).startsWith("notas::deleted::");
            assertThat(attr.getName()).endsWith("::11");
            assertThat(attr.getDeletedAt()).isNotNull();
            verify(attributeRepository).save(attr);
            verify(usage).removeValuesForAttribute(attr);
            verify(usage).recalcStatusForType(type);
        }
    }
}
