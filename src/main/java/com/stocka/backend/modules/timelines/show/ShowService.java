package com.stocka.backend.modules.timelines.show;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.dto.PieceAttributeValueResponseDto;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.repository.PieceAttributeValueRepository;
import com.stocka.backend.modules.pieces.repository.PieceOrganizationAttributeValueRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeActionRepository;
import com.stocka.backend.modules.piecetypes.service.PieceTypeActionService;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.ports.entity.Port;
import com.stocka.backend.modules.ports.repository.PortRepository;
import com.stocka.backend.modules.ports.service.PortConfigJsonCodec;
import com.stocka.backend.modules.storage.R2Service;
import com.stocka.backend.modules.timelines.entity.Timeline;
import com.stocka.backend.modules.timelines.entity.TimelineScene;
import com.stocka.backend.modules.timelines.scene.JpaSceneContext;
import com.stocka.backend.modules.timelines.scene.SceneContext;
import com.stocka.backend.modules.timelines.scene.SceneContext.PieceRef;
import com.stocka.backend.modules.timelines.scene.SceneDocument;
import com.stocka.backend.modules.timelines.scene.SceneJsonCodec;
import com.stocka.backend.modules.timelines.service.TimelineSceneService;
import com.stocka.backend.modules.timelines.service.TimelineService;

/**
 * Generates the {@code stocka-show} package and the {@code .belen.zip} bundle of a timeline from its
 * saved scene and the organization's pieces, actions, ports and audio files. The same engine the
 * editor runs in the browser, so the mobile app and the frontend can download a show without it.
 */
@Service
public class ShowService {

    private final TimelineService timelines;
    private final TimelineSceneService scenes;
    private final SceneJsonCodec sceneCodec;
    private final PieceRepository pieces;
    private final PortRepository ports;
    private final PortConfigJsonCodec portConfigCodec;
    private final PieceTypeActionRepository actions;
    private final PieceTypeService typeService;
    private final PieceTypeActionService actionService;
    private final PieceAttachmentRepository attachments;
    private final PieceAttributeValueRepository typeValues;
    private final PieceOrganizationAttributeValueRepository orgValues;
    private final ShowPackageBuilder builder;
    private final R2Service r2;

    public ShowService(TimelineService timelines, TimelineSceneService scenes, SceneJsonCodec sceneCodec,
                       PieceRepository pieces, PortRepository ports, PortConfigJsonCodec portConfigCodec,
                       PieceTypeActionRepository actions, PieceTypeService typeService,
                       PieceTypeActionService actionService, PieceAttachmentRepository attachments,
                       PieceAttributeValueRepository typeValues, PieceOrganizationAttributeValueRepository orgValues,
                       ShowPackageBuilder builder, R2Service r2) {
        this.timelines = timelines;
        this.scenes = scenes;
        this.sceneCodec = sceneCodec;
        this.pieces = pieces;
        this.ports = ports;
        this.portConfigCodec = portConfigCodec;
        this.actions = actions;
        this.typeService = typeService;
        this.actionService = actionService;
        this.attachments = attachments;
        this.typeValues = typeValues;
        this.orgValues = orgValues;
        this.builder = builder;
        this.r2 = r2;
    }

    /**
     * Builds the show package of a timeline.
     *
     * @param orgId      organization id
     * @param timelineId timeline id
     * @return the package, hashed, with {@code meta.assets} resolved
     * @throws ApiException 404 when the timeline does not exist in the organization or has no saved
     *                      scene; 422 ({@link ErrorCodes#TIMELINE_SHOWS_INVALID}) with the blocking
     *                      issues in {@code params.issues} when the scene cannot be exported
     */
    @Transactional(readOnly = true)
    public ShowPackage showPackage(Integer orgId, Integer timelineId) {
        Timeline timeline = timelines.findInOrg(orgId, timelineId);
        return build(timeline).pkg();
    }

    /**
     * Prepares the bundle of a timeline: the package plus the storage key of every asset, each
     * checked to exist before a single byte is streamed.
     *
     * @param orgId      organization id
     * @param timelineId timeline id
     * @return the bundle to hand to {@link ShowBundleWriter}
     * @throws ApiException as {@link #showPackage}, plus 404
     *                      ({@link ErrorCodes#TIMELINE_SHOWS_ASSET_NOT_FOUND}) when an asset has no
     *                      attachment behind it or its bytes are gone
     */
    @Transactional(readOnly = true)
    public ShowBundle bundle(Integer orgId, Integer timelineId) {
        Timeline timeline = timelines.findInOrg(orgId, timelineId);
        ShowPackage pkg = build(timeline).pkg();
        Map<String, ShowAssetRef> refs = new HashMap<>();
        if (pkg.meta().assets() != null) {
            pkg.meta().assets().forEach(ref -> refs.put(ref.kind() + "/" + ref.name(), ref));
        }
        List<ShowBundle.BundleAsset> assets = new ArrayList<>();
        for (String kind : List.of("audio")) {
            List<String> names = pkg.assets().audio();
            for (String name : names) {
                ShowAssetRef ref = refs.get(kind + "/" + name);
                PieceAttachment attachment = ref == null ? null : attachments.findById(ref.attachmentId())
                        .filter(a -> a.getPiece() != null && a.getPiece().getId().equals(ref.pieceId())
                                && a.getPiece().getOrganization().getId().equals(orgId))
                        .orElse(null);
                if (attachment == null || !r2.exists(attachment.getR2Key())) {
                    throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.TIMELINE_SHOWS_ASSET_NOT_FOUND, Map.of("asset", name));
                }
                assets.add(new ShowBundle.BundleAsset("assets/" + kind + "/" + name, kind, name, attachment.getR2Key()));
            }
        }
        return new ShowBundle(pkg, ShowBundleWriter.fileName(pkg), assets);
    }

    private ShowBuildResult build(Timeline timeline) {
        Organization org = timeline.getOrganization();
        TimelineScene scene = scenes.find(org.getId(), timeline.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.TIMELINE_SHOWS_SCENE_NOT_FOUND));
        SceneDocument doc = sceneCodec.parse(scenes.documentOf(scene));
        SceneContext context = new JpaSceneContext(org, pieces, ports, actions, typeService, actionService);

        List<ShowPort> orgPorts = new ArrayList<>();
        for (Port port : ports.findByOrganizationOrderByPositionAscIdAsc(org)) {
            orgPorts.add(ShowPort.from(port, portConfigCodec.deserialize(port.getConfigJson())));
        }

        // Audio files of the audio pieces, as the editor loads them before exporting: the package
        // checks their asset names and lists where every file comes from.
        Set<Integer> pieceIds = new LinkedHashSet<>();
        doc.items().forEach(item -> {
            if (item.pieceId() != null) pieceIds.add(item.pieceId());
        });
        Map<Integer, List<ShowAudioFile>> audioByPiece = new LinkedHashMap<>();
        Map<Integer, Piece> pieceEntities = new HashMap<>();
        for (Integer pieceId : pieceIds) {
            Optional<Piece> piece = pieces.findByIdAndOrganization(pieceId, org);
            if (piece.isEmpty()) continue;
            pieceEntities.put(pieceId, piece.get());
            Optional<PieceRef> ref = context.pieceById(pieceId);
            boolean audioPiece = ref.isPresent() && ref.get().typeKinds().stream().anyMatch("audio"::equals);
            if (audioPiece) {
                List<ShowAudioFile> files = new ArrayList<>();
                for (PieceAttachment a : attachments.findByPieceAndKind(piece.get(), PieceAttachmentKind.AUDIO)) {
                    files.add(ShowAudioFile.from(a));
                }
                audioByPiece.put(pieceId, files);
            }
        }

        ShowBuildInput input = ShowBuildInput.of(doc, context, orgPorts)
                .withAudioByPiece(audioByPiece)
                .withLedLengthOf(pieceId -> ledLengthOf(pieceEntities.get(pieceId)))
                .withTimeline(timeline.getName(), timeline.getId());
        ShowBuildResult result = builder.build(input);
        if (!result.ok()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("errors", result.errors().size());
            params.put("issues", result.errors());
            params.put("warnings", result.warnings());
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.TIMELINE_SHOWS_INVALID, params);
        }
        return result;
    }

    /** @return the LED count of a piece from its length attribute (type or organization scope), or {@code null} */
    private Integer ledLengthOf(Piece piece) {
        if (piece == null) {
            return null;
        }
        List<PieceAttributeValueResponseDto> values = new ArrayList<>();
        typeValues.findByPiece(piece).forEach(v -> values.add(PieceAttributeValueResponseDto.fromType(v)));
        orgValues.findByPiece(piece).forEach(v -> values.add(PieceAttributeValueResponseDto.fromOrg(v)));
        return StripLayout.ledLengthFromAttributes(values);
    }
}
