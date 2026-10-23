# cu-03-exportar-articulos

Objetivo: el Dueño baja a CSV los artículos que muestra la pantalla Artículos, como la «Exportación Rápida» de GDS (G25, GDS-19) · Por qué: las ferreterías migran y revisan precios por planilla.
Rama: `feat/cu-03-exportar-articulos` desde `b70b536` · Entrega: single-pr · Runner: `./mvnw test -Dtest=<Clase>` (backend), `npx ng test --watch=false --include=<spec>` (frontend) · Mirror Engram: `odd/cu-03-exportar-articulos/tasks` (ok) · Modelo: caso 3, Opus.
Pronóstico: ~650 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: endpoint de exportación, botón «Exportar» en Artículos, docs. · Fuera de alcance: la carga inicial (ya la cubre la importación de CU-04 con «Crear los artículos nuevos»), Excel `.xlsx`, filtros nuevos.
Restricciones: tenant solo del JWT; un endpoint nuevo lleva su test de aislamiento; plata en `BigDecimal`.

- S1: «exportar articulos» (Luca, 2026-10-23), con la propuesta que aprobó («dale me parece bien»):
  - «**Quién:** solo el Dueño, porque el archivo lleva costos y márgenes.»
  - «**Qué artículos:** los que muestra la pantalla Artículos (búsqueda y «incluir inactivos»), todos juntos, sin páginas.»
  - «**Formato:** CSV que abre bien en Excel argentino: separador `;`, coma decimal y UTF-8 con BOM.»
  - «**Columnas:** códigos: interno, de barras, del proveedor · datos: descripción, rubro, proveedor, unidad · precio: moneda, costo, IVA, margen, precio final, precio manual (Sí/No) · stock, stock mínimo, ubicación, activo (Sí/No)»
  - Aceptación: `GET /api/articulos/exportacion?q=&incluirInactivos=` da `text/csv` con BOM, encabezado y una fila por artículo del filtro, en el orden de la pantalla (descripción, id); el Vendedor recibe 403; otra ferretería no ve los artículos ajenos; la pantalla baja el archivo con el filtro aplicado. — Checks: `./mvnw test -Dtest=ExportacionArticulosIntegrationTest`, `npx ng test --watch=false --include=src/app/features/articulos/articulos.spec.ts`
- S2 (supuesto): el margen sale como lo muestra la pantalla: el porcentaje propio, o «Heredado» si el artículo hereda el del rubro o el por defecto.
- S3 (supuesto): sin separador de miles; los importes con los decimales guardados (costo y precio con 2, stock con los suyos, sin ceros de más); fin de línea CRLF; los campos con `;`, comillas o saltos de línea van entre comillas dobles, con las comillas internas duplicadas.
- S4 (supuesto, seguridad): un texto que empieza con `=`, `+`, `-`, `@`, tab o retorno sale con un apóstrofo adelante, para que Excel no lo ejecute como fórmula (inyección CSV).
- S5 (supuesto): el archivo se llama `articulos-AAAA-MM-DD.csv`, con la fecha de Buenos Aires.
- S6: propuesta del advisory 1, aprobada por Luca («dale», 2026-10-23): «escribir los tres códigos como `="0012"`: Excel los muestra tal cual y, si lo guardás, sigue saliendo `0012`. Eso obliga a que la importación de CU-04 también entienda `="..."`». — Aceptación: el código interno, el de barras y el del proveedor salen como `="…"` (vacío si no hay), y como esos códigos ya no se ejecutan, no llevan el apóstrofo de S4. La lectura de CSV de CU-04 convierte una celda `="…"` en su texto. — Checks: `./mvnw test -Dtest=EscritorCsvTest,ExportacionArticulosIntegrationTest,LectorCsvTest`

## Tasks

- [x] T1 · S1-S5 · ruta: inline · Aceptación: endpoint con tests de formato, filtro, orden, rol y aislamiento · Evidencia: RED `EscritorCsvTest` 5 errores (sin implementar) y `ExportacionArticulosIntegrationTest` 400 (ruta inexistente) → GREEN 5/5 y 5/5; ArchUnit 64/0; commit `e617be3` · Risk: item 2 (inyección CSV)
- [x] T2 · S1, S5 · ruta: inline · Aceptación: botón «Exportar» del Dueño en la barra de Artículos baja el archivo con el filtro; el Vendedor no lo ve · Evidencia: RED compilación (sin `exportar` ni `descarga.ts`) → GREEN 86/86 en 3 specs; CORS expone Content-Disposition (RED null → GREEN 6/6); ego-browser: `?q=tornillo` baja `articulos-2026-10-23.csv` con BOM y 18 filas, igual que la pantalla; commit `e33a000` · Risk: item 3 (CORS compartido, cambio aditivo)
- [ ] T4 · S6 · ruta: inline · Aceptación: los códigos salen como `="…"` y la importación CSV los lee como texto · Evidencia: — · Risk: item 3 (`LectorCsv` lo comparte toda la importación de CU-04)
- [x] T3 · S1 · ruta: inline · Aceptación: `docs/spec.md` y `docs/relevamiento-gds.md` dicen qué quedó · Evidencia: CU-03 punto 4, G25 y GDS-19 marcados · Risk: none

## Log

- L1 (2026-10-23) Pedido original, textual:
  > exportar articulos

  Propuesta aprobada, textual: «dale me parece bien» (ver S1).
- L2 (2026-10-23) Exploración: el filtro de la pantalla es `FILTRO_ARTICULOS` (`ArticuloRepository.java:34`), y el listado ordena por descripción e id (`ArticuloService.java:85`). `StockBajoMinimoController.java:23` es el molde de rol (`hasRole('DUENO')`). No hay ningún escritor CSV en el backend ni descargas de archivos en el frontend. El relevamiento no ejecutó la exportación de GDS (`docs/relevamiento-gds.md:92`), así que las columnas son nuestra propuesta.
- L3 (2026-10-23) Verificación:
  - Checks: backend `./mvnw test` 4486/0, BUILD SUCCESS. Frontend 117 archivos en verde. `ng build` OK, con el warning de presupuesto de `dialogo-cobro.ts`, que ya estaba.
  - `odd-verifier` (opus): sin blockers; S1 a S5 pasan. Sus sondas quedaron como tests: apóstrofo en todas las columnas de texto (con el DDE), números en los bordes (`1000`, `12,5`, `-5`, `-0,5`), 401 sin token y `/{id}` que sigue andando. `ExportacionArticulosIntegrationTest` 9/0.
  - Advisory 3 corregido: Safari podía cortar la descarga, así que la dirección temporal ahora se libera después del clic.
  - Advisory 2 (un espacio o un `＝` de ancho completo delante de la fórmula): Excel no lo evalúa; queda así.
  - Abierto, advisory 1: Excel convierte los códigos a número (`0012` queda `12`, `7790001234567` queda `7,79E+12`). Es una decisión de producto para Luca.
