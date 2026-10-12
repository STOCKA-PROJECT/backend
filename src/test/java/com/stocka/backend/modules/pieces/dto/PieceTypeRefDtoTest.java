package com.stocka.backend.modules.pieces.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.piecetypes.entity.PieceType;

@DisplayName("PieceTypeRefDto")
class PieceTypeRefDtoTest {

    @Test
    @DisplayName("exposes the catalog id of the type's kind")
    void from_should_exposeKindCatalogId() {
        PieceType type = new PieceType().setId(4).setName("Tira Led").setKind(OutputKind.LED_STRIP);

        PieceTypeRefDto dto = PieceTypeRefDto.from(type);

        assertThat(dto.id()).isEqualTo(4);
        assertThat(dto.name()).isEqualTo("Tira Led");
        assertThat(dto.kind()).isEqualTo("led_strip");
    }

    @Test
    @DisplayName("leaves kind null for inventory-only types")
    void from_should_leaveKindNull() {
        PieceType type = new PieceType().setId(9).setName("Eina");

        assertThat(PieceTypeRefDto.from(type).kind()).isNull();
    }
}
