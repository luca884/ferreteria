# GDS Ferretería Plus 7 contra la spec de ferreteria

Informe de investigación (solo lectura) sobre el relevamiento de GDS que Luca hizo en `mostrador-gestion`, cruzado con `docs/spec.md` de ferreteria.

**Fechas.** Primera versión: 2026-10-12, sobre GDS 7.51. Actualizado el 2026-10-13 con la 7.53 (relevada el 12 y el 13 de octubre por interfaz) y con el catálogo funcional y el informe final del relevamiento (`origin/main`, commit `11e5a1a`).

**Abreviaturas de fuentes.** Todas son rutas relativas al clon `mostrador-gestion`, en `origin/main`:

- `R` = `documentacion/investigaciones/gds-ferreteria-plus-7/README.md` (bitácora). `R:NNN` son líneas del README **del commit `20e20ed`** (el de la primera versión de este informe); el README creció mucho y esas líneas ya no coinciden. Las citas nuevas usan el id de la entrada de bitácora (`F-1450`, etc.), que no cambia.
- `C` = `documentacion/investigaciones/gds-ferreteria-plus-7/catalogo-funcional.md` (catálogo funcional, 2026-10-13). `C §3` es su sección 3.
- `I` = `documentacion/investigaciones/gds-ferreteria-plus-7/informe-final.md` (conclusiones y matriz comparativa).
- `BL` = `documentacion/backlog.md`, sección "Brechas detectadas en el relevamiento GDS" (es el backlog de Mostrador, no el de ferreteria).
- `M:nombre` = `documentacion/investigaciones/gds-ferreteria-plus-7/capturas/menu-753-<nombre>.txt` (volcado exacto de menús de la 7.53, con sus teclas).
- `S` = `documentacion/sesiones/2026-08-02-relevamiento-gds.md`.
- `fNNN` = captura `capturas/fNNN-*.png`. De la primera versión: abrí solo siete (f010, f121, f323, f413, f636, f637 y f655). **De la 7.53 no abrí ninguna captura**: los datos salen del texto de la bitácora, del catálogo y de los volcados de menú.

**Marcas del catálogo** que se repiten abajo: **[probado]** se ejecutó con datos, **[visto]** se abrió la pantalla sin operar, **[manual]** solo descrito en el manual o en plantillas, **[bloqueado]** no disponible en la demo.

---

## 1. Resumen del relevamiento

**Qué se relevó.** Ferretería Plus de GDS Sistemas, versión demo instalada localmente en Windows, con datos ficticios. Hubo dos tandas:

- **7.51, 2026-08-02** (de 15:46 a 19:17). Luca la automatizó con scripts de PowerShell y UI Automation (`tools/gds-*.ps1`). Sacaba una captura y un JSON del árbol de controles por paso.
- **7.53, 2026-10-12 y 2026-10-13.** La demo de la 7.51 expiró el 2026-10-12 y bloqueó el programa entero, sin modo de solo lectura (F-1100). El titular del equipo reinstaló y quedó la 7.53, una demo funcional salvo `Remitos` (F-1400, F-1450). Se cambió el método: ahora se recorren los menús por la API de Windows (233 opciones, `M:principal`) y se probó un circuito completo con datos ficticios: compra, venta mixta, venta a cuenta corriente, cobranza con cheque, pago a proveedor, comisiones, presupuesto facturado, compra con desglose y ocho reportes (F-1600 a F-1720).

El objetivo de Luca era usar GDS como referencia funcional para "Mostrador", un gestor comercial general (multi-sucursal, Next.js/Prisma). No era una ferretería SaaS como la actual. Por eso el relevamiento es administrativo y la 7.53 sigue sin mostrar un importador de listas de proveedor ejecutado.

**Tres conclusiones del informe final** (`I`) que también valen para ferreteria:

1. Los documentos tienen que tener **efectos fijos y trazables** sobre stock y caja. En GDS el mismo comprobante mueve o no stock según interruptores globales, y no queda registrado con cuál se emitió (F-1710).
2. La cuenta corriente tiene que **imputar cobros a comprobantes**. GDS trabaja sobre el saldo total y por eso solo cobra comisión la venta pagada completa en el mismo comprobante (F-1700).
3. **Lectura y destrucción separadas.** `Anular`, `Borrar`, `Vaciar Tabla` y las correcciones masivas conviven con las consultas.

**Cobertura** (`R`, `S`, `C`, `I`, `BL`):

| Módulo GDS | Estado | Profundidad |
| --- | --- | --- |
| Navegación, menús, accesos rápidos | Relevado | Completo. En la 7.53, además, el volcado exacto de los menús con sus teclas (`M:*`) |
| Artículos: alta, modificación, costo calculado | Relevado | Bueno (F-100, F-120, F-1420) |
| Artículos: **Actualización de Precios** | **Visto en 7.53, no ejecutado** | Filtros, fórmula y redondeo documentados (F-1420, f1414). No se sabe si hay vista previa |
| Artículos: **Importar / Exportar** | **Visto en 7.53, no ejecutado** | Importación y exportación rápida, y dos asistentes (F-1420). Es el único módulo adicional habilitado de la demo |
| Artículos: etiquetas con código de barras | **Sin acceso en la demo** | Solo plantillas instaladas (F-1720) |
| Clientes | Relevado | Formulario completo, cuenta corriente y cobranza (F-1430, F-1450, F-1600) |
| Compras y proveedores | Relevado | Ingreso, costo real, desglose de pago, cuenta corriente, pago y desglose de compras (F-1440, F-1600, F-1720) |
| Cheques y bancos | Relevado | Muy profundo (ciclo completo y bugs) |
| Ventas y facturación | Relevado | Factura A a un RI y Factura B a consumidor final, pago mixto, límite de cuenta corriente (F-1450, F-1600, F-1700). **CAE y ARCA no están habilitados en la demo**: el ejecutable trae el formulario `TF_FACTURA_ELECTRONICA` pero no se pudo abrir (`C §7`) |
| Presupuestos | **Probado en 7.53** | Descuento de stock según interruptor y `Traer` sin doble descuento (F-1710) |
| Remitos | **Bloqueado en la demo** (F-1450) | Solo se vio que existe. Se hizo en CU-07 según el catálogo (G55), con efecto fijo |
| Vendedores y comisiones | Relevado | Liquidación probada (sin pagar) y falla por falta de imputación (F-1700) |
| Caja | Probado | Caja del día, ingresos varios, gastos varios y retiros (F-1460). No hay apertura ni cierre como operación |
| Reportes | Catálogo + 8 ejecutados | 307 plantillas (F-1300), ocho reportes con datos (F-1600) y gráficos (F-1470) |
| Usuarios, permisos, configuración | **Probado en 7.53** | 28 permisos más administrador, límites, solapas de configuración (F-1410, F-1480) |
| Auditoría | Visto | Consulta, anulaciones y controles (F-1000, F-1480) |
| Backups, mantenimiento, solucionar problemas | Manual y menú | Once opciones de `Solucionar Problemas` y cinco de `Mantenimiento` inventariadas, ninguna ejecutada (F-1200, F-1400) |

**Qué cambia para CU-04.** La **Actualización de Precios** de GDS ya se vio, así que dejó de ser un hueco. Pero sigue sin haber un importador de listas ejecutado: lo que se sabe de las listas de proveedores sale del costo calculado del artículo, de la compra y de `Configurar Descuentos`. Ver la sección 4.

---

## 2. Inventario de funciones de GDS

Hay 101 funciones, G01 a G101. Las G01 a G84 son las de la primera versión (varias corregidas o ampliadas con la 7.53, marcadas con "7.53"). Las **G85 a G101 son nuevas**. La clase (A a E) se justifica en la sección 3.

| # | Módulo | Función | Detalle observado | Fuente |
| --- | --- | --- | --- | --- |
| G01 | General | Accesos rápidos por tecla | F1 Artículos, F2 Clientes, F3 Compras, F4 Ventas, F5 Remito, F6 Presupuestos y botones grandes laterales. Cada pantalla tiene además su propio menú con teclas (7.53). Inventario completo en la sección 6 | `R:95-96`, `R:100`, `R:134`, `M:principal`, `M:ventas`, F-001 |
| G02 | General | Cotización del dólar global | "Dolar: $1.100,00" fijo en la pantalla principal. La opción `Valor del Dolar` no abre ninguna ventana (7.53) | `R:97`, `R:101`, f636, F-1480 |
| G03 | General | Panel de ayuda, guías y manual | Panel lateral "¿Necesitás ayuda?" y menú Ayuda | `R:98`, `R:153-156` |
| G04 | General | Notas | Botón lateral "Notas" (no se exploró) | `R:96`, f636 |
| G05 | General | Impresora de Windows obligatoria | Aviso que bloquea el ingreso y la confirmación de una compra. En 7.53 también al grabar una factura: avisa dos veces y el segundo aviso queda **detrás** de la ventana de venta y la bloquea sin verse (7.53) | `R:46-47`, `R:53-54`, `R:315-316`, F-1700 |
| G06 | General | Reparación de integridad | Reindexa después de un cierre anormal. Cerrar varios formularios de golpe tira un "Access violation" (7.53) | `R:48-50`, `R:55-56`, F-1490 |
| G07 | General | Licencia de la demo | Cuenta los usos disponibles. Al vencer, la 7.51 se bloqueó por completo, sin solo lectura ni salida de datos (7.53) | `R:43`, `R:52`, F-1100, `I` |
| G08 | Artículos | Identificación | Código interno (es también el código que se escanea), código del proveedor y descripción | `R:177`, f121 |
| G09 | Artículos | Clasificación | Rubro, unidad, **un solo** proveedor y ubicación | `R:178`, f121 |
| G10 | Artículos | Marca | Campo propio, búsqueda F3 por marca y reportes por marca | `R:178`, f323, f636 |
| G11 | Artículos | Tablas auxiliares | Rubro, marca y unidad editables desde el formulario y desde Administración | `R:151`, `R:280-281`, f121 |
| G12 | Artículos | Stock y stock mínimo | El stock no se edita en el maestro, solo por ajuste o ingreso | `R:179`, `R:226-227` |
| G13 | Artículos | Costo, % de ganancia e IVA | Ganancia por artículo (30 por defecto) e IVA en combo (21; también existe 10,5) | `R:180`, f121, f413 |
| G14 | Artículos | **Costo calculado** | "Precio de lista del Proveedor" menos **hasta 3 descuentos encadenados** (`% + % + %`) da el costo. El costo directo está bloqueado ("El costo se calcula desde la solapa COSTO CALCULADO"). En 7.53 se confirma en la compra (`Cálculo de Costo real`: lista $300 y 10 % dan $270) y existe `Configurar Descuentos` (G96) | `R:185`, `R:228-229`, `R:308-310`, f323, f121, F-1420, F-1600, F-1480 |
| G15 | Artículos | Precio efectivo y precio tarjeta | Dos precios por artículo. En la demo la tarjeta está 25 % arriba (4.500 contra 5.625). Los nombres de las cuatro listas son configurables (7.53) | `R:181`, f323, F-1480 |
| G16 | Artículos | Lista 3 y lista 4 | Precios manuales extra (por ejemplo mayorista). Hay una opción "Las listas son precio neto" (7.53) | `R:181`, `R:231-232`, F-1480 |
| G17 | Artículos | "Lista 1 calculada" y "$ Calculado" | Marca si el precio sale de costo × ganancia o es manual | `R:182-183`, f121 |
| G18 | Artículos | Costo en dólares y Lista 1 en dólares | Costo y precio en USD convertidos con la cotización global | `R:182-183`, f121 |
| G19 | Artículos | Fecha de última compra | La actualiza el ingreso de mercadería | `R:184`, `R:317` |
| G20 | Artículos | En promoción | Marca y filtro en el stock valorizado | `R:184`, `R:560-561` |
| G21 | Artículos | Fotografía | Botón F12 y listado con foto | `R:184`, `R:187-188`, `R:542` |
| G22 | Artículos | Fecha de modificación | Columna "F. Modif." en el listado y en la búsqueda de artículos de la venta (7.53) | `R:196-197`, f323, F-1600 |
| G23 | Artículos | Alta mínima | Alcanzan código y descripción; costo y precio son opcionales | `R:191-200` |
| G24 | Artículos | Listado | Nuevo, modificar, borrar, buscar (F1 código, F2 descripción, F3 marca), ordenar y movimientos. 18 columnas, vista configurable y dos perfiles guardados: solo consulta y modificación. Teclas propias en el menú (7.53) | `R:186-188`, f323, F-1420, `M:articulos` |
| G25 | Artículos | Importar / Exportar y vista configurable | 7.53: `Importación Rápida` (actualizar descripciones, stock y precio, solo precios, reemplazar o agregar), `Exportación Rápida` y dos asistentes. Abiertos, **no ejecutados** | `R:189`, f323, F-1420 |
| G26 | Artículos | Actualización de precios | 7.53: filtra por marca, rubro, proveedor y rango de códigos; elige si cambia el **costo** o el **precio de venta**; fórmula `Lista X = Lista Y + valor` en % o en $; redondeo a un múltiplo configurable (0,50 por defecto). No se ejecutó | `R:115`, f010, F-1420 (f1414) |
| G27 | Artículos | Ajuste de stock | Panel con el maestro y panel con los ajustes pendientes y su signo. Trae un botón `Poner Stock en Cero` (no se ejecutó) (7.53) | `R:116`, f637, F-1420 |
| G28 | Artículos | Salida de mercadería y consulta de salidas | Salidas por artículo con motivo (tabla auxiliar; la demo trae solo `ROBO`). La consulta muestra `Total Costo` y `Total Precio` por salida y ofrece `Anular` y `Borrar` en la misma pantalla (7.53) | `R:117-118`, f637, F-1420 |
| G29 | Artículos | Movimientos por artículo | Histórico con el movimiento de entrada asociado al remito. También "clientes que compraron el artículo" (Ctrl+A) | `R:187`, `R:318-319`, f346, `M:articulos` |
| G30 | Clientes | Datos generales | Código automático, razón social, contacto, fecha de nacimiento, dirección con provincia y país, teléfono, celular, WhatsApp, DNI/CUIT, condición IVA, rubro, correo y web | `R:257-262`, F-1430 |
| G31 | Clientes | Política comercial | Transporte, zona, vendedor, límite de cuenta corriente, % de descuento, lista por defecto y condiciones. La pantalla aclara que esos datos "se cargan directamente en la factura" (7.53) | `R:263-269`, F-1430 |
| G32 | Clientes | Observaciones | Texto libre largo | `R:270` |
| G33 | Clientes | Validación mínima | Solo la razón social es obligatoria | `R:271-274` |
| G34 | Compras | Ingreso de mercadería | Tipo de comprobante (`REM` por defecto), punto de venta, número, categoría de la compra y fecha. Renglones con costo editable (`/` abre el cálculo con 3 descuentos, ver G86). Suma stock y movimiento. **Corrige la primera versión**: el costo sí se puede tocar en la compra | `R:290-319`, F-1440, F-1600 |
| G35 | Compras y ventas | Renglones repetidos | En la compra, "unificar renglones" es un interruptor. En la venta, `Unificar Renglones al ingresar el mismo artículo` viene **tildado** (7.53). Corrige la primera versión: no era un defecto fijo | `R:311-312`, F-1440, F-1480 |
| G36 | Compras | Pago combinado de la compra | Efectivo, pago digital, cheque y cuenta corriente. Ver G53 para el desglose y su defecto del mouse (7.53) | `R:313-314`, F-1600 |
| G37 | Compras | Nota de crédito del proveedor y su consulta | Se titula "Devoluciones a Proveedor", con `Actualiza Caja` y `Actualiza Stock` destildados (7.53) | `R:122`, `R:125`, F-1440 |
| G38 | Compras | Consulta de compras | Reconstruye el remito con encabezado, pago y renglones. Busca por F1 número, F2 fecha, F3 proveedor | `R:124`, `R:366`, `R:374-375`, F-1440 |
| G39 | Proveedores | Cuenta corriente del proveedor | Últimos 21 movimientos y saldo. Acciones `Pago`, `Adelanto`, `Recargo`, `Anular Pago`, `Detalle del Comprobante` y `Orden de Pago` (7.53) | `R:318-319`, f593-f597, F-1440 |
| G40 | Proveedores | Pago a proveedores | Fecha, importe, concepto y medio; orden de pago y anular pago. Alimenta "Pagos a proveedores" en la caja | `R:380-400` |
| G41 | Proveedores | Maestro de proveedores | Listado con el saldo. Mismas columnas que clientes, sin política comercial (7.53) | `R:137`, f350, F-1430 |
| G42 | Cheques | Alta de cheque | Recepción, importe, cliente, número, banco, vencimiento y "sale efectivo de caja". El alta manual **no valida** importes ni datos mínimos (`I`) | `R:424-427`, `I` |
| G43 | Cheques | Cartera | Depositar en una cuenta, rechazar (afecta la cuenta del cliente) y anular. Pestañas de cartera, rechazados e histórico. La anulación de un cheque rechazado no tuvo efecto verificable (`I`) | `R:428-436`, `I` |
| G44 | Cheques | Entregar un cheque al proveedor | Se usa como medio de pago a proveedores | `R:437-439` |
| G45 | Cheques | Maestro de bancos | Solo descripción, con "Vaciar Tabla" | `R:440-441` |
| G46 | Cheques | Consultas | Cartera, recibidos hoy o entre fechas, vencen en 5 días, entregados e histórico a Excel | `R:126-132`, `R:443-449` |
| G47 | Ventas | Factura según el cliente | El cliente hereda tipo de factura, condición IVA, vendedor, lista, descuento y forma de pago. El tipo A/B cambia solo según la condición de IVA, con numeración independiente; Factura M opcional (7.53) | `R:339-340`, f413, F-1600, F-1480 |
| G48 | Ventas | Lista de precios en la venta | Se elige la lista (1 a 4) por comprobante | `R:341-343`, f413 |
| G49 | Ventas | Renglón con precio cero | Lo permite. Al cambiar de lista no recalcula los renglones | `R:341-343` |
| G50 | Ventas | Precio con IVA incluido y desglose | Neto, IVA 21 % e IVA 10,5 % por separado. La lista 3 de 1.400 se toma como precio final. Con 4 unidades a $500 el total del comprobante da **$1.999,98** y no $2.000, porque redondea por renglón (7.53) | `R:344-345`, f413, F-1600 |
| G51 | Ventas | Descuento por renglón y global | Columna "Dto%", campo "% Desc" y teclas `+` (por ítem) y `-` (global). Cada usuario tiene un límite de descuento (80 % en el ADMIN de la demo) | f413, F-1450, F-1410 |
| G52 | Ventas | Ajuste por redondeo del total | Casilla "Ajuste x Redondeo", destildada por defecto | f413, F-1600 |
| G53 | Ventas | Pago mixto | Desglose con solapas EFECTIVO, P. DIGITAL, CHEQUE y CTA. CTE. Al cargar una parte, el resto se calcula solo y `Enter` pasa al medio siguiente. **Defecto**: cambiar de solapa con el mouse confirma el importe sugerido | `R:346-348`, `R:622-623`, F-1600 |
| G54 | Ventas | Límite de cuenta corriente | Pasarlo bloquea la confirmación ("El limite de venta a cta cte del cliente fue superado") y no ofrece autorización. La salida es repartir el pago | `R:346-348`, F-1600 |
| G55 | Ventas | Remitos (F5) | **Bloqueado en la demo**. El catálogo los describe: envío con o sin descuento de stock, luego se factura con `Traer`. Hay un interruptor para imprimir el remito junto a la factura. **Hecho en CU-07:** el remito es un documento de entrega con efecto fijo (descuenta stock al emitirlo, no cobra), se factura con «Pasar a venta» conciliando la diferencia de stock, y el Dueño puede anularlo | `R:77`, `R:134`, F-1450, `C §3` |
| G56 | Ventas | Presupuestos (F6) | **Probado en 7.53**. Misma estructura que la factura, con descuento por renglón. Estados pendiente, aceptado, rechazado y facturado (en los reportes y el catálogo). Se factura con `Traer` sin doble descuento de stock. Interruptores para descontar stock al emitirlo y para limitar presupuestos sin stock | `R:77`, `R:134`, F-1450, F-1710, `C §3` |
| G57 | Ventas | Extras de la factura | Botones "Traer" (ver G56) y "Seña", N° de orden de compra, remito y observaciones. `Seña` exige un permiso propio que el ADMIN de la demo no tiene tildado | f413, F-1410, F-1450 |
| G58 | Ventas | Notas de crédito | Parte del `Nro. Factura` con buscador. Interruptores independientes `Actualiza Caja` (destildado) y `Actualiza Stock` (tildado) | `R:135`, F-1450 |
| G59 | Ventas | Cobranzas | Cuenta corriente del cliente con `Pago`, `Descuento`, `Adelanto`, `Recargo`, `Anular Pago` e `Imprimir Recibo`. Medios: efectivo, tarjeta, cheque, retenciones, depósito o transferencia y otros. **No imputa el cobro a la factura**: la factura financiada conserva su saldo aunque el cliente esté al día (el manual lo declara) | `R:353-378`, F-1450, F-1600, F-1700 |
| G60 | Ventas | Consulta de facturas y NC | Reconstruye el comprobante y ofrece **Anular** y **Borrar** junto a `Imprimir`. Busca por F1 número, F2 fecha, F3 cliente. Exporta encabezado y renglones por separado | `R:136`, `R:374-375`, F-1450, `M:consulta-facturas` |
| G61 | Vendedores | Maestro de vendedores | Documento, domicilio, teléfonos, sueldo, % de comisión, interruptor "Sobre el Neto", zona y observaciones | `R:500-502`, F-1430 |
| G62 | Vendedores | Liquidación de comisiones | Sobre total o neto, inclusión manual de facturas, % por factura (F3) y Pagar. **Solo liquida facturas con saldo propio cero**: con cuenta corriente sin imputación, solo cobra comisión la venta pagada completa en el mismo comprobante | `R:503-511`, F-1700 |
| G63 | Vendedores | Reporte de ventas por vendedor | Por rango de fechas | `R:510-511` |
| G64 | Caja | Caja del día | Caja inicial (editable o arrastrada del día anterior con `-1`), ingresos y salidas por concepto, y totales por efectivo, pago digital, cheque y otros **a la vez**. Es una vista del día, **sin apertura ni cierre**. Admite efectivo negativo sin aviso (-$200 en rojo) | `R:143-144`, `R:349-351`, `R:376-378`, `R:396-400`, F-1460, F-1600 |
| G65 | Caja | Ingresos varios, gastos varios y retiros | **Probados en 7.53**. Los tres comparten formulario, son solo efectivo y la descripción es un combo con alta rápida | `R:80-81`, `R:144`, F-1460 |
| G66 | Reportes | Resumen y ganancia | Resumen del día o entre fechas (ventas, devoluciones, costo, IVA, ganancia) y ganancia con y sin NC. Ejecutado: ventas $2.000, costo $1.080, ganancia $572,90. Suma renglones, mientras `Clientes Activos` suma totales de comprobante ($2.000,00 contra $1.999,98) | `R:539`, `R:558-559`, f636, F-1600 |
| G67 | Reportes | Ventas del día, entre fechas, por día y mensuales | Submenú Ventas | f636 |
| G68 | Reportes | Ventas por medio de pago digital y con descuento | Submenú Ventas | `R:539-540`, f636 |
| G69 | Reportes | Ventas agrupadas | Por artículo, zona, usuario, marca y rubro | `R:540`, f636 |
| G70 | Reportes | IVA Ventas e IVA Compras | Libro IVA Ventas (normal, a Excel, agrupado y por provincia). El libro IVA Compras se **probó** con una compra de desglose (ver G87) | `R:540`, f636, F-1720 |
| G71 | Reportes | Facturas anuladas | Submenú Ventas | `R:541`, f636 |
| G72 | Reportes | Reportes de artículos | Listado, por rubro, stock valorizado (ejecutado: 6 × $270 = $1.620), stock crítico, stock inicial, ajustes, con ubicación, salidas y con foto | `R:541-542`, `R:560-562`, f637, F-1600 |
| G73 | Reportes | Reportes de caja | Diaria (ejecutado: coincide con la pantalla), ingresos, gastos y retiros | `R:543`, F-1600 |
| G74 | Reportes | Rankings | Por artículo (cantidad, precio, rentabilidad, menos vendidos), cliente o vendedor | `R:544`, f639, `C §8` |
| G75 | Reportes | Gráficos estadísticos | Cuatro vistas: marcas por cantidad, ganancia por marca, productos por cantidad y clientes por monto | `R:536`, f636, F-1470 |
| G76 | Reportes | Reportes de otros módulos | Compras, devoluciones, remitos, presupuestos, clientes (cumpleaños del mes, activos), cuentas corrientes, proveedores y pagos. En total 307 plantillas, 86 de comprobantes y 37 de artículos | `R:145-147`, `R:534-536`, F-1300 |
| G77 | Administración | Usuarios y permisos | **Probado en 7.53**: 28 permisos por operación más `ser ADMINISTRADOR`, límite de descuento y de cuenta corriente por usuario, y usuarios conectados. Las claves se ven en texto plano en la grilla y `Ingreso con clave` viene apagado. Un permiso negado **deshabilita** el control | `R:83`, `R:151`, F-1410, F-1480 |
| G78 | Administración | Configuración, solucionar problemas y mantenimiento | La configuración se vio (G89, G91, G92, G95, G97 y G98 salen de ahí). Las once opciones de `Solucionar Problemas` y las cinco de `Mantenimiento` no se ejecutaron: borran cobranzas, pagos y detalle de cuentas corrientes sin confirmación diferenciada, y una cambia el reloj de Windows | `R:151-152`, `R:595-596`, F-1400, F-1480, `I` |
| G79 | Administración | Consulta de auditoría | Fecha, hora, acción, usuario y PC; búsqueda por acción (F1), fecha (F2) o usuario (F3). Mezcla acciones de negocio con errores técnicos del sistema | `R:577-589`, F-1000, F-1490 |
| G80 | Administración | Consulta de anulaciones | Submenú Auditoría, con `Tipo`, `Fecha`, `Nro`, `Usuario` y `Descripcion` | f655, F-1450 |
| G81 | Administración | Controles de datos | "Verificar costos en cero o mal cargados", "Ventas con costo mayor a precio de venta" y "Artículos con alícuota de IVA en 0" | `R:590-594`, f655 |
| G82 | Administración | Corrección masiva de IVA | "Poner IVA 21 % en artículos con IVA en 0" | `R:592-593`, f655 |
| G83 | Administración | Facturas que no descontaron stock | Detecta inconsistencias entre la venta y el stock | `R:593-594`, f655 |
| G84 | Cheques y bancos | Vaciar tabla | Acción destructiva global | `R:440-441` |
| G85 | Artículos | **Etiquetas con código de barras** | EAN-13, Code39 y Code128. Hojas A4 de 4 y 5 columnas (código + descripción; una variante suma marca y talle), tres tamaños y etiqueta de envío. **No hay acceso desde la interfaz de la demo** (el ejecutable trae `TF_ETIQUETAS` y `TF_IMPRESION_BARRAS`); se relevó por las plantillas. Cada una lee una tabla en memoria con los artículos elegidos | F-1720, `C §1` [manual] |
| G86 | Compras | Cálculo de costo real en la compra | `/` abre `Lista Proveedor` + 3 descuentos encadenados = `Costo`, con el `PRECIO de VENTA` al lado. `Actualiza Datos en el artículo (instantáneo)` viene tildado: la compra reescribe el costo del maestro, pero **el precio de venta no se recalcula** | F-1600, `C §1` |
| G87 | Compras | Desglose de compras | Opcional (solapa Compras de la configuración). Carga de comprobantes **sin artículos**: CAE, vencimiento, neto gravado y exento, IVA 27/21/10,5, percepciones de IVA, IIBB y municipales, impuestos internos y otros, y período. Para gastos y servicios y para el libro IVA Compras. **No calcula ni valida el IVA contra el neto**, el comprobante impreso sale casi en blanco y el libro pone el número del comprobante bajo la columna CUIT | F-1720, `I` |
| G88 | Clientes | Consumidor final y límite de venta sin datos | Cliente `CONS. FINAL` predefinido (la factura arranca en `CF`) y parámetro `Límite de Venta a Cons.Final sin datos` ($5.000,00 en la demo) | F-1450, F-1480, `C §2` |
| G89 | Configuración | Interruptores globales de stock y caja | `Factura`, `Remito` y `Presupuesto` descuentan del stock según su interruptor; la NC tiene los suyos para stock, caja y cuenta corriente. Apagar el de factura permite vender sin mover stock y el **único aviso** es un texto en el título de la ventana. El comprobante no registra con qué configuración se emitió | F-1710, F-1480, `I` |
| G90 | Configuración | Limitar la venta sin stock | Interruptores `Limitar la venta sin stock` y `Limitar presupuesto sin stock` (este tildado) | F-1480 |
| G91 | Ventas | Facturar artículos en negativo | Interruptor `Facturar artículos negativos`: permite devoluciones como renglón negativo dentro de la factura | F-1480, `C §3` |
| G92 | Ventas | Factura rápida: foco en el código | Interruptor `Foco Primero en el Código (Factura Rápida)` y `Búsqueda de artículos por` (descripción por defecto). La búsqueda de artículos de la venta (F5) muestra stock y fecha de modificación | F-1480, F-1600 |
| G93 | Configuración | Medios de pago digital con % y recargo | Tabla auxiliar configurable: billetera virtual, Mercado Pago, transferencia, Visa 1 cuota, Visa 6 cuotas y Visa débito, cada una con `Porcentaje` y `Recargo` | F-1480 |
| G94 | Ventas y compras | Alta rápida sin salir del documento | Nuevo cliente desde la factura (F9), artículo nuevo desde la compra (F9). Rubro, marca, unidad, zona, provincia y banco también se dan de alta desde el formulario que los usa | F-1430, F-1440, `M:ventas`, `M:compras` |
| G95 | Administración | Numeración de comprobantes | Once tipos con su número actual editable (AR, CA, CB, CO, FA, FB, ND, OP, PR, RE, RX) | F-1480 |
| G96 | Administración | Configurar descuentos | Es la Actualización de Precios aplicada a **descuentos**: filtra por marca, rubro, proveedor y rango de códigos, y fija tres descuentos encadenados más el redondeo | F-1480 |
| G97 | Administración | Stock mínimo en base a ventas | Opción de Configuración para calcular el stock mínimo con las ventas | F-1400, `C §1` |
| G98 | Administración | Backup | Al salir ofrece una copia ZIP por día de la semana y por mes. Directorio configurable | F-1480, `C §9` |
| G99 | Ventas | Impresión, formularios preimpresos y fiscal | Diseñador de facturas preimpresas (seis plantillas), copias e impresora por tipo de comprobante, soporte para controladores fiscales Epson y Hasar | F-1480, `C §3` |
| G100 | Ventas | Efectivo recibido y vuelto | El catálogo marca el pago mixto con vuelto. **La bitácora no muestra el cálculo del vuelto**: no está verificado en pantalla | `C §3`, `C §10` |
| G101 | Administración | Límites por usuario | `Límite de Descuento` y `Límite de Cta Corriente` por usuario, además de los 28 permisos (ver G77) | F-1410 |

---

## 3. Cruce con la spec de ferreteria

Las clases son las del pedido: A = ya cubierto, B = refina un CU, C = candidato nuevo al MVP, D = post-MVP, E = descartar. "Impl." indica si ya está implementado (esqueleto, CU-03) o pendiente con su fase. CU-06 está en desarrollo en la rama `feat/cu-06-buscar-articulo`.

| # | Clase | Dónde cae / qué hacer | Por qué |
| --- | --- | --- | --- |
| G01 | **B** | CU-06/07, fase 4: atajos fijos. **La propuesta concreta está en la sección 6** | El criterio de la fase 4 es "solo con teclado y lector". GDS confirma que el mostrador vive de las teclas de función. La propuesta anterior (F6 presupuesto, Esc cancelar) cambia: ver los conflictos de la sección 6 |
| G02 | **D** | Cotización con fecha y fuente, junto con G18 | Solo sirve si hay costos en USD. Ver la pregunta 3 |
| G03 | **E** | — | Principio "sin capacitación": si una pantalla necesita manual, se rediseña |
| G04 | **E** | — | No se sabe qué hace y no hay evidencia de uso |
| G05 | **E** | — | El MVP imprime PDF desde el navegador. La impresora nunca debe bloquear (y un aviso oculto detrás de la ventana, menos) |
| G06 | **E** | — | Problema propio de una base de archivos local; MySQL es transaccional |
| G07 | **E** | La lección va al backlog como criterio de diseño: GDS-39 | Licenciamiento de escritorio. Lo que sí hay que copiar al revés: ante una suscripción vencida, solo lectura y exportación de datos, no bloqueo total |
| G08 | **A** | CU-03, **impl.** | Ferreteria es más completo: separa código interno y código de barras |
| G09 | **A** | CU-03, **impl.** | Rubro, unidad, un proveedor y ubicación ya existen. Que GDS tenga **un solo proveedor por artículo** confirma el modelo actual |
| G10 | **D** | Agregar `marca` al artículo más adelante | Útil para buscar y para reportes, pero hoy la marca puede ir en la descripción |
| G11 | **A** | Rubros **impl.** (esqueleto), unidad como enum | Lo que no conviene copiar es el editor global abierto desde un botón del formulario (`R:287-288`) |
| G12 | **A** | CU-03 **impl.** (el request no trae stock) y CU-09 fase 6 | El mismo criterio que GDS: el stock solo cambia por movimientos |
| G13 | **A** | CU-03 **impl.** | Margen por artículo, 30 % por defecto (igual que `V2__articulos_y_precios.sql`) e IVA por artículo |
| G14 | **B** | **CU-04 y CU-03, fase 3. Ver la sección 4** | Es la evidencia más fuerte del relevamiento. En GDS el costo **siempre** es precio de lista menos hasta 3 bonificaciones, y la 7.53 lo confirma en tres lugares (artículo, compra y `Configurar Descuentos`) |
| G15 | **C** | CU-07, fase 4: recargo % por medio de pago (crédito) configurable por ferretería, sin segunda lista. Ver también G93 | El precio de contado contra el de tarjeta es universal en Argentina. Como recargo no toca el modelo del artículo |
| G16 | **D** | Listas múltiples | La spec ya lo deja afuera ("mayorista y minorista"). GDS las tiene manuales, lo que suma carga de mantenimiento |
| G17 | **D** | Precio fijo manual por artículo (que la importación no pise) | Caso real (precio sugerido de marca), pero el MVP resuelve con el margen por artículo |
| G18 | **D** | `moneda` en el proveedor más cotización | Es aditivo si se guarda el precio de lista aparte (GDS-03). Ver la pregunta 3 |
| G19 | **D** | Fecha de última compra, derivada de los ingresos | Depende del ingreso con remito (GDS-16) |
| G20 | **D** | — | Promociones: nadie las pidió para el MVP |
| G21 | **E** | — | El mostrador no necesita foto y suma almacenamiento por tenant |
| G22 | **B** | CU-03 y CU-06: mostrar "costo actualizado el …" (sale del historial, sin tocar el esquema) | El dueño quiere saber qué precios están viejos, y GDS lo pone en el listado y en la búsqueda de la venta. **Hecho (2026-10-23, Luca):** «Costo al» en Artículos y en el mostrador; es la fecha más nueva entre el último cambio de costo o moneda, la última lista que lo confirmó y el alta |
| G23 | **A** | CU-03 **impl.** (exige costo) | La spec es más estricta a propósito: el propio relevamiento advierte que GDS deja vender artículos incompletos (`R:198-206`) |
| G24 | **A** | CU-03 **impl.** (listado, búsqueda y baja lógica) | El "Borrar" físico de GDS se reemplaza por la baja lógica |
| G25 | **B** | Fase 6: exportar artículos a CSV y carga inicial reutilizando CU-04 | La fase 6 dice "carga inicial de datos del piloto" y la fase 0 pide "una exportación real de artículos". Que GDS tenga importación y exportación rápidas confirma que las ferreterías migran por planilla. **Hecho en CU-03 (2026-10-23, Luca):** «Exportar» en Artículos baja a CSV lo que muestra la pantalla (columnas propuestas, porque el relevamiento no ejecutó la de GDS) |
| G26 | **A** | CU-05, fase 3 (pendiente). La suma de un monto fijo en $ queda como GDS-40 (D) | El mismo concepto. GDS filtra por marca, rubro, proveedor y rango de códigos; CU-05 filtra por proveedor, rubro o búsqueda, que alcanza. El redondeo a múltiplo (0,50 en GDS) coincide con el de ferreteria |
| G27 | **A** | CU-09, fase 6 | Un botón "poner stock en cero" global es justo lo que no hay que copiar |
| G28 | **A** | CU-09, fase 6 (ajuste con motivo: rotura, conteo) | GDS agrupa las salidas por motivo, que sirve para el reporte |
| G29 | **B** | CU-09: pantalla "movimientos del artículo" | La spec registra los movimientos pero no dice dónde se consultan. **Hecho en CU-09 (2026-10-23, Luca):** pantalla de movimientos por artículo (punto 3 de CU-09), solo el Dueño, con fecha, tipo, cantidad con signo, saldo, comprobante, usuario y motivo, Desde/Hasta y páginas. «Clientes que compraron el artículo» (Ctrl+A) **hecho en CU-03 (2026-10-23, Luca):** botón «Clientes» en Artículos, una fila por cliente con compras, cantidad y última compra |
| G30 | **B** | Cliente (CU-07, fase 4/5): sumar email y WhatsApp/teléfono y validar el dígito del CUIT. Descartar nacimiento, rubro y web | CU-07 dice "envía el comprobante en PDF" y la pregunta abierta menciona WhatsApp. **Hecho en CU-11 (2026-10-18, Luca):** razón social, documento (DNI, CUIT o CUIL con dígito verificador), condición de IVA, domicilio, teléfono, WhatsApp y correo; sin nacimiento, rubro ni web |
| G31 | **D** | Junto con la cuenta corriente y las listas | Vendedor, zona y transporte son de distribuidora; límite, descuento y lista dependen de G16 y G59. El informe final sugiere copiar la política comercial del cliente a la venta cuando llegue. **Hecho en CU-11 (2026-10-18, Luca):** el **límite de cuenta corriente** del cliente (junto con su saldo; vacío = sin cuenta corriente habilitada, 0 = sin crédito, solo lo pone el Dueño). Vendedor, zona, transporte, descuento y lista por defecto siguen diferidos |
| G32 | **D** | — | Barato, pero nadie lo pidió |
| G33 | **A** | Cliente en la spec | **Hecho en CU-11:** solo la razón social es obligatoria, el documento es opcional pero completo y único por ferretería |
| G34 | **B** | CU-09, fase 6: "ingreso de mercadería" con varios renglones y número de remito del proveedor | La spec lo trata como un ajuste suelto. Cargar un remito de 30 renglones de a uno no es práctico |
| G35 | **B** | CU-07, fase 4: escanear dos veces el mismo artículo **suma cantidad** en el renglón. Ver GDS-33 | Reclasificada (antes E). En la venta de GDS es lo que viene por defecto, y es lo que hace rápido un mostrador con lector: una pistola que agrega un renglón nuevo por cada pasada es un problema |
| G36 | **D** | Con compras | Fuera de alcance ("Compras … y cuenta corriente con proveedores") |
| G37 | **D** | Con compras | Ídem |
| G38 | **D** | Con compras | Ídem |
| G39 | **D** | — | Excluida explícitamente en la spec |
| G40 | **D** | — | Ídem. Mientras tanto, el pago en efectivo a un proveedor lo cubre G65 como "gasto o retiro" |
| G41 | **A** | Proveedor CU-03 **impl.** (sin saldo) | — |
| G42 | **D** | — | Cheques y echeqs son comunes, pero arrastran cuentas corrientes. El informe final suma que GDS no los valida |
| G43 | **D** | — | Ídem |
| G44 | **D** | — | Ídem |
| G45 | **D** | — | Ídem |
| G46 | **D** | — | Ídem |
| G47 | **A** | CU-07, fase 4/5 | La regla A/B/C ya está en la spec |
| G48 | **D** | Junto con G16 | — |
| G49 | **B** | CU-07: **no se puede vender a precio 0** (o solo el Dueño, con confirmación) | El relevamiento lo marca como riesgo (`R:341-343`, `R:628-629`) |
| G50 | **A** | CU-07 y reglas de precios. **Sumar un test**: el total del comprobante es la suma de los renglones y los reportes suman totales de comprobante (GDS-37) | El precio final de ferreteria ya incluye IVA, y las alícuotas van por ítem. GDS pierde dos centavos por redondear por renglón, y dos de sus reportes ya no coinciden entre sí |
| G51 | **C** | CU-07, fase 4: descuento % manual por renglón o total, con tope configurable para el Vendedor | El "le hago un 10 %" del mostrador es diario. No es "descuento por cliente" (eso está excluido). GDS pone el tope por usuario (G101) |
| G52 | **E** | — | Ferreteria ya redondea cada precio al múltiplo. Un ajuste en el total complica el cuadre de importes ante ARCA |
| G53 | **B** | CU-07 paso 4: "uno **o más** medios de pago", con el resto calculado solo | El modelo ya tiene `Pago` 1—N, pero el CU habla de un solo medio. Pagar parte en efectivo y parte con débito es común. De GDS conviene el resto automático y no el cambio de solapa que confirma sin querer |
| G54 | **D** | Junto con la cuenta corriente, con aviso y autorización de un rol habilitado | Refinamiento de G59. El informe final marca que el bloqueo sin salida es un defecto. **Hecho en CU-07 (2026-10-18, Luca):** la venta a cuenta corriente que pasa el límite se rechaza con 422 `LIMITE_CUENTA_CORRIENTE` y **avisa**; la **autoriza solo el Dueño** logueado (reenvía con `autorizaExceso`, y el movimiento guarda que excedió y quién lo autorizó). El Vendedor no tiene salida por su cuenta («Pedile al Dueño que la autorice») y si manda la autorización es 403. Sin PIN de supervisor. Con límite vacío no hay cuenta corriente y ni el Dueño la autoriza |
| G55 | **A** | CU-07 alternativo ("remito interno") | Bloqueado en la demo, así que no suma evidencia nueva. **Hecho en CU-07 (2026-10-18, Luca):** el remito es el documento de entrega de GDS con efecto fijo (GDS-35): descuenta stock al emitirlo, sin cobro ni caja, y se factura con «Pasar a venta» sin doble descuento (la venta concilia la diferencia). Anular devuelve el stock y es del Dueño. Lo del catálogo (envío con o sin descuento de stock) queda fijo en «con descuento»; la **venta sin factura** que cobra es otro documento. **Hecho en CU-07 (2026-10-18, Luca):** como marca «No facturable» al cobrar, no como documento aparte (estado `NO_FACTURABLE`, misma numeración) |
| G56 | **B** | CU-07: separar **presupuesto (cotización con estados que no descuenta stock y se convierte en venta, el "Traer")** de la **venta sin factura**. Ver GDS-13 | La spec usa "presupuesto" para la venta en negro. En GDS son documentos distintos y el efecto sobre el stock lo decide un interruptor global (G89). En ferreteria el efecto es fijo: el presupuesto no descuenta. Ver la pregunta 6. **Hecho en CU-07:** el presupuesto es la cotización con estados; la venta sin factura es la marca «No facturable» al cobrar (estado `NO_FACTURABLE`, misma numeración) |
| G57 | **D** | Seña, N° de orden de compra y observaciones | "Traer" queda dentro de G56 |
| G58 | **A** | CU-08, fase 5 | Un interruptor para mover caja y otro para el stock en la NC es configuración de más (G89) |
| G59 | **C** | Cuenta corriente de clientes y cobranzas (saldo, cobro en efectivo o transferencia), **con imputación a comprobantes**. Ver GDS-20 | GDS la integra en la factura misma, como pago mixto con cuenta corriente. La spec ya prevé meterla "si la respuesta es sí, mucho". La falta de imputación rompe las comisiones (G62). Ver la pregunta 7. **Hecho en CU-07 (2026-10-18, Luca), solo la mitad de la venta (tramo 2):** la forma «Cuenta corriente» como parte de la venta, única o en el pago mixto, con un libro de debe y haber por cliente y saldo; la caja la muestra como renglón informativo que no entra en el efectivo esperado. **Hecho en CU-12 (2026-10-19, Luca), tramo 3a, la mitad de la cobranza:** la **imputación de cada cobro a comprobantes**, que es lo que GDS no hace. Solo el Dueño cobra, en efectivo o transferencia (un medio por recibo, sin recargo), desde la pantalla de cuenta del cliente: el sistema sugiere imputar del comprobante más viejo (FIFO) y el Dueño lo edita; lo que no se imputa queda **a cuenta**, y se puede cobrar un **adelanto** a un cliente sin deuda (el `Adelanto` de GDS, con saldo a favor). Cada cobro emite un **recibo interno no fiscal** con numerador propio (vista imprimible; la leyenda se confirma con el contador), es idempotente por clave y exige la caja abierta del día: el efectivo suma al esperado y la transferencia es informativa (CU-10). El pendiente de cada comprobante y lo que el cliente tiene a cuenta se derivan del libro y de las imputaciones. **Hecho en CU-12 (2026-10-21, Luca), tramo 3b-1, `Anular Pago` y el extracto:** solo el Dueño anula un recibo entero, con **motivo obligatorio** (5 a 200 caracteres), exigiendo la caja abierta del día (también si el cobro fue por transferencia); la anulación escribe un contra-asiento `ANULACION` en el libro, marca el recibo `ANULADO` con quién, cuándo y por qué, y **los comprobantes imputados vuelven a quedar pendientes** (algo que GDS no puede hacer porque no imputa). Se reimprime el recibo con la marca «ANULADO»; anular dos veces da 409 con quién y cuándo; la anulación de un cobro en efectivo resta del efectivo esperado de la caja abierta (la transferencia es informativa) y una caja cerrada no cambia. La pantalla de cuenta suma «Movimientos»: el **extracto** con el saldo real de cada línea, filtros de fecha y tipo, paginación e impresión (CU-12). **Hecho en CU-12 (2026-10-23, Luca: «como lo tiene gds»), tramo 3b-2, `Recargo` y `Descuento`:** solo el Dueño, con motivo obligatorio, confirmación con el saldo resultante e idempotencia por clave, sin caja; el Recargo queda como partida pendiente que se cobra y el Descuento se imputa FIFO editable (lo que sobra queda a cuenta). A diferencia de GDS, que no imputa, la **deuda nueva consume sola el saldo a favor** (venta a cuenta y Recargo), y un cliente con saldo a favor que se va se lleva a cero con un Recargo y se desactiva. Los ajustes no se anulan: se compensan con el contrario. **Pendiente:** la nota de crédito (fase de facturación) y los demás medios (tarjeta, cheque, retenciones). Un cobro mal cargado se corrige anulándolo y cobrando de nuevo |
| G60 | **B** | CU-08, fase 5: consulta y **reimpresión** por número, fecha o cliente, **sin "Borrar"** | CU-08 dice "busca la factura" pero la pantalla no está definida. Un comprobante con CAE no se borra. **Hecho en CU-16 (2026-10-23, Luca):** pantalla «Ventas» con la consulta por número, fecha o cliente, el detalle y la reimpresión como comprobante interno; sin anular ni borrar; solo el Dueño (GDS lo da por permiso de usuario) |
| G61 | **E** | — | El vendedor ya es el `Usuario`. Sueldo y domicilio son de RR.HH. |
| G62 | **D** | Después de GDS-20 (imputación) | Las comisiones existen en ferreterías, pero no el primer día. Hay que liquidarlas sobre comprobantes **cobrados**. **Habilitado (2026-10-19, Luca) como dato derivado:** con la imputación de G59 (CU-12, tramo 3a) cada comprobante a cuenta corriente tiene lo **cobrado** (Σ imputaciones) y lo **pendiente** (lo debido − lo cobrado), y la pantalla de la cuenta muestra los pendientes. Las **comisiones siguen post-MVP**: no hay liquidación, porcentajes ni pago de comisiones |
| G63 | **B** | Reporte del MVP: ventas por usuario | Es barato: el comprobante ya cuelga de la caja, y la caja del usuario. **Hecho en CU-15 (2026-10-22, Luca)** |
| G64 | **A** | CU-10, fase 4. Mantener la sesión con arqueo y **validar egresos contra el saldo** (GDS-36) | La caja de GDS no es una sesión y deja el efectivo en negativo sin avisar. Su fortaleza, el corte por concepto y por medio a la vez, vale la pena en CU-10 |
| G65 | **B** | CU-10: movimientos de caja manuales (**retiro, gasto, ingreso**) con motivo | Sin esto el arqueo nunca cierra: el dueño saca efectivo o le paga al flete con la caja. GDS los probó y confirma que comparten un solo formulario |
| G66 | **B** | Reporte del MVP: agregar **ganancia**, **copiando el costo en `ItemComprobante`** | Hay que decidirlo antes de crear la tabla en la fase 4. Después el costo histórico se pierde. **Hecho en CU-15 (2026-10-22, Luca):** el renglón copia el costo desde la V26; las ventas anteriores quedan sin costo y se avisan. Devoluciones y «ganancia con y sin NC» esperan la nota de crédito |
| G67 | **A** | El MVP trae "ventas del día y de un rango de fechas" | **Hecho en CU-15 (2026-10-22, Luca):** resumen, por usuario, por medio de pago, por día o mes, por artículo, por rubro y con descuento, cada uno con «Desde / Hasta» como GDS |
| G68 | **A** | CU-10 muestra el total por medio de pago | "Con descuento" iría con G51, ya en D |
| G69 | **D** | Por usuario, ver G63 | Reportes avanzados, fuera de alcance |
| G70 | **D** | — | Libro IVA, fuera de alcance. El contador baja los comprobantes de "Mis Comprobantes" de ARCA. El informe final lo lista como "implementar" en Mostrador; acá queda post-MVP |
| G71 | **D** | — | CU-08 ya permite encontrar las NC |
| G72 | **D** | El stock crítico ya está en CU-09 (A). Stock valorizado y rankings, post-MVP. El listado con foto es E | Reportes avanzados. **Hecho en CU-09 (2026-10-23, Luca), el stock crítico:** pantalla «Stock bajo mínimo» como el «Listado de Artículos bajo Stock Mínimo» de GDS (columnas de GDS más rubro y proveedor; orden por descripción, código, rubro o proveedor, sin marca porque el artículo no tiene) e imprimible. Stock valorizado y los demás reportes de artículos siguen post-MVP |
| G73 | **B** | Junto con G65 | Viene gratis si existen los movimientos de caja |
| G74 | **D** | — | El ranking por artículo sirve para comprar, pero es post-MVP |
| G75 | **E** | — | Sin evidencia de uso. Es decoración |
| G76 | **D** | — | Cada uno depende de un módulo post-MVP |
| G77 | **A** | CU-02, fase 6 (roles fijos). Deshabilitar, no ocultar, lo que el rol no puede | Tres roles alcanzan. Las claves en texto plano y el ingreso sin clave por defecto son lo contrario de lo que se quiere. **Hecho en CU-07 y CU-11 (2026-10-18, Luca):** el límite de cuenta corriente **no se copia por usuario**: es del cliente, y lo único que depende del rol es quién lo pone y lo ve (solo el Dueño; el Vendedor ve «alcanza» o «no alcanza» sin cifras) y quién autoriza un exceso (solo el Dueño). Lo que el rol no puede, se deshabilita con la razón a la vista |
| G78 | **E** | Configuración = CU-01 (A) | Mantenimiento y reparación son de escritorio y no aplican a un SaaS. Las correcciones masivas sin confirmación son el ejemplo de lo que hay que evitar |
| G79 | **D** | Auditoría general | El MVP ya audita lo crítico: historial de precios, movimientos de stock y caja. Mantenerla separada de las herramientas de mantenimiento |
| G80 | **A** | CU-08 | Las NC se listan |
| G81 | **B** | CU-03 y CU-04: filtros "sin costo", "costo 0" y "margen negativo", y controles en la vista previa | Es el control preventivo que GDS hace después. Conviene hacerlo antes (ver la sección 4) |
| G82 | **E** | — | Ferreteria exige la alícuota (21 % por defecto). Un cambio masivo de alícuota podría ir en CU-05 si alguien lo pide |
| G83 | **E** | — | Por diseño, la venta y el movimiento de stock van en la misma transacción |
| G84 | **E** | — | Una acción destructiva global, inaceptable |
| G85 | **C** | Etiquetas imprimibles desde el catálogo (EAN-13 y Code128, hoja A4 en columnas), fase 4 si la fase 0 dice que etiquetan ellos. Ver GDS-31 y la pregunta 9 | Si la ferretería no tiene códigos en los artículos, el lector no sirve y la meta de los 15 segundos no se cumple. Es una vista imprimible desde el navegador, sin hardware especial. Code39 queda afuera: EAN-13 y Code128 cubren los lectores comunes. **Hecho en CU-03 (2026-10-23, Luca):** pantalla «Etiquetas» del Dueño, hoja A4 de 4 o 5 columnas con barras, código y descripción |
| G86 | **B** | CU-09, fase 6: si el ingreso de mercadería actualiza el costo, **avisar que el precio de venta no cambia** (o recalcularlo con el margen, a decidir). Ver GDS-16 | GDS reescribe el costo y deja el precio como estaba, sin aviso. Con las bonificaciones guardadas por proveedor (GDS-01), el cálculo es el mismo que en CU-04 |
| G87 | **D** | Con compras | Compras y libro de IVA están fuera del MVP. Si algún día entra, copiar la carga sin artículos pero con IVA sugerido desde el neto y la suma validada (`BL`) |
| G88 | **B** | CU-07, fase 4/5: venta a Consumidor Final por encima de un umbral exige DNI o CUIT. Ver GDS-32 y la pregunta 10 | CU-07 paso 2 ya pone Consumidor Final por defecto. El tope para identificar al comprador lo fija ARCA y cambia; hay que verificarlo en la prueba de la fase 1 antes de fijar un valor |
| G89 | **E** | — (registrar la decisión: GDS-35) | Los efectos de un documento sobre stock y caja son fijos. Si el comprobante cambia de efecto según un interruptor global, el inventario se desfasa sin rastro |
| G90 | **B** | CU-07, fase 4: decidir qué pasa al vender sin stock (bloquear, avisar o permitir). Ver GDS-34 y la pregunta 11 | La spec no lo dice. En una ferretería el stock del sistema suele estar atrasado, así que un bloqueo duro frena el mostrador |
| G91 | **E** | Devolución = nota de crédito (CU-08) y venta nueva | Un renglón negativo mezcla venta y devolución en un solo comprobante. A confirmar en la prueba con ARCA de la fase 1 cómo se informan importes negativos |
| G92 | **A** | CU-06, **en desarrollo**: el campo de búsqueda tiene el foco siempre y busca por código de barras, interno, código de proveedor o descripción | GDS necesita un interruptor para eso y F6 aparte para el código de proveedor. Ferreteria lo resuelve con un solo campo |
| G93 | **C** | CU-07, fase 4: tabla de medios de pago con % de recargo por ferretería. Es el soporte de GDS-11 | GDS ya lo resuelve así (Visa 6 cuotas con su recargo). Confirma que no hace falta una segunda lista de precios (G15, G16) |
| G94 | **B** | CU-07: crear cliente sin salir de la venta. El alta rápida de artículos queda para CU-03 | Si el cliente pide factura A y no existe, cortar la venta para ir a otra pantalla cuesta tiempo en el mostrador |
| G95 | **E** | — | ARCA asigna la numeración de las facturas; los internos (presupuesto) se numeran solos |
| G96 | **D** | Descuentos o bonificaciones por rubro o marca dentro de un proveedor | Refina GDS-01. Con bonificaciones por proveedor alcanza para el MVP |
| G97 | **D** | CU-09 o reportes, post-MVP (GDS-41) | Útil para el dueño, pero el stock mínimo manual alcanza al inicio |
| G98 | **E** | — | En un SaaS el backup es del servicio (queda en "preparación operativa" del backlog de Mostrador) |
| G99 | **E** | — | El MVP imprime PDF y factura electrónica. No hay formularios preimpresos ni controladores fiscales |
| G100 | **B** | CU-07 paso 4: en efectivo, ingresar el importe recibido y mostrar el vuelto. Ver GDS-07 | Evita errores al dar el cambio y entra en la meta de 15 segundos si es opcional (Enter acepta "justo"). Verificar en la visita a la ferretería, porque la bitácora no lo vio |
| G101 | **D** | Límites por usuario | El MVP tiene roles fijos. El tope de descuento del Vendedor ya va con GDS-12. El límite de cuenta corriente quedó **por cliente** y no por usuario (CU-11, 2026-10-18, Luca) |

**Totales: 101 funciones.** A = 22 · B = 22 · C = 5 · D = 34 · E = 18.

- **A (22):** G08, G09, G11, G12, G13, G23, G24, G26, G27, G28, G33, G41, G47, G50, G55, G58, G64, G67, G68, G77, G80, G92.
- **B (22):** G01, G14, G22, G25, G29, G30, G34, G35, G49, G53, G56, G60, G63, G65, G66, G73, G81, G86, G88, G90, G94, G100.
- **C (5):** G15, G51, G59, G85, G93.
- **D (34):** G02, G10, G16 a G20, G31, G32, G36 a G40, G42 a G46, G48, G54, G57, G62, G69 a G72, G74, G76, G79, G87, G96, G97, G101.
- **E (18):** G03 a G07, G21, G52, G61, G75, G78, G82, G83, G84, G89, G91, G95, G98, G99.

**Cambios contra la primera versión.** G35 pasó de E a B. Se agregaron 17 funciones (G85 a G101: 1 A, 5 B, 2 C, 4 D, 5 E). Se actualizaron con datos de la 7.53 unas 50 filas de G01 a G84 (todas las marcadas con "7.53" o con fuentes `F-NNNN`, `C`, `I` o `M:*`), sin cambiar su clase salvo G35.

Algunas funciones tienen partes en otra clase y cuentan por su clase principal: G72 tiene el stock crítico en A (CU-09) y el listado con foto en E; G78 tiene la configuración en A (CU-01).

**Ya implementado** (esqueleto y CU-03): G08, G09, G11, G12, G13, G23, G24, G41. **En desarrollo** (CU-06): G92. Ninguna función de GDS cae sobre lo que CU-04 ya resolvió, salvo las de la sección 4.

---

## 4. Impacto inmediato en CU-04 (importar lista de proveedor) — URGENTE

**Contexto:** GDS sigue sin mostrar un importador de listas ejecutado. En la 7.53 se vieron la *Actualización de Precios* (F-1420) y la *Importación Rápida* con sus asistentes, pero no se ejecutó ninguna. Los hallazgos salen del modelo de costo del artículo (f323, f121), de la compra (F-1600) y de `Configurar Descuentos` (F-1480).

### 4.1 Bonificaciones en cascada — **CONTRADICE PARCIALMENTE la decisión "bonificaciones fuera de alcance"**

- **Evidencia:** en GDS el costo de un artículo **no se puede cargar directo**. El sistema lo bloquea con "El costo se calcula desde la solapa COSTO CALCULADO" (`R:228-229`, f121). Esa solapa tiene exactamente: *Precio de lista del Proveedor*, *Descuentos Aplicados `[ ]% + [ ]% + [ ]%`* y *El costo calculado es* (f323). La compra también usa ese costo: "precio de lista del proveedor menos hasta tres descuentos encadenados" (`R:308-310`).
- **Evidencia nueva de la 7.53:**
  - En la compra, `/` abre `Cálculo de Costo real`: lista $300 con 10 % da un costo de $270 (F-1600).
  - `Administración → Configuración → Configurar Descuentos` es la actualización masiva aplicada a descuentos: filtra por marca, rubro, **proveedor** y rango de códigos y fija **tres descuentos encadenados** más el redondeo (F-1480). Es decir, GDS asigna las bonificaciones en bloque por proveedor, que es justo lo que se propone abajo.
- **Lectura:** el modelo mental de GDS (y de su mercado) es que el proveedor publica un **precio de lista** y la ferretería tiene **bonificaciones** (por ejemplo 40 + 10 + 5). Si CU-04 toma la columna "precio" del Excel como costo, en todos los proveedores que trabajan así **el costo queda inflado y el precio de venta también**. El error es silencioso y masivo: es justo lo que CU-04 tiene que evitar.
- **Límite de la evidencia:** en las pruebas las casillas de descuento estaban vacías o con un solo valor, así que el relevamiento no muestra con qué frecuencia se usan los tres. En la ficha del artículo son **por artículo**.
- **Recomendación:** no hace falta revertir toda la decisión. Propongo una versión mínima: **hasta 3 bonificaciones % por proveedor**, guardadas junto al flag "lista incluye IVA" y al mapeo de columnas, que se aplican en la importación: `costo = precio_lista × (1 − b1) × (1 − b2) × (1 − b3)`. Esfuerzo S: tres columnas en `proveedor` y una multiplicación en el dominio, con sus tests. Las bonificaciones por artículo o por rubro dentro de un proveedor, como en GDS, quedan post-MVP. **Es la pregunta 1.**

### 4.2 IVA: el flag "lista incluye IVA" no puede dividir siempre por 1,21 — **AJUSTA la decisión**

- **Evidencia:** GDS maneja **IVA 21 % y 10,5 %** por artículo (f121, combo IVA; f413, renglones "IVA 21 %" e "IVA 10,5 %"). `AlicuotaIva` de ferreteria ya tiene alícuotas por artículo. El libro IVA Compras de GDS trae también el 27 % (F-1720).
- **Impacto:** si la lista viene con IVA, sacarlo tiene que usar **la alícuota del artículo existente**. Para un artículo nuevo, la alícuota por defecto (21 %), y que se pueda corregir en la vista previa o en el alta en bloque. Dividir siempre por 1,21 deja mal el costo de todo lo que va al 10,5 %.
- **Orden de operaciones:** quitar el IVA y aplicar las bonificaciones son multiplicaciones, así que el orden no cambia el resultado. Conviene calcular con precisión completa y **redondear una sola vez** al final, a 2 decimales, igual que hace `PrecioCalculator`. GDS redondea por renglón y pierde centavos en el comprobante (G50): es la prueba de por qué.

### 4.3 Guardar el precio de lista del proveedor aparte del costo — **recomendado, no contradice nada**

GDS conserva las dos cifras, precio de lista y costo calculado (f323), y en la compra muestra al lado el precio de venta vigente (G86). Guardar `precio_lista_proveedor` en el artículo, y si se quiere también en el historial, permite tres cosas:

1. Si cambia una bonificación, recalcular sin reimportar.
2. Comparar en la vista previa "lista anterior contra lista nueva" del mismo proveedor, sin el ruido de las bonificaciones.
3. Sumar después una moneda USD (G18) sin migración de datos.

Es una migración aditiva (`V3`, columna nullable). Esfuerzo S.

### 4.4 Cruce por código del proveedor — **CONFIRMA las decisiones**

GDS tiene **un solo** "Cód. Prov." y **un solo** proveedor por artículo (f121, `R:177-178`). Coincide con `uk_articulo_codigo_proveedor (ferreteria_id, proveedor_id, codigo_proveedor)`. No hay evidencia de que haga falta más de un proveedor por artículo. La 7.53 no cambia esto.

### 4.5 Controles de calidad en la vista previa — **refina "filas con error se saltean y listan"**

GDS tiene, aparte, "Verificar costos en cero o mal cargados" y "Ventas con costo mayor a precio de venta" (f655, `R:590-594`). Es decir: los costos mal importados son un problema real. Conviene hacerlo **antes** de aplicar, dentro de la vista previa:

- Costo vacío, 0 o no numérico → **error**: la fila se saltea y se lista. Eso ya entra en la decisión tomada.
- Variación mayor a un umbral (por ejemplo ±50 %) → **advertencia**, no error. Atrapa el mapeo de una columna equivocada, unidades distintas (caja contra unidad) o una lista con o sin IVA mal marcada. Esfuerzo S.

### 4.6 Margen y redondeo — **sin contradicciones, con una corrección**

- La ganancia de GDS es por artículo, con 30 por defecto (f121). Coincide con el margen heredado y con el 30 % por defecto de ferreteria. La importación cambia el costo, nunca el margen.
- En GDS algunos precios son manuales (lista 3 y 4, "Lista 1 calculada" desmarcada; G17). En ferreteria todo precio es calculado, así que no existe el caso "costo nuevo > precio manual". Si más adelante aparecen precios fijos (D), la vista previa tiene que marcar el margen negativo.
- **Corrección a la primera versión:** GDS **sí** redondea a un múltiplo. Tanto `Actualización de Precios` como `Configurar Descuentos` tienen un redondeo configurable (0,50 por defecto) (F-1420, F-1480). Coincide con la decisión de ferreteria de redondear al múltiplo. Además GDS redondea el total del comprobante con una casilla aparte (G52), y eso se sigue descartando.
- **Hecho (2026-10-23, Luca: «vamos con gds»):** el Dueño edita el margen por defecto y el múltiplo de redondeo en «Margen y redondeo» (CU-03). El redondeo arranca en 0 y, al guardar, se recalculan los precios que dependen de esos valores, con aviso previo de cuántos artículos cambian.

### 4.7 Moneda — **no previsto en CU-04; pregunta**

GDS tiene "Costo en dólares" por artículo y la cotización en pantalla todo el tiempo (G02, G18). Si algún proveedor del piloto manda la lista en USD, CU-04 necesita una moneda por proveedor y una cotización. Con el punto 4.3 queda preparado sin hacer nada más ahora. **Pregunta 3.**

### 4.8 Decisiones de CU-04 que el relevamiento no toca

"Faltantes solo se cuentan", "mapeo por índice" y "filas con error se saltean" no tienen ninguna evidencia en contra en GDS. La alta en bloque de artículos nuevos con el margen del rubro coincide con el alta mínima de GDS (G23), pero ferreteria exige costo, y lo trae la lista.

### 4.9 CU-05 contra la Actualización de Precios de GDS (nuevo)

Ahora que se vio la pantalla (G26), la comparación es posible:

| | GDS (7.53) | CU-05 de ferreteria |
| --- | --- | --- |
| Filtros | Marca, rubro, proveedor, rango de códigos | Proveedor, rubro o búsqueda |
| Qué cambia | Costo o precio de venta | Costo (porcentaje) o margen |
| Fórmula | `Lista X = Lista Y + valor`, en % o en $ | Porcentaje de aumento |
| Redondeo | Múltiplo configurable (0,50) | Múltiplo de ferreteria, que el Dueño edita en «Margen y redondeo» (CU-03, 2026-10-23) |
| Vista previa | No se sabe (no se ejecutó) | Sí, antes de confirmar |

Conclusión: CU-05 cubre lo importante. Lo único que GDS tiene de más es sumar un **monto fijo en $** (GDS-40, post-MVP) y filtrar por rango de códigos (no hace falta con búsqueda).

---

## 5. Impacto en lo ya implementado (CU-03)

| Tema | Qué muestra GDS | Recomendación para el modelo de CU-03 |
| --- | --- | --- |
| Precio de lista y bonificaciones | El costo sale de lista − 3 descuentos (G14, G86, G96) | **Cambiar ahora (fase 3):** `articulo.precio_lista_proveedor` nullable y `proveedor.bonificacion_1/2/3`. El costo sigue editable a mano (origen MANUAL) para artículos sin proveedor |
| Varias listas de precio | 4 listas, más una lista por defecto por cliente (G15, G16, G31) | **No cambiar el artículo.** Contado contra tarjeta se resuelve como recargo por medio de pago en CU-07 (C), como hace la tabla de medios de GDS (G93). Las listas mayoristas, post-MVP |
| Costo en USD | Por artículo, con cotización global (G18) | No cambiar ahora. Con `precio_lista_proveedor` guardado aparte, sumar `proveedor.moneda` después es aditivo |
| Varios proveedores por artículo | **No**: un proveedor y un código de proveedor (G09) | **Mantener** el modelo actual |
| Variantes de código de barras | No se ven. GDS ni siquiera separa el código interno del de barras (G08) | **Mantener** un solo `codigo_barras`. Ferreteria ya está mejor que GDS |
| Etiquetas con código de barras | Plantillas EAN-13, Code39 y Code128 en hojas A4 (G85) | No cambia el modelo. Sumar después una vista imprimible con los artículos elegidos (GDS-31). Si el artículo no tiene código de barras, permitir usar el código interno |
| Marca | Campo propio con reportes (G10) | Post-MVP, como columna nullable |
| Costo editable | Bloqueado en GDS | Ferreteria lo deja editar en CU-03. Está bien para el MVP; con bonificaciones, el costo de los artículos importados sale de la lista |
| Fecha de última actualización de precio | "F. Modif." en el listado y en la búsqueda de la venta (G22) | Exponerla en el listado y la búsqueda desde `historial_precio`, sin cambiar el esquema |
| Stock fuera del maestro | Igual (G12) | Ya está bien: `ArticuloRequest` ignora el stock |
| Stock mínimo por ventas | Opción de configuración (G97) | Post-MVP (GDS-41) |
| Costo en el ítem vendido (modelo futuro, no CU-03) | Hay reportes de ganancia (G66) | Agregar `costo_unitario` a `ItemComprobante` antes de crearlo en la fase 4 |

---

## 6. Atajos de teclado

Pedido de Luca: las teclas **no tienen que coincidir con GDS**, pero tienen que hacer más rápido el mostrador con **solo teclado y lector de código de barras**. La meta de la fase 4 es una venta de 5 artículos en menos de 15 segundos.

### 6.1 Inventario de teclas de GDS

Salen de los volcados de menú de la 7.53 (`M:*`, exactos), de la bitácora (`R`, F-001, F-1420, F-1440, F-1450, F-1000, F-1430, F-1600) y del catálogo (`C §3`). En las pantallas que abren con su propia barra de menús, el menú es la fuente más confiable.

**Menú principal** (`M:principal`, F-001, F-1400)

| Tecla | Acción en GDS |
| --- | --- |
| F1 | Artículos (agregar o modificar) |
| F2 | Clientes (agregar o modificar) |
| F3 | Compras (ingreso de mercadería) |
| F4 | Ventas (facturas) |
| F5 | Remitos |
| F6 | Presupuestos |
| Ctrl+Alt+G | Ayuda → Acerca de |

El menú principal no tiene teclas para Cheques, Proveedores, Vendedores, Caja, Reportes ni Administración. En la configuración se elige si en la botonera aparece Remito (F5) o Presupuesto (F6) (`Ver en Botonera Principal`, F-1480).

**Factura / venta** (`M:ventas`, F-1450)

| Tecla | Acción en GDS |
| --- | --- |
| F3 | Buscar cliente |
| F5 | Buscar artículo |
| F6 | Buscar por código de proveedor |
| F9 | Cliente nuevo |
| `*` | Cambiar cantidad |
| `/` | Cambiar precio (en la compra abre el cálculo de costo real) |
| `+` | Descuento por ítem |
| `-` | Descuento global |
| Ctrl+R (botón `R`) | Quitar un renglón |
| Ctrl+D | Modificar la descripción de servicios |
| F12 | Mostrar fotografía |
| F11 | Pasar a remito |

Los cuatro símbolos aparecen en el menú como `[ * ]`, `[ / ]`, `[ + ]` y `[ - ]` **sin aceleradores**, y también como botones de la botonera. El catálogo los llama atajos, pero la bitácora no probó su uso por teclado. Están en el teclado numérico, donde ya está la mano del cajero. La pantalla de `Presupuesto` (F6) repite la estructura de la factura.

**Compras** (`M:compras`, F-1440): F3 buscar proveedor, F5 buscar artículo, F9 artículo nuevo, `*` cantidad, `/` precio y Ctrl+R quitar un renglón.

**Listado de artículos** (`M:articulos`, F-1420): Ins nuevo, Ctrl+M modificar, Ctrl+Del borrar, F1 buscar por código, F2 por descripción, F3 por marca, Ctrl+B buscar en la columna activa, Ctrl+O ordenar por la columna actual, F12 foto y Ctrl+A clientes que compraron el artículo.

**Listado de clientes** (`M:clientes`): Ins, Ctrl+M, Ctrl+Del, F1 código, F2 nombre, F3 dirección, F4 teléfono, Ctrl+B y Ctrl+O.

**Consultas** (`M:consulta-facturas`, `M:cheques`, F-1440, F-1000): en facturas, F1 número, F2 fecha y F3 cliente. En compras, F1 número, F2 fecha y F3 proveedor. En cheques, F1 número y F2 fecha. En auditoría, F1 acción, F2 fecha y F3 usuario.

**Otras** (F-1430, F-1600): en la liquidación de comisiones, F3 cambia el porcentaje de una factura y clic o Enter la incluye o la quita del total. En el desglose de pago, Enter pasa al medio siguiente con el resto ya cargado.

**Lo que no hay evidencia de que exista:** Esc para cancelar o limpiar, Tab como atajo, o teclas para cobrar. El relevamiento no lo probó.

### 6.2 Lo que enseña GDS

- **Las teclas de función cambian de significado según la pantalla.** F3 es Compras en el menú, buscar cliente en la venta, proveedor en la compra, marca en artículos y dirección en clientes. F1 es Artículos en el menú y "buscar por código" en los listados. F5 y F6 son Remito y Presupuesto en el menú, pero buscar artículo y buscar por código de proveedor dentro de la venta. Esto obliga a mirar la pantalla antes de apretar. En ferreteria cada tecla tiene que significar lo mismo en todo el mostrador.
- **Cada función frecuente tiene tecla, botón y menú.** Es el patrón que `I` recomienda adoptar ("los atajos de teclado").
- **GDS necesita seis teclas para buscar** (artículo por código, por descripción, por marca, por código de proveedor, cliente y proveedor). Ferreteria tiene **un solo campo** (CU-06) que resuelve las cuatro búsquedas de artículo. Esas teclas desaparecen.
- **Lo que no hay que copiar:** `*`, `/`, `+` y `-` como teclas sueltas (ver 6.3), y que acciones destructivas tengan atajo (Ctrl+Del para borrar).

### 6.3 Restricciones del mostrador

El lector funciona como un teclado: **escribe el código en el campo de búsqueda y manda Enter** (spec, CU-06). De ahí salen las reglas:

1. **Mientras el foco está en un campo donde se escriben códigos o descripciones, ninguna tecla imprimible puede ser un atajo.** Un `*`, un `/`, un `+`, un `-` o una letra es parte del texto. Hay códigos con guiones y barras, y los Code39 suelen llevar `*` como delimitador de inicio y fin (el lector normalmente lo saca, pero no siempre). Por eso GDS puede usar el teclado numérico y ferreteria no.
2. **Sirven las teclas que el lector no genera**: F1 a F12, Esc, flechas, Supr y combinaciones Ctrl o Alt. Enter y Tab quedan reservadas para confirmar y moverse.
3. **Una tecla imprimible puede ser atajo solo cuando el foco no está en un campo de texto** y siempre con una salida segura: si el cajero escanea sin darse cuenta de que el foco quedó en el ticket, ninguna tecla suelta puede borrar nada. Por eso el mapa de abajo no usa ninguna tecla imprimible como atajo.
4. **Las teclas del navegador.** F5 recarga (pierde el ticket en curso), F11 es pantalla completa, F12 las herramientas de desarrollo, F1 la ayuda del navegador, F6 el foco en la barra de direcciones, F3 buscar siguiente, F7 la navegación con cursor. Ctrl+W, Ctrl+T, Ctrl+N, Ctrl+Tab y Alt+F4 **no se pueden interceptar** o conviene no intentarlo. Algunas teclas de función sí se pueden anular con `preventDefault` (CU-06 lo hace con F2), pero eso se verifica en el navegador del piloto y no hay que pelearse con el navegador por teclas de uso común.
5. **En notebooks y teclados Mac las teclas F requieren Fn** si no se configuran como estándar. Hay que ver con qué equipos trabaja el mostrador.

### 6.4 Mapa propuesto — *propuesta, a confirmar en CU-07*

**CU-06 ya está decidido** y no se toca: F2, Esc y flechas con Enter. Lo demás es propuesta.

| Tecla | Acción | Estado | Por qué |
| --- | --- | --- | --- |
| **F2** | Foco al buscador y selecciona todo el texto | Decidido (CU-06) | Tecla de función: no la escribe el lector, no la usa el navegador. Funciona desde cualquier lugar de la pantalla |
| **Esc** | Limpia buscador, resultados y ficha, y deja el foco en el buscador. En CU-07: si hay un diálogo abierto, lo cierra primero. **Nunca cancela ni vacía la venta** | Decidido (CU-06), con la regla nueva propuesta | GDS no usa Esc. Es la salida universal. Cancelar la venta no puede estar a un dedo de distancia: va con botón y confirmación |
| **↑ / ↓ y Enter** | Moverse por la lista de resultados y elegir | Decidido (CU-06) | No son imprimibles. La lista solo existe con texto en el campo |
| **Enter** (en el buscador) | Con coincidencia exacta o fila elegida, **agrega 1 unidad al ticket** y deja el foco y el texto listos para la próxima pasada. Escanear el mismo artículo otra vez suma cantidad (G35) | Propuesta (extiende CU-06) | El Enter ya lo manda el lector: escanear es agregar. No hay que tocar nada más. Es el punto de extensión que deja el diseño de CU-06 |
| **F4** | **Cobrar**: abre el pago desde cualquier foco, si el ticket no está vacío | Propuesta | Es la acción que cierra la venta, la más frecuente. Se parece a GDS (F4 es Ventas) pero acá es "cobrar". No está reservada por el navegador, y Alt+F4 es otra combinación |
| **F3** | **Cantidad** del renglón elegido (por defecto el último agregado): abre el número, Enter confirma y el foco vuelve al buscador | Propuesta, a validar | Reemplaza al `*` de GDS. Es lo que más se corrige en una venta ("son 20 tornillos"). **Conflicto leve:** F3 es "buscar siguiente" en Chrome y Firefox; se puede anular con `preventDefault`, pero hay que probarlo |
| **F8** | **Guardar como presupuesto** (cotización: no descuenta stock, se convierte en venta) | Propuesta | **Reemplaza a F6.** F6 lleva el foco a la barra de direcciones del navegador y en GDS ya significa "buscar por código de proveedor". F8 no tiene uso en los navegadores comunes |
| **F9** | **Elegir cliente** (CUIT o DNI; Consumidor Final por defecto). Dentro del selector, alta rápida si no existe (G94) | Propuesta | Es F9 en GDS para "cliente nuevo". En ferreteria el selector y el alta rápida van juntos |
| **↑** con el buscador **vacío** | Pasa el foco al ticket y elige el último renglón. Después ↑ y ↓ recorren los renglones | Propuesta | No choca con CU-06: la lista de resultados solo existe cuando hay texto. No imprimible |
| **Enter** sobre un renglón | Edita el renglón: cantidad y descuento %. Esc vuelve al buscador | Propuesta | Camino sin F3: ↑, Enter, número, Enter. Reemplaza a los `+` y `-` de GDS |
| **Supr** sobre un renglón | Quita el renglón | Propuesta | Reemplaza a Ctrl+R de GDS. Solo actúa con el foco en el ticket, que es donde se elige el renglón a propósito. Regla de seguridad: con el foco en el ticket, cualquier tecla imprimible devuelve el foco al buscador y el texto entra ahí, así un escaneo desatendido no dispara nada |
| **Dentro del pago**: Enter, ← →, Esc | Enter confirma con efectivo y el total como importe recibido; ← y → cambian de medio (efectivo, débito, crédito, transferencia, pago mixto); Esc vuelve al ticket | Propuesta | En el diálogo no hay campo de código, así que no hay conflicto con el lector. Efectivo por el total es la venta típica: **F4 y Enter**. El resto se calcula solo, como en el desglose de GDS, sin el defecto del mouse (G53) |

**Cómo queda la meta de 15 segundos.** Cinco artículos escaneados (el Enter lo manda el lector), **F4**, **Enter**. No hace falta tocar el mouse. Para cambiar una cantidad: **F3**, el número y Enter. Para vender a un cliente identificado: **F9**, CUIT y Enter, y después igual. Esto se tiene que cronometrar en el mostrador de una ferretería real.

**Navegación entre pantallas.** Sin teclas en el MVP. El Vendedor aterriza en `/mostrador` y vive en esa pantalla; el resto va por el menú. Las F1 a F6 de GDS (artículos, clientes, compras, ventas, remitos, presupuestos) responden a que GDS es un sistema de muchas pantallas y su botonera es el centro. Ferreteria no tiene ese problema.

**Sin atajo a propósito.** Vaciar o cancelar el ticket, anular una venta y las acciones de CU-08 y CU-10. Son acciones que cuestan caro si se hacen sin querer (G60, G84).

### 6.5 Conflictos y teclas que no se usan

| Tecla | Conflicto | Decisión |
| --- | --- | --- |
| **F6** | La propuesta anterior de este informe (G01) usaba F6 para presupuesto. En GDS es "buscar por código de proveedor" (venta) y en el menú "Presupuesto". En los navegadores lleva el foco a la barra de direcciones | **No se usa.** Presupuesto pasa a F8 |
| **F5** | En GDS es Remito (menú) y "buscar artículo" (venta). En el navegador recarga la página y **pierde el ticket en curso** | **No se usa.** Si el usuario la aprieta, conviene un aviso de "salir de la página" cuando hay un ticket abierto |
| **F11** | En GDS es "pasar a remito". En el navegador es pantalla completa, y puede ser útil para el cajero | **No se usa** |
| **F12** | En GDS es "mostrar fotografía". En el navegador abre las herramientas de desarrollo | **No se usa** |
| **F1** | En GDS es Artículos y "buscar por código". En el navegador abre la ayuda | **No se usa** |
| **F3** | En GDS es buscar cliente, proveedor, marca y dirección según la pantalla. En el navegador es "buscar siguiente" | **Se usa para la cantidad**, a validar. Si choca, la alternativa es el camino de ↑, Enter |
| **F4** | En GDS es "Ventas". Alt+F4 cierra la ventana (otra combinación) | Se usa para cobrar. Cuidar que el cajero no la combine con Alt |
| **F7** | En los navegadores pregunta por la navegación con cursor | No se usa |
| **`*` `/` `+` `-` y letras** | Son los atajos de GDS en la venta, pero los lee el campo de búsqueda | **No se usan como atajos** (6.3) |
| **Ctrl+W, Ctrl+T, Ctrl+N, Ctrl+Tab, Alt+F4, Ctrl+D, Ctrl+P, Ctrl+R, Ctrl+F** | Son del navegador (cerrar o abrir pestañas y ventanas, favoritos, imprimir, recargar, buscar). Ctrl+D y Ctrl+R además son de GDS (descripción de servicios y quitar renglón) | No se usan |
| **Alt + letra** | Abren los menús del navegador en varios equipos, y Alt+← es "atrás" | No se usan |
| **Tab y Shift+Tab** | Mover el foco entre controles | No se reasignan |

### 6.6 Para validar antes de congelar el mapa

- Qué navegador y qué equipos usa el piloto (teclas F con o sin Fn, teclado numérico).
- Cómo está configurado el lector: que mande solo Enter al final (sin prefijo, sin Tab) y que no emita las teclas de los atajos.
- Que `preventDefault` funcione para F3, F4, F8 y F9 en ese navegador (F2 ya está probado en CU-06).
- Cronometrar la venta de 5 artículos con el mapa en una ferretería real (la meta de la fase 4).

---

## 7. Propuesta de backlog

Ordenada por prioridad. Las de la fase 3 afectan a CU-04 y van primero. **GDS-31 en adelante son de la 7.53**: el orden de la tabla no es el de prioridad para esas, la dan las columnas Fase y Clase.

| Id | Descripción | Clase | Fase | Esfuerzo |
| --- | --- | --- | --- | --- |
| GDS-01 | Bonificaciones en cascada (hasta 3 %) por proveedor, aplicadas al importar: `costo = lista × Π(1 − bᵢ)`. GDS lo confirma en el artículo, la compra y `Configurar Descuentos` | B | 3 (CU-04) | S |
| GDS-02 | "Lista incluye IVA" quita el IVA según la alícuota del artículo (21 % para los nuevos, editable en la vista previa) | B | 3 (CU-04) | S |
| GDS-03 | Guardar `precio_lista_proveedor` aparte del costo, con migración aditiva | B | 3 (CU-03/04) | S |
| GDS-04 | Vista previa: costo 0 o vacío es error; variación mayor a ±X % es advertencia | B | 3 (CU-04) | S |
| GDS-05 | Mostrar la fecha de la última actualización de costo en el listado y la búsqueda. **Hecho en CU-03 (2026-10-23, Luca)**: «Costo al» | B | 3 (CU-03) | S |
| GDS-06 | Movimientos manuales de caja: retiro, gasto e ingreso con motivo, incluidos en el arqueo | B | 4 (CU-10) | M |
| GDS-07 | Pago mixto: más de un medio por venta, con el resto calculado solo y vuelto en efectivo (importe recibido opcional) | B | 4 (CU-07) | S |
| GDS-08 | Copiar el costo en `ItemComprobante` y sumar ganancia y ventas por usuario al reporte del MVP. **Hecho en CU-15 (2026-10-22, Luca)** | B | 4 | S |
| GDS-09 | Bloquear la venta de artículos con precio 0 | B | 4 (CU-07) | S |
| GDS-10 | Atajos de teclado fijos en el mostrador. **CU-06 decidido (F2, Esc, flechas y Enter).** CU-07 propuesto: F4 cobrar, F3 cantidad, F8 presupuesto, F9 cliente, ↑ y Supr en el ticket. Ver la sección 6 | B | 4 (CU-06/07) | S |
| GDS-11 | Recargo % por medio de pago (tarjeta de crédito), configurable, con una tabla de medios de pago como la de GDS. **Hecho en CU-07:** tabla de formas de pago por ferretería con su recargo (0 a 200 %), prorrateado entre renglones y por parte en el pago mixto. La columna `Porcentaje` de GDS (probable comisión de la tarjeta) no se implementa | C | 4 (CU-07) | M |
| GDS-12 | Descuento manual % por renglón o total, con tope para el Vendedor | C | 4 (CU-07) | M |
| GDS-13 | Presupuesto como cotización con estados (pendiente, aceptado, rechazado, facturado), sin stock, con vencimiento y convertible en venta sin doble descuento, separado de la venta sin factura. **Hecho en CU-07:** presupuesto con estados, validez de 1 a 90 días (15 por defecto), numeración propia, sin stock ni caja, pantalla de presupuestos y «Pasar a venta» con los precios de hoy (el cliente es texto libre hasta que exista la tabla de clientes; sin PDF hasta la fase 5; la venta sin factura es la marca «No facturable» al cobrar) | B | 4 (CU-07) | M |
| GDS-14 | Cliente con email y WhatsApp, y validación del dígito del CUIT | B | 4/5 (CU-07) | S |
| GDS-15 | Consulta y reimpresión de comprobantes (número, fecha, cliente), sin borrado, con acciones destructivas separadas de las consultas. **Hecho en CU-16 (2026-10-23, Luca)** para las ventas; la nota de crédito y su consulta esperan la fase 5 | B | 5 (CU-08) | M |
| GDS-16 | Ingreso de mercadería por remito del proveedor (varios renglones y número). Si actualiza el costo, avisar que el precio de venta no cambia | B | 6 (CU-09) | M |
| GDS-17 | Consulta de movimientos de stock por artículo. **Hecho en CU-09 (2026-10-23, Luca)** | B | 6 (CU-09) | S |
| GDS-18 | Listado de control: artículos sin costo, sin rubro, con margen negativo | B | 6 (CU-03) | S |
| GDS-19 | Exportar artículos a CSV y carga inicial reutilizando el pipeline de CU-04. **Hecho en CU-03 (2026-10-23, Luca)**: «Exportar» en Artículos; la carga inicial es la importación de CU-04 | B | 6 (CU-03) | M |
| GDS-20 | Cuenta corriente de clientes y cobranzas (saldo y cobro) **con imputación de cada cobro a comprobantes**, si la fase 0 lo confirma. **En curso (2026-10-18, Luca):** el tramo 1, la tabla de clientes con F9 en el mostrador (CU-11), y el **tramo 2 están hechos**: la forma de pago «Cuenta corriente» (CU-07, inactiva hasta que el Dueño la activa, con el cliente obligatorio), el libro de debe y haber con el saldo del cliente, el límite por cliente que solo pone el Dueño, el aviso y la autorización del exceso solo por el Dueño (G54), el saldo y el límite que ve solo el Dueño (el Vendedor ve «alcanza» o «no alcanza»), no poder desactivar a un cliente con saldo y el renglón informativo de la caja (CU-10). **El tramo 3a está hecho (2026-10-19, Luca):** la cobranza en efectivo o transferencia **con imputación a cada comprobante** (CU-12; FIFO sugerida y editable, excedente y adelanto a cuenta, solo el Dueño, 409 `IMPUTACION_EXCEDE_DEUDA` si la deuda cambió), el **recibo interno no fiscal** con numerador propio e idempotencia por clave, la pantalla de cuenta del cliente con sus pendientes y la caja con cobranzas (el efectivo suma al esperado, la transferencia es informativa). **El tramo 3b-1 está hecho (2026-10-21, Luca):** la **anulación** de una cobranza (`Anular Pago`, G59: solo el Dueño, con motivo, caja abierta del día, contra-asiento en el libro, los comprobantes vuelven a quedar pendientes, recibo reimprimible con la marca «ANULADO» y la caja con el renglón de anulaciones) y el **extracto de movimientos** (saldo real por línea, filtros de fecha y tipo, paginación e impresión), con los CU-10, CU-11 y CU-12 actualizados. **El tramo 3b-2 está hecho (2026-10-23, Luca, «como lo tiene gds»):** `Recargo` y `Descuento` manuales con motivo (CU-12 punto 8), el Recargo como partida que se cobra, el Descuento imputado FIFO, la deuda nueva que consume sola el saldo a favor y desactivar a un cliente con saldo a favor llevándolo a cero con un Recargo. La nota de crédito sigue para la fase de facturación, y el PDF del recibo y las comisiones (G62) después | C | 6 o post-MVP | L |
| GDS-21 | Moneda USD por proveedor y cotización con fecha | D | post-MVP | M |
| GDS-22 | Límite de crédito y lista o descuento por cliente. El límite avisa y pide autorización de un rol, no bloquea sin salida. **Hecho en CU-07 (2026-10-18, Luca) para el límite de cuenta corriente**, dentro de GDS-20 (tramo 2): avisa y lo autoriza solo el Dueño; lista o descuento por cliente siguen post-MVP | D | post-MVP | M |
| GDS-23 | Listas de precios múltiples (mayorista) | D | post-MVP | L |
| GDS-24 | Compras, cuenta corriente de proveedores, pagos y orden de pago. Incluye compras sin artículos (gastos y servicios) con IVA sugerido y validado, y el libro IVA Compras | D | post-MVP | L |
| GDS-25 | Cheques y echeqs: cartera, depósito, rechazo y entrega, con validación de importes | D | post-MVP | L |
| GDS-26 | Reportes avanzados: stock valorizado, ranking por artículo, ventas por rubro y marca, libro IVA ventas a Excel. Los resúmenes suman totales de comprobante, no renglones | D | post-MVP | M |
| GDS-27 | Campo marca, promoción y precio fijo manual | D | post-MVP | S |
| GDS-28 | Comisiones de vendedores, liquidadas sobre comprobantes cobrados (depende de GDS-20). **Habilitada (2026-10-19, Luca):** el dato «cobrado y pendiente por comprobante» ya sale de la imputación de GDS-20 (tramo 3a); la liquidación sigue post-MVP | D | post-MVP | M |
| GDS-29 | Auditoría general por acción, usuario y fecha, separada de las herramientas de mantenimiento | D | post-MVP | M |
| GDS-30 | Seña en la venta | D | post-MVP | S |
| GDS-31 | Etiquetas imprimibles con código de barras desde el catálogo (EAN-13 y Code128, hoja A4 en columnas, tres tamaños). Sube a fase 3 o 4 si el piloto etiqueta sus artículos. **Hecho en CU-03 (2026-10-23, Luca)**: «Etiquetas», 4 o 5 columnas, sin los tres tamaños | C | 4 (según fase 0) | M |
| GDS-32 | Venta a Consumidor Final: por encima de un umbral configurable exige DNI o CUIT. Verificar el valor vigente de ARCA en la fase 1 | B | 4/5 (CU-07) | S |
| GDS-33 | Escanear dos veces el mismo artículo suma cantidad en el renglón (no agrega otro) | B | 4 (CU-07) | S |
| GDS-34 | Regla de venta sin stock suficiente: decidir si bloquea, avisa o permite (ver pregunta 11) | B | 4 (CU-07) | S |
| GDS-35 | Registrar como decisión que los documentos tienen efectos **fijos** sobre stock y caja, sin interruptores globales | A | 4 | S |
| GDS-36 | Validar egresos de caja contra el saldo en efectivo de la sesión (aviso o autorización) | B | 4 (CU-10) | S |
| GDS-37 | Totales coherentes: el total del comprobante es la suma de sus renglones y los reportes suman totales de comprobante, con un test de dominio (en GDS difieren dos centavos) | B | 4 (CU-07) | S |
| GDS-38 | Crear cliente sin salir de la venta (alta rápida con CUIT o DNI) | B | 4/5 (CU-07) | S |
| GDS-39 | Ante una suscripción vencida, degradar a solo lectura y permitir exportar los datos | D | post-MVP | S |
| GDS-40 | Aumento de precios con un monto fijo en $ además del porcentaje (CU-05) | D | post-MVP | S |
| GDS-41 | Calcular el stock mínimo en base a las ventas | D | post-MVP | M |

---

## 8. Preguntas para Luca

1. **Bonificaciones (urgente para CU-04):** ¿las listas de los proveedores de Mar del Plata vienen como "precio de lista" con bonificaciones (por ejemplo 40 + 10)? ¿Metemos GDS-01, hasta 3 bonificaciones por **proveedor**, ya en CU-04, o se mantiene afuera y el dueño mapea una columna de neto? (GDS asigna las bonificaciones en bloque por proveedor, marca o rubro con `Configurar Descuentos`.)
2. **IVA mixto:** si la lista incluye IVA, ¿confirmás que se quite según la alícuota de cada artículo (GDS-02) y que los nuevos entren al 21 %, corregible en la vista previa?
3. **Dólares:** ¿algún proveedor de la ferretería piloto manda la lista en USD? Si dice que sí, GDS-21 sube a fase 3; si no, alcanza con GDS-03.
4. **Contado contra tarjeta:** ¿la ferretería piloto cobra distinto con tarjeta de crédito, o por cuotas? GDS lo resuelve con una tabla de medios de pago con % de recargo (Visa 1 cuota, Visa 6 cuotas). Si cobra distinto, ¿alcanza con un recargo % por medio de pago (GDS-11)?
5. **Descuentos en el mostrador:** ¿el vendedor puede hacer un descuento manual? ¿Con tope, o solo el Dueño (GDS-12)? En GDS el tope es por usuario.
6. **"Presupuesto":** en la spec es la venta sin factura; en GDS es una cotización con estados que después se convierte en venta. ¿Cuál de los dos necesitan, o los dos (GDS-13)? **Resuelta (2026-10-18, Luca):** la cotización como GDS primero; la venta sin factura queda para después como otro documento.
7. **Cuenta corriente:** ¿qué respondieron en la fase 0 a "¿venden a cuenta corriente?"? GDS la pone en la misma pantalla de factura, como pago mixto con cuenta corriente, **pero no imputa los cobros**: si entra, ¿lo hacemos con imputación desde el principio (GDS-20)? *(2026-10-18: la venta a cuenta corriente con límite, el tramo 2, ya está hecha; las cobranzas con imputación, el tramo 3, quedan por decidir con la respuesta de la fase 0.)* *(2026-10-19: se hizo con imputación desde el principio, el tramo 3a, con recibo interno no fiscal; la anulación, los ajustes y el extracto quedan para el 3b.)*
8. **Caja:** ¿sumamos a CU-10 los retiros y gastos de caja (GDS-06)? Sin ellos, la diferencia del arqueo no explica la plata que sale de la caja. GDS los probó y deja el efectivo negativo sin aviso (GDS-36).
9. **Etiquetas (nueva):** ¿los artículos del piloto ya vienen con código de barras, o los etiquetan ellos? Si etiquetan, GDS-31 pasa a la fase 4 (sin códigos, el lector no sirve y los 15 segundos no se cumplen).
10. **Consumidor final sin datos (nueva):** GDS tiene un tope parametrizable ($5.000 en la demo) para vender a Consumidor Final sin identificarlo. ¿Lo hacemos parámetro por ferretería, con el valor vigente de ARCA verificado en la fase 1 (GDS-32)?
11. **Stock insuficiente (nueva):** ¿se puede vender sin stock en el sistema? GDS lo resuelve con un interruptor (`Limitar la venta sin stock`). Mi propuesta es **avisar y dejar vender** (GDS-34), porque en una ferretería el stock del sistema suele estar atrasado.
12. **Atajos (nueva):** ¿te cierra el mapa de la sección 6 (F4 cobrar, F3 cantidad, F8 presupuesto, F9 cliente)? ¿Y qué navegador y equipos usa el piloto?

---

## Anexo: qué se puede reusar de `mostrador-gestion` (Next.js/Prisma)

- **Dominio puro con tests** (`src/domain/inventory.ts`, `cash.ts`, `quotes.ts`, `permissions.ts`): es el mismo enfoque que `PrecioCalculator`. Conviene repetirlo para la caja (arqueo con movimientos) y los presupuestos (estado y vencimiento, que en Mostrador se resuelve al consultar: EXPIRED).
- **`FiscalDocument`** con `idempotencyKey` único, `requestPayload` y `responsePayload` en JSON y `originalDocumentId` para las NC: es un buen molde para el `Comprobante` de la fase 5 (reintentos sin duplicar y bandeja de rechazados con el error tal cual).
- **`InventoryMovement`** con `referenceType` y `referenceId` (venta, compra, ajuste): sirve como patrón para `MovimientoStock`, más general que solo `comprobante_id`.
- **`AccountEntry`** con debe y haber, compartido por clientes y proveedores: una base simple para GDS-20 si la cuenta corriente entra. Ojo: `BL` marca que hoy no tiene vínculo a la venta (falta la imputación).
- **`src/lib/csv.ts`** (escapa comillas, comas y saltos, CRLF): una referencia para GDS-19.
- **No reusar el importador** (`src/app/api/import/products/route.ts`): parte el CSV con `split(",")`, aborta todo ante la primera fila inválida, hace upsert por fila sin vista previa y usa `companyId` fijo. Es lo contrario de las decisiones de CU-04.
- **Lecciones de UX del relevamiento** que sirven igual: confirmar importes fuera de rango, no poner "Borrar" al lado de "Anular", que la impresora nunca bloquee, deshabilitar (no ocultar) los controles sin permiso y que un aviso importante no sea solo un texto en el título de la ventana (el de GDS cuando el stock está deshabilitado).
- **Lecciones de UX nuevas de la 7.53**: el desglose de pago que calcula solo la parte financiada es un buen patrón; en cambio, que cambiar de solapa con el mouse confirme el importe sugerido es un defecto. Un permiso negado debe deshabilitar el control y explicar por qué.
