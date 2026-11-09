# cu-09-movimientos-articulo

Objetivo: el Dueño consulta los movimientos de stock de un artículo, con su saldo y el comprobante que los originó · Por qué: la spec registra los movimientos pero no dice dónde se consultan (G29, GDS-17)
Rama: `feat/cu-09-movimientos-articulo` desde `3660596` · Entrega: single-pr · Runner: `./mvnw test -Dtest=<Clase>` (backend), `npx ng test --watch=false --include=<spec>` (frontend) · Mirror Engram: `odd/cu-09-movimientos-articulo/tasks` (ok)
Pronóstico: ~1200 líneas cambiadas · Heurística por tarea: ~400 (no es límite) · Modelo: caso 3, todo con Opus en esta sesión (Luca pidió seguir de corrido)

## Specs

Alcance: endpoint paginado de movimientos por artículo, pantalla `/articulos/:id/movimientos`, accesos desde el listado y la ficha, spec y relevamiento · Fuera de alcance: «clientes que compraron el artículo» (Ctrl+A en GDS, otra acción), links a cada comprobante (las pantallas de ventas, remitos y compras no tienen ruta por id)
Restricciones: la ferretería sale del JWT; artículo ajeno da 404; `stock` no puede depender de `ventas` ni de `compras` (ModulosArchTest)

- S1: "movimientos por artículo" … "cualquier duda o dilema o forma de hacerlo, vamos por como lo hace gds" (2026-10-23) — Aceptación: `GET /api/articulos/{id}/movimientos-stock?desde&hasta&page&size` devuelve los movimientos del artículo, el más nuevo primero, cada uno con fecha, tipo, cantidad con signo, saldo después del movimiento, comprobante, usuario y motivo. Paginado como la consulta de ventas (20 por página, tope 100) — Checks: `./mvnw test -Dtest=MovimientosArticuloIntegrationTest`
- S2 (supuesto, como GDS): saldo calculado hacia atrás desde el stock actual, así el último movimiento muestra el stock de hoy aunque falte el stock inicial.
- S3 (supuesto, como GDS): el comprobante se muestra como lo nombra cada pantalla: «Venta N.º 12», «Remito N.º 5», compra «FA 00003-00001234 · Proveedor» con los códigos de tipo de GDS (FA, FB, FC, REM). El ajuste no tiene comprobante y muestra su motivo.
  - Reemplazado el 2026-11-08 por el S1 de `detalles-de-los-recorridos.md`: la compra se nombra como en Compras, «Factura A 00003-00001234 · Proveedor» o «Remito 00036-03608111 · Proveedor».
- S4 (supuesto): solo el Dueño, como la consulta de ventas y el ajuste de stock (GDS lo da por permiso; acá hay roles fijos).
- S5: otra ferretería no ve nada: artículo ajeno da 404 — Checks: `MovimientosArticuloIntegrationTest`
- S6: pantalla con Desde/Hasta y páginas como Ventas, accesible con «Movimientos» desde el listado de artículos (barra) y desde la ficha (junto al stock actual) — Checks: specs de `features/articulos/movimientos-*`

## Tasks

- [x] T1 · S1-S5 · ruta: inline · Aceptación: endpoint, consulta y saldo, con aislamiento · Evidencia: `MovimientosArticuloIntegrationTest` 5/5, `ModulosArchTest` 40, `TenantScopingArchTest` 24, `ArticulosParaVentaServiceTest` 9/9 · commit 4f6b03d · Risk: item 2 (aislamiento por tenant en consultas nativas)
- [x] T2 · S6 · ruta: inline · Aceptación: pantalla, ruta y accesos; verificada con ego-browser · Evidencia: specs focalizados 369/369, suite frontend 2917/2917, `ng build` OK; ego-browser: ficha → «Ver movimientos» (BUL-002, saldos 200 → 192), barra → `/articulos/331/movimientos`, páginas (2 de 112) y filtro 19/10 (1 de 2) · commit 5cb54b4 · Risk: none
- [x] T3 · S1 · ruta: inline · Aceptación: spec (CU-09) y relevamiento (G29, GDS-17) · Evidencia: CU-09 punto 3, G29 y GDS-17 marcados hechos · Risk: none

## Log

- L3 (2026-10-23) Suite backend 4450/0 sobre d45d60a. Verificador opus: pasa S1-S6 sin blockers. Advisory 1: el nombre de usuario de la consulta nativa no filtraba por ferretería (solo con datos corruptos). Corregido test-first con `referenciasAjenasNoSeFiltran`, que también fija que comprobante, remito y compra ajenos no se muestran (RED con el nombre ajeno → GREEN 6/6). La suite completa no se volvió a correr: el cambio es una condición en una consulta que solo usa este endpoint.

- L1 (2026-10-23) Pedido original, textual:
  > dale, cualquier duda o dilema o forma de hacerlo, vamos por como lo hace gds
  Respuesta a la propuesta: «Movimientos de stock por artículo (G29/GDS-17) … pantalla en la ficha del artículo con fecha, tipo (venta, compra, ajuste), cantidad, saldo, usuario y comprobante, como GDS.»
- L2 (2026-10-23) Exploración (odd-explorer): paginado de CU-16 en `VentaController.java:59-68`, `VentaGestion.java:52-63`, `PageResponse`; nombres de usuario por lote en `CajaRepository.nombresScoped`; `ModulosArchTest:85` prohíbe stock→ventas y `:109` cualquier dependencia de compras; sin rutas por id a venta, remito o compra. Decisión técnica: los números y el usuario salen de una consulta nativa de solo lectura en `MovimientoStockRepository` (une tablas, no clases), y el stock actual y los datos del artículo de un método nuevo del puerto `ArticulosParaVenta`.
