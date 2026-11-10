# responsive

Objetivo: la app se puede usar bien en celular, tablet y laptop, sin cambiar cómo se ve en escritorio · Por qué: el relevamiento a 390×844 del 2026-11-09 encontró que ninguna pantalla es usable hoy en el celular (L2).
Rama: `feat/responsive` desde `c65fccf5` · Entrega: single-pr · Runner: frontend `npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` / `npx ng build` · Mirror Engram: `odd/responsive/tasks` (ok)
Pronóstico: ~1.700 líneas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite) · Modelo: Opus escribió el doc; lo ejecuta la sesión de trabajo inline (deepseek/glm), sin terminal aparte (L8, corrige L6) · Rebaseada sobre `main` (`56e45c7a`, el merge de los bugs) el 2026-11-10, sin conflictos (L8)

## Specs

Alcance: el frontend (`frontend/src`), en el orden aprobado por Luca (L3): menú → pop-ups → tablas (con tarjetas en las pantallas de consulta) → mostrador → barra de acciones, avisos y controles; más los detalles puntuales del relevamiento.
Fuera de alcance: los bugs funcionales del relevamiento (van en otra rama, L3), el backend, la impresión (`@media print` y las hojas imprimibles no se tocan, salvo para que el pop-up que las muestra entre en la pantalla) y cualquier cambio de flujo o de permisos.
Restricciones:
- En escritorio (≥ 1024 px) todo queda como hoy. La prueba es una medición a 1280 y 1440 px antes (`main`) y después.
- Cortes: celular ≤ 639 px, tablet 640–1023 px, escritorio ≥ 1024 px. Las pistas de teclado se esconden con `@media (hover: none) and (pointer: coarse)`, no por ancho. Esto es un supuesto (S12).
- Todo cambio visual se prueba con ego-browser en la app real, como lo ve el usuario (memoria «verificar con ego-browser»). Vitest no carga `styles.css` (`src/test-setup.ts` solo fija TZ) ni evalúa media queries en jsdom. Por eso el layout se prueba con la medición M y los tests cubren solo el comportamiento: abrir y cerrar el menú, traer avisos a la vista y etiquetas de las tarjetas.
- Los specs existentes que dependen de la estructura se mantienen:
  - `shell.spec.ts`: `nav[aria-label="Secciones"]`, `button.grupo[aria-expanded]` + `aria-controls`, orden buscador → nav → cotización, «Saltar al contenido» como primer `a`, 23 links del Dueño.
  - `dialogo-detalle.spec.ts`: las clases de `dialogo-detalle`.
  - `dialogo-cobro.spec.ts:404-422`: el `maxHeight` literal de `.cobro`. Si se cambia, se actualiza el spec a propósito.
- Presupuesto de CSS por componente en el build: 4 kB de warning y 8 kB de error (`angular.json:40-43`). Las reglas compartidas van en `styles.css`.

**Medición M** (la usan todas las tareas): ego-browser con emulación `Emulation.setDeviceMetricsOverride` `{width, height, deviceScaleFactor: 2, mobile: true}` y `Emulation.setTouchEmulationEnabled` a 360×780, 390×844, 768×1024 y 1024×768. Se reaplica en cada script, porque cada invocación es un proceso nuevo. A 1280×800 y 1440×900 va sin `mobile`. Por pantalla se mide:
- (a) `main.contenido.scrollWidth <= clientWidth` y `document.documentElement.scrollWidth <= innerWidth`. Ojo: `main` tiene `overflow:auto`, así que el desborde no se ve en `document`.
- (b) Ningún botón, link o campo visible queda con su rectángulo fuera de `[0, innerWidth]`, salvo dentro de un contenedor con `overflow-x: auto`. `elementFromPoint` en su centro devuelve el propio control: nada lo tapa.
- (c) Con un pop-up abierto, el `.dialogo` entra en el viewport, y la ✕ y los botones del pie se ven y se pueden tocar.
- (d) Por debajo de 1024 px, los campos tienen `font-size >= 16px` y los controles miden 40 px de alto o más.
- (e) Una captura del viewport.

Usuarios locales (contraseña `demo1234`): `dueno@faro.qa`, `vendedor@faro.qa`, `dueno@puerto.qa`, `vendedor@puerto.qa` y `dueno@perla.qa`. El super admin local tiene TOTP activado: el secreto está en Engram, `ferreteria/discovery/relevamiento-responsive-celular`. Perfiles de ego-browser «Ferreteria 1/2/3» (`Profile 3/8/9`). Para cambiar de usuario, cerrar sesión desde la UI. Nunca tocar `localStorage` con Angular cargado.

- S1: «se debe poder utilizar perfectamente en el celular, tiene que estar hecho y adaptado para eso, también tablets, laptops etc.» (Luca, 2026-11-06), y «la idea es que todo sea pueda observar de buena maera y entendible» (L1). Aceptación: con la medición M, las 37 rutas pasan (a)–(d) a 360, 390, 768 y 1024 px, y a 1280 y 1440 px miden igual que en `main`. Checks: medición M completa en T10, suite y `ng build`.
- S2 (menú): «una de las forma para segurarnos es poner en el browser en fromate celular aprox y ver todas las pantallas» (L1), sobre el hallazgo «el menú lateral es fijo y no hay ningún `@media` en `layout/shell.css`. En el celular el menú se come el 54 % de la pantalla y deja 178 px para el contenido» (L2). Aceptación:
  - Por debajo de 1024 px, el menú lateral pasa a un cajón que se abre con un botón «Menú» en un encabezado. El botón tiene `aria-expanded` y `aria-controls`.
  - El encabezado muestra el título de la pantalla.
  - El cajón se cierra al navegar, con Esc y tocando el velo. El foco queda atrapado mientras está abierto y vuelve al botón al cerrar.
  - `main` ocupa todo el ancho. Buscador, grupos, cotización y bloque de usuario siguen dentro del cajón.
  - Desde 1024 px queda igual que hoy.
  - Checks: `shell.spec.ts` con RED → GREEN de abrir, cerrar al navegar, Esc y velo; medición M en 3 pantallas a los 6 anchos.
- S3 (pop-ups): sobre el hallazgo «Pop-ups más anchos que la pantalla […] El detalle de una compra mide 825 px y solo se cierra con Esc» (L2). Aceptación:
  - Ningún pop-up es más ancho que el viewport menos su margen.
  - La ✕ y los botones del pie siempre se ven. El pie hace wrap.
  - El contenido scrollea dentro del pop-up (vertical) y las tablas del pop-up, dentro de su contenedor (horizontal).
  - Supuesto: en celular, los pop-ups de detalle (`app-dialogo-detalle`) y los imprimibles ocupan toda la pantalla, con cabecera y pie fijos.
  - Aplica a los 17 que usan `.dialogo-fondo` directo, a los 5 de `app-dialogo-detalle` y a `panel-lateral`.
  - Checks: medición M (c) con cada pop-up abierto a 390 y 1280.
- S4 (tablas): «dale me parece bien» (Luca, 2026-11-08) sobre la propuesta «en todas las tablas, ancho mínimo por columna (importe con «/ kg» ~120 px, descripción ~180 px), scroll horizontal DENTRO de la grilla (`.grilla-contenedor`, nunca en la página: totales y botones siempre a la vista) y la primera columna fija (sticky, como inmovilizar en Excel)». Aceptación:
  - Toda tabla de pantalla está dentro de un contenedor con scroll horizontal (`.grilla-marco` o `.grilla-contenedor`). Hoy hay ~30 sueltas (L4).
  - La columna que identifica la fila queda fija al scrollear en horizontal.
  - Importes y códigos no se parten.
  - Filtros, título, totales y botones no se mueven con el scroll de la tabla.
  - Checks: medición M (a) y (b) en cada pantalla con tabla, y una captura scrolleada a la derecha.
- S5 (tarjetas): misma aprobación de S4, sobre «En el celular, tarjetas (cada fila = bloque de «etiqueta: valor») solo en las pantallas que tiene sentido usar desde el teléfono: consulta de precio y stock, ventas del día, reportes». Aceptación:
  - Por debajo de 640 px, la lista de Artículos, la de Ventas y las tablas de Reportes se ven como tarjetas «etiqueta: valor», sin scroll horizontal.
  - Tocar la tarjeta hace lo mismo que tocar la fila.
  - Desde 640 px siguen siendo grilla.
  - Checks: un spec por pantalla que verifique que cada celda lleva su etiqueta; medición M a 390.
- S6 (mostrador): sobre el hallazgo «El mostrador tiene diseño de escritorio: la botonera mide 1018 px de largo y «Cobrar (F4)» queda fuera de pantalla […] la descripción queda con 0 px de ancho» (L2). Aceptación:
  - Por debajo de 1024 px, el mostrador va en una columna.
  - Cobrar y el total quedan siempre a la vista. Supuesto: barra fija abajo en celular.
  - Todos los botones de la botonera se pueden tocar sin salir de la pantalla.
  - En el ticket y en los resultados se lee la descripción. Supuesto: renglones como tarjetas en celular.
  - Los pop-ups de cantidad, descuento, descuento total, cliente y cobro entran en la pantalla. En el cobro, los botones de forma de pago no parten palabras y en «Mixto» los botones de cobrar se alcanzan.
  - Checks: una venta completa en ego-browser a 390 (buscar, agregar, cantidad, descuento, cobrar en efectivo y mixto) con la medición M; los specs del mostrador siguen verdes.
- S7 (barra de acciones): sobre el hallazgo «Barra de acciones fija arriba que ocupa entre 130 y 280 px, hasta un tercio de la pantalla» (L2). Aceptación:
  - Por debajo de 640 px, la barra ocupa una sola fila de 60 px como máximo y scrollea en horizontal si no entran los botones.
  - El título de la pantalla pasa al encabezado de S2. Supuesto: el `h1` queda para lectores de pantalla.
  - Sigue fija arriba, para el modelo «elegir la fila y tocar la acción».
  - Checks: medición M y alto de `app-barra-acciones` en las 31 pantallas que la usan.
- S8 (avisos): sobre el hallazgo «Los avisos aparecen lejos de donde tocaste» (L2). Aceptación:
  - Todo aviso de éxito o de error que responde a una acción del usuario se trae a la vista al aparecer, si no lo está.
  - Al fallar una validación, se enfoca el primer campo con error.
  - Precedentes a reutilizar: `caja.ts:272-274`, `importaciones.ts:396-402` y `actualizacion-masiva.ts:261-267` (`scrollIntoView?.()`, que jsdom no implementa).
  - Aplica a las pantallas del relevamiento: Clientes, Cotización, Usuarios, Formas de pago, Proveedores, Nueva compra, el error de CUIT del alta de ferretería y los avisos del Mostrador, más los otros `p.alerta` de acción (~38 en 22 plantillas).
  - Checks: RED → GREEN de la directiva o el helper y de una pantalla; medición en ego-browser (el aviso queda dentro del viewport).
- S9 (controles): sobre el hallazgo «Controles chicos para el dedo: los campos tienen letra de 13 o 14 px, y en iPhone eso hace zoom al tocarlos» (L2). Aceptación:
  - Por debajo de 1024 px, `input`, `select` y `textarea` van a 16 px.
  - Botones y campos miden 44 px de alto o más; `button.chico`, 40 px o más.
  - Casillas y radios con su etiqueta tienen un área táctil de 40 px o más.
  - Los links «N.º» y los de documentos tienen 40 px de alto como mínimo.
  - Los ítems del menú miden 44 px o más.
  - Checks: medición M (d).
- S10 (pistas de teclado): sobre el hallazgo «Pistas de teclado que en el celular no sirven: F2–F10, Esc/Enter, ↑↓, «Enter edita» y «arrastralo hasta acá»» (L2). Aceptación:
  - Con `(hover: none) and (pointer: coarse)` no se ven:
    - `[data-tecla]::after` (38 botones).
    - `kbd` (botonera del mostrador y barra).
    - `.pista-codigo` y `.pista-medios`.
    - «· Enter edita» y «Enter abre la ficha» (6 pantallas).
    - «También podés arrastrarlo hasta acá».
    - «(F4)», «con F8» y «F9».
  - Para el texto suelto se agrega una clase común, `.pista-teclado`.
  - En escritorio se siguen viendo.
  - Checks: medición en ego-browser con emulación táctil; verificar con `matchMedia('(hover: none) and (pointer: coarse)')` que la emulación los dispara.
- S11 (puntuales del relevamiento, L2):
  - Ficha de artículo: «Ajustar stock» tapado por la tarjeta de Precio final (`articulo-form.css:11-42`, `.solo-lectura` sin wrap y `.dato` con `min-width:180px`). También en la ficha del Vendedor.
  - Importar lista: el select «Ver la hoja» queda fuera de pantalla (`div.navegacion-hojas`).
  - El select de proveedor se estira hasta el nombre más largo (Nueva compra y filtro de Compras).
  - Artículos: «Siguiente» se pasa solo a otra línea, y el placeholder del buscador sale cortado.
  - Factura y comprobantes imprimibles dentro del pop-up: la columna Importe sale cortada.
  - Caja: el «·» queda suelto al partir la línea «Abrió X · fecha · Cerró Y», y el input de efectivo es más angosto que los otros.
  - Login: el degradé corta a 38vh a mitad del formulario.
  - Clientes: el formulario largo arriba empuja la lista. Va con S8.
  - Aceptación: cada uno se ve bien con la medición M a 390 y no cambia a 1280.
- S12 (supuestos, marcados como tales; Luca los confirma o corrige al ver T1, T2 y T5 en ego-browser):
  - (a) Cortes en 640 y 1024 px.
  - (b) Menú en cajón por debajo de 1024 px.
  - (c) Pop-ups de detalle a pantalla completa en celular.
  - (d) Total y Cobrar del mostrador en una barra fija abajo en celular.
  - (e) Barra de acciones en una fila con scroll horizontal y el título en el encabezado.
  - (f) Pistas de teclado ocultas por `hover/pointer`.
  - (g) Tocar el velo de un pop-up sigue sin cerrarlo (como hoy, para no perder lo cargado). Basta con que la ✕ siempre se vea.

## Tasks

- [x] T0 · S1 · ruta: inline · Aceptación: hay una línea base de la medición M en `main` (`56e45c7a`, el merge de los bugs; L8 corrige el `c65fccf5` del doc) a 1280 y 1440 en las 37 rutas, guardada en `.scratch/medicion-base/` para comparar después. Las rutas con `:id` usan un id real de cada ferretería · Evidencia: `corrida.js` midió 38 rutas × 2 anchos (76 medidas, 0 fallas) el 2026-11-10 sobre la rama ya rebaseada, que no tiene código: 0 desbordes de `main` y de `document`, 0 controles fuera del viewport y 76 capturas en `.scratch/medicion-base/` (JSON `base-1280-1440.json`). Único «tapado» preexistente: en 29 de 76 medidas el bloque de usuario del menú (`div.usuario`, `a.cambiar-contrasena`, `span.avatar`) tapa «Configurar» y «Ocultar las cotizaciones»; los otros 223 son la lista detrás de un pop-up abierto, que es el caso (c) · commit: ver L9 · Risk: none
- [x] T1 · S2, S12a-b · ruta: delegada a `gentle-ai-worker` (backstop de contexto) con superficies `frontend/src/app/layout/`; verificación y commit del padre · Aceptación: S2. Toca `layout/shell.{html,css,ts}` y `shell.spec.ts`; `styles.css` no hizo falta (el ancho del cajón sale de `min(86vw, 320px)` y la columna de escritorio sigue con `--ancho-menu`). Además, por T0: en el cajón `.usuario` deja de ser `position: sticky`, así «Configurar» y «Ocultar las cotizaciones» quedan alcanzables · Evidencia: RED 8 de 9 tests nuevos (sin implementación) → GREEN `shell.spec.ts` 62/62 con los 52 previos sin retocar, y `cotizacion-refresco.spec.ts` 4/4; `ng build` OK con dos warnings de budget, el de `dialogo-cobro` que ya estaba y `shell.css` 5,11 kB (nuevo: el warning es 4 kB y el error 8 kB); medición M a 1280/1440: **76 medidas, 0 diferencias contra T0** después de corregir una regresión que la primera corrida marcó —`grid-template-rows: minmax(0,1fr)` en el `:host` clampaba el lateral y hacía aparecer el scrollbar interno del menú en 5 rutas a 1440 (191 → 176 px)— · commit: ver L10 · Risk: item 3 (el shell lo usan todas las pantallas) + item 6 (ningún test atrapa el layout) → medición M a 1280/1440 contra T0, hecha
- [ ] T2 · S3, S12c, S12g · ruta: inline · Aceptación: S3. Toca `styles.css:872-990` (`.dialogo-fondo` con columna `minmax(0,1fr)`, `.dialogo` con `max-height` y `overflow`, pie con wrap, detalle a pantalla completa por debajo de 640 px), los anchos propios de cada diálogo (L4) y los contenedores de tablas dentro de pop-ups · Evidencia: — · Risk: item 3 + item 6 → medición M (c) de cada pop-up a 390 y 1280
- [ ] T3 · S4, S11 (imprimibles) · ruta: inline · Aceptación: S4. Agrega una clase común para fijar la columna que identifica la fila (`position: sticky; left: 0` con fondo) en `styles.css:625-635`, envuelve las ~30 tablas sueltas (L4) y define anchos mínimos e importes `nowrap` · Evidencia: — · Risk: item 6 → medición M a 390 y 1280 en cada pantalla con tabla
- [ ] T4 · S5 · ruta: inline (o delegada por el backstop de contexto: archivos propios de artículos, ventas y reportes) · Aceptación: S5. Tarjetas por CSS por debajo de 640 px con `data-etiqueta` en cada celda y `td::before`. RED: spec de etiquetas por pantalla · Evidencia: — · Risk: item 6
- [ ] T5 · S6, S12d · ruta: inline (o delegada por el backstop de contexto: `features/mostrador/*`) · Aceptación: S6. Toca `mostrador.css:5-16`, `botonera.ts:13-57`, `ticket-lista.{html,css}`, `lista-resultados.css:13-18`, `dialogo-cobro.ts:335-355` y los diálogos de cantidad, descuento y cliente · Evidencia: — · Risk: item 6 → venta completa en ego-browser a 390 y 1280
- [ ] T6 · S7, S12e · ruta: inline · Aceptación: S7. Toca `styles.css:451-546` (`app-barra-acciones`, `.accion-barra`) y el título en el encabezado de T1 · Evidencia: — · Risk: item 3 (31 pantallas) + item 6 → alto de la barra medido en las 31
- [ ] T7 · S8, S11 (Clientes) · ruta: inline · Aceptación: S8. Una directiva o helper compartido en `shared/`, que reutiliza el patrón `scrollIntoView?.({block: 'nearest'})`, aplicado a los avisos de acción. RED: spec de la directiva y de una pantalla (Clientes) · Evidencia: — · Risk: none
- [ ] T8 · S9, S10, S12f · ruta: inline · Aceptación: S9 y S10. Toca `styles.css:220-260` (controles), `321-404` (botones), `416-449` (`kbd`, `[data-tecla]`), la clase `.pista-teclado` en los ~12 textos sueltos (L4) y los ítems del menú · Evidencia: — · Risk: item 6 → medición M (d) a 390 y que nada cambie a 1280
- [ ] T9 · S11 · ruta: inline · Aceptación: S11, menos lo que ya cubrieron T3 y T7 · Evidencia: — · Risk: none
- [ ] T10 · S1, cierre · ruta: delegada (3 agentes en paralelo, uno por perfil de ego-browser, como en el relevamiento) + `odd-verifier` por item 6 · Aceptación: medición M completa en las 37 rutas a los 6 anchos, sin desbordes ni controles tapados, y 1280/1440 igual que T0; suite frontend y `npx ng build` verdes; captura de cada pantalla a 390 para Luca · Evidencia: — · Risk: item 6 → `odd-verifier` una vez al final de la feature

## Log

- L1 (2026-11-09) Pedido original, textual:
  > continuamos con el proyecto responsive y una de las forma para segurarnos es poner en el browser en fromate celular aprox y ver todas las pantallas carteles etc necesarios, para eso podes lanzar subagentes y cree en ego lite varios perfiles, de ferreteria 1 al ferreteria 3, cada uno si quiere puede entrar a estudios distintos y no se van a mezclar, asi lo hacmeos mas rapido a la ocmprobacion, la idea es que todo sea pueda observar de buena maera y entendible y obviamente cda agente debe reportar algun bugs o detalle si se lo cruza
- L2 (2026-11-09) Relevamiento a 390×844. Tres agentes en paralelo con los perfiles de ego-browser «Ferreteria 1/2/3», sobre las ferreterías locales Faro (venta diaria), Puerto (catálogo y stock) y Perla + super admin (ventas, reportes, configuración). Resultado: ninguna pantalla se puede usar hoy en el celular. Los problemas que se repiten, verificados en código:
  1. El menú lateral es fijo, de 212 px (`shell.css:4`, sin `@media`), y le deja 178 px al contenido.
  2. La columna de `.dialogo-fondo` (`styles.css:872`, `display:grid`) crece con el contenido: pop-ups de hasta 825 px con la ✕ y los botones fuera de pantalla.
  3. Hay ~30 tablas sin contenedor y ninguna tiene la primera columna fija.
  4. El mostrador está armado como grilla `minmax(0,1fr) 320px`: la botonera mide 1018 px sin wrap y la descripción queda con 0 px.
  5. La barra de acciones fija ocupa entre 130 y 280 px.
  6. Los avisos aparecen lejos de la acción.
  7. Los campos tienen 13–14 px de letra y los controles miden entre 30 y 36 px.
  8. Las pistas de teclado se ven en táctil.

  Además, los detalles puntuales de S11. Las capturas quedaron en el scratch de la sesión `ca950774`. Los bugs funcionales que se vieron van en otra rama.
- L3 (2026-11-09) Luca aprueba el orden propuesto («menú → pop-ups → tablas, con tarjetas en las pantallas de consulta → mostrador → barra de acciones, avisos y controles»), textual:
  > dale vamos con eso y luego con los bugs que no son de responseive en otra rama
- L4 (2026-11-09) Mapa del código (`odd-explorer`):
  - Shell: `shell.html:2-80`, `shell.css:2-7,31-40,179-192,249-258`, `--ancho-menu` en `styles.css:94`. No hay estado de colapso del menú.
  - Diálogos: `styles.css:872-990`. 17 componentes usan `.dialogo-fondo` directo; los 5 detalles usan `shared/dialogo-detalle.ts:22-65`; `panel-lateral` está en `dialogo-ajuste-stock.ts:159-161,333-366`. Anchos propios: factura-imprimible 820, documento-no-fiscal-imprimible 820, extracto-imprimible 860, recibo-imprimible 480, dialogo-anulacion 560, dialogo-cobranza 600, dialogo-ajuste 600, dialogo-anular 520, dialogo-sin-factura 520, venta-imprimible 640, dialogo-presupuesto 520, dialogo-remito 520 y dialogo-cobro 540.
  - Tablas: 50 `<table>`, de las cuales solo 13 tienen contenedor (`.grilla-marco` y `.grilla-contenedor` en `styles.css:625-635`, `.tabla-contenedor` y `ticket-lista`). Sueltas:
    - ventas.html:100
    - compras.html:62 y :113
    - presupuestos.html:75 y :140
    - remitos.html:75 y :131
    - historial-cajas.html:36
    - resumen-caja.html:93
    - nueva-compra.html:150 y :300
    - etiquetas.html:87
    - movimientos-articulo.html:61
    - clientes-articulo.html:44
    - movimientos-cuenta.html:105
    - cuenta-cliente.html:106
    - facturacion.html:189
    - reportes.html:94/125/150/177/214/241/289
    - stock-bajo-minimo.html:50
    - controles-de-datos.html:50
    - admin-ferreterias.html:271
    - cotizacion-lateral.html:44
    - dialogo-cobranza.html:67
    - dialogo-ajuste.html:72
    - configuracion-precios.html:136
    - las hojas imprimibles
  - Barra: `shared/barra-acciones.ts` solo hace `<ng-content />`; los estilos están en `styles.css:451-546`; la usan 31 pantallas.
  - Avisos: no hay componente compartido (~38 `p.alerta` en 22 plantillas). Precedentes de scroll al resultado: `caja.ts:272-274`, `importaciones.ts:396-402` y `actualizacion-masiva.ts:261-267`. `admin-ferreterias` no hace scroll: mueve el aviso dentro del formulario y enfoca un campo (`admin-ferreterias.ts:375-379`).
  - Pistas de teclado, tres mecanismos:
    - `[data-tecla]::after` en `styles.css:433-449`, en 38 botones.
    - `<kbd>` en 3 sitios: `botonera.ts:26`, `dialogo-cobro.ts:173` y `dialogo-cobranza.html:63`.
    - Texto suelto: `mostrador.html:18`, el «Enter edita» de clientes, formas-pago, rubros, proveedores, usuarios y articulos, `selector-archivo.ts:93-95`, `nueva-compra.html:28`, `cuenta-cliente.html:28`, `presupuestos.html:6` y `dialogo-cobro.ts:631,830`.
  - Controles: `styles.css:220-260` (14 px heredado, 36 px de alto), `321-404` (botones de 36 px; `.chico` de 30 px), tokens en `55-108`. Los únicos `@media` de ancho están en `styles.css:713` y en formularios (700, 900 y 901-1640). No se usan `@angular/cdk/layout`, `matchMedia` ni un servicio de ancho.
  - Mostrador: `mostrador.css:5-16,59-63,190-210`, `botonera.ts:13-57`, `ticket-lista.css:1-25` (`table-layout:fixed`, ~33rem fijos), `lista-resultados.css:13-18`, `dialogo-cobro.ts:313,335-355,516-569`.
  - Ficha: `articulo-form.css:11-42,133`.
  - Riesgo: item 3 (estilos compartidos) e item 6 (Vitest no carga `styles.css`; ningún test atrapa regresiones de layout).
- L5 (2026-11-09) Progreso: doc escrito y commiteado en `feat/responsive`. Ninguna tarea empezada. Próximo paso: Luca aprueba el ruteo (terminal con Opus) y el ejecutor arranca por T0 y T1.
- L6 (2026-11-09) Luca aprueba el ruteo («¿Lo ejecutamos así?», terminal nueva con Opus), textual:
  > dale

  Próximo paso: la terminal con Opus arranca por T0 y T1. La app local queda levantada en 4200 y 8080, con MySQL en 3307.
- L7 (2026-11-09) Cambio de orden. La terminal de esta feature nunca arrancó: la rama solo tiene los commits del doc. Mientras tanto, la de `fix/bugs-relevamiento-celular` terminó T2, T5, T7 y T8 y quedó esperando este merge. Se propone que los bugs vayan primero: la rama es más chica y ya va por la mitad, y como acá no hay código, no hay choques. Luca, textual (a «¿Le cambiamos el orden?»):
  > dale

  Próximo paso, cuando `fix/bugs-relevamiento-celular` esté mergeada en `main`:
  1. Rebasear `feat/responsive` sobre `main`. Solo tiene commits de doc, así que no hay conflictos.
  2. Arrancar por T0.

  Las líneas citadas en L4 pueden haberse corrido en los archivos que tocó la rama de bugs: `nueva-compra`, `reportes`, `ventas`, `presupuestos`, `remitos`, `compras`, `configuracion-ventas`, `datos-fiscales`, `importaciones` y los listados con Desde/Hasta. Se releen antes de editar.
- L8 (2026-11-10) Merge de los bugs y arranque de esta feature. `fix/bugs-relevamiento-celular` entró en `main` como PR #41 (`56e45c7a`), con la rama remota y el worktree ya borrados. `feat/responsive` se rebaseó sobre `main` sin conflictos (solo tenía los commits del doc): ahora es `bc29091e` sobre `56e45c7a`. Cambia el ejecutor: la feature la ejecuta la sesión de trabajo inline (deepseek/glm), no una terminal con Opus como decía L6. Luca, textual:
  > recorda que estamos usando deepseek y glm ahora

  Al preguntarle con qué modelo ejecutar T0–T10, eligió «Inline en esta sesión». Consecuencias: T10 ya no nombra un verificador Opus, y la línea base de T0 se mide sobre el `main` actual (`56e45c7a`) y no sobre `c65fccf5`, que es anterior a los arreglos de los bugs que cambiaron textos y avisos en `nueva-compra`, `reportes`, `ventas`, `presupuestos`, `remitos`, `compras`, `configuracion-ventas`, `datos-fiscales` e `importaciones`.
- L9 (2026-11-10) T0 hecho. Se escribió `.scratch/medicion/` (ignorado por git, nueva línea en `.gitignore`): `medir.js` es la medición M y `corrida.js` la corrida completa —entra como Dueño de Faro, recorre sus 36 rutas, y después entra como super admin para `/admin/ferreterias`, con el código TOTP generado en el momento— reutilizable tal cual en T10 sumando los anchos de celular y tablet. Base guardada en `.scratch/medicion-base/base-1280-1440.json` + 76 capturas. La primera corrida contaba como «tapados» los controles que un pop-up abierto deja atrás y los recortados por un contenedor con scroll; se separaron antes de dar T0 por cerrado (223 quedan como caso (c), ninguno real). Hallazgo preexistente que T1 tiene que resolver: el bloque de usuario del menú tapa «Configurar» y «Ocultar las cotizaciones» en 29 de 76 medidas de escritorio.
- L10 (2026-11-10) T1 hecho. El writer implementó el cajón con el estado en un `signal` (`menuAbierto`) y el título leído del primer `h1` de la pantalla con un `MutationObserver` sobre el host (ninguna de las 31 pantallas se tocó; en T6 el `h1` pasa a ser solo para lectores de pantalla y esto sigue funcionando). Esc, el ciclo de Tab y la devolución del foco al botón «Menú» viven en `(keydown)` del host; el cajón se cierra en `NavigationEnd` y también en `NavigationSkipped` (tocar la opción de la pantalla en la que ya se está). Cerrado queda fuera del orden de tabulación con `visibility: hidden` dentro del `@media`. La primera medición posterior marcó 5 diferencias en el ancho del menú a 1440: el `grid-template-rows` del `:host` clampaba el lateral al viewport y le agregaba scrollbar interno; se sacó porque el encabezado y el velo son `display: none` en escritorio y no son ítems del grid. La corrida siguiente dio 0 diferencias en las 76 medidas. Los dos warnings de budget de CSS no son error y uno ya existía.
  Slice: T0 + T1 son 627 líneas autoradas en 6 commits. La entrega de la feature es `single-pr` (encabezado del doc), así que el corte para revisar es al cerrar T10: un solo PR con T0–T10, sin PR encadenado.
