# decisiones-qa

Objetivo: cerrar las «Decisiones de producto que salieron» del QA funcional del 05/11 con lo que Luca aprobó: la NC resta al vendedor, aviso de EAN-13 inválido, «Precio s/IVA» como costo, sin la marca «No facturable» en las notas, 404 con «No se encontró…» en ventas, presupuestos y remitos, y el doble redondeo documentado · Por qué: la nota del QA las dejó para esta sesión; medios y bajos ya están en `main`.
Rama: `fix/decisiones-qa` desde `046a582` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` · frontend `npx ng test --watch=false --include=<spec>` · Mirror Engram: `odd/decisiones-qa/tasks` (ok)
Pronóstico: ~350 líneas cambiadas · Heurística por tarea: ~400 (no es límite) · Ruteo: caso 3 (mecánico), ejecutado en la misma sesión

## Specs

Alcance: las recomendaciones que aprobó Luca («vamos con tus recomendaciones»): puntos 1, 2, 5 y 7 de la nota del QA, el extra del 404 y la línea de spec del punto 3 · Fuera de alcance: baja de rubros (punto 4), la letra X con el Simulador (punto 6), cambiar el orden de redondeo de los ajustes (punto 3 queda como está), la importación (no avisa del EAN).
Restricciones: multi-tenant (regla 1 a 3 de CLAUDE.md); plata en `BigDecimal`; test-first; si cambia una pantalla, revisar la captura de la guía de uso.

- S1: «Por usuario» resta las NC a quien anuló, no a quien vendió — Aceptación: en `/api/ventas/reportes/por-usuario` la nota de crédito y la anulación interna restan cantidad, total y ganancia en la fila del usuario que hizo la venta anulada; el Dueño que anuló no aparece con totales negativos por eso — Checks: `./mvnw test -Dtest=NotaDeCreditoEnLosLectoresIntegrationTest,ReporteVentasIntegrationTest`
- S2: Se acepta un EAN-13 con el dígito verificador mal, sin aviso — Aceptación: en el formulario de artículo, un código de barras de 13 dígitos con el verificador incorrecto muestra un aviso debajo del campo y deja guardar igual; con 13 dígitos correctos, con otra longitud o vacío no hay aviso — Checks: `npx ng test --watch=false --include=src/app/features/articulos/articulo-form.spec.ts`
- S3: La columna «Precio s/IVA» no se reconoce sola como costo — Aceptación: una lista cuya única columna de precio es «Precio s/IVA» (o «Sin IVA», «Neto», «s/IVA») propone esa columna como costo; con una columna de costo reconocida además, el par de IVA sigue valiendo solo si están las dos — Checks: `./mvnw test -Dtest=ReconocedorDeColumnasTest`
- S4: Anulación interna de una venta sin facturar queda como «No facturable» — Aceptación: en la lista de Ventas, las filas que son nota (NC o anulación interna) no muestran la marca «No facturable»; la venta no facturable la sigue mostrando — Checks: `npx ng test --watch=false --include=src/app/features/ventas/ventas.spec.ts`
- S5: el «No se pudo cargar» de ventas, presupuestos y remitos de otra ferretería — Aceptación: con 404 el detalle dice «No se encontró la venta.», «No se encontró el presupuesto.» y «No se encontró el remito.»; con otro error sigue el texto de hoy, igual que compras (`4f853eb`) — Checks: specs de `ventas`, `presupuestos` y `remitos`
- S6: «dejarlo así y documentarlo en una línea de la spec» (punto 3: ajustes sobre el precio ya redondeado, redondeado otra vez) — Aceptación: CU-14 punto 3 de `docs/spec.md` dice que el precio calculado ya viene redondeado y el resultado se redondea otra vez — Checks: lectura
- S7 (supuesto): el aviso del EAN se calcula con el algoritmo estándar (suma ponderada 1 y 3, módulo 10) sobre los 13 dígitos ya recortados; un código con letras o de otra longitud no es un EAN y no se evalúa.
- S8 (supuesto): en la hoja imprimible de la venta (`venta-hoja`) la marca «No facturable» de una nota también se omite si la hoja se muestra para una nota.

## Tasks

- [ ] T1 · S1 · ruta: inline · Aceptación: la fila del vendedor queda en 0 tras anular su venta; el Dueño sin fila negativa · Evidencia: — · Risk: none (test de integración lo cubre; el reporte es solo lectura)
- [ ] T2 · S2 S7 · ruta: inline · Aceptación: aviso con 13 dígitos y verificador mal; sin aviso en los demás casos; guarda igual · Evidencia: — · Risk: none
- [ ] T3 · S3 · ruta: inline · Aceptación: «Precio s/IVA» sola → `columnaCosto`; par de IVA sin cambios · Evidencia: — · Risk: item 3 (compartido con todas las importaciones: una lista que hoy no reconoce costo pasa a reconocerlo; una con «Lista» + «Sin IVA» no cambia, pinneado por `parDeIva`)
- [ ] T4 · S4 S8 · ruta: inline · Aceptación: sin «No facturable» en filas nota · Evidencia: — · Risk: none
- [ ] T5 · S5 · ruta: inline · Aceptación: 404 → «No se encontró…» en las tres pantallas · Evidencia: — · Risk: none
- [ ] T6 · S6 · ruta: inline · Aceptación: línea en CU-14 · Evidencia: — · Risk: none
- [ ] T7 · cierre · suite backend, suite frontend, `ng build`, prueba de punta a punta con ego-browser, nota del QA y `estado-actual.md` en Obsidian · Evidencia: —

## Log

- L1 (2026-11-06) Pedido original, textual:
  > nos habiamos qeudado con las decisiones de producto, dame el contexto de cada uno y tu recomendacion
  > vamos con tus recomendaciones y se me paso algo por la cabeza, a la hora de usar el escaner por codigo de barra como busca eso el producto ezxato si al crearlo ya te pone un codigo o vos mismo lo podemos modificar como se hace esa busqueda y como se realcionael codigo de barra de cualqueir producto con la oinformacion y datos que tiene un producto hoy en dia en el poreycto
- L2 (2026-11-06) Exploración: `ReporteVentas.java:167` agrupa por `c.usuarioId` y `ComprobanteRepository.java:51` lo documenta («la NC es de quien anulo»); la nota guarda la venta en `comprobante_asociado_id` (`Comprobante.java:105`). El test `NotaDeCreditoEnLosLectoresIntegrationTest.porUsuarioLaNotaEsDeQuienLaHizo` pinnea el comportamiento viejo y se invierte. El formulario de artículo no valida el código de barras (`articulo-form.ts:263`, solo `maxLength`). `ReconocedorDeColumnas.java:169` descarta el par de IVA si falta una de las dos; «Precio s/IVA» normaliza a `PRECIO S IVA` y no está en `SinonimosDeColumnas.java:42`. `ventas.html:102` muestra «No facturable» con `!fila.facturable` sin mirar si es nota. Compras ya distingue el 404 (`compras.ts:116-119`, `4f853eb`); ventas, presupuestos y remitos no.
