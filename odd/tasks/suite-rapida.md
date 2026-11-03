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

- [ ] T1 · S2, S3 · ruta: inline · Aceptación: `PlantillaDeMigracion` (contenedor compartido + plantilla por versión + clon) y `PlantillaDeMigracionTest` RED→GREEN · Evidencia: — · Risk: item 6 (un clon infiel daría verde sin probar)
- [ ] T2 · S1, S2 · ruta: inline · Aceptación: las clases `Migracion*Test` con MySQL propio usan la plantilla; mismos tests, 0 fallas · Evidencia: — · Risk: item 6
- [ ] T3 · S1, S3 · ruta: inline · Aceptación: mutación de una migración detectada; suite completa medida contra L2 · Evidencia: — · Risk: —

## Log

- L1 (2026-11-03) Pedido original, textual:
  > ddale vamos con eso
  (respuesta a: «El siguiente paso que propongo es acelerar la suite del backend […] ¿Arrancamos con eso?»; después, «dale» al caso 5 de ruteo.)
- L2 (2026-11-03) Exploración (reportes de surefire de la última corrida, 517 clases, 5976 tests): 28,4 min de tests sin `ImagenDeProduccionTest`; las 36 clases `Migracion*Test` suman 18,1 min (64 %). `MigracionV32Test` 284 s con 107 tests: cada `@BeforeEach` crea una base y migra V1→V31 (`backend/src/test/java/com/ferreteria/facturacion/MigracionV32Test.java:79`); 30 clases levantan su propio `mysql:8.4`. Las migraciones no crean vistas, triggers, rutinas ni columnas generadas; sí CHECK y FKs (por eso el clon usa `SHOW CREATE TABLE` y no `CREATE TABLE … LIKE`, que no copia FKs). Integración (`*IntegrationTest`): 197 clases, ~8 min.
