# Stocka MCP guide

Stocka manages the inventory of nativity-scene (belén) associations: **pieces** (figures, buildings,
lights…) grouped in **organizations**, classified by **piece types** with custom attributes, placed in a
tree of **locations**, owned by users or **contacts**, and animated by **timelines** that the Raspberry Pi
player runs. Users write in Catalan or Spanish; keep their data verbatim.

## Conventions

- Call `me` first. Every organization tool takes `org` = the organization **slug** from `me`.
- Ids are integers. Names are for people; ids are for tools. When a tool accepts a name (for example
  `add_item.pieceName`), it must match exactly one row or the tool returns the candidates.
- Lists are paginated: `page` starts at 0, `size` is at most 50. Read `totalPages`.
- Errors return `{error, message, hint}`. Follow the hint. `auth.insufficient_scope` means the user
  must reconnect the app granting that permission; `auth.forbidden` means the user's role does not
  allow it: tell the user, do not retry.
- Scopes: `read`, `inventory:write`, `timelines:write`, `hardware:write`, `org:manage`, `delete`.
  Deleting anything needs `delete`.
- Dates are ISO-8601. Durations in milliseconds unless the field says otherwise.

## Pieces

1. `piece_types_list` tells you the types and their attributes (`name`, `displayName`, `type`,
   `required`, options). Attribute values are strings; SELECT uses one of the options, MULTI_SELECT a
   JSON array of options, BOOLEAN `true`/`false`, dates `YYYY-MM-DD`.
2. `pieces_search` finds pieces. `q` is semantic when `me` reports `semanticSearch: true` for the
   organization (describe the piece: "pastor dormint amb barret"); otherwise it matches name and
   description. Combine with `typeIds`, `locationId`, `status` (`PENDING` = required attributes missing,
   `ACTIVE`) and attribute filters.
3. `piece_get` returns the full detail including attribute values and attachments.
4. `piece_create` / `piece_update` accept attribute values by attribute **name or id**. A piece becomes
   ACTIVE when every required attribute of its types has a value. `piece_update` is partial: send only
   what changes; use `clearLocation` / `clearOwner` to unset.
5. `pieces_export` returns CSV; `pieces_import` takes the same CSV (`dryRun: true` by default; run it
   first, show the report, then import with `dryRun: false`).

## Locations and contacts

`locations_tree` returns the whole tree (ids, parents, piece counts). `location_create` with
`parentId` null creates a root. `location_delete` only works on empty locations. Contacts are owners
who are not Stocka users: `contacts_list` / `contact_create`.

## Timelines

A timeline has a **scene**: a board (cm), layers, **items** (a piece placed on the board, optionally
bound to hardware ports), **tracks** (one per item) and **clips** (an action of the item's piece type
played at `startMs` for `durationMs` with `params`). Read `stocka://schema/scene` for the fields.

Workflow:

1. `timelines_list` / `timeline_get` (summary) / `timeline_scene_get` (`format: "compact"` by default,
   `"raw"` for the full document).
2. `timeline_create` (name) creates the timeline and an empty scene; note the `version`. The show lasts
   until the end of its last clip: there is no length to set. To start from an existing show (a variant,
   next year's belén) use `timeline_duplicate` instead: it copies the whole scene into a new timeline
   (`name` optional) and the copy's scene starts at version 1.
3. `timeline_scene_edit` applies a list of operations atomically and returns the new `version`, the
   ids it created and validation issues. Always pass `expectedVersion`; on
   `timeline_scenes.version_conflict` re-read with `timeline_scene_get` and retry.
   Operations: `add_layer`, `add_item` (pieceId or pieceName; x/y/width/height in cm, auto-placed when
   omitted; `ports` by id or name), `update_item`, `set_item_ports` (`ports` by id or name, any port of the
   kind of one of the piece's types; when the piece has several types of the same kind, e.g. «Llum» and
   «Moviment» both relays, `portTypes` `{"Sortida 3": "Llum", "Sortida 4": "Moviment"}` says which type each
   port plays, and the actions of each type drive only its port; a port without an entry plays its own
   type), `remove_item`, `add_track`,
   `rename_track`, `remove_track`, `add_clip` (itemId or trackId; action by name or id; `startMs` or
   `after: clipId`; `durationMs`; `params` by name), `update_clip`, `move_clip`, `remove_clip`,
   `set_board`, `set_strip_order`, `set_schedule` (when the start button works:
   `{"rule": "allow"|"block", "windows": [{"days": ["mon", …], "from": "17:00", "to": "21:00"}]}`;
   `allow` = only inside a window, `block` = never inside one; local time of the Pi, `to` may be `24:00` and a
   `to` not after `from` crosses midnight; `schedule: null` removes it). It travels as `mode.schedule` of the
   package and only affects a short press of an `onActive: play` sensor; stop, panic and long presses always work,
   and the Pi ignores it until it knows the time (a phone or the editor connecting gives it).
4. `timeline_validate` reports errors (must fix) and warnings (mention them).
5. `timeline_show_export` builds the `stocka-show` package the Raspberry Pi plays (hardware, events,
   assets, `meta.hash`); with blocking errors it fails with `timeline_shows.invalid` and lists them.
   The same package is at `GET /organizations/{org}/timelines/{id}/show`, and `/bundle` adds the media
   files as a `.belen.zip`.
6. `timeline_scene_put` replaces the whole document; use it only for restores.

Clip parameters: static parameters are fixed on the action definition and must not be sent; dynamic
ones are sent in `params`; the parameter flagged `isDuration` is the clip length and must not be sent
either. Types with a hardware `kind` are validated against the output catalog (`stocka://catalog`):
`rgb` is `[r, g, b]`, `enum` one of the listed values, numbers within `min`/`max`.

## Hardware (private feature)

Only organizations whose owner is a Stocka administrator have ports and type actions. `me` reports
`hardwareEnabled`. `catalog_get` (or `stocka://catalog`) lists kinds, actions and board limits;
`ports_list` / `port_create` manage GPIO ports; `piece_type_actions_list` / `piece_type_action_create`
manage the actions a type exposes to timelines.

Motors (`motor`) are H-bridge outputs: two native pins (`pinForward`, `pinReverse`) or an expander with
`channelForward`/`channelReverse`. Their actions are `forward` and `reverse` (the clip length is the turning
time) and `stop`. `maxTimeForward`/`maxTimeReverse` are hard cuts; a clip longer than one is an error unless
that direction has a limit sensor (`limitForward`/`limitReverse`, naming a `sensor` port), then only a warning.
Sensors (`sensor`) are infrastructure inputs (pin or expander + channel) with no timeline actions; `onActive`
says what the player does when one fires: `none` (limit switch), `play` (start button), `stop_show`, `panic`.
A sensor is `digital` by default; with `source: measured` it reads a value through a `model` of
`profiles.sensor` (distance in cm with `vl53l0x` or `hc-sr04`, temperature in °C with `ds18b20`, temperature,
humidity or pressure with `bme280` + `quantity`) and is active when the value is ≤ `threshold` (`activeWhen: below`)
or ≥ it (`above`), released past `hysteresis`. It is used exactly like a digital one (limit switch or `onActive`).

## Limits

- Text fields are truncated at 2 000 characters in results; `piece_get` returns the full description.
- Scene documents are capped at 2 MB; batches of operations at 100.
- 120 tool calls per minute per user.
