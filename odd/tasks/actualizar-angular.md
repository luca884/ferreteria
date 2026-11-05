# actualizar-angular

Objetivo: sacar los 6 avisos de `npm audit` del frontend (1 bajo, 1 moderado, 4 altos) subiendo Angular dentro de la 22.2 · Por qué: las versiones de Angular fijan `zod`, `vite` y sus dependencias, así que `npm update` solo no alcanza; ninguno llega al bundle de la app (herramientas de desarrollo y tests), es mantenimiento.
Rama: `chore/actualizar-angular` desde `e132a39` · Entrega: single-pr · Runner: `cd frontend && npm audit` / suite `npx ng test --watch=false` + `npx ng build` · Mirror Engram: `odd/actualizar-angular/tasks`
Pronóstico: ~20 líneas escritas (más el `package-lock.json` generado) · Ruteo: caso 5 (Opus de punta a punta: dependencias, alto riesgo item 5)

## Specs

Alcance: `frontend/package.json` y `frontend/package-lock.json` · Fuera de alcance: subir de versión mayor (jsdom 31, Angular 23) y el backend.

Pedido (2026-11-05):
- «Dale, hacé la actualización de Angular ahora»

- S1: «hacé la actualización de Angular ahora» — Aceptación: `@angular/*` en la última 22.2 (core 22.2.4; cli, build y cdk 22.2.6), con `ng update`, y las dependencias transitivas dentro de sus rangos. `npm audit` sin avisos. Los rangos vulnerables son `zod <4.6.6`, `vite >=8.3.0 <=8.3.2`, `undici >=8.0.0 <8.12.2` y `fast-uri <3.1.9` — Checks: `npm audit`, `npm ls`.
- S2: la app sigue igual (supuesto: una actualización de parche no cambia comportamiento) — Aceptación: suite del frontend y `ng build` en verde; la app local abre y cobra como antes — Checks: suite, build, ego-browser.

## Tasks

- [x] T1 · S1 · ruta: inline · Aceptación: RED `npm audit` con 6 avisos → GREEN 0 avisos; `npm ls` sin versiones mezcladas de `@angular/*` · Evidencia: RED «6 vulnerabilities (1 low, 1 moderate, 4 high)» → `ng update @angular/core@22.2.4 @angular/cli@22.2.6 @angular/cdk@22.2.6` dejó solo `undici` 8.11.2 → `npm update undici` (8.12.4, dentro de `^8.11.2` de jsdom) → «found 0 vulnerabilities». `npm ls` sin errores; todo `@angular/*` en 22.2.4 (cdk, build y cli en 22.2.6). Fuera de `@angular/*`, el lockfile cambia solo herramientas de build y test: `zod` 4.6.6, `vite` 8.3.5, `undici` 8.12.4, `fast-uri` 3.1.9, `postcss` 8.5.32, `oxc-parser` 0.156.0 y sus bindings, `rolldown` 1.2.16 y sus bindings; nuevos y opcionales de desarrollo: `@emnapi/*`, `@napi-rs/wasm-runtime`, `@tybys/wasm-util` y `@oxc-parser/binding-wasm32-wasi` (corrección del verificador, L3) · commit `bbf5e24` · Risk: item 5 (dependencias)
- [x] T2 · S2 · ruta: inline · Aceptación: suite frontend, `ng build` y ego-browser (login, Mostrador con una venta, Artículos, Configuración) · Evidencia: suite 155 archivos, 3855/3855; `ng build` OK (solo el aviso de presupuesto de `dialogo-cobro`, que ya estaba); el bundle no trae `zod`. App local como Dueño de El Tornillo: login, Artículos, ficha del artículo 1, Clientes, Caja, Ventas, Margen y redondeo y Usuarios abren sin error; en el Mostrador, F4 y «Cobrar sin factura» dieron «Venta N.º 12 cobrada (no facturable): $ 27.285,49» · Risk: item 5
- [x] T3 · S1 S2 · ruta: `odd-verifier` (opus) · Aceptación: pass sin blockers · Evidencia: ver L3 · Risk: —

## Log

- L1 (2026-11-05) Pedido original, textual: ver «Pedido» en Specs. Antes, Luca preguntó «porque decimes de actualizar angualr??»; se le explicó que los avisos son de herramientas de desarrollo y que es mantenimiento, no urgente.
- L2 (2026-11-05) Exploración (`npm audit --json`, `npm ls`, `npm view`):
  - `zod` 4.6.5: lo fija exacto `@angular/cli` 22.2.2 (la 22.2.6 fija 4.6.6). También lo declara `@angular/forms`, pero su código no lo importa y el build no lo incluye.
  - `vite` 8.3.0: lo trae `@angular/build` 22.2.2 (la 22.2.6 trae 8.3.5). Vitest ya usa 8.3.4.
  - `undici` 8.11.2: lo trae `jsdom` 30.1.2 (`^8.11.2`); con el rango alcanza para 8.12.x.
  - `fast-uri` 3.1.8: lo trae `ajv` (`^3.0.1`) de `@angular-devkit/core`; 3.1.9 entra en el rango.
- L3 (2026-11-05) `odd-verifier` (opus) sobre `c7e4c09`: **pass**, sin blockers.
  - S1: `npm ci` desde el lockfile (npm 11.19 y npm 10.9.4 del `packageManager`) con «found 0 vulnerabilities»; base `e132a39` con «6 vulnerabilities». `npm ls` sin errores; `@angular/*` sin versiones mezcladas y en la última 22.2. `npm install --package-lock-only` deja el lockfile idéntico.
  - S2: suite 3855/3855; `ng build` OK con el mismo aviso de `dialogo-cobro` que la base; `dist/` sin `zod`; bundle inicial +1 kB (323,54 → 324,53 kB).
  - Lockfile: todo lo que cambia es Angular o su tooling de build y test; ningún paquete de runtime fuera de `@angular/*` y ningún salto mayor.
  - A1: la evidencia de T1 no nombraba `rolldown` ni algunos binarios opcionales nuevos. **Corregido** en T1.
  - A2 (upstream, inofensivo): `oxc-parser` 0.156 instala `@oxc-parser/binding-wasm32-wasi` con `@emnapi` 2.0.0-alpha.6 también en macOS; es opcional y de desarrollo.
  - A3 (no lo causa el cambio): npm 11 lista 5 paquetes con scripts de instalación sin `allowScripts` (esbuild, lmdb, `@parcel/watcher`, fsevents, msgpackr-extract).
  - Probes sugeridos (`npm audit` en CI, lockfile en sync): el repo no tiene CI desde que se dio de baja Railway; quedan anotados, sin cambio.
