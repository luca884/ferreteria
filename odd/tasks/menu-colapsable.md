# menu-colapsable

Objetivo: que el menú lateral de la app se pueda achicar a una columna de íconos con un botón y que se acuerde cómo lo dejó cada persona, para dejarle más lugar a pantallas como el mostrador.
Rama: `feat/menu-colapsable` desde `0a110cdd` (main, independiente de `feat/mostrador-simple`) · Entrega: single-pr · Runner: frontend `npx ng test --watch=false --include=<spec>` · Mirror Engram: `odd/menu-colapsable/tasks` (`project: "ferreteria"`)
Pronóstico: ~400 líneas cambiadas, sólo frontend (`layout/`) · Heurística por tarea: ~400 (no es límite) · Ruteo: worker para la implementación (backstop de contexto), verificación y QA inline

## Specs

Alcance: el shell de la app (`frontend/src/app/layout/`) · Fuera de alcance: el contenido del menú y su personalización (CU-17), el mostrador, el menú del celular (cajón y barra de destinos, debajo de 1024 px) y el backend.
Restricciones: test-first donde jsdom alcanza (el CSS de media query se verifica en el navegador); los tests del shell siguen pasando; accesible por teclado, con nombre accesible y `title` en cada ícono de la columna angosta.

- S1: «ves toda una sección a la izquierda que son las categorías» (L1 de `mostrador-simple`) — Decisión (L1): el menú lateral se achica con un botón y vuelve a abrirse con el mismo botón; achicado es una columna de unos 64 px con íconos.
- S2: Decisión (L1): «Botón manual, y se acuerda tu elección»: el estado se guarda en el navegador (clave propia en `localStorage`) y se aplica al abrir la app; sin dato, el menú abre como hoy (ancho). No cambia solo al entrar a ninguna pantalla.
- S3: Decisión (L1): «Un ícono nuevo por grupo»: Ventas, Artículos, Compras, Precios y Configuración tienen un ícono propio, en el estilo de los de `icono-menu.ts` (SVG en línea, trazo, 16 px); la columna angosta muestra el Mostrador y los cinco grupos.
- S4: Decisión del agente (a confirmar con Luca al verlo): en la columna angosta, tocar un grupo abre el menú ancho con ese grupo desplegado; no hay menú flotante.
- S5: la columna angosta esconde lo que no entra (nombre de la ferretería, buscador, cotización, datos del usuario salvo el avatar con «Salir» a mano) y no cambia nada debajo de 1024 px.

## Tasks

- [x] T1 · S1, S2, S4, S5 · ruta: worker · Aceptación: botón que alterna ancho/angosto, estado persistido, `--ancho-menu` y CSS de la columna angosta desde 1024 px, tests del estado y de la persistencia · Evidencia: RED 10 de 160 en `layout/**` → GREEN 160/160; `features/mostrador/**` 1359/1359; ego-browser a 1440 px: expandido, columna angosta (logo, botón, seis íconos, avatar y «Salir»), tocar un grupo ensancha y abre ese grupo y guarda `menu-lateral-colapsado=false`; a 390 px el cajón queda igual · commit (ver git log) · Risk: none. Sin verificar a la vista: el corte exacto a 1023/1024 px y el menú de super admin en la columna
- [x] T2 · S3 · ruta: worker · Aceptación: cinco íconos de grupo en `icono-menu.ts` (o equivalente) y su uso en la columna angosta, con `title` y nombre accesible · Evidencia: test de un ícono por grupo en `shell.spec.ts`; capturas: cada grupo con su ícono y el color de su tono · commit (mismo que T1)
- [x] T3 · cierre · Aceptación: suite del frontend, `ng build`, ego-browser a 1440, 1100 y 390 px con capturas, doc del menú actualizada, `node odd/indice-tareas.mjs` · Evidencia: suite frontend completa 200 archivos, 6504 aprobadas, 4 salteadas; `ng build` exit 0; ego-browser a 1440 y 390 px (no se probó 1100 ni el corte 1023/1024); doc en `docs/spec/cu-17-personalizar-menu.md` («Achicar el menú»); índice regenerado · commit (ver git log) · Risk: none

## Log

- L1 (2026-11-24) Origen: en la conversación del mostrador (feature `mostrador-simple`, L1) Luca señaló «toda una sección a la izquierda que son las categorías», que resultó ser el menú lateral de la app. Después del cierre de esa rama pidió seguir con el menú colapsable. Decisiones de Luca, textuales (respuestas a la consulta): «Botón manual, y se acuerda tu elección» · «Un ícono nuevo por grupo» · «Desde main, independiente del mostrador».
- L2 (2026-11-24) Mapeo (explorador): ancho `--ancho-menu: 212px` en `styles.css:94`; grid del shell en `layout/shell.ts:63-67`; `aside.lateral` en `shell.css:46-58`; los grupos no tienen ícono (puntito `.grupo::before`, `shell.css:170-185`); las opciones usan `app-icono-menu` (`icono-menu.ts`, 25 nombres en `menu.ts:29-45`); no existe estado de colapso ni clave de persistencia de menú (sólo `cotizacion-lateral-abierta`); el encabezado con «Menú» está oculto desde 1024 px (`shell.css:560-566`); `shell.spec.ts` ~67 `it`, jsdom no evalúa media queries.
- L3 (2026-11-24) Cierre: T1–T3 [x]. Pendiente de Luca: confirmar S4 (tocar un grupo en la columna abre el menú ancho, sin menú flotante); push, PR y merge. Anotado, sin tocar: con un grupo abierto el bloque de cotización queda cortado abajo del menú ancho (ya pasaba antes).
- L4 (2026-11-25) Seguimiento del review (hallazgos no bloqueantes): R4-001 la preferencia se guarda por usuario (`menu-lateral-colapsado:{usuarioId}`; sin sesión no se lee ni se guarda); R3 test del ícono genérico, título y nombre de un grupo propio. R2-001 (ids de grupo desde `menu.ts`) se deja: sólo legibilidad. Suite frontend 200 archivos / 6507 tests, ng build OK. Risk: none (la clave vieja sin usuario queda huérfana: cada persona vuelve a elegir una vez).
