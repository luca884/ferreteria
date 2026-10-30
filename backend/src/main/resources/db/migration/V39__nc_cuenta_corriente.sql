-- CU-08 y CU-12, rama 10 de facturacion (cu-08-nc-cuenta-corriente T2: S1; D11, y S6 a S9 confirmados por Luca el
-- 2026-10-29): la nota de credito (o la anulacion interna) de una venta a cuenta corriente es el TERCER origen de credito de
-- la imputacion, junto al recibo (V21) y al Descuento (V28). Cambia solo imputacion; ninguna fila existente cambia.
--
-- El libro NO cambia. La NC asienta un renglon tipo 'NC' por cada pago a cuenta corriente de la venta: haber = el monto de
-- ese pago, pago_id = ese pago, comprobante_id = el comprobante NOTA_CREDITO, sin recibo, sin motivo y sin ajuste, con la
-- referencia «Nota de credito N.º n» o «Anulacion N.º n». Entra con los CHECK que ya hay:
--   ck_mcc_tipo_v21 y ck_mcc_sentido_v21 (V21)  'NC' ya esta admitido, con haber > 0.
--   ck_mcc_importes (V20)                        debe 0 y haber > 0.
--   ck_mcc_venta_ref (V20)                       solo obliga a la VENTA.
--   ck_mcc_recibo_ref (V21)                      sin recibo; comprobante y pago solo estan prohibidos en COBRO y ANULACION.
--   ck_mcc_motivo (V22)                          sin motivo (solo ANULACION y AJUSTE lo llevan).
--   ck_mcc_ajuste_ref (V28)                      sin ajuste.
--   uk_mcc_pago_tipo (V20)                       (pago_id, 'NC') es unico: un pago a cuenta no se acredita dos veces.
--   fk_mcc_pago y fk_mcc_comprobante (V29)       el pago y la NC son de la misma ferreteria.
-- Supuesto validado en MigracionV39Test (elRenglonNcEntraEnElLibro).
--
-- imputacion.nc_origen_id: el comprobante NOTA_CREDITO cuyo credito se imputa. FK compuesta (ferreteria_id, nc_origen_id) a
-- comprobante (ferreteria_id, id) (patron V29, por uk_comprobante_ferreteria_y_id) con su indice
-- ix_imputacion_ferreteria_nc_origen: una NC de otra ferreteria no se imputa ni por error. Sin acciones ON DELETE ni ON
-- UPDATE (un CHECK no puede usar columnas con acciones referenciales). Que el origen sea una NOTA_CREDITO, y de la venta del
-- mismo cliente, lo controla el codigo: un CHECK no puede mirar otra fila.
--
-- ck_imputacion_origen (V28) pasa a «exactamente uno de recibo_id, ajuste_origen_id y nc_origen_id»: DROP del viejo y ADD de
-- ck_imputacion_origen_v39, con nombre NUEVO y en UNA sola ALTER TABLE junto con la columna, la FK y los unicos (como V21:
-- MySQL no garantiza que un DROP y un ADD del mismo nombre convivan en una sentencia). Es atomica: la tabla nunca queda sin
-- CHECK de origen. Las filas existentes tienen nc_origen_id NULL y exactamente uno de los otros dos: lo cumplen.
-- ck_imputacion_destino no cambia: el destino de una NC es, como el de los otros, un comprobante o un Recargo.
--
-- Unicos por par, como los de V28 (sin ferreteria_id: el id de la NC ya es de una sola ferreteria): una NC imputa una sola
-- vez a cada comprobante (uk_imputacion_nc_comprobante) y a cada Recargo (uk_imputacion_nc_ajuste). Las imputaciones de una
-- NC no se dan de baja (vigente queda en true): la NC no se anula.
--
-- Rollback: backend/src/test/resources/clientes/rollback-v39-nc-cuenta-corriente.sql, SOLO sin NC en la cuenta corriente (se
-- niega con un solo renglon NC en el libro o una sola imputacion con nc_origen_id; con alguno se conserva todo).

ALTER TABLE imputacion
    ADD COLUMN nc_origen_id BIGINT NULL AFTER ajuste_origen_id,
    ADD INDEX ix_imputacion_ferreteria_nc_origen (ferreteria_id, nc_origen_id),
    ADD CONSTRAINT fk_imputacion_nc_origen FOREIGN KEY (ferreteria_id, nc_origen_id) REFERENCES comprobante (ferreteria_id, id),
    ADD CONSTRAINT uk_imputacion_nc_comprobante UNIQUE (nc_origen_id, comprobante_id),
    ADD CONSTRAINT uk_imputacion_nc_ajuste      UNIQUE (nc_origen_id, ajuste_destino_id),
    DROP CHECK ck_imputacion_origen,
    ADD CONSTRAINT ck_imputacion_origen_v39 CHECK (
        (recibo_id IS NOT NULL) + (ajuste_origen_id IS NOT NULL) + (nc_origen_id IS NOT NULL) = 1);
