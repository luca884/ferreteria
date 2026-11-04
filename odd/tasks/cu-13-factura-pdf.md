# cu-13-factura-pdf

Objetivo: que el Dueño suba la factura del proveedor en PDF y la compra (CU-13) quede completa para revisar y confirmar · Por qué: pedido del relevamiento de ferreteros locales, bloque 3 ("Carga de facturas de proveedor por PDF").
Rama: `feat/cu-13-factura-pdf` desde `d22c9e2` · Entrega: single-pr · Ruteo: caso 5 (Opus de punta a punta: diseño incierto y lectura de archivos de afuera) · Runner: backend `./mvnw test -Dtest=<Clase>` / `./mvnw test`; frontend `npx ng test --watch=false [--include=<spec>]`, `npx ng build` · Mirror Engram: `odd/cu-13-factura-pdf/tasks`
Pronóstico: ~1500 líneas cambiadas (agregadas + borradas, sin generados), provisorio hasta cerrar el diseño · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: leer una factura de proveedor en PDF con texto y completar con ella el formulario de compra de CU-13, que después pasa por la vista previa y la confirmación de siempre. · Fuera de alcance: factura por foto o escáner (OCR; el relevamiento lo deja para el futuro), pago de la compra, IVA y libro IVA Compras, alta rápida de artículos.
Restricciones: reglas de arquitectura de `CLAUDE.md` (tenant solo del JWT, `*Scoped`, 404 entre ferreterías, test de aislamiento por endpoint, plata en `BigDecimal`). El PDF se lee con el proceso aparte de CU-04 (`LectorPdf` → `ExtractorPdf`: 256 MB, 30 s, tope de palabras, un PDF por vez). Las facturas reales no se versionan (`Facturas/` en `.gitignore`, con el patrón `[Ff]acturas/`); los tests usan facturas armadas con datos inventados.

- S1: relevamiento (Engram `producto/relevamiento-ferreteros-2026-11`): "Carga de facturas de proveedor por PDF." — Aceptación: por definir con las decisiones de L3.
- S2: Luca (2026-11-04): "dale, arrancá con la feature y agregá facturas al gitignore, y proba la reconstruccion tambien de este pdf y vemos si realmente capta toda la informaicon que da el archivo" — Aceptación: `Facturas/` ignorada por git; la reconstrucción probada sobre la factura real, con lo que capta y lo que no. — Checks: `git check-ignore Facturas/`; corrida de `ExtractorPdf` y `TablaDePdf` sobre la factura (L2).
- S3 (propuesta, a confirmar): el PDF completa el encabezado (tipo, punto de venta, número, fecha y el proveedor por su CUIT) y los renglones (el artículo por el código del proveedor, la cantidad y el costo unitario sin IVA). El Dueño revisa, resuelve los renglones sin artículo y sigue con la vista previa de CU-13.

## Tasks

- [x] T1 · S2 · ruta: inline · Aceptación: `Facturas/` en `.gitignore` · Evidencia: `git check-ignore -v Facturas/ facturas/` → `.gitignore:25:[Ff]acturas/`; `git status` ya no la lista · Risk: none
- [x] T2 · S2 · ruta: inline · Aceptación: reconstrucción probada sobre la factura real · Evidencia: L2 · Risk: none
- [ ] T3 · S1, S3 · ruta: inline · Provisoria: lector de renglones de factura (dominio puro, test-first con factura inventada): por línea, código, cantidad, unitario y neto con cantidad × unitario = neto, más el control contra «Transporte» y el subtotal · Evidencia: — · Risk: —
- [ ] T4 · S1, S3 · ruta: inline · Provisoria: encabezado (letra, punto de venta, número, fecha, CUIT del proveedor) · Evidencia: — · Risk: —
- [ ] T5 · S1, S3 · ruta: inline · Provisoria: endpoint que recibe el PDF y devuelve el borrador de la compra (solo Dueño, aislamiento entre ferreterías) · Evidencia: — · Risk: —
- [ ] T6 · S1, S3 · ruta: inline · Provisoria: pantalla de Compras: subir el PDF, formulario completo, renglones sin artículo marcados · Evidencia: — · Risk: —
- [ ] T7 · todas · ruta: inline · Cierre: suite backend y frontend, `ng build`, prueba en la app real con ego-browser, spec actualizada · Evidencia: — · Risk: —

## Log

- L1 (2026-11-04) Pedido original, textual:
  > pienso que eso lo tendriamos que adaptar bien igualment, me gustaria saber ocmo es que funciona  todo el "escaneo" en archivo pdf si lo descomprimer y toma literalmente todo el texto que hay o como funciona exactamente, ahi cree una carpeta en la direccion del proeycto llamada facturas, ahi vas a entonctrar una factura que consegui  para este mismo ejemplo una comra a un proveedor

  > dale, arrancá con la feature y agregá facturas al gitignore, y proba la reconstruccion tambien de este pdf y vemos si realmente capta toda la informaicon que da el archivo
- L2 (2026-11-04) Prueba con la factura real (Factura A de un proveedor, 3 páginas, 53 renglones; fuera del repo):
  - `ExtractorPdf` saca 1.711 palabras sin error: todo el texto está (renglones, encabezado, totales, CAE). Las imágenes (logo y QR) no se leen.
  - `TablaDePdf.armar` (el armado de CU-04) da 178 filas y **todas de una sola celda**: no encuentra ninguna columna. En una lista de precios la tabla es casi todo el documento; en la factura los renglones son 53 de 178 líneas, y el encabezado y el pie que se repiten en cada página (líneas como «Iva : Resp. Monotributo … REALIZO: 0001 …») tapan los huecos verticales. El mapeo de columnas de CU-04 no sirve tal cual.
  - Texto superpuesto en el PDF: «FACTURA» y «FECHA:» se dibujan en el mismo lugar y salen mezclados («URA 0013 - 00605684», «FFEACCHAT:»); lo mismo con «CONDICIONES DE VENTA» e «Ing. Brutos», y «VENCIMIENTO DE ESTA FACTURA» con la leyenda de intereses. El número «0013 - 00605684», la fecha «24/09/2026» y «C.U.I.T.: 30-65716216-3» salen limpios; la letra «A» queda pegada a otra línea.
  - Los renglones salen limpios, uno por línea: `código cantidad descripción despacho fecha lugar unitario neto [*]`. Leídos por línea: 53 de 53, cantidad × unitario = neto en todos, la suma de cada página da el «Transporte» impreso, y la suma total (1.707.999,65) + «EMBALAJE Y CARGA : 4.44%» (75.835,18) da el subtotal de la factura (1.783.834,83). El gravado al 21 % (1.547.863,27) = netos sin `*` + embalaje; el `*` marca IVA 10,5 %.
  - Números en formato US en toda la factura (`235,971.56`); los totales se ven en formato AR en la imagen pero el texto los trae en US. Cantidades con decimal (`1.0`, `12.0`). Códigos como `9960110.5`, `MKT100020`, `R2780219`.
  - Conclusión: la información está completa y se puede autocontrolar con la aritmética de la propia factura; hace falta un lector de factura propio (por línea), no el de columnas de CU-04.
- L3 (2026-11-04) Decisiones abiertas: D1 qué hacer con cargos sobre el total como «EMBALAJE Y CARGA 4.44%» (prorratear en el costo de cada renglón o no); D2 renglones cuyo código no tiene artículo; D3 diseñar con más facturas de otros proveedores. Hallazgo aparte (no es de esta feature): la factura dice que el comprador es monotributista y CU-13 carga el costo sin IVA. · Próximo paso: D1 con Luca.
