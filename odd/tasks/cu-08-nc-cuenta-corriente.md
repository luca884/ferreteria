# cu-08-nc-cuenta-corriente

Objetivo: que la NC (o la anulación interna) de una venta cobrada a cuenta corriente baje la deuda del cliente: acredita solo lo que la venta cargó a cuenta corriente, se imputa primero a esa factura y el resto queda a favor, sin romper las invariantes del libro. · Por qué: rama 10 de la fase 5 (`wiki/plan-facturacion.md` del vault, :614-651). D11 la confirmó (:822). Hoy esa anulación da 422 (`ventas/service/RegistroAnulacionDeVenta.java:126-129`).
Rama: `feat/cu-08-nc-cuenta-corriente` desde `309d91f` (`main`, ramas 1 a 8 en producción; la rama 9 espera su merge) · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Dtest=<Clase>` / `cd frontend && npx ng test --watch=false --include=<spec>` · Mirror Engram: `odd/cu-08-nc-cuenta-corriente/tasks` (ok)
Ruteo: caso 5, Opus de punta a punta (Luca: «dale», 2026-10-29). · Pronóstico: ~2.500 líneas (el plan dice 1.500 a 2.500) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance:
- La spec: CU-08 (cuenta corriente), CU-12 (libro, pendiente, crédito e invariantes con la NC) y el Modelo de datos.
- La V39 (el plan dice V38, ya usada por la rama 8).
- El puerto de `clientes.api`, la fábrica NC del libro y la imputación con origen NC.
- La anulación de ventas sin el 422 de cuenta corriente.
- El extracto con la NC.

Fuera de alcance (plan):
- Devolver en efectivo un saldo a favor.
- Aplicar a mano el saldo a favor a pendientes viejos.

Restricciones:
- La ferretería sale solo del JWT. `ventas` habla con `clientes` solo por `clientes.api` (`ModulosArchTest:474`); solo `CuentaCorrienteService` llama a las fábricas del libro y de la imputación (`ModulosArchTest:574-601`, `Set.of` de nombres de método).
- El libro es append-only y el saldo se escribe con un UPDATE relativo (`sumarSaldoScoped`).
- Orden de locks de la anulación (`RegistroAnulacionDeVenta:36-45`): cliente (X) → venta (X) → lecturas planas → registro fiscal → numerador → NC → stock → renglones → registro fiscal de la NC. El asiento de la cuenta va después del comprobante de la NC y respeta el orden de la cobranza dentro de `clientes`: partidas (id ascendente) → imputado (FOR UPDATE) → libro y saldo (`docs/spec.md:363`).
- Las lecturas de crédito y de lo imputado van con FOR UPDATE (`CuentaCorrienteService.creditoDisponible`, :404-440).
- Cada commit con un ítem de riesgo va aislado, y lo verifica `odd-verifier` (opus) al final.

- S1 (plan, migración): "V38: nc_origen_id en imputacion, con FK compuesta. ck_imputacion_origen se amplía a «exactamente uno de recibo, ajuste o NC», con DROP y ADD en una sola ALTER (como V21), más los únicos por par." — Aceptación: V39 con el CHECK nuevo (nombre nuevo, como `_v21`), FK compuesta `(ferreteria_id, nc_origen_id)` con su índice (patrón V29), únicos por par; `MigracionV39Test` con el rollback probado y la guarda «sin NC»; la cadena de rollbacks (el de V28 hace `DROP CHECK ck_imputacion_origen`) sigue andando. El plan dice «MigracionV6Test la incluye», pero esa es de catálogo: la cubre `MigracionV39Test`.
- S2 (plan, puerto): "Método nuevo del puerto en clientes.api, que hoy «no declara nada de nota de crédito» (spec.md:365). Fábrica NC del libro, sumada a la lista cerrada de ModulosArchTest." — Aceptación: el método nuevo vive en `clientes.api`, la fábrica `nc` en `MovimientoCuentaCliente` y el origen NC en `Imputacion`; `ModulosArchTest` la suma al `Set` y exige que exista.
- S3 (plan, reglas): "La NC acredita solo lo que la venta cargó a cuenta corriente, se imputa primero a esa factura y el resto queda a cuenta. Las lecturas de crédito van con FOR UPDATE. Se extienden I1, I2, I2f e I-inactivo. El cliente tiene que estar activo, como en la anulación." — Aceptación: con una venta de $ 1.000 en efectivo y $ 500 a cuenta, la NC acredita $ 500; si la factura debía $ 500, queda pagada; si ya estaba cobrada, quedan $ 500 a favor; `InvarianteCuenta` cubre la NC en I1, I2a, I2c, I2f e I-inactivo.
- S4 (plan): "Se quita el 422 de la rama 8, y el extracto filtra por el tipo NC." — Aceptación: anular una venta a cuenta corriente da 201; el extracto muestra la NC y filtra por ella.
- S5 (plan, terminado): "I1, I2, I2e, I2f e I-inactivo se cumplen después de NC normales, repetidas, rechazadas y concurrentes con cobranzas y ventas a cuenta del mismo cliente. La NC acredita solo la parte a cuenta, se imputa primero a la factura y el extracto la muestra. V38 va en una sola ALTER, con rollback probado y guarda «sin NC». MigracionV6Test la incluye. odd-verifier sin bloqueantes sobre el commit de la migración." — Suma: suites, `ng build` si cambia el frontend, ego-browser.

Decisiones de producto (S6 a S9; Luca: «dale», 2026-10-29, L3):
- S6 (confirmado por Luca el 2026-10-29): la anulación interna (venta sin CAE, rama 8 S11) de una venta a cuenta corriente acredita igual que la NC: tiene los mismos efectos.
- S7 (confirmado por Luca el 2026-10-29): lo que sobra queda a favor y se usa solo en la próxima venta a cuenta corriente del cliente, igual que el crédito de un recibo (`consumirCredito`). La parte pagada en efectivo sigue D11: no se toca, el Dueño carga un egreso.
- S8 (confirmado por Luca el 2026-10-29): «el cliente tiene que estar activo» vale solo para la venta que cargó a cuenta corriente: con el cliente desactivado, 422 «El cliente está desactivado.». Las demás ventas se siguen anulando como hoy aunque el cliente esté desactivado (`ClienteDelDocumento.java:77-86`).
- S9 (confirmado por Luca el 2026-10-29): en el extracto la NC se ve como «Nota de crédito N.º n» o «Anulación N.º n», con la factura que anula, en el haber; no es anulable.

## Tasks

- [ ] T1 · S1-S9 · ruta: inline · Aceptación: commit docs con CU-08, CU-12 (pendiente, crédito, I2 con la NC) y el Modelo de datos · Evidencia: — · Risk: none
- [ ] T2 · S1 · ruta: inline · Aceptación: V39 + `MigracionV39Test` (aplica, CHECK, FK, únicos, rollback con guarda, cadena con V28) · Evidencia: — · Risk: item 1 (CHECK no aditivo sobre `imputacion`)
- [ ] T3 · S2, S3, S7 · ruta: inline · Aceptación: puerto, fábrica `nc`, origen NC de la imputación, NC como crédito en `creditoDisponible`, `InvarianteCuenta` ampliada y `ModulosArchTest`; tests de servicio de la imputación (a la factura, a favor, consumo posterior) · Evidencia: — · Risk: item 3 (puerto y lista cerrada), item 4 (locks y lecturas de crédito)
- [ ] T4 · S3, S4, S6, S8 · ruta: inline · Aceptación: `RegistroAnulacionDeVenta` sin el 422, llamando al puerto con la parte a cuenta corriente, en NC y anulación interna; cliente activo para la venta a cuenta; S6 de `AnulacionDeVentaIntegrationTest` reescrito; concurrencia NC contra cobranza y venta del mismo cliente · Evidencia: — · Risk: item 4 (orden de locks)
- [ ] T5 · S4, S9 · ruta: inline · Aceptación: extracto con la NC (backend y pantalla), confirmación de anular en Ventas si cambia el texto · Evidencia: — · Risk: none
- [ ] T6 · S5 · ruta: inline + `odd-verifier` (opus) · Aceptación: suites, `ng build`, ego-browser, verificador sin bloqueantes sobre T2, T3 y T4 · Evidencia: — · Risk: —

## Log

- L1 (2026-10-29) Pedido original, textual:
  > dale continuamos
  (tras proponer la rama 10 con el caso 5; Luca: «dale»).
- L2 (2026-10-29) Mapa (odd-explorer, sonnet), con el 422 verificado por el padre en `RegistroAnulacionDeVenta.java:126`:
  - el tipo `NC` ya existe en el libro (`ck_mcc_tipo_v21`, NC con haber > 0, sin motivo por `ck_mcc_motivo`), e `InvarianteCuenta` ya suma la NC aparte en I2c (`test/.../support/InvarianteCuenta.java:244-275`);
  - `ck_imputacion_origen` es de V28 (`V28__ajustes_cuenta_corriente.sql:80-91`): `(recibo_id IS NULL) <> (ajuste_origen_id IS NULL)`; las FK de `imputacion` son compuestas desde V29 (:162-177);
  - `CuentaCorrienteParaVenta` (`clientes/api`, :15-29) solo tiene `verificarCupo` y `asentarVenta`; lo implementa `CuentaCorrienteService` (:58, escrituras con `MANDATORY`);
  - `creditoDisponible` (:404-440) hace `switch` sobre COBRO, ANULACION y AJUSTE y tira `IllegalStateException` con cualquier otro: la NC como crédito lo tiene que ampliar;
  - `imputarContraPartidas` (:249-322) es el algoritmo a reusar para imputar a la factura;
  - el extracto ya filtra por `TipoMovimientoCuenta` y `NC` está en el enum (`ExtractoCuenta.java:80-120`); falta ver la pantalla;
  - el libro no tiene columna para el comprobante de la NC: `comprobante_id` en el renglón NC lo permite `ck_mcc_recibo_ref`.
  Desfases del plan: V38 → V39; «como V21» es el patrón, pero el CHECK es de V28; «MigracionV6Test» → `MigracionV39Test`.
- L3 (2026-10-29) Luca confirmó S6 a S9: «dale». Por el contexto del padre (backstop de ~150k), la implementación va con writers `odd-implementer` (opus, caso 5) en serie: W1 = T1+T2, W2 = T3, W3 = T4+T5; el padre commitea cada unidad de riesgo aparte.
