# CU-14 · Precio manual y ajustes sobre el precio final

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · caso de uso extracto de `spec.md:298-306` en la partición del 2026-11-10.

<!-- spec payload: spec.md:298-306 -->
**CU-14 Precio manual y ajustes sobre el precio final** (quien edita artículos; decidido por Luca el 2026-10-22, como GDS G17 y G26)

1. **Precio manual.** En el artículo, la casilla «Precio manual» deja escribir el precio final (con IVA) a mano. Mientras está tildada, el precio no cambia por nada automático: la importación de listas (CU-04), la actualización masiva de costo o margen (CU-05), las compras (CU-13), la cascada del margen del rubro y el recálculo por cotización pueden cambiar el costo, pero no el precio. Al destildarla, el precio se vuelve a calcular con la regla de precios. Cambiar el precio a mano guarda historial con origen manual.
2. **Margen negativo.** Las vistas previas de CU-04, CU-05 y CU-13 avisan cuando un artículo con precio manual queda con un costo mayor que su precio sin IVA (margen negativo). Es un aviso, no impide confirmar. Se comparan los dos importes que muestra el aviso, el costo real en pesos y el precio sin IVA, redondeados al centavo: si se leen iguales, no avisa (2026-11-07, Luca).
3. **Ajustes sobre el precio final.** Cada artículo tiene una lista de ajustes, sin límite, con el botón «Agregar»: nombre opcional (por ejemplo «Flete»), tipo (% o $) y valor, positivo o negativo. Todos se calculan sobre el precio calculado (no se encadenan; el orden no cambia el resultado): precio = precio calculado + Σ (precio calculado × % / 100) + Σ $. El precio calculado ya viene redondeado al múltiplo de la configuración, y el resultado se redondea otra vez: la cuenta es la que el Dueño ve en pantalla (lo que muestra la columna más sus ajustes), aunque redondee dos veces (QA 05/11, decisión del 06/11). Se vuelven a aplicar en cada recálculo. Con precio manual no se aplican (se conservan para cuando se destilde).
4. **Actualización masiva del precio final.** CU-05 suma la opción «precio final», en % o en $, sobre el precio final actual. Los artículos afectados quedan con precio manual al precio nuevo (como GDS). La vista previa muestra el precio anterior y el nuevo, y marca la variación fuerte y el margen negativo.
5. **Aislamiento.** Los ajustes son datos de la ferretería: un artículo de otra ferretería es 404, igual que uno inexistente.
<!-- fin payload: spec.md:298-306 -->

