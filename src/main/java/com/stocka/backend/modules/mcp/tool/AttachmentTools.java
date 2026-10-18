package com.stocka.backend.modules.mcp.tool;

import com.stocka.backend.modules.common.schema.Param;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.stocka.backend.modules.mcp.tool.support.BytesMultipartFile;
import com.stocka.backend.modules.mcp.tool.support.OrgAccess;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.dto.PieceAttachmentResponseDto;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.service.PieceAttachmentService;
import com.stocka.backend.modules.storage.PresignedDownload;

/**
 * Attachments of a piece: add one from a public URL, get a temporary download link, delete.
 */
@Component
public class AttachmentTools implements ToolSource {

    static final int MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024;

    public record AddFromUrlInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece id", required = true) Integer pieceId,
            @Param(value = "Public https URL of the file to attach", required = true) String url,
            @Param(value = "IMAGE (jpg/png/webp/gif, 25 MB), DOCUMENT (any, 100 MB) or AUDIO (mp3/wav/ogg/flac/m4a, 50 MB)", required = true) PieceAttachmentKind kind,
            @Param("File name to store; defaults to the last path segment of the URL") String filename,
            @Param("Audio duration in milliseconds (AUDIO only)") Long durationMs) {
    }

    public record AttachmentInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece id", required = true) Integer pieceId,
            @Param(value = "Attachment id", required = true) Integer attachmentId) {
    }

    public record LinkResult(
            @Param("Temporary download URL") String url,
            @Param("When the URL stops working") Instant expiresAt) {
    }

    private final OrgAccess access;
    private final PieceAttachmentService attachments;
    private final RestClient http;

    @Autowired
    public AttachmentTools(OrgAccess access, PieceAttachmentService attachments) {
        this(access, attachments, defaultHttp());
    }

    AttachmentTools(OrgAccess access, PieceAttachmentService attachments, RestClient http) {
        this.access = access;
        this.attachments = attachments;
        this.http = http;
    }

    private static RestClient defaultHttp() {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public List<ToolDefinition<?>> tools() {
        return List.of(
                ToolDefinition.of("piece_attachment_add_from_url",
                        "Downloads a file from a public https URL and attaches it to a piece as IMAGE, DOCUMENT or "
                                + "AUDIO. Needs OWNER, MANAGER or USER.",
                        AddFromUrlInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.CREATE, this::addFromUrl)
                        .returning(PieceAttachmentResponseDto.class),
                ToolDefinition.of("piece_attachment_link",
                        "Returns a temporary download URL for an attachment (to show an image or open a document).",
                        AttachmentInput.class, OAuthScope.READ, ToolHints.READ, this::link).returning(LinkResult.class),
                ToolDefinition.of("piece_attachment_delete",
                        "Deletes an attachment. Needs OWNER, MANAGER or USER and the delete scope.",
                        AttachmentInput.class, OAuthScope.DELETE, ToolHints.DELETE, this::delete)
                        .returning(ToolResults.Done.class));
    }

    private PieceAttachmentResponseDto addFromUrl(ToolCall<AddFromUrlInput> call) {
        AddFromUrlInput in = call.input();
        Organization org = access.writePieces(call.caller(), in.org());
        String url = in.url() == null ? "" : in.url().trim();
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new ToolException("upload.url_invalid", "Only https URLs can be attached",
                    "Give a public https URL of the file.", Map.of("url", url));
        }
        ResponseEntity<byte[]> response;
        try {
            response = http.get().uri(URI.create(url)).retrieve().toEntity(byte[].class);
        } catch (RestClientException | IllegalArgumentException e) {
            throw new ToolException("upload.url_unreachable", "The URL could not be downloaded: " + e.getMessage(),
                    "Check that the URL is public and reachable.", Map.of("url", url));
        }
        byte[] bytes = response.getBody() == null ? new byte[0] : response.getBody();
        if (bytes.length == 0) {
            throw new ToolException("upload.url_unreachable", "The URL returned no content", null, Map.of("url", url));
        }
        if (bytes.length > MAX_DOWNLOAD_BYTES) {
            throw new ToolException("upload.too_large", "The file is larger than 100 MB", null,
                    Map.of("bytes", bytes.length));
        }
        String filename = in.filename() == null || in.filename().isBlank() ? filenameOf(url) : in.filename().trim();
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        BytesMultipartFile file = new BytesMultipartFile("file", filename, contentType, bytes);
        return PieceAttachmentResponseDto.from(attachments.upload(org.getId(), in.pieceId(), in.kind(), file,
                in.durationMs()));
    }

    private LinkResult link(ToolCall<AttachmentInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        PresignedDownload download = attachments.presign(org.getId(), call.input().pieceId(), call.input().attachmentId());
        return new LinkResult(download.url(), download.expiresAt());
    }

    private ToolResults.Done delete(ToolCall<AttachmentInput> call) {
        Organization org = access.writePieces(call.caller(), call.input().org());
        attachments.softDelete(org.getId(), call.input().pieceId(), call.input().attachmentId());
        return ToolResults.Done.of("Attachment " + call.input().attachmentId() + " deleted");
    }

    static String filenameOf(String url) {
        String path = URI.create(url).getPath();
        if (path == null || path.isBlank() || path.endsWith("/")) return "attachment";
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.isBlank() ? "attachment" : last;
    }
}
