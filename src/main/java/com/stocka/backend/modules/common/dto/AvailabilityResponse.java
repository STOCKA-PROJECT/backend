package com.stocka.backend.modules.common.dto;

/**
 * Result of a uniqueness/format check for an identifier such as a username or an
 * organization slug. When {@link #available()} is {@code true}, {@link #reason()} is
 * {@code null}; otherwise it carries the rejection cause.
 *
 * @param available whether the identifier can be used
 * @param reason    rejection cause; {@code null} when {@code available} is {@code true}
 */
public record AvailabilityResponse(boolean available, Reason reason) {

    /**
     * Reason an identifier is not available.
     */
    public enum Reason {
        /** The identifier is already used by an existing record. */
        TAKEN,
        /** The identifier is in the reserved list and cannot be claimed. */
        RESERVED,
        /** The identifier does not match the expected format. */
        INVALID_FORMAT
    }

    /**
     * Builds an "available" response.
     *
     * @return an instance with {@code available=true} and {@code reason=null}
     */
    public static AvailabilityResponse ok() {
        return new AvailabilityResponse(true, null);
    }

    /**
     * Builds an "unavailable" response with the given reason.
     *
     * @param reason rejection cause; must not be {@code null}
     * @return an instance with {@code available=false} and the supplied reason
     */
    public static AvailabilityResponse unavailable(Reason reason) {
        return new AvailabilityResponse(false, reason);
    }
}
