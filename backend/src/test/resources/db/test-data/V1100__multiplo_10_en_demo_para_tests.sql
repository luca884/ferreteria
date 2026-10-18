-- Solo tests: la base de tests vuelve al multiplo de $10 con que se escribieron los tests de precios.
-- Desde H11 (V9) las ferreterias van sin redondeo, pero el codigo del multiplo sigue y esos tests lo siguen probando:
-- las demo y las ferreterias que los tests insertan a mano (toman el default de la columna) quedan en $10.
-- SinRedondeoIntegrationTest prueba el caso real (sin multiplo) con una ferreteria propia en 0.
ALTER TABLE ferreteria ALTER COLUMN redondeo_multiplo SET DEFAULT 10.00;

UPDATE ferreteria SET redondeo_multiplo = 10.00;
