-- Movimientos de stock de los articulos demo SOLO para los perfiles dev y test (CU-07).
-- Toda variacion de stock es un movimiento y articulo.stock_actual es su suma cacheada (R-015), asi que
-- cada articulo demo con stock recibe un AJUSTE "Stock inicial demo" por el stock que ya tiene. El de
-- stock 0 (el 5) no lleva movimiento. A nombre del dueno de la ferreteria: se usa MIN(id) y no un JOIN
-- (como V1001) para que una base dev con dos duenos no duplique filas.
-- NOT EXISTS lo hace re-ejecutable. MySQL materializa el SELECT antes de insertar, asi que el NOT EXISTS
-- no ve las filas de este mismo INSERT.

INSERT INTO movimiento_stock (ferreteria_id, articulo_id, tipo, cantidad, motivo, usuario_id)
SELECT a.ferreteria_id, a.id, 'AJUSTE', a.stock_actual, 'Stock inicial demo',
       (SELECT MIN(u.id) FROM usuario u WHERE u.ferreteria_id = a.ferreteria_id AND u.rol = 'DUENO')
FROM articulo a
WHERE a.id BETWEEN 1 AND 8
  AND a.stock_actual <> 0
  AND NOT EXISTS (SELECT 1 FROM movimiento_stock m WHERE m.articulo_id = a.id);
