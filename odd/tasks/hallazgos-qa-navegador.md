# hallazgos-qa-navegador

Objetivo: corregir los dos hallazgos de severidad media del QA en el navegador del 2026-11-04 (saldo de «Movimientos de stock» con el seed demo y la precisión del reloj en Linux) · Por qué: informe `Projects/Ferreteria/wiki/qa-navegador-2026-11-04.md` del vault (rama `claude/youthful-fermat-8ttqna`), hallazgo 1 y «nanosegundos en Linux» de la segunda ronda.
Rama: `fix/hallazgos-qa-navegador` desde `f2c5eeb` · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Dtest=<Clase>` / suite `./mvnw test` · Mirror Engram: `odd/hallazgos-qa-navegador/tasks` (pending: el modo automático no deja escribir en Engram)
Pronóstico: ~220 líneas cambiadas · Ruteo: caso 5 (Opus de punta a punta: reloj compartido por toda la app)

## Specs

Alcance: integrar el arreglo del saldo que ya existe en la rama `claude/vigilant-pasteur-l6sz43` (commits `106610f` y `e95ed4d`, desde `01767ae`) y recortar la hora de la aplicación a microsegundos · Fuera de alcance: los hallazgos menores del informe (clic en el renglón del ticket, 404 de un artículo ajeno, campo de Etiquetas, Vendedor en `/admin/ferreterias`) y la carrera F4 + escaneo.
Restricciones: sin migraciones de esquema; la de `db/dev-data` no corre en producción. Las columnas de hora son todas `DATETIME(6)` o `TIMESTAMP(6)`.

Pedido (2026-11-04), respuesta de Luca «dale arranca» a: «Te recomiendo arrancar por los dos de gravedad media: traer el arreglo del saldo que ya existe y recortar el reloj a microsegundos. ¿Arranco por esos dos?»

- S1: «traer el arreglo del saldo que ya existe» — Aceptación: con el seed demo, el saldo de «Movimientos de stock» no da negativo y una fecha corrida no lo rompe (specs S1 y S2 de `odd/tasks/cu-09-saldo-movimientos-demo.md`) — Checks: `MovimientosArticuloIntegrationTest`, `StockInicialDemoIntegrationTest`
- S2: «recortar el reloj a microsegundos» — Aceptación: el `Clock` de Spring (`RelojConfig`) y `HoraArgentina.ahora()` dan horas sin nanosegundos sueltos, así la primera respuesta de una cobranza, una anulación o `habilitadaEn` coincide con la repetida y con el GET también en Linux (spec CU-12 punto 3) — Checks: test unitario del recorte; `CobranzaIntegrationTest`, `AnulacionIntegrationTest`, `ConfigArcaAdminIntegrationTest` en un contenedor Linux
- S3 (supuesto): recortar (truncar) y no redondear: MySQL redondea al guardar, pero una vez recortada la hora no le queda nada que redondear.

## Tasks

- [x] T1 · S1 · ruta: inline · Aceptación: los commits de la otra rama aplicados sobre `f2c5eeb` sin `Co-Authored-By`, con sus tests en verde · Evidencia: RED en la Mac con el código de `main` (`fechaCorridaNoCorrompeElSaldo`: VENTA sale primera; `StockInicialDemoIntegrationTest` 2/2 fallan) → GREEN con el arreglo: 16 clases y 217 tests (los dos nuevos, `*Stock*Test`, `MigracionV4Test`, `ModulosArchTest`, `TenantScopingArchTest`) sin fallas; commits `4401e88` (fix) y el de docs que sigue · Risk: item 1 (migración de `db/dev-data`, solo dev y test)
- [ ] T2 · S2 S3 · ruta: inline · Aceptación: RED → GREEN del recorte; los 9 tests de integración que fallaban en Linux, en verde en un contenedor Linux · Evidencia: — · Risk: item 3 (reloj compartido por toda la app)
- [ ] T3 · S1 S2 · ruta: inline · Aceptación: suite backend en verde · Evidencia: — · Risk: —

## Log

- L1 (2026-11-04) Pedido original, textual:
  > dale arranca
  (respuesta a la recomendación citada en Specs).
- L2 (2026-11-04) Exploración:
  - El informe probó `main` `01767ae`. En `f2c5eeb` siguen los dos: `MovimientoStockRepository.java:62` ordena por `fecha desc, id desc`, y `RelojConfig.java:21` devuelve `Clock.system(BUENOS_AIRES)` sin recortar.
  - `HoraArgentina.ahora()` usa `LocalDateTime.now(ZONA)` (reloj del sistema, no el bean) en 8 archivos de `src/main`.
  - Los commits de la otra rama traen `Co-Authored-By`, que Luca no permite: se reaplican con `cherry-pick --no-commit` y se commitean sin esa línea.
