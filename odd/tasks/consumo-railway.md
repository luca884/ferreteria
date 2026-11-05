# consumo-railway

Objetivo: bajar el gasto de Railway de la ferretería sin perder rendimiento · Por qué: casi todo el costo es memoria tomada (backend ~1,16 GB y MySQL ~881 MB de pico en 7 días); proyección de noviembre ~$12.
Rama: `chore/consumo-railway` desde `d324791` · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Pimagen` · Mirror Engram: `odd/consumo-railway/tasks` (ok)
Ruteo: caso 5 (entrega y entorno, item 5) · Pronóstico: ~60 líneas

## Specs

Alcance: parámetros de memoria de la JVM del backend (`backend/Dockerfile`) y caché de InnoDB de MySQL en Railway (comando de arranque, lo cambia Luca).
Fuera de alcance: Serverless o dormir servicios (arranque en frío de la JVM y tareas programadas); el tope de 768 MB del heap (lo necesitan las listas grandes).
Restricciones: sin perder rendimiento; los límites de cada servicio (2 vCPU y 3 GB) los puso Luca en Railway.

- S1: "dale me parece bien ese cambio" (bajar la memoria de MySQL y que la JVM devuelva la memoria que no usa) — Aceptación: la imagen corre con G1 explícito, GC periódico, ratios de heap libre y `MALLOC_ARENA_MAX=2`; después de un pico la JVM devuelve la memoria. — Checks: `./mvnw test -Pimagen`
- S2: "tambien restringi el uso de Vcpu y la ram que puede sar cada servicio del poryuecto de la ferreteria, a 3 de ram para cada uno" — hecho por Luca; verificado con `railway status --json`: los tres servicios con `cpu: 2` y `memoryBytes: 3000000000`.

## Tasks

- [x] T1 · S1 · ruta: inline · Aceptación: JVM con `-XX:+UseG1GC -XX:G1PeriodicGCInterval=300000 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30` y `MALLOC_ARENA_MAX=2` · Evidencia: RED `ImagenDeProduccionTest#laJvmDevuelveLaMemoriaQueNoUsa` → GREEN `-Pimagen` 10/0 · Risk: item 5 (imagen de producción)
- [x] T2 · S1 · ruta: Luca en Railway · Aceptación: MySQL arranca con `--innodb-buffer-pool-size=256M` · Evidencia: `railway environment config` → `startCommand` de MySQL con `--innodb-buffer-pool-size=256M`; deploy `460ec875` (2026-11-02 12:56Z) en `SUCCESS` · Risk: item 5 (reinicia la base)
- [x] T3 · S1 · ruta: Luca + agente · Aceptación: 24-48 h después del deploy, `railway metrics --all --since 1d --memory` muestra menos memoria que la línea de base · Evidencia (2026-11-03, serie horaria de `railway metrics --raw`, ver L5): en estado estable, backend ~390-430 MB (antes ~620-760 de promedio diario y 1,16 GB de pico), MySQL ~300-316 MB (antes ~480 de promedio y 881 MB de pico), frontend 14-40 MB (igual) · Risk: none

## Log

- L1 (2026-11-02) Pedido, textual:
  > coom es posible que de lo que vamos del mes, el proyecto de la ferreteria haya gastado mas uso, osea mas dinero gastador que el poryecto de pialtes que lleva meses en desarrollo, que  puedo configurar o hacer para que baje la tarigfa sin perder rendimiento

  > hablo del gasto de railway, nada mas tengo esos dos proeytos

  > dale me parece bien ese cambio, tambien restringi el uso de Vcpu y la ram que puede sar cada servicio del poryuecto de la ferreteria, a 3 de ram para cada uno
- L2 (2026-11-02) Diagnóstico (`railway usage`): octubre Sendo $10,78 y ferreteria $1,55 (estuvo desde el 27/10); noviembre a 2 días, ferreteria $0,80 y Sendo $0,75. En ferreteria, memoria ≈ 99 % del costo (backend $0,455, MySQL $0,323, frontend $0,027). Línea de base de memoria (`railway metrics`): pico de 7 días backend 1,16 GB, MySQL 881 MB, frontend 52 MB; recién reiniciados (tras los límites de Luca) 601 MB, 389 MB y 14 MB.
- L3 (2026-11-02) Corrección: había propuesto apagar `performance_schema`, pero el arranque de MySQL en Railway ya tiene `--performance_schema=0`. Lo que crece es `--innodb-buffer-pool-size=1G`; el volumen ocupa 306 MB con logs y tablas del sistema, así que 256M alcanzan para tener todos los datos en memoria.
- L4 (2026-11-02) Prueba local de la JVM (Java 17, 400 MB de pico y reposo): sin los parámetros quedan 720 MB tomados; con G1 periódico y ratios, baja a 13 MB al correr el GC periódico (3 s en la prueba, 5 min en producción). `ImagenDeProduccionTest` 10/0, incluido el lector de PDF (la JVM hija hereda `MALLOC_ARENA_MAX`). Suites completas no aplican: no cambió código de `src/main` ni del front.
- L5 (2026-11-03) Medición de T3 con `railway metrics --service <s> --memory --since 7d --raw --json` (el resumen de `--all` muestra el valor actual, no el pico; `--raw` no se combina con `--all`). Serie horaria, desde que Luca puso los límites y reinició (2026-11-02 ~12:40Z):
  - Estado estable: backend 387-426 MB, MySQL 290-316 MB, frontend 14-40 MB. Antes, en los días tranquilos, el backend quedaba plano en ~720-760 MB y MySQL en ~480 MB.
  - Los picos del backend (897/1095 MB a las 17-18Z, 815 a las 23Z, 1460 a las 03Z) coinciden con deploys y vuelven a ~410 MB en la hora siguiente; sin los parámetros de la JVM antes se quedaba arriba.
  - Límites de la medición: ~15 h de estado estable y no 24-48 h; la serie es de un punto por hora, así que un pico corto puede no verse; los deploys de hoy (cuatro, con los dos del backend) meten picos propios.
  - Gasto: `railway usage projects` (período 31/10-30/11) muestra ferretería $0,96 y Sendo $0,99. En L2 la ferretería llevaba $0,80 a los 2 días (~$0,40/día); desde los cambios sumó ~$0,16 en ~15 h (~$0,25/día). Cuenta de control con la memoria estable (~0,72 GB ≈ 410 + 305 + 20 MB a ~$0,0139 por GB-hora) = ~$0,24/día frente a ~$0,40/día antes, o sea cerca de $7 al mes contra los ~$12 proyectados. Aproximado: los deploys de hoy suman picos.
  - Cierra T3 con la aceptación cumplida (menos memoria que la línea de base). Re-medir con `railway usage projects` en una semana si se quiere confirmar el gasto del mes.
