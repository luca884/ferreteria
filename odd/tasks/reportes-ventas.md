# reportes-ventas

Objetivo: el Dueño consulta las ventas de un día o de un rango de fechas en reportes separados, como el menú Ventas de GDS, con la ganancia calculada sobre el costo que tenía cada artículo al venderlo · Por qué: lo que falta contra GDS (G63, G66, G67, GDS-08); hoy no hay ningún reporte y la venta no guarda el costo
Rama: `feat/reportes-ventas` desde `4da8fa1` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / suite `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` + `npx ng build` · Mirror Engram: `odd/reportes-ventas/tasks` (ok)
Pronóstico: ~1800 líneas cambiadas · Heurística por tarea: ~400 (no es límite) · Modelo: caso 5 (Opus de punta a punta: migración de `item_comprobante`)

## Specs

Alcance: costo por renglón vendido (migración y registro de la venta), seis reportes de ventas por API y una pantalla «Reportes» para el Dueño · Fuera de alcance: devoluciones y facturas anuladas (esperan la nota de crédito), marca y zona (no existen), rankings, gráficos, exportar a Excel, imprimir.
Restricciones: los totales salen del total de cada comprobante (GDS-37), nunca de sumar renglones. Solo el Dueño (rol del JWT). Otra ferretería no ve nada (regla 7). Fechas del día de Buenos Aires (`HoraArgentina`).

Texto aprobado por Luca (2026-10-22, «dale»):

> Reportes separados, como el menú Ventas de GDS, cada uno con su «Desde / Hasta»:
> 1. Resumen del día y entre fechas: ventas, costo, IVA, ganancia y cantidad de comprobantes.
> 2. Ventas por usuario.
> 3. Ventas por medio de pago.
> 4. Ventas por día y por mes.
> 5. Ventas por artículo y por rubro.
> 6. Ventas con descuento.
>
> Quedan afuera por ahora: devoluciones y facturas anuladas, porque necesitan la nota de crédito; marca y zona, porque esos campos no existen; rankings y gráficos.
>
> Dos cosas las haría distinto a GDS: los totales se arman con el total de cada comprobante, no sumando renglones. Así evitamos el error de 2 centavos que tiene GDS. Cada venta guarda el costo que tenía al momento de vender.

- S1: "Cada venta guarda el costo que tenía al momento de vender." — Aceptación: cada renglón de una venta nueva guarda el costo unitario sin IVA en pesos (un artículo en USD se pasa con el dólar vigente de la ferretería) y el costo del renglón (unitario × cantidad, a 2 decimales); vale también al pasar a venta un presupuesto o un remito — Checks: `MigracionV26Test`, `CostoDeVentaIntegrationTest`
- S2 (supuesto, de la propuesta anterior): las ventas ya cargadas quedan sin costo (no se completan con el costo de hoy); los reportes avisan cuántas ventas no tienen costo y la ganancia no las incluye — Checks: `MigracionV26Test`, `ReporteVentasIntegrationTest`
- S3: "Resumen del día y entre fechas: ventas, costo, IVA, ganancia y cantidad de comprobantes." — Aceptación: `GET /api/reportes/ventas/resumen?desde&hasta` devuelve total, neto, IVA, costo, ganancia (neto − costo, solo de ventas con costo), cantidad de comprobantes y cantidad sin costo — Checks: `ReporteVentasIntegrationTest`
- S4: "Ventas por usuario." — Aceptación: por usuario que confirmó la venta: nombre, cantidad, total y ganancia — Checks: ídem
- S5: "Ventas por medio de pago." — Aceptación: por forma de pago (nombre guardado en el pago): cantidad de pagos y monto; la suma de los montos es el total del resumen — Checks: ídem
- S6: "Ventas por día y por mes." — Aceptación: un renglón por día (o por mes) con ventas: cantidad, total y ganancia — Checks: ídem
- S7: "Ventas por artículo y por rubro." — Aceptación: por artículo: descripción, cantidad vendida, total y ganancia; por rubro (el rubro actual del artículo, «Sin rubro» si no tiene): cantidad de renglones, total y ganancia — Checks: ídem
- S8: "Ventas con descuento." — Aceptación: las ventas con descuento global o de algún renglón: número, fecha, usuario, cliente, descuento global %, importe descontado y total — Checks: ídem
- S9: "los totales se arman con el total de cada comprobante, no sumando renglones." — Aceptación: el total del resumen es la suma de `comprobante.total`; los cortes por usuario, día y mes también — Checks: ídem
- S10 (supuesto): solo el Dueño (Vendedor 403); sin fechas es hoy (falta una, se toma la otra); «Hasta» antes de «Desde» da 400 «La fecha «Hasta» no puede ser anterior a «Desde».» y más de 366 días, 400 «El reporte admite hasta 366 días.»; entran las ventas «No facturables»; no entran presupuestos, remitos ni cobranzas — Checks: ídem
- S11: aislamiento por tenant (regla 7): los reportes de una ferretería no cuentan ventas de otra — Checks: ídem
- S12: pantalla «Reportes» (solo Dueño) con «Desde / Hasta» (hoy por defecto) y los seis reportes — Checks: specs de Vitest + ego-browser

## Tasks

- [x] T1 · S1 S2 · ruta: inline · Aceptación: V26 aditiva (`costo_unitario` y `costo` nulos en `item_comprobante`) + `MigracionV26Test` + rollback + `MigracionV6Test` a V26 · Evidencia: RED 5 errores (sin V26) y 1 falla → GREEN `MigracionV26Test` 6/6 + `MigracionV6Test` 14/14 (el CHECK con un solo NULL pasaba por UNKNOWN; se exige IS NOT NULL en las dos) · commit en la línea de abajo · Risk: item 1 (migración)
- [x] T2 · S1 · ruta: inline · Aceptación: la venta guarda el costo (pesos y USD, desde presupuesto y remito) · Evidencia: RED `CostoDeVentaIntegrationTest` 5 fallas (costo nulo) → GREEN 6/6 con `ArticulosParaVentaServiceTest` 8, `RegistroVentaTest` 78, `AjusteStock*Test`, `ModulosArchTest` 40, `ComprobanteRepositoryTest` 2 · sin test: USD sin ningún dólar guardado deja el costo en NULL (la tabla de cotizaciones es compartida entre tests) · commit siguiente · Risk: item 3 (`ArticuloVendible` lo usan ventas y stock)
- [x] T3 · S2-S11 · ruta: inline · Aceptación: los seis reportes por API con rol, rango y tenant · Evidencia: RED `ReporteVentasIntegrationTest` 15 fallas (404) → GREEN 15/15 con `ModulosArchTest` 40, `TenantScopingArchTest` 24, `ArticulosParaVentaServiceTest` 8 · `GET /api/reportes/ventas/{resumen,por-usuario,por-medio-pago,por-periodo?agrupar=DIA|MES,por-articulo,por-rubro,con-descuento}` · commit siguiente · Risk: none
- [x] T4 · S12 · ruta: inline · Aceptación: pantalla y menú; ego-browser · Evidencia: RED compilación (módulos inexistentes) y `shell.spec`/`app.routes.spec` 2 fallas → GREEN `features/reportes` 19/19, suite front 2879/2879, `ng build` OK · ego-browser: dos ventas por API (Vendedor 2 × «EXPRIM-O» BLANCO en efectivo; Dueño 1 × NARANJA con 10 % en débito); resumen del 22/10: 7 comprobantes, $ 737.307,75, costo $ 378.850,50 (3 × 126.283,50), ganancia $ 97.252,81 y «5 ventas sin costo registrado» (las del E2E anterior a la V26); por medio de pago suma el total; por rubro, por artículo, por usuario y con descuento OK; «Hasta» antes de «Desde» avisa sin pedir; al cambiar el día, «hoy» pasó a 23/10. Después se cerró la caja de prueba · commit siguiente · Risk: none
- [ ] T5 · S1-S12 · ruta: inline · Aceptación: `docs/spec.md` (CU-15) y `docs/relevamiento-gds.md` (G63, G66, G67, GDS-08) · Evidencia: — · Risk: none

## Log

- L1 (2026-10-22) Pedido original, textual: «dale» a «¿Arranco por los reportes con el costo en el renglón?»; después «revisa como lo hace gds», «vamos por el lado de gds» y «dale» a la lista de seis reportes citada arriba.
- L2 (2026-10-22) GDS (bitácora 7.53, `mostrador-gestion` `origin/main`): `Resumen del día` dio ventas $2.000,00, costos $1.080, IVA $347,10, ganancia $572,90 (ganancia = neto − costo); suma renglones y no coincide con `Clientes Activos` ($1.999,98). El menú Ventas tiene resumen del día y entre fechas, ganancia entre fechas, por día, mes, artículo, zona, usuario, marca, rubro, medio de pago digital, con descuento y anuladas, cada uno con «Desde / Hasta». Código: `item_comprobante` sin costo (`V4__ventas_y_stock.sql:30`); los renglones se arman en `RegistroVenta.java:195`; `ArticuloVendible` sale de `ArticulosParaVentaService.vendible`; el dólar vigente es `DolarDePrecios.vigente()`; nombres de usuario con `CajaRepository.nombresScoped`; Dueño con `@PreAuthorize("hasRole('DUENO')")`.
