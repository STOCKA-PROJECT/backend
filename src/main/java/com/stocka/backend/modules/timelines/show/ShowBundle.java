package com.stocka.backend.modules.timelines.show;

import java.util.List;

/**
 * A {@code stocka-bundle} ready to be streamed: the show package and the storage keys of the assets
 * it references (every one already checked to exist), plus the canonical file name.
 *
 * @param pkg      the show package
 * @param fileName {@code <slug>-<hash8>.belen.zip}
 * @param assets   assets in {@code assets.audio} order
 */
public record ShowBundle(ShowPackage pkg, String fileName, List<BundleAsset> assets) {

    /**
     * One media file of the bundle.
     *
     * @param path  entry path inside the zip ({@code assets/<kind>/<name>})
     * @param kind  {@code audio}
     * @param name  asset name
     * @param r2Key storage key of the bytes
     */
    public record BundleAsset(String path, String kind, String name, String r2Key) {
    }
}
