package com.stocka.backend.modules.search.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Link between a piece and the XEYE element that represents it.
 *
 * <p>Keeps the organization id on purpose: a soft-deleted piece can no longer be loaded, yet its
 * element must still be removed from the right list.
 */
@Entity
@Table(name = "search_entries")
public class SearchEntry {

    @Id
    @Column(name = "piece_id")
    private Integer pieceId;

    @Column(name = "organization_id", nullable = false)
    private Integer organizationId;

    @Column(name = "xeye_element_id", nullable = false)
    private Long xeyeElementId;

    @Column(name = "indexed_at", nullable = false)
    private LocalDateTime indexedAt;

    public Integer getPieceId() { return pieceId; }
    public SearchEntry setPieceId(Integer pieceId) { this.pieceId = pieceId; return this; }

    public Integer getOrganizationId() { return organizationId; }
    public SearchEntry setOrganizationId(Integer organizationId) { this.organizationId = organizationId; return this; }

    public Long getXeyeElementId() { return xeyeElementId; }
    public SearchEntry setXeyeElementId(Long xeyeElementId) { this.xeyeElementId = xeyeElementId; return this; }

    public LocalDateTime getIndexedAt() { return indexedAt; }
    public SearchEntry setIndexedAt(LocalDateTime indexedAt) { this.indexedAt = indexedAt; return this; }
}
