-- Correcciones de los datos demo SOLO para los perfiles dev y test (CU-07, rama 2 de facturacion, S9). V1000 ya esta
-- aplicada y no se edita: se corrige aca con UPDATE.
-- Los CUIT de las ferreterias demo con el digito verificador correcto (common/Cuit.java): la factura lleva el CUIT del
-- emisor y el simulador lo valida. Solo esto: nada de las tablas de V32 ni de V33, porque varios tests de migracion
-- corren db/dev-data sola sobre una base vieja. La marca «de prueba» de las demo se pone desde el panel del super admin.
-- Los UPDATE son idempotentes (filtran por el valor viejo).

UPDATE ferreteria SET cuit = '30-00000001-5' WHERE id = 1 AND cuit = '30-00000001-1';
UPDATE ferreteria SET cuit = '30-00000002-3' WHERE id = 2 AND cuit = '30-00000002-2';
