# bugs-relevamiento-celular

Objetivo: arreglar los bugs funcionales (no de responsive) que encontró el relevamiento en celular del 2026-11-09 · Por qué: Luca pidió tratarlos en una rama aparte del responsive (L1).
Rama: `fix/bugs-relevamiento-celular` desde `c65fccf5` (worktree `../ferreteria-bugs-celular`) · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / suite `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` / `npx ng build` · Mirror Engram: `odd/bugs-relevamiento-celular/tasks` (ok)
Pronóstico: ~650 líneas · Heurística por tarea: ~400 (no es límite) · Modelo: una terminal con Opus en el worktree. T2, T5, T7 y T8 arrancan ya; T1, T3, T4 y T6, después del merge de `feat/responsive` (L6)

## Specs

Alcance: los 10 bugs y textos de S1–S10, que salen del relevamiento (`odd/tasks/responsive.md`, L2) y del mapa del código (L3).
Fuera de alcance:
- Todo lo de responsive (`feat/responsive`).
- Controles de datos «Sin rubro» con 4.133 filas sin páginas. Es una decisión de CU-03 tomada como GDS (G81): «sin páginas, listado para imprimir, como los de GDS» (`odd/tasks/cu-03-controles-de-datos.md`, S1). Se le avisa a Luca al cerrar y no se toca.
- Los avisos del mostrador que se acumulan, el código anterior que queda seleccionado después de vender y «Salir» sin confirmación: no está claro que sean bugs.

Restricciones:
- Orden (L6): T2, T5, T7 y T8 arrancan ya desde `c65fccf5`, en paralelo con `feat/responsive`, porque casi no tocan lo mismo. T1, T3, T4 y T6 esperan el merge del responsive y el rebase de T0, porque cruzan con sus archivos: `nueva-compra.html:28`, las tablas y tarjetas de `reportes.html`, los filtros de los 5 listados, `presupuestos.html:6` y los specs de Ventas, Presupuestos y Remitos. Después va T9.
- Mientras corran las dos terminales:
  - Esta rama se prueba en 8081 y 4201 contra la misma MySQL (memoria «probar la rama en puertos aparte»): `./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081` y `npx ng serve --port 4201` con un proxy de `/api` a 8081. La del responsive usa 8080 y 4200: no se tocan.
  - Solo tests puntuales (`-Dtest=<Clase>`, `--include=<spec>`). Las suites completas van en T9, después del rebase («Nunca dos suites a la vez»).
- Ninguna migración.
- Si cambia un endpoint de datos de una ferretería, lleva su caso de aislamiento por tenant (regla 7 del `CLAUDE.md`).
- Cada arreglo se prueba de punta a punta en la app levantada con ego-browser: antes en `main` y después en la rama (memorias «probar de punta a punta» y «verificar con ego-browser»).
- Usuarios locales: `dueno@perla.qa`, `dueno@puerto.qa` y `dueno@faro.qa`, todos con `demo1234`.

- S1 (CUIT vacío): «dale vamos con eso y luego con los bugs que no son de responseive en otra rama» (L1), sobre el bug «Nueva compra: aparece «No encontramos un proveedor con el CUIT **.** Elegilo de la lista.», con el CUIT vacío (`nueva-compra.html:39`)» (L2). Aceptación:
  - Si el PDF no trae ningún CUIT, la pantalla dice «No encontramos el CUIT del proveedor en la factura. Elegilo de la lista.» El texto es un supuesto (S11a).
  - Con uno o más CUIT sin proveedor, sigue como hoy.
  - Causa: `cuits` llega como `[]`, que en JS es truthy (`nueva-compra.html:37`, `nueva-compra.ts:307`).
  - Checks: `nueva-compra.spec.ts` RED → GREEN con `cuits: []`; el caso de 2 CUIT (`nueva-compra.spec.ts:883`) sigue verde.
- S2 (lista leída como factura): misma cita de L1, sobre el bug «Nueva compra: si se sube un PDF de lista de precios como si fuera una factura, se crean miles de renglones «Sin artículo». La página queda de unos 153.000 px» (L2). Aceptación:
  - Supuesto S11b: la lectura de factura en PDF admite hasta 500 renglones, el mismo tope que la compra (`CompraRequest.java:51`).
  - Si son más, responde 400 «La factura trae más de 500 renglones: no parece una factura. Cargá la compra a mano.» y la pantalla lo muestra sin cargar renglones.
  - Se actualiza `docs/spec.md:271-281` con la regla.
  - Checks: test del servicio (`LecturaDeFactura` o `FacturaDePdf`) RED → GREEN con 501 renglones; `FacturaPdfIntegrationTest` y `CompraFacturaPdfRegresionIntegrationTest` siguen verdes; ego-browser con `Lista/LISTA 336.pdf` en Nueva compra.
- S3 (Reportes, resultado viejo): misma cita de L1, sobre el bug «Reportes: con un rango de fechas inválido sale el error y, debajo, el «No hubo ventas…» de la consulta anterior» (L2). Aceptación:
  - Cuando el rango falla en la pantalla o el servidor da error, el resultado anterior desaparece, incluido el reporte de caja imprimible.
  - Causa: `reportes.ts:140-160` no limpia `resultado`.
  - Checks: `reportes.spec.ts` RED → GREEN, junto a `:359-381`.
- S4 (rango invertido): misma cita de L1, sobre el bug «Ventas: si el rango de fechas está al revés, no avisa» (L2). Aceptación:
  - En Ventas, con «Desde» posterior a «Hasta», la pantalla muestra «La fecha «Hasta» no puede ser anterior a «Desde».» y no consulta. Es el mismo texto de Reportes (`reportes.models.ts:170-175`, `ReporteVentas.java:119-127`).
  - El servidor responde 400 con ese mismo texto, y el front ya lo muestra (`ventas.ts:236-243`).
  - Supuesto S11c: lo mismo en los otros listados con Desde/Hasta que tienen el mismo hueco: Compras, Presupuestos, Remitos y Movimientos del artículo (`MovimientosArticulo.java:48`).
  - `ExtractoCuenta.java:71-73` pasa a ese mismo texto. Supuesto: texto único.
  - Checks: spec de cada pantalla RED → GREEN; un test de integración por endpoint con el 400; los casos de rango válido siguen verdes.
- S5 (tope de Consumidor Final): misma cita de L1, sobre el bug «Consumidor Final: si escribís «-5» se guarda 5,00 sin avisar» (L2). Aceptación:
  - Supuesto S11d: el campo del tope acepta el «-» (`negativos` de `shared/importe-input.ts:38-39`), así se ve el mensaje que ya existe, «El tope tiene que ser mayor que $ 0.» (`configuracion-ventas.html:56-61`), y no se guarda.
  - Los otros 13 campos de importe sin `negativos` siguen como hoy.
  - Checks: `configuracion-ventas.spec.ts` RED → GREEN; `importe-input.spec.ts:120-124` sigue verde.
- S6 (color de la ganancia): misma cita de L1, sobre el bug «Reportes: «Ganancia con NC» sale en verde aunque sea negativa» (L2). Aceptación:
  - Los importes de ganancia del resumen se pintan en verde cuando son mayores o iguales a 0 y en rojo (`--error`) cuando son menores, con la convención `.negativo` (`stock-bajo-minimo.html:80`, `articulos.html:228`).
  - La columna Ganancia de las tablas no cambia.
  - Causa: `reportes.css:45-47` pinta fijo.
  - Checks: `reportes.spec.ts` RED → GREEN con ganancia negativa y positiva.
- S7 (volver al documento de origen): misma cita de L1, sobre el bug «Links entre documentos: si vas de la venta al presupuesto, al cerrarlo no volvés a la venta» (L2). Aceptación:
  - Supuesto S11e: un pop-up que se abrió desde el link de otro documento vuelve a ese documento al cerrarse (`Location.back()` con una marca en el `state` del link).
  - Abierto por URL directa o desde su lista, sigue volviendo a la lista como hoy (`odd/tasks/informacion-y-links.md`, S2).
  - Aplica a Ventas, Presupuestos, Remitos y Compras (`presupuestos.ts:209-219`, `ventas.ts:312-323`, `remitos.ts:214-223` y `compras.ts`) y a los links de `venta-hoja.ts:50-60`, «Anula la venta», «Vendido en…» y «Facturado en…».
  - Checks: RED → GREEN en los specs de las 4 pantallas; `presupuestos.spec.ts:861-880`, `ventas.spec.ts:1186-1226` y `remitos.spec.ts:932-960` siguen verdes para la URL directa.
- S8 (datos fiscales vacíos): misma cita de L1, sobre el hallazgo «Datos fiscales: domicilio vacío sin error visible» (L2). Hoy es correcto por spec: guardar vacío vale (`docs/spec.md:44`, D1 del 2026-10-28) y se exige recién al habilitar la facturación (`ConfigArcaParaPlataformaImpl.java:122-140`). Aceptación:
  - S11f, confirmado: debajo de Domicilio, Inicio de actividades e Ingresos Brutos, cuando están vacíos, va un aviso no bloqueante «Completalo para poder facturar.» con `.campo-aviso` (`styles.css:864`). El campo está ahí mismo: el Dueño lo completa, guarda y el aviso desaparece (S12).
  - Se sigue pudiendo guardar vacío y no cambia el contrato.
  - S12: cuando el super admin intenta habilitar la facturación y faltan datos (`ConfigArcaParaPlataformaImpl.java:124-140`), el mensaje dice cuáles faltan y dónde se completan: «Los completa el Dueño en Configuración → Datos de la ferretería.». Los edita el Dueño (CU-01, `admin-ferreterias.ts:71`). Cuando están completos, el super admin vuelve a tocar «Habilitar».
  - Checks: `datos-fiscales.spec.ts` RED → GREEN (aviso con el campo vacío, sin aviso con el campo completo); `:92-110` sigue verde; test del mensaje de habilitación RED → GREEN; ego-browser: vaciar el domicilio, ver el aviso, completarlo, guardar y que el aviso desaparezca.
- S9 (numeración simulada repetida): misma cita de L1, sobre el hallazgo «En dos facturas simuladas se repite la numeración. Probablemente sea el simulador de desarrollo, pero no lo confirmé» (L2). Aceptación:
  - Primero se confirma con la base local: `cbte_tipo`, `ambiente` y `punto_venta` de las ventas 7 y 10 y de las NC 18 y 20 de Perla.
  - Si son series distintas (causa probable: fuera de producción la etiqueta lleva siempre «X» y oculta el tipo, `HojaFiscal.java:64-66,100-122`, `ventas.ts:256-268`), la etiqueta simulada muestra la letra real: «X (B) 00001-00000002». Supuesto S11g.
  - La marca de simulado se mantiene.
  - Si la causa es otra, se anota en el Log y se frena para decidir.
  - Checks: los tests de `HojaFiscal` y `ventas.spec.ts:148-151,237,648-651` actualizados a propósito, RED → GREEN.
- S10 (textos): misma cita de L1, sobre «Importación aplicada: dice «Los precios ya están actualizados» aunque todo fue «Nuevos»» y «Completa el comprobante y los renglones con lo que lee de la factura (Nueva compra): poco claro/sin sujeto» (L2). Aceptación:
  - En `importaciones.html:35`, «Los precios ya están actualizados.» sale solo si algún precio cambió.
  - Si hubo artículos creados, se agrega «Se crearon N artículos nuevos.». Si fue todo nuevo, va solo esa frase. Se usan `r.conteos` (`importacion.models.ts:54-64`).
  - El texto de ayuda de `nueva-compra.html:28` pasa a «Completamos el comprobante y los renglones con lo que leemos de la factura.». La segunda frase, la de arrastrar, queda como la deje el responsive.
  - Checks: `importaciones.spec.ts` RED → GREEN (solo nuevos, mixto, solo cambios); `nueva-compra.spec.ts` con el texto nuevo.
- S12 (que el usuario pueda arreglar lo que falta): «me parece bien ,lo unico si se le dice al usuario falta X nosotros le tenemos que dar la posibilidad de quelo puedo arreglar, por ejemplo los datos fiscales con el aviso ese el ususario debe poder arreglar essos datos y probar de nuevo, es lo mismo que en un formulario te diga pone tu email y no te de un lugar para ingresarlo» (L5). Aceptación: todo mensaje de esta rama que diga que falta algo, o que algo está mal, lleva al lugar donde se arregla, y después se puede reintentar:
  - S1: el aviso del CUIT queda junto al select de proveedor, que se enfoca. Al elegir el proveedor, el aviso desaparece.
  - S2: con el 400 de «no parece una factura», el formulario de Nueva compra queda vacío y usable para cargar a mano, y se puede subir otro PDF.
  - S4: con el rango invertido se enfoca el campo «Hasta»; al corregirlo, se busca de nuevo.
  - S5: el mensaje sale debajo del campo del tope, que se enfoca.
  - S8: el aviso va debajo de cada campo vacío, en la misma pantalla. El mensaje del super admin dice dónde se completan.
  - Checks: cada caso en su spec (el foco o la presencia del control junto al mensaje) y en ego-browser, arreglando y reintentando.
- S11 (supuestos, sin cobertura de GDS; confirmados por Luca, L5):
  - (a) Texto de S1.
  - (b) Tope de 500 renglones en la lectura de factura.
  - (c) Validar el rango en todos los listados con Desde/Hasta, con un texto único.
  - (d) Solo el campo del tope de Consumidor Final acepta el «-».
  - (e) Volver al documento de origen con `Location.back()` y `state`.
  - (f) Aviso no bloqueante en los 3 datos fiscales vacíos.
  - (g) Letra real entre paréntesis en la etiqueta simulada.

## Tasks

Orden (L6): T2, T5, T7, T8 → T0 → T1, T3, T4, T6 → T9.

- [ ] T0 · rebase después del responsive · ruta: inline · Aceptación: antes de T1, T3, T4 y T6, `feat/responsive` está mergeada en `main` y esta rama queda rebaseada, con los conflictos resueltos. Los esperables están en la línea 14 de `configuracion-ventas.html` y `datos-fiscales.html` (T5), donde el responsive pone su directiva de avisos. Después se repiten los tests puntuales de T2, T5, T7 y T8 · Evidencia: — · Risk: none
- [ ] T1 · S1, S10 (texto de Nueva compra), S12 · ruta: inline · Aceptación: S1, el texto de S10 en Nueva compra y lo de S1 en S12 · Evidencia: — · Risk: none
- [x] T2 · S2, S12 · ruta: inline · Aceptación: S2 y lo de S2 en S12 · Evidencia: `LecturaDeFacturaTest` RED (501 renglones no lanzaba) → GREEN 2/2; `FacturaPdfIntegrationTest` 8/8 y `CompraFacturaPdfRegresionIntegrationTest` 6/6 verdes; `nueva-compra.spec.ts` 58/58 con el caso nuevo de S12 (verde de entrada: el front ya dejaba el formulario usable); `docs/spec.md` actualizado; ego-browser (L7) · commit 819f064f · Risk: none (cambio pedido, endpoint que solo consume el front)
- [ ] T3 · S3, S6 · ruta: inline · Aceptación: S3 y S6 (los dos en `reportes/`) · Evidencia: — · Risk: none
- [ ] T4 · S4, S12 · ruta: inline · Aceptación: S4 y lo de S4 en S12 · Evidencia: — · Risk: item 3 (5 endpoints `GET` de listados pasan de 200 vacío a 400 con rango invertido) → `odd-verifier` al cierre
- [x] T5 · S5, S8, S12 · ruta: inline · Aceptación: S5, S8 (incluido el mensaje del super admin) y lo de S5 y S8 en S12 · Evidencia: `configuracion-ventas.spec.ts` RED («-5» quedaba «5,00») → GREEN 8/8, y `importe-input.spec.ts` sigue verde (22/22 entre los dos); `datos-fiscales.spec.ts` RED (sin avisos) → GREEN 13/13, incluido `:92-110`; `ConfigArcaTest` RED 2 → GREEN 31/31 y `ConfigArcaAdminIntegrationTest` 23/23 con el texto nuevo; ego-browser (L8) · commit: ver L8 · Risk: none
- [ ] T6 · S7 · ruta: inline · Aceptación: S7 · Evidencia: — · Risk: none
- [ ] T7 · S9 · ruta: inline · Aceptación: S9 (primero confirmar la causa con la base) · Evidencia: — · Risk: none
- [ ] T8 · S10 (importación) · ruta: inline · Aceptación: S10 en `importaciones.html:35` · Evidencia: — · Risk: none
- [ ] T9 · cierre · ruta: inline + `odd-verifier` (Opus) por T4 · Aceptación: suite backend (`./mvnw test`, sin otra suite corriendo), suite frontend y `npx ng build` verdes; cada bug probado en ego-browser, antes en `main` y después en la rama · Evidencia: — · Risk: item 3 (T4)

## Log

- L1 (2026-11-09) Pedido, textual (respuesta a «Cuando terminen junto todo en un solo reporte […] Mi recomendación es armar el feature doc de la rama del responsive»):
  > dale vamos con eso y luego con los bugs que no son de responseive en otra rama
- L2 (2026-11-09) Los bugs funcionales que encontró el relevamiento en celular (detalle en `odd/tasks/responsive.md`, L2, de la rama `feat/responsive`), tal como se le reportaron a Luca:
  1. Nueva compra: aparece «No encontramos un proveedor con el CUIT . Elegilo de la lista.», con el CUIT vacío.
  2. Nueva compra: si se sube un PDF de lista de precios como si fuera una factura, se crean miles de renglones «Sin artículo». La página queda de unos 153.000 px.
  3. Controles de datos → «Sin rubro»: muestra las 4.133 filas juntas, sin páginas.
  4. Reportes: con un rango de fechas inválido sale el error y, debajo, el «No hubo ventas…» de la consulta anterior.
  5. Ventas: si el rango de fechas está al revés, no avisa.
  6. Consumidor Final: si escribís «-5» se guarda 5,00 sin avisar.
  7. Reportes: «Ganancia con NC» sale en verde aunque sea negativa.
  8. Links entre documentos: si vas de la venta al presupuesto, al cerrarlo no volvés a la venta.

  Además, sin confirmar: datos fiscales con domicilio vacío sin error, numeración simulada repetida y dos textos.
- L3 (2026-11-09) Mapa del código (`odd-explorer`). De los 11 puntos, hay 7 con causa confirmada en el código:
  - 1: `FacturaDePdf.java:332-364`, `nueva-compra.ts:307`, `nueva-compra.html:37`.
  - 3: decisión de CU-03.
  - 4: `reportes.ts:140-160`.
  - 5: `ventas.ts:266-291`, `VentaGestion.java:125-134`.
  - 6: `importe-input.ts:38-39`, `importe.ts:10-25`.
  - 7: `reportes.css:45-47`.
  - 8: `navigateByUrl` fijo a la lista.

  El resto:
  - El 2 se confirmó sin tope ni chequeo (`LecturaDeFactura.java:36-40` solo rechaza 0 renglones); no se confirmó por qué una lista arma renglones.
  - El 9 es correcto por spec (`docs/spec.md:44`).
  - El 10 es probablemente la etiqueta «X», que oculta el tipo. La base no permite duplicados (`V32__facturacion.sql:129`).
  - Los textos están en `importaciones.html:35` y `nueva-compra.html:28`.

  GDS no cubre las decisiones abiertas, así que quedan como supuestos en S11 (memoria «dudas de producto como GDS»).
- L4 (2026-11-09) Progreso: doc escrito y commiteado en `fix/bugs-relevamiento-celular`. Ninguna tarea empezada. Próximo paso: Luca confirma o corrige los supuestos de S11; la ejecución arranca por T0 después del merge del responsive.
- L5 (2026-11-09) Luca confirma los 7 supuestos de S11 y agrega una regla («¿Te cierran los 7 supuestos o cambiás alguno?»), textual:
  > me parece bien ,lo unico si se le dice al usuario falta X nosotros le tenemos que dar la posibilidad de quelo puedo arreglar, por ejemplo los datos fiscales con el aviso ese el ususario debe poder arreglar essos datos y probar de nuevo, es lo mismo que en un formulario te diga pone tu email y no te de un lugar para ingresarlo

  La regla pasa a S12 y se aplica a los mensajes de esta rama. El único caso donde quien ve el aviso no puede arreglarlo es el super admin al habilitar la facturación (los datos los edita el Dueño, CU-01): ahí el mensaje dice dónde se completan. El aviso del Dueño pasa a «Completalo para poder facturar.». Próximo paso: la ejecución arranca por T0 después del merge del responsive.
- L6 (2026-11-09) Luca pregunta: «¿Arranco ahora por T2, T5, T7 y T8, o esperamos a que se mergee el responsive como dice el doc?». Se revisó contra el plan del responsive (`odd/tasks/responsive.md`, rama `feat/responsive`, que todavía no tiene código commiteado):
  - T2 es backend y `docs/spec.md`.
  - T7 es `HojaFiscal.java` y la etiqueta en `ventas.ts`; el responsive toca `ventas.html`.
  - T8 está en `importaciones.html:35`; el responsive toca el selector de hojas, en `:88`.
  - T5 solo roza la línea 14 (`p.alerta`) de dos plantillas.

  Las otras cuatro cruzan con el responsive. Se propone partir T0: rebase antes de T1, T3, T4 y T6, la app en 8081/4201 y solo tests puntuales hasta T9. Respuesta, textual:
  > dale

  Próximo paso: una terminal con Opus en `../ferreteria-bugs-celular` arranca por T2.
- L7 (2026-11-09) T2 hecha (commit 819f064f). El tope va en `LecturaDeFactura` (compras), después del chequeo de 0 renglones y antes de buscar proveedor y artículos. El front no cambió: con el 400 ya dejaba el formulario vacío y usable; el caso nuevo de `nueva-compra.spec.ts` lo fija. Ego-browser con `Lista/LISTA 336.pdf` en Nueva compra (Perla):
  - Antes (4200, `feat/responsive`, igual a `main` en estos archivos): sin aviso y 1.157 renglones «Sin artículo».
  - Después (8081/4201): «La factura trae más de 500 renglones: no parece una factura. Cargá la compra a mano.», 0 renglones, «Todavía no agregaste artículos.», el proveedor se elige y el selector de PDF sigue habilitado. Reintento con `Facturas/pdf (17).pdf`: se lee, el aviso desaparece y la lectura cuadra.
  - Observación, fuera de alcance: los errores de lectura salen en la alerta de abajo, lejos del selector de PDF (igual que «parece escaneado»). En el celular puede quedar fuera de la vista; se le comenta a Luca.

  `npm ci` en `frontend/` ya corrido en este worktree. Próximo paso: T5.
- L8 (2026-11-09) T5 hecha (el código en el commit «fix: el tope de Consumidor Final no se guarda negativo…»).
  - S5: el campo del tope lleva `negativos` y, si el formulario no es válido al guardar, se enfoca el campo.
  - S8: el aviso «Completalo para poder facturar.» sale de lo guardado, no de lo que se escribe: se va al completar y guardar. Si el campo tiene un error, se muestra el error. El aviso entra en el `aria-describedby`.
  - Super admin: «Lo/Los carga el Dueño en «Datos de la ferretería».» pasa a «Lo/Los completa el Dueño en Configuración → Datos de la ferretería.» (`ConfigArca.faltanDatos`). Los textos de `PasosFiscales` (los ve el Dueño al facturar) no se tocan.

  Ego-browser (Perla; el «antes» en 4200 y el «después» en 8081/4201, misma base):
  - Antes: «-5» en el tope se guardó como 5,00 con «Se guardó el tope.»; con el domicilio vacío y guardado, ningún aviso.
  - Después, S8: con el domicilio vacío, «Completalo para poder facturar.» debajo del campo; se completó, se guardó y el aviso se fue.
  - Después, S5: con «-5» queda «-5,00», «El tope tiene que ser mayor que $ 0.» y no se guarda. Si se toca «Guardar» con el foco todavía en el campo, el primer toque no envía: el blur muestra el error, el botón baja entre el `mousedown` y el `mouseup` y el click cae en el `form` (registrado con los eventos). Igual el mensaje queda debajo del campo y nada se guarda; el segundo toque envía y enfoca el campo. Hoy pasa lo mismo con «0»: es el corrimiento de cualquier error que aparece al salir del campo. Se le comenta a Luca y no se toca.
  - El mensaje del super admin no se probó en el browser porque su login pide TOTP. Lo cubre `ConfigArcaAdminIntegrationTest`.
  - Se restauraron el tope (100.000,00) y el domicilio de Perla.

  Próximo paso: T7.
