-- Ajuste (corte 3): cada articulo guarda la base con la que se calculo su costo en la ultima importacion: las tres
-- bonificaciones del proveedor y si la lista traia el IVA incluido. Editar los ajustes recalcula el costo desde el
-- precio de lista con ESA base y no con la que el proveedor tenga hoy.
-- Aditiva: solo agrega columnas NULL. No toca costo, precio, ajustes ni historial.

-- Mismas escala y rango que las bonificaciones del proveedor (V3). NULL = sin bonificacion / sin base guardada.
ALTER TABLE articulo
    ADD COLUMN bonificacion1     DECIMAL(5, 2) NULL,
    ADD COLUMN bonificacion2     DECIMAL(5, 2) NULL,
    ADD COLUMN bonificacion3     DECIMAL(5, 2) NULL,
    ADD COLUMN lista_incluye_iva BOOLEAN       NULL,
    ADD CONSTRAINT ck_articulo_bonificacion1 CHECK (bonificacion1 IS NULL OR bonificacion1 BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_articulo_bonificacion2 CHECK (bonificacion2 IS NULL OR bonificacion2 BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_articulo_bonificacion3 CHECK (bonificacion3 IS NULL OR bonificacion3 BETWEEN 0 AND 100);

-- Relleno de los articulos anteriores a V7: SOLO los que tienen proveedor y precio de lista. Su base es la que el
-- proveedor tiene AL MOMENTO DE MIGRAR, no necesariamente la de su ultima importacion (importacion_lista no guarda
-- las bonificaciones): si el proveedor cambio despues, el costo guardado no coincide y el desglose lo muestra.
-- El JOIN compara ferreteria_id para no mezclar tenants.
-- Quedan en NULL (sin lista): los articulos sin proveedor, aunque tengan precio de lista, y los que se cargaron a mano
-- (sin precio de lista), como todos los datos demo.
UPDATE articulo a
    JOIN proveedor p ON p.id = a.proveedor_id AND p.ferreteria_id = a.ferreteria_id
SET a.bonificacion1     = p.bonificacion1,
    a.bonificacion2     = p.bonificacion2,
    a.bonificacion3     = p.bonificacion3,
    a.lista_incluye_iva = p.lista_incluye_iva
WHERE a.precio_lista_proveedor IS NOT NULL;

-- Rollback: backend/src/test/resources/catalogo/rollback-v7-base-de-lista.sql
