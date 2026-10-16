-- Moneda y cotizacion: articulos en dolares con el precio final siempre en pesos.
-- Aditiva: no edita V1 a V4 ni rellena filas. Los articulos que ya existen quedan en ARS.
-- Las fechas son UTC (la JDBC corre en UTC); la hora y el dia del recalculo se interpretan en Buenos Aires.

-- La moneda del costo del articulo. El precio_final sigue en pesos (mostrador, ticket y ventas no cambian).
ALTER TABLE articulo
    ADD COLUMN moneda VARCHAR(3) NOT NULL DEFAULT 'ARS' AFTER costo,
    ADD CONSTRAINT ck_articulo_moneda CHECK (moneda IN ('ARS', 'USD'));

CREATE INDEX ix_articulo_moneda ON articulo (ferreteria_id, moneda);

-- El recalculo diario por cotizacion no lo hace ningun usuario: usuario_id pasa a admitir NULL
-- (se muestra como "Automatico"). La FK al usuario se mantiene.
ALTER TABLE historial_precio
    MODIFY COLUMN usuario_id BIGINT NULL,
    ADD COLUMN moneda VARCHAR(3) NOT NULL DEFAULT 'ARS' AFTER origen;

-- Ultimo valor conocido de cada tipo de cotizacion. GLOBAL a proposito: la cotizacion del mercado es la misma
-- para todas las ferreterias, asi que no lleva ferreteria_id (no es un dato de negocio de un tenant).
-- actualizada_en es la fecha que informa la fuente; obtenida_en, el momento en que se obtuvo con exito.
CREATE TABLE cotizacion (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    moneda          VARCHAR(3)     NOT NULL,
    casa            VARCHAR(30)    NOT NULL,
    nombre          VARCHAR(60)    NOT NULL,
    compra          DECIMAL(15, 4) NULL,
    venta           DECIMAL(15, 4) NULL,
    fuente          VARCHAR(20)    NOT NULL,
    actualizada_en  DATETIME(6)    NOT NULL,
    obtenida_en     DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_cotizacion_moneda_casa UNIQUE (moneda, casa)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Configuracion de cotizacion de cada ferreteria y su dolar vigente (el que fijo su ultimo recalculo).
-- No se rellena: la fila se crea en el codigo con estos valores por defecto la primera vez que se pide.
CREATE TABLE configuracion_cotizacion (
    id                    BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id         BIGINT         NOT NULL,
    activa                BOOLEAN        NOT NULL DEFAULT TRUE,
    casa_precios          VARCHAR(30)    NOT NULL DEFAULT 'oficial',
    lado_precios          VARCHAR(10)    NOT NULL DEFAULT 'VENTA',
    visibles              JSON           NOT NULL DEFAULT (JSON_ARRAY('USD:oficial')),
    hora_recalculo        TIME           NOT NULL DEFAULT '08:00:00',
    vigente               DECIMAL(15, 4) NULL,
    vigente_casa          VARCHAR(30)    NULL,
    vigente_lado          VARCHAR(10)    NULL,
    vigente_desde         DATETIME(6)    NULL,
    ultimo_recalculo_dia  DATE           NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_configuracion_cotizacion_ferreteria UNIQUE (ferreteria_id),
    CONSTRAINT fk_configuracion_cotizacion_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT ck_configuracion_cotizacion_lado CHECK (lado_precios IN ('COMPRA', 'VENTA')),
    CONSTRAINT ck_configuracion_cotizacion_vigente CHECK (vigente IS NULL OR vigente > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
