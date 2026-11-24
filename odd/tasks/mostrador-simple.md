# mostrador-simple

Objetivo: el mostrador abre directo en Venta con un selector de modo (Venta / Presupuesto / Remito), sin botones apagados, y muestra cada acción cuando se puede usar; la funcionalidad queda intacta. Elegida la propuesta A de `design/mostrador-simple/`.
Rama: `feat/mostrador-simple` desde `0a110cdd` · Entrega: single-pr · Runner: frontend `npx ng test --watch=false --include=<spec>` · Mirror Engram: `odd/mostrador-simple/tasks` (`project: "ferreteria"`)
Pronóstico: ~600 líneas cambiadas, sólo frontend · Heurística por tarea: ~400 (no es límite) · Ruteo: inline (una sola pantalla; ver Writer rule)

## Specs

Alcance: sólo la pantalla del mostrador (`frontend/src/app/features/mostrador/`) · Fuera de alcance: el menú lateral de la app, los listados de presupuestos y remitos, el backend, los diálogos de cobro, presupuesto y remito (siguen igual).
Restricciones: test-first; los atajos de teclado no cambian; el celular (`movil-sectores`, `movil-pulgar`) sigue con su barra de cobro fija abajo.

- S1: «que no tenga todo al principio lleno de botones, sin siquiera el usuario pueda utilizarlos» — Aceptación: con el ticket vacío no se ve ningún botón apagado; se ve el buscador, el selector de modo, el cliente y un mensaje de «listo para vender».
- S2: «las opciones del principio, venta, presupuesto, remito y todo eso» — Decisión (L2): el mostrador abre directo en Venta y arriba hay un selector de modo con esas tres opciones.
- S3: «en ventas ya te aparece la pantalla de ventas, la de remito te aparece de remito directamente» — Aceptación: el botón principal cambia con el modo: «Cobrar» (F4) en Venta, «Guardar presupuesto» (F8) en Presupuesto, «Emitir remito» en Remito; cada uno abre el diálogo que ya existe.
- S4: «Que se pueda hacer todo lo que podamos hacer, es decir, que toda la funcionalidad quede intacta» — Aceptación: las 10 acciones (Buscar, Cantidad, Descuento, Dto. total, Cobrar, Presupuesto, Remito, Cliente, Quitar, Vaciar) siguen alcanzables, con las mismas teclas y los mismos motivos cuando no se pueden usar.
- S5: Decisión (L2): al cambiar de modo el ticket se conserva; cambia sólo el cierre.
- S6: Decisión (L2, propuesta A): con renglones, a la derecha aparece el panel de cierre con el total, el botón principal del modo y las acciones secundarias (Dto. total, Vaciar); Cantidad, Descuento y Quitar aparecen sobre el renglón elegido.
- S7: la ficha del artículo (precio, stock, ubicación) sigue a la vista cuando se escanea o elige un artículo, aunque el ticket todavía no tenga renglones.

## Tasks

- [ ] T1 · S2, S5 · ruta: inline · Aceptación: señal `modo` (Venta por defecto) y selector de modo en la barra; el ticket no cambia al cambiar de modo · Evidencia: — · commit: —
- [ ] T2 · S1, S6, S7 · ruta: inline · Aceptación: estado vacío sin botones apagados ni panel; panel de cierre con total y acciones secundarias cuando hay renglones o ficha · Evidencia: — · commit: —
- [ ] T3 · S3, S4 · ruta: inline · Aceptación: botón principal por modo y acciones sobre el renglón elegido; la botonera de diez botones deja de verse y las teclas siguen iguales; celular intacto · Evidencia: — · commit: —
- [ ] T4 · cierre · Aceptación: suite frontend, `npx ng build`, ego-browser en escritorio y celular con capturas para Luca, docs del mostrador actualizadas, `node odd/indice-tareas.mjs` · Evidencia: — · commit: —

## Log

- L1 (2026-11-24) Pedido original, textual:
  > Tenemos que hacer la parte del mostrador más sencilla y práctica, que no tenga todo al principio lleno de botones, sin siquiera el usuario pueda utilizarlos, porque apenas entras, ves toda una sección a la izquierda que son las categorías, ves un mostrador al lado de un ticket que está vacío claramente. Luego ves todos botones arriba que dicen remito, presupuesto, todo eso, pero están apagados, no los puedes usar. Eso es lo que te estoy diciendo que se ve a primera vista. Otra cosa es cómo lo planeamos nosotros, que primero se tienen que cargar los productos y todo eso. Quiero reinventar esa parte un poco, cambiarla, y que el mostrador sea directamente más sencillo. Que se pueda hacer todo lo que podamos hacer, es decir, que toda la funcionalidad quede intacta. Lo que sí hay que revisar y ver cómo podemos hacer la parte de visual, la parte interactiva del usuario. ¿A qué me refiero? En vez de tener todo eso tan complejo y tan cargado a primera vista, podríamos hacerlo más sencillo con tal vez en el mostrador, pocas opciones, las opciones del principio, venta, presupuesto, remito y todo eso, y luego al apretar cada botón individualmente te carga una pantalla donde vos puedas acceder a eso. Por ejemplo, en ventas ya te aparece la pantalla de ventas, la de remito te aparece de remito directamente, y luego se cargan los productos o lo que sea necesario para cada sección de esa parte. ¿Entendés a qué me refiero? Hablemos un poco del tema, a ver cómo lo planeamos.
- L2 (2026-11-24) Decisiones de Luca, textuales (respuestas a la consulta): «Abre directo en Venta, con selector de modo» · «El ticket se conserva y cambia el cierre» · «Sólo el mostrador (rediseño de la pantalla)». Después de ver las tres maquetas: «vamos con A» (`design/mostrador-simple/01-pestanas-y-panel-de-cierre.html`).
- L3 (2026-11-24) Mapeo: lo «de la izquierda» que Luca llama categorías es el menú lateral de toda la app (`layout/shell.html:19-66`), no del mostrador; queda fuera de alcance y pendiente de decisión aparte. Presupuesto y remito no exigen cliente (sólo la cuenta corriente y el tope de Consumidor Final). Remito no tiene tecla. Los diálogos de presupuesto (validez, observaciones) y remito se mantienen; la maqueta A los mostraba como campos del panel, en esta primera entrega siguen siendo el diálogo que ya existe.
