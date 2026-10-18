package com.stocka.backend.modules.timelines.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * In-memory {@link SceneContext} for tests, with deterministic ids ({@code item-1}, {@code item-2}…).
 */
class StubSceneContext implements SceneContext {

    final Map<Integer, PieceRef> pieces = new LinkedHashMap<>();
    final Map<Integer, PortRef> ports = new LinkedHashMap<>();
    final Map<Integer, ActionRef> actions = new LinkedHashMap<>();
    private final Map<String, AtomicInteger> counters = new LinkedHashMap<>();

    StubSceneContext piece(int id, String name, int typeId, String typeName, String kind) {
        pieces.put(id, new PieceRef(id, name, List.of(typeId), List.of(typeName), listOf(kind)));
        return this;
    }

    StubSceneContext piece(int id, String name, List<Integer> typeIds, List<String> typeNames, List<String> kinds) {
        pieces.put(id, new PieceRef(id, name, typeIds, typeNames, kinds));
        return this;
    }

    StubSceneContext port(int id, String name, Integer pieceTypeId, String kind) {
        ports.put(id, new PortRef(id, name, pieceTypeId, kind));
        return this;
    }

    StubSceneContext action(int id, String name, int typeId, String typeName, String kind, ActionParameterDto... params) {
        actions.put(id, new ActionRef(id, name, name, typeId, typeName, kind, List.of(params)));
        return this;
    }

    static ActionParameterDto param(String name, AttributeType type, boolean required, boolean dynamic,
                                    String staticValue, boolean duration) {
        ActionParameterDto p = new ActionParameterDto();
        p.setName(name);
        p.setDisplayName(name);
        p.setType(type);
        p.setRequired(required);
        p.setDynamic(dynamic);
        p.setStaticValue(staticValue);
        p.setIsDuration(duration);
        return p;
    }

    static ActionParameterDto select(String name, boolean required, List<String> options) {
        ActionParameterDto p = param(name, AttributeType.SELECT, required, true, null, false);
        AttributeValidatorsDto v = new AttributeValidatorsDto();
        v.setOptions(options);
        p.setValidators(v);
        return p;
    }

    private static List<String> listOf(String kind) {
        List<String> out = new ArrayList<>();
        out.add(kind);
        return out;
    }

    @Override
    public Optional<PieceRef> pieceById(int id) {
        return Optional.ofNullable(pieces.get(id));
    }

    @Override
    public List<PieceRef> piecesByName(String name) {
        String needle = name.toLowerCase(Locale.ROOT);
        return pieces.values().stream().filter(p -> p.name().toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    @Override
    public Optional<PortRef> portById(int id) {
        return Optional.ofNullable(ports.get(id));
    }

    @Override
    public Optional<PortRef> portByName(String name) {
        return ports.values().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }

    @Override
    public List<ActionRef> actionsOfPiece(PieceRef piece) {
        return actions.values().stream().filter(a -> piece.typeIds().contains(a.typeId())).toList();
    }

    @Override
    public Optional<ActionRef> actionById(int id) {
        return Optional.ofNullable(actions.get(id));
    }

    @Override
    public String newId(String prefix) {
        return prefix + "-" + counters.computeIfAbsent(prefix, k -> new AtomicInteger()).incrementAndGet();
    }
}
