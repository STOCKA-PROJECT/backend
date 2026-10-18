# Conectar un asistente a Stocka (MCP)

Stocka expone un **servidor MCP** (Model Context Protocol) en `https://stocka.xeye.es/mcp`. Cualquier
asistente compatible (Claude.ai, Claude Desktop, Claude Code, Cursor, ChatGPT…) puede consultar y
editar tus artículos, tipos, ubicaciones, contactos y timelines **con tu propia cuenta y tus permisos**.
Cada persona autoriza su asistente por separado; nadie puede ver más de lo que ve en la web.

## Cómo conectarlo

1. **Claude.ai** (web, escritorio, móvil): Configuración › Conectores › *Añadir conector personalizado*.
   Nombre libre, URL `https://stocka.xeye.es/mcp`. Al activarlo se abre la pantalla de inicio de
   sesión de Stocka.
2. **Claude Code** (terminal):
   ```bash
   claude mcp add --transport http stocka https://stocka.xeye.es/mcp
   ```
   La primera vez que uses una herramienta se abrirá el navegador para autorizar.
3. Otros clientes: usa la misma URL. Si el cliente pide "client id", déjalo vacío: Stocka admite
   registro dinámico (RFC 7591) y Client ID Metadata Documents, los dos mecanismos del estándar MCP.

La URL y el comando también están en **Mi cuenta › Conecta un asistente**.

## La pantalla de autorización

Inicias sesión con tu correo y contraseña (y el código de verificación en dos pasos si lo tienes
activado). Después ves qué aplicación pide acceso, a qué dirección volverá y los **permisos** que
concedes:

| Permiso | Qué permite |
|---|---|
| `read` | Leer artículos, tipos, ubicaciones, contactos, timelines y puertos de tus organizaciones |
| `inventory:write` | Crear y editar artículos, tipos y atributos, ubicaciones, contactos y adjuntos; importar |
| `timelines:write` | Crear y editar timelines y sus escenas |
| `hardware:write` | Puertos y acciones de tipo (solo organizaciones con hardware) |
| `org:manage` | Miembros, invitaciones y reindexado de la búsqueda (desmarcado por defecto) |
| `delete` | Borrar artículos, tipos, ubicaciones, contactos, timelines y adjuntos (desmarcado por defecto) |

Tu **rol en cada organización** se aplica igualmente: un SPECTATOR no puede escribir aunque conceda
`inventory:write`. Las aplicaciones que se identifican con un documento de metadatos de un host de
confianza (por ejemplo `claude.ai`) aparecen como *verificadas*.

## Qué puede hacer el asistente

- Buscar artículos describiéndolos («pastor dormido con sombrero»): la búsqueda es semántica cuando
  la organización tiene índice (igual que en la web).
- Crear y actualizar artículos indicando los atributos por nombre, mover artículos de ubicación,
  revisar el historial, exportar e importar CSV (primero en modo simulación).
- Gestionar tipos y atributos, ubicaciones y contactos.
- Crear una timeline a partir de una descripción: coloca artículos, añade clips con las acciones
  de cada tipo y valida el resultado contra el catálogo de hardware. Lo que crea el asistente se
  abre tal cual en el editor de timelines, y viceversa.
- En organizaciones con hardware: puertos y acciones de tipo.

Pídele al asistente que empiece por la herramienta `me` y lea la guía (`stocka://guide`): así conoce
tus organizaciones y las reglas de uso.

## Revocar el acceso

En **Mi cuenta › Aplicaciones conectadas** ves cada aplicación autorizada, sus permisos, cuándo se
autorizó y cuándo se usó por última vez. *Revocar* corta el acceso de inmediato: la aplicación tendrá
que volver a pedir permiso. Cambiar tu contraseña también invalida los accesos concedidos antes.

Cada autorización y cada revocación quedan registradas en **Actividad de seguridad**.

## Límites y detalles técnicos

- Los tokens de acceso duran 30 minutos; el asistente los renueva solo (hasta 30 días sin volver a
  iniciar sesión, 90 días como máximo desde la autorización).
- 120 llamadas a herramientas por minuto y persona.
- Las escenas de timeline se editan con operaciones (añadir pieza, añadir clip, mover…) y control de
  versión: si alguien guarda desde el editor a la vez, el asistente recarga y reintenta.
- Para desarrollo local el backend genera una clave de firma efímera; en producción la clave vive en
  `stocka.env` (`OAUTH_JWK_PRIVATE_KEY`). `MCP_ENABLED=false` apaga el servidor MCP sin tocar nada más.
- El backend va detrás de Caddy (TLS en el proxy): `server.forward-headers-strategy=framework` hace que
  las redirecciones absolutas (login, consentimiento) salgan como `https://stocka.xeye.es/...`; sin ello
  salen en `http://` y solo funcionan gracias a la redirección 308 y al HSTS de Caddy.
- Especificación: https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization.
