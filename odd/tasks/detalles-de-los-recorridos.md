# detalles-de-los-recorridos

Objetivo: corregir tres detalles vistos en los recorridos de la unidad de medida (sesión 70) · Por qué: Movimientos nombra la compra distinto que Compras, el contador de la vista previa de la compra ignora los recargos y la vista previa de la importación muestra los precios sin la unidad.
Rama: `fix/detalles-de-los-recorridos` desde `33b2f7f6` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / suite `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` / `npx ng build` · Mirror Engram: `odd/detalles-de-los-recorridos/tasks` (ok)
Pronóstico: ~250 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite) · Modelo: Opus de punta a punta (caso 2)

## Specs

Alcance: los detalles 1, 3 y 4 de la propuesta (L1). Fuera de alcance: el detalle 2 (el código exacto en Nueva compra queda como está, L3), el TOTP del super admin y lo que depende de ARCA o del contador.
Restricciones: sin migraciones. Mensajes y textos en español rioplatense.

- S1: «dale» (L4), a la propuesta: «que la compra en Movimientos diga **«Factura A 00003-00001234 · Proveedor»** y **«Remito 00036-03608111 · Proveedor»**, igual que Compras» — Aceptación: `GET /api/articulos/{id}/movimientos-stock` devuelve el comprobante de la compra con `TipoCompra.comprobante(...)` («Factura A 00003-00001234 · Bulonera Sur», «Remito 00036-03608111 · …»); ventas, remitos, anulaciones, notas de crédito y ajustes no cambian; la spec (CU-09 punto 3) y el S3 de `cu-09-movimientos-articulo.md` quedan al día — Checks: `./mvnw test -Dtest=MovimientosArticuloIntegrationTest`, `npx ng test --watch=false --include=src/app/features/articulos/movimientos-articulo.spec.ts`
- S2: «dale» (L2), al detalle «El contador «suben/bajan» de la vista previa de la compra no cuenta los recargos» — Aceptación: en la vista previa de una compra, `suben`, `bajan` e `iguales` comparan el costo real nuevo (costo de la compra con sus recargos) contra el costo real anterior (costo del artículo con sus recargos de hoy); sin recargos da lo mismo que hoy — Checks: `./mvnw test -Dtest=CompraRecargosIntegrationTest,CompraServiceIntegrationTest`
- S2a (supuesto): el contador sigue al costo real porque la compra fija el costo y los recargos que trae, y los vacíos los borra (`Articulo.java:544`). Así, una compra sin recargos sobre un artículo que los tenía cuenta como «baja» aunque el costo de la factura sea igual, porque el costo real y el precio sí bajan.
- S3: «dale» (L2), al detalle «En la importación, el «Precio nuevo» sale sin «/ kg» (`CambioPlaneado` no trae la unidad)» — Aceptación: `POST /api/importaciones/vista-previa` trae `unidad` en cada cambio; la vista previa muestra «/ kg», «/ m» o «/ l» en el precio nuevo de un artículo por kilo, metro o litro, y nada en uno por unidad, con `porUnidad` — Checks: `./mvnw test -Dtest=ImportacionIntegrationTest,PlanificadorImportacionTest`, `npx ng test --watch=false --include=src/app/features/importaciones/vista-previa-tabla.spec.ts`
- S3a (supuesto): como en la vista previa de la compra (PR #35), el sufijo va en el costo anterior, el costo nuevo, el precio anterior y el precio nuevo, y también en los artículos nuevos (`AltaPlaneada.unidad` ya llega) y en el «Costo real» del detalle de la fila.

## Tasks

- [x] T1 · S1 · ruta: inline · Aceptación: comprobante de la compra con palabras en Movimientos; spec y cu-09 al día · Evidencia: RED `MovimientosArticuloTest` 8/8 fallan («FA 00036-03608111 · Bulonera Sur»); GREEN 8/8, `MovimientosArticuloIntegrationTest` 6/6, `ModulosArchTest` 50/50, `movimientos-articulo.spec.ts` 14/14 · commit: ver L5 · Risk: none
- [x] T2 · S2, S2a · ruta: inline · Aceptación: contador por costo real, con test de recargos · Evidencia: RED `CompraRecargosIntegrationTest` 2/7 fallan (`$.suben` 0 en vez de 1; `$.bajan` 0 en vez de 1); GREEN 7/7, `CompraServiceIntegrationTest` 22/22, `CompraControllerIntegrationTest` 8/8 · commit: ver L6 · Risk: none
- [x] T3 · S3, S3a · ruta: inline · Aceptación: `unidad` en `ArticuloVigente` y `CambioPlaneado`; sufijo en la vista previa de la importación · Evidencia: RED `ImportacionIntegrationTest#laVistaPreviaTraeLaUnidadDeLosArticulosQueCambian` (NPE: no hay `unidad`), `vista-previa-tabla.spec.ts` y `detalle-fila.spec.ts` 2 fallan («$ 1.000,00» sin « / kg»); GREEN backend 2/2, front `importaciones/**` 8 archivos 365/365 · commit: ver L7 · Risk: item 3 (`ArticuloVigente.de()` también lo usa Actualizar precios) → `odd-verifier`

## Log

- L1 (2026-11-08) Pedido original, textual: «continuamos». La propuesta de esta sesión, con los pendientes de la sesión 70:
  > 1. Movimientos dice «REM 00036-…» y Compras dice «Remito 00036-…».
  > 2. En la compra nueva, un código exacto agrega el artículo sin mostrar la lista.
  > 3. El contador «suben/bajan» de la vista previa de la compra no cuenta los recargos.
  > 4. En la importación, el «Precio nuevo» sale sin «/ kg» (`CambioPlaneado` no trae la unidad).
  >
  > Te recomiendo juntar los 4 chicos en una tanda, en una rama propia y con Opus de punta a punta.
- L2 (2026-11-08) Luca: «dale».
- L3 (2026-11-08) El detalle 2 ya se había aprobado en el recorrido del 06/11: «Buscar por código exacto lo agrega directo con el costo actual precargado ✔» (`wiki/recorrido-pantallas-2026-11-06.md:167`). Así anda el lector de código de barras. Se recomendó dejarlo como está. Luca: «lo dejamos».
- L4 (2026-11-08) Exploración (`odd-explorer`):
  - **Detalle 1.** `MovimientosArticulo.java:88` arma «REM 00036-…» con el código del enum, como dice el S3 de `cu-09-movimientos-articulo.md`, que era un supuesto («como lo nombra cada pantalla», con los códigos de GDS). Compras usa `TipoCompra.comprobante()`. Se recomendó usarlo también en Movimientos. Luca: «dale».
  - **Detalle 3.** `RegistroCompra.java:160` compara `costoNuevo` contra `costoAnterior`. `CostoDeCompra` ya trae `costoRealAnterior` y `costoRealNuevo` (`ArticulosParaCompraService.java:94-96`). Importar lista y Actualizar precios tienen contadores propios: ahí los recargos son los mismos antes y después.
  - **Detalle 4.** `CambioPlaneado` se arma solo en `PlanificadorImportacion.java:246-256`, desde `ArticuloVigente`, que no tiene la unidad. `ArticuloVigente.de()` también lo usa `ActualizacionMasivaService:217`. El front pinta en `vista-previa-tabla.ts:126-147` y `detalle-fila.ts:66-73`.
- L5 (2026-11-08) T1 hecha. `stock` no puede usar `TipoCompra`: `ModulosArchTest.nadieDependeDeCompras` lo prohíbe. Por eso `MovimientosArticulo.tipoDeCompra` repite las cuatro etiquetas, y `MovimientosArticuloTest` las compara con el enum (si se agrega un tipo, el test falla). El S3 de `cu-09-movimientos-articulo.md` lleva una nota que apunta a este S1. Las fixtures de `movimientos-articulo.spec.ts` y el comentario de `articulo.models.ts` quedaron con «Factura A».
- L6 (2026-11-08) T2 hecha. `RegistroCompra.plan` compara `costoRealNuevo` contra `costoRealAnterior`. Casos nuevos en `CompraRecargosIntegrationTest`:
  - mismo costo con +10 % sube;
  - costo 95 con +10 % sube;
  - mismo costo con −10 % baja;
  - sin recargos, igual queda igual y 110 sube;
  - sin recargos sobre un artículo que los tenía baja (S2a).
  La spec (CU-13 punto 3) y el comentario de `PlanCompra` quedaron al día. La advertencia de variación fuerte (±50 %) sigue comparando el costo de la factura: no entra en esta tanda.
- L7 (2026-11-08) T3 hecha.
  - **Backend.** `ArticuloVigente` y `CambioPlaneado` suman `unidad` al final (null da UNIDAD). Los constructores de antes delegan con UNIDAD, así que los tests y Actualizar precios no cambian. `ArticuloVigente.de()` toma `getUnidad()` y `PlanificadorImportacion.cambio(...)` la pasa al cambio.
  - **Front.** `CambioPlaneado.unidad` es obligatorio en el modelo, y se sumó a los 4 helpers de los specs. `vista-previa-tabla.ts` agrega `porUnidad` a los cuatro importes, de los cambios y de las altas, y `detalle-fila.ts` lo agrega al «Costo real». El «Unidad: Kilo» del detalle de un alta queda como estaba.
