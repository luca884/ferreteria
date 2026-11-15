# Móvil usable: dirección «barra al pulgar»

Implementar en la app la dirección **A · Barra al pulgar** de `design/movil-usable/01-barra-al-pulgar.html`,
para que la app se pueda usar de verdad en un celular sin cambiar cómo se ve en escritorio.
Rama `feat/movil-pulgar` desde `main` en `7ca1cc02`. Sesión 77.

## Specs

S1 · El móvil usa la dirección A del tablero: destinos fijos abajo, buscador siempre visible arriba, y
una línea por artículo con el importe a la derecha. Literal de Luca: «me gusta mas la direccion a con el
pulgar eso esta bueno con el movil».

S2 · El criterio de aceptación es entender y usar: «tiene que ser simples y practicas se tiene que
entender», y el estado de partida que hay que dejar atrás es «es horrible no se llega a entneder nada y
hay cosa que ocupan mucho espacio».

S3 · El alcance es el celular, siempre en formato móvil. La verificación se hace en 390 px de ancho.

S4 · En pantalla angosta (≤ 639.98 px) el dato tiene que estar antes que los controles: ningún bloque de
filtros puede ocupar la primera pantalla, y ningún dato necesario para decidir puede quedar escondido a la
derecha de un scroll horizontal sin señal.

S5 · Todo control táctil mide al menos 44×44 px, y un botón apagado por una regla de negocio sigue
explicando el motivo (contrato de `shared/motivo-de-accion.ts`, ya en `main`).

S6 · El escritorio no cambia. Es la restricción que dejó la feature `responsive`: la app se usa en
celular, tablet y laptop sin tocar cómo se ve en escritorio.

S7 · Las tres pantallas del tablero se implementan con el mismo patrón, no una por una con estilos
distintos: Artículos, Ventas y Mostrador.

## Tasks

- [x] T1 · S1, S6 · shell móvil: barra inferior de cuatro destinos, sin sacar el cajón. · Evidencia: `<nav class="destinos" aria-label="Destinos">` en `shell.html`, con los destinos derivados de `gruposPara` filtrados por rol, «Más» reusando `alternarMenu()`, y `display: none` en la base para que el escritorio quede idéntico. Medido en 390 px: barra de 57 px de alto, cada destino 96×56, «Artículos» con `aria-current="page"`, contenido con padding inferior para que no lo tape. El super admin no ve la barra si no tiene destinos reales. shell spec 70/70, suite completa 167 archivos / 4583 tests, `ng build` OK. · commit: `15568b08`
- [x] T2 · S1, S2, S4 · Artículos: buscador fuera de la barra que se desliza (sticky, visible sin tocar nada), «Filtros» con la cuenta de los puestos, y una fila de dos renglones por artículo con el precio final en mono tabular a la derecha. Los datos de ficha (cód. proveedor, costo, margen, IVA, última actualización) se ocultan sólo en el celular y siguen en la tabla ancha. Medido: 390 px sin desborde, buscador en la primera vista, fila de **64 px** (antes la tarjeta de 9 renglones: entraban 1,5 artículos por pantalla, ahora 13); a 640 y 1280 la tabla muestra sus 10 columnas y el bloque móvil no existe. articulos spec 86/86, suite completa 167 archivos / 4589 tests, `ng build` OK. · commit: `808e3d87`
- [x] T3 · S2, S4 · Ventas: los seis filtros plegados detrás de «Filtros» con la cuenta de los puestos (el panel **es** el mismo formulario, sin duplicar campos como en T2), y una línea por venta: número, cliente y el total a la derecha en mono tabular, factura y fecha en el renglón chico. Medido en 390 px: la primera venta aparece a 189 px, sin desborde, fila de 87 px (104 en las que llevan marca) y la columna del cliente arranca en el mismo x en todas; el «N.º de factura» dejó de recortar su valor (332 px de ancho). A 640 y 1280 el formulario vuelve a estar visible y el botón no existe. ventas spec 73/73, suite completa 167 archivos / 4594 tests, `ng build` OK. · commit: `db26aa22`
- [x] T4 · S1, S4, S5 · Mostrador: los avisos en una franja de 54 px con la cuenta, «Ocultar» y «Cerrar la caja» a un toque (el panel de avisos queda plegado), y el estado vacío pasa de barras sueltas a un código de barras enmarcado en fila con su texto. Medido en 390 px: el contenido empieza en 267 px (antes ~390, con las dos alertas apiladas), sin desborde y con el Cobrar fijo en 780 sin que nada lo tape; a 1280 la franja no existe y los avisos se ven como siempre. **La pista de teclado ya estaba oculta** por CSS (`mostrador.css:338` y `styles.css:1330`, táctil), no se tocó. mostrador + avisos-facturacion 326/326, suite completa 167 archivos / 4600 tests, `ng build` OK. · commit: `078b8865`
- [x] T5 · S2, S5 · tres defectos medidos, todos de celular: el título del encabezado ahora puede ocupar dos líneas (defensivo, ver L13: el recorte no se reprodujo), los números de página de Ventas/Presupuestos/Remitos pasan de 9–18 px a una fila de 44 px, los tildes y radios quedan en 20 px dentro de una etiqueta de 44 px, y el paginador de Artículos baja de 87 a **48 px** en una línea con la flecha visible y el rótulo como nombre accesible. Medido en 390 px: paginador 44 px de alto, contador 194 px de texto en 235 px, botones 44×44, tildes 175×44 con input de 20×20, sin desborde (375 ≤ 390); a 640/768 y 1280 nada cambia. shell + articulos 161/161, suite completa 167 archivos / 4605 tests, `ng build` OK (los mismos seis avisos de presupuesto: `shell.css` 6,54 kB y `articulos.css` 4,40 kB crecieron, ninguna llegó al error de 8 kB). · commit: `32248f0d`
- [ ] T6 · S2, S4 · tablas anchas peores: stock bajo mínimo (1267 px) e historial de cajas (1020 px) con **fila apilada**, sin esconder el dato y sin tocar la hoja de impresión. · Evidencia: — · commit: —
- [ ] T7 · S2, S4 · clientes (900 px) y usuarios (890 px): fila apilada reusando la transformación de tarjeta que ya usa Artículos si alcanza. · Evidencia: — · commit: —
- [ ] T8 · S2, S4 · proveedores (784 px), compras (778), presupuestos (780), remitos (660) y formas de pago (660): la misma fila apilada. · Evidencia: — · commit: —

## Log

- L1 (2026-11-14) Luca, textual: «ya hice el merge, ahora quiero que hagamos una revision pantalla por pantalla como un recorrido de la app y ver como quedaron, levanta el poreycto abri un space tuyo y entra como dueno de una ferreteria de prueba y vemos como quedo todo pero en formato movil siempre».
- L2 (2026-11-14) Luca, textual: «estuve viendo en el modo lmovil y es horrible no se llega a entneder nada y hay cosa que ocupan mucho espacio, no hay alguna skill par el fornted y para movil en especifico?».
- L3 (2026-11-14) Luca, textual: «hagamos algo primero verifica las pantallas pero tambien utilizalas tiene que ser simples y practicas se tiene que entender, y luego vemos los 2 o 3 disenos con gentle desing haber como los podemos hacer».
- L4 (2026-11-14) Luca, textual: «me gusta mas la direccion a con el pulgar eso esta bueno con el movil». Elige la dirección A y con eso queda fijado S1.
- L5 (2026-11-14) Recorrido de estructura hecho: 27 pantallas en 390 px, capturas en `.recorrido-movil/` (ignorada por git) e informe en `.recorrido-movil/hallazgos.md`. Lo sistémico: filtros que ocupan la primera pantalla, buscador de Artículos escondido dentro de la barra que se desliza, fichas de 9 renglones, tablas de hasta 1267 px en scroll horizontal sin señal, 9 títulos cortados y paginadores de 9 px de ancho.
- L6 (2026-11-14) Direcciones escritas en `design/movil-usable/` (01 barra al pulgar, 02 ficha que sube, 03 buscar primero) sobre el sistema de diseño sincronizado en `design/system.md` desde `frontend/src/styles.css`. El auto-chequeo encontró campos de búsqueda falsos sin `input`, targets de 14 px, filas tocables que no eran botones y radios fuera de token; se corrigieron sin cambiar el diseño. Las pastillas se restauraron con `--radio-pastilla`, que documenta el `999px` que el proyecto ya usa en `.pastilla`.
- L7 (2026-11-14) Corte de slice: el primer commit de la rama (`f8414f33`) es sólo documentación y diseño (1182 líneas, casi todas HTML de los tableros), así que no es un slice de código. A partir de acá **cada tarea T1–T6 es su propio slice** con su verificación en 390 px y su commit, en ese orden: el shell primero porque T2–T4 dependen de la barra inferior.

- L8 (2026-11-14) T1 cerrada con el cajón intacto: los 10 casos del cajón (`.abrir-menu`, `.velo`, trampa de Tab, Esc, cierre al navegar) siguen pasando, y sólo cambió el alcance de dos selectores del spec de `nav a` a `aside nav a`, porque ahora el shell tiene una segunda `<nav>`. El Vendedor no ve el destino Ventas porque su rol no puede abrirlo. Decisión: la barra no se renderiza cuando el rol no tiene destinos reales (el super admin quedaba con un nav de un solo botón). `shell.css` pasó el presupuesto de 4 kB y ahora da un quinto warning de build (6,36 kB contra el límite duro de 8 kB); queda para T5 junto con los otros cuatro.
- L9 (2026-11-14) Estrategia de entrega de la rama: cada tarea T1–T6 es un commit `feat(...)` propio más su `docs:` con la evidencia en este archivo. Los 1419 renglones que acumula la rama en tres commits son casi todos el HTML de los tableros de diseño (`f8414f33`), no código de la app: el commit de T1 son cuatro archivos del shell. El push y el PR los decide Luca.

- L10 (2026-11-14) T2: el worker duplicó los controles a propósito (una copia en la barra con `.solo-escritorio` y otra en `.herramientas-movil`) en vez de mover el nodo, para no tocar la barra compartida; el precio es dos juegos de ids y de tildes que hay que mantener en espejo cuando cambien los filtros. Medición del padre en el navegador, que el worker no puede hacer: la primera versión dejaba la fila en **83 px** porque el código tenía renglón propio, así que la grilla pasó a dos renglones (`descripcion descripcion precio` / `codigo estado stock`) y quedó en 64 px con 13 filas por pantalla. Queda anotado, fuera de T2: el bloque de paginación («Página 1 de 208», Anterior/Siguiente) se come ~90 px arriba de la lista y el botón «Buscar» repite el submit que ya dispara Enter; los dos entran en T5.

- L11 (2026-11-14) T3: el worker plegó el formulario real en vez de duplicar los seis campos (mejor que el espejo de T2, que deja dos juegos de ids que hay que mantener en sincronía cuando cambien los filtros). Medición del padre: la primera versión usaba `fit-content(40%)` en la columna del número, así que una venta con marca larga («No facturable») corría el cliente y cada renglón arrancaba en otro lado — se veía roto. Con la columna en 6rem el cliente arranca en el mismo x siempre; las filas con marca suben a 104 px porque la marca cae debajo del número. El cliente se corta con puntos suspensivos en la columna angosta: el texto completo sigue en el DOM y en el detalle. `ventas.css` queda 376 bytes arriba del aviso de 4 kB (4,38 kB, límite duro 8 kB), junto con `shell.css` y `articulos.css`: los tres presupuestos van a T5.

- L12 (2026-11-14) T4: los avisos del CAE (`app-avisos-cae`) y «Primeros pasos» **quedan fuera de la franja** porque esas dos piezas están fuera de las superficies de la tarea y plegarlas sin contarlas escondería un aviso. En este tenant no se dibujan, así que la ganancia medida es real; la propuesta para cerrarlo del todo es que cada componente exponga su cuenta (~3 líneas por componente más su spec). Pendiente, no bloquea T4. `mostrador.css` pasó a 6,91 kB y se suma a los otros presupuestos de CSS: seis avisos de build, decisión de política para T5.

- L13 (2026-11-14) **Corrección de un hallazgo mío**: el «título cortado en 9 pantallas» de L5 era un falso positivo de mi detector `scrollWidth > clientWidth`. Medido de nuevo en el navegador con la app en `main` a 390 px: la caja del título mide **263 px**, el más largo de esos nueve («Controles de datos») mide **130 px** y `corta=false` en los cinco que revisé, igual que en las capturas 17/24/25/35 del recorrido, donde los títulos se leen enteros. El cambio de T5 (dos líneas con `line-clamp`) queda como red de seguridad para títulos largos, no como reparación de un corte visible. Lección: un detector de desborde por DOM no distingue «no entra» de «el contenedor tiene overflow oculto», así que un hallazgo así se confirma con el rectángulo medido o con la captura antes de afirmarlo.
- L14 (2026-11-14) Alcance real del diff de T5, para la revisión: el bloque nuevo de `styles.css` es de celular pero toca clases globales (`.paginas`, `table :is(button.enlace,a)`, las etiquetas de tildes) que usan pantallas fuera de las superficies de la tarea (Presupuestos, Remitos, Compras, Clientes, historial, Usuarios, Configuración, Importaciones); reduce alturas y no debería cambiar nada más, pero ningún spec de esas pantallas lo afirma. Es el punto más ancho de la entrega.

- L15 (2026-11-14) Luca eligió **fila apilada** para las tablas anchas, el mismo patrón de Artículos y Ventas, en lugar del scroll horizontal señalado. T6 se acota a las dos peores (stock bajo mínimo e historial de cajas) porque T7 y T8 quedan para el resto: entre las tres hay nueve tablas que hoy se deslizan de costado, y `stock-bajo-minimo` es además una hoja de impresión, así que su `@media print` no se toca.
