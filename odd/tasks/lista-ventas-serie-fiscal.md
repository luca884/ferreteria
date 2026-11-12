# lista-ventas-serie-fiscal

Objetivo: que la lista de Ventas distinga de qué serie fiscal es cada comprobante, para que dos series distintas no se lean como la misma numeración repetida.

- Rama: ninguna, el arreglo ya está en `main` (`fd1c387f`). Queda sólo el deploy, que hace Luca.
- Origen: la revisión de la caja 5 del 2026-11-12, que arrancó como sospecha de números fiscales duplicados en producción.

## Specs

- S1 — textual del pedido: «okey anotalo y eso hay que arreglarlo y lo podmos hacer cuando sigamos con los comestico».
- S2 — la evidencia que disparó el hallazgo: en la lista de `/api/ventas` de la Ferretería de Prueba (`ambiente` SIMULADOR) dos comprobantes de series distintas se ven idénticos, «X 00001-00000009 Simulado», para la venta 32 (Factura A nº 9, 08/11 21:24) y la venta 15 (Factura B nº 9, 30/10 20:04). La `X` sale de `HojaFiscal.letra()`, que sólo muestra la letra real cuando el comprobante es válido como factura, y eso exige `ambiente` PRODUCCION.

## Tasks

- [x] T1 · S1, S2 · ruta: ninguna, no hay código que escribir · que la lista de Ventas muestre la letra de la serie entre paréntesis cuando la hoja va como X. Aceptación: el DTO del listado manda la letra del registro y la pantalla la usa. · Evidencia: ya en `main` desde `fd1c387f` (2026-11-09 07:21Z, `bugs-relevamiento-celular` S9), que tocó `backend/src/main/java/com/ferreteria/ventas/web/VentaResumenResponse.java` (+6: campo `letraFiscal` en `FiscalResumenResponse`), `frontend/src/app/features/ventas/ventas.ts` (+19: `letraEnPantalla` en la columna Factura), `frontend/src/app/shared/factura/factura.models.ts` (+11) y sus specs. Producción corre un build anterior: `railway deployment list` da backend SUCCESS `2026-11-09T05:26:48Z` y frontend `2026-11-09T05:28:39Z`, los dos previos al commit, y por eso `GET /api/ventas` todavía responde `fiscal` sin `letraFiscal`. · commit: `fd1c387f`

## Log

- L1 (2026-11-12) Pedido, textual: «okey anotalo y eso hay que arreglarlo y lo podmos hacer cuando sigamos con los comestico».
- L2 (2026-11-12) La nota nace de un falso positivo que me comí yo: agrupé las ventas por la etiqueta de pantalla (`X · punto de venta · número`) y vi «duplicados» en las series 1 a 9. Agrupando por la identidad fiscal real (`clase + letra + puntoVenta + numero`, que sólo trae el detalle de cada venta) no hay **ningún** duplicado: `FACTURA-A-1` va 1..9 y `FACTURA-B-1` va 1..17, las dos monótonas y sin huecos reusados. `uk_comprobante_fiscal_numero` (V32) hace imposible dos AUTORIZADO con la misma identidad dentro de una serie. El motor fiscal no tenía nada que arreglar.
- L3 (2026-11-12) Mi primer diagnóstico dijo que faltaban «tres piezas: DTO del listado, modelo del frontend y un test». Era falso: las tres ya estaban. Lo que falta no es código sino **deploy**: producción quedó en el build del 09/11 02:26 ART y el arreglo se commiteó ese mismo día a las 04:21 ART.
- L4 (2026-11-12) Dato de paso, sobre la sospecha original de la caja 5: ese deploy de las 02:26 ART del 09/11 lo hizo un agente (`meta.cliCaller` = `claude_code`), y la caja 5 se cerró a las 02:00 ART, media hora antes. La caja quedó **cerrada, sin diferencia** ($ 887.749,50 contra $ 887.749,50) y sin movimientos: nada que reparar.
