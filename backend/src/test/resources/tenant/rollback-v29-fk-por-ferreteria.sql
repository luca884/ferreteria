-- Rollback de las FK por ferreteria: saca la V29 y deja las FK y los indices como en la V28 (FK de negocio solo por id).
-- Siempre se puede correr: solo afloja restricciones y ninguna fila cambia. En prod, revertir el codigo solo no rompe nada
-- (ddl-auto=validate no mira FK); este script hace falta solo para volver a migraciones anteriores con sus propios rollbacks,
-- que nombran FK e indices de la V28. En dev/test, con los datos demo (out-of-order), correrlo ANTES de levantar el codigo
-- anterior. Primero las tablas hijas y al final los UNIQUE (ferreteria_id, id) de los padres, que las FK nuevas necesitan.
ALTER TABLE ajuste_cuenta
    DROP FOREIGN KEY fk_ajuste_cuenta_cliente,
    DROP FOREIGN KEY fk_ajuste_cuenta_usuario;
ALTER TABLE ajuste_cuenta
    ADD CONSTRAINT fk_ajuste_cuenta_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id),
    ADD CONSTRAINT fk_ajuste_cuenta_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    DROP INDEX ix_ajuste_cuenta_ferreteria_usuario;

ALTER TABLE articulo
    DROP FOREIGN KEY fk_articulo_proveedor,
    DROP FOREIGN KEY fk_articulo_rubro,
    DROP FOREIGN KEY fk_articulo_ultima_importacion;
ALTER TABLE articulo
    ADD CONSTRAINT fk_articulo_proveedor FOREIGN KEY (proveedor_id) REFERENCES proveedor (id),
    ADD CONSTRAINT fk_articulo_rubro FOREIGN KEY (rubro_id) REFERENCES rubro (id),
    ADD CONSTRAINT fk_articulo_ultima_importacion FOREIGN KEY (ultima_importacion_id) REFERENCES importacion_lista (id);

ALTER TABLE caja
    DROP FOREIGN KEY fk_caja_usuario_apertura,
    DROP FOREIGN KEY fk_caja_usuario_cierre;
ALTER TABLE caja
    ADD CONSTRAINT fk_caja_usuario_apertura FOREIGN KEY (usuario_apertura_id) REFERENCES usuario (id),
    ADD CONSTRAINT fk_caja_usuario_cierre FOREIGN KEY (usuario_cierre_id) REFERENCES usuario (id),
    DROP INDEX ix_caja_ferreteria_usuario_apertura,
    DROP INDEX ix_caja_ferreteria_usuario_cierre;

ALTER TABLE compra
    DROP FOREIGN KEY fk_compra_proveedor,
    DROP FOREIGN KEY fk_compra_usuario;
ALTER TABLE compra
    ADD CONSTRAINT fk_compra_proveedor FOREIGN KEY (proveedor_id) REFERENCES proveedor (id),
    ADD CONSTRAINT fk_compra_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    DROP INDEX ix_compra_ferreteria_usuario;

ALTER TABLE comprobante
    DROP FOREIGN KEY fk_comprobante_caja,
    DROP FOREIGN KEY fk_comprobante_cliente,
    DROP FOREIGN KEY fk_comprobante_presupuesto,
    DROP FOREIGN KEY fk_comprobante_remito,
    DROP FOREIGN KEY fk_comprobante_usuario;
ALTER TABLE comprobante
    ADD CONSTRAINT fk_comprobante_caja FOREIGN KEY (caja_id) REFERENCES caja (id),
    ADD CONSTRAINT fk_comprobante_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id),
    ADD CONSTRAINT fk_comprobante_presupuesto FOREIGN KEY (presupuesto_id) REFERENCES presupuesto (id),
    ADD CONSTRAINT fk_comprobante_remito FOREIGN KEY (remito_id) REFERENCES remito (id),
    ADD CONSTRAINT fk_comprobante_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    DROP INDEX ix_comprobante_ferreteria_cliente,
    DROP INDEX ix_comprobante_ferreteria_presupuesto,
    DROP INDEX ix_comprobante_ferreteria_remito,
    DROP INDEX ix_comprobante_ferreteria_usuario;

ALTER TABLE historial_precio
    DROP FOREIGN KEY fk_historial_articulo,
    DROP FOREIGN KEY fk_historial_usuario;
ALTER TABLE historial_precio
    ADD CONSTRAINT fk_historial_articulo FOREIGN KEY (articulo_id) REFERENCES articulo (id),
    ADD CONSTRAINT fk_historial_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    DROP INDEX ix_historial_precio_ferreteria_usuario;

ALTER TABLE importacion_lista
    DROP FOREIGN KEY fk_importacion_proveedor,
    DROP FOREIGN KEY fk_importacion_usuario;
ALTER TABLE importacion_lista
    ADD CONSTRAINT fk_importacion_proveedor FOREIGN KEY (proveedor_id) REFERENCES proveedor (id),
    ADD CONSTRAINT fk_importacion_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    DROP INDEX ix_importacion_lista_ferreteria_usuario;

ALTER TABLE imputacion
    DROP FOREIGN KEY fk_imputacion_ajuste_destino,
    DROP FOREIGN KEY fk_imputacion_ajuste_origen,
    DROP FOREIGN KEY fk_imputacion_cliente,
    DROP FOREIGN KEY fk_imputacion_comprobante,
    DROP FOREIGN KEY fk_imputacion_recibo;
ALTER TABLE imputacion
    ADD CONSTRAINT fk_imputacion_ajuste_destino FOREIGN KEY (ajuste_destino_id) REFERENCES ajuste_cuenta (id),
    ADD CONSTRAINT fk_imputacion_ajuste_origen FOREIGN KEY (ajuste_origen_id) REFERENCES ajuste_cuenta (id),
    ADD CONSTRAINT fk_imputacion_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id),
    ADD CONSTRAINT fk_imputacion_comprobante FOREIGN KEY (comprobante_id) REFERENCES comprobante (id),
    ADD CONSTRAINT fk_imputacion_recibo FOREIGN KEY (recibo_id) REFERENCES recibo (id),
    DROP INDEX ix_imputacion_ferreteria_ajuste_destino,
    DROP INDEX ix_imputacion_ferreteria_ajuste_origen,
    DROP INDEX ix_imputacion_ferreteria_comprobante,
    DROP INDEX ix_imputacion_ferreteria_recibo;

ALTER TABLE item_compra
    DROP FOREIGN KEY fk_item_compra_articulo,
    DROP FOREIGN KEY fk_item_compra_compra;
ALTER TABLE item_compra
    ADD CONSTRAINT fk_item_compra_articulo FOREIGN KEY (articulo_id) REFERENCES articulo (id),
    ADD CONSTRAINT fk_item_compra_compra FOREIGN KEY (compra_id) REFERENCES compra (id),
    DROP INDEX ix_item_compra_ferreteria_articulo;

ALTER TABLE item_comprobante
    DROP FOREIGN KEY fk_item_comprobante_articulo,
    DROP FOREIGN KEY fk_item_comprobante_comprobante;
ALTER TABLE item_comprobante
    ADD CONSTRAINT fk_item_comprobante_articulo FOREIGN KEY (articulo_id) REFERENCES articulo (id),
    ADD CONSTRAINT fk_item_comprobante_comprobante FOREIGN KEY (comprobante_id) REFERENCES comprobante (id),
    DROP INDEX ix_item_comprobante_ferreteria_articulo;

ALTER TABLE item_presupuesto
    DROP FOREIGN KEY fk_item_presupuesto_articulo,
    DROP FOREIGN KEY fk_item_presupuesto_presupuesto;
ALTER TABLE item_presupuesto
    ADD CONSTRAINT fk_item_presupuesto_articulo FOREIGN KEY (articulo_id) REFERENCES articulo (id),
    ADD CONSTRAINT fk_item_presupuesto_presupuesto FOREIGN KEY (presupuesto_id) REFERENCES presupuesto (id),
    DROP INDEX ix_item_presupuesto_ferreteria_articulo;

ALTER TABLE item_remito
    DROP FOREIGN KEY fk_item_remito_articulo,
    DROP FOREIGN KEY fk_item_remito_remito;
ALTER TABLE item_remito
    ADD CONSTRAINT fk_item_remito_articulo FOREIGN KEY (articulo_id) REFERENCES articulo (id),
    ADD CONSTRAINT fk_item_remito_remito FOREIGN KEY (remito_id) REFERENCES remito (id),
    DROP INDEX ix_item_remito_ferreteria_articulo;

ALTER TABLE movimiento_caja
    DROP FOREIGN KEY fk_movimiento_caja_caja,
    DROP FOREIGN KEY fk_movimiento_caja_usuario;
ALTER TABLE movimiento_caja
    ADD CONSTRAINT fk_movimiento_caja_caja FOREIGN KEY (caja_id) REFERENCES caja (id),
    ADD CONSTRAINT fk_movimiento_caja_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    DROP INDEX ix_movimiento_caja_ferreteria_usuario;

ALTER TABLE movimiento_cuenta_cliente
    DROP FOREIGN KEY fk_mcc_ajuste,
    DROP FOREIGN KEY fk_mcc_autorizado,
    DROP FOREIGN KEY fk_mcc_cliente,
    DROP FOREIGN KEY fk_mcc_comprobante,
    DROP FOREIGN KEY fk_mcc_pago,
    DROP FOREIGN KEY fk_mcc_recibo,
    DROP FOREIGN KEY fk_mcc_usuario;
ALTER TABLE movimiento_cuenta_cliente
    ADD CONSTRAINT fk_mcc_ajuste FOREIGN KEY (ajuste_id) REFERENCES ajuste_cuenta (id),
    ADD CONSTRAINT fk_mcc_autorizado FOREIGN KEY (autorizado_por) REFERENCES usuario (id),
    ADD CONSTRAINT fk_mcc_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id),
    ADD CONSTRAINT fk_mcc_comprobante FOREIGN KEY (comprobante_id) REFERENCES comprobante (id),
    ADD CONSTRAINT fk_mcc_pago FOREIGN KEY (pago_id) REFERENCES pago (id),
    ADD CONSTRAINT fk_mcc_recibo FOREIGN KEY (recibo_id) REFERENCES recibo (id),
    ADD CONSTRAINT fk_mcc_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    DROP INDEX ix_movimiento_cuenta_cliente_ferreteria_ajuste,
    DROP INDEX ix_movimiento_cuenta_cliente_ferreteria_autorizado_por,
    DROP INDEX ix_movimiento_cuenta_cliente_ferreteria_comprobante,
    DROP INDEX ix_movimiento_cuenta_cliente_ferreteria_pago,
    DROP INDEX ix_movimiento_cuenta_cliente_ferreteria_recibo,
    DROP INDEX ix_movimiento_cuenta_cliente_ferreteria_usuario;

ALTER TABLE movimiento_stock
    DROP FOREIGN KEY fk_movimiento_articulo,
    DROP FOREIGN KEY fk_movimiento_compra,
    DROP FOREIGN KEY fk_movimiento_comprobante,
    DROP FOREIGN KEY fk_movimiento_remito,
    DROP FOREIGN KEY fk_movimiento_usuario;
ALTER TABLE movimiento_stock
    ADD CONSTRAINT fk_movimiento_articulo FOREIGN KEY (articulo_id) REFERENCES articulo (id),
    ADD CONSTRAINT fk_movimiento_compra FOREIGN KEY (compra_id) REFERENCES compra (id),
    ADD CONSTRAINT fk_movimiento_comprobante FOREIGN KEY (comprobante_id) REFERENCES comprobante (id),
    ADD CONSTRAINT fk_movimiento_remito FOREIGN KEY (remito_id) REFERENCES remito (id),
    ADD CONSTRAINT fk_movimiento_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    DROP INDEX ix_movimiento_stock_ferreteria_compra,
    DROP INDEX ix_movimiento_stock_ferreteria_comprobante,
    DROP INDEX ix_movimiento_stock_ferreteria_remito,
    DROP INDEX ix_movimiento_stock_ferreteria_usuario;

ALTER TABLE pago
    DROP FOREIGN KEY fk_pago_comprobante,
    DROP FOREIGN KEY fk_pago_forma_pago;
ALTER TABLE pago
    ADD CONSTRAINT fk_pago_comprobante FOREIGN KEY (comprobante_id) REFERENCES comprobante (id),
    ADD CONSTRAINT fk_pago_forma_pago FOREIGN KEY (forma_pago_id) REFERENCES forma_pago (id);

ALTER TABLE presupuesto
    DROP FOREIGN KEY fk_presupuesto_cliente,
    DROP FOREIGN KEY fk_presupuesto_usuario;
ALTER TABLE presupuesto
    ADD CONSTRAINT fk_presupuesto_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id),
    ADD CONSTRAINT fk_presupuesto_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    DROP INDEX ix_presupuesto_ferreteria_cliente,
    DROP INDEX ix_presupuesto_ferreteria_usuario;

ALTER TABLE recibo
    DROP FOREIGN KEY fk_recibo_caja,
    DROP FOREIGN KEY fk_recibo_caja_anulacion,
    DROP FOREIGN KEY fk_recibo_cliente,
    DROP FOREIGN KEY fk_recibo_usuario,
    DROP FOREIGN KEY fk_recibo_usuario_anulacion;
ALTER TABLE recibo
    ADD CONSTRAINT fk_recibo_caja FOREIGN KEY (caja_id) REFERENCES caja (id),
    ADD CONSTRAINT fk_recibo_caja_anulacion FOREIGN KEY (caja_anulacion_id) REFERENCES caja (id),
    ADD CONSTRAINT fk_recibo_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id),
    ADD CONSTRAINT fk_recibo_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    ADD CONSTRAINT fk_recibo_usuario_anulacion FOREIGN KEY (usuario_anulacion_id) REFERENCES usuario (id),
    DROP INDEX ix_recibo_ferreteria_usuario,
    DROP INDEX ix_recibo_ferreteria_usuario_anulacion;

ALTER TABLE remito
    DROP FOREIGN KEY fk_remito_cliente,
    DROP FOREIGN KEY fk_remito_usuario;
ALTER TABLE remito
    ADD CONSTRAINT fk_remito_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id),
    ADD CONSTRAINT fk_remito_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    DROP INDEX ix_remito_ferreteria_cliente,
    DROP INDEX ix_remito_ferreteria_usuario;

ALTER TABLE ajuste_cuenta DROP INDEX uk_ajuste_cuenta_ferreteria_y_id;
ALTER TABLE articulo DROP INDEX uk_articulo_ferreteria_y_id;
ALTER TABLE caja DROP INDEX uk_caja_ferreteria_y_id;
ALTER TABLE cliente DROP INDEX uk_cliente_ferreteria_y_id;
ALTER TABLE compra DROP INDEX uk_compra_ferreteria_y_id;
ALTER TABLE comprobante DROP INDEX uk_comprobante_ferreteria_y_id;
ALTER TABLE forma_pago DROP INDEX uk_forma_pago_ferreteria_y_id;
ALTER TABLE importacion_lista DROP INDEX uk_importacion_lista_ferreteria_y_id;
ALTER TABLE pago DROP INDEX uk_pago_ferreteria_y_id;
ALTER TABLE presupuesto DROP INDEX uk_presupuesto_ferreteria_y_id;
ALTER TABLE proveedor DROP INDEX uk_proveedor_ferreteria_y_id;
ALTER TABLE recibo DROP INDEX uk_recibo_ferreteria_y_id;
ALTER TABLE remito DROP INDEX uk_remito_ferreteria_y_id;
ALTER TABLE rubro DROP INDEX uk_rubro_ferreteria_y_id;
ALTER TABLE usuario DROP INDEX uk_usuario_ferreteria_y_id;
DELETE FROM flyway_schema_history WHERE version = '29';
