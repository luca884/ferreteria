# ip-del-cliente-en-railway

Objetivo: que en Railway el límite de intentos del login cuente por la IP real del cliente. · Por qué: el borde nuevo de Railway llega a Caddy desde muchas IPs (`100.64.0.x` y, detrás, CDN77) y Caddy toma la de ese salto, así que el límite por IP y el de email + IP no frenan a nadie.
Rama: `fix/ip-del-cliente-en-railway` desde `098a84d1` · Entrega: single-pr · Runner: sin runner para el Caddyfile (Caddy en Docker con un backend eco; prueba en producción con el chequeo del README) · Mirror Engram: `odd/ip-del-cliente-en-railway/tasks` (ok) · Modelo: caso 5 (alto riesgo: seguridad), Opus de punta a punta
Pronóstico: ~30 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: `frontend/Caddyfile`, el deploy del frontend en Railway y el README (Deploy). · Fuera de alcance: el backend (`LimiteDeIntentos`, `RemoteIpValve`), los umbrales del límite y el TOTP del super admin.
Restricciones: la IP del cliente sale solo de lo que pone el borde de Railway: lo que mande el cliente en `X-Real-IP` o `X-Forwarded-For` no cuenta. Según Railway (docs.railway.com/networking/public-networking/specs-and-limits), el borde manda «`X-Real-IP` for identifying client's remote IP».

- S1: «¿Arreglo el límite del login en una rama `fix/` y lo vuelvo a subir?» → Luca: «Dale» — Aceptación: Caddy toma la IP del cliente del `X-Real-IP` que pone el borde de Railway (`100.64.0.0/10` es de confianza por defecto) y se la pasa al backend en `X-Real-IP`. Si el pedido no viene de un proxy de confianza, cuenta la dirección de la conexión y no lo que mande el cliente — Checks: Caddy en Docker con un backend eco (antes y después); `caddy validate`.
- S2: «quiero subir de nuevo a railway todo est poryecto, hacelo» — Aceptación: el frontend con el arreglo, desplegado en Railway. Chequeo del README: 21 logins seguidos con `X-Real-IP` y `X-Forwarded-For` falsos, uno distinto cada vez, terminan en 429, y el WARN del bloqueo muestra la IP pública de quien prueba — Checks: `curl` contra producción y `railway logs --service backend`.
- S3: el README (Deploy) describe cómo se calcula la IP del cliente y el chequeo de después del deploy — Aceptación: el párrafo del frontend coincide con el Caddyfile nuevo — Checks: lectura.

## Tasks

- [ ] T1 · S1 · ruta: inline · Aceptación: con el Caddyfile de `main`, un proxy de confianza que manda `X-Real-IP: 203.0.113.7` llega al eco con la IP del proxy (RED). Con el nuevo llega `203.0.113.7`, y un cliente que no es de confianza llega con su dirección aunque mande `X-Real-IP` (GREEN) · Evidencia: — · Risk: item 2 (seguridad), item 6 (no hay test automático del Caddyfile)
- [ ] T2 · S2 · ruta: inline · Aceptación: `railway up ./frontend` con «Deploy complete»; 21 logins falsos → el último da 429 y el WARN muestra la IP pública · Evidencia: — · Risk: item 2, item 5 (deploy)
- [ ] T3 · S3 · ruta: inline · Aceptación: README → Deploy → Frontend al día · Evidencia: — · Risk: none

## Log

- L1 (2026-11-07) Pedido original, textual:
  > quiero subir de nuevo a railway todo est poryecto, hacelo necesitas algun permiso mio??

  Después del deploy (MySQL, backend y frontend en SUCCESS desde `098a84d1`), el chequeo del README falló y propuse: «¿Arreglo el límite del login en una rama `fix/` y lo vuelvo a subir?». Luca:
  > Dale
- L2 (2026-11-07) Exploración:
  - En producción, 21 logins con `X-Real-IP` y `X-Forwarded-For` falsos dan 21×401. Sin headers falsos, 7 fallos del mismo email también dan 7×401.
  - Con 52 fallos de un email, el WARN de `LimiteDeIntentos` (`backend/src/main/java/com/ferreteria/auth/service/LimiteDeIntentos.java:120`) muestra `100.64.0.18`, `.9`, `.19` y `.20`: el borde de Railway (`server: railway-hikari`, `x-hikari-trace: gru1`). Caddy no confía en él (`private_ranges` no incluye `100.64.0.0/10`) y toma la dirección de la conexión.
  - Con `PROXIES_DE_CONFIANZA=private_ranges 100.64.0.0/10`, puesto en el frontend por variable, el WARN muestra `46.151.194.130`. Es CDN77 São Paulo (AS60068), el salto anterior que `X-Forwarded-For` lee de derecha a izquierda, y también rota.
  - `frontend/Caddyfile:11-14` arma `{client_ip}` desde `X-Forwarded-For` y `frontend/Caddyfile:32` lo pone en `X-Real-IP`. Viene de S3 de `revision-seguridad-medios` (2026-11-05), que dejó pendiente «confirmar en Railway (cuando vuelva) qué IP ve Caddy del borde».
  - Railway documenta `X-Real-IP` como la IP del cliente. Caddy puede leer la IP de ahí con `client_ip_headers`, solo si el pedido viene de un proxy de confianza.
