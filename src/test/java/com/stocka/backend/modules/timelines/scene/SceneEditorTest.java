package com.stocka.backend.modules.timelines.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Clip;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Item;
import com.stocka.backend.modules.timelines.scene.SceneEditor.Change;

@DisplayName("SceneEditor")
class SceneEditorTest {

    private StubSceneContext ctx;
    private SceneDocument doc;

    @BeforeEach
    void setUp() {
        ctx = new StubSceneContext()
                .piece(1, "Pastor", 10, "Figura", null)
                .piece(2, "Casa", 20, "Edifici", "led_segment")
                .piece(3, "Pastor petit", 10, "Figura", null)
                .port(100, "Fondo1", 20, "led_segment")
                .port(101, "Focos", 30, "relay")
                .action(500, "animate", 20, "Edifici", "led_segment",
                        StubSceneContext.param("animation_type", AttributeType.SELECT, true, false, "fade", false),
                        StubSceneContext.param("color", AttributeType.TEXT, true, true, null, false),
                        StubSceneContext.param("duration", AttributeType.DECIMAL, false, true, null, true))
                .action(501, "clear", 20, "Edifici", "led_segment")
                .action(502, "wave", 10, "Figura", null);
        doc = SceneDocument.empty(60_000, null);
    }

    private Change apply(SceneOp... ops) {
        Change change = SceneEditor.apply(doc, List.of(ops), ctx);
        doc = change.document();
        return change;
    }

    private SceneOpException failure(SceneOp... ops) {
        try {
            SceneEditor.apply(doc, List.of(ops), ctx);
        } catch (SceneOpException e) {
            return e;
        }
        throw new AssertionError("expected a SceneOpException");
    }

    @Nested
    @DisplayName("items")
    class Items {

        @Test
        @DisplayName("add_item by id places the piece on the first layer, on a grid, with a track")
        void addById() {
            Change change = apply(new SceneOp.AddItem(2, null, null, null, null, null, null, null, null, null));
            assertThat(change.createdIds()).containsExactly("item-1", "track-1");
            Item item = doc.items().get(0);
            assertThat(item.pieceId()).isEqualTo(2);
            assertThat(item.layerId()).isEqualTo("layer-1");
            assertThat(item.width()).isEqualTo(100);
            assertThat(item.x()).isEqualTo(20);
            assertThat(doc.tracks()).hasSize(1);
            assertThat(doc.tracks().get(0).name()).isEqualTo("Casa");
            assertThat(doc.tracks().get(0).itemId()).isEqualTo("item-1");
        }

        @Test
        @DisplayName("add_item by unique name, with cm geometry and ports by name")
        void addByName() {
            apply(new SceneOp.AddItem(null, "casa", null, 12.5, 7.0, 30.0, 4.0, 90.0, List.of("Fondo1"), null));
            Item item = doc.items().get(0);
            assertThat(item.pieceId()).isEqualTo(2);
            assertThat(item.x()).isEqualTo(125);
            assertThat(item.y()).isEqualTo(70);
            assertThat(item.width()).isEqualTo(300);
            assertThat(item.height()).isEqualTo(40);
            assertThat(item.rotation()).isEqualTo(90);
            assertThat(item.ports()).containsExactly(100);
        }

        @Test
        @DisplayName("an exact name wins over partial matches")
        void exactNameWins() {
            apply(new SceneOp.AddItem(null, "Pastor", null, null, null, null, null, null, null, null));
            assertThat(doc.items().get(0).pieceId()).isEqualTo(1);
        }

        @Test
        @DisplayName("an ambiguous name lists the candidates; an unknown name or id fails")
        void nameErrors() {
            SceneOpException ambiguous = failure(new SceneOp.AddItem(null, "pas", null, null, null, null, null, null, null, null));
            assertThat(ambiguous.getParams()).containsEntry("index", 0).containsEntry("op", "add_item");
            assertThat(ambiguous.getParams().get("detail").toString()).contains("several");
            SceneOpException several = failure(new SceneOp.AddItem(null, "tor", null, null, null, null, null, null, null, null));
            assertThat(several.getParams()).containsKey("candidates");
            assertThat(failure(new SceneOp.AddItem(null, "zzz", null, null, null, null, null, null, null, null)).getParams()
                    .get("detail").toString()).contains("no piece");
            assertThat(failure(new SceneOp.AddItem(99, null, null, null, null, null, null, null, null, null)).getParams()
                    .get("detail").toString()).contains("piece not found");
            assertThat(failure(new SceneOp.AddItem(null, null, null, null, null, null, null, null, null, null)).getParams()
                    .get("detail").toString()).contains("required");
        }

        @Test
        @DisplayName("ports must exist and match the piece's types")
        void portRules() {
            assertThat(failure(new SceneOp.AddItem(2, null, null, null, null, null, null, null, List.of("Nope"), null))
                    .getParams().get("detail").toString()).contains("port not found");
            assertThat(failure(new SceneOp.AddItem(1, null, null, null, null, null, null, null, List.of("100"), null))
                    .getParams().get("detail").toString()).contains("piece type");
        }

        @Test
        @DisplayName("items are placed on a grid of 8 columns")
        void grid() {
            for (int i = 0; i < 9; i++) {
                apply(new SceneOp.AddItem(1, null, null, null, null, null, null, null, null, null));
            }
            assertThat(doc.items().get(7).x()).isEqualTo(20 + 7 * 120);
            assertThat(doc.items().get(8).x()).isEqualTo(20);
            assertThat(doc.items().get(8).y()).isEqualTo(140);
        }

        @Test
        @DisplayName("update_item changes geometry and layer; unknown layer fails")
        void update() {
            apply(new SceneOp.AddItem(1, null, null, null, null, null, null, null, null, null),
                    new SceneOp.AddLayer("Fons"));
            apply(new SceneOp.UpdateItem("item-1", "layer-2", 1.0, 2.0, 3.0, 4.0, 45.0));
            Item it = doc.items().get(0);
            assertThat(it.layerId()).isEqualTo("layer-2");
            assertThat(it.x()).isEqualTo(10);
            assertThat(it.width()).isEqualTo(30);
            assertThat(it.rotation()).isEqualTo(45);
            assertThat(failure(new SceneOp.UpdateItem("item-1", "layer-9", null, null, null, null, null)).getParams()
                    .get("detail").toString()).contains("layer not found");
            assertThat(failure(new SceneOp.UpdateItem("item-1", null, null, null, -1.0, null, null)).getParams()
                    .get("detail").toString()).contains("positive");
        }

        @Test
        @DisplayName("set_item_ports replaces the ports; remove_item cascades to tracks, clips and strip order")
        void portsAndRemoval() {
            apply(new SceneOp.AddItem(2, null, null, null, null, null, null, null, null, null),
                    new SceneOp.SetItemPorts("item-1", List.of("Fondo1"), null),
                    new SceneOp.AddClip("item-1", null, "clear", 0L, null, null, 1000L, null, null),
                    new SceneOp.SetStripOrder("Fondo1", List.of("item-1")));
            assertThat(doc.items().get(0).ports()).containsExactly(100);
            assertThat(doc.clips()).hasSize(1);
            apply(new SceneOp.RemoveItem("item-1"));
            assertThat(doc.items()).isEmpty();
            assertThat(doc.tracks()).isEmpty();
            assertThat(doc.clips()).isEmpty();
            assertThat(doc.stripOrder().get("100")).isEmpty();
        }
    }

    @Nested
    @DisplayName("layers and tracks")
    class LayersAndTracks {

        @Test
        @DisplayName("add_layer stacks on top; add/rename/remove track")
        void layersTracks() {
            Change change = apply(new SceneOp.AddLayer("Fons"));
            assertThat(change.createdIds()).containsExactly("layer-2");
            assertThat(doc.layers().get(1).z()).isEqualTo(1);
            apply(new SceneOp.AddItem(1, null, null, null, null, null, null, null, null, null),
                    new SceneOp.AddTrack("item-1", "Segona"));
            assertThat(doc.tracks()).extracting(t -> t.name()).containsExactly("Pastor", "Segona");
            apply(new SceneOp.RenameTrack("track-2", "Tercera"));
            assertThat(doc.tracks().get(1).name()).isEqualTo("Tercera");
            apply(new SceneOp.AddClip(null, "track-2", "wave", 0L, null, null, null, null, null));
            apply(new SceneOp.RemoveTrack("track-2"));
            assertThat(doc.tracks()).hasSize(1);
            assertThat(doc.clips()).isEmpty();
            assertThat(failure(new SceneOp.AddLayer(" ")).getParams().get("detail").toString()).contains("required");
            assertThat(failure(new SceneOp.RenameTrack("track-9", "x")).getParams().get("detail").toString())
                    .contains("track not found");
        }
    }

    @Nested
    @DisplayName("clips")
    class Clips {

        @BeforeEach
        void item() {
            apply(new SceneOp.AddItem(2, null, null, null, null, null, null, null, List.of("Fondo1"), null));
        }

        @Test
        @DisplayName("add_clip by action name on the item's track, default duration, chained starts")
        void addChained() {
            Change first = apply(new SceneOp.AddClip("item-1", null, "clear", null, null, null, null, null, null));
            assertThat(first.createdIds()).containsExactly("clip-1");
            Clip c1 = doc.clips().get(0);
            assertThat(c1.trackId()).isEqualTo("track-1");
            assertThat(c1.pieceTypeActionId()).isEqualTo(501);
            assertThat(c1.startMs()).isZero();
            assertThat(c1.durationMs()).isEqualTo(SceneDocument.DEFAULT_CLIP_MS);

            apply(new SceneOp.AddClip("item-1", null, "animate", null, null, null, 3000L, Map.of("color", "[255,0,0]"), "Fosa"));
            Clip c2 = doc.clips().get(1);
            assertThat(c2.startMs()).isEqualTo(2000);
            assertThat(c2.paramValues()).containsEntry("color", "[255,0,0]");
            assertThat(c2.label()).isEqualTo("Fosa");

            apply(new SceneOp.AddClip("item-1", null, "500", null, "clip-1", 500L, 1000L, null, null));
            assertThat(doc.clips().get(2).startMs()).isEqualTo(2500);
            assertThat(doc.clips().get(2).pieceTypeActionId()).isEqualTo(500);

            apply(new SceneOp.AddClip(null, "track-1", "clear", 10_000L, null, null, 100L, null, null));
            assertThat(doc.clips().get(3).startMs()).isEqualTo(10_000);
        }

        @Test
        @DisplayName("actions must belong to the piece's types; unknown ones list the options")
        void actionRules() {
            SceneOpException unknown = failure(new SceneOp.AddClip("item-1", null, "wave", 0L, null, null, null, null, null));
            assertThat(unknown.getParams().get("detail").toString()).contains("no action named");
            assertThat(unknown.getParams().get("candidates").toString()).contains("animate").contains("clear");
            assertThat(failure(new SceneOp.AddClip("item-1", null, "502", 0L, null, null, null, null, null)).getParams()
                    .get("detail").toString()).contains("not an action");
            assertThat(failure(new SceneOp.AddClip("item-1", null, null, 0L, null, null, null, null, null)).getParams()
                    .get("detail").toString()).contains("required");
            assertThat(failure(new SceneOp.AddClip(null, null, "clear", 0L, null, null, null, null, null)).getParams()
                    .get("detail").toString()).contains("itemId or trackId");
            assertThat(failure(new SceneOp.AddClip("item-1", null, "clear", -5L, null, null, null, null, null)).getParams()
                    .get("detail").toString()).contains("negative");
            assertThat(failure(new SceneOp.AddClip("item-1", null, "clear", 0L, null, null, 0L, null, null)).getParams()
                    .get("detail").toString()).contains("positive");
            assertThat(failure(new SceneOp.AddClip("item-1", null, "clear", null, "clip-9", null, null, null, null)).getParams()
                    .get("detail").toString()).contains("clip not found");
        }

        @Test
        @DisplayName("update_clip merges or replaces params, changes action, duration and label")
        void update() {
            apply(new SceneOp.AddClip("item-1", null, "animate", 0L, null, null, 1000L, Map.of("color", "red", "x", 1), null));
            apply(new SceneOp.UpdateClip("clip-1", null, 2000L, Map.of("color", "blue"), null, "L"));
            Clip c = doc.clips().get(0);
            assertThat(c.durationMs()).isEqualTo(2000);
            assertThat(c.paramValues()).containsEntry("color", "blue").containsEntry("x", 1);
            assertThat(c.label()).isEqualTo("L");
            apply(new SceneOp.UpdateClip("clip-1", "clear", null, Map.of(), true, null));
            c = doc.clips().get(0);
            assertThat(c.pieceTypeActionId()).isEqualTo(501);
            assertThat(c.paramValues()).isEmpty();
            assertThat(c.label()).isEqualTo("L");
            assertThat(failure(new SceneOp.UpdateClip("clip-1", null, 0L, null, null, null)).getParams()
                    .get("detail").toString()).contains("positive");
        }

        @Test
        @DisplayName("move_clip and remove_clip")
        void moveRemove() {
            apply(new SceneOp.AddClip("item-1", null, "clear", 0L, null, null, 1000L, null, null),
                    new SceneOp.AddTrack("item-1", "B"));
            apply(new SceneOp.MoveClip("clip-1", 5000L, "track-2"));
            assertThat(doc.clips().get(0).startMs()).isEqualTo(5000);
            assertThat(doc.clips().get(0).trackId()).isEqualTo("track-2");
            assertThat(failure(new SceneOp.MoveClip("clip-1", -1L, null)).getParams().get("detail").toString()).contains("negative");
            assertThat(failure(new SceneOp.MoveClip("clip-1", null, "track-9")).getParams().get("detail").toString())
                    .contains("track not found");
            apply(new SceneOp.RemoveClip("clip-1"));
            assertThat(doc.clips()).isEmpty();
            assertThat(failure(new SceneOp.RemoveClip("clip-1")).getParams().get("detail").toString()).contains("clip not found");
        }
    }

    @Nested
    @DisplayName("document")
    class Document {

        @Test
        @DisplayName("set_board validates its numbers")
        void board() {
            apply(new SceneOp.SetBoard(200.0, null, "#000"));
            assertThat(doc.board().width()).isEqualTo(2000);
            assertThat(doc.board().height()).isEqualTo(720);
            assertThat(doc.board().background()).isEqualTo("#000");
            assertThat(failure(new SceneOp.SetBoard(-1.0, null, null)).getParams().get("detail").toString()).contains("positive");
        }

        @Test
        @DisplayName("the show lasts until the end of its last clip, also when it grows, shrinks or loses every clip")
        void durationFollowsTheLastClip() {
            assertThat(doc.durationMs()).isEqualTo(SceneDocument.DEFAULT_DURATION_MS);
            apply(new SceneOp.AddItem(2, null, null, null, null, null, null, null, null, null));
            Change added = apply(new SceneOp.AddClip("item-1", null, "clear", 90_000L, null, null, 5000L, null, null));
            assertThat(doc.durationMs()).isEqualTo(95_000);
            String clipId = added.createdIds().get(added.createdIds().size() - 1);
            apply(new SceneOp.MoveClip(clipId, 1000L, null));
            assertThat(doc.durationMs()).isEqualTo(6000);
            apply(new SceneOp.RemoveClip(clipId));
            assertThat(doc.durationMs()).as("without clips the last length stays").isEqualTo(6000);
        }

        @Test
        @DisplayName("set_strip_order resolves the port and checks the items")
        void stripOrder() {
            apply(new SceneOp.AddItem(2, null, null, null, null, null, null, null, null, null));
            apply(new SceneOp.SetStripOrder("100", List.of("item-1")));
            assertThat(doc.stripOrder()).containsEntry("100", List.of("item-1"));
            assertThat(failure(new SceneOp.SetStripOrder("Fondo1", List.of("item-9"))).getParams().get("detail").toString())
                    .contains("item not found");
            assertThat(failure(new SceneOp.SetStripOrder("nope", List.of())).getParams().get("detail").toString())
                    .contains("port not found");
        }

        @Test
        @DisplayName("set_schedule stores a valid schedule and keeps it through other edits")
        void scheduleSet() {
            SceneDocument.Schedule schedule = new SceneDocument.Schedule("block",
                    List.of(new SceneDocument.ScheduleWindow(List.of("sat", "sun"), "13:00", "16:00")));
            Change change = apply(new SceneOp.SetSchedule(schedule));
            assertThat(doc.schedule()).isEqualTo(schedule);
            assertThat(change.log()).anySatisfy(line -> assertThat(line).contains("schedule set: block with 1 window"));
            apply(new SceneOp.SetBoard(100.0, 50.0, null));
            assertThat(doc.schedule()).isEqualTo(schedule);
        }

        @Test
        @DisplayName("set_schedule with null removes the schedule")
        void scheduleRemoved() {
            apply(new SceneOp.SetSchedule(new SceneDocument.Schedule("allow",
                    List.of(new SceneDocument.ScheduleWindow(List.of("mon"), "17:00", "21:00")))));
            Change change = apply(new SceneOp.SetSchedule(null));
            assertThat(doc.schedule()).isNull();
            assertThat(change.log()).anySatisfy(line -> assertThat(line).contains("schedule removed"));
        }

        @Test
        @DisplayName("set_schedule rejects an invalid schedule with every problem in the detail")
        void scheduleInvalid() {
            SceneOpException e = failure(new SceneOp.SetSchedule(new SceneDocument.Schedule("maybe",
                    List.of(new SceneDocument.ScheduleWindow(List.of("mon"), "17:00", "17:00")))));
            assertThat(e.getParams()).containsEntry("op", "set_schedule").containsEntry("index", 0);
            assertThat(e.getParams().get("detail").toString())
                    .contains("rule must be allow or block").contains("it lasts nothing");
        }

        @Test
        @DisplayName("a failing operation in the middle leaves the input document untouched")
        void atomic() {
            SceneDocument before = doc;
            assertThatThrownBy(() -> SceneEditor.apply(before, List.of(
                    new SceneOp.AddItem(1, null, null, null, null, null, null, null, null, null),
                    new SceneOp.RemoveClip("missing")), ctx))
                    .isInstanceOf(SceneOpException.class)
                    .satisfies(e -> assertThat(((SceneOpException) e).getParams()).containsEntry("index", 1));
            // The editor mutates the lists of the document it was given: callers must not reuse it on failure.
            assertThat(failure(new SceneOp.SetBoard(0.0, null, null)).getParams()).containsEntry("index", 0);
        }
    }


    @Nested
    @DisplayName("port roles (portTypes)")
    class PortRoles {

        @BeforeEach
        void twoRelayTypes() {
            ctx.piece(4, "Molí", List.of(40, 41), List.of("Llum", "Moviment"), List.of("relay", "relay"))
                    .port(102, "Sortida 2", 41, "relay")
                    .port(103, "Sortida 3", 41, "relay");
        }

        @Test
        @DisplayName("set_item_ports binds each port as a type of the piece, by port id or name and type id or name")
        void setRoles() {
            apply(new SceneOp.AddItem(4, null, null, null, null, null, null, null, null, null));
            Change change = apply(new SceneOp.SetItemPorts("item-1", List.of("Sortida 2", "103"), Map.of("sortida 2", "Llum", "103", "41")));
            Item it = doc.items().get(0);
            assertThat(it.ports()).containsExactly(102, 103);
            assertThat(it.portTypes()).containsExactly(Map.entry(102, 40), Map.entry(103, 41));
            assertThat(change.log()).anyMatch(l -> l.contains("playing"));
            // update_item keeps the roles; set_item_ports without roles falls back to the ports' own types
            apply(new SceneOp.UpdateItem("item-1", null, 5.0, null, null, null, null));
            assertThat(doc.items().get(0).portTypes()).containsEntry(102, 40);
            apply(new SceneOp.SetItemPorts("item-1", List.of("Sortida 2"), null));
            assertThat(doc.items().get(0).portTypes()).isEmpty();
        }

        @Test
        @DisplayName("add_item accepts a port of another type when portTypes makes it play a type of the piece")
        void addItemWithRole() {
            apply(new SceneOp.AddItem(4, null, null, null, null, null, null, null, List.of("Focos"), Map.of("Focos", "Llum")));
            assertThat(doc.items().get(0).ports()).containsExactly(101);
            assertThat(doc.items().get(0).portTypes()).containsExactly(Map.entry(101, 40));
        }

        @Test
        @DisplayName("refuses a foreign type without a role, a type the piece lacks, another kind, unknown keys and blanks")
        void failures() {
            apply(new SceneOp.AddItem(4, null, null, null, null, null, null, null, null, null));
            assertThat(detail(new SceneOp.SetItemPorts("item-1", List.of("Focos"), null))).contains("does not have").contains("portTypes");
            assertThat(detail(new SceneOp.SetItemPorts("item-1", List.of("Focos"), Map.of("Focos", "Edifici")))).contains("has no type");
            assertThat(detail(new SceneOp.SetItemPorts("item-1", List.of("Fondo1"), Map.of("Fondo1", "Llum")))).contains("cannot play");
            assertThat(detail(new SceneOp.SetItemPorts("item-1", List.of("Sortida 2"), Map.of("Sortida 3", "Llum")))).contains("not in ports");
            assertThat(detail(new SceneOp.SetItemPorts("item-1", List.of("Sortida 2"), Map.of("Sortida 2", " ")))).contains("empty");
            assertThat(doc.items().get(0).ports()).isEmpty();
        }

        private String detail(SceneOp op) {
            return failure(op).getParams().get("detail").toString();
        }
    }
}
