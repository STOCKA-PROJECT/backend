package com.stocka.backend.modules.storage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("R2Properties")
class R2PropertiesTest {

    @Nested
    @DisplayName("validateProductionStorage")
    class Validation {

        @Test
        @DisplayName("should pass when useLocal=false even if prod profile is active")
        void should_pass_whenUseLocalFalse() {
            R2Properties props = propertiesWithProfiles("prod");
            props.setUseLocal(false);
            assertThatCode(props::validateProductionStorage).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should pass when useLocal=true and no profile contains 'prod'")
        void should_pass_whenUseLocalTrueInDev() {
            R2Properties props = propertiesWithProfiles("dev");
            props.setUseLocal(true);
            assertThatCode(props::validateProductionStorage).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should pass when useLocal=true and no profile is active")
        void should_pass_whenUseLocalTrueAndNoProfiles() {
            R2Properties props = propertiesWithProfiles();
            props.setUseLocal(true);
            assertThatCode(props::validateProductionStorage).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should fail when useLocal=true and 'prod' profile is active")
        void should_fail_whenUseLocalTrueWithProdProfile() {
            R2Properties props = propertiesWithProfiles("prod");
            props.setUseLocal(true);
            assertThatThrownBy(props::validateProductionStorage)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("R2_USE_LOCAL")
                    .hasMessageContaining("prod");
        }

        @Test
        @DisplayName("should fail when useLocal=true and any active profile contains 'prod' as substring")
        void should_fail_whenAnyProfileContainsProd() {
            R2Properties props = propertiesWithProfiles("staging", "production");
            props.setUseLocal(true);
            assertThatThrownBy(props::validateProductionStorage)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static R2Properties propertiesWithProfiles(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        R2Properties props = new R2Properties();
        ReflectionTestUtils.setField(props, "environment", env);
        return props;
    }
}
