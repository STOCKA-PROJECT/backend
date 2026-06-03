# Stocka Offline-Sync — Decisiones cerradas y auditoría de fallos

> Estado: **revisión de diseño** · Rama: `claude/desktop-app-offline-sync-xFg76`
> Complementa a `backend/docs/offline-sync/DESIGN.md` (diseño autoritativo) y
> `frontend/docs/offline-sync/DESIGN.md`. Este documento (1) resuelve los puntos abiertos del
> §14 del diseño y (2) audita los modos de fallo del sistema, con severidad, disparador,
> mitigación y fase. Las etiquetas `[BACKEND]` / `[FRONTEND]` indican dónde se mitiga.

---

# Parte 1 — Resolución de los puntos abiertos

## D1. ¿Exponer `syncId` también en la API REST actual? → **SÍ**

**Decisión**: el `syncId` (UUID) se asigna **en la creación, en cualquier canal**:
- creación **online vía REST** → lo asigna el **servidor** (`@PrePersist` / default de columna);
- creación **offline** → lo genera el **cliente** (`crypto.randomUUID()`) y el servidor lo respeta
  en el push.

Y se **devuelve en todos los DTOs** de las entidades sincronizables.

**Porqué**: web y escritorio deben converger a un único modelo de identidad. Si la web crea un
piece sin `syncId`, ese piece "no existe" para el cliente offline hasta que reciba uno → caos de
correlación. Asignándolo siempre en creación, un cambio hecho en la web aparece en el siguiente
pull del escritorio con identidad estable. Coste: añadir el campo a DTOs y un `@PrePersist`.
Beneficio extra: deep-links e idempotencia por `syncId` también en la web/PWA futura.

**Invariante**: `syncId` es **inmutable** tras la creación y **único global** (no solo por org),
para que un `syncId` no pueda saltar de organización.

## D2. CSN por organización vs secuencia global → **Contador por org con bloqueo de fila**

**Decisión**: una tabla `org_change_sequence(organization_id PK, value BIGINT)` y, **dentro de
la misma transacción** que cada mutación, `SELECT … FOR UPDATE` + `value = value + 1`, estampando
ese valor en la columna `rev` de la fila modificada.

**Porqué descartamos el changelog con `AUTO_INCREMENT` global** (que parecía más escalable):
con `AUTO_INCREMENT`, una transacción que obtiene un `seq` **mayor** puede **commitear antes**
que otra con `seq` menor aún en vuelo. Un cliente que avanza su checkpoint hasta el `seq` mayor
**se salta para siempre** el cambio de `seq` menor cuando este commitee después (es el problema
clásico de visibilidad de cursores CDC sobre auto-increment). El contador con `FOR UPDATE`
mantiene el bloqueo hasta el commit, por lo que **el orden de asignación de `rev` == orden de
commit**: cursor monótono, reanudable y **sin huecos saltables**.

**Coste aceptado**: serializa las escrituras *dentro de una misma org* (no entre orgs). Para el
patrón de uso de Stocka es asumible. Si una org llegara a ser un hotspot de escritura, se
reevalúa (sharding del contador, o changelog con marca de agua de seguridad).

## D3. Storage RxDB: Dexie vs SQLite → **Dexie en F1–F2; SQLite+SQLCipher en F3 si se exige cifrado fuerte**

**Decisión**: empezar con **Dexie/IndexedDB** (cero plugins nativos, portable a PWA en F4).
Pero con una advertencia de seguridad que condiciona F3:

- IndexedDB en el WebView se almacena **en disco sin cifrar**. El plugin de cifrado de RxDB
  cifra **valores de campos**, pero **no los índices ni las claves** → datos sensibles que estén
  indexados (`name`, `serialNumber`) **se filtrarían en claro** ante un portátil robado.
- Si el requisito de "cifrado en reposo" es duro (datos de organización en equipos de campo),
  en **F3 se migra el storage a SQLite + SQLCipher** (cifrado de DB completa) vía plugin de
  Tauri, con la clave en el keychain del SO.

**Regla**: no indexar en claro campos sensibles bajo Dexie; tratar el cifrado fuerte como
entregable de F3, no de F1.

## D4. Estrategia de auth dual (web cookie vs escritorio bearer) → **Abstracción en `useApi`, una sola lógica de refresh**

**Decisión**: `useApi` expone una **estrategia de credenciales** inyectable por target:
- **web**: cookies httpOnly + proxy Nuxt `/api` (como hoy).
- **escritorio**: base URL absoluta + `Authorization: Bearer` + refresh token en **keychain**.

La **lógica de refresh con mutex anti-estampida** existente **no se duplica**: solo se
parametriza *de dónde* se leen/escriben las credenciales. El backend acepta el refresh token por
**header/body** además de por cookie y habilita **CORS** para el origin de Tauri.

---

# Parte 2 — Auditoría de modos de fallo

Severidad: **Crítico** (corrupción/pérdida de datos o bloqueo) · **Alto** (degradación seria /
inconsistencia visible) · **Medio** · **Bajo**.

## A. Integridad referencial y orden de aplicación

### R1 — `[BACKEND]` Las queries de sync filtran los borrados por `@SQLRestriction` · **Crítico**
Todas las entidades usan `@SQLRestriction("deleted_at IS NULL")`. Si `/sync/changes` lee con los
repositorios normales, **nunca verá las filas borradas** → el cliente **no se entera de los
borrados** y mantiene fantasmas para siempre.
**Mitigación**: las consultas de sync deben **leer también los borrados** (query nativa o entidad
sin restricción / `@Filter` desactivable). Los tombstones (`deletedAt != null`) son parte del
flujo de pull. **Test obligatorio**: borrar en server → el pull lo entrega.

### R2 — `[BACKEND]` `rev` estampado por bulk-update no dispara `@UpdateTimestamp`/historial · **Alto**
Si se estampa `rev` con un `UPDATE` masivo o nativo, los callbacks de Hibernate
(`@UpdateTimestamp`, registro en `PieceHistory`) **no se ejecutan** → `updatedAt` y la auditoría
quedan inconsistentes.
**Mitigación**: estampar `rev` en la **capa de servicio** dentro del flujo de persistencia normal
(no por bulk), o recalcular timestamps/historial explícitamente.

### R3 — `[BACKEND][FRONTEND]` Dependencia circular: `piece.coverAttachment` ↔ `attachment.piece` · **Alto**
Un `Attachment` tiene FK a `piece` (necesita que el piece exista), pero `piece.coverAttachmentId`
apunta al attachment (necesita que el attachment exista). Creados ambos offline, **ningún orden
de push los satisface a la vez**.
**Mitigación**: push en **dos fases** — (1) upsert del piece **sin** cover, (2) upsert del
attachment, (3) patch del piece fijando `coverAttachmentSyncId`. El motor de outbox debe modelar
esta dependencia diferida.

### R4 — `[FRONTEND]` Referencias colgantes transitorias entre lotes de pull · **Medio**
Un `piece` puede llegar en un lote referenciando un `pieceTypeSyncId`/`locationSyncId` aún no
materializado (edición que sube el `rev` del piece por encima del de su tipo).
**Mitigación**: la UI **tolera refs colgantes** (placeholder "cargando…"/nombre diferido) y se
resuelven en el siguiente pull; nunca romper el render por una ref ausente. Aplicar cambios en
orden de dependencia *dentro* del lote reduce, pero no elimina, el caso.

### R5 — `[BACKEND]` Push parcial de un lote con dependencias · **Alto**
Si en un lote la `location` falla validación pero el `piece` que la referencia "tiene éxito", se
crea un piece con FK lógica rota.
**Mitigación**: agrupar por dependencia; si una dependencia padre es `rejected`, **no aplicar**
los hijos del mismo lote (devolver `rejected` en cascada con `errorCode=dependency_failed`). El
array de `results` reporta por mutación.

## B. Conflictos y pérdida de datos (LWW)

### R6 — `[BACKEND][FRONTEND]` LWW pierde ediciones concurrentes de campos distintos · **Alto (aceptado)**
Dos usuarios editan offline el mismo piece (uno el `name`, otro el `status`). El segundo push
sobrescribe el documento completo → se pierde el cambio del primero aunque tocaran campos
distintos.
**Mitigación F2**: aceptado por diseño (LWW). **Mitigación F4 opcional**: *merge por campo*
usando el `baseRev` para hacer 3-way merge campo a campo, apoyándose en `PieceHistory`. El
contrato de transporte ya lleva `baseRev`, así que no rompe compatibilidad. Mientras tanto:
**registrar en `PieceHistory` toda sobrescritura por conflicto** para que sea auditable/reversible.

### R7 — `[BACKEND]` Resurrección de tombstones (delete vs update) · **Crítico**
Server tiene el piece **borrado** (`rev=N`). Un cliente que no vio el borrado pushea un `upsert`
con `baseRev<N`. Un LWW ingenuo ("la última escritura gana") **resucitaría** un registro borrado.
**Regla explícita**: el **borrado es pegajoso**. `upsert` con `baseRev` anterior a un `deletedAt`
del servidor → `status=rejected (errorCode=deleted_upstream)`; el cliente marca su copia como
borrada y avisa. Solo un `upsert` con `baseRev >= rev_del_borrado` (es decir, el cliente **vio**
el borrado y deliberadamente recrea) puede "des-borrar". Simétricamente, un `delete` offline vs
un `update` server más nuevo se resuelve por `rev` con borrado preferente salvo edición posterior
explícita.

### R8 — `[BACKEND][FRONTEND]` Tormenta de sincronización por mutación rechazada · **Alto**
Una mutación `rejected` (validación) re-encolada se reintenta en bucle infinito → consumo y ruido.
**Mitigación**: los `4xx`/`rejected` **no se reintentan**; van a un **dead-letter** local tras
N intentos, se revierte el cambio local y se **notifica al usuario** (toast + bandeja de
conflictos). Backoff solo para red/`5xx`.

## C. Unicidad e invariantes de negocio offline

### R9 — `[BACKEND][FRONTEND]` Colisión de `serialNumber` único por org creado offline · **Alto**
Dos usuarios offline crean pieces con el mismo `serialNumber` (único por org). Ambos pushean; el
segundo viola la unique constraint. **LWW no aplica** (no es conflicto de versión, es colisión de
clave de negocio).
**Mitigación**: el server devuelve `rejected (errorCode=serial_conflict)`; el cliente **conserva
el dato local** (el usuario hizo trabajo real), marca el piece con badge de conflicto y pide
renombrar el serial. `serialNumber` es nullable → no bloquea la creación, solo el duplicado.

### R10 — `[BACKEND]` Colisión de nombres únicos (`piece_type`/`org_attr` por org) offline · **Medio**
Mismo patrón que R9 para `uk_piece_type_org_name`, `uk_org_piece_attr_org_name`,
`uk_piece_type_attr_type_name`. Manejo idéntico: `rejected` + reconciliación en UI.

### R11 — `[BACKEND]` Ciclos en el árbol de locations por moves concurrentes · **Alto**
A mueve L1→bajo L2 offline; B mueve L2→bajo L1 offline. Cada uno válido localmente; juntos forman
un ciclo. LWW sobre moves de árbol puede crear ciclos o inconsistencia.
**Mitigación**: el server **re-valida aciclicidad en cada push** (no confía en la validación
local); el move que cerraría el ciclo → `rejected (errorCode=cycle)`; el cliente reconcilia con el
`serverDoc`.

### R12 — `[BACKEND]` Borrado de location/piece-type que exige "vacío" pero offline se llenó · **Alto**
Un MANAGER borra offline un `piece_type` "sin pieces", pero otro usuario creó offline un piece de
ese tipo. Al sincronizar, el invariante "no borrar tipo con pieces" se viola.
**Mitigación**: revalidar el invariante en el server al aplicar el `delete`; si ya no se cumple →
`rejected (errorCode=not_empty)`; el cliente revierte el borrado local.

## D. Esquema de atributos y validación divergente

### R13 — `[BACKEND][FRONTEND]` Atributo `required` nuevo añadido server-side mientras el cliente está offline · **Alto**
Un MANAGER añade un `PieceTypeAttribute` `required`. Un usuario offline edita un piece de ese tipo
sin ese valor (no lo conocía). Push → validación de `required` falla → `rejected`, pese a que el
usuario no pudo saberlo.
**Mitigación**: la regla `required` **solo bloquea en la creación o cuando el cliente ya conocía
el atributo** (`baseRev` posterior a la creación del atributo). Para pieces preexistentes editados
con un `baseRev` anterior, tratar el `required` faltante como **warning no bloqueante** (el server
acepta y marca el piece como "incompleto"). Evita perder ediciones legítimas por una regla nueva.

### R14 — `[FRONTEND]` Doble implementación de validadores (cliente y servidor) divergen · **Medio**
Para validar offline, el cliente debe replicar la lógica de `validatorsJson` (min/max/regex/
options/`AttributeType`). Dos implementaciones → divergencia → o falsos rechazos locales o
sorpresas en el push.
**Mitigación**: **el servidor es la autoridad**. El cliente hace validación *best-effort* a partir
del mismo `validatorsJson` (que ya se sincroniza), pero cualquier discrepancia se resuelve por el
`rejected` del server. Centralizar la spec de validación en un formato declarativo compartido
reduce la divergencia.

## E. Adjuntos / R2

### R15 — `[BACKEND][FRONTEND]` Binario subido a R2 pero falla el push del metadato (o viceversa) · **Alto**
Orden roto → objeto huérfano en R2, o `attachmentsMeta=UPLOADED` apuntando a un `r2Key`
inexistente.
**Mitigación**: orden estricto **binario→`r2Key`→metadato**; si el metadato falla, reintentar (el
binario ya está). **GC server-side** de objetos R2 no referenciados por ningún metadato vivo (job
periódico). Estado `FAILED` recuperable en el cliente.

### R16 — `[FRONTEND]` URL presignada caducada / sin red para descargar · **Medio**
Se cachea **el binario**, no la URL (ya en diseño). Pero la primera descarga exige red; offline,
un adjunto nunca abierto no está disponible.
**Mitigación**: indicar en UI "disponible al reconectar"; opción de *prefetch* de adjuntos de los
pieces vistos recientemente. Caché LRU con tope configurable.

### R17 — `[FRONTEND]` Subida de 100 MB bloquea la cola de sync de datos · **Medio**
Un documento grande monopoliza el canal y retrasa la convergencia de datos.
**Mitigación**: **colas separadas/priorizadas** — datos primero, binarios en segundo plano con
límite de concurrencia y reanudación.

## F. Autenticación, sesión y permisos

### R18 — `[BACKEND][FRONTEND]` Usuario expulsado de la org / rol degradado mientras edita offline · **Alto**
El usuario es removido o pasa a SPECTATOR; sus mutaciones offline se rechazan en bloque (403).
**Mitigación**: manejar `403` por pérdida de permiso de forma específica: **conservar** el trabajo
local en una bandeja "no sincronizable", explicar el motivo, no perder datos silenciosamente.
El cliente conoce su rol del último pull y **evita encolar** mutaciones no permitidas, pero el
server es autoridad final.

### R19 — `[BACKEND][FRONTEND]` Refresh token caducado tras semanas offline · **Medio**
Refresh TTL 7/30 días. Si el equipo estuvo offline más tiempo, el refresh falla → re-login.
**Mitigación**: re-login **conserva el `outbox`**; tras autenticar, se drena. Avisar con
antelación ("tu sesión caducará, conéctate"). Nunca borrar datos locales al caducar la sesión.

### R20 — `[FRONTEND]` `navigator.onLine` miente (captive portal / backend caído) · **Medio**
Reporta "online" sin alcanzar el backend → intentos de sync que fallan.
**Mitigación**: comprobación real de alcanzabilidad (ping a `/health`) + **circuit breaker** con
backoff antes de declarar online.

## G. Concurrencia local, esquema y versiones

### R21 — `[FRONTEND]` Varias ventanas/instancias Tauri sobre la misma IndexedDB · **Alto**
Sin `multiInstance` + elección de líder (BroadcastChannel), dos ventanas procesan el `outbox` en
paralelo → mutaciones duplicadas.
**Mitigación**: configurar RxDB `multiInstance` con líder; **idempotencia por `mutationId`** como
red de seguridad (R24).

### R22 — `[FRONTEND]` Migración de esquema RxDB rompe la apertura de la DB o tira el `outbox` · **Crítico**
Cambiar el esquema local sin estrategia de migración impide abrir la DB; una migración destructiva
**pierde mutaciones no sincronizadas**.
**Mitigación**: versionar esquemas RxDB con `migrationStrategies`; **prohibido** borrar/recrear la
DB con outbox pendiente. Antes de una migración mayor, **drenar el outbox** (forzar sync) o
preservarlo aparte.

### R23 — `[BACKEND][FRONTEND]` Desfase de versión cliente↔contrato de sync · **Alto**
Un cliente desactualizado (offline semanas) habla con un `/sync` evolucionado, o al revés.
**Mitigación**: **versionar la API** `/sync` (`/sync/v1/...`), incluir `minClientVersion` en la
respuesta; el server mantiene compatibilidad hacia atrás o fuerza upgrade. El updater de Tauri
mitiga pero no cubre equipos muy desactualizados.

### R24 — `[BACKEND]` Reintento tras respuesta perdida re-aplica una operación no idempotente · **Alto**
El push aplicó pero la respuesta se perdió; el cliente reintenta. Los `upsert` por `syncId` son
idempotentes, pero **acciones con efecto secundario** (subida a R2, envío de email) no.
**Mitigación**: tabla `sync_mutation(mutation_id PK, …)`; un `mutationId` repetido → `duplicate`
+ `serverDoc` sin re-ejecutar efectos. **Retención** de la tabla mayor que la ventana máxima de
reintento (definir, p. ej. 30 días) para no re-aplicar reintentos tardíos.

## H. Migración de datos y escala

### R25 — `[BACKEND]` Backfill de `syncId`/`rev` en filas existentes con escrituras vivas · **Alto**
Asignar `syncId=UUID()` y `rev` a millones de filas mientras hay tráfico puede colisionar o dejar
`rev` inconsistentes.
**Mitigación**: backfill en **ventana de mantenimiento** o por lotes idempotentes; columnas
primero `NULL`, backfill, luego `NOT NULL`/`UNIQUE`. Inicializar el CSN por org **por encima** del
máximo `rev` backfillado.

### R26 — `[BACKEND][FRONTEND]` Sync inicial completa de una org enorme · **Medio**
La primera descarga baja todo el dataset → tiempo/memoria.
**Mitigación**: pull paginado con `hasMore` + checkpoint **persistido incrementalmente** y
**reanudable** si se interrumpe. Futuro: replicación parcial por filtros (location/tipo).

### R27 — `[BACKEND]` Crecimiento de la tabla de idempotencia y de `PieceHistory` · **Medio**
El replay de muchas ediciones offline genera mucho historial; la tabla de idempotencia crece sin
fin.
**Mitigación**: prune por antigüedad de `sync_mutation` (respetando R24); el historial es
append-only por diseño, pero registrar el **timestamp de edición del cliente** en `PieceHistory`
(no el de sync) para que la auditoría sea fiel.

## I. Seguridad y privacidad

### R28 — `[FRONTEND]` Dispositivo compartido / varias cuentas → fuga de datos de otra org · **Alto**
Si dos cuentas Stocka usan la app en el mismo equipo, la DB local debe **particionarse por cuenta**
y limpiarse al logout; si no, la cuenta B ve datos de la org de A.
**Mitigación**: namespace de DB por usuario/cuenta; **wipe** (o desmontaje cifrado) al logout;
clave de cifrado por cuenta en keychain.

### R29 — `[FRONTEND]` Datos en claro en IndexedDB ante robo de portátil · **Alto**
Ver D3: Dexie no cifra en reposo; el plugin de RxDB no cifra índices.
**Mitigación**: F3 con SQLite+SQLCipher; entretanto, no indexar en claro campos sensibles y dejar
claro el alcance del riesgo.

### R30 — `[BACKEND]` CORS demasiado abierto para el origin de Tauri · **Medio**
Permitir el bearer desde un origin custom mal configurado abre superficie.
**Mitigación**: allowlist estricta del origin de la app; mantener la CSP de `nuxt.config.ts`;
permisos mínimos de Tauri (FS a `appLocalDataDir`, http solo al backend, sin shell).

## J. Integridad de referencias de usuario

### R31 — `[BACKEND]` `ownerUserSyncId` apunta a un usuario removido de la org o borrado · **Medio**
Asignación de owner offline a un usuario que ya no pertenece a la org.
**Mitigación**: el server valida pertenencia en el push; si no es válido → `rejected` o **null-out**
del owner (decisión de producto), nunca FK rota.

### R32 — `[FRONTEND]` Colisión/duplicación de `syncId` por copia defectuosa de documento · **Bajo**
La colisión de `randomUUID` es despreciable, pero un bug que clone un doc conservando `syncId`
fusionaría dos entidades lógicas en el upsert.
**Mitigación**: asignar `syncId` **solo** en el factory de creación; revisar que duplicar/clonar en
UI genere nuevo `syncId`.

---

# Parte 3 — Cambios al diseño derivados de la auditoría

Estos puntos se incorporan al diseño autoritativo (`DESIGN.md`) en la próxima revisión:

1. **(R1)** Las consultas de `/sync/changes` **deben incluir filas borradas** (bypass de
   `@SQLRestriction`); los tombstones son parte del contrato de pull.
2. **(R7)** Regla explícita **delete-vs-update**: el borrado es pegajoso; `upsert` con `baseRev`
   anterior al borrado → `rejected (deleted_upstream)`, sin resurrección.
3. **(R3)** El outbox modela **dependencias diferidas**: piece sin cover → attachment → patch de
   cover (dos fases).
4. **(R5)** Semántica de **push parcial con rechazo en cascada** por dependencia.
5. **(R13)** `required` **no bloquea** ediciones de pieces preexistentes cuyo `baseRev` es anterior
   a la creación del atributo (se acepta con marca "incompleto").
6. **(R9/R10/R11/R12)** Catálogo de `errorCode` de `rejected`: `serial_conflict`, `name_conflict`,
   `cycle`, `not_empty`, `deleted_upstream`, `dependency_failed`, `permission_denied`,
   `validation_failed`. El cliente mapea cada uno a una acción de reconciliación.
7. **(R24)** `sync_mutation` con política de **retención > ventana de reintento** (p. ej. 30 días).
8. **(R23)** Versionar la API: `/sync/v1/...` + `minClientVersion` en la respuesta.
9. **(R2)** Estampar `rev` en la **capa de servicio**, no por bulk-update, para preservar
   timestamps e historial; aplicar también a las escrituras REST de la web.
10. **(R8)** Bandeja de conflictos/dead-letter en el cliente; `4xx` no se reintentan.

## Resumen de severidad

| Severidad | Riesgos |
|-----------|---------|
| **Crítico** | R1 (tombstones filtrados), R7 (resurrección), R22 (migración RxDB / pérdida de outbox) |
| **Alto** | R2, R3, R5, R6, R8, R9, R11, R12, R13, R15, R18, R21, R23, R24, R25, R28, R29 |
| **Medio** | R4, R10, R14, R16, R17, R19, R20, R26, R27, R30, R31 |
| **Bajo** | R32 |

Los tres **Críticos** y los **Altos** de las secciones A, B y C son condición de salida de la
**F2** (escritura offline): sin ellos resueltos, hay riesgo real de pérdida o corrupción de datos.
