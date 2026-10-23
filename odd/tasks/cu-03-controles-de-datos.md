# cu-03-controles-de-datos

Objetivo: el Dueño ve e imprime los artículos mal cargados (costo 0, sin rubro, margen negativo, IVA 0) para corregirlos · Por qué: GDS-18 del relevamiento (G81, «Controles de datos» de GDS): hoy nadie ve qué artículos quedaron incompletos o venden a pérdida
Rama: `feat/cu-03-controles-de-datos` desde `eb7ec01` · Entrega: single-pr · Runner: `./mvnw test -Dtest=<Clase>` (backend), `npx ng test --watch=false --include=<spec>` (frontend) · Mirror Engram: `odd/cu-03-controles-de-datos/tasks` (ok)
Pronóstico: ~800 líneas cambiadas · Heurística por tarea: ~400 (no es límite) · Modelo: caso 5, Opus de punta a punta (Luca aprobó la propuesta)

## Specs

Alcance: endpoint del listado por control, pantalla imprimible con selector de control, acceso desde el menú, spec y relevamiento · Fuera de alcance: «Ventas con costo mayor a precio de venta» de G81 (es un control sobre ventas ya hechas; la ganancia por artículo ya está en Reportes), la corrección masiva de IVA (G82, clase E), filtros nuevos en la pantalla Artículos
Restricciones: la ferretería sale del JWT; repositorios con métodos `*Scoped`; plata en `BigDecimal`; todo endpoint nuevo con un test de que otra ferretería no lo ve

- S1: "dale vamos con esas cosas pendientes las de contra GDS abierto dentro del mvp" (2026-10-23), para GDS-18: «Listado de control: artículos sin costo, sin rubro, con margen negativo» — Aceptación: `GET /api/articulos/controles?control=COSTO_CERO|SIN_RUBRO|MARGEN_NEGATIVO|IVA_CERO` devuelve todos los artículos activos de la ferretería que caen en ese control, sin páginas (listado para imprimir, como los de GDS), ordenados por descripción, cada uno con código interno, descripción, rubro, proveedor, costo y moneda, alícuota de IVA, precio final y si el precio es manual — Checks: `./mvnw test -Dtest=ControlesDeDatosIntegrationTest`
- S2 (supuesto, como GDS): un control por vez, elegido de un selector, como el submenú «Controles de datos» de GDS (G81: «Verificar costos en cero o mal cargados», «Artículos con alícuota de IVA en 0»). Se suman «sin rubro» y «margen negativo» de GDS-18. El costo nunca es null (`articulo.costo NOT NULL`), así que «sin costo» es costo 0.
- S3 (supuesto): margen negativo es costo en pesos mayor que el precio final sin IVA, con la misma cuenta que el aviso de CU-14 (`MargenNegativo`: costo × cotización × (100 + IVA) > precio × 100; margen 0 no sale), para todos los artículos y no solo los de precio manual. Un artículo en USD usa el dólar vigente de los precios; sin dólar no sale. Un costo 0 no es margen negativo.
- S4 (supuesto): solo el Dueño, como el stock bajo mínimo y los reportes (muestra el costo). Los dados de baja no salen.
- S5: otra ferretería no ve nada: el listado trae solo los artículos propios — Checks: `ControlesDeDatosIntegrationTest`
- S6: pantalla «Controles de datos» con el selector de control, la tabla y «Imprimir» (solo sale el listado, con título, control y fecha); vacío lo dice; cada fila lleva a la ficha del artículo para corregirlo; accesible desde el menú — Checks: specs de `features/controles-de-datos/*`

## Tasks

- [x] T1 · S1-S5 · ruta: inline · Aceptación: endpoint y consulta con aislamiento · Evidencia: `ControlesDeDatosIntegrationTest` RED 8/8 (400, la ruta caía en `/{id}`) → GREEN 8/8; `TenantScopingArchTest` 24, `ModulosArchTest` 40. «Sin rubro» es sin un rubro propio: uno de otra ferretería cuenta como ninguno · commit 477e644 · Risk: item 2 (aislamiento por tenant en consulta nativa con joins)
- [x] T2 · S2, S4, S6 · ruta: inline · Aceptación: pantalla, ruta, menú e impresión; verificada con ego-browser · Evidencia: specs RED (no compilaba: faltaba el componente) → GREEN 80/80 (pantalla, servicio, rutas, shell); ego-browser con `dueno@tornillo.demo`: menú → `/controles-de-datos`, sin rubro lista 5296 artículos de la Lista 336, costo en cero y margen negativo vacíos; con TOR-001 en costo 0 y HER-001 a $ 9.000 (cambiados a mano en la base dev y vueltos atrás) aparecen en su control · commit 2482d1f · Risk: none
- [x] T3 · S1 · ruta: inline · Aceptación: spec (CU-03) y relevamiento (G81, GDS-18) · Evidencia: CU-03 punto 8, G81 y GDS-18 actualizados · commit baa296c · Risk: none

## Log

- L1 (2026-10-23) Pedido original, textual:
  > dale vamos con esas cosas pendientes las de contra GDS abierto dentro del mvp, yo me voy un rato, para esas tareas necesitas alguna deciion mio?
  Respuesta a la propuesta: «GDS-18 (listado de control): pantalla aparte imprimible, como «Controles de datos» de GDS (G81): costo 0 o vacío, sin rubro, margen negativo. Solo Dueño, como «Stock bajo mínimo».» Luca, sobre 3b-2: «como lo tiene gds».
- L2 (2026-10-23) Exploración: G81 de GDS (`docs/relevamiento-gds.md:148`) trae «Verificar costos en cero o mal cargados», «Ventas con costo mayor a precio de venta» y «Artículos con alícuota de IVA en 0». Acá `articulo.costo` es `NOT NULL` y admite 0 (`Articulo.java:295`); `rubro_id` es nullable (`Articulo.java:69`); el aviso de margen negativo es `MargenNegativo.mensaje` (`catalogo/model/MargenNegativo.java`); el dólar de los precios sale de `DolarDePrecios.vigente()` (`cotizacion/api`). Molde: stock bajo mínimo (`StockBajoMinimoController`, `ArticuloRepository.bajoMinimo`, `features/stock-bajo-minimo`).
- L3 (2026-10-23) Cierre: suite backend 4519/0 sobre baa296c; suite frontend 3013/3013; `ng build` OK (el aviso de presupuesto de `dialogo-cobro` ya estaba). Al mergear con `feat/cu-07-consumidor-final`, `layout/shell.spec.ts` queda con `toBe(20)` en las dos ramas y el menú con 21 enlaces: corregir a 21. Risk: item 2 (consulta nativa con joins por ferretería) → `odd-verifier`.
