# Personalizar el menú lateral (CU-17)

Que el Dueño arme el menú lateral de su ferretería (grupos, opciones, orden) para él y para cada usuario.
Rama `feat/cu-17-personalizar-menu` desde `main` en `8f3fee92`. Spec: `docs/spec/cu-17-personalizar-menu.md`.

## Specs

S1 · Literal de Luca: «que el dueno de la ferreteria pueda perzonalizar su pantalla, a que me refiero, al apartado de los secctores que se despliegan, por ejemplo ventas configuracion etc, que pueda perzonalizarlo, cambiar de lugar los contenidos que tiene adentro elegir donde ponerlos creaer las suyas etc».

S2 · Alcance: «la idea es que el dueno pueda perzonalizar la suya y la de los demas usuaarios de la ferreteria como los vendedores». Resuelto con «Base por rol + ajuste por usuario»: el Dueño arma una base por rol (Dueño, Vendedores) y puede retocar el menú de un usuario puntual. Gana el más específico: ajuste del usuario, base del rol, menú original. Solo el Dueño edita.

S3 · Qué se edita (las cuatro, elegidas por Luca): «Mover opciones entre grupos y reordenar», «Crear, renombrar y borrar grupos propios», «Ocultar opciones que no usa», «Renombrar y reordenar los grupos originales».

S4 · Cómo se edita: «Solo arrastrar y soltar». Sin botones de subir y bajar. Como arrastrar no sirve en el celular, el editor se usa desde la computadora y en pantalla chica avisa «Personalizá el menú desde la computadora.».

S5 · Celular: «Se adapta sola a la disposición». La barra inferior es Mostrador, los dos primeros grupos con opciones para ese usuario y «Más». Cada grupo tiene su pantalla de entrada (hoy solo Ventas). Con el menú original queda igual que hoy.

S6 · Personalizar no cambia permisos: ocultar o mover nunca da ni quita acceso. `soloDueno` y el 403 del servidor siguen igual, y el editor no ofrece pantallas de solo Dueño al armar el menú de un Vendedor (el servidor también las rechaza).

S7 · Una opción nueva del sistema no desaparece para quien ya personalizó (cae en su grupo original o en «Otras») y una clave guardada que ya no existe se ignora. «Mostrador» queda fijo arriba. Siempre hay «Restaurar».

S8 · Aislamiento (reglas de AGENTS.md 1, 2, 3 y 7): la ferretería sale del JWT; el repositorio extiende `TenantScopedRepository`; el usuario de otra ferretería devuelve 404; hay un caso de integración que prueba que otra ferretería no ve ni edita la disposición. Migración Flyway aditiva.

## Tasks

- [x] T0 · S1-S8 · spec: `docs/spec/cu-17-personalizar-menu.md`, entrada en el índice, `02-casos-de-uso.md` (17 CU), modelo de datos y este feature doc. · Inline. · commit: `be5f7337`
- [ ] T1 · S5, S6, S7 · refactor del frontend sin cambio visible: `menu.ts` pasa de constante a catálogo de pantallas (clave estable, ruta, ícono, `soloDueno`) más una disposición; `Seccion` deja de ser una unión cerrada (id de grupo + color); la barra del celular, el buscador y `seccionDe` leen de la misma disposición; `HUB_VENTAS` se generaliza a un hub por grupo. Los tests actuales de `menu.spec.ts`, `shell.spec.ts` y `buscador-menu.spec.ts` pasan sin cambios de expectativa. · Inline. · Risk: item 3 (código compartido: menú, barra del celular, buscador, `/area-ventas`), commit propio.
- [ ] T2 · S2, S6, S7, S8 · backend: migración, entidad, repositorio scoped, servicio y controller. `GET /api/menu` (menú resuelto del logueado, cualquier rol de ferretería) y `GET`/`PUT`/`DELETE` de la disposición por rol y por usuario (solo Dueño). Valida claves contra el catálogo y rechaza pantallas de solo Dueño en la de un Vendedor. Tests de integración: otra ferretería no ve ni edita (404), el Vendedor recibe 403 al escribir, y el orden de resolución. · Inline, test-first. · Risk: item 2 (permisos) y item 1 (dato nuevo; no destructivo).
- [ ] T3 · S2, S5, S7 · el shell consume `GET /api/menu`, con el menú original como fallback si falla; mezcla las pantallas nuevas y filtra por rol. · Inline. · Risk: item 3.
- [ ] T4 · S2, S3, S4, S6 · editor «Personalizar menú» en Configuración (solo Dueño): selector «Menú de», arrastrar y soltar con `@angular/cdk/drag-drop` (verificar si ya es dependencia), grupos propios con color de paleta, ocultar, renombrar y «Restaurar»; en pantalla chica, el aviso de S4. · Inline.
- [ ] T5 · S1-S8 · cierre: suite completa, `ng build`, revisión a 390 px y a 1280 px, spec con los endpoints reales, README si corresponde. · Inline.

## Log

- L1 (2026-11-20) Pedido original de Luca, literal: «tenemos que agrgar que el dueno de la ferreteria pueda perzonalizar su pantalla, a que me refiero, al apartado de los secctores que se despliegan, por ejemplo ventas configuracion etc, que pueda perzonalizarlo, cambiar de lugar los contenidos que tiene adentro elegir donde ponerlos creaer las suyas etc, hablemos del tema y hgamos un buen plan».
- L2 (2026-11-20) Exploración: el menú es la constante `GRUPOS` de `frontend/src/app/layout/menu.ts` (5 grupos, 22 opciones); `Seccion` es una unión cerrada que también da el tono del shell (`data-seccion`) y decide qué grupo se abre al navegar (`seccionDe`); la barra del celular usa `DESTINOS_AL_PULGAR` y `HUB_VENTAS`; no hay nada en el backend sobre preferencias de pantalla. Como el caso de uso no estaba en la spec, T0 lo escribe antes del código (regla de AGENTS.md).
- L3 (2026-11-20) Respuestas de Luca. Alcance: «la idea es que el dueno pueda perzonalizar la suya y la de los demas usuaarios de la ferreteria como los vendedores». Qué se edita: las cuatro opciones. Cómo: «Solo arrastrar y soltar». Celular: «Se adapta sola a la disposición». Modelo de guardado: «Base por rol + ajuste por usuario».
- L4 (2026-11-20) Supuestos propios que Luca todavía no confirmó, anotados en la spec como «propuestos»: hasta 10 grupos, rótulo de 1 a 30 caracteres, sin repetidos dentro de una disposición, mensajes del editor, y que un usuario desactivado conserva su ajuste. Riesgo que se le advirtió: arrastrar y soltar sin alternativa es incómodo con teclado y en el celular; la mitigación acordada es que el editor sea de escritorio.
- L5 (2026-11-20) «dale» a empezar por T0: spec y feature doc primero.
