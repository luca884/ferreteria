# cu-07-consumidor-final

Objetivo: una venta facturable desde el tope de Consumidor Final exige un cliente identificado (DNI, CUIT o CUIL), y el Dueño edita ese tope · Por qué: GDS-32 del relevamiento (G88, «Límite de Venta a Cons.Final sin datos» de GDS); ARCA exige identificar al comprador desde cierto importe
Rama: `feat/cu-07-consumidor-final` desde `eb7ec01` · Entrega: single-pr · Runner: `./mvnw test -Dtest=<Clase>` (backend), `npx ng test --watch=false --include=<spec>` (frontend) · Mirror Engram: `odd/cu-07-consumidor-final/tasks` (ok)
Pronóstico: ~900 líneas cambiadas · Heurística por tarea: ~400 (no es límite) · Modelo: caso 5, Opus de punta a punta (Luca aprobó la propuesta)

## Specs

Alcance: tope por ferretería (migración aditiva), su pantalla de configuración para el Dueño, el rechazo de la venta en el backend y el aviso en el mostrador · Fuera de alcance: presupuestos y remitos (no son comprobantes fiscales; la venta que sale de ellos sí se controla), la factura con CAE (fase 5), la identificación con CUIT para Ganancias sin importar el monto (la pide el comprador, no es una regla del sistema)
Restricciones: la ferretería sale del JWT; plata en `BigDecimal`; todo endpoint nuevo con un test de que otra ferretería no lo ve; ningún rechazo guarda nada

- S1: "dale vamos con esas cosas pendientes las de contra GDS abierto dentro del mvp" (2026-10-23), para GDS-32: «Venta a Consumidor Final: por encima de un umbral configurable exige DNI o CUIT. Verificar el valor vigente de ARCA en la fase 1» — Aceptación: `POST /api/ventas` facturable con total (con recargo) mayor o igual al tope y sin cliente, o con un cliente sin documento, responde 422 `CONSUMIDOR_FINAL_SIN_DATOS` con el mensaje «Desde $ <tope> la venta a Consumidor Final necesita identificar al comprador: elegí un cliente con DNI, CUIT o CUIL.» y no guarda nada; con un cliente con documento, o por debajo del tope, se vende como siempre — Checks: `./mvnw test -Dtest=VentaConsumidorFinalIntegrationTest`
- S2 (supuesto, como GDS): el tope es un parámetro por ferretería que edita el Dueño (en GDS, `Límite de Venta a Cons.Final sin datos` en la configuración). Valor inicial **$ 10.000.000,00**: el vigente de ARCA (RG 5700/2025, que mantiene la RG 5866/2026). Tiene que ser mayor que 0. Rige desde el importe igual al tope («igual o superior»).
- S3 (supuesto): la venta no facturable no se controla: no va a ser un comprobante fiscal. Presupuestos y remitos tampoco; sí la venta que sale de ellos.
- S4 (supuesto): «identificado» es un cliente con documento cargado (el documento del cliente ya es DNI, CUIT o CUIL con dígito verificador). Un nombre libre sin cliente elegido no identifica.
- S5: `GET` y `PUT /api/configuracion-ventas` (`topeConsumidorFinal`), solo el Dueño; el Vendedor recibe 403; un tope 0, negativo o vacío es 400; cambiar el tope de una ferretería no toca el de otra — Checks: `ConfiguracionVentasIntegrationTest`
- S6: pantalla «Consumidor Final» (menú, grupo «Cobro») donde el Dueño ve y guarda el tope, con la referencia de ARCA; en el mostrador, el rechazo deja el cobro abierto con el mensaje para elegir el cliente — Checks: specs de `features/configuracion-ventas/*` y `mostrador`

## Tasks

- [ ] T1 · S2, S5 · ruta: inline · Aceptación: migración del tope, servicio y endpoint de configuración con aislamiento · Evidencia: — · Risk: item 1 (migración aditiva)
- [ ] T2 · S1, S3, S4 · ruta: inline · Aceptación: rechazo de la venta sin datos desde el tope · Evidencia: — · Risk: item 3 (contrato de `POST /api/ventas`: motivo nuevo)
- [ ] T3 · S6 · ruta: inline · Aceptación: pantalla, menú y aviso del mostrador; verificada con ego-browser · Evidencia: — · Risk: none
- [ ] T4 · S1 · ruta: inline · Aceptación: spec (CU-07) y relevamiento (G88, GDS-32) · Evidencia: — · Risk: none

## Log

- L1 (2026-10-23) Pedido original, textual:
  > dale vamos con esas cosas pendientes las de contra GDS abierto dentro del mvp, yo me voy un rato, para esas tareas necesitas alguna deciion mio?
  Respuesta a la propuesta: «GDS-32 (Consumidor Final sin identificar): tope editable por el Dueño, como parámetro de GDS (G88). Venta que lo pasa exige cliente con DNI/CUIT al cobrar. Valor inicial = el vigente de ARCA (lo busco y lo dejo como supuesto anotado).»
- L2 (2026-10-23) Exploración: ARCA fija $ 10.000.000 para identificar al consumidor final (RG 5700/2025, desde el 29/05/2025; la RG 5866/2026, vigente desde el 01/07/2026, mantiene el monto), con DNI, CUIL, CDI o pasaporte; las notas lo dicen como «igual o superior». Acá la venta resuelve el cliente en `ClienteDelDocumento.resolverParaVenta` (`ventas/service/ClienteDelDocumento.java`) con el puerto `ClientesParaVenta.tomarParaVenta`, que hoy devuelve `ClienteReferencia(id, razonSocial, activo)` sin el documento; el total con recargo sale de `TotalesVenta` en `RegistroVenta.registrar`. La configuración por ferretería vive en `Ferreteria` (margen y redondeo, `ConfiguracionPreciosService`). El mostrador trata los motivos en `mostrador.ts` (`switch (rechazo.motivo)`) y los reconoce en `venta.service.ts` (`MOTIVOS`).
