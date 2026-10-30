package com.stocka.backend.modules.timelines.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.stocka.backend.modules.timelines.scene.SceneContext.ActionRef;
import com.stocka.backend.modules.timelines.scene.SceneContext.PieceRef;
import com.stocka.backend.modules.timelines.scene.SceneContext.PortRef;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Board;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Clip;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Item;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Layer;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Track;

/**
 * Applies a batch of {@link SceneOp}s to a {@link SceneDocument}.
 *
 * <p>The batch is atomic: the first failing operation raises {@link SceneOpException} (with its
 * index) and nothing is returned. Conveniences for callers that reason in names rather than ids:
 * pieces, ports and actions may be referenced by name when the name is unique; items are placed on
 * a grid when no position is given; a clip without {@code startMs} goes after the last clip of its
 * track. Ids of created layers, items, tracks and clips are reported so the next batch can use them.
 */
public final class SceneEditor {

    /** Outcome of a batch. */
    public record Change(SceneDocument document, List<String> createdIds, List<String> log) {
    }

    private static final int GRID_COLUMNS = 8;
    private static final double GRID_STEP_PX = 120;
    private static final double GRID_MARGIN_PX = 20;

    private final SceneContext context;
    private final List<String> created = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private SceneDocument doc;

    private SceneEditor(SceneDocument doc, SceneContext context) {
        this.doc = doc;
        this.context = context;
    }

    /**
     * @param document starting document
     * @param ops      operations, applied in order
     * @param context  organization data
     * @return the edited document and the created ids
     * @throws SceneOpException on the first invalid operation
     */
    public static Change apply(SceneDocument document, List<SceneOp> ops, SceneContext context) {
        SceneEditor editor = new SceneEditor(document, context);
        for (int i = 0; i < ops.size(); i++) {
            SceneOp op = ops.get(i);
            if (op == null) {
                throw new SceneOpException(i, "null", "operation is empty");
            }
            editor.applyOne(i, op);
        }
        return new Change(editor.doc.withDurationFromClips(), List.copyOf(editor.created), List.copyOf(editor.log));
    }

    private void applyOne(int i, SceneOp op) {
        switch (op) {
            case SceneOp.AddLayer o -> addLayer(i, o);
            case SceneOp.AddItem o -> addItem(i, o);
            case SceneOp.UpdateItem o -> updateItem(i, o);
            case SceneOp.SetItemPorts o -> setItemPorts(i, o);
            case SceneOp.RemoveItem o -> removeItem(i, o);
            case SceneOp.AddTrack o -> addTrack(i, o);
            case SceneOp.RenameTrack o -> renameTrack(i, o);
            case SceneOp.RemoveTrack o -> removeTrack(i, o);
            case SceneOp.AddClip o -> addClip(i, o);
            case SceneOp.UpdateClip o -> updateClip(i, o);
            case SceneOp.MoveClip o -> moveClip(i, o);
            case SceneOp.RemoveClip o -> removeClip(i, o);
            case SceneOp.SetBoard o -> setBoard(i, o);
            case SceneOp.SetStripOrder o -> setStripOrder(i, o);
            case SceneOp.SetSchedule o -> setSchedule(i, o);
        }
    }

    // ---------- layers ----------

    private void addLayer(int i, SceneOp.AddLayer op) {
        if (op.name() == null || op.name().isBlank()) {
            throw new SceneOpException(i, "add_layer", "name is required");
        }
        int z = doc.layers().stream().mapToInt(Layer::z).max().orElse(-1) + 1;
        Layer layer = new Layer(uniqueId("layer"), op.name().trim(), z, true, false, 1);
        doc.layers().add(layer);
        created.add(layer.id());
        log.add("layer " + layer.id() + " \"" + layer.name() + "\" added");
    }

    // ---------- items ----------

    private void addItem(int i, SceneOp.AddItem op) {
        PieceRef piece = resolvePiece(i, "add_item", op.pieceId(), op.pieceName());
        String layerId = op.layerId();
        if (layerId == null || layerId.isBlank()) {
            if (doc.layers().isEmpty()) {
                Layer layer = new Layer(uniqueId("layer"), "Capa 1", 0, true, false, 1);
                doc.layers().add(layer);
                created.add(layer.id());
            }
            layerId = doc.layers().get(0).id();
        } else if (findLayer(layerId).isEmpty()) {
            throw new SceneOpException(i, "add_item", "layer not found: " + layerId);
        }
        double width = SceneDocument.toPx(positive(i, "add_item", "width", op.width(), SceneDocument.DEFAULT_ITEM_SIZE_CM));
        double height = SceneDocument.toPx(positive(i, "add_item", "height", op.height(), SceneDocument.DEFAULT_ITEM_SIZE_CM));
        double x;
        double y;
        if (op.x() == null || op.y() == null) {
            int n = doc.items().size();
            x = GRID_MARGIN_PX + (n % GRID_COLUMNS) * GRID_STEP_PX;
            y = GRID_MARGIN_PX + (n / GRID_COLUMNS) * GRID_STEP_PX;
        } else {
            x = SceneDocument.toPx(op.x());
            y = SceneDocument.toPx(op.y());
        }
        Bindings bindings = resolvePorts(i, "add_item", piece, op.ports(), op.portTypes());
        Item item = new Item(uniqueId("item"), piece.id(), layerId, x, y, width, height,
                op.rotation() == null ? 0 : op.rotation(), null, null, bindings.ports(), null, bindings.portTypes());
        doc.items().add(item);
        created.add(item.id());
        Track track = new Track(uniqueId("track"), item.id(), piece.name());
        doc.tracks().add(track);
        created.add(track.id());
        log.add("item " + item.id() + " (" + piece.name() + ") added with track " + track.id());
    }

    private void updateItem(int i, SceneOp.UpdateItem op) {
        int index = indexOfItem(i, "update_item", op.itemId());
        Item it = doc.items().get(index);
        String layerId = it.layerId();
        if (op.layerId() != null && !op.layerId().isBlank()) {
            if (findLayer(op.layerId()).isEmpty()) {
                throw new SceneOpException(i, "update_item", "layer not found: " + op.layerId());
            }
            layerId = op.layerId();
        }
        Item updated = new Item(it.id(), it.pieceId(), layerId,
                op.x() == null ? it.x() : SceneDocument.toPx(op.x()),
                op.y() == null ? it.y() : SceneDocument.toPx(op.y()),
                op.width() == null ? it.width() : SceneDocument.toPx(positive(i, "update_item", "width", op.width(), 1)),
                op.height() == null ? it.height() : SceneDocument.toPx(positive(i, "update_item", "height", op.height(), 1)),
                op.rotation() == null ? it.rotation() : op.rotation(),
                it.widthAttr(), it.heightAttr(), it.ports(), it.ledPath(), it.portTypes());
        doc.items().set(index, updated);
        log.add("item " + it.id() + " updated");
    }

    private void setItemPorts(int i, SceneOp.SetItemPorts op) {
        int index = indexOfItem(i, "set_item_ports", op.itemId());
        Item it = doc.items().get(index);
        PieceRef piece = context.pieceById(it.pieceId() == null ? -1 : it.pieceId()).orElse(null);
        Bindings bindings = resolvePorts(i, "set_item_ports", piece, op.ports(), op.portTypes());
        doc.items().set(index, it.withPorts(bindings.ports(), bindings.portTypes()));
        log.add("item " + it.id() + " ports set to " + bindings.ports()
                + (bindings.portTypes().isEmpty() ? "" : " playing " + bindings.portTypes()));
    }

    private void removeItem(int i, SceneOp.RemoveItem op) {
        int index = indexOfItem(i, "remove_item", op.itemId());
        Item it = doc.items().remove(index);
        List<String> trackIds = doc.tracks().stream().filter(t -> it.id().equals(t.itemId())).map(Track::id).toList();
        doc.tracks().removeIf(t -> it.id().equals(t.itemId()));
        doc.clips().removeIf(c -> trackIds.contains(c.trackId()));
        doc.stripOrder().values().forEach(list -> list.remove(it.id()));
        log.add("item " + it.id() + " removed with " + trackIds.size() + " track(s)");
    }

    // ---------- tracks ----------

    private void addTrack(int i, SceneOp.AddTrack op) {
        Item it = doc.items().get(indexOfItem(i, "add_track", op.itemId()));
        String name = op.name() == null || op.name().isBlank()
                ? context.pieceById(it.pieceId() == null ? -1 : it.pieceId()).map(PieceRef::name).orElse("Pista")
                : op.name().trim();
        Track track = new Track(uniqueId("track"), it.id(), name);
        doc.tracks().add(track);
        created.add(track.id());
        log.add("track " + track.id() + " added to item " + it.id());
    }

    private void renameTrack(int i, SceneOp.RenameTrack op) {
        int index = indexOfTrack(i, "rename_track", op.trackId());
        if (op.name() == null || op.name().isBlank()) {
            throw new SceneOpException(i, "rename_track", "name is required");
        }
        Track t = doc.tracks().get(index);
        doc.tracks().set(index, new Track(t.id(), t.itemId(), op.name().trim()));
        log.add("track " + t.id() + " renamed");
    }

    private void removeTrack(int i, SceneOp.RemoveTrack op) {
        int index = indexOfTrack(i, "remove_track", op.trackId());
        Track t = doc.tracks().remove(index);
        doc.clips().removeIf(c -> t.id().equals(c.trackId()));
        log.add("track " + t.id() + " removed");
    }

    // ---------- clips ----------

    private void addClip(int i, SceneOp.AddClip op) {
        Track track = resolveTrack(i, "add_clip", op.itemId(), op.trackId(), true);
        Item item = doc.items().get(indexOfItem(i, "add_clip", track.itemId()));
        ActionRef action = resolveAction(i, "add_clip", item, op.action());
        long duration = op.durationMs() == null ? SceneDocument.DEFAULT_CLIP_MS : op.durationMs();
        if (duration <= 0) {
            throw new SceneOpException(i, "add_clip", "durationMs must be positive");
        }
        long start;
        if (op.startMs() != null) {
            start = op.startMs();
        } else if (op.after() != null && !op.after().isBlank()) {
            Clip after = doc.clips().get(indexOfClip(i, "add_clip", op.after()));
            start = after.endMs() + (op.gapMs() == null ? 0 : op.gapMs());
        } else {
            start = doc.clips().stream().filter(c -> track.id().equals(c.trackId())).mapToLong(Clip::endMs).max().orElse(0);
        }
        if (start < 0) {
            throw new SceneOpException(i, "add_clip", "startMs must not be negative");
        }
        Clip clip = new Clip(uniqueId("clip"), track.id(), action.id(), start, duration,
                op.params() == null ? Map.of() : op.params(), op.label());
        doc.clips().add(clip);
        created.add(clip.id());
        log.add("clip " + clip.id() + " (" + action.name() + " @" + start + "ms) added to track " + track.id());
    }

    private void updateClip(int i, SceneOp.UpdateClip op) {
        int index = indexOfClip(i, "update_clip", op.clipId());
        Clip c = doc.clips().get(index);
        Integer actionId = c.pieceTypeActionId();
        if (op.action() != null && !op.action().isBlank()) {
            Track track = doc.tracks().get(indexOfTrack(i, "update_clip", c.trackId()));
            Item item = doc.items().get(indexOfItem(i, "update_clip", track.itemId()));
            actionId = resolveAction(i, "update_clip", item, op.action()).id();
        }
        long duration = c.durationMs();
        if (op.durationMs() != null) {
            if (op.durationMs() <= 0) throw new SceneOpException(i, "update_clip", "durationMs must be positive");
            duration = op.durationMs();
        }
        Map<String, Object> params = new LinkedHashMap<>(c.paramValues());
        if (op.params() != null) {
            if (Boolean.TRUE.equals(op.replaceParams())) params.clear();
            params.putAll(op.params());
        }
        doc.clips().set(index, new Clip(c.id(), c.trackId(), actionId, c.startMs(), duration, params,
                op.label() == null ? c.label() : op.label()));
        log.add("clip " + c.id() + " updated");
    }

    private void moveClip(int i, SceneOp.MoveClip op) {
        int index = indexOfClip(i, "move_clip", op.clipId());
        Clip c = doc.clips().get(index);
        String trackId = c.trackId();
        if (op.trackId() != null && !op.trackId().isBlank()) {
            indexOfTrack(i, "move_clip", op.trackId());
            trackId = op.trackId();
        }
        long start = op.startMs() == null ? c.startMs() : op.startMs();
        if (start < 0) {
            throw new SceneOpException(i, "move_clip", "startMs must not be negative");
        }
        doc.clips().set(index, new Clip(c.id(), trackId, c.pieceTypeActionId(), start, c.durationMs(), c.paramValues(),
                c.label()));
        log.add("clip " + c.id() + " moved to " + start + "ms on track " + trackId);
    }

    private void removeClip(int i, SceneOp.RemoveClip op) {
        Clip c = doc.clips().remove(indexOfClip(i, "remove_clip", op.clipId()));
        log.add("clip " + c.id() + " removed");
    }

    // ---------- document ----------

    private void setBoard(int i, SceneOp.SetBoard op) {
        Board b = doc.board();
        double width = op.width() == null ? b.width() : SceneDocument.toPx(positive(i, "set_board", "width", op.width(), 1));
        double height = op.height() == null ? b.height() : SceneDocument.toPx(positive(i, "set_board", "height", op.height(), 1));
        doc = new SceneDocument(doc.schemaVersion(), new Board(width, height, op.background() == null ? b.background() : op.background()),
                doc.layers(), doc.items(), doc.tracks(), doc.clips(), doc.durationMs(), doc.fps(), doc.stripOrder(), doc.schedule());
        log.add("board set to " + SceneDocument.toCm(width) + "x" + SceneDocument.toCm(height) + " cm");
    }

    private void setStripOrder(int i, SceneOp.SetStripOrder op) {
        PortRef port = resolvePort(i, "set_strip_order", op.port());
        List<String> items = new ArrayList<>();
        for (String itemId : op.itemIds() == null ? List.<String>of() : op.itemIds()) {
            indexOfItem(i, "set_strip_order", itemId);
            items.add(itemId);
        }
        doc.stripOrder().put(String.valueOf(port.id()), items);
        log.add("strip order of port " + port.name() + " set");
    }

    private void setSchedule(int i, SceneOp.SetSchedule op) {
        if (op.schedule() == null) {
            doc = doc.withSchedule(null);
            log.add("schedule removed: the button always works");
            return;
        }
        List<String> problems = ScheduleRules.problems(op.schedule());
        if (!problems.isEmpty()) {
            throw new SceneOpException(i, "set_schedule", String.join("; ", problems));
        }
        doc = doc.withSchedule(op.schedule());
        log.add("schedule set: " + op.schedule().rule() + " with " + op.schedule().windows().size() + " window(s)");
    }

    // ---------- resolution helpers ----------

    private PieceRef resolvePiece(int i, String op, Integer id, String name) {
        if (id != null) {
            return context.pieceById(id).orElseThrow(() -> new SceneOpException(i, op, "piece not found: " + id));
        }
        if (name == null || name.isBlank()) {
            throw new SceneOpException(i, op, "pieceId or pieceName is required");
        }
        List<PieceRef> matches = context.piecesByName(name.trim());
        List<PieceRef> exact = matches.stream().filter(p -> p.name().equalsIgnoreCase(name.trim())).toList();
        List<PieceRef> chosen = exact.size() == 1 ? exact : matches;
        if (chosen.size() == 1) return chosen.get(0);
        if (chosen.isEmpty()) {
            throw new SceneOpException(i, op, "no piece named \"" + name + "\"");
        }
        throw new SceneOpException(i, op, "several pieces match \"" + name + "\"; use pieceId",
                chosen.stream().map(p -> p.id() + " " + p.name()).toList());
    }

    /** Port ids bound to an item and the piece type each one plays for it (explicit entries only). */
    private record Bindings(List<Integer> ports, Map<Integer, Integer> portTypes) {
    }

    /**
     * Resolves port references and the type each one plays: the {@code portTypes} entry (by port id or
     * name → type id or name of the piece), else the port's own type. The role must be a type of the
     * piece and of the port's kind, as the validator and the export require.
     */
    private Bindings resolvePorts(int i, String op, PieceRef piece, List<String> refs, Map<String, String> typeRefs) {
        List<Integer> ports = new ArrayList<>();
        Map<Integer, Integer> roles = new LinkedHashMap<>();
        if (refs == null) return new Bindings(ports, roles);
        Map<String, String> pendingTypeRefs = typeRefs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(typeRefs);
        for (String ref : refs) {
            PortRef port = resolvePort(i, op, ref);
            Integer role = port.pieceTypeId();
            String typeRef = takeTypeRef(pendingTypeRefs, port);
            if (typeRef != null) {
                role = resolvePieceType(i, op, piece, port, typeRef);
                roles.put(port.id(), role);
            }
            if (piece != null && role != null) {
                int typeIndex = piece.typeIds().indexOf(role);
                if (typeIndex < 0) {
                    throw new SceneOpException(i, op, "port \"" + port.name() + "\" belongs to a piece type that \""
                            + piece.name() + "\" does not have; pass portTypes to make it play one of its types of kind "
                            + port.kind(), piece.typeNames());
                }
                if (!Objects.equals(piece.typeKinds().get(typeIndex), port.kind())) {
                    throw new SceneOpException(i, op, "port \"" + port.name() + "\" is a " + port.kind()
                            + " output and cannot play type \"" + piece.typeNames().get(typeIndex) + "\" ("
                            + piece.typeKinds().get(typeIndex) + ")");
                }
            }
            if (!ports.contains(port.id())) ports.add(port.id());
        }
        if (!pendingTypeRefs.isEmpty()) {
            throw new SceneOpException(i, op, "portTypes names ports that are not in ports: " + pendingTypeRefs.keySet());
        }
        return new Bindings(ports, roles);
    }

    /** Removes and returns the {@code portTypes} entry of a port (keyed by its id or its name), or {@code null}. */
    private static String takeTypeRef(Map<String, String> typeRefs, PortRef port) {
        for (String key : typeRefs.keySet()) {
            boolean byId = parseInt(key).map(id -> id == port.id()).orElse(false);
            if (byId || key.trim().equalsIgnoreCase(port.name())) {
                return typeRefs.remove(key);
            }
        }
        return null;
    }

    /** A type of the piece, by id or exact name (case-insensitive); the item's piece must be known. */
    private static int resolvePieceType(int i, String op, PieceRef piece, PortRef port, String ref) {
        if (piece == null) {
            throw new SceneOpException(i, op, "the item's piece no longer exists, so port \"" + port.name()
                    + "\" cannot be given a type");
        }
        if (ref == null || ref.isBlank()) {
            throw new SceneOpException(i, op, "the type of port \"" + port.name() + "\" is empty");
        }
        Optional<Integer> byId = parseInt(ref);
        for (int t = 0; t < piece.typeIds().size(); t++) {
            int typeId = piece.typeIds().get(t);
            if (byId.map(id -> id == typeId).orElse(false) || piece.typeNames().get(t).equalsIgnoreCase(ref.trim())) {
                return typeId;
            }
        }
        throw new SceneOpException(i, op, "\"" + piece.name() + "\" has no type " + ref + " for port \"" + port.name() + "\"",
                piece.typeNames());
    }

    private PortRef resolvePort(int i, String op, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new SceneOpException(i, op, "port reference is empty");
        }
        Optional<PortRef> byId = parseInt(ref).flatMap(context::portById);
        if (byId.isPresent()) return byId.get();
        return context.portByName(ref.trim())
                .orElseThrow(() -> new SceneOpException(i, op, "port not found: " + ref));
    }

    private ActionRef resolveAction(int i, String op, Item item, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new SceneOpException(i, op, "action is required");
        }
        PieceRef piece = context.pieceById(item.pieceId() == null ? -1 : item.pieceId())
                .orElseThrow(() -> new SceneOpException(i, op, "the item's piece no longer exists: " + item.pieceId()));
        List<ActionRef> actions = context.actionsOfPiece(piece);
        Optional<Integer> id = parseInt(ref);
        if (id.isPresent()) {
            return actions.stream().filter(a -> a.id() == id.get()).findFirst()
                    .orElseThrow(() -> new SceneOpException(i, op, "action " + ref + " is not an action of the types of \""
                            + piece.name() + "\"", actions.stream().map(a -> a.id() + " " + a.name() + " (" + a.typeName() + ")").toList()));
        }
        List<ActionRef> matches = actions.stream().filter(a -> a.name().equalsIgnoreCase(ref.trim())
                || (a.displayName() != null && a.displayName().equalsIgnoreCase(ref.trim()))).toList();
        if (matches.size() == 1) return matches.get(0);
        if (matches.isEmpty()) {
            throw new SceneOpException(i, op, "no action named \"" + ref + "\" on the types of \"" + piece.name() + "\"",
                    actions.stream().map(a -> a.id() + " " + a.name() + " (" + a.typeName() + ")").toList());
        }
        throw new SceneOpException(i, op, "several actions named \"" + ref + "\"; use the action id",
                matches.stream().map(a -> a.id() + " " + a.name() + " (" + a.typeName() + ")").toList());
    }

    private Track resolveTrack(int i, String op, String itemId, String trackId, boolean createIfMissing) {
        if (trackId != null && !trackId.isBlank()) {
            return doc.tracks().get(indexOfTrack(i, op, trackId));
        }
        if (itemId == null || itemId.isBlank()) {
            throw new SceneOpException(i, op, "itemId or trackId is required");
        }
        Item item = doc.items().get(indexOfItem(i, op, itemId));
        Optional<Track> existing = doc.tracks().stream().filter(t -> item.id().equals(t.itemId())).findFirst();
        if (existing.isPresent()) return existing.get();
        if (!createIfMissing) {
            throw new SceneOpException(i, op, "item " + itemId + " has no track");
        }
        String name = context.pieceById(item.pieceId() == null ? -1 : item.pieceId()).map(PieceRef::name).orElse("Pista");
        Track track = new Track(uniqueId("track"), item.id(), name);
        doc.tracks().add(track);
        created.add(track.id());
        return track;
    }

    /** Asks the context for ids until one is unused anywhere in the document. */
    private String uniqueId(String prefix) {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String id = context.newId(prefix);
            boolean used = doc.layers().stream().anyMatch(l -> l.id().equals(id))
                    || doc.items().stream().anyMatch(it -> it.id().equals(id))
                    || doc.tracks().stream().anyMatch(t -> t.id().equals(id))
                    || doc.clips().stream().anyMatch(c -> c.id().equals(id));
            if (!used) return id;
        }
        throw new IllegalStateException("could not generate a unique id for " + prefix);
    }

    private Optional<Layer> findLayer(String id) {
        return doc.layers().stream().filter(l -> l.id().equals(id)).findFirst();
    }

    private int indexOfItem(int i, String op, String itemId) {
        for (int k = 0; k < doc.items().size(); k++) {
            if (doc.items().get(k).id().equals(itemId)) return k;
        }
        throw new SceneOpException(i, op, "item not found: " + itemId);
    }

    private int indexOfTrack(int i, String op, String trackId) {
        for (int k = 0; k < doc.tracks().size(); k++) {
            if (doc.tracks().get(k).id().equals(trackId)) return k;
        }
        throw new SceneOpException(i, op, "track not found: " + trackId);
    }

    private int indexOfClip(int i, String op, String clipId) {
        for (int k = 0; k < doc.clips().size(); k++) {
            if (doc.clips().get(k).id().equals(clipId)) return k;
        }
        throw new SceneOpException(i, op, "clip not found: " + clipId);
    }

    private static double positive(int i, String op, String field, Double value, double fallback) {
        if (value == null) return fallback;
        if (value <= 0) throw new SceneOpException(i, op, field + " must be positive");
        return value;
    }

    private static Optional<Integer> parseInt(String ref) {
        try {
            return Optional.of(Integer.valueOf(ref.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
