# limite-intentos-login

Objetivo: el login frena los intentos repetidos por email y por IP, con 429 y un aviso en pantalla · Por qué: hallazgo 1 (ALTA) de la revisión de seguridad del 01/11: el login no tiene límite y el backend está expuesto.
Rama: `fix/limite-intentos-login` desde `1e37704` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / `npx ng test --watch=false` · Mirror Engram: `odd/limite-intentos-login/tasks` (ok)
Ruteo: caso 5 (alto riesgo, seguridad: Opus de punta a punta) · Pronóstico: ~450 líneas cambiadas · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: límite por email y por IP en `POST /api/auth/login`, respuesta 429 con aviso, IP real del cliente detrás de Railway y Caddy, aviso en la pantalla de login.
Fuera de alcance: sacar el dominio público del servicio `backend` (lo hace Luca en Railway), lista negra de contraseñas comunes, TOTP para el SUPER_ADMIN.
Restricciones: el bloqueo no gasta bcrypt; contadores en memoria (una sola instancia; se reinician con cada deploy); el login con email inexistente sigue costando un bcrypt (no revela cuentas).

Propuesta aprobada por Luca («dale, me parece bien, arrancá»):

- S1: "Límite por email: 5 intentos fallidos seguidos bloquean ese email por un tiempo creciente (1 min, 5 min, 15 min). Un login correcto lo resetea." — Aceptación: el 6.º intento tras 5 fallos da 429 sin evaluar la contraseña; al vencer, 5 fallos más bloquean 5 min y después 15 min (tope); un login correcto vuelve el contador y el nivel a cero. — Checks: `LimiteDeIntentosTest`, `LimiteDeIntentosLoginIntegrationTest`
- S2: "Límite por IP: ~20 intentos por minuto, para frenar al que prueba muchos emails desde la misma IP." — Aceptación: el intento 21 de la misma IP dentro del minuto da 429 aunque cada email sea distinto; otra IP no se ve afectada. — Checks: ídem
- S3: "Pasado el límite: respuesta 429 sin gastar el bcrypt, y el front muestra «Demasiados intentos. Probá de nuevo en N minutos.»" — Aceptación: 429 con `Retry-After` y ese mensaje; la pantalla de login lo muestra en el error enlazado a los dos campos. — Checks: integración backend + `login.spec.ts`
- S4: "En memoria (Caffeine), alcanza con una sola instancia." — Aceptación: sin tabla ni migración; las entradas vencidas se purgan.
- S5: "IP real: detrás de Caddy el backend ve la IP de Caddy, no la del cliente." — Aceptación: con `X-Real-IP` desde un proxy interno el backend cuenta la IP del cliente; sin el header cuenta la dirección de la conexión. — Checks: test con Tomcat real (`RANDOM_PORT`).
- S6 (supuesto): con N = 1 el texto dice «1 minuto» (singular); N es el redondeo hacia arriba de los segundos que faltan.
- S7 (supuesto): la racha de fallos de un email se olvida tras 1 hora sin fallos (vuelve al nivel 1 min).
- S8 (supuesto): el límite por IP cuenta todo intento que pasa el chequeo de la IP (correcto, incorrecto o rechazado por el email) en una ventana fija de 1 minuto; los rechazados por la IP no suman, y un rechazo por IP no le suma un fallo al email.
- S9 (supuesto): los límites salen de `app.login.*` (prod: 5 y 20); el perfil `test` los sube para que la suite (todo MockMvc viene de 127.0.0.1) no choque con ellos.

## Tasks

- [x] T1 · S1 S2 S4 S6 S7 S8 · ruta: inline · Aceptación: `LimiteDeIntentos` (con `Clock`) cumple las reglas de email e IP y purga lo vencido · Evidencia: RED de compilación → GREEN `LimiteDeIntentosTest` 15/0 (incluye 200 pedidos en paralelo: exactamente 5 por email y 20 por IP) · Risk: item 2 (seguridad), item 4 (concurrencia: `ConcurrentHashMap.compute`)
- [x] T2 · S1 S2 S3 S5 S9 · ruta: inline · Aceptación: `AuthService` + controlador + `GlobalExceptionHandler` dan 429 con `Retry-After` y el mensaje, sin bcrypt; `X-Real-IP` vía `server.tomcat.remoteip.remote-ip-header` · Evidencia: RED de compilación → GREEN `AuthServiceTest` 19/0, `LimiteDeIntentosLoginIntegrationTest` 4/0 (Tomcat real), `LoginFerreteriaSuspendidaIntegrationTest` 6/0; mutación: con `remote-ip-header` vacío los 4 fallan · Risk: item 2, item 5 (config de despliegue)
- [x] T3 · S3 · ruta: inline · Aceptación: la pantalla de login muestra el mensaje del 429 · Evidencia: RED 2 fallan → GREEN `login.spec.ts` 14/14 · Risk: none
- [ ] T4 · todas · ruta: inline + `odd-verifier` · Aceptación: suites backend y frontend, `ng build`, verificación en ego-browser y verifier PASS · Evidencia: — · Risk: item 2

## Log

- L1 (2026-11-02) Pedido original, textual:
  > dale, arrancamos por el límite de intentos dame el briefing

  > dale, me parece bien, arrancá
- L2 (2026-11-02) Exploración: login en `auth/service/AuthService.java:45` (bcrypt siempre, con hash señuelo); `SecurityConfig.java:40` `permitAll`; `frontend/Caddyfile:11-13` reenvía `/api` a `backend.railway.internal` sin límite; `login.ts:71` solo distingue 401. Spring Boot 3.3.13 confía por defecto en rangos privados, incluido `f[cd]..` (red privada IPv6 de Railway). Railway documenta `X-Real-IP` como la IP del cliente (docs.railway.com/networking/public-networking/specs-and-limits); el foro reporta casos con IPs de su infraestructura → se registra un WARN con la IP en cada bloqueo para confirmarlo en producción.
- L3 (2026-11-02) Cambio respecto del briefing: no hace falta `trusted_proxies` en Caddy. Caddy reenvía `X-Real-IP` tal cual; el backend lo lee con `server.tomcat.remoteip.remote-ip-header: X-Real-IP` y solo lo acepta si la conexión viene de un proxy interno. Contadores con `ConcurrentHashMap` (sin dependencia nueva) en vez de Caffeine: misma idea, sin tocar el `pom.xml` (item 5).
- L4 (2026-11-02) Diseño: `reservar` va antes del bcrypt y cuenta el intento como fallo; `exito` borra la racha (también con la ferretería suspendida: la contraseña era buena). Purga de lo vencido a lo sumo una vez por minuto. Un WARN por cada bloqueo nuevo, con email e IP, para confirmar en producción qué IP ve el backend. Spring Boot 3.3.13 arma el `RemoteIpValve` con solo `remote-ip-header` (verificado con `javap` de `TomcatWebServerFactoryCustomizer`): no hace falta `forward-headers-strategy`. El valve también procesa `X-Forwarded-Proto` (Caddy manda `http`), como hasta ahora. Front: un 429 sin mensaje muestra «Demasiados intentos. Probá de nuevo en unos minutos.» (supuesto, texto de respaldo).
- L5 (2026-11-02) Progreso: T1-T3 en verde. Pendiente T4: suite backend, suite front, `ng build`, ego-browser local, `odd-verifier`.
