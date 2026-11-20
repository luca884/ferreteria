# CU-06 · Buscar artículo

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · caso de uso extracto de `spec.md:100-105` en la partición del 2026-11-10.

<!-- spec payload: spec.md:100-105 -->
**CU-06 Buscar artículo** (Vendedor)

1. Escanea el código de barras o escribe parte de la descripción o el código. La mayoría de las ferreterías ya tiene lector: funciona como teclado (escribe el código y manda Enter), así que el campo de búsqueda tiene que tener el foco siempre y reaccionar al Enter.
2. Ve precio, stock y ubicación al instante, sin hacer clic en nada. También ve «Costo actualizado el»: desde cuándo está al día el costo (GDS-05, como la «F. Modif.» de GDS). Es una fecha, no el costo.
<!-- fin payload: spec.md:100-105 -->

**Agregado móvil: escáner por cámara** (≤639.98 px, Vendedor y Dueño, en Artículos).
Con `BarcodeDetector` y `getUserMedia`, el botón junto al buscador abre la cámara a pantalla completa.
El código leído se busca con el servicio de Artículos; en la tarjeta del resultado está «Abrir ficha».
La app vuelve a pedir acceso cada vez que se abre el escáner; si el navegador no lo admite o lo deniega, se puede escribir a mano.
No cambian las reglas de negocio, los roles ni los textos del buscador manual; el Mostrador sigue con lector.
Pendientes (L21): el botón va «entre la caja y “Buscar”»; el video reemplaza la tira decorativa,
la cámara trasera es preferida y la búsqueda «incluye dados de baja», que se marcan en la tarjeta.


