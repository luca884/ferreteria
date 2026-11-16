# CU-06 · Buscar artículo

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · caso de uso extracto de `spec.md:100-105` en la partición del 2026-11-10.

<!-- spec payload: spec.md:100-105 -->
**CU-06 Buscar artículo** (Vendedor)

1. Escanea el código de barras o escribe parte de la descripción o el código. La mayoría de las ferreterías ya tiene lector: funciona como teclado (escribe el código y manda Enter), así que el campo de búsqueda tiene que tener el foco siempre y reaccionar al Enter.
2. Ve precio, stock y ubicación al instante, sin hacer clic en nada. También ve «Costo actualizado el»: desde cuándo está al día el costo (GDS-05, como la «F. Modif.» de GDS). Es una fecha, no el costo.
<!-- fin payload: spec.md:100-105 -->

**Agregado móvil: escáner por cámara** (Vendedor y Dueño, en Artículos, en el celular). Quien no tiene lector puede escanear el código de barras con la cámara del teléfono desde el buscador de Artículos: el código leído entra al campo de búsqueda y se busca como si se hubiera escrito (regla 1). Pide permiso de cámara la primera vez; si se niega, el buscador sigue funcionando a mano. No cambia el Mostrador, que sigue con lector. Diseño: `design/movil-articulos/01-toca-la-fila.html`.


