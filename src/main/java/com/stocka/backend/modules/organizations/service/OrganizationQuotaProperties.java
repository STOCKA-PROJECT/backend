package com.stocka.backend.modules.organizations.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anti-DoS quotas applied at the organization and user level (issue #21). Loaded from
 * {@code stocka.organizations.quota.*}.
 *
 * <p>Limits enforced:
 * <ul>
 *   <li>{@code maxOrgsPerUser} — maximum number of organizations a single user can own.</li>
 *   <li>{@code maxPiecesPerOrg} — maximum number of (non-deleted) pieces per organization.</li>
 *   <li>{@code maxBytesPerOrg} — maximum total size in bytes of (non-deleted) piece attachments
 *       across the organization.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "stocka.organizations.quota")
public class OrganizationQuotaProperties {
    private int maxOrgsPerUser = 10;
    private long maxPiecesPerOrg = 10_000L;
    private long maxBytesPerOrg = 53_687_091_200L; // 50 GiB

    public int getMaxOrgsPerUser() { return maxOrgsPerUser; }
    public void setMaxOrgsPerUser(int v) { this.maxOrgsPerUser = v; }

    public long getMaxPiecesPerOrg() { return maxPiecesPerOrg; }
    public void setMaxPiecesPerOrg(long v) { this.maxPiecesPerOrg = v; }

    public long getMaxBytesPerOrg() { return maxBytesPerOrg; }
    public void setMaxBytesPerOrg(long v) { this.maxBytesPerOrg = v; }
}
