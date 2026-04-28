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
10. `organizations/70-pieces/get-piece-history` — entradas: `PIECE_CREATED`, `ATTRIBUTE_VALUE_CHANGED`, `STATUS_CHANGED`, `ATTACHMENT_ADDED`, etc.

Notas:

- `owner@stocka.local / 123456` lo crea el `AdminSeeder` al arrancar la app.
- Si ejecutas dos veces `signup-user` o `create-admin-owner`, puede fallar por email duplicado (esperado).
- En dev, R2 usa el fallback local (`stocka.r2.use-local=true`); para usar Cloudflare R2 real, define `R2_USE_LOCAL=false` y `R2_BUCKET / R2_ENDPOINT / R2_ACCESS_KEY / R2_SECRET_KEY`.
- `SPECTATOR` puede consultar todo el contenido de la organización pero no escribir nada.
