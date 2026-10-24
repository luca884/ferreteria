# fk-por-ferreteria

Objetivo: la base rechaza por sí sola que una fila de una ferretería apunte a una fila de otra (FK compuestas con `ferreteria_id`) · Por qué: deuda vista por los verificadores: las 56 FK de negocio apuntan solo al `id`, así que hoy el aislamiento entre ferreterías depende solo del código
Rama: `feat/fk-por-ferreteria` desde `39e20da` · Entrega: single-pr · Runner: `./mvnw test -Dtest=<Clase>` (backend) · Mirror Engram: `odd/fk-por-ferreteria/tasks` (ok)
Pronóstico: ~650 líneas cambiadas · Heurística por tarea: ~400 (no es límite) · Modelo: caso 5, Opus de punta a punta (alto riesgo: migración)

## Specs

Alcance: migración V29 que cambia las 56 FK entre tablas de negocio por FK compuestas, su script de rollback, su test de migración, ajustes de los tests que sembraban referencias cruzadas, y la spec · Fuera de alcance: las FK hacia `ferreteria` (ya son por ferretería), cambios de código de aplicación, índices nuevos más allá de los que pida MySQL
Restricciones: Flyway, nunca `ddl-auto` distinto de `validate`; `ddl-auto=validate` no mira FK; MySQL 8.4 exige que la FK apunte a una PRIMARY o UNIQUE completa

- S1: "todas" (2026-10-23), sobre «Todas las FK de negocio: `(ferreteria_id, x_id) REFERENCES padre (ferreteria_id, id)`, con `UNIQUE (ferreteria_id, id)` en la tabla padre, en una migración V29» — Aceptación: después de la V29, toda FK de una tabla con `ferreteria_id` hacia otra tabla con `ferreteria_id` (salvo `ferreteria`) es compuesta y empieza por `ferreteria_id`; las 15 tablas padre tienen `UNIQUE (ferreteria_id, id)` — Checks: `./mvnw test -Dtest=MigracionV29Test`
- S2 (supuesto): insertar o actualizar una fila con una referencia a una fila de otra ferretería falla en la base (por FK), en cada una de las 56 FK; una referencia NULL en una columna nullable sigue valiendo; una referencia a la misma ferretería sigue valiendo — Checks: `MigracionV29Test`
- S3 (supuesto): un usuario SUPER_ADMIN (`ferreteria_id` NULL) no puede quedar referenciado por datos de negocio. Hoy no pasa: solo opera `/api/admin/**` — Checks: `MigracionV29Test`
- S4 (supuesto): la V29 corre sobre datos existentes sin referencias cruzadas sin cambiar ninguna fila. Con una referencia cruzada se niega antes de tocar nada (guarda) — Checks: `MigracionV29Test`
- S5 (supuesto): script de rollback que deja las FK como en la V28 — Checks: `MigracionV29Test`
- S6: la suite sigue verde; los tests que sembraban a mano una referencia a otra ferretería (estado que ahora es imposible) se ajustan sin perder lo que prueban — Checks: `./mvnw test`

## Tasks

- [x] T1 · S1-S5 · ruta: inline · Aceptación: V29, rollback y `MigracionV29Test` · Evidencia: `MigracionV29Test` RED 7/7 (sin la V29: «No migration with a target version 29»; el esquema actual tenía las FK solo por id) → GREEN 7/7: 56 FK compuestas, UNIQUE en los 15 padres, cruces rechazados al insertar, al actualizar y al mudar, SUPER_ADMIN rechazado, filas intactas, guarda `ck_v29_sin_referencias_a_otra_ferreteria` sin tocar el esquema, rollback con FK e índices idénticos a la V28. `MigracionV6Test` lleva «29»; con las sondas del verificador, 10/10 · commits 333bbca, y las sondas en el commit de cierre · Risk: item 1 (migración de esquema sobre datos guardados)
- [x] T2 · S6 · ruta: inline · Aceptación: suite backend verde con los tests ajustados; spec actualizada · Evidencia: suite con la V29 RED 4605 / 14 errores (siembras cruzadas, ver L4) → GREEN 4598/0; spec «Modelo de datos» · commits f555835, 333bbca · Risk: item 6 parcial (tests que sembraban estados ahora imposibles)

## Log

- L1 (2026-10-23) Pedido original, textual:
  > vamos con la deuda tcnica
  Ante la pregunta de alcance (todas las FK de negocio o solo las de `articulo` a rubro y proveedor y las de `usuario`): «todas».
- L2 (2026-10-23) Exploración en la base dev (V28): 56 FK de negocio, todas `NO ACTION`, hacia 15 padres (`ajuste_cuenta`, `articulo`, `caja`, `cliente`, `compra`, `comprobante`, `forma_pago`, `importacion_lista`, `pago`, `presupuesto`, `proveedor`, `recibo`, `remito`, `rubro`, `usuario`), ninguno con `UNIQUE (ferreteria_id, id)`. Ninguna fila de la base dev cruza ferreterías (conteo por FK = 0). `usuario.ferreteria_id` es NULL solo para SUPER_ADMIN (`V1__esquema_inicial.sql:28`), que solo usa `/api/admin/**` (`SecurityConfig.java:73`).
- L3 (2026-10-23) Diseño probado en una base aparte del MySQL dev: MySQL no deja soltar y crear una FK con el mismo nombre en un solo `ALTER` (1826), y crearla en otro `ALTER` choca con el índice que MySQL había creado solo para la FK vieja (1061). Por eso, cada tabla lleva dos `ALTER`: el primero suelta las FK y el segundo agrega un índice `(ferreteria_id, x_id)` explícito, salvo que ya haya uno que empiece así (35 índices nuevos), y crea las FK. El índice viejo de `x_id` se queda, porque lo usan las consultas que van del padre a los hijos. La guarda es una tabla temporal con un CHECK, que falla aunque el `sql_mode` no sea estricto. El SQL se generó desde el inventario de `information_schema` y se revisó a mano.
- L4 (2026-10-23) Suite con la V29: 4605 tests, 14 errores. Ninguno es de código de aplicación: todos son tests que sembraban a mano una referencia a otra ferretería, que la base ahora rechaza. Hay dos grupos:
  - Siete tests cuyo único caso era ese estado imposible. Se borran, porque convertirlos en «la base lo rechaza» repetiría `MigracionV29Test` dentro de tests de endpoints: `ControlesDeDatosIntegrationTest`, `ExportacionArticulosIntegrationTest` y `StockBajoMinimoIntegrationTest` (`rubroYProveedorAjenosNoSeFiltran`); `ReporteVentasRegresionTest.rubroDeOtraFerreteriaNoSeFiltra`; `ClientesDelArticuloIntegrationTest.ventasAjenasNoCuentan`; `MovimientosArticuloIntegrationTest.referenciasAjenasNoSeFiltran`; `InvarianteCuentaTest.i2eCajaDeLaAnulacionDeOtraFerreteria`. Los joins por `ferreteria_id` del código quedan como estaban.
  - Siembras que cruzaban de paso. `CostoAlIntegrationTest` pierde CAL-5 (historial e importación de otra ferretería) y su segunda ferretería. `MigracionV2Test` usaba `select id from usuario limit 1`, de otra ferretería, y ahora crea su propio usuario.
  Los 10 tests ajustados pasan: Controles 7, Exportación 8, Stock bajo mínimo 4, Reporte regresión 3, Clientes del artículo 8, Movimientos 5, Invariante 25, Costo al 6, V2 12, V6 14.
- L5 (2026-10-23) `odd-verifier` (opus) sobre 333bbca: sin bloqueantes; S1 a S6 pasan. Probó cada una de las 56 FK:
  - con un cruce: rechazo, en la guarda (también con `sql_mode=''`) y después de la V29;
  - con la misma ferretería: aceptada;
  - NULL en las 25 columnas nullable: aceptado;
  - SUPER_ADMIN en las 15 FK hacia `usuario`: rechazo.
  
  La V29 corre out-of-order sobre V28 + `db/dev-data` sin cambiar filas (`CHECKSUM TABLE`). El rollback deja el mismo esquema que la V28. Los 7 tests borrados solo cubrían el estado imposible.
  
  Avisos sin cambios:
  - el rollback deja los índices en otro orden dentro de `SHOW CREATE TABLE` (cosmético);
  - si la guarda corta, Flyway deja la V29 con `success=0` y hace falta `flyway repair` (documentado en la V29);
  - `ADD FOREIGN KEY` copia cada tabla hija con las escrituras bloqueadas: hoy prod no tiene datos, pero con datos conviene correrla en una ventana sin uso.
  
  Se suman 3 de sus sondas a `MigracionV29Test` (10/10): la guarda revisa las 56 FK del inventario, la guarda frena una referencia a un SUPER_ADMIN, y ningún índice de la V28 se pierde.
