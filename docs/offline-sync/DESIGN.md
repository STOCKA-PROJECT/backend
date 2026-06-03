# Stocka — Diseño técnico: aplicación de escritorio offline-first con sincronización

> Estado: **borrador para revisión** · Rama: `claude/desktop-app-offline-sync-xFg76`
> Ámbito: backend (`stocka-project/backend`) + frontend (`stocka-project/frontend`).
> Este documento es el diseño **autoritativo y completo**. El companion específico de
> frontend/escritorio vive en `frontend/docs/offline-sync/DESIGN.md`.

## 1. Objetivo

Ofrecer una **aplicación de escritorio para Windows y macOS** que:

1. Reutilice la app Nuxt actual **sin eliminar la web** (una sola base de código de UI).
2. Permita **trabajar completamente offline** (consultar, crear, editar y borrar pieces,
   locations, piece-types y sus atributos).
3. **Sincronice** de forma incremental y robusta cuando hay conexión.
4. Sea **escalable a producción**: segura, observable y con resolución de conflictos definida.

### Decisiones ya cerradas (con el usuario)

| Decisión | Elección |
|----------|----------|
| Shell de escritorio | **Tauri 2** |
| Motor de sincronización | **A medida con RxDB** (storage Dexie/IndexedDB) |
| Política de conflictos base | **Last-Write-Wins**, servidor autoritativo |

## 2. Principios de diseño

- **Offline-first**: la UI lee y escribe **siempre** contra la base de datos local (RxDB).
  La red es un detalle del motor de sync, nunca un bloqueante de la interacción.
- **Una base de código, dos targets de build**: Nuxt SSR/SSG para la web (como hoy) y Nuxt
  SPA estática empaquetada en Tauri para escritorio.
- **Sincronización por agregados**: el `Piece` es la raíz de agregado y viaja como **un único
  documento** que incluye sus valores de atributos, sus tipos asociados y las referencias a
  adjuntos. Esto evita sincronizar tablas hijas sin timestamps de forma independiente.
- **Servidor autoritativo + LWW**: ante un conflicto, gana la última escritura según un
  número de revisión que **asigna el servidor**, no el reloj del cliente.
- **Idempotencia de extremo a extremo**: cada mutación del cliente lleva un id estable; el
  servidor deduplica reintentos.
- **Aprovechar lo existente**: refresh tokens con rotación, soft-delete (`deletedAt`),
  `createdAt`/`updatedAt` y el historial de pieces ya están en el backend; el diseño se apoya
  en ellos en vez de reinventarlos.

## 3. Arquitectura de alto nivel

```
┌──────────────────────── App de escritorio (Win/Mac) — Tauri 2 ───────────────────────┐
│                                                                                       │
│   Nuxt SPA (Vue 3 + Pinia)                                                            │
│        │  lee/escribe (queries reactivas)                                             │
│        ▼                                                                              │
│   RxDB (storage Dexie → IndexedDB)   ◄── colecciones: pieces, locations, pieceTypes, │
│        │                                  orgAttributes, attachmentsMeta, outbox,    │
│        │                                  syncState                                   │
│        │                                                                              │
│   Motor de sync (replication a medida)                                                │
│        │  pull(checkpoint) + push(changes)                                            │
│   Cola de adjuntos (FS local Tauri + caché LRU de binarios)                           │
│        │                                                                              │
└────────┼──────────────────────────────────────────────────────────────────────────────┘
         │  HTTPS + Bearer (token en keychain del SO)
         ▼
┌──────────────────────────────── Backend Spring Boot ─────────────────────────────────┐
│   SyncController:  GET /organizations/{slug}/sync/changes?since=…                     │
│                    POST /organizations/{slug}/sync/mutations                          │
│   + columnas syncId (UUID) y rev (BIGINT) por entidad sincronizable                   │
│   + secuencia de cambios por organización (org change sequence)                       │
│   + tabla de idempotencia de mutaciones                                               │
│        │                                   │                                          │
│        ▼                                   ▼                                          │
│     MariaDB                          Cloudflare R2 (binarios, presigned URLs)         │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

## 4. El problema de la identidad: PKs autoincrementales

Hoy todas las entidades usan `Integer` con `GenerationType.AUTO` (AUTO_INCREMENT de MariaDB).
**Un cliente offline no puede generar ese id**, por lo que no puede crear filas con identidad
estable antes de sincronizar. Solución:

- Añadir a cada entidad sincronizable una columna **`syncId` `CHAR(36)` (UUID v4)**, única,
  **generada por quien crea la fila** (cliente o servidor). Es la **clave de correlación**
  entre el documento RxDB local y la fila MariaDB.
- El `id` `Integer` sigue siendo la PK interna y la que usan las FKs existentes; **no se expone
  como identidad de sync**. El cliente referencia relaciones por `syncId`
  (p. ej. `locationSyncId`, `pieceTypeSyncIds`).
- En el push, el servidor resuelve `syncId → id` (crea si no existe, actualiza si existe).
  Así un piece creado offline con `syncId = X` y un `locationSyncId = Y` se materializa
  correctamente aunque la location `Y` también se haya creado en el mismo lote.

> Esto evita el frágil patrón de "id temporal negativo + remapeo". El `syncId` es la verdad
> de identidad para el cliente durante toda la vida del registro.

## 5. El cursor de cambios: número de revisión por organización

No hay columna `@Version`. Usar `updatedAt` (reloj) como cursor es frágil (relojes
desincronizados, colisiones en el mismo milisegundo, escrituras en lote). Diseño:

- Una **secuencia monótona por organización**, el *Org Change Sequence Number* (**CSN**).
- Cada vez que el servidor persiste una creación/actualización/borrado de una entidad
  sincronizable de la org, incrementa el CSN de esa org y **estampa el nuevo valor en la
  columna `rev BIGINT`** de la fila modificada.
- El **checkpoint** del cliente es, por colección, el mayor `rev` ya visto. El pull pide
  "dame todo lo que tenga `rev > checkpoint`, ordenado por `rev` asc".
- Implementación del CSN en MariaDB: una tabla `org_change_sequence(organization_id, value)`
  actualizada con `UPDATE … SET value = value + 1` dentro de la **misma transacción** que la
  mutación (bloqueo de fila por org evita huecos/duplicados). Alternativa más simple para el
  MVP: una secuencia global única (`@Version`-like) por tabla `sync_revision`. Se prefiere
  por-org para acotar el pull al tenant.

`rev` es monótono y libre de relojes → cursor determinista, reanudable y resistente a
reintentos.

## 6. Modelo de datos local (RxDB)

Documentos por colección. Campos de control comunes a todos: `syncId` (id primario del doc),
`rev` (último rev del servidor conocido; `null` si aún no sincronizado), `updatedAt`,
`deletedAt` (tombstone), `_localDirty` (boolean, hay cambios sin enviar).

### 6.1 `pieces` (agregado raíz)

```jsonc
{
  "syncId": "f0e1…",            // UUID, PK del documento
  "rev": 1842,                   // rev del servidor; null si creado offline y no sincronizado
  "organizationSlug": "acme",
  "name": "Bomba 3000",
  "serialNumber": "BG-0098",     // único por org (validado en server; ver §9 conflictos)
  "description": "…",
  "status": "ACTIVE",            // PENDING | ACTIVE
  "ownerUserSyncId": "u-…|null",
  "locationSyncId": "loc-…|null",
  "pieceTypeSyncIds": ["pt-…"],  // ManyToMany por syncId
  "coverAttachmentSyncId": "att-…|null",
  // valores de atributos embebidos (tablas hijas sin timestamps → viajan con el agregado)
  "typeAttributeValues":  [ { "attributeSyncId": "pta-…", "value": "12.5" } ],
  "orgAttributeValues":   [ { "attributeSyncId": "opa-…", "value": "azul" } ],
  "createdAt": "2026-06-01T10:00:00Z",
  "updatedAt": "2026-06-02T12:30:00Z",
  "deletedAt": null,
  "_localDirty": false
}
```

### 6.2 `locations`

Árbol por `parentSyncId` (self-ref). Campos: `syncId, rev, organizationSlug, name,
description, parentSyncId|null, createdAt, updatedAt, deletedAt, _localDirty`.
Regla local: no permitir ciclos al mover (validar antes de marcar dirty).

### 6.3 `pieceTypes` y sus atributos

`pieceTypes`: `syncId, rev, organizationSlug, name, createdAt, updatedAt, deletedAt`.
Los `PieceTypeAttribute` se modelan como colección propia `pieceTypeAttributes`
(`syncId, rev, pieceTypeSyncId, name, displayName, type, required, position, validatorsJson,
…`) porque tienen ciclo de vida y soft-delete propios.

### 6.4 `orgAttributes`

`OrganizationPieceAttribute` → `syncId, rev, organizationSlug, name, displayName, type,
required, position, validatorsJson, …`.

### 6.5 `attachmentsMeta`

Solo metadatos (el binario va aparte, §8): `syncId, rev, pieceSyncId, kind (IMAGE|DOCUMENT),
originalFilename, mimeType, sizeBytes, r2Key|null, localPath|null, uploadState
(PENDING_UPLOAD|UPLOADED|FAILED), createdAt, deletedAt`.

### 6.6 Colecciones de control (no se sincronizan)

- `outbox`: cola de mutaciones pendientes de push (§7.3).
- `syncState`: por colección, `{ checkpointRev, lastPullAt, lastPushAt }`.
- `blobCache`: índice LRU de binarios descargados (`r2Key`, `localPath`, `bytes`, `lastUsedAt`).

> **Catálogos de solo lectura local**: usuarios/miembros de la org se replican como referencia
> (para pintar nombres/avatares y validar `ownerUserSyncId`) pero el cliente **no** los muta.

## 7. Protocolo de sincronización

Replicación RxDB clásica: **pull con checkpoint + push de cambios + LWW**.

### 7.1 Pull — `GET /organizations/{slug}/sync/changes`

Query: `?since={checkpoints}&limit=500` donde `since` codifica el `rev` por colección
(o un cursor opaco que el server entiende).

Respuesta (lote ordenado por `rev` asc, por colección):

```jsonc
{
  "changes": {
    "pieceTypes":          [ { …doc, "rev": 1801, "deletedAt": null } ],
    "pieceTypeAttributes": [ … ],
    "locations":           [ … ],
    "orgAttributes":       [ … ],
    "pieces":              [ { …doc agregado, "rev": 1842 } ],
    "attachmentsMeta":     [ … ]
  },
  "checkpoint": { "pieces": 1842, "locations": 1790, "pieceTypes": 1801, … },
  "hasMore": false   // true → el cliente vuelve a pedir con el nuevo checkpoint
}
```

- Los borrados llegan como documentos con `deletedAt != null` (tombstone). El cliente marca
  el doc local como borrado (no lo elimina físicamente hasta que el server confirma).
  **Crítico (R1)**: las consultas de `/sync/changes` deben **incluir las filas borradas**
  haciendo *bypass* del `@SQLRestriction("deleted_at IS NULL")`; con los repositorios normales
  el server nunca entregaría los borrados y el cliente mantendría fantasmas. Ver
  `DECISIONS-AND-RISKS.md` §R1.
- **Orden de aplicación local** para respetar dependencias (FKs lógicas):
  `pieceTypes → pieceTypeAttributes → locations → orgAttributes → pieces → attachmentsMeta`.

### 7.2 Push — `POST /organizations/{slug}/sync/mutations`

```jsonc
{
  "mutations": [
    {
      "mutationId": "9b2c…",         // UUID idempotente generado por el cliente
      "collection": "pieces",
      "op": "upsert",                 // upsert | delete
      "syncId": "f0e1…",
      "baseRev": 1840,                // rev sobre el que el cliente editó (null si creación)
      "doc": { …agregado completo… }  // omitido si op=delete
    }
  ]
}
```

Respuesta:

```jsonc
{
  "results": [
    {
      "mutationId": "9b2c…",
      "status": "applied",            // applied | conflict | duplicate | rejected
      "syncId": "f0e1…",
      "serverDoc": { …estado canónico…, "rev": 1843 }  // siempre devuelto si applied/conflict
    }
  ]
}
```

- **Idempotencia**: tabla `sync_mutation(mutation_id PK, organization_id, applied_rev,
  created_at)`. Si llega un `mutationId` ya visto → `status=duplicate`, se devuelve el
  `serverDoc` resultante (sin re-aplicar). Esto hace los reintentos seguros (red caída tras
  aplicar pero antes de recibir respuesta).
- **Validación de negocio**: las reglas existentes (unicidad de `serialNumber` por org,
  no-ciclos en locations, `required` de atributos, permisos por rol de org) se ejecutan en el
  server. Si fallan → `status=rejected` con `errorCode` → el cliente revierte el cambio local
  y avisa al usuario (§10).
- **Permisos**: el push respeta el rol de org del usuario (OWNER/MANAGER/USER/SPECTATOR). Un
  SPECTATOR no puede pushear; USER puede pieces pero no piece-types/locations, etc. El cliente
  conoce el rol y **no encola** mutaciones no permitidas, pero el server es la autoridad final.

### 7.3 Outbox y orden de envío

- Toda escritura local crea/actualiza una entrada en `outbox` y marca el doc `_localDirty`.
- El push envía en **lotes ordenados por dependencia** (mismo orden que el pull) para que un
  `piece` que referencia una `location` creada offline llegue **después** de su location.
- Reintentos con backoff exponencial (2s, 4s, 8s, 16s; tope configurable) ante errores de red
  o 5xx; los 4xx de validación no se reintentan (se marcan como `rejected`).

### 7.4 Resolución de conflictos (LWW, servidor autoritativo)

Al aplicar una mutación `upsert` con `baseRev`:

1. Si `baseRev == rev_actual_servidor` → no hay conflicto: aplica, `rev = CSN++`, responde
   `applied`.
2. Si `baseRev < rev_actual_servidor` → **conflicto**: otro cliente escribió antes.
   Política base **LWW**: la escritura entrante **gana** y sobrescribe (es "la última"), salvo
   que viole una invariante de negocio (entonces `rejected`). El server responde `conflict`
   con el `serverDoc` canónico resultante para que el cliente reconcilie su copia.
3. El historial (`PieceHistory`) registra el cambio igual que hoy, dejando traza auditable de
   la sobrescritura.

**Regla delete-vs-update (R7, Crítico)**: el **borrado es pegajoso**. Un `upsert` con `baseRev`
anterior al `deletedAt` del servidor **no resucita** el registro → `status=rejected`
(`errorCode=deleted_upstream`); el cliente marca su copia como borrada. Solo un `upsert` cuyo
`baseRev` sea posterior al borrado (el cliente **vio** el tombstone y recrea deliberadamente)
puede des-borrar. Sin esta regla, un LWW ingenuo resucitaría datos borrados.

> **Nota de producto**: LWW puede perder cambios concurrentes de campos distintos. Está
> aceptado como base (F2). En §13 se deja la puerta al *merge por campo* apoyándose en
> `PieceHistory` para una fase posterior, sin cambiar el contrato de transporte.
> El catálogo completo de `errorCode` de `rejected` y las reglas de reconciliación viven en
> `DECISIONS-AND-RISKS.md` (Parte 2 y Parte 3).

## 8. Adjuntos / Cloudflare R2 offline

Los binarios (imágenes ≤25 MB, documentos ≤100 MB) **no** entran en RxDB.

**Subida offline**
1. El usuario adjunta un fichero sin red → se guarda en el FS local de Tauri
   (`appLocalDataDir/attachments/{syncId}`), se crea `attachmentsMeta` con
   `uploadState=PENDING_UPLOAD`, `syncId` generado en cliente, y se añade al outbox.
2. Al reconectar: el cliente pide al backend una **subida** (multipart al endpoint de
   attachments existente, o presigned PUT a R2), obtiene el `r2Key`, actualiza el meta a
   `UPLOADED` y lo sincroniza. El binario local se conserva en la caché.

**Descarga / caché**
- Los metadatos se sincronizan siempre; el binario se baja **on-demand** vía la ruta existente
  `GET …/attachments/{aid}/download` (302 a presigned URL) y se cachea en `blobCache`.
- **Evicción LRU** con tope de tamaño configurable (p. ej. 2 GB) para no replicar todo R2 en
  cada equipo.

**Consistencia**: si un piece se borra (tombstone), sus binarios locales se marcan para
evicción; el borrado en R2 lo sigue haciendo el backend (best-effort, como hoy).

## 9. Cambios concretos en el backend

> Todos respetan los estándares del repo (constructor injection, DTOs, `@Transactional`,
> Javadoc en tipos públicos, tests JUnit 5 + Mockito).

1. **Migración de esquema** (Hibernate `ddl-auto=update` + script idempotente de backfill):
   - Añadir `sync_id CHAR(36)` único y `rev BIGINT` (indexado) a: `piece`, `location`,
     `piece_type`, `piece_type_attribute`, `organization_piece_attribute`, `piece_attachment`.
     (Las tablas de *valores* de atributos no necesitan `sync_id`: viajan dentro del agregado
     `Piece`.)
   - Backfill: `sync_id = UUID()` para filas existentes; `rev` inicial = secuencia por org.
2. **`org_change_sequence`** (tabla) + componente `OrgChangeSequenceService` que entrega el
   siguiente `rev` por org dentro de la transacción de mutación.
3. **`sync_mutation`** (tabla de idempotencia) + servicio de deduplicación.
4. **`SyncController`** (módulo nuevo `modules/sync/`):
   - `GET /organizations/{slug}/sync/changes` (pull con checkpoint).
   - `POST /organizations/{slug}/sync/mutations` (push idempotente, transaccional, batched).
   - Reutiliza la autorización por rol de org ya existente.
5. **Mappers** entre entidades JPA y los **DTO de documento de sync** (agregado de piece con
   sus valores de atributos embebidos). No exponer entidades JPA directamente (estándar del
   repo).
6. **Estampado de `rev`**: hook en las rutas de escritura (las existentes y las de sync) para
   incrementar el CSN y fijar `rev`. Centralizar en la capa de servicio para no duplicar.

> Las escrituras vía la **API REST actual** (web online) también deben estampar `rev`/`syncId`,
> de modo que web y escritorio convergen al mismo modelo y un cambio hecho en la web aparece en
> el siguiente pull del escritorio. Esto se hace en la capa de servicio compartida.

## 10. Autenticación y sesión offline

El backend ya tiene **refresh tokens con rotación, familias y reuse-detection** (TTL 7 días /
30 con *remember-me*), y access tokens de 15 min. Eso resuelve la mayor parte del problema.

Retos específicos del escritorio (detallados en el companion de frontend):
- Hoy `useApi` depende de **cookies httpOnly** puestas por el **servidor Nuxt** (proxy `/api`).
  En la SPA de escritorio **no hay servidor Nuxt** → la app habla **directo** con el backend y
  guarda el **refresh token en el keychain del SO** (vía plugin seguro de Tauri), no en cookie.
  El backend debe **aceptar el refresh token por body/header** además de por cookie
  (pequeño añadido al endpoint `/auth/refresh`), y **CORS** debe permitir el origin de la app
  (`tauri://localhost` / esquema custom).
- **Desbloqueo offline**: con un access token caducado y sin red, la app sigue funcionando
  contra RxDB; al recuperar red, refresca el token. Si el refresh token también caducó
  (>7/30 días sin conexión), se exige re-login (los cambios locales en outbox se conservan y se
  envían tras autenticar).
- **Cifrado en reposo** de la base local (§11).

## 11. Seguridad

- **DB local cifrada**: la base RxDB/IndexedDB del WebView contiene datos de la organización en
  el portátil del usuario → cifrar en reposo. Opciones: storage cifrado de RxDB con clave
  derivada, o SQLite+SQLCipher si se migra el storage. La **clave** se guarda en el **keychain
  del SO** (Keychain en macOS, Credential Manager en Windows) vía plugin de Tauri, nunca en
  disco en claro.
- **Tokens** en keychain, no en `localStorage`.
- **CORS y CSP**: permitir solo el origin de la app de escritorio y el dominio web; mantener la
  CSP estricta que ya define `nuxt.config.ts`.
- **Permisos de Tauri**: allowlist mínima (FS limitado a `appLocalDataDir`, http solo al
  backend, sin shell).
- **Firma y notarización**: firmar el binario en Windows (Authenticode) y notarizar en macOS;
  el updater de Tauri exige binarios firmados.

## 12. Observabilidad y operación

- Métricas de sync en el cliente (pendientes en outbox, último pull/push OK, conflictos) →
  Sentry (ya integrado en el frontend) con breadcrumbs.
- En el backend: logs estructurados de `/sync/*` (parametrizados, SLF4J), contador de
  conflictos y de mutaciones duplicadas; alertas si el CSN no avanza.
- **Auto-update** de la app vía updater de Tauri (canal estable + endpoint de releases).

## 13. Roadmap por fases (revisado)

| Fase | Entregable | Backend | Frontend/escritorio |
|------|-----------|---------|---------------------|
| **F0** | App de escritorio arranca en Win/Mac con la UI actual | — | Tauri 2 + Nuxt SPA target + pipeline firma/notarización/updater |
| **F1** | Lectura offline | `syncId`+`rev`+CSN, `GET /sync/changes`, mappers | RxDB + pull + render desde RxDB + caché de adjuntos |
| **F2** | Escritura offline | `POST /sync/mutations` idempotente, LWW, validaciones | outbox + push + UUID cliente + estados de sync en UI |
| **F3** | Robustez producción | tabla idempotencia endurecida, métricas, permisos | cifrado en reposo, refresh offline, backpressure, reintentos |
| **F4** | Bonus / evolución | merge por campo (opcional) usando `PieceHistory` | misma SPA como **PWA** (offline en web reusando la capa de sync) |

## 14. Decisiones abiertas / riesgos

1. **Origen de identidad doble (`id` Integer + `syncId`)**: ¿exponemos `syncId` en la API REST
   actual también, o solo en `/sync/*`? Recomendado: exponerlo en ambos para converger web y
   escritorio.
2. **CSN por org vs secuencia global**: por-org acota el pull al tenant (mejor a escala) pero
   añade una fila de bloqueo por org. Global es más simple para el MVP. Recomendado: por-org.
3. **Storage RxDB**: Dexie/IndexedDB (cero nativo, portable a PWA) vs SQLite nativo de Tauri
   (mejor para datasets grandes y cifrado con SQLCipher). Recomendado: **Dexie en F1–F2**,
   reevaluar SQLite en F3 si el volumen lo exige.
3. **Tamaño de datasets por org**: si una org tiene cientos de miles de pieces, replicar todo
   localmente puede ser caro. Mitigación futura: sync parcial por filtros (location, tipo) o
   *lazy partial replication*. Fuera del MVP.
4. **Migración de `useApi`**: la web seguirá con cookies httpOnly vía proxy Nuxt; el escritorio
   usará bearer + keychain. Hay que **abstraer la estrategia de auth** en `useApi` según target
   (web vs Tauri) sin duplicar lógica de refresh.

---

### Apéndice A — Inventario real del backend (verificado en código)

- PKs `Integer` `GenerationType.AUTO` en todas las entidades de dominio; `PieceHistory` usa
  `Long`.
- Soft-delete (`deletedAt` + Hibernate `@SQLRestriction`) en: piece, location, piece_type,
  piece_type_attribute, organization_piece_attribute, piece_attachment, organization,
  organization_member, user.
- `createdAt`/`updatedAt` (`@CreationTimestamp`/`@UpdateTimestamp`) en las entidades de dominio;
  **las tablas de valores de atributos NO tienen timestamps ni soft-delete** → por eso viajan
  embebidas en el agregado `Piece`.
- **Sin** columna `@Version` en ninguna entidad → se introduce `rev`.
- Auth: access token 15 min (`JWT_EXPIRATION_TIME`), refresh tokens en tabla `refresh_tokens`
  con `familyId`, rotación, `REUSE_DETECTED`, TTL 7/30 días, cookie `stocka_refresh` (path
  `/auth`).
- Idempotencia actual: derivada de contenido para Resend (no hay tabla genérica) → se introduce
  `sync_mutation` para el push.
- Paginación: offset-based (`Pageable`), sin cursores → el sync introduce cursor por `rev`.
