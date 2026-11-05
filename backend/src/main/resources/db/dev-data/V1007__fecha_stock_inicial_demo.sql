-- Fecha del AJUSTE "Stock inicial demo" de V1003, SOLO para los perfiles dev y test (CU-09).
-- V1003 lo insertaba sin fecha y tomaba el DEFAULT CURRENT_TIMESTAMP(6) de V4, que es la hora de MySQL (UTC en
-- docker-compose y en Testcontainers). La aplicacion guarda la hora de Buenos Aires convertida segun la zona de la JVM
-- (docs/spec.md, «Despliegue: zona horaria»), asi que con la JVM en UTC el ajuste quedaba unas 3 horas en el futuro y
-- la pantalla de movimientos lo listaba arriba de las ventas del dia.
-- Una fecha fija del pasado, al mediodia: cualquiera sea la zona de la JVM (corre como mucho 3 horas) queda en el mismo
-- dia y antes de cualquier movimiento real. V1003 no se toca (Flyway valida su checksum); en una base dev ya creada,
-- out-of-order aplica esta encima. Re-ejecutable.

UPDATE movimiento_stock
SET fecha = '2026-01-01 12:00:00'
WHERE articulo_id BETWEEN 1 AND 8
  AND tipo = 'AJUSTE'
  AND motivo = 'Stock inicial demo'
  AND comprobante_id IS NULL;
