-- CU-07 (GDS-11, RC-001): formas de pago por ferreteria, como la tabla de medios de GDS (Visa 1 cuota, Visa 6 cuotas...).
-- Cada forma tiene su nombre, el medio al que pertenece (el enum MedioPago: la caja sigue sumando por medio) y su % de
-- recargo (0 a 200). No se borran, porque las van a referenciar los pagos: se desactivan. El nombre es unico por
-- ferreteria sin distinguir mayusculas ni tildes (la collation es _ai_ci).
-- Cada ferreteria que ya existe queda con las cuatro de siempre y sin recargo. Las que se creen despues las recibe el
-- servicio la primera vez que pide sus formas.

CREATE TABLE forma_pago (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    ferreteria_id      BIGINT       NOT NULL,
    nombre             VARCHAR(60)  NOT NULL,
    medio              VARCHAR(20)  NOT NULL,
    recargo_porcentaje DECIMAL(5, 2) NOT NULL DEFAULT 0,
    activa             BOOLEAN      NOT NULL DEFAULT TRUE,
    orden              INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_forma_pago_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_forma_pago_nombre UNIQUE (ferreteria_id, nombre),
    CONSTRAINT ck_forma_pago_recargo CHECK (recargo_porcentaje >= 0 AND recargo_porcentaje <= 200)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO forma_pago (ferreteria_id, nombre, medio, orden)
SELECT f.id, base.nombre, base.medio, base.orden
FROM ferreteria f
         CROSS JOIN (SELECT 'Efectivo' AS nombre, 'EFECTIVO' AS medio, 1 AS orden
                     UNION ALL SELECT 'Débito', 'DEBITO', 2
                     UNION ALL SELECT 'Crédito', 'CREDITO', 3
                     UNION ALL SELECT 'Transferencia', 'TRANSFERENCIA', 4) base;
