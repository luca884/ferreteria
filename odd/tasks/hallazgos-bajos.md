# hallazgos-bajos

Objetivo: cerrar los hallazgos BAJOS de la revisión de seguridad del 01/11 ([[revision-seguridad-2026-11-01]]) que tienen arreglo sin decisión de producto: 9 (MySQL de desarrollo abierto a la red), 6 (cabeceras de seguridad en Caddy) y 5 (cambio obligatorio de contraseña solo en el frontend). El 7 y el 8 piden una decisión de Luca.
Rama: `fix/hallazgos-bajos` desde `8e8b75c` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / `./mvnw test` / `./mvnw test -Pimagen`; frontend `npx ng test --watch=false --include=<spec>` / `npx ng test --watch=false` / `npx ng build` · Mirror Engram: `odd/hallazgos-bajos/tasks` (pendiente)
Ruteo: caso 5 (alto riesgo: item 2 seguridad, item 5 entorno y deploy) · Pronóstico: ~300 líneas · Estado: en curso

## Specs

Pedido de Luca: «lluego continua con task pequenas o minor pendientes». Los arreglos salen de la revisión de seguridad (sección «Fix» de cada hallazgo).

- S1 (hallazgo 9): "`\"127.0.0.1:3307:3306\"`" en `docker-compose.yml`. Aceptación: desde otra IP de la red el puerto 3307 está cerrado; desde la máquina, el backend dev conecta por `localhost:3307`.
- S2 (hallazgo 6): "un bloque `header` con `Strict-Transport-Security`, `Content-Security-Policy` (`default-src 'self'`, Google Fonts y `frame-ancestors 'none'`), `X-Content-Type-Options nosniff` y `Referrer-Policy`." Aceptación: la imagen del frontend responde con las cuatro cabeceras y la app anda con la CSP puesta (login, mostrador, una hoja imprimible), sin violaciones de CSP en la consola.
  - Supuesto: `style-src` lleva `'unsafe-inline'` (Angular inyecta los estilos de los componentes con `<style>`); `script-src` queda en `'self'`, por eso se apaga `inlineCritical` (el build mete un `<script>` inline que cambia el `media` de la hoja de estilos).
- S3 (hallazgo 5): "con el flag prendido, 403 a todo salvo `GET /api/auth/me` y `PUT /api/auth/password`." Aceptación: un usuario con `debe_cambiar_contrasena` recibe 403 en cualquier otro endpoint; después de cambiarla, el mismo token anda. El login sigue andando.
  - Supuesto: el mensaje del 403 es «Cambiá tu contraseña para seguir.».
- S4 (hallazgos 7 y 8): pendientes de decisión de Luca (arreglar o documentar como riesgo aceptado). No se tocan sin su respuesta.

## Tasks

- [x] T1 · S1 · `docker-compose.yml` con `127.0.0.1:3307:3306` · `docker port` → `127.0.0.1:3307`; `nc` desde la IP de la LAN (192.168.0.244) cerrado, desde 127.0.0.1 abierto; backend dev con health `UP` · Risk: item 5
- [x] T2 · S2 · `frontend/Caddyfile` (cabeceras) + `angular.json` (`inlineCritical: false`) · `ng build` sin `<script>` inline; imagen local (`docker build frontend`, Caddy en 8090 → backend dev) con las cuatro cabeceras en `curl -I` y `/api` 401 sin token; ego-browser con el Dueño en 8 pantallas: 0 violaciones, fondo y Geist aplicados; control: un `<script>` inline inyectado no corre y se registra `script-src-elem inline` · Risk: item 5
- [x] T3 · S3 · backend: `SesionVigente.estado` (CERRADA, VIGENTE, DEBE_CAMBIAR_CONTRASENA; la consulta por request devuelve la marca) y `JwtAuthenticationFilter` corta con 403 «Cambiá tu contraseña para seguir.» todo `/api` salvo `GET /api/auth/me`, `PUT /api/auth/password` y `POST /api/auth/login` · RED `CambioContrasenaIntegrationTest` 2 fallas (200 y 400 en vez de 403) → GREEN 13/13; `UsuariosIntegrationTest` 27/27 y `FerreteriasAdminIntegrationTest` 15/15 adaptados (el alta prende la marca: `altaCompleta` ahora prueba 403 → cambio → 200). Frontend: el shell no pide la cotización con la marca y la pide al apagarse (RED 1 falla → `shell.spec.ts` 45/45). ego-browser local: vendedor con la marca → «Mi contraseña» sin errores, cotización «Sin datos»; al cambiarla aparece «Oficial venta 1.560,00» · Risk: item 2
- [ ] T4 · S4 · decisión de Luca sobre 7 y 8
- [ ] T5 · todas · suites backend y frontend, `ng build`, `-Pimagen`, `odd-verifier` · Risk: items 2 y 5

## Log

- L1 (2026-11-02) Pedido, textual:
  > lluego continua con task pequenas o minor pendientes

  Después del deploy del hallazgo 3. Lo que queda de la revisión son los 5 BAJOS (5 a 9). Se empieza por los que tienen arreglo sin decisión de producto.
- L2 (2026-11-02) Exploración:
  - `frontend/Caddyfile:9-23`: sin cabeceras; Railway termina el TLS antes de Caddy. En producción (`curl -I`) no llega ninguna de las cuatro.
  - Build: `dist/frontend/browser/index.html` trae `<style>` inline (fuentes y CSS crítico) y un `<script>` inline de beasties que pasa `styles-*.css` de `media="print"` a `all`: con `script-src 'self'` la app quedaría sin estilos. Recursos externos: solo Google Fonts (`fonts.googleapis.com`, `fonts.gstatic.com`). Descargas por `blob:` con `<a download>` (`core/archivos/descarga.ts`); el QR de la factura es inline.
  - Hallazgo 5: `JwtAuthenticationFilter.java:38` valida el token con `SesionVigente.vigente` (una consulta por request) y no mira `debe_cambiar_contrasena`; `contrasena.guard.ts` es el único lugar que lo exige.
- L3 (2026-11-02) T1 (`39f214a`) y T2 hechos. Para probar la imagen local, el backend dev necesita `CORS_ORIGINS=http://localhost:8090`: Caddy reenvía el `Origin` y el backend responde 403 «Invalid CORS request» si no lo tiene (en producción ya está configurado).
- L4 (2026-11-02) T3: con la marca, el shell pedía la cotización, recibía 403 y no volvía a pedirla hasta 5 minutos después del cambio de contraseña. Ahora no la pide con la marca y la pide en cuanto se apaga (`toObservable` + `pairwise`). El vendedor demo quedó con `demo1234` y sin la marca.
