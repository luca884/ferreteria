-- H11: por ahora el precio final no se redondea a un multiplo (decision de Luca, 2026-10-17).
-- Con el multiplo de $10 una arandela de $3,15 quedaba en $10. Multiplo 0 = sin redondeo: el precio queda con centavos.
-- Los precios ya guardados no se recalculan aca: se recalculan solos la proxima vez que cambia el costo, el margen o el dolar.
-- Si las ferreterias lo piden, se evaluara redondear hacia arriba al peso entero.
ALTER TABLE ferreteria ALTER COLUMN redondeo_multiplo SET DEFAULT 0.00;

UPDATE ferreteria SET redondeo_multiplo = 0.00;
