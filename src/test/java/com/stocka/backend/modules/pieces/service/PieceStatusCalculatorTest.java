package com.stocka.backend.modules.pieces.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.pieces.entity.PieceAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceOrganizationAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;

@DisplayName("PieceStatusCalculator")
class PieceStatusCalculatorTest {

    private final PieceStatusCalculator sut = new PieceStatusCalculator();

    private static PieceTypeAttribute typeAttr(int id, boolean required) {
        return new PieceTypeAttribute()
                .setId(id)
                .setName("a" + id)
                .setDisplayName("A" + id)
                .setType(AttributeType.TEXT)
                .setRequired(required);
    }

    private static PieceAttributeValue typeValue(PieceTypeAttribute attr, String value) {
        return new PieceAttributeValue().setAttribute(attr).setValue(value);
    }

    private static OrganizationPieceAttribute orgAttr(int id, boolean required) {
        return new OrganizationPieceAttribute()
                .setId(id)
                .setName("o" + id)
                .setDisplayName("O" + id)
                .setType(AttributeType.TEXT)
                .setRequired(required);
    }

    private static PieceOrganizationAttributeValue orgValue(OrganizationPieceAttribute attr, String value) {
        return new PieceOrganizationAttributeValue().setAttribute(attr).setValue(value);
    }

    @Nested
    @DisplayName("type-only schema")
    class TypeOnly {

        @Test
        @DisplayName("returns ACTIVE when every required type attribute has a value")
        void should_returnActive_when_allRequiredHaveValue() {
            PieceTypeAttribute a1 = typeAttr(1, true);
            PieceTypeAttribute a2 = typeAttr(2, true);
            PieceStatus result = sut.compute(List.of(a1, a2),
                    List.of(typeValue(a1, "x"), typeValue(a2, "y")));
            assertThat(result).isEqualTo(PieceStatus.ACTIVE);
        }

        @Test
        @DisplayName("returns PENDING when a required type attribute is missing")
        void should_returnPending_when_anyRequiredMissing() {
            PieceTypeAttribute a1 = typeAttr(1, true);
            PieceTypeAttribute a2 = typeAttr(2, true);
            PieceStatus result = sut.compute(List.of(a1, a2), List.of(typeValue(a1, "x")));
            assertThat(result).isEqualTo(PieceStatus.PENDING);
        }

        @Test
        @DisplayName("returns PENDING when a required type attribute is blank")
        void should_returnPending_when_anyRequiredBlank() {
            PieceTypeAttribute a1 = typeAttr(1, true);
            PieceStatus result = sut.compute(List.of(a1), List.of(typeValue(a1, "  ")));
            assertThat(result).isEqualTo(PieceStatus.PENDING);
        }

        @Test
        @DisplayName("ignores optional type attributes for status")
        void should_ignoreOptionalAttributes() {
            PieceTypeAttribute required = typeAttr(1, true);
            PieceTypeAttribute optional = typeAttr(2, false);
            PieceStatus result = sut.compute(List.of(required, optional), List.of(typeValue(required, "x")));
            assertThat(result).isEqualTo(PieceStatus.ACTIVE);
        }

        @Test
        @DisplayName("returns ACTIVE when there are no attributes at all")
        void should_returnActive_when_noAttributes() {
            PieceStatus result = sut.compute(List.of(), List.of());
            assertThat(result).isEqualTo(PieceStatus.ACTIVE);
        }

        @Test
        @DisplayName("returns ACTIVE when only optional type attributes exist and none filled")
        void should_returnActive_when_onlyOptionalAttributes() {
            PieceTypeAttribute optional = typeAttr(1, false);
            PieceStatus result = sut.compute(List.of(optional), List.of());
            assertThat(result).isEqualTo(PieceStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("org-only schema")
    class OrgOnly {

        @Test
        @DisplayName("returns ACTIVE when every required org attribute has a value")
        void should_returnActive_when_orgRequiredFilled() {
            OrganizationPieceAttribute o1 = orgAttr(1, true);
            PieceStatus result = sut.compute(List.of(), List.of(),
                    List.of(o1), List.of(orgValue(o1, "2026-01-01")));
            assertThat(result).isEqualTo(PieceStatus.ACTIVE);
        }

        @Test
        @DisplayName("returns PENDING when a required org attribute is missing")
        void should_returnPending_when_orgRequiredMissing() {
            OrganizationPieceAttribute o1 = orgAttr(1, true);
            PieceStatus result = sut.compute(List.of(), List.of(),
                    List.of(o1), List.of());
            assertThat(result).isEqualTo(PieceStatus.PENDING);
        }

        @Test
        @DisplayName("returns PENDING when a required org attribute is blank")
        void should_returnPending_when_orgRequiredBlank() {
            OrganizationPieceAttribute o1 = orgAttr(1, true);
            PieceStatus result = sut.compute(List.of(), List.of(),
                    List.of(o1), List.of(orgValue(o1, "")));
            assertThat(result).isEqualTo(PieceStatus.PENDING);
        }

        @Test
        @DisplayName("ignores optional org attributes for status")
        void should_ignoreOptionalOrgAttributes() {
            OrganizationPieceAttribute opt = orgAttr(1, false);
            PieceStatus result = sut.compute(List.of(), List.of(),
                    List.of(opt), List.of());
            assertThat(result).isEqualTo(PieceStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("mixed type and org schemas")
    class Mixed {

        @Test
        @DisplayName("returns ACTIVE when both spaces are fully satisfied")
        void should_returnActive_when_bothSpacesFilled() {
            PieceTypeAttribute t1 = typeAttr(1, true);
            OrganizationPieceAttribute o1 = orgAttr(1, true);
            PieceStatus result = sut.compute(List.of(t1), List.of(typeValue(t1, "x")),
                    List.of(o1), List.of(orgValue(o1, "y")));
            assertThat(result).isEqualTo(PieceStatus.ACTIVE);
        }

        @Test
        @DisplayName("returns PENDING when type space is satisfied but org space is missing")
        void should_returnPending_when_orgMissing() {
            PieceTypeAttribute t1 = typeAttr(1, true);
            OrganizationPieceAttribute o1 = orgAttr(1, true);
            PieceStatus result = sut.compute(List.of(t1), List.of(typeValue(t1, "x")),
                    List.of(o1), List.of());
            assertThat(result).isEqualTo(PieceStatus.PENDING);
        }

        @Test
        @DisplayName("returns PENDING when org space is satisfied but type space is missing")
        void should_returnPending_when_typeMissing() {
            PieceTypeAttribute t1 = typeAttr(1, true);
            OrganizationPieceAttribute o1 = orgAttr(1, true);
            PieceStatus result = sut.compute(List.of(t1), List.of(),
                    List.of(o1), List.of(orgValue(o1, "y")));
            assertThat(result).isEqualTo(PieceStatus.PENDING);
        }

        @Test
        @DisplayName("does not confuse type and org attributes that share the same id")
        void should_disambiguateOnIdCollision() {
            // Both attributes share id=42 but live in different spaces. The type attribute
            // is satisfied; the org one is not. Status must be PENDING.
            PieceTypeAttribute t = typeAttr(42, true);
            OrganizationPieceAttribute o = orgAttr(42, true);
            PieceStatus result = sut.compute(List.of(t), List.of(typeValue(t, "filled")),
                    List.of(o), List.of());
            assertThat(result).isEqualTo(PieceStatus.PENDING);
        }
    }
}
