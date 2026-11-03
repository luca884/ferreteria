# review-hallazgos-tramos-2-y-3

Objetivo: corregir los 15 hallazgos del code review de los tramos 2 y 3 ([[review-hallazgos-tramos-2-y-3]] en el vault, `cambios/`), dos de ellos bugs que estaban en producción.
Rama: `fix/review-tramos-2-y-3` desde `595f2c5` · Entrega: single-pr · Runner: frontend `npx ng test --watch=false --include=<spec>` / `npx ng test --watch=false` / `npx ng build`; backend `./mvnw test -Dtest=<Clase>` / `./mvnw test` · Mirror Engram: `odd/review-hallazgos-tramos-2-y-3/tasks` (ok)
Ruteo: caso 1 (Sonnet de punta a punta; sin ítems de alto riesgo) · Pronóstico: ~600 líneas · Estado: en curso

## Specs

Alcance: los 15 hallazgos del review, uno por tarea o por grupo de archivo. Fuera de alcance: los «Detalles menores» del review, salvo los que se rozan al tocar el mismo código.

- S1: «dale me parece bien» (a «¿Empiezo por el 1 y el 2?») — Aceptación: el cartel de Facturación no dice «salen con factura» si el mostrador no emite (`GET /api/ventas/facturacion`), y el CUIT del pedido solo se manda si el campo se ve.
- S2 (supuesto): el resto de los hallazgos (3 a 15) entra con la misma autorización («vamos con los pendientes mas chico»); se avanza sin preguntar entre ítems (memoria «seguir sin consultar entre ítems»). Cada uno con su test primero cuando cambia comportamiento. El 14 (backend, unifica el mensaje del CUIT de Clientes) y el 6 (cambia dónde se arma el ticket) se avisan al cerrar.
- S3: «no me preguntes sobre commits» — commits en español por unidad de trabajo, sin atribución, sin push.

## Tasks

- [x] T1 · S1 · hallazgos 1 y 2: `FacturacionService.emision()`, cartel «Facturación habilitada, pero todavía no emite…», `pideCuit` · RED 3 fallas (cartel ×2, CUIT simulador) → GREEN 37/37 · suite 3513/3513 · `ng build` ok · ego-browser local (certificado vencido → amarillo; restaurado → verde) · `058b329`
- [x] T2 · S2 · hallazgos 3, 7 y 15 (`dialogo-cliente.ts`): tras un error del servidor el foco va al primer campo inválido (o a «Guardar y elegir»), el error del teléfono va debajo del campo y se borra al editar (`errorTelefono`), el aviso «Se ven N de M» es `role="status"` con la clase `.ayuda`; `resultados`/`total`/`consultaBuscada` salen de una sola `respuesta` · RED 4 fallas → GREEN 38/38 · `5d6b237`
- [x] T3 · S2 · hallazgos 6, 4 y 5: «Pasar a venta» (`presupuestos.ts`, `remitos.ts`) lee el cliente de hoy y `ticketDePresupuesto`/`ticketDeRemito` reciben su `condicionIva` (4.º parámetro opcional); el mostrador conserva su lectura solo para tickets viejos. Los tests 4 y 5 ahora prueban algo (ticket guardado con condición; lectura lenta con `Subject`) · RED 2 fallas (+ error de compilación en `ticket.spec`) → GREEN ticket 84/84, presupuestos 44/44, remitos 49/49, mostrador 291/291 · mutación: sin `|| clienteCondicionIva` falla el test 4 y sin el guard de la carrera falla el 5 (código restaurado, `git diff` limpio) · `3aca8c0`
- [x] T4 · S2 · hallazgos 8 y 9 (`actualizacion-masiva.ts`): el botón «Actualizar N artículos» llama a `abrirConfirmacion()` (nunca aplica) y `confirmar()` solo aplica con la confirmación abierta; tras un error al aplicar el foco va a «Ver vista previa»; comentarios de «segunda confirmación» corregidos · RED 3 fallas (doble clic ×2, foco) → GREEN 199/199 · `95288d3`
- [x] T5 · S2 · hallazgos 10, 11 y 13 (`dialogo-ajuste-stock.ts`): `leerNumero` marca `negativo` (el «-0» lleva signo → «—» en la vista previa y error al confirmar); al sumar, `validar()` rechaza un stock resultante mayor al máximo con el mismo texto del backend; una sola `errorDeFormato(leido, unidad)` + tabla `MENSAJES` para la vista previa y los dos modos; `confirmar()` ya no relee el texto · RED 3 fallas (`-0` ×2, tope) con 4 tests de caracterización/borde ya en verde → GREEN 45/45 · `8b7764d`
- [x] T6 · S2 · hallazgo 12: `erroresPorCampo` y `mensajeDe` salen de `core/api/api-error.ts` en `admin-ferreterias`, `articulo-form` (también su `mensajeDe` copiado), `dialogo-ajuste-stock` y `mi-contrasena` (lectura a mano de `errors`). Cambia el borde: un error por campo vacío o en blanco ya no suma una línea vacía en el formulario del artículo ni se traga la respuesta en el ajuste de stock (ahora muestra el mensaje general) · RED 2 fallas → GREEN 5 specs, 288/288 · commit abajo
- [ ] T7 · S2 · hallazgo 14 (backend): `DocumentoCliente.cuit` delega en `common/Cuit`
- [ ] T8 · todas · suites de frontend y backend, `ng build`, ego-browser de lo visible · Risk: none

## Log

- L1 (2026-11-02) Pedido, textual:
  > vamos con los pendientes mas chico y lo de verificar el probar 5 fallos en email inventado comprobalo vos tambien

  Con la recomendación «corregir el 1 y el 2 y juntar el deploy con el Healthcheck»: «dale me parece bien». Después: «dale, no me preguntes sobre commits».
- L2 (2026-11-02) T1 hecho. Además: el límite de login por IP no es global (comprobado en producción); `backend/railway.json` no se aplica (Healthcheck Path cargado en el panel, pendiente de deploy; draining por variable). Ver `estado-actual`.
- L3 (2026-11-02) Hallazgo 1: el estado «habilitada» del servidor no alcanza; la regla real de emisión también pide ambiente, punto de venta, certificado vigente y que ARCA no frene (`FacturacionParaVentaImpl.paraElMostrador` → `emisionSinLock`). Se consulta `GET /api/ventas/facturacion` (también lo lee el Dueño) cada vez que cambia la configuración; si la consulta falla, queda lo que dice la configuración. Para probarlo en local se venció el certificado de El Tornillo (`credencial_arca.vigente_desde/hasta`, con el `CHECK` de vigencia) y se restauró.
- L5 (2026-11-02) T6: quedan otras copias de `mensajeDe` en `articulos.ts`, `proveedores.ts`, `importaciones.ts` y `actualizacion-masiva.ts` (esta última con otra firma). No son parte de los 15 hallazgos; se avisan al cerrar, no se tocan.
- L4 (2026-11-02) T2 y T3 hechos (`5d6b237`, `3aca8c0`). Hallazgo 6, decisión de diseño: la condición no va en el DTO del presupuesto (sería una foto vieja): «Pasar a venta» la lee de hoy y, si la lectura falla, el ticket se arma igual y el mostrador la reintenta. Pasaron los chequeos focalizados; la suite completa se corre en T8.
