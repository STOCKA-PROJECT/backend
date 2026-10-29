package com.stocka.backend.modules.pieces.dto;

import java.util.List;

/**
 * Outcome of one batch of {@code POST /admin/attachments/thumbnails/backfill}.
 *
 * @param processed number of IMAGE attachments without a thumbnail the batch looked at
 * @param generated how many of them now have a thumbnail
 * @param failed    ids of the ones that could not be decoded or stored (they stay without a
 *                  thumbnail, are counted in {@code remaining} and are retried by the next batch)
 * @param remaining IMAGE attachments still without a thumbnail after this batch
 */
public record ThumbnailBackfillReport(int processed, int generated, List<Integer> failed, long remaining) {
}
