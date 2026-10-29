# cu-08-nota-de-credito

Objetivo: que el Dueño anule una venta. Si la venta tiene una factura autorizada, la anula con una nota de crédito (NC) total de la misma letra y con CAE propio. Si no tiene CAE, la anula con una anulación interna con motivo. Las dos reponen el stock, no tocan la caja y CU-15 las resta. · Por qué: rama 8 de la fase 5 (`wiki/plan-facturacion.md` del vault, :526-578). Hoy una venta no se puede anular: CU-16 punto 4 lo deja para CU-08.
Rama: `feat/cu-08-nota-de-credito` desde `57f6d8d` (`main`, con las ramas 1 a 7 en producción) · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Dtest=<Clase>` / `cd frontend && npx ng test --watch=false --include=<spec>` · Mirror Engram: `odd/cu-08-nota-de-credito/tasks` (ok)
Ruteo: caso 5, Opus de punta a punta (Luca: «dale me parece correcto», 2026-10-29). Los writers delegados corren con Opus. · Pronóstico: ~5.000 líneas cambiadas (el plan dice 2.500 a 3.500; la rama 7 duplicó su estimación) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance:
- La spec: D11, D12 y D13 en CU-08, CU-16 punto 4, CU-15 punto 2, el Modelo de datos y «Fuera del alcance».
- La NC total, emitida desde el detalle de Ventas por el Dueño.
- La anulación interna de una venta sin CAE (D12: sí).
- La V37.
- El stock de la NC.
- Los lectores de `tipo = VENTA`: reportes, Ventas, stock y avisos.
- El impreso de la NC.

Fuera de alcance:
- La NC y la anulación interna sobre cuenta corriente: rama 10. Hasta entonces, 422.
- La NC por renglones (D13: total).
- Devolver efectivo desde la caja (D11: el Dueño carga un egreso).

Restricciones (del mapa, L2):
- La ferretería sale solo del JWT. Una venta de otra ferretería es 404.
- `facturacion.api` usa solo tipos del JDK; `facturacion` no depende de `ventas`, y solo `PasosFiscales` escribe los repositorios fiscales (`ModulosArchTest`).
- `facturacion` no lee el reloj del sistema: todo va con el `Clock`.
- `ComprobanteFiscal` es `@Immutable`.
- Ninguna llamada a ARCA dentro de una transacción abierta.
- Orden de locks del plan: caja (S) → cliente → factura original → numerador → NC → stock (id ascendente) → libro. La fachada va sin transacción y con reintentos, como `AnulacionService` (`MAX_INTENTOS`).
- Un estado nuevo del registro fiscal entra en `ConfigArcaRepository.registrosAbiertos`, o traba el cambio de ambiente.
- Cada commit con un ítem de riesgo va aislado, y lo verifica `odd-verifier` (opus) al final.

- S1 (plan, docs): "Commit docs: pasa D11, D12 y D13 a la spec: CU-08, CU-16 punto 4, CU-15 punto 2, Modelo de datos (NOTA_CREDITO y comprobante asociado) y Fuera del alcance (NC sobre cuenta corriente)." — Aceptación: la spec dice cuándo se anula con NC y cuándo con anulación interna, con sus mensajes, sus efectos y lo que queda para la rama 10.
- S2 (plan, NC): "NC total (D13) desde el detalle de Ventas, separada de «Imprimir», con confirmación y sin atajo de teclado. Solo la emite el Dueño." — Aceptación: el botón está solo en el detalle del Dueño y pide confirmación. El Vendedor recibe 403 y la venta de otra ferretería da 404.
- S3 (plan, reglas): "solo sobre una factura AUTORIZADA del mismo ambiente; con un envío en curso o en EN_VERIFICACION, 409 «Esperá el CAE»; lleva la letra de la factura (3, 8 o 13), CbtesAsoc (tipo, punto de venta, número, CUIT y fecha) y el receptor copiado de la factura; usa el mismo motor, con su propia serie; una NC rechazada sigue siendo la única: se corrige y se reenvía desde la bandeja." — Aceptación:
  - hay NC A, B y C, con la letra, el comprobante asociado y el receptor de la factura;
  - el simulador rechaza una NC sin asociado (10197) o de otra clase (10040);
  - con la factura pendiente o en verificación, 409;
  - una factura de otro ambiente no admite NC: el cerrojo de ambiente lo frena;
  - una NC rechazada aparece en la bandeja y se reenvía con «Reintentar», sin crear otra.
- S4 (plan, concurrencia): "comprobante.tipo suma NOTA_CREDITO, con el numerador interno de siempre. comprobante_asociado_id lleva FK compuesta y UNIQUE: una NC por factura, y la segunda da 409 con quién y cuándo." — Aceptación: dos NC simultáneas de la misma factura dan una 201 y una 409, y la 409 dice quién y cuándo anuló.
- S5 (plan, stock): "Stock: un movimiento NOTA_CREDITO que repone todo lo vendido, también lo que salió por el remito de origen. Los movimientos del artículo y el extracto la nombran por su tipo." — Aceptación:
  - después de la NC de una venta que salió de un remito, el stock vuelve a lo que había antes del remito;
  - «Movimientos del artículo» muestra «Nota de crédito N.º n» y «Anulación N.º n».
- S6 (plan, plata; D11): "La plata según D11. Lo recomendado: no toca la caja (no lleva caja ni pagos). Una venta con un pago a cuenta corriente da 422 hasta la rama 10." — Aceptación: la caja no cambia, y una venta con un pago a cuenta corriente da 422 sin guardar nada.
- S7 (plan, reportes): "CU-15 resta la NC y muestra la ganancia con y sin NC (G66). Ventas la lista, y la caja no la suma." — Aceptación: con una venta y su NC, los reportes de CU-15 dan cero en total y en ganancia, y el resumen muestra la ganancia con y sin NC.
- S8 (plan, impreso): "Impreso de la NC con el componente de la rama 4, con QR solo en producción." — Aceptación: la hoja de la NC tiene la letra, el número, el CAE y la factura asociada. Sale con QR solo en PRODUCCION; en SIMULADOR sale con la marca de siempre.
- S9 (plan, D12): "anulación interna de una venta sin CAE: la hace el Dueño, con motivo, con los mismos efectos que la NC y sin documento fiscal. Toma el registro con el mismo UPDATE condicional que el job; si hay un envío abierto, 409." — Aceptación:
  - vale para una venta no facturable, sin registro fiscal, pendiente sin enviar, rechazada, fuera de fecha o sin factura;
  - con un envío en curso, en verificación o en revisión, 409;
  - un pendiente que el job toma a la vez queda en una de dos: o lo envía el job o lo anula el Dueño, nunca las dos cosas.
- S10 (plan, terminado): "MigracionV6Test incluye la V37, y su rollback está probado. Suites y ng build verdes. Pasa por ego-browser. odd-verifier sin bloqueantes sobre cada commit de alto riesgo." — Aceptación: `MigracionV37Test` con su rollback, la V37 en `MigracionV6Test:182`, las dos suites, `ng build`, ego-browser y el verificador sin bloqueantes.

Decisiones de producto (S11 a S16; Luca: «listo continua», 2026-10-29, L3):
- S11 (confirmado por Luca el 2026-10-29): la anulación interna es un comprobante `NOTA_CREDITO` sin registro fiscal, con su número interno y su motivo. Así, los reportes, el stock y Ventas la tratan igual que la NC, y la venta anulada se ve con «Anulada por la NC N.º n» o «Anulada: <motivo>», con quién y cuándo.
- S12 (confirmado por Luca el 2026-10-29): la NC y la anulación cuentan en la fecha en que se hacen, no en la de la venta, como en GDS. En el resumen de CU-15 hay un renglón «Notas de crédito» y «Ganancia sin NC» / «Ganancia con NC». «Por medio de pago» no las resta, porque no mueven plata.
- S13 (confirmado por Luca el 2026-10-29): la NC no pide motivo. La anulación interna pide un motivo obligatorio de hasta 200 caracteres, con los mismos mensajes que «Dejar sin factura».
- S14 (confirmado por Luca el 2026-10-29): en Ventas, la NC y la anulación son renglones propios: «Nota de crédito» con el total en negativo y su factura. Desde su detalle se reimprime la NC.
- S15 (confirmado por Luca el 2026-10-29): los textos son:
  - confirmación: «¿Anular la venta N.º n? Se emite una nota de crédito y vuelve el stock. La caja no cambia: si devolvés plata, cargá un egreso.»;
  - 409: «Esperá el CAE de la factura para anularla.» y «La venta ya fue anulada por <usuario> el <fecha>.»;
  - 422: «La venta tiene un pago a cuenta corriente: todavía no se puede anular.»
- S16 (confirmado por Luca el 2026-10-29): el remito de origen sigue «facturado» después de la NC. La NC devuelve el stock, pero no reabre el remito.

### Contratos (los fija el padre, L5)

- **C1 · Puerto fiscal para la anulación** (`facturacion.api.FacturacionParaVenta`, solo tipos del JDK; T3 lo implementa y T4 lo usa):
  - `ViaDeAnulacion tomarParaAnular(long ventaId)`. Exige transacción. Toma el registro vigente de la venta con lock X (es el paso «factura original» del orden de locks) y devuelve:
    - `NOTA_DE_CREDITO` si es AUTORIZADO;
    - `ANULACION_INTERNA` sin registro, o con uno PENDIENTE (sin número ni intento abierto), RECHAZADO, FUERA_DE_FECHA o SIN_FACTURA.
  - Lanza `FacturaImpedidaException`:
    - `ESPERAR_CAE` con EN_CURSO, EN_VERIFICACION, CAE_NO_COINCIDE o SERIE_DESINCRONIZADA;
    - `FACTURACION_FRENADA` si es AUTORIZADO pero la ferretería no puede facturar hoy, con el mismo motivo que «Reintentar»;
    - `OTRO_AMBIENTE` si es AUTORIZADO pero el ambiente de hoy de `config_arca` no es el de la factura. Es el cerrojo de ambiente, con el texto «La factura es de otro ambiente de ARCA: no se puede anular con nota de crédito.».
  - `long registrarNotaDeCredito(long notaId, long ventaId)`. Exige la transacción, después de `tomarParaAnular`. Da de alta el registro PENDIENTE de la NC:
    - clase NOTA_CREDITO, el ambiente, el punto de venta y la letra de la factura, y `CbteTipo` 3, 8 o 13;
    - el receptor, el emisor, los importes y las alícuotas copiados del registro de la factura, nunca del cliente de hoy;
    - el asociado (tipo, punto de venta, número, CUIT del emisor y `CbteFch` de la factura), con la fecha del `Clock`.
    - Devuelve el id del registro.
    - El CAE se pide después del commit, con el `autorizar(notaId)` de siempre.
  - `void cerrarSinCae(long ventaId, String motivo, long usuarioId)`. Exige la transacción, después de `tomarParaAnular` con `ANULACION_INTERNA`. Con un vigente PENDIENTE, RECHAZADO o FUERA_DE_FECHA, lo pasa a SIN_FACTURA con el motivo, el usuario y la hora, con un UPDATE condicional como el del job. Sin registro, o ya SIN_FACTURA, no hace nada.
  - `reintentar` sobre el registro de una NC (RECHAZADO o FUERA_DE_FECHA) ignora el receptor y los renglones que le pasa ventas: vuelve a copiar de la factura asociada.
  - `documento`, `consulta` y `resumenes` de una NC: clase NOTA_CREDITO, `codigo` 3, 8 o 13, y un campo nuevo `comprobanteAsociado` («Factura B 00001-00000045»; null en una factura).
- **C2 · Esquema.** La V37 es de `comprobante` (T2). La V38 es de `comprobante_fiscal` (T3): las columnas del asociado. Cada una tiene su test y su rollback con guarda. `MigracionV6Test` lista las dos y es de T2.
- **C3 · Comprobante de la NC** (T2 lo modela y T4 lo crea):
  - `tipo = NOTA_CREDITO`, `comprobante_asociado_id` (la venta, FK compuesta, UNIQUE) y `motivo_anulacion` (solo en la anulación interna);
  - `usuario_id` y `fecha` son quién y cuándo;
  - sin caja ni pagos;
  - los renglones (con su costo) y los totales copiados de la venta, en positivo; los lectores restan.
- **C4 · Lectores (T5):**
  - CU-15 resta la NC en su fecha y la atribuye a su usuario. «Por medio de pago» no la resta, y «Con descuento» lista solo ventas.
  - El resumen suma `notasDeCredito` (cantidad y total) y `gananciaSinNotas` / `ganancia`.
  - La lista de Ventas incluye las NC (`tipo`, `ventaAnulada { id, numero }`). Cada venta lleva `anulacion { notaId, numero, interna, motivo, usuario, fecha } | null`.
  - Los clientes del artículo no cuentan las ventas anuladas.
  - «Movimientos del artículo» etiqueta «Nota de crédito N.º n», o «Anulación N.º n» si tiene `motivo_anulacion`.

## Tasks

Orden: T1 → T2 → T3 ∥ T5 (superficies separadas: `facturacion` y los lectores) → T4 → T6 → T7 → T8.


- [x] T1 · S1, S11-S16 · ruta: inline · Aceptación: spec con D11, D12, D13 y los supuestos confirmados · Evidencia: `docs/spec.md` (CU-08, CU-15.2, CU-16.4, modelo, fuera del alcance) · Risk: none
- [x] T2 · S4, S9, S10, S11 · ruta: writer «ventas» (junto con T5) · Aceptación: V37 (`comprobante_asociado_id` con FK compuesta y UNIQUE, columnas de la anulación interna), `TipoComprobante.NOTA_CREDITO`, `MigracionV37Test` con rollback · Evidencia: `MigracionV37Test` RED 17 → GREEN 17/17, `NotaDeCreditoTest` 4/4, commit `f760592` · Risk: item 1 (migración)
- [x] T3 · S3, S8 · ruta: writer «facturacion» (worktree) · Aceptación: el motor da de alta la NC con su letra, `CbtesAsoc` y el receptor de la factura, en su propia serie; el simulador da 10197 y 10040; «Reintentar» de la bandeja sirve para una NC y copia el receptor y el asociado de la factura, no del cliente de hoy; la hoja de la NC · Evidencia: `MigracionV38Test` RED 1 falla + 22 errores → GREEN 23/23, `NotaDeCreditoIntegrationTest` RED (no compila) → GREEN 29/29, 270 focalizados verdes (en la copia del writer), commit `12641ed` · Risk: items 1, 2 y 4 (V38, cerrojo de ambiente, lock del registro y carrera con el job)
- [ ] T4 · S2-S6, S9, S13, S15 · ruta: writer «backend» · Aceptación: los endpoints del Dueño para la NC y la anulación, con la fachada con reintentos, el orden de locks, los 409 y el 422, el stock `NOTA_CREDITO`, el 403 y el 404 · Evidencia: — · Risk: items 2 y 4 (Dueño, locks y doble anulación)
- [x] T5 · S5, S7, S12, S14 · ruta: writer «ventas» (junto con T2) · Aceptación: los lectores de `tipo = VENTA` (`ComprobanteRepository:29,93,127,153,172`, `VentaGestion:187`, `MovimientoStockRepository:46`), los reportes de CU-15 y la lista de Ventas; un test de cobertura arma una venta con su NC y pide cada reporte de CU-15, la lista de Ventas, los movimientos y los clientes del artículo, y compara contra lo esperado, así un lector que falte se ve · Evidencia: `NotaDeCreditoEnLosLectoresIntegrationTest` RED 7 fallas + 3 errores → GREEN 11/11; 188 tests focalizados (los lectores y sus regresiones) verdes salvo `MigracionV6Test#historialDeMigraciones`, que espera la V38 · Risk: item 3 (lectores compartidos)
- [ ] T6 · S2, S7, S8, S13-S15 · ruta: writer «frontend» · Aceptación: «Anular» en el detalle, la confirmación, el diálogo de la anulación interna, la NC en la lista y su impreso, los reportes y los movimientos del artículo · Evidencia: — · Risk: none
- [ ] T7 · S10 · ruta: inline · Aceptación: suite backend, suite frontend, `ng build`, ego-browser (NC B y A, anulación interna, stock y reportes) y `odd-verifier` sin bloqueantes · Evidencia: — · Risk: —
- [ ] T8 · — · ruta: Luca · Aceptación: merge, push y deploy; la V37 aplicada en prod · Evidencia: — · Risk: item 5 (deploy)

## Log

- L1 (2026-10-29) Pedido original, textual:
  > continuamos
  >
  > dale me parece correcto
- L2 (2026-10-29) Exploración (`odd-explorer`; rutas bajo `backend/src/main/java/com/ferreteria`):
  - **Comprobante.**
    - `ventas/model/TipoComprobante.java:4` tiene solo `VENTA`, y la columna es texto sin CHECK.
    - La fábrica fija `tipo = VENTA` en `Comprobante.java:150-152`.
    - El numerador interno es `NumeradorComprobanteRepository`.
    - `uk_comprobante_ferreteria_y_id (ferreteria_id, id)` está en `V29:84`, así que sirve de destino para la FK compuesta.
    - La última migración es V36, y la V37 está libre (`MigracionV6Test:182`).
  - **Lectores de `tipo = VENTA` (ítem 3).**
    - `ComprobanteRepository.java`:
      - `:29`, `VENTAS_DEL_RANGO`, que usan los reportes de CU-15 en `:38/:49/:65/:75`;
      - `:93`, los filtros de Ventas;
      - `:127`, `ventasDelUsuarioEnLaCaja`, para el aviso del CAE;
      - `:153` y `:172`, consultas nativas con `v.tipo='VENTA'` (los clientes del artículo).
    - `VentaGestion.java:187`.
    - La caja suma pagos por `caja_id` (`PagoRepository.java:24`), así que una NC sin caja ni pagos queda afuera sola.
    - `stock/repository/MovimientoStockRepository.java:46` toma `c.numero` para la etiqueta.
  - **Motor fiscal.**
    - `Letra` ya tiene el `CbteTipo` de la NC (1/3, 6/8, 11/13).
    - `SerieFiscal` es por ambiente, punto de venta y `CbteTipo`, así que la NC tiene su serie sola.
    - `PlantillasWsfe.java:132-148` ya arma `CbtesAsoc`.
    - El simulador ya valida 10197, 10237 y 10040 (`ReglasWsfe.java:76,79,91,146-152`).
    - Falta: `AltaFiscal` (`facturacion/motor/AltaFiscal.java:19`) no lleva ni la factura asociada ni la letra.
    - Las transiciones condicionales están en `ComprobanteFiscalRepository.java:116-197`.
  - **Locks y reintentos.**
    - El orden está en `RegistroVenta.java:64-69`.
    - El molde de la fachada es `AnulacionService.java:31,54` con `RegistroAnulacion`.
  - **Stock.**
    - `TipoMovimiento` es texto, así que `NOTA_CREDITO` no necesita migración.
    - La venta de un remito mueve solo la diferencia (`StockService.facturarRemito` `:141-150`). La NC tiene que reponer lo entregado por el remito más esa diferencia.
    - En el front, `articulo.models.ts:174` y `movimientos-articulo.ts:14-18`.
  - **Ventas.**
    - `VentaController.java:109,119,128,137`, con `@PreAuthorize("hasRole('DUENO')")`.
    - La hoja: `HojaFiscal.java:79,131`; en el front, `venta-imprimible` → `app-venta-hoja`.
  - **Cuenta corriente.** `RegistroVenta.java:255` detecta un pago con `MedioPago.CUENTA_CORRIENTE`.
- L3 (2026-10-29) Luca preguntó «que pensas de esas tareas» y después respondió «listo continua»; el padre lo toma como confirmación de S11 a S16. Ajustes:
  - el orden queda T3 ∥ T5 y después T4;
  - T5 suma un test de cobertura de los lectores;
  - T3: «Reintentar» de una NC copia de la factura.
- L4 (2026-10-29) T1 cerrada: la spec. Decisiones técnicas del padre:
  - el contrato es `POST /api/ventas/{id}/anular`, con `{ "motivo": "…" }`, que solo vale en la anulación interna. Responde 201 con la NC;
  - anular una NC da 409 «Una nota de crédito no se anula.»;
  - el primer 400 del motivo se adapta a «Contá por qué se anula la venta.», porque el de «Dejar sin factura» habla de la factura. El de largo es el mismo.
  - Próximo paso: T2 (V37).
- L5 (2026-10-29) Reparto (padre). T2 + T5 van juntas en un writer «ventas», en el árbol principal, porque T5 necesita el tipo nuevo. T3 va en un writer «facturacion», en el worktree `../ferreteria-cu-08-motor` (rama `feat/cu-08-nc-motor`), así no comparten `backend/target`. Las superficies son disjuntas. T3 suma la V38 porque el asociado vive en el registro fiscal. Cuando terminen los dos, el padre integra la rama del motor y corre la suite una vez.
- L6 (2026-10-29) T2 y T5 cerradas (writer «ventas»).
  - **Arreglo del padre.** La etiqueta de «Movimientos del artículo» se arma en `stock/service/MovimientosArticulo.comprobante()`, que estaba fuera de la superficie del writer, así que la puso el padre. La proyección pedía un `Boolean` y MySQL devuelve `0/1` como `Long`, lo que daba un 500. Ahora se proyecta `motivoAnulacion` (texto) y se pregunta si es null. Prueba: `NotaDeCreditoEnLosLectoresIntegrationTest` + `MovimientosArticulo*Test` 16/16.
  - **Decisiones inferidas del writer:**
    - la NC es SIN_FACTURAR y la anulación interna NO_FACTURABLE;
    - la NC no copia `presupuesto_id`, `remito_id` (los dos UNIQUE) ni `caja_id`;
    - `Comprobante.notaDeCredito` exige una VENTA guardada y un motivo no vacío; los 400 los valida T4;
    - en CU-15, `cantidad`, `ventasSinCosto`, `renglones` y `renglonesSinCosto` cuentan solo ventas. Los importes van netos de NC, `notasDeCredito.total` es positivo y `gananciaSinNotas` es la de las ventas solas;
    - en la lista, la NC lleva el `total` positivo y el front la muestra en negativo por el `tipo`;
    - `anulacion` siempre viaja (null si no hay), y `ventaAnulada` solo en las NC;
    - `VentaGestion.venta(id)` sigue aceptando solo VENTA, porque la usan «Reintentar» y «Dejar sin factura»; el detalle y `/factura` usan `ventaONota(id)`;
    - el detalle (`VentaResponse`) todavía no trae `tipo` ni `ventaAnulada`.
  - **Para T4.** «Reintentar» sobre una NC hoy da 404, porque pasa por `venta(id)`. Tiene que usar `ventaONota`, sin ofrecer «Dejar sin factura» sobre una NC. Además, `VentaResponse` suma `tipo`, `ventaAnulada` y `anulacion`.
- L7 (2026-10-29) T3 cerrada (writer «facturacion»). El padre la trajo con un cherry-pick (`12641ed`) y borró el worktree.
  - **Arreglo del padre.** `ESPERAR_CAE` y `OTRO_AMBIENTE` rompían el `switch` exhaustivo de `FacturaDeLaVenta.rechazo`. Se mapean a `VentaRechazadaException.Motivo` con 409, sin commit todavía: va con T4.
  - **Firmas para T4:** `ViaDeAnulacion tomarParaAnular(long ventaId)` (`NOTA_DE_CREDITO`, `ANULACION_INTERNA`), `long registrarNotaDeCredito(long notaId, long ventaId)` y `void cerrarSinCae(long ventaId, String motivo, long usuarioId)`. `DocumentoFiscal` y `ResumenFiscal` suman `clase`, `codigo` y `comprobanteAsociado`.
  - **Decisiones inferidas del writer:**
    - la NC nace con `ComprobanteFiscal.notaDeCredito(notaId, factura, registradoEn)`, que copia todo del registro de la factura, alícuotas incluidas, sin recalcular; `AltaFiscal` no cambia;
    - la V38 suma seis columnas `asociado_*` (FK compuesta al registro de la factura), juntas y solo en NOTA_CREDITO. No hay UNIQUE por factura: una NC por venta sale de V37 más el vigente de V36;
    - `tomarParaAnular` lee el vigente y lo toma `FOR UPDATE` por PK, para no dejar gap locks contra el alta de otra venta. Si un «Reintentar» lo reemplazó, lanza `CannotAcquireLockException` para que la fachada reintente. Toma `config_arca` en S solo con AUTORIZADO;
    - con AUTORIZADO, primero mira OTRO_AMBIENTE y después FACTURACION_FRENADA;
    - `cerrarSinCae` valida el motivo con los textos de la spec, y con otro estado lanza `IllegalStateException`. `registrarNotaDeCredito` sin factura AUTORIZADO, también;
    - «Reintentar» de una NC copia de la factura y aplica OTRO_AMBIENTE (va más allá de C1, y es coherente con el cerrojo);
    - `comprobanteAsociado` es «Factura B 00001-00000045» en PRODUCCION y «X · <marca>» fuera de producción. El título «NOTA DE CRÉDITO» sale de `estadoFiscal.clase` (T6);
    - con el registro tomado por la anulación, `numerar` del job espera, después actualiza 0 filas y termina en `SERIE_PERDIDA`. El padre verificó que es transitorio, «de otra instancia» (`PasosFiscales.java:514-528`), así que no suma backoff ni traba la serie.
- L8 (2026-10-29) Suite backend sobre `12641ed` y el mapeo del switch: 5790 corridos, 2 fallas, ambas en `MigracionV4Test`: `sinColumnasDeMas` y `sinCheckEnTipoEstadoYMedio`. Ese test fija el diseño de `comprobante`. Se le suman las dos columnas de la V37 y `ck_comprobante_nota_de_credito`, que ata `NOTA_CREDITO` a su venta sin cerrar los valores de `tipo`. `MigracionV4Test` 15/15. La suite completa se vuelve a correr en T7. Próximo paso: T4.
