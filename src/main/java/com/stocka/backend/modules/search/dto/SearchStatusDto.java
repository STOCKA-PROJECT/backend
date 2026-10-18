package com.stocka.backend.modules.search.dto;

import java.time.LocalDateTime;

/**
 * State of the semantic index of one organization.
 *
 * @param organizationId         organization id
 * @param enabled                whether the server has the XEYE integration configured
 * @param indexed                whether the organization already has its XEYE list
 * @param indexedPieces          pieces with an element in XEYE
 * @param totalPieces            active pieces of the organization
 * @param trainingPending        whether changes are waiting for the next training
 * @param lastTrainingLaunchedAt when the last training was launched, or {@code null}
 */
public record SearchStatusDto(
        Integer organizationId,
        boolean enabled,
        boolean indexed,
        long indexedPieces,
        long totalPieces,
        boolean trainingPending,
        LocalDateTime lastTrainingLaunchedAt
) {
}
