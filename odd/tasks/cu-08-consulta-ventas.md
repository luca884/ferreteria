# cu-08-consulta-ventas

Objetivo: consultar las ventas hechas (por número, fecha o cliente), ver cada una con sus renglones y pagos, y reimprimirla, como la «Consulta de Facturas» de GDS · Por qué: lo que falta contra GDS (G60, GDS-15); hoy una venta cobrada no se puede volver a ver ni imprimir
Rama: `feat/cu-08-consulta-ventas` desde `8d1e205` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / suite `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` + `npx ng build` · Mirror Engram: `odd/cu-08-consulta-ventas/tasks` (ok)
Pronóstico: ~1300 líneas cambiadas · Heurística por tarea: ~400 (no es límite) · Modelo: Opus de punta a punta (sesión en curso)

## Specs

Alcance: listado de ventas con filtros, detalle de una venta y su vista imprimible · Fuera de alcance: anular y borrar (anular es la nota de crédito de CU-08, fase 5; un comprobante con CAE no se borra), exportar, pestaña de anulados, ticket impreso al cobrar en el mostrador.
Restricciones: otra ferretería no ve nada y una venta suya es 404 (reglas 3 y 7). Fechas del día de Buenos Aires.

Texto de Luca (2026-10-23): «dale» a seguir con la consulta y reimpresión de ventas; ante la pregunta de acceso: «en estas decisieones me gusta irme por como lo hace gds».

GDS (`R:136`, `R:374-375`, F-1450, `M:consulta-facturas`): busca por F1 número, F2 fecha, F3 cliente; columnas Nro, Fecha, Hora, Cliente, Cod.Cli, CUIT, Total, Usuario; abajo el comprobante reconstruido con sus renglones y el desglose de pagos; `Imprimir` junto a `Anular` y `Borrar`. Acceso por permisos de usuario «Consulta de Ventas» y «Reimprimir Facturas» (este pide el anterior).

- S1: listado de ventas de la ferretería, más nuevas primero, paginado, con filtros número, fecha desde/hasta (días, ambos inclusive) y cliente (contiene, sin distinguir mayúsculas ni tildes). Cada fila: número, fecha y hora, cliente («Consumidor Final» si no hay), total, usuario que la cobró y si es «No facturable» — Checks: `ConsultaVentasIntegrationTest`
- S2: detalle de una venta: renglones (artículo, cantidad, precio, descuento, total), subtotal, descuento, recargo, total, pagos por forma, cliente, usuario, fecha y si salió de un presupuesto o remito; una inexistente o de otra ferretería es 404 «Venta no encontrada» — Checks: ídem
- S3: «Imprimir» abre la venta en una vista imprimible (como el recibo de cuenta corriente) con la leyenda provisoria «Comprobante interno, no válido como factura» hasta que exista el CAE (fase 5) — Checks: specs de Vitest + ego-browser
- S4 (supuesto, mapeo de GDS a roles fijos): solo el Dueño (Vendedor 403 en la API; la pantalla lo avisa). GDS lo da por permiso de usuario y acá no hay permisos por usuario; abrirlo al Vendedor le dejaría sumar las ventas en efectivo del día y rompería el cierre ciego (CU-10). Se abre al Vendedor cambiando el `@PreAuthorize` si Luca lo pide — Checks: `ConsultaVentasIntegrationTest`
- S5: sin «Anular» ni «Borrar» (GDS-15: acciones destructivas separadas de la consulta) — Checks: specs de Vitest
- S6: aislamiento por tenant (regla 7): el listado no trae ventas de otra ferretería — Checks: `ConsultaVentasIntegrationTest`
- S7: pantalla «Ventas» en el menú del Dueño — Checks: specs de Vitest + ego-browser

## Tasks

- [x] T1 · S1 S2 S4 S6 · ruta: inline · Aceptación: `GET /api/ventas` (filtros `numero`, `cliente`, `desde`, `hasta`, `page`, `size`) y `GET /api/ventas/{id}`, solo Dueño · Evidencia: RED `ConsultaVentasIntegrationTest` 5 fallas (405) → GREEN 5/5 con `TenantScopingArchTest` 24 y `ModulosArchTest` 40 · `VentaResponse` suma `usuario` (solo en la consulta; `NON_NULL`, el POST no cambia) · commit siguiente · Risk: none
- [x] T2 · S3 S5 S7 · ruta: inline · Aceptación: pantalla «Ventas» (lista, filtros, detalle, imprimible) y menú; ego-browser · Evidencia: RED compilación (módulos inexistentes) y `shell.spec`/`app.routes.spec` 3 fallas → GREEN `features/ventas` 22/22, suite front 2902/2902, `ng build` OK (el aviso de presupuesto de `dialogo-cobro` ya estaba) · ego-browser (El Tornillo): Vendedor sin enlace y con «La consulta de ventas es solo del dueño.»; Dueño: 2249 ventas en 113 páginas, más nuevas primero con usuario; «PEREZ» trae las 2 de «Construcciones Pérez»; N.º 2245 → detalle con renglón, total y pago «Cuenta corriente»; «Imprimir» abre la hoja con el foco en Imprimir; con `@media print` emulado solo sale la hoja (sin menú, filtros, tabla ni botones), con la leyenda; Esc cierra · commit siguiente · Risk: none
- [x] T3 · S1-S7 · ruta: inline · Aceptación: `docs/spec.md` (CU-16) y `docs/relevamiento-gds.md` (G60, GDS-15) · Evidencia: lectura estructural · commit siguiente · Risk: none

## Log

- L1 (2026-10-23) Pedido original, textual: «dale» a «¿Sigo con la consulta y reimpresión de ventas (G60)?»; ante «¿El Vendedor también consulta y reimprime ventas, o solo el Dueño?»: «en estas decisieones me gusta irme por como lo hace gds».
- L2 (2026-10-23) Mapa: `VentaController` solo tiene `POST /api/ventas`; `VentaResponse.de(VentaRegistrada)` arma la venta con renglones y pagos; molde de listado en `RemitoController`/`RemitoGestion`/`RemitoRepository.buscar`; nombres de usuario con `CajaRepository.nombresScoped`; búsqueda por texto escapada como `ClienteService.escapar` (collation `_ai_ci`); imprimible en `clientes/cuenta/recibo-imprimible` con reglas `@media print` en `styles.css`. GDS (F-1410): «Consulta de Ventas» y «Reimprimir Facturas» son casilleros por usuario; el permiso «Consultar e Imprimir Reportes» ya se mapeó a solo Dueño (CU-15).
- L3 (2026-10-23) Cierre: T1-T3 hechas. Suite backend 4462 tests, 0 fallas; suite front 2902/2902; `ng build` OK. Risk: none (sin migración; `VentaResponse` suma un campo opcional que el POST no manda; acceso probado con 403 y 404 de otra ferretería). Supuesto para Luca: S4, solo el Dueño. Próximo paso: merge de Luca.
