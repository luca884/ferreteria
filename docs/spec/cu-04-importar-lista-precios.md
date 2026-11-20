# CU-04 · Importar lista de precios del proveedor

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · caso de uso extracto de `spec.md:82-92` en la partición del 2026-11-10.

<!-- spec payload: spec.md:82-92 -->
**CU-04 Importar lista de precios del proveedor** (Dueño) — el caso más importante del sistema

1. Elige el proveedor y sube el archivo. Los proveedores mandan las listas en **Excel y/o PDF**: se aceptan Excel (.xlsx, .xls), CSV y PDF con texto; el PDF se extrae a tabla y pasa por la misma vista previa (ver nota abajo).
2. Mapea columnas una sola vez (código, descripción, costo); el mapeo queda guardado para ese proveedor.
3. El sistema cruza por código del proveedor y muestra una vista previa: cuántos suben, cuántos bajan, cuántos son nuevos, cuántos no están en la lista.
4. El dueño confirma y se actualizan los costos y precios en una sola transacción.
   - Alternativo: artículos nuevos → se pueden dar de alta en bloque con el margen por defecto del rubro.

> **Listas en PDF (2026-10-11, hecho el 2026-10-26):** varias listas llegan solo en PDF. Un PDF con texto se lee como **una sola hoja** («PDF») con todas las páginas seguidas: cada línea es una fila y las columnas salen de los huecos verticales que comparten las líneas de la tabla, así que el mapeo, la muestra y la vista previa son los mismos que con un Excel. Las líneas sueltas (títulos, rubros, encabezados y pies de página, notas) van enteras a la primera columna y la vista previa las saltea como títulos; el encabezado repetido en cada página también se saltea. Un importe con «US$» o «U$S» adelante se lee como el número (la moneda sale de la columna de moneda o de la moneda de todas las filas); si la lista no dice la moneda, esa fila no se importa y la vista previa avisa «El costo está en dólares, pero la lista no dice la moneda. Mapeá la columna de moneda o elegí la moneda de todas las filas.» (no se adivina la moneda, como GDS). Probado con las dos listas reales: LISTA 336 (152 páginas, 7 columnas, sus 4.139 artículos) y M-S (27 páginas, 1.167 artículos). Un PDF **escaneado** no tiene texto y da «El PDF no tiene texto para leer. Pedile al proveedor la lista en Excel o en un PDF que no sea escaneado.» (no hay OCR); uno con contraseña o dañado da el mismo aviso que un Excel. Como un PDF armado a propósito puede agotar la memoria o la CPU de quien lo lee, el PDF se lee **en un proceso aparte** con su propia memoria (256 MB), 30 segundos y un tope de palabras: pasarlos da «La lista es demasiado grande para leerla. Dividila en archivos más chicos.» y el servidor no se entera (ver Despliegue: lectura de PDF). Se lee un PDF por vez; un pedido que no consigue turno en 30 segundos da «Ya se está leyendo otra lista en PDF. Probá de nuevo en un momento.». Cada ferretería espera un solo PDF: si sube otro mientras se lee o espera el primero, recibe ese aviso al instante. Quedan afuera las páginas rotadas o a dos columnas.
<!-- fin payload: spec.md:82-92 -->

**Agregado móvil: importar lista** (≤639.98 px, solo Dueño).
La barra de pasos queda arriba y la acción de cada paso al pie: «Elegir archivo…», «Ver vista previa»,
«Continuar con N filas» y «Confirmar importación». La confirmación y el resultado tienen pantalla propia.
El mapeo muestra una muestra fija, una hoja para elegir columna y «Más ajustes» plegable.
En Revisar los conteos filtran las tarjetas y una hoja muestra el detalle de la fila.
No cambian las reglas de negocio, los roles, los límites ni los errores de validación.
Pendientes: el pie dice «Elegir archivo…» y no «Leer la lista» (L46); «Más ajustes» se abre «en línea» (L48).
La ayuda «sigue hablando de las tarjetas de conteo y del select “Tipo”» (L50, L52); «Continuar con 0 filas» sigue habilitado, no así la confirmación (L52).

