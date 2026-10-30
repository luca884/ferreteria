# cu-07-remito-presupuesto-imprimible

Objetivo: que el Vendedor y el Dueño impriman el remito y el presupuesto como documentos no fiscales, con la misma base que la factura (emisor, letra X, «DOCUMENTO NO VALIDO COMO FACTURA», sin CAE ni QR). · Por qué: rama 9 de la fase 5 (`wiki/plan-facturacion.md` del vault, :580-612). Hoy la spec dice «Sin PDF ni impresión hasta la fase 5» (`docs/spec.md:137`).
Rama: `feat/cu-07-remito-presupuesto-imprimible` desde `309d91f` (`main`, con las ramas 1 a 8 en producción) · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Dtest=<Clase>` / `cd frontend && npx ng test --watch=false --include=<spec>` · Mirror Engram: `odd/cu-07-remito-presupuesto-imprimible/tasks` (ok)
Ruteo: caso 3 (Opus investiga y planifica, después `/model sonnet` para implementar), propuesto y aceptado por Luca el 2026-10-29. · Pronóstico: ~1.000 líneas cambiadas (el plan dice 400 a 800, solo frontend; suma un poco de backend, L3) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance:
- Hojas imprimibles (`window.print`) del remito y del presupuesto, desde el detalle de Remitos y de Presupuestos, para el Vendedor y el Dueño.
- La lectura de los datos del emisor para el Vendedor y el Dueño (backend, L3).
- El remito facturado dice qué venta (y qué factura, si tiene CAE) lo facturó (backend, L3).
- La spec (`docs/spec.md`, CU-07 alternativos remito y presupuesto).

Fuera de alcance (plan):
- El PDF generado en el backend.
- El remito con CAI o el remito electrónico.
- El recibo y el extracto siguen como están.

Restricciones:
- La ferretería sale solo del JWT; el remito de otra ferretería es 404 (ya lo es).
- `ventas` habla con `facturacion` solo por `FacturacionParaVenta` (`resumenes`, `facturacion/api/FacturacionParaVenta.java:73`) y con `ferreteria` solo por `ferreteria.api` (`ModulosArchTest`).
- Un documento no fiscal nunca puede salir con apariencia fiscal: letra X siempre, sin código, número fiscal, CAE ni QR.
- Todo endpoint nuevo de datos de una ferretería lleva su caso de aislamiento (regla 7 del `CLAUDE.md`).

- S1 (plan): "Hojas imprimibles (window.print) desde Remitos y desde Presupuestos, para el Vendedor y el Dueño. Llevan la letra X y «DOCUMENTO NO VALIDO COMO FACTURA» (el texto lo confirma el contador), sin CAE ni QR." — Aceptación: desde el detalle, «Imprimir» abre la hoja; Enter imprime y Esc cierra, como la factura imprimible; al imprimir sale solo la hoja. La hoja lleva el emisor (razón social, domicilio comercial, condición frente al IVA, CUIT, Ingresos Brutos e inicio de actividades), la X, «DOCUMENTO NO VALIDO COMO FACTURA», «Remito N.º n» o «Presupuesto N.º n», la fecha, el cliente (o nada si no hay), los renglones, los totales y las observaciones. El presupuesto suma «Válido hasta dd/MM/aaaa». Nunca letra A/B/C, código, CAE ni QR. — Checks: Vitest de las dos hojas y de las dos pantallas.
- S2 (plan): "El remito facturado muestra la factura que lo facturó." — Aceptación: `GET /api/remitos/{id}` de un remito FACTURADO trae la venta que lo facturó (id y número) y, si esa venta tiene factura válida, su letra y número fiscal. El detalle y la hoja dicen «Facturado en la venta N.º n» y, con factura, «Factura B 00001-00000012». Un remito emitido o anulado no lo trae. — Checks: test de integración del detalle.
- S3 (L3, Luca: «dale me parece bien»): el Vendedor y el Dueño leen los datos del emisor de su ferretería para imprimir, en un endpoint de solo lectura. `/api/datos-fiscales` sigue siendo solo del Dueño. — Aceptación: el Dueño y el Vendedor reciben los datos de su ferretería; el super admin, 403; cada ferretería ve solo los suyos. — Checks: test de integración con roles y aislamiento.
- S4 (L2, Luca: «no tengo el texto, dejalo anotado para dejarlo a lo ultimo»): la leyenda «DOCUMENTO NO VALIDO COMO FACTURA» y si un remito sin CAI sirve para acompañar la mercadería quedan provisorias, para confirmar con el contador al final. — Aceptación: el texto vive en una sola constante marcada provisoria, y la spec y el doc lo dicen.
- S5 (plan, terminado): "Hay tests de componente de las dos hojas: datos, letra X y leyenda. Pasa por ego-browser como Vendedor y como Dueño. Suite de frontend y ng build verdes." — Suma la suite de backend por T1 y T2.

## Tasks

- [x] T1 · S3 · ruta: delegada (Luca: «dale, lanza un sobagente con soonet que lo implemente») · Aceptación: `GET /api/emisor` (Dueño y Vendedor, ferretería del JWT, sin `ferreteria` del cliente) con test de roles y aislamiento · Evidencia: `EmisorIntegrationTest` RED 3/4 en 404 → GREEN 4/4; commit `079b32a` · Risk: item 3 (endpoint nuevo, aditivo)
- [x] T2 · S2 · ruta: delegada · Aceptación: `RemitoResponse.facturadoPor` en el detalle de un remito FACTURADO, con la factura de `resumenes` si es válida; test de integración · Evidencia: `RemitoFacturadoPorIntegrationTest` RED (NPE, `facturadoPor` null) → GREEN 3/3; commit `079b32a` · Risk: item 3 (respuesta ampliada, aditiva)
- [x] T3 · S1, S4 · ruta: delegada · Aceptación: componente de hoja no fiscal compartido + diálogo imprimible (Enter/Esc, reglas `@media print`), con Vitest de datos, X y leyenda, y que nunca salga código, CAE ni QR · Evidencia: 3 specs de `shared/documento-no-fiscal` RED (TS2307) → GREEN 15/15; leyenda en `LEYENDA_DOCUMENTO_NO_FISCAL`; commit `d17af78` · Risk: none
- [x] T4 · S1, S2 · ruta: delegada · Aceptación: «Imprimir» en el detalle de Remitos y de Presupuestos (Vendedor y Dueño), carga del emisor y error si no se pudo; «Facturado en la venta N.º n» en el detalle del remito; Vitest de las pantallas · Evidencia: RED observado después (revirtiendo la implementación: TS2353 `facturadoPor`), GREEN 120/120 en remitos, presupuestos y la hoja; commit `d17af78` · Risk: none
- [x] T5 · S1, S2, S3, S4 · ruta: delegada · Aceptación: la spec deja de decir «Sin PDF ni impresión hasta la fase 5» y describe las hojas, el emisor y la leyenda provisoria · Evidencia: readback; commit `54e6dd7` · Risk: none
- [x] T6 · S5 · ruta: inline · Aceptación: suite de backend, suite de frontend, `ng build` y ego-browser como Vendedor y como Dueño · Evidencia: suites y build del L4; ego-browser L5 · Risk: —
- [ ] T7 · S4 · ruta: Luca (externa, al final) · Aceptación: el contador confirma la leyenda y el remito sin CAI; se ajusta la constante y la spec · Evidencia: — · Risk: —

## Log

- L1 (2026-10-29) Pedido original, textual:
  > continuamos con la rama 9
- L2 (2026-10-29) Luca, sobre el texto del contador: «no tengo el texto, dejalo anotado para dejarlo a lo ultimo».
- L3 (2026-10-29) Exploración: el plan dice «solo frontend», pero tiene dos huecos:
  - la hoja de la factura toma el emisor del registro fiscal (`shared/factura/factura-hoja.html`), y el remito y el presupuesto no lo traen; `/api/datos-fiscales` es solo del Dueño (`SecurityConfig.java:54`);
  - `RemitoResponse` no dice qué venta lo facturó (`ventas/web/RemitoResponse.java:20`); la venta guarda `remitoId` (`ventas/model/Comprobante.java:99`).
  Se propuso sumar los dos datos al backend (solo lectura, aditivo, sin migración) y Luca respondió «dale me parece bien». El emisor va en un endpoint propio de `ferreteria` en vez de en cada detalle: así `ventas` no suma una dependencia de `ferreteria`, y el presupuesto y el remito lo leen igual. Sale con los datos actuales de la ferretería: estos documentos no los congelan.
  Base reusable: `FacturaImprimible` (diálogo, Enter/Esc, `@media print` en `styles.css:862`), `VentaImprimible` y `factura-hoja.css`.
- L4 (2026-10-29) Luca: «dale, lanza un sobagente con soonet que lo implemente». T1-T5 con un `odd-implementer` (sonnet). Reporte: backend 5824/0, `ModulosArchTest`+`TenantScopingArchTest` 76/0, frontend 3391/3391, `ng build` OK (warning de budget de `dialogo-cobro.ts`, previo). Spot check del padre: los focalizados del frontend 120/120 y `EmisorIntegrationTest`+`RemitoFacturadoPorIntegrationTest` 7/0. Desvíos: el super admin recibe 403 por `@PreAuthorize` sin tocar `SecurityConfig`; `facturadoPor` solo en el detalle (anular un facturado es 409); las hojas usan `fechaCompletaLocal` (con año); el `cuit` de `DatosFiscales` llega con guiones y la hoja lo normaliza. Commits `079b32a`, `d17af78`, `54e6dd7` (~1.400 líneas). Próximo: T6 ego-browser; T7 al final con el contador.
- L5 (2026-10-29) ego-browser en local (perfil dev): como Vendedor, Remitos → N.º 6 (facturado) → «Imprimir»: hoja con emisor de El Tornillo, X, «DOCUMENTO NO VALIDO COMO FACTURA», «Remito N.º 6», «Facturado en la venta N.º 2254» (sin «Factura …»: en dev la venta sale X por el simulador, como debe); Esc cierra. Como Dueño, Presupuestos → N.º 5 → «Imprimir»: «Válido hasta 07/11/2026» y observaciones; con `media: print` solo sale la hoja. Riesgo: T1 y T2 son agregados pedidos (S2, S3) que consume solo el frontend propio, así que no entran en el ítem 3 (un cambio pedido no es alto riesgo por sí solo): `Risk: none`, sin `odd-verifier`. Pendiente: T7 (contador) y el merge, que corre Luca.
