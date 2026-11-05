# cpu-tabla-de-pdf

Objetivo: que una lista en PDF armada a propósito no ocupe la CPU del servidor compartido · Por qué: medido el 2026-11-05, `TablaDePdf.armar` tarda 109 s con 500.000 palabras en columnas angostas (cuadrático en `unirHuecosSinVariedad`), y en las listas de precios corre fuera del turno de lectura de PDF. Pendiente «Medir la CPU de `TablaDePdf.armar` fuera del semáforo» de la revisión de seguridad del 2026-11-01 (vault `Projects/Ferreteria/pendientes-reviews-2026-11.md`).
Rama: `fix/cpu-tabla-de-pdf` desde `39a177c` · Entrega: single-pr · Runner: backend `cd backend && ./mvnw test -Dtest=<Clase>` / suite `./mvnw test` · Mirror Engram: `odd/cpu-tabla-de-pdf/tasks`
Pronóstico: ~120 líneas cambiadas · Ruteo: caso 5 (Opus de punta a punta: seguridad, alto riesgo item 2)

## Specs

Alcance: lectura de listas de precios en PDF (`TablaDePdf`, `LectorPdf`) · Fuera de alcance: la factura de proveedor en PDF (ya cerrada en `revision-seguridad-medios`, S1), el deploy (Railway está dado de baja desde el 2026-11-03).

Pedido (2026-11-05): «dale empeza con la seguridad que podes hacer vos, y luego vamos con el mantenimineto chico» (sobre «Medir la CPU de `TablaDePdf.armar`»).

Medición (test descartable, no commiteado), `TablaDePdf.armar` en `main` `39a177c`:

| Forma | 80.000 palabras | 160.000 | 500.000 (tope) |
|---|---|---|---|
| Lista normal, 20 palabras por línea | 27 ms | 51 ms | 169 ms |
| 4 líneas, columnas angostas con la misma palabra | 2,2 s | 10,3 s | 109 s |
| 4 líneas, columnas anchas | 7 ms | 25 ms | 43 ms (400, más de 200 columnas) |

- S1: el peor PDF que entra en el tope de palabras (500.000) se arma en pocos segundos — Aceptación: la forma «columnas angostas» con 500.000 palabras se arma en menos de 2 s; las listas que hoy se leen se arman igual (`TablaDePdfTest`, `LectorPdfTest`, los tests de importación de PDF en verde) — Checks: test con tope de tiempo; diferencial contra `main` con las formas medidas y las listas de prueba.
  - Supuesto: alcanza con sacar el recorrido de todas las palabras por cada par de columnas (indexar por columna los pares de palabras vecinas); el resultado de `unirHuecosSinVariedad` no cambia.
- S2: armar la tabla de una lista en PDF corre dentro del turno de lectura, como la factura — Aceptación: con un armado bloqueado, un segundo pedido recibe «Ya se está leyendo otra lista en PDF. Probá de nuevo en un momento.» — Checks: test en `LectorPdfTest`, como `interpretarLasPalabrasTambienOcupaElTurno`.

## Tasks

- [x] T1 · S1 · ruta: inline · Aceptación: RED (500.000 palabras en columnas angostas pasan el tope de 2 s) → GREEN; diferencial igual a `main` · Evidencia: RED `TablaDePdfTest` «execution timed out after 2000 ms» en `huecosAngostosConLaMismaPalabra…` y `huecosAngostosConPalabrasDistintas…` → GREEN 21/21 (se sumó `muchasColumnasDebiles…` por el cuadrático de `unirDebiles`, ver L4). Medición con el código nuevo: la forma de 109 s tarda 85 ms; la lista normal de 500.000 palabras, 145 ms. Diferencial (clase temporal con el `TablaDePdf` de `main`, no commiteada): 30.003 casos iguales (30.000 al azar + las 3 formas medidas a 20.000 palabras); dos mutaciones (empate de vecinas en `unirDebiles`, variedad en `variasPalabrasAntesDe`) las detecta en la semilla 511 y en la 0 · Commit `6a66409` · Risk: item 2 (CPU del servidor compartido)
- [x] T2 · S2 · ruta: inline · Aceptación: RED (el armado no ocupa el turno) → GREEN · Evidencia: RED de compilación (constructor `LectorPdf(Topes, armar)`) y después de comportamiento («Expecting code to raise a throwable»: el segundo pedido entraba mientras se armaba) → GREEN `LectorPdfTest` 23/23, `TablaDePdfTest` 21/21, `LectorDeFacturaPdfTest` 2/2. `TurnoDeLectura.conTurno` suelta el turno en un `finally`, así que un 400 al armar no lo deja tomado · Commit `037a8fb` · Risk: item 2
- [x] T3 · S1 S2 · ruta: inline · Aceptación: suite backend; punta a punta con la app levantada (subir el PDF armado en `main` y en la rama) · Evidencia: suite backend 6259/6259 (12 min 49 s) sobre `037a8fb`. Punta a punta: ver L5 · Risk: —
- [ ] T4 · S1 S2 · ruta: `odd-verifier` (opus) · Aceptación: pass sin blockers · Evidencia: — · Risk: —

## Log

- L1 (2026-11-05) Pedido de Luca: «dale empeza con la seguridad que podes hacer vos, y luego vamos con el mantenimineto chico».
- L2 (2026-11-05) `gitleaks git --log-opts=--all` (8.30.1) sobre 718 commits: 3 hallazgos, los tres datos de prueba falsos (`SinSecretosEnLogsTest` 40 y 42, token y sign inventados; `CredencialArcaIntegrationTest` 323, un encabezado de clave privada cortado e inválido). Sin secretos reales.
- L3 (2026-11-05) Análisis: `unirHuecosSinVariedad` llama a `palabrasDistintasAntesDe` (que recorre todas las palabras) por cada par de columnas candidatas, antes de `controlarTamano`; con 4 líneas y 125.000 palabras por línea hay ~125.000 candidatas. `LectorPdf.leer` arma la tabla después de soltar el turno (`TablaDePdf.armar(palabras(contenido))`).
- L4 (2026-11-05) Al leer el código apareció otro cuadrático, el de `unirDebiles`: volvía a empezar desde la primera columna y hacía `remove` en cada unión, con hasta ~250.000 columnas débiles (100 líneas, cada columna con texto en 2). Se resolvió con una sola pasada y vecinas enlazadas. Es equivalente porque las columnas anteriores a la primera débil no son débiles, y una unión solo les sube las líneas (`max`). `unirHuecosSinVariedad` queda en una pasada porque la columna derecha de cada hueco siempre es una original: las palabras que la preceden se indexan una vez, con búsqueda binaria.
- L5 (2026-11-05) Punta a punta con la app levantada (perfil dev, MySQL de `docker compose`): PDF real armado con PDFBox 3.0.7 (una hoja de 14.400 pt, 60 líneas × 7.000 palabras «a», Helvetica de 2 pt, paso de 1,912 pt y un hueco ancho en el medio; 1,8 MB, 420.000 palabras). `dueno@tornillo.demo` y, 1 s después, `dueno@tuerca.demo` lo suben a `POST /api/importaciones/muestra`:
  - `main` `39a177c`: 200 en 18,9 s y 200 en 19,0 s. Los dos armados corren a la vez en la JVM del servidor.
  - rama `037a8fb`: 200 en 1,25 s y 200 en 1,31 s (el segundo espera el turno). La misma muestra: hoja «PDF», 2 columnas, celdas de «a».
  - No se comparó byte a byte la respuesta de `main` con la de la rama; la equivalencia la da el diferencial de T1.
