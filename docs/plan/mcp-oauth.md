# Plan · Servidor MCP de Stocka con OAuth 2.1

> Estado: propuesta (2026-10-18). No hay código ni despliegue asociados a este documento.
> Repos afectados: `backend` (casi todo), `frontend` (dos tarjetas), `xeye-infra` (variables de entorno).

## 0. Resumen en diez líneas

- Stocka expondrá un **servidor MCP remoto** (Streamable HTTP, sin estado) en `https://stocka.xeye.es/mcp`, dentro del propio backend Spring Boot.
- Cualquier cliente MCP (Claude.ai, Claude Code, Claude Desktop, Cursor, ChatGPT, Inspector) se conecta con **OAuth 2.1 + PKCE**. El usuario inicia sesión en Stocka, elige qué permisos concede y el asistente actúa **con su identidad y sus roles de organización**.
- El backend hace también de **Authorization Server** (Spring Security 7 ya lo incluye). No hay servicio nuevo ni proveedor externo. Un token, una audiencia, un despliegue.
- Las herramientas MCP son **adaptadores finos** sobre los servicios actuales: la autorización (`@orgSecurity`), la validación, los códigos de error y la auditoría se reutilizan tal cual.
- Catálogo de **~40 herramientas** con nombres predecibles (`piece_get`, `timeline_scene_edit`), salidas compactas, errores con código y pista de corrección, más recursos de referencia (catálogo, esquema de escena, guía) y prompts guiados.
- Las timelines se editan mediante **operaciones** (`add_item`, `add_clip`, `move_clip`…) que el backend aplica, valida y guarda con concurrencia optimista; el modelo nunca reescribe el JSON entero a ciegas.
- Alcances (scopes) por dominio y por riesgo: `read`, `inventory:write`, `timelines:write`, `hardware:write`, `org:manage`, `delete`, `offline_access`. Borrar y administrar la organización se conceden solo si el usuario los marca en la pantalla de consentimiento.
- El usuario ve y revoca sus **aplicaciones conectadas** desde «Mi cuenta». Revocar invalida el refresh token al instante y el access token en segundos.
- Se añaden dos módulos (`oauth`, `mcp`), una migración (V22), tres cadenas de seguridad ordenadas y páginas de login/consentimiento servidas por el backend con el brandbook.
- Seis fases; la fase 0 es un spike de dos días que fija versiones y resuelve las tres incógnitas técnicas antes de escribir nada definitivo.

---

## 1. Decisión de arquitectura

### 1.1 Opciones consideradas

| | A · MCP dentro del backend (Java) | B · Servicio MCP aparte (TypeScript, VPS o Cloudflare Worker) |
|---|---|---|
| Authorization Server | Backend (Spring Authorization Server) | Backend igualmente (hace falta de todas formas) |
| Token hacia la API | El mismo token: audiencia = `…/mcp`, las tools llaman a los servicios en proceso | El token del MCP **no puede reenviarse** a la API (la especificación lo prohíbe): hace falta *token exchange* (RFC 8693) o una segunda capa de tokens |
| Autorización por rol | `@PreAuthorize` + `@orgSecurity` reutilizados sin cambios | Reimplementar o confiar ciegamente en la API |
| Validación y errores | `ApiException`, `ErrorCodes`, `messages_*` reutilizados | Mapear errores HTTP a mano |
| Escena de timeline | Hay que portar a Java la validación de clips y el montaje del show (hoy en TS) | Podría importar los utils del editor si se extrae un paquete compartido |
| Tests | JUnit, `@SpringBootTest` + MockMvc contra `/mcp` | Vitest + mocks de la API |
| Despliegue | Ninguno nuevo (imagen actual, `deploy.sh`) | Contenedor o Worker nuevo, variables, dominio |
| Riesgo | Dependencia de Spring AI 2.0.x en el backend | Dos procesos, dos modelos de seguridad, más superficie |

**Decisión: A.** El backend es el sistema de registro y ya contiene todas las reglas; añadir un segundo proceso solo para traducir JSON-RPC a HTTP duplica lógica y abre el problema del *passthrough* de tokens. Lo único que B ganaría (reutilizar `showPackage.ts`) se compensa moviendo el motor de escena al backend (fase 4-5), que además cierra un hueco actual: hoy el backend acepta cualquier JSON como escena.

Si en el futuro se quisiera un MCP en Cloudflare, el Authorization Server de este plan sigue sirviendo: el Worker pediría un token con `audience` propia y haría *token exchange* contra `/oauth2/token`.

### 1.2 Piezas y versiones

| Pieza | Elección | Por qué |
|---|---|---|
| Authorization Server | `spring-boot-starter-oauth2-authorization-server` (gestionado por Boot 4.0.6; Spring Security **7.0.5**, que corrige CVE-2026-59354 del registro dinámico) | Ya está en el BOM; mismo proceso; JDBC para clientes, autorizaciones y consentimientos |
| Servidor MCP | `spring-ai-starter-mcp-server-webmvc` (Spring AI **2.0.1**, MCP Java SDK 2.0.x, Jackson 3 por defecto, spec 2025-11-25) con `spring.ai.mcp.server.protocol=STATELESS` | Streamable HTTP sin sesión: escala horizontalmente como la API; Jackson 3 coincide con el backend |
| Plomería OAuth específica de MCP | `org.springaicommunity:mcp-server-security-spring-boot` y `mcp-authorization-server-spring-boot` **0.1.14** (solo compatibles con Spring AI 2.0.x) | Aportan `McpServerOAuth2Configurer` (401 con `WWW-Authenticate: Bearer resource_metadata=…`, metadatos RFC 9728, validación de audiencia), `ClientIdMetadataDocumentRegisteredClientRepository` (CIMD), registro dinámico anónimo y `LocalhostWildcardPortValidator` (Claude Code usa `http://localhost:<puerto aleatorio>/callback`) |
| Login y consentimiento | Thymeleaf (ya es dependencia por los emails) + CSS propio del brandbook, ca/es/en con `messages_*.properties` | El backend necesita una sesión de navegador solo durante la autorización; no hay que tocar el worker de Nuxt |

La librería comunitaria es 0.1.x: se fija la versión, se envuelve tras interfaces propias (sección 3.6) y el spike de la fase 0 comprueba que todo arranca en Boot 4.0.6. Si no encaja, las cuatro piezas que aporta son pequeñas y se escriben a mano (repositorio de clientes CIMD, validador de redirect, endpoint `/oauth2/register`, controlador de metadatos + entry point).

### 1.3 Flujo completo

```
Cliente MCP (Claude)            Backend Stocka (stocka.xeye.es)                       Navegador del usuario
      |                                   |                                                   |
      |-- POST /mcp (sin token) --------->|                                                   |
      |<- 401 WWW-Authenticate: Bearer    |                                                   |
      |     resource_metadata=".../.well-known/oauth-protected-resource/mcp", scope="read …"  |
      |-- GET /.well-known/oauth-protected-resource/mcp ->| {resource, authorization_servers:[issuer], scopes_supported}
      |-- GET /.well-known/oauth-authorization-server -->| {authorization_endpoint, token_endpoint, registration_endpoint,
      |                                   |               client_id_metadata_document_supported:true, code_challenge_methods_supported:[S256], …}
      |   (client_id = URL CIMD de Claude, o POST /oauth2/register si el cliente no soporta CIMD)
      |-- abre navegador: /oauth2/authorize?client_id&redirect_uri&scope&resource&code_challenge&state -------------->|
      |                                   |<- GET /oauth2/authorize ----------------------------------------------------|
      |                                   |-> 302 /auth/connect/login (o sesión Stocka ya reconocida, §2.5)            |
      |                                   |<- email + contraseña (+ 2FA) ------------------------------------------------|
      |                                   |-> pantalla de consentimiento: app, host de redirección, scopes marcables     |
      |                                   |<- POST /oauth2/consent ------------------------------------------------------|
      |<------------------------------ 302 redirect_uri?code&state&iss ---------------------------------------------------|
      |-- POST /oauth2/token (code + code_verifier + resource) -->|
      |<- {access_token (JWT, aud=https://stocka.xeye.es/mcp), refresh_token, scope} |
      |-- POST /mcp initialize / tools/list / tools/call  (Authorization: Bearer …) -->|
      |<- JSON-RPC                        |  tools → servicios → @orgSecurity → DB
```

---

## 2. Módulo `oauth` (Authorization Server)

Paquete `com.stocka.backend.modules.oauth`. Cadena de seguridad `@Order(1)` con `securityMatcher` sobre `/oauth2/**`, `/.well-known/oauth-authorization-server`, `/auth/connect/**`, `/oauth2/consent`. Sesión `IF_REQUIRED` solo en esta cadena (cookie `STOCKA_AS_SESSION`, 10 min, `HttpOnly`, `Secure`, `SameSite=Lax`), CSRF activado en los formularios. La cadena actual pasa a `@Order(3)` sin cambios.

### 2.1 Decisiones de protocolo

| Tema | Decisión |
|---|---|
| Issuer | `https://stocka.xeye.es` en prod (`OAUTH_ISSUER`), `http://localhost:9095` en dev |
| Recurso protegido (RFC 8707 / 9728) | URI canónica `https://stocka.xeye.es/mcp`; metadatos en `/.well-known/oauth-protected-resource/mcp` y copia en la raíz `/.well-known/oauth-protected-resource` (Claude prueba ambas) |
| Grants | `authorization_code` + PKCE S256 obligatorio, `refresh_token` con rotación (`reuseRefreshTokens(false)`). **No** `client_credentials`, implicit ni password |
| Clientes | Públicos (`token_endpoint_auth_method=none`). Tres vías de registro, por prioridad: **CIMD** (`client_id` = URL https del documento; Claude.ai y Claude Code publican el suyo), **pre-registro** (fila creada por un admin en `oauth2_registered_client`) y **registro dinámico** RFC 7591 anónimo (deprecado en la spec 2026-07-28 pero aún necesario para otros hosts) |
| Redirect URIs | Coincidencia exacta para `https://…`; para loopback (`127.0.0.1`, `localhost`, `[::1]`) se ignora el puerto (`LocalhostWildcardPortValidator`). Nunca `http://` no loopback. Claude.ai registra `https://claude.ai/api/mcp/auth_callback` |
| Access token | JWT firmado con clave asimétrica (EC P-256, `kid` fijo), **30 min**. Claims: `iss`, `sub` = id de usuario, `aud` = URI del recurso, `scope`, `client_id`, `email`, `tokenVersion` (el mismo mecanismo de logout global que los JWT actuales), `jti` |
| Refresh token | Opaco, 30 días deslizantes, máximo absoluto 90 días; solo si se pidió `offline_access` (Claude lo añade cuando aparece en `scopes_supported`) |
| Metadatos del AS | Añadir vía `authorizationServerMetadataCustomizer`: `client_id_metadata_document_supported: true`, `scopes_supported` (lista completa), `code_challenge_methods_supported: ["S256"]`, `authorization_response_iss_parameter_supported: true` (RFC 9207, con `iss` en la redirección) |
| Consentimiento parcial | El usuario puede desmarcar scopes; el token lleva solo los aprobados; `oauth2_authorization_consent` recuerda la decisión por (cliente, usuario) y la pantalla se salta si ya hay consentimiento con esos scopes |
| Latencia | Claude corta discovery, registro y token a 10 s. La obtención del CIMD se hace con timeout de 3 s y caché de 1 h; todo lo demás es local |
| Revocación | `DELETE /users/me/connected-apps/{id}` borra autorizaciones y consentimientos de ese cliente para ese usuario y apunta los `jti` de los access tokens vivos en `oauth2_revoked_tokens` (TTL = expiración). El resource server consulta esa tabla con caché en memoria de 30 s |

### 2.2 Scopes

| Scope | Concede | Marcado por defecto en el consentimiento |
|---|---|---|
| `read` | Toda lectura sobre las organizaciones del usuario (artículos, tipos, ubicaciones, contactos, timelines, puertos, estado de búsqueda, miembros) | sí |
| `inventory:write` | Crear y editar artículos, tipos y atributos, ubicaciones, contactos, adjuntos; importación | sí |
| `timelines:write` | Crear y editar timelines y sus escenas | sí |
| `hardware:write` | Puertos y acciones de tipo (función privada: además exige `@orgSecurity.canManagePorts/…Actions`) | sí |
| `org:manage` | Ajustes de organización, miembros, invitaciones, reindexar búsqueda | **no** |
| `delete` | Cualquier borrado lógico (artículos, tipos, ubicaciones, contactos, timelines, puertos, adjuntos) | **no** |
| `offline_access` | Refresh token | sí (no se muestra como permiso, se explica como «mantener la conexión») |

Reglas: `*:write` implica `read` para su dominio; el rol de organización sigue mandando (un SPECTATOR con `inventory:write` sigue sin poder escribir). `scopes_supported` del recurso protegido lista todos salvo `offline_access` (va en los del AS), de modo que Claude los pide todos y el usuario decide en la pantalla. Una tool llamada sin su scope responde con el error MCP `auth.insufficient_scope` y, a nivel HTTP solo cuando la petición entera carece de scope, `403` + `WWW-Authenticate: Bearer error="insufficient_scope", scope="…"` para que los clientes que implementan *step-up* vuelvan a pedir permisos.

### 2.3 Ficheros

| Fichero (bajo `modules/oauth/`) | Contenido |
|---|---|
| `OAuthServerProperties` | `stocka.oauth.*`: `enabled`, `issuer`, `jwk.private-key` (PEM), `jwk.kid`, `jwk.previous-private-key` (rotación), `access-token-ttl` (30m), `refresh-token-ttl` (30d), `refresh-absolute-ttl` (90d), `session-ttl` (10m), `dcr.enabled` (true), `dcr.max-clients-per-ip-per-day` (20), `cimd.trusted-hosts` (`claude.ai`), `cimd.cache-ttl` (1h), `cimd.timeout` (3s) |
| `OAuthServerConfiguration` | Cadena `@Order(1)`; `AuthorizationServerSettings` (issuer, rutas por defecto `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/oauth2/revoke`, `/oauth2/register`); `JWKSource` desde `OAuthKeyLoader`; `JdbcRegisteredClientRepository` envuelto por `CimdRegisteredClientRepository`; `JdbcOAuth2AuthorizationService`; `JdbcOAuth2AuthorizationConsentService`; `OAuthTokenCustomizer`; validador de redirect; `authenticationEntryPoint` → `/auth/connect/login`; `consentPage("/oauth2/consent")` |
| `OAuthKeyLoader` | Lee el PEM de la propiedad, construye `ECKey` con `kid`; falla rápido si falta en perfil `prod`; incluye la clave anterior en el JWKS mientras dure la rotación |
| `OAuthScope` (enum) | Nombre, clave i18n de descripción, dominio, `implies()`; `OAuthScope.parse(String)` |
| `OAuthTokenCustomizer` | `OAuth2TokenCustomizer<JwtEncodingContext>`: `aud` (URI canónica; si la petición trajo `resource`, debe coincidir o se rechaza con `invalid_target`), `email`, `tokenVersion`, `client_name`; recorta `scope` a los aprobados |
| `client/ClientMetadataDocument` (record), `client/ClientMetadataDocumentClient` (`RestClient`, timeout, tamaño máximo 64 KB, solo `https`, sin redirecciones), `client/CimdRegisteredClientRepository` | Resuelve `client_id` con forma de URL: descarga, valida (`client_id` idéntico a la URL, `redirect_uris` presentes, `grant_types ⊆ {authorization_code, refresh_token}`, `token_endpoint_auth_method = none`), cachea y sintetiza un `RegisteredClient` público con PKCE. Marca `trusted` si el host está en `cimd.trusted-hosts` |
| `dcr/DynamicClientRegistrationController` (`POST /oauth2/register`, anónimo) | RFC 7591: valida `redirect_uris` (https o loopback), `application_type`, `grant_types`, `client_name` (≤ 80), responde `client_id` aleatorio sin secreto; política de rate limit `oauth.register.ip`; persiste con `registered_at`; `dcr/RegisteredClientCleanupJob` borra clientes dinámicos sin autorizaciones desde hace 30 días (cron como `RefreshTokenCleanupJob`) |
| `web/ConnectLoginController` (`GET/POST /auth/connect/login`, `GET/POST /auth/connect/2fa`) | Formulario email + contraseña con `AuthenticationManager` existente, rate limit `auth.login.ip` reutilizado, 2FA con `TwoFactorService` (TOTP o código de recuperación), auditoría `LOGIN_SUCCESS/FAILED` con `channel=oauth`; al terminar deja la `Authentication` (principal `User`) en la sesión y continúa al `/oauth2/authorize` guardado |
| `web/ConsentController` (`GET/POST /oauth2/consent`) | Muestra `client_name`, `client_uri`, logo si lo hay, **host del `redirect_uri`** (obligatorio por la spec; aviso extra si solo hay loopback), lista de scopes con checkbox y descripción, organizaciones del usuario con su rol (informativo en v1); envía `POST /oauth2/authorize` con los scopes aprobados |
| `templates/oauth/{layout,login,twofactor,consent,error}.html`, `static/oauth/stocka.css` | Brandbook (tokens de color, tipografía, botones) en CSS plano; sin JS salvo el autofocus; textos vía `MessageSource` (`oauth.ui.*`) |
| `service/ConnectedAppService`, `entity/OAuthRevokedToken` + repositorio, `controller/ConnectedAppsController` (`GET /users/me/connected-apps`, `DELETE /users/me/connected-apps/{clientId}`), `dto/ConnectedAppDto` (`clientId`, `name`, `trusted`, `scopes`, `grantedAt`, `lastUsedAt`, `expiresAt`) | Lista agrupando `oauth2_authorization` por cliente; revoca; registra `OAUTH_APP_AUTHORIZED` y `OAUTH_APP_REVOKED` en `SecurityEventType` (aparecen en `/users/me/security/activity`) |
| `OAuthAuthorizationCleanupJob` | Borra autorizaciones expiradas y `oauth2_revoked_tokens` caducados (cron `OAUTH_CLEANUP_CRON`, 03:45) |
| `db/migration/V22__oauth_server.sql` | Esquema oficial de Spring Authorization Server adaptado a MariaDB (`oauth2_registered_client`, `oauth2_authorization` con columnas `*_value` en `TEXT/BLOB`, `oauth2_authorization_consent`) + `oauth2_revoked_tokens(jti PK, expires_at, índice)` + columna `registered_via ENUM('DCR','CIMD','MANUAL')` y `last_used_at` en `oauth2_registered_client`. Re-ejecutable (`IF NOT EXISTS`) como V19/V21 |
| `ErrorCodes` + `messages_{es,ca,en}` | `oauth.client_invalid`, `oauth.redirect_uri_invalid`, `oauth.cimd_unreachable`, `oauth.cimd_invalid`, `oauth.registration_disabled`, `oauth.consent_required`, `auth.insufficient_scope`; textos de UI `oauth.ui.*` |
| `application.properties` / `.env.example` | `OAUTH_ISSUER`, `OAUTH_JWK_PRIVATE_KEY`, `OAUTH_JWK_KID`, `OAUTH_JWK_PREVIOUS_PRIVATE_KEY`, `OAUTH_ACCESS_TTL`, `OAUTH_REFRESH_TTL`, `OAUTH_DCR_ENABLED`, `OAUTH_CIMD_TRUSTED_HOSTS`, `OAUTH_CLEANUP_CRON` |

### 2.4 Cómo encaja con la seguridad actual

- Los JWT HS256 de la SPA y los JWT del AS **no se mezclan**: la cadena `@Order(2)` de `/mcp` solo acepta tokens firmados por el AS; la cadena REST solo acepta los de `JwtService`. Un token MCP enviado a `/organizations/...` recibe 401 y viceversa.
- `tokenVersion` se incluye en los tokens del AS para que el logout global (`JWT_TOKEN_VERSION`) también los invalide.
- Cambiar la contraseña invalida los access tokens MCP emitidos antes (misma comprobación `passwordChangedAt` que `JwtAuthenticationFilter`) y revoca todos los refresh tokens del usuario en el AS (gancho en `revokeAllSessions`).
- El login del AS pasa por el mismo `AuthenticationProvider`, el mismo rate limit y la misma 2FA. Google login en la pantalla del AS queda para la fase 6 (requiere un callback servidor a servidor; hoy el flujo es de la SPA).
- Cabeceras: la configuración global (`frame-ancestors 'none'`, `form-action 'self'`) sirve para login y consentimiento; la redirección final al cliente es un 302, no un `form-action`.

### 2.5 Atajo de sesión (fase 6, opcional)

La cookie de refresh de la SPA se emite en el dominio del backend con `path=/auth` y `SameSite=Lax`, así que una navegación de primer nivel desde claude.ai a `https://stocka.xeye.es/auth/connect/login` **la lleva**. El controlador puede validar el refresh token sin rotarlo y ofrecer «Continuar como Joan» sin volver a escribir la contraseña (el consentimiento se muestra siempre). Por eso el login del AS vive bajo `/auth/connect/`, no bajo `/oauth2/`. Hasta que se implemente, el formulario de contraseña es el camino.

---

## 3. Módulo `mcp` (resource server + servidor MCP)

Paquete `com.stocka.backend.modules.mcp`. Cadena de seguridad `@Order(2)` con `securityMatcher("/mcp", "/.well-known/oauth-protected-resource/**")`.

### 3.1 Resource server

| Fichero | Contenido |
|---|---|
| `McpProperties` | `stocka.mcp.*`: `enabled` (false por defecto: sin él no se registran ni la cadena ni las tools), `resource-url`, `cors-allowed-origins` (vacío; solo clientes MCP de navegador lo necesitan), `max-page-size` (50), `default-page-size` (20), `max-ops-per-edit` (100), `tool-timeout` (20s) |
| `McpSecurityConfiguration` | `McpServerOAuth2Configurer` (o, en el fallback, `oauth2ResourceServer().jwt()` con decoder construido sobre el `JWKSource` local, sin HTTP): validadores de `iss`, `aud`, `exp`, `tokenVersion`, `jti` no revocado, usuario existente y no bloqueado; entry point que devuelve **401** (nunca 200 con cabecera) con `WWW-Authenticate: Bearer resource_metadata="…/mcp", scope="read inventory:write timelines:write"`; `ProtectedResourceMetadataController` para las dos rutas `.well-known` con `resource`, `authorization_servers`, `scopes_supported`, `bearer_methods_supported:["header"]`, `resource_documentation` |
| `McpAuthenticationConverter` | `Jwt` → `McpAuthentication` (extiende `AbstractAuthenticationToken`): principal = entidad `User` cargada por `sub`, authorities = rol global + `SCOPE_<scope>`; así `@orgSecurity.*(…, principal)` funciona sin tocar nada |
| `McpRequestContext` | Si el spike muestra que las tools no corren en el hilo del request, un `McpTransportContextExtractor` copia la `Authentication` al `McpTransportContext` y un `McpAuthenticationRestorer` la repone en `SecurityContextHolder` antes de invocar la tool |
| Rate limit | Políticas `mcp.calls.user` (120/min, ráfaga 30) y `mcp.unauthenticated.ip` en `RateLimitConfig`; el filtro existente ya mide por IP y usuario |

### 3.2 Servidor MCP

Propiedades: `spring.ai.mcp.server.protocol=STATELESS`, `name=stocka`, `version=<versión del jar>`, `type=SYNC`, `stateless.mcp-endpoint=/mcp`, `capabilities.{tool,resource,prompt}=true`, `completion=false`, `instructions` cargadas desde `resources/mcp/instructions.md`. Sin estado implica que no hay *elicitation* ni *sampling*: las confirmaciones de acciones peligrosas las hace el host a partir de las anotaciones de cada tool, y el scope `delete` acota el daño.

Modelo de programación: beans Spring con `@McpTool`, `@McpToolParam`, `@McpResource`, `@McpPrompt` (anotaciones MCP integradas en Spring AI, escaneo automático). Cada clase de dominio es un bean; cada método es una tool; los parámetros son records con Javadoc que se vuelca a `description` del JSON Schema. Si el spike descarta las anotaciones, el mismo código se registra como `McpStatelessServerFeatures.SyncToolSpecification` a través de `McpToolRegistry` (una clase, el único punto que conoce el SDK).

Convenciones para que el modelo acierte a la primera:

- Nombres `snake_case`, `<dominio>_<verbo>`; mismo verbo en todos los dominios (`_list`, `_get`, `_create`, `_update`, `_delete`). Las tools de organización reciben `org` (slug) como primer parámetro; `me` devuelve los slugs.
- Descripciones en inglés, una frase de propósito + cuándo usarla + qué devuelve. Las `instructions` del servidor dicen que los usuarios hablan catalán o castellano y que los datos deben mostrarse tal cual.
- Salidas: `structuredContent` con `outputSchema` declarado y un resumen de texto de una línea. Listas paginadas `{items, page, size, total, totalPages}`; tamaño máximo 50. Fechas ISO. Sin campos internos (`deletedAt`, hashes).
- Anotaciones MCP en cada tool: `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint=false`.
- Errores (`isError=true`) con cuerpo `{code, message, hint, params}`: `code` es el de `ErrorCodes`, `message` el texto en inglés del bundle, `hint` una frase accionable («ask the user to reconnect granting the delete permission», «call timeline_scene_get to refresh the version and retry»). `ToolErrorMapper` cubre `ApiException`, `ResponseStatusException` (`legacy.<status>`), validación por campos, `AccessDeniedException` (distingue scope insuficiente de rol insuficiente), `XeyeException`, y todo lo demás como `server.internal_error` sin traza.
- Idempotencia: `piece_create` con `serialNumber` repetido devuelve 409 con el id existente en `params`; toda edición de escena exige `expectedVersion`.
- Observabilidad: una línea `mcp_tool name=… user=… org=… ms=… ok=… code=…` por llamada (sin payloads), breadcrumb Sentry, contador por tool.

### 3.3 Catálogo de tools (v1)

Scope mínimo entre paréntesis. «R» = `readOnlyHint`, «D» = `destructiveHint`, «I» = `idempotentHint`.

**Identidad y organizaciones**

| Tool | Hace | Scope | Hints |
|---|---|---|---|
| `me` | Usuario actual y sus organizaciones con rol y capacidades (`portsEnabled`, `pieceTypeActionsEnabled`, búsqueda semántica activa) | `read` | R |
| `org_get` | Organización: nombre, slug, rol, uso/cuotas (`/usage`), estado del índice de búsqueda | `read` | R |
| `org_members_list` | Miembros con rol | `read` | R |
| `org_invite` | Invitar por email con rol | `org:manage` | |
| `org_member_set_role` | Cambiar rol | `org:manage` | I |
| `org_member_remove` | Expulsar miembro | `org:manage` | D |
| `search_reindex` | Reconstruir el índice XEYE de la organización | `org:manage` | I |

**Ubicaciones**

| Tool | Hace | Scope | Hints |
|---|---|---|---|
| `locations_tree` | Árbol completo compacto (`id`, `name`, `parentId`, `pieceCount`) | `read` | R |
| `location_create` | Crear raíz o hija | `inventory:write` | |
| `location_update` | Renombrar o mover (`parentId`, `null` = raíz) | `inventory:write` | I |
| `location_delete` | Borrar (debe estar vacía) | `delete` | D |

**Tipos de artículo y atributos**

| Tool | Hace | Scope | Hints |
|---|---|---|---|
| `piece_types_list` | Tipos con `kind` y atributos (`name`, `displayName`, `type`, `required`, resumen de validadores: opciones, min/max) | `read` | R |
| `piece_type_get` | Un tipo con atributos completos y, si la organización lo permite, sus acciones | `read` | R |
| `piece_type_create` | Nombre, `kind` opcional, atributos iniciales | `inventory:write` | |
| `piece_type_update` | Renombrar, cambiar `kind` | `inventory:write` | I |
| `piece_type_attribute_add` / `_update` / `_remove` | Atributos del tipo (`_remove` → `delete`) | `inventory:write` / `delete` | |
| `piece_type_delete` | Borrar tipo sin artículos | `delete` | D |
| `org_attributes_list` / `org_attribute_create` / `_update` / `_delete` | Atributos a nivel de organización | `read` / `inventory:write` / `delete` | |

**Artículos**

| Tool | Hace | Scope | Hints |
|---|---|---|---|
| `pieces_search` | Búsqueda con `q` (semántica vía XEYE cuando está activa, `LIKE` si no), `typeIds`, `locationId`, `ownerUserId` / `ownerContactId`, `status`, filtros de atributos, página; devuelve ítems compactos y `semantic: true/false` | `read` | R |
| `piece_get` | Detalle: descripción, tipos, valores de atributos con `displayName`, propietario, ruta de ubicación, adjuntos (`id`, `kind`, nombre, tamaño, `durationMs`) | `read` | R |
| `piece_history` | Historial paginado (diffs) | `read` | R |
| `piece_create` | Nombre, serie, descripción, `pieceTypeIds`, propietario, ubicación, `attributeValues` por **nombre o id** de atributo | `inventory:write` | |
| `piece_update` | Parcial; `clearOwner`, `clearLocation`, `coverAttachmentId`; valores por nombre o id | `inventory:write` | I |
| `piece_delete` | Borrado lógico | `delete` | D |
| `piece_attachment_add_from_url` | Descarga una URL https (límites de tamaño y MIME de `stocka.pieces.attachment.*`) y la adjunta como IMAGE, DOCUMENT o AUDIO (`durationMs` opcional) | `inventory:write` | |
| `piece_attachment_link` | URL presignada de descarga (caduca) para que el host muestre la imagen o el documento | `read` | R |
| `piece_attachment_delete` | Borra un adjunto | `delete` | D |
| `pieces_export` | CSV filtrado (mismo esquema de columnas que la exportación), tope `max-export-rows` | `read` | R |
| `pieces_import` | CSV en texto, `mode=create|upsert`, `dryRun` por defecto **true**; devuelve el informe por fila | `inventory:write` | |

**Contactos**

| Tool | Hace | Scope |
|---|---|---|
| `contacts_list` / `contact_create` / `contact_update` / `contact_delete` | Propietarios que no son usuarios; `contact_link` / `contact_unlink` a usuario | `read` / `inventory:write` / `delete` |

**Hardware (función privada; además exige rol OWNER/MANAGER en organización de un admin)**

| Tool | Hace | Scope |
|---|---|---|
| `catalog_get` | Catálogo de salidas (kinds, acciones, parámetros, placa) | `read` |
| `ports_list` / `port_create` / `port_update` / `port_delete` | Puertos con `kind`, `pin`, `config` validados por `PortConfigValidator` | `read` / `hardware:write` / `delete` |
| `piece_type_actions_list` / `piece_type_action_create` / `_update` / `_delete` | Acciones de un tipo validadas por `ActionCatalogValidator` | `read` / `hardware:write` / `delete` |

**Timelines** (detalle del motor en §4)

| Tool | Hace | Scope | Hints |
|---|---|---|---|
| `timelines_list` | Timelines con `durationMs`, nº de clips, `updatedAt`, `version` | `read` | R |
| `timeline_get` | Metadatos + **resumen de escena** (ítems con pieza y puertos, pistas, clips ordenados, problemas) | `read` | R |
| `timeline_scene_get` | Escena en `format=compact` (por defecto) o `raw` (JSON íntegro) | `read` | R |
| `timeline_create` | Nombre, `durationMs`, tablero en cm; crea la escena vacía y devuelve `version` | `timelines:write` | |
| `timeline_update` | Renombrar | `timelines:write` | I |
| `timeline_scene_edit` | Lista de operaciones + `expectedVersion`; aplica, valida, guarda; devuelve ids creados, nueva `version`, resumen y problemas | `timelines:write` | |
| `timeline_scene_put` | Reemplazo completo del documento (`raw`) + `expectedVersion`; para copias o restauraciones | `timelines:write` | |
| `timeline_validate` | Problemas de la escena (errores y avisos) sin guardar | `read` | R |
| `timeline_delete` | Borrado lógico de timeline y escena | `delete` | D |

Fuera de v1 (se listan para no olvidarlos): tools `/admin/*`, subida de adjuntos en base64, invitaciones recibidas (`/invitations/me`), preferencias de notificación, sesiones de usuario.

### 3.4 Recursos y prompts

| Recurso | URI | Contenido |
|---|---|---|
| Guía | `stocka://guide` | `resources/mcp/guide.md`: flujos recomendados (alta de artículo, auditoría de pendientes, crear una timeline), convenciones de ids y versiones, límites |
| Catálogo | `stocka://catalog` | `catalog.json` (igual que `GET /contract/catalog`) |
| Esquema de escena | `stocka://schema/scene` | JSON Schema del documento de escena + semántica (`startMs` relativo al principio, `paramValues` por nombre, parámetro de duración ligado a `durationMs` del clip, `first/last` relativos al segmento) |
| Resumen de organización | `stocka://org/{slug}/overview` (template) | Cuentas por tipo/estado, ubicaciones raíz, últimos cambios; sirve como primer vistazo barato |

| Prompt | Argumentos | Guía |
|---|---|---|
| `timeline_from_description` | `org`, `description`, `durationSeconds` | Pasos: `org_get` → `pieces_search` por términos de la descripción → `piece_type_get` para conocer acciones y parámetros → `timeline_create` → `timeline_scene_edit` por lotes de ≤ 30 operaciones → `timeline_validate` → resumen al usuario |
| `inventory_audit` | `org` | Artículos `PENDING`, sin ubicación, sin propietario o sin foto; propone correcciones con `piece_update` y pide confirmación |
| `find_piece` | `org`, `what` | Cómo formular `q` para la búsqueda semántica y cómo refinar con filtros |

### 3.5 Ficheros

| Fichero (bajo `modules/mcp/`) | Contenido |
|---|---|
| `McpProperties`, `McpSecurityConfiguration`, `McpAuthentication`, `McpAuthenticationConverter`, `ProtectedResourceMetadataController`, `McpRequestContext` | §3.1 |
| `McpServerConfiguration` | Carga `instructions.md`; registra `McpToolRegistry` si no se usan anotaciones; CORS de `/mcp` |
| `tool/IdentityTools`, `OrganizationTools`, `LocationTools`, `PieceTypeTools`, `PieceTools`, `AttachmentTools`, `ImportExportTools`, `ContactTools`, `HardwareTools`, `SearchTools`, `TimelineTools` | Un bean por dominio; cada método `@McpTool` + `@PreAuthorize("hasAuthority('SCOPE_…') and @orgSecurity.…(#org, principal)")`; cuerpo de 5-15 líneas: resolver organización, llamar al servicio, mapear a DTO compacto |
| `tool/support/OrgAccess` | `requireCurrent(slug)` + pertenencia; mensajes `organizations.not_found` |
| `tool/support/ToolResults`, `ToolErrorMapper`, `Pages`, `Truncation` | Construcción de respuestas, errores, paginación, recorte de textos largos (`description` > 2 000 caracteres se corta con marca) |
| `dto/` | `OrgSummary`, `LocationNode`, `TypeSummary`, `AttributeSummary`, `PieceSummary`, `PieceDetail`, `AttachmentSummary`, `ContactSummary`, `PortSummary`, `ActionSummary`, `TimelineSummary`, `SceneSummary`, `SceneIssue`, `EditResult`, `ImportReport` |
| `resource/GuideResource`, `CatalogResource`, `SceneSchemaResource`, `OrgOverviewResource` | §3.4 |
| `prompt/TimelinePrompts`, `InventoryPrompts` | §3.4 |
| `McpCallLogger` | Aspecto o decorador del registro: log, métrica, rate limit por usuario |
| `resources/mcp/instructions.md`, `guide.md`, `scene.schema.json` | Textos y esquema |
| `SecurityConfiguration` | Pasa a `@Order(3)`; `anyRequest().authenticated()` sin cambios |
| `CLAUDE.md`, `bruno/README.md`, `bruno/mcp/*.bru`, `docs/mcp.md` | Documentación (§7) |

### 3.6 Interfaces que aíslan las librerías

- `McpToolRegistry` es el único sitio que importa `io.modelcontextprotocol.*`; las tools solo conocen records propios y `ToolResults`.
- `ClientRegistry` (interfaz propia) envuelve el `RegisteredClientRepository`; `CimdRegisteredClientRepository` y el fallback manual la implementan.
- `ProtectedResourceMetadataController` y el entry point son nuestros aunque se use `McpServerOAuth2Configurer`, para controlar el formato exacto de la cabecera 401 que exige Claude.

---

## 4. Motor de escena en el backend (generar y editar timelines)

Hoy la escena es un JSON opaco propiedad del editor (`timeline-editor/app/types/editor.ts`, `schemaVersion = 1`); el backend solo comprueba referencias cruzadas (`TimelineSceneService.validateReferences`). Para que un modelo edite timelines con seguridad el backend necesita entender el documento. El editor sigue siendo el dueño del esquema; el backend añade un modelo tipado, un aplicador de operaciones y un validador que **preserva los campos que no conoce** (compatibilidad hacia delante con `@JsonAnySetter/@JsonAnyGetter`).

Paquete `modules/timelines/scene/`:

| Clase | Responsabilidad |
|---|---|
| `SceneDocument`, `Board`, `Layer`, `Item`, `Track`, `Clip` (records Jackson 3 con `extra: Map<String,Object>`) | Espejo de `editor.ts`; `SceneDocument.empty(durationMs, boardCm)` reproduce `emptyDocument()` |
| `SceneIds` | `nextId(prefix)` = `<prefix>-<uuid>` como `ids.ts` |
| `SceneOp` (interfaz sellada, polimórfica por `op`) | `add_layer`, `add_item`, `update_item`, `remove_item`, `set_item_ports`, `add_track`, `rename_track`, `remove_track`, `add_clip`, `update_clip`, `move_clip`, `remove_clip`, `set_duration`, `set_board`, `set_strip_order` |
| `SceneEditor.apply(doc, ops, ctx)` | Aplica en orden y de forma atómica; comodidades para el modelo: `add_item` acepta `pieceId` o `pieceName` (coincidencia única; si hay varias devuelve candidatos), capa por defecto, tamaño desde los atributos de anchura/altura de la pieza si existen, posición automática en rejilla si faltan `x,y`; `add_clip` acepta `trackId` o `itemId` (busca o crea la pista), `action` por id o por nombre entre las acciones de los tipos de la pieza, `params` por nombre, `startMs` o `after: <clipId>` para encadenar; `set_item_ports` por id o nombre de puerto; `remove_item` elimina pistas y clips dependientes |
| `SceneValidator.validate(doc, org)` | Estructura (ids únicos, referencias internas, `startMs ≥ 0`, `durationMs > 0`), referencias de organización (lo que ya hace `validateReferences`), semántica por clip: la acción pertenece a un tipo de la pieza; parámetros obligatorios presentes; tipos y rangos según `ActionParameterDto` y, cuando el tipo tiene `kind`, según el catálogo (port a Java de `checkValue` de `showValidate.ts`: `int`, `number`, `bool`, `string`, `rgb`, `enum`, `min/max`); el parámetro de duración no se envía como valor; clip dentro de la duración total; avisos: ítem sin puerto para una acción con `kind`, dos acciones `continuous` solapadas en el mismo objetivo, fundido sin `from_color` |
| `SceneSummarizer` | Vista compacta: `durationMs`, ítems (`id`, pieza, tipos, puertos por nombre, capa), pistas, clips ordenados por `startMs` con nombre de acción y parámetros, problemas; es lo que devuelven `timeline_get` y `timeline_scene_get(compact)` |
| `TimelineSceneService` | Nuevos métodos `applyOps(orgId, timelineId, ops, expectedVersion)`, `summary(...)`, `validate(...)`; `upsert` pasa a usar `SceneValidator` (estructura + referencias; la semántica solo avisa, para no romper el autosave del editor) |
| REST (para el editor, Bruno y tests) | `POST /organizations/{slug}/timelines/{id}/scene/ops`, `GET …/scene/summary`, `POST …/scene/validate`; mismos permisos que `/scene` |

Concurrencia: el editor autosave usa `version`; una edición MCP incrementa `version` y el editor, al chocar, ya recarga (`timeline_scenes.version_conflict`). En el otro sentido, la tool devuelve la `version` actual en el error para que el modelo relea y reintente.

Fase 5 (recomendada): portar `showPackage.ts` + `canonicalJson.ts` a `ShowPackageBuilder` en Java, con test que compruebe que `contract/samples/belen-2026.show.json` produce **el mismo hash** que el test del editor y que `tools/show_hash.py`; nuevo `GET …/timelines/{id}/show` y tool `timeline_export_show`. Así el contrato tiene una sola implementación servidor y el editor puede dejar de montar el paquete en el cliente cuando se quiera.

---

## 5. Frontend

| Fichero | Cambio |
|---|---|
| `app/pages/dashboard/mi-cuenta.vue` | Dos secciones nuevas tras «Cambiar contraseña» |
| `app/components/dashboard/ConnectedAppsCard.vue` | Lista de aplicaciones conectadas (nombre, insignia «verificada» si CIMD de host de confianza, permisos, concedida, último uso) con botón «Revocar» y confirmación |
| `app/components/dashboard/AssistantConnectCard.vue` | «Conecta un asistente»: URL del servidor (`https://stocka.xeye.es/mcp`) con botón de copiar; pasos para Claude.ai (conector personalizado), Claude Code (`claude mcp add --transport http stocka https://stocka.xeye.es/mcp`) y Claude Desktop; nota de qué permisos pedir |
| `app/stores/connectedApps.ts` | `fetch()`, `revoke(clientId)` |
| `app/types/api.ts` | `ConnectedAppDto` |
| `i18n/locales/{ca,es,en}.json` | `dashboard.account.connected_apps.*`, `dashboard.account.assistant.*`, `errors.oauth.*` |

Las páginas de login y consentimiento **no** están en Nuxt: las sirve el backend (§2.3) porque el flujo OAuth necesita una sesión en el dominio del AS.

---

## 6. Infraestructura y despliegue

- `env/stocka.env` (+ `stocka.env.example` y `check-env.sh`): `MCP_ENABLED`, `MCP_RESOURCE_URL=https://stocka.xeye.es/mcp`, `OAUTH_ISSUER=https://stocka.xeye.es`, `OAUTH_JWK_PRIVATE_KEY` (PEM EC P-256 generado con `openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt`, en una línea con `\n` o base64), `OAUTH_JWK_KID`, `OAUTH_DCR_ENABLED=true`, `OAUTH_CIMD_TRUSTED_HOSTS=claude.ai`. La clave nunca se imprime; se genera directamente en el servidor.
- Caddy: nada que cambiar (proxy de todo el host). Verificar que `/.well-known/*` llega al backend y que no hay caché.
- Red: las llamadas de Claude salen de `160.79.104.0/21`; no hay WAF delante. Presupuesto de latencia de 10 s en discovery/token: todo es local a la base de datos.
- Despliegue con el procedimiento habitual: suite completa, ensayo de la migración V22 sobre un volcado, `backup.sh` manual, push a `master`, verificar `deploy.sh history` y la tabla de Flyway. Primer despliegue con `MCP_ENABLED=false` (solo el AS y la migración); segundo con `true`.
- Rollback: `MCP_ENABLED=false` apaga la cadena `/mcp` y las tools sin tocar la base de datos.

---

## 7. Pruebas y documentación

### 7.1 Tests (JUnit 5, `@Nested`, exhaustivos)

| Clase | Grupos |
|---|---|
| `OAuthScopeTest` | parseo, implicaciones, descripciones en los tres idiomas |
| `OAuthKeyLoaderTest` | PEM válido, inválido, ausente en `prod`, rotación con clave previa |
| `OAuthTokenCustomizerTest` | claims, `aud` fijo, `resource` coincidente / distinto (`invalid_target`), recorte de scopes |
| `ClientMetadataDocumentClientTest` (`MockRestServiceServer`) | documento válido, `client_id` distinto, sin `redirect_uris`, tamaño excesivo, http, timeout, caché |
| `CimdRegisteredClientRepositoryTest` | síntesis del cliente, `trusted`, fallback a JDBC para ids no URL |
| `RedirectUriValidatorTest` | https exacto, loopback con puerto variable (`127.0.0.1`, `localhost`, `[::1]`), http no loopback rechazado, fragmentos |
| `DynamicClientRegistrationControllerTest` | cuerpo válido, `application_type`, URIs inválidas, deshabilitado (`oauth.registration_disabled`), rate limit |
| `ConnectLoginControllerTest`, `ConsentControllerTest` | credenciales, 2FA (TOTP, recuperación, fallo), CSRF, `continue` seguro (solo rutas locales), consentimiento parcial, host loopback con aviso |
| `ConnectedAppServiceTest` | listado agrupado, revocación (autorizaciones, consentimiento, `jti`), auditoría |
| `McpAuthenticationConverterTest` | usuario inexistente, `tokenVersion` distinto, contraseña cambiada tras `iat`, `jti` revocado, scopes → authorities |
| `ProtectedResourceMetadataControllerTest`, `McpEntryPointTest` | cuerpo de metadatos, cabecera 401 exacta (`resource_metadata`, `scope`), 403 `insufficient_scope` |
| `ToolErrorMapperTest` | cada tipo de excepción → `{code, message, hint}` |
| `*ToolsTest` (uno por clase de tools, servicios mockeados) | camino feliz, scope ausente, rol insuficiente, organización ajena, paginación y tope, recorte, cada error de dominio relevante |
| `SceneEditorTest`, `SceneValidatorTest`, `SceneSummarizerTest` | cada operación (éxito y cada fallo), ids generados, comodidades (`pieceName` ambiguo, `after`), campos desconocidos preservados, cada regla de validación, resumen determinista |
| `TimelineSceneServiceTest` (extender) | `applyOps` con `expectedVersion` correcto / obsoleto / nulo, atomicidad ante error en la op 3 de 5 |
| Integración `OAuthFlowIntegrationTest` (`@SpringBootTest` + MockMvc) | Baile completo: 401 → metadatos → registro dinámico → authorize (login + 2FA + consentimiento) → código → token con PKCE → `POST /mcp initialize`, `tools/list`, `tools/call me` → refresh con rotación (el antiguo deja de valer) → revocación → 401. Variante CIMD con servidor de metadatos simulado. Variante de scope insuficiente |
| Integración `McpToolsIntegrationTest` | Fixture como `PieceSemanticListIntegrationTest`: `pieces_search` (ranker mockeado), `piece_create` con valores por nombre, `timeline_create` + `timeline_scene_edit` + `timeline_validate` + conflicto de versión con el editor (`PUT /scene`) |
| `FlywayMigrationsTest` | V22 pasa el chequeo |

Manuales: MCP Inspector (`npx @modelcontextprotocol/inspector`) contra `http://localhost:9095/mcp`; Claude Code con `claude mcp add --transport http stocka …`; conector personalizado en Claude.ai contra producción con una cuenta de prueba.

### 7.2 Bruno

Carpeta `bruno/mcp/`: `get-protected-resource-metadata`, `get-authorization-server-metadata`, `register-client` (DCR), `authorize` (documenta el paso de navegador y cómo capturar `code`), `token-pkce` (script pre-request que genera `code_verifier`/`code_challenge`), `refresh`, `revoke-connected-app`, `mcp-initialize`, `mcp-tools-list`, `mcp-tools-call-me`, `mcp-tools-call-pieces-search`, `mcp-tools-call-timeline-scene-edit`. `bruno/README.md`: sección «MCP y OAuth» con la secuencia y los códigos nuevos en la tabla.

### 7.3 Documentación

- `CLAUDE.md`: filas de endpoints (`/oauth2/*`, `/.well-known/*`, `/mcp`, `/users/me/connected-apps`, `/scene/ops|summary|validate`) y sección «MCP y OAuth» (cadenas de seguridad, scopes, variables, cómo añadir una tool).
- `docs/mcp.md`: guía de usuario para conectar Claude y otros clientes, permisos, revocación, límites y preguntas frecuentes (en castellano y catalán).
- `docs/mcp-tools.md`: catálogo generado desde las anotaciones (`tools/list` volcado en Markdown por un test) para no desincronizar documentación y código.
- Memoria del proyecto: fichero `project_stocka_mcp.md` con las decisiones y las variables de producción.

---

## 8. Fases y orden de trabajo

| Fase | Entrega | Criterio de salida | Tamaño |
|---|---|---|---|
| **0 · Spike** | Rama local con `spring-ai-starter-mcp-server-webmvc` 2.0.1, `mcp-server-security-spring-boot` y `mcp-authorization-server-spring-boot` 0.1.14 sobre Boot 4.0.6; una tool `ping`; tres cadenas de seguridad conviviendo; Inspector conectado con OAuth | (1) arranca con Jackson 3 y la suite actual sigue verde; (2) se confirma si las tools corren en el hilo del request o hace falta `McpTransportContext`; (3) se confirma si las anotaciones `@McpTool` funcionan en modo `STATELESS`; (4) decisión librería comunitaria vs. fallback manual | S (2 días) |
| **1 · Authorization Server** | Módulo `oauth` completo (§2) menos Google y atajo de sesión; V22; aplicaciones conectadas (API) | `OAuthFlowIntegrationTest` verde; Claude Code obtiene un token y llama a `me`; suite completa verde | L |
| **2 · MCP de lectura** | Módulo `mcp` (§3.1-3.2), tools de lectura de todos los dominios, recursos, prompts, errores, logging, rate limit | Conector en Claude.ai lee artículos, tipos, ubicaciones y timelines de una organización real; documentación `docs/mcp.md` | M |
| **3 · Escritura de inventario** | Tools de creación/edición/borrado de artículos, tipos, atributos, ubicaciones, contactos, adjuntos por URL, importación/exportación; scope `delete` | Tests de cada tool; auditoría e historial de artículos reflejan los cambios hechos por MCP | M |
| **4 · Motor de escena** | §4: modelo, operaciones, validador, resumen, REST `/scene/ops|summary|validate`, tools de timelines | Crear una timeline del belén desde una descripción en Claude.ai, abrirla en el editor y que valide sin errores; conflicto de versión en ambos sentidos probado | L |
| **5 · Hardware, organización y show** | Tools de puertos y acciones, `org:manage`, `ShowPackageBuilder` + `GET …/show` + `timeline_export_show` con hash cruzado; frontend (tarjetas de «Mi cuenta»); CLAUDE.md, Bruno, memoria | `.show.json` generado por MCP pasa `contract/validate.py` y coincide en hash con el del editor | M |
| **6 · Endurecimiento y producción** | Revisión de seguridad (lista §9), `iss` en respuestas, limpieza de clientes DCR, insignia de host de confianza, atajo de sesión (§2.5), Google en el login del AS, pruebas de carga sobre `/mcp` (ráfagas de 20 llamadas paralelas), despliegue en dos pasos (§6) | Conector publicado para los usuarios de Stocka; `MCP_ENABLED=true` en producción | M |

Dependencias: 1 → 2 → 3; 4 depende de 2; 5 depende de 3 y 4; 6 cierra. Las fases 3 y 4 pueden ir en paralelo.

---

## 9. Riesgos, límites y decisiones abiertas

**Riesgos técnicos**

1. **Madurez de `mcp-security` 0.1.x**: fijar versión, interfaces propias (§3.6), fallback manual estimado en dos días.
2. **Dependencia de Spring AI en el backend**: solo el starter del servidor MCP; sin modelos ni clientes de IA. Vigilar el tamaño de la imagen.
3. **Modo sin estado**: no hay *elicitation*; las confirmaciones dependen del host. Mitigación: `destructiveHint`, scope `delete` desmarcado por defecto, `dryRun=true` por defecto en importación.
4. **Hilo de ejecución de las tools**: si el SDK no ejecuta en el hilo del request, `SecurityContextHolder` estará vacío; el spike lo resuelve con `McpTransportContext`.
5. **Validación de `redirect_uri` con `localhost`**: OAuth 2.1 desaconseja `localhost` pero Claude Code lo usa; se acepta solo para loopback y se avisa en el consentimiento.
6. **Claude.ai exige 401 con `resource_metadata`** (ignora la cabecera en un 200) y `resource` del metadato igual a la URL que el usuario escribe: el test de integración fija ambas cosas; documentar en `docs/mcp.md` que la URL es exactamente `https://stocka.xeye.es/mcp` sin barra final.
7. **Presupuesto de tokens del modelo**: escenas de hasta 2 MB; por eso `compact` es el formato por defecto y `raw` hay que pedirlo; listas con tope 50 y recorte de textos.
8. **Doble propiedad del esquema de escena**: el backend preserva campos desconocidos y solo valida lo que entiende; `SCENE_SCHEMA_VERSION` se comprueba y una versión mayor que la conocida se rechaza en `applyOps` (no en `upsert`, que sigue aceptando lo que el editor mande).
9. **Latencia de revocación**: ≤ 30 s por la caché de `jti`; aceptable, documentado.
10. **Ráfagas de llamadas paralelas** desde Claude.ai: política `mcp.calls.user` y pool de conexiones suficiente (HikariCP ya dimensionado para la API).

**Límites de v1**

- Sin tools de administración global ni gestión de sesiones/notificaciones.
- Sin subida de ficheros en base64 (solo por URL https).
- Sin restricción del token a organizaciones concretas (todas las del usuario, con su rol). Es una extensión natural del consentimiento (claim `orgs`) para una fase posterior.
- Sin Google en la pantalla de login del AS hasta la fase 6.

**Decisiones que conviene confirmar antes de la fase 1**

1. Scopes `delete` y `org:manage` **desmarcados por defecto** en el consentimiento (recomendado: sí).
2. Mantener el registro dinámico anónimo activo junto a CIMD (recomendado: sí, con rate limit y limpieza; revisar cuando los hosts principales hayan migrado a CIMD).
3. Mover el montaje del paquete de show al backend en la fase 5 y que el editor lo consuma después (recomendado: sí; una sola implementación del contrato en servidor).
4. Idioma de las descripciones de tools y mensajes de error hacia el modelo: inglés (recomendado) con datos y textos de usuario en su idioma.

---

## 10. Referencias

- Especificación MCP, autorización (2026-07-28): https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization y registro de clientes: https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration
- Requisitos de Claude para conectores remotos (callbacks, CIMD/DCR, 401, latencias): https://claude.com/docs/connectors/building/authentication
- Spring AI 2.0, servidor MCP sin estado: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-stateless-server-boot-starter-docs.html
- MCP Security para Spring (resource server, authorization server, CIMD, loopback): https://github.com/spring-ai-community/mcp-security
- MCP Java SDK 2.0 (Jackson 3): https://java.sdk.modelcontextprotocol.io/latest/overview/
- Spring Authorization Server, registro dinámico: https://docs.spring.io/spring-authorization-server/reference/guides/how-to-dynamic-client-registration.html y CVE-2026-59354 (corregida en Spring Security 7.0.5): https://spring.io/security/cve-2026-59354
