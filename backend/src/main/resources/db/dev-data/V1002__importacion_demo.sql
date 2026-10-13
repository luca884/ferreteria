-- Configuracion de lista de los proveedores de demo SOLO para los perfiles dev y test (CU-04).
-- No agrega filas: ajusta los proveedores de V1001 para poder probar la importacion a mano.
--   1 Distribuidora Bulonera Sur: mapeo ya guardado (hoja 0, datos desde la fila 2, codigo en A,
--     descripcion en B, costo en C). Las claves del JSON son las propiedades de MapeoLista.
--   2 Herramientas del Puerto: la lista trae IVA y viene con bonificaciones en cascada 40 % + 10 %
--     (lista 1000 con IVA -> 1000 / 1,21 x 0,60 x 0,90 = 446,28), todavia sin mapeo.
--   3 Pinturas Atlantico: sin configurar, para probar el primer mapeo desde cero.
-- Los articulos demo quedan con precio_lista_proveedor en NULL y no hay importaciones previas.

UPDATE proveedor
SET mapeo_lista = '{"hoja": 0, "filaDesde": 2, "columnaCodigo": 0, "columnaDescripcion": 1, "columnaCosto": 2, "encabezados": {"codigo": "Codigo", "descripcion": "Descripcion", "costo": "Precio"}}'
WHERE id = 1;

UPDATE proveedor
SET lista_incluye_iva = TRUE, bonificacion1 = 40.00, bonificacion2 = 10.00
WHERE id = 2;
