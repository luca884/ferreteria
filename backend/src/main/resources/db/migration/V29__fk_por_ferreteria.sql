-- FK por ferreteria: toda FK entre tablas de negocio pasa a ser (ferreteria_id, x_id) contra (ferreteria_id, id) del padre.
-- Asi la base rechaza sola que una fila de una ferreteria apunte a una fila de otra (antes solo lo frenaba el codigo). Un x_id
-- NULL no se chequea, como antes. Un usuario SUPER_ADMIN (ferreteria_id NULL) no puede quedar en datos de negocio.
-- Solo cambia restricciones e indices: ninguna fila cambia. Las FK conservan su nombre (los rollbacks anteriores las nombran).
--
-- Guarda: antes de tocar el esquema cuenta las FK con alguna fila que apunte a otra ferreteria (o a un SUPER_ADMIN). Si hay
-- alguna, el CHECK de la tabla temporal se viola (ck_v29_sin_referencias_a_otra_ferreteria) y la migracion se corta sin
-- cambiar nada. Hay que corregir esas filas, sacar la V29 fallida del historial (flyway repair) y volver a levantar.
--
-- MySQL no deja soltar y volver a crear una FK con el mismo nombre en un solo ALTER, asi que cada tabla lleva dos: el primero
-- suelta sus FK y el segundo crea los indices (ferreteria_id, x_id) que falten y las FK nuevas. El indice viejo de x_id se
-- queda: lo usan las consultas que van del padre a los hijos sin ferreteria_id (por ejemplo, los renglones de una venta).
CREATE TEMPORARY TABLE guarda_v29 (
    referencias_a_otra_ferreteria INT NOT NULL,
    CONSTRAINT ck_v29_sin_referencias_a_otra_ferreteria CHECK (referencias_a_otra_ferreteria = 0)
);

INSERT INTO guarda_v29 (referencias_a_otra_ferreteria)
SELECT
      EXISTS (SELECT 1 FROM ajuste_cuenta h JOIN cliente p ON p.id = h.cliente_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM ajuste_cuenta h JOIN usuario p ON p.id = h.usuario_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM articulo h JOIN proveedor p ON p.id = h.proveedor_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM articulo h JOIN rubro p ON p.id = h.rubro_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM articulo h JOIN importacion_lista p ON p.id = h.ultima_importacion_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM caja h JOIN usuario p ON p.id = h.usuario_apertura_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM caja h JOIN usuario p ON p.id = h.usuario_cierre_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM compra h JOIN proveedor p ON p.id = h.proveedor_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM compra h JOIN usuario p ON p.id = h.usuario_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM comprobante h JOIN caja p ON p.id = h.caja_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM comprobante h JOIN cliente p ON p.id = h.cliente_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM comprobante h JOIN presupuesto p ON p.id = h.presupuesto_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM comprobante h JOIN remito p ON p.id = h.remito_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM comprobante h JOIN usuario p ON p.id = h.usuario_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM historial_precio h JOIN articulo p ON p.id = h.articulo_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM historial_precio h JOIN usuario p ON p.id = h.usuario_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM importacion_lista h JOIN proveedor p ON p.id = h.proveedor_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM importacion_lista h JOIN usuario p ON p.id = h.usuario_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM imputacion h JOIN ajuste_cuenta p ON p.id = h.ajuste_destino_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM imputacion h JOIN ajuste_cuenta p ON p.id = h.ajuste_origen_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM imputacion h JOIN cliente p ON p.id = h.cliente_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM imputacion h JOIN comprobante p ON p.id = h.comprobante_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM imputacion h JOIN recibo p ON p.id = h.recibo_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM item_compra h JOIN articulo p ON p.id = h.articulo_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM item_compra h JOIN compra p ON p.id = h.compra_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM item_comprobante h JOIN articulo p ON p.id = h.articulo_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM item_comprobante h JOIN comprobante p ON p.id = h.comprobante_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM item_presupuesto h JOIN articulo p ON p.id = h.articulo_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM item_presupuesto h JOIN presupuesto p ON p.id = h.presupuesto_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM item_remito h JOIN articulo p ON p.id = h.articulo_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM item_remito h JOIN remito p ON p.id = h.remito_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_caja h JOIN caja p ON p.id = h.caja_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_caja h JOIN usuario p ON p.id = h.usuario_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_cuenta_cliente h JOIN ajuste_cuenta p ON p.id = h.ajuste_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_cuenta_cliente h JOIN usuario p ON p.id = h.autorizado_por WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_cuenta_cliente h JOIN cliente p ON p.id = h.cliente_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_cuenta_cliente h JOIN comprobante p ON p.id = h.comprobante_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_cuenta_cliente h JOIN pago p ON p.id = h.pago_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_cuenta_cliente h JOIN recibo p ON p.id = h.recibo_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_cuenta_cliente h JOIN usuario p ON p.id = h.usuario_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_stock h JOIN articulo p ON p.id = h.articulo_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_stock h JOIN compra p ON p.id = h.compra_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_stock h JOIN comprobante p ON p.id = h.comprobante_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_stock h JOIN remito p ON p.id = h.remito_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM movimiento_stock h JOIN usuario p ON p.id = h.usuario_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM pago h JOIN comprobante p ON p.id = h.comprobante_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM pago h JOIN forma_pago p ON p.id = h.forma_pago_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM presupuesto h JOIN cliente p ON p.id = h.cliente_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM presupuesto h JOIN usuario p ON p.id = h.usuario_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM recibo h JOIN caja p ON p.id = h.caja_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM recibo h JOIN caja p ON p.id = h.caja_anulacion_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM recibo h JOIN cliente p ON p.id = h.cliente_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM recibo h JOIN usuario p ON p.id = h.usuario_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM recibo h JOIN usuario p ON p.id = h.usuario_anulacion_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM remito h JOIN cliente p ON p.id = h.cliente_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id))
    + EXISTS (SELECT 1 FROM remito h JOIN usuario p ON p.id = h.usuario_id WHERE NOT (p.ferreteria_id <=> h.ferreteria_id));

DROP TEMPORARY TABLE guarda_v29;

ALTER TABLE ajuste_cuenta ADD CONSTRAINT uk_ajuste_cuenta_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE articulo ADD CONSTRAINT uk_articulo_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE caja ADD CONSTRAINT uk_caja_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE cliente ADD CONSTRAINT uk_cliente_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE compra ADD CONSTRAINT uk_compra_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE comprobante ADD CONSTRAINT uk_comprobante_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE forma_pago ADD CONSTRAINT uk_forma_pago_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE importacion_lista ADD CONSTRAINT uk_importacion_lista_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE pago ADD CONSTRAINT uk_pago_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE presupuesto ADD CONSTRAINT uk_presupuesto_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE proveedor ADD CONSTRAINT uk_proveedor_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE recibo ADD CONSTRAINT uk_recibo_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE remito ADD CONSTRAINT uk_remito_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE rubro ADD CONSTRAINT uk_rubro_ferreteria_y_id UNIQUE (ferreteria_id, id);
ALTER TABLE usuario ADD CONSTRAINT uk_usuario_ferreteria_y_id UNIQUE (ferreteria_id, id);

ALTER TABLE ajuste_cuenta
    DROP FOREIGN KEY fk_ajuste_cuenta_cliente,
    DROP FOREIGN KEY fk_ajuste_cuenta_usuario;
ALTER TABLE ajuste_cuenta
    ADD INDEX ix_ajuste_cuenta_ferreteria_usuario (ferreteria_id, usuario_id),
    ADD CONSTRAINT fk_ajuste_cuenta_cliente FOREIGN KEY (ferreteria_id, cliente_id) REFERENCES cliente (ferreteria_id, id),
    ADD CONSTRAINT fk_ajuste_cuenta_usuario FOREIGN KEY (ferreteria_id, usuario_id) REFERENCES usuario (ferreteria_id, id);

ALTER TABLE articulo
    DROP FOREIGN KEY fk_articulo_proveedor,
    DROP FOREIGN KEY fk_articulo_rubro,
    DROP FOREIGN KEY fk_articulo_ultima_importacion;
ALTER TABLE articulo
    ADD CONSTRAINT fk_articulo_proveedor FOREIGN KEY (ferreteria_id, proveedor_id) REFERENCES proveedor (ferreteria_id, id),
    ADD CONSTRAINT fk_articulo_rubro FOREIGN KEY (ferreteria_id, rubro_id) REFERENCES rubro (ferreteria_id, id),
    ADD CONSTRAINT fk_articulo_ultima_importacion FOREIGN KEY (ferreteria_id, ultima_importacion_id) REFERENCES importacion_lista (ferreteria_id, id);

ALTER TABLE caja
    DROP FOREIGN KEY fk_caja_usuario_apertura,
    DROP FOREIGN KEY fk_caja_usuario_cierre;
ALTER TABLE caja
    ADD INDEX ix_caja_ferreteria_usuario_apertura (ferreteria_id, usuario_apertura_id),
    ADD INDEX ix_caja_ferreteria_usuario_cierre (ferreteria_id, usuario_cierre_id),
    ADD CONSTRAINT fk_caja_usuario_apertura FOREIGN KEY (ferreteria_id, usuario_apertura_id) REFERENCES usuario (ferreteria_id, id),
    ADD CONSTRAINT fk_caja_usuario_cierre FOREIGN KEY (ferreteria_id, usuario_cierre_id) REFERENCES usuario (ferreteria_id, id);

ALTER TABLE compra
    DROP FOREIGN KEY fk_compra_proveedor,
    DROP FOREIGN KEY fk_compra_usuario;
ALTER TABLE compra
    ADD INDEX ix_compra_ferreteria_usuario (ferreteria_id, usuario_id),
    ADD CONSTRAINT fk_compra_proveedor FOREIGN KEY (ferreteria_id, proveedor_id) REFERENCES proveedor (ferreteria_id, id),
    ADD CONSTRAINT fk_compra_usuario FOREIGN KEY (ferreteria_id, usuario_id) REFERENCES usuario (ferreteria_id, id);

ALTER TABLE comprobante
    DROP FOREIGN KEY fk_comprobante_caja,
    DROP FOREIGN KEY fk_comprobante_cliente,
    DROP FOREIGN KEY fk_comprobante_presupuesto,
    DROP FOREIGN KEY fk_comprobante_remito,
    DROP FOREIGN KEY fk_comprobante_usuario;
ALTER TABLE comprobante
    ADD INDEX ix_comprobante_ferreteria_cliente (ferreteria_id, cliente_id),
    ADD INDEX ix_comprobante_ferreteria_presupuesto (ferreteria_id, presupuesto_id),
    ADD INDEX ix_comprobante_ferreteria_remito (ferreteria_id, remito_id),
    ADD INDEX ix_comprobante_ferreteria_usuario (ferreteria_id, usuario_id),
    ADD CONSTRAINT fk_comprobante_caja FOREIGN KEY (ferreteria_id, caja_id) REFERENCES caja (ferreteria_id, id),
    ADD CONSTRAINT fk_comprobante_cliente FOREIGN KEY (ferreteria_id, cliente_id) REFERENCES cliente (ferreteria_id, id),
    ADD CONSTRAINT fk_comprobante_presupuesto FOREIGN KEY (ferreteria_id, presupuesto_id) REFERENCES presupuesto (ferreteria_id, id),
    ADD CONSTRAINT fk_comprobante_remito FOREIGN KEY (ferreteria_id, remito_id) REFERENCES remito (ferreteria_id, id),
    ADD CONSTRAINT fk_comprobante_usuario FOREIGN KEY (ferreteria_id, usuario_id) REFERENCES usuario (ferreteria_id, id);

ALTER TABLE historial_precio
    DROP FOREIGN KEY fk_historial_articulo,
    DROP FOREIGN KEY fk_historial_usuario;
ALTER TABLE historial_precio
    ADD INDEX ix_historial_precio_ferreteria_usuario (ferreteria_id, usuario_id),
    ADD CONSTRAINT fk_historial_articulo FOREIGN KEY (ferreteria_id, articulo_id) REFERENCES articulo (ferreteria_id, id),
    ADD CONSTRAINT fk_historial_usuario FOREIGN KEY (ferreteria_id, usuario_id) REFERENCES usuario (ferreteria_id, id);

ALTER TABLE importacion_lista
    DROP FOREIGN KEY fk_importacion_proveedor,
    DROP FOREIGN KEY fk_importacion_usuario;
ALTER TABLE importacion_lista
    ADD INDEX ix_importacion_lista_ferreteria_usuario (ferreteria_id, usuario_id),
    ADD CONSTRAINT fk_importacion_proveedor FOREIGN KEY (ferreteria_id, proveedor_id) REFERENCES proveedor (ferreteria_id, id),
    ADD CONSTRAINT fk_importacion_usuario FOREIGN KEY (ferreteria_id, usuario_id) REFERENCES usuario (ferreteria_id, id);

ALTER TABLE imputacion
    DROP FOREIGN KEY fk_imputacion_ajuste_destino,
    DROP FOREIGN KEY fk_imputacion_ajuste_origen,
    DROP FOREIGN KEY fk_imputacion_cliente,
    DROP FOREIGN KEY fk_imputacion_comprobante,
    DROP FOREIGN KEY fk_imputacion_recibo;
ALTER TABLE imputacion
    ADD INDEX ix_imputacion_ferreteria_ajuste_destino (ferreteria_id, ajuste_destino_id),
    ADD INDEX ix_imputacion_ferreteria_ajuste_origen (ferreteria_id, ajuste_origen_id),
    ADD INDEX ix_imputacion_ferreteria_comprobante (ferreteria_id, comprobante_id),
    ADD INDEX ix_imputacion_ferreteria_recibo (ferreteria_id, recibo_id),
    ADD CONSTRAINT fk_imputacion_ajuste_destino FOREIGN KEY (ferreteria_id, ajuste_destino_id) REFERENCES ajuste_cuenta (ferreteria_id, id),
    ADD CONSTRAINT fk_imputacion_ajuste_origen FOREIGN KEY (ferreteria_id, ajuste_origen_id) REFERENCES ajuste_cuenta (ferreteria_id, id),
    ADD CONSTRAINT fk_imputacion_cliente FOREIGN KEY (ferreteria_id, cliente_id) REFERENCES cliente (ferreteria_id, id),
    ADD CONSTRAINT fk_imputacion_comprobante FOREIGN KEY (ferreteria_id, comprobante_id) REFERENCES comprobante (ferreteria_id, id),
    ADD CONSTRAINT fk_imputacion_recibo FOREIGN KEY (ferreteria_id, recibo_id) REFERENCES recibo (ferreteria_id, id);

ALTER TABLE item_compra
    DROP FOREIGN KEY fk_item_compra_articulo,
    DROP FOREIGN KEY fk_item_compra_compra;
ALTER TABLE item_compra
    ADD INDEX ix_item_compra_ferreteria_articulo (ferreteria_id, articulo_id),
    ADD CONSTRAINT fk_item_compra_articulo FOREIGN KEY (ferreteria_id, articulo_id) REFERENCES articulo (ferreteria_id, id),
    ADD CONSTRAINT fk_item_compra_compra FOREIGN KEY (ferreteria_id, compra_id) REFERENCES compra (ferreteria_id, id);

ALTER TABLE item_comprobante
    DROP FOREIGN KEY fk_item_comprobante_articulo,
    DROP FOREIGN KEY fk_item_comprobante_comprobante;
ALTER TABLE item_comprobante
    ADD INDEX ix_item_comprobante_ferreteria_articulo (ferreteria_id, articulo_id),
    ADD CONSTRAINT fk_item_comprobante_articulo FOREIGN KEY (ferreteria_id, articulo_id) REFERENCES articulo (ferreteria_id, id),
    ADD CONSTRAINT fk_item_comprobante_comprobante FOREIGN KEY (ferreteria_id, comprobante_id) REFERENCES comprobante (ferreteria_id, id);

ALTER TABLE item_presupuesto
    DROP FOREIGN KEY fk_item_presupuesto_articulo,
    DROP FOREIGN KEY fk_item_presupuesto_presupuesto;
ALTER TABLE item_presupuesto
    ADD INDEX ix_item_presupuesto_ferreteria_articulo (ferreteria_id, articulo_id),
    ADD CONSTRAINT fk_item_presupuesto_articulo FOREIGN KEY (ferreteria_id, articulo_id) REFERENCES articulo (ferreteria_id, id),
    ADD CONSTRAINT fk_item_presupuesto_presupuesto FOREIGN KEY (ferreteria_id, presupuesto_id) REFERENCES presupuesto (ferreteria_id, id);

ALTER TABLE item_remito
    DROP FOREIGN KEY fk_item_remito_articulo,
    DROP FOREIGN KEY fk_item_remito_remito;
ALTER TABLE item_remito
    ADD INDEX ix_item_remito_ferreteria_articulo (ferreteria_id, articulo_id),
    ADD CONSTRAINT fk_item_remito_articulo FOREIGN KEY (ferreteria_id, articulo_id) REFERENCES articulo (ferreteria_id, id),
    ADD CONSTRAINT fk_item_remito_remito FOREIGN KEY (ferreteria_id, remito_id) REFERENCES remito (ferreteria_id, id);

ALTER TABLE movimiento_caja
    DROP FOREIGN KEY fk_movimiento_caja_caja,
    DROP FOREIGN KEY fk_movimiento_caja_usuario;
ALTER TABLE movimiento_caja
    ADD INDEX ix_movimiento_caja_ferreteria_usuario (ferreteria_id, usuario_id),
    ADD CONSTRAINT fk_movimiento_caja_caja FOREIGN KEY (ferreteria_id, caja_id) REFERENCES caja (ferreteria_id, id),
    ADD CONSTRAINT fk_movimiento_caja_usuario FOREIGN KEY (ferreteria_id, usuario_id) REFERENCES usuario (ferreteria_id, id);

ALTER TABLE movimiento_cuenta_cliente
    DROP FOREIGN KEY fk_mcc_ajuste,
    DROP FOREIGN KEY fk_mcc_autorizado,
    DROP FOREIGN KEY fk_mcc_cliente,
    DROP FOREIGN KEY fk_mcc_comprobante,
    DROP FOREIGN KEY fk_mcc_pago,
    DROP FOREIGN KEY fk_mcc_recibo,
    DROP FOREIGN KEY fk_mcc_usuario;
ALTER TABLE movimiento_cuenta_cliente
    ADD INDEX ix_movimiento_cuenta_cliente_ferreteria_ajuste (ferreteria_id, ajuste_id),
    ADD INDEX ix_movimiento_cuenta_cliente_ferreteria_autorizado_por (ferreteria_id, autorizado_por),
    ADD INDEX ix_movimiento_cuenta_cliente_ferreteria_comprobante (ferreteria_id, comprobante_id),
    ADD INDEX ix_movimiento_cuenta_cliente_ferreteria_pago (ferreteria_id, pago_id),
    ADD INDEX ix_movimiento_cuenta_cliente_ferreteria_recibo (ferreteria_id, recibo_id),
    ADD INDEX ix_movimiento_cuenta_cliente_ferreteria_usuario (ferreteria_id, usuario_id),
    ADD CONSTRAINT fk_mcc_ajuste FOREIGN KEY (ferreteria_id, ajuste_id) REFERENCES ajuste_cuenta (ferreteria_id, id),
    ADD CONSTRAINT fk_mcc_autorizado FOREIGN KEY (ferreteria_id, autorizado_por) REFERENCES usuario (ferreteria_id, id),
    ADD CONSTRAINT fk_mcc_cliente FOREIGN KEY (ferreteria_id, cliente_id) REFERENCES cliente (ferreteria_id, id),
    ADD CONSTRAINT fk_mcc_comprobante FOREIGN KEY (ferreteria_id, comprobante_id) REFERENCES comprobante (ferreteria_id, id),
    ADD CONSTRAINT fk_mcc_pago FOREIGN KEY (ferreteria_id, pago_id) REFERENCES pago (ferreteria_id, id),
    ADD CONSTRAINT fk_mcc_recibo FOREIGN KEY (ferreteria_id, recibo_id) REFERENCES recibo (ferreteria_id, id),
    ADD CONSTRAINT fk_mcc_usuario FOREIGN KEY (ferreteria_id, usuario_id) REFERENCES usuario (ferreteria_id, id);

ALTER TABLE movimiento_stock
    DROP FOREIGN KEY fk_movimiento_articulo,
    DROP FOREIGN KEY fk_movimiento_compra,
    DROP FOREIGN KEY fk_movimiento_comprobante,
    DROP FOREIGN KEY fk_movimiento_remito,
    DROP FOREIGN KEY fk_movimiento_usuario;
ALTER TABLE movimiento_stock
    ADD INDEX ix_movimiento_stock_ferreteria_compra (ferreteria_id, compra_id),
    ADD INDEX ix_movimiento_stock_ferreteria_comprobante (ferreteria_id, comprobante_id),
    ADD INDEX ix_movimiento_stock_ferreteria_remito (ferreteria_id, remito_id),
    ADD INDEX ix_movimiento_stock_ferreteria_usuario (ferreteria_id, usuario_id),
    ADD CONSTRAINT fk_movimiento_articulo FOREIGN KEY (ferreteria_id, articulo_id) REFERENCES articulo (ferreteria_id, id),
    ADD CONSTRAINT fk_movimiento_compra FOREIGN KEY (ferreteria_id, compra_id) REFERENCES compra (ferreteria_id, id),
    ADD CONSTRAINT fk_movimiento_comprobante FOREIGN KEY (ferreteria_id, comprobante_id) REFERENCES comprobante (ferreteria_id, id),
    ADD CONSTRAINT fk_movimiento_remito FOREIGN KEY (ferreteria_id, remito_id) REFERENCES remito (ferreteria_id, id),
    ADD CONSTRAINT fk_movimiento_usuario FOREIGN KEY (ferreteria_id, usuario_id) REFERENCES usuario (ferreteria_id, id);

ALTER TABLE pago
    DROP FOREIGN KEY fk_pago_comprobante,
    DROP FOREIGN KEY fk_pago_forma_pago;
ALTER TABLE pago
    ADD CONSTRAINT fk_pago_comprobante FOREIGN KEY (ferreteria_id, comprobante_id) REFERENCES comprobante (ferreteria_id, id),
    ADD CONSTRAINT fk_pago_forma_pago FOREIGN KEY (ferreteria_id, forma_pago_id) REFERENCES forma_pago (ferreteria_id, id);

ALTER TABLE presupuesto
    DROP FOREIGN KEY fk_presupuesto_cliente,
    DROP FOREIGN KEY fk_presupuesto_usuario;
ALTER TABLE presupuesto
    ADD INDEX ix_presupuesto_ferreteria_cliente (ferreteria_id, cliente_id),
    ADD INDEX ix_presupuesto_ferreteria_usuario (ferreteria_id, usuario_id),
    ADD CONSTRAINT fk_presupuesto_cliente FOREIGN KEY (ferreteria_id, cliente_id) REFERENCES cliente (ferreteria_id, id),
    ADD CONSTRAINT fk_presupuesto_usuario FOREIGN KEY (ferreteria_id, usuario_id) REFERENCES usuario (ferreteria_id, id);

ALTER TABLE recibo
    DROP FOREIGN KEY fk_recibo_caja,
    DROP FOREIGN KEY fk_recibo_caja_anulacion,
    DROP FOREIGN KEY fk_recibo_cliente,
    DROP FOREIGN KEY fk_recibo_usuario,
    DROP FOREIGN KEY fk_recibo_usuario_anulacion;
ALTER TABLE recibo
    ADD INDEX ix_recibo_ferreteria_usuario (ferreteria_id, usuario_id),
    ADD INDEX ix_recibo_ferreteria_usuario_anulacion (ferreteria_id, usuario_anulacion_id),
    ADD CONSTRAINT fk_recibo_caja FOREIGN KEY (ferreteria_id, caja_id) REFERENCES caja (ferreteria_id, id),
    ADD CONSTRAINT fk_recibo_caja_anulacion FOREIGN KEY (ferreteria_id, caja_anulacion_id) REFERENCES caja (ferreteria_id, id),
    ADD CONSTRAINT fk_recibo_cliente FOREIGN KEY (ferreteria_id, cliente_id) REFERENCES cliente (ferreteria_id, id),
    ADD CONSTRAINT fk_recibo_usuario FOREIGN KEY (ferreteria_id, usuario_id) REFERENCES usuario (ferreteria_id, id),
    ADD CONSTRAINT fk_recibo_usuario_anulacion FOREIGN KEY (ferreteria_id, usuario_anulacion_id) REFERENCES usuario (ferreteria_id, id);

ALTER TABLE remito
    DROP FOREIGN KEY fk_remito_cliente,
    DROP FOREIGN KEY fk_remito_usuario;
ALTER TABLE remito
    ADD INDEX ix_remito_ferreteria_cliente (ferreteria_id, cliente_id),
    ADD INDEX ix_remito_ferreteria_usuario (ferreteria_id, usuario_id),
    ADD CONSTRAINT fk_remito_cliente FOREIGN KEY (ferreteria_id, cliente_id) REFERENCES cliente (ferreteria_id, id),
    ADD CONSTRAINT fk_remito_usuario FOREIGN KEY (ferreteria_id, usuario_id) REFERENCES usuario (ferreteria_id, id);

