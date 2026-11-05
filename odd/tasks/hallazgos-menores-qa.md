# hallazgos-menores-qa

Objetivo: corregir los tres hallazgos menores del QA en el navegador del 2026-11-04 que siguen en `main` · Por qué: informe `Projects/Ferreteria/wiki/qa-navegador-2026-11-04.md` del vault (rama `claude/youthful-fermat-8ttqna`), hallazgos 2, 4 y 8.
Rama: `fix/hallazgos-menores-qa` desde `40edc91` · Entrega: single-pr · Runner: `cd frontend && npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` + `npx ng build` · Mirror Engram: `odd/hallazgos-menores-qa/tasks` (pending: el modo automático no deja escribir en Engram)
Pronóstico: ~200 líneas cambiadas · Ruteo: caso 2 (Opus; chico, solo frontend)

## Specs

Alcance: solo frontend; las tres pantallas del informe · Fuera de alcance: el Vendedor en `/admin/ferreterias` (sin guard a propósito, `app.routes.ts:209`), Enter en el pago mixto con ARCA (decisión pendiente) y la carrera F4 + escaneo.

Pedido (2026-11-04): «Vamos con los hallazgos del qa», en respuesta a la lista que incluía «Hallazgos menores del QA: que un clic en un renglón del ticket lo elija; que un artículo de otra ferretería muestre «no encontrado» en vez de «No se pudieron cargar»; que Etiquetas limpie el campo después de buscar».

- S1: «que un clic en un renglón del ticket lo elija» — Aceptación: un clic en un renglón del ticket del Mostrador lo deja elegido, y F3, F7 y F10 actúan sobre él. El foco vuelve al buscador, así el lector sigue andando — Checks: `ticket.spec.ts`, `ticket-lista.spec.ts`, `mostrador.spec.ts`
- S2: «que un artículo de otra ferretería muestre «no encontrado» en vez de «No se pudieron cargar»» — Aceptación: en `/articulos/{id}/movimientos` y en `/articulos/{id}/clientes`, un 404 muestra «El artículo no existe.» sin «Reintentar», como la cuenta del cliente. Los demás errores siguen igual — Checks: `movimientos-articulo.spec.ts`, `clientes-articulo.spec.ts`
- S3: «que Etiquetas limpie el campo después de buscar» — Aceptación: después de una búsqueda por descripción, el texto del campo queda seleccionado: lo próximo que se escribe lo reemplaza, sin pegarse. Al agregar un artículo de los resultados, el foco vuelve al campo con el texto seleccionado. Los resultados siguen a la vista para sumar otros — Checks: `etiquetas.spec.ts`
- S4 (supuesto): «El artículo no existe.» con el mismo criterio que «El cliente no existe.» de la cuenta del cliente; y en Etiquetas se selecciona el texto en vez de borrarlo, para que se siga viendo qué se buscó.

## Tasks

- [ ] T1 · S1 · ruta: inline · Evidencia: — · Risk: —
- [ ] T2 · S2 S4 · ruta: inline · Evidencia: — · Risk: —
- [ ] T3 · S3 S4 · ruta: inline · Evidencia: — · Risk: —
- [ ] T4 · S1-S3 · ruta: inline · Aceptación: suite frontend, `ng build` y prueba en la app real con ego-browser · Evidencia: — · Risk: —

## Log

- L1 (2026-11-04) Pedido original, textual:
  > Vamos con los hallazgos del qa
- L2 (2026-11-04) Exploración:
  - `ticket-lista.html` no tiene `(click)`; las acciones usan `ticket().elegido ?? último` (`mostrador.ts:587`); los atajos se escuchan en `document:keydown`.
  - `movimientos-articulo.ts:74` y `clientes-articulo.ts:60` muestran siempre `MENSAJE_LISTA` con «Reintentar»; `cuenta-cliente.ts:209` ya distingue el 404 con `reintentable`.
  - `etiquetas.ts:88-89` deja el texto de una búsqueda por descripción en el campo; solo `limpiarBuscador` (código exacto) lo borra.
  - La captura 45 del informe es «Clientes que lo compraron» de un artículo de otra ferretería.
