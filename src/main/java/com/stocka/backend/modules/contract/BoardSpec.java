package com.stocka.backend.modules.contract;

import java.util.Map;
import java.util.Set;

/**
 * Pin rules of a controller board, taken from the catalog's {@code boards} section.
 *
 * @param id            board id, e.g. {@code rpi4b}
 * @param gpioMin       lowest valid GPIO number
 * @param gpioMax       highest valid GPIO number
 * @param reserved      pins that must not be used, with the reason
 * @param warnings      pins that work but deserve a notice, with the reason
 * @param ws281xChannel PWM/SPI/PCM pins that can drive a WS281x strip, mapped to their channel
 * @param ws281xWarning WS281x pins that work but deserve a notice (e.g. PCM interferes with audio), with the reason
 * @param dmaMin        lowest DMA channel
 * @param dmaMax        highest DMA channel
 * @param dmaAvoid      DMA channels that work but are not recommended
 */
public record BoardSpec(
        String id,
        int gpioMin,
        int gpioMax,
        Map<Integer, String> reserved,
        Map<Integer, String> warnings,
        Map<Integer, Integer> ws281xChannel,
        Map<Integer, String> ws281xWarning,
        int dmaMin,
        int dmaMax,
        Set<Integer> dmaAvoid
) {
    /**
     * Whether {@code pin} is a valid GPIO number on this board (reserved pins included).
     *
     * @param pin GPIO number
     * @return {@code true} when inside the board range
     */
    public boolean isValidGpio(int pin) {
        return pin >= gpioMin && pin <= gpioMax;
    }

    /**
     * Whether {@code pin} is reserved and must not be used.
     *
     * @param pin GPIO number
     * @return {@code true} when reserved
     */
    public boolean isReserved(int pin) {
        return reserved.containsKey(pin);
    }

    /**
     * Notice attached to a WS281x pin, if any.
     *
     * @param pin GPIO number
     * @return the warning text, or {@code null} when the pin has none
     */
    public String ws281xWarning(int pin) {
        return ws281xWarning.get(pin);
    }
}
