# cu-09-saldo-movimientos-demo

Objetivo: que «Movimientos de stock» (CU-09, `/articulos/{id}/movimientos`) muestre saldos correctos en una base dev/test recién creada · Por qué: QA en browser sobre `main` `01767ae` (2026-11-04): TOR-001 mostraba «Stock inicial demo» +500 → 477 arriba de las ventas del día y saldos negativos debajo (-23, -25, -15, -5, -3, -2).
Rama: `claude/vigilant-pasteur-l6sz43` desde `01767ae` · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Dtest=<Clase>` / suite `./mvnw test`

## Causa

- `db/dev-data/V1003__movimientos_demo.sql` inserta el AJUSTE «Stock inicial demo» sin `fecha`: toma el `DEFAULT CURRENT_TIMESTAMP(6)` de V4, la hora de MySQL (UTC en docker-compose y en Testcontainers). La aplicación guarda la hora de Buenos Aires convertida según la zona de la JVM (`hibernate.jdbc.time_zone=UTC`, spec «Despliegue: zona horaria»); con la JVM en UTC el ajuste queda ~3 horas en el futuro.
- `MovimientoStockRepository.deArticulo` ordenaba por `fecha desc, id desc` y el saldo se calcula hacia atrás desde el stock actual: la fila adelantada sale primera y corre todos los saldos.

## Specs

- S1: el seed demo tiene una fecha coherente y anterior a cualquier movimiento real, con una migración nueva de `db/dev-data` (V1003 no se toca: Flyway valida su checksum; dev usa `out-of-order: true`) — Checks: `StockInicialDemoIntegrationTest`
- S2: una fecha corrida no puede romper el saldo: el orden del listado y de `sumaPosterior` es el `id` (el movimiento se inserta en la misma transacción que mueve el stock, con el artículo bloqueado: el `id` es el orden en que se aplicó), como el extracto de cuenta corriente — Checks: `MovimientosArticuloIntegrationTest#fechaCorridaNoCorrompeElSaldo`

## Tasks

- [x] T1 · S1 S2 · ruta: inline · Evidencia: RED `MovimientosArticuloIntegrationTest` 1 falla (VENTA con +3 h sale primera) y `StockInicialDemoIntegrationTest` 2 fallas (el ajuste demo en 2026-11-04T16:03 contra ahora 13:03 en Buenos Aires) → GREEN con `V1007__fecha_stock_inicial_demo.sql` (fecha fija `2026-01-01 12:00:00`) y orden por `id`; focalizados (stock, `MigracionV4Test`, `ModulosArchTest`, `TenantScopingArchTest`, ventas facturadas y NC) en verde, también con `TZ=America/Argentina/Buenos_Aires`; suite backend 5972 tests, 10 fallas que son las mismas en `main` sin el cambio (ver L2) · Risk: none (sin migración de esquema; la de dev-data no corre en prod)

## Otras tablas con `DEFAULT CURRENT_TIMESTAMP`

`comprobante.fecha`, `presupuesto.fecha`, `remito.fecha`, `compra.registrada`, `importacion_lista.fecha`, `historial_precio.fecha`, `articulo.creado_en`, `ferreteria.creado_en`, `usuario.creado_en`. La aplicación siempre las escribe (ningún mapeo es `insertable = false`); solo los seeds dependen del DEFAULT:
- `V1000`: `ferreteria.creado_en` y `usuario.creado_en` de las demo (el panel del super admin muestra `creadoEn`, sin orden ni saldo).
- `V1001`: `articulo.creado_en` y el `historial_precio` inicial de los 8 demo; entran en «Costo al» (`ArticuloRepository.costoAl`, `greatest(...)`): con la JVM en UTC, un seed creado entre las 21 y las 24 de Buenos Aires da «Costo al» de mañana. Cosmético, sin saldo; no se corrige acá.

## Log

- L1 (2026-11-04) Pedido: corregir el seed y blindar el saldo contra un desfase de fecha, test-first, commits en español.
- L2 (2026-11-04) Suite en un contenedor Linux con la JVM en UTC: 10 fallas preexistentes, iguales en `01767ae` sin el cambio (`HistorialPrecioTest.fechaEsAhora` espera la JVM en Buenos Aires; `CobranzaIntegrationTest`, `AnulacionIntegrationTest`, `ConfigArcaAdminIntegrationTest` y las de concurrencia HTTP comparan nanosegundos del reloj de Linux contra los microsegundos que guarda MySQL). Ajenas a esta rama.
