# decisiones-pendientes

Objetivo: cerrar tres decisiones abiertas: lista negra de contraseñas comunes, «1.500» ambiguo al ajustar stock y la rueda del mouse en los campos numéricos · Por qué: S1c de la revisión de seguridad del 01/11 y dos sugerencias del QA de la Ferretería de Prueba.
Rama: `fix/decisiones-pendientes` desde `39a177c` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / `./mvnw test`; frontend `npx ng test --watch=false [--include=<spec>]`, `npx ng build` · Mirror Engram: `odd/decisiones-pendientes/tasks`
Ruteo: caso 5 (alto riesgo, seguridad: Opus de punta a punta) · Pronóstico: ~400 líneas cambiadas, más la lista de contraseñas (dato, ~2.200 líneas) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: lista negra en todos los flujos que fijan una contraseña; aviso de número ambiguo en el diálogo de ajuste de stock; la rueda del mouse no cambia campos numéricos.
Fuera de alcance: TOTP del SUPER_ADMIN («El TOTP antes de volver a producción con ferreterías reales»), contraseñas ya guardadas, otros lectores de números.
Restricciones: reglas de arquitectura de `CLAUDE.md`; mensajes en español rioplatense.

Propuesta aprobada por Luca («dale, vamos con las tres recomendaciones»):

- S1: "**Lista negra:** rechaza contraseñas comunes al crearlas o cambiarlas. Las que ya existen no se tocan." / "**Recomiendo:** la lista negra ahora." — Aceptación: en el alta de usuario, el reset del Dueño, el cambio propio y el alta y el reset del Dueño por el super admin, una contraseña de la lista da 400 en su campo con «Esa contraseña es muy común. Elegí otra más difícil de adivinar.»; el login con una contraseña común ya guardada sigue andando. — Checks: `PoliticaDeContrasenaTest`, `UsuariosIntegrationTest`, `CambioContrasenaIntegrationTest`, `FerreteriasAdminIntegrationTest`
- S2 (supuesto): la lista es la `10k-most-common` de SecLists (licencia MIT) filtrada a 8–72 caracteres, más una lista propia de contraseñas locales (`ferreteria123`, `argentina`, `mardelplata`, `bocajuniors`…); se compara sin distinguir mayúsculas y sin los espacios de los costados.
- S3: "no adivinar. Si hay un punto seguido de exactamente 3 dígitos y no hay coma, pedir: «¿1500 o 1,5? Usá la coma para los decimales». Lo demás sigue igual." — Aceptación: en el stock real y en la cantidad, «1.500» da «¿Quisiste decir 1500 o 1,5? Usá la coma para los decimales.» al confirmar y «—» en la vista previa; «145.5», «145,5», «1,5» y «1.500,25» se leen como hoy. — Checks: `dialogo-ajuste-stock.spec.ts`
- S4 (supuesto): «0.250» se lee como 0,25 (un 0 delante no puede ser de miles).
- S5: "que la rueda no cambie ningún campo numérico de la app." — Aceptación: la rueda sobre un `input[type=number]` con el foco no cambia su valor; sobre uno sin foco o sobre otro elemento no se toca nada. — Checks: spec del listener global
- S6 (supuesto): mientras el puntero está sobre el campo numérico con foco, la rueda tampoco desplaza la página (se cancela el evento entero).

## Tasks

- [x] T1 · S1 S2 · ruta: inline · Aceptación: `PoliticaDeContrasena` rechaza las de la lista en los cinco flujos · Evidencia: RED 7/72 (los 7 casos nuevos: 2 unitarios, alta y reset en `UsuariosIntegrationTest`, cambio propio, alta y reset del super admin) → GREEN 147/0 con `UsuarioServiceTest`, `AuthServiceTest`, `FerreteriasAdminServiceTest` (en `UsuarioServiceTest` «clave1234» pasó a «clave-segura-77»: ahora es común) · Risk: item 2 (seguridad)
- [x] T2 · S3 S4 · ruta: inline · Aceptación: aviso de número ambiguo en el ajuste de stock · Evidencia: RED 2/49 (stock real y cantidad; el caso que fija «145.5», «1.500,25», «1000.250» y «0.250» ya pasaba) → GREEN 49/49 `dialogo-ajuste-stock.spec.ts`; los ceros finales se sacan en la pregunta («2.000» → «¿2000 o 2?») · Risk: none
- [ ] T3 · S5 S6 · ruta: inline · Aceptación: listener global de la rueda · Evidencia: — · Risk: none
- [ ] T4 · todas · ruta: inline + `odd-verifier` · Aceptación: suites backend y frontend, `ng build`, punta a punta con la app y verifier PASS · Evidencia: — · Risk: item 2

## Log

- L1 (2026-11-05) Pedido original, textual:
  > okey de cada decision dame el contexto de la situaicon para poder decidir, y sobre las recomendaciones de algunos cambios que el otro dia te los mande, ya solucionamos todo eso??

  > dale, vamos con las tres recomendaciones
- L2 (2026-11-05) Exploración: las cinco altas y cambios de contraseña pasan por `auth/model/PoliticaDeContrasena.validar` (`UsuarioService.java:54,109`, `AuthService.java:113`, `UsuariosParaPlataformaImpl.java:43,96`); el front ya muestra los errores por campo del servidor (`usuarios.ts:169`, `admin-ferreterias.ts:222,304`). `leerNumero` (`dialogo-ajuste-stock.ts:78`) lee «1.500» como 1,5. Hay 13 `input[type=number]`, entre ellos el precio manual (`desglose-precio.html:86`) y el margen por defecto (`configuracion-precios.html:55`).
