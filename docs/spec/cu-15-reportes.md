# CU-15 · Reportes de ventas

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · caso de uso extracto de `spec.md:308-322` en la partición del 2026-11-10.

<!-- spec payload: spec.md:308-322 -->
**CU-15 Reportes de ventas** (solo el Dueño; decidido por Luca el 2026-10-22, como el menú Ventas de GDS: G63, G66, G67 y GDS-08)

1. **Pantalla «Reportes».** El Dueño elige el reporte y el rango «Desde / Hasta» (días de Buenos Aires, los dos incluidos, hoy al entrar, hasta 366 días). Hay seis reportes, cada uno aparte como en GDS:
   - **Resumen:** ventas (total), neto, IVA, costo, ganancia y cantidad de comprobantes.
   - **Por usuario:** quién confirmó cada venta, con cantidad, total y ganancia.
   - **Por medio de pago:** cada forma de pago con la cantidad de pagos y el monto; los montos suman el total.
   - **Por día o mes:** un renglón por período con ventas.
   - **Por artículo y por rubro:** cantidad vendida, total y ganancia; el rubro es el de hoy del artículo («Sin rubro» si no tiene).
   - **Con descuento:** las ventas con descuento general o de algún renglón, con el importe descontado.
2. **Qué entra.** Las ventas, también las «No facturables», y desde CU-08 las notas de crédito y las anulaciones internas, que **restan** en la fecha en que se hacen, no en la de la venta (como GDS). El resumen muestra «Ventas» (las ventas solas), «Notas de crédito» (cuántas y lo que restan) y el «Total» que queda, y la ganancia sin NC y con NC (G66). «Por medio de pago» no las resta, porque no mueven plata (CU-08 punto 4). Los presupuestos y los remitos no son ventas, y las cobranzas tampoco. Marca, zona, rankings y gráficos quedan fuera.
3. **Totales.** Salen del total de cada comprobante, nunca de sumar renglones (GDS-37: GDS da $ 2.000,00 en un reporte y $ 1.999,98 en otro para la misma venta).
4. **Costo y ganancia.** Cada renglón vendido copia el costo del artículo al vender, sin IVA y en pesos (un artículo en USD se pasa con el dólar vigente); cambiar el costo después no cambia la ganancia de lo ya vendido. Ganancia = neto − costo. Las ventas anteriores a este cambio no tienen costo (no se completan con el de hoy) y la pantalla las avisa: «N ventas sin costo registrado: la ganancia no las incluye.»
5. **API.** `GET /api/reportes/ventas/{resumen, por-usuario, por-medio-pago, por-periodo, por-articulo, por-rubro, con-descuento}` con `desde` y `hasta` (ISO; sin fechas, hoy; falta una, se usa la otra) y `agrupar=DIA|MES` en `por-periodo`. El Vendedor recibe 403. «Hasta» antes de «Desde» es 400 «La fecha «Hasta» no puede ser anterior a «Desde».» y más de 366 días, 400 «El reporte admite hasta 366 días.». Otra ferretería no ve ni suma estas ventas.
<!-- fin payload: spec.md:308-322 -->

**Agregado móvil: Reportes** (≤639.98 px, solo Dueño).
Desde el hub Ventas, «Reporte» abre una hoja inferior para elegir el tipo; la consulta se hace con «Ver» en el formulario.
Los resultados se ven como renglones de resumen y tarjetas, con «Imprimir» en la barra de acciones cuando corresponde.
No cambian las reglas de negocio, los roles, los cálculos ni los mensajes.
Pendientes (L37): «No hay “Volver” en Reportes» y «Agrupar por» y «Tipo» siguen siendo «select nativos».

