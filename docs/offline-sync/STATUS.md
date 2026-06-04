# Offline-sync — Estado de implementación

> Rama: `claude/desktop-app-offline-sync-xFg76`. Este documento refleja qué está
> **implementado y verificado** y qué queda, a fecha del último commit. Complementa a
> `DESIGN.md` (diseño) y `DECISIONS-AND-RISKS.md` (auditoría de 32 riesgos).

## Resumen

| Capa | Estado |
|------|--------|
| Identidad `syncId`/`rev` + secuencia de cambios (CSN) | ✅ Completo (6 entidades) |
| Pull `/sync/v1/changes` | ✅ Completo (6 colecciones, tombstones R1) |
| Push `/sync/v1/mutations` | ✅ 5 colecciones de datos + endpoints de binarios de adjuntos (`/sync/v1/attachments`) |
| Supresión de emails en replay (B3) | ✅ `NotificationSuppressionContext` + flag `replay` en el evento |
| Cliente escritorio (Tauri + RxDB Dexie + motor de sync + repos de las 6 colecciones) | ✅ Completo y verificado (39 tests Vitest) |
| Auth escritorio (refresh por header + token en body) + CORS Tauri + `DesktopSession` cliente | ✅ Completo |
| Bootstrap Nuxt (`$stockaSync`) + `useSync` + Dexie + checkpoint persistente | ✅ Implementado; build de escritorio verificado |
| Integración con stores/componentes existentes (`id`→`syncId`) | ✅ Stores `locations`, `pieceTypes`, `organizationPieceAttributes` y `pieces` en modo dual; pendiente validar con la app en ejecución |
| Adjuntos (binarios + cola separada) | ✅ Cola local `attachmentQueue` + push best-effort (R15–R17); descarga on-demand pendiente de la app |
| `TokenStore` keychain | ✅ `TauriKeychainTokenStore` (crate `keyring`) con fallback a memoria |
| Cifrado en reposo (R29) | ✅ Cifrado de campos sensibles en RxDB con clave en keychain |
| Auto-sync al reconectar + badge de estado de sync | ✅ Plugin `online` + `useDesktopSyncStatus` + `SyncStatusBadge` |
| Firma / notarización / auto-update (M-Dist) | 🟡 Infra lista (updater + workflow CI); requiere certificados externos y el JS del updater para el "buscar actualizaciones" |

## Backend (verificado con Java 25 + H2)

- **Workstream A** — `SyncableBaseEntity` (`syncId` único + `rev`), `OrgChangeSequenceService`
  (contador monótono por org con `UPDATE` bloqueante → cursor sin huecos saltables, D2/R2),
  `SyncStamper` estampando en la capa de servicio. Flyway `V15`–`V17`.
- **Pull (B1)** — `SyncReadRepository` con queries **nativas** (bypass de `@SQLRestriction` →
  tombstones, R1) para las 6 colecciones; `pieces` como agregado con valores de atributos
  embebidos y refs por `syncId`. `SyncService` con cursor por colección + `hasMore`.
- **Push (B2)** — `SyncPushService`: idempotencia (`sync_mutation`, R24), LWW con reporte de
  `conflict`, **borrado pegajoso** (R7), permisos por mutación (R18), `name_conflict` con
  pre-check (R10), `dependency_failed` para refs ausentes (R5), liberación de slot en borrado.
  Colecciones soportadas: **`locations`, `pieceTypes`, `orgAttributes`, `pieceTypeAttributes`,
  `pieces`**.
- **`pieces` (push)** — implementado **reutilizando `PieceService`**: `create(orgId, dto, syncId)`
  preserva el `syncId` de cliente; el handler resuelve las refs `syncId`→id (tipos, location,
  owner, atributos) y construye `Create/UpdatePieceDto`, por lo que validación, valores de
  atributos, cálculo de status e historial se reusan intactos. Cada mutación de piece corre en
  una transacción `REQUIRES_NEW` aislada (un fallo no contamina el lote); excepciones de dominio
  → `serial_conflict` / `dependency_failed` / `validation_failed`. El `serverDoc` devuelto incluye
  los valores de atributos (no se pierden al reconciliar).

### Adjuntos (push de binarios) — ✅

- `POST /organizations/{slug}/sync/v1/attachments` (multipart): resuelve la pieza por `syncId`,
  conserva el `syncId` de adjunto asignado por el cliente e **idempotente** por ese `syncId`
  (reintento devuelve el existente sin re-subir). Reutiliza todo el pipeline de validación
  (Tika MIME, dimensiones/tamaño/cuota, enlace de portada).
- `DELETE /organizations/{slug}/sync/v1/attachments/{syncId}`: replay de borrado offline,
  idempotente. Ambos requieren `canWritePieces`.
- `PieceAttachmentService` refactorizado (`store`/`deleteResolved` compartidos) +
  `uploadForSync`/`softDeleteBySyncId`; `PieceAttachmentRepository.findBySyncId`.

### Supresión de emails en replay (B3) — ✅

- `NotificationSuppressionContext` (thread-local) capturado en `ResourceLifecycleEvent` al
  publicar (el listener `@Async` corre en otro hilo). `SyncPushService.push` envuelve el lote, así
  drenar el outbox no inunda a los suscriptores con un email por cada cambio reaplicado. Los
  servicios de dominio no se tocan (el constructor de 7 args del evento lee el contexto).

### Completado previamente

- **Auth (C)**: refresh token por header/body además de cookie; CORS para el origin de Tauri.

## Frontend / escritorio (verificado con Vitest)

- **F0** — Tauri 2 + target SPA (`nuxt.config` `$env.desktop`), sin tocar la web.
- **Datos** — RxDB: 6 colecciones + `outbox`; `createStockaDatabase` con storage inyectable
  (Dexie en app, memoria en tests) y namespace por usuario (R28).
- **Motor de sync** — `runPull` (cursor/reanudación, tombstones), `pushOutbox` (reconciliación
  LWW + dead-letter R8), `runSync` (orquestador), `createSyncTransport` (`/sync/v1` con Bearer).
- **Escritura offline** — `locationRepository` (escritura local inmediata + encolado).

### Integración de stores (modo dual web/escritorio)

Patrón: el store ramifica en `useRuntimeConfig().public.desktop`. En web la ruta es **idéntica**
a la actual (API vía BFF Nitro); en escritorio se respalda con RxDB + outbox y un `syncQuietly`
best-effort (push+pull) tras cada mutación. La identidad `syncId` se expone a los componentes como
un `id` numérico determinista (FNV-1a de `syncId`), compartido entre stores, y se mapea de vuelta
para las escrituras. Los **76 componentes y páginas no se tocan**.

Migrados y verificados (Vitest + `generate:desktop` + `generate`):

- **`locations`** — árbol, detalle con breadcrumb, crear/renombrar/mover/borrar offline.
- **`pieceTypes`** — listado/detalle con atributos embebidos (ensamblados desde la colección
  `pieceTypeAttributes`), crear/renombrar/borrar y alta/edición/baja de atributos offline. Las
  *actions* (sub-recurso gated, fuera del set de sync) siguen siendo online.
- **`organizationPieceAttributes`** — listado y CRUD de atributos de organización offline.
- **`pieces`** (el agregado) — tablero por ubicación, listado paginado con filtros (`typeId`,
  `locationId`, `ownerUserId`, `status`, `q`, orden y paginado resueltos en cliente), detalle con
  valores de atributos (tipo+org) y metadatos de adjuntos, y crear/editar/mover/borrar offline.
  El **historial** es best-effort (server-computed; offline → página vacía). Los **adjuntos** se
  suben/borran offline vía la cola de binarios (abajo); la **descarga** on-demand del binario sigue
  necesitando red.

### Adjuntos, seguridad y estado de sync — ✅

- **Cola de binarios** (`attachmentQueue`, R15–R17): `queueAttachmentUpload`/`queueAttachmentDelete`
  + `pushAttachments` (drenaje best-effort: reconcilia `rev`/`r2Key` al subir, dead-letter en 4xx,
  reintenta 5xx/red; nunca aborta el sync de datos). `transport` con multipart contra
  `/sync/v1/attachments`; el motor la drena entre push y pull. Store `pieces` encola la subida con
  los bytes en base64.
- **Keychain** (`TauriKeychainTokenStore`, crate `keyring`): tokens en el llavero del SO; degrada a
  memoria fuera de Tauri. `createTokenStore()` elige automáticamente.
- **Cifrado en reposo** (R29): campos sensibles cifrados en RxDB con clave por máquina en el
  llavero (`db_key`).
- **Auto-sync al reconectar** (plugin `online`) y **badge de estado** (`useDesktopSyncStatus` +
  `SyncStatusBadge` en el topbar: pendientes/sincronizando/sin conexión/conflictos + sincronizar
  manual).

### Pendiente frontend

- **Validación end-to-end con la app Tauri en ejecución** (la valida el usuario): los binarios de
  adjuntos y la descarga on-demand dependen del WebView + R2 reales.
- **Disparador "buscar actualizaciones" en la UI**: necesita el paquete JS
  `@tauri-apps/plugin-updater` (el canal de updater y el CI de firma ya están listos, M-Dist).
- `equipo`/`puertos` quedan online a propósito (fuera del set de sync).

## Verticales funcionando end-to-end

- **`locations`**: backend pull+push ↔ frontend RxDB apply + outbox push + reconciliación.
- **Catálogo** (`pieceTypes`, atributos): backend pull+push verificado; frontend pull aplica.

## Cómo ejecutar las pruebas

- Backend: `JAVA_HOME=<jdk25> ./mvnw test` (H2, Flyway desactivado en test).
- Frontend: `pnpm test` (Vitest; RxDB con storage en memoria).
