-- CU-14: precio manual y ajustes sobre el precio final del articulo.
-- precio_manual: el Dueño fijo el precio a mano y nada automatico lo recalcula (como «Lista 1 calculada» en GDS).
-- ajustes_precio: lista de ajustes {nombre, tipo PORCENTAJE|MONTO, valor} sobre el precio final, en el orden en que
-- se cargaron. Va en la misma fila del articulo (JSON) y no en una tabla aparte: se lee y se escribe siempre con el
-- articulo, que ya esta aislado por ferreteria_id.
-- Aditiva: los articulos existentes quedan sin precio manual y sin ajustes, con el mismo precio. En MySQL 8.4 las dos
-- columnas entran con ALGORITHM=INSTANT.
ALTER TABLE articulo
    ADD COLUMN precio_manual  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN ajustes_precio JSON    NOT NULL DEFAULT (JSON_ARRAY());

-- Rollback: backend/src/test/resources/catalogo/rollback-v24-precio-manual.sql
