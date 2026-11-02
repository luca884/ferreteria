# tope-columnas-listas

Objetivo: una lista CSV, Excel o PDF con columnas de más se rechaza antes de agotar la memoria del servidor compartido · Por qué: hallazgo 4 (MEDIA) de la revisión de seguridad del 01/11: un CSV de 25 MB con `a;a;a;…` en una línea, o un `.xlsx` con una celda en la columna XFD por fila, arman millones de celdas, dan `OutOfMemoryError` y fallan los pedidos de todas las ferreterías.
Rama: `fix/cu-04-tope-columnas` desde `30444b4` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / `./mvnw test`; imagen `./mvnw test -Pimagen` · Mirror Engram: `odd/tope-columnas-listas/tasks` (ok)
Ruteo: caso 5 (alto riesgo: item 4 concurrencia, item 5 imagen) · Pronóstico: ~450 líneas · Estado: planificado, sin empezar

## Specs

Alcance: lectores de lista (`catalogo/service/lectura/`): `LectorCsv`, `LectorExcel`, `TablaDePdf`; turno global para CSV y Excel; `backend/Dockerfile`.
Fuera de alcance: lectura de Excel en streaming (API de eventos de POI) y subir el tope de 20.000 filas (mejora pendiente, cuando una piloto traiga una lista que lo necesite); la JVM hija del PDF (ya aislada: 256 MB, un PDF por vez, 500.000 palabras, 30 s).
Restricciones: una lista real (muchas filas y pocas columnas) se lee igual que hoy; topes de filas sin cambio (`MAXIMO_FILAS_DE_DATOS` 20.000, `MAXIMO_FILAS_LEIDAS` 25.000).

Propuesta aprobada por Luca («okey me parece bien»):

- S1: "Tope durante la lectura y no después: las columnas vacías del final se ignoran; si hay datos más allá de la columna 200, la lista se rechaza con «La lista tiene datos en más de 200 columnas. Dejá solo las columnas que usás.»" — Aceptación: CSV, Excel y PDF con un dato en la columna 201 → 400 con ese texto; una celda vacía (con formato) en la columna XFD no rechaza el Excel.
- S2: "Tope de celdas totales: más de un millón de celdas con datos (20.000 filas × 50 columnas) se rechaza con «La lista es demasiado grande para leerla. Dividila en archivos más chicos.»" (el `MENSAJE_GRANDE` existente de `LimitesDeLista`).
- S3: "Una lista CSV o Excel a la vez en todo el sistema, como el PDF; si hay otra leyéndose espera hasta 30 s y si no le llega el turno: «Ya se está leyendo otra lista. Probá de nuevo en un momento.»" — Aceptación: mismo modelo que `LectorPdf.conTurno` (semáforo justo, una espera por ferretería).
- S4: "`-XX:+ExitOnOutOfMemoryError`: si igual se quedara sin memoria, la JVM se cierra y Railway la reinicia en vez de quedar degradada."
- S5 (decisión de diseño acordada en la conversación):
  - **CSV:** escaneo previo del texto, carácter por carácter y sin crear objetos, que cuenta separadores por línea respetando las comillas, ANTES de `CSVFormat.parse`: `commons-csv` 1.14.1 arma el registro entero antes de devolverlo y solo tiene `setMaxRows` (verificado con `javap`), así que contar en `celdas()` llega tarde.
  - **Excel:** `row.cellIterator()` (solo las celdas que existen) en vez de `0..getLastCellNum()` con `Celda.VACIA` de relleno; contar y cortar mientras se lee.
  - **PDF:** `TablaDePdf` ya calcula líneas y columnas antes de `repartir`: chequear ahí (columnas > 200 o líneas × columnas > 1.000.000) antes de armar celdas.

## Tasks

- [ ] T1 · S1 S2 S5 · CSV: escaneo previo en `LectorCsv` (`LectorCsv.java:36-58`) · RED con el CSV de una línea `a;a;…` (y heap acotado si hace falta) · Risk: item 2
- [ ] T2 · S1 S2 S5 · Excel: `LectorExcel.java:110-129` con `cellIterator` y conteo · RED con `.xlsx` de celda en XFD (dato → 400; vacía con formato → se lee) · Risk: item 2
- [ ] T3 · S1 S2 S5 · PDF: chequeo en `TablaDePdf.java:62-83` antes de `repartir` (`:232`) · Risk: item 2
- [ ] T4 · S3 · turno global CSV/Excel (modelo `LectorPdf.java:65,95-110`), test de concurrencia · Risk: item 4
- [ ] T5 · S4 · `backend/Dockerfile`: `-XX:+ExitOnOutOfMemoryError` + `ImagenDeProduccionTest#laJvmDevuelveLaMemoriaQueNoUsa` (o test propio) · Risk: item 5
- [ ] T6 · todas · suites backend y `-Pimagen`, ego-browser (importar una lista real y una patológica), `odd-verifier` · Risk: item 2

## Log

- L1 (2026-11-02) Pedido, textual:
  > vammos de las imoprtante al menos

  > oeky pero eso suele pasar en las ferreterias que la lista de productos son muy grands y tienen muchas filas, como lo podemos optimizar

  > pero recordemos que no solo es con excel que se pueden subir las listas o las facturas, tambien pdf  o csv

  > okey me parece bien, por ultimo explicame que es lo que vas a modificar o cual es el cambio principal sugerido, no se puede primero detectar cuantas filas y columnas tiene primero y luego "escanear" "revisar" el archivo?? pregunto, no digo que se tenga q eu hacer asi

  > okey py pra pdf?

  > dale deja todo planeado para la proxima sesion, cerramos sesion
- L2 (2026-11-02) Exploración: `LimitesDeLista.java:35` (`MAXIMO_COLUMNAS_DE_MUESTRA` = 200) recorta solo la respuesta (`ImportacionService.java:420`); `LectorExcel.java:81` abre el libro entero con `WorkbookFactory` y `:111` recorre hasta `getLastCellNum()`; `LectorCsv.java:44-66` sin tope de columnas; `TablaDePdf.java:62` tiene tope de filas pero no de columnas (no reproducido; el riesgo es teórico con un PDF armado a propósito). El único endpoint que recibe archivos es `ImportacionController` (no se suben facturas).
- L3 (2026-11-02) Corrección en la conversación: primero se dijo que en CSV alcanzaba contar mientras se lee; verificado que `commons-csv` arma la fila entera antes, la idea de Luca (escanear primero) es la correcta para CSV.
- L4 (2026-11-02) Próximo paso: arrancar por T1 (CSV), test-first.
