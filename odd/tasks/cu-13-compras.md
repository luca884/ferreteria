# cu-13-compras

Objetivo: registrar una compra (factura o remito del proveedor) que sume el stock y actualice el costo y el precio de los artículos · Por qué: punto 4 del orden acordado (compras y proveedores), tramo 1
Rama: `feat/cu-13-compras` desde `d73b24e` · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Dtest=<Clase>` / `cd frontend && npx ng test --watch=false --include=<spec>` · Mirror Engram: `odd/cu-13-compras/tasks` (ok)
Pronóstico: ~1.800 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: CU-13 de `docs/spec.md` (puntos 1 a 6) · Fuera de alcance: el bloque «Fuera del tramo 1» de CU-13; precio manual y ajustes sobre el precio final (feature aparte, posterior)
Restricciones: reglas de arquitectura de `CLAUDE.md` (tenant, `TenantScopedRepository`, 404 entre ferreterías, `BigDecimal`, Flyway, test de aislamiento por endpoint); `compras` habla con `catalogo` y `stock` solo por sus `api`.

- S1: "Primer tramo: registrar una compra (la factura del proveedor) que ingrese el stock de los artículos y actualice su costo." — Aceptación: CU-13 puntos 1, 2 y 4 — Checks: tests de dominio e integración de `compras`
- S2: "dale, como lo hace gds" (último costo: la compra pisa el costo del artículo) — Aceptación: CU-13 punto 4, costo = costo del renglón — Checks: test de servicio y de integración
- S3: "okey perfecto ahi entendi" (recalcular el precio de venta, con vista previa antes de confirmar) — Aceptación: CU-13 puntos 3 y 4 — Checks: tests de vista previa y de confirmación
- S4 (supuesto): solo el Dueño registra y consulta compras; tipos FA, FB, FC y REM; número único por proveedor, tipo y punto de venta; costo en la moneda del artículo; un artículo no se repite en la compra — confirmar o descartar
- S5 (supuesto): consulta con listado (filtro por proveedor y fechas) y detalle — CU-13 punto 5
- S6: aislamiento entre ferreterías (CU-13 punto 6, regla 7 de `CLAUDE.md`)

## Tasks

- [x] T1 · S1-S3 · ruta: inline · Dominio y persistencia: migración V23 (`compra`, `item_compra`, `movimiento_stock.compra_id`), `OrigenPrecio.COMPRA`, `TipoMovimiento.COMPRA`, puertos `catalogo.api` (planear y aplicar costo de compra, con historial) y `stock` (registrar entrada de compra) · Evidencia: RED `ArticulosParaCompraServiceTest` (sin bean, 7 errores) y `StockServiceTest` (no compila) → GREEN los dos; `MigracionV2Test` actualizado por `COMPRA`; `ModulosArchTest` y `TenantScopingArchTest` verdes · Risk: item 1 (migración), item 3 (código compartido con CU-04, CU-05 y ventas: `Articulo.calcularPrecio` ahora delega en `precioConCosto`)
- [x] T2 · S1-S4 · ruta: inline · Servicio `compras`: vista previa y confirmación (validaciones, 409 por duplicado, idempotencia por clave, una transacción) · Evidencia: RED `ValidacionCompraTest` + `CompraServiceIntegrationTest` (no compila) → GREEN 5 + 11; `ModulosArchTest` con 3 reglas nuevas de `compras` y `TenantScopingArchTest` verdes · Risk: item 4 (concurrencia: la fachada reintenta `ConcurrencyFailureException` y resuelve la carrera de las claves únicas; sin test concurrente todavía)
- [x] T3 · S4-S6 · ruta: inline · Endpoints `POST /api/compras/vista-previa`, `POST /api/compras`, `GET /api/compras`, `GET /api/compras/{id}`, con rol Dueño y test de aislamiento · Evidencia: RED `CompraControllerIntegrationTest` 6/6 en 404 → GREEN 6/6 (403 del Vendedor en los cuatro, 404 de compra, proveedor y artículo ajenos, 400 de forma) · Risk: item 2 (rol y tenant)
- [ ] T4 · S1-S5 · ruta: inline · Pantalla Compras (listado, detalle y carga con renglones y vista previa), ruta y menú del Dueño · Evidencia: — · Risk: none
- [ ] T5 · todas · ruta: inline + `odd-verifier` · Cierre: suite backend, suite frontend, `npx ng build`, verificador independiente · Evidencia: — · Risk: items 1, 2, 3, 4

## Log

- L1 (2026-10-21) Pedido original, textual:
  > Arrancamos el punto 4 del orden acordado: compras a proveedores. Primer tramo: registrar una compra (la factura del proveedor) que ingrese el stock de los artículos y actualice su costo.
- L2 (2026-10-21) Decisiones de Luca: último costo («dale, como lo hace gds»); recalcular el precio con vista previa («okey perfecto ahi entendi»); precio manual y parámetros que suman o restan al precio final van como feature aparte después de este tramo («dale»). Pedido textual de esa feature: «se puede recalcular el precio de un articulo obviamente, eso lo puede hacer el dueno, lo puede hacer modificando el precio directamente o tocando alguna de las varianetes que los contituyen el margen costo o iva o otro que quiera agrear, eso es importante tambien que tenga disponible creaer otro parametro de sumarle o restarle al precio final».
- L3 (2026-10-21) Exploración: no hay un servicio único para cambiar el costo; `Articulo.ajustarCosto` (`Articulo.java:270`, el que usa CU-05) cambia solo el costo y recalcula el precio. Historial con `HistorialPrecio.de` (`HistorialPrecio.java:90`). `OrigenPrecio` = IMPORTACION, MASIVO, MANUAL, COTIZACION. `TipoMovimiento` = VENTA, AJUSTE, REMITO, ANULACION_REMITO. `movimiento_stock` referencia `comprobante_id` y `remito_id`. El puerto `ArticulosParaVenta` solo tiene `buscar`, `ajustarStock` y `stockVigente`. Molde de documento: `ventas` remito (`RemitoService`, `RegistroRemito`, `V16__remitos.sql`). Variación fuerte: `VariacionFuerte` ±50 %. Última migración: V22. ArchUnit: `ModulosArchTest`.
- L4 (2026-10-21) Progreso: CU-13 escrito en `docs/spec.md` (también el modelo de datos, el origen del historial y la lista de fuera de alcance) · Próximo paso: T1 test-first.
