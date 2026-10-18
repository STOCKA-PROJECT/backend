package com.stocka.backend.modules.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.contacts.entity.Contact;
import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceOrganizationAttributeValue;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.search.client.XeyeClient.ElementInput;
import com.stocka.backend.modules.users.entity.User;

@DisplayName("PieceSearchDocument")
class PieceSearchDocumentTest {

    private static Piece piece(String name) {
        return new Piece().setId(42).setName(name);
    }

    private static PieceSearchDocument of(Piece piece) {
        return PieceSearchDocument.of(piece, List.of(), List.of());
    }

    private static PieceAttributeValue typeValue(String displayName, AttributeType type, String value) {
        PieceTypeAttribute attribute = new PieceTypeAttribute().setName(displayName.toLowerCase())
                .setDisplayName(displayName).setType(type);
        return new PieceAttributeValue().setAttribute(attribute).setValue(value);
    }

    private static PieceOrganizationAttributeValue orgValue(String displayName, AttributeType type, String value) {
        OrganizationPieceAttribute attribute = new OrganizationPieceAttribute().setName(displayName.toLowerCase())
                .setDisplayName(displayName).setType(type);
        return new PieceOrganizationAttributeValue().setAttribute(attribute).setValue(value);
    }

    @Nested
    @DisplayName("text")
    class Text {

        @Test
        @DisplayName("is the name when there is no serial number")
        void nameOnly() {
            assertThat(of(piece("Pastor")).text()).isEqualTo("Pastor");
        }

        @Test
        @DisplayName("appends the serial number")
        void nameAndSerial() {
            assertThat(of(piece("Pastor").setSerialNumber("SN-7")).text()).isEqualTo("Pastor · SN-7");
        }

        @Test
        @DisplayName("ignores a blank serial number and trims the name")
        void blankSerial() {
            assertThat(of(piece("  Pastor ").setSerialNumber("  ")).text()).isEqualTo("Pastor");
        }

        @Test
        @DisplayName("is clipped to the XEYE limit")
        void clipped() {
            String text = of(piece("x".repeat(1200))).text();
            assertThat(text).hasSize(PieceSearchDocument.TEXT_MAX_LENGTH);
        }
    }

    @Nested
    @DisplayName("description")
    class Description {

        @Test
        @DisplayName("is null when the piece has nothing beyond its name")
        void nullWhenEmpty() {
            assertThat(of(piece("Pastor")).description()).isNull();
        }

        @Test
        @DisplayName("lists the piece types, comma separated, in order")
        void types() {
            Piece piece = piece("Pastor");
            piece.setPieceTypes(new LinkedHashSet<>(List.of(
                    new PieceType().setName("Figura"), new PieceType().setName("Fusta"))));
            assertThat(of(piece).description()).isEqualTo("Tipus: Figura, Fusta");
        }

        @Test
        @DisplayName("includes the free-text description")
        void freeText() {
            assertThat(of(piece("Pastor").setDescription("Amb una ovella a l'espatlla")).description())
                    .isEqualTo("Descripció: Amb una ovella a l'espatlla");
        }

        @Test
        @DisplayName("includes the location path from the root to the leaf")
        void locationPath() {
            Location root = new Location().setName("Magatzem");
            Location shelf = new Location().setName("Prestatge 2").setParent(root);
            assertThat(of(piece("Pastor").setLocation(shelf)).description())
                    .isEqualTo("Ubicació: Magatzem / Prestatge 2");
        }

        @Test
        @DisplayName("names the owner member by first and last name")
        void ownerUser() {
            User owner = new User().setName("Joan").setLastName("Martorell").setEmail("j@x.es");
            assertThat(of(piece("Pastor").setOwner(owner)).description()).isEqualTo("Propietari: Joan Martorell");
        }

        @Test
        @DisplayName("falls back to the member e-mail when the name is blank")
        void ownerUserEmail() {
            User owner = new User().setName(" ").setLastName(null).setEmail("j@x.es");
            assertThat(of(piece("Pastor").setOwner(owner)).description()).isEqualTo("Propietari: j@x.es");
        }

        @Test
        @DisplayName("names the owner contact when there is no member owner")
        void ownerContact() {
            Contact contact = new Contact().setName("Maria").setLastName("Pons");
            assertThat(of(piece("Pastor").setOwnerContact(contact)).description()).isEqualTo("Propietari: Maria Pons");
        }

        @Test
        @DisplayName("prefers the member owner over the contact owner")
        void ownerUserBeforeContact() {
            Piece piece = piece("Pastor")
                    .setOwner(new User().setName("Joan").setLastName("M"))
                    .setOwnerContact(new Contact().setName("Maria"));
            assertThat(of(piece).description()).isEqualTo("Propietari: Joan M");
        }

        @Test
        @DisplayName("puts organization attributes before type attributes, one per line")
        void attributesOrder() {
            PieceSearchDocument doc = PieceSearchDocument.of(piece("Pastor"),
                    List.of(typeValue("Material", AttributeType.TEXT, "fusta")),
                    List.of(orgValue("Color", AttributeType.TEXT, "marró")));
            assertThat(doc.description()).isEqualTo("Color: marró\nMaterial: fusta");
        }

        @Test
        @DisplayName("skips blank attribute values")
        void blankValues() {
            PieceSearchDocument doc = PieceSearchDocument.of(piece("Pastor"),
                    List.of(typeValue("Material", AttributeType.TEXT, "  "),
                            typeValue("Pes", AttributeType.DECIMAL, null)),
                    List.of());
            assertThat(doc.description()).isNull();
        }

        @Test
        @DisplayName("renders a multi-select JSON array as a comma list")
        void multiSelect() {
            PieceSearchDocument doc = PieceSearchDocument.of(piece("Pastor"),
                    List.of(typeValue("Estils", AttributeType.MULTI_SELECT, "[\"barroc\",\"popular\"]")), List.of());
            assertThat(doc.description()).isEqualTo("Estils: barroc, popular");
        }

        @Test
        @DisplayName("keeps a multi-select value that is not a JSON array as is")
        void multiSelectRaw() {
            PieceSearchDocument doc = PieceSearchDocument.of(piece("Pastor"),
                    List.of(typeValue("Estils", AttributeType.MULTI_SELECT, "barroc")), List.of());
            assertThat(doc.description()).isEqualTo("Estils: barroc");
        }

        @Test
        @DisplayName("renders booleans as sí / no")
        void booleans() {
            PieceSearchDocument doc = PieceSearchDocument.of(piece("Pastor"),
                    List.of(typeValue("Pintat", AttributeType.BOOLEAN, "true"),
                            typeValue("Trencat", AttributeType.BOOLEAN, "FALSE")), List.of());
            assertThat(doc.description()).isEqualTo("Pintat: sí\nTrencat: no");
        }

        @Test
        @DisplayName("orders the lines: types, description, location, owner, attributes")
        void fullOrder() {
            Piece piece = piece("Pastor").setDescription("desc")
                    .setLocation(new Location().setName("Caixa"))
                    .setOwner(new User().setName("Joan").setLastName("M"));
            piece.setPieceTypes(new LinkedHashSet<>(List.of(new PieceType().setName("Figura"))));
            PieceSearchDocument doc = PieceSearchDocument.of(piece,
                    List.of(typeValue("Material", AttributeType.TEXT, "fusta")), List.of());
            assertThat(doc.description()).isEqualTo(
                    "Tipus: Figura\nDescripció: desc\nUbicació: Caixa\nPropietari: Joan M\nMaterial: fusta");
        }

        @Test
        @DisplayName("is clipped to the XEYE limit")
        void clipped() {
            String description = of(piece("Pastor").setDescription("y".repeat(5000))).description();
            assertThat(description).hasSize(PieceSearchDocument.DESCRIPTION_MAX_LENGTH);
        }
    }

    @Nested
    @DisplayName("params and element input")
    class ParamsAndInput {

        @Test
        @DisplayName("carry the piece id")
        void pieceId() {
            assertThat(of(piece("Pastor")).params()).isEqualTo(Map.of("pieceId", 42));
        }

        @Test
        @DisplayName("convert to the element payload field by field")
        void toElementInput() {
            ElementInput input = of(piece("Pastor").setDescription("d")).toElementInput();
            assertThat(input.text()).isEqualTo("Pastor");
            assertThat(input.description()).isEqualTo("Descripció: d");
            assertThat(input.params()).isEqualTo(Map.of("pieceId", 42));
        }
    }

    @Nested
    @DisplayName("locationPath")
    class LocationPath {

        @Test
        @DisplayName("is null without location")
        void nullLocation() {
            assertThat(PieceSearchDocument.locationPath(null)).isNull();
        }

        @Test
        @DisplayName("skips unnamed ancestors")
        void unnamedAncestor() {
            Location root = new Location().setName(" ");
            Location leaf = new Location().setName("Caixa").setParent(root);
            assertThat(PieceSearchDocument.locationPath(leaf)).isEqualTo("Caixa");
        }

        @Test
        @DisplayName("stops on a cyclic tree instead of looping forever")
        void cycle() {
            Location a = new Location().setName("A");
            Location b = new Location().setName("B").setParent(a);
            a.setParent(b);
            String path = PieceSearchDocument.locationPath(a);
            assertThat(path.split(" / ")).hasSize(PieceSearchDocument.MAX_LOCATION_DEPTH);
        }
    }

    @Nested
    @DisplayName("displayValue")
    class DisplayValue {

        @Test
        @DisplayName("is null for null or blank")
        void blank() {
            assertThat(PieceSearchDocument.displayValue(AttributeType.TEXT, null)).isNull();
            assertThat(PieceSearchDocument.displayValue(AttributeType.TEXT, "  ")).isNull();
        }

        @Test
        @DisplayName("trims plain values")
        void trims() {
            assertThat(PieceSearchDocument.displayValue(AttributeType.INTEGER, " 12 ")).isEqualTo("12");
        }

        @Test
        @DisplayName("is null for an empty multi-select array")
        void emptyMultiSelect() {
            assertThat(PieceSearchDocument.displayValue(AttributeType.MULTI_SELECT, "[]")).isNull();
        }

        @Test
        @DisplayName("keeps a non true/false boolean value as is")
        void oddBoolean() {
            assertThat(PieceSearchDocument.displayValue(AttributeType.BOOLEAN, "maybe")).isEqualTo("maybe");
        }
    }
}
