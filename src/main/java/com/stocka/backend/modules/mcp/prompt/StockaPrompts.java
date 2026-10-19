package com.stocka.backend.modules.mcp.prompt;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Guided workflows offered as prompts: building a timeline from a description, auditing the
 * inventory and finding a piece by describing it.
 */
@Component
public class StockaPrompts implements PromptSource {

    @Override
    public List<PromptDefinition> prompts() {
        return List.of(
                new PromptDefinition("timeline_from_description", "Timeline from a description",
                        "Create a complete timeline (scene with items and clips) from a free-text description of "
                                + "the show, using the organization's pieces and their actions.",
                        List.of(new Argument("org", "Organization slug", true),
                                new Argument("description", "What should happen, in the user's words", true),
                                new Argument("durationSeconds", "Total length in seconds (default 120)", false),
                                new Argument("name", "Name of the timeline (default derived from the description)", false)),
                        StockaPrompts::timelineFromDescription),
                new PromptDefinition("inventory_audit", "Inventory audit",
                        "Review the organization's pieces for missing data (pending status, no location, no "
                                + "owner, no photo) and propose fixes, asking before changing anything.",
                        List.of(new Argument("org", "Organization slug", true)),
                        StockaPrompts::inventoryAudit),
                new PromptDefinition("find_piece", "Find a piece by describing it",
                        "Locate pieces from a natural-language description using semantic search and filters.",
                        List.of(new Argument("org", "Organization slug", true),
                                new Argument("what", "Description of the piece being looked for", true)),
                        StockaPrompts::findPiece));
    }

    static String timelineFromDescription(Map<String, String> args) {
        String duration = args.getOrDefault("durationSeconds", "").isBlank() ? "120" : args.get("durationSeconds");
        String name = args.getOrDefault("name", "");
        return """
                Build a timeline in the Stocka organization `%s` from this description:

                "%s"

                Total duration: %s seconds.%s

                Work step by step and keep the user informed:
                1. Call `org_get` for `%s` and read the `stocka://guide` resource if you have not yet.
                2. Find the pieces the description mentions with `pieces_search` (semantic search understands \
                descriptions in Catalan and Spanish). Prefer ACTIVE pieces. If a piece is ambiguous, list the \
                candidates and ask.
                3. For each piece you will use, call `piece_type_get` on its types to learn the available actions \
                and their parameters (static parameters are fixed on the action; dynamic ones are set per clip; \
                the duration parameter is the clip length).
                4. Create the timeline with `timeline_create` (name, durationMs). Reuse an existing one only if the \
                user says so.
                5. Add the pieces and clips with `timeline_scene_edit` in batches of at most 30 operations: \
                `add_item` for each piece (set `ports` when the organization has hardware), then `add_clip` per \
                moment of the show with `startMs`, `durationMs`, the action name and its parameters. Keep the \
                `expectedVersion` returned by the previous call.
                6. Run `timeline_validate` and fix every error it reports; mention the warnings to the user. If the \
                user wants the file for the Raspberry Pi, `timeline_show_export` returns the show package.
                7. Finish with a short summary of the timeline: duration, pieces, number of clips, and how to open \
                it in the editor.
                """.formatted(args.get("org"), args.get("description"), duration,
                name.isBlank() ? "" : " Name it \"" + name + "\".", args.get("org"));
    }

    static String inventoryAudit(Map<String, String> args) {
        return """
                Audit the inventory of the Stocka organization `%s`:

                1. Call `org_get` and `piece_types_list` to understand the types and their required attributes.
                2. Page through `pieces_search` with `status: "PENDING"` and list what each pending piece lacks \
                (call `piece_get` when the list view is not enough).
                3. Then page through ACTIVE pieces and flag those without location, without owner or without \
                a cover photo.
                4. Present the findings as a table grouped by problem, with piece ids and names.
                5. Propose concrete fixes (for example a location to assign). Apply them with `piece_update` only \
                after the user confirms, and never delete anything during an audit.
                """.formatted(args.get("org"));
    }

    static String findPiece(Map<String, String> args) {
        return """
                Find pieces in the Stocka organization `%s` matching this description: "%s".

                Use `pieces_search` with the description as `q` (it is ranked semantically when the organization \
                has the search index; otherwise it is a plain text match on name and description). If there are \
                too many results, narrow them with `typeIds`, `locationId` or `status`. Show the best matches with \
                id, name, types and location, and offer to open the details with `piece_get`.
                """.formatted(args.get("org"), args.get("what"));
    }
}
