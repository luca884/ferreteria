# aviso-y-paginacion

Objetivo: los avisos con importes ya no cortan el importe en dos renglones, y en Artículos a 1280 px la paginación no se pisa con los filtros · Por qué: los dos detalles pendientes de la sesión 71 (`detalles-chicos-de-hoy.md`, L5).
Rama: `fix/aviso-y-paginacion` desde `edfa0a5f` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / suite `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` / `npx ng build` · Mirror Engram: `odd/aviso-y-paginacion/tasks` (ok)
Pronóstico: ~120 líneas · Modelo: Opus; T1 delegada por el backstop de contexto

## Specs

Alcance: los dos detalles de L1. Fuera de alcance: el alto de la pantalla del QR con error (opcional, no elegido) y el responsive.
Restricciones: sin migraciones ni cambios de contrato JSON (solo cambia un carácter dentro del texto de los avisos). Todo cambio visual se prueba con ego-browser en la app real a 1280 y 1440 px.

- S1: «dale me parece bien, y vamos con tus recomendacion» (L1), sobre el detalle «En celdas angostas, el aviso corta entre «de $» y el importe» — Aceptación:
  - En los avisos que arma el backend con `ImporteArgentino`, el importe nunca se parte en dos renglones: ni entre la moneda y el número («$ 769,50», «US$ 12,00») ni entre el número y su unidad («/ m»). Para eso usa espacios duros (NBSP, U+00A0), que es lo que ya hace el frontend.
  - El resto del texto sigue cortando normal.
  - Usan `ImporteArgentino` `VariacionFuerte`, `MargenNegativo`, `PlanificadorMasivo` y `ArticuloService`, todos en `catalogo`.
  - Checks: los tests de esas clases; ego-browser con el aviso en una celda angosta.
- S2: misma cita, sobre el detalle «En Artículos a 1280, «Página 1 de 208 · 4149 artículos» se pisa con los checkboxes «Mostrar dados de baja» y «Solo en oferta»» — Aceptación: a 1280 y 1440 px, la paginación y los dos checkboxes no se superponen, y la página no tiene scroll horizontal — Checks: ego-browser con medidas (rectángulos sin intersección, `scrollWidth`/`clientWidth`), `npx ng build`
- S3: los supuestos S1a y S2a de `detalles-chicos-de-hoy.md` se toman como confirmados (L1): no hay cambio de código.

## Tasks

- [x] T1 · S1, S2 · ruta: delegada (`odd-implementer` Opus, backstop de contexto) · Evidencia: L4 · Risk: none (solo cambia un carácter del texto de los avisos; CSS acotado a Artículos)
- [x] T2 · cierre · ruta: inline · Aceptación: suite backend, suite frontend y `ng build` verdes · Evidencia: L4 · Risk: —

## Log

- L1 (2026-11-09) Luca, textual: «dale me parece bien, y vamos con tus recomendacion y sobre la caja 5 no pasa nada ni me acuerdo que hice».
  - Responde a la recomendación de hacer en una tanda chica, probada con ego-browser, los dos detalles de código: el aviso que corta entre «de $» y el importe, y la paginación de Artículos que se pisa a 1280.
  - Interpretación, que se le avisó: S1a y S2a del PR #38 quedan confirmados como están.
  - Caja 5 de producción: no se toca.
- L2 (2026-11-09) Mapa: `ImporteArgentino` (`catalogo/model/ImporteArgentino.java`) se usa solo en `ArticuloService`, `VariacionFuerte`, `MargenNegativo` y `PlanificadorMasivo`, para textos de aviso, no para CSV, PDF ni hojas. Antecedente: `ImporteArgentino` usa espacio común y el frontend, NBSP (sesión 71).
- L3 (2026-11-09) Luca, textual: «si podes tocar la caja 5 no pasa nada». Se cerró la caja 5 de producción (Ferretería de Prueba) como Dueño de Prueba, por la API y con un token aparte, sin pisar la sesión del super admin en el navegador. Datos: abierta el 08/11 a las 20:02, inicial $ 782.109,50, ventas en efectivo $ 105.640,00, esperado $ 887.749,50, contado $ 887.749,50, diferencia 0. Ahora `/api/cajas/actual` da `SIN_CAJA`.
- L4 (2026-11-09) T1 (`odd-implementer` Opus): 17 archivos, +148/−81.
  - **S1.** `ImporteArgentino` usa NBSP y suma `formato(valor, Moneda, Unidad)`, que ahora usa `VariacionFuerte`. RED: `ImporteArgentinoTest` falló 8 de 9, y los llamadores 24 de 171. GREEN: 460 focalizados, 0 fallas.
    - Ajustes de tests: `CasosCompartidosMargenNegativoTest` normaliza el NBSP, porque el JSON lo comparte con un spec del front que normaliza los espacios. `CompraFacturaPdfRegresionIntegrationTest` lee en UTF-8, porque MockMvc decodificaba ISO-8859-1.
    - Ego-browser, Actualizar precios con CAB-001 (+146,24 %, solo la vista previa): «$ 500,00 / m» y «$ 1.231,20 / m» quedan en un renglón entre 1280 y 780 px. Con espacios comunes se partían.
  - **S2.** `articulos.css`: `app-barra-acciones { flex-shrink: 0 }`. La barra se achicaba a su `min-height` aunque los checkboxes bajaban de renglón.
    - Antes, a 1280, la paginación se pisaba con los dos checkboxes.
    - Después no se pisa a 1280 ni a 1440, sin scroll horizontal (1280/1280 y 1440/1440) ni vertical.
    - Queda abierto: otras pantallas con el mismo patrón podrían tener lo mismo; una regla global en `styles.css` sería otra decisión.
  - **Cierre.**
    - Suite backend 6527/0.
    - `ng build` OK, con el aviso de presupuesto que ya estaba.
    - El padre corrió `ImporteArgentinoTest` y `VariacionFuerteTest` (26/0) y la suite frontend (164 archivos).
    - No quedan contenedores sueltos.
