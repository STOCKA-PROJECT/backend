# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Stocka Backend — a Spring Boot 4.0.6 REST API (Java 25) providing JWT-based authentication and role-based authorization. Uses MariaDB 11 for persistence and Maven for builds.

## Build & Run Commands

```bash
# Start MariaDB (required before running the app)
docker compose up -d

# Build
./mvnw clean package

# Build (skip tests)
./mvnw clean package -DskipTests

# Run in dev mode (with live reload via DevTools)
./mvnw spring-boot:run

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=BackendApplicationTests
```

The app runs on port **9095**. MariaDB is exposed on host port **3307**.

## Architecture

Module-based layout under `src/main/java/com/stocka/backend/modules/`:

```
modules/
├── auth/          Controller + service for signup/login
├── bootstrap/     RoleSeeder (order=1) and AdminSeeder (order=2) run on startup
├── health/        GET /health (public)
├── contract/      OutputKind + OutputCatalog (copy of rpi4-system/contract/catalog.json) + GET /contract/catalog
├── roles/         Role entity (USER, ADMIN) + repository
├── security/      SecurityConfiguration, JwtAuthenticationFilter, JwtService
├── users/         User entity, UserController, AdminController, UserService
├── oauth/         OAuth 2.1 authorization server for MCP connectors (login/consent pages, DCR, CIMD, connected apps)
├── mcp/           MCP server at /mcp (resource server chain, tools, resources, prompts)
└── timelines/scene/  Scene document model, operations (SceneEditor), validator and summary shared by REST and MCP
```

Each module follows **Controller → Service → Repository** layering with constructor-based DI.

### Authentication & Security

- Stateless JWT auth (HMAC-SHA256, 24h expiration, JJWT library)
- `JwtAuthenticationFilter` extracts Bearer token, validates, and sets `SecurityContext`
- BCrypt password encoding
- Public endpoints: `/auth/**`, `/health`. Everything else requires authentication.
- Role-based access via `@PreAuthorize` annotations (e.g. `hasRole('ADMIN')`)
- User entity implements `UserDetails`; email is used as the Spring Security username

### Database

- MariaDB 11 via Docker Compose (container: `stocka-bd`, port 3307→3306)
- Spring Data JPA with `ddl-auto=update` (auto schema migration)
- H2 available as test-scoped dependency
- Entities: `User` (ManyToOne → `Role`), `Role` (enum: USER, ADMIN)

### Bootstrap Seeders

On startup, `RoleSeeder` creates USER/ADMIN roles, then `AdminSeeder` creates a default admin account. Both are idempotent (skip if data exists).

## API Endpoints

| Method | Path           | Access        | Description              |
|--------|----------------|---------------|--------------------------|
| GET    | `/health`      | Public        | Health check             |
| POST   | `/auth/signup` | Public        | Register user            |
| POST   | `/auth/login`  | Public        | Login, returns JWT       |
| GET    | `/users/me`    | Authenticated | Current user profile     |
| GET    | `/users`       | ADMIN         | List all users           |
| POST   | `/admins`      | ADMIN         | Create admin user        |
| POST   | `/webhooks/resend` | Public (Svix-signed) | Resend webhook events (only when `EMAIL_PROVIDER=resend`) |
| GET    | `/contract/catalog` | Authenticated | Output catalog (kinds, actions, board rules) shared with the belén player |
| GET    | `/organizations/{orgSlug}/search/status` | any member | Semantic index status (XEYE) |
| POST   | `/organizations/{orgSlug}/search/reindex` | OWNER, MANAGER | Rebuild the organization's semantic index |
| POST   | `/admin/search/reindex` | ADMIN | Rebuild every organization's semantic index |
| GET    | `/.well-known/oauth-authorization-server`, `/.well-known/oauth-protected-resource[/mcp]` | Public | OAuth discovery (RFC 8414 / RFC 9728) |
| GET/POST | `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/oauth2/revoke` | OAuth clients | Spring Authorization Server endpoints |
| POST   | `/oauth2/register` | Public (rate-limited) | Dynamic client registration (RFC 7591), public clients only |
| GET/POST | `/auth/connect/login`, `/auth/connect/2fa`, `/oauth2/consent` | Browser | Login + consent pages of the authorization server (Thymeleaf) |
| POST   | `/mcp` | MCP access token | MCP server (Streamable HTTP, stateless, JSON-RPC) |
| GET/DELETE | `/users/me/connected-apps[?clientId=]` | Authenticated | OAuth clients the user authorized; revoke |
| POST   | `/organizations/{orgSlug}/timelines/{id}/scene/ops` | OWNER, MANAGER, USER | Apply scene operations atomically (see `SceneOp`) |
| GET    | `/organizations/{orgSlug}/timelines/{id}/scene/summary` | any member | Compact scene view with validation findings |
| POST   | `/organizations/{orgSlug}/timelines/{id}/scene/validate` | any member | Validation report of the scene |

### Domain endpoints (under `/organizations/{orgId}/...`)

Org membership roles: `OWNER`, `MANAGER`, `USER`, `SPECTATOR` (read-only).

| Method | Path                                          | Access (org role)         | Description                     |
|--------|-----------------------------------------------|---------------------------|---------------------------------|
| POST   | `/locations`                                  | OWNER, MANAGER            | Create location (root or child) |
| GET    | `/locations` / `/locations/tree` / `/{id}`    | any member incl. SPECTATOR| List/tree/detail                |
| PATCH  | `/locations/{id}`                             | OWNER, MANAGER            | Rename / move (no cycles)       |
| DELETE | `/locations/{id}`                             | OWNER, MANAGER            | Soft-delete (must be empty)     |
| POST   | `/piece-types` (+`/{id}/attributes`)          | OWNER, MANAGER            | Create types and attributes     |
| GET    | `/piece-types` / `/{id}`                      | any member                | List / detail                   |
| PATCH  | `/piece-types/{id}` (+`/attributes/{attrId}`) | OWNER, MANAGER            | Rename, toggle required, etc.   |
| DELETE | `/piece-types/{id}` (+`/attributes/{attrId}`) | OWNER, MANAGER            | Soft-delete (no pieces)         |
| POST   | `/pieces`                                     | OWNER, MANAGER, USER      | Create piece                    |
| GET    | `/pieces` (filters) / `/{id}`                 | any member                | Paginated list / detail         |
| PATCH  | `/pieces/{id}`                                | OWNER, MANAGER, USER      | Update fields/values            |
| DELETE | `/pieces/{id}`                                | OWNER, MANAGER, USER      | Soft-delete                     |
| GET    | `/pieces/{id}/history`                        | any member                | Diff-based history              |
| POST   | `/pieces/{id}/attachments`                    | OWNER, MANAGER, USER      | Upload IMAGE, DOCUMENT or AUDIO (`durationMs` optional) |
| GET    | `/pieces/{id}/attachments(?kind=)`            | any member                | List                            |
| GET    | `/pieces/{id}/attachments/{aid}/download`     | any member                | 302 to presigned URL            |
| GET    | `/pieces/{id}/attachments/{aid}/content`      | any member                | Streams the bytes through the backend (same-origin for the editor) |
| DELETE | `/pieces/{id}/attachments/{aid}`              | OWNER, MANAGER, USER      | Soft-delete + best-effort R2 rm |
| GET    | `/pieces/export(?format=csv\|xlsx&filters)`   | any member                | Bulk export (CSV/XLSX), filtered |
| GET    | `/pieces/import/template(?format=)`           | OWNER, MANAGER, USER      | Empty header-only template       |
| POST   | `/pieces/import(?format=&mode=&dryRun=)`      | OWNER, MANAGER, USER      | Bulk import; dry-run preview then all-or-nothing commit |

Ports (`/organizations/{orgId}/ports`, gated to admin-owned orgs) carry a closed `kind` from the
output catalog (`relay`, `led_strip`, `led_segment`, `motor`, `audio`, `video`, `button`) plus a
per-kind `config` validated by `PortConfigValidator`; piece types may declare a `kind`, in which
case their actions are validated against the catalog by `ActionCatalogValidator`. The catalog JSON
is a byte-identical copy of `rpi4-system/contract/catalog.json` (also copied to
`timeline-editor/app/data/catalog.json`): change it there first.

Import/export share the piece column schema (fixed columns + one column per
attribute, named `"<Type> / <DisplayName>"` or `"Org / <DisplayName>"`).
References are by human-readable name: `owner_email`, `location_path`
(`Almacén/Estante`), `piece_types` (`;`-separated). `mode=create` (default) or
`upsert` (match existing by `serialNumber`); `dryRun=true` validates without
writing and returns a per-row report; `dryRun=false` rejects with 422 (writing
nothing) if any row is invalid. Blank cells mean "leave unchanged" — import never
clears fields, and attachments are out of scope. Caps:
`stocka.pieces.import-export.max-{import,export}-rows`.

### Email providers

Three interchangeable providers behind a single `EmailService` interface, selected via
`app.email.provider` (`EMAIL_PROVIDER` env var):

| Provider | When to use | Behavior |
|----------|-------------|----------|
| `local` (default) | Local dev | Renders the email and writes the HTML to `${EMAIL_LOCAL_DIR:target/emails}` |
| `smtp`            | Self-hosted SMTP / MailHog | Sends through `JavaMailSender` |
| `resend`          | Production / staging | Sends through Resend HTTP API with idempotency keys + Spring Retry exponential backoff; webhooks land at `POST /webhooks/resend` |

Templates (Thymeleaf, under `resources/templates/email/`) and i18n bundles
(`messages_{es,ca,en}.properties`) are shared across providers.

Resend variables:
- `EMAIL_PROVIDER=resend`
- `EMAIL_FROM=onboarding@resend.dev` — sandbox: only delivers to your Resend account email.
  Verify a domain at https://resend.com/domains and switch to `Stocka <no-reply@your-domain>`.
- `RESEND_API_KEY=re_...` — required.
- `RESEND_WEBHOOK_SECRET=whsec_...` — **optional**. When empty/unset, the `/webhooks/resend`
  endpoint is not registered (sending still works). Set it to the Svix signing secret from
  the Resend webhook config to enable the endpoint. Format: `whsec_` followed by standard
  base64 (alphabet `[A-Za-z0-9+/]`, no underscores) — copy verbatim from the Resend dashboard.
- `RESEND_MAX_RETRIES` (default `3`) and `RESEND_INITIAL_BACKOFF_MS` (default `500`) tune the
  retry policy applied to HTTP 429 / 5xx / network errors.

### Storage (Cloudflare R2)

Attachments live in Cloudflare R2 via the AWS S3 SDK v2 (`software.amazon.awssdk:s3`). In dev
you can opt-in to the local fallback (`LocalR2StorageService`, active when
`stocka.r2.use-local=true`), which writes files to `${stocka.r2.local-dir:/tmp/stocka-r2/}`
and serves them through `LocalR2DownloadController` at `/dev/r2/**`.

**Production requires** `R2_USE_LOCAL=false` (the default), `R2_BUCKET`, `R2_ENDPOINT`,
`R2_ACCESS_KEY` and `R2_SECRET_KEY`. `R2Properties` fails fast on startup when
`use-local=true` and any active Spring profile contains `prod`, so attachments cannot be
silently written to ephemeral container storage in production (issue #17).

Per-piece attachment limits (configurable via `stocka.pieces.attachment.*`):
- IMAGE: jpg/png/webp/gif, max 25 MB, max 50 per piece.
- DOCUMENT: any MIME (incl. images), max 100 MB, max 50 per piece.
- AUDIO: mp3/wav/ogg/flac/m4a (what VLC plays on the belén RPi), max 50 MB, max 50 per piece;
  carries `durationMs` measured by the client. The timeline editor names the asset
  `<attachmentId>-<filename>` and reads the bytes from `/content`.

### Semantic search (XEYE)

Optional, off by default. With `XEYE_SEARCH_ENABLED=true` and `XEYE_API_KEY` (an XEYE key with the
scopes `lists:write`, `trainings:write` and `search`), module `search/` keeps one public XEYE list
per organization (`stocka-org-<id>`, table `search_lists`) with one element per active piece
(table `search_entries`): `text` = name · serial, `description` = types, free text, location path,
owner and attribute values (`PieceSearchDocument`), `params.pieceId`. Piece lifecycle events
index/remove elements asynchronously (`PieceLifecycleSearchListener`); `SemanticSearchTrainer`
launches one training per list once edits have settled (`XEYE_TRAINING_DELAY`, default 10m).
`GET /pieces?q=` then ranks through XEYE (`PieceSearchRanker` → `SemanticSearchRanker`, hits under
`XEYE_MIN_SCORE` dropped, at most `XEYE_MAX_RESULTS`), applies the structural filters on top and
falls back to `LIKE` when the feature is off, nothing is indexed yet or XEYE fails. On startup
`SemanticSearchBootstrap` indexes every organization that has no list yet (so enabling the key on
an existing installation backfills by itself); a full rebuild (`/search/reindex`) recreates every
element in two batch calls and is the fix when types/locations were renamed (those are not
re-indexed until the piece is edited). XEYE's own docs: https://xeye.es/docs.

### MCP server and OAuth 2.1

Assistants (Claude.ai, Claude Code, Cursor…) connect to `https://<backend>/mcp` with OAuth 2.1 + PKCE; the
backend is also the **authorization server** (Spring Security 7's authorization server, JDBC tables from
`V22__oauth_server.sql`). Three ordered security chains: `@Order(1)` `OAuthServerConfiguration` (`/oauth2/**`,
`/auth/connect/**`, `/.well-known/oauth-authorization-server`; the only chain with sessions, 10 min),
`@Order(2)` `McpSecurityConfiguration` (`/mcp`, protected resource metadata; resource server validating
RS256 tokens with `aud` = `MCP_RESOURCE_URL`), `@Order(3)` the REST chain. Tokens never cross: SPA JWTs are
refused by `/mcp`, MCP tokens by the REST API.

- Client registration: Client ID Metadata Documents (`CimdRegisteredClientRepository`, URL client ids, cached
  documents, `OAUTH_CIMD_TRUSTED_HOSTS` marks "verified" apps), anonymous DCR (`DynamicClientRegistrationController`,
  `OAUTH_DCR_ENABLED`), or rows inserted by hand in `oauth2_registered_client`. All public clients; loopback redirects
  (`localhost`, `127.0.0.1`) match on any port (`OAuthRedirectUriValidator`).
- Scopes (`OAuthScope`): `read`, `inventory:write`, `timelines:write`, `hardware:write`, `org:manage`, `delete`,
  `offline_access`; writes imply `read`; `delete` and `org:manage` are unticked by default on the consent page; the
  organization role still applies on top. Refresh tokens only with `offline_access`, rotated, capped by
  `OAUTH_REFRESH_ABSOLUTE_TTL` (`OAuthRefreshTokenGenerator`); public clients refresh with `client_id` only
  (`PublicClientRefreshAuthentication`).
- Access tokens: RS256 JWT (`OAUTH_JWK_PRIVATE_KEY`, PKCS#8 RSA PEM; ephemeral key outside `prod`), 30 min,
  claims `sub` = user id, `aud`, `email`, `tokenVersion`, `client_name` (`OAuthTokenCustomizer`). `/mcp` accepts a
  token only while its `oauth2_authorization` row exists (`McpAuthenticationConverter`), so revoking from
  "connected apps" (`ConnectedAppService`) is immediate.
- Tools: one `ToolSource` bean per domain in `modules/mcp/tool` (`ToolDefinition` = name, description, input
  record annotated with `@Param`, scope, hints, handler). `McpToolRegistry` is the only class touching the MCP SDK:
  it checks the scope, rate-limits per user, converts arguments, restores the `SecurityContext` on the SDK worker
  thread (services read the current user from it), serializes `structuredContent` + JSON text, and maps every
  exception to `{error, message, hint, params}` (`ToolErrorMapper`). JSON Schemas come from `ToolSchemas`
  (records → properties; `SceneOp` → `oneOf`). Resources (`stocka://guide`, `stocka://catalog`,
  `stocka://schema/scene`, `stocka://org/{slug}/overview`) and prompts live in `resource/` and `prompt/`.
- Timelines are edited through `SceneOp` batches (`SceneEditor`, `SceneValidator`, `SceneSummary`,
  `SceneJsonCodec` keeps unknown editor fields) with optimistic `version`; the same engine backs
  `POST …/scene/ops` for the editor. Adding a tool = one `ToolDefinition` in the domain's `ToolSource` + a test.
- Config: `stocka.oauth.*` (`OAUTH_*`), `stocka.mcp.*` (`MCP_*`), `spring.ai.mcp.server.*` (protocol `STATELESS`,
  annotation scanner off). Tests enable everything through `src/test/resources/application.properties` and
  create the JDBC tables with `oauth2-schema-h2.sql`. End-to-end: `OAuthFlowIntegrationTest`,
  `McpToolsIntegrationTest` (helper `OAuthTestClient`). User guide: `docs/mcp.md`; design: `docs/plan/mcp-oauth.md`.

## API Testing

Bruno collections are in `bruno/`. See `bruno/README.md` for the recommended test sequence.

## Raspberry Pi del belén

Claude Code tiene acceso SSH directo a la Raspberry Pi que ejecuta el player (`ssh rpi4b '<comando>'`).
Todo lo relativo a la conexión, el despliegue y el estado del arranque está en `../rpi4-system/CLAUDE.md`.

---

## Core Purpose

The main objective of this backend is to allow users to manage **organizations** and their associated **assets ("pieces")** in a structured and flexible way.

Each user belongs to one or more organizations and can manage the pieces within those organizations depending on their permissions.

---

## Key Features

### 1. Authentication & User Management

* User registration and login
* Authentication (JWT-based or session-based)
* Role-based access control (RBAC)
* Association of users with organizations

### 2. Organization Management

* Create, update, and delete organizations
* Assign users to organizations
* Manage organization-level permissions

### 3. Piece Management (Core Domain)

Each **piece** represents an asset belonging to an organization.

A piece includes:

* Basic attributes (name, description, etc.)
* Custom attributes based on its type
* Associated images and files
* Ownership information
* Location within a hierarchical structure

### 4. Piece Types

* Define reusable **piece types**
* Each type can have its own schema of attributes
* Pieces inherit structure from their type

### 5. File & Image Storage

* All media (images and files) are stored in **Cloudflare R2**
* Backend handles upload, retrieval, and deletion
* References to files are stored in the database

### 6. Ownership

* Each piece can have one or more **owners**
* Ownership can be used for filtering, permissions, or tracking

### 7. Location System (Hierarchical)

* Locations are structured as a **tree**
* A location can contain:

  * Sub-locations
  * Pieces
* Enables flexible organization (e.g., warehouse → shelf → box)

---

## Storage

* Cloudflare R2 for object storage (images/files)

---

## Design Principles

* **Modularity**: Clear separation between domains (users, organizations, pieces, locations, etc.)
* **Scalability**: Designed to support growing data and users
* **Extensibility**: Piece types and attributes allow flexible data modeling
* **Security**: Authentication and authorization enforced across all endpoints
* **Maintainability**: Clean architecture and consistent coding practices

---

## Future Considerations

* Audit logs for tracking changes
* Advanced permissions system
* Search and filtering capabilities
* Pagination and performance optimizations
* Caching layer (e.g., Redis)
* Event-driven features (webhooks, notifications)

---

## Open Questions

(To be defined as the project evolves)

* What authentication strategy will be used (JWT, OAuth, etc.)?
* Will users belong to multiple organizations simultaneously?
* What level of granularity is required for permissions?
* How complex should piece-type attribute schemas be?
* Should location trees support reordering and drag-and-drop semantics?

---

## Summary

This backend serves as the central system for managing organizational assets in a structured, extensible way. It integrates relational data (MariaDB), object storage (Cloudflare R2), and a modern Java backend (Spring Boot), all packaged in a Dockerized environment for easy deployment.

---

## Skills a aplicar siempre

En cualquier tarea que toque código de este repo, lee y aplica las siguientes skills. Su contenido se carga automáticamente abajo gracias a las `@`-references:

- **Siempre** (cualquier cambio de código Java):
  - @.claude/skills/java-coding-standards/SKILL.md
  - @.claude/skills/java-springboot/SKILL.md
- **Cuando escribas o modifiques tests** (`src/test/...`):
  - @.claude/skills/java-junit/SKILL.md
- **Cuando añadas o modifiques tipos públicos** (clases, interfaces, métodos `public`):
  - @.claude/skills/java-docs/SKILL.md
- **Solo si tocas integración con Cloudflare R2** (almacenamiento de imágenes/ficheros):
  - @.claude/skills/cloudflare/SKILL.md
