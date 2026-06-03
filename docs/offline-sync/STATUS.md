# Offline-sync — Estado de implementación

> Rama: `claude/desktop-app-offline-sync-xFg76`. Este documento refleja qué está
> **implementado y verificado** y qué queda, a fecha del último commit. Complementa a
> `DESIGN.md` (diseño) y `DECISIONS-AND-RISKS.md` (auditoría de 32 riesgos).

## Resumen

| Capa | Estado |
|------|--------|
| Identidad `syncId`/`rev` + secuencia de cambios (CSN) | ✅ Completo (6 entidades) |
| Pull `/sync/v1/changes` | ✅ Completo (6 colecciones, tombstones R1) |
| Push `/sync/v1/mutations` | 🟡 4/6 colecciones (todo el catálogo) |
| Cliente escritorio (Tauri + RxDB + motor de sync) | ✅ Motor completo y verificado |
| Integración con UI (stores/componentes) | ⏳ Pendiente |
| Auth keychain + CORS Tauri | ⏳ Pendiente |
| Adjuntos (binarios + caché) | ⏳ Pendiente |
| Cifrado en reposo (F3) · firma (M-Dist) | ⏳ Pendiente |

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
  Colecciones soportadas: **`locations`, `pieceTypes`, `orgAttributes`, `pieceTypeAttributes`**.

### Pendiente backend — y por qué

- **`pieces` (push)**: requiere gestionar los **valores de atributos embebidos** + recálculo de
  status. Un push parcial sería **incorrecto**: la reconciliación del cliente hace upsert del
  `serverDoc`, así que devolver el agregado sin sus valores **los borraría** localmente. El
  enfoque correcto es **reutilizar `PieceService`** (refactor para aceptar un `syncId` de cliente
  y resolver refs `syncId`→id), preservando validación/historial/status. Es un sub-bloque propio.
- **`attachments` (push)**: implica subida de binarios a R2 desde la cola offline (no solo
  metadatos); cola binaria separada + caché LRU.
- **Auth (C)**: aceptar el refresh token por header/body además de cookie; CORS para el origin
  de Tauri.

## Frontend / escritorio (verificado con Vitest)

- **F0** — Tauri 2 + target SPA (`nuxt.config` `$env.desktop`), sin tocar la web.
- **Datos** — RxDB: 6 colecciones + `outbox`; `createStockaDatabase` con storage inyectable
  (Dexie en app, memoria en tests) y namespace por usuario (R28).
- **Motor de sync** — `runPull` (cursor/reanudación, tombstones), `pushOutbox` (reconciliación
  LWW + dead-letter R8), `runSync` (orquestador), `createSyncTransport` (`/sync/v1` con Bearer).
- **Escritura offline** — `locationRepository` (escritura local inmediata + encolado).

### Pendiente frontend

- **Integración UI (el grande)**: reescribir los stores Pinia para consumir RxDB y migrar la
  identidad `id` numérico → `syncId` en los componentes. **Requiere la app en ejecución**
  (Tauri + WebView + backend) para verificarse; no encaja en tests unitarios aislados.
- Repositorios RxDB para las demás colecciones (réplicas de `locationRepository`).
- `useApi` con estrategia bearer+keychain por target; `useSync` (online real + badges);
  middlewares offline.

## Verticales funcionando end-to-end

- **`locations`**: backend pull+push ↔ frontend RxDB apply + outbox push + reconciliación.
- **Catálogo** (`pieceTypes`, atributos): backend pull+push verificado; frontend pull aplica.

## Cómo ejecutar las pruebas

- Backend: `JAVA_HOME=<jdk25> ./mvnw test` (H2, Flyway desactivado en test).
- Frontend: `pnpm test` (Vitest; RxDB con storage en memoria).
