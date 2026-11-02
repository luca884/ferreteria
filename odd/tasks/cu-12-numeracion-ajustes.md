# cu-12-numeracion-ajustes

Objetivo: los Recargos y los Descuentos de la cuenta corriente se numeran cada uno con su propio numerador, como GDS (G95). Por qué: hoy comparten un solo numerador y el primer Descuento puede salir «Descuento N.º 2», lo que confunde en el extracto (hallazgo de la sección 19 de la Ferretería de Prueba).
Rama: `fix/cu-12-numeracion-ajustes` desde `b941b1f` · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Dtest=<Clase>` / suite `./mvnw test` · Mirror Engram: `odd/cu-12-numeracion-ajustes/tasks` (ok)
Pronóstico: ~350 líneas cambiadas · Ruteo: caso 5 (Opus de punta a punta: migración de datos, riesgo alto) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: numerador de ajustes por ferretería y por tipo, la migración V40 con su rollback, y la spec. Fuera de alcance: renumerar los ajustes que ya existen (sus referencias están copiadas en el libro y pueden estar impresas); el frontend (la referencia la arma el backend).
Restricciones: los ajustes existentes conservan su número; el orden de locks del ajuste no cambia (cliente → numerador → partidas, S6 de cu-12-ajustes-cuenta-corriente).

- S1: "dale hacelo com gds" y "dale me parece bien vamos con eso como hace gds" (Luca, 2026-11-01) — Aceptación: el primer Recargo y el primer Descuento de una ferretería salen N.º 1 cada uno, y cada tipo sigue correlativo por su lado («Descuento N.º 1» aunque antes haya Recargos); dos ferreterías no se mezclan — Checks: `./mvnw test -Dtest=AjusteCuentaIntegrationTest`
- S2 (supuesto, explicado a Luca antes del «dale»): los datos existentes no cambian; después de la migración cada tipo sigue desde su número más alto actual en esa ferretería — Aceptación: con Recargo 1 y Descuento 2 cargados antes de la V40, el próximo Recargo sale 2 y el próximo Descuento 3 — Checks: `./mvnw test -Dtest=MigracionV40Test`
- S3 (supuesto): el rollback de la V40 vuelve al numerador único solo si no hay dos ajustes de la misma ferretería con el mismo número; si los hay, se niega sin tocar nada — Checks: `./mvnw test -Dtest=MigracionV40Test`

## Tasks

- [x] T1 · S2, S3 · ruta: inline · Aceptación: V40 (numerador con `tipo` y único por ferretería y tipo, filas por tipo con el máximo actual, único de `ajuste_cuenta` por ferretería, tipo y número) y `rollback-v40` probados · Evidencia: `MigracionV40Test` RED 6/6 «No migration with a target version 40» → GREEN 6/6 · Risk: item 1 (migración que cambia datos guardados)
- [x] T2 · S1 · ruta: inline · Aceptación: `RegistroAjuste` pide el número del tipo del ajuste; `AjusteCuentaIntegrationTest#numeracion` reescrito para S1; historial de `MigracionV6Test` con la 40 · Evidencia: `AjusteCuentaIntegrationTest` RED 18/24 (500: el UPSERT viejo no escribe `tipo`) → GREEN 24/24; `ModulosArchTest` 50, `TenantScopingArchTest` 26, `MigracionV6Test` 14 · Risk: item 1 (escribe con el esquema nuevo)
- [ ] T3 · S1 · ruta: inline · Aceptación: spec actualizada (CU-12 API y nota de V28/V40), suite backend verde, `odd-verifier` por el riesgo 1 · Evidencia: — · Risk: —

## Log

- L1 (2026-11-01) Pedido original, textual:
  > dale hacelo com gds, pero antestengo una duda porque vaias sesiones se habal de un contador, explicame a que te refiers
  >
  > dale me parece bien vamos con eso como hace gds
- L2 (2026-11-01) Exploración: el numerador es una fila por ferretería (`V28__ajustes_cuenta_corriente.sql:39`, `uk_numerador_ajuste_cuenta_ferreteria`); `ajuste_cuenta` tiene `uk_ajuste_cuenta_numero (ferreteria_id, numero)` (V28:63), que hay que pasar a (ferreteria_id, tipo, numero) o el primer Descuento chocaría con el Recargo N.º 1. Único llamador: `RegistroAjuste.java:102`. `AjusteCuentaIntegrationTest#numeracion` (línea 254) fija hoy la numeración compartida. GDS numera cada tipo de comprobante por separado (`docs/relevamiento-gds.md` G95). En producción, la Ferretería de Prueba tiene Recargo N.º 1 y Descuento N.º 2.
