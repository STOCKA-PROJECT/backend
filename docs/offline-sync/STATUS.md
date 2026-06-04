# Offline-sync — Estado de implementación

> Rama: `claude/desktop-app-offline-sync-xFg76`. Este documento refleja qué está
> **implementado y verificado** y qué queda, a fecha del último commit. Complementa a
> `DESIGN.md` (diseño) y `DECISIONS-AND-RISKS.md` (auditoría de 32 riesgos).

## Resumen

| Capa | Estado |
|------|--------|
| Identidad `syncId`/`rev` + secuencia de cambios (CSN) | ✅ Completo (6 entidades) |
| Pull `/sync/v1/changes` | ✅ Completo (6 colecciones, tombstones R1) |
| Push `/sync/v1/mutations` | 🟡 5/6 colecciones (catálogo + pieces); falta attachments |
| Cliente escritorio (Tauri + RxDB Dexie + motor de sync + repos de las 6 colecciones) | ✅ Completo y verificado (26 tests Vitest) |
| Auth escritorio (refresh por header + token en body) + CORS Tauri + `DesktopSession` cliente | ✅ Backend + sesión cliente; falta solo el `TokenStore` de keychain |
| Bootstrap Nuxt (`$stockaSync`) + `useSync` + Dexie + checkpoint persistente | ✅ Implementado; build de escritorio verificado |
| Integración con stores/componentes existentes (`id`→`syncId`) | 🟡 Stores `locations`, `pieceTypes`, `organizationPieceAttributes` y `pieces` en modo dual (web=API, escritorio=RxDB); pendiente validar con la app en ejecución |
| Adjuntos (binarios + caché) | ⏳ Pendiente (metadatos sí se asemblan offline; descarga/subida de binarios siguen online) |
| `TokenStore` keychain · cifrado en reposo (F3) · firma (M-Dist) | ⏳ Pendiente |

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

### Pendiente backend — y por qué

- **`attachments` (push)**: implica subida de binarios a R2 desde la cola offline (no solo
  metadatos); cola binaria separada + caché LRU.
- **Supresión de emails en replay** (B3): el push de pieces reutiliza `PieceService`, que publica
  eventos de ciclo de vida (emails). Falta un flag "origen sync" para suprimirlos al drenar el
  outbox y evitar avalanchas de correo.
- **Auth (C)**: aceptar el refresh token por header/body además de cookie; CORS para el origin
  de Tauri.

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
  El **historial** es best-effort (server-computed; offline → página vacía) y los **binarios de
  adjuntos** (subida/descarga) siguen siendo online (no forman parte del set de sync).

### Pendiente frontend

- **Validación end-to-end con la app en ejecución** (Tauri + WebView + backend) de los stores en
  modo dual; algunos flujos (cobertura de portada offline, descarga de binarios) dependen del
  workstream de adjuntos.
- `org` resolution offline ya cae a `localStorage`; equipo/puertos quedan online (fuera de scope).
- `useApi` con `TokenStore` de keychain (hoy en memoria); `useSync` (online real + badges);
  cola de binarios de adjuntos; cifrado en reposo (F3); firma (M-Dist).

## Verticales funcionando end-to-end

- **`locations`**: backend pull+push ↔ frontend RxDB apply + outbox push + reconciliación.
- **Catálogo** (`pieceTypes`, atributos): backend pull+push verificado; frontend pull aplica.

## Cómo ejecutar las pruebas

- Backend: `JAVA_HOME=<jdk25> ./mvnw test` (H2, Flyway desactivado en test).
- Frontend: `pnpm test` (Vitest; RxDB con storage en memoria).
