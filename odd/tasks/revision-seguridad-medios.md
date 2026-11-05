# revision-seguridad-medios

Objetivo: cerrar los hallazgos 3 y 5 (MEDIA) y 7 (BAJA) de la revisión de seguridad del 2026-11-05 · Por qué: el límite de intentos del login por IP se puede saltear, una factura en PDF armada a propósito ocupa la CPU del servidor compartido, y con un token robado se prueba la contraseña actual sin freno. Informe: vault `Projects/Ferreteria/wiki/revision-seguridad-2026-11-05.md`.
Rama: `fix/revision-seguridad-medios` desde `03316e1` · Entrega: single-pr · Runner: backend `cd backend && ./mvnw test -Dtest=<Clase>` / suite `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / `npx ng test --watch=false` · Mirror Engram: `odd/revision-seguridad-medios/tasks`
Pronóstico: ~350 líneas cambiadas · Ruteo: caso 5 (Opus de punta a punta: seguridad, alto riesgo item 2)

## Specs

Alcance: hallazgos 3, 5 y 7 del informe · Fuera de alcance: el bajo 6 (bloqueo de una cuenta ajena: decisión de Luca, arreglar o riesgo aceptado), `ClienteJson` sin `NumeroDelPedido` (anotado), el deploy (Railway está dado de baja desde el 2026-11-03).

Pedido (2026-11-05):
- «Dale, arrancá» (sobre la propuesta: el 3, el 5 y el 7 en una rama, test-first, probados de punta a punta contra la app levantada en `main` y en la rama).
- «quieor qu etemrines esos puntos como hacemos siempre con odd y rdd y por ultimo revision con el poryecto levantado, cuadno termines dejame un informe con todo lo que hiciste y como quedo»

- S1: medio 5, «Leer una factura en PDF es cúbico y corre fuera del semáforo» — Aceptación: una línea con muchos números (1.600 o más) se lee en menos de 1 s y no es un renglón; el peor PDF que entra en el tope de palabras (500.000) se interpreta en pocos segundos, y la interpretación corre dentro del turno de lectura de PDF (una por vez en el servidor). Las facturas que hoy se leen se siguen leyendo igual (`FacturaDePdfTest`, `FacturaDePdfImpuestosYFormatosTest`, `LectorDeFacturaPdfTest` en verde) — Checks: test con tope de tiempo; test de que la interpretación ocupa el turno.
  - Supuesto: una línea con más de 30 números no es un renglón (fix del informe: «saltear líneas con más de ~30 números»).
- S2: bajo 7, «"Cambiar mi contraseña" no tiene límite de intentos» — Aceptación: `PUT /api/auth/password` cuenta los fallos de `passwordActual` por usuario; pasado el límite responde 429 con `Retry-After` y «Demasiados intentos. Probá de nuevo en N minutos.» sin evaluar la contraseña; una actual correcta borra la racha. La pantalla «Mi contraseña» muestra ese aviso y no desloguea — Checks: `LimiteDeIntentosTest`, `AuthServiceTest`, test de integración del endpoint, `mi-contrasena.spec.ts`.
  - Supuesto: las mismas reglas que el email del login (5 fallos seguidos bloquean 1, 5 y después 15 minutos; la racha se olvida tras 1 hora), con contador propio por usuario.
- S3: medio 3, «El límite de intentos por IP confía en un header que pone el cliente» — Aceptación: Caddy pone `X-Real-IP` con la IP del cliente que calcula él (`{client_ip}`, con `trusted_proxies` y lectura de derecha a izquierda de `X-Forwarded-For`), pisando el que mande el cliente. El límite por IP cuenta las IPv6 por su /64; IPv4 sigue igual — Checks: `LimiteDeIntentosTest`; prueba de punta a punta con Caddy en Docker delante del backend local: en `main` 25 logins con un `X-Real-IP` distinto cada uno no dan 429; en la rama el 21.º da 429.
  - Pendiente fuera de alcance: confirmar en Railway (cuando vuelva) qué IP ve Caddy del borde y que el borde agrega la IP del cliente a `X-Forwarded-For`.

## Tasks

- [ ] T1 · S1 · ruta: inline · Aceptación: RED (línea de 1.600 números pasa el tope de tiempo) → GREEN; la interpretación va dentro del turno · Risk: item 2 (CPU del servidor compartido)
- [ ] T2 · S2 · ruta: inline · Aceptación: RED (6.º intento con actual mala da 400) → GREEN 429; front muestra el aviso · Risk: item 2
- [ ] T3 · S3 · ruta: inline · Aceptación: RED (dos IPv6 del mismo /64 no comparten el límite) → GREEN; Caddyfile con `header_up X-Real-IP {client_ip}` · Risk: item 2, item 5 (config del proxy)
- [ ] T4 · S1 S2 S3 · ruta: inline · Aceptación: suites backend y frontend, `ng build`, prueba de punta a punta contra `main` y contra la rama con la app levantada · Risk: —
- [ ] T5 · S1 S2 S3 · ruta: `odd-verifier` (opus) · Aceptación: pass sin blockers · Risk: —

## Log

- L1 (2026-11-05) Pedido original, textual: ver «Pedido» en Specs.
- L2 (2026-11-05) Exploración:
  - `FacturaDePdf.renglon` (`:166-186`): por cada par (unitario, neto) recorre las palabras entre los dos buscando %, y por cada cantidad anterior arma la terna: O(n³) por línea. Corre después de `LectorPdf.palabras`, que suelta el turno al volver (`LectorDeFacturaPdf.java:26`).
  - `AuthService.cambiarContrasena` (`:99-103`): bcrypt de la actual sin límite. `mi-contrasena.ts` ya muestra el `message` de un error sin campos; el interceptor solo desloguea con 401.
  - `frontend/Caddyfile:22`: `reverse_proxy` sin `header_up`; Caddy reenvía el `X-Real-IP` del pedido. Caddy (docs, `trusted_proxies`): sin proxies de confianza `{client_ip}` es la dirección de la conexión; con `trusted_proxies_strict` lee `X-Forwarded-For` de derecha a izquierda (lo que agrega el borde) y el valor que mande el cliente queda a la izquierda.
