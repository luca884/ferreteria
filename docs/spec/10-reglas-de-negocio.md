# Reglas de negocio

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · sección extracto de `spec.md:343-418` en la partición del 2026-11-10.

<!-- spec payload: spec.md:343-418 -->
## Reglas de negocio

Estas reglas se codifican en el dominio y se cubren con tests unitarios antes que cualquier pantalla.

### Precios

- El costo del proveedor se guarda **sin IVA**. Precio neto = costo × (1 + margen). Precio final = neto × (1 + alícuota de IVA).
- El margen se define por artículo; si no tiene, hereda el del rubro; si no, el margen por defecto de la ferretería. Al cambiar el margen de un rubro, antes de guardar la pantalla de Rubros muestra la lista de los artículos activos que heredan ese margen y cambian de precio (código, descripción, precio de hoy y precio nuevo; hasta 1.000, con el total) y pide confirmación; los de precio manual no cuentan. Si ninguno cambia, guarda sin preguntar.
- Sobre el precio final calculado se suman los ajustes del artículo, todos sobre el mismo precio calculado (CU-14). Un artículo con precio manual conserva el precio que escribió el Dueño y no usa la fórmula (CU-14).
- Redondeo: el precio final se redondea hacia arriba a un múltiplo que elige el Dueño (CU-03, «Margen y redondeo»), como GDS. Con múltiplo 0 no se redondea y el precio queda con centavos (escala 2, hacia arriba al centavo). Toda ferretería arranca en 0 (H11, migración V9); desde 2026-10-23 el Dueño lo puede cambiar.
- Todo cambio de costo o precio guarda historial: fecha, valor anterior, valor nuevo y origen (importación, masivo, manual, compra).
- Montos en `BigDecimal` (Java) y `DECIMAL(15,2)` (base). Nunca `double`.

### Comprobantes

La letra es la que autoriza ARCA hoy (decidido por Luca el 2026-10-28, D2 del plan de facturación: RG 1415 con la RG 5003 y el anexo del manual WSFEv1 4.8). La venta sin cliente es a Consumidor Final.

| Emisor (la ferretería) | Cliente | Comprobante |
| --- | --- | --- |
| Responsable Inscripto | Responsable Inscripto | Factura A |
| Responsable Inscripto | Monotributista | Factura A, con la leyenda de la Ley 27.618 |
| Responsable Inscripto | Consumidor Final, Exento o venta sin cliente | Factura B |
| Monotributista | Cualquiera, también la venta sin cliente | Factura C, sin IVA |
| Exento | Cualquiera, también la venta sin cliente | Factura C, sin IVA (el contador confirma esta fila antes de habilitar una ferretería exenta) |

Cada pedido a ARCA lleva estos códigos (el mapeo vive en `facturacion`):

| Dato del pedido | Valores |
| --- | --- |
| `CbteTipo` | Factura A 1, B 6 y C 11; nota de crédito A 3, B 8 y C 13 |
| `DocTipo` | CUIT 80, CUIL 86, DNI 96 y sin identificar 99 (venta sin cliente o cliente sin documento) |
| `CondicionIVAReceptorId` | Responsable Inscripto 1, Exento 4, Consumidor Final (también la venta sin cliente) 5 y Monotributista 6 |

- **Todo pedido lleva `CondicionIVAReceptorId`.** ARCA rechaza el pedido sin ella (10246, obligatoria desde el 01/12/2026) y la B a un Monotributista (10243). La letra es una función pura con una tabla de verdad exhaustiva en JSON (las 3 condiciones del emisor por las 4 del cliente, más la venta sin cliente) que comparten el test de dominio y el simulador.
- La A a un Monotributista lleva la leyenda de la Ley 27.618 (RG 5003, desde el 01/07/2021). La hoja impresa es de la rama 4 del plan de facturación.
- La factura B a consumidor final informa el IVA contenido (Régimen de Transparencia Fiscal). Validar el formato vigente con un contador.
- **Tope de identificación de ARCA** (decidido por Luca el 2026-10-28, D4 del plan de facturación). Desde el monto que fija ARCA ($ 10.000.000,00 hoy: RG 5700/2025, que mantiene la RG 5866/2026), la venta a consumidor final exige identificar al comprador con DNI, CUIT o CUIL. Ese monto es un valor global de la configuración (`app.facturacion.tope-identificacion`), no del código ni de cada ferretería, y se actualiza cuando ARCA lo cambia. Con la facturación habilitada, la venta sin cliente identificado nunca llega a ese tope, aunque el Dueño haya fijado uno mayor: rige el menor de los dos (CU-07 paso 2, «Consumidor Final sin identificar»). Sin la facturación habilitada rige solo el tope del Dueño.
- La numeración la da ARCA, por serie (ferretería, ambiente, punto de venta y tipo): el número y la fecha fiscal se asignan al enviar, nunca se reenvía a ciegas y un rechazo libera el número (ver Integración ARCA → Numeración).
- El IVA que se informa se desglosa por alícuota a nivel comprobante, no renglón por renglón (ver Integración ARCA → Qué se manda).
- Un comprobante con CAE es inmutable. Solo se corrige con nota de crédito.

### Stock

- Toda variación de stock es un movimiento (venta, anulación, ajuste, ingreso). El stock actual es la suma de movimientos, cacheada en el artículo.
- Se permite stock negativo con advertencia: en el mostrador la venta no se frena por un error de carga.
- Las cantidades admiten decimales (cable por metro, clavos por kilo).

### Cuenta corriente

- El saldo del cliente es un caché del libro: **saldo = Σ debe − Σ haber** de sus movimientos, siempre. Positivo es deuda y negativo, saldo a favor. El libro es solo de alta (no se edita ni se borra) y un único servicio escribe el saldo y el libro.
- Una venta excede el límite si saldo + lo vendido a cuenta > límite. El límite NULL no habilita la cuenta y no se autoriza ni siquiera con el Dueño; el exceso sí lo autoriza el Dueño (CU-07).
- **Concurrencia:** el chequeo del límite y el asiento se hacen con el cliente tomado con lock (`SELECT … FOR UPDATE` nativo, que lee el saldo vigente y no la foto de la transacción) y el saldo se escribe con un UPDATE relativo. Toda venta con cliente, sea o no a cuenta corriente, toma al cliente en el mismo lugar del orden de locks: caja → presupuesto → remito → **cliente** → numerador → comprobante → artículos → hijos. Es lo que evita el deadlock con la FK al insertar el comprobante. La venta facturable suma la configuración de ARCA con lock compartido entre el cliente y el numerador, y el registro fiscal como último hijo (CU-07 paso 3).
- La caja suma lo vendido a cuenta como renglón informativo: entra en el total de ventas y no en el efectivo esperado.
- **Pendiente e imputación (tramos 3a y 3b-1):** el **pendiente** de un comprobante es Σ debe de sus movimientos `VENTA` − Σ imputaciones **vigentes** contra él, y nunca es negativo; una venta mixta solo debe su parte a cuenta corriente. Lo que un recibo no imputa es su **crédito sin imputar** («a cuenta»): Σ haber de los `COBRO` − Σ debe de las `ANULACION` − Σ imputaciones vigentes. El crédito sin imputar de una **nota de crédito** (CU-08) es Σ haber de sus movimientos `NC` − Σ imputaciones con esa NC como origen; esas imputaciones son siempre vigentes, porque la NC no se anula. Si se anula un recibo que pagaba una venta ya anulada, la nota de crédito de esa venta le vuelve a imputar, en automático, lo que el recibo cubría, hasta lo que le quede sin imputar: la venta anulada no vuelve a deber mientras su nota tenga crédito. Ambos son **derivados**, no se guardan. La imputación es un vínculo explícito recibo → comprobante que no toca el libro ni el saldo; el que baja el saldo es el `COBRO` (uno por recibo) y el que lo vuelve a subir es la `ANULACION` (una por recibo anulado). Una imputación es **vigente** si y solo si su recibo no está anulado; la anulación las pasa a no vigentes (de a todas, una sola vez) y es la única escritura permitida sobre una imputación. Todas las lecturas del libro de partidas (pendientes, crédito a cuenta, lo imputado con lock) consideran solo las vigentes; el recibo se reimprime con sus imputaciones originales.
- **Invariantes (se verifican después de cualquier operación, también las repetidas, rechazadas y concurrentes):** **I1** saldo guardado = Σ debe − Σ haber del libro (la `ANULACION` suma en el debe); **I2** Σ pendientes de los comprobantes del cliente − Σ crédito sin imputar de sus recibos, Descuentos y notas de crédito = saldo (con un adelanto sin deuda: 0 − crédito = saldo negativo), sobre imputaciones vigentes. I2 se desglosa en: ningún pendiente negativo; por recibo, exactamente un `COBRO` de haber igual al total y, si está emitido, ninguna `ANULACION` y Σ imputaciones vigentes ≤ total, o, si está anulado, exactamente una `ANULACION` de debe igual al total y ninguna imputación vigente; por nota de crédito, exactamente un `NC` por cada pago a cuenta corriente de la venta que anula, con haber igual a ese pago, y Σ imputaciones con ella como origen ≤ Σ de esos haberes; la igualdad global; y que imputación, recibo y débito del comprobante sean del mismo cliente. **I2e** (coherencia de la anulación): imputación no vigente ⇔ existe la `ANULACION` del recibo ⇔ el recibo está `ANULADO`, y la caja de la anulación es de la misma ferretería. **I-inactivo**: un cliente desactivado tiene saldo 0 (por eso la NC de una venta a cuenta corriente pide el cliente activo).
- **Concurrencia de la cobranza:** toma la caja compartida (`FOR SHARE`), después el **cliente** con lock, antes de cualquier lectura plana, y recién entonces el numerador de recibos: caja → cliente → numerador de recibos → recibo → partidas (por id ascendente) → imputaciones → libro y saldo. La lectura de lo imputado es la actual (con lock), no la foto de la transacción: es lo que evita imputar dos veces la misma deuda. Compatible con el orden de la venta (cliente siempre antes que cualquier numerador): no hay ciclos. El cierre de caja toma la caja exclusiva y espera a las cobranzas en vuelo; las que llegan después ven la caja cerrada.
- **Concurrencia de la nota de crédito (rama 10):** la anulación de una venta toma primero al **cliente** con lock y después la venta, así que una NC y una cobranza, una venta a cuenta o un ajuste del mismo cliente se serializan. El asiento en la cuenta va después de dar de alta el comprobante de la NC y, dentro de `clientes`, sigue el orden de la cobranza: partidas (por id ascendente) → lo imputado y el crédito (con lock, lectura actual) → libro y saldo.
- El **límite de crédito aplica solo a ventas**: un `COBRO` nunca se rechaza por límite, ni con el límite vacío o en 0 ni cuando el saldo queda a favor.
- **Concurrencia de la anulación (3b-1):** toma la caja abierta compartida (`FOR SHARE`), después el **cliente** con lock, y recién entonces el recibo: caja → cliente → recibo → imputaciones del recibo (por id ascendente) → libro y saldo. Es el mismo prefijo caja → cliente que la cobranza y la venta, y **no toma ningún numerador**, así que anular recibos de clientes distintos no se serializa. Antes del lock del cliente solo se resuelve, sin locks y en una transacción aparte de solo lectura, de qué cliente es el recibo (es inmutable) y se revalida bajo lock; ninguna lectura plana puede anteceder al lock del cliente, porque fijaría una foto vieja del estado del recibo. Una anulación contra un cierre de caja se serializa (o entra antes del cierre, y el resumen del cierre la incluye, o recibe 422 `CAJA_CERRADA`): nunca queda una anulación en una caja cerrada. Anulación y desactivación del mismo cliente también se serializan. El cierre de caja toma la caja abierta por el mismo camino que los lectores (vender, cobrar, anular), sin lo cual cerrar la caja y vender a la vez podía dar deadlock; el resultado de negocio no cambió.
- **Corregir un cobro:** un cobro mal cargado se corrige anulando su recibo y cobrando de nuevo con una clave nueva (CU-12). El saldo se ajusta a mano con un Recargo o un Descuento (CU-12 punto 8, tramo 3b-2), que no se anulan: se compensan con el contrario.
- **Lo que el Vendedor puede deducir (aceptado):** como ve «alcanza» o «no alcanza» para un importe dado, probando importes con `GET /api/clientes/{id}/cuenta-corriente?importe=` (o con los rechazos `LIMITE_CUENTA_CORRIENTE` de las ventas) puede acotar por tanteo cuánto le queda disponible a un cliente. Es inherente a la decisión de que el Vendedor vea solo eso, y por ahora queda aceptado. Una posible mitigación futura es limitar el ritmo de consultas.

### Despliegue: zona horaria

- La JVM del backend **tiene que correr con la zona `America/Argentina/Buenos_Aires`** (variable de entorno `TZ=America/Argentina/Buenos_Aires` o argumento `-Duser.timezone=America/Argentina/Buenos_Aires`, igual a `HoraArgentina.ZONA`) y **no puede cambiar entre un deploy y el siguiente**. Las fechas y horas de la aplicación son de Buenos Aires (el `Clock` del servidor); Hibernate las guarda convertidas según la zona de la JVM (`hibernate.jdbc.time_zone=UTC`) y las lee y filtra con la misma conversión. Con la zona fija y sin horario de verano, cada movimiento cae en exactamente un día y el extracto, la caja «del día» y los recibos son correctos.
- **Si la zona de la JVM cambia entre la escritura y la lectura** (por ejemplo, un deploy nuevo en un contenedor con la zona en UTC sobre una base con datos escritos con −03), las fechas ya guardadas se leen corridas por la diferencia de horas: los movimientos de las últimas o primeras horas del día pasan al día anterior o siguiente, la hora que se muestra sale corrida (3 horas) y los filtros por día del extracto y la caja «del día» dejan de coincidir con lo que vio el usuario. Nunca se duplican ni se pierden movimientos (cada uno sigue en exactamente un día), pero el día queda corrido. Con una zona con horario de verano que salta a medianoche, la hora mostrada puede salir corrida 1 hora. Esto es preexistente y de toda la aplicación, no de una pantalla.
- **Antes de cada deploy:** verificar que el entorno fija la zona (Railway: variable `TZ`). Al arrancar, el backend avisa con un WARN en el log (con las dos zonas) si `ZoneId.systemDefault()` difiere de `HoraArgentina.ZONA`; no frena el arranque.

### Despliegue: lectura de PDF

- Para leer una lista en PDF (CU-04), el backend lanza **otra JVM** (`LectorPdf` → `ExtractorPdf`) con el `java` de la suya (`java.home`) y `-Xmx256m`. El contenedor necesita entonces **hasta unos 400 MB más de memoria** mientras se lee un PDF (uno por vez; LISTA 336 llega a unos 300 MB y un PDF que agota los 256 MB, a unos 370 MB) y poder escribir en el directorio temporal (`java.io.tmpdir`), donde quedan el PDF y sus palabras mientras dura la lectura.
- El backend tiene que arrancar con **`java -jar` sobre el jar de Spring Boot** (la JVM hija arranca con su `PropertiesLauncher`) o con un classpath común. Las variables `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS` no pasan a la JVM hija, así que no le cambian la memoria.
- La JVM hija corre con `nice -n 19` (`/usr/bin/nice` o `/bin/nice`) y `-XX:ActiveProcessorCount=1`: si un PDF la tiene ocupada hasta los 30 s, el servidor le gana la CPU. Si la imagen no tiene `nice`, la hija arranca igual, con la prioridad normal.
- Si la JVM hija no arranca, cada PDF da «No se pudo leer el archivo» y el log del backend dice `El proceso que lee el PDF terminó con el código N`.
<!-- fin payload: spec.md:343-418 -->

