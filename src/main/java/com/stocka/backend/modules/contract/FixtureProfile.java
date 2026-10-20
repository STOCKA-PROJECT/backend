package com.stocka.backend.modules.contract;

/**
 * A DMX fixture profile from the catalog's {@code profiles.moving_head} section: how many channels
 * the fixture occupies from its start address and the physical ranges of its parameters. The channel
 * map itself is only needed by the player.
 *
 * @param id       profile id, e.g. {@code varytec-hero-wash-712z}
 * @param name     human-readable name
 * @param channels DMX channels the fixture occupies
 * @param panMax   pan range in degrees
 * @param tiltMax  tilt range in degrees
 * @param zoomMin  narrowest beam angle in degrees
 * @param zoomMax  widest beam angle in degrees
 */
public record FixtureProfile(String id, String name, int channels, double panMax, double tiltMax, double zoomMin, double zoomMax) {

    /**
     * Last DMX channel the fixture uses when its first channel is {@code address}.
     *
     * @param address start address (1–512)
     * @return the inclusive last channel
     */
    public int lastChannel(int address) {
        return address + channels - 1;
    }
}
