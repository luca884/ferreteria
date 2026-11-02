# tope-descuento-documento

Objetivo: un Vendedor ya no usa el descuento de un presupuesto o remito del Dueño para saltear su tope · Por qué: hallazgo 2 (MEDIA) de la revisión de seguridad del 01/11 ([[revision-seguridad-2026-11-01]]): con un presupuesto del Dueño al 40 % de 1 artículo, un Vendedor con tope del 5 % vende cualquier artículo y cualquier cantidad al 40 %, y reactiva un presupuesto RECHAZADO para lo mismo.
Rama: `fix/cu-07-tope-descuento-documento` desde `30444b4` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / `npx ng test --watch=false` / `npx ng build` · Mirror Engram: `odd/tope-descuento-documento/tasks` (ok)
Ruteo: caso 5 (alto riesgo: item 2, autorización) · Pronóstico: ~350 líneas · Estado: terminado; falta merge y deploy (los corre Luca)

## Specs

Alcance: `ventas/service/TopeDeDescuento`, `RegistroVenta.autorizados`, `PresupuestoGestion.cambiarEstado` y su controller; pantalla de presupuestos (acciones del Vendedor sobre un RECHAZADO).
Fuera de alcance: hallazgo 3 (anular un remito facturándolo por una unidad); topes al emitir el presupuesto o el remito (ya los controla `RegistroPresupuesto`/`RegistroRemito`).
Restricciones: la regla de Luca del 2026-10-22 sigue: «los descuentos del presupuesto o remito que se pasa a venta ya los autorizo quien lo emitio; el tope del usuario solo frena lo que se agregue o se suba» (`RegistroVenta.java:497-498`). El Dueño no tiene tope: no cambia nada para él.

Propuesta aprobada por Luca («dale»):

- S1: "el descuento del documento vale solo para los artículos que el documento tenía y hasta la cantidad que tenía." — Aceptación: con un presupuesto o remito al 40 % (global o del renglón), un Vendedor con tope del 5 %:
  - vende el mismo artículo hasta la cantidad del documento al 40 % → se registra;
  - pide más cantidad del artículo → ese renglón vuelve a su tope (400 con el mensaje del tope);
  - agrega un artículo que el documento no tenía con el global del 40 % → 400 («Sumado al descuento general, pasa tu tope de descuento de 5 %.»).
- S2: "Un artículo que el documento no tenía siempre usa el tope del Vendedor."
- S3: "reactivar un presupuesto RECHAZADO queda solo para el Dueño." — Aceptación: `PATCH /api/presupuestos/{id}/estado` de RECHAZADO a PENDIENTE o ACEPTADO con un Vendedor → 403; con el Dueño → 200. La pantalla no le muestra al Vendedor «Aceptar» ni «Volver a pendiente» en un RECHAZADO.
- S4 (supuesto descartado, L3): un artículo no se repite ni en el documento ni en la venta («Un artículo no puede repetirse en el ticket: sumá la cantidad en un solo renglón.»), así que cada artículo tiene un solo descuento y una sola cantidad.

## Tasks

- [x] T1 · S1 S2 · `TopeDeDescuento` (`Autorizado` con descuento y cantidad) + `RegistroVenta.autorizados` · RED 2 fallas en `TopeDescuentoIntegrationTest` (más cantidad, artículo agregado con el global; presupuesto y remito) → GREEN 18/18 · `79c13f1` · Risk: item 2
- [x] T2 · S3 · `PresupuestoGestion.cambiarEstado(id, estado, esDueno)`: sacar de RECHAZADO sin ser Dueño → 403 (`AccessDeniedException`) · RED `PresupuestoConsultaIntegrationTest.reactivarUnRechazadoEsDelDueno` → GREEN 10/10 (+ `PresupuestoVentaIntegrationTest` 9/9); `cambiaDeEstado` ajustado: la reactivación la hace el Dueño · `18abfe0` · Risk: item 2
- [x] T3 · S3 · frontend `presupuestos`: «Aceptar» y «Volver a pendiente» en un RECHAZADO solo para el Dueño · RED 1 falla → GREEN 42/42; «Rechazar y Volver a pendiente…» pasó a Dueño · `f811396` · Risk: ninguno
- [x] T4 · todas · suites backend y frontend, `ng build`, ego-browser local (Vendedor con tope, presupuesto del Dueño), `odd-verifier` · Risk: item 2 · `./mvnw test` 5901/0 · frontend 3500/3500 · `ng build` ok · ego-browser local (vendedor demo con tope 10 %): presupuesto N.º 3 RECHAZADO → solo «Imprimir»; N.º 2 del Dueño (2 bulones al 50 %) pasado a venta con 3 → renglón «Tu tope de descuento es 10 %.»; con 2 → «Venta N.º 6 cobrada: $ 80,66» · `odd-verifier`: S1-S4 PASS, sin bloqueantes (L5)

## Log

- L1 (2026-11-02) Pedido, textual:
  > ¿Arrancamos con el hallazgo 2? → «da;e»

  Propuesta (recomendación, sin GDS: el relevamiento solo dice que el presupuesto se factura con «Traer», G56): el descuento del documento vale solo para sus artículos y hasta su cantidad; un artículo agregado usa el tope del Vendedor; reactivar un RECHAZADO, solo el Dueño. Luca: «dale».
- L2 (2026-11-02) Exploración:
  - `TopeDeDescuento.java:44`: el global se permite hasta `tope.max(autorizados.global())`, para toda la venta.
  - `TopeDeDescuento.java:51-52`: un artículo que no estaba en el documento hereda el global del documento (`efectivo(0, global)`); no se miran cantidades.
  - `RegistroVenta.java:500-514`: `autorizados` arma `articuloId → descuento` (el último renglón gana si se repite).
  - `PresupuestoGestion.java:64-76` y `PresupuestoController.java:71`: cualquier transición entre PENDIENTE, ACEPTADO y RECHAZADO, sin rol. `RegistroVenta.java:454`: un RECHAZADO no se cobra (409), por eso reactivarlo es el paso del abuso.
  - 403 de negocio: `AccessDeniedException` (como `RegistroVenta.java:311`), «No tenés permiso para hacer esto».
  - Frontend: `presupuestos.spec.ts:373` espera para un RECHAZADO «Imprimir», «Aceptar» y «Volver a pendiente», sin distinguir rol.
- L3 (2026-11-02) S4 descartado: al emitir un presupuesto con el mismo artículo dos veces, el servidor responde 400 «Un artículo no puede repetirse en el ticket: sumá la cantidad en un solo renglón.»; la venta tiene la misma regla. `unArticuloAgregadoNoUsaElDescuentoDeOtroRenglon` ya pasaba antes del cambio (el mapa era por artículo): queda como regresión.
- L4 (2026-11-02) T1-T3 hechos. Frontend 3500/3500 y `ng build` ok. Próximo: suite del backend, ego-browser local (Vendedor con tope y presupuesto del Dueño) y `odd-verifier`.
- L5 (2026-11-02) `odd-verifier` (Opus): S1-S4 PASS, sin bloqueantes; cada abuso que el código anterior (`30444b4`) permitía ahora falla, y los flujos legítimos siguen andando (Vendedor con igual o menos cantidad y descuento, Dueño con cualquier cantidad, Vendedor sin tope). Avisos: el 403 llega con el texto genérico «No tenés permiso para hacer esto» (`GlobalExceptionHandler` pisa el mensaje de `AccessDeniedException`); con el descuento general del documento y más cantidad, el error es «Sumado al descuento general…» (igual nombra el tope); un presupuesto vencido sigue pasándose a venta con su descuento (PP-006, por diseño). Se sumaron tres pruebas del verificador como tests: metros con una milésima más, y sobre un rechazado 400/404 antes que el 403.
