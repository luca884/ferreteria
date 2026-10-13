# GDS Ferretería Plus 7 contra la spec de ferreteria

Informe de investigación (solo lectura) sobre el relevamiento de GDS que Luca hizo en `mostrador-gestion`, cruzado con `docs/spec.md` de ferreteria. Fecha del informe: 2026-10-12.

**Abreviaturas de fuentes** (rutas relativas al clon `mostrador-gestion`):

- `R` = `documentacion/investigaciones/gds-ferreteria-plus-7/README.md` (relevamiento principal)
- `S` = `documentacion/sesiones/2026-08-02-relevamiento-gds.md`
- `fNNN` = captura `documentacion/investigaciones/gds-ferreteria-plus-7/capturas/fNNN-*.png`. Abrí solo siete: f010, f121, f323, f413, f636, f637 y f655. Las demás las cito por su nombre y por lo que dice el texto.

---

## 1. Resumen del relevamiento

**Qué se relevó.** Ferretería Plus 7.51 de GDS Sistemas, versión demo instalada localmente en Windows, con datos ficticios (`R:12-20`). Fue una sola jornada, el **2026-08-02** (de 15:46 a 19:17 según los JSON de captura y `R:659`). Luca lo automatizó con scripts de PowerShell y UI Automation (`tools/gds-*.ps1`) que sacaban una captura y un JSON del árbol de controles por paso. Hay 368 PNG y 353 JSON. Los JSON casi no traen texto útil, más allá de algunos rótulos como "Recargo", "Adelanto", "Costo en dólares" o "Lista 1 Calculada".

El objetivo de Luca era usar GDS como referencia funcional para "Mostrador", un gestor comercial general (multi-sucursal, Next.js/Prisma). No era una ferretería SaaS como la actual, así que el foco del relevamiento fue administrativo, no la gestión de precios.

**Cobertura** (`R:65-84`, `S`, `backlog.md`):

| Módulo GDS | Estado | Profundidad |
| --- | --- | --- |
| Navegación, menús, accesos rápidos | Relevado | Completo (inventario de todos los menús de primer nivel) |
| Artículos: alta, modificación, costo calculado | Relevado | Bueno. Quedaron afuera etiquetas y movimientos completos (`R:68-69`) |
| Artículos: **Actualización de Precios** | **No relevado** | Solo se vio que existe en el menú (`R:115`, f010) |
| Artículos: **Importar / Exportar** | **No relevado** | Solo se vio el menú del listado (`R:189`, f121/f323) |
| Clientes | Relevado | Formulario completo, cuenta corriente y cobranza |
| Compras y proveedores | Relevado | Ingreso por remito, cuenta corriente, pago y consulta. El maestro de proveedores no se documentó campo por campo |
| Cheques y bancos | Relevado | Muy profundo (ciclo completo y bugs) |
| Ventas y facturación | Relevado parcial | Solo Factura A a un RI con lista 3 y pago mixto. **No se vio Factura B, consumidor final, CAE ni ARCA** |
| Remitos, presupuestos, pedidos | **No relevado** (`R:77`) | Solo se vio que existen (F5/F6) |
| Vendedores y comisiones | Relevado | Consultivo, sin pagar comisiones |
| Caja | Parcial (`R:80-81`) | Caja del día vista. **No se vieron ingresos varios, gastos, retiros ni cierre** |
| Reportes | Solo inventario (`R:82`) | Dos reportes ejecutados sobre una base vacía (`R:548-569`) |
| Usuarios, permisos, configuración | **No relevado** (`R:83`) | — |
| Auditoría | Parcial | Consulta y submenú (`R:577-596`) |
| Backups, mantenimiento, solucionar problemas | **No relevado** (`R:84`) | — |

**Advertencia clave para CU-04:** las dos pantallas más relevantes para importar listas, *Actualización de Precios* e *Importar/Exportar*, **nunca se abrieron**. Lo que sigue sobre listas de proveedores sale del modelo de costo del artículo (pestaña "Costo Calculado") y de la compra, no del importador de GDS.

---

## 2. Inventario de funciones de GDS

Encontré 84 funciones, numeradas G01 a G84. La clase (A a E) se justifica en la sección 3.

| # | Módulo | Función | Detalle observado | Fuente |
| --- | --- | --- | --- | --- |
| G01 | General | Accesos rápidos por tecla | F1 Artículos, F2 Clientes, F3 Compras, F4 Ventas, F5 Remito, F6 Presupuestos y botones grandes laterales | `R:95-96`, `R:100`, `R:134` |
| G02 | General | Cotización del dólar global | "Dolar: $1.100,00" fijo en la pantalla principal | `R:97`, `R:101`, f636 |
| G03 | General | Panel de ayuda, guías y manual | Panel lateral "¿Necesitás ayuda?" y menú Ayuda | `R:98`, `R:153-156` |
| G04 | General | Notas | Botón lateral "Notas" (no se exploró) | `R:96`, f636 |
| G05 | General | Impresora de Windows obligatoria | Aviso que bloquea el ingreso y la confirmación de una compra | `R:46-47`, `R:53-54`, `R:315-316` |
| G06 | General | Reparación de integridad | Reindexa después de un cierre anormal | `R:48-50`, `R:55-56` |
| G07 | General | Licencia de la demo | Cuenta los usos disponibles | `R:43`, `R:52` |
| G08 | Artículos | Identificación | Código interno (es también el código que se escanea), código del proveedor y descripción | `R:177`, f121 |
| G09 | Artículos | Clasificación | Rubro, unidad, **un solo** proveedor y ubicación | `R:178`, f121 |
| G10 | Artículos | Marca | Campo propio, búsqueda F3 por marca y reportes por marca | `R:178`, f323, f636 |
| G11 | Artículos | Tablas auxiliares | Rubro, marca y unidad editables desde el formulario y desde Administración | `R:151`, `R:280-281`, f121 |
| G12 | Artículos | Stock y stock mínimo | El stock no se edita en el maestro, solo por ajuste o ingreso | `R:179`, `R:226-227` |
| G13 | Artículos | Costo, % de ganancia e IVA | Ganancia por artículo (30 por defecto) e IVA en combo (21; también existe 10,5) | `R:180`, f121, f413 |
| G14 | Artículos | **Costo calculado** | "Precio de lista del Proveedor" menos **hasta 3 descuentos encadenados** (`% + % + %`) da el costo. El costo directo está bloqueado ("El costo se calcula desde la solapa COSTO CALCULADO") | `R:185`, `R:228-229`, `R:308-310`, f323, f121 |
| G15 | Artículos | Precio efectivo y precio tarjeta | Dos precios por artículo. En la demo la tarjeta está 25 % arriba (4.500 contra 5.625) | `R:181`, f323 |
| G16 | Artículos | Lista 3 y lista 4 | Precios manuales extra (por ejemplo mayorista) | `R:181`, `R:231-232` |
| G17 | Artículos | "Lista 1 calculada" y "$ Calculado" | Marca si el precio sale de costo × ganancia o es manual | `R:182-183`, f121 |
| G18 | Artículos | Costo en dólares y Lista 1 en dólares | Costo y precio en USD convertidos con la cotización global | `R:182-183`, f121 |
| G19 | Artículos | Fecha de última compra | La actualiza el ingreso de mercadería | `R:184`, `R:317` |
| G20 | Artículos | En promoción | Marca y filtro en el stock valorizado | `R:184`, `R:560-561` |
| G21 | Artículos | Fotografía | Botón F12 y listado con foto | `R:184`, `R:187-188`, `R:542` |
| G22 | Artículos | Fecha de modificación | Columna "F. Modif." en el listado | `R:196-197`, f323 |
| G23 | Artículos | Alta mínima | Alcanzan código y descripción; costo y precio son opcionales | `R:191-200` |
| G24 | Artículos | Listado | Nuevo, modificar, borrar, buscar (F1 código, F2 descripción, F3 marca), ordenar y movimientos | `R:186-188`, f323 |
| G25 | Artículos | Importar / Exportar y vista configurable | Solo se vio el menú, no se relevó | `R:189`, f323 |
| G26 | Artículos | Actualización de precios | Opción del menú Artículos, no se relevó | `R:115`, f010 |
| G27 | Artículos | Ajuste de stock | Opción del menú (reporte "Ajuste de Stock") | `R:116`, f637 |
| G28 | Artículos | Salida de mercadería y consulta de salidas | Salidas por artículo y por motivo | `R:117-118`, f637 |
| G29 | Artículos | Movimientos por artículo | Histórico con el movimiento de entrada asociado al remito | `R:187`, `R:318-319`, f346 |
| G30 | Clientes | Datos generales | Código automático, razón social, contacto, fecha de nacimiento, dirección, teléfono, celular, WhatsApp, DNI/CUIT, condición IVA, rubro, correo y web | `R:257-262` |
| G31 | Clientes | Política comercial | Transporte, zona, vendedor, límite de cuenta corriente, % de descuento, lista por defecto y condiciones que pasan a la factura | `R:263-269` |
| G32 | Clientes | Observaciones | Texto libre largo | `R:270` |
| G33 | Clientes | Validación mínima | Solo la razón social es obligatoria | `R:271-274` |
| G34 | Compras | Ingreso de mercadería por remito | Proveedor, número, renglones y costo tomado del maestro (no se edita en la compra). Suma stock y movimiento | `R:290-319` |
| G35 | Compras | Renglones repetidos | El mismo código agrega renglones separados sin consolidar | `R:311-312` |
| G36 | Compras | Pago combinado de la compra | Efectivo, pago digital, cheque y cuenta corriente | `R:313-314` |
| G37 | Compras | Nota de crédito del proveedor y su consulta | Opciones del menú | `R:122`, `R:125` |
| G38 | Compras | Consulta de compras | Reconstruye el remito con encabezado, pago y renglones | `R:124`, `R:366`, `R:374-375` |
| G39 | Proveedores | Cuenta corriente del proveedor | Saldo, recargo y adelanto | `R:318-319`, f593-f597 |
| G40 | Proveedores | Pago a proveedores | Fecha, importe, concepto y medio; orden de pago y anular pago. Alimenta "Pagos a proveedores" en la caja | `R:380-400` |
| G41 | Proveedores | Maestro de proveedores | Listado con el saldo (campos no documentados) | `R:137`, f350 |
| G42 | Cheques | Alta de cheque | Recepción, importe, cliente, número, banco, vencimiento y "sale efectivo de caja" | `R:424-427` |
| G43 | Cheques | Cartera | Depositar en una cuenta, rechazar (afecta la cuenta del cliente) y anular. Pestañas de cartera, rechazados e histórico | `R:428-436` |
| G44 | Cheques | Entregar un cheque al proveedor | Se usa como medio de pago a proveedores | `R:437-439` |
| G45 | Cheques | Maestro de bancos | Solo descripción, con "Vaciar Tabla" | `R:440-441` |
| G46 | Cheques | Consultas | Cartera, recibidos hoy o entre fechas, vencen en 5 días, entregados e histórico a Excel | `R:126-132`, `R:443-449` |
| G47 | Ventas | Factura según el cliente | El cliente hereda tipo de factura, condición IVA, vendedor, lista, descuento y forma de pago | `R:339-340`, f413 |
| G48 | Ventas | Lista de precios en la venta | Se elige la lista (1 a 4) por comprobante | `R:341-343`, f413 |
| G49 | Ventas | Renglón con precio cero | Lo permite. Al cambiar de lista no recalcula los renglones | `R:341-343` |
| G50 | Ventas | Precio con IVA incluido y desglose | Neto, IVA 21 % e IVA 10,5 % por separado. La lista 3 de 1.400 se toma como precio final | `R:344-345`, f413 |
| G51 | Ventas | Descuento por renglón y global | Columna "Dto%" y campo "% Desc" en la factura | f413 |
| G52 | Ventas | Ajuste por redondeo del total | Casilla "Ajuste x Redondeo" | f413 |
| G53 | Ventas | Pago mixto | Efectivo más cuenta corriente, con distribución automática | `R:346-348`, `R:622-623` |
| G54 | Ventas | Límite de cuenta corriente | Pasarlo bloquea la confirmación | `R:346-348` |
| G55 | Ventas | Remitos (F5) | Existen; no se relevaron | `R:77`, `R:134` |
| G56 | Ventas | Presupuestos (F6) | Existen; no se relevaron | `R:77`, `R:134` |
| G57 | Ventas | Extras de la factura | Botones "Traer" y "Seña", N° de orden de compra, remito y observaciones (no documentados en el texto) | f413 |
| G58 | Ventas | Notas de crédito | Opción del menú Ventas | `R:135` |
| G59 | Ventas | Cobranzas | Fecha, concepto y medio. Deuda y pago son movimientos separados; el importe tiene que ser mayor que 0 | `R:353-378` |
| G60 | Ventas | Consulta de facturas y NC | Reconstruye el comprobante y ofrece **Anular** y **Borrar** en la misma pantalla | `R:136`, `R:374-375` |
| G61 | Vendedores | Maestro de vendedores | Documento, domicilio, teléfonos, sueldo, % de comisión, zona y observaciones | `R:500-502` |
| G62 | Vendedores | Liquidación de comisiones | Sobre total o neto, inclusión manual de facturas, % por factura (F3) y Pagar | `R:503-511` |
| G63 | Vendedores | Reporte de ventas por vendedor | Por rango de fechas | `R:510-511` |
| G64 | Caja | Caja del día | Inicial, ventas en efectivo, cobranzas, pagos a proveedores y efectivo final | `R:143-144`, `R:349-351`, `R:376-378`, `R:396-400` |
| G65 | Caja | Ingresos varios, gastos varios y retiros | Opciones del menú; no se probaron | `R:80-81`, `R:144` |
| G66 | Reportes | Resumen y ganancia | Resumen del día o entre fechas (ventas, devoluciones, costo, IVA, ganancia) y ganancia con y sin NC | `R:539`, `R:558-559`, f636 |
| G67 | Reportes | Ventas del día, entre fechas, por día y mensuales | Submenú Ventas | f636 |
| G68 | Reportes | Ventas por medio de pago digital y con descuento | Submenú Ventas | `R:539-540`, f636 |
| G69 | Reportes | Ventas agrupadas | Por artículo, zona, usuario, marca y rubro | `R:540`, f636 |
| G70 | Reportes | IVA Ventas | Normal, a Excel, agrupado y por provincia | `R:540`, f636 |
| G71 | Reportes | Facturas anuladas | Submenú Ventas | `R:541`, f636 |
| G72 | Reportes | Reportes de artículos | Listado, por rubro, stock valorizado, stock crítico, stock inicial, ajustes, con ubicación, salidas y con foto | `R:541-542`, `R:560-562`, f637 |
| G73 | Reportes | Reportes de caja | Diaria, ingresos, gastos y retiros | `R:543` |
| G74 | Reportes | Rankings | Por artículo, cliente o vendedor | `R:544`, f639 |
| G75 | Reportes | Gráficos estadísticos | Opción del menú | `R:536`, f636 |
| G76 | Reportes | Reportes de otros módulos | Compras, devoluciones, remitos, presupuestos, clientes, cuentas corrientes, proveedores y pagos | `R:145-147`, `R:534-536` |
| G77 | Administración | Usuarios | No se relevó | `R:83`, `R:151` |
| G78 | Administración | Configuración, solucionar problemas y mantenimiento | No se relevaron | `R:151-152`, `R:595-596` |
| G79 | Administración | Consulta de auditoría | Fecha, hora, acción, usuario y PC; búsqueda por acción, fecha o usuario | `R:577-589` |
| G80 | Administración | Consulta de anulaciones | Submenú Auditoría | f655 |
| G81 | Administración | Controles de datos | "Verificar costos en cero o mal cargados", "Ventas con costo mayor a precio de venta" y "Artículos con alícuota de IVA en 0" | `R:590-594`, f655 |
| G82 | Administración | Corrección masiva de IVA | "Poner IVA 21 % en artículos con IVA en 0" | `R:592-593`, f655 |
| G83 | Administración | Facturas que no descontaron stock | Detecta inconsistencias entre la venta y el stock | `R:593-594`, f655 |
| G84 | Cheques y bancos | Vaciar tabla | Acción destructiva global | `R:440-441` |

---

## 3. Cruce con la spec de ferreteria

Las clases son las del pedido: A = ya cubierto, B = refina un CU, C = candidato nuevo al MVP, D = post-MVP, E = descartar. "Impl." indica si ya está implementado (esqueleto, CU-03) o pendiente con su fase.

| # | Clase | Dónde cae / qué hacer | Por qué |
| --- | --- | --- | --- |
| G01 | **B** | CU-06/07, fase 4: definir atajos fijos (F2 buscar, F4 cobrar, F6 presupuesto, Esc cancelar) | El criterio de la fase 4 es "solo con teclado y lector". GDS confirma que el mostrador vive de las teclas de función |
| G02 | **D** | Cotización con fecha y fuente, junto con G18 | Solo sirve si hay costos en USD. Ver la pregunta 3 |
| G03 | **E** | — | Principio "sin capacitación": si una pantalla necesita manual, se rediseña |
| G04 | **E** | — | No se sabe qué hace y no hay evidencia de uso |
| G05 | **E** | — | El MVP imprime PDF desde el navegador. La impresora nunca debe bloquear |
| G06 | **E** | — | Problema propio de una base de archivos local; MySQL es transaccional |
| G07 | **E** | — | Licenciamiento de escritorio. El cobro va por fuera |
| G08 | **A** | CU-03, **impl.** | Ferreteria es más completo: separa código interno y código de barras |
| G09 | **A** | CU-03, **impl.** | Rubro, unidad, un proveedor y ubicación ya existen. Que GDS tenga **un solo proveedor por artículo** confirma el modelo actual |
| G10 | **D** | Agregar `marca` al artículo más adelante | Útil para buscar y para reportes, pero hoy la marca puede ir en la descripción |
| G11 | **A** | Rubros **impl.** (esqueleto), unidad como enum | Lo que no conviene copiar es el editor global abierto desde un botón del formulario (`R:287-288`) |
| G12 | **A** | CU-03 **impl.** (el request no trae stock) y CU-09 fase 6 | El mismo criterio que GDS: el stock solo cambia por movimientos |
| G13 | **A** | CU-03 **impl.** | Margen por artículo, 30 % por defecto (igual que `V2__articulos_y_precios.sql`) e IVA por artículo |
| G14 | **B** | **CU-04 y CU-03, fase 3. Ver la sección 4** | Es la evidencia más fuerte del relevamiento. En GDS el costo **siempre** es precio de lista menos hasta 3 bonificaciones |
| G15 | **C** | CU-07, fase 4: recargo % por medio de pago (crédito) configurable por ferretería, sin segunda lista | El precio de contado contra el de tarjeta es universal en Argentina. Como recargo no toca el modelo del artículo |
| G16 | **D** | Listas múltiples | La spec ya lo deja afuera ("mayorista y minorista"). GDS las tiene manuales, lo que suma carga de mantenimiento |
| G17 | **D** | Precio fijo manual por artículo (que la importación no pise) | Caso real (precio sugerido de marca), pero el MVP resuelve con el margen por artículo |
| G18 | **D** | `moneda` en el proveedor más cotización | Es aditivo si se guarda el precio de lista aparte (GDS-03). Ver la pregunta 3 |
| G19 | **D** | Fecha de última compra, derivada de los ingresos | Depende del ingreso con remito (GDS-14) |
| G20 | **D** | — | Promociones: nadie las pidió para el MVP |
| G21 | **E** | — | El mostrador no necesita foto y suma almacenamiento por tenant |
| G22 | **B** | CU-03 y CU-06: mostrar "costo actualizado el …" (sale del historial, sin tocar el esquema) | El dueño quiere saber qué precios están viejos, y GDS lo pone en el listado |
| G23 | **A** | CU-03 **impl.** (exige costo) | La spec es más estricta a propósito: el propio relevamiento advierte que GDS deja vender artículos incompletos (`R:198-206`) |
| G24 | **A** | CU-03 **impl.** (listado, búsqueda y baja lógica) | El "Borrar" físico de GDS se reemplaza por la baja lógica |
| G25 | **B** | Fase 6: exportar artículos a CSV y carga inicial reutilizando CU-04 | La fase 6 dice "carga inicial de datos del piloto" y la fase 0 pide "una exportación real de artículos" |
| G26 | **A** | CU-05, fase 3 (pendiente) | El mismo concepto. Que esté en el primer nivel del menú confirma que es una tarea frecuente |
| G27 | **A** | CU-09, fase 6 | — |
| G28 | **A** | CU-09, fase 6 (ajuste con motivo: rotura, conteo) | — |
| G29 | **B** | CU-09: pantalla "movimientos del artículo" | La spec registra los movimientos pero no dice dónde se consultan |
| G30 | **B** | Cliente (CU-07, fase 4/5): sumar email y WhatsApp/teléfono y validar el dígito del CUIT. Descartar nacimiento, rubro y web | CU-07 dice "envía el comprobante en PDF" y la pregunta abierta menciona WhatsApp |
| G31 | **D** | Junto con la cuenta corriente y las listas | Vendedor, zona y transporte son de distribuidora; límite, descuento y lista dependen de G16 y G59 |
| G32 | **D** | — | Barato, pero nadie lo pidió |
| G33 | **A** | Cliente en la spec | — |
| G34 | **B** | CU-09, fase 6: "ingreso de mercadería" con varios renglones y número de remito del proveedor | La spec lo trata como un ajuste suelto. Cargar un remito de 30 renglones de a uno no es práctico |
| G35 | **E** | — | Es un defecto de GDS: hay que consolidar |
| G36 | **D** | Con compras | Fuera de alcance ("Compras … y cuenta corriente con proveedores") |
| G37 | **D** | Con compras | Ídem |
| G38 | **D** | Con compras | Ídem |
| G39 | **D** | — | Excluida explícitamente en la spec |
| G40 | **D** | — | Ídem. Mientras tanto, el pago en efectivo a un proveedor lo cubre G65 como "gasto o retiro" |
| G41 | **A** | Proveedor CU-03 **impl.** (sin saldo) | — |
| G42 | **D** | — | Cheques y echeqs son comunes, pero arrastran cuentas corrientes |
| G43 | **D** | — | Ídem |
| G44 | **D** | — | Ídem |
| G45 | **D** | — | Ídem |
| G46 | **D** | — | Ídem |
| G47 | **A** | CU-07, fase 4/5 | La regla A/B/C ya está en la spec |
| G48 | **D** | Junto con G16 | — |
| G49 | **B** | CU-07: **no se puede vender a precio 0** (o solo el Dueño, con confirmación) | El relevamiento lo marca como riesgo (`R:341-343`, `R:628-629`) |
| G50 | **A** | CU-07 y reglas de precios | El precio final de ferreteria ya incluye IVA, y las alícuotas van por ítem |
| G51 | **C** | CU-07, fase 4: descuento % manual por renglón o total, con tope configurable para el Vendedor | El "le hago un 10 %" del mostrador es diario. No es "descuento por cliente" (eso está excluido) |
| G52 | **E** | — | Ferreteria ya redondea cada precio al múltiplo. Un ajuste en el total complica el cuadre de importes ante ARCA |
| G53 | **B** | CU-07 paso 4: "uno **o más** medios de pago" | El modelo ya tiene `Pago` 1—N, pero el CU habla de un solo medio. Pagar parte en efectivo y parte con débito es común |
| G54 | **D** | Junto con la cuenta corriente | Refinamiento de G59 |
| G55 | **A** | CU-07 alternativo ("remito interno") | — |
| G56 | **B** | CU-07: separar **presupuesto (cotización que no descuenta stock y se convierte en venta, el "Traer")** de la **venta sin factura** | La spec usa "presupuesto" para la venta en negro. En GDS son documentos distintos. Ver la pregunta 6 |
| G57 | **D** | Seña, N° de orden de compra y observaciones | "Traer" queda dentro de G56 |
| G58 | **A** | CU-08, fase 5 | — |
| G59 | **C** | Cuenta corriente de clientes y cobranzas (saldo, cobro en efectivo o transferencia) | GDS la integra en la factura misma, como pago mixto con cuenta corriente. La spec ya prevé meterla "si la respuesta es sí, mucho". Ver la pregunta 7 |
| G60 | **B** | CU-08, fase 5: consulta y **reimpresión** por número, fecha o cliente, **sin "Borrar"** | CU-08 dice "busca la factura" pero la pantalla no está definida. Un comprobante con CAE no se borra |
| G61 | **E** | — | El vendedor ya es el `Usuario`. Sueldo y domicilio son de RR.HH. |
| G62 | **D** | — | Las comisiones existen en ferreterías, pero no el primer día |
| G63 | **B** | Reporte del MVP: ventas por usuario | Es barato: el comprobante ya cuelga de la caja, y la caja del usuario |
| G64 | **A** | CU-10, fase 4 | — |
| G65 | **B** | CU-10: movimientos de caja manuales (**retiro, gasto, ingreso**) con motivo | Sin esto el arqueo nunca cierra: el dueño saca efectivo o le paga al flete con la caja |
| G66 | **B** | Reporte del MVP: agregar **ganancia**, **copiando el costo en `ItemComprobante`** | Hay que decidirlo antes de crear la tabla en la fase 4. Después el costo histórico se pierde |
| G67 | **A** | El MVP trae "ventas del día y de un rango de fechas" | — |
| G68 | **A** | CU-10 muestra el total por medio de pago | "Con descuento" iría con G51, ya en D |
| G69 | **D** | Por usuario, ver G63 | Reportes avanzados, fuera de alcance |
| G70 | **D** | — | Libro IVA, fuera de alcance. El contador baja los comprobantes de "Mis Comprobantes" de ARCA |
| G71 | **D** | — | CU-08 ya permite encontrar las NC |
| G72 | **D** | El stock crítico ya está en CU-09 (A). Stock valorizado y rankings, post-MVP. El listado con foto es E | Reportes avanzados |
| G73 | **B** | Junto con G65 | Viene gratis si existen los movimientos de caja |
| G74 | **D** | — | El ranking por artículo sirve para comprar, pero es post-MVP |
| G75 | **E** | — | Sin evidencia de uso. Es decoración |
| G76 | **D** | — | Cada uno depende de un módulo post-MVP |
| G77 | **A** | CU-02, fase 6 (roles fijos) | — |
| G78 | **E** | Configuración = CU-01 (A) | Mantenimiento y reparación son de escritorio y no aplican a un SaaS |
| G79 | **D** | Auditoría general | El MVP ya audita lo crítico: historial de precios, movimientos de stock y caja |
| G80 | **A** | CU-08 | Las NC se listan |
| G81 | **B** | CU-03 y CU-04: filtros "sin costo", "costo 0" y "margen negativo", y controles en la vista previa | Es el control preventivo que GDS hace después. Conviene hacerlo antes (ver la sección 4) |
| G82 | **E** | — | Ferreteria exige la alícuota (21 % por defecto). Un cambio masivo de alícuota podría ir en CU-05 si alguien lo pide |
| G83 | **E** | — | Por diseño, la venta y el movimiento de stock van en la misma transacción |
| G84 | **E** | — | Una acción destructiva global, inaceptable |

**Totales: 84 funciones.** A = 21 · B = 16 · C = 3 · D = 30 · E = 14.

- **A (21):** G08, G09, G11, G12, G13, G23, G24, G26, G27, G28, G33, G41, G47, G50, G55, G58, G64, G67, G68, G77, G80.
- **B (16):** G01, G14, G22, G25, G29, G30, G34, G49, G53, G56, G60, G63, G65, G66, G73, G81.
- **C (3):** G15, G51, G59.
- **D (30):** G02, G10, G16 a G20, G31, G32, G36 a G40, G42 a G46, G48, G54, G57, G62, G69 a G72, G74, G76, G79.
- **E (14):** G03 a G07, G21, G35, G52, G61, G75, G78, G82, G83, G84.

Dos funciones tienen partes en otra clase, y cuentan por su clase principal: G72 tiene el stock crítico en A (CU-09) y el listado con foto en E; G78 tiene la configuración en A (CU-01).

**Ya implementado** (esqueleto y CU-03): G08, G09, G11, G12, G13, G23, G24, G41. Ninguna función de GDS cae sobre lo que CU-04 ya resolvió, salvo las de la sección 4.

---

## 4. Impacto inmediato en CU-04 (importar lista de proveedor) — URGENTE

**Contexto:** GDS no muestra su importador. *Actualización de Precios* e *Importar/Exportar* no se abrieron (`R:115`, `R:189`). Los hallazgos salen del modelo de costo del artículo (f323, f121) y de la compra (`R:308-310`).

### 4.1 Bonificaciones en cascada — **CONTRADICE PARCIALMENTE la decisión "bonificaciones fuera de alcance"**

- **Evidencia:** en GDS el costo de un artículo **no se puede cargar directo**. El sistema lo bloquea con "El costo se calcula desde la solapa COSTO CALCULADO" (`R:228-229`, f121). Esa solapa tiene exactamente: *Precio de lista del Proveedor*, *Descuentos Aplicados `[ ]% + [ ]% + [ ]%`* y *El costo calculado es* (f323). La compra también usa ese costo: "precio de lista del proveedor menos hasta tres descuentos encadenados" (`R:308-310`).
- **Lectura:** el modelo mental de GDS (y de su mercado) es que el proveedor publica un **precio de lista** y la ferretería tiene **bonificaciones** (por ejemplo 40 + 10 + 5). Si CU-04 toma la columna "precio" del Excel como costo, en todos los proveedores que trabajan así **el costo queda inflado y el precio de venta también**. El error es silencioso y masivo: es justo lo que CU-04 tiene que evitar.
- **Límite de la evidencia:** en la prueba las tres casillas estaban vacías, así que el relevamiento no muestra con qué frecuencia se usan. Tampoco muestra si GDS las asigna por proveedor o por artículo: en la pantalla son **por artículo**.
- **Recomendación:** no hace falta revertir toda la decisión. Propongo una versión mínima: **hasta 3 bonificaciones % por proveedor**, guardadas junto al flag "lista incluye IVA" y al mapeo de columnas, que se aplican en la importación: `costo = precio_lista × (1 − b1) × (1 − b2) × (1 − b3)`. Esfuerzo S: tres columnas en `proveedor` y una multiplicación en el dominio, con sus tests. Las bonificaciones por artículo o por rubro dentro de un proveedor, como en GDS, quedan post-MVP. **Es la pregunta 1.**

### 4.2 IVA: el flag "lista incluye IVA" no puede dividir siempre por 1,21 — **AJUSTA la decisión**

- **Evidencia:** GDS maneja **IVA 21 % y 10,5 %** por artículo (f121, combo IVA; f413, renglones "IVA 21 %" e "IVA 10,5 %"). `AlicuotaIva` de ferreteria ya tiene alícuotas por artículo.
- **Impacto:** si la lista viene con IVA, sacarlo tiene que usar **la alícuota del artículo existente**. Para un artículo nuevo, la alícuota por defecto (21 %), y que se pueda corregir en la vista previa o en el alta en bloque. Dividir siempre por 1,21 deja mal el costo de todo lo que va al 10,5 %.
- **Orden de operaciones:** quitar el IVA y aplicar las bonificaciones son multiplicaciones, así que el orden no cambia el resultado. Conviene calcular con precisión completa y **redondear una sola vez** al final, a 2 decimales, igual que hace `PrecioCalculator`.

### 4.3 Guardar el precio de lista del proveedor aparte del costo — **recomendado, no contradice nada**

GDS conserva las dos cifras, precio de lista y costo calculado (f323). Guardar `precio_lista_proveedor` en el artículo, y si se quiere también en el historial, permite tres cosas:

1. Si cambia una bonificación, recalcular sin reimportar.
2. Comparar en la vista previa "lista anterior contra lista nueva" del mismo proveedor, sin el ruido de las bonificaciones.
3. Sumar después una moneda USD (G18) sin migración de datos.

Es una migración aditiva (`V3`, columna nullable). Esfuerzo S.

### 4.4 Cruce por código del proveedor — **CONFIRMA las decisiones**

GDS tiene **un solo** "Cód. Prov." y **un solo** proveedor por artículo (f121, `R:177-178`). Coincide con `uk_articulo_codigo_proveedor (ferreteria_id, proveedor_id, codigo_proveedor)`. No hay evidencia de que haga falta más de un proveedor por artículo.

### 4.5 Controles de calidad en la vista previa — **refina "filas con error se saltean y listan"**

GDS tiene, aparte, "Verificar costos en cero o mal cargados" y "Ventas con costo mayor a precio de venta" (f655, `R:590-594`). Es decir: los costos mal importados son un problema real. Conviene hacerlo **antes** de aplicar, dentro de la vista previa:

- Costo vacío, 0 o no numérico → **error**: la fila se saltea y se lista. Eso ya entra en la decisión tomada.
- Variación mayor a un umbral (por ejemplo ±50 %) → **advertencia**, no error. Atrapa el mapeo de una columna equivocada, unidades distintas (caja contra unidad) o una lista con o sin IVA mal marcada. Esfuerzo S.

### 4.6 Margen y redondeo — **sin contradicciones**

- La ganancia de GDS es por artículo, con 30 por defecto (f121). Coincide con el margen heredado y con el 30 % por defecto de ferreteria. La importación cambia el costo, nunca el margen.
- En GDS algunos precios son manuales (lista 3 y 4, "Lista 1 calculada" desmarcada; G17). En ferreteria todo precio es calculado, así que no existe el caso "costo nuevo > precio manual". Si más adelante aparecen precios fijos (D), la vista previa tiene que marcar el margen negativo.
- El redondeo al múltiplo de ferreteria no tiene equivalente en GDS, que trabaja con centavos y redondea el total en la factura (G52). La decisión actual se mantiene.

### 4.7 Moneda — **no previsto en CU-04; pregunta**

GDS tiene "Costo en dólares" por artículo y la cotización en pantalla todo el tiempo (G02, G18). Si algún proveedor del piloto manda la lista en USD, CU-04 necesita una moneda por proveedor y una cotización. Con el punto 4.3 queda preparado sin hacer nada más ahora. **Pregunta 3.**

### 4.8 Decisiones de CU-04 que el relevamiento no toca

"Faltantes solo se cuentan", "mapeo por índice" y "filas con error se saltean" no tienen ninguna evidencia en contra en GDS. La alta en bloque de artículos nuevos con el margen del rubro coincide con el alta mínima de GDS (G23), pero ferreteria exige costo, y lo trae la lista.

---

## 5. Impacto en lo ya implementado (CU-03)

| Tema | Qué muestra GDS | Recomendación para el modelo de CU-03 |
| --- | --- | --- |
| Precio de lista y bonificaciones | El costo sale de lista − 3 descuentos (G14) | **Cambiar ahora (fase 3):** `articulo.precio_lista_proveedor` nullable y `proveedor.bonificacion_1/2/3`. El costo sigue editable a mano (origen MANUAL) para artículos sin proveedor |
| Varias listas de precio | 4 listas, más una lista por defecto por cliente (G15, G16, G31) | **No cambiar el artículo.** Contado contra tarjeta se resuelve como recargo por medio de pago en CU-07 (C). Las listas mayoristas, post-MVP |
| Costo en USD | Por artículo, con cotización global (G18) | No cambiar ahora. Con `precio_lista_proveedor` guardado aparte, sumar `proveedor.moneda` después es aditivo |
| Varios proveedores por artículo | **No**: un proveedor y un código de proveedor (G09) | **Mantener** el modelo actual |
| Variantes de código de barras | No se ven. GDS ni siquiera separa el código interno del de barras (G08) | **Mantener** un solo `codigo_barras`. Ferreteria ya está mejor que GDS |
| Marca | Campo propio con reportes (G10) | Post-MVP, como columna nullable |
| Costo editable | Bloqueado en GDS | Ferreteria lo deja editar en CU-03. Está bien para el MVP; con bonificaciones, el costo de los artículos importados sale de la lista |
| Fecha de última actualización de precio | "F. Modif." en el listado (G22) | Exponerla en el listado y la búsqueda desde `historial_precio`, sin cambiar el esquema |
| Stock fuera del maestro | Igual (G12) | Ya está bien: `ArticuloRequest` ignora el stock |
| Costo en el ítem vendido (modelo futuro, no CU-03) | Hay reportes de ganancia (G66) | Agregar `costo_unitario` a `ItemComprobante` antes de crearlo en la fase 4 |

---

## 6. Propuesta de backlog

Ordenada por prioridad. Las de la fase 3 afectan a CU-04 y van primero.

| Id | Descripción | Clase | Fase | Esfuerzo |
| --- | --- | --- | --- | --- |
| GDS-01 | Bonificaciones en cascada (hasta 3 %) por proveedor, aplicadas al importar: `costo = lista × Π(1 − bᵢ)` | B | 3 (CU-04) | S |
| GDS-02 | "Lista incluye IVA" quita el IVA según la alícuota del artículo (21 % para los nuevos, editable en la vista previa) | B | 3 (CU-04) | S |
| GDS-03 | Guardar `precio_lista_proveedor` aparte del costo, con migración aditiva | B | 3 (CU-03/04) | S |
| GDS-04 | Vista previa: costo 0 o vacío es error; variación mayor a ±X % es advertencia | B | 3 (CU-04) | S |
| GDS-05 | Mostrar la fecha de la última actualización de costo en el listado y la búsqueda | B | 3 (CU-03) | S |
| GDS-06 | Movimientos manuales de caja: retiro, gasto e ingreso con motivo, incluidos en el arqueo | B | 4 (CU-10) | M |
| GDS-07 | Pago mixto: más de un medio por venta | B | 4 (CU-07) | S |
| GDS-08 | Copiar el costo en `ItemComprobante` y sumar ganancia y ventas por usuario al reporte del MVP | B | 4 | S |
| GDS-09 | Bloquear la venta de artículos con precio 0 | B | 4 (CU-07) | S |
| GDS-10 | Atajos de teclado fijos en el mostrador | B | 4 (CU-06/07) | S |
| GDS-11 | Recargo % por medio de pago (tarjeta de crédito), configurable | C | 4 (CU-07) | M |
| GDS-12 | Descuento manual % por renglón o total, con tope para el Vendedor | C | 4 (CU-07) | M |
| GDS-13 | Presupuesto como cotización (sin stock, con vencimiento) convertible en venta, separado de la venta sin factura | B | 4 (CU-07) | M |
| GDS-14 | Cliente con email y WhatsApp, y validación del dígito del CUIT | B | 4/5 (CU-07) | S |
| GDS-15 | Consulta y reimpresión de comprobantes (número, fecha, cliente), sin borrado | B | 5 (CU-08) | M |
| GDS-16 | Ingreso de mercadería por remito del proveedor (varios renglones y número) | B | 6 (CU-09) | M |
| GDS-17 | Consulta de movimientos de stock por artículo | B | 6 (CU-09) | S |
| GDS-18 | Listado de control: artículos sin costo, sin rubro, con margen negativo | B | 6 (CU-03) | S |
| GDS-19 | Exportar artículos a CSV y carga inicial reutilizando el pipeline de CU-04 | B | 6 | M |
| GDS-20 | Cuenta corriente de clientes y cobranzas (saldo y cobro), si la fase 0 lo confirma | C | 6 o post-MVP | L |
| GDS-21 | Moneda USD por proveedor y cotización con fecha | D | post-MVP | M |
| GDS-22 | Límite de crédito y lista o descuento por cliente | D | post-MVP | M |
| GDS-23 | Listas de precios múltiples (mayorista) | D | post-MVP | L |
| GDS-24 | Compras, cuenta corriente de proveedores, pagos y orden de pago | D | post-MVP | L |
| GDS-25 | Cheques y echeqs: cartera, depósito, rechazo y entrega | D | post-MVP | L |
| GDS-26 | Reportes avanzados: stock valorizado, ranking por artículo, ventas por rubro y marca, IVA ventas a Excel | D | post-MVP | M |
| GDS-27 | Campo marca, promoción y precio fijo manual | D | post-MVP | S |
| GDS-28 | Comisiones de vendedores | D | post-MVP | M |
| GDS-29 | Auditoría general por acción, usuario y fecha | D | post-MVP | M |
| GDS-30 | Seña en la venta | D | post-MVP | S |

---

## 7. Preguntas para Luca

1. **Bonificaciones (urgente para CU-04):** ¿las listas de los proveedores de Mar del Plata vienen como "precio de lista" con bonificaciones (por ejemplo 40 + 10)? ¿Metemos GDS-01, hasta 3 bonificaciones por **proveedor**, ya en CU-04, o se mantiene afuera y el dueño mapea una columna de neto?
2. **IVA mixto:** si la lista incluye IVA, ¿confirmás que se quite según la alícuota de cada artículo (GDS-02) y que los nuevos entren al 21 %, corregible en la vista previa?
3. **Dólares:** ¿algún proveedor de la ferretería piloto manda la lista en USD? Si dice que sí, GDS-21 sube a fase 3; si no, alcanza con GDS-03.
4. **Contado contra tarjeta:** ¿la ferretería piloto cobra distinto con tarjeta de crédito? Si cobra distinto, ¿alcanza con un recargo % por medio de pago (GDS-11)?
5. **Descuentos en el mostrador:** ¿el vendedor puede hacer un descuento manual? ¿Con tope, o solo el Dueño (GDS-12)?
6. **"Presupuesto":** en la spec es la venta sin factura; en GDS es una cotización que después se convierte en venta. ¿Cuál de los dos necesitan, o los dos (GDS-13)?
7. **Cuenta corriente:** ¿qué respondieron en la fase 0 a "¿venden a cuenta corriente?"? GDS la pone en la misma pantalla de factura, como pago mixto con cuenta corriente.
8. **Caja:** ¿sumamos a CU-10 los retiros y gastos de caja (GDS-06)? Sin ellos, la diferencia del arqueo no explica la plata que sale de la caja.

---

## Anexo: qué se puede reusar de `mostrador-gestion` (Next.js/Prisma)

- **Dominio puro con tests** (`src/domain/inventory.ts`, `cash.ts`, `quotes.ts`, `permissions.ts`): es el mismo enfoque que `PrecioCalculator`. Conviene repetirlo para la caja (arqueo con movimientos) y los presupuestos (estado y vencimiento, que en Mostrador se resuelve al consultar: EXPIRED).
- **`FiscalDocument`** con `idempotencyKey` único, `requestPayload` y `responsePayload` en JSON y `originalDocumentId` para las NC: es un buen molde para el `Comprobante` de la fase 5 (reintentos sin duplicar y bandeja de rechazados con el error tal cual).
- **`InventoryMovement`** con `referenceType` y `referenceId` (venta, compra, ajuste): sirve como patrón para `MovimientoStock`, más general que solo `comprobante_id`.
- **`AccountEntry`** con debe y haber, compartido por clientes y proveedores: una base simple para GDS-20 si la cuenta corriente entra.
- **`src/lib/csv.ts`** (escapa comillas, comas y saltos, CRLF): una referencia para GDS-19.
- **No reusar el importador** (`src/app/api/import/products/route.ts`): parte el CSV con `split(",")`, aborta todo ante la primera fila inválida, hace upsert por fila sin vista previa y usa `companyId` fijo. Es lo contrario de las decisiones de CU-04.
- **Lecciones de UX del relevamiento** que sirven igual: confirmar importes fuera de rango, no poner "Borrar" al lado de "Anular" y que la impresora nunca bloquee.
