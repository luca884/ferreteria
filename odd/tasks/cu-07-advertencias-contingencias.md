# cu-07-advertencias-contingencias

Objetivo: corregir las advertencias A1, A10 y A11 de la rama 7 (`odd/tasks/cu-07-contingencias.md`, L11) · Por qué: un «Reintentar» con la fecha original puede terminar FUERA_DE_FECHA, un FUERA_DE_FECHA del job no dice por qué y el aviso de pendientes viejas salta con un reintento recién hecho.
Rama: `fix/cu-07-advertencias-contingencias` desde `730cc42` · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Dtest=<Clase>` / `cd frontend && npx ng test --watch=false --include=<spec>` · Mirror Engram: `odd/cu-07-advertencias-contingencias/tasks` (ok) · Ruteo: caso 5, Opus de punta a punta (aprobado: «dale me parece correcto, continua»)
Pronóstico: ~300 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: las tres advertencias, en backend (`facturacion`, `ventas/web`) y en el detalle de Ventas del frontend · Fuera de alcance: A9 (10016 por una regla de fecha que `VentanaCbteFch` no modela), migraciones, el texto del 409 `FECHA_NUEVA_REQUERIDA`.
Restricciones: la fecha nunca cambia sola (rama 7, S5); sin cambios de esquema; el 10016 de ARCA (que ya guarda sus errores) no cambia.

- S1 (A1): "un «Reintentar» con la fecha original queda detrás de un pendiente de hoy de la misma serie y puede terminar FUERA_DE_FECHA" — Comportamiento aprobado: si en la serie hay un registro en trámite (PENDIENTE, EN_CURSO o EN_VERIFICACION) con una fecha posterior a la de la venta, la fecha original ya no entra: el reintento sin confirmar da 409 `FECHA_NUEVA_REQUERIDA` («La fecha de la venta ya no entra en ARCA. Confirmá que la factura salga con la fecha de hoy.») y el detalle marca `fechaNuevaRequerida`. Vale para la factura (`reintentar`), la nota de crédito (`reintentarNota`) y el detalle (`fechaEntraHoy`). — Checks: `./mvnw test -Dtest=AccionesDelDuenoIntegrationTest`
- S2 (A10): "un FUERA_DE_FECHA del job no muestra el motivo en el detalle" — Comportamiento aprobado: un FUERA_DE_FECHA sin errores guardados muestra en el detalle de Ventas, textual:
  - «ARCA ya no acepta la fecha de esta venta (28/10/2026): admite hasta 5 días antes de hoy. Reintentá con la fecha de hoy o dejala sin factura.»
  - «ARCA ya no acepta la fecha de esta venta (28/10/2026): ya hay una factura autorizada con fecha posterior (30/10/2026) y las fechas no pueden ir para atrás. Reintentá con la fecha de hoy o dejala sin factura.»
  Se arma al leer (sin guardar nada). El FUERA_DE_FECHA por el 10016 de ARCA, que trae sus errores, no lo lleva. — Checks: `./mvnw test -Dtest=BandejaDeFacturasIntegrationTest`, `npx ng test --watch=false --include=src/app/features/ventas/ventas.spec.ts`
- S3 (A11): "C4 cuenta como «vieja» un reintento con la fecha original" — Comportamiento aprobado: en un reintento, los 30 minutos del aviso «pendientes viejas» (rama 7, S11) se cuentan desde el reintento (el `reemplazado_en` del registro que reemplazó), no desde la venta. — Checks: `./mvnw test -Dtest=AvisosDelMostradorIntegrationTest`
- S4 (supuesto): en S2, si ninguna de las dos condiciones vale al leer (solo pasaría con una fecha futura de otro mes), no se muestra motivo.

## Tasks

- [x] T1 · S1 · ruta: inline · Aceptación: tests del reintento de factura y de NC, y del detalle, con un pendiente posterior en la serie · Evidencia: RED `laFechaNoEntraSiLaSerieTieneUnPendientePosterior` («Expecting value to be false but was true») y `unaNcRechazadaConUnaPendientePosteriorPideLaFechaDeHoy` («Expecting code to raise a throwable»); control `unPendienteDelMismoDiaNoImpide` verde → GREEN `AccionesDelDuenoIntegrationTest` 13/0, `NotaDeCreditoIntegrationTest` 30/0 (`ComprobanteFiscalRepository.ultimaHoraEnTramite`, `PasosFiscales.ultimaParaReintentar`) · Risk: item 3 (`PasosFiscales.reintentar` y `fechaEntraHoy`, compartidos)
- [x] T2 · S2, S4 · ruta: inline · Aceptación: los dos textos en el detalle por HTTP y en la pantalla · Evidencia: RED `BandejaDeFacturasIntegrationTest` (3 fallas y 1 error: sin `motivoFueraDeFecha`) → GREEN 10/0; `VentaFacturadaDocumentoIntegrationTest` (fija la forma del detalle) 16/0 con el campo nuevo en null; `AccionesDeFacturaIntegrationTest` 9/0; front RED por compilación → GREEN `ventas.spec.ts` 45/45 (`BandejaFiscal.motivoFueraDeFecha`, `PasosFiscales.fechasDelRegistro`, `.motivo-fuera-de-fecha` con el estilo de aviso) · Risk: item 3 (contrato `FacturaConsultada` / `FiscalConsultadoResponse`, campo agregado)
- [x] T3 · S3 · ruta: inline · Aceptación: un reintento con la fecha original no cuenta como viejo hasta 30 minutos después del reintento · Evidencia: RED `unReintentoCuentaDesdeElReintento` («expected: 0 but was: 1» recién reintentada) → GREEN `AvisosDelMostradorIntegrationTest` 5/0 (consulta `avisos` con `not exists` sobre `reemplazado_en >= :limite`) · Risk: item 3 (consulta `avisos`)
- [ ] T4 · S1-S4 · ruta: inline (suites) + `odd-verifier` · Aceptación: suite backend, suite frontend, `ng build`, ego-browser del detalle y verificador sin bloqueantes · Evidencia: — · Risk: item 3

## Log

- L1 (2026-11-03) Pedido original, textual:
  > vamos a arreglar lo mas posible vamos con las 3
  Aprobación del comportamiento y los textos: «dale me parece correcto, continua».
- L2 (2026-11-03) Exploración (odd-explorer, verificado `PasosFiscales.java:620-673`):
  - A1: el reintento decide con `VentanaCbteFch.admite(fecha original, hoy, serie_fiscal.ultima_cbte_fch)` (`PasosFiscales.java:636-641`, `:661-666`, y `fechaEntraHoy` `:568-573`); no mira la cola. La cola es FIFO por id (`ComprobanteFiscalRepository.siguientePendiente`), el reintento tiene id nuevo, y la CbteFch de un PENDIENTE es la fecha de `registrado_en` (`MotorFiscal.java:278`).
  - A10: tres caminos a FUERA_DE_FECHA; solo `fueraDeFechaPorArca` (10016) guarda errores. `fueraDeFecha` (ventana vencida, `MotorFiscal.java:279-280`) y `vencerSinReenvio` (`:472-473`) no guardan nada. El detalle sale de `BandejaFiscal.consultada` → `FacturaConsultada` → `VentaResponse.FiscalConsultadoResponse` → `ventas.html:189-197`.
  - A11: `comprobante_fiscal` no tiene hora de alta de la fila; el reintento con la fecha original copia `registrado_en`, y el momento del reintento queda en `reemplazado_en` del registro reemplazado (`PasosFiscales.java:644`).
- L3 (2026-11-03) Próximo paso: T1.
