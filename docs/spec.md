# Ferretería SaaS — Especificación del MVP

Esta spec está **partida por caso de uso**. Antes era un solo archivo de 170 KB (~43k tokens):
leerla entera costaba media ventana de contexto. Ahora se lee sólo lo que la tarea necesita.

## Cómo leerla

1. Empezá por esta página y por [`02-casos-de-uso.md`](spec/02-casos-de-uso.md) si necesitás ubicar el CU.
2. Leé **sólo** el archivo del CU que vas a tocar, más las secciones transversales que ese CU cite.
3. Si algo no está en la spec, no se implementa sin preguntar.

## Casos de uso

| CU | Archivo | Qué cubre | Rol | Tamaño |
|---|---|---|---|---|
| CU-01 | [`cu-01-configurar-ferreteria.md`](spec/cu-01-configurar-ferreteria.md) | Configurar la ferretería | Dueño | 9.5 KB |
| CU-02 | [`cu-02-usuarios.md`](spec/cu-02-usuarios.md) | Gestionar usuarios | Dueño | 0.7 KB |
| CU-03 | [`cu-03-articulos.md`](spec/cu-03-articulos.md) | Gestionar artículos | Dueño | 6.7 KB |
| CU-04 | [`cu-04-importar-lista-precios.md`](spec/cu-04-importar-lista-precios.md) | Importar lista de precios del proveedor | Dueño | 3.8 KB |
| CU-05 | [`cu-05-actualizar-precios.md`](spec/cu-05-actualizar-precios.md) | Actualizar precios masivamente | Dueño | 2.1 KB |
| CU-06 | [`cu-06-buscar-articulo.md`](spec/cu-06-buscar-articulo.md) | Buscar artículo | Vendedor | 1.5 KB |
| CU-07 | [`cu-07-vender-facturar.md`](spec/cu-07-vender-facturar.md) | Vender y facturar | Vendedor | 28.8 KB |
| CU-08 | [`cu-08-anular-venta.md`](spec/cu-08-anular-venta.md) | Anular una venta | Dueño | 5.4 KB |
| CU-09 | [`cu-09-stock.md`](spec/cu-09-stock.md) | Consultar y ajustar stock | Dueño | 3.0 KB |
| CU-10 | [`cu-10-caja.md`](spec/cu-10-caja.md) | Abrir y cerrar caja | Vendedor y Dueño | 7.1 KB |
| CU-11 | [`cu-11-clientes.md`](spec/cu-11-clientes.md) | Clientes | Vendedor y Dueño | 6.4 KB |
| CU-12 | [`cu-12-cobranza.md`](spec/cu-12-cobranza.md) | Cobranza de cuenta corriente | Dueño | 26.5 KB |
| CU-13 | [`cu-13-compras.md`](spec/cu-13-compras.md) | Registrar una compra | Dueño | 8.1 KB |
| CU-14 | [`cu-14-precio-manual.md`](spec/cu-14-precio-manual.md) | Precio manual y ajustes sobre el precio final | quien edita artículos | 3.0 KB |
| CU-15 | [`cu-15-reportes.md`](spec/cu-15-reportes.md) | Reportes de ventas | Dueño | 3.3 KB |
| CU-16 | [`cu-16-consultar-ventas.md`](spec/cu-16-consultar-ventas.md) | Consultar y reimprimir ventas | Dueño | 6.5 KB |
| CU-17 | [`cu-17-personalizar-menu.md`](spec/cu-17-personalizar-menu.md) | Personalizar el menú | Dueño | 8.8 KB |

## Secciones transversales

| Archivo | Contenido | Tamaño |
|---|---|---|
| [`00-vision-y-alcance.md`](spec/00-vision-y-alcance.md) | Visión y alcance | 1.2 KB |
| [`01-actores-y-roles.md`](spec/01-actores-y-roles.md) | Actores y roles | 2.3 KB |
| [`02-casos-de-uso.md`](spec/02-casos-de-uso.md) | Casos de uso del MVP + mapa de módulos | 2.5 KB |
| [`10-reglas-de-negocio.md`](spec/10-reglas-de-negocio.md) | Reglas de negocio | 14.9 KB |
| [`11-modelo-de-datos.md`](spec/11-modelo-de-datos.md) | Modelo de datos | 21.7 KB |
| [`12-integracion-arca.md`](spec/12-integracion-arca.md) | Integración ARCA | 16.6 KB |
| [`13-arquitectura-y-stack.md`](spec/13-arquitectura-y-stack.md) | Arquitectura y stack | 2.4 KB |
| [`14-fuera-de-alcance.md`](spec/14-fuera-de-alcance.md) | Fuera del alcance del MVP | 3.1 KB |
| [`15-plan-de-implementacion.md`](spec/15-plan-de-implementacion.md) | Plan de implementación | 4.3 KB |
| [`16-preguntas-abiertas.md`](spec/16-preguntas-abiertas.md) | Preguntas abiertas para validar con ferreterías | 2.5 KB |

## Mapa de líneas del archivo original

Citado como `docs/spec.md:<línea>` en el SDD y en `odd/tasks` (59 referencias). El archivo original
tenía 676 líneas y se partió el 2026-11-10 **sin editar una sola palabra**: cada bloque se copió verbatim.
Única excepción, corregida el 2026-11-10 por decisión de Luca: el conteo de casos de uso de
[`spec/02-casos-de-uso.md`](spec/02-casos-de-uso.md), que decía «12 casos de uso … cinco módulos» cuando la propia
lista tiene 16 CU y seis módulos. Está marcada ahí con un comentario.
Para resolver una cita histórica, buscá la línea en la tabla:

| Rango original | Archivo nuevo |
|---|---|
| `41-60` | [`spec/cu-01-configurar-ferreteria.md`](spec/cu-01-configurar-ferreteria.md) |
| `61-64` | [`spec/cu-02-usuarios.md`](spec/cu-02-usuarios.md) |
| `67-81` | [`spec/cu-03-articulos.md`](spec/cu-03-articulos.md) |
| `82-91` | [`spec/cu-04-importar-lista-precios.md`](spec/cu-04-importar-lista-precios.md) |
| `92-97` | [`spec/cu-05-actualizar-precios.md`](spec/cu-05-actualizar-precios.md) |
| `100-104` | [`spec/cu-06-buscar-articulo.md`](spec/cu-06-buscar-articulo.md) |
| `105-146` | [`spec/cu-07-vender-facturar.md`](spec/cu-07-vender-facturar.md) |
| `147-167` | [`spec/cu-08-anular-venta.md`](spec/cu-08-anular-venta.md) |
| `170-175` | [`spec/cu-09-stock.md`](spec/cu-09-stock.md) |
| `176-189` | [`spec/cu-10-caja.md`](spec/cu-10-caja.md) |
| `192-208` | [`spec/cu-11-clientes.md`](spec/cu-11-clientes.md) |
| `209-256` | [`spec/cu-12-cobranza.md`](spec/cu-12-cobranza.md) |
| `257-297` | [`spec/cu-13-compras.md`](spec/cu-13-compras.md) |
| `298-305` | [`spec/cu-14-precio-manual.md`](spec/cu-14-precio-manual.md) |
| `308-321` | [`spec/cu-15-reportes.md`](spec/cu-15-reportes.md) |
| `322-342` | [`spec/cu-16-consultar-ventas.md`](spec/cu-16-consultar-ventas.md) |
| `5-18` | [`spec/00-vision-y-alcance.md`](spec/00-vision-y-alcance.md) |
| `19-34` | [`spec/01-actores-y-roles.md`](spec/01-actores-y-roles.md) |
| `35-38` | [`spec/02-casos-de-uso.md`](spec/02-casos-de-uso.md) |
| `343-417` | [`spec/10-reglas-de-negocio.md`](spec/10-reglas-de-negocio.md) |
| `418-474` | [`spec/11-modelo-de-datos.md`](spec/11-modelo-de-datos.md) |
| `475-567` | [`spec/12-integracion-arca.md`](spec/12-integracion-arca.md) |
| `568-601` | [`spec/13-arquitectura-y-stack.md`](spec/13-arquitectura-y-stack.md) |
| `602-622` | [`spec/14-fuera-de-alcance.md`](spec/14-fuera-de-alcance.md) |
| `623-660` | [`spec/15-plan-de-implementacion.md`](spec/15-plan-de-implementacion.md) |
| `661-676` | [`spec/16-preguntas-abiertas.md`](spec/16-preguntas-abiertas.md) |
| `1`, `3` | esta página (título y puntero al doc vivo) |
| `39`, `65`, `98`, `168`, `190`, `306` | [`spec/02-casos-de-uso.md`](spec/02-casos-de-uso.md) (encabezados de módulo) |

## Fuente de verdad

El contenido normativo vive en `docs/spec/`. Los documentos históricos (`.atl/sdd/archive/`, `odd/tasks/`)
conservan sus citas `spec.md:<línea>` y no se reescriben: se resuelven con la tabla de arriba.
Al citar de acá en adelante, usá `docs/spec/<archivo>.md` y no el número de línea.
