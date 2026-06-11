package com.stocka.backend.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /auth/handoff/exchange}: the ticket obtained from {@code POST /auth/handoff}.
 */
public class HandoffExchangeRequestDto {
    @NotBlank
    private String ticket;

    public String getTicket() { return ticket; }
    public HandoffExchangeRequestDto setTicket(String ticket) { this.ticket = ticket; return this; }
}
