# cu-03-configuracion-precios

Objetivo: el Dueño edita el margen por defecto y el múltiplo de redondeo de su ferretería, y los precios afectados se recalculan · Por qué: `/api/configuracion-precios` solo tiene GET; GDS deja configurar ganancia y redondeo (G13, G26)
Rama: `feat/cu-03-configuracion-precios` desde `3660596` · Entrega: single-pr · Runner: `./mvnw test -Dtest=<Clase>` (backend), `npx ng test --watch=false --include=<spec>` (frontend) · Mirror Engram: `odd/cu-03-configuracion-precios/tasks` (ok)
Pronóstico: ~1400 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite) · Modelo: caso 5 (grande y de alto riesgo: cambia precios guardados), Opus de punta a punta

## Specs

Alcance: editar margen por defecto y múltiplo de redondeo; recalcular precios; pantalla del Dueño; spec y relevamiento · Fuera de alcance: redondeo del total del comprobante (G52), redondeo distinto por operación en la actualización masiva
Restricciones: solo el Dueño; la ferretería sale del JWT; plata en BigDecimal; precio manual no cambia (CU-14)

- S1: "vamos con gds" (2026-10-23), sobre la propuesta: «el Dueño edita los dos valores. El redondeo sigue en 0 hasta que lo cambie.» — Aceptación: `PUT /api/configuracion-precios` guarda margen y múltiplo de la ferretería del token; Vendedor y super admin 403; otra ferretería no ve ni cambia nada — Checks: `./mvnw test -Dtest=ConfiguracionPreciosIntegrationTest`
- S2: «Al guardar, se recalculan los artículos que heredan el margen por defecto.» — Aceptación: con cambio solo de margen, se recalculan los artículos activos sin margen propio y sin rubro; historial MASIVO por cada precio que cambia — Checks: `./mvnw test -Dtest=ConfiguracionPreciosServiceTest`
- S3: «Si cambia el múltiplo, se recalculan todos los artículos sin precio manual» — Aceptación: con cambio de múltiplo, se recalculan todos los artículos activos sin precio manual — Checks: `./mvnw test -Dtest=ConfiguracionPreciosServiceTest`
- S4: «con vista previa del impacto antes de confirmar.» — Aceptación: la pantalla cuenta cuántos artículos se recalculan con los valores nuevos y pide confirmación, como el margen del rubro — Checks: `npx ng test --watch=false --include=src/app/features/configuracion-precios/**`
- S5 (supuesto): la cascada toca solo artículos activos, como la del margen del rubro (`ArticuloRepository.heredanMargenDeRubroScoped`). Los en dólares usan el dólar vigente; sin cotización guardada, 409 y no se guarda nada.
- S6 (supuesto): validaciones como el margen del rubro (0 a 1000 %, 2 decimales). Múltiplo entre 0 y 1000, 2 decimales; 0 es sin redondeo.
- S7 (supuesto): un artículo cuyos ajustes dejarían el precio en 0 o menos conserva su precio y vuelve en los avisos, como en las otras cascadas.

## Tasks

- [x] T1 · S2, S3, S5, S7 · ruta: inline · Aceptación: servicio actualiza la configuración, cuenta afectados y recalcula · Evidencia: RED (no compila) → GREEN `RecalculoPreciosServiceTest` + `ConfiguracionPreciosServiceTest` 34/34 · commit `76e1a6e` · Risk: item 1 (cambia precios guardados)
- [x] T2 · S1, S5, S6 · ruta: inline · Aceptación: PUT y conteo por API, seguridad, aislamiento por tenant · Evidencia: RED (404/405) → GREEN `ConfiguracionPreciosIntegrationTest` 10/10, ArchUnit 64/64 · commit `76e1a6e` · Risk: item 1
- [x] T3 · S4 · ruta: inline · Aceptación: pantalla `/configuracion/precios`, menú, confirmación con conteo, verificada con ego-browser · Evidencia: pantalla y servicio sin RED observado (implementación escrita antes de correr los specs) → 18/18; ruta y menú RED (4) → GREEN; suite 2923/2923; `ng build` OK (aviso de budget de `dialogo-cobro`, previo); ego-browser con El Tornillo: 5358 contados, 5097 recalculados con múltiplo 0,50; volver a 0 tardó ~4 s · Risk: none
- [x] T4 · S1 · ruta: inline · Aceptación: spec (regla de redondeo, H11) y relevamiento GDS actualizados · Evidencia: `docs/spec.md` CU-03 punto 3 y regla de redondeo; `docs/relevamiento-gds.md` 4.6 y 4.9 · Risk: none

## Log

- L1 (2026-10-23) Pedido original, textual:
  > vamos con gds
  Respuesta a la propuesta: «Mi recomendación es ir como GDS: el Dueño edita los dos valores. El redondeo sigue en 0 hasta que lo cambie. Al guardar, se recalculan los artículos que heredan el margen por defecto. Si cambia el múltiplo, se recalculan todos los artículos sin precio manual, con vista previa del impacto antes de confirmar.»
- L2 (2026-10-23) Exploración: GET en `backend/src/main/java/com/ferreteria/ferreteria/web/ConfiguracionPreciosController.java`; campos en `Ferreteria.java:44-52` (margen 30, múltiplo 0); cascada del rubro en `RecalculoPreciosService.java:62` con conteo previo (`RubroService.java:81`, `rubros.ts:97`); la regla H11 está en `docs/spec.md:245`. El margen del rubro nunca es null, así que el margen por defecto solo llega a los artículos sin margen propio y sin rubro. Reactivar no recalcula precios (`ArticuloService.java:140`).
