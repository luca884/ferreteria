# Preguntas abiertas para validar con ferreterías

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · sección extracto de `spec.md:661-677` en la partición del 2026-11-10.

<!-- spec payload: spec.md:661-677 -->
## Preguntas abiertas para validar con ferreterías

Estas preguntas se llevan impresas a cada visita. Las respuestas pueden mover casos de uso adentro o afuera del MVP.

- [ ] ¿Qué sistema usan hoy, cuánto pagan por mes y qué es lo que más odian de él?
- [ ] ¿Cuántos artículos activos tienen y cuántos proveedores les mandan listas? ¿Cada cuánto llegan?
- [ ] ¿En qué formato llegan las listas (Excel, PDF, web del proveedor)? Pedir una de muestra.
- [ ] ¿Son Responsables Inscriptos o Monotributistas? ¿Qué proporción de facturas A hacen?
- [ ] ¿Cuántas ventas hacen en un día fuerte? ¿Cuántos puestos de venta usan a la vez?
- [ ] ¿Usan lector de código de barras? ¿Los artículos tienen código o los etiquetan ellos?
- [ ] ¿Imprimen la factura en A4, en ticket térmico o la mandan por WhatsApp?
- [ ] ¿Venden a cuenta corriente? La venta con límite (CU-07 y CU-11) y la cobranza con imputación, su anulación y el extracto (CU-12, tramos 3a y 3b-1) ya están hechas; la respuesta decide si se usan y qué falta (los ajustes manuales del tramo 3b-2 ya están hechos como en GDS: confirmar con la ferretería que les sirve el Recargo para una deuda vieja). Preguntar también si les alcanza el límite por cliente que autoriza el Dueño y si les sirve que cobre solo el Dueño.
- [ ] ¿Les alcanza un **recibo interno no fiscal** al cobrar la cuenta corriente? Confirmar con el contador de la ferretería la leyenda («Recibo interno, no válido como comprobante fiscal»), la marca «ANULADO» del recibo anulado, la leyenda del extracto impreso («Resumen interno de cuenta, no válido como comprobante fiscal») y si necesitan otro comprobante para sus clientes. Hasta confirmarlo, las leyendas son provisorias.
- [ ] ¿Cobran la cuenta corriente solo en efectivo y transferencia, y siempre contra caja abierta? ¿Reciben pagos a cuenta (adelantos) y los quieren aplicar solos a la próxima venta? ¿Con qué frecuencia se equivocarían al cargar un cobro (se corrige anulándolo y cobrando de nuevo, con caja abierta)?
- [ ] ¿Quién carga los precios hoy y cuánto tiempo le lleva?
- [ ] ¿Cuánto pagarían por mes por algo que les resuelva las listas de precios y la facturación?
<!-- fin payload: spec.md:661-677 -->

