# suite-rapida

Objetivo: que la suite del backend tarde bastante menos sin perder lo que prueba · Por qué: hoy suma ~28 min de tests y frena cada cierre.
Rama: `test/suite-rapida` desde `1539d70` · Entrega: single-pr · Runner: `cd backend && ./mvnw test` (focalizado `-Dtest=<Clase>`) · Mirror Engram: `odd/suite-rapida/tasks` (ok) · Ruteo: caso 5 (Opus de punta a punta), aprobado por Luca: «dale».
Pronóstico: ~700 líneas cambiadas (helper + test de fidelidad ~250, 30 clases de migraciones ~450 casi todo borrado) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: infraestructura de los tests de migraciones del backend (`Migracion*Test` con MySQL propio). Fuera de alcance: código de producción, migraciones, `IntegrationTestBase`, paralelizar surefire (se mide al final y se propone aparte).
Restricciones: los tests de migraciones tienen que seguir probando lo mismo: base nueva por test, en la versión anterior, contra MySQL 8.4 real.

- S1: "Cuando el proyecto esté completo: acelerar la suite del backend" (Próximos Pasos, pedido de Luca del 2026-11-02) y «dale vamos con eso» (2026-11-03) — Aceptación: la suite completa baja de forma medible frente a la línea de base (L2) con el mismo número de tests y 0 fallas — Checks: `./mvnw test`
- S2: «Migrar una sola vez por clase a una base modelo y clonarla para cada test […] Usar un solo MySQL para todas las clases de migraciones» (propuesta aprobada, «dale») — Aceptación: cada versión se migra una vez por JVM; cada test recibe un clon propio — Checks: `-Dtest=PlantillaDeMigracionTest`, `-Dtest='Migracion*Test'`
- S3: «voy a validar el clon contra la base migrada (columnas, índices y FKs iguales) y a romper una migración a propósito para confirmar que los tests siguen fallando» — Aceptación: test de fidelidad que compara clon y base migrada para todas las versiones (columnas, índices, FKs, CHECK, opciones de tabla, datos, historial de Flyway, AUTO_INCREMENT); una migración rota a propósito hace fallar su `Migracion*Test` — Checks: `-Dtest=PlantillaDeMigracionTest` y mutación en copia descartable

## Tasks

- [x] T1 · S2, S3 · ruta: inline · Aceptación: `PlantillaDeMigracion` (contenedor compartido + plantilla por versión + clon) y `PlantillaDeMigracionTest` RED→GREEN · Evidencia: RED (no compila sin el helper), GREEN 3/3; mutación del clon con `CREATE TABLE … LIKE` → falla «esquema en la V1»; commit `ac272bb` · Risk: item 6 (un clon infiel daría verde sin probar)
- [x] T2 · S1, S2 · ruta: inline · Aceptación: las clases `Migracion*Test` con MySQL propio usan la plantilla; mismos tests, 0 fallas · Evidencia: `-Dtest='Migracion*Test,PlantillaDeMigracionTest'` 527/0 en 5:47 (suma de clases 345 s, antes ~1086 s); ningún `@Test` borrado; commit `f49a1f4` · Risk: item 6
- [x] T3 · S1, S3 · ruta: inline · Aceptación: mutación de una migración detectada; suite completa medida contra L2 · Evidencia: mutación de la V32 detectada (L3); `./mvnw test` 5967/0 (5964 + los 3 de `PlantillaDeMigracionTest`), BUILD SUCCESS, 14:51 de reloj; suma por clase 14,9 min (antes 28,4), migraciones 5,3 min (antes 18,1) · Risk: —
- [ ] T4 · S1, S2, S3 · ruta: delegada (Verification trigger: Risk item 6) · Aceptación: `odd-verifier` sin bloqueantes · Evidencia: — · Risk: —

## Log

- L1 (2026-11-03) Pedido original, textual:
  > ddale vamos con eso
  (respuesta a: «El siguiente paso que propongo es acelerar la suite del backend […] ¿Arrancamos con eso?»; después, «dale» al caso 5 de ruteo.)
- L2 (2026-11-03) Exploración (reportes de surefire de la última corrida, 517 clases, 5976 tests): 28,4 min de tests sin `ImagenDeProduccionTest`; las 36 clases `Migracion*Test` suman 18,1 min (64 %). `MigracionV32Test` 284 s con 107 tests: cada `@BeforeEach` crea una base y migra V1→V31 (`backend/src/test/java/com/ferreteria/facturacion/MigracionV32Test.java:79`); 30 clases levantan su propio `mysql:8.4`. Las migraciones no crean vistas, triggers, rutinas ni columnas generadas; sí CHECK y FKs (por eso el clon usa `SHOW CREATE TABLE` y no `CREATE TABLE … LIKE`, que no copia FKs). Integración (`*IntegrationTest`): 197 clases, ~8 min.
- L3 (2026-11-03) T1 y T2. Clonar la V31 cuesta ~360 ms contra ~2,6 s de migrar desde cero. Cada plantilla sale de la anterior más cercana y migra lo que falta (armar las 40 cuesta una migración completa); `PlantillaDeMigracionTest` tarda ~40 s. Probado y descartado: `--skip-log-bin` + `innodb_flush_log_at_trx_commit=0` (~10 %, no vale cambiar la config del MySQL). Variantes a mano: `MigracionV13Test` arranca sin base y el primer `migrarHasta` clona; `MigracionV38Test` sigue calculando «la anterior a la 38». Gotcha: `sd` 1.0 procesa por línea, para regex multilínea hace falta `-A`. Mutación de la V32 (`punto_venta` INT→BIGINT) → `MigracionV32Test.tablasYColumnas` falla; revertida.
