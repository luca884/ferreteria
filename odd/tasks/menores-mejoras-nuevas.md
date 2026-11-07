# menores-mejoras-nuevas

Objetivo: cerrar las tres decisiones de cierre de las mejoras nuevas que llevan código (1, 5 y 6) · Por qué: las vio el recorrido de PR #25 y #26 y Luca aprobó cada recomendación
Rama: `fix/menores-mejoras-nuevas` desde `88d259df` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / `npx ng test --watch=false` · Mirror Engram: `odd/menores-mejoras-nuevas/tasks` (ok) · Modelo: Opus de punta a punta (Luca: «dale, aprobado, andá con Opus»)
Pronóstico: ~150 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: las decisiones 1, 5 y 6 de `estado-actual.md` (sesión 64, «Decisiones») · Fuera de alcance: las decisiones 2, 3 y 4 («quedan como están»), #36, #26, #97 y la guía de uso
Restricciones: mensajes al usuario en español rioplatense; el contador nuevo no cambia los otros modos del masivo ni la huella

- S1: "«Vaciar» de Etiquetas sin confirmación → pedir confirmación con `app-confirmacion`, como «Vaciar» del Mostrador." — Detalle (supuesto, copia del Mostrador): título «¿Vaciar la lista de etiquetas?», mensaje «Se saca 1 artículo.» / «Se sacan N artículos.», botón «Vaciar» en rojo y el foco en «Cancelar». Cancelar (o Esc) no toca la lista — Aceptación: con artículos en la lista, «Vaciar» abre la confirmación; «Cancelar» deja la lista y lo guardado como estaban; «Vaciar» del diálogo hace lo de hoy (saca todo y borra lo guardado) — Checks: `etiquetas.spec.ts`
- S2: "La tarjeta «Sin cambio de precio» de «Cambiar el rubro» mezcla «ya en el destino» y «solo cambia el rubro» → separarla en dos, en una tanda de menores." — Detalle (supuesto técnico): `ConteosMasivos` suma `yaEnElDestino` (solo cuenta en `CAMBIAR_RUBRO`; 0 en los otros modos; `sinCambioDePrecio` sigue igual, así que `total = suben + bajan + sinCambioDePrecio + conError` no cambia). Con «Cambiar el rubro», la vista previa muestra «Ya en el destino» (`yaEnElDestino`) y «Solo cambia el rubro» (`sinCambioDePrecio − yaEnElDestino`) en lugar de «Sin cambio de precio»; los otros modos siguen con sus tarjetas. CU-05 de la spec lo menciona — Aceptación: con un filtro que tenga uno ya en el destino, uno con margen propio y uno heredado, la vista previa dice Suben 1, Ya en el destino 1, Solo cambia el rubro 1; «Cambiar el costo en %» sigue mostrando «Sin cambio de precio» — Checks: `PlanificadorMasivoTest`, `ActualizacionMasivaServiceTest`, `OperacionMasivaTest`, `actualizacion-masiva.spec.ts`
- S3: "La confirmación del margen del rubro usa el nombre viejo si cambian nombre y margen juntos → usar el nombre nuevo, en la misma tanda." — Aceptación: editar «Bulonería» a «Bulones» y el margen 35 → 45 pregunta «¿Cambiar el margen de Bulones de 35 % a 45 %?» — Checks: `rubros.spec.ts`

## Tasks

- [ ] T1 · S1 · ruta: inline · Aceptación: la de S1 · Evidencia: — · Risk: —
- [ ] T2 · S2 · ruta: inline · Aceptación: la de S2 · Evidencia: — · Risk: —
- [ ] T3 · S3 · ruta: inline · Aceptación: la de S3 · Evidencia: — · Risk: —

## Log

- L1 (2026-11-07) Pedido original, textual:
  > continuamos

  Con la propuesta (las tres decisiones que llevan código, rama `fix/menores-mejoras-nuevas`, Opus de punta a punta), Luca: «dale, aprobado, andá con Opus». Las decisiones las había aprobado en la sesión 64: «dale, aprobado todo como recomendás, cerrá sesión».
- L2 (2026-11-07) Exploración: `vaciar()` en `frontend/src/app/features/etiquetas/etiquetas.ts:293`, botón en `etiquetas.html:4`; el Mostrador confirma con su propio diálogo (`features/mostrador/dialogo-confirmacion.ts:63-72`: «¿Vaciar el ticket?», «Se pierden N renglones.», foco en Cancelar), las otras pantallas usan `shared/confirmacion.ts` (`app-confirmacion`). El contador sale de `PlanificadorMasivo.java:74` (un artículo ya en el destino es `Fila` sin modificar, `:199-201`); las tarjetas, de `actualizacion-masiva.ts:301-310`. El título del margen usa `cambio.rubro.nombre` (`rubros.ts:129`) y el nombre nuevo está en `cambio.request.nombre`.
