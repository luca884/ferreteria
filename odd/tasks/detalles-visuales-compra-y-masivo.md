# detalles-visuales-compra-y-masivo

Objetivo: que la vista previa de Nueva compra con recargos entre a 1280 px y que Actualizar precios diga «/ kg» · Por qué: los dos se vieron en los recorridos del PR #36 (`odd/tasks/detalles-de-los-recorridos.md`, L9).
Rama: `fix/detalles-visuales-compra-y-masivo` desde `9ca6076f` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / suite `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` / `npx ng build` · Mirror Engram: `odd/detalles-visuales-compra-y-masivo/tasks` (ok)
Pronóstico: ~250 líneas cambiadas · Heurística por tarea: ~400 (no es límite) · Modelo: Opus de punta a punta; T1 delegada a un `odd-implementer` Opus por el backstop de contexto de la sesión

## Specs

Alcance: la vista previa de Nueva compra y la de Actualizar precios. Fuera de alcance: los ejemplos de Configuración de precios y de Rubros (otros DTO), el detalle de la compra (`compras.html`, ya con «/ kg» desde el PR #35) y la advertencia de variación fuerte de la compra.
Restricciones: sin migraciones. Todo cambio visual se prueba en la app real con ego-browser, a 1280 y 1440 px, como lo ve el usuario (Luca, 2026-11-08: «tengo que tener la prueba de que el usuario final lo puede ver bien»).

- S1: «dale me parece perfecto vamos con eso» (L1), al detalle «la vista previa de Nueva compra con recargos (10 columnas) desborda a 1280 px, y ya pasaba antes» — Aceptación: con recargos en la compra, a 1280 y 1440 px la página no tiene scroll horizontal, SUBTOTAL se ve y los importes no se parten, con un artículo por unidad y otro por kilo; la regla queda acotada a `.vista-previa` (no toca `.renglones` ni el detalle de `compras.html`) — Checks: ego-browser (medidas antes y después), `npx ng test --watch=false --include=src/app/features/compras/nueva-compra.spec.ts`, `npx ng build`
- S2: «dale me parece perfecto vamos con eso» (L1), al detalle «Actualizar precios no muestra «/ kg»» — Aceptación: `POST` de la vista previa de Actualizar precios trae `unidad` en cada cambio; en la tabla, un artículo por kilo, metro o litro lleva «/ kg», «/ m» o «/ l» en el costo anterior y nuevo, el precio anterior y nuevo, el «Costo real» y el «Sin ajustes», y uno por unidad no lleva nada; la huella no cambia; la tabla sigue entrando a 1280 px — Checks: `./mvnw test -Dtest=ActualizacionMasivaIntegrationTest,PlanificadorMasivoTest,OperacionMasivaTest,HuellaMasivaTest`, `npx ng test --watch=false --include=src/app/features/actualizacion-masiva/actualizacion-masiva.spec.ts`, ego-browser
- S2a (supuesto): «Costo real» y «Sin ajustes» también llevan el sufijo, como el «Costo real» de la importación (PR #36, S3a).

## Tasks

- [x] T1 · S2, S2a · ruta: delegada (`odd-implementer` Opus; Writer trigger: backstop de contexto de ~150k de la sesión) · Aceptación: `unidad` en `CambioMasivo` y sufijo en la tabla · Evidencia: RED `ActualizacionMasivaIntegrationTest#laVistaPreviaTraeLaUnidadDeCadaArticuloQueCambia` (NPE, no hay `unidad`) y 2 de 221 en el spec del front; GREEN backend 241/241 (5 clases), front 221/221 (confirmado por el padre), `ng build` OK; ancho: en T3 · commit `64726786` · Risk: none. El campo nuevo del JSON es lo que pide S2. `CambioMasivo` y `PlanificadorMasivo` solo los usa Actualizar precios; el constructor viejo sigue, y la huella la cubre `HuellaMasivaTest.laUnidadNoCambiaLaHuella`.
- [x] T2 · S1, S2 · ruta: inline (medición previa con un worker de ego-browser) · Aceptación: la vista previa de Nueva compra entra a 1280 px; Actualizar precios vuelve a entrar con «/ kg» · Evidencia: V4a en `compras.css` y `.detalle-fila` en `actualizacion-masiva.css` (L4); specs 278/278 y `ng build` OK; el ancho no se prueba en jsdom · commit `b499300a` · Risk: none
- [x] T3 · S1, S2 · ruta: worker de ego-browser · Aceptación: las dos tablas medidas a 1280 y 1440 px en la app real, con capturas · Evidencia: L5 (compra 1053/1053 y 1213/1213; masivo 962/962 y 1137/1137) · Risk: none

## Log

- L1 (2026-11-08) Pedido de Luca, textual: «dale me parece perfecto vamos con eso». Responde a esta recomendación: «cuando termine el deploy, probar en producción y después hacer una tanda chica con los dos detalles visuales del punto 2, probados con ego-browser». El punto 2 decía:
  > - la vista previa de Nueva compra con recargos (10 columnas) desborda a 1280 px, y ya pasaba antes;
  > - Actualizar precios no muestra «/ kg»;
  > - la advertencia de variación fuerte (±50 %) de la compra compara el costo de la factura, no el costo real.

  La tanda toma los dos primeros, que son los visuales.
- L2 (2026-11-08) Exploración (`odd-explorer`):
  - **Nueva compra.** La vista previa (`nueva-compra.html:300-342`) es un `<table>` sin contenedor con scroll, con `th` y `.num` en `nowrap` global (`styles.css:569`, `:609`). Por eso desborda `main.contenido`. `compras.css` lo comparten `compras.ts` y `nueva-compra.ts`, así que la regla va acotada a `.vista-previa`. Los tests usan índices fijos de columnas (`nueva-compra.spec.ts:1020-1058`).
  - **Actualizar precios.**
    - Backend: `CambioMasivo` (`catalogo/model/CambioMasivo.java:27-48`) se serializa directo en `VistaPreviaMasivaResponse.cambios`. Se arma solo en `PlanificadorMasivo.java:88-100`, con `articulo.unidad()` a mano. `HuellaMasiva` no lo usa, y `HuellaMasivaTest.laUnidadNoCambiaLaHuella` lo cubre. En los tests solo hay un `new CambioMasivo`, en `OperacionMasivaTest.java:73`.
    - Front: `actualizacion-masiva.html:200-270` (costos `:230-233`, «Costo real» `:236-240`, precios `:252-258`, «Sin ajustes» `:263`), dentro de `.grilla-contenedor`. Hay literales `CambioMasivo` en `actualizacion-masiva.spec.ts`.
- L3 (2026-11-08) El PR #36 pasó en producción (ver la nota del vault `cambios/detalles-de-los-recorridos`). En producción, la vista previa de Nueva compra con recargos da `main.contenido` 1167/1053 y `section.vista-previa` 1151/1021 a 1280: es S1.
  Otras rarezas que se vieron y quedan fuera de esta tanda:
  - la advertencia del 60 % de la importación dice «de 769,50 a 1.231,20», sin «$» ni «/ m»;
  - «PR36-NUEVO1» se parte en el guion;
  - en Movimientos, los botones «Volver a la ficha» y «Ver compras» se ven altos.
- L4 (2026-11-08) Medición con ego-browser sobre `64726786`, como Dueño de El Tornillo.
  - **Nueva compra con «Flete» 10 %, a 1280.** La línea de base desborda 210 px: `main.contenido` 1263/1053. Sin recargo entra.
    - V1 (encabezados en dos renglones) da 1183. V2 (V1 más el stock en dos renglones) da 1135. V3 (V2 más 8 px de margen) da 1055.
    - **V4a** (V2 más `padding-inline: 6px`) entra: 1053/1053, con unos 22 px de sobra. Es la que se eligió.
    - La descripción del artículo ocupa 5 renglones a 1280, como en la línea de base. Mejorarla pide sacar o fusionar columnas: es decisión de producto y queda afuera.
  - **Actualizar precios con T1, a 1280.** `.grilla-contenedor` da 999/962. El renglón del «Costo real» es `nowrap` y, con los dos «/ kg», la columna Costo llega a 387 px. **Es una regresión de T1.** Con `table.cambios .detalle-fila { white-space: normal }` entra: 962/962. A 1440 entra igual.
  - **Implementado** (`b499300a`):
    - V4a en `compras.css`, acotada a `.vista-previa`;
    - `.detalle-fila` con `white-space: normal` en `actualizacion-masiva.css`;
    - `porUnidadDe` con espacios duros, para que el «kg» no quede solo cuando el renglón se parte.
    Specs de los dos componentes: 278/278. `ng build` OK.
- L5 (2026-11-08) Prueba final en la app real sobre `b499300a`, sin estilos inyectados.
  - **Nueva compra con «Flete» 10 %.** `main.contenido` da 1053/1053 a 1280 y 1213/1213 a 1440. Sin recargo, a 1280, da 1053/1053. SUBTOTAL se ve y ningún importe se parte.
  - **Lo que no cambió.** La tabla de renglones de Nueva compra y el detalle de la compra 8 siguen con `nowrap` y 12 px; el detalle entra (812 dentro de 860).
  - **Actualizar precios.** `.grilla-contenedor` da 962/962 a 1280 y 1137/1137 a 1440.
    - «Costo real: $ 4.400,00 / kg → $ 4.620,00 / kg» y, abajo, «(recargos 10 %)».
    - El sufijo lleva U+00A0 y ningún «kg» queda solo.
    - «/ kg» en U36-1, «/ m» en U36-6 y sin sufijo en U36-5.
    - «Sin ajustes» no apareció con estos datos: lo cubre el spec.
  - **Como ferretero.** Actualizar precios se lee bien. En Nueva compra a 1280, las columnas de plata van juntas y la descripción ocupa 5 renglones, como antes; a 1440 se lee cómoda.
  - **Base local intacta.** `recargos_costo` de U36-1 volvió a `[]` y no se registró ni se aplicó nada.
  - **Capturas.** En el scratchpad de la sesión, `tanda2/final-*.png`.
- L6 (2026-11-08) Cierre sobre `b499300a`:
  - backend `./mvnw test`: 6454 tests, 0 fallas, 0 errores;
  - frontend `npx ng test --watch=false`: 164 archivos pasan;
  - `ng build` OK, solo con el aviso de presupuesto de `dialogo-cobro.ts`, que ya estaba.

  Sin `odd-verifier`: el Risk es none. El JSON nuevo es el pedido y el código que se tocó es solo de Actualizar precios.
  Queda para Luca, como decisión de producto: en Nueva compra con recargos a 1280 px, la descripción del artículo va en 5 renglones. Ya pasaba antes; mejorarla pide fusionar o sacar columnas.
