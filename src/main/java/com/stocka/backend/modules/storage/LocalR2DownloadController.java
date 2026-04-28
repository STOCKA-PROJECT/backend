package com.stocka.backend.modules.storage;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Streams attachments stored by {@link LocalR2StorageService} back to the client. Only loaded
 * when {@code stocka.r2.use-local=true}; production deployments use real R2 presigned URLs.
 *
 * <p>Authenticated; access control on the underlying piece happens before we hand out the
 * presigned URL, so any authenticated user with a valid local URL can fetch it. The bucket is
 * not exposed publicly in any environment.
 */
@RestController
@RequestMapping("/dev/r2")
@ConditionalOnProperty(name = "stocka.r2.use-local", havingValue = "true", matchIfMissing = true)
public class LocalR2DownloadController {
    private final LocalR2StorageService storage;

    public LocalR2DownloadController(LocalR2StorageService storage) {
        this.storage = storage;
    }

    @GetMapping("/**")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> download(@PathVariable(required = false) String ignored,
                                             jakarta.servlet.http.HttpServletRequest request) {
        String fullPath = request.getRequestURI();
        String prefix = "/dev/r2/";
        int idx = fullPath.indexOf(prefix);
        String rawKey = idx >= 0 ? fullPath.substring(idx + prefix.length()) : "";
        String key = Arrays.stream(rawKey.split("/"))
                .map(seg -> URLDecoder.decode(seg, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));

        if (key.isBlank() || !storage.exists(key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado");
        }

        Path file = storage.resolve(key);
        String mime;
        try {
            mime = Files.probeContentType(file);
        } catch (IOException e) {
            mime = null;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFileName() + "\"")
                .contentType(mime != null ? MediaType.parseMediaType(mime) : MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }
}
