-- CU-07 pasos 3 y 5, rama 2 de facturacion (D2 y D3 del plan, 2026-10-28): las tablas del modulo facturacion para armar,
-- numerar y autorizar una factura ante ARCA. Aditiva: son todas nuevas y ninguna tabla existente cambia. comprobante sigue sin
-- CAE ni estado fiscal (el registro fiscal es una tabla 1-1 aparte) y ferreteria sin datos de ARCA (van en config_arca).
--
-- config_arca: 1-1 con ferreteria; la fila nace cuando el super admin toca algo. La marca de_prueba, el ambiente, el punto de
-- venta y la facturacion habilitada los pone solo el super admin (D3). ck_config_arca_simulador_de_prueba: SIMULADOR solo en
-- una ferreteria de prueba. ck_config_arca_simulador_punto_venta: con SIMULADOR el punto de venta es 1, sin pantalla para
-- cambiarlo (S3). ck_config_arca_habilitada: habilitada exige ambiente, punto de venta y fecha, y la fecha va solo
-- mientras este habilitada. Lo que vive en otras tablas (datos de CU-01 completos, sin registros abiertos al cambiar el
-- ambiente o el punto de venta) lo valida el codigo.
--
-- serie_fiscal: una fila por serie (ferretería, ambiente, punto de venta, CbteTipo). El lease con dueño es (lease_dueno,
-- lease_vence): se toma con un UPDATE condicional (WHERE lease_dueno IS NULL OR lease_vence <= ahora OR lease_dueno = yo), se
-- usa solo si sigue siendo propio y no vencio, y se suelta poniendo los dos en NULL; lease_dueno es un id por toma, asi un
-- dueño viejo no pisa al nuevo. ultimo_autorizado y ultima_cbte_fch son lo ultimo que se supo autorizado (NULL: todavia no se
-- consulto a ARCA; 0: la serie no tiene ninguno) y alimentan el chequeo de CbteFch y la recuperacion.
--
-- comprobante_fiscal: el registro fiscal, 1-1 con comprobante. Copia al registrar el receptor, el emisor, los importes y las
-- alicuotas (lo que va a ARCA se congela al vender); el ambiente se fija al registrar y no cambia. registrado_en es la hora de
-- la venta (UTC) de la que sale la CbteFch. numero y cbte_fch van juntos y se asignan al enviar. Estados:
--   PENDIENTE             espera su turno en la serie (FIFO por id), sin numero;
--   EN_CURSO              con numero y CbteFch y un intento ABIERTO, guardado antes de llamar;
--   EN_VERIFICACION       timeout de lectura o 5xx: no se sabe si ARCA lo autorizo; traba la serie;
--   AUTORIZADO            con CAE, vencimiento y resultado A;
--   RECHAZADO             ARCA lo rechazo (resultado R): el numero se libera;
--   FUERA_DE_FECHA        la CbteFch no entra antes de enviar: sin numero, sin reintento y sin cambiar la fecha;
--   CAE_NO_COINCIDE       alerta: ARCA tiene ese numero con otros datos; conserva el numero (no se renumera) y traba la serie;
--   SERIE_DESINCRONIZADA  alerta: ARCA va mas de uno atras (ultimo < N-1); conserva el numero y traba la serie.
-- ck_comprobante_fiscal_coherencia (S10, estilo V22): numero y CbteFch van juntos; sin numero solo en PENDIENTE, RECHAZADO y
-- FUERA_DE_FECHA; CAE solo en AUTORIZADO y siempre con su vencimiento; resultado A solo en AUTORIZADO y R solo en RECHAZADO.
-- uk_comprobante_fiscal_numero: un numero por serie; los NULL no chocan, asi que entran todos los que esperan.
--
-- comprobante_fiscal_alicuota: el desglose por alicuota a nivel comprobante, con el Id de WSFEv1 (3 = 0 %, 4 = 10,5 %,
-- 5 = 21 %, 6 = 27 %, 8 = 5 %, 9 = 2,5 %), BaseImp e Importe. La C no lleva filas.
--
-- intento_fiscal: cada FECAESolicitar, guardado en una transaccion corta ANTES de llamar, con su numero y CbteFch, y el pedido
-- y la respuesta tal cual. Abiertos: ABIERTO (llamada en vuelo, sin terminar), EN_VERIFICACION (sin respuesta segura) y ALERTA
-- (necesita a una persona). Cerrados: AUTORIZADO, RECHAZADO y NO_LLEGO (timeout de conexion, o la verificacion dio N-1 y se
-- reenvia el mismo numero en un intento nuevo). ck_intento_fiscal_coherencia: terminado_en falta solo en ABIERTO, cerrado_en
-- falta solo en los abiertos, y el resultado sigue al estado como en el registro.
-- uk_intento_fiscal_abierto_por_serie: a lo sumo un intento abierto por serie, aunque falle el lease. Es un indice funcional:
-- la expresion vale serie_fiscal_id si el intento esta abierto y NULL si no, y los NULL no chocan.
--
-- Toda tabla lleva ferreteria_id NOT NULL y las FK entre tablas de negocio son compuestas (ferreteria_id, x_id), como en V29.
-- Horas en DATETIME(6) UTC; CbteFch y vencimiento del CAE en DATE (fechas fiscales de ARCA). Plata en DECIMAL(15, 2).
-- Enums en VARCHAR con CHECK de valores. V33 queda para las tablas del simulador.

CREATE TABLE config_arca (
    id                      BIGINT      NOT NULL AUTO_INCREMENT,
    ferreteria_id           BIGINT      NOT NULL,
    de_prueba               BOOLEAN     NOT NULL DEFAULT FALSE,
    ambiente                VARCHAR(20) NULL,
    punto_venta             INT         NULL,
    facturacion_habilitada  BOOLEAN     NOT NULL DEFAULT FALSE,
    habilitada_en           DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_config_arca_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_config_arca_ferreteria UNIQUE (ferreteria_id),
    CONSTRAINT ck_config_arca_ambiente CHECK (ambiente IS NULL OR ambiente IN ('SIMULADOR', 'HOMOLOGACION', 'PRODUCCION')),
    CONSTRAINT ck_config_arca_punto_venta CHECK (punto_venta IS NULL OR punto_venta BETWEEN 1 AND 99999),
    CONSTRAINT ck_config_arca_simulador_de_prueba CHECK (ambiente IS NULL OR ambiente <> 'SIMULADOR' OR de_prueba),
    CONSTRAINT ck_config_arca_simulador_punto_venta CHECK (ambiente IS NULL OR ambiente <> 'SIMULADOR' OR punto_venta <=> 1),
    CONSTRAINT ck_config_arca_habilitada CHECK (
        facturacion_habilitada = (habilitada_en IS NOT NULL)
        AND (NOT facturacion_habilitada OR (ambiente IS NOT NULL AND punto_venta IS NOT NULL)))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE serie_fiscal (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    ferreteria_id      BIGINT      NOT NULL,
    ambiente           VARCHAR(20) NOT NULL,
    punto_venta        INT         NOT NULL,
    cbte_tipo          INT         NOT NULL,
    ultimo_autorizado  BIGINT      NULL,
    ultima_cbte_fch    DATE        NULL,
    lease_dueno        VARCHAR(36) NULL,
    lease_vence        DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_serie_fiscal_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT uk_serie_fiscal_clave UNIQUE (ferreteria_id, ambiente, punto_venta, cbte_tipo),
    CONSTRAINT uk_serie_fiscal_ferreteria_y_id UNIQUE (ferreteria_id, id),
    CONSTRAINT ck_serie_fiscal_clave CHECK (ambiente IN ('SIMULADOR', 'HOMOLOGACION', 'PRODUCCION')
        AND punto_venta BETWEEN 1 AND 99999 AND cbte_tipo IN (1, 6, 11, 3, 8, 13)),
    CONSTRAINT ck_serie_fiscal_lease CHECK ((lease_dueno IS NULL) = (lease_vence IS NULL)),
    CONSTRAINT ck_serie_fiscal_ultimo CHECK ((ultimo_autorizado IS NULL OR ultimo_autorizado BETWEEN 0 AND 99999999)
        AND (ultima_cbte_fch IS NULL OR COALESCE(ultimo_autorizado, 0) > 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE comprobante_fiscal (
    id                          BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id               BIGINT         NOT NULL,
    comprobante_id              BIGINT         NOT NULL,
    ambiente                    VARCHAR(20)    NOT NULL,
    clase                       VARCHAR(20)    NOT NULL,
    letra                       VARCHAR(1)     NOT NULL,
    cbte_tipo                   INT            NOT NULL,
    punto_venta                 INT            NOT NULL,
    numero                      BIGINT         NULL,
    cbte_fch                    DATE           NULL,
    estado                      VARCHAR(30)    NOT NULL,
    cae                         VARCHAR(14)    NULL,
    cae_vencimiento             DATE           NULL,
    resultado                   VARCHAR(1)     NULL,
    observaciones               JSON           NULL,
    errores                     JSON           NULL,
    receptor_doc_tipo           INT            NOT NULL,
    receptor_doc_nro            BIGINT         NOT NULL,
    receptor_condicion_iva_id   INT            NOT NULL,
    receptor_nombre             VARCHAR(120)   NULL,
    receptor_domicilio          VARCHAR(200)   NULL,
    emisor_cuit                 VARCHAR(13)    NOT NULL,
    emisor_razon_social         VARCHAR(150)   NOT NULL,
    emisor_condicion_iva        VARCHAR(30)    NOT NULL,
    emisor_domicilio_comercial  VARCHAR(200)   NOT NULL,
    emisor_ingresos_brutos      VARCHAR(50)    NOT NULL,
    emisor_inicio_actividades   DATE           NOT NULL,
    imp_total                   DECIMAL(15, 2) NOT NULL,
    imp_neto                    DECIMAL(15, 2) NOT NULL,
    imp_iva                     DECIMAL(15, 2) NOT NULL,
    imp_tot_conc                DECIMAL(15, 2) NOT NULL,
    imp_op_ex                   DECIMAL(15, 2) NOT NULL,
    registrado_en               DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_comprobante_fiscal_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_comprobante_fiscal_comprobante FOREIGN KEY (ferreteria_id, comprobante_id)
        REFERENCES comprobante (ferreteria_id, id),
    CONSTRAINT uk_comprobante_fiscal_comprobante UNIQUE (ferreteria_id, comprobante_id),
    CONSTRAINT uk_comprobante_fiscal_ferreteria_y_id UNIQUE (ferreteria_id, id),
    CONSTRAINT uk_comprobante_fiscal_numero UNIQUE (ferreteria_id, ambiente, punto_venta, cbte_tipo, numero),
    CONSTRAINT ck_comprobante_fiscal_ambiente CHECK (ambiente IN ('SIMULADOR', 'HOMOLOGACION', 'PRODUCCION')),
    CONSTRAINT ck_comprobante_fiscal_tipo CHECK ((clase, letra, cbte_tipo) IN (
        ('FACTURA', 'A', 1), ('FACTURA', 'B', 6), ('FACTURA', 'C', 11),
        ('NOTA_CREDITO', 'A', 3), ('NOTA_CREDITO', 'B', 8), ('NOTA_CREDITO', 'C', 13))),
    CONSTRAINT ck_comprobante_fiscal_numeracion CHECK (punto_venta BETWEEN 1 AND 99999
        AND (numero IS NULL OR numero BETWEEN 1 AND 99999999)),
    CONSTRAINT ck_comprobante_fiscal_estado CHECK (estado IN ('PENDIENTE', 'EN_CURSO', 'EN_VERIFICACION', 'AUTORIZADO',
        'RECHAZADO', 'FUERA_DE_FECHA', 'CAE_NO_COINCIDE', 'SERIE_DESINCRONIZADA')),
    CONSTRAINT ck_comprobante_fiscal_coherencia CHECK (
        (numero IS NULL) = (cbte_fch IS NULL)
        AND (numero IS NULL) = (estado IN ('PENDIENTE', 'RECHAZADO', 'FUERA_DE_FECHA'))
        AND (cae IS NOT NULL) = (estado = 'AUTORIZADO')
        AND (cae IS NULL) = (cae_vencimiento IS NULL)
        AND resultado <=> CASE estado WHEN 'AUTORIZADO' THEN 'A' WHEN 'RECHAZADO' THEN 'R' END),
    CONSTRAINT ck_comprobante_fiscal_receptor CHECK (receptor_doc_tipo IN (80, 86, 96, 99)
        AND receptor_doc_nro BETWEEN 0 AND 99999999999 AND receptor_condicion_iva_id IN (1, 4, 5, 6)),
    CONSTRAINT ck_comprobante_fiscal_emisor CHECK (
        emisor_condicion_iva IN ('RESPONSABLE_INSCRIPTO', 'MONOTRIBUTISTA', 'EXENTO')),
    CONSTRAINT ck_comprobante_fiscal_importes CHECK (imp_neto >= 0 AND imp_iva >= 0 AND imp_tot_conc >= 0
        AND imp_op_ex >= 0 AND imp_total = imp_neto + imp_iva + imp_tot_conc + imp_op_ex),
    INDEX ix_comprobante_fiscal_cola (ferreteria_id, ambiente, punto_venta, cbte_tipo, estado, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE comprobante_fiscal_alicuota (
    id                     BIGINT         NOT NULL AUTO_INCREMENT,
    ferreteria_id          BIGINT         NOT NULL,
    comprobante_fiscal_id  BIGINT         NOT NULL,
    iva_id                 INT            NOT NULL,
    base_imp               DECIMAL(15, 2) NOT NULL,
    importe                DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_comprobante_fiscal_alicuota_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_comprobante_fiscal_alicuota_comprobante_fiscal FOREIGN KEY (ferreteria_id, comprobante_fiscal_id)
        REFERENCES comprobante_fiscal (ferreteria_id, id),
    CONSTRAINT uk_comprobante_fiscal_alicuota_iva UNIQUE (ferreteria_id, comprobante_fiscal_id, iva_id),
    CONSTRAINT ck_comprobante_fiscal_alicuota_valores CHECK (iva_id IN (3, 4, 5, 6, 8, 9)
        AND base_imp >= 0 AND importe >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE intento_fiscal (
    id                      BIGINT      NOT NULL AUTO_INCREMENT,
    ferreteria_id           BIGINT      NOT NULL,
    comprobante_fiscal_id   BIGINT      NOT NULL,
    serie_fiscal_id         BIGINT      NOT NULL,
    numero                  BIGINT      NOT NULL,
    cbte_fch                DATE        NOT NULL,
    estado                  VARCHAR(20) NOT NULL,
    resultado               VARCHAR(1)  NULL,
    pedido                  MEDIUMTEXT  NOT NULL,
    respuesta               MEDIUMTEXT  NULL,
    respuesta_verificacion  MEDIUMTEXT  NULL,
    iniciado_en             DATETIME(6) NOT NULL,
    terminado_en            DATETIME(6) NULL,
    cerrado_en              DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_intento_fiscal_ferreteria FOREIGN KEY (ferreteria_id) REFERENCES ferreteria (id),
    CONSTRAINT fk_intento_fiscal_comprobante_fiscal FOREIGN KEY (ferreteria_id, comprobante_fiscal_id)
        REFERENCES comprobante_fiscal (ferreteria_id, id),
    CONSTRAINT fk_intento_fiscal_serie_fiscal FOREIGN KEY (ferreteria_id, serie_fiscal_id)
        REFERENCES serie_fiscal (ferreteria_id, id),
    CONSTRAINT ck_intento_fiscal_estado CHECK (estado IN ('ABIERTO', 'EN_VERIFICACION', 'ALERTA', 'AUTORIZADO', 'RECHAZADO',
        'NO_LLEGO')),
    CONSTRAINT ck_intento_fiscal_coherencia CHECK (
        (terminado_en IS NULL) = (estado = 'ABIERTO')
        AND (cerrado_en IS NULL) = (estado IN ('ABIERTO', 'EN_VERIFICACION', 'ALERTA'))
        AND resultado <=> CASE estado WHEN 'AUTORIZADO' THEN 'A' WHEN 'RECHAZADO' THEN 'R' END),
    INDEX ix_intento_fiscal_comprobante_fiscal (ferreteria_id, comprobante_fiscal_id, id),
    INDEX ix_intento_fiscal_serie_fiscal (ferreteria_id, serie_fiscal_id, id),
    UNIQUE INDEX uk_intento_fiscal_abierto_por_serie (ferreteria_id,
        (CASE WHEN estado IN ('ABIERTO', 'EN_VERIFICACION', 'ALERTA') THEN serie_fiscal_id END))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Rollback: backend/src/test/resources/facturacion/rollback-v32-facturacion.sql, SOLO sin datos (se niega con una sola fila
-- en cualquiera de las cinco tablas, aunque se corra con mysql --force; con datos se conserva todo).
