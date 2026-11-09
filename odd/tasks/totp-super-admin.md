# totp-super-admin

Objetivo: el super admin entra con email, contraseña y el código de 6 números de Google Authenticator (TOTP). Lo activa una sola vez escaneando un QR · Por qué: hoy alcanza con la contraseña para entrar a la cuenta que abre todas las ferreterías. El límite de intentos y la lista negra frenan a quien la adivina, no a quien la roba (L1, L3).
Rama: `feat/totp-super-admin` desde `ffdad494` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / suite `./mvnw test` / imagen `./mvnw test -Pimagen`; frontend `npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` / `npx ng build` · Mirror Engram: `odd/totp-super-admin/tasks` (ok)
Pronóstico: ~1300 líneas (backend ~950 con tests, frontend ~350) · Heurística por tarea: ~400 (no es límite) · Modelo: caso 5, Opus de punta a punta (aprobado en L2)

## Specs

Alcance: segundo factor TOTP solo para `SUPER_ADMIN` (login, alta con QR, verificación, reset por base documentado), en el backend, el frontend, el README y `docs/spec.md`.
Fuera de alcance: TOTP para Dueño o Vendedor, códigos de recuperación (descartados en L3), un botón de reset en la app, rotación de `TOTP_CLAVE_MAESTRA`, «recordar este dispositivo».
Restricciones: reglas de `CLAUDE.md` (mensajes en español rioplatense, migraciones con Flyway y rollback probado). Sin dependencias nuevas: el TOTP (RFC 6238) va con `javax.crypto` y el QR con zxing, que ya está en el `pom.xml`. Todo cambio visual se prueba con ego-browser a 1280 px.

### Contrato (fijo para el backend y el frontend)

- `POST /api/auth/login` (sin cambios para Dueño y Vendedor): `LoginResponse` suma `segundoPaso` y `tokenTotp`.
  - Dueño y Vendedor: `{token, sesion}`, con `segundoPaso` y `tokenTotp` en `null` o ausentes.
  - `SUPER_ADMIN` con la contraseña bien: 200 `{token: null, sesion: null, segundoPaso: "ALTA" | "CODIGO", tokenTotp}`. `ALTA` si el TOTP no está activado; `CODIGO` si lo está. El `tokenTotp` vence a los 5 minutos.
- `POST /api/auth/totp/alta`, body `{tokenTotp}`, devuelve 200 `{clave, uri, qr}`:
  - `clave`: el secreto en Base32, sin relleno;
  - `uri`: `otpauth://totp/Ferreter%C3%ADa:<email>?secret=<clave>&issuer=Ferreter%C3%ADa&algorithm=SHA1&digits=6&period=30`;
  - `qr`: la matriz del QR de `uri`, en filas de `'0'` y `'1'`, como `qrModulos` de la factura.
  - Si ya hay un secreto pendiente sin confirmar, devuelve el mismo (el QR no cambia hasta activarlo).
  - TOTP ya activado: 409 «El código de la app ya está activado.».
- `POST /api/auth/totp/verificar`, body `{tokenTotp, codigo}`, devuelve 200 `{token, sesion}` (`segundoPaso` y `tokenTotp` en `null` o ausentes). Si el TOTP estaba pendiente, queda activado.
- Errores (formato `ApiError` de siempre, `{status, message, errors, timestamp}`), para los dos endpoints de `/api/auth/totp/`:
  - `tokenTotp` inválido o vencido: 401 «Pasaron más de 5 minutos. Volvé a ingresar tu email y contraseña.», con `errors.motivo = "TOTP_VENCIDO"`;
  - código incorrecto o ya usado: 401 «El código no es correcto. Fijate en la app y probá de nuevo.», con `errors.motivo = "CODIGO_INCORRECTO"`;
  - código que no son 6 números: 400 «El código tiene 6 números.»;
  - verificar sin haber pedido el alta: 409 «Primero escaneá el código QR.»;
  - demasiados códigos mal: 429 con el mensaje y el `Retry-After` de `DemasiadosIntentosException`.
- Los dos endpoints de `/api/auth/totp/` son públicos para Spring Security: los autoriza el `tokenTotp` del body, no un `Authorization`.

### Requisitos

- S1: «la proxima seguimos con el TOTP del super admin» (L1) y «osea para entrar al super admin debo poner emial contrasena y un codigo mas??» (L2, respondido que sí) — Aceptación: el `SUPER_ADMIN` con la contraseña bien no recibe el token, sino `segundoPaso` y `tokenTotp`. Con el código bien en `/api/auth/totp/verificar` recibe `{token, sesion}` y entra a `admin/ferreterias`, como hoy. En la pantalla de login, después de email y contraseña aparece el campo del código — Checks: test de integración del flujo, `login.spec.ts`, ego-browser
- S2: «okey entonces el escaner el qr es solamente una vez» (L2) — Aceptación: con el TOTP sin activar, el login pide `ALTA` y la pantalla muestra el QR. El primer código bien lo activa. Después de eso, `/alta` da 409 y el login pide `CODIGO`: el QR no se vuelve a mostrar nunca — Checks: test de integración, `login.spec.ts`, ego-browser
- S3: «entonces voy a utilizar google authenticator para eso» (L2) — Aceptación: RFC 6238 con HMAC-SHA1, 6 dígitos y pasos de 30 s, compatible con Google Authenticator. Se acepta el paso actual, el anterior y el siguiente. Los vectores SHA-1 del Apéndice B de la RFC 6238 dan los últimos 6 dígitos esperados — Checks: test unitario con los vectores oficiales
- S4: reset por base, «dale» (L3) — Aceptación: el README (Deploy) documenta el `UPDATE` que borra el TOTP de un `SUPER_ADMIN`. Después de correrlo, el login vuelve a pedir `ALTA` — Checks: test de integración (el `UPDATE` del README y después `ALTA`)
- S5: la propuesta de seguridad aceptada en L3: «un código usado no se acepta de nuevo», «límite de intentos», «el secreto se guarda cifrado en la base». Aceptación:
  - S5a: el `tokenTotp` no sirve como token de sesión: cualquier otro endpoint da 401.
  - S5b: un token de `SUPER_ADMIN` que no pasó por el código (por ejemplo, uno emitido antes del deploy) da 401.
  - S5c: un código ya usado da `CODIGO_INCORRECTO`. Dos verificaciones simultáneas con el mismo código dan un 200 y un 401.
  - S5d: 5 códigos mal seguidos del mismo usuario dan 429 con la escalera de `LimiteDeIntentos` (1, 5, 15 y 30 min). El código bien reinicia la racha.
  - S5e: el secreto se guarda con AES-256-GCM, con la clave `TOTP_CLAVE_MAESTRA`. Nunca va al log ni vuelve a la API después de activado.
  - Checks: tests de integración y unitarios por cada uno
- S6 (supuesto): textos de la pantalla. Paso del código: título «Código de la app», ayuda «Abrí Google Authenticator y escribí el código de 6 números que muestra para Ferretería.», botón «Ingresar» y enlace «Volver». Alta: título «Activá el código de la app», «1. Escaneá este código QR con Google Authenticator. Se muestra solo esta vez.», «¿No podés escanearlo? Cargá esta clave a mano:» con la clave en grupos de 4, «2. Escribí el código de 6 números que te muestra la app.» y botón «Activar e ingresar». El campo del código lleva `inputmode="numeric"` y `autocomplete="one-time-code"`.
- S7 (supuesto): `TOTP_CLAVE_MAESTRA` (32 bytes en Base64) es obligatoria en `prod`: sin ella el backend no arranca, como `ARCA_CLAVE_MAESTRA`. En dev y test va una clave fija. Luca la crea en Railway antes del deploy. Si cambia, el super admin se resetea por base (S4).
- S8 (supuesto): Dueño y Vendedor no cambian: el mismo login, la misma respuesta y las mismas pantallas.
- S9 (supuesto): los tests que entran como super admin sin probar el login (31 archivos, por `IntegrationTestBase.login`) reciben el token completo de un helper. El flujo TOTP se prueba de punta a punta en tests propios, y `ImagenDeProduccionTest` lo recorre entero contra la imagen real.

## Tasks

- [x] T1 · S1–S5, S7–S9 (backend, README, spec) · ruta: delegada (Writer trigger: dos unidades independientes de 100+ líneas en paralelo, T1 y T2, con superficies disjuntas; backstop de contexto) · Evidencia (L6): RED con 46 fallas. GREEN: 231 focalizados; suite 6515/0; `-Pimagen` 12/0. El padre corrió `SegundoFactorIntegrationTest` y `TotpTest`: 19/0 · Risk: items 1 (V48 en `usuario`), 2 (seguridad), 3 (contrato del login), 4 (código usado con pedidos simultáneos) y 5 (variable nueva en el deploy)
- [x] T2 · S1, S2, S6, S8 (frontend) · ruta: delegada (mismo trigger que T1) · Evidencia (L5): RED login 11/30, servicio 4/17, interceptor 2/10. GREEN `login.spec.ts` 30/30 (también lo corrió el padre), `auth.service.spec.ts` 17/17, interceptor 23/23, `ng build` OK. Superficie respetada · Risk: items 2 y 3 (van a T4)
- [x] T3 · S1–S8 · ruta: worker de ego-browser (Opus) · Aceptación: con la app local, el alta con QR, el código bien, mal y repetido, y Dueño y Vendedor sin cambios, a 1280 px · Evidencia: L7, 6/6 PASS · Risk: —
- [x] T4 · todo · ruta: `odd-verifier` (Opus) · Aceptación: sin blockers · Evidencia: L10, PASS sin blockers (218/0 focalizados y 12/0 de sondas) · Risk: items 1–5
- [x] T5 · cierre · ruta: inline · Aceptación: suite backend, `-Pimagen`, suite frontend y `ng build` verdes · Evidencia: L6, L8 y L12. Commits `bfe8b42d`, `fe2a208c` y `45e20092` · Risk: —

## Log

- L1 (2026-11-08) Luca, textual (de `detalles-chicos-de-hoy.md`, L1): «la proxima seguimos con el TOTP del super admin». Antecedente (Engram `decision/totp-super-admin`, 2026-11-05): «para despues de produccion no lo veo tan imporntante ahora». Railway volvió el 2026-11-07.
- L2 (2026-11-09) Luca, textual, en orden:
  - «continuamos»;
  - «dale describime que es la tarea que es lo que pasa hoy en dia yu lo que hayt que solucionar» (aprueba el caso 5);
  - «osea para entrar al super admin debo poner emial contrasena y un codigo mas?? o como es ? esa parte de com odebo ingresar yodecime en resumen»;
  - «si, pero si me roban las credenciales, cual es la diferencia que la otra persona pueda escanear es eqr y poner el codigo que le sale que yo lo haga,»: se explicó que el QR se muestra una sola vez, y que el punto débil es antes de activarlo, por eso se activa apenas sale el deploy;
  - «okey entonces el escaner el qr es solamente una vez y me da un codigo y desde entonces pongo el gmail contrase y ese mismo codigo?? es asi ?»: se explicó que el código cambia cada 30 s;
  - «okey pero que app de mi celular lo va a hacer y ocmo el propio sistema lo va a hacer a lo del codigo»;
  - «aaa okey listo, entonces voy a utilizar google authenticator para eso».
- L3 (2026-11-09) Decisión: «cual es la diferencia entre las dos opcione sque osn». Se comparó el reset por base con los códigos de recuperación y se recomendó el reset por base, porque el super admin es solo Luca y ya maneja Railway. Luca: «dale». Se describió la propuesta y quedó aceptada con ella: login en dos pasos, alta obligatoria con QR, «un código usado no se acepta de nuevo», «límite de intentos» y «el secreto se guarda cifrado en la base».
- L4 (2026-11-09) Exploración (`odd-explorer`):
  - **Login.** `AuthService.login` (`auth/service/AuthService.java:54-80`) devuelve `LoginResponse(token, sesion)`. `JwtService.leerToken` (`:52-64`) acepta cualquier token firmado con sub, email, rol y ferreteriaId, e ignora los claims desconocidos. `JwtAuthenticationFilter.java:39-62` y `SesionVigente`. `SecurityConfig.java:40` hace `permitAll` solo de `POST /api/auth/login`.
  - **Bloqueos.** `ModulosArchTest.soloFacturacionArmaElQrConZxing` (`:265-273`) prohíbe zxing fuera de `facturacion`. `nadieDependeDeFacturacionSalvoPorSuPuerto` (`:210`) impide usar `CifradorDeClaves` desde `auth`.
    - Decisión técnica: se habilita `auth` en la regla de zxing.
    - Decisión técnica: el cifrado del secreto va en `auth`, con su propia clave (S7), sin tocar el de ARCA.
  - **Tiempo y QR.** El `Clock` está en `common/config/RelojConfig.java:20-23`, con `RelojControlableConfig` en los tests. `QrArca.modulos` y `factura-hoja.ts:79-88` son el precedente de la matriz del QR dibujada en SVG.
  - **Límite.** `LimiteDeIntentos.reservarCambioDeContrasena(long)` (`:145`) es el precedente por usuario. `application-test.yml:20-23` sube los límites para toda la suite.
  - **Tests.** 31 archivos entran como `admin@demo.local` por `IntegrationTestBase.login` (`:81-88`). `ImagenDeProduccionTest` entra como `SUPER_ADMIN` (`:199-205`, `:433-440`).
  - **Migración.** La última es V47, así que la nueva es V48, con `MigracionV48Test` y su rollback, según `support/PlantillaDeMigracion.java`.
  - **Frontend.** `auth.service.ts:28-39` guarda el token en `localStorage`. `autenticado = token !== null`, así que el `tokenTotp` no va ahí. `auth.interceptor.ts:29` saca `/auth/login` del manejo del 401. Pantalla: `features/login/login.ts`.
- L5 (2026-11-09) T2 (`odd-implementer` Opus): 9 archivos, +782/−67, unos 400 de tests.
  - `LoginResponse` pasa a ser una unión (`Ingreso` o `SegundoPasoPendiente`).
  - `AuthService.altaTotp` y `verificarTotp`: el `tokenTotp` vive solo en el componente.
  - El interceptor no trata como sesión vencida un 401 de `/auth/totp/`.
  - Login con tres pasos: el QR en SVG con `barras()` de `features/etiquetas/codigo-barras`, que se importa sin tocarla.
  - El campo del código acepta «123 456» pegado.
  - Hallazgo: antes, `AuthService.login` escribía el texto 'null' en `localStorage` si el token venía nulo.
  - `ng build` avisa del presupuesto de CSS de `dialogo-cobro.ts`, que ya estaba en la base.
- L6 (2026-11-09) T1 (`odd-implementer` Opus): 25 archivos modificados (+527/−64) y 22 nuevos.
  - **Servicio.** `SegundoFactorService` tiene el alta, la verificación y `entrar`, el único que emite el token completo del super admin. Lo cuida `SesionDelSuperAdminArchTest`.
  - **`JwtService`.** `generarToken` rechaza al `SUPER_ADMIN`. `leerToken` rechaza los tokens con `tipo` y los de `SUPER_ADMIN` sin `segundoFactor`.
  - **Código usado.** Un solo `UPDATE` condicional (`usarPasoTotp`). Las columnas TOTP de `Usuario` van con `insertable=false, updatable=false`.
  - **V48.** Agrega tres columnas y el CHECK `ck_usuario_totp`.
  - **Orden de `verificar`.** Primero el 400, después el 401 `TOTP_VENCIDO`, el 409, el 429 y el 401 `CODIGO_INCORRECTO`.
  - **Diferencias con lo pedido.** El 400 trae también `errors.codigo`. La respuesta del Dueño y el Vendedor suma `segundoPaso: null` y `tokenTotp: null`, que el contrato permite.
  - **Fuera de la lista de clases.** `MigracionV6Test` suma la 48.
  - **No se hizo.** El chequeo diferencial contra la base.
- L7 (2026-11-09) T3 en la app local (`fe2a208c`, 1280x800, ego-browser). Pasan los 6 recorridos:
  1. **Alta.** El QR en SVG coincide con las filas de `/alta` (641/641 módulos). Los textos de S6 salen tal cual. Sin scroll a 1280.
  2. **Código mal y después bien.** El mal da `CODIGO_INCORRECTO`, el campo se vacía y el foco vuelve; el bien entra a `/admin/ferreterias`.
  3. **Código repetido.** «Código de la app» sin QR. El código ya usado se rechaza, también dentro del mismo paso.
  4. **«Volver».** Vuelve a las credenciales, que siguen cargadas.
  5. **Dueño.** Un solo `/auth/login` y entra al mostrador.
  6. **Reset por base.** El `UPDATE` del README vuelve a mostrar el QR. El secreto pendiente se repite al reingresar.

  En el log del backend no aparece el secreto ni la URI.

  Detalle menor, sin cambiar: con el aviso de error en el paso del alta, la tarjeta pasa 30 px del alto de 800, y la página hace scroll vertical, no horizontal. Capturas en el scratchpad de la sesión (`t3-totp/`).
- L8 (2026-11-09) T5, frontend: `npx ng test --watch=false`, 164 archivos pasan. `npx ng build` OK, con el aviso de presupuesto de `dialogo-cobro.ts` que ya estaba en la base.
- L9 (2026-11-09) Luca escaneó el QR con Google Authenticator en la app local y entró con el código. Textual: «listo ya lo porbe y anda bien». Prueba real de S2 y S3 con el celular.
- L10 (2026-11-09) T4, `odd-verifier` (Opus), sobre una copia de `fe2a208c`: PASS, sin blockers. Corrió 218 tests focalizados, 0 fallas, y los specs del front (30, 17 y 10). Sus 12 sondas pasan:
  - el `tokenTotp` como Bearer da 401 en todo;
  - los tokens falsificados con la firma (`segundoFactor` como texto o con `tipo`) se rechazan;
  - la ventana es ±1 paso; ±2 se rechaza;
  - el 400, el 409 y los tokens inválidos no gastan intentos, y el límite es por usuario;
  - un rechazo no cambia la fila;
  - 5 verificaciones simultáneas dan un solo 200;
  - 4 altas simultáneas dan una sola clave;
  - el log no tiene secretos;
  - el reset con alta pendiente funciona.

  Advertencias, ninguna rompe algo de la base:
  1. El login del Dueño y del Vendedor suma dos claves en null.
  2. El `tokenTotp` se puede reusar durante sus 5 minutos y no lo corta un cambio de contraseña. Las sesiones ya emitidas siguen después de un reset por base. Nada de eso está en la spec.
  3. El límite vive en memoria, como el del login.
  4. Un código mal escrito con un token inválido da 400, el orden de L6.
  5. Si cambia `TOTP_CLAVE_MAESTRA`, `verificar` da 500 después de gastar un intento; lo cubre el reset por base.

  Las sondas pasan al repo como `SegundoFactorBordesIntegrationTest`.
- L11 (2026-11-09) Luca cargó `TOTP_CLAVE_MAESTRA` en el servicio `backend` de Railway, con `openssl rand -base64 32 | railway variables --service backend --set-from-stdin TOTP_CLAVE_MAESTRA --skip-deploys`. Textual: «listo ya tire el comando de opcion 1». Control sin mostrar el valor: decodificada mide 32 bytes. No se disparó ningún deploy.
- L12 (2026-11-09) Las sondas de L10 pasan al repo como `SegundoFactorBordesIntegrationTest`: 10 tests, de p01 a p10. Cada `println` se volvió una aserción o se sacó porque el dato ya estaba afirmado. Se sacaron enteras p11 y p12, que eran de depuración. 10/0, también corrido por el padre.

  Observación, no es blocker: con 5 verificaciones simultáneas del mismo código, el intento se reserva antes de comparar. Por eso el log avisa «5 códigos incorrectos seguidos… se bloquea 1 min» aunque una de ellas entró. El test igual da un 200, cuatro 401 y ningún 429.

  T5 cerrada:
  - suite backend 6515/0 (L6; después solo se sumó este test);
  - `-Pimagen` 12/0;
  - suite frontend con 164 archivos que pasan;
  - `ng build` OK.

  Próximo paso: Luca hace push, PR, merge y deploy, y apenas sale el deploy entra a producción y escanea el QR (S2, README).
