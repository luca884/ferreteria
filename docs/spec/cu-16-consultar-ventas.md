# CU-16 · Consultar y reimprimir ventas

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · caso de uso extracto de `spec.md:322-343` en la partición del 2026-11-10.

<!-- spec payload: spec.md:322-343 -->
**CU-16 Consultar y reimprimir ventas** (solo el Dueño; decidido por Luca el 2026-10-23, como la «Consulta de Facturas» de GDS: G60 y GDS-15)

1. **Pantalla «Ventas».** El Dueño busca por número, por cliente (contiene, sin distinguir mayúsculas ni tildes), por fecha «Desde / Hasta» (días de Buenos Aires, los dos incluidos) y por número de factura («12», «1-12» o «00001-00000012», de cualquier letra; otro formato da 400 «Escribí el número de la factura como 12 o 00001-00000012.»). La lista va de la más nueva a la más vieja, de a 20, con número, fecha y hora, cliente («Consumidor Final» si no tiene), usuario que la cobró, total, la marca «No facturable» y, desde la rama 4 del plan de facturación, la columna «Factura»: la letra de la hoja (X si no es válida como factura), el punto de venta y el número fiscal, el estado si no está autorizada y la marca del ambiente fuera de producción.
2. **Detalle.** Al elegir una venta se ve como al cobrarla: renglones, subtotal, descuento, recargo, total, pagos por forma, cliente, quién la cobró y si salió de un presupuesto o de un remito.
3. **Reimprimir.** «Imprimir» abre la venta en una hoja imprimible con la leyenda provisoria «Comprobante interno, no válido como factura» (se confirma con el contador). La venta con registro fiscal muestra e imprime la factura (A, B, C o X, CU-07 paso 5).
4. **Anular, sin borrar.** Anular es CU-08: nota de crédito si la venta tiene CAE, anulación interna si no. Una venta no se borra (GDS-15).
5. **Acceso.** GDS lo da por permisos de usuario («Consulta de Ventas», «Reimprimir Facturas»); acá no hay permisos por usuario y queda para el Dueño: con el listado el Vendedor podría sumar el efectivo del día y el cierre de caja dejaría de ser ciego (CU-10).
6. **API.** `GET /api/ventas` con `numero`, `cliente`, `desde`, `hasta`, `numeroFiscal`, `page` y `size` (hasta 100), con el resumen `fiscal` de cada venta, y `GET /api/ventas/{id}` (la misma respuesta que al cobrar, con `fiscal` y su `documento`, más `usuario`). El Vendedor recibe 403; una venta de otra ferretería o que no existe da 404 «Venta no encontrada», y otra ferretería no la encuentra por número de factura. Con `desde` y `hasta`, «Hasta» antes de «Desde» es 400 en `hasta` «La fecha «Hasta» no puede ser anterior a «Desde».»; la pantalla lo avisa sin consultar y enfoca «Hasta». Lo mismo vale en los listados de Compras, Presupuestos, Remitos y Movimientos del artículo (bugs-relevamiento-celular, S4); con una sola fecha o ninguna no se valida.
7. **Pendientes y rechazadas** (rama 7 del plan de facturación; D10, decidido por Luca el 2026-10-28; los detalles, el 2026-10-29). Es la bandeja de las ventas que todavía no tienen CAE o que ARCA no autorizó.
   - El filtro `fiscal` suma «Pendientes» (`PENDIENTES`: `PENDIENTE`, `EN_CURSO`, `EN_VERIFICACION` y las que están en revisión) y «Rechazadas» (`RECHAZADAS`: `RECHAZADO` y `FUERA_DE_FECHA`).
   - El detalle muestra cada error de ARCA con su código y su texto tal cual, más una explicación de los códigos conocidos, y las observaciones de una factura autorizada.
   - **«Reintentar»** (solo una rechazada o fuera de fecha). Vuelve a leer el cliente y los datos de la ferretería (CU-01) y arma una factura nueva: la letra puede cambiar. La rechazada queda como historia, con sus errores.
     - Si la fecha de la venta ya no entra en ARCA, pide confirmar que la factura salga con la fecha de hoy. Sin confirmar, 409 `FECHA_NUEVA_REQUERIDA` «La fecha de la venta ya no entra en ARCA. Confirmá que la factura salga con la fecha de hoy.». La fecha nunca cambia sola.
     - Con la facturación frenada (deshabilitada, sin certificado vigente o bloqueada por ARCA), 409 `FACTURACION_FRENADA` «La facturación está frenada: <motivo>.». Sin una factura rechazada para reintentar, 409 `SIN_FACTURA_RECHAZADA` «Esta venta no tiene una factura rechazada para reintentar.». Si el cliente de hoy no cumple la identificación del Consumidor Final, el mismo 422 que el cobro.
     - Responde como el cobro: espera el CAE el mismo rato corto y, si no llega, queda pendiente para el job.
   - **«Dejar sin factura»** (solo una rechazada o fuera de fecha), con motivo obligatorio de hasta 200 caracteres (400: «Contá por qué la venta queda sin factura.» o «El motivo tiene hasta 200 caracteres.»). La saca de la bandeja y la deja marcada «Sin factura» en Ventas, con el motivo, quién y cuándo. Es definitivo: después no se puede reintentar.
   - **En revisión.** Una diferencia con ARCA que el sistema no resuelve solo (CAE que no coincide o serie desincronizada) se ve con «Hay una diferencia con ARCA que tiene que revisar soporte.», sin acciones. La resuelve soporte y el job no la toca.
   - **«Reimprimir factura».** Una venta que salió con ticket provisorio y después se autorizó lo muestra en la lista y en el detalle.
   - API: `POST /api/ventas/{id}/factura/reintentar` (`{ "fechaDeHoy": false }`) y `POST /api/ventas/{id}/factura/sin-factura` (`{ "motivo": "…" }`), solo el Dueño. El Vendedor recibe 403 y una venta de otra ferretería, 404.
8. **Avisos en el mostrador del Dueño** (rama 7). Al abrir el mostrador y cada minuto: cuántas ventas hay rechazadas, pendientes de CAE desde hace más de 30 minutos y en revisión, y si la facturación está bloqueada por ARCA, con un enlace a «Ventas». Solo se muestra lo que no es cero (`GET /api/facturacion/avisos-mostrador`).
<!-- fin payload: spec.md:322-343 -->

**Agregado móvil: consulta y detalle de ventas** (≤639.98 px, solo Dueño).
El hub Ventas muestra los destinos de cada rol: Caja, Presupuestos, Remitos y Clientes; el Dueño suma consulta de ventas y Reportes.
En la consulta, las ventas se ven en tarjetas y «Filtros» y el chip fiscal abren una hoja inferior.
El detalle ocupa la pantalla completa: «Reintentar» y «Dejar sin factura», o «Imprimir», quedan fijos al pie según el estado;
«Anular» está en la hoja del ⋯ y «Imprimir» pasa ahí cuando el pie lo ocupa la bandeja de ARCA.
No cambian las reglas de negocio, los roles, las rutas de consulta o detalle ni los textos de ARCA.
Pendientes (L35): «Facturas» sigue siendo un selector, «Reimprimir factura» sigue también en la lista y «Anular» se apaga durante una acción «sin texto de motivo».

