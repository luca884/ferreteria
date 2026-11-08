# recorrido-vendedor

Objetivo: comprobar en producción que los dos vendedores de la Ferretería de Prueba pueden hacer todo lo que la spec le da al Vendedor, y nada de lo que es solo del Dueño · Por qué: Luca quiere el mismo recorrido que se hizo con el Dueño (`odd/tasks/recorrido-dueno.md`), ahora con el Vendedor y en la app real.
Rama: `chore/recorrido-vendedor` desde `9570d058` · Entrega: single-pr · Runner: el recorrido es en el navegador (ego-browser) contra producción; si un hallazgo pide código, backend `./mvnw test -Dtest=<Clase>` / `./mvnw test`, frontend `npx ng test --watch=false --include=<spec>` / `npx ng test --watch=false` / `npx ng build` · Mirror Engram: `odd/recorrido-vendedor/tasks` (ok) · Modelo: caso 5 (grande e incierto: no se sabe qué va a aparecer), Opus de punta a punta; el recorrido lo hace un worker (respaldo de contexto de la sesión).
Pronóstico: desconocido hasta terminar el recorrido (T1 y T2 no cambian código) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: producción (https://frontend-production-c14f.up.railway.app), solo la «Ferretería de Prueba (uso interno)», como `vendedor1@prueba.test` (Sofía Acosta, tope de descuento 10 %) y `vendedor2@prueba.test` (Matías Romero, sin tope). Credenciales: vault, `wiki/ferreteria-de-prueba.md`. Todo lo que `docs/spec.md` le da al Vendedor (fila del rol en la tabla de roles y las reglas de cada CU), y que lo que es solo del Dueño no se vea en el front ni pase en el back (403). Ver [[seguridad-en-todas-las-capas]].
Fuera de alcance: ARCA real (la ferretería factura con el Simulador), cambiar la configuración de la ferretería, crear, resetear o desactivar usuarios, cambiar contraseñas, y arreglar código. Los hallazgos se le muestran a Luca antes de tocar código.
Restricciones: se escribe solo en la Ferretería de Prueba, con lo que un vendedor haría en un día normal. Cada documento creado se anota con su número. La caja queda cerrada al terminar, como estaba. No se entra como Dueño salvo para leer.

- S1: «todas son de la misma ferreteria??? proba si las venderos pueden hacer todo lo que deben hacer, quieor quepruebes sus funcionalidaddes como hicimos con los duenos» — Aceptación: cada función del Vendedor que define la spec probada con al menos uno de los dos vendedores, y el tope de descuento probado con los dos. Cada pantalla o acción solo del Dueño probada en el front (sin menú, guard de ruta) y en el back (403). Informe de hallazgos con evidencia y una recomendación por hallazgo — Checks: ego-browser contra producción.
- S2 (supuesto): «en producción» porque Luca pasó las credenciales de producción y pidió «proba vos con las credenciales y entra». La Ferretería de Prueba existe para probar en la app real sin tocar datos de clientes (`wiki/ferreteria-de-prueba.md`).
- S3: a «¿Cuáles de H1 a H4 querés que arregle?», Luca: «dale todos». Se arreglan con la recomendación de L3:
  - **H1:** un Vendedor que manda `autorizaExceso: true` recibe 403 con el mensaje de la spec («Autorizar el exceso es solo del Dueño», `docs/spec.md:117`) y no con «No tenés permiso para hacer esto». El Dueño sigue autorizando como hoy.
  - **H2:** los endpoints solo del Dueño de compras, ajustes de stock, vista previa del margen y escrituras de rubros, proveedores, artículos y formas de pago le dan 403 al Vendedor por ruta, antes de validar el cuerpo o los parámetros. Con un cuerpo inválido, el Vendedor recibe 403 y no 400. El Dueño con un cuerpo inválido sigue recibiendo el mismo 400. Lo que el Vendedor hace hoy (leer artículos, rubros, proveedores y formas de pago, alta y edición de clientes, vender) sigue igual.
  - **H3:** la spec describe la app de hoy: los dos botones de cobro en lugar de la casilla «No facturable» (CU-07, `docs/spec.md:129`) y «Última actualización» / «Costo actualizado el» en lugar de «Costo al» (CU-03 p. 5).
  - **H4:** en el pago Mixto, al entrar con el mouse (clic o foco) a una forma vacía, se sugiere el resto igual que con Enter. Lo que ya se escribió no se pisa y Enter sigue igual. La spec dice esto mismo.

## Tasks

- [x] T1 · S1, S2 · ruta: delegada (respaldo de contexto: la sesión ya pasó el deploy, el arreglo de la IP y su verificación) · Aceptación: recorrido completo con los dos vendedores, con la lista de qué se probó, qué pasó, los documentos creados y la caja cerrada · Evidencia: L3 (worker Opus, 42 capturas en el scratch de la sesión); spot check del parent: H1 en `RegistroVenta.java:318` y `GlobalExceptionHandler.java:92-95` · Risk: item 1 (escribe datos en producción, en la ferretería de prueba)
- [x] T2 · S1 · ruta: inline · Aceptación: informe a Luca con los hallazgos y una recomendación por cada uno · Evidencia: L3; informe en el chat el 2026-11-08 · Risk: none
- [ ] T3 · S3 (H1) · ruta: delegada (respaldo de contexto de ~150k: la sesión ya pasó el deploy, el arreglo de la IP y dos recorridos; un solo writer para T3 a T6) · Aceptación: S3 H1, con un test de integración que verifica el cuerpo del 403 · Evidencia: — · Risk: item 2 (seguridad)
- [ ] T4 · S3 (H2) · ruta: delegada (mismo writer) · Aceptación: S3 H2, con tests: el Vendedor con un cuerpo inválido recibe 403 en cada ruta nueva; el Dueño con el mismo cuerpo recibe el 400 de antes; lo que el Vendedor ya hacía sigue en 200/201 · Evidencia: — · Risk: item 2 (seguridad), item 3 (cambia la respuesta de la API para el Vendedor; pedido)
- [ ] T5 · S3 (H4) · ruta: delegada (mismo writer) · Aceptación: S3 H4, con un test en `dialogo-cobro.spec.ts` y una prueba en ego-browser en local · Evidencia: — · Risk: none
- [ ] T6 · S3 (H3, H4) · ruta: delegada (mismo writer) · Aceptación: S3 H3 y el texto de H4 en `docs/spec.md` · Evidencia: — · Risk: none
- [ ] T7 · S3 · ruta: inline + `odd-verifier` · Aceptación: suites de backend y frontend, `npx ng build`, prueba de punta a punta en local (antes con `main`, después con la rama) y `odd-verifier` por el item 2 · Evidencia: — · Risk: item 2

## Log

- L1 (2026-11-08) Pedido original, textual:
  > todas son de la misma ferreteria???
  > proba si las venderos pueden hacer todo lo que deben hacer, quieor quepruebes sus funcionalidaddes como hicimos con los duenos

  Antes, en la misma sesión: «Con ego lite entra a la URL del proyecto del front y entra con las credenciales de la ferreteria de prueba» y «proba vos con la scredenciales y entra». Como Dueño (solo lectura): 57 artículos, 8 rubros, 5 proveedores, 11 clientes, ventas hasta la N.º 21, 4 presupuestos, 3 remitos, 3 compras, 1 caja cerrada (30/10) y 3 usuarios (el Dueño y los dos vendedores). Facturación habilitada con el Simulador, punto de venta 1.
- L2 (2026-11-08) Referencias: `odd/tasks/recorrido-dueno.md` (método: recorrido como usuario real, hallazgos H#), `odd/tasks/guards-por-rol.md` (guards de ruta por rol), `docs/spec.md:27` (Vendedor: «Buscar artículos, vender, facturar, consultar stock, abrir y cerrar la caja») y las reglas por CU (presupuestos y remitos: `docs/spec.md:132-141`; cuenta corriente: `:117-119` y `:200-231`; compras y recargos solo del Dueño: `:289`; reintentar factura: `:335`).
- L3 (2026-11-08) Recorrido hecho (T1), con Sofía y Matías, sin errores graves. Las 24 rutas del Dueño redirigen a `/mostrador`, y los endpoints del Dueño responden 403 con un pedido bien formado.
  - **Probado y anda:**
    - el tope de descuento: Sofía, 10,5 % rechazado y 10 % aceptado; Matías, 15 % + 5 % aceptados;
    - las formas de pago: efectivo con vuelto, Mixto, Crédito +10 %, no facturable;
    - el Simulador (X y «SIMULADO») y la reimpresión;
    - la cuenta corriente dentro del límite. Con exceso, el Vendedor no puede autorizar y no ve cifras;
    - presupuesto y remito pasados a venta. Facturar un remito en menos cantidad da 403;
    - Artículos, Rubros y Proveedores de solo lectura, sin costo ni margen;
    - Clientes sin saldo ni límite;
    - la caja: ingreso y gasto (sin retiro), sin totales en vivo, cierre ciego e «Imprimir».
  - **Hallazgos:**
    - H1 (baja): el 403 de `autorizaExceso` dice «No tenés permiso para hacer esto» en vez del mensaje de la spec, porque `AccessDeniedException` cae en el manejador genérico.
    - H2 (baja): con un cuerpo o parámetro inválido, 4 endpoints del Dueño le dan 400 con la validación al Vendedor antes del 403: `compras/vista-previa`, `ajustes-stock`, `vista-previa-margen` y `PUT rubros`.
    - H3 (baja): la spec quedó atrás de la app: «No facturable» ahora son dos botones de cobro, y «Costo al» ahora es «Última actualización».
    - H4 (decisión de producto): en Mixto, el resto se sugiere solo con Enter, no con un clic.
  - **Creado:**
    - ventas 22 a 28 (Sofía: 22 a 27; Matías: 28);
    - el presupuesto 5 (Vendido) y el remito 4 (Facturado);
    - el cliente 12, «Ramón Prueba (recorrido vendedor)»;
    - la caja del 01/11, que estaba abierta: la cerró Sofía, sin diferencia;
    - la caja 3: la abrió Sofía y la cerró Matías, sin diferencia.

    Estado final: `SIN_CAJA`.
  - **No probado:** la reimpresión con CAE tardío, el diálogo de impresión real (se reemplazó `window.print`), el alta de cliente desde F9, y los POST de alta del Dueño con cuerpo válido (se cubrieron con PUT y DELETE sobre un id inexistente).

  Próximo paso: Luca decide qué hallazgos se arreglan.
- L4 (2026-11-08) Luca: «dale todos» (a «¿Cuáles de H1 a H4 querés que arregle?»). S3 y T3 a T7. Pronóstico: ~250 líneas (backend ~150 con tests, frontend ~60, spec ~20). Un solo writer (Opus) por el respaldo de contexto de la sesión.
