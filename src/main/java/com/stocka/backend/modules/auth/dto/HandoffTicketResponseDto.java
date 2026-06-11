package com.stocka.backend.modules.auth.dto;

/**
 * Response of {@code POST /auth/handoff}: a short-lived ticket the caller passes to a separate
 * front-end app (opened in a new window) which exchanges it for its own session.
 *
 * @param ticket signed, short-lived handoff JWT
 */
public record HandoffTicketResponseDto(String ticket) {
}
