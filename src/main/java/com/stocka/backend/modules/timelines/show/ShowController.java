package com.stocka.backend.modules.timelines.show;

import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.stocka.backend.modules.organizations.service.OrganizationResolver;

/**
 * Server-side export of a timeline: the {@code stocka-show} package as JSON and the self-contained
 * {@code .belen.zip} bundle (show plus media files) the Raspberry Pi imports offline. Any member of
 * the organization, SPECTATOR included, may download them, like the rest of the organization's
 * content.
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/timelines/{timelineId}")
public class ShowController {

    /** Media type of the bundle. */
    public static final MediaType APPLICATION_ZIP = MediaType.parseMediaType("application/zip");

    private final ShowService showService;
    private final ShowBundleWriter bundleWriter;
    private final OrganizationResolver orgResolver;

    public ShowController(ShowService showService, ShowBundleWriter bundleWriter, OrganizationResolver orgResolver) {
        this.showService = showService;
        this.bundleWriter = bundleWriter;
        this.orgResolver = orgResolver;
    }

    /**
     * The show package of the timeline.
     *
     * @return 200 with the package; 404 when the timeline or its scene does not exist; 422 with the
     *         blocking issues when the scene cannot be exported
     */
    @GetMapping(value = "/show", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<ShowPackage> show(@PathVariable String orgSlug, @PathVariable Integer timelineId) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        return ResponseEntity.ok(showService.showPackage(orgId, timelineId));
    }

    /**
     * The {@code .belen.zip} bundle of the timeline, streamed. The package is built and every asset
     * checked before the first byte is written, so validation failures are proper error responses.
     *
     * @return 200 {@code application/zip} as an attachment named {@code <slug>-<hash8>.belen.zip};
     *         404 / 422 as {@link #show}, plus 404 when an asset is missing
     */
    @GetMapping("/bundle")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<StreamingResponseBody> bundle(@PathVariable String orgSlug, @PathVariable Integer timelineId) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        ShowBundle bundle = showService.bundle(orgId, timelineId);
        StreamingResponseBody body = out -> bundleWriter.write(bundle, out, Instant.now());
        return ResponseEntity.ok()
                .contentType(APPLICATION_ZIP)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + bundle.fileName() + "\"")
                .body(body);
    }
}
