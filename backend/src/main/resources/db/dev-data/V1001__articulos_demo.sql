-- Proveedores y articulos de demo SOLO para los perfiles dev y test (CU-03).
-- Los CUIT son ficticios. La configuracion de precios de las ferreterias queda en los defaults de V2
-- (margen 30 %, multiplo 10), asi que no hay UPDATE. Los ids son explicitos (como en V1000) para que
-- los tests puedan referirse a ellos; los rubros de V1000 no tienen id fijo, se buscan por nombre.

INSERT INTO proveedor (id, ferreteria_id, nombre, cuit, activo) VALUES
    (1, 1, 'Distribuidora Bulonera Sur', '30-00000011-1', TRUE),
    (2, 1, 'Herramientas del Puerto',    NULL,            TRUE),
    (3, 2, 'Pinturas Atlantico',         '30-00000022-2', TRUE);

-- precio_final = costo x (1 + margen) x 1,21, redondeado hacia arriba al multiplo de 10 (calculado a mano):
--   1: 100   x 1,35 (rubro)  x 1,21 =   163,35 -> 170
--   2: 40    x 1,50 (propio) x 1,21 =    72,60 -> 80
--   3: 8000  x 1,40 (rubro)  x 1,21 = 13552    -> 13560
--   4: 500   x 1,30 (ferret.) x 1,21 =   786,50 -> 790
--   5: 3000  x 1,40 (rubro)  x 1,21 =  5082    -> 5090   (inactivo)
--   6: 30000 x 1,30 (rubro)  x 1,21 = 47190    -> 47190  (ya es multiplo)
--   7: 2500  x 1,30 (rubro)  x 1,21 =  3932,50 -> 3940
--   8: 50    x 1,30 (ferret.) x 1,21 =    78,65 -> 80
-- El articulo 8 repite el codigo TOR-001 del 1: cada ferreteria tiene su propio espacio de codigos.
INSERT INTO articulo (id, ferreteria_id, codigo_interno, codigo_barras, codigo_proveedor, descripcion, rubro_id,
                      proveedor_id, costo, margen_porcentaje, alicuota_iva, precio_final, unidad, stock_actual,
                      stock_minimo, ubicacion, activo) VALUES
    (1, 1, 'TOR-001', '7790000000011', 'DBS-100', 'Tornillo hexagonal 1/4 x 1',
        (SELECT id FROM rubro WHERE ferreteria_id = 1 AND nombre = 'Buloneria'), 1,
        100.00, NULL, 'IVA_21', 170.00, 'UNIDAD', 500, 100, 'Estante A1', TRUE),
    (2, 1, 'BUL-002', '7790000000028', 'DBS-200', 'Bulon cabeza redonda 3/8 x 3',
        (SELECT id FROM rubro WHERE ferreteria_id = 1 AND nombre = 'Buloneria'), 1,
        40.00, 50.00, 'IVA_21', 80.00, 'UNIDAD', 200, 50, 'Estante A2', TRUE),
    (3, 1, 'HER-001', '7790000000035', 'HDP-010', 'Taladro percutor 650 W',
        (SELECT id FROM rubro WHERE ferreteria_id = 1 AND nombre = 'Herramientas'), 2,
        8000.00, NULL, 'IVA_21', 13560.00, 'UNIDAD', 6, 2, 'Vitrina 1', TRUE),
    (4, 1, 'CAB-001', NULL, NULL, 'Cable unipolar 2,5 mm',
        NULL, NULL,
        500.00, NULL, 'IVA_21', 790.00, 'METRO', 150, 30, 'Deposito', TRUE),
    (5, 1, 'OLD-001', NULL, NULL, 'Martillo de goma (discontinuado)',
        (SELECT id FROM rubro WHERE ferreteria_id = 1 AND nombre = 'Herramientas'), 2,
        3000.00, NULL, 'IVA_21', 5090.00, 'UNIDAD', 0, 0, NULL, FALSE),
    (6, 2, 'PIN-001', '7790000000066', 'PAT-001', 'Latex interior blanco 20 L',
        (SELECT id FROM rubro WHERE ferreteria_id = 2 AND nombre = 'Pintureria'), 3,
        30000.00, NULL, 'IVA_21', 47190.00, 'UNIDAD', 8, 3, 'Fondo', TRUE),
    (7, 2, 'PIN-002', '7790000000073', 'PAT-002', 'Esmalte sintetico negro 1 L',
        (SELECT id FROM rubro WHERE ferreteria_id = 2 AND nombre = 'Pintureria'), 3,
        2500.00, NULL, 'IVA_21', 3940.00, 'LITRO', 20, 5, 'Fondo', TRUE),
    (8, 2, 'TOR-001', NULL, NULL, 'Tornillo autoperforante 4,2 x 19',
        NULL, NULL,
        50.00, NULL, 'IVA_21', 80.00, 'UNIDAD', 1000, 200, NULL, TRUE);

-- Una entrada inicial de historial (MANUAL, sin valores anteriores) por articulo, a nombre del
-- dueno de la ferreteria. V1000 inserta los usuarios sin id, asi que se los busca por rol.
INSERT INTO historial_precio (ferreteria_id, articulo_id, costo_anterior, costo_nuevo, precio_anterior, precio_nuevo,
                              origen, usuario_id)
SELECT a.ferreteria_id, a.id, NULL, a.costo, NULL, a.precio_final, 'MANUAL', u.id
FROM articulo a
         JOIN usuario u ON u.ferreteria_id = a.ferreteria_id AND u.rol = 'DUENO'
WHERE a.id BETWEEN 1 AND 8;
