# cu-03-costo-actualizado

Objetivo: el listado de Artículos y la búsqueda del mostrador muestran desde cuándo está al día el costo, como «F. Modif.» de GDS (G22, GDS-05) · Por qué: el Dueño quiere saber qué precios están viejos.
Rama: `feat/cu-03-costo-actualizado` desde `a67d6db` · Entrega: single-pr · Runner: `./mvnw test -Dtest=<Clase>` (backend), `npx ng test --watch=false --include=<spec>` (frontend) · Mirror Engram: `odd/cu-03-costo-actualizado/tasks` (ok) · Modelo: caso 3, Opus.
Pronóstico: ~450 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: `costoAl` en las respuestas de artículo (Dueño y Vendedor) y del mostrador; columna en Artículos; dato en los resultados del mostrador; docs. · Fuera de alcance: filtros u orden por fecha, la exportación CSV y la ficha.
Restricciones: sin migración; tenant solo del JWT; todo cruce con otra tabla une por `ferreteria_id`.

- S1: «dale, vamos con gds-05» y «dale» (Luca, 2026-10-23) a la propuesta:
  - «**Qué fecha:** la más nueva entre el último cambio de costo y la última importación de lista que tocó el artículo, aunque haya confirmado el mismo costo. Es la que responde «¿este precio está viejo?». Si contamos solo los cambios, un artículo que el proveedor confirmó ayer aparece como viejo.»
  - «**Dónde:** columna «Costo al» (dd/mm/aaaa) en Artículos; en los resultados de búsqueda del mostrador, visible también para el Vendedor, como en GDS: es una fecha, no muestra el costo; sin fecha (artículo cargado a mano y nunca cambiado), la fecha de alta.»
  - «Sin migración: sale del historial y de las importaciones.»
  - Aceptación: `GET /api/articulos`, `GET /api/articulos/{id}` y `GET /api/articulos/mostrador` traen `costoAl` (fecha). La fecha es la mayor entre el último historial con cambio de costo o de moneda, la fecha de la última importación del artículo y la fecha de alta. Un historial que solo cambió el precio (cotización, margen, masivo de precio) no la mueve. Historial e importación de otra ferretería no cuentan. — Checks: `./mvnw test -Dtest=CostoAlIntegrationTest`
- S2 (supuesto): la columna «Costo al» se ve para el Dueño y para el Vendedor en Artículos, porque es una fecha y no muestra el costo, igual que en el mostrador.
- S3 (supuesto): las respuestas de guardar (alta, edición, ajustes, reactivación) también traen `costoAl`, para que ninguna respuesta de artículo lo tenga vacío.

## Tasks

- [ ] T1 · S1, S3 · ruta: inline · Aceptación: `costoAl` en las tres respuestas, con tests de cada fuente de fecha y de aislamiento · Evidencia: — · Risk: item 3 (respuestas de API que consume el front; campo nuevo, aditivo)
- [ ] T2 · S1, S2 · ruta: inline · Aceptación: columna «Costo al» en Artículos y «Costo al dd/mm/aaaa» en los resultados del mostrador · Evidencia: — · Risk: none
- [ ] T3 · S1 · ruta: inline · Aceptación: `docs/spec.md` (CU-03 y CU-06) y `docs/relevamiento-gds.md` (G22, GDS-05) dicen qué quedó · Evidencia: — · Risk: none

## Log

- L1 (2026-10-23) Pedido original, textual:
  > dale, vamos con gds-05

  Propuesta aprobada, textual: «dale» (ver S1).
- L2 (2026-10-23) Exploración:
  - `historial_precio` guarda `costo_anterior`, `costo_nuevo`, `moneda` y `moneda_anterior`, con índice `(ferreteria_id, articulo_id, fecha)` (`V2__articulos_y_precios.sql:66`).
  - Una importación que no cambia el costo no deja historial; solo marca `articulo.ultima_importacion_id` (`ImportacionService.java:199-204`). Su fecha es `importacion_lista.fecha`.
  - `articulo.creado_en` es la fecha de alta.
  - Las respuestas se arman en `ArticuloService` (7 lugares), `ArticuloVendedorResponse.from` y `BusquedaMostradorService.java:42,62`.
