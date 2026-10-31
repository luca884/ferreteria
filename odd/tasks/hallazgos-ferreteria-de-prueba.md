# hallazgos-ferreteria-de-prueba

Objetivo: corregir los hallazgos de la carga de la Ferretería de Prueba del 2026-10-30 (vault `wiki/datos-ferreteria-de-prueba.md` → `## Hallazgos`), empezando por los reportes.

- Rama: `fix/hallazgos-ferreteria-de-prueba` (desde `main` después de commitear A2 de cu-01).
- Ruteo: caso 3 (Opus investiga y planifica; tareas mecánicas en Sonnet). Aprobado por Luca el 2026-10-31.
- Pedido: «dale, armá el plan empezando por los reportes» (Luca, 2026-10-31).

## Specs

- S1: «cualquier inconveniente que se encuentre o limitacion la registras para corregirla» (Luca, 2026-10-30).
- S2: «dale, armá el plan empezando por los reportes» (Luca, 2026-10-31).

## Contexto verificado

- Reportes: «Por medio de pago» en bruto y el resto neto de NC es **decisión confirmada**: S12 de `odd/tasks/cu-08-nota-de-credito.md:56` («“Por medio de pago” no las resta, porque no mueven plata») y `docs/spec.md:286`. El cálculo no es un error; falta que la pantalla diga qué suma cada total.
- Cierre de caja: la NC no mueve la caja (`docs/spec.md:176`, aviso «La caja no cambia: si devolvés plata, cargá un egreso»), por eso el total incluye las ventas después anuladas. Falta mostrarlo.
- Letra «X» en el simulador: por diseño (`HojaFiscal.java:121`). No entra.

## Tareas

Cada tarea que agrega algo fuera de la spec espera el OK de Luca antes de implementarse (CLAUDE.md del proyecto).

- [x] T1 · Reportes y cierre de caja: aclarar bruto vs. neto. «Por medio de pago» con leyenda de que no resta las notas de crédito y el renglón de NC del día; el cierre de caja con un renglón informativo de anulaciones del día. **Decidido (2026-10-31, como GDS)**: solo se aclara en pantalla; el cálculo no cambia (G58: la NC de GDS no actualiza caja; G64: la caja muestra cada medio sin descontar devoluciones; G66: ventas y devoluciones por separado).
- [ ] T2 · Anular una venta facturada pide el motivo, como la anulación interna. Verificar si la NC ya guarda motivo (si suma columna → migración, alto riesgo 1).
- [ ] T3 · Foco después de guardar en Usuarios y Rubros: reproducir a mano antes de tocar código.
- [ ] T4 · Cobranza y descuento: la imputación FIFO arranca precargada.
- [ ] T5 · Actualización masiva: confirmación antes de aplicar, con aviso de que no tiene deshacer.
- [ ] T6 · Compras: el costo precargado con coma decimal.
- [ ] T7 · Ajuste de stock: la vista previa no muestra una cantidad inválida.
- [ ] T8 · Cliente (F9): avisar que hay más clientes y que se busca escribiendo.
- [ ] T9 · CUIT inválido: un solo mensaje en Clientes y Proveedores, junto al campo.
- [ ] T10 · Pantalla de Facturación: decir si está habilitada; en Simulador no mostrar los pasos del certificado de ARCA.
- [ ] T11 · «Pasar a venta» desde presupuesto o remito: el cobro muestra la letra (A/B) del cliente.
- [ ] T12 · Guion: sacar anotaciones de las descripciones, cambiar PLO-001 por PIN-001 en Artículos › Clientes y aclarar las filas de anulación en /ventas (sin código).

Fuera del plan (a decidir aparte): «Crear los artículos nuevos» apagado por defecto en la importación, numeración compartida de recargos y descuentos, rueda del mouse en campos de precio.

## Log

- 2026-10-31: plan armado. T1 verificada contra S12 de cu-08: el cálculo es decisión, el arreglo es de presentación.
- 2026-10-31: A2 de cu-01 commiteado en `main` (`0ae9bcf`). Antes: backend 5864/0, frontend 3395/3395, `ng build` ok. Después se abrió la rama `fix/hallazgos-ferreteria-de-prueba` y se aprobó el ruteo (caso 3).
- 2026-10-31: T1 decidida como GDS: solo presentación (G58, G64, G66).
- 2026-10-31: T1 implementada. «Por usuario» cuenta las ventas emitidas, también las anuladas después, y el total resta las NC en su fecha; es la misma decisión (S12 de cu-08), así que también se aclara en pantalla. La NC no tiene `caja_id`: el renglón del cierre se deriva de las ventas de la caja con NC (`ComprobanteRepository.anuladasDeLaCajaScoped`, sin migración) y sale en `GET /api/cajas/{id}` como `ventasAnuladas` (campo nuevo; solo lo consume nuestro frontend). `/api/cajas/actual` sigue ciega. RED: `AnulacionDeVentaIntegrationTest#laCajaNoCambia` (NPE, faltaba el campo) y 3 specs (`resumen-caja`, `reportes`). GREEN: backend 33 + 18 + 6 + 26 focalizados, frontend 49/49. Textos elegidos por el agente (a confirmar con Luca): «Ventas anuladas (n)» con «ya están en el total de ventas: anular no cambia la caja»; las aclaraciones de «Por medio de pago» y «Por usuario». Suites: backend 5864/0, frontend 3399/3399, `ng build` ok (el warning de tamaño de `dialogo-cobro.ts` ya estaba). ego-browser en local (El Tornillo, 2 ventas por $ 240 y 1 anulada por $ 80): el resumen de la caja muestra «Ventas anuladas (1) $ 80,00» después del «Total de ventas $ 240,00», con el esperado sin cambios; Reportes: Resumen $ 160, Por medio de pago $ 240 con la aclaración y Por usuario 2 ventas por $ 160 con la suya. En la base de dev se cerró la caja 1 (de otro día) para abrir la del día.
