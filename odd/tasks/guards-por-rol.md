# guards-por-rol

Objetivo: que cada pantalla por rol tenga su control también en el frontend, además del 403 del servidor · Por qué: el QA del 2026-11-04 (hallazgo 3) encontró que un Vendedor abría `/admin/ferreterias`. Las rutas no tenían guard de rol «a propósito» (`app.routes.ts`), y Luca pidió seguridad en las dos capas.
Rama: `fix/guards-por-rol` desde `5124855` · Entrega: single-pr · Runner: `cd frontend && npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` + `npx ng build` · Mirror Engram: `odd/guards-por-rol/tasks`
Pronóstico: ~300 líneas cambiadas · Ruteo: caso 5 (Opus de punta a punta: seguridad de todas las rutas, alto riesgo item 2)

## Specs

Alcance: las rutas del shell en `frontend/src/app/app.routes.ts` · Fuera de alcance: el backend, que ya responde 403 en cada caso (`SecurityConfig` y `@PreAuthorize`); los avisos «solo del Dueño» de cada pantalla, que quedan como una capa más.

Pedido (2026-11-05):
- «Como que un vendedor pued entrar al supermercado admin que solamente puedo entrar yo con mis credenciales»
- «Siempre tiene que haber en todos lados […] seguridad en todos lados»
- «Dale me parece bien», en respuesta al orden «cerrar esta rama, después los controles de rol en todas las pantallas y por último actualizar Angular».

- S1: «seguridad en todos lados» — Aceptación: cada ruta del shell declara los roles que pueden entrar. Si alguien entra con otro rol, aunque escriba la URL a mano, la ruta no se abre y va a su inicio: el Vendedor al Mostrador y el super admin a Ferreterías. Una ruta del shell sin roles declarados no se abre para nadie (cerrada por defecto) — Checks: spec del guard y spec de las rutas.
- S2: «un vendedor pued entrar al supermercado admin» — Aceptación: el mapa de roles por ruta es este:
  - **Dueño y Vendedor:** `mostrador`, `presupuestos`, `remitos`, `clientes`, `caja`, `rubros`, `articulos`, `articulos/:id` (ficha; el Vendedor la ve sin costos), `proveedores`.
  - **Solo Dueño:** `clientes/:id/cuenta`, `caja/historial`, `articulos/nuevo`, `articulos/:id/movimientos`, `articulos/:id/clientes`, `compras`, `compras/nueva`, `compras/:id`, `ventas`, `reportes`, `stock-bajo-minimo`, `controles-de-datos`, `etiquetas`, `importaciones`, `actualizacion-masiva`, `configuracion/cotizacion`, `configuracion/precios`, `configuracion/ventas`, `configuracion/datos-fiscales`, `configuracion/facturacion`, `configuracion/formas-pago`, `usuarios`.
  - **Solo super admin:** `admin/ferreterias`.
  - **Todo usuario logueado:** `mi-contrasena`.
  - Sale de `menu.ts` (`soloDueno`), de los avisos de cada pantalla y de `SecurityConfig`/`@PreAuthorize` — Checks: spec de las rutas que recorre el mapa completo.
- S3: «Dale» (Luca, 2026-11-05, a «propongo mandarlo a su inicio sin mostrar un mensaje») — Aceptación: sin permiso, se redirige al inicio del rol sin aviso, como con una URL inexistente (`**`); sin sesión, al login.

## Tasks

- [x] T1 · S1 S3 · ruta: inline · Aceptación: `rolGuard` (CanActivateChild en el shell) lee `data.roles` de la ruta; sin roles, cerrada; con otro rol, va al inicio del rol. RED → GREEN en `rol.guard.spec.ts` · Evidencia: RED (no compilaba: no existía `./rol.guard`) → GREEN `rol.guard.spec` 8/8; `inicioDe(rol)` sale de `inicioPorRol` para no duplicar el inicio de cada rol · commit `a42de1d` · Risk: item 2 (seguridad)
- [x] T2 · S1 S2 · ruta: inline · Aceptación: `data.roles` en todas las rutas del shell según S2; spec de rutas que recorre el mapa y falla si una ruta del shell no declara roles; comentarios «Sin guard de rol» actualizados · Evidencia: con el `app.routes.ts` de `main`, el spec nuevo falla (mapa, guards del shell, navegaciones del Vendedor) → GREEN con las rutas nuevas; specs de `core/auth` y rutas 79/79. Los tests viejos que exigían «sin guard de rol» se reemplazaron por el mapa · commit `a42de1d` · Risk: item 2
- [x] T3 · S1-S3 · ruta: inline · Aceptación: suite frontend, `ng build` y ego-browser (Vendedor en `/admin/ferreterias`, `/ventas` y `/configuracion/facturacion`; super admin en `/mostrador`; Dueño en todas sus pantallas) · Evidencia: suite frontend 155 archivos, 3749/3749; `ng build` OK. App local: el Vendedor que escribe `/admin/ferreterias`, `/ventas`, `/configuracion/facturacion`, `/usuarios`, `/clientes/1/cuenta` o `/articulos/nuevo` cae en `/mostrador`, y abre `/caja`, `/articulos/1` y `/mi-contrasena`. El super admin que escribe `/mostrador`, `/articulos` o `/configuracion/facturacion` cae en `/admin/ferreterias`, y abre «Cambiar mi contraseña». El Dueño abre todas sus pantallas, y `/admin/ferreterias` lo lleva a `/mostrador` · Risk: —
- [ ] T4 · S1-S3 · ruta: `odd-verifier` (opus) · Aceptación: pass sin blockers · Risk: —

## Log

- L1 (2026-11-05) Pedido original, textual: ver «Pedido» en Specs.
- L2 (2026-11-05) Exploración:
  - Guards actuales: `authGuard` (sesión) y `contrasenaGuard` (cambio obligatorio), en `core/auth/`.
  - La sesión (rol incluido) se lee de `localStorage` al arrancar (`AuthService._sesion`), así que el guard la tiene disponible de forma sincrónica.
  - `inicioPorRol` ya decide el inicio de cada rol (`core/auth/inicio-por-rol.ts`).
  - El menú ya filtra por rol con `soloDueno` (`layout/menu.ts`), y el super admin no ve el menú de la ferretería (`shell.html:13`).
  - Backend: `SecurityConfig.java:43-81` y `@PreAuthorize` en los controladores (por ejemplo, el alta y la edición de artículos son solo del Dueño).
