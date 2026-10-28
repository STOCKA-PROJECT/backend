package com.stocka.backend.modules.timelines.show;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.contract.CatalogAction;
import com.stocka.backend.modules.contract.CatalogKind;
import com.stocka.backend.modules.contract.CatalogParam;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.timelines.scene.SceneDocument;
import com.stocka.backend.modules.timelines.show.ExecutionPlan.ExecutionItem;
import com.stocka.backend.modules.timelines.show.ExecutionPlan.ExecutionStep;
import com.stocka.backend.modules.timelines.show.ShowCoerce.CoerceResult;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowAssets;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowEvent;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowMeta;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowMode;
import com.stocka.backend.modules.timelines.show.StripLayout.StripSlot;

/**
 * Builds the {@code stocka-show} v3 package from a scene. The execution plan resolves clips into
 * steps per board item; here each step becomes one event per port of the item that belongs to the
 * piece type owning the action, with parameters coerced to the catalog types. Pieces on a LED strip
 * become segments of it. The result is validated with the device's rules and hashed canonically, so
 * the backend, the editor ({@code utils/showPackage.ts}) and {@code tools/show_hash.py} agree on
 * the hash of the same show.
 */
@Component
public class ShowPackageBuilder {

    private final OutputCatalog catalog;

    public ShowPackageBuilder(OutputCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * The package before hashing, for inspection.
     *
     * @param hardware  hardware entries
     * @param timeline  events in time order
     * @param assets    declared assets
     * @param issues    every finding, duplicates included
     * @param assetRefs origin of the audio assets resolved against {@code audioByPiece}
     * @param pieces    piece names by hardware port, in scene order (empty when no item has a port)
     */
    public record Assembled(Map<String, Map<String, Object>> hardware, List<ShowEvent> timeline, ShowAssets assets,
                            List<ShowIssue> issues, List<ShowAssetRef> assetRefs, Map<String, List<String>> pieces) {
    }

    /**
     * Builds the package, or reports why it cannot be built.
     *
     * @param input the scene and its organization data
     * @return the package when there is no error, plus the deduplicated findings
     */
    public ShowBuildResult build(ShowBuildInput input) {
        Assembled assembled = assemble(input);
        List<ShowIssue> errors = dedupe(assembled.issues().stream().filter(ShowIssue::isError).toList());
        List<ShowIssue> warnings = dedupe(assembled.issues().stream().filter(i -> !i.isError()).toList());
        if (!errors.isEmpty()) {
            return new ShowBuildResult(null, errors, warnings);
        }
        ShowMode mode = modeOf(input);
        ShowPackage unhashed = new ShowPackage(ShowPackage.FORMAT, ShowPackage.VERSION, null, input.board(), mode,
                assembled.hardware(), assembled.timeline(), input.doc().durationMs(), assembled.assets());
        String hash = ShowCanonicalJson.showHash(unhashed);
        String name = input.timelineName() == null || input.timelineName().isBlank() ? "Show" : input.timelineName();
        // Where each file comes from, when known (outside the hash: the show is the same).
        List<ShowAssetRef> refs = input.audioByPiece() == null ? null : assembled.assetRefs();
        Map<String, List<String>> pieces = assembled.pieces().isEmpty() ? null : assembled.pieces();
        ShowMeta meta = new ShowMeta(name, input.timelineId(), input.now().get(), hash, refs, pieces);
        ShowPackage pkg = new ShowPackage(ShowPackage.FORMAT, ShowPackage.VERSION, meta, input.board(), mode,
                assembled.hardware(), assembled.timeline(), input.doc().durationMs(), assembled.assets());
        return new ShowBuildResult(pkg, errors, warnings);
    }

    /**
     * Assembles the package without hashing, so every step can be inspected.
     *
     * @param input the scene and its organization data
     * @return hardware, events, assets and every finding
     */
    public Assembled assemble(ShowBuildInput input) {
        List<ShowIssue> issues = new ArrayList<>();
        SceneDocument doc = input.doc();
        Map<Integer, ShowPort> portById = new HashMap<>();
        for (ShowPort port : input.orgPorts()) {
            portById.put(port.id(), port);
        }

        ExecutionPlan plan = ExecutionPlan.build(doc, input.context(), portById::get);
        issues.addAll(plan.issues());

        ShowHardware.Built built = ShowHardware.build(input.orgPorts(), catalog);
        issues.addAll(built.issues());
        Map<String, Map<String, Object>> hardware = built.hardware();

        // Pieces on a LED strip become segments of it: laid out in the scene's strip order, each as
        // long as its length attribute (the last one may take the rest). Their clips then target the
        // segment with first/last relative to the piece, exactly like a segment port would.
        Map<String, Map<Integer, String>> segmentOf = new HashMap<>();
        Set<String> taken = new HashSet<>(hardware.keySet());
        for (ShowPort port : input.orgPorts()) {
            if (!"led_strip".equals(port.kind())) continue;
            List<ExecutionItem> onPort = plan.items().stream()
                    .filter(i -> i.ports().stream().anyMatch(p -> p.id() == port.id())).toList();
            if (onPort.isEmpty()) continue;
            Map<String, ExecutionItem> onPortById = new HashMap<>();
            onPort.forEach(i -> onPortById.put(i.itemId(), i));
            List<String> ordered = StripLayout.orderItems(onPort.stream().map(ExecutionItem::itemId).toList(),
                    doc.stripOrder().get(String.valueOf(port.id())));
            Integer stripLength = port.config().get("length") instanceof Number n ? n.intValue() : null;
            Map<String, StripSlot> layout = StripLayout.layoutStrip(ordered,
                    id -> ledLengthOf(input, onPortById.get(id).pieceId()), stripLength);
            for (int index = 0; index < ordered.size(); index++) {
                String itemId = ordered.get(index);
                ExecutionItem item = onPortById.get(itemId);
                StripSlot slot = layout.get(itemId);
                String pieceName = item.pieceName() != null ? item.pieceName() : itemId;
                if (!slot.explicit() && index < ordered.size() - 1) {
                    issues.add(ShowIssue.error("E_LED_LENGTH", "«" + pieceName
                            + "» no té atribut de longitud (LED) i no és l'última peça de «" + port.name()
                            + "»: posa-li la longitud o mou-la al final."));
                    continue;
                }
                if (slot.length() <= 0) {
                    issues.add(ShowIssue.error("E_LED_LENGTH", "«" + pieceName + "» no té cap LED a «" + port.name()
                            + "»: les peces anteriors ja omplen la tira"
                            + (slot.explicit() ? "" : " i no té atribut de longitud") + "."));
                    continue;
                }
                String name = StripLayout.uniqueName(pieceName, taken);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("kind", "led_segment");
                entry.put("strip", port.name());
                entry.put("first", slot.offset());
                entry.put("last", slot.offset() + slot.length() - 1);
                hardware.put(name, entry);
                segmentOf.computeIfAbsent(itemId, k -> new HashMap<>()).put(port.id(), name);
            }
        }

        Set<String> audioAssets = new LinkedHashSet<>();
        Map<String, ShowAssetRef> assetRefs = new LinkedHashMap<>();
        List<ShowEvent> events = new ArrayList<>();
        Map<String, ExecutionItem> itemById = new HashMap<>();
        plan.items().forEach(i -> itemById.put(i.itemId(), i));
        CatalogKind segmentKind = catalog.kind(OutputKind.LED_SEGMENT);

        for (ExecutionStep step : plan.schedule()) {
            if (step.action() == null) continue;
            ExecutionItem item = itemById.get(step.itemId());
            if (item == null) continue;
            String label = step.label() != null ? step.label() : step.action().displayName();
            String itemName = item.pieceName() != null ? item.pieceName() : item.itemId();
            List<ShowPort> targets = item.ports().stream()
                    .filter(p -> Objects.equals(p.pieceTypeId(), step.action().pieceTypeId())).toList();
            if (targets.isEmpty()) {
                issues.add(ShowIssue.error("E_NO_PORT", "La peça «" + itemName
                        + "» no té cap port del tipus de peça de l'acció «" + step.action().displayName() + "» (clip «"
                        + label + "», " + step.startMs() + " ms).", step.clipId(), null));
                continue;
            }
            for (ShowPort port : targets) {
                // A piece on a strip drives its own segment, unless the action is strip-wide (setBrightness).
                String segment = segmentOf.getOrDefault(item.itemId(), Map.of()).get(port.id());
                boolean useSegment = segment != null && segmentKind.actions().containsKey(step.action().name());
                String targetName = useSegment ? segment : port.name();
                CatalogKind spec = useSegment ? segmentKind : OutputKind.parse(port.kind()).map(catalog::kind).orElse(null);
                CatalogAction action = spec == null ? null : spec.actions().get(step.action().name());
                if (spec == null || action == null) {
                    issues.add(ShowIssue.error("E_ACTION_UNKNOWN", "L'acció «" + step.action().name()
                            + "» no existeix per a sortides " + port.kind() + " (port «" + port.name() + "», clip «"
                            + label + "»).", step.clipId(), port.name()));
                    continue;
                }
                Object rawAnimation = step.params().get("animation_type");
                String animation = !action.animations().isEmpty() && rawAnimation instanceof String a ? a : null;
                Map<String, CatalogParam> allowed = animation != null ? action.paramsFor(animation) : action.allParams();
                Map<String, Object> params = new LinkedHashMap<>();
                boolean valid = true;
                for (Map.Entry<String, Object> p : step.params().entrySet()) {
                    String name = p.getKey();
                    Object raw = p.getValue();
                    CatalogParam pspec = allowed.get(name);
                    if (pspec == null) {
                        // A type may declare the parameters of every animation at once: the ones the clip does
                        // not fill never reach the package, and a value left over from another animation is
                        // dropped with a warning. Only a name no animation knows is an error.
                        if (ShowCoerce.isBlank(raw)) continue;
                        boolean ofOtherAnimation = action.animations().values().stream()
                                .anyMatch(anim -> anim.params().containsKey(name));
                        if (animation != null && ofOtherAnimation) {
                            issues.add(ShowIssue.warning("W_ANIMATION_PARAM_IGNORED", "El paràmetre «" + name
                                    + "» no s'aplica a l'animació «" + animation + "» i no s'exporta (clip «" + label + "», "
                                    + step.startMs() + " ms).", step.clipId(), port.name()));
                            continue;
                        }
                        valid = false;
                        String code = animation != null ? "E_ANIMATION_PARAM" : "E_PARAM_UNKNOWN";
                        issues.add(ShowIssue.error(code, "El paràmetre «" + name + "» no existeix a " + port.kind() + "."
                                + step.action().name() + (animation != null ? " per a l'animació «" + animation + "»" : "")
                                + " (clip «" + label + "»).", step.clipId(), port.name()));
                        continue;
                    }
                    CoerceResult res = ShowCoerce.coerceParam(raw, pspec);
                    if (!res.ok()) {
                        valid = false;
                        String code = switch (res.reason()) {
                            case REQUIRED -> "E_PARAM_REQUIRED";
                            case RANGE -> "E_PARAM_RANGE";
                            case ENUM -> "E_PARAM_ENUM";
                            case TYPE -> "E_PARAM_TYPE";
                        };
                        String text = res.reason() == ShowCoerce.Reason.REQUIRED
                                ? "Falta el paràmetre obligatori «" + name + "» al clip «" + label + "» (" + step.startMs() + " ms)."
                                : "El paràmetre «" + name + "» del clip «" + label + "» ha de ser " + res.expected()
                                + " (valor: " + ShowCanonicalJson.canonical(raw) + ").";
                        issues.add(ShowIssue.error(code, text, step.clipId(), port.name()));
                        continue;
                    }
                    if (res.omitted()) continue;
                    params.put(name, res.value());
                    if (pspec.asset() != null && res.value() instanceof String assetName) {
                        audioAssets.add(assetName);
                        if ("audio".equals(pspec.asset()) && input.audioByPiece() != null
                                && item.pieceId() != null && input.audioByPiece().containsKey(item.pieceId())) {
                            List<ShowAudioFile> files = input.audioByPiece().getOrDefault(item.pieceId(), List.of());
                            ShowAudioFile att = AudioAssets.findAudioByAssetName(files, assetName);
                            if (att == null) {
                                valid = false;
                                issues.add(ShowIssue.error("E_ASSET_MISSING", "El fitxer «" + assetName + "» del clip «" + label
                                        + "» no és entre els àudios de «" + itemName + "»: tria'n un altre.", step.clipId(), port.name()));
                            } else {
                                assetRefs.put(assetName, new ShowAssetRef(assetName, "audio", item.pieceId(), att.id(),
                                        att.mimeType(), att.sizeBytes(), att.durationMs()));
                                double start = ShowNumbers.isNumber(step.params().get("start"))
                                        ? ((Number) step.params().get("start")).doubleValue() : 0;
                                Double time = ShowNumbers.isNumber(step.params().get("time"))
                                        ? ((Number) step.params().get("time")).doubleValue() : null;
                                AudioAssets.AudioFileWindow window = AudioAssets.audioFileWindow(start, time, att.durationMs());
                                if (window.beyondEndSec() > 0.05) {
                                    issues.add(ShowIssue.warning("W_AUDIO_BEYOND_END", "El clip «" + label + "» (" + step.startMs()
                                            + " ms) s'allarga " + String.format(Locale.ROOT, "%.1f", window.beyondEndSec())
                                            + " s més enllà del final de «" + att.originalFilename() + "» ("
                                            + AudioAssets.formatClock(att.durationMs()) + "): aquesta part no sonarà.",
                                            step.clipId(), port.name()));
                                }
                            }
                        }
                    }
                }
                for (String name : ShowValidator.requiredParams(action, animation)) {
                    if (!params.containsKey(name) && !step.params().containsKey(name)) {
                        valid = false;
                        issues.add(ShowIssue.error("E_PARAM_REQUIRED", "Falta el paràmetre obligatori «" + name + "» al clip «"
                                + label + "» (" + step.startMs() + " ms).", step.clipId(), port.name()));
                    }
                }
                if (!valid) continue;
                events.add(new ShowEvent(step.startMs(), targetName, step.action().name(), params));
            }
        }

        // Stable sort by time: ties keep clip order, then the item's port order.
        List<ShowEvent> timeline = new ArrayList<>(events);
        timeline.sort((a, b) -> Long.compare(a.t(), b.t()));

        ShowAssets assets = new ShowAssets(new ArrayList<>(audioAssets));
        issues.addAll(ShowValidator.validate(hardware, timeline, input.board(), doc.durationMs(), assets, catalog));
        issues.addAll(ShowValidator.validateSchedule(modeOf(input), hardware));
        return new Assembled(hardware, timeline, assets, issues, new ArrayList<>(assetRefs.values()),
                piecesByPort(plan, segmentOf, hardware));
    }

    /**
     * Which pieces each hardware port drives, for the player to show next to the port ({@code meta.pieces}).
     * Items without a known piece are skipped; a piece on a strip names the strip and its own segment.
     */
    static Map<String, List<String>> piecesByPort(ExecutionPlan plan, Map<String, Map<Integer, String>> segmentOf,
                                                  Map<String, Map<String, Object>> hardware) {
        Map<String, Set<String>> byPort = new LinkedHashMap<>();
        for (ExecutionItem item : plan.items()) {
            if (item.pieceName() == null) continue;
            List<String> ports = new ArrayList<>();
            item.ports().forEach(port -> ports.add(port.name()));
            ports.addAll(segmentOf.getOrDefault(item.itemId(), Map.of()).values());
            for (String port : ports) {
                if (hardware.containsKey(port)) {
                    byPort.computeIfAbsent(port, k -> new LinkedHashSet<>()).add(item.pieceName());
                }
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        byPort.forEach((port, names) -> out.put(port, new ArrayList<>(names)));
        return out;
    }

    /** The input's mode with the scene's button schedule, unless the mode already carries one. */
    static ShowMode modeOf(ShowBuildInput input) {
        ShowMode mode = input.mode();
        return mode.schedule() == null && input.doc().schedule() != null ? mode.withSchedule(input.doc().schedule()) : mode;
    }

    private static Integer ledLengthOf(ShowBuildInput input, Integer pieceId) {
        if (input.ledLengthOf() == null || pieceId == null) {
            return null;
        }
        return input.ledLengthOf().apply(pieceId);
    }

    private static List<ShowIssue> dedupe(List<ShowIssue> issues) {
        Set<String> seen = new HashSet<>();
        List<ShowIssue> out = new ArrayList<>();
        for (ShowIssue issue : issues) {
            if (seen.add(issue.dedupeKey())) {
                out.add(issue);
            }
        }
        return out;
    }
}
