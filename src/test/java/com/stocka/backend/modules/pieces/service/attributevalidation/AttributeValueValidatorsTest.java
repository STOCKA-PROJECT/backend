package com.stocka.backend.modules.pieces.service.attributevalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;

/**
 * Focused tests per validator strategy. Each {@code @Nested} class targets one type and exercises
 * happy path, required vs optional handling, and the type-specific failure modes.
 */
@DisplayName("AttributeValueValidators")
class AttributeValueValidatorsTest {

    private static AttributeValidatorsDto rules() {
        return new AttributeValidatorsDto();
    }

    @Nested
    @DisplayName("TextAttributeValidator")
    class Text {
        private final TextAttributeValidator sut = new TextAttributeValidator();

        @Test
        void should_returnNull_when_optionalAndBlank() {
            assertThat(sut.validateAndNormalize("  ", rules(), false)).isNull();
        }

        @Test
        void should_throw400_when_requiredAndBlank() {
            assertThatThrownBy(() -> sut.validateAndNormalize("", rules(), true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_trim_value() {
            assertThat(sut.validateAndNormalize("  hi ", rules(), true)).isEqualTo("hi");
        }

        @Test
        void should_throw400_when_exceedsMaxLength() {
            AttributeValidatorsDto r = rules().setMaxLength(3);
            assertThatThrownBy(() -> sut.validateAndNormalize("toolong", r, true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_throw400_when_belowMinLength() {
            AttributeValidatorsDto r = rules().setMinLength(5);
            assertThatThrownBy(() -> sut.validateAndNormalize("hey", r, true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_throw400_when_regexMismatch() {
            AttributeValidatorsDto r = rules().setRegex("\\d+");
            assertThatThrownBy(() -> sut.validateAndNormalize("abc", r, true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_passWhenRegexMatches() {
            AttributeValidatorsDto r = rules().setRegex("\\d+");
            assertThat(sut.validateAndNormalize("123", r, true)).isEqualTo("123");
        }
    }

    @Nested
    @DisplayName("LongTextAttributeValidator")
    class LongText {
        private final LongTextAttributeValidator sut = new LongTextAttributeValidator();

        @Test
        void should_acceptNewlines_andNotTrimContent() {
            String input = "line1\n  line2\n";
            assertThat(sut.validateAndNormalize(input, rules(), true)).isEqualTo(input);
        }

        @Test
        void should_throw400_when_exceedsMaxLength() {
            AttributeValidatorsDto r = rules().setMaxLength(5);
            assertThatThrownBy(() -> sut.validateAndNormalize("123456", r, true))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("IntegerAttributeValidator")
    class Integer {
        private final IntegerAttributeValidator sut = new IntegerAttributeValidator();

        @Test
        void should_acceptValidInteger() {
            assertThat(sut.validateAndNormalize("42", rules(), true)).isEqualTo("42");
        }

        @Test
        void should_throw400_when_notNumeric() {
            assertThatThrownBy(() -> sut.validateAndNormalize("abc", rules(), true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_throw400_when_belowMin() {
            AttributeValidatorsDto r = rules().setMin(BigDecimal.TEN);
            assertThatThrownBy(() -> sut.validateAndNormalize("3", r, true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_throw400_when_aboveMax() {
            AttributeValidatorsDto r = rules().setMax(BigDecimal.valueOf(100));
            assertThatThrownBy(() -> sut.validateAndNormalize("999", r, true))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("DecimalAttributeValidator")
    class Decimal {
        private final DecimalAttributeValidator sut = new DecimalAttributeValidator();

        @Test
        void should_acceptValidDecimal() {
            assertThat(sut.validateAndNormalize("3.14", rules(), true)).isEqualTo("3.14");
        }

        @Test
        void should_throw400_when_notNumeric() {
            assertThatThrownBy(() -> sut.validateAndNormalize("not a number", rules(), true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_throw400_when_tooManyDecimals() {
            AttributeValidatorsDto r = rules().setDecimals(2);
            assertThatThrownBy(() -> sut.validateAndNormalize("1.234", r, true))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("PriceAttributeValidator")
    class Price {
        private final PriceAttributeValidator sut = new PriceAttributeValidator();

        @Test
        void should_padTo2Decimals_byDefault() {
            assertThat(sut.validateAndNormalize("12", rules(), true)).isEqualTo("12.00");
        }

        @Test
        void should_appendCurrency_whenConfigured() {
            AttributeValidatorsDto r = rules().setCurrency("EUR");
            assertThat(sut.validateAndNormalize("9.99", r, true)).isEqualTo("9.99 EUR");
        }

        @Test
        void should_acceptInlineCurrency() {
            assertThat(sut.validateAndNormalize("5.00 USD", rules(), true)).isEqualTo("5.00 USD");
        }

        @Test
        void should_throw400_when_negative() {
            assertThatThrownBy(() -> sut.validateAndNormalize("-1.00", rules(), true))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("DateAttributeValidator")
    class Date {
        private final DateAttributeValidator sut = new DateAttributeValidator();

        @Test
        void should_acceptValidIsoDate() {
            assertThat(sut.validateAndNormalize("2026-04-25", rules(), true)).isEqualTo("2026-04-25");
        }

        @Test
        void should_throw400_when_notIso() {
            assertThatThrownBy(() -> sut.validateAndNormalize("25/04/2026", rules(), true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_throw400_when_futureNotAllowed() {
            AttributeValidatorsDto r = rules().setAllowFuture(false);
            assertThatThrownBy(() -> sut.validateAndNormalize("3000-01-01", r, true))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("DateTimeAttributeValidator")
    class DateTime {
        private final DateTimeAttributeValidator sut = new DateTimeAttributeValidator();

        @Test
        void should_acceptValidIsoDateTime() {
            assertThat(sut.validateAndNormalize("2026-04-25T10:00:00", rules(), true))
                    .isEqualTo("2026-04-25T10:00");
        }

        @Test
        void should_throw400_when_invalidFormat() {
            assertThatThrownBy(() -> sut.validateAndNormalize("2026-04-25 10:00", rules(), true))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("BooleanAttributeValidator")
    class Boolean {
        private final BooleanAttributeValidator sut = new BooleanAttributeValidator();

        @Test
        void should_acceptTrueCaseInsensitive() {
            assertThat(sut.validateAndNormalize("TRUE", rules(), true)).isEqualTo("true");
        }

        @Test
        void should_acceptFalseCaseInsensitive() {
            assertThat(sut.validateAndNormalize("False", rules(), true)).isEqualTo("false");
        }

        @Test
        void should_rejectYes() {
            assertThatThrownBy(() -> sut.validateAndNormalize("yes", rules(), true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_rejectOne() {
            assertThatThrownBy(() -> sut.validateAndNormalize("1", rules(), true))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("SelectAttributeValidator")
    class Select {
        private final SelectAttributeValidator sut = new SelectAttributeValidator();

        @Test
        void should_acceptValueInOptions() {
            AttributeValidatorsDto r = rules().setOptions(List.of("red", "blue"));
            assertThat(sut.validateAndNormalize("red", r, true)).isEqualTo("red");
        }

        @Test
        void should_throw400_when_valueNotInOptions() {
            AttributeValidatorsDto r = rules().setOptions(List.of("red", "blue"));
            assertThatThrownBy(() -> sut.validateAndNormalize("green", r, true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_throw500_when_optionsMissing() {
            assertThatThrownBy(() -> sut.validateAndNormalize("red", rules(), true))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                    .isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("MultiSelectAttributeValidator")
    class MultiSelect {
        private final MultiSelectAttributeValidator sut = new MultiSelectAttributeValidator();

        @Test
        void should_normalizeJsonArrayToJson() {
            AttributeValidatorsDto r = rules().setOptions(List.of("a", "b", "c"));
            String result = sut.validateAndNormalize("[\"a\",\"b\"]", r, true);
            assertThat(result).isEqualTo("[\"a\",\"b\"]");
        }

        @Test
        void should_acceptCommaSeparated_andNormalizeToJson() {
            AttributeValidatorsDto r = rules().setOptions(List.of("a", "b", "c"));
            String result = sut.validateAndNormalize(" a, b ", r, true);
            assertThat(result).isEqualTo("[\"a\",\"b\"]");
        }

        @Test
        void should_throw400_when_anyValueNotInOptions() {
            AttributeValidatorsDto r = rules().setOptions(List.of("a", "b"));
            assertThatThrownBy(() -> sut.validateAndNormalize("[\"a\",\"x\"]", r, true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_throw400_when_belowMinItems() {
            AttributeValidatorsDto r = rules().setOptions(List.of("a", "b", "c")).setMinItems(2);
            assertThatThrownBy(() -> sut.validateAndNormalize("[\"a\"]", r, true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_throw400_when_aboveMaxItems() {
            AttributeValidatorsDto r = rules().setOptions(List.of("a", "b", "c")).setMaxItems(1);
            assertThatThrownBy(() -> sut.validateAndNormalize("[\"a\",\"b\"]", r, true))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("UrlAttributeValidator")
    class Url {
        private final UrlAttributeValidator sut = new UrlAttributeValidator();

        @Test
        void should_acceptHttpsUrl() {
            assertThatCode(() -> sut.validateAndNormalize("https://stocka.local/x", rules(), true))
                    .doesNotThrowAnyException();
        }

        @Test
        void should_throw400_when_notUrl() {
            assertThatThrownBy(() -> sut.validateAndNormalize("not a url", rules(), true))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void should_throw400_when_unsupportedScheme() {
            assertThatThrownBy(() -> sut.validateAndNormalize("ftp://stocka.local", rules(), true))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("EmailAttributeValidator")
    class Email {
        private final EmailAttributeValidator sut = new EmailAttributeValidator();

        @Test
        void should_acceptValidEmail() {
            assertThat(sut.validateAndNormalize("foo@bar.com", rules(), true)).isEqualTo("foo@bar.com");
        }

        @Test
        void should_throw400_when_invalid() {
            assertThatThrownBy(() -> sut.validateAndNormalize("foo@bar", rules(), true))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }
}
