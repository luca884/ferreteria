# cu-10-egresos-contra-efectivo

Objetivo: un gasto o retiro mayor al efectivo esperado de la caja no pasa sin control · Por qué: hoy deja la caja en negativo sin aviso (E2E 2026-10-22, ítem 1; GDS-36 / G64)
Rama: `feat/cu-10-egresos-contra-efectivo` desde `b81e7f8` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / suite `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` + `npx ng build` · Mirror Engram: `odd/cu-10-egresos-contra-efectivo/tasks` (ok)
Pronóstico: ~350 líneas cambiadas · Heurística por tarea: ~400 (no es límite) · Modelo: caso 5 (Opus de punta a punta: concurrencia)

## Specs

Alcance: gasto y retiro de la caja abierta (POST `/api/cajas/{id}/movimientos`) y su formulario en la pantalla de caja · Fuera de alcance: anulación de cobranzas en efectivo, ingresos, ventas.
Restricciones: cierre ciego (al Vendedor nunca se le muestran totales de la caja abierta); el retiro ya es solo del Dueño; mismo patrón que el límite de cuenta corriente (CC-005/CC-013).

- S1: "me parece correcto" (Luca, 2026-10-22) sobre la propuesta: "Vendedor: el sistema lo frena, salvo que lo autorice el dueño." — Aceptación: un gasto del Vendedor cuyo importe supera el efectivo esperado da 422 sin cifras y no se registra — Checks: test de integración
- S2: misma aprobación sobre: "Dueño: ve un aviso y puede confirmar igual." — Aceptación: el Dueño sin confirmación recibe 422 con efectivo disponible, importe y faltante; con `confirmaSinEfectivo: true` se registra — Checks: test de integración + pantalla
- S3 (supuesto): igual al efectivo esperado entra (la caja queda en 0); solo "mayor" frena.
- S4 (supuesto): el Vendedor que manda `confirmaSinEfectivo: true` ante un faltante recibe 403, como el `autorizaExceso` de cuenta corriente.
- S5: dos egresos a la vez no pueden pasar los dos el control (lock exclusivo de la caja para gasto y retiro) — Aceptación: test de concurrencia — Checks: test de integración
- S6: aislamiento por tenant (regla 7 de CLAUDE.md): la caja de otra ferretería sigue dando 404 — Checks: test existente/nuevo

## Tasks

- [x] T1 · S1 S2 S3 S4 S6 · ruta: inline · Aceptación: dominio + servicio + endpoint con tests de integración · Evidencia: commit `c0b02e5` · RED `CajaIntegrationTest$EgresosContraElEfectivo` 4 fallas (201 en vez de 422/403) → GREEN 48/48 · Risk: item 4 (concurrencia del control)
- [x] T2 · S5 · ruta: inline · Aceptación: lock exclusivo para egresos con test de concurrencia · Evidencia: commit `c0b02e5` · RED `EgresosConcurrentesIntegrationTest` "hay 0" en LOCK WAIT → GREEN junto con `CajaIntegrationTest` y `CierreDeCajaContraLectoresIntegrationTest` · Risk: item 4
- [x] T3 · S1 S2 · ruta: inline · Aceptación: pantalla de caja muestra el rechazo al Vendedor y el aviso con «Registrar igual» al Dueño; verificado con ego-browser · Evidencia: commit `d888c00` · RED 2 fallas → GREEN `caja.spec.ts` 21/21 · ego-browser OK · Risk: none

## Log

- L1 (2026-10-22) Pedido original, textual:
  > me parece correcto

  Sobre la pregunta: "hoy un retiro o un gasto mayor al efectivo de la caja pasa sin aviso y la caja queda en negativo. El relevamiento (GDS-36) solo dice "aviso o autorización". Te recomiendo copiar lo que ya hace el límite de cuenta corriente: Vendedor: el sistema lo frena, salvo que lo autorice el dueño. Dueño: ve un aviso y puede confirmar igual."
- L2 (2026-10-22) Exploración: `CajaService.registrarMovimiento` (backend/src/main/java/com/ferreteria/ventas/service/CajaService.java:110) toma la caja con lock compartido (`CajaRepository.paraMovimientoScoped`, :78), así que dos egresos simultáneos leerían el mismo esperado. El esperado sale de `ResumenCaja.efectivoEsperado()` vía `CajaService.resumen` (:227). Patrón de autorización: `RegistroVenta.verificarCupo` (:232) y `VentaRechazadaResponse.CuentaCorrienteResponse`. Front: `frontend/src/app/features/caja/caja.ts:152`.
- L3 (2026-10-22) Progreso: T1-T3 hechas. Suite backend 4399 tests, 0 fallas; suite frontend 2832/2832; `ng build` OK. ego-browser: el vendedor abre la caja con $ 100 y un gasto de $ 150 da «No hay efectivo suficiente en la caja para este gasto. Pedile al Dueño que lo cargue.» sin botón de confirmar; el dueño ve «No alcanza el efectivo de la caja para este gasto. Hay $ 100,00 en efectivo y faltan $ 50,00: la caja va a quedar en negativo.» y «Registrar igual» lo carga. `historialDelDueno` hacía un gasto con la caja en 0: ahora lo carga el dueño confirmando (mismo esperado -50 y diferencia 50). Tamaño: ~594 líneas (>400, single-pr, sin partir). · Pendiente: `odd-verifier` (Risk item 4). · Próximo paso: verificación independiente.
