# CU-13 · Registrar una compra

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · caso de uso extracto de `spec.md:257-298` en la partición del 2026-11-10.

<!-- spec payload: spec.md:257-298 -->
**CU-13 Registrar una compra** (solo el Dueño; punto 4 del orden acordado, tramo 1: ingreso de mercadería; GDS G34, G38 y G86)

1. El Dueño carga la factura (o el remito) del proveedor: **proveedor** activo, **tipo** (Factura A, Factura B, Factura C o Remito), **punto de venta** (1 a 99999), **número** (1 a 99999999) y **fecha** (no futura). Proveedor, tipo, punto de venta y número no se pueden repetir en la ferretería: 409 «Esa compra ya está cargada: <tipo> <ppppp>-<nnnnnnnn> de <proveedor>.».
2. Agrega renglones: **artículo** activo, **cantidad** mayor a 0 (entera si el artículo se vende por unidad) y **costo unitario sin IVA** mayor a 0 (hasta 2 decimales), en la moneda del artículo. Un artículo no se repite en la misma compra, y una compra no mezcla artículos en pesos y en dólares. Entre 1 y 500 renglones.
3. **Vista previa** antes de confirmar: por renglón, el costo y el precio de venta anteriores y nuevos, cuántos suben, cuántos bajan y cuántos quedan igual (por el costo real: con los recargos de la compra contra los que tiene hoy el artículo). Marca la variación fuerte (más de ±50 %, la misma advertencia de CU-04 y CU-05). La vista previa no guarda nada.
4. El Dueño confirma y, **en una sola transacción**:
   - suma la cantidad al stock de cada artículo, con un movimiento de stock de tipo **compra** que referencia la compra;
   - **pisa el costo** del artículo con el del renglón (último costo, como GDS; decidido 2026-10-21) y **recalcula el precio de venta** con la regla de precios. GDS deja el precio viejo sin avisar (G86); acá no;
   - guarda el historial de precio con origen **compra** solo si el costo o el precio cambiaron;
   - no toca el precio de lista del proveedor, las bonificaciones, los ajustes, el margen ni el IVA del artículo.
   Si algo falla, no queda nada. La confirmación es idempotente por clave, como el remito.
5. **Consulta**: listado de compras (fecha, proveedor, comprobante, cantidad de renglones y neto = Σ cantidad × costo) con filtro por proveedor y por rango de fechas, y el detalle de cada compra con sus renglones.
6. **Aislamiento.** Una compra, un proveedor o un artículo de otra ferretería es 404, igual que uno inexistente. Un Vendedor recibe 403, también con un cuerpo o un parámetro inválido (el 403 va antes de la validación).
7. **Factura del proveedor en PDF** (cu-13-factura-pdf, pedido del relevamiento de ferreteros, 2026-11-04).
   - **Lectura.** En «Nueva compra», «Leer factura en PDF» (`POST /api/compras/factura-pdf`, solo el Dueño) lee una factura con texto. Usa el mismo proceso aparte y los mismos límites que las listas en PDF de CU-04.
   - **Qué completa.** El formulario sale con lo que se lee, sin guardar nada:
     - el proveedor, por el CUIT de la factura (el de la propia ferretería no cuenta; si ningún proveedor activo tiene ese CUIT, lo elige el Dueño);
     - el tipo, por «Código 01/06/11» o la letra;
     - el punto de venta, el número y la fecha;
     - los renglones, cada uno con su artículo (buscado por el código del proveedor), la cantidad y el costo unitario sin IVA.
   - **Cómo se leen los renglones.** No se arman columnas como en las listas. Cada línea es un renglón si trae cantidad × unitario = neto, con una bonificación opcional.
   - **Renglones sin artículo.** Los que no tienen artículo quedan aparte, con «Elegir artículo» o «Quitar». Mientras quede alguno no hay vista previa: «Resolvé los renglones sin artículo.».
   - **Control.** La suma de los netos más los recargos se compara con el subtotal impreso. Si coincide: «La lectura cuadra con el total de la factura…»; si no: «No pudimos comprobar el total de la factura: revisá los renglones antes de confirmar.».
   - **PDF escaneado.** Da «El PDF no tiene texto para leer (parece escaneado). Cargá la compra a mano.».
   - **Sin renglones.** Da «No se encontraron renglones en la factura. Cargá la compra a mano.».
   - **Demasiados renglones.** La lectura admite hasta 500 renglones, el mismo tope que la compra. Con más (por ejemplo, una lista de precios subida como factura) da «La factura trae más de 500 renglones: no parece una factura. Cargá la compra a mano.» y no carga nada: el formulario queda para cargar a mano o subir otro PDF.
   - **El PDF no se guarda.** Guardar el original queda como un «tal vez» para el final.
8. **Recargos del costo.**
   - **Qué son.** Un cargo de la factura sobre el total, como «EMBALAJE Y CARGA 4.44%», se lee como recargo si su importe es ese % de la suma de los netos. La compra lo lleva en `compra.recargos` y lo deja en cada artículo, pisando sus recargos como pisa el costo; una compra sin recargos los borra.
   - **En el precio.** Cada artículo puede tener hasta 5 recargos con nombre (de 1 a 40 caracteres) y un % entre −99,99 y 99,99, distinto de 0. El Dueño también los edita en la ficha. Se aplican sobre el costo antes del margen y el IVA: costo real = costo × (1 + Σ % / 100), a 2 decimales. Todo cálculo del precio parte del costo real: alta, edición, listas, actualización masiva, compras, cascada del rubro y cotización.
   - **Quién más usa el costo real.** El aviso de margen negativo y el costo que copia la venta para la ganancia (CU-15). El control «pisar costo» de CU-04 y la exportación siguen con el costo base.
   - **Vista previa.** Muestra el costo real anterior y el nuevo.
   - **Reintentos.** Reintentar con la misma clave y otros recargos da 409.
   - **Ejemplo.** Una soldadora con costo $ 235.971,56 y «Embalaje y carga 4,44 %» queda con costo real $ 246.448,70.
9. **Desglose y última compra en la ficha.**
   - **Desglose.** El desglose del precio muestra casilleros con el concepto arriba y el valor abajo: costo, cada recargo con su % y su importe, costo real, margen, IVA, ajustes y precio final.
   - **Última compra.** Debajo dice «Última compra: <comprobante> del dd/MM/aaaa», con un link que abre esa compra (`/compras/{id}`).
   - **Visibilidad.** Solo para el Dueño; el Vendedor no ve recargos ni compras.

> **Fuera del tramo 1:** el pago de la compra (efectivo, cheque, cuenta corriente, G36), la cuenta corriente y el pago a proveedores (G39, G40), la anulación de una compra, la devolución al proveedor (G37), el cálculo del costo desde la lista menos 3 bonificaciones en el renglón (G86), el IVA y el total de la factura, el alta rápida de un artículo desde la compra (G94), la fecha de última compra (G19) y el libro IVA Compras (G87). El precio manual y los ajustes sobre el precio final son una feature aparte, posterior (2026-10-21, Luca).
<!-- fin payload: spec.md:257-298 -->

**Agregado móvil: Compras y Proveedores** (≤639.98 px).
Proveedores muestra tarjetas; el Dueño tiene «Nuevo» flotante, ficha a pantalla completa con «Guardar cambios» al pie al editar
y baja o reactivación en la hoja del ⋯. El Vendedor conserva la lista de solo lectura, sin ficha ni «Nuevo».
Compras muestra tarjetas y una hoja de filtros; el detalle es de pantalla completa con renglones plegables. «Nueva compra» sigue en la barra.
Nueva compra pliega Comprobante, Artículos y Recargos y fija «Ver vista previa» al pie. La previa es una pantalla aparte:
los renglones desplegables muestran costo, precio y stock, y «Volver» y «Confirmar compra» quedan fijos al pie.
No cambian las reglas de negocio, los roles, los textos de validación ni la escritura de stock y costos.
Pendientes: la baja tiene botones «lado a lado» y «Lista de precios» no es tarjeta aparte (L62); el paginador «queda arriba de la lista» (L64).
«Ver vista previa» sigue habilitado con datos incompletos; no hay «Elegir PDF» ni grilla de campos (L67), ni «escáner por cámara» (L69).
En la previa «Volver» está «sólo en el pie», la advertencia va «dentro del detalle» y no hay «indicador de progreso» (L71).

