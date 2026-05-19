package com.stocka.backend.modules.piecetypes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeAttributeDto;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeDto;
import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeAttributeRepository;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PieceTypeService")
class PieceTypeServiceTest {

    @Mock PieceTypeRepository pieceTypeRepository;
    @Mock PieceTypeAttributeRepository attributeRepository;
    @Mock OrganizationService organizationService;
    @Mock ValidatorsJsonCodec validatorsCodec;
    @Mock PieceTypeUsage usage;
    @Mock ApplicationEventPublisher events;

    private PieceTypeService sut;

    private final Organization org = new Organization();

    @BeforeEach
    void setUp() {
        org.setId(1);
        sut = new PieceTypeService(
                pieceTypeRepository, attributeRepository, organizationService,
                validatorsCodec, Optional.of(usage), events);
        // Lenient because the static-helper tests don't go through the service layer.
        lenient().when(organizationService.findById(1)).thenReturn(org);
    }

    private PieceType existingType(int id, String name) {
        PieceType t = new PieceType().setOrganization(org).setName(name);
        t.setId(id);
        return t;
    }

    @Nested
    @DisplayName("create — uniqueness")
    class CreateUniqueness {

        @Test
        @DisplayName("rejects with PIECE_TYPES_NAME_CONFLICT when an active type already uses the name")
        void should_rejectDuplicateName_preCheck() {
            when(pieceTypeRepository.findByOrganizationAndName(org, "Tira Led"))
                    .thenReturn(Optional.of(existingType(99, "Tira Led")));
            CreatePieceTypeDto dto = new CreatePieceTypeDto().setName("Tira Led");

            assertThatThrownBy(() -> sut.create(1, dto))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_NAME_CONFLICT);
                    });
            verify(pieceTypeRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("translates a DB-level uk_piece_type_org_name violation into a 409 ApiException")
        void should_translateDataIntegrityViolation_intoConflict() {
            // Pre-check sees no duplicate (race condition: another tx commits in between)
            when(pieceTypeRepository.findByOrganizationAndName(org, "Tira Led"))
                    .thenReturn(Optional.empty());
            when(pieceTypeRepository.saveAndFlush(any(PieceType.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate entry '1-Tira Led'"));
            CreatePieceTypeDto dto = new CreatePieceTypeDto().setName("Tira Led");

            assertThatThrownBy(() -> sut.create(1, dto))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_NAME_CONFLICT);
                        assertThat(ex.getCause()).isInstanceOf(DataIntegrityViolationException.class);
                    });
        }

        @Test
        @DisplayName("trims whitespace before checking uniqueness")
        void should_trimNameBeforeUniqueCheck() {
            when(pieceTypeRepository.findByOrganizationAndName(org, "Tira Led"))
                    .thenReturn(Optional.empty());
            when(pieceTypeRepository.saveAndFlush(any(PieceType.class)))
                    .thenAnswer(inv -> {
                        PieceType t = inv.getArgument(0);
                        t.setId(7);
                        return t;
                    });
            CreatePieceTypeDto dto = new CreatePieceTypeDto().setName("  Tira Led  ");

            PieceType created = sut.create(1, dto);

            assertThat(created.getName()).isEqualTo("Tira Led");
            verify(pieceTypeRepository).findByOrganizationAndName(org, "Tira Led");
        }

        @Test
        @DisplayName("rejects blank name with HTTP 400")
        void should_rejectBlankName() {
            CreatePieceTypeDto dto = new CreatePieceTypeDto().setName("   ");

            assertThatThrownBy(() -> sut.create(1, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("nombre del tipo");
            verify(pieceTypeRepository, never()).findByOrganizationAndName(any(), any());
        }
    }

    @Nested
    @DisplayName("create — initial attributes")
    class CreateAttributes {

        @Test
        @DisplayName("rejects duplicated attribute names in payload before persisting")
        void should_rejectDuplicateAttrNamesInPayload() {
            when(pieceTypeRepository.findByOrganizationAndName(org, "Tool"))
                    .thenReturn(Optional.empty());
            when(pieceTypeRepository.saveAndFlush(any(PieceType.class)))
                    .thenAnswer(inv -> {
                        PieceType t = inv.getArgument(0);
                        t.setId(7);
                        return t;
                    });
            CreatePieceTypeDto dto = new CreatePieceTypeDto()
                    .setName("Tool")
                    .setAttributes(List.of(
                            new CreatePieceTypeAttributeDto().setName("color").setType(AttributeType.TEXT),
                            new CreatePieceTypeAttributeDto().setName("color").setType(AttributeType.TEXT)
                    ));

            assertThatThrownBy(() -> sut.create(1, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("duplicad");
            verify(attributeRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("update — rename")
    class UpdateRename {

        @Test
        @DisplayName("renames when target is unique inside the org")
        void should_renameWhenTargetIsUnique() {
            PieceType current = existingType(7, "Tool");
            when(pieceTypeRepository.findById(7)).thenReturn(Optional.of(current));
            when(pieceTypeRepository.findByOrganizationAndName(org, "Hammer"))
                    .thenReturn(Optional.empty());
            when(pieceTypeRepository.saveAndFlush(current)).thenReturn(current);
            UpdatePieceTypeDto dto = new UpdatePieceTypeDto();
            dto.setName("Hammer");

            PieceType saved = sut.update(1, 7, dto);

            assertThat(saved.getName()).isEqualTo("Hammer");
        }

        @Test
        @DisplayName("rejects rename to a name held by another active type in the same org")
        void should_rejectRenameToOtherActiveName() {
            PieceType current = existingType(7, "Tool");
            when(pieceTypeRepository.findById(7)).thenReturn(Optional.of(current));
            when(pieceTypeRepository.findByOrganizationAndName(org, "Hammer"))
                    .thenReturn(Optional.of(existingType(8, "Hammer")));
            UpdatePieceTypeDto dto = new UpdatePieceTypeDto();
            dto.setName("Hammer");

            assertThatThrownBy(() -> sut.update(1, 7, dto))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_NAME_CONFLICT);
                    });
            verify(pieceTypeRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rename to same name skips uniqueness check")
        void should_skipCheck_whenSameName() {
            PieceType current = existingType(7, "Tool");
            when(pieceTypeRepository.findById(7)).thenReturn(Optional.of(current));
            when(pieceTypeRepository.saveAndFlush(current)).thenReturn(current);
            UpdatePieceTypeDto dto = new UpdatePieceTypeDto();
            dto.setName("Tool");

            sut.update(1, 7, dto);

            verify(pieceTypeRepository, never()).findByOrganizationAndName(any(), any());
        }
    }

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {

        @Test
        @DisplayName("renames the row to free the (org_id, name) slot and stamps deleted_at")
        void should_renameAndStampDeletedAt() {
            PieceType current = existingType(42, "Tira Led");
            when(pieceTypeRepository.findById(42)).thenReturn(Optional.of(current));
            when(usage.countPiecesOfType(current)).thenReturn(0L);

            sut.softDelete(1, 42);

            assertThat(current.getName()).isEqualTo("Tira Led::deleted::42");
            assertThat(current.getDeletedAt()).isNotNull();
            verify(pieceTypeRepository).save(current);
        }

        @Test
        @DisplayName("truncates the original name so the renamed value still fits the column length")
        void should_truncateLongNamesOnSoftDelete() {
            String longName = "x".repeat(PieceTypeService.MAX_TYPE_NAME_LENGTH);
            PieceType current = existingType(123456, longName);
            when(pieceTypeRepository.findById(123456)).thenReturn(Optional.of(current));
            when(usage.countPiecesOfType(current)).thenReturn(0L);

            sut.softDelete(1, 123456);

            assertThat(current.getName()).hasSizeLessThanOrEqualTo(PieceTypeService.MAX_TYPE_NAME_LENGTH);
            assertThat(current.getName()).endsWith("::deleted::123456");
        }

        @Test
        @DisplayName("blocks deletion when the type still has pieces and does not rename")
        void should_blockWhenTypeInUse() {
            PieceType current = existingType(42, "Tira Led");
            when(pieceTypeRepository.findById(42)).thenReturn(Optional.of(current));
            when(usage.countPiecesOfType(current)).thenReturn(3L);

            assertThatThrownBy(() -> sut.softDelete(1, 42))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("3 artículos");
            assertThat(current.getName()).isEqualTo("Tira Led");
            assertThat(current.getDeletedAt()).isNull();
            verify(pieceTypeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("addAttribute")
    class AddAttribute {

        @Test
        @DisplayName("rejects duplicate attribute name with PIECE_TYPES_ATTRIBUTE_NAME_CONFLICT")
        void should_rejectDuplicateAttributeName() {
            PieceType type = existingType(7, "Tool");
            PieceTypeAttribute existing = new PieceTypeAttribute()
                    .setName("color").setPieceType(type);
            existing.setId(50);
            when(pieceTypeRepository.findById(7)).thenReturn(Optional.of(type));
            when(attributeRepository.findByPieceTypeAndName(type, "color"))
                    .thenReturn(Optional.of(existing));
            CreatePieceTypeAttributeDto dto = new CreatePieceTypeAttributeDto()
                    .setName("color").setType(AttributeType.TEXT);

            assertThatThrownBy(() -> sut.addAttribute(1, 7, dto))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ATTRIBUTE_NAME_CONFLICT);
                    });
            verify(attributeRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("translates a DB-level uk_piece_type_attr_type_name violation into a 409 ApiException")
        void should_translateDataIntegrityViolation() {
            PieceType type = existingType(7, "Tool");
            when(pieceTypeRepository.findById(7)).thenReturn(Optional.of(type));
            when(attributeRepository.findByPieceTypeAndName(type, "color"))
                    .thenReturn(Optional.empty());
            when(attributeRepository.findByPieceTypeOrderByPositionAscIdAsc(type))
                    .thenReturn(List.of());
            when(attributeRepository.saveAndFlush(any(PieceTypeAttribute.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate"));
            CreatePieceTypeAttributeDto dto = new CreatePieceTypeAttributeDto()
                    .setName("color").setType(AttributeType.TEXT);

            assertThatThrownBy(() -> sut.addAttribute(1, 7, dto))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ATTRIBUTE_NAME_CONFLICT);
                    });
        }
    }

    @Nested
    @DisplayName("findInOrg")
    class FindInOrg {

        @Test
        @DisplayName("rejects access to a type that belongs to another org with PIECE_TYPES_NOT_FOUND")
        void should_isolateTypesByOrg() {
            Organization other = new Organization();
            other.setId(99);
            PieceType foreign = new PieceType().setOrganization(other).setName("Tool");
            foreign.setId(7);
            when(pieceTypeRepository.findById(7)).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> sut.findInOrg(1, 7))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("returns the type when ownership matches")
        void should_returnTypeWhenOrgMatches() {
            PieceType current = existingType(7, "Tool");
            when(pieceTypeRepository.findById(7)).thenReturn(Optional.of(current));

            PieceType result = sut.findInOrg(1, 7);

            assertThat(result).isSameAs(current);
        }
    }

    @Nested
    @DisplayName("buildSoftDeletedName helper")
    class BuildSoftDeletedNameHelper {

        @Test
        @DisplayName("appends the id suffix when the resulting value fits")
        void should_appendSuffix() {
            assertThat(PieceTypeService.buildSoftDeletedName("Tool", 42, 120))
                    .isEqualTo("Tool::deleted::42");
        }

        @Test
        @DisplayName("truncates the base name when needed to respect maxLength")
        void should_truncateWhenTooLong() {
            String result = PieceTypeService.buildSoftDeletedName("abcdef", 7, 12);
            assertThat(result).hasSize(12);
            assertThat(result).endsWith("::deleted::7");
        }

        @Test
        @DisplayName("substitutes a placeholder for null id (defensive)")
        void should_handleNullId() {
            assertThat(PieceTypeService.buildSoftDeletedName("Tool", null, 120))
                    .isEqualTo("Tool::deleted::?");
        }

        @Test
        @DisplayName("handles a null base name gracefully")
        void should_handleNullName() {
            assertThat(PieceTypeService.buildSoftDeletedName(null, 5, 120))
                    .isEqualTo("::deleted::5");
        }
    }

}
