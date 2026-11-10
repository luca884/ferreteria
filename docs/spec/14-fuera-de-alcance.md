# Fuera del alcance del MVP

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · sección extracto de `spec.md:602-623` en la partición del 2026-11-10.

<!-- spec payload: spec.md:602-623 -->
## Fuera del alcance del MVP

Nada de esto entra hasta que haya una ferretería piloto usando el sistema todos los días. Cada ítem vuelve a la lista solo si un cliente real lo pide.

- Lo que falta de la cuenta corriente de clientes. Ya están hechas la venta a cuenta corriente con su límite y su saldo (CU-07 y CU-11), la **cobranza con imputación a cada comprobante y recibo interno** (CU-12, tramo 3a de GDS-20), la **anulación de una cobranza** con el **extracto de movimientos** por pantalla, API e impresión (CU-12, tramo 3b-1, G59 «Anular Pago»), y la **nota de crédito sobre una venta a cuenta corriente** (CU-08, rama 10 de facturación). Quedan afuera:
  - Un botón **«aplicar saldo a favor» a pendientes viejos** (desde el tramo 3b-2 la deuda nueva consume sola el crédito; igual pueden convivir si el Dueño deja un cobro o un Descuento a cuenta habiendo deuda, o si la nota de crédito de una venta ya cobrada deja saldo a favor) y **devolver un saldo a favor en efectivo** desde la caja; anular o editar un ajuste (se compensa con el contrario)
  - **Deshacer una anulación**, anular parcialmente o editar un recibo (un cobro mal cargado se anula y se cobra de nuevo), anular ajustes, una lista fija de motivos, la devolución en efectivo de un saldo a favor, **PDF, CSV o correo del extracto** (hoy es una vista imprimible del navegador) y una clave de idempotencia propia de la anulación (la anulación es idempotente por estado: 409)
  - Nota de crédito por renglones o cantidades (D13: la NC es total) y devolver efectivo desde la caja al anular (D11: se carga un egreso)
  - **PDF del recibo** (hoy es una vista imprimible del navegador), cobro con tarjeta, recargo o mora, y cobrar con otros medios (hoy efectivo o transferencia)
  - **Comisiones de vendedores** (G62): solo queda disponible el dato derivado «cobrado y pendiente por comprobante»; liquidarlas es post-MVP
- PIN de supervisor para autorizar un exceso (hoy lo autoriza solo el Dueño logueado), recargo por financiación de la cuenta corriente y límite por usuario
- De compras, todo lo que no es el tramo 1 de CU-13: órdenes de compra, pago de la compra, cuenta corriente y pago a proveedores, anulación y devolución al proveedor
- Múltiples sucursales o depósitos por ferretería
- Listas de precios múltiples (mayorista y minorista) y descuentos por cliente
- Libro IVA Ventas e integración con el contador
- Reportes avanzados y tableros: rankings, gráficos, ventas por marca o zona, stock valorizado y libro IVA (el MVP trae los reportes de ventas de CU-15)
- CAEA y modo offline
- Impresora fiscal o de tickets térmica con drivers propios (el MVP imprime PDF desde el navegador)
- Tienda online, catálogo público o integración con Mercado Libre
- Cobro de la suscripción dentro del sistema (al principio se cobra por fuera)
<!-- fin payload: spec.md:602-623 -->

