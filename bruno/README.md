# Stocka Auth API (Bruno)

Orden recomendado para probar:

1. `stock-auth/00-public/hello`
2. `stock-auth/10-auth/signup-user`
3. `stock-auth/10-auth/login-user`
4. `stock-auth/30-admins/create-admin-owner`
5. `stock-auth/20-users/get-me-owner`
6. `stock-auth/20-users/get-all-users-owner`
7. `stock-auth/20-users/get-all-users-admin`
8. `stock-auth/30-admins/create-admin-admin-forbidden`

## Flujo del dominio (locations / piece-types / pieces / attachments / history)

1. `organizations/10-organizations/create-organization` — captura `orgId`.
2. `organizations/30-invitations/create-invitation` con `role=SPECTATOR` (nuevo rol disponible).
3. `organizations/50-locations/create-location-root` — captura `locationId`.
4. `organizations/60-piece-types/create-piece-type` — captura `pieceTypeId` y `attributeId`.
5. `organizations/70-pieces/create-piece-pending` — sin valores → status `PENDING`.
6. `organizations/70-pieces/update-piece-set-values` — completar valores → status `ACTIVE`.
7. `organizations/70-pieces/attachments/upload-image` — sube una imagen (jpg/png/webp/gif, max 25 MB). En dev queda en `/tmp/stocka-r2/`.
8. `organizations/70-pieces/attachments/upload-document` — sube un documento (cualquier formato, max 100 MB).
9. `organizations/70-pieces/attachments/download-attachment` — devuelve `302` a la presigned URL.
9b. `organizations/70-pieces/attachments/upload-audio` — sube un audio (mp3/wav/ogg/flac/m4a, max 50 MB) con `durationMs` opcional; `get-attachment-content` devuelve los bytes a través del backend (lo usa el editor de timeline).
10. `organizations/70-pieces/get-piece-history` — entradas: `PIECE_CREATED`, `ATTRIBUTE_VALUE_CHANGED`, `STATUS_CHANGED`, `ATTACHMENT_ADDED`, etc.

Timelines (líneas de tiempo, mismos permisos que pieces):

- `organizations/66-timelines/create-timeline` — captura `timelineId`. Nombre único por organización.
- `organizations/66-timelines/list-timelines` / `get-timeline` / `update-timeline` / `delete-timeline`.
- `organizations/66-timelines/get-scene` / `put-scene` — documento del Timeline Editor (tablero +
  capas + clips) como JSON versionado. `put-scene` usa concurrencia optimista (`version`).

Timeline Editor (ventana nueva):

- `auth/handoff` (con `JWTtoken`) → devuelve un `ticket`; cópialo a la var `handoffTicket`.
- `auth/handoff-exchange` → canjea el `ticket` por una sesión (igual que login).

Puertos, catálogo y acciones (contrato con el reproductor del belén; función privada: solo en
organizaciones cuyo owner es ADMIN global):

1. `contract/get-catalog` — kinds, acciones, parámetros y placa `rpi4b`. Cualquier usuario autenticado.
2. `organizations/60-piece-types/create-piece-type-with-kind` — tipo con `kind` (p. ej. `led_segment`); captura `pieceTypeId`.
3. `organizations/68-ports/create-port-led-strip` — tira (infraestructura, sin tipo): pin WS281x + `config.dma/channel/length`.
4. `organizations/68-ports/create-port-led-segment` — segmento sobre la tira por nombre, `first/last` dentro de la tira.
5. `organizations/68-ports/create-port` (relé), `create-port-motor`, `create-port-audio`, `create-port-button` — un ejemplo por kind.
6. `organizations/62-piece-type-actions/create-action` — acción `animate` validada contra el catálogo del kind del tipo.
7. Rechazos: `create-port-kind-mismatch`, `create-port-segment-out-of-range`, `create-port-pin-conflict`,
   `create-action-not-in-catalog`, `create-action-duration-mismatch`, `update-piece-type-kind` sobre un tipo con acciones incompatibles.

El catálogo (`src/main/resources/contract/catalog.json`) es una copia literal de `rpi4-system/contract/catalog.json`;
si cambia allí, hay que copiarlo aquí y al editor (`timeline-editor/app/data/catalog.json`).

Notas:

- `owner@stocka.local / 123456` lo crea el `AdminSeeder` al arrancar la app.
- Si ejecutas dos veces `signup-user` o `create-admin-owner`, puede fallar por email duplicado (esperado).
- En dev, R2 usa el fallback local (`stocka.r2.use-local=true`); para usar Cloudflare R2 real, define `R2_USE_LOCAL=false` y `R2_BUCKET / R2_ENDPOINT / R2_ACCESS_KEY / R2_SECRET_KEY`.
- `SPECTATOR` puede consultar todo el contenido de la organización pero no escribir nada.

## Webhooks

- `webhooks/resend-webhook` — POST `/webhooks/resend`. Solo activo cuando `EMAIL_PROVIDER=resend`.
  Verifica la firma Svix; sin firma válida responde `401`. Ver instrucciones dentro del propio `.bru` para pruebas con dominio público (ngrok / tailscale funnel).

## Formato de errores

Todos los errores devuelven `application/problem+json` siguiendo RFC 7807, extendido con un campo `code` estable que el frontend usa para resolver la traducción (`errors.<code>` en `i18n/locales/{es,ca,en}.json`).

Ejemplo de cuerpo (signup con contraseñas distintas):

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Las contraseñas no coinciden.",
  "instance": "/auth/signup",
  "code": "auth.passwords_mismatch",
  "timestamp": "2026-04-30T10:12:33Z"
}
```

Campos:

| Campo       | Obligatorio | Descripción                                                                  |
|-------------|-------------|------------------------------------------------------------------------------|
| `status`    | sí          | HTTP status code                                                             |
| `code`      | sí          | identificador estable `<modulo>.<evento>` (ver tabla más abajo)              |
| `detail`    | sí          | mensaje localizado según `Accept-Language` (es por defecto, también ca y en) |
| `errors[]`  | opcional    | lista por campo en errores de validación (`field`, `code`, `message`)        |
| `params`    | opcional    | parámetros nombrados (p.ej. `{"max": 50}`) para placeholders i18n            |
| `instance`  | opcional    | URI del request                                                              |
| `timestamp` | opcional    | ISO-8601 UTC                                                                 |

### Codes definidos

| Code                                          | Status | Notas                          |
|-----------------------------------------------|--------|--------------------------------|
| `auth.invalid_credentials`                    | 401    | login con credenciales malas   |
| `auth.email_not_verified`                     | 403    |                                |
| `auth.passwords_mismatch`                     | 400    | signup / cambio de contraseña  |
| `auth.unauthenticated`                        | 401    | token ausente, inválido, expirado |
| `auth.forbidden`                              | 403    | sin permiso de rol             |
| `users.email_taken`                           | 409    |                                |
| `users.username_taken`                        | 409    |                                |
| `users.username_invalid`                      | 400    |                                |
| `users.username_reserved`                     | 400    |                                |
| `pieces.not_found`                            | 404    |                                |
| `piece_types.not_found`                       | 404    |                                |
| `piece_types.attribute_invalid`               | 400    |                                |
| `piece_types.kind_invalid` / `kind_change_invalid` / `kind_in_use` | 400 / 400 / 409 | `kind` fuera del catálogo; cambio de kind con acciones o puertos incompatibles |
| `piece_types.action_not_in_catalog` / `action_param_unknown` / `action_param_missing` / `action_param_type_mismatch` / `action_param_options_invalid` / `action_param_static_invalid` / `action_duration_param_mismatch` | 400 | acción de un tipo con kind validada contra el catálogo |
| `ports.kind_required` / `kind_invalid` / `kind_immutable` | 400 | el kind es obligatorio e inmutable |
| `ports.piece_type_required` / `piece_type_kind_mismatch` | 400 | tipo obligatorio salvo infraestructura; su kind debe coincidir |
| `ports.pin_invalid` / `pin_reserved` / `pin_not_allowed` / `pin_conflict` | 400 / 400 / 400 / 409 | GPIO fuera de rango, reservado (0, 1), kind sin pin, pin ya usado (incluidos los del motor) |
| `ports.config_unknown_key` / `config_missing_key` / `config_type_mismatch` / `config_out_of_range` / `motor_pins_not_distinct` | 400 | config validada por kind contra el catálogo |
| `ports.strip_pin_unsupported` / `strip_channel_mismatch` | 400 | tira en pin no WS281x o canal incoherente |
| `ports.segment_strip_not_found` / `segment_out_of_range` / `strip_shrink_breaks_segment` / `strip_in_use` | 400 / 400 / 400 / 409 | segmentos y tiras |
| `locations.not_found`                         | 404    |                                |
| `organizations.not_found`                     | 404    |                                |
| `organizations.invitation_limit_reached`      | 400    | usa `params.max`               |
| `organizations.invitation_expired`            | 410    |                                |
| `storage.r2_unavailable`                      | 503    |                                |
| `upload.too_large`                            | 413    |                                |
| `upload.invalid_kind`                         | 400    |                                |
| `validation.required` / `validation.failed`   | 400    | bean validation                |
| `request.malformed_body` / `request.not_found`| 400 / 404 | binding del request         |
| `server.internal_error`                       | 500    | catch-all                      |
| `legacy.<status>`                             | -      | puente para `ResponseStatusException` no migrado; el `detail` mantiene el texto en español original |

### Negociación de idioma

El frontend envía `Accept-Language: es | ca | en`. El backend resuelve el locale con `AcceptHeaderLocaleResolver` (default `es`) y traduce el `detail` a partir de `messages_<locale>.properties`. Si el code no tiene traducción, cae a `errors.generic.<status>` y, en último recurso, al code literal.
