# cu-07-tope-descuento

Objetivo: cada usuario tiene un tope de descuento y el mostrador no deja pasarlo · Por qué: hoy cualquier vendedor puede dar hasta 99,99 % (E2E 2026-10-22, ítem 8; GDS «Límite de Descuento» por usuario)
Rama: `feat/cu-07-tope-descuento` desde `b81e7f8` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / suite `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` + `npx ng build` · Mirror Engram: `odd/cu-07-tope-descuento/tasks` (ok)
Pronóstico: ~700 líneas cambiadas · Heurística por tarea: ~400 (no es límite) · Modelo: caso 5 (Opus de punta a punta: migración + contrato de sesión)

## Specs

Alcance: usuario (migración, alta y edición, sesión), control del descuento al confirmar venta, presupuesto y remito, pantallas de Usuarios y Mostrador · Fuera de alcance: autorización del Dueño para pasar el tope (GDS no la tiene), topes por rubro o por artículo.
Restricciones: el control lo hace el backend; la pantalla solo adelanta el aviso. Usuarios de otra ferretería siguen dando 404.

- S1: "Usuarios: un campo "Tope de descuento (%)" por usuario en la pantalla que ya existe" — Aceptación: el Dueño carga y edita el tope (0 a 99,99, hasta 2 decimales, vacío = sin tope) y lo ve en la lista — Checks: integración `UsuarioController` + spec de usuarios + ego-browser
- S2: "Mostrador: si el descuento supera el tope del usuario, el sistema lo frena con un aviso." — Aceptación: venta, presupuesto o remito con un descuento mayor al tope da 400 «Tu tope de descuento es N %.» en la clave del renglón o del descuento global, y no se registra; el diálogo de descuento avisa antes de aplicar — Checks: integración ventas + spec de mostrador + ego-browser
- S3: "El dueño no tiene tope." — Aceptación: un Dueño no guarda tope (el campo no aparece para el rol Dueño y el backend lo deja vacío) y nunca se lo frena — Checks: integración
- S4: "Usuarios ya cargados: quedan sin tope" — Aceptación: migración V25 aditiva, columna nula; los usuarios existentes quedan con NULL — Checks: `MigracionV25Test` (incluye rollback)
- S5 (supuesto): se compara el descuento efectivo de cada renglón, renglón y global combinados (1 − (1 − r)(1 − g)), para que 10 % + 10 % no esquive un tope de 10 %. Igual al tope entra.
- S6 (supuesto): el tope es el del usuario que confirma la venta, el presupuesto o el remito (el usuario del JWT).
- S8: "dale" (Luca, 2026-10-22) a: "al pasar a venta se respetan los descuentos que ya traía el documento, y el tope del vendedor vale solo si agrega o sube un descuento" — Aceptación: un Vendedor con tope pasa a venta el presupuesto o remito del Dueño con más descuento que su tope; si sube el descuento del renglón o el global por encima del documento y de su tope, 400 — Checks: `TopeDescuentoIntegrationTest`
- S7: aislamiento por tenant (regla 7 de CLAUDE.md): el tope se lee del usuario de la ferretería del JWT; editar un usuario de otra ferretería sigue dando 404 — Checks: integración

## Tasks

- [x] T1 · S4 · ruta: inline · Aceptación: V25 + `MigracionV25Test` + rollback + `MigracionV6Test` a V25 · Evidencia: commit `6a09e35` · RED 6 errores (columna inexistente) → GREEN `MigracionV25Test` 6/6 + `MigracionV6Test` 14/14 · Risk: item 1 (migración)
- [x] T2 · S1 S3 S7 · ruta: inline · Aceptación: `Usuario.topeDescuento`, alta/edición/respuesta y sesión con el tope; Dueño sin tope · Evidencia: commit `eb82afc` · RED `UsuariosTopeDescuentoIntegrationTest` 6 fallas → GREEN 6/6; `UsuariosIntegrationTest.listaActivos` fijaba los campos de la respuesta y suma `topeDescuento` · Risk: item 3 (contrato `/api/usuarios` y sesión, aditivo)
- [x] T3 · S2 S3 S5 S6 · ruta: inline · Aceptación: venta, presupuesto y remito rechazan un descuento sobre el tope; puerto `auth.api` con tipos del JDK · Evidencia: commit `bd04833` · RED `TopeDescuentoIntegrationTest` 4 fallas (201 en vez de 400) → GREEN 7/7 con `ModulosArchTest`, `TenantScopingArchTest`, `RegistroVentaTest` y los de descuentos, presupuesto y remito · Risk: none
- [x] T4 · S1 S3 · ruta: inline · Aceptación: pantalla de Usuarios con el campo y la columna; ego-browser · Evidencia: commits `0666dfc`, `dc96df3` · RED compilación (campo inexistente) → GREEN `usuarios.spec.ts` 34/34 · ego-browser OK · Risk: none
- [x] T5 · S2 · ruta: inline · Aceptación: el diálogo de descuento del mostrador avisa del tope; el 400 del backend se muestra; ego-browser · Evidencia: commit `83afe10` · diálogo: tests escritos junto con el código (sin RED observado) 21/21; mostrador RED 1 falla → GREEN 254/254 · ego-browser OK · Risk: none
- [x] T6 · S2 · ruta: inline · Aceptación: `docs/spec.md` CU-07 dice el tope por usuario · Evidencia: commit `83afe10` · Risk: none
- [x] T7 · S8 · ruta: inline · Aceptación: «Pasar a venta» respeta los descuentos del presupuesto o remito · Evidencia: RED 3 fallas (400 en vez de 201, mensaje) → GREEN `TopeDescuentoIntegrationTest` 15/15 con `RegistroVentaTest`, `PresupuestoVentaIntegrationTest`, `RemitoVentaIntegrationTest` y las ArchTest · Risk: none

## Log

- L1 (2026-10-22) Pedido original, textual:
  > dale me parece correcto

  Sobre la propuesta: "Usuarios: un campo "Tope de descuento (%)" por usuario en la pantalla que ya existe (necesita migración). Mostrador: si el descuento supera el tope del usuario, el sistema lo frena con un aviso. El dueño no tiene tope. Usuarios ya cargados: quedan sin tope". Antes, a «como lo hace gds»: GDS tiene un «Límite de Descuento» por usuario.
- L2 (2026-10-22) Exploración: `Usuario` (backend/src/main/java/com/ferreteria/auth/model/Usuario.java), `UsuarioService.crear`/`actualizar`, `SesionResponse` vía `AuthService.toSesion`; `ValidacionTicket.renglones` se llama desde `RegistroVenta`, `RegistroPresupuesto` y `RegistroRemito`; `auth.api` solo depende del JDK (`ModulosArchTest`). Front: `mostrador/dialogo-descuento.ts`, `mostrador.ts` (manejo de 400), `usuarios.ts/html`.
- L3 (2026-10-22) Progreso: T1-T6 hechas. Suite backend 4393 tests, 0 fallas; suite frontend 2838/2838; `ng build` OK. ego-browser: el Dueño le carga 10 % al vendedor demo y la lista muestra «10 %» (el Dueño «—», otro vendedor «Sin tope»; con rol Dueño el campo no aparece); el vendedor ve «Tu tope es 10 %.» en F7, 15 % da «Tu tope de descuento es 10 %.» y 10 % se aplica; 10 % + 5 % general al guardar el presupuesto marca el renglón con «Sumado al descuento general, pasa tu tope de descuento de 10 %.». Después se volvió a dejar al vendedor demo sin tope. Límite conocido: la sesión guardada no se refresca sola; si el Dueño cambia el tope, el aviso previo del diálogo usa el valor viejo hasta el próximo login (el backend frena igual). PUT `/api/usuarios/{id}` sin `topeDescuento` deja al usuario sin tope (el front siempre lo manda). Tamaño: ~970 líneas (>400, single-pr, sin partir). · Pendiente: `odd-verifier` (Risk items 1 y 3). · Próximo paso: verificación independiente.
- L4 (2026-10-22) Verificación independiente (`odd-verifier`, opus): sin blockers, S1-S7 pasan. Suite backend 4400/0 (con sus 7 sondas), frontend 2839/2839, `ng build` OK. Sondas convertidas en regresión en `TopeDescuentoIntegrationTest` (rango antes que tope, tope antes que 409, repetición de clave anterior al tope, tope 0 en el renglón 1, 99,99 combinado) y en `MigracionV25Test` (el CHECK rechaza pasar a Dueño con tope). Corregido el comentario del rollback: en prod revertir el código no alcanza, hay que sacar el CHECK. Advertencias: el diálogo del renglón no mira el combinado (lo frena el backend); PUT sin tope lo saca (único consumidor, el front, siempre lo manda). Decisión abierta para Luca: un Vendedor con tope no puede pasar a venta un presupuesto o remito del Dueño con un descuento mayor a su tope (sigue S6 tal cual; antes se podía).
- L5 (2026-10-22) Luca, ante el aviso 1 del verificador («como lo tiene gds»): el relevamiento no cubre el caso (G51/G101 dicen el límite por usuario; G56 el `Traer`). Recomendé respetar los descuentos del documento y Luca respondió, textual:
  > dale

  Hecho como T7: `TopeDeDescuento.Autorizados` (global del documento y descuento de cada artículo); se compara contra el mayor entre el tope y lo del documento. Límite: en el mostrador, reabrir F7 sobre un renglón traído con más descuento que el tope y confirmar el mismo valor lo rechaza el diálogo (Esc lo deja como estaba).
