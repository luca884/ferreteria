# error-de-campo-pegado

Objetivo: que el 400 del reseteo de contraseña no quede pegado bajo el campo cuando el usuario ya está escribiendo otra cosa.

- Rama: `fix/error-de-campo-pegado` (desde `main` en `371c726f`, el merge del PR #47).
- Origen: hallazgo informativo `R3-stale-field-error-while-typing` de la revisión nativa `review-00bafaa80638ea1d`, y el cabo que `advertencias-recorrido-dueno`/`cabos-auth-l13` L8 ya había anotado antes de que la revisión lo confirmara.

## Specs

- S1 — textual del cierre de la revisión: «`R3-stale-field-error-while-typing`, lens `reliability`, `frontend/src/app/features/usuarios/usuarios.ts:277-282`, severity `SUGGESTION`, disposition `informational`». El mensaje del servidor queda visible mientras el usuario tipea otra contraseña, así que describe algo que ya no está en pantalla.
- S2 — textual de L8 de `cabos-auth-l13.md`: «el error de campo del reseteo no se borra al tipear (el panel de reset no bindea `(editado)`, a diferencia de `mi-contrasena`)». Se resuelve con el patrón que ya existe: `mi-contrasena.html:23` bindea `(editado)="limpiarErrores()"` y `mi-contrasena.ts:35-38` limpia el error de campo y el general.

## Tasks

- [x] T1 · S1, S2 · ruta: inline · al tipear en el formulario de reset, `errorNueva` y `errorReset` se limpian. Aceptación: RED observado en `usuarios.spec.ts` (el `#nueva-error` sigue ahí después de tipear), y GREEN con ese caso más el resto del archivo. · Evidencia: `limpiarErroresDeReset()` en `usuarios.ts`, bindeado como `(editado)="limpiarErroresDeReset()"` en el `app-contrasena-form` del panel de reset (el mismo patrón de `mi-contrasena.html:23`). RED: **47 casos, 1 rojo**, `AssertionError: expected <span …> to be null`. GREEN: **47/47**. · commit: —

## Log

- L1 (2026-11-12) Pedido, textual: «ya esta merge, continua», después de elegir «Merge de #47 y después el fix del error pegado».
- L2 (2026-11-12) El documento se escribe y se commitea **junto** con el test y el arreglo, en un solo commit: en el PR #47 el commit de documentación posterior a la revisión generó un candidato nuevo y obligó a una segunda ronda de revisión sobre 4 líneas.
- L3 (2026-11-12) CI local: suite frontend **166/166 archivos, 4504 tests, 0 fallos**; `npx ng build` **exit 0** con los warnings de presupuesto de siempre. La suite del backend **no** se corrió y no corresponde: el diff no toca `backend/`.
