# medios-qa

Objetivo: arreglar los tres hallazgos medios del QA funcional del 05/11 (pasar a venta con oferta, factura B y detalle con descuento global, reimportar el CSV exportado) · Por qué: `wiki/qa-funcional-2026-11-05.md` del vault, «Hallazgos reales › Medios».
Rama: `fix/medios-qa` desde `f0c8b01` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / `./mvnw test`; frontend `npx ng test --watch=false [--include=<spec>]`, `npx ng build` · Mirror Engram: `odd/medios-qa/tasks` (ok)
Ruteo: caso 3 (Opus investiga y planifica; la implementación es mecánica y puede seguir con `/model sonnet` en esta sesión) · Pronóstico: ~300 líneas cambiadas · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: los tres medios de la nota del QA. Fuera de alcance: los bajos y las decisiones de producto de la misma nota (próxima tanda); la factura A (sus renglones sin IVA ya suman el neto gravado); el mostrador; las hojas de presupuesto y remito.
Restricciones: reglas de arquitectura de `CLAUDE.md`; mensajes en español rioplatense; plata en centavos enteros en el front y `BigDecimal` en el back.

- S1: "**«Pasar a venta» pierde la oferta** (C-1). […] `presupuestos.ts` y `remitos.ts` leen cada artículo con `ArticulosService.obtener`, cuyo `precioFinal` es el normal (la oferta viene aparte en `oferta`) […] El ticket muestra el precio normal y el servidor lo corrige al cobrar («Cambió el precio…») […] Afecta también a remitos." — Aceptación: al pasar a venta un presupuesto o un remito con un artículo en oferta vigente, el renglón del ticket lleva `precioOferta` como precio, `enOferta` true y el normal como `precioNormal` (igual que la búsqueda del mostrador); sin oferta, o con una vencida, queda el `precioFinal`. — Checks: `presupuestos.spec.ts`, `remitos.spec.ts`
- S2: "**Factura B y detalle de venta con descuento global** (B-2). Los renglones ya traen el descuento prorrateado (10 × 56,87 = 511,83) y abajo `factura-hoja.html` muestra Subtotal 5.138,00 y Descuento −513,80: parece aplicado dos veces. Total e IVA bien." — Aceptación: en la hoja sin IVA discriminado (B, su NC y su X), el «Importe» de cada renglón es precio × cantidad (`bruto`); abajo, Subtotal (la suma de esos importes) cuando hay descuento o recargo, Descuento, Recargo o Descuento por forma de pago, y Total: todo suma a la vista. La A no cambia. — Checks: `factura-imprimible.spec.ts` (o el spec de la hoja)
- S3: "Relacionado: la columna «DTO.» queda vacía con descuento global (C)." — Aceptación: en el detalle de la venta, «Dto.» muestra el % efectivo del renglón con el global incluido (1 − (1 − r)(1 − g); 5 % y 10 % dan 14,5 %); sin ninguno, vacío. — Checks: spec de `venta-hoja`
- S4 (supuesto, mismo problema que S2 con el recargo): en el detalle de la venta, el «Total» del renglón no incluye el recargo de la forma de pago (`total − recargo`), que ya aparece abajo; así precio × cantidad − Dto. ≈ Total y la suma de los renglones más el recargo da el total.
- S5: "Además propone el código interno como código del proveedor (también lo vio B)." — Aceptación: con columnas «Código» y «Código del proveedor» en el mismo encabezado, la propuesta toma «Código del proveedor» como código; con «Código» solo, sigue tomándolo. — Checks: `ReconocedorDeColumnasTest`, `ImportacionIntegrationTest`
- S6: Luca eligió «Rechazarlo con aviso (Recomendado)»: "La importación reconoce los 17 encabezados de la exportación y no la acepta: explica que es una exportación de la app y no una lista de proveedor, y que para cambiar precios en masa está el cambio masivo." — Aceptación: subir a «Importar lista» un archivo cuyo encabezado es el de la exportación de Artículos (CSV, o el mismo guardado como Excel) da 400, sin escribir nada, en la muestra, la vista previa y al aplicar, con «Este archivo es una exportación de Artículos, no una lista de un proveedor: su costo ya trae las bonificaciones y los ajustes, y se aplicarían otra vez. Para cambiar precios en masa usá Actualizar precios.» (supuesto: el texto exacto); la pantalla lo muestra. Un archivo con otros encabezados sigue igual. — Checks: `ImportacionIntegrationTest`, spec de `importaciones`

## Tasks

- [ ] T1 · S1 · ruta: inline · Aceptación: pasar a venta con la oferta de hoy · Evidencia: — · Risk: none
- [ ] T2 · S2 S3 S4 · ruta: inline · Aceptación: hojas de factura sin IVA y de venta coherentes · Evidencia: — · Risk: item 3 (la hoja la comparten la B, su NC y el X)
- [ ] T3 · S5 S6 · ruta: inline · Aceptación: código del proveedor primero y exportación rechazada · Evidencia: — · Risk: item 3 (el reconocedor lo usan todas las listas)
- [ ] T4 · todas · ruta: inline + `odd-verifier` · Aceptación: suites, `ng build`, punta a punta y capturas de la guía si cambió una pantalla · Evidencia: — · Risk: item 3

## Log

- L1 (2026-11-05) Pedido original, textual:
  > continuamos

  Contexto: el resumen de la sesión anterior deja como próximo paso «Arreglar los 3 medios (pasar a venta con oferta, reimportar CSV, Factura B con descuento global)»; Luca, esa sesión: «los errores medios y bajos o detalles lo hacemos la proxima».
- L2 (2026-11-05) Exploración: el servidor ya da la oferta del día en `ArticuloResponse.oferta` (`OfertaResponse.java:13`, `vigente` y `precioOferta`); presupuestos y remitos arman el ticket con `ArticulosService.obtener` (`presupuestos.ts:288`, `remitos.ts:297`). `RenglonVenta.java:30-36`: `total` = bruto con el descuento del renglón y después el global, más el recargo repartido; `VentaResponse.RenglonResponse` manda `bruto`, el `descuentoPorcentaje` propio del renglón (no el global, aunque `ventas.models.ts:120` diga «el global incluido») y `recargo`. La hoja B pinta `precioUnitario` y `total` (`factura-hoja.html:100-104`) y abajo Subtotal (suma de brutos) y Descuento (todo lo descontado). Reconocedor: `SinonimosDeColumnas.java:33` pone «CODIGO» en prioridad 0 y «CODIGO DEL PROVEEDOR» en 3, y `ReconocedorDeColumnas.java:161-166` se queda con la de menor prioridad. La exportación (`ExportacionArticulosService.java:26`, 17 encabezados) escribe el costo ya calculado y la importación lo toma como lista: aplica IVA, bonificaciones guardadas del proveedor y ajustes del artículo. Ningún caso especial para la exportación propia.
- L3 (2026-11-05) Decisión de Luca sobre la reimportación: «Rechazarlo con aviso (Recomendado)». Ruteo caso 3 propuesto; Luca respondió la pregunta, que era la condición para arrancar.
