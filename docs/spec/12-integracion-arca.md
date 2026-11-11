# Integración ARCA

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · sección extracto de `spec.md:475-568` en la partición del 2026-11-10.

<!-- spec payload: spec.md:475-568 -->
## Integración ARCA

La facturación usa dos servicios SOAP de ARCA: WSAA para obtener el token y WSFEv1 para pedir el CAE. Todo se construye primero contra un **simulador del WSFEv1 4.8** dentro de `facturacion`; la homologación y el paso a producción se hacen con la piloto (rama 11 del plan de facturación). El ambiente de cada ferretería no es un flag: lo fija el super admin o la CA del certificado (CU-01, «Ambiente de ARCA»).

**Ambientes** (quién los fija: decidido por Luca el 2026-10-28, D3 del plan de facturación; el resto, reglas del plan de facturación del 2026-10-28):

- Hay tres: `SIMULADOR`, `HOMOLOGACION` y `PRODUCCION`. El ambiente va en cada registro fiscal y en sus únicos, se fija al crearlo y no cambia: un cambio de configuración no reetiqueta lo ya emitido ni manda a ARCA real un pendiente simulado.
- **Lo simulado nunca parece fiscal.** La marca, la letra y el QR salen del ambiente del registro, nunca de la configuración actual. Fuera de producción, y en todo lo que no tiene CAE, la pantalla, la API y el impreso muestran la letra X con «DOCUMENTO NO VALIDO COMO FACTURA» y «SIMULADO — sin validez fiscal» (u «HOMOLOGACIÓN — sin validez fiscal»), sin QR ni «Comprobante Autorizado». La hoja es de la rama 4. En la API, la apariencia la da `fiscal.documento` (`validoComoFactura` en false: letra X y, en null, el código, el número fiscal, el CAE y el QR); los campos de estado de `fiscal` (CU-07 paso 5, «Respuesta») informan el registro tal como está, con su ambiente, y ninguna pantalla ni hoja los usa para mostrar la factura.
- **Nada simulado llega a ARCA real.** El adaptador se elige por el ambiente del registro: el simulador rechaza lo que no es `SIMULADOR` y el cliente real rechaza `SIMULADOR`. Las URL de ARCA salen del ambiente, nunca de un campo editable. El ambiente y el punto de venta no cambian mientras haya registros `PENDIENTE`, `EN_CURSO` o `EN_VERIFICACION`.

**Flujo de una factura:**

1. Se arma el TRA (LoginTicketRequest), se firma en CMS/PKCS#7 con el certificado (BouncyCastle) y se llama a `loginCms` del WSAA. Devuelve token y sign, válidos unas 12 horas.
2. El ticket de acceso (TA: token, sign y vencimiento) se guarda cifrado en la base por ferretería, servicio y ambiente, y se reusa hasta que vence, también después de un reinicio. No se pide uno por factura: dentro del lapso preventivo (10 minutos en homologación y 2 en producción, según el manual WSAA 20.2.19, que ARCA puede cambiar sin aviso) ARCA rechaza un pedido nuevo con un TA vigente.
3. Con la serie tomada, se asignan el número siguiente y la `CbteFch`, y el intento se guarda antes de llamar (ver «Numeración»).
4. `FECAESolicitar` con cabecera, importes, el desglose por alícuota y el receptor con su `CondicionIVAReceptorId`. Devuelve CAE y vencimiento, o las observaciones y errores.
5. El resultado se guarda en el registro fiscal (el comprobante no cambia): el CAE, su vencimiento y las observaciones y errores tal cual. La factura impresa lleva el código QR obligatorio, que se arma en `facturacion` (rama 4).

Desde la rama 6, el simulador hace los cinco pasos igual que ARCA: recibe el mismo XML y la misma firma CMS (ver «Simulador»).

**Cliente de ARCA** (rama 6 del plan de facturación):

- El XML de WSAA y de WSFEv1 se arma con plantillas de texto y se lee con javax.xml, sin cliente generado del WSDL. El parser no acepta DOCTYPE ni entidades externas. Se lee lo que trae ARCA: errores, observaciones y eventos.
- WSFEv1: FEDummy, FEParamGetPtosVenta, FECompUltimoAutorizado, FECAESolicitar y FECompConsultar.
- **El TA se pide de a uno.** Un lease con dueño en la base, como el de la serie, hace que entre las dos JVM de un deploy haya un solo `loginCms` por ferretería, servicio y ambiente. El que llega mientras otro lo pide espera un rato acotado. El TA se renueva con margen, antes de vencer, y el TRA sale con el `generationTime` unos minutos antes del `Clock`.
- **Transporte.** Timeouts de conexión y de lectura dentro del presupuesto de la llamada: el backend no arranca si no alcanza para WSAA y WSFEv1 juntos. TLS con el truststore del JDK: 1.3 y 1.2, y en 1.2 solo suites ECDHE (servicios1 ofrece DHE de 1024 bits). Las URL de homologación y producción están en la configuración, nunca en un campo editable.
- **Logs.** No se vuelca el SOAP; el token y el sign van enmascarados.
- **Pruebas.** Una suite de contrato corre siempre contra el simulador y contra un servidor HTTP del JDK con respuestas reales guardadas. Un perfil opt-in (`-Parca-real`) corre FEDummy contra ARCA real.

**Errores de ARCA** (cortacircuito por ferretería, rama 6; códigos de la especificación técnica WSAA 1.2.2):

- Certificado (`cms.cert.*`) o no autorizado (`coe.notAuthorized`): bloquea la facturación de la ferretería hasta que el Dueño active otro certificado o «Probar conexión» dé OK. Mientras tanto la venta sale sin factura (CU-01, «Sin facturación») y no se llama a WSAA.
- Error del sistema (`xml.*`, `wsn.notFound`, `cms.bad`, `cms.bad.base64`, `cms.sign.invalid` o un código desconocido): alerta en el log para soporte y no se llama a WSAA por un rato. El registro queda pendiente; la ferretería sigue facturando.
- Transitorio (`wsaa.*` y `wsn.unavailable`): no se llama a WSAA por 60 segundos o más, como pide ARCA, y el registro queda pendiente. «TA vigente» (`coe.alreadyAuthenticated`) también es transitorio, con su propia espera.
- Token o firma inválidos en WSFEv1 (600): se pide un TA nuevo una sola vez y se repite la llamada una vez.

**Qué se manda** (la letra: decidido por Luca el 2026-10-28, D2 del plan de facturación; el desglose, plan de facturación del 2026-10-28):

- La letra, `CbteTipo`, `DocTipo` y `CondicionIVAReceptorId` salen de las tablas de «Reglas de negocio → Comprobantes». El mapeo del dominio al pedido es el mismo en todos los ambientes.
- **Desglose del IVA por alícuota a nivel comprobante.** Para cada alícuota a, con T el total con IVA de sus renglones: `BaseImp` = T / (1 + a) redondeado al centavo e `Importe` = T − `BaseImp`. En la C, `ImpNeto` es el total, `ImpIVA` vale 0 y no va el array de IVA. `ImpTotal` es siempre lo cobrado. Sumar el IVA renglón por renglón, como la venta de hoy, no pasa la tolerancia de 10051 en tickets chicos: dos renglones de $ 100 al 21 % ya fallan, y por comprobante pasan. El neto y el IVA del Comprobante (los de los reportes de CU-15) pueden diferir en centavos del desglose fiscal; se acepta mientras el libro IVA esté fuera del MVP.
- Lo que va a ARCA (letra, receptor, emisor y desglose) se congela en el registro fiscal al vender (rama 3): el reintento automático nunca lo recalcula. Solo «Reintentar» del Dueño (CU-16) arma un registro nuevo con la copia de ese momento (D10).

**Numeración** (plan de facturación del 2026-10-28, regla «Nunca se reenvía a ciegas»):

- La serie es (ferretería, ambiente, punto de venta, `CbteTipo`). Se toma con un **lease con dueño en la base**, que vale entre JVM (en cada deploy conviven dos): hay a lo sumo un intento abierto por serie, y la serie se atiende en orden FIFO.
- El número y la `CbteFch` se asignan **al enviar**, no al vender. El intento se guarda con ellos en una transacción corta antes de llamar; la llamada va sin transacción ni lock abiertos, y el resultado se guarda en otra transacción corta con un UPDATE condicional.
- Antes de enviar se chequea la `CbteFch`: no puede ser anterior a la del último autorizado ni a más de 5 días antes del envío (N−5) y, si es posterior al envío, tiene que caer en el mismo mes. Lo que no entra pasa a `FUERA_DE_FECHA`, sin reintento: la fecha nunca cambia sola (solo «Reintentar» del Dueño, con su confirmación, la pasa a la de hoy: CU-16).
- Un timeout de conexión cuenta como no enviado. Un timeout de lectura o un 5xx pasa el registro a `EN_VERIFICACION`, que traba la serie hasta resolverse con `FECompUltimoAutorizado` y `FECompConsultar`, sobre el número N del intento:
  - si ARCA ya autorizó N o más, se consulta N y se adopta el CAE solo si coinciden todos los campos; si no coinciden, alerta sin renumerar;
  - si el último autorizado es N−1, se reenvía el mismo intento;
  - si es menor que N−1, alerta de serie desincronizada.
- Un rechazo libera el número: el registro rechazado queda sin número.
- **10016** (el número o la fecha no son los próximos; rama 7). El código es el mismo para los dos casos, así que se consulta `FECompUltimoAutorizado`: si el número enviado no era el próximo, la serie se resincroniza con ARCA (también hacia atrás) y el registro vuelve a pendiente sin número, para renumerarse; si era el próximo, el problema es la fecha y pasa a `FUERA_DE_FECHA`. Un 10016 no deja el registro rechazado. Si ARCA va atrás de números que la serie ya tiene (por ejemplo, un homologación vaciado con facturas nuestras), no se renumera: es una serie desincronizada, que traba la serie hasta que la revise soporte.
- **602** de `FECompConsultar` («no existe») no es un error transitorio. Al sincronizar la serie, el 602 del último es que no está: la serie sigue sin su fecha. En la verificación, con ARCA en N o más, es una contradicción (la numeración de ARCA es correlativa, así que N tendría que existir): alerta sin renumerar, como un CAE que no coincide, porque renumerar a ciegas podría duplicar la factura de la venta.

**Simulador** (plan de facturación del 2026-10-28; rama 2, y desde la rama 6 en el borde del transporte):

- Va detrás de un puerto en términos de WSFEv1 (`FECompUltimoAutorizado`, `FECAESolicitar` y `FECompConsultar`) y solo atiende el ambiente `SIMULADOR`, que solo se asigna a una ferretería marcada «de prueba» (CU-01).
- Copia el manual WSFEv1 4.8, con la versión escrita en el código y sus mismos códigos y mensajes: valida 10013, 10015 (formato), 10016, 10023, 10040, 10048, 10051 (HALF_EVEN y tolerancias), 10061, 10071, 10152, 10197, 10243 y 10246, y devuelve como observaciones 10041, 10063, 10188, 10217 y 10237. Cada diferencia que aparezca con ARCA real se agrega como test del simulador.
- Tiene **estado propio** en tablas con `ferreteria_id` (V33), que sobrevive a un reinicio. La latencia es configurable, y las fallas (timeout antes y después de autorizar, 502 y rechazo con observaciones) se inyectan solo en tests.
- **En el borde del transporte** (rama 6). El ambiente `SIMULADOR` usa el mismo cliente SOAP que ARCA, con un transporte que no sale a la red. Allí el simulador valida el XML contra el XSD del WSDL de WSFEv1 (guardado con su fecha) y verifica el CMS contra la CA de simulación de la ferretería. También emite el TA con un lapso configurable, rechaza el `Auth` que no emitió (600) y usa el mismo núcleo de reglas. Exige una credencial vigente, como los otros ambientes. En dev, el perfil dev genera al arrancar la credencial simulada de las ferreterías demo; nunca hay datos cifrados en `db/dev-data`.

**Tiempo:** todo el tiempo de `facturacion` (la `CbteFch`, los leases y los vencimientos) sale del `Clock` inyectado; ArchUnit le prohíbe `HoraArgentina.ahora()`, `now()` e `Instant.now()`.

**Cómo se autentica cada ferretería:** se eligió el **certificado propio por tenant** (decisión de Luca, 2026-10-22). Cada ferretería genera su certificado con su clave fiscal, carga sus datos (CUIT, punto de venta) y hace sus trámites con ARCA; el sistema solo conecta esa ferretería con ARCA. No hay CUIT del servicio ni delegación. Los dos modelos que se evaluaron:

| Modelo | Cómo funciona | A favor | En contra |
| --- | --- | --- | --- |
| Certificado propio por tenant | Cada ferretería genera su certificado con clave fiscal y lo sube al sistema | Cada CUIT es independiente | Onboarding más difícil; hay que guardar N claves privadas y vigilar N vencimientos |
| Delegación al CUIT del servicio | Vos tenés un solo certificado; cada ferretería delega el servicio de factura electrónica a tu CUIT desde ARCA, y en cada llamada se informa el CUIT representado | Una sola clave que custodiar; el alta es un trámite de 5 minutos | Necesitás CUIT propio y todo depende de un solo certificado |

**Seguridad:** claves privadas cifradas en la base con AES-GCM, con la clave maestra en una variable de entorno y nunca en el repo. Aviso al dueño 30 días antes del vencimiento del certificado.

- **Formato del dato cifrado** (rama 5 del plan de facturación): versión, id de la clave maestra, nonce aleatorio de 12 bytes y texto cifrado con tag de 128 bits (AES/GCM). El AAD lleva el propósito (clave de la credencial o de la CA del simulador), el `ferreteria_id` y la huella de la clave pública: un dato copiado a otra ferretería o a otro uso no se descifra.
- **Clave maestra:** `ARCA_CLAVE_MAESTRA`, 32 bytes en Base64. En producción no tiene valor por defecto: sin ella, o con otro largo, el backend no arranca (como `JWT_SECRET`). En dev tiene un valor por defecto y en los tests es fija. Se guarda una copia fuera de Railway: si se pierde, las claves cifradas no se recuperan y cada ferretería tiene que pedir un certificado nuevo (ningún test detecta esa pérdida).
- **Rotación sin migrar:** la clave nueva va en `ARCA_CLAVE_MAESTRA` y la vieja en `ARCA_CLAVE_MAESTRA_ANTERIOR`; el id de clave del dato dice con cuál se descifra. Nada se recifra: lo cifrado con la vieja se lee mientras siga en `ARCA_CLAVE_MAESTRA_ANTERIOR`. Cada pedido nuevo sale con la clave actual, así que la vieja se saca recién cuando todas las ferreterías renovaron el certificado (dura dos años). Las CA del simulador no se renuevan: sin la vieja, «Firmar con el simulador» de esa ferretería da 409. Hay un solo lugar para la anterior: rotar otra vez antes de sacarla deja ilegible lo cifrado con la primera. Un id desconocido o un dato que no se descifra deja la credencial «ilegible» y bloquea solo la facturación de esa ferretería.
- **Sin secretos afuera:** ninguna respuesta ni log lleva la clave privada ni el dato cifrado; las respuestas dan CUIT, emisor, vigencia, huella, ambiente y estado.
- **Ticket de acceso** (rama 6): el token y el sign se guardan cifrados con el mismo formato, con su propio propósito en el AAD y la huella de la credencial que los pidió. Un TA de otra credencial no se usa. Ninguna respuesta ni log lleva el token, el sign ni el CMS.

**Contingencias:**

- ARCA caída o con timeout → la venta queda "pendiente de CAE" (en su registro fiscal) y el cliente se lleva un ticket provisorio X («DOCUMENTO NO VALIDO COMO FACTURA»). Lo retoma la venta siguiente de la misma serie (CU-07 paso 5) y el job de reintento (rama 7).
- **Contingencia mientras no conteste el contador** (D9, decidido por Luca el 2026-10-28): espera corta en línea, ticket provisorio X y reintento automático acotado, que vence a los 5 días corridos. Hasta que el contador diga si en la B a consumidor final se corta en el día, «5 días» es la misma ventana de la `CbteFch` (N−5 por día de Buenos Aires, ver «Numeración»): lo que ya no entra pasa a `FUERA_DE_FECHA` y queda en la bandeja del Dueño (CU-16). Si el contador confirma que la ferretería está en el Anexo de la RG 4290, el CAEA entra como rama propia antes del paso a producción.
- **Job de reintento** (rama 7). Corre en un hilo propio, no en el del scheduler que comparten el recálculo diario y las cotizaciones. Cada minuto recorre las ferreterías activas y, de cada una, las series con trabajo:
  - una serie tomada por otro (la venta u otra JVM) se saltea;
  - primero resuelve el envío en duda, después atiende los pendientes en orden FIFO y corta la serie en el primer error transitorio;
  - el backoff es por serie y vive en la base: arranca en 1 minuto, se duplica en cada pasada con error y tiene un tope de 30 minutos; vuelve a cero cuando la serie autoriza;
  - la pasada tiene un tope de tiempo, y una serie trabada por una alerta no se toca hasta que la resuelva soporte.
- La cola de reintentos es solo para ARCA caída o con timeout. La venta facturable de una ferretería que no puede facturar no entra nunca: queda sin factura (CU-01, «Sin facturación»).
- ARCA rechaza el comprobante → se guarda el error tal cual lo devuelve ARCA y se le muestra al Dueño en la bandeja de «Ventas», con «Reintentar» y «Dejar sin factura» (CU-16, punto 7; D10).
- Antes de reintentar un envío en duda se resuelve `EN_VERIFICACION` con `FECompUltimoAutorizado` y `FECompConsultar` (ver «Numeración»), para no duplicar facturas.
- El régimen CAEA (CAE anticipado para emitir offline) queda para después del MVP, salvo que el contador confirme que la ferretería está en el Anexo de la RG 4290 (D9).
<!-- fin payload: spec.md:475-568 -->

