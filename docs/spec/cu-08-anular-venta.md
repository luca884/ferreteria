# CU-08 · Anular una venta

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · caso de uso extracto de `spec.md:147-168` en la partición del 2026-11-10.

<!-- spec payload: spec.md:147-168 -->
**CU-08 Anular una venta** (solo el Dueño; rama 8 del plan de facturación; D11, D12 y D13, decididos por Luca el 2026-10-28, y los detalles el 2026-10-29)

1. **Desde el detalle de Ventas** (CU-16), con «Anular», que va separado de «Imprimir», no tiene atajo de teclado y pide confirmación: «¿Anular la venta N.º n? Se emite una nota de crédito y vuelve el stock. La caja no cambia: si devolvés plata, cargá un egreso.» Sin CAE (punto 3), dice «La venta no tiene factura: queda anulada con el motivo y vuelve el stock.» en lugar de la primera oración.
2. **Con nota de crédito** (la venta tiene su factura `AUTORIZADO`). Se emite una nota de crédito **total** (D13: una devolución parcial es la NC total y una venta nueva con lo que el cliente se queda) con su propio CAE, sin motivo:
   - lleva la letra de la factura (`CbteTipo` 3, 8 o 13), el comprobante asociado (`CbtesAsoc`: tipo, punto de venta, número, CUIT del emisor y fecha) y el receptor copiado de la factura, no el cliente de hoy;
   - sale solo en el ambiente de la factura, con el mismo motor y su propia serie;
   - si ARCA no contesta, queda pendiente como una factura (job de reintento, rama 7); si la rechaza, sigue siendo la única NC de esa venta y se corrige con «Reintentar» desde la bandeja de Ventas, que vuelve a copiar de la factura.
3. **Con anulación interna** (D12: la venta no tiene CAE: no facturable, anterior a la facturación o con la facturación apagada, pendiente que nunca se envió, rechazada, fuera de fecha o sin factura). ARCA solo admite una NC asociada a una factura de su clase, así que se anula sin documento fiscal, con motivo obligatorio de hasta 200 caracteres (400: «Contá por qué se anula la venta.» o «El motivo tiene hasta 200 caracteres.»). Un pendiente que nunca se envió se toma con el mismo cambio condicional que usa el job: o lo envía el job o lo anula el Dueño, nunca las dos cosas.
4. **Efectos** (los mismos en los dos casos). La NC y la anulación interna son comprobantes `NOTA_CREDITO` con su número interno de siempre, asociados a la venta:
   - **stock:** vuelve todo lo vendido, también lo que salió por el remito de origen, con movimientos «Nota de crédito N.º n» o «Anulación N.º n». El remito sigue «facturado»;
   - **caja:** no cambia (D11, como GDS: «Actualiza Caja» destildado). La NC no lleva caja ni pagos; si se devuelve efectivo, el Dueño carga un egreso (CU-10);
   - **cuenta corriente** (rama 10 del plan de facturación; S6 a S9, confirmados por Luca el 2026-10-29): si la venta tenía pagos a cuenta corriente, la NC acredita **solo esa parte**, y la anulación interna hace lo mismo. Por cada pago a cuenta corriente de la venta asienta un movimiento `NC` al haber, con el monto de ese pago y la referencia «Nota de crédito N.º n» o «Anulación N.º n», y el saldo del cliente baja en esa suma. El crédito se **imputa primero a la venta anulada**, hasta lo que esa factura todavía debe; lo que sobra (la venta ya estaba cobrada en todo o en parte) queda **a favor** y lo usa sola la próxima venta a cuenta corriente del cliente, igual que el crédito de un recibo (CU-12 punto 8). La parte pagada con otros medios sigue D11: no se toca y, si se devuelve efectivo, el Dueño carga un egreso. Ejemplo: una venta de $ 1.500, con $ 1.000 en efectivo y $ 500 a cuenta: la NC acredita $ 500; si la venta debía esos $ 500, queda pagada; si ya estaba cobrada, quedan $ 500 a favor. Un pago a cuenta no se acredita dos veces;
   - **reportes:** CU-15 las resta en la fecha en que se hacen (punto 2);
   - **Ventas:** la venta queda «Anulada por la NC N.º n» o «Anulada: <motivo>», con quién y cuándo, y la NC o la anulación aparece como un renglón propio con el total en negativo y la venta que anula. Desde su detalle se reimprime la NC, con QR solo en producción.
5. **Errores:**
   - con la factura en envío, en verificación o en revisión, 409 «Esperá el CAE de la factura para anularla.»;
   - una venta ya anulada, 409 «La venta ya fue anulada por <usuario> el <fecha>.» (una sola NC por venta: dos a la vez dan una 201 y una 409);
   - una venta con un pago a cuenta corriente de un cliente desactivado, 422 «El cliente está desactivado.» (un cliente desactivado tiene saldo 0 y la NC lo dejaría a favor: primero se reactiva). Solo vale para la venta que cargó a cuenta corriente: las demás se anulan como siempre aunque su cliente esté desactivado;
   - una nota de crédito no se anula: 409 «Una nota de crédito no se anula.»
6. **API:** `POST /api/ventas/{id}/anular` con `{ "motivo": "…" }` (el motivo solo vale en la anulación interna). Responde 201 con la NC. El Vendedor recibe 403 y la venta de otra ferretería, 404.
<!-- fin payload: spec.md:147-168 -->

