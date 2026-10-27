# commons-lang3

Objetivo: `commons-lang3` 3.18.0 en el backend (lo que pide `commons-compress` 1.28.0 y cierra CVE-2025-48924) sin que el MySQL de los tests cambie de configuración · Por qué: el BOM de Spring Boot 3.3.13 baja `commons-lang3` a 3.14.0, que tiene la CVE y hace fallar la copia de archivos de Testcontainers.
Rama: `fix/commons-lang3` desde `9bac0d1` · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Dtest=<Clase>` (focalizado), `./mvnw test` (suite), `./mvnw test -Pimagen` (imagen) · Mirror Engram: `odd/commons-lang3/tasks` (ok) · Ruteo: caso 5 (cambia una dependencia de producción), Opus de punta a punta.
Pronóstico: ~120 líneas cambiadas (agregadas + borradas, sin generados) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: la versión de `commons-lang3` en `backend/pom.xml`, la configuración que Testcontainers copia al MySQL de los tests y los tests que fijan las dos cosas. · Fuera de alcance: otras dependencias, el `Dockerfile`, el frontend, la configuración del MySQL de Railway.
Restricciones: el MySQL de los tests sigue con los valores de fábrica de `mysql:8.4`, como hoy. La suite no pierde tests.

- S1: "vamos con tu recomendacion" (a la propuesta: "subir a 3.18.0 y, en el mismo cambio, hacer que los 6 `MySQLContainer` de los tests no copien el `my.cnf` de Testcontainers, para que sigan con los valores de fábrica de MySQL 8.4 (los 64 MB que tienen hoy). Sumaría un test que fije esos valores: daría RED con la versión nueva si el `my.cnf` se copia y GREEN cuando se anula. Así cerramos la vulnerabilidad, desaparece el error de los logs y los tests no se mueven de donde están.") — Aceptación: `commons-lang3` resuelve a 3.18.0; `commons-compress` arma un tar sin `NoSuchMethodError`; el MySQL de los tests tiene `max_allowed_packet` 67108864 y `innodb_buffer_pool_size` 134217728. — Checks: tests focalizados, `./mvnw dependency:tree`, `./mvnw test`, `./mvnw test -Pimagen`.
- S2 (supuesto): "los 6 `MySQLContainer`" son en realidad 23 archivos con `new MySQLContainer<>("mysql:8.4")`; el arreglo cubre todos, y también los que se agreguen después.

## Tasks

- [x] T1 · S1, S2 · ruta: inline · Aceptación: un test sobre el MySQL de `IntegrationTestBase` fija `max_allowed_packet` = 67108864 e `innodb_buffer_pool_size` = 134217728 (verde hoy; RED al subir `commons-lang3` sin anular el `my.cnf`; GREEN con el `my.cnf` anulado). · Evidencia: L4 · Risk: item 5 (configuración de los tests)
- [x] T2 · S1 · ruta: inline · Aceptación: un test arma una entrada tar con `commons-compress` (RED con 3.14.0, `NoSuchMethodError`); `backend/pom.xml` fija `commons-lang3.version` en 3.18.0 (GREEN). · Evidencia: L4 · Risk: item 5 (dependencia de producción)
- [ ] T3 · S1 · ruta: inline + `odd-verifier` · Aceptación: `dependency:tree` con 3.18.0, suite completa, `-Pimagen`, verificación independiente, vault. · Evidencia: — · Risk: item 5

## Log

- L1 (2026-10-27) Pedido original, textual: "dale" (a la recomendación de fijar `<commons-lang3.version>3.18.0</commons-lang3.version>`) y, después de la corrección, "vamos con tu recomendacion".
- L2 (2026-10-27) Exploración:
  - `dependency:tree`: `poi-ooxml` 5.5.1 → `commons-compress` 1.28.0 (compile) → `commons-lang3` 3.14.0 ("version managed from 3.18.0"). Es el único que pide `commons-lang3`.
  - De las 17 referencias de `commons-compress` 1.28.0 a `commons-lang3` (javap, nombre y descriptor), a la 3.14.0 le falta una sola: `SystemProperties.getUserName(String)`, que usa solo `TarArchiveEntry`. A la 3.19.0 no le falta ninguna. POI lee los xlsx como zip y el código no usa `commons-compress`: hoy no hay `NoSuchMethodError` posible en producción (la recomendación inicial exageraba ese riesgo; corregido con Luca antes de empezar).
  - El efecto real está en los tests: `MySQLContainer` copia `mysql-default-conf/my.cnf` (de `org.testcontainers:mysql` 1.21.4) a `/etc/mysql/conf.d` y la copia se cae. Sonda con el classpath de test: con 3.14.0, `conf.d` vacío, `max_allowed_packet` 67108864 y buffer pool 134217728; con 3.19.0, `my.cnf` copiado, 1048576 y 16777216.
  - CVE-2025-48924 (`ClassUtils.getClass`, desborde de pila con entradas largas, severidad media): afecta a `commons-lang3` desde 3.0 hasta antes de 3.18.0.
  - `src/test/java` crea 23 `MySQLContainer` (`IntegrationTestBase`, `ImagenDeProduccionTest`, `ArticuloRepositoryQueriesTest`, `ArticuloRepositoryFiltroImportacionTest` y 19 `MigracionV*Test`).
- L3 (2026-10-27) Decisión de diseño: en lugar de tocar los 23 archivos, `src/test/resources/mysql-default-conf/my.cnf` tapa el recurso de Testcontainers con el mismo nombre (`target/test-classes` va antes que los jars en el classpath de surefire). Cubre también los contenedores que se agreguen después; el test de T1 detecta si deja de funcionar.
- L4 (2026-10-27) T1 y T2. Con 3.14.0: `DependenciasTest` RED ("java.lang.NoSuchMethodError: 'java.lang.String org.apache.commons.lang3.SystemProperties.getUserName(java.lang.String)'") y `MySQLDeLosTestsTest` verde (caracteriza lo de hoy). Con `<commons-lang3.version>3.18.0</commons-lang3.version>`: `DependenciasTest` GREEN y `MySQLDeLosTestsTest` RED ("expected: 67108864L but was: 1048576L"). Con `src/test/resources/mysql-default-conf/my.cnf` (solo comentarios): `MySQLDeLosTestsTest`, `DependenciasTest` y `MigracionV29Test` 12/12, BUILD SUCCESS, sin `NoSuchMethodError` en el log. `dependency:tree`: `commons-lang3:jar:3.18.0:compile`, solo por `commons-compress`. Las 17 referencias de `commons-compress` 1.28.0 a `commons-lang3` existen en 3.18.0 (javap, nombre y descriptor).
