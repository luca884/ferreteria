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

- [ ] T1 · S1, S6 · shell móvil: barra inferior de cuatro destinos y barra de app con el título, sin el menú lateral de 320 px como única forma de navegar. · Evidencia: — · commit: —
- [ ] T2 · S1, S2, S4 · Artículos: buscador fijo arriba, lista de una línea con el precio a la derecha, filtros detrás de un botón con contador. · Evidencia: — · commit: —
- [ ] T3 · S2, S4 · Ventas: filtros fuera de la primera pantalla (botón con contador) y lista con el importe alineado para poder comparar. · Evidencia: — · commit: —
- [ ] T4 · S1, S4, S5 · Mostrador: los avisos en una franja de una línea, sin la pista de teclado en pantalla táctil, y el estado vacío sin el ícono roto. · Evidencia: — · commit: —
- [ ] T5 · S2, S5 · títulos cortados en las 9 pantallas que los recortan y controles menores a 44 px (paginadores de 9–18 px, checkboxes). · Evidencia: — · commit: —
- [ ] T6 · S2, S4 · tablas anchas (stock bajo mínimo 1267 px, clientes, usuarios, historial de cajas) con scroll horizontal señalado o fila apilada, sin esconder el dato. · Evidencia: — · commit: —

## Log

- L1 (2026-11-14) Luca, textual: «ya hice el merge, ahora quiero que hagamos una revision pantalla por pantalla como un recorrido de la app y ver como quedaron, levanta el poreycto abri un space tuyo y entra como dueno de una ferreteria de prueba y vemos como quedo todo pero en formato movil siempre».
- L2 (2026-11-14) Luca, textual: «estuve viendo en el modo lmovil y es horrible no se llega a entneder nada y hay cosa que ocupan mucho espacio, no hay alguna skill par el fornted y para movil en especifico?».
- L3 (2026-11-14) Luca, textual: «hagamos algo primero verifica las pantallas pero tambien utilizalas tiene que ser simples y practicas se tiene que entender, y luego vemos los 2 o 3 disenos con gentle desing haber como los podemos hacer».
- L4 (2026-11-14) Luca, textual: «me gusta mas la direccion a con el pulgar eso esta bueno con el movil». Elige la dirección A y con eso queda fijado S1.
- L5 (2026-11-14) Recorrido de estructura hecho: 27 pantallas en 390 px, capturas en `.recorrido-movil/` (ignorada por git) e informe en `.recorrido-movil/hallazgos.md`. Lo sistémico: filtros que ocupan la primera pantalla, buscador de Artículos escondido dentro de la barra que se desliza, fichas de 9 renglones, tablas de hasta 1267 px en scroll horizontal sin señal, 9 títulos cortados y paginadores de 9 px de ancho.
- L6 (2026-11-14) Direcciones escritas en `design/movil-usable/` (01 barra al pulgar, 02 ficha que sube, 03 buscar primero) sobre el sistema de diseño sincronizado en `design/system.md` desde `frontend/src/styles.css`. El auto-chequeo encontró campos de búsqueda falsos sin `input`, targets de 14 px, filas tocables que no eran botones y radios fuera de token; se corrigieron sin cambiar el diseño. Las pastillas se restauraron con `--radio-pastilla`, que documenta el `999px` que el proyecto ya usa en `.pastilla`.
