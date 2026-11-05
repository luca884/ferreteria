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

- [ ] T1 · S1 · ruta: inline · Aceptación: RED `npm audit` con 6 avisos → GREEN 0 avisos; `npm ls` sin versiones mezcladas de `@angular/*` · Risk: item 5 (dependencias)
- [ ] T2 · S2 · ruta: inline · Aceptación: suite frontend, `ng build` y ego-browser (login, Mostrador con una venta, Artículos, Configuración) · Risk: item 5
- [ ] T3 · S1 S2 · ruta: `odd-verifier` (opus) · Aceptación: pass sin blockers · Risk: —

## Log

- L1 (2026-11-05) Pedido original, textual: ver «Pedido» en Specs. Antes, Luca preguntó «porque decimes de actualizar angualr??»; se le explicó que los avisos son de herramientas de desarrollo y que es mantenimiento, no urgente.
- L2 (2026-11-05) Exploración (`npm audit --json`, `npm ls`, `npm view`):
  - `zod` 4.6.5: lo fija exacto `@angular/cli` 22.2.2 (la 22.2.6 fija 4.6.6). También lo declara `@angular/forms`, pero su código no lo importa y el build no lo incluye.
  - `vite` 8.3.0: lo trae `@angular/build` 22.2.2 (la 22.2.6 trae 8.3.5). Vitest ya usa 8.3.4.
  - `undici` 8.11.2: lo trae `jsdom` 30.1.2 (`^8.11.2`); con el rango alcanza para 8.12.x.
  - `fast-uri` 3.1.8: lo trae `ajv` (`^3.0.1`) de `@angular-devkit/core`; 3.1.9 entra en el rango.
