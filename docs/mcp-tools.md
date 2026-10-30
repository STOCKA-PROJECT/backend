# Stocka MCP: tools, resources and prompts

Generated from the registered definitions by `McpToolCatalogTest` (`./mvnw test -Dtest=McpToolCatalogTest -Dstocka.docs.write=true`). Every tool takes the organization slug as `org` unless stated otherwise; scopes are checked before the organization role.

## Tools (61)

| Tool | Scope | Hints | Description |
|---|---|---|---|
| `piece_attachment_add_from_url` | `inventory:write` |  | Downloads a file from a public https URL and attaches it to a piece as IMAGE, DOCUMENT or AUDIO. Needs OWNER, MANAGER or USER. |
| `piece_attachment_link` | `read` | read-only idempotent | Returns a temporary download URL for an attachment (to show an image or open a document). |
| `piece_attachment_delete` | `delete` | destructive idempotent | Deletes an attachment. Needs OWNER, MANAGER or USER and the delete scope. |
| `contacts_list` | `read` | read-only idempotent | Contacts of the organization (owners who are not Stocka users), optionally filtered by text. |
| `contact_create` | `inventory:write` |  | Creates a contact. Needs OWNER, MANAGER or USER. |
| `contact_update` | `inventory:write` | idempotent | Changes a contact's details. Needs OWNER or MANAGER. |
| `contact_delete` | `delete` | destructive idempotent | Deletes a contact that owns no pieces. Needs OWNER or MANAGER and the delete scope. |
| `contact_link` | `inventory:write` | idempotent | Links a contact to a Stocka user, optionally moving the contact's pieces to that user. Needs OWNER or MANAGER. |
| `contact_unlink` | `inventory:write` | idempotent | Removes the link between a contact and a user. Needs OWNER or MANAGER. |
| `catalog_get` | `read` | read-only idempotent | The output catalog: hardware kinds, their configuration fields, actions with parameters (types, ranges, enums, which one is the duration) and the Raspberry Pi board limits. Also available as the stocka://catalog resource. |
| `ports_list` | `read` | read-only idempotent | GPIO ports of the organization (kind, pin, configuration, bound piece type). |
| `port_create` | `hardware:write` |  | Creates a port validated against the catalog and the board (pins, DMA, strip ranges). Needs OWNER or MANAGER of a hardware-enabled organization. |
| `port_update` | `hardware:write` | idempotent | Changes a port (name, piece type, pin, configuration; the kind is immutable). |
| `port_delete` | `delete` | destructive idempotent | Deletes a port (a strip with segments cannot be deleted). Needs the delete scope. |
| `piece_type_actions_list` | `read` | read-only idempotent | Actions a piece type exposes to timelines, with their parameters. |
| `piece_type_action_create` | `hardware:write` |  | Adds an action to a piece type, validated against the catalog of the type's kind. Needs OWNER or MANAGER of a hardware-enabled organization. |
| `piece_type_action_update` | `hardware:write` | idempotent | Changes an action of a piece type. |
| `piece_type_action_delete` | `delete` | destructive idempotent | Deletes an action of a piece type (clips using it become invalid). Needs the delete scope. |
| `me` | `read` | read-only idempotent | Returns the authenticated user, the organizations they belong to (with slug, role and feature flags) and the scopes of this connection. Call it first to learn which organization slugs to use. |
| `pieces_export` | `read` | read-only idempotent | Exports pieces as CSV text (fixed columns plus one column per attribute: 'Type / Attribute' or 'Org / Attribute'; references by name: owner_email, location_path with '/', piece_types separated by ';'). Same filters as pieces_search. |
| `pieces_import_template` | `read` | read-only idempotent | Empty CSV with the header row the import expects for this organization. |
| `pieces_import` | `inventory:write` |  | Imports pieces from CSV text. dryRun=true (default) validates and reports per row without writing; dryRun=false applies all rows or none. Blank cells leave fields unchanged. Needs OWNER, MANAGER or USER. |
| `locations_tree` | `read` | read-only idempotent | Every location of the organization as a flat list with parent ids, full paths and how many pieces sit directly in each. Use the ids for piece locations and filters. |
| `location_create` | `inventory:write` |  | Creates a location, as a root or under a parent. Needs OWNER or MANAGER. |
| `location_update` | `inventory:write` | idempotent | Renames, describes or moves a location (parentId or moveToRoot). Needs OWNER or MANAGER. |
| `location_delete` | `delete` | destructive idempotent | Deletes an empty location (no pieces, no children). Needs OWNER or MANAGER and the delete scope. |
| `org_get` | `read` | read-only idempotent | Details of an organization: the caller's role, quota usage (pieces and storage), whether hardware features are enabled and the state of the semantic search index. |
| `org_members_list` | `read` | read-only idempotent | Members of an organization with their role. Member ids are what org_member_set_role and org_member_remove expect. |
| `org_invite` | `org:manage` |  | Invites someone by email to the organization with a role; they receive an email with the link. Needs OWNER or MANAGER. |
| `org_member_set_role` | `org:manage` | idempotent | Changes the role of a member. Needs OWNER. |
| `org_member_remove` | `org:manage` | destructive idempotent | Removes a member from the organization (their pieces stay). Needs OWNER or MANAGER. |
| `search_reindex` | `org:manage` | idempotent | Rebuilds the semantic search index of the organization (use after renaming types or locations, or when search results look stale). Needs OWNER or MANAGER. |
| `pieces_search` | `read` | read-only idempotent | Finds pieces. With q the results are ranked by meaning when the organization has a semantic index (describe what you look for, in any language); otherwise q matches name and description. Combine with type, location, owner, status and attribute filters. Paginated. |
| `piece_get` | `read` | read-only idempotent | Full detail of a piece: description, types, owner, location path, attribute values, attachments. |
| `piece_history` | `read` | read-only idempotent | Change history of a piece (who changed what and when), newest first. Paginated. |
| `piece_create` | `inventory:write` |  | Creates a piece. Attribute values can reference attributes by name, label or id. The piece is ACTIVE when every required attribute of its types has a value, PENDING otherwise. Needs OWNER, MANAGER or USER. |
| `piece_update` | `inventory:write` | idempotent | Partial update of a piece: only the given fields change. Attribute values are merged. Use clearOwner / clearLocation / clearCover to unset. Needs OWNER, MANAGER or USER. |
| `piece_delete` | `delete` | destructive idempotent | Deletes a piece (soft delete; attachments go with it). Needs OWNER, MANAGER or USER and the delete scope. |
| `piece_types_list` | `read` | read-only idempotent | Piece types of the organization with their attributes (name, label, type, required, validation rules) and, when hardware is enabled, their actions. Needed before creating pieces or clips. |
| `piece_type_get` | `read` | read-only idempotent | One piece type with attributes and actions. |
| `piece_type_create` | `inventory:write` |  | Creates a piece type with optional initial attributes. Needs OWNER or MANAGER. |
| `piece_type_update` | `inventory:write` | idempotent | Renames a piece type or sets its hardware kind. Needs OWNER or MANAGER. |
| `piece_type_delete` | `delete` | destructive idempotent | Deletes a piece type that no piece uses. Needs OWNER or MANAGER and the delete scope. |
| `piece_type_attribute_add` | `inventory:write` |  | Adds an attribute to a piece type. Pieces of the type become PENDING if the attribute is required until they get a value. Needs OWNER or MANAGER. |
| `piece_type_attribute_update` | `inventory:write` | idempotent | Changes an attribute of a piece type (name, label, type, required, position, rules). Needs OWNER or MANAGER. |
| `piece_type_attribute_remove` | `delete` | destructive idempotent | Removes an attribute from a piece type and its values from every piece. Needs OWNER or MANAGER and the delete scope. |
| `org_attributes_list` | `read` | read-only idempotent | Organization-wide piece attributes (apply to every piece regardless of type). |
| `org_attribute_create` | `inventory:write` |  | Creates an organization-wide piece attribute. Needs OWNER or MANAGER. |
| `org_attribute_update` | `inventory:write` | idempotent | Changes an organization-wide piece attribute. Needs OWNER or MANAGER. |
| `org_attribute_delete` | `delete` | destructive idempotent | Deletes an organization-wide piece attribute and its values. Needs OWNER or MANAGER and the delete scope. |
| `timelines_list` | `read` | read-only idempotent | Timelines of the organization with their scene version, length and counts. |
| `timeline_get` | `read` | read-only idempotent | A timeline with the compact view of its scene: items (piece names, ports, position in cm), tracks, clips in time order with action names and parameters, and validation findings. |
| `timeline_scene_get` | `read` | read-only idempotent | The scene of a timeline: format compact (default) returns the summary; raw returns the full editor document (large). Both include the version. |
| `timeline_create` | `timelines:write` |  | Creates a timeline with an empty scene (one layer, given length and board size) and returns its id and scene version. Needs OWNER, MANAGER or USER. |
| `timeline_duplicate` | `timelines:write` |  | Copies a timeline with its whole scene (board, items, tracks, clips, schedule) into a new timeline and returns it; the copy's scene starts at version 1. Needs OWNER, MANAGER or USER. |
| `timeline_update` | `timelines:write` | idempotent | Renames a timeline. Needs OWNER, MANAGER or USER. |
| `timeline_scene_edit` | `timelines:write` | idempotent | Edits the scene with a batch of operations (add_item, add_clip, move_clip, remove_clip, set_board …), applied atomically. The show lasts until the end of its last clip. Sizes in cm, times in ms, references by id or unique name. Returns the new version, the ids created (use them in the next batch), validation findings and the compact scene. On timeline_scenes.version_conflict re-read the scene and retry. Needs OWNER, MANAGER or USER. |
| `timeline_scene_put` | `timelines:write` | idempotent | Replaces the whole scene document (for restores or bulk rewrites; prefer timeline_scene_edit). Needs OWNER, MANAGER or USER. |
| `timeline_validate` | `read` | read-only idempotent | Validates the scene (references, clip parameters against the action definitions and the output catalog, timing) without changing anything. |
| `timeline_show_export` | `read` | read-only idempotent | Exports the scene as the stocka-show package the Raspberry Pi plays (hardware from the organization's ports, events with parameters coerced to the catalog types, assets, meta.hash). Fails with timeline_shows.invalid and the blocking issues when the scene has errors; fix them with timeline_scene_edit. The same package is served at GET /organizations/{org}/timelines/{id}/show and bundled with its media at /bundle. |
| `timeline_delete` | `delete` | destructive idempotent | Deletes a timeline and its scene. Needs OWNER, MANAGER or USER and the delete scope. |

### Parameters

#### `piece_attachment_add_from_url`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `pieceId` | integer | yes | Piece id |
| `url` | string | yes | Public https URL of the file to attach |
| `kind` | enum [IMAGE, DOCUMENT, AUDIO] | yes | IMAGE (jpg/png/webp/gif, 25 MB), DOCUMENT (any, 100 MB) or AUDIO (mp3/wav/ogg/flac/m4a, 50 MB) |
| `filename` | string | no | File name to store; defaults to the last path segment of the URL |
| `durationMs` | integer | no | Audio duration in milliseconds (AUDIO only) |

#### `piece_attachment_link`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `pieceId` | integer | yes | Piece id |
| `attachmentId` | integer | yes | Attachment id |

#### `piece_attachment_delete`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `pieceId` | integer | yes | Piece id |
| `attachmentId` | integer | yes | Attachment id |

#### `contacts_list`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `q` | string | no | Text to match against name, last name and email |

#### `contact_create`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `name` | string | yes | First name |
| `lastName` | string | no | Last name |
| `email` | string | no | Email |
| `phone` | string | no | Phone |
| `notes` | string | no | Notes |

#### `contact_update`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `contactId` | integer | yes | Contact id |
| `name` | string | no | New first name |
| `lastName` | string | no | New last name |
| `email` | string | no | New email |
| `phone` | string | no | New phone |
| `notes` | string | no | New notes |

#### `contact_delete`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `contactId` | integer | yes | Contact id |

#### `contact_link`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `contactId` | integer | yes | Contact id |
| `userId` | integer | yes | Stocka user id to link the contact to |
| `migratePieces` | boolean | no | Move the contact's pieces to the user (default false) |

#### `contact_unlink`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `contactId` | integer | yes | Contact id |

#### `catalog_get`

No arguments.

#### `ports_list`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |

#### `port_create`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `name` | string | yes | Port name, unique in the organization |
| `kind` | string | yes | Kind from the catalog: relay, gpio_expander, led_strip, led_segment, motor, audio, moving_head, sensor |
| `pieceTypeId` | integer | no | Piece type id whose kind matches (omit for infrastructure kinds gpio_expander, led_strip and sensor) |
| `pin` | integer | no | BCM GPIO pin for kinds that take one (led_strip; relay and sensor unless they use expander + channel) |
| `config` | object | no | Kind-specific configuration as in the catalog (e.g. relay: expander + channel 0-15 instead of pin, activeLow; gpio_expander: chip mcp23017, bus, address 32-39, activeLow; led_strip: dma, channel, length; led_segment: strip, first, last; motor: pinForward + pinReverse or expander + channelForward + channelReverse, maxTimeForward, maxTimeReverse, optional limitForward/limitReverse naming sensor ports, lockOnTimeout, switchDelayMs; sensor: digital (default) = pin or expander + channel, activeLow, pull up/down/none; or source measured = model of profiles.sensor (vl53l0x, hc-sr04 with pinTrig + pinEcho, ds18b20 with pin, bme280 with quantity), threshold, activeWhen above/below, hysteresis, pollMs; both: debounceMs, onActive none/play/stop_show/panic; moving_head: profile, address 1-512) |

#### `port_update`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `portId` | integer | yes | Port id |
| `name` | string | no | New name |
| `pieceTypeId` | integer | no | New piece type id |
| `pin` | integer | no | New pin |
| `config` | object | no | New configuration (replaces the current one) |

#### `port_delete`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `portId` | integer | yes | Port id |

#### `piece_type_actions_list`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `typeId` | integer | yes | Piece type id |

#### `piece_type_action_create`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `typeId` | integer | yes | Piece type id (the type must have a kind) |
| `name` | string | yes | Action name from the catalog for the type's kind (e.g. turn_on, animate, fill, play) |
| `displayName` | string | no | Label |
| `description` | string | no | Description |
| `position` | integer | no | Order |
| `parameters` | array of object | no | Parameters: every catalog parameter you expose, each either dynamic (set per clip) or static |

#### `piece_type_action_update`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `typeId` | integer | yes | Piece type id |
| `actionId` | integer | yes | Action id |
| `name` | string | no | New name |
| `displayName` | string | no | New label |
| `description` | string | no | New description |
| `position` | integer | no | New order |
| `parameters` | array of object | no | New parameters (replace the current ones) |

#### `piece_type_action_delete`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `typeId` | integer | yes | Piece type id |
| `actionId` | integer | yes | Action id |

#### `me`

No arguments.

#### `pieces_export`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `q` | string | no | Free text filter |
| `typeIds` | array of integer | no | Only these piece type ids |
| `locationId` | integer | no | Only this location id |
| `ownerUserId` | integer | no | Only this owner user id |
| `ownerContactId` | integer | no | Only this owner contact id |
| `status` | enum [ACTIVE, PENDING] | no | PENDING or ACTIVE |
| `attributes` | array of object | no | Attribute value filters |

#### `pieces_import_template`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |

#### `pieces_import`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `csv` | string | yes | CSV text with the same columns as pieces_export / pieces_import_template |
| `mode` | enum [CREATE, UPSERT] | no | CREATE (default) or UPSERT (match existing pieces by serial number) |
| `dryRun` | boolean | no | Validate only and return the per-row report without writing (default true). Run with false only after showing the user the dry-run report |

#### `locations_tree`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |

#### `location_create`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `name` | string | yes | Name, unique among siblings |
| `description` | string | no | Description |
| `parentId` | integer | no | Parent location id; omit to create a root location |

#### `location_update`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `locationId` | integer | yes | Location id |
| `name` | string | no | New name |
| `description` | string | no | New description |
| `parentId` | integer | no | Move under this parent (cannot be itself or a descendant) |
| `moveToRoot` | boolean | no | Move to the root level |

#### `location_delete`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `locationId` | integer | yes | Location id |

#### `org_get`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |

#### `org_members_list`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |

#### `org_invite`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `email` | string | yes | Email of the person to invite |
| `role` | enum [OWNER, MANAGER, USER, SPECTATOR] | yes | Role to grant: OWNER, MANAGER, USER or SPECTATOR |

#### `org_member_set_role`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `memberId` | integer | yes | Member id (from org_members_list, not the user id) |
| `role` | enum [OWNER, MANAGER, USER, SPECTATOR] | yes | New role: OWNER, MANAGER, USER or SPECTATOR |

#### `org_member_remove`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `memberId` | integer | yes | Member id (from org_members_list) |

#### `search_reindex`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |

#### `pieces_search`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `q` | string | no | Free text. Semantic when the organization is indexed (describe the piece), otherwise matched against name and description |
| `typeIds` | array of integer | no | Only pieces having one of these piece type ids |
| `locationId` | integer | no | Only pieces placed directly in this location id |
| `ownerUserId` | integer | no | Only pieces owned by this user id |
| `ownerContactId` | integer | no | Only pieces owned by this contact id |
| `status` | enum [ACTIVE, PENDING] | no | PENDING (required attributes missing) or ACTIVE |
| `attributes` | array of object | no | Attribute value filters |
| `page` | integer | no | Zero-based page (default 0) |
| `size` | integer | no | Page size (default 20, max 50) |

#### `piece_get`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `pieceId` | integer | yes | Piece id |

#### `piece_history`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `pieceId` | integer | yes | Piece id |
| `page` | integer | no | Zero-based page (default 0) |
| `size` | integer | no | Page size (default 20, max 50) |

#### `piece_create`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `name` | string | yes | Name |
| `serialNumber` | string | no | Serial number, unique in the organization |
| `description` | string | no | Free-text description |
| `pieceTypeIds` | array of integer | yes | Piece type ids (at least one) |
| `ownerUserId` | integer | no | Owner user id |
| `ownerContactId` | integer | no | Owner contact id (a contact is an owner who is not a Stocka user) |
| `locationId` | integer | no | Location id |
| `attributeValues` | array of object | no | Attribute values |

#### `piece_update`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `pieceId` | integer | yes | Piece id |
| `name` | string | no | New name |
| `serialNumber` | string | no | New serial number |
| `description` | string | no | New description |
| `pieceTypeIds` | array of integer | no | New set of piece type ids (replaces the current ones) |
| `ownerUserId` | integer | no | New owner user id |
| `ownerContactId` | integer | no | New owner contact id |
| `clearOwner` | boolean | no | Remove the owner |
| `locationId` | integer | no | New location id |
| `clearLocation` | boolean | no | Remove the location |
| `coverAttachmentId` | integer | no | Attachment id to use as cover image |
| `clearCover` | boolean | no | Remove the cover image |
| `attributeValues` | array of object | no | Attribute values to set (others stay) |

#### `piece_delete`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `pieceId` | integer | yes | Piece id |

#### `piece_types_list`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |

#### `piece_type_get`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `typeId` | integer | yes | Piece type id |

#### `piece_type_create`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `name` | string | yes | Type name, unique in the organization |
| `kind` | string | no | Hardware output kind (relay, led_strip, led_segment, motor, audio, moving_head) when pieces of this type are driven by the player; omit for plain inventory |
| `attributes` | array of object | no | Initial attributes |

#### `piece_type_update`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `typeId` | integer | yes | Piece type id |
| `name` | string | no | New name |
| `kind` | string | no | New hardware kind (cannot be removed once set) |

#### `piece_type_delete`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `typeId` | integer | yes | Piece type id |

#### `piece_type_attribute_add`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `typeId` | integer | yes | Piece type id |
| `attribute` | object | yes | The attribute |

#### `piece_type_attribute_update`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `typeId` | integer | yes | Piece type id |
| `attributeId` | integer | yes | Attribute id |
| `name` | string | no | New technical name |
| `displayName` | string | no | New label |
| `type` | enum [TEXT, LONGTEXT, INTEGER, DECIMAL, PRICE, DATE, DATETIME, BOOLEAN, SELECT, MULTI_SELECT, URL, EMAIL, MEMBER] | no | New type |
| `required` | boolean | no | Whether required |
| `position` | integer | no | New position |
| `validators` | object | no | New validation rules (replaces the current ones) |

#### `piece_type_attribute_remove`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `typeId` | integer | yes | Piece type id |
| `attributeId` | integer | yes | Attribute id |

#### `org_attributes_list`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |

#### `org_attribute_create`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `attribute` | object | yes | The attribute |

#### `org_attribute_update`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `attributeId` | integer | yes | Attribute id |
| `name` | string | no | New technical name |
| `displayName` | string | no | New label |
| `type` | enum [TEXT, LONGTEXT, INTEGER, DECIMAL, PRICE, DATE, DATETIME, BOOLEAN, SELECT, MULTI_SELECT, URL, EMAIL, MEMBER] | no | New type |
| `required` | boolean | no | Whether required |
| `position` | integer | no | New position |
| `validators` | object | no | New validation rules (replaces the current ones) |

#### `org_attribute_delete`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `attributeId` | integer | yes | Attribute id |

#### `timelines_list`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |

#### `timeline_get`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `timelineId` | integer | yes | Timeline id |

#### `timeline_scene_get`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `timelineId` | integer | yes | Timeline id |
| `format` | string | no | compact (default: summary with names, cm and issues) or raw (the full editor document) |

#### `timeline_create`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `name` | string | yes | Name, unique in the organization |
| `durationMs` | integer | no | Length in ms of the empty timeline (default 60000); once it has clips the show lasts until the end of the last one |
| `boardWidthCm` | number | no | Board width in cm (default 128) |
| `boardHeightCm` | number | no | Board height in cm (default 72) |

#### `timeline_duplicate`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `timelineId` | integer | yes | Timeline id to copy |
| `name` | string | no | Name of the copy, unique in the organization (default: "<name> (copy)", numbered when taken) |

#### `timeline_update`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `timelineId` | integer | yes | Timeline id |
| `name` | string | yes | New name |

#### `timeline_scene_edit`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `timelineId` | integer | yes | Timeline id |
| `ops` | array of operations | yes | Operations, applied in order and atomically (max 100) |
| `expectedVersion` | integer | no | Scene version you read (timelines_list / timeline_get / previous edit). Required once the scene exists |

#### `timeline_scene_put`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `timelineId` | integer | yes | Timeline id |
| `document` | object | yes | Full scene document (see the stocka://schema/scene resource) |
| `expectedVersion` | integer | no | Scene version you read; required once the scene exists |

#### `timeline_validate`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `timelineId` | integer | yes | Timeline id |

#### `timeline_show_export`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `timelineId` | integer | yes | Timeline id |

#### `timeline_delete`

| Argument | Type | Required | Description |
|---|---|---|---|
| `org` | string | yes | Organization slug |
| `timelineId` | integer | yes | Timeline id |

## Resources

| URI | Name | Description |
|---|---|---|
| `stocka://guide` | Stocka MCP guide | How to use the Stocka tools: recommended workflows, ids and versions, limits. |
| `stocka://catalog` | Output catalog | Hardware output kinds (relay, gpio_expander, led_strip, led_segment, motor, audio, moving_head, sensor), their actions and parameters, and the Raspberry Pi board profile. Needed to write clip parameters and port configurations. |
| `stocka://schema/scene` | Timeline scene schema | JSON Schema of the timeline scene document (board, layers, items, tracks, clips) with the semantics of every field. |
| `stocka://org/{slug}/overview` | Organization overview | Counts of pieces (by status), types, locations and timelines of an organization: a cheap first look. |

## Prompts

| Prompt | Arguments | Description |
|---|---|---|
| `timeline_from_description` | `org`* `description`* `durationSeconds` `name` | Create a complete timeline (scene with items and clips) from a free-text description of the show, using the organization's pieces and their actions. |
| `inventory_audit` | `org`* | Review the organization's pieces for missing data (pending status, no location, no owner, no photo) and propose fixes, asking before changing anything. |
| `find_piece` | `org`* `what`* | Locate pieces from a natural-language description using semantic search and filters. |
