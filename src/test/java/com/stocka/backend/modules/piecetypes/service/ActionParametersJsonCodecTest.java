package com.stocka.backend.modules.piecetypes.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

@DisplayName("ActionParametersJsonCodec")
class ActionParametersJsonCodecTest {

    private final ActionParametersJsonCodec codec = new ActionParametersJsonCodec();

    @Test
    @DisplayName("serialize should return null for a null list")
    void serialize_should_returnNull_when_listNull() {
        assertThat(codec.serialize(null)).isNull();
    }

    @Test
    @DisplayName("deserialize should return an empty list for null or blank JSON")
    void deserialize_should_returnEmptyList_when_blank() {
        assertThat(codec.deserialize(null)).isEmpty();
        assertThat(codec.deserialize("   ")).isEmpty();
    }

    @Test
    @DisplayName("should round-trip a typed parameter list preserving every field")
    void should_roundTrip_parameterList() {
        ActionParameterDto tiempo = new ActionParameterDto()
                .setName("tiempo")
                .setDisplayName("Tiempo")
                .setType(AttributeType.INTEGER)
                .setRequired(true)
                .setPosition(0)
                .setValidators(new AttributeValidatorsDto().setMin(BigDecimal.ZERO).setMax(BigDecimal.valueOf(3600)));

        String json = codec.serialize(List.of(tiempo));
        List<ActionParameterDto> restored = codec.deserialize(json);

        assertThat(restored).hasSize(1);
        ActionParameterDto out = restored.get(0);
        assertThat(out.getName()).isEqualTo("tiempo");
        assertThat(out.getDisplayName()).isEqualTo("Tiempo");
        assertThat(out.getType()).isEqualTo(AttributeType.INTEGER);
        assertThat(out.getRequired()).isTrue();
        assertThat(out.getPosition()).isZero();
        assertThat(out.getValidators()).isNotNull();
        assertThat(out.getValidators().getMin()).isEqualByComparingTo("0");
        assertThat(out.getValidators().getMax()).isEqualByComparingTo("3600");
    }
}
