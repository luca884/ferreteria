-- Ajustes de cuenta corriente, tramo 3b-2 (GDS-20, G59): el Dueño corrige a mano el saldo de un cliente con un Recargo (sube la
-- deuda) o un Descuento (la baja), y la deuda nueva consume sola el saldo a favor. Nace el ajuste con su numerador, el libro
-- enlaza su AJUSTE con el ajuste, y la imputacion pasa a tener un ORIGEN (un recibo o un Descuento) y un DESTINO (un
-- comprobante o un Recargo).
--
-- Modelo: el Recargo es una PARTIDA pendiente como un comprobante (su pendiente = su importe - Σ imputaciones vigentes); el
-- Descuento y el recibo son CREDITO (lo que no imputan queda «a cuenta»). La invariante Σ pendiente - a cuenta = saldo sigue
-- valiendo: el AJUSTE del libro lleva el Recargo al debe y el Descuento al haber, igual que la VENTA y el COBRO.
--
-- numerador_ajuste_cuenta: copia del molde de numerador_recibo (V21); el UPSERT del numero toma el lock de la fila y serializa
-- los ajustes de la ferreteria.
--
-- ajuste_cuenta es INMUTABLE: ningun UPDATE ni DELETE (un Recargo mal cargado se compensa con un Descuento y al reves). fecha no
-- tiene DEFAULT: la fija el Clock del servicio. clave es la de idempotencia del cliente HTTP: UNIQUE por ferreteria. cliente_id
-- va propio (los pendientes se consultan por cliente sin join); que el cliente sea de la ferreteria lo garantiza el servicio.
-- Sin tope de importe (solo el de la columna): la confirmacion explicita es de la pantalla.
--
-- movimiento_cuenta_cliente.ajuste_id: FK opcional, UNICA (un movimiento por ajuste). ck_mcc_ajuste_ref: solo el AJUSTE lleva
-- ajuste y todo AJUSTE lo lleva. ck_mcc_tipo_v21, ck_mcc_sentido_v21 y ck_mcc_motivo (V21, V22) ya admiten el AJUSTE con motivo,
-- sin recibo y con debe o haber: no se tocan. El ADD CHECK valida toda la tabla: hasta ahora ningun codigo de la aplicacion
-- escribe un AJUSTE, asi que no hay filas que lo rompan (los tests que lo siembran por SQL siembran tambien su ajuste).
--
-- imputacion: recibo_id y comprobante_id pasan a opcionales; nacen ajuste_origen_id (un Descuento) y ajuste_destino_id (un
-- Recargo). CHECK: exactamente un origen y exactamente un destino. Unica por cada par (origen, destino); el UNIQUE de V21
-- (recibo, comprobante) sigue. Las filas existentes no cambian (todas tienen recibo y comprobante). Que el origen sea un Descuento
-- y el destino un Recargo lo valida el codigo. Sigue siendo append-only: la unica mutacion permitida es vigente true -> false al
-- anular el recibo de origen. ix_imputacion_cliente_ajuste cubre la lectura de lo imputado por (cliente, Recargo).
--
-- imputacion.automatica: true cuando la imputacion NO la pidio nadie sino que la creo sola la deuda nueva al consumir credito
-- (una venta a cuenta o un Recargo pagados con el saldo a favor); false para las que pide el recibo o el Descuento. El recibo
-- impreso y la comparacion de una cobranza repetida miran solo las que no son automaticas: lo que un recibo imputo DESPUES
-- (al pagar una venta posterior) no es parte de ese recibo. Las filas existentes quedan en false.
--
-- DROP NOT NULL con MODIFY: MySQL lo admite sobre una columna con FK (la FK queda). Supuesto validado en MigracionV28Test.
--
-- Rollback: backend/src/test/resources/clientes/rollback-v28-ajustes-cuenta-corriente.sql, SOLO sin ajustes cargados (se
-- niega solo si hay alguno: con ajustes se conserva todo).

CREATE TABLE numerador_ajuste_cuenta (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    ferreteria_id  BIGINT NOT NULL,
    ultimo_numero  BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_numerador_ajuste_cuenta_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_numerador_ajuste_cuenta_ferreteria UNIQUE (ferreteria_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ajuste_cuenta (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id  BIGINT         NOT NULL,
    cliente_id     BIGINT         NOT NULL,
    numero         BIGINT         NOT NULL,
    tipo           VARCHAR(10)    NOT NULL,
    importe        DECIMAL(15, 2) NOT NULL,
    motivo         VARCHAR(200)   NOT NULL,
    clave          VARCHAR(36)    NOT NULL,
    fecha          DATETIME(6)    NOT NULL,
    usuario_id     BIGINT         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ajuste_cuenta_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_ajuste_cuenta_cliente    FOREIGN KEY (cliente_id)    REFERENCES cliente (id),
    CONSTRAINT fk_ajuste_cuenta_usuario    FOREIGN KEY (usuario_id)    REFERENCES usuario (id),
    CONSTRAINT uk_ajuste_cuenta_numero     UNIQUE (ferreteria_id, numero),
    CONSTRAINT uk_ajuste_cuenta_clave      UNIQUE (ferreteria_id, clave),
    CONSTRAINT ck_ajuste_cuenta_tipo       CHECK (tipo IN ('RECARGO', 'DESCUENTO')),
    CONSTRAINT ck_ajuste_cuenta_importe    CHECK (importe > 0),
    CONSTRAINT ck_ajuste_cuenta_motivo     CHECK (CHAR_LENGTH(TRIM(motivo)) >= 5),
    INDEX ix_ajuste_cuenta_cliente (ferreteria_id, cliente_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE movimiento_cuenta_cliente
    ADD COLUMN ajuste_id BIGINT NULL,
    ADD CONSTRAINT fk_mcc_ajuste FOREIGN KEY (ajuste_id) REFERENCES ajuste_cuenta (id),
    ADD CONSTRAINT uk_mcc_ajuste UNIQUE (ajuste_id),
    ADD CONSTRAINT ck_mcc_ajuste_ref CHECK ((tipo = 'AJUSTE') = (ajuste_id IS NOT NULL));

ALTER TABLE imputacion
    MODIFY COLUMN recibo_id      BIGINT NULL,
    MODIFY COLUMN comprobante_id BIGINT NULL,
    ADD COLUMN ajuste_origen_id  BIGINT NULL,
    ADD COLUMN ajuste_destino_id BIGINT NULL,
    ADD COLUMN automatica        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT fk_imputacion_ajuste_origen FOREIGN KEY (ajuste_origen_id)  REFERENCES ajuste_cuenta (id),
    ADD CONSTRAINT fk_imputacion_ajuste_destino FOREIGN KEY (ajuste_destino_id) REFERENCES ajuste_cuenta (id),
    ADD CONSTRAINT uk_imputacion_recibo_ajuste      UNIQUE (recibo_id, ajuste_destino_id),
    ADD CONSTRAINT uk_imputacion_ajuste_comprobante UNIQUE (ajuste_origen_id, comprobante_id),
    ADD CONSTRAINT uk_imputacion_ajuste_ajuste      UNIQUE (ajuste_origen_id, ajuste_destino_id),
    ADD CONSTRAINT ck_imputacion_origen  CHECK ((recibo_id IS NULL) <> (ajuste_origen_id IS NULL)),
    ADD CONSTRAINT ck_imputacion_destino CHECK ((comprobante_id IS NULL) <> (ajuste_destino_id IS NULL)),
    ADD INDEX ix_imputacion_cliente_ajuste (ferreteria_id, cliente_id, ajuste_destino_id);
