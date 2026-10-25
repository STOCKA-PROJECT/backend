package com.stocka.backend.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /auth/handoff/exchange}: the ticket obtained from {@code POST /auth/handoff} and
 * whether the new session should be persistent (the editor asks for it on phones, whose browsers drop
 * session cookies when they are closed).
 */
public class HandoffExchangeRequestDto {
    @NotBlank
    private String ticket;

    private boolean rememberMe;

    public String getTicket() { return ticket; }
    public HandoffExchangeRequestDto setTicket(String ticket) { this.ticket = ticket; return this; }

    public boolean isRememberMe() { return rememberMe; }

    public HandoffExchangeRequestDto setRememberMe(boolean rememberMe) { this.rememberMe = rememberMe; return this; }
}
