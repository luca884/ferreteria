# guia-de-uso-al-dia

Objetivo: que la guía de uso vuelva a coincidir con la app después de los PR #29 y #30, y cerrar el residuo de Clientes a 1280 px.
Rama: `fix/clientes-1280` desde `c183a7f0` (main) · Entrega: single-pr (repo: solo T1 y este doc; la guía vive en el vault) · Runner: `cd frontend && npx ng test --watch=false --include=<spec>`; capturas con `guia-de-uso/fuente/anotar.mjs` en ego-browser · Modelo: Opus de punta a punta (como la pasada anterior de la guía) · Mirror Engram: `odd/guia-de-uso-al-dia/tasks` (ok)
Pronóstico: repo ~15 líneas; vault ~25 capturas pisadas y ~20 párrafos de texto, más el PDF rearmado.

## Specs

- S1 (pedido): «dale vamos con esos 3 puntos ahoora», sobre: «1. La guía de uso quedó desactualizada en algunos puntos. Por ejemplo, el «Ojo» de `05.md:156` dice que «Solo en oferta» no cambia lo que se exporta, y desde el PR #30 sí lo cambia.» — Aceptación: ningún texto de `guia-de-uso/fuente/secciones/*.md` contradice la app de `c183a7f0` + T1; las capturas que muestran algo que cambió se rehacen con el mismo nombre (lista en L2); el PDF se rearma con 0 cortes. — Checks: cada captura mirada con Read; el chequeo de cortes de `armar.mjs`.
- S2 (pedido): «3. **El residuo de Clientes:** a 1280 px quedan 25 px de Contacto detrás del scroll.» — Aceptación: a 1280×800, con el alta al costado, la tabla de Clientes entra entera (sin scroll de costado). — Checks: `clientes.spec.ts`; ego-browser.
- S3 (pedido): «2. **Bugs que dejamos fuera a propósito:** L6.3 […], L9.2 […], L10.3 […]». — Aceptación: L6.3 y L9.2 quedan como están por decisión ya tomada (G08; la spec pide la «X» del Simulador y G47), avisado a Luca. L10.3 se arregla recién con un PDF real de «Comprobante en línea» de ARCA.
- S4 (supuesto): no se suman capturas nuevas para lo que hoy no tiene captura (costo real en Controles de datos, «Precio manual: no cambia», «Costo real» en el Detalle de Importar, aviso de sesión cerrada, ficha del Vendedor): alcanza con el texto.

## Tasks

- [x] T1 · S2 · ruta: inline · Aceptación: la de S2 · Evidencia: 8 px: RED 1/92 → GREEN 92/92, ego 663 de 663 en El Tornillo (`8e4fd1f2`); reabierta por la tanda D (9 px de más en El Faro): 6 px, RED 1/92 → GREEN, más el `nowrap` de Proveedores solo en las celdas (RED 1/60 → GREEN; 152/152 los dos specs) (`b96adbb4`) · Risk: none
- [x] T2 · S1, S4 · ruta: delegada, tanda A (caps. 1 y 5, Anexo B; La Rambla y el super admin) · Aceptación: la de S1 para sus capturas y textos (L2) · Evidencia: 7/7 capturas pisadas, `r.faltan` vacío; textos en `00.md`, `04.md`, `16.md` (L3) · Risk: none
- [x] T3 · S1, S4 · ruta: delegada, tanda B (caps. 6 a 8; La Perla) · Aceptación: ídem · Evidencia: 6/6 capturas; textos en `05.md`, `06.md`, `07.md` (L3) · Risk: none
- [x] T4 · S1, S4 · ruta: delegada, tanda C (caps. 10 y 11; del Puerto) · Aceptación: ídem · Evidencia: 6/6 capturas; textos en `09.md`, `10.md` (L3) · Risk: none
- [x] T5 · S1, S4 · ruta: delegada, tanda D (caps. 12 a 15; El Faro) · Aceptación: ídem · Evidencia: 8/8 capturas; textos en `12.md`, `14.md` (L3) · Risk: none
- [x] T5b · S1, S2 · ruta: delegada · rehacer `13-01` y `13-01b` (6 px) y `B-02`, `B-03` (el alta del admin, abajo y a todo el ancho) · Evidencia: 4/4 capturas; Clientes en El Faro 663 de 663 a 1280×800 · Risk: none
- [x] T6 · S1 · ruta: inline · Anexo A (`15.md:47-48`), rearmar `guia-de-uso.md` y el PDF, chequeo de cortes · Evidencia: `guia-de-uso.md` rearmado (1725 líneas, encabezado en `c183a7f0`); `imprimir.sh` → 137 págs.; páginas del Anexo B revisadas (cada paso con su captura) · Risk: none
- [ ] T7 · S3 · bloqueada: espera el PDF real de ARCA de Luca

## Log

- L1 (2026-11-07) Pedido original, textual. Luca, ante «Lo pendiente es esto: 1. La guía de uso quedó desactualizada […] 2. Bugs que dejamos fuera a propósito […] 3. El residuo de Clientes […] Yo seguiría con el punto 1»:
  > dale vamos con esos 3 puntos ahoora
  - Corrección mía al empezar: L6.3 y L9.2 no estaban «fuera a propósito» por tiempo, sino decididos «queda como está» en `odd/tasks/bugs-de-la-guia.md` (fuera de alcance). Solo L10.3 es un bug y necesita el PDF.
- L2 (2026-11-07) Mapa (`odd-explorer`, Sonnet) de la guía contra los cambios 1–19 de los PR #29 y #30. Capturas viejas (25), por tanda:
  - A: `01-04b` (cotizaciones pegadas), `05-03` (CUIT partido), `B-01`, `B-04`, `B-05`, `B-10`, `B-12` (tabla del admin, alta abajo entre 901 y 1640 px).
  - B: `06-07` (ajuste sin monto), `06-10b` (motivo en el paginador), `07-02` (falta la fila vacía 2), `07-10` y `07-14` (alineación del masivo), `08-01` (el resultado no sale marcado).
  - C: `10-04` y `10-06` (Gastos y Retiros sin «−$»), `10-05c` y `10-06b` (Vendedor: «ingresos, gastos y retiros»), `11-05c` y `11-15` («-$» en el ticket y la factura).
  - D: `12-01a`, `12-02a`, `12-05` (hojas sin unidad), `13-01` y `13-01b` (orden de columnas de Clientes), `14-01` («Venta N.º» partido), `14-04` («-$» en la nota de crédito), `15-04` (fila de total gris y sticky).
  - Textos: `00.md` (aviso de sesión cerrada, opcional), `04.md:43`, `05.md:3,14,18,76,151,156,206`, `06.md:13,36,119`, `07.md:12`, `09.md:7,25,50` y un Ojo de la caja de otro día, `10.md:106`, `12.md:11,75,147`, `15.md:47,48`, `16.md:6,9,76`.
  - Próximo paso: build de `fix/clientes-1280` servido en 4201–4209 (`servir.mjs`) y las cuatro tandas en paralelo.
- L3 (2026-11-07) Las cuatro tandas (`odd-implementer`, Opus) terminaron: 27 capturas pisadas, cada una comparada con la vieja; `r.faltan` vacío en todas.
  - A: `05-03` se sacó con un CUIT válido, porque el error del CUIT no se partía (bug mío de `503c03d6`, arreglado en `b96adbb4`); el Ojo del texto sigue explicando el error. `B-04` necesitó crear «Ferretería Alfar» (`dueno@alfar.qa` / `Alfar-mostrador-31`, ACTIVA). La Rambla quedó ACTIVA. `03.md:43` (no `04.md:43`, como decía el mapa) lo corregí yo. `B-02` y `B-03`, fuera de la lista, también quedaron viejas: van a T5b.
  - B: `07-02` con el visor al 150 % para que la marca no tape el número de la fila vacía 2; `08-01` busca «Cable» para que se vea el primero marcado. PIN-003 se dio de baja y se reactivó.
  - C: en del Puerto, caja con ingreso, gasto y retiro, la venta N.º 27 (factura B simulada) y la N.º 28; la caja quedó cerrada.
  - D: Remito N.º 4 emitido y anulado, venta N.º 23 anulada con la nota de crédito N.º 24. Encontró 9 px de scroll en Clientes con los datos de El Faro: T1 reabierta y pasada a 6 px.
  - Rarezas reportadas, sin tocar: en el admin entre 901 y 1640 px, el aviso «Se creó …» queda arriba fuera de la vista y «Resetear contraseña del Dueño» abre el formulario abajo sin bajar hasta él (consecuencia de `503c03d6`); en el login, si la sesión se cerró y el reintento falla, se ven los dos avisos a la vez (de `6eb46bf2`); la lista de Ventas muestra la nota de crédito con «-$» (decidido en bugs-de-la-guia L3: un negativo de `formatearMonto` no cambia).
  - Anexo A (`15.md:47-48`) al día: Enter en Artículos para el Vendedor y Enter en Nueva compra.
- L4 (2026-11-07) Cierre.
  - Suite del front completa: 164 archivos, 4381/4381. `ng build` OK (solo el aviso de presupuesto de `dialogo-cobro.ts`, que ya estaba). El backend no cambió.
  - El chequeo de cortes de la pasada anterior era un script de un solo uso que no quedó guardado; las reglas siguen en `armar.mjs`. Se revisaron a ojo las páginas donde más cambió una captura (Anexo B, págs. 129–130): sin cortes.
  - 31 capturas pisadas en total (27 de las tandas y 4 de T5b), 0 nuevas (S4).
  - Risk: none (CSS de dos pantallas, con test; la guía es documentación fuera del repo).
  - Pendiente: T7 (L10.3) espera el PDF real de ARCA. Push, PR y merge de `fix/clientes-1280`: Luca.
