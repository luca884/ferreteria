# unidad-de-medida

Objetivo: que la unidad de venta (kilo, metro, litro) se reconozca al importar y se vea en la factura, en los precios, en las compras y en los movimientos · Por qué: tanda del #36; hoy un artículo por kilo se vende bien, pero la factura imprime «1,5» sin unidad, el precio no dice «/ kg» y la importación rechaza «Kg.», «KGS» y «Kilos».
Rama: `feat/unidad-de-medida` desde `506c0245` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>`, frontend `npx ng test --watch=false --include=<spec>` · Mirror Engram: `odd/unidad-de-medida/tasks` (ok)
Pronóstico: ~450 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite) · Ruteo: Opus de punta a punta en esta sesión (aprobado: «dale me parece bien»)

## Specs

Alcance: reconocer la unidad al importar (columna y descripción) y mostrarla en la factura, junto al precio, en las compras y en los movimientos de stock · Fuera de alcance: unidades nuevas (el enum sigue con UNIDAD, METRO, KILO y LITRO), convertir cajas a kilos, cambiar la unidad de artículos que ya existen al importar (decisión 32: solo los nuevos la toman), migraciones.
Restricciones: la unidad ya se guarda por renglón (`ItemComprobante.unidad`, `ItemCompra.unidad`), así que no hay columna nueva. `NormalizadorCodigo` lo usan 9 clases y no se toca. Criterio de producto: «como lo hace gds» (L2); lo que GDS no cubre va como supuesto.

- S1: «aceptar «Kg.», «KGS» y «Kilos» en la importación» (propuesta aprobada con «dale me parece correcto, como lo hace gds») — Aceptación: en la columna de unidad, «Kg.», «KGS» y «Kilos» dan Kilo; un texto que no es unidad sigue dando «Unidad no reconocida: «…»» — Checks: `./mvnw test -Dtest=UnidadDeListaTest,PlanificadorImportacionTest`
- S1a (supuesto): la misma regla vale para las otras tres unidades: punto final, plural y abreviaturas habituales («MTS», «Metros», «LTS», «Litros», «UNID», «Unidades», «KILOGRAMOS»). GDS acepta cualquier texto en su tabla de unidades (L2); acá el enum se queda con cuatro valores.
- S2: «estaria ueno que el sistema lo reconozca ya que sea que pongan la unidad en tabla o tambien en descripcion» — Aceptación: si la fila no trae unidad en la columna (sin columna o celda vacía), una descripción con «x kg», «x kilo», «por kilo», «x mt», «x metro», «por metro», «x lt», «x litro» o «por litro» da esa unidad al artículo nuevo; la columna gana sobre la descripción; la vista previa ya lo muestra («Unidad: Kilo», `detalle-fila.ts:89`) — Checks: `./mvnw test -Dtest=UnidadDeListaTest,PlanificadorImportacionTest`
- S2a (supuesto): solo cuenta «x» o «por» seguidos directamente de la unidad, sin número en el medio. Así «Cable unipolar 2,5mm x100m», «Cinta aisladora 20m» y «Pintura latex interior blanco 4L» (listas de prueba del vault, L3) siguen siendo por unidad, porque son un rollo o una lata que se venden enteros. Las letras sueltas «m» y «l» no cuentan en la descripción (sí en la columna).
- S3: ««kg» en la factura (antes, confirmarlo en la documentación de ARCA)» — Aceptación: la factura A, B, C y la nota de crédito imprimen la cantidad con unidad («1,5 kilos», «2 unidades»), igual que el remito y el presupuesto — Checks: `npx ng test --watch=false --include=src/app/shared/factura/factura-hoja.spec.ts`
- S3a (supuesto): GDS pega la unidad al final de la descripción del renglón (L2); acá va en la columna Cantidad, como ya la imprimen el remito y el presupuesto. ARCA no la exige ni la prohíbe (L2).
- S4: ««/ kg» en los precios» — Aceptación: el precio unitario lleva «/ kg», «/ m» o «/ l» en el ticket del mostrador, la ficha del artículo, los resultados de búsqueda, la lista de Artículos (costo y precio) y el formulario del artículo; la unidad por pieza no lleva sufijo, como hoy en el ticket — Checks: specs de esos componentes
- S4a (supuesto): GDS no lo cubre (muestra la unidad en una columna aparte, L2).
- S5: «la unidad en la compra y en Movimientos» — Aceptación: la cantidad lleva unidad en el detalle de la compra, en la vista previa de la compra nueva (cantidad y stock antes → después) y en el stock de la búsqueda de la compra; en Movimientos, la cantidad y el saldo llevan unidad. El renglón «Sin artículo» de la compra no tiene unidad y queda como está — Checks: specs de compras y movimientos
- S5a (supuesto): GDS no lo cubre. El campo de cantidad de la carga queda sin unidad (L5).

## Tasks

- [x] T1 · S1, S1a, S2, S2a · ruta: inline · Aceptación: `UnidadDeLista` acepta las variantes y lee la unidad de la descripción; `PlanificadorImportacion` usa la descripción cuando la columna no trae dato · Evidencia: RED 29 fallas en `UnidadDeListaTest` y 2 en `PlanificadorImportacionTest$AltasConCamposDeLaLista` (sin el cambio del planificador) → GREEN 59 + 169; commit `5f878c5c` · Risk: none
- [x] T2 · S3, S3a · ruta: inline · Aceptación: `factura-hoja` imprime cantidad con unidad en A y en B/C · Evidencia: RED «2» y «1,5» sin unidad → GREEN `factura-hoja` + `factura-imprimible` 30/30; commit `14986dc3` · Risk: none
- [x] T3 · S4, S4a · ruta: inline · Aceptación: sufijo de precio compartido en `core/formato/unidad.ts` y usado en las cinco pantallas · Evidencia: RED 9 fallas (stub de `porUnidad`) → GREEN 6 specs; commit `64dd9775` · Risk: none
- [x] T4 · S5, S5a · ruta: inline · Aceptación: compras y movimientos con unidad · Evidencia: RED 6 fallas → GREEN 3 specs; commit `a99ec5cd` · Risk: none
- [x] T5 · S1-S5 · ruta: inline (recorrido delegado a un worker Opus con ego-browser) · Aceptación: suite backend, suite frontend, `npx ng build` y recorrido en la app local con ego-browser (importación con «Kg.» y con «x kg», factura, mostrador, compra y movimientos) · Evidencia: `./mvnw test` 6441/0 fallas, BUILD SUCCESS; `npx ng test --watch=false` 164/164 archivos; `npx ng build` OK (aviso de presupuesto de `dialogo-cobro`, previo); recorrido 5/5 PASA (L6) · Risk: none

## Log

- L1 (2026-11-08) Pedido original, textual. Propuesta de la sesión: «La tanda de unidades del #36 […] «kg» en la factura (antes, confirmarlo en la documentación de ARCA); aceptar «Kg.», «KGS» y «Kilos» en la importación; «/ kg» en los precios; la unidad en la compra y en Movimientos». Luca:
  > dale me parece correcto, como lo hace gds

  Con el plan de cuatro puntos y el ruteo, Luca:
  > dale me parece bien, igualmente estaria ueno que el sistema lo reconozca ya que sea que pongan la unidad en tabla o tambien en descripcion, ya tomamos la prueba del anterioir lista de productos, no me acuerdo si tenia algun articulo en kg o unidad

  Antecedente (#36, 2026-11-08): «eso se vende por kilo y en la lista debe decir a cuanto el kilo».
- L2 (2026-11-08) GDS y ARCA.
  - GDS (`mostrador-gestion`, `documentacion/investigaciones/gds-ferreteria-plus-7/README.md`): la unidad es una tabla auxiliar editable con alta rápida, y la importación puede traer unidades que no están en la tabla (`Actualizar Tabla de Unidades`). El listado de artículos tiene una columna `Unidad`. La impresión deja elegir la composición de la descripción del renglón: `Descripción + Marca + Rubro + Unidad`. El relevamiento no muestra unidad junto al precio, ni en las compras, ni en los movimientos.
  - ARCA: RG 1415, Anexo II, apartado A, punto I (https://biblioteca.afip.gob.ar/pdfp/rg_afip_1415_2002_a2_v2.htm). Pide «a) Descripción que permita identificar el bien vendido», «b) Cantidad de los bienes enajenados» y «c) Precios unitarios y totales». No pide unidad de medida. WSFEv1 no manda renglones. Hueco: un texto de Infoleg sobre comprobantes electrónicos que, según el buscador, menciona «unidad de medida» dio 403 y no se pudo leer.
- L3 (2026-11-08) Listas de prueba del vault. Solo `lista-bulonera-del-sur.csv` trae columna UNIDAD: 20 artículos en UN y 2 en KG (los clavos BS-2015 y BS-2016). Los Excel (pinturería, importadora, bulonera de noviembre) y el PDF de electricidad no tienen columna de unidad, y ninguna descripción dice «x kg». Sí dicen «x100m», «20m» y «4L», que son piezas.
- L4 (2026-11-08) Exploración (odd-explorer):
  - `cantidadConUnidad` (`frontend/src/app/core/formato/unidad.ts:17`) ya se usa en el remito, el presupuesto, la hoja de venta, la ficha, los resultados y Artículos. La única hoja sin unidad es la factura (`factura-hoja.html:82` y `:103`), porque `RenglonParaFactura` (`factura.models.ts:133`) no declara `unidad`, aunque `RenglonVentaResponse` la trae.
  - El sufijo «kg» del ticket es privado (`ticket-lista.ts:9`).
  - Compras: `compra.models.ts:44,94` tipa `unidad` como `string`, y `RenglonEnCarga` no la guarda.
  - Movimientos: el componente ya carga el artículo completo (`movimientos-articulo.ts:46`).
  - Importación: `UnidadDeLista.java:29-41` es el único punto, llamado desde `PlanificadorImportacion.java:310`.
  - Próximo paso: T1.
- L5 (2026-11-08) T1 a T4 hechas.
  - **T1.** La columna de unidad saca los puntos finales y acepta plurales y abreviaturas (`deAbreviatura`). La descripción se lee con el patrón «x»/«por» + unidad, sin número en el medio (`UNIDAD_EN_DESCRIPCION`). La columna gana; una unidad no reconocida en la columna sigue siendo error aunque la descripción diga «x kg».
  - **T2.** `RenglonParaFactura.unidad` (nuevo) sale del `RenglonVentaResponse`, que ya la traía. Las expectativas viejas de la hoja pasaron de «2» a «2 unidades».
  - **T3.** `porUnidad` en `core/formato/unidad.ts`. En oferta, el sufijo va en el precio de oferta y no en el tachado.
  - **T4.** El campo de cantidad de la carga queda sin unidad: la factura leída (`BorradorDeFacturaResponse.Renglon`) no trae la unidad del artículo, y agregarla cambia el contrato del backend. La unidad se ve en la vista previa antes de confirmar. Era un agregado mío, no del pedido, y se sacó de S5. `compra.models.ts` pasó `unidad` de `string` a `UnidadDeMedida`.
  - Próximo paso: T5.
- L6 (2026-11-08) T5, cierre.
  - **Suites.** La del frontend falló una vez en `app.config.spec.ts`: el artículo de ejemplo es por METRO y ahora el precio dice «/ m». Se ajustó (`dbf8eaae`). Después: backend 6441/0, frontend 164/164 y build OK.
  - **Recorrido en la app local** (worker Opus con ego-browser, Dueño de El Tornillo). Pasan los 5 pasos:
    - Importación de `lista-unidades-36.csv`: «Kg.», «KGS», «Kilos» y «x kg» dan Kilo; «por metro» da Metro; «x100m» y UN con «x kg» quedan por unidad; «caja» da «Unidad no reconocida: «caja»».
    - Artículos «$ 6.534,00 / kg».
    - Compra: vista previa «20,5 kilos» y «0 → 20,5 kilos», y el detalle dice «20,5 kilos».
    - Mostrador: ticket «$ 6.534,00 / kg»; la hoja de la venta 27 dice «1,5 kilos».
    - Movimientos: «+20,5 kilos» y «-1,5 kilos | 19 kilos».
  - **No probado en la app.** La factura A, B o C real: el ambiente local es Simulador y sale la X, que usa la misma hoja. La A y la B quedan cubiertas por `factura-hoja.spec.ts`.
  - **Fuera de alcance, visto en el recorrido.** Sin «/ kg» quedan:
    - el precio en vivo y el desglose del formulario;
    - el costo de la búsqueda de la compra;
    - los costos y precios de la vista previa y del detalle de la compra;
    - el precio unitario de la factura;
    - el precio nuevo de la importación.
  - Datos locales creados: U36-1 a U36-7, el remito 00036-00003601 y la venta 27.
  - App local apagada y MySQL detenido.
