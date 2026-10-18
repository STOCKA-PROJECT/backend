package com.stocka.backend.modules.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("OAuthScope")
class OAuthScopeTest {

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @ParameterizedTest
        @EnumSource(OAuthScope.class)
        @DisplayName("every scope round-trips through its wire value")
        void roundTrip(OAuthScope scope) {
            assertThat(OAuthScope.fromValue(scope.value())).contains(scope);
        }

        @Test
        @DisplayName("unknown, blank and null values are empty")
        void unknown() {
            assertThat(OAuthScope.fromValue("admin")).isEmpty();
            assertThat(OAuthScope.fromValue("  ")).isEmpty();
            assertThat(OAuthScope.fromValue(null)).isEmpty();
        }

        @Test
        @DisplayName("parse keeps known scopes in order and drops unknown ones")
        void parseCollection() {
            assertThat(OAuthScope.parse(List.of("delete", "nope", "read", "delete")))
                    .containsExactly(OAuthScope.DELETE, OAuthScope.READ);
            assertThat(OAuthScope.parse(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("implication")
    class Implication {

        @Test
        @DisplayName("every write scope implies read")
        void writeImpliesRead() {
            assertThat(OAuthScope.INVENTORY_WRITE.implies(OAuthScope.READ)).isTrue();
            assertThat(OAuthScope.TIMELINES_WRITE.implies(OAuthScope.READ)).isTrue();
            assertThat(OAuthScope.HARDWARE_WRITE.implies(OAuthScope.READ)).isTrue();
            assertThat(OAuthScope.ORG_MANAGE.implies(OAuthScope.READ)).isTrue();
            assertThat(OAuthScope.DELETE.implies(OAuthScope.READ)).isTrue();
        }

        @Test
        @DisplayName("offline_access implies nothing and read implies only itself")
        void narrow() {
            assertThat(OAuthScope.OFFLINE_ACCESS.implies(OAuthScope.READ)).isFalse();
            assertThat(OAuthScope.READ.implies(OAuthScope.INVENTORY_WRITE)).isFalse();
            assertThat(OAuthScope.INVENTORY_WRITE.implies(OAuthScope.DELETE)).isFalse();
        }

        @Test
        @DisplayName("satisfies looks across the granted set")
        void satisfies() {
            assertThat(OAuthScope.satisfies(Set.of(OAuthScope.TIMELINES_WRITE), OAuthScope.READ)).isTrue();
            assertThat(OAuthScope.satisfies(Set.of(OAuthScope.READ), OAuthScope.DELETE)).isFalse();
            assertThat(OAuthScope.satisfies(null, OAuthScope.READ)).isFalse();
        }
    }

    @Nested
    @DisplayName("catalog")
    class Catalog {

        @Test
        @DisplayName("resource scopes exclude offline_access; all values include it")
        void lists() {
            assertThat(OAuthScope.resourceScopeValues()).doesNotContain("offline_access").contains("read", "delete");
            assertThat(OAuthScope.allValues()).contains("offline_access");
        }

        @Test
        @DisplayName("delete and org:manage are not pre-checked; offline_access is not a permission")
        void defaults() {
            assertThat(OAuthScope.DELETE.isCheckedByDefault()).isFalse();
            assertThat(OAuthScope.ORG_MANAGE.isCheckedByDefault()).isFalse();
            assertThat(OAuthScope.READ.isCheckedByDefault()).isTrue();
            assertThat(OAuthScope.OFFLINE_ACCESS.isPermission()).isFalse();
            assertThat(OAuthScope.READ.messageKey()).isEqualTo("oauth.scope.read");
        }
    }
}
