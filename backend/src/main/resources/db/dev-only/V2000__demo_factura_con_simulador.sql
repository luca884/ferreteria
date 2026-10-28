-- Datos SOLO para el perfil dev (CU-07, rama 3 de facturacion, S7): las ferreterias demo facturan con el simulador de
-- WSFEv1. El Tornillo es Responsable Inscripto (Factura A o B) y La Tuerca Monotributista (Factura C), desde V1000.
-- No va en db/dev-data porque esa ubicacion la carga tambien el perfil test, y ahi ninguna ferreteria tiene config_arca
-- sin que el test la cree. db/dev-only la carga solo application-dev.yml, y numera desde V2000 para no chocar con
-- db/dev-data (V1000+) ni con db/test-data (V1100+).
-- Condicional, para una base dev que ya tenga datos: completa solo los datos de CU-01 que esten vacios (el servicio guarda un
-- texto en blanco como NULL) y crea config_arca solo si la ferreteria no tiene una; no pisa lo cargado desde las pantallas.
-- La config_arca queda como la dejaria el super admin desde el panel: marca «de prueba», el simulador con el punto de venta 1
-- y la facturacion habilitada (los CHECK de V32 piden las tres cosas juntas). Las demo se buscan por CUIT (V1006).
-- Ingresos Brutos: en la provincia de Buenos Aires (ARBA) el numero de inscripcion es la CUIT.

UPDATE ferreteria
SET domicilio_comercial = COALESCE(domicilio_comercial, 'Av. Independencia 1234, Mar del Plata'),
    inicio_actividades  = COALESCE(inicio_actividades, '2015-03-01'),
    ingresos_brutos     = COALESCE(ingresos_brutos, '30-00000001-5')
WHERE cuit = '30-00000001-5';

UPDATE ferreteria
SET domicilio_comercial = COALESCE(domicilio_comercial, 'Av. Luro 3200, Mar del Plata'),
    inicio_actividades  = COALESCE(inicio_actividades, '2019-08-01'),
    ingresos_brutos     = COALESCE(ingresos_brutos, '30-00000002-3')
WHERE cuit = '30-00000002-3';

INSERT INTO config_arca (ferreteria_id, de_prueba, ambiente, punto_venta, facturacion_habilitada, habilitada_en)
SELECT f.id, TRUE, 'SIMULADOR', 1, TRUE, UTC_TIMESTAMP(6)
FROM ferreteria f
WHERE f.cuit IN ('30-00000001-5', '30-00000002-3')
  AND NOT EXISTS (SELECT 1 FROM config_arca c WHERE c.ferreteria_id = f.id);
