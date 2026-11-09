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
- S4: «dale, vamso con eso» (L11), a la variante B2 — Aceptación: a 1280 y 1440 px la grilla de la vista previa de la importación no tiene scroll horizontal, cada importe va con su «/ kg» en una línea y el Detalle se ve entero; los encabezados de esa tabla pueden ir en dos renglones y el Detalle arranca en 120 px — Checks: ego-browser en la app real («tengo que tener la prueba de que el usuario final lo puede ver bien»), `npx ng test --watch=false --include=src/app/features/importaciones/vista-previa-tabla.spec.ts`, `npx ng build`

## Tasks

- [x] T1 · S1 · ruta: inline · Aceptación: comprobante de la compra con palabras en Movimientos; spec y cu-09 al día · Evidencia: RED `MovimientosArticuloTest` 8/8 fallan («FA 00036-03608111 · Bulonera Sur»); GREEN 8/8, `MovimientosArticuloIntegrationTest` 6/6, `ModulosArchTest` 50/50, `movimientos-articulo.spec.ts` 14/14 · commit `b144ab6b` · Risk: none
- [x] T2 · S2, S2a · ruta: inline · Aceptación: contador por costo real, con test de recargos · Evidencia: RED `CompraRecargosIntegrationTest` 2/7 fallan (`$.suben` 0 en vez de 1; `$.bajan` 0 en vez de 1); GREEN 7/7, `CompraServiceIntegrationTest` 22/22, `CompraControllerIntegrationTest` 8/8 · commit `a0087293` · Risk: none
- [x] T3 · S3, S3a · ruta: inline · Aceptación: `unidad` en `ArticuloVigente` y `CambioPlaneado`; sufijo en la vista previa de la importación · Evidencia: RED `ImportacionIntegrationTest#laVistaPreviaTraeLaUnidadDeLosArticulosQueCambian` (NPE: no hay `unidad`), `vista-previa-tabla.spec.ts` y `detalle-fila.spec.ts` 2 fallan («$ 1.000,00» sin « / kg»); GREEN backend 2/2, front `importaciones/**` 8 archivos 365/365 · commit `9daf1589` · Risk: item 3 (`ArticuloVigente.de()` también lo usa Actualizar precios) → `odd-verifier`
- [ ] T4 · S3a, S4 · ruta: inline · Aceptación: con «/ kg» en la misma línea que el importe, la tabla de la vista previa de la importación entra a 1280 px sin scroll horizontal (L9, L11) · Evidencia: primer intento `254e594e` (el sufijo baja de renglón) reemplazado por B2 en `963aa428`; `importaciones/**` 8 archivos 365/365, `ng build` OK; el ancho no se puede probar en jsdom, así que la prueba es ego-browser: pendiente · Risk: none

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
- L8 (2026-11-08) Checks completos sobre `9daf1589`:
  - backend `./mvnw test`: Tests run: 6452, Failures: 0, Errors: 0, Skipped: 0;
  - frontend `npx ng test --watch=false`: 164 archivos, 4409 tests;
  - `npx ng build`: OK, solo con el aviso de presupuesto de `dialogo-cobro.ts`, que ya estaba.
  Se lanzaron en paralelo un `odd-verifier` (Opus), por el ítem 3 de T3, y un recorrido con ego-browser en la app local (worker Opus).
- L9 (2026-11-08) Recorrido con ego-browser sobre `9daf1589`, Dueño de El Tornillo, a 1280×800. Pasan S1, S2 y S3.
  - **S1.** «Factura A 00005-00001234 · Distribuidora Bulonera Sur» y «Remito 00036-00003602 · …». Ventas, remitos, anulaciones y ajustes, igual que antes.
  - **S2.** Con el mismo costo y «Flete» al 10 % da «1 sube · 0 bajan · 0 iguales»; sin recargo, «1 igual».
  - **S3.** U36-1: «$ 4.000,00 / kg … $ 7.350,75 / kg». U36-5, por unidad, sin sufijo. El nuevo «Alambre recocido x kg» dice «$ 3.800,00 / kg» y «$ 5.977,40 / kg».
  - **Sin ver.** El «Costo real / kg»: ningún artículo por kilo tiene recargos en la base local. Lo cubre `detalle-fila.spec.ts`.
  - **Regresión de T3.** `.num` lleva `white-space: nowrap` en toda la app. Con el sufijo en las cuatro columnas, `.grilla-contenedor` se desborda (977 contra 1015 px) y el Detalle queda cortado. Se abre T4: el importe va en un `<span>` que no se parte y el sufijo, después de un `<wbr>`, puede bajar de renglón; solo en esta tabla.
  - **Fuera de alcance**, visto en el recorrido:
    - la vista previa de Nueva compra con recargos (10 columnas) se desborda a 1280 px, con un artículo por unidad, así que no es por el sufijo;
    - Actualizar precios no muestra «/ kg».
  - **Base local.** No cambió nada; no se aplicó ni se registró nada.
- L10 (2026-11-08) `odd-verifier` (Opus) sobre `9daf1589` contra `33b2f7f6`: **pass, sin blockers**.
  - **S1 a S3a.** Pasan. Los tests nuevos fallan en la baseline y pasan en HEAD.
  - **Ítem 3, Actualizar precios.** Comparó la baseline con HEAD: las vistas previas de los 6 modos, con y sin inactivos, el 409 por huella vieja y dos aplicaciones. Dan lo mismo en las respuestas y en la base (`articulo`, `historial_precio`, `movimiento_stock`), y las huellas son iguales.
  - **Vista previa de la importación.** Solo suma `unidad`. El registro de la compra no devuelve contadores.
  - **Advertencias.** Son las mismas de S2a y L6: una compra sin recargos marca «baja» aunque las columnas de costo real no se muestren, y la variación fuerte compara el costo de factura. No probó un cambio con LITRO en el front.
  - **Sondas que quedan como regresión:**
    - `HuellaMasivaTest.laUnidadNoCambiaLaHuella`, 19/19;
    - `unidad` en `cambiosDeMoneda` (KILO) y `correcciones` (METRO), en `ImportacionIntegrationTest#laVistaPreviaTraeLasListasNuevasConHojaYLosContadoresNuevos`, 1/1.
- L11 (2026-11-08) Medición con ego-browser a 1280 px (`254e594e`, mismo CSV).
  - **Línea de base.** Sin el sufijo, que es como está `main`, la grilla ya desbordaba: 991 contra 977 px, con el Detalle cortado 13 px. T3 la llevó a 1015 y el `<wbr>` de `254e594e` la devolvió a 991, pero el «/ kg» bajaba de renglón en algunas columnas, incluso a 1440.
  - **Variante B2.** Encabezados con `white-space: normal` y `.detalle div { min-width: 120px }`, solo en esta tabla. Mide 977/977 y deja cada importe en una línea con su «/ kg».
  - **Decisión.** Luca, textual: «dale, vamso con eso,  cada vez que haya un cambio visual utilizar ego lite para ver como queda y como lo va a ver el usuario y si es realmente lo que queremos, no me sirve ponerlo en un html y listo tengo que tener la prueba de que el usuario final lo puede ver bien».
  - **S4.** La tabla de la vista previa de la importación entra a 1280 px con el «/ kg» en la misma línea que el importe. Se arregla también el desborde de 14 px que ya tenía `main`. Para eso, los encabezados de esa tabla pueden ir en dos renglones y el Detalle arranca en 120 px.
  - **Cómo quedó.** Se volvió la tabla a `9daf1589` (sin `<wbr>` ni el test de T4) y se aplicó B2 (`963aa428`). La prueba es ego-browser, en la app real.
