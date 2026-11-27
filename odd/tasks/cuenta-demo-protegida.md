# cuenta-demo-protegida

Objetivo: que nadie pueda dejar afuera al resto cambiando las credenciales de la cuenta demo pública (`demo@prueba.test`, rol Dueño, publicada en el README) · Por qué: el repo es público para el CV y los recruiters entran con esa cuenta; hoy cualquiera puede cambiarle la contraseña o el email.
Rama: `feat/cuenta-demo-protegida` desde `f97abed7` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / `./mvnw test`, frontend `npx ng test --watch=false --include=<spec>` / `npx ng test --watch=false` / `npx ng build` · Mirror Engram: `odd/cuenta-demo-protegida/tasks` · Modelo: caso 5 (seguridad + migración), Opus; la implementación la hace un worker (respaldo de contexto de la sesión).
Pronóstico: ~250 líneas · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: una marca por usuario que protege las credenciales de una cuenta demo. Sin pantalla para prenderla: la migración la prende para `demo@prueba.test` (no existe en dev ni en test, así que ahí no cambia nada) y los tests la prenden a mano.
Fuera de alcance: limitar qué datos de negocio puede tocar la cuenta demo (sigue siendo Dueño), resetear datos, licencia.

- S1: «la cuenta tiene sentido que sea de dueno pero es verdad que tendriamos que deshabilitar lo de la contrasena» — Aceptación: con la marca prendida, `PUT /api/auth/password` rechaza el cambio con 403 y el mensaje «Es una cuenta de demostración: no se puede cambiar la contraseña ni el email.», sin tocar el hash (sigue entrando con la contraseña de antes). Sin la marca, todo sigue igual. — Checks: test de integración del endpoint.
- S2: a la propuesta «con la marca puesta, el backend rechaza cambiar la contraseña, el email, y el reseteo de contraseña desde la gestión de usuarios; el frontend esconde "Cambiar mi contraseña" para esa cuenta; tests de integración de cada caso», Luca: «dale». Aceptación:
  - `PUT /api/usuarios/{id}` sobre un usuario con la marca: si el email cambia, 403 con el mismo mensaje de S1 y sin cambios; si el email queda igual, la edición del resto (nombre, rol, tope) sigue como hoy (con sus reglas actuales: no cambiarse el rol a sí mismo, último Dueño).
  - `PUT /api/usuarios/{id}/password` sobre un usuario con la marca: 403 con el mismo mensaje y sin cambios.
  - La sesión (respuesta del login y lo que el frontend guarda) dice si la cuenta es demo; con la marca, el frontend no muestra «Cambiar mi contraseña».
  - Sin la marca, los tres endpoints y la pantalla siguen exactamente como hoy (test que lo pruebe).
- S4: a la propuesta «a un usuario demo nadie lo puede desactivar ni cambiarle el rol (ni otro Dueño), con 403 y test», Luca: «dale». Aceptación: con la marca, desactivar el usuario y cambiarle el rol en `PUT /api/usuarios/{id}` (o el endpoint de desactivación que exista) responden 403 con el mensaje «Es una cuenta de demostración: no se puede desactivar ni cambiar su rol.» y no cambian nada, aunque lo pida otro Dueño. Editar nombre o tope de la demo sin tocar rol ni email sigue igual. Sin la marca, desactivar y cambiar el rol siguen exactamente como hoy (test que lo pruebe).
- S3 (supuesto técnico): la marca es la columna `usuario.cuenta_demo BOOLEAN NOT NULL DEFAULT FALSE` (V50), y la misma migración hace `UPDATE usuario SET cuenta_demo = TRUE WHERE email = 'demo@prueba.test'`. Así producción queda protegida con el deploy, sin pasos a mano.

## Tasks

- [x] T1 · S1, S2, S3 · ruta: delegada (respaldo de contexto: la sesión ya pasó los 150k) · Aceptación: S1-S3 con tests de integración (backend) y spec del frontend; suite backend y frontend verdes; `ng build` ok · Evidencia: L4 · Commit: `138d636c` · Risk: item 1 (migración), item 2 (seguridad)
- [x] T2 · S1-S3 · ruta: inline · Aceptación: revisión nativa (RDD) del commit de T1 y PR abierto · Evidencia: L5 · Risk: —
- [ ] T3 · S4 · ruta: delegada (respaldo de contexto) · Aceptación: S4 con tests de integración; suite backend verde; verificación independiente y revisión nativa · Evidencia: — · Commit: — · Risk: item 2 (seguridad)

## Log

- L1 (2026-11-27) Luca: «me parece bien no tener licencia y que quede para mi uso nada mas, y la cuenta tiene sentido que sea de dueno pero es verdad que tendriamos que deshabilitar lo de la contrasena, creo que ya lo tiene bloqueado ese usuario en la bdd»
- L2 (2026-11-27) Verificado: no hay bloqueo. Ninguna migración tiene una marca así; `AuthService.cambiarContrasena` (`AuthService.java:110-124`) cambia la contraseña si la actual es correcta. Además `EdicionUsuarioRequest` deja que el Dueño se cambie el email. No se probó en producción para no cambiarle la contraseña de verdad a la demo.
- L3 (2026-11-27) Luca aprueba la propuesta (S2): «dale».
- L4 (2026-11-27) T1: RED observado (V50 inexistente; con solo la migración, los tres endpoints daban 204/200 en vez de 403; la sesión sin `cuentaDemo`; el enlace visible). GREEN: `CuentaDemoIntegrationTest` + `MigracionV50Test` 7/7; con `MigracionV6Test` y `TenantScopingArchTest` 49/49; frontend 6549 tests y `ng build` ok; suite backend completa exit 0 (8 m 36 s) sobre el estado final.
- L5 (2026-11-27) T2: revisión nativa con lentes risk y reliability (escalada por ítem 2) aprobada y reconocida, lineage `review-a2d4a09dc7733031`. Queda fuera del alcance, para decidir: otro Dueño creado desde la demo puede desactivarla.
- L6 (2026-11-27) Verificación independiente (`gentle-ai-verify`, Risk: item 1 e item 2): S1, S2 y S3 PASS, sin bloqueantes. Tests focalizados 49/49 backend y 112/112 frontend. Sin otro escritor de `password_hash` o `email` salvo el reseteo del super admin (`UsuariosParaPlataformaImpl:91-98`), que queda sin guardia a propósito: es la vía para recuperar la demo.
- L7 (2026-11-27) Explicado a Luca el hueco: desde la demo se crea otro Dueño, y ese Dueño puede desactivar a la demo (las reglas solo impiden desactivarse a uno mismo y quedarse sin Dueño activo, `UsuarioService.java:23-24`) o pasarla a Vendedor. Luca: «dale» → S4, T3.
