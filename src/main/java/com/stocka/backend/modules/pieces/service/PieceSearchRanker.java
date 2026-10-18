package com.stocka.backend.modules.pieces.service;

import java.util.List;
import java.util.Optional;

import com.stocka.backend.modules.organizations.entity.Organization;

/**
 * Ranks the pieces of an organization against a free-text query.
 *
 * <p>Implemented by the semantic search module. {@link PieceService} asks the ranker first and
 * falls back to its {@code LIKE} filter when the ranker returns {@link Optional#empty()}, so the
 * listing keeps working when the feature is off, the organization is not indexed yet or the
 * search service is unreachable.
 */
public interface PieceSearchRanker {

    /**
     * Returns the ids of the pieces that match {@code query}, best match first.
     *
     * @param organization organization whose pieces are searched
     * @param query        free text typed by the user
     * @return the ranked piece ids (possibly empty when nothing matches), or {@link Optional#empty()}
     *         when the ranker cannot answer and the caller must fall back
     */
    Optional<List<Integer>> rank(Organization organization, String query);
}
