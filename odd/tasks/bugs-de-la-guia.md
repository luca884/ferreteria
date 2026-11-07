# bugs-de-la-guia

Objetivo: arreglar los bugs de la app que vieron las tandas de capturas de la guía de uso (L6, L8, L9 y L10 de `cambios/guia-de-uso-capturas` en el vault) · Por qué: la guía los dejó anotados para otra rama.
Rama: `feat/bugs-de-la-guia` desde `9b49ef61` (encima de `feat/para-decidir-caja-y-margen`, sin mergear: S4 usa el `restado()` que esa rama cambió) · Entrega: single-pr · Runner: `cd frontend && npx ng test --watch=false --include=<spec>` y `cd backend && ./mvnw test -Dtest=<Clase>` · Mirror Engram: `odd/bugs-de-la-guia/tasks` (ok)
Pronóstico: ~750 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite) · Modelo: Opus de punta a punta (caso 5).

## Specs

Alcance: 21 de los 24 bugs, con las recomendaciones aprobadas por Luca (L1). Los textos entre comillas son del QA, tal cual.
Fuera de alcance:
- L6.3 «Tipear rápido en el cobro («2000» + Enter en el Mixto) se toma como escaneo»: queda como está. Lo dispara una herramienta que tipea con menos de 30 ms entre teclas; subir el mínimo rompe los escaneos de códigos cortos (G08).
- L9.2 «Con el Simulador, las facturas A y B se muestran como «X» con números repetidos»: queda como está. La spec pide la X («lo simulado nunca parece fiscal»), la numeración por tipo es la de ARCA (G47), y el Simulador lo tiene solo la ferretería de prueba.
- L10.3 «El lector de facturas PDF toma como descripción solo lo que está entre la cantidad y el unitario, y no lee el «Punto de Venta / Comp. Nro» de ARCA en varias columnas.»: espera una factura de ARCA real («Comprobante en línea») para el test.
- #36 y el índice del PDF de la guía.
Restricciones: los arreglos de CSS no se pueden medir con Vitest (jsdom no calcula anchos): se testea la propiedad con `getComputedStyle` cuando el estilo es del componente, y el ancho real se corrobora en ego-browser a 1280×800.

### Cobro y caja
- S1 (L6.1): «Cobro en Mixto con 5 formas de pago: en 800 px de alto el diálogo (~842 px) corta «COBRAR» y **Cancelar** y no scrollea.» — Aceptación: el diálogo de cobro tiene alto máximo de la ventana y scrollea, como los otros diálogos. — Checks: `dialogo-cobro.spec.ts`.
- S2 (L6.2): «El Dueño autoriza un exceso de cuenta corriente con ARCA activo: la fila de botones se sale del diálogo de 540 px y corta **Autorizar exceso y cobrar sin factura** y **Cancelar**.» — Aceptación: los dos botones parten su texto en renglones y no ensanchan la grilla. — Checks: `dialogo-cobro.spec.ts`.
- S3 (L6.4): «La caja del Vendedor dice «ingresos, gastos y retiros», pero el Vendedor solo tiene Ingreso y Gasto.» — Aceptación: el Vendedor lee «ingresos y gastos» en la ayuda y en el título; el Dueño sigue con «ingresos, gastos y retiros». — Checks: `caja.spec.ts`.
- S4 (L6.5): «El descuento sale «-$» con guion en el TOTAL y en la factura, y «−$» en la caja.» — Aceptación: todo monto restado sale con «−$» (U+2212) desde un único helper de `core/formato`; un monto negativo de `formatearMonto` no cambia. — Checks: `monto.spec.ts` y los specs de las hojas.
- S5 (L6.6): «El borde de foco del cobro deja a veces dos formas de pago marcadas.» — Aceptación: se reproduce en ego-browser y queda una sola forma marcada. — Checks: `dialogo-cobro.spec.ts` y ego-browser.

### Pantallas
- S6 (L8.3): «A 1280 px, la tabla de Ferreterías del admin se corre de costado, y en Proveedores el CUIT y el teléfono se parten en 3 renglones.» — Aceptación: a 1280 px la tabla del admin entra sin scroll de costado, y el CUIT y el teléfono van en un renglón. — Checks: specs de los componentes y ego-browser.
- S7 (L8.4): «En la tabla de cotizaciones del menú, compra y venta quedan pegadas («1.520,001.570,00») en la fila no resaltada.» — Aceptación: hay separación entre compra y venta en todas las filas. — Checks: `cotizacion-lateral.spec.ts` y ego-browser.
- S8 (L9.1): «En Clientes, a 1280 px, SALDO y LÍMITE quedan detrás del scroll horizontal.» — Aceptación: a 1280 px se ven sin scroll de costado. — Checks: `clientes.spec.ts` y ego-browser.
- S9 (L9.4): «La celda TOTAL de «Por artículo» tiene otro fondo.» — Aceptación: la fila del total tiene el mismo fondo que sus celdas. — Checks: `reportes.spec.ts`.
- S10 (L9.5): «En la lista de Ventas, «Nota de crédito · Venta N.º 14» parte el número en dos renglones.» — Aceptación: «Venta N.º 14» no se parte. — Checks: `ventas.spec.ts`.
- S11 (L10.2): «En Stock bajo mínimo, a 1280 px, ÚLTIMA COMPRA desborda y tapa A REPONER.» — Aceptación: a 1280 px no se pisan. — Checks: `stock-bajo-minimo.spec.ts` y ego-browser.
- S12 (L10.4): «Con el aviso de dado de baja, el paginador de Artículos se parte en dos líneas.» — Aceptación: el aviso va en su propia línea, fuera del paginador. — Checks: `articulos.spec.ts`.
- S13 (L10.5): «El rótulo «Operación» de Actualizar precios queda desalineado.» — Aceptación: los campos de «2. Qué cambio» se alinean arriba. — Checks: `actualizacion-masiva.spec.ts` y ego-browser.

### Formularios y sesión
- S14 (L8.1): «En Formas de pago, después de cancelar Desactivar y tocar una fila, el alta vacía muestra «El nombre es obligatorio.» (en Usuarios, sin confirmar).» — Aceptación: el alta vacía no muestra el error hasta que se escribe o se intenta guardar. — Checks: specs de cada pantalla.
- S15 (L8.2): «Al suspender, la sesión abierta del Dueño vuelve al ingreso sin mensaje.» — Aceptación: el login dice «Tu sesión se cerró. Ingresá de nuevo.» cuando el back cierra la sesión. — Checks: `auth.interceptor.spec.ts`, `login.spec.ts` y ego-browser.
- S16 (supuesto, L1): el arreglo de S14 va también en Rubros, Clientes y Proveedores, que tienen la misma causa.
- S17 (supuesto, L1): el aviso de S15 es genérico y solo del front; al reingresar, el login ya dice «Tu ferretería está suspendida. Contactá al proveedor.».

### Catálogo y compras
- S18 (L9.3): «La hoja del presupuesto y del remito sigue sin unidades (ya estaba pendiente).» — Aceptación: la cantidad sale con su unidad, como en el resto de la app. — Checks: `documento-no-fiscal-hoja.spec.ts`.
- S19 (L10.1): «Exportar» ignora «Solo en oferta»; quedó como Ojo en 6.8. — Aceptación: con «Solo en oferta», Exportar baja solo los artículos en oferta (la spec: baja «lo que muestra la pantalla»); otra ferretería no ve nada. — Checks: `ExportacionArticulosIntegrationTest`, `articulos.service.spec.ts`.
- S20 (L10.6): «El Vendedor no puede abrir la ficha desde la lista.» — Aceptación: el Vendedor abre la ficha de solo lectura desde la lista; el back ya le devuelve la ficha sin costos. — Checks: `articulos.spec.ts`.
- S21 (L10.7): «El tile «Flete +5 %» de «De dónde sale el precio» no muestra el monto.» — Aceptación: el ajuste del precio muestra su monto, como los recargos. — Checks: `desglose-precio.spec.ts`.
- S22 (L10.8): «La grilla de importación oculta la fila vacía.» — Aceptación: el visor muestra las filas vacías entre las que tienen datos. — Checks: `importaciones.spec.ts`.
- S23 (L10.9): «En Nueva compra, Enter no suma hasta apretar ↓.» — Aceptación: con resultados, Enter suma el primero, como en el Mostrador. — Checks: `nueva-compra.spec.ts`.
- S24 (supuesto, L1): en S22 el relleno tiene un tope, y «N filas» sigue contando solo las filas con datos.
- S25 (supuesto, L1): en S6 y S8, si el CSS no alcanza a 1280 px, Saldo y Límite van antes que Contacto (Clientes).

## Tasks

- [x] T1 · S1–S5 · ruta: inline · Aceptación: la de cada S · Evidencia: S1–S2 RED 2/138 → GREEN 139/139; S3 RED 3/36 → GREEN caja 99/99; S4 RED compile + 19/580 → GREEN 1369/1369 (46 archivos); S5 RED 1/139 → GREEN 139/139; ego-browser a 1280×800 (L3) · Risk: item 3 (S4: helper de formato que usan 8 hojas)
- [x] T2 · S6–S13, S25 · ruta: inline · Aceptación: la de cada S, medida a 1280×800 · Evidencia: RED→GREEN por pantalla (clientes 577/577, proveedores 58/58, admin 64/64, stock 10/10, ventas+reportes 92/92, artículos 461/461, masivo 224/224, layout 104/104); ego-browser a 1280×800 (L4) · Risk: none
- [ ] T3 · S14–S17 · ruta: inline · Aceptación: la de cada S · Evidencia: — · Risk: item 3 (S15: interceptor de todos los pedidos)
- [ ] T4 · S18–S24 · ruta: inline · Aceptación: la de cada S · Evidencia: — · Risk: item 3 (S19: parámetro nuevo del endpoint de exportar)

## Log

- L1 (2026-11-07) Pedido original, textual. Luca: «dale vamos con eso» (después de cerrar para-decidir-caja-y-margen, ante «Lo que sigue es la tanda de unos 24 bugs de la guía»). Ante el plan (21 con arreglo; L6.3, L9.2 y L10.3 fuera; supuestos de S16, S17, S24 y S25; «−$» en todos lados; rama encima de para-decidir; Opus; `odd-verifier` al final):
  > dale vamos con eso y cuanto nos faltaria del poryecto hablando en general
- L2 (2026-11-07) Exploración (tres `odd-explorer`, Sonnet):
  - Cobro: `.cobro` tiene `overflow:hidden` sin `max-height` (`dialogo-cobro.ts:272-276`); los otros diálogos usan `max-height: calc(100vh - var(--e-8)); overflow-y:auto`. `.elige-factura` es `1fr 1fr` con botones `nowrap` (`:548-551`). `elegir()` no hace nada con una opción deshabilitada (`:880-884`).
  - Descuento: 8 plantillas prefijan «-» a un monto positivo (`factura-hoja.html:126,132`, `documento-no-fiscal-hoja.html:86`, `venta-hoja.html:66,73`, `totales-ticket.ts:23`, `presupuestos.html:174`, `remitos.html:165`); la caja usa `restado()` (`resumen-caja.ts`).
  - Caja: `caja.html:9-10` y `:42` tienen el texto fijo.
  - Formularios: `invalido()` = `touched && invalid` (`formas-pago.ts:239-243`, `usuarios.ts:278-281`, `rubros.ts:170`, `clientes.ts:378`, `proveedores.ts:183`).
  - Sesión: todo 401 llega como «No autenticado» (`SecurityConfig.java:84-89`); `auth.interceptor.ts:29-37` navega a `/login` sin motivo.
  - Pantallas: `.listado.con-alta` resta 340 px; `th` global es `sticky`, gris y en mayúsculas (`styles.css:557-570`); `ventas.html:126`; `stock-bajo-minimo.css:11-13`; `articulos.html:123-133`; `actualizacion-masiva.css:16-22`; `cotizacion-lateral.css:130-175`.
  - Catálogo: `documento-no-fiscal.models.ts:17` sin `unidad`; exportar sin `enOferta` (`articulos.service.ts:43-47`, `ExportacionArticulosController.java:32-41`); `articulos.ts:60-64,128-129` solo Dueño; `desglose-precio.ts:167-169` ajuste con `importe: null`; los lectores descartan las filas vacías (`LectorCsv.java:50`, `LibroXlsx.java:517`, `LibroXls.java:375`); `nueva-compra.ts:170-173` deja `activo = -1`.
  - Próximo paso: T1.
- L3 (2026-11-07) T1 hecha.
  - S1: `.cobro` con `max-height: calc(100vh - var(--e-8))` y `overflow-y: auto`. En ego-browser a 1280×800, con el Mixto de 7 formas, el diálogo mide 768 px, la rueda lo baja y los tres botones quedan a la vista.
  - S2: con columnas iguales (`minmax(0, 1fr)`), «Cobrar y facturar con ARCA» se partía en dos renglones también en el caso normal, que nadie pidió cambiar. Las dos formas pasaron a un `div.formas-de-cobrar` flex con `flex-wrap` y botones `flex: 1 1 auto`: en el caso normal comparten la fila en un renglón cada uno, como antes; con el exceso («Verificacion L10», saldo $ 3.500 y límite $ 5.000, ticket $ 1.569,60) cada uno baja a su renglón, a todo el ancho y dentro del diálogo.
  - S3: `movimientosQueCarga` en `caja.ts` y el título del formulario según `auth.esDueno()`.
  - S4: `formatearRestado` y `RestadoPipe` en `core/formato/monto.ts`; las 8 plantillas, `resumen-caja.ts` y el descuento por forma de pago del cobro lo usan. `formatearMonto` de un negativo (la anulación en la lista de Ventas) sigue con su guion. En ego-browser, el ticket con 10 % de descuento dice «Descuento −$ 156,96» (U+2212).
  - S5: reproducido en ego-browser. Justo después de cambiar de forma con la flecha se ven dos marcadas: la anterior se apaga con la transición de 150 ms del botón global, y además conserva el hover del mouse apoyado. Con `.medio { transition: none }`, la anterior queda solo con el gris del hover.
  - Próximo paso: T2.
- L4 (2026-11-07) T2 hecha. Todo medido en ego-browser a 1280×800, antes y después.
  - S8 Clientes: la tabla medía 820 px y entraban 663. Se parte entre el tipo y el número del documento (el número nunca), el saldo («a favor»), el contacto y los encabezados, con 10 px de margen en las celdas. Igual queda en 689 px: los mínimos son el número del CUIT y los encabezados de una palabra («CONTACTO», «ESTADO»). Con S25: Saldo y Límite van antes que Contacto, que quedó al final; a 1280 px quedan 25 px de Contacto detrás del scroll. El Vendedor ve Contacto después de Estado.
  - S6 Proveedores: CUIT, teléfono y WhatsApp en un renglón. Así la tabla pasaba a desbordar 11 px (antes el CUIT partido le dejaba lugar): el correo se puede partir en cualquier lado y entra (678 de 678).
  - S6 Admin: 11 columnas, 1125 px contra 663. Entre 901 y 1640 px el alta va abajo de la tabla (sticky apagado); los encabezados se parten, el email solo después de la arroba (`<wbr>`, `break-word`) y las celdas llevan 10 px de margen: 1019 de 1019. A 1700 px el alta sigue al costado.
  - S7: la columna del nombre pasa de 36 % a 32 % («Mayorista» sigue entrando) y la compra lleva `var(--e-2)` a la derecha: entre compra y venta quedan de 7 a 16 px, con o sin resaltado.
  - S9: `.fila-total th` sin fondo, mayúsculas, sticky ni letra chica (computado igual que su `td`).
  - S10: `Venta&nbsp;N.º&nbsp;n`; en Ventas, «Venta N.º n» queda en un renglón en las tres notas.
  - S11: la fecha de la última compra en un span `nowrap`; el proveedor se parte. Sin desborde (1068 de 1068).
  - S12: el motivo va en una `p.motivo-linea` reservada entre el paginador y la grilla (como Clientes), con el mismo id y texto; el paginador queda en una línea (46 px).
  - S13: `fila-cambio` con `align-items: flex-start`: «Operación» y «Porcentaje (%)» a la misma altura (318 y 318; antes 356 y 318).
  - Próximo paso: T3.
