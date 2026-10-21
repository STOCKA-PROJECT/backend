package com.stocka.backend.modules.devices.dto;

import jakarta.validation.constraints.Size;

/**
 * One alert a player reports in its heartbeat, e.g. a motor that reached its hard time limit without
 * its limit switch firing ({@code motor_timeout}) or a sensor that stopped the show.
 *
 * @param code    machine code ({@code motor_timeout}, {@code sensor_panic}, {@code sensor_stop_show})
 * @param port    port the alert is about, or {@code null}
 * @param message human-readable text (Catalan), as the player wrote it
 * @param at      ISO-8601 instant on the player's clock
 */
public record DeviceAlertDto(
        @Size(max = 40) String code,
        @Size(max = 160) String port,
        @Size(max = 500) String message,
        @Size(max = 40) String at
) { }
