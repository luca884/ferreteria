# cu-03-etiquetas

Objetivo: el Dueño imprime etiquetas con código de barras de los artículos que elige, en hoja A4 de 4 o 5 columnas, como las plantillas de GDS (G85, GDS-31) · Por qué: sin código en el artículo el lector del mostrador no sirve.
Rama: `feat/cu-03-etiquetas` desde `57b9c4f` · Entrega: single-pr · Runner: `npx ng test --watch=false --include=<spec>` (frontend) · Mirror Engram: `odd/cu-03-etiquetas/tasks` (ok) · Modelo: caso 3, Opus.
Pronóstico: ~750 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: pantalla `/etiquetas` (solo Dueño) con búsqueda, lista de artículos con copias, elección de 4 o 5 columnas e impresión; codificadores EAN-13 y Code128 en SVG; enlace en el menú; docs. · Fuera de alcance: Code39, los tres tamaños, la etiqueta de envío, precio en la etiqueta, guardar la lista.
Restricciones: sin cambios de backend ni de base; sin dependencias nuevas; impresión con `window.print()` y las reglas `@media print` de `styles.css`.

- S1: «dale» (Luca, 2026-10-23) a la propuesta:
  - «**Dónde:** pantalla nueva «Etiquetas» para el Dueño. Busca artículos por código o descripción, los suma a una lista con cantidad de copias (1 por defecto) e imprime. La lista vive solo en la pantalla y no se guarda, como la tabla en memoria de GDS.»
  - «**Hoja:** A4 en 4 o 5 columnas, que eligís antes de imprimir. Se imprime desde el navegador (`window.print()`), sin PDF ni hardware especial.»
  - «**Contenido de cada etiqueta:** código de barras, el código en texto y la descripción. Sin precio, como las plantillas de GDS.»
  - «**Tipo de código:** si el código de barras tiene 13 dígitos válidos, va como EAN-13; si no, como Code128; si el artículo no tiene código de barras, va el código interno en Code128, que el mostrador ya encuentra al escanearlo.»
  - «**Afuera:** Code39, los tres tamaños y la etiqueta de envío. EAN-13 y Code128 alcanzan para los lectores comunes.»
  - «**Sin cambios en el backend ni en la base.** Reuso la búsqueda que ya existe y armo los códigos de barras en el front, sin librerías nuevas: hoy el front solo depende de Angular. Los codificadores llevan tests con códigos conocidos.»
  - Aceptación: los codificadores dan los patrones de barras de códigos conocidos (EAN-13 con dígito verificador válido; Code128 con su checksum); la pantalla suma artículos de la búsqueda, cambia copias, quita, elige columnas y la hoja tiene una etiqueta por copia con barras, código y descripción; el Vendedor no ve el enlace ni la pantalla. — Checks: `npx ng test --watch=false --include=<spec>`, `npx ng build`, ego-browser.
- S2 (supuesto): «Etiquetas» va en el menú del Dueño después de «Stock bajo mínimo»; un Vendedor que entra por la URL ve un aviso y la pantalla no pide nada al servidor, como las otras pantallas solo del Dueño.
- S3 (supuesto): los artículos dados de baja no se pueden sumar a la lista (no tiene sentido etiquetar lo que no se vende), igual que en el ticket del mostrador.

## Tasks

- [ ] T1 · S1 · ruta: inline · Aceptación: `codigo-barras.ts` arma EAN-13 (si son 13 dígitos con verificador válido) o Code128 (B, o C para pares de dígitos) en módulos de barras, con tests de vectores conocidos · Evidencia: — · Risk: item 6 (un patrón mal armado no lo detecta nada más que el lector; los vectores conocidos lo cubren)
- [ ] T2 · S1, S2, S3 · ruta: inline · Aceptación: pantalla `/etiquetas`, ruta y enlace del menú; hoja imprimible con SVG · Evidencia: — · Risk: none
- [ ] T3 · S1 · ruta: inline · Aceptación: `docs/spec.md` (CU-03) y `docs/relevamiento-gds.md` (G85, GDS-31) dicen qué quedó · Evidencia: — · Risk: none

## Log

- L1 (2026-10-23) Pedido original, textual:
  > dale

  (a «¿Seguimos con etiquetas?»). Propuesta aprobada, textual: «dale» (ver S1).
- L2 (2026-10-23) Exploración:
  - GDS: `docs/relevamiento-gds.md:152` (G85), `:262`, `:377` (usar código interno si no hay código de barras), `:544` (GDS-31).
  - Búsqueda reusable: `MostradorService.buscar(q)` (`features/mostrador/mostrador.service.ts:12`), devuelve `ArticuloMostrador` con `codigoInterno`, `codigoBarras`, `descripcion`, `activo`.
  - Patrón de pantalla imprimible solo Dueño: `features/stock-bajo-minimo/` y `@media print` en `styles.css:850`. Menú en `layout/shell.html:61-87`, con su orden fijado en `layout/shell.spec.ts:170`.
