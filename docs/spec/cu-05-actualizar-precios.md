# CU-05 · Actualizar precios masivamente

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · caso de uso extracto de `spec.md:92-98` en la partición del 2026-11-10.

<!-- spec payload: spec.md:92-98 -->
**CU-05 Actualizar precios masivamente** (Dueño)

1. Filtra por proveedor, rubro o búsqueda.
2. Aplica un porcentaje de aumento al costo, cambia el margen o cambia el precio final en % o en $ (este último deja los artículos con precio manual, CU-14), o **cambia el rubro**: pasa los artículos del filtro a otro rubro de la ferretería. Con el rubro nuevo cambia el margen heredado y por eso el precio; los que tienen margen propio o precio manual cambian de rubro sin cambiar de precio («Solo cambia el rubro»), y los que ya están en ese rubro quedan sin cambio. La vista previa cuenta aparte unos y otros: «Ya en el destino» y «Solo cambia el rubro», en lugar de «Sin cambio de precio». El historial de precios registra solo los artículos cuyo precio cambió. GDS cambia el rubro de a uno (G11); este cambio en bloque es propio.
3. Ve la vista previa y confirma.
<!-- fin payload: spec.md:92-98 -->

