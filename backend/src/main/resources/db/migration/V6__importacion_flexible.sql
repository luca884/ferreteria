-- Importacion flexible (corte 2): descuentos y recargos por articulo, ultima importacion de cada articulo, moneda
-- anterior en el historial y contadores nuevos de la importacion.
-- Aditiva: no edita V1 a V5 ni rellena filas. Los articulos que ya existen quedan con NULL en las cuatro columnas
-- nuevas de articulo y ningun precio cambia.

-- Hasta tres descuentos o recargos en %, negativo es descuento: de -99,99 a 1000 (igual que la actualizacion masiva),
-- asi ningun ajuste deja el costo en cero o negativo. NULL es "sin ajuste".
-- ultima_importacion_id apunta a la importacion que toco el articulo por ultima vez (NULL: nunca lo toco una).
ALTER TABLE articulo
    ADD COLUMN ajuste1 DECIMAL(6, 2) NULL,
    ADD COLUMN ajuste2 DECIMAL(6, 2) NULL,
    ADD COLUMN ajuste3 DECIMAL(6, 2) NULL,
    ADD COLUMN ultima_importacion_id BIGINT NULL,
    ADD CONSTRAINT ck_articulo_ajuste1 CHECK (ajuste1 IS NULL OR ajuste1 BETWEEN -99.99 AND 1000),
    ADD CONSTRAINT ck_articulo_ajuste2 CHECK (ajuste2 IS NULL OR ajuste2 BETWEEN -99.99 AND 1000),
    ADD CONSTRAINT ck_articulo_ajuste3 CHECK (ajuste3 IS NULL OR ajuste3 BETWEEN -99.99 AND 1000),
    ADD CONSTRAINT fk_articulo_ultima_importacion FOREIGN KEY (ultima_importacion_id) REFERENCES importacion_lista (id);

-- Para filtrar los articulos por la importacion que los toco (corte 3).
CREATE INDEX ix_articulo_ultima_importacion ON articulo (ferreteria_id, ultima_importacion_id);

-- La moneda del costo ANTERIOR, solo en un cambio de moneda: asi el costo anterior y el nuevo no se leen en la misma
-- moneda. Es NULL en todo el resto (la moneda de la entrada es la del articulo despues del cambio).
ALTER TABLE historial_precio
    ADD COLUMN moneda_anterior VARCHAR(3) NULL AFTER moneda,
    ADD CONSTRAINT ck_historial_moneda_anterior CHECK (moneda_anterior IS NULL OR moneda_anterior IN ('ARS', 'USD'));

-- Cuantos articulos cambiaron de moneda, cuantos se corrigieron (IVA o ajustes con el mismo costo) y cuantas filas de
-- titulo se saltearon. Las importaciones anteriores quedan en 0.
ALTER TABLE importacion_lista
    ADD COLUMN cambian_moneda INT NOT NULL DEFAULT 0,
    ADD COLUMN corrigen       INT NOT NULL DEFAULT 0,
    ADD COLUMN salteadas      INT NOT NULL DEFAULT 0;
