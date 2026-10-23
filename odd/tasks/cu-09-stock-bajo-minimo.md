# cu-09-stock-bajo-minimo

Objetivo: el Dueño ve e imprime los artículos que quedaron bajo su stock mínimo, con cuánto reponer · Por qué: CU-09 punto 1 («los artículos bajo el mínimo») no tiene pantalla; el stock mínimo se carga pero nadie lo consulta (G72, «Listado de Stock Crítico» de GDS)
Rama: `feat/cu-09-stock-bajo-minimo` desde `327f640` (apilada sobre `feat/cu-09-movimientos-articulo`, que todavía no está en main) · Entrega: single-pr · Runner: `./mvnw test -Dtest=<Clase>` (backend), `npx ng test --watch=false --include=<spec>` (frontend) · Mirror Engram: `odd/cu-09-stock-bajo-minimo/tasks` (ok)
Pronóstico: ~900 líneas cambiadas · Heurística por tarea: ~400 (no es límite) · Modelo: caso 3, todo con Opus en esta sesión (Luca pidió seguir de corrido)

## Specs

Alcance: endpoint del listado, pantalla imprimible con orden, acceso desde el menú, spec y relevamiento · Fuera de alcance: stock mínimo calculado con las ventas (G97/GDS-41, post-MVP), marca (el artículo no tiene marca), stock valorizado y los demás reportes de artículos de GDS (G72, clase D)
Restricciones: la ferretería sale del JWT; repositorios con métodos `*Scoped`; plata en `BigDecimal`; todo endpoint nuevo con un test de que otra ferretería no lo ve

- S1: "dale, vamos con bajo mínimo como gds" (2026-10-23) — Aceptación: `GET /api/articulos/bajo-minimo?orden=DESCRIPCION|CODIGO|RUBRO|PROVEEDOR` devuelve todos los artículos activos de la ferretería con stock actual menor al mínimo, sin páginas (es un listado para imprimir, como el de GDS), cada uno con código interno, descripción, rubro, proveedor, costo y moneda, unidad, stock, mínimo y «a reponer» (mínimo − stock). Orden por defecto: descripción — Checks: `./mvnw test -Dtest=StockBajoMinimoIntegrationTest`
- S2 (supuesto, como GDS): las columnas del impreso de GDS («Listado de Artículos bajo Stock Mínimo»: Código, Descripción, Costo, Stock, St.Min, Mínimo a reponer) y su orden (Descrip., Código, Rubro, Proveedor; Marca no, porque acá no hay marca). Se suman Rubro y Proveedor como columnas para que el orden por esos campos se lea en el impreso y sirva para pedirle al proveedor.
- S3 (supuesto): «bajo el mínimo» es stock < mínimo y mínimo > 0. Un artículo con mínimo 0 no tiene mínimo cargado y no sale aunque tenga stock negativo. Los dados de baja no salen.
- S4 (supuesto): solo el Dueño, como los reportes y el ajuste de stock (muestra el costo).
- S5: otra ferretería no ve nada: el listado trae solo los artículos propios — Checks: `StockBajoMinimoIntegrationTest`
- S6: pantalla «Stock bajo mínimo» con el selector de orden, la tabla y «Imprimir» (solo sale el listado, con título y fecha); vacío lo dice; accesible desde el menú — Checks: specs de `features/stock-bajo-minimo/*`

## Tasks

- [x] T1 · S1-S5 · ruta: inline · Aceptación: endpoint y consulta con aislamiento · Evidencia: `StockBajoMinimoIntegrationTest` RED (400, la ruta caía en `/{id}`) → GREEN 4/4; `TenantScopingArchTest` 24 (pidió filas `Object[]` en vez de proyección en el paquete del repositorio), `ModulosArchTest` 40 · commit c50e7bf · Risk: item 2 (aislamiento por tenant en consulta nativa con joins)
- [x] T2 · S2, S4, S6 · ruta: inline · Aceptación: pantalla, ruta, menú e impresión; verificada con ego-browser · Evidencia: specs focalizados 75/75, suite frontend 2927/2927, `ng build` OK (el aviso de presupuesto de `dialogo-cobro` ya estaba); ego-browser: menú → `/stock-bajo-minimo` con HER-001 y TOR-001 (a reponer 6 y 1.847), orden por código, vista de impresión solo con el listado · commit 202691f · Risk: none
- [x] T3 · S1 · ruta: inline · Aceptación: spec (CU-09 punto 1) y relevamiento (G72) · Evidencia: CU-09 punto 1 y G72 actualizados · Risk: none

## Log

- L3 (2026-10-23) Suite backend 4455/0 sobre 0fbba4b. Verificador opus: pasa S1-S6 sin blockers. Probó rubro y proveedor ajenos (nombre en null), decimales, stock negativo, los cuatro órdenes con empates y nulos, choques de rutas con `/api/articulos/{id}` y las reglas de impresión de los otros imprimibles. Advisory 1 (de antes de la feature): las FK de `articulo` a rubro y proveedor son solo por id. Se commiteó su sonda principal como regresión (`rubroYProveedorAjenosNoSeFiltran`, 5/5).

- L1 (2026-10-23) Pedido original, textual:
  > dale, vamos con bajo mínimo como gds
  Respuesta a la propuesta: «Artículos bajo el mínimo — CU-09 punto 1 de la spec (parte del MVP, no extra) … En GDS es el reporte "Stock crítico" (G72).»
- L2 (2026-10-23) Exploración: GDS (bitácora `gds-ferreteria-plus-7`, F-1600, capturas f1642 y f1643): diálogo «Ordenado por» DESCRIP./CODIGO/RUBRO/MARCA/PROVEEDOR y vista previa «Listado de Artículos bajo Stock Mínimo» con fecha y columnas Código, Descripción, Costo, Stock, St.Min, Mínimo a reponer. Acá: `articulo.stock_minimo` es `NOT NULL DEFAULT 0` (`V2__articulos_y_precios.sql:32`); rubro y proveedor van por id (`Articulo.java:69-73`), así que el orden por nombre sale de una consulta nativa con joins por `ferreteria_id`; la impresión usa las reglas `@media print` de `styles.css:849`.
