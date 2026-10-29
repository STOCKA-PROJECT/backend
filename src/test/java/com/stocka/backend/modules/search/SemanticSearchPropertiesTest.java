package com.stocka.backend.modules.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SemanticSearchProperties")
class SemanticSearchPropertiesTest {

    @Nested
    @DisplayName("isConfigured")
    class IsConfigured {

        @Test
        @DisplayName("is false by default")
        void defaults() {
            assertThat(new SemanticSearchProperties().isConfigured()).isFalse();
        }

        @Test
        @DisplayName("needs both the flag and a key")
        void flagAndKey() {
            SemanticSearchProperties props = new SemanticSearchProperties();
            props.setEnabled(true);
            assertThat(props.isConfigured()).isFalse();
            props.setApiKey("  ");
            assertThat(props.isConfigured()).isFalse();
            props.setApiKey(" key ");
            assertThat(props.isConfigured()).isTrue();
            assertThat(props.getApiKey()).isEqualTo("key");
            props.setEnabled(false);
            assertThat(props.isConfigured()).isFalse();
        }
    }

    @Nested
    @DisplayName("values")
    class Values {

        @Test
        @DisplayName("builds the list name from the prefix and the organization id")
        void listName() {
            SemanticSearchProperties props = new SemanticSearchProperties();
            assertThat(props.listNameFor(12)).isEqualTo("stocka-org-12");
            props.setListPrefix("belen-");
            assertThat(props.listNameFor(12)).isEqualTo("belen-12");
        }

        @Test
        @DisplayName("clamps maxResults to the XEYE range")
        void maxResults() {
            SemanticSearchProperties props = new SemanticSearchProperties();
            props.setMaxResults(5000);
            assertThat(props.getMaxResults()).isEqualTo(SemanticSearchProperties.XEYE_MAX_RESULTS);
            props.setMaxResults(0);
            assertThat(props.getMaxResults()).isEqualTo(1);
        }

        @Test
        @DisplayName("clamps minScore to 0..1")
        void minScore() {
            SemanticSearchProperties props = new SemanticSearchProperties();
            props.setMinScore(-1);
            assertThat(props.getMinScore()).isZero();
            props.setMinScore(3);
            assertThat(props.getMinScore()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("waits 30 minutes after the last change by default")
        void trainingDelayDefault() {
            assertThat(new SemanticSearchProperties().getTrainingDelay()).isEqualTo(Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("tolerates null durations and strings")
        void nullSafe() {
            SemanticSearchProperties props = new SemanticSearchProperties();
            props.setTrainingDelay(null);
            props.setTimeout(null);
            props.setBaseUrl(null);
            props.setEmbeddingModel(null);
            props.setListPrefix(null);
            assertThat(props.getTrainingDelay()).isEqualTo(Duration.ZERO);
            assertThat(props.getTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(props.getBaseUrl()).isEmpty();
            assertThat(props.getEmbeddingModel()).isEmpty();
            assertThat(props.getListPrefix()).isEmpty();
        }
    }
}
