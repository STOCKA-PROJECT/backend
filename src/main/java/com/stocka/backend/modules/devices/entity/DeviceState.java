package com.stocka.backend.modules.devices.entity;

/**
 * Lifecycle states a belén player reports in its heartbeat. Mirrors the {@code PlayerCore} state
 * machine of {@code rpi4-system}: booting, safe mode (hardware off after a failure), idle with a
 * show loaded, playing, test mode and error.
 */
public enum DeviceState {
    BOOT,
    SAFE,
    IDLE,
    PLAYING,
    TEST,
    ERROR;

    /** Regular expression accepting exactly the state names, for Bean Validation on the heartbeat. */
    public static final String PATTERN = "BOOT|SAFE|IDLE|PLAYING|TEST|ERROR";
}
