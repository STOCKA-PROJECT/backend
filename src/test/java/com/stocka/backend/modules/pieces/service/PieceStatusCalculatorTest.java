package com.stocka.backend.modules.pieces.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.pieces.entity.PieceAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;

@DisplayName("PieceStatusCalculator")
class PieceStatusCalculatorTest {

    private final PieceStatusCalculator sut = new PieceStatusCalculator();

    private static PieceTypeAttribute attr(int id, boolean required) {
        return new PieceTypeAttribute()
                .setId(id)
                .setName("a" + id)
                .setDisplayName("A" + id)
                .setType(AttributeType.TEXT)
                .setRequired(required);
    }

    private static PieceAttributeValue value(PieceTypeAttribute attr, String value) {
        return new PieceAttributeValue().setAttribute(attr).setValue(value);
    }

    @Test
    @DisplayName("should return ACTIVE when all required attributes have value")
    void should_returnActive_when_allRequiredHaveValue() {
        PieceTypeAttribute a1 = attr(1, true);
        PieceTypeAttribute a2 = attr(2, true);
        PieceStatus result = sut.compute(List.of(a1, a2),
                List.of(value(a1, "x"), value(a2, "y")));
        assertThat(result).isEqualTo(PieceStatus.ACTIVE);
    }

    @Test
    @DisplayName("should return PENDING when a required attribute is missing")
    void should_returnPending_when_anyRequiredMissing() {
        PieceTypeAttribute a1 = attr(1, true);
        PieceTypeAttribute a2 = attr(2, true);
        PieceStatus result = sut.compute(List.of(a1, a2), List.of(value(a1, "x")));
        assertThat(result).isEqualTo(PieceStatus.PENDING);
    }

    @Test
    @DisplayName("should return PENDING when a required attribute has blank value")
    void should_returnPending_when_anyRequiredBlank() {
        PieceTypeAttribute a1 = attr(1, true);
        PieceStatus result = sut.compute(List.of(a1), List.of(value(a1, "  ")));
        assertThat(result).isEqualTo(PieceStatus.PENDING);
    }

    @Test
    @DisplayName("should ignore optional attributes for status")
    void should_ignoreOptionalAttributes() {
        PieceTypeAttribute required = attr(1, true);
        PieceTypeAttribute optional = attr(2, false);
        PieceStatus result = sut.compute(List.of(required, optional), List.of(value(required, "x")));
        assertThat(result).isEqualTo(PieceStatus.ACTIVE);
    }

    @Test
    @DisplayName("should return ACTIVE when there are no attributes")
    void should_returnActive_when_noAttributes() {
        PieceStatus result = sut.compute(List.of(), List.of());
        assertThat(result).isEqualTo(PieceStatus.ACTIVE);
    }

    @Test
    @DisplayName("should return ACTIVE when only optional attributes exist and none filled")
    void should_returnActive_when_onlyOptionalAttributes() {
        PieceTypeAttribute optional = attr(1, false);
        PieceStatus result = sut.compute(List.of(optional), List.of());
        assertThat(result).isEqualTo(PieceStatus.ACTIVE);
    }
}
