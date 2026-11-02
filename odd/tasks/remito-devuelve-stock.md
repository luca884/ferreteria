# remito-devuelve-stock

Objetivo: un Vendedor ya no anula de hecho un remito facturándolo por menos de lo que entregó · Por qué: hallazgo 3 (MEDIA) de la revisión de seguridad del 01/11 ([[revision-seguridad-2026-11-01]]): el remito 30 entregó 100 bolsas y 20 caños, el Vendedor lo factura con 1 bolsa, el remito pasa a FACTURADO y el stock recupera 99 bolsas y 20 caños que ya salieron. Anular un remito es solo del Dueño (`RemitoController.java:71`).
Rama: `fix/cu-07-remito-devuelve-stock` desde `8b96006` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / `npx ng test --watch=false` / `npx ng build` · Mirror Engram: `odd/remito-devuelve-stock/tasks` (ok)
Ruteo: caso 5 (alto riesgo: item 2, autorización) · Pronóstico: ~150 líneas · Estado: terminado; falta merge y deploy (los corre Luca)

## Specs

Alcance: `RegistroVenta.registrar` (venta con `remitoId`), un 403 de negocio con mensaje propio en `common/exception`, el aviso del mostrador al cobrar, `docs/spec.md` (CU-07, remito).
Fuera de alcance: los 5 hallazgos BAJOS; la devolución parcial por nota de crédito (CU-08) no cambia.
GDS: no sirve de referencia, los remitos estaban bloqueados en la demo (G55, F-1450).

Propuesta aprobada por Luca («me parece bien»):

- S1: "Si al facturar el remito algún artículo **vuelve** al stock, el Vendedor recibe un 403 con un mensaje del tipo «Facturar menos de lo que entregó el remito devuelve stock: lo tiene que hacer el Dueño.»" — Aceptación: remito de 3 y venta de 1 con un Vendedor → 403 con ese mensaje; el remito sigue EMITIDO, el stock no cambia y no se crea la venta. Lo mismo si se saca del ticket un artículo del remito.
- S2: "El Dueño sí puede facturarlo." — Aceptación: el mismo remito de 3 y venta de 1 con el Dueño → 201, vuelven 2.
- S3: "Facturar igual o más de lo entregado sigue libre para los dos." — Aceptación: con un Vendedor, igual al remito → 201; más cantidad → 201; un artículo que el remito no tenía, agregado sin sacar los del remito → 201.
- S4 (supuesto): el mostrador muestra el mensaje del 403 en el cobro y deja el ticket y el diálogo como están, como con el exceso de cuenta corriente (`mostrador.ts:860`).

## Tasks

- [x] T1 · S1 S2 S3 · backend: `SoloDelDuenoException` (403 con su mensaje) + `verificarQueNoDevuelveStock` en `RegistroVenta.registrar`, después de `validar` y antes del numerador · RED `RemitoVentaIntegrationTest` 2 fallas (`vendedorNoDevuelveStock`, `vendedorNoSacaUnArticuloDelRemito`: 201 en vez de 403) → GREEN 17/17 (+ `RegistroVentaTest` 82, `TopeDescuentoIntegrationTest` 19, `RemitoFacturadoPorIntegrationTest` 3, `CostoDeVentaIntegrationTest` 6); `ventaDeMenos` y `articulosQueCambian` pasan al Dueño · `7b84ba6` · Risk: item 2
- [x] T2 · S4 · frontend: test de regresión en `mostrador.spec.ts` (403 al cobrar un remito: mensaje en el cobro, conserva remito y ticket). Sin RED: `alFallarEnvio` ya mostraba el mensaje de cualquier 403 · 289/289 · `1418c56` · Risk: ninguno
- [x] T3 · todas · `docs/spec.md` (en `7b84ba6`), suites backend y frontend, `ng build`, ego-browser local, `odd-verifier` · Risk: item 2 · `./mvnw test` 5933/0 · frontend 3506/3506 · `ng build` ok · ego-browser local ok (L4) · `odd-verifier`: S1-S4 PASS, sin blockers (L5); sondas commiteadas: `RemitoVentaIntegrationTest` 21/21

## Log

- L1 (2026-11-02) Pedido, textual:
  > continuamos, que cosas nos quedan pendientes en genral del proyecto → (recomendación: hallazgo 3) → «dale seguimos con eso»

  Propuesta (sin GDS, G55 bloqueado en la demo): como `verificarCupo`, si algún artículo vuelve al stock, el Vendedor recibe 403 y el Dueño sí factura; igual o más, libre. Costo dicho: si el cliente devuelve parte de lo entregado, el Vendedor tiene que llamar al Dueño. Luca: «me parece bien».
- L2 (2026-11-02) Exploración:
  - `StockService.java:144-170` (`facturarRemito`): delta por artículo = entregado − vendido; positivo vuelve al stock.
  - `RegistroVenta.java:181` y `:467` (`remitoParaFacturar`): solo mira el estado, no el rol. `usuarioEsDueno` ya llega a `registrar`.
  - `GlobalExceptionHandler.java:92`: `AccessDeniedException` siempre responde «No tenés permiso para hacer esto» (pisa el mensaje), por eso hace falta un 403 con mensaje propio.
  - `mostrador.ts:858-863`: un 403 al cobrar muestra `mensajeDe(err, …)` en `avisoCobro` y deja el ticket.
  - `RemitoVentaIntegrationTest#ventaDeMenos` y `#articulosQueCambian` usan al Vendedor para devolver stock: pasan al Dueño.
- L3 (2026-11-02) T1 y T2 hechos (`7b84ba6`, `1418c56`; 135+/14−). `docs/spec.md` (CU-07, remito) suma la regla. El chequeo va después de `validar` para que los 400 del ticket salgan primero, y reusa los renglones del remito que ya usaba `facturarRemito` (una sola lectura). Próximo: suites backend y frontend, `ng build`, ego-browser local y `odd-verifier`.
- L4 (2026-11-02) Checks: `./mvnw test` 5933/0 (BUILD SUCCESS) · frontend 3506/3506 · `ng build` ok · ego-browser local (espacio 15, cerrado): el Vendedor emite el remito N.º 1 con 3 tornillos, lo pasa a venta con 1 y al cobrar ve en el diálogo «Facturar menos de lo que entregó el remito devuelve stock: lo tiene que hacer el Dueño.», con el ticket en «Venta del remito N.º 1»; el Dueño hace lo mismo y sale «Venta N.º 7 cobrada: $ 181,49», remito FACTURADO. Falta: `odd-verifier`.
- L5 (2026-11-02) `odd-verifier` (Opus), HEAD `1418c56` contra `8b96006` con las mismas 13 sondas: S1-S4 PASS, sin blockers. Cada abuso daba 201 en el baseline y ahora da 403 sin escribir nada (menos cantidad, sacar un artículo, mezcla de más y de menos, una milésima menos en metros, `autorizaExceso=true`); los flujos legítimos responden igual que antes; los 400/404/409 del ticket y del remito salen antes; `StockService.facturarRemito` tiene un solo llamador; la clave rechazada queda libre y no saltea el chequeo. Se commitean cuatro sondas como tests (`vendedorMezclaDeMasYDeMenos`, `vendedorDecimales`, `rechazoNoConsumeNumeradorNiClave`, `ordenDeErrores`): 21/21. Advisories: comentario viejo en `mostrador.ts:859` (corregido); el 403 nuevo gana sobre el rechazo de cupo o de Consumidor Final (de un rechazo a otro, sin escribir); el rol sale del JWT, como en `verificarCupo` (igual que en el baseline).
