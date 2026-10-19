-- Cuenta corriente de clientes, tramo 2 (GDS-20, CC-001 a CC-004, CC-022 a CC-024): el limite y el saldo del cliente, el
-- libro de movimientos y la forma de pago «Cuenta corriente» sembrada inactiva.
--
-- cliente.limite_cuenta_corriente: NULL = el cliente no tiene cuenta corriente habilitada; 0 = habilitada sin credito; no hay
-- «sin tope». cliente.saldo es una CACHE del libro: positivo = el cliente debe, negativo = saldo a favor. Siempre vale
-- Σ(debe - haber) de sus movimientos; solo lo escribe el servicio de cuenta corriente, con un UPDATE relativo bajo el lock
-- de la fila del cliente (saldo = saldo + delta). Los clientes que ya existen quedan sin cuenta corriente y con saldo 0, que
-- es consistente con un libro vacio.
--
-- movimiento_cuenta_cliente es el libro: append-only (no se edita ni se borra; no hay ON DELETE). Cada fila lleva debe O haber
-- (exactamente un lado, mayor a 0). Este tramo solo escribe VENTA (debe, una por pago a cuenta corriente, con su
-- comprobante_id y pago_id); COBRO, AJUSTE y NC quedan previstos para el tramo 3. UNIQUE (pago_id, tipo) respalda la
-- idempotencia: un pago no se asienta dos veces (los NULL no chocan). excedio_limite y autorizado_por van juntos: se marca
-- cuando el Dueño autorizo un exceso. fecha no tiene DEFAULT: la fija el Clock del servicio; el orden canonico es id.
-- ferreteria_id va en la tabla y la consulta es siempre scoped; la FK no verifica que cliente y movimiento sean de la misma
-- ferreteria, lo garantizan los finders del repositorio (convencion del repo).
-- Supuesto validado en MigracionV20Test: el CHECK con igualdad booleana (excedio_limite = (autorizado_por IS NOT NULL))
-- funciona en MySQL 8.4.
--
-- La forma «Cuenta corriente» (medio CUENTA_CORRIENTE, recargo 0, inactiva) se siembra SOLO en las ferreterias que ya
-- tienen formas: el servicio de formas de pago siembra las de siempre unicamente si la ferreteria no tiene NINGUNA, y si esta
-- migracion le insertara una a una ferreteria sin formas, jamas recibiria las cuatro (el servicio le siembra las 5). No choca
-- con una forma ya llamada «cuenta corriente» (collation _ai_ci) ni con una de medio CUENTA_CORRIENTE, y es idempotente.
-- El Dueño la activa desde Formas de pago.

ALTER TABLE cliente
    ADD COLUMN limite_cuenta_corriente DECIMAL(15, 2) NULL,
    ADD COLUMN saldo                   DECIMAL(15, 2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_cliente_limite_cc CHECK (limite_cuenta_corriente IS NULL OR limite_cuenta_corriente >= 0);

CREATE TABLE movimiento_cuenta_cliente (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id   BIGINT         NOT NULL,
    cliente_id      BIGINT         NOT NULL,
    tipo            VARCHAR(10)    NOT NULL,
    debe            DECIMAL(15, 2) NOT NULL DEFAULT 0,
    haber           DECIMAL(15, 2) NOT NULL DEFAULT 0,
    comprobante_id  BIGINT         NULL,
    pago_id         BIGINT         NULL,
    fecha           DATETIME(6)    NOT NULL,
    usuario_id      BIGINT         NOT NULL,
    excedio_limite  BOOLEAN        NOT NULL DEFAULT FALSE,
    autorizado_por  BIGINT         NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_mcc_ferreteria  FOREIGN KEY (ferreteria_id)  REFERENCES ferreteria (id),
    CONSTRAINT fk_mcc_cliente     FOREIGN KEY (cliente_id)     REFERENCES cliente (id),
    CONSTRAINT fk_mcc_comprobante FOREIGN KEY (comprobante_id) REFERENCES comprobante (id),
    CONSTRAINT fk_mcc_pago        FOREIGN KEY (pago_id)        REFERENCES pago (id),
    CONSTRAINT fk_mcc_usuario     FOREIGN KEY (usuario_id)     REFERENCES usuario (id),
    CONSTRAINT fk_mcc_autorizado  FOREIGN KEY (autorizado_por) REFERENCES usuario (id),
    CONSTRAINT uk_mcc_pago_tipo   UNIQUE (pago_id, tipo),
    CONSTRAINT ck_mcc_tipo        CHECK (tipo IN ('VENTA', 'COBRO', 'AJUSTE', 'NC')),
    CONSTRAINT ck_mcc_importes    CHECK (debe >= 0 AND haber >= 0 AND (debe > 0) <> (haber > 0)),
    CONSTRAINT ck_mcc_sentido     CHECK ((tipo = 'VENTA' AND debe > 0) OR (tipo IN ('COBRO', 'NC') AND haber > 0) OR tipo = 'AJUSTE'),
    CONSTRAINT ck_mcc_venta_ref   CHECK (tipo <> 'VENTA' OR (comprobante_id IS NOT NULL AND pago_id IS NOT NULL)),
    CONSTRAINT ck_mcc_exceso      CHECK (excedio_limite = (autorizado_por IS NOT NULL)),
    INDEX ix_mcc_cliente     (ferreteria_id, cliente_id, id),
    INDEX ix_mcc_comprobante (comprobante_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO forma_pago (ferreteria_id, nombre, medio, recargo_porcentaje, activa, orden)
SELECT f.id, 'Cuenta corriente', 'CUENTA_CORRIENTE', 0, FALSE,
       (SELECT COALESCE(MAX(x.orden), 0) + 1 FROM forma_pago x WHERE x.ferreteria_id = f.id)
FROM ferreteria f
WHERE EXISTS (SELECT 1 FROM forma_pago fp WHERE fp.ferreteria_id = f.id)
  AND NOT EXISTS (SELECT 1 FROM forma_pago fp
                  WHERE fp.ferreteria_id = f.id AND (fp.medio = 'CUENTA_CORRIENTE' OR fp.nombre = 'Cuenta corriente'));
