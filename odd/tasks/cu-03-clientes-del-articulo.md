# cu-03-clientes-del-articulo

Objetivo: el Dueño ve qué clientes compraron un artículo, como el Ctrl+A «clientes que compraron el artículo» del listado de GDS (G29) · Por qué: saber a quién avisar cuando vuelve un artículo, cambia o tiene una falla.
Rama: `feat/cu-03-clientes-del-articulo` desde `092c928` · Entrega: single-pr · Runner: `./mvnw test -Dtest=<Clase>` (backend), `npx ng test --watch=false --include=<spec>` (frontend) · Mirror Engram: `odd/cu-03-clientes-del-articulo/tasks` (ok) · Modelo: caso 3, Opus.
Pronóstico: ~700 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: endpoint `GET /api/articulos/{id}/clientes` (solo Dueño) con una fila por cliente y la cantidad de ventas sin cliente; pantalla `/articulos/{id}/clientes` con botón «Clientes» en la barra de Artículos; docs. · Fuera de alcance: plata (precio o total), filtros por fecha, ir del cliente a su cuenta.
Restricciones: sin migración; tenant solo del JWT; todo cruce con otra tabla une por `ferreteria_id`; un artículo ajeno da 404.

- S1: «dale» (Luca, 2026-10-23) a la propuesta:
  - «**Dónde:** botón «Clientes» en la barra de Artículos, para el artículo elegido, solo para el Dueño (son datos de ventas, igual que la consulta de ventas). Abre `/articulos/{id}/clientes`, al lado de «Movimientos».»
  - «**Qué muestra:** una fila por cliente que compró el artículo, con: razón social; cantidad de compras (ventas); cantidad total comprada, con su unidad; fecha de la última compra. Ordenado de la compra más reciente a la más vieja, en páginas de 20.»
  - «**Ventas sin cliente** (consumidor final): no aparecen, porque no hay a quién llamar. Una línea al pie dice cuántas son.»
  - «**Sin plata en la tabla:** el precio del renglón es anterior a los descuentos y recargos de la venta, y podría confundir. Si después querés ver a cuánto se lo vendiste, eso es otra cosa.»
  - «**Sin migración:** sale de las ventas y sus renglones. La columna `articulo_id` ya tiene índice (lo crea la foreign key).»
  - «**Aislamiento:** como todo, test de que otra ferretería no ve sus clientes y de que un artículo de otra ferretería da 404.»
  - Aceptación: el endpoint agrupa por cliente las ventas que tienen el artículo (un artículo no se repite en el ticket), ordena por última compra (la más nueva primero), pagina, y trae `ventasSinCliente`; Vendedor 403; artículo ajeno o inexistente 404; ventas y clientes de otra ferretería no cuentan. — Checks: `./mvnw test -Dtest=ClientesDelArticuloIntegrationTest`
- S2 (supuesto): la razón social es la actual del cliente (la tabla `cliente`), no la copiada en la venta, para que un cliente renombrado salga una sola vez.
- S3 (supuesto): un cliente dado de baja igual aparece (compró), marcado «Desactivado» como en la cuenta corriente.

## Tasks

- [x] T1 · S1, S2, S3 · ruta: inline · Aceptación: endpoint con tests de agrupado, orden, páginas, ventas sin cliente, permisos y aislamiento · Evidencia: RED `ClientesDelArticuloIntegrationTest` 6 fallas (404/403 sin endpoint) → GREEN 6/6; `ModulosArchTest` 40/0, `TenantScopingArchTest` 24/0; commit `68de7b8` · Risk: none
- [x] T2 · S1, S3 · ruta: inline · Aceptación: pantalla `/articulos/{id}/clientes` y botón «Clientes» en la barra (solo Dueño) · Evidencia: specs escritos antes de la implementación pero corridos después (RED no observado): `clientes-articulo.spec.ts` 8/8, `articulos.spec.ts` 60/60, `articulos.service.spec.ts` 27/27; ego-browser: TOR-001 con 31 clientes en 2 páginas y «Además hubo 16 ventas sin cliente», Vendedor sin botón y con aviso; commit `a9d000e` · Risk: none
- [x] T3 · S1 · ruta: inline · Aceptación: `docs/spec.md` (CU-03) y `docs/relevamiento-gds.md` (G29) dicen qué quedó · Evidencia: CU-03 punto 7, G29; commit `22b2d07` · Risk: none

## Log

- L1 (2026-10-23) Pedido original, textual:
  > vamos con esa

  (a «¿Seguimos con esa?», «clientes que compraron el artículo»). Propuesta aprobada, textual: «dale» (ver S1).
- L2 (2026-10-23) Exploración:
  - GDS solo nombra la función: `docs/relevamiento-gds.md:96` (G29) y `:430` (Ctrl+A en el listado de artículos); sin captura de la pantalla.
  - Ventas: `comprobante` (`cliente_id` nulo, `fecha`) e `item_comprobante` (`articulo_id`, `cantidad`, `unidad`), `V4__ventas_y_stock.sql:7,30`. No hay anulación de ventas.
  - Patrón a copiar: movimientos del artículo, `stock/web/MovimientosArticuloController.java`, `stock/service/MovimientosArticulo.java` (404 con `ArticulosParaVenta.stockActual`), front `features/articulos/movimientos-articulo.*` y botón en `articulos.html:28`.
- L3 (2026-10-23) Hallazgos:
  - Un artículo no puede repetirse en el ticket (400 «Un artículo no puede repetirse en el ticket»): no hay dos renglones del mismo artículo en una venta. La consulta igual cuenta ventas distintas.
  - Fuera de esta feature: la pantalla de Etiquetas (`cu-03-etiquetas`, ya en main) usaba la clase `.etiqueta`, que es la pastilla global de `styles.css:696` (nowrap, fondo gris). Se renombró a `.rotulo` con un test que lo fija; commit `01d82f0`.
