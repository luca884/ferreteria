# Ferretería SaaS — Especificación del MVP

> Versión viva (editable): [Claude Doc](https://claude.ai/code/artifact/2f71e9a9-f655-41f6-85f2-29fdc96a94f7) · capturada el 2026-10-11

## Visión y alcance

Un sistema web multi-tenant para que una ferretería de Mar del Plata venda, facture electrónicamente con ARCA y mantenga sus precios al día, sin instalar nada. Cada ferretería es un tenant aislado: entra con su usuario y tiene todo ahí.

**Problema que resuelve:** actualizar miles de precios cuando llega la lista del proveedor, vender rápido en el mostrador y emitir la factura con CAE sin pelearse con ARCA.

**Principio rector: simple, práctico y que funcione.** Cada pantalla tiene que poder usarla un vendedor sin capacitación. Si una función no la pide una ferretería real, no entra.

**Criterios de éxito del MVP:**

- Una venta con factura B emitida en menos de 15 segundos desde que se escanea el primer artículo
- Una lista de precios de proveedor de 5.000 artículos importada y aplicada en menos de 2 minutos
- Una ferretería piloto usándolo a diario durante un mes sin volver a su sistema anterior

## Actores y roles

El MVP tiene tres roles fijos. Nada de permisos configurables por ahora.

| Rol | Quién es | Puede |
| --- | --- | --- |
| Super admin | Vos (el proveedor del servicio) | Dar de alta ferreterías, suspenderlas, ver estado general. No ve datos de ventas |
| Dueño | El dueño o encargado de la ferretería | Todo dentro de su ferretería: configuración, ARCA, usuarios, precios, reportes, anulaciones |
| Vendedor | Quien atiende el mostrador | Buscar artículos, vender, facturar, consultar stock, abrir y cerrar la caja |

**Actor externo:** ARCA (WSAA para autenticación, WSFEv1 para pedir el CAE).

## Casos de uso del MVP

Son 10 casos de uso, agrupados en cuatro módulos. Si algo no está en esta lista, no entra en el MVP.

### Configuración

**CU-01 Configurar la ferretería** (Dueño)

1. Carga razón social, CUIT, condición frente al IVA, domicilio e inicio de actividades.
2. Carga el punto de venta habilitado en ARCA para factura electrónica (web service).
3. Sube el certificado (.crt) y la clave privada, o genera el CSR desde el sistema.
4. El sistema prueba la conexión contra ARCA y muestra el último número de comprobante autorizado.
   - Alternativo: el certificado vence o es inválido → se avisa y se bloquea la facturación (la venta sin factura sigue habilitada).

**CU-02 Gestionar usuarios** (Dueño)

1. Crea, edita o desactiva usuarios con rol Dueño o Vendedor.

### Artículos y precios

**CU-03 Gestionar artículos** (Dueño)

1. Alta, edición y baja lógica de un artículo: código interno, código de barras, código del proveedor, descripción, rubro, proveedor, costo, margen, alícuota de IVA, unidad y stock mínimo.
2. El precio de venta se calcula solo: costo × (1 + margen) × (1 + alícuota de IVA) (ver Reglas de negocio).

**CU-04 Importar lista de precios del proveedor** (Dueño) — el caso más importante del sistema

1. Elige el proveedor y sube el archivo. Los proveedores mandan las listas en **Excel y/o PDF**: primero se soporta Excel/CSV; el PDF se extrae a tabla y pasa por la misma vista previa (ver nota abajo).
2. Mapea columnas una sola vez (código, descripción, costo); el mapeo queda guardado para ese proveedor.
3. El sistema cruza por código del proveedor y muestra una vista previa: cuántos suben, cuántos bajan, cuántos son nuevos, cuántos no están en la lista.
4. El dueño confirma y se actualizan los costos y precios en una sola transacción.
   - Alternativo: artículos nuevos → se pueden dar de alta en bloque con el margen por defecto del rubro.

> **Listas en PDF (2026-10-11):** varias listas llegan solo en PDF. La extracción de tablas de un PDF depende del formato de cada proveedor, así que se implementa después de Excel/CSV, con muestras reales, y siempre pasa por la vista previa antes de aplicar.

**CU-05 Actualizar precios masivamente** (Dueño)

1. Filtra por proveedor, rubro o búsqueda.
2. Aplica un porcentaje de aumento al costo o cambia el margen.
3. Ve la vista previa y confirma.

### Venta

**CU-06 Buscar artículo** (Vendedor)

1. Escanea el código de barras o escribe parte de la descripción o el código. La mayoría de las ferreterías ya tiene lector: funciona como teclado (escribe el código y manda Enter), así que el campo de búsqueda tiene que tener el foco siempre y reaccionar al Enter.
2. Ve precio, stock y ubicación al instante, sin hacer clic en nada.

**CU-07 Vender y facturar** (Vendedor)

1. Agrega artículos al ticket escaneando o buscando; ajusta cantidades.
   - Puede aplicar un **descuento en %** a un renglón (F7) o a todo el ticket (F6). Va de 0 a 99,99 %, con hasta 2 decimales; 0 lo saca. No hay tope por usuario por ahora. El descuento global se aplica a cada renglón (prorrateo), así el total es exactamente la suma de los renglones. El comprobante guarda el descuento de cada renglón, el global y el subtotal antes de descuentos; el que lo aplicó es el usuario de la venta.
2. Elige el cliente: Consumidor Final por defecto, o **F9** (botón «Cliente»): busca por razón social, CUIT o DNI entre los clientes activos, elige con ↑↓ y Enter, y puede dar de alta uno nuevo sin salir del mostrador (CU-11). El cliente se ve en el ticket con un «Quitar», se guarda con el ticket y viaja con la venta, el presupuesto y el remito; tras cobrar el ticket vuelve a quedar sin cliente. La venta guarda el id del cliente y una **copia de su razón social**: renombrarlo después no cambia lo ya emitido. En el presupuesto y el remito el diálogo muestra el cliente elegido en lugar del campo de texto libre, que sigue valiendo cuando no se eligió uno. «Pasar a venta» desde un presupuesto o un remito trae su cliente al ticket. Un cliente de otra ferretería es 404 y uno desactivado es 422; repetir la clave con otro cliente es `CLAVE_CON_OTRO_CONTENIDO`.
3. El sistema decide el comprobante (A, B o C) según la condición de IVA de la ferretería y del cliente.
4. Elige la **forma de pago** entre las que la ferretería tiene activas (por defecto Efectivo, Débito, Crédito y Transferencia; «Cuenta corriente» existe pero viene inactiva hasta que el Dueño la activa); o **pago mixto** (más de una forma): carga una parte y el resto se sugiere solo en la forma siguiente, y las partes tienen que sumar exactamente el total. Cada forma va una sola vez. El vuelto es solo del pago en efectivo único.
   - Cada forma tiene un **recargo en %** (0 a 200, 2 decimales; 0 por defecto) que el Dueño configura en Configuración → Formas de pago, donde también da de alta, edita, activa y desactiva (las formas no se borran). Cada una pertenece a un medio (efectivo, débito, crédito, transferencia o cuenta corriente), así la caja sigue sumando por medio. Siempre tiene que quedar al menos una forma activa de efectivo.
   - El recargo se calcula después de los descuentos, sobre la parte pagada con esa forma, y se reparte entre los renglones en proporción a su total (con su alícuota de IVA, porque integra la base del IVA): el total sigue siendo la suma de los renglones. El diálogo de cobro muestra el recargo y el total a cobrar. En el pago mixto la parte que se carga es la del ticket sin recargo.
   - El comprobante y cada renglón guardan el recargo; cada pago guarda la forma, su nombre y % del momento, el recargo y lo cobrado. Cambiar el % después no toca las ventas hechas.
   - **Cuenta corriente** (GDS-20, tramo 2): la forma «Cuenta corriente» (medio `CUENTA_CORRIENTE`) vende a crédito y suma deuda al cliente. Todas las ferreterías con formas la reciben **inactiva** y con recargo 0 (V20); las nuevas también (la quinta forma, inactiva). El Dueño la activa en Configuración → Formas de pago, y el recargo de esa forma queda fijo en 0 (el servidor lo fuerza, la pantalla lo deshabilita). Puede ser forma única o una parte del pago mixto: en el mixto solo la parte a cuenta suma deuda.
   - **El cliente es obligatorio:** pagar a cuenta corriente sin `clienteId` es 400 en `clienteId` («Elegí el cliente para vender a cuenta corriente.»); uno de otra ferretería es 404 y uno desactivado, 422. El cliente tiene que tener la cuenta habilitada (CU-11): con el límite en NULL la venta es 422 `CUENTA_CORRIENTE_NO_HABILITADA` («<razón social> no tiene cuenta corriente habilitada.»), también para el Dueño.
   - **Límite y exceso:** la venta excede si el saldo más lo que va a cuenta supera el límite (con límite 0 cualquier venta a cuenta excede). Un exceso es 422 `LIMITE_CUENTA_CORRIENTE`, con el objeto `cuentaCorriente { puedeAutorizar }` y, solo si lo ve el Dueño, `saldo`, `limite`, `disponible`, `importe` y `exceso`. Lo **autoriza solo el Dueño logueado**: reenvía la venta con `autorizaExceso: true`. Al Vendedor sin la marca se le rechaza con «Pedile al Dueño que la autorice.» y sin ninguna cifra; si un Vendedor manda `autorizaExceso: true` y la venta excede, es 403 («Autorizar el exceso es solo del Dueño») y no se guarda nada. Si la venta no excede, la marca se ignora. No hay PIN de supervisor. `autorizaExceso` no es parte del contenido de la venta: repetir la clave con otra marca no cambia nada.
   - **Qué queda guardado:** por cada pago a cuenta corriente, un movimiento `VENTA` en el libro del cliente con el debe igual al monto del pago, el comprobante, el pago, el usuario y la fecha; si hubo exceso, `excedio_limite` y quién lo autorizó. Se asienta en la misma transacción que la venta: si algo falla no queda movimiento ni cambia el saldo, y reenviar la misma clave devuelve la venta original (200) sin un segundo movimiento. Una **venta no facturable** a cuenta corriente suma deuda igual que una facturable. Los rechazos de cuenta corriente no consumen número, ni stock, ni caja.
   - **Diálogo de cobro:** con el cliente del ticket pide `GET /api/clientes/{id}/cuenta-corriente?importe=` (el importe es el total del ticket sin recargo) y la opción «Cuenta corriente» queda **deshabilitada con la razón a la vista** si falta el cliente (F9), la cuenta no está habilitada, no alcanza el límite (Vendedor) o no se pudo verificar. El Dueño ve el saldo, el límite y el disponible; ante un exceso la opción sigue elegible, avisa cuánto lo supera y el botón pasa a «Autorizar exceso y cobrar». El Vendedor ve solo «Alcanza» o «No alcanza el límite… Pedile al Dueño». La cuenta corriente nunca es la forma inicial (Efectivo sigue siendo F4 + Enter). El pre-chequeo vale para la forma única; en el pago mixto decide el servidor, y si rechaza por límite el diálogo se queda abierto con el aviso y, para el Dueño, la autorización. El aviso tras cobrar dice «cobrada a cuenta corriente».
   - **El saldo solo sube en este tramo:** no hay cobranza ni nota de crédito que lo baje (tramo 3), así que el Dueño puede bajar o quitar el límite para frenar ventas nuevas.
5. Confirma: se pide el CAE a ARCA, se descuenta el stock y se imprime o envía el comprobante en PDF.
   - Alternativo: ARCA no responde → la venta queda guardada como "pendiente de CAE" y se reintenta sola.
   - Alternativo: **venta no facturable** → en el diálogo de cobro, la casilla «No facturable» (arranca destildada en cada cobro, se llega con Tab o clic) deja la venta con estado `NO_FACTURABLE` en vez de `SIN_FACTURAR`. Cobra, entra en la caja, descuenta el stock y usa la misma numeración que las demás ventas; lo único que cambia es que **la fase 5 no la factura nunca** (no entra en la cola de pendientes de CAE ni en el libro IVA) y el estado no cambia después. El pedido (`POST /api/ventas`) manda `facturable: false`; sin el campo es `true`. La venta responde `facturable` y el aviso del mostrador dice «cobrada (no facturable)». La marca es parte del contenido de la venta: reenviar la misma clave con otra marca es `CLAVE_CON_OTRO_CONTENIDO`. La puede usar quien vende (Vendedor y Dueño); una venta que sale de un presupuesto o de un remito también puede marcarse. (El presupuesto y el remito de abajo son otros documentos.)
   - Alternativo: **presupuesto** (F8): el ticket se guarda como una cotización en vez de cobrarse. No pide caja abierta, forma de pago ni CAE, y no toca el stock (se puede presupuestar sin stock suficiente). El diálogo pide el cliente (texto libre, opcional), la validez en días (15 por defecto, de 1 a 90) y observaciones (opcionales). Lleva su numeración propia por ferretería, los descuentos del ticket y el total sin recargo (el recargo depende de la forma de pago, que se elige al cobrar). Se puede guardar con los mismos precios que vio el cajero: si un precio cambió, se avisa como en el cobro.
   - El presupuesto tiene **estados**: pendiente al emitirlo, aceptado y rechazado (se cambian a mano, en cualquier orden) y facturado, que solo lo pone la venta hecha desde él. «Vencido» no es un estado: es un pendiente o aceptado cuyo último día de validez ya pasó, y solo avisa.
   - Vendedor y Dueño ven los presupuestos de su ferretería en `Presupuestos` (lista con filtros por estado, número y fecha, y detalle con renglones). **Pasar a venta** deja en el mostrador un ticket con las cantidades y los descuentos del presupuesto pero con los **precios de hoy** (el presupuesto no fija precios; se puede cobrar aunque haya vencido). Un artículo que ya no se puede vender (borrado, dado de baja o sin precio) queda marcado en el ticket para que el cajero lo quite. Si el mostrador ya tenía un ticket con renglones, se pregunta antes de reemplazarlo.
   - Al cobrar, la venta lleva el presupuesto de origen y en la misma transacción lo deja facturado; un presupuesto rechazado o ya facturado no se puede cobrar (409) y uno de otra ferretería es un 404. Si la venta falla, el presupuesto no cambia. Si el cajero vacía el ticket, el presupuesto sigue como estaba. Como el presupuesto nunca descontó stock, no hay doble descuento.
   - Alternativo: **remito** (botón «Remito» de la botonera, sin tecla): el ticket sale como un documento de **entrega**, como el F5 de GDS pero con efecto fijo (sin interruptores globales, GDS-35). No cobra: no pide caja abierta, forma de pago ni CAE y no lleva recargo. **Descuenta el stock al emitirlo** y con stock insuficiente emite igual y avisa qué artículos quedaron en negativo. El diálogo pide el cliente (texto libre, opcional) y observaciones (opcionales, sirven para la dirección de entrega o la orden de compra). Lleva su numeración propia por ferretería, los descuentos del ticket y el total sin recargo, con la misma validación de precios que el cobro.
   - El remito tiene tres **estados**: emitido, facturado (lo pone solo la venta hecha desde él) y anulado. Facturado y anulado son finales.
   - Vendedor y Dueño ven los remitos de su ferretería en `Remitos` (lista con filtros por estado, número y fecha, y detalle con renglones). **Pasar a venta** deja en el mostrador un ticket con las cantidades y los descuentos del remito pero con los **precios de hoy**, igual que el presupuesto. Al cobrar, la venta lleva el remito de origen y en la misma transacción lo deja facturado; uno anulado o ya facturado no se puede cobrar (409) y uno de otra ferretería es un 404. Una venta sale de un presupuesto o de un remito, no de los dos.
   - **Sin doble descuento de stock:** como el remito ya descontó lo que entregó, la venta que sale de él mueve solo la **diferencia** por artículo (vendido menos entregado). Si el cajero cambió las cantidades, sale lo que se vendió de más y vuelve lo que sobra; un artículo que el remito no tenía sale entero y uno que no se vendió vuelve entero. Cada diferencia queda como movimiento de la venta, con el motivo «Diferencia con el remito N.º X». El aviso de sin stock mira solo lo que sale en la venta.
   - **Anular un remito** es solo del Dueño y pide confirmación: devuelve al stock lo que salió y deja un movimiento de anulación por artículo. Un remito ya facturado no se anula (la devolución va por nota de crédito, CU-08). Sin PDF ni impresión hasta la fase 5.

**CU-08 Anular una venta** (Dueño)

1. Busca la factura y emite la nota de crédito asociada (con su propio CAE).
2. El stock se repone automáticamente.

### Stock y caja

**CU-09 Consultar y ajustar stock** (Dueño)

1. Ve el stock actual y los artículos bajo el mínimo.
2. Hace un ajuste manual (ingreso de mercadería, rotura, conteo) con un motivo; queda registrado el movimiento.

**CU-10 Abrir y cerrar caja** (Vendedor y Dueño)

1. Hay **una sola caja abierta por ferretería**, no una por usuario: cualquier usuario (Dueño o Vendedor) vende sobre la caja abierta. Abrir con una ya abierta se rechaza («Ya hay una caja abierta.»).
2. Abre la caja con el efectivo inicial (cero o más). El sistema propone el efectivo contado del último cierre (o $ 0 si nunca hubo); si se abre con otro monto, queda registrada la diferencia de apertura (inicial − contado anterior) y quién abrió.
3. **Sin caja abierta no se vende** («Abrí la caja para vender.»). Cada venta queda asociada a la caja abierta. Si la caja abierta es de un día anterior, no se vende ni se cargan movimientos hasta cerrarla y abrir una nueva («La caja del dd/MM sigue abierta. Cerrala antes de vender.»); cerrarla sí se puede.
4. Durante el día se cargan movimientos de caja con importe, motivo, usuario y hora: ingresos varios y gastos varios (Dueño y Vendedor) y retiros (solo el Dueño).
5. **Cierre ciego:** el que cierra (Dueño o Vendedor) carga solo el efectivo contado. Recién entonces el sistema muestra el total por medio de pago (efectivo, débito, crédito, transferencia y, como renglón **informativo**, «Ventas en cuenta corriente»), los ingresos, gastos y retiros, el efectivo esperado (inicial + ventas en efectivo + ingresos − gastos − retiros) y la diferencia (contado − esperado). Lo vendido a cuenta corriente entra en el total de ventas pero **no** en el efectivo esperado: no entró plata a la caja. Mientras la caja está abierta el Vendedor no ve lo esperado ni los totales; el Dueño sí ve la caja del día en vivo.
6. Una caja cerrada es inmutable: no se reabre, no se edita y no admite movimientos ni ventas.
7. El Dueño ve el historial de cajas cerradas (más nuevas primero) y el detalle de cada una: totales, movimientos y quién abrió y cerró.

### Clientes

**CU-11 Clientes** (Vendedor y Dueño)

1. En `Clientes` (menú lateral) ve la lista, ordenada por razón social y con páginas de 20, y busca por razón social (contiene, sin distinguir mayúsculas ni tildes) o por documento (empieza con los dígitos, con o sin guiones y puntos). «Solo activos» oculta los desactivados.
2. Da de alta y edita un cliente con: **razón social** (obligatoria, hasta 120), **documento** (DNI, CUIT o CUIL, opcional), condición de IVA (Consumidor final por defecto, Responsable inscripto, Monotributista o Exento), domicilio, teléfono, WhatsApp y correo. No lleva fecha de nacimiento, rubro ni web.
   - El documento va **completo o no va**: tipo y número juntos. Se guarda solo con dígitos. El DNI tiene 7 u 8 dígitos; el CUIT y el CUIL, 11, con prefijo válido (20, 23, 24, 27, 30, 33 o 34) y dígito verificador módulo 11. Si la cuenta del verificador da 10, no existe verificador válido y ese número se rechaza.
   - El documento es **único por ferretería** entre los cargados, aunque el otro cliente esté desactivado (409). Dos clientes sin documento conviven; otra ferretería puede tener el mismo documento.
3. El **Dueño** desactiva y reactiva (el Vendedor no ve el botón). Los clientes no se borran: se desactivan, y uno desactivado deja de ofrecerse en F9 pero sus ventas y documentos se conservan.
   - **Un cliente con saldo distinto de cero no se desactiva**, ni con deuda ni con saldo a favor: es 422 («No se puede desactivar: el cliente tiene saldo en cuenta corriente.»), y la pantalla deja el botón apagado con la razón a la vista. Con saldo 0 se desactiva y reactivar siempre se puede. La verificación toma al cliente con lock, así que una venta a cuenta corriente en paralelo o entra antes (y la desactivación se rechaza) o ve al cliente desactivado (y la venta se rechaza); nunca pasan las dos.
4. Consumidor Final no es un cliente de la tabla: es una venta «sin cliente».
5. Un cliente de otra ferretería es 404, igual que uno que no existe. Todo se resuelve con la ferretería del JWT.
6. **Cuenta corriente** (GDS-20, tramo 2): cada cliente lleva un **límite** y un **saldo**. El límite vacío (NULL) es «sin cuenta corriente habilitada»; 0 es «habilitada, sin crédito»; no hay «sin tope». El saldo es lo que el cliente debe (positivo) o lo que tiene a favor (negativo); en este tramo solo lo mueven las ventas.
   - **El límite lo pone solo el Dueño**, por un endpoint propio: `PUT /api/clientes/{id}/cuenta-corriente/limite` con `{ "limite": número }` (0 a 9.999.999.999.999,99; con 2 decimales) o `{ "limite": null }` para deshabilitar. Sin la clave `limite` es 400 y no cambia nada. El Vendedor recibe 403. No va en el alta ni en la edición del cliente (`PUT`/`POST /api/clientes` lo ignoran), porque esa edición la usa también el Vendedor y reemplaza todo el cliente. Bajar el límite por debajo del saldo o deshabilitar con saldo está permitido: solo frena las ventas nuevas.
   - **Saldo y límite los ve solo el Dueño.** `ClienteResponse` (lista, detalle, alta, edición y cambio de estado) trae `cuentaCorrienteHabilitada` para todos y `limiteCuentaCorriente` y `saldo` solo para el Dueño (al Vendedor se le omiten, no se enmascaran). `GET /api/clientes/{id}/cuenta-corriente?importe=` responde `habilitada` y, si vino `importe`, `alcanza`; el Dueño recibe además `limite`, `saldo` y `disponible`. El Vendedor solo sabe si **alcanza** o no, sin cifras.
   - **Pantalla Clientes:** el Dueño ve las columnas Saldo (con «a favor» si es negativo) y Límite («Sin habilitar» si no hay) y edita la sección «Cuenta corriente» (casilla «Habilitar» y límite; 0 permitido), tanto en el alta como en la edición; guardar son dos llamadas, el cliente y después el límite (si falla la segunda, el cliente queda guardado y se avisa). El Vendedor no ve la sección. **F9** muestra «Cta. cte.» a todos los roles y «Saldo $ X · Límite $ Y» solo al Dueño.
   - Un saldo se puede mirar pero no corregir a mano: el libro (ver Modelo de datos) solo recibe movimientos de las ventas. El extracto de movimientos por pantalla o API, las cobranzas y la nota de crédito quedan para el tramo 3.

## Reglas de negocio

Estas reglas se codifican en el dominio y se cubren con tests unitarios antes que cualquier pantalla.

### Precios

- El costo del proveedor se guarda **sin IVA**. Precio neto = costo × (1 + margen). Precio final = neto × (1 + alícuota de IVA).
- El margen se define por artículo; si no tiene, hereda el del rubro; si no, el margen por defecto de la ferretería.
- Sin redondeo por ahora: el precio final queda con centavos (escala 2, hacia arriba al centavo). Si las ferreterías lo piden, se evaluará redondear hacia arriba al peso entero. El código del múltiplo configurable sigue, con múltiplo 0 (sin redondeo) en todas las ferreterías (H11, migración V9).
- Todo cambio de costo o precio guarda historial: fecha, valor anterior, valor nuevo y origen (importación, masivo, manual).
- Montos en `BigDecimal` (Java) y `DECIMAL(15,2)` (base). Nunca `double`.

### Comprobantes

| Emisor (la ferretería) | Cliente | Comprobante |
| --- | --- | --- |
| Responsable Inscripto | Responsable Inscripto | Factura A |
| Responsable Inscripto | Consumidor Final, Monotributista o Exento | Factura B |
| Monotributista | Cualquiera | Factura C |

- La factura B a consumidor final informa el IVA contenido (Régimen de Transparencia Fiscal). Validar el formato vigente con un contador.
- Por encima del monto que fija ARCA, la venta a consumidor final exige identificar al cliente con DNI. El monto queda como parámetro configurable, no en el código.
- La numeración la da ARCA: antes de emitir se consulta el último comprobante autorizado por punto de venta y tipo.
- Un comprobante con CAE es inmutable. Solo se corrige con nota de crédito.

### Stock

- Toda variación de stock es un movimiento (venta, anulación, ajuste, ingreso). El stock actual es la suma de movimientos, cacheada en el artículo.
- Se permite stock negativo con advertencia: en el mostrador la venta no se frena por un error de carga.
- Las cantidades admiten decimales (cable por metro, clavos por kilo).

### Cuenta corriente

- El saldo del cliente es un caché del libro: **saldo = Σ debe − Σ haber** de sus movimientos, siempre. Positivo es deuda y negativo, saldo a favor. El libro es solo de alta (no se edita ni se borra) y un único servicio escribe el saldo y el libro.
- Una venta excede el límite si saldo + lo vendido a cuenta > límite. El límite NULL no habilita la cuenta y no se autoriza ni siquiera con el Dueño; el exceso sí lo autoriza el Dueño (CU-07).
- **Concurrencia:** el chequeo del límite y el asiento se hacen con el cliente tomado con lock (`SELECT … FOR UPDATE` nativo, que lee el saldo vigente y no la foto de la transacción) y el saldo se escribe con un UPDATE relativo. Toda venta con cliente, sea o no a cuenta corriente, toma al cliente en el mismo lugar del orden de locks: caja → presupuesto → remito → **cliente** → numerador → comprobante → artículos → hijos. Es lo que evita el deadlock con la FK al insertar el comprobante.
- La caja suma lo vendido a cuenta como renglón informativo: entra en el total de ventas y no en el efectivo esperado.

## Modelo de datos

Una sola base compartida y todas las tablas de negocio con `tenant_id`. Es el mismo esquema que en Sendo: la ferretería sale del JWT (`TenantFilter` → `TenantContext`), los repositorios extienden `TenantScopedRepository` y filtran siempre por `ferreteria_id`, y un test de ArchUnit prohíbe los finders sin scope.

| Entidad | Atributos clave | Relaciones |
| --- | --- | --- |
| Tenant | razón social, CUIT, condición IVA, domicilio, inicio de actividades, estado (activo/suspendido) | 1—N con todo lo demás |
| ConfigArca | punto de venta, certificado y clave (cifrados), vencimiento del certificado, ambiente (homologación/producción) | 1—1 con Tenant |
| Usuario | email, hash de contraseña, nombre, rol, activo | N—1 Tenant |
| Rubro | nombre, margen por defecto | 1—N Artículo |
| Proveedor | nombre, CUIT, mapeo de columnas de su lista (JSON) | 1—N Artículo, 1—N ImportacionLista |
| Articulo | código interno, código de barras, código proveedor, descripción, costo, margen, alícuota IVA, precio final, unidad, stock actual, stock mínimo, ubicación, activo | N—1 Rubro, N—1 Proveedor |
| HistorialPrecio | costo y precio anterior y nuevo, origen, fecha, usuario | N—1 Articulo |
| ImportacionLista | archivo, fecha, cantidades (suben, bajan, nuevos), estado | N—1 Proveedor |
| MovimientoStock | tipo (venta, ajuste, remito, anulación de remito), cantidad (decimal, con signo), motivo, fecha, usuario | N—1 Articulo, N—1 Comprobante (opcional), N—1 Remito (opcional) |
| Cliente | razón social, tipo y número de documento (opcionales y siempre juntos, único por ferretería), condición IVA, domicilio, teléfono, WhatsApp, correo, activo, límite de cuenta corriente (`limite_cuenta_corriente`, NULL = sin cuenta corriente, 0 = sin crédito, nunca negativo), saldo (`saldo`, 0 por defecto; positivo = debe, negativo = a favor; caché del libro) | N—1 Ferretería, 1—N Comprobante, 1—N Presupuesto, 1—N Remito, 1—N MovimientoCuentaCliente |
| Comprobante | tipo (FA, FB, FC, NC), punto de venta, número, fecha, neto, IVA, total, subtotal (antes de descuentos), descuento global %, recargo, cliente (opcional, con copia de su razón social), presupuesto o remito de origen (opcional, nunca los dos), CAE, vencimiento del CAE, estado (pendiente, autorizado, rechazado) | N—1 Cliente, N—1 Caja, 1—N Item, 1—N Pago |
| ItemComprobante | descripción, cantidad, precio unitario, alícuota IVA, bruto, descuento % del renglón, recargo, subtotal (copiados al momento de la venta) | N—1 Comprobante, N—1 Articulo |
| Presupuesto | número propio por ferretería, fecha, vencimiento, estado (pendiente, aceptado, rechazado, facturado), cliente (texto libre, opcional), observaciones, neto, IVA, total, subtotal (antes de descuentos), descuento global %, usuario que lo emitió | N—1 Ferretería, N—1 Usuario, 1—N ItemPresupuesto, 0—1 Comprobante (la venta que lo facturó) |
| ItemPresupuesto | descripción, unidad, cantidad, precio unitario, alícuota IVA, bruto, descuento % del renglón, subtotal (copiados al emitirlo) | N—1 Presupuesto, N—1 Articulo |
| Remito | número propio por ferretería, fecha, estado (emitido, facturado, anulado), cliente (texto libre, opcional), observaciones, neto, IVA, total, subtotal (antes de descuentos), descuento global %, usuario que lo emitió | N—1 Ferretería, N—1 Usuario, 1—N ItemRemito, 1—N MovimientoStock, 0—1 Comprobante (la venta que lo facturó) |
| ItemRemito | descripción, unidad, cantidad, precio unitario, alícuota IVA, bruto, descuento % del renglón, subtotal (copiados al emitirlo) | N—1 Remito, N—1 Articulo |
| FormaPago | nombre (único por ferretería), medio (efectivo, débito, crédito, transferencia, cuenta corriente), recargo % (siempre 0 si el medio es cuenta corriente), activa (la forma «Cuenta corriente» se siembra inactiva), orden | N—1 Ferretería, 1—N Pago |
| Pago | forma de pago (con nombre y recargo % copiados al momento), medio (puede ser cuenta corriente), recargo, monto cobrado (parte + recargo) | N—1 Comprobante, N—1 FormaPago, 0—1 MovimientoCuentaCliente (si el medio es cuenta corriente) |
| Caja | apertura (fecha y usuario que abre), cierre (fecha y usuario que cierra), efectivo inicial, efectivo contado, efectivo esperado, diferencia (contado − esperado), diferencia de apertura (inicial − contado del cierre anterior), abierta (una sola por ferretería) | N—1 Ferretería, N—1 Usuario (abre), N—1 Usuario (cierra), 1—N Comprobante, 1—N MovimientoCaja |
| MovimientoCaja | tipo (ingreso, gasto, retiro), importe, motivo, fecha, usuario | N—1 Caja, N—1 Usuario |
| MovimientoCuentaCliente | tipo (venta, cobro, ajuste, nota de crédito; en este tramo solo se escribe venta), debe y haber (≥ 0, uno solo de los dos mayor que 0), fecha (la fija el reloj del servidor), usuario, excedió el límite y quién lo autorizó (juntos o ninguno) | N—1 Ferretería, N—1 Cliente, N—1 Usuario, N—1 Usuario (autoriza, opcional), N—1 Comprobante y N—1 Pago (obligatorios en la venta; un pago no se asienta dos veces) |

**Decisiones:**

- `ItemComprobante` copia descripción y precio: si mañana cambia el artículo, la factura vieja no cambia.
- Índices compuestos que empiezan por `tenant_id`: (`tenant_id`, `codigo_barras`), (`tenant_id`, `codigo_proveedor`, `proveedor_id`) y un índice full-text sobre la descripción para la búsqueda del mostrador.
- Migraciones con Flyway desde el día uno.
- La cuenta corriente es un **libro de debe y haber** (`movimiento_cuenta_cliente`, V20) con un saldo cacheado en el cliente: el saldo es siempre la suma del libro, y la base lo cuida con CHECKs (un solo lado, el sentido según el tipo, referencias de la venta, exceso con autorizador) y una restricción única por (pago, tipo). Hay un movimiento por pago a cuenta corriente, no por venta, para que el tramo 3 pueda imputar cobros a cada comprobante sin tocar el libro. No se borra nada: sin `ON DELETE`.

## Integración ARCA

La facturación usa dos servicios SOAP de ARCA: WSAA para obtener el token y WSFEv1 para pedir el CAE. Todo se desarrolla primero contra homologación y se pasa a producción por tenant con un flag.

**Flujo de una factura:**

1. Se arma el TRA (LoginTicketRequest), se firma en CMS/PKCS#7 con el certificado (BouncyCastle) y se llama a `loginCms` del WSAA. Devuelve token y sign, válidos unas 12 horas.
2. El token se cachea por tenant y por servicio hasta que vence. No se pide uno por factura: ARCA rechaza pedidos repetidos con un token vigente.
3. `FECompUltimoAutorizado` devuelve el último número para ese punto de venta y tipo; el nuevo es ese + 1.
4. `FECAESolicitar` con cabecera, importes, alícuotas de IVA y datos del cliente. Devuelve CAE y vencimiento, o las observaciones y errores.
5. Se guarda el CAE, se genera el PDF con el código QR obligatorio y se marca el comprobante como autorizado.

**Cómo se autentica cada ferretería:** hay dos modelos, y hay que elegir uno antes de codear.

| Modelo | Cómo funciona | A favor | En contra |
| --- | --- | --- | --- |
| Certificado propio por tenant | Cada ferretería genera su certificado con clave fiscal y lo sube al sistema | Cada CUIT es independiente | Onboarding más difícil; hay que guardar N claves privadas y vigilar N vencimientos |
| Delegación al CUIT del servicio | Vos tenés un solo certificado; cada ferretería delega el servicio de factura electrónica a tu CUIT desde ARCA, y en cada llamada se informa el CUIT representado | Una sola clave que custodiar; el alta es un trámite de 5 minutos | Necesitás CUIT propio y todo depende de un solo certificado |

**Seguridad:** claves privadas cifradas en la base con AES-GCM, con la clave maestra en una variable de entorno y nunca en el repo. Aviso al dueño 30 días antes del vencimiento del certificado.

**Contingencias:**

- ARCA caída o con timeout → el comprobante queda "pendiente de CAE", el cliente se lleva un ticket provisorio y un job reintenta cada pocos minutos.
- ARCA rechaza el comprobante → se guarda el error tal cual lo devuelve ARCA y se le muestra al dueño en una bandeja de rechazados.
- Antes de reintentar, se consulta si el número ya fue autorizado (`FECompConsultar`) para no duplicar facturas.
- El régimen CAEA (CAE anticipado para emitir offline) queda para después del MVP.

## Arquitectura y stack

Es un monolito modular con el mismo stack que Sendo. Sin microservicios: una ferretería no necesita escalar módulos por separado, y un solo deploy es más fácil de mantener para una persona.

```mermaid
flowchart TB
    FE["Frontend Angular (SPA)<br/>Mostrador y panel del dueño · Vercel o Cloudflare Pages"]
    subgraph BE["Backend Spring Boot · monolito modular en Railway"]
        AUTH["Auth y tenants<br/>Login, JWT, roles, filtro tenant_id"]
        CAT["Catálogo<br/>Artículos, rubros, proveedores, listas"]
        VEN["Ventas<br/>Ticket, comprobantes, pagos, caja"]
        STK["Stock<br/>Movimientos, ajustes, mínimos"]
        FAC["Facturación<br/>Cliente WSAA/WSFEv1, reintentos de CAE"]
        DOC["Documentos<br/>PDF con QR"]
    end
    DB[("MySQL en Railway<br/>base compartida · Flyway")]
    ARCA["ARCA<br/>WSAA (token) y WSFEv1 (CAE)"]
    FE -- "HTTPS + JWT con el tenant" --> BE
    BE -- "JPA + filtro de tenant" --> DB
    FAC -- "SOAP, solo desde este módulo" --> ARCA
```

Cada módulo es un paquete con su dominio, servicios y controladores. ArchUnit impide que un módulo use las clases internas de otro, y solo Facturación conoce a ARCA.

| Capa | Tecnología |
| --- | --- |
| Backend | Java, Spring Boot, Spring Security (JWT), Hibernate/JPA, Maven |
| Base de datos | MySQL, Flyway |
| Frontend | Angular, TypeScript, RxJS |
| ARCA | Cliente SOAP generado del WSDL, BouncyCastle para firmar el TRA |
| PDF | Generación en el backend (por ejemplo OpenPDF) con el QR de ARCA |
| Tests | JUnit 5, Mockito, Testcontainers, ArchUnit, Karma/Jasmine |
| Infra | Docker, Railway (backend y base), Vercel o Cloudflare Pages (frontend) |

## Fuera del alcance del MVP

Nada de esto entra hasta que haya una ferretería piloto usando el sistema todos los días. Cada ítem vuelve a la lista solo si un cliente real lo pide.

- Cobranzas de la cuenta corriente de clientes: el cobro con imputación a cada comprobante, el recibo y el extracto de movimientos (el tramo 3 de GDS-20). La venta a cuenta corriente, con su límite y su saldo, ya está hecha (CU-07 y CU-11)
- Nota de crédito sobre una venta a cuenta corriente (hasta que exista, el saldo no baja) y los ajustes manuales del saldo
- PIN de supervisor para autorizar un exceso (hoy lo autoriza solo el Dueño logueado), recargo por financiación de la cuenta corriente y límite por usuario
- Compras, órdenes de compra y cuenta corriente con proveedores
- Múltiples sucursales o depósitos por ferretería
- Listas de precios múltiples (mayorista y minorista) y descuentos por cliente
- Libro IVA Ventas e integración con el contador
- Reportes avanzados y tableros (el MVP solo trae ventas del día y de un rango de fechas)
- CAEA y modo offline
- Impresora fiscal o de tickets térmica con drivers propios (el MVP imprime PDF desde el navegador)
- Tienda online, catálogo público o integración con Mercado Libre
- Cobro de la suscripción dentro del sistema (al principio se cobra por fuera)

## Plan de implementación

Son siete fases (de la 0 a la 6) en orden de riesgo y de dolor del cliente. La fase 0 y la prueba con ARCA corren en paralelo porque ARCA es lo único que puede trabar el proyecto entero.

0. **Validación con ferreterías** (1 semana, en paralelo con la fase 1)
   - Visitar 3 o 4 ferreterías con las preguntas de la sección siguiente.
   - Conseguir un Excel real de lista de proveedor y una exportación real de artículos.
   - Resultado: esta lista de casos de uso ajustada y una ferretería piloto comprometida.
1. **Prueba técnica de ARCA en homologación** (1 semana)
   - Proyecto aparte, sin UI: certificado de homologación, `loginCms`, `FECompUltimoAutorizado` y `FECAESolicitar` de una factura B.
   - Terminado cuando: un test de integración obtiene un CAE real de homologación.
2. **Esqueleto** (3 a 5 días)
   - Repo, Spring Boot + Angular, auth y multi-tenant traídos de Sendo, Flyway, Docker Compose y deploy en Railway.
   - Terminado cuando: un usuario de cada uno de dos tenants entra y no ve nada del otro (con test).
3. **Artículos y precios** (CU-03, CU-04, CU-05) (2 a 3 semanas)
   - Terminado cuando: el Excel real de la fase 0 se importa con vista previa y se aplica sin errores.
4. **Mostrador** (CU-06, CU-07 sin ARCA, CU-10) (2 semanas)
   - Búsqueda con lector de código de barras, ticket, medios de pago, presupuesto y caja.
   - Terminado cuando: una venta de 5 artículos se cierra en menos de 15 segundos solo con teclado y lector.
5. **Facturación electrónica** (CU-01, CU-07 completo, CU-08) (2 semanas)
   - Se enchufa el código de la fase 1: CAE, PDF con QR, nota de crédito, reintentos y bandeja de rechazados.
6. **Stock, pulido y piloto** (CU-02, CU-09) (2 semanas)
   - Ajustes de stock, gestión de usuarios, carga inicial de datos del piloto y paso a producción en ARCA.

**Forma de trabajo con Claude Code:** este documento se exporta como `docs/spec.md` en el repo y el `CLAUDE.md` apunta a él. Cada caso de uso se implementa en una rama propia: primero los tests del dominio, después el endpoint y después la pantalla.

## Preguntas abiertas para validar con ferreterías

Estas preguntas se llevan impresas a cada visita. Las respuestas pueden mover casos de uso adentro o afuera del MVP.

- [ ] ¿Qué sistema usan hoy, cuánto pagan por mes y qué es lo que más odian de él?
- [ ] ¿Cuántos artículos activos tienen y cuántos proveedores les mandan listas? ¿Cada cuánto llegan?
- [ ] ¿En qué formato llegan las listas (Excel, PDF, web del proveedor)? Pedir una de muestra.
- [ ] ¿Son Responsables Inscriptos o Monotributistas? ¿Qué proporción de facturas A hacen?
- [ ] ¿Cuántas ventas hacen en un día fuerte? ¿Cuántos puestos de venta usan a la vez?
- [ ] ¿Usan lector de código de barras? ¿Los artículos tienen código o los etiquetan ellos?
- [ ] ¿Imprimen la factura en A4, en ticket térmico o la mandan por WhatsApp?
- [ ] ¿Venden a cuenta corriente? La venta con límite ya está hecha (CU-07 y CU-11); si la respuesta es "sí, mucho", entran las cobranzas con imputación (tramo 3). Preguntar también si les alcanza el límite por cliente que autoriza el Dueño.
- [ ] ¿Quién carga los precios hoy y cuánto tiempo le lleva?
- [ ] ¿Cuánto pagarían por mes por algo que les resuelva las listas de precios y la facturación?
