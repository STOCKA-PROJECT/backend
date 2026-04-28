package com.stocka.backend.modules.locations.service;

import com.stocka.backend.modules.locations.entity.Location;

/**
 * Hook other modules can implement to veto the deletion of a location they have content in. The
 * pieces module wires an implementation that returns the count of active pieces in the location;
 * additional modules (future) can plug in extra checks the same way.
 *
 * <p>Implementations should be cheap (a single {@code count} query). They run on every delete.
 */
public interface LocationContentChecker {

    /**
     * @return number of objects this checker knows about that live in {@code location}; if greater
     *         than zero, the deletion is blocked
     */
    long countContent(Location location);

    /**
     * @return short, human-readable description of what this checker counts (e.g. {@code
     *         "artículos"}). Used to build the 400 message shown to the user.
     */
    String contentLabel();
}
