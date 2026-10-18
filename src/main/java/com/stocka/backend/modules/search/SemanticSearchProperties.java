package com.stocka.backend.modules.search;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the semantic piece search backed by XEYE ({@code https://xeye.es}).
 *
 * <p>Bound from {@code stocka.search.xeye.*}. The feature is off unless {@link #isEnabled()} and an
 * API key are both set, in which case every organization gets its own XEYE list (named with
 * {@link #getListPrefix()} + organization id) that is kept in sync with its pieces and queried
 * instead of the plain {@code LIKE} filter.
 */
@ConfigurationProperties(prefix = "stocka.search.xeye")
public class SemanticSearchProperties {

    /** Hard cap of the XEYE search endpoint ({@code limit} is 1..1000). */
    public static final int XEYE_MAX_RESULTS = 1000;
    /** Hard cap of the XEYE search term length. */
    public static final int XEYE_MAX_TERM_LENGTH = 500;

    private boolean enabled = false;
    private String baseUrl = "https://api.xeye.es/v1";
    private String apiKey = "";
    private String listPrefix = "stocka-org-";
    private int maxResults = 200;
    private double minScore = 0.3;
    private Duration trainingDelay = Duration.ofMinutes(10);
    private Duration timeout = Duration.ofSeconds(10);
    private boolean llmEnrichment = true;
    private String embeddingModel = "";

    /**
     * Whether the integration can actually be used: enabled and with an API key.
     *
     * @return {@code true} when searches and indexing must go through XEYE
     */
    public boolean isConfigured() {
        return enabled && !apiKey.isBlank();
    }

    /**
     * Name of the XEYE list that holds the pieces of an organization.
     *
     * @param organizationId id of the organization
     * @return the list name, unique per organization within the XEYE account
     */
    public String listNameFor(Integer organizationId) {
        return listPrefix + organizationId;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl == null ? "" : baseUrl.trim(); }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey.trim(); }

    public String getListPrefix() { return listPrefix; }
    public void setListPrefix(String listPrefix) { this.listPrefix = listPrefix == null ? "" : listPrefix.trim(); }

    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) {
        this.maxResults = Math.max(1, Math.min(XEYE_MAX_RESULTS, maxResults));
    }

    public double getMinScore() { return minScore; }
    public void setMinScore(double minScore) { this.minScore = Math.max(0.0, Math.min(1.0, minScore)); }

    public Duration getTrainingDelay() { return trainingDelay; }
    public void setTrainingDelay(Duration trainingDelay) {
        this.trainingDelay = trainingDelay == null ? Duration.ZERO : trainingDelay;
    }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout; }

    public boolean isLlmEnrichment() { return llmEnrichment; }
    public void setLlmEnrichment(boolean llmEnrichment) { this.llmEnrichment = llmEnrichment; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel == null ? "" : embeddingModel.trim();
    }
}
