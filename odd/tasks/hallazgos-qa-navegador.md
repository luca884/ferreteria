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
- [x] T2 · S2 S3 · ruta: inline · Aceptación: RED → GREEN del recorte; los 9 tests de integración que fallaban en Linux, en verde en un contenedor Linux · Evidencia: RED en un contenedor Linux (`eclipse-temurin:17-jdk`, JVM en UTC) con `main`: 319 tests y 9 fallas, las del informe (`ConfigArcaAdmin` 2, `AnulacionIntegrationTest` 2, `CobranzaIntegrationTest` 3, `CobranzaConcurrenciaHttp` 1, `AnulacionConcurrenciaHttp` 1; p. ej. `.675534169Z` contra `.675534Z`). RED en la Mac de `HoraArgentinaTest` (no existía `enMicrosegundos`) → GREEN: Mac `HoraArgentinaTest` 4/4 y `AnulacionIntegrationTest` 20/20; Linux 29 clases y 331 tests sin fallas · commit `7fe28ac` · Risk: item 3 (reloj compartido por toda la app)
- [x] T3 · S1 S2 · ruta: inline · Aceptación: suite backend en verde · Evidencia: suite Mac en `7fe28ac`: 6180 tests, 0 fallas y 1 error ajeno a la rama (ver L3); suite Mac en `d4d5eb8`: 6185/6185, BUILD SUCCESS (8:08 min) · Risk: —

## Log

- L1 (2026-11-04) Pedido original, textual:
  > dale arranca
  (respuesta a la recomendación citada en Specs).
- L2 (2026-11-04) Exploración:
  - El informe probó `main` `01767ae`. En `f2c5eeb` siguen los dos: `MovimientoStockRepository.java:62` ordena por `fecha desc, id desc`, y `RelojConfig.java:21` devuelve `Clock.system(BUENOS_AIRES)` sin recortar.
  - `HoraArgentina.ahora()` usa `LocalDateTime.now(ZONA)` (reloj del sistema, no el bean) en 8 archivos de `src/main`.
  - Los commits de la otra rama traen `Co-Authored-By`, que Luca no permite: se reaplican con `cherry-pick --no-commit` y se commitean sin esa línea.
- L3 (2026-11-04) Suite backend en la Mac sobre `7fe28ac`: 6180 tests, 0 fallas, 1 error: `StockServiceIntegrationTest.unaTransaccionRevertidaNoDejaNada`, `Duplicate entry '30-79900001-1'`. Es ajeno a la rama: `ArticulosParaVentaCostoRealTest` (de `2ccb38c`, ya en `main`) genera los CUIT con el mismo patrón (`79900000 + SECUENCIA`, dígito 1). Cuando surefire pone las dos clases en la misma JVM (mismo MySQL), chocan. Reproducido con `-Dtest='ArticulosParaVentaCostoRealTest,StockServiceIntegrationTest' -DforkCount=1 -Dsurefire.runOrder=alphabetical`. Ninguna de las dos clases se toca en esta rama. Arreglo posible: otro rango de CUIT en uno de los dos tests; no se hizo porque queda fuera del pedido. Próximo paso: `odd-verifier` (Risk item 3).
- L4 (2026-11-04) `odd-verifier` (opus) sobre `7d83398`: **pass**, sin blockers.
  - S1: todos los caminos que insertan en `movimiento_stock` lo hacen después del `UPDATE articulo` de `ajustarStock`, en la misma transacción (`StockService.java`: venta :67-70, remito :86-93, anulación de remito :106-113, compra :127-134, factura de remito :153-160, NC :176-181, ajustes :243-245 y :287-289). El orden por id es el de aplicación. V1007 corre solo en dev y test (`application-prod.yml:4` usa solo `db/migration`).
  - S2: RED en el baseline en Linux con las 9 fallas → GREEN en HEAD. El recorte también arregla venta, remito y presupuesto, que ningún test comparaba. Los `Instant.now()` sin reloj solo se usan para el `timestamp` de las respuestas de error, que no se guarda.
  - A1, causado por el cambio: `Clock.tick(base, 1 µs).millis()` divide por cero en JDK 17. **Corregido** en `d4d5eb8` con un `Clock` propio que recorta `instant()` y delega `millis()`, `getZone()` y `withZone()`. RED (`millisDelReloj`: `ArithmeticException`) → GREEN (`HoraArgentinaTest` 6/6).
  - A2, preexistente: con el filtro Desde/Hasta y una fecha corrida en medio de la secuencia de ids, el saldo de las filas de abajo se corre, porque la fila que el filtro deja afuera no se resta (`MovimientosArticulo.java:59-66`). Después de V1007, el único origen realista es una venta y una NC concurrentes del mismo artículo con la medianoche entre las dos. **No se tocó**: queda para que Luca decida si se calcula el saldo de cada fila sin el filtro o si se documenta la salvedad, como en CU-12.
  - A3, menor: `order by m.id desc` ya no aprovecha `ix_movimiento_articulo` para ordenar y hace filesort sobre los movimientos del artículo. Con el volumen de una ferretería no importa.
  - Probes commiteados como tests en `d4d5eb8`: `FechaDeLaRespuestaIntegrationTest` (fallaba en Linux en el baseline) y `MovimientosTodosLosCaminosIntegrationTest`. Linux, 31 clases y 336 tests sin fallas; Mac, los nuevos en verde.
