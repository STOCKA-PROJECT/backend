package com.stocka.backend.modules.mcp.tool.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.mcp.tool.ToolException;
import com.stocka.backend.modules.mcp.tool.support.AttributeValueResolver.ValueInput;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.organizations.service.OrganizationPieceAttributeService;
import com.stocka.backend.modules.pieces.dto.AttributeScope;
import com.stocka.backend.modules.pieces.dto.AttributeValueInputDto;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;

@DisplayName("AttributeValueResolver")
class AttributeValueResolverTest {

    private final PieceTypeService types = mock(PieceTypeService.class);
    private final OrganizationPieceAttributeService orgAttributes = mock(OrganizationPieceAttributeService.class);
    private final AttributeValueResolver resolver = new AttributeValueResolver(types, orgAttributes);

    private final PieceType figura = new PieceType().setId(10).setName("Figura");
    private final PieceType edifici = new PieceType().setId(20).setName("Edifici");
    private final PieceTypeAttribute color = new PieceTypeAttribute().setId(1).setName("color").setDisplayName("Color").setPieceType(figura);
    private final PieceTypeAttribute alcada = new PieceTypeAttribute().setId(2).setName("alcada").setDisplayName("Alçada").setPieceType(figura);
    private final PieceTypeAttribute colorEdifici = new PieceTypeAttribute().setId(3).setName("color").setDisplayName("Color").setPieceType(edifici);
    private final OrganizationPieceAttribute notes = new OrganizationPieceAttribute().setId(50).setName("notes").setDisplayName("Notes");
    private final OrganizationPieceAttribute colorOrg = new OrganizationPieceAttribute().setId(51).setName("color").setDisplayName("Color org");

    @BeforeEach
    void setUp() {
        when(types.findInOrg(1, 10)).thenReturn(figura);
        when(types.findInOrg(1, 20)).thenReturn(edifici);
        when(types.attributesOf(figura)).thenReturn(List.of(color, alcada));
        when(types.attributesOf(edifici)).thenReturn(List.of(colorEdifici));
        when(orgAttributes.listAll(1)).thenReturn(List.of(notes, colorOrg));
    }

    private ToolException failure(List<Integer> typeIds, ValueInput input) {
        try {
            resolver.resolve(1, typeIds, List.of(input));
        } catch (ToolException e) {
            return e;
        }
        throw new AssertionError("expected ToolException");
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("by name, label or id among the piece's types")
        void typeAttributes() {
            List<AttributeValueInputDto> out = resolver.resolve(1, List.of(10), List.of(
                    new ValueInput("color", null, "verd"),
                    new ValueInput("Alçada", null, "12"),
                    new ValueInput("2", "TYPE", "13")));
            assertThat(out).extracting(AttributeValueInputDto::attributeId).containsExactly(1, 2, 2);
            assertThat(out).extracting(AttributeValueInputDto::effectiveScope).containsOnly(AttributeScope.TYPE);
            assertThat(out.get(0).value()).isEqualTo("verd");
        }

        @Test
        @DisplayName("falls back to organization attributes, or targets them explicitly")
        void orgAttributes() {
            List<AttributeValueInputDto> out = resolver.resolve(1, List.of(10), List.of(
                    new ValueInput("notes", null, "n"),
                    new ValueInput("color", "ORG", "x")));
            assertThat(out.get(0).attributeId()).isEqualTo(50);
            assertThat(out.get(0).scope()).isEqualTo(AttributeScope.ORG);
            assertThat(out.get(1).attributeId()).isEqualTo(51);
        }

        @Test
        @DisplayName("a type match wins over an organization attribute with the same name")
        void typeWins() {
            List<AttributeValueInputDto> out = resolver.resolve(1, List.of(10), List.of(new ValueInput("color", null, "v")));
            assertThat(out.get(0).attributeId()).isEqualTo(1);
        }

        @Test
        @DisplayName("null inputs resolve to an empty list")
        void empty() {
            assertThat(resolver.resolve(1, List.of(), null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("unknown names list where to look")
        void unknown() {
            ToolException e = failure(List.of(10), new ValueInput("pes", null, "1"));
            assertThat(e.getCode()).isEqualTo("pieces.attribute_unknown");
            assertThat(e.getHint()).contains("piece_types_list");
        }

        @Test
        @DisplayName("the same name on two of the piece's types is ambiguous and lists candidates")
        void ambiguous() {
            ToolException e = failure(List.of(10, 20), new ValueInput("color", null, "v"));
            assertThat(e.getCode()).isEqualTo("pieces.attribute_ambiguous");
            assertThat(e.getParams().get("candidates").toString()).contains("TYPE:1").contains("TYPE:3");
        }

        @Test
        @DisplayName("missing attribute reference and bad scope are invalid arguments")
        void invalid() {
            assertThat(failure(List.of(10), new ValueInput(" ", null, "v")).getCode()).isEqualTo("mcp.invalid_arguments");
            assertThatThrownBy(() -> resolver.resolve(1, List.of(10), List.of(new ValueInput("color", "weird", "v"))))
                    .isInstanceOf(ToolException.class).hasMessageContaining("mcp.invalid_arguments");
        }
    }
}
