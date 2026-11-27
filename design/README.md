# Diseños

Maquetas HTML de las pantallas, hechas antes de implementarlas para comparar alternativas. Cada carpeta es un tema y
cada archivo `NN-<nombre>.html` es una alternativa o una pantalla del tema. Se abren directo en el navegador.

Son la referencia de diseño, no la pantalla final: la app real está en `frontend/src/app` y puede diferir en detalles.

| Carpeta | Qué cubre |
| --- | --- |
| `menu-editor-botones/` | Editor del menú (CU-17) |
| `mostrador-simple/` | Modo simple del mostrador |
| `movil-articulos/` | Artículos, stock, etiquetas y rubros en el celular |
| `movil-compras/` | Compras y proveedores en el celular |
| `movil-configuracion/` | Configuración y usuarios en el celular |
| `movil-mostrador/` | Mostrador y cobro en el celular |
| `movil-precios/` | Importación de listas, actualización masiva y cotización en el celular |
| `movil-usable/` | Patrones comunes para pantallas chicas |
| `movil-ventas/` | Presupuestos, remitos, caja, clientes, ventas y reportes en el celular |

`system.md` documenta los tokens de diseño (colores, tipografía, espaciado) sincronizados desde
`frontend/src/styles.css`; `system.html` los muestra.
