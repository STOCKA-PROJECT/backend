package com.stocka.backend.modules.pieces.service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.storage.PresignedDownload;
import com.stocka.backend.modules.storage.R2Properties;
import com.stocka.backend.modules.storage.R2Service;
import com.stocka.backend.modules.storage.R2UnavailableException;
import com.stocka.backend.modules.storage.UploadedObject;
import com.stocka.backend.modules.users.entity.User;

/**
 * Upload, list, presign-download and delete piece attachments. Validates per-{@code kind} MIME
 * and size limits, talks to R2 through {@link R2Service}, and records every change in piece
 * history.
 */
@Service
public class PieceAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(PieceAttachmentService.class);

    private final PieceAttachmentRepository attachmentRepository;
    private final PieceService pieceService;
    private final PieceHistoryService historyService;
    private final R2Service r2Service;
    private final R2Properties r2Properties;
    private final PieceAttachmentProperties limits;

    public PieceAttachmentService(
            PieceAttachmentRepository attachmentRepository,
            PieceService pieceService,
            PieceHistoryService historyService,
            R2Service r2Service,
            R2Properties r2Properties,
            PieceAttachmentProperties limits
    ) {
        this.attachmentRepository = attachmentRepository;
        this.pieceService = pieceService;
        this.historyService = historyService;
        this.r2Service = r2Service;
        this.r2Properties = r2Properties;
        this.limits = limits;
    }

    @Transactional
    public PieceAttachment upload(Integer orgId, Integer pieceId, PieceAttachmentKind kind, MultipartFile file) {
        if (kind == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El tipo de adjunto es obligatorio (IMAGE o DOCUMENT)");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo es obligatorio");
        }
        Piece piece = pieceService.findInOrg(orgId, pieceId);

        long size = file.getSize();
        String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType().toLowerCase();
        validateForKind(piece, kind, mime, size);

        String safeName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String key = r2Service.buildPieceKey(piece.getOrganization().getId(), piece.getId(), safeName);

        UploadedObject stored;
        try {
            stored = r2Service.upload(key, file.getInputStream(), size, mime);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo leer el archivo subido: " + e.getMessage());
        }

        PieceAttachment attachment = new PieceAttachment()
                .setPiece(piece)
                .setKind(kind)
                .setR2Key(stored.key())
                .setOriginalFilename(safeName)
                .setMimeType(mime)
                .setSizeBytes(stored.sizeBytes())
                .setUploadedBy(currentUser());
        PieceAttachment saved = attachmentRepository.save(attachment);
        historyService.recordAttachmentAdded(piece, currentUser(), safeName);
        return saved;
    }

    public List<PieceAttachment> list(Integer orgId, Integer pieceId, PieceAttachmentKind kindFilter) {
        Piece piece = pieceService.findInOrg(orgId, pieceId);
        if (kindFilter == null) {
            return attachmentRepository.findByPiece(piece);
        }
        return attachmentRepository.findByPieceAndKind(piece, kindFilter);
    }

    public PresignedDownload presign(Integer orgId, Integer pieceId, Integer attachmentId) {
        PieceAttachment attachment = findInPiece(orgId, pieceId, attachmentId);
        return r2Service.presign(attachment.getR2Key(), Duration.ofMinutes(r2Properties.getPresignedTtlMinutes()));
    }

    @Transactional
    public void softDelete(Integer orgId, Integer pieceId, Integer attachmentId) {
        PieceAttachment attachment = findInPiece(orgId, pieceId, attachmentId);
        attachment.setDeletedAt(LocalDateTime.now());
        attachmentRepository.save(attachment);
        try {
            r2Service.delete(attachment.getR2Key());
        } catch (R2UnavailableException e) {
            log.warn("r2_delete_failed key={} reason={}", attachment.getR2Key(), e.getMessage());
        }
        historyService.recordAttachmentRemoved(attachment.getPiece(), currentUser(), attachment.getOriginalFilename());
    }

    private PieceAttachment findInPiece(Integer orgId, Integer pieceId, Integer attachmentId) {
        Piece piece = pieceService.findInOrg(orgId, pieceId);
        PieceAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Adjunto no encontrado"));
        if (!attachment.getPiece().getId().equals(piece.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Adjunto no encontrado");
        }
        return attachment;
    }

    private void validateForKind(Piece piece, PieceAttachmentKind kind, String mime, long size) {
        if (kind == PieceAttachmentKind.IMAGE) {
            if (!limits.getAllowedImageMimes().contains(mime)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tipo de imagen no permitido: " + mime + ". Formatos válidos: jpg, png, webp, gif");
            }
            if (size > limits.getMaxImageBytes()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La imagen excede el tamaño máximo permitido (" + limits.getMaxImageBytes() + " bytes)");
            }
            long current = attachmentRepository.countByPieceAndKind(piece, PieceAttachmentKind.IMAGE);
            if (current >= limits.getMaxImagesPerPiece()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Se ha alcanzado el límite de " + limits.getMaxImagesPerPiece() + " imágenes por artículo");
            }
        } else {
            if (size > limits.getMaxDocumentBytes()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El documento excede el tamaño máximo permitido (" + limits.getMaxDocumentBytes() + " bytes)");
            }
            long current = attachmentRepository.countByPieceAndKind(piece, PieceAttachmentKind.DOCUMENT);
            if (current >= limits.getMaxDocumentsPerPiece()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Se ha alcanzado el límite de " + limits.getMaxDocumentsPerPiece() + " documentos por artículo");
            }
        }
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return null;
        }
        return user;
    }
}
