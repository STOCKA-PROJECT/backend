package com.stocka.backend.modules.contract;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A measured-sensor model from the catalog's {@code profiles.sensor} section: how it is wired, the
 * I2C addresses it answers on and the quantities it measures with their unit and range. Ports of
 * kind {@code sensor} with {@code source: measured} name one through {@code model}.
 *
 * @param id         model id, e.g. {@code vl53l0x}
 * @param name       human-readable name
 * @param wiring     {@code i2c}, {@code onewire} or {@code trig_echo}
 * @param addresses  I2C addresses the chip accepts (the first is the default); empty for other wirings
 * @param quantities measured quantities by id ({@code distance}, {@code temperature}…), in catalog order
 * @param pollMs     default reading period in milliseconds
 */
public record SensorProfile(String id, String name, String wiring, List<Integer> addresses,
                            Map<String, Quantity> quantities, int pollMs) {

    /** Wiring of a chip on the I2C bus (GPIO 2 and 3). */
    public static final String WIRING_I2C = "i2c";
    /** Wiring of a 1-Wire chip on one GPIO ({@code pin}). */
    public static final String WIRING_ONEWIRE = "onewire";
    /** Wiring of an ultrasonic module on two GPIOs ({@code pinTrig}, {@code pinEcho}). */
    public static final String WIRING_TRIG_ECHO = "trig_echo";

    /**
     * A quantity a model measures.
     *
     * @param unit unit of {@code threshold} and of the readings, e.g. {@code cm}
     * @param min  lowest value the chip reports
     * @param max  highest value the chip reports
     */
    public record Quantity(String unit, BigDecimal min, BigDecimal max) {
    }

    /**
     * The quantity a sensor watches: the requested one, or the only one of the model when none is given.
     *
     * @param requested {@code quantity} of the port config, may be {@code null}
     * @return the quantity id, or empty when it is missing on a multi-quantity model or not measured
     */
    public Optional<String> resolveQuantity(String requested) {
        if (requested == null) {
            return quantities.size() == 1 ? Optional.of(quantities.keySet().iterator().next()) : Optional.empty();
        }
        return quantities.containsKey(requested) ? Optional.of(requested) : Optional.empty();
    }

    /**
     * The I2C address a sensor answers on: the configured one or the model's default.
     *
     * @param configured {@code address} of the port config, may be {@code null}
     * @return the address, or empty for models outside the I2C bus
     */
    public Optional<Integer> effectiveAddress(Integer configured) {
        if (!WIRING_I2C.equals(wiring)) {
            return Optional.empty();
        }
        if (configured != null) {
            return Optional.of(configured);
        }
        return addresses.isEmpty() ? Optional.empty() : Optional.of(addresses.get(0));
    }
}
