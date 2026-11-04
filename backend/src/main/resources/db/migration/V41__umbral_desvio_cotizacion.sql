-- mejoras-ferreteros-bloque-1 (S1): el aviso de un dolar que se aleja del vigente deja de ser un 20 % fijo en el codigo y
-- pasa a ser un porcentaje de cada ferreteria, que elige el Dueno entre 1 % y 50 %. Las filas existentes quedan en 5 %, el
-- valor que pidieron los ferreteros: desde el deploy, un dolar que se mueve mas del 5 % espera la confirmacion del Dueno.
--
-- Deploy: el codigo anterior no conoce la columna y la ignora (tiene DEFAULT); se puede deployar en cualquier momento.
-- Rollback: ALTER TABLE configuracion_cotizacion DROP CHECK ck_configuracion_cotizacion_umbral,
--           DROP COLUMN umbral_desvio_porcentaje;
ALTER TABLE configuracion_cotizacion
    ADD COLUMN umbral_desvio_porcentaje DECIMAL(5, 2) NOT NULL DEFAULT 5.00,
    ADD CONSTRAINT ck_configuracion_cotizacion_umbral CHECK (umbral_desvio_porcentaje BETWEEN 1 AND 50);
