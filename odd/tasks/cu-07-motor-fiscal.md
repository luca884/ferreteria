# cu-07-motor-fiscal

Objetivo: el módulo `facturacion` arma y numera la factura como ARCA la autoriza (letra A/B/C, desglose del IVA por alícuota, número por serie con recuperación después de un timeout) contra un simulador del WSFEv1 4.8, sin enganche en las ventas. · Por qué: rama 2 de la fase 5 (`wiki/plan-facturacion.md` del vault); es lo que hace correcta una factura antes de que la venta la pida (rama 3).
Rama: `feat/cu-07-motor-fiscal` desde `ed315cf` (cabeza de `feat/cu-01-datos-fiscales`, sin mergear) · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Dtest=<Clase>` / `cd frontend && npx ng test --watch=false --include=<spec>` · Mirror Engram: `odd/cu-07-motor-fiscal/tasks` (ok)
Ruteo: caso 5, Opus de punta a punta; Luca: «dale vamos con eso» (2026-10-28). Los writers delegados corren también con Opus. · Pronóstico: ~4.000 líneas cambiadas · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: CU-07 pasos 3 y 5 sin pantalla de venta: paquete `facturacion`, ConfigArca, registro fiscal, serie, intentos, motor de numeración con recuperación, simulador WSFEv1 4.8 con estado propio, y en el panel del super admin la marca «de prueba», el ambiente SIMULADOR y «facturación habilitada». · Fuera de alcance: enganche en la venta y respuesta del cobro (rama 3); pantallas del mostrador e impresión, QR (rama 4); certificado, WSAA, SOAP y TLS (ramas 5 y 6); job de reintento y bandeja (rama 7); NC (rama 8), salvo los códigos 3, 8 y 13 en la tabla de mapeo.
Restricciones: la ferretería sale solo del JWT; toda tabla nueva con `ferreteria_id`, FK compuestas y `TenantScopedRepository`; todo endpoint nuevo con su caso de aislamiento; plata en `BigDecimal`; tiempo del `Clock` inyectado; migraciones aditivas V32 y V33 con rollback probado con guarda; cada unidad con ítem de riesgo en su propio commit y `odd-verifier` (opus) sobre ese commit.

- S1: D2, aprobada por Luca el 2026-10-28 («perfecto vamos con tus recomendaciones, lo que podamos usar del gds mejor»), textual: "¿Corregimos la tabla A/B/C de la spec (spec.md:275-279) a la vigente de ARCA? Un emisor RI da A al RI y al Monotributista, con la leyenda de la Ley 27.618, y B al Consumidor Final y al Exento. Un emisor Monotributista o Exento da C siempre, sin IVA. Todo pedido lleva CondicionIVAReceptorId." — Aceptación: la letra es una función pura con tabla de verdad exhaustiva en JSON (3 condiciones del emisor × 4 del cliente, más la venta sin cliente) que comparten el test de dominio y el simulador; mapeo a CbteTipo 1, 6 y 11 (3, 8 y 13 para la NC), DocTipo 80, 86, 96 y 99 y CondicionIVAReceptorId 1, 4, 5 y 6; el simulador rechaza con 10243 y 10246 lo que el anexo del manual no admite. — Checks: test de la tabla de verdad, tests del simulador.
- S2: D3, aprobada por Luca el 2026-10-28 (misma respuesta), textual: "¿Quién fija el ambiente de ARCA de cada ferretería? La propuesta: el simulador lo asigna solo el super admin, y solo a una ferretería marcada «de prueba» (hoy, la Ferretería de Prueba). Homologación o producción salen de la CA que firmó el certificado validado, de producción no se vuelve y el Dueño nunca elige el ambiente." Con el ajuste aprobado al preguntar: "la «facturación habilitada» de cada ferretería también la prende el super admin, y exige los datos de CU-01 completos." — Aceptación: solo el super admin pone la marca «de prueba», asigna SIMULADOR (nunca a una ferretería sin la marca ni con registros de homologación o producción) y prende «facturación habilitada» (solo con razón social, condición de IVA, domicilio comercial, inicio de actividades e Ingresos Brutos cargados, y con ambiente asignado); el Dueño y el Vendedor reciben 403; otra ferretería no ve nada. El ambiente y el punto de venta no cambian mientras haya registros abiertos. — Checks: test de integración del panel, tests de dominio de ConfigArca.
- S3: Luca, 2026-10-28, ante «mientras una ferretería factura contra el simulador, ¿quién fija su punto de venta?», textual: "Fijo en 1 (Recomendado)" — "Al asignar el simulador, el punto de venta queda en 1, sin pantalla. Es lo más simple: en el simulador el número no tiene efecto fiscal. El real lo carga el Dueño en la rama 5." — Aceptación: asignar SIMULADOR deja el punto de venta en 1; no hay campo para cambiarlo en esta rama. — Checks: test de ConfigArca.
- S4: criterio de Luca para toda la fase, textual: "perfecto vamos con tus recomendaciones, lo que podamos usar del gds mejor" — Aceptación: las dudas de producto se resuelven como GDS cuando GDS lo muestra; si no, se anotan como supuesto.
- S5 (supuesto, del plan revisado, regla «Nunca se reenvía a ciegas»): la serie (ferretería, ambiente, punto de venta, tipo) se toma con un lease con dueño en la base; el número y la CbteFch se asignan al enviar y el intento se guarda en una transacción corta antes de llamar; la llamada va sin transacción ni lock; el resultado se guarda en otra transacción corta con UPDATE condicional; timeout de conexión = no enviado; timeout de lectura o 5xx = EN_VERIFICACION, que traba la serie hasta resolverse con FECompUltimoAutorizado y FECompConsultar (N o más: adopta el CAE solo si coinciden todos los campos, si no alerta sin renumerar; N−1: reenvía el mismo intento; menos de N−1: alerta de serie desincronizada); un rechazo libera el número; FIFO por serie; antes de enviar, la CbteFch no puede ser anterior a la última autorizada, entra en N−5 y en el mismo mes si es posterior; lo que no entra pasa a FUERA_DE_FECHA sin reintento y sin cambiar la fecha. — Aceptación: batería con MySQL real (molde `VentaConcurrenciaIntegrationTest`): autorizaciones simultáneas en una serie dan números consecutivos sin 10016; un corte después de guardar el intento se recupera y adopta el CAE sin duplicar; un CAE huérfano que no coincide queda en alerta; un rechazo libera el número; dos instancias con leases nunca usan el mismo N; ninguna conexión del pool queda tomada durante la llamada.
- S6 (supuesto, del plan revisado): el simulador va detrás de un puerto en términos de WSFEv1 (FECompUltimoAutorizado, FECAESolicitar, FECompConsultar), con estado en tablas propias con `ferreteria_id` que sobrevive a un reinicio; valida 10013, 10015 (formato), 10016, 10023, 10040, 10048, 10051 (HALF_EVEN y tolerancias), 10061, 10071, 10152, 10197, 10243 y 10246; devuelve como observaciones 10041, 10063, 10188, 10217 y 10237; usa códigos y mensajes del manual 4.8 con la versión escrita en el código; latencia configurable; fallas inyectables solo en tests (timeout antes y después de autorizar, 502, rechazo con observaciones). Rechaza todo lo que no sea ambiente SIMULADOR. — Aceptación: tests del simulador por código; reinicio conserva el estado.
- S7 (supuesto, del plan revisado): desglose fiscal por alícuota a nivel comprobante: BaseImp = redondeo(T/(1+a)) e Importe = T − BaseImp; en la C, ImpNeto es el total, ImpIVA 0 y sin array de IVA; ImpTotal es siempre lo cobrado. — Aceptación: dos y tres renglones de $ 100 al 21 % pasan 10051 en el simulador; sumados por renglón, como hoy, fallan.
- S8 (supuesto, del plan revisado): fronteras de módulos en `ModulosArchTest`, en reemplazo de `todaviaNoExisteElPaqueteFacturacion`: solo facturacion conoce a ARCA (escaneo de hosts en el código fuente); `facturacion.api` usa solo el JDK; ventas usa facturacion solo por su api; facturacion no depende de ventas, clientes, stock ni catalogo; el registro fiscal tiene un único escritor, sin setters del CAE; facturacion no usa `HoraArgentina.ahora()`, `now()` ni `Instant.now()`. — Aceptación: las reglas pasan y fallan ante una violación armada a propósito.
- S9 (supuesto, del plan revisado): sin enganche en ventas y apagado en producción: una ferretería sin ConfigArca vende exactamente como hoy; la suite existente sigue verde sin cambios en ventas; V1006 de dev-data deja CUIT demo con dígito verificador válido (30-00000001-5 y 30-00000002-3); la marca «de prueba» de las demo se pone desde el panel (ver L3). — Aceptación: suite completa verde; `MigracionV6Test` con V32, V33 (y la lista de dev-data si aplica).
- S10 (supuesto, de la crítica de completitud): V32 lleva un CHECK de coherencia del registro fiscal (AUTORIZADO ⇔ CAE, vencimiento, número y fecha presentes; número nulo en PENDIENTE y RECHAZADO); el commit de docs actualiza el «Plan de implementación» de la spec (la fase 1 pasa a simulador más homologación con la piloto, rama 11; la fase 5 lista las ramas); el armado del QR va a vivir en facturacion (rama 4), así que la regla de hosts no necesita excepción. — Aceptación: test de migración del CHECK; spec actualizada.

## Tasks

- [x] T1 · S1, S2, S3, S7, S10 · ruta: delegada (writer «docs»; disparador: backstop de contexto ~150k, en paralelo con T2 y T3) · Aceptación: commit `docs:` que pasa D2, D3 (con el ajuste y el punto de venta fijo en 1), el modelo de datos (registro fiscal y ConfigArca en facturacion, Comprobante sin CAE ni estado fiscal), módulos, Integración ARCA (simulador, ambiente por registro, número al enviar, recuperación) y el Plan de implementación a `docs/spec.md` · Evidencia: docs pasivos, sin RED; readback estructural del writer (6 tablas con columnas parejas, fences balanceados, referencias cruzadas con destino); muestra del padre: tabla A/B/C y CU-01 paso 2 correctos; `docs/spec.md` +93/−24 · Risk: none (docs)
- [x] T2 · S1, S7, S8 · ruta: delegada (writer «dominio», sigue con T5; disparador: unidades disjuntas en paralelo con T1 y T3, y backstop) · Aceptación: paquete `facturacion` con la letra A/B/C, el mapeo a WSFEv1 y el desglose por alícuota como funciones puras; tabla de verdad JSON en test resources; reglas nuevas de `ModulosArchTest` · Evidencia: RED con stubs (82 tests, 2 fallas y 11 errores; `todaviaNoExisteElPaqueteFacturacion` violada 10 veces); GREEN `LetraTablaDeVerdadTest` 2, `DesgloseFiscalTest` 7, `MapeoWsfeTest` 4, `ModulosArchTest` 45, `TenantScopingArchTest` 24; las 5 reglas nuevas fallan con una violación sembrada en una copia scratch; commit `7525aeb` (T1: `b329c10`) · Risk: none (dominio puro, sin persistencia)
- [x] T3 · S2, S5, S9, S10 · ruta: delegada (writer «motor», sigue con T6; diseña V32 para el motor de T6) · Aceptación: V32 aditiva (`config_arca` con la marca «de prueba» (L3), `comprobante_fiscal` con CHECK de coherencia, `comprobante_fiscal_alicuota`, `intento_fiscal`, `serie_fiscal`; FK compuestas; UNIQUE (ferreteria_id, ambiente, punto_venta, cbte_tipo, numero)); `MigracionV32Test` con rollback con guarda; `MigracionV6Test` con la 32 · Evidencia: RED 91 tests (89 errores «No migration with a target version 32», 2 fallas); GREEN `MigracionV32Test` 90/0, con `MigracionV6Test#historialDeMigraciones` y `MigracionV31Test` 100/0; `MigracionV29Test#ningunaFkDeNegocioSoloPorId` 1/1; control del padre `MigracionV6Test` 1/1; commit `6eab13c`; `odd-verifier`: B1 (L7) corregido en `1e07edf` y cerrado en el recheck con el cliente real (L18) · Risk: item 1 (migración) → commit propio
- [x] T4 · S2, S3, S9 · ruta: delegada (después del commit de T3) · Aceptación: ConfigArca y la marca «de prueba»; endpoints y panel del super admin para marcar, asignar SIMULADOR (punto de venta 1) y prender «facturación habilitada» con sus requisitos; 403 para Dueño y Vendedor; aislamiento; V1006 de dev-data · Evidencia: RED `ConfigArcaTest` 19 (stub), `TenantScopingArchTest#todosLosRepositoriosDeFacturacionSonDeTenant` (repo sembrado con `JpaRepository`), `ConfigArcaAdminIntegrationTest` 11/13 (404), `DatosDemoCuitIntegrationTest`, 11 specs del panel; GREEN backend focalizado 148/0, migraciones V6/V7/V8/V24-V27 55/0, frontend 47/47, `ng build` OK; control del padre 32/32; commits `4c2c3b7` y `8328ae1` (corrección); `odd-verifier`: B1 cerrado en el recheck (62/0; `totalVentas`, `ventasDelDia` y `puntoVentas` sembradas siguen fallando; los 404 no crean filas) · Risk: item 2 (cerrojo del ambiente y permisos)
- [x] T5 · S1, S6, S7 · ruta: delegada (writer «dominio», después de T2) · Aceptación: puerto WSFEv1 y simulador con V33, validaciones y observaciones del manual 4.8, latencia y fallas inyectables en tests, estado que sobrevive a un reinicio · Evidencia: RED V33 35 (34 errores y 1 falla), simulador 97 (stub), ajuste de `Obs` 114 con 47 fallas; GREEN del writer 218/0; control del padre en el árbol principal, con T4: 254/0; commit `302856b`; `odd-verifier` sin bloqueantes (231/0 y 191/0 focalizados, 7 sondas propias); sus sondas pasan a `SimuladorWsfeBordesTest` (se corre en T7) · Risk: item 1 (V33) y item 6 (la recuperación se prueba solo contra el simulador hasta la rama 11)
- [x] T6 · S5, S8 · ruta: delegada (writer «motor», después de T3 y T5) · Aceptación: registro fiscal con único escritor, serie con lease, intento antes de llamar, llamada sin transacción, resultado con UPDATE condicional, EN_VERIFICACION y recuperación, FIFO, chequeo de CbteFch y FUERA_DE_FECHA, rechazo que libera el número; batería de concurrencia con MySQL real · Evidencia: RED con stubs, 110 tests (9 fallas y 29 errores); GREEN del writer focalizado 280/0 y concurrencia 9/9 en 5 corridas (delta de deadlocks 0); cada regla de ArchUnit falla con su semilla; control del padre 30/0 (con `SimuladorWsfeBordesTest`); commit `c2d1bf4`; `odd-verifier`: B1 (reenvío N−1 sin ventana, L19) corregido en `46bf0ec` (RED→GREEN, sondas en `MotorFiscalBordesIntegrationTest` 23/0) y cerrado en el recheck (L21; sus sondas en `4e53955`, `MotorFiscalReenvioIntegrationTest` 8/0) · Risk: item 4 (concurrencia del motor)
- [x] T7 · todas · ruta: inline (suites) + `odd-verifier` por commit de riesgo · Aceptación: suite backend, suite frontend y `ng build` verdes; ego-browser del panel del super admin; verificador sin bloqueantes · Evidencia: suite backend sobre `4e53955` 5088/0, BUILD SUCCESS en 21:27 min; frontend 132 archivos verdes y `ng build` OK sobre `c2d1bf4` (el frontend no cambió después); ego-browser del panel OK (L20); los 4 verificadores sin bloqueantes abiertos (L7/L18, L10/L13, L16, L19/L21) · Risk: item 1 (V32, V33), item 4 (motor), item 6 (la recuperación se prueba solo contra el simulador hasta la rama 11)

## Log

- L1 (2026-10-28) Pedido original, textual. Luca, después del cierre de la rama 1: «okey continuamos». Ante la propuesta de ruteo («caso 5: Opus de punta a punta, porque es grande y de alto riesgo») y de arrancar desde `feat/cu-01-datos-fiscales`: «dale vamos con eso». Ante el punto de venta del simulador: «Fijo en 1 (Recomendado)». D2 y D3: «perfecto vamos con tus recomendaciones, lo que podamos usar del gds mejor».
- L2 (2026-10-28) Exploración (`odd-explorer`, sonnet):
  - ArchUnit: `ModulosArchTest.java:183-186` `todaviaNoExisteElPaqueteFacturacion` (sin `that()`, falla con la primera clase de `com.ferreteria.facturacion..`); moldes: frontera `:58-62`, puerto solo JDK `:208-212` y `:626-631`, escritor único `:216-225`, append-only `:229-241`, escaneo de fuente por regex `:157-173`; plataforma `:606-640`. `TenantScopingArchTest.java:48-62` (finders sin scope); la regla «todo repo del paquete es de tenant» es por paquete (`:187-193`, `:267-276`), y `:239-265` exige que los `default` llamen a `TenantContext.requireFerreteriaId()`. `TenantScopedRepository.java:15-33`, `UnscopedTenantAccess.java:13-18`; job por ferretería: `RecalculoDiarioJob.java:18-19,51-60`.
  - Comprobante: `V4__ventas_y_stock.sql:7-50` (`tipo`/`estado` VARCHAR sin CHECK; `item_comprobante.alicuota_iva` VARCHAR con el nombre del enum `catalogo.model.AlicuotaIva`); `uk_comprobante_ferreteria_y_id` en `V29__fk_por_ferreteria.sql:84`; `numerador_comprobante` (`V4:88-95`, serie interna, distinta de la de ARCA). `Comprobante.java:35-43,68`; `EstadoComprobante` `{SIN_FACTURAR, NO_FACTURABLE}`. IVA por renglón: `RenglonVenta.java:56-58` (neto = total×100/(100+iva) HALF_UP) y `TotalesVenta.java:22-36` (suma por renglón).
  - Concurrencia: `VentaConcurrenciaIntegrationTest.java:43-66` (pool, latches), `support/BarreraDeLocks.java:18-36` (delta de deadlocks 0), sin HTTP: `CuentaCorrienteConcurrenciaPuertoIntegrationTest.java:32-156`. `IntegrationTestBase.java:55-60` (MySQL 8.4, `--max-connections=1000`), `:81-88` login. `application-test.yml`: `app.programacion.habilitada: false`.
  - Tiempo: bean `Clock` en `common/config/RelojConfig.java:19-22`; `HoraArgentina` estático (`:18`, `:22`); `Clock.fixed` solo en tests unitarios por constructor. Hueco: no hay forma de fijar el `Clock` dentro del contexto Spring.
  - dev-data: V1000-V1005; CUIT demo `30-00000001-1` y `30-00000002-2` inválidos según `common/Cuit.java:29-37` (esperados 5 y 3). `MigracionV8/24/25/26/27/31Test` migran `db/dev-data` solos sobre una base vieja (`MigracionV31Test:147,152`).
  - Panel: `plataforma/web/FerreteriasAdminController.java:22-54` (`PATCH /{id}/activo`, reset), `FerreteriasAdminService.java:81-146`, `FerreteriaAdminResponse.java:9-16`; puerto hacia plataforma: `auth/.../UsuariosParaPlataformaImpl`. Frontend: acciones en `<app-barra-acciones>` sobre la fila elegida (`admin-ferreterias.html:1-20`, `.ts:181-237`).
  - Migraciones: lista en `MigracionV6Test.java:189` (el DisplayName `:182` dice «V1 a V26»); molde `MigracionV31Test.java:38-216` y `rollback-v31-datos-fiscales.sql`; CHECK de coherencia estilo V22: `V22__anulacion_cobranza_extracto.sql:37-43`. UPDATE condicional: `ConfiguracionCotizacionRepository.java:31-41`, `ImputacionRepository.java:180-184`; contador atómico `NumeradorComprobanteRepository.java:16-22`; locks `CajaRepository.java:32-55`.
  - spec: tabla A/B/C `:276-285`; modelo de datos `:320-366` (Tenant `:328`, ConfigArca `:329`, Comprobante `:340`, Módulos `:366`); Integración ARCA `:368-394`; Arquitectura `:396-428` (`:418`); Plan de implementación `:451-475` (fase 1 `:459-461`, fase 5 `:470-471`).
  - Sin menciones previas de `ConfigArca`, `wsfe` ni hosts de ARCA en código, configuración o docs.
- L3 (2026-10-28) Decisiones técnicas (no de producto):
  - `de_prueba`, ambiente, punto de venta y «facturación habilitada» viven en `config_arca`, no en `ferreteria`: todo lo fiscal es de `facturacion`. `plataforma` lo lee y lo cambia por `facturacion.api` (molde `UsuariosParaPlataformaImpl`).
  - `facturacion` puede depender de `ferreteria` (datos fiscales completos, copia del emisor): la regla del plan solo prohíbe ventas, clientes, stock y catalogo.
  - `facturacion` no usa `catalogo.model.AlicuotaIva`: modela la alícuota con el Id de WSFEv1 (3 = 0 %, 4 = 10,5 %, 5 = 21 %, 6 = 27 %, 8 = 5 %, 9 = 2,5 %) y su porcentaje en `BigDecimal`; la rama 3 traduce.
  - `facturacion` recibe el `Clock` inyectado. Para fijar la hora en los tests de integración se agrega un `Clock` controlable en la configuración de test (hoy no existe).
  - `TenantScopingArchTest` suma «todo repositorio de `facturacion..repository` es de tenant».
  - V1006 solo corrige los CUIT demo, sin tocar objetos de V32/V33, porque seis tests de migración corren `db/dev-data` solo sobre bases viejas. La marca «de prueba» de las ferreterías demo se pone desde el panel, que es el flujo real.
  - Writers con Opus (caso 5). El writer «motor» diseña V32 pensando en el motor de T6 y lo continúa; el writer «dominio» sigue con el simulador (T5), que comparte la tabla de verdad. · Próximo paso: T1, T2 y T3 en paralelo.
- L4 (2026-10-28) T1 y T2 hechas. Decisiones técnicas del writer «dominio»: redondeo HALF_EVEN en el desglose (con las seis alícuotas, T/(1+a) nunca cae justo en medio centavo, así que da lo mismo que HALF_UP y coincide con el chequeo 10051 del simulador); una alícuota cuyo T suma 0 no va en AlicIva; se suma `ImpTrib` en 0 porque entra en la ecuación de ImpTotal; `RenglonFiscal` rechaza totales negativos o con más de 2 decimales; la regla del reloj prohíbe todo `now(...)` de `java.time` salvo `now(Clock)`. La spec quedó con los estados PENDIENTE, EN_CURSO, EN_VERIFICACION, AUTORIZADO, RECHAZADO y FUERA_DE_FECHA: hay que cruzarlos con los de V32 (T3). El worktree aislado arrancó en `c9e3dfe` y el writer hizo fast-forward a `85187a3`; los próximos writers en worktree tienen que verificar la base. · Próximo paso: cerrar T3.
- L5 (2026-10-28) T3: V32 commiteada sola (`6eab13c`). Diseño del writer «motor»:
  - estados del registro: PENDIENTE, EN_CURSO, EN_VERIFICACION, AUTORIZADO, RECHAZADO, FUERA_DE_FECHA y dos alertas que traban la serie sin renumerar, CAE_NO_COINCIDE y SERIE_DESINCRONIZADA (la spec se alineó en el mismo commit);
  - intento = un FECAESolicitar: ABIERTO, EN_VERIFICACION y ALERTA abiertos; AUTORIZADO, RECHAZADO y NO_LLEGO cerrados; el reenvío por N−1 cierra el intento viejo y abre otro con el mismo número y fecha;
  - a lo sumo un intento abierto por serie con un UNIQUE funcional (`uk_intento_fiscal_abierto_por_serie`);
  - lease en `serie_fiscal` (`lease_dueno` UUID por toma, `lease_vence`) con UPDATE condicional; `ultimo_autorizado` NULL = todavía no se consultó;
  - CHECK de coherencia como igualdades booleanas; CHECK de (clase, letra, CbteTipo), de ImpTotal = suma de los importes, DocTipo, CondicionIVAReceptorId, Id de IVA y rangos de punto de venta y número.
  Para T6: el TTL del lease tiene que superar el presupuesto total de la llamada; si no, otra instancia puede ver N−1 con la llamada en vuelo y reenviar. · En paralelo: `odd-verifier` sobre `6eab13c`, investigación de los códigos y mensajes del manual WSFEv1 para T5 (el writer no tiene web) y T4 en el árbol principal.
- L6 (2026-10-28) Investigación del manual WSFEv1 (agente con web; referencia en el vault, `evidencia/plan-facturacion/wsfev1-referencia.md`). Fuente: «RG 4291 – Proyecto FE v4.8, revisión 1 de diciembre de 2026», https://www.afip.gob.ar/fe/ayuda/documentos/wsfev1-RG-4291.pdf. Diferencias con el plan:
  - 10015 es la identificación del receptor en la B (DocTipo 99 y DocNro 0 bajo el tope), no formato;
  - 10040 es la compatibilidad con `CbtesAsoc`;
  - 10243 es la condición inválida para la clase y 10246 la ausencia;
  - el anexo cruza la condición con la clase: A admite 1, 6, 13 y 16; B admite 4, 5, 7, 8, 9, 10 y 15. La tabla A/B/C de D2 coincide;
  - tolerancia de 10048, 10051, 10023 y 10061: relativo ≤ 0,01 % o absoluto ≤ 0,01, HALF_EVEN;
  - CbteFch del concepto 1: N−5 a N+5 sin salir del mes, y no anterior al último autorizado.
  Inferido: CAEFchVto = CbteFch + 10, CAE de 14 dígitos, Ids de IVA 8 y 9. No está en el manual: el Msg literal por código, el monto de la RG 4444 y la respuesta de FECompConsultar ante un comprobante inexistente. Se confirman contra homologación en la rama 6. · T5 lanzada en worktree, con esa referencia.
- L7 (2026-10-28) `odd-verifier` (opus) sobre `6eab13c`. El esquema sirve al motor de S5 sin huecos:
  - aditiva (dump idéntico al de V31 sin las tablas nuevas);
  - out-of-order sobre una base dev con datos demo;
  - las FK no cruzan ferreterías;
  - el UNIQUE funcional frena dos intentos abiertos, también en concurrencia y en una reapertura por UPDATE;
  - el lease toma, renueva y vence sin carreras;
  - el CHECK de coherencia deja pasar todas las transiciones de S5 y rechaza los estados imposibles.
  
  Tests: `MigracionV32Test` 90/0; V6, V7, V8, V24 a V27, V29 y V31: 74/0.
  
  **Bloqueante B1**: `rollback-v32-facturacion.sql:10-24` borra las 5 tablas con datos y la fila 32 del historial si se pega en un `mysql` interactivo o se corre con `--force`. La guarda es un INSERT que falla y los DROP no dependen de ella. En la base (`7525aeb`) el rollback de V31 no borra.
  
  Advisories:
  - A1: nada en la base ata el intento a la serie ni al número del registro (lo cuida T6).
  - A2: la collation `_ci` acepta estados en minúscula, y el CAE no tiene CHECK de formato.
  - A3: la spec no lista los estados del intento ni las columnas de la serie. Las alertas cuentan como abiertas para el cerrojo; el pedido de T4 ya lo dice.
  - A4: ImpTrib, Concepto y MonId no se guardan.
  - A5: no hay CHECK de «punto de venta 1 con SIMULADOR».
  - A6: para la rama 3, tres tests hacen `delete from comprobante` y van a chocar con la FK del registro fiscal.
  - A7: problema previo: el rollback de V31 en modo interactivo borra la fila 31 del historial.
  
  Corrección (una tanda, al writer «motor» cuando T4 libere el árbol):
  - B1, con un test en modo «continuar ante error»;
  - A5 como CHECK;
  - las sondas del verificador como regresión (EN_VERIFICACION y ALERTA contra un ABIERTO, reapertura por UPDATE, N−1 en una transacción, dos tomas del lease).
  
  Después, un recheck acotado a B1.
- L8 (2026-10-28) T4 commiteada sola (`4c2c3b7`). Decisiones del writer:
  - rutas `PATCH /api/admin/ferreterias/{id}/de-prueba`, `/simulador` y `/facturacion`, con el patrón de `/activo` (400, 404, 422);
  - `@UnscopedTenantAccess` en métodos puntuales de `ConfigArcaParaPlataformaImpl`; no se fija `TenantContext` con el id de la ruta;
  - el cerrojo vive en `ConfigArca` (`ReglaDeNegocioException`) y suma «homologación y producción no se tocan desde el panel»;
  - «abiertos» = todo estado salvo AUTORIZADO, RECHAZADO y FUERA_DE_FECHA, y las alertas cuentan; las consultas son nativas sobre `comprobante_fiscal` hasta que T6 traiga la entidad;
  - un 422 no crea la fila; prender de nuevo conserva `habilitada_en` y apagar la borra;
  - `elPuertoDeFacturacionSoloDependeDelJdk` pasa a `allowEmptyShould(false)`.
  Gotcha: con `-Dtest='A,B#metodo'`, surefire saltea las clases `@Nested`.
  Para la rama 3: el chequeo de «sin registros abiertos» no toma lock; el alta del registro y el cambio de ambiente se coordinan con `FOR SHARE` en `config_arca` al registrar y `FOR UPDATE` en el panel.
- L9 (2026-10-28) Corrección de B1 (writer «motor», en un worktree scratch, patch aplicado por el padre). El rollback de V32:
  1. suelta una guarda vieja;
  2. crea la guarda con `CREATE TABLE … (CHECK (filas = 0)) SELECT` de la suma de los COUNT;
  3. borra todo en un único `DROP TABLE` que incluye la guarda (atómico: sin guarda no borra nada);
  4. borra la 32 del historial solo si las tablas ya no existen.
  
  A5: CHECK `ck_config_arca_simulador_punto_venta`. Sondas del verificador como tests:
  - EN_VERIFICACION y ALERTA contra un ABIERTO;
  - reapertura por UPDATE;
  - N−1 en una transacción;
  - dos tomas del lease, con `BarreraDeLocks`.
  
  RED: `MigracionV32Test` 107 tests, 7 fallas (B1 en sus 5 casos, A5 en 2). GREEN 107/0. Control del padre con `ConfigArcaAdminIntegrationTest`, `ConfigArcaTest` y `MigracionV6Test`: 153/0. Commit `1e07edf`. Recheck de B1 pedido al verificador.
- L10 (2026-10-28) `odd-verifier` (opus) sobre `4c2c3b7` (T4). Lo que se verificó:
  - permisos: 135 casos de Dueño, Vendedor y Dueño ajeno dan 403, sin token 401, y ninguna variante de ruta saltea el permiso;
  - inexistente 404, cuerpos inválidos 400;
  - todo el cerrojo, por HTTP;
  - habilitar con CU-01 completo;
  - regresión del alta, suspender y reset contra la base;
  - `@UnscopedTenantAccess` solo alcanzable por SUPER_ADMIN.

  S2 y S3 cumplidos. S9 no cumplido por B1.
  - **B1 (T4)**: `SuperAdminSinVentasIntegrationTest:44` falla con la clave `puntoVenta` (prohíbe toda clave con «venta»). En `6eab13c` pasa, y el 148/0 del writer no lo incluía. Es un falso positivo de la heurística, pero deja la suite en rojo.
  - Advisories:
    - A1: ninguna regla ata las consultas con ferretería explícita de `ConfigArcaRepository` a `@UnscopedTenantAccess`, ni limita `ConfigArcaParaPlataforma` a `plataforma`;
    - A2: update perdido entre «sacar marca» y «asignar simulador» en paralelo (dos 200; el CHECK sostiene la invariante);
    - A3: el primer toque concurrente da 409 genéricos;
    - A4: el javadoc de `configDe` no es exacto;
    - A5: la coerción de Jackson ya estaba en la base.

  Corrección en una tanda, a cargo del writer de T4: B1 (excepción acotada para `puntoVenta` en el test), A2 (lock pesimista en las acciones del panel, con test de concurrencia), A1 (dos reglas de ArchUnit), A4 y las sondas como regresión. Después, recheck de B1.
- L11 (2026-10-28) T5 (writer «simulador», en un worktree desde `6eab13c`):
  - puerto `facturacion/wsfe` (`ServicioWsfe`, records de WSFEv1, `PedidoNoLlegoException` y `ResultadoInciertoException`);
  - simulador `facturacion/simulador` con V33 y su rollback;
  - suite de contrato abstracta (`ContratoWsfeTest`) y fallas en un decorador de test (`WsfeConFallas`).
  
  RED V33: 35 tests, 34 errores y 1 falla. RED simulador: 97 tests (stub y `Campos` vacío). GREEN focalizado: 214/0. Sin RED propio: la concurrencia del simulador (6 pedidos simultáneos con el mismo número dan un A y cinco 10016).
  
  Inferidos (marcados en el código): CAE de 14 dígitos al azar, CAEFchVto = CbteFch + 10, Ids 8 y 9, CbteNro 0 sin comprobantes, 602 al consultar un inexistente, padrón = CUIT con dígito válido, 10217, 10188, 10237 y 10041. Códigos sumados: 10007 y 10242. Montos configurables sin verificar contra la normativa: `tope-rg4444` 10.000.000 y `minimo-fce` 3.958.316.
  
  10051: compara el Importe contra BaseImp × alícuota sin redondear (si redondeara al centavo, la suma por renglón quedaría justo a 0,01 y pasaría). Los arrays van en TEXT con CHECK de JSON, porque una columna JSON de MySQL devuelve 100.00 como 100.0.
  
  Ajustes pedidos antes de integrar:
  - sacar `wsfe.Ambiente` y usar el de T4;
  - los rechazos de validación van en las `Obs` del detalle con R, como en los ejemplos del manual (págs. 83 y 86), no en `Errors` (el error estaba en el pedido del padre).
  
  Reglas de ArchUnit propuestas para después (T6 o integración): todo repositorio de `facturacion..` es de tenant, también `facturacion.simulador`, y nadie fuera del simulador depende de `SimuladorWsfe` ni de sus repositorios.
- L12 (2026-10-28) Corrección de T4 (`8328ae1`):
  - **B1**: excepción solo para la clave `puntoVenta` en `SuperAdminSinVentasIntegrationTest`. RED: la falla original. GREEN: 2/0.
  - **A2**: las acciones del panel arrancan con `crearSiFalta` (`INSERT … ON DUPLICATE KEY UPDATE`, lock exclusivo) y siguen con `bloquearDeFerreteria` (`PESSIMISTIC_WRITE`). El lock va primero en la transacción: en REPEATABLE READ, la foto de las lecturas sin lock se toma en la primera lectura. Por eso el 404 de una ferretería inexistente ahora sale del puerto. `FOR UPDATE` solo, sobre una fila inexistente, daba deadlock por locks de hueco. `ConfigArcaConcurrenciaIntegrationTest`: RED con `[200, 200]` y `[200, 409]`; GREEN 3/3, con delta de deadlocks 0.
  - **A1**: `TenantScopingArchTest#lasConsultasDeConfigArcaSoloLasLlamaCodigoConExcepcionDocumentada` y `ModulosArchTest#elPuertoDeConfigArcaParaPlataformaSoloLoUsaPlataforma`. Las dos tienen su RED con una violación sembrada en una copia scratch.
  - **A4**: el javadoc queda documentado. Un `false` sin fila deja la fila por defecto; una regla rota la revierte.
  - Sondas: 9 tests nuevos en `ConfigArcaAdminIntegrationTest` (22/22).
  
  Focalizado del writer: 166/0. Control del padre: 20/0.
  
  Para la rama 3: al dar de alta un registro, leer `config_arca` con `FOR SHARE` hasta el commit (documentado en el repositorio y el puerto). Recheck de B1 pedido al verificador de T4.
- L13 (2026-10-28) Recheck de T4 sobre `8328ae1`: B1 cerrado. Efectos nuevos, sin defecto:
  - el 404 sale del puerto, con el mismo mensaje;
  - un 422 hace INSERT y rollback (deja un hueco en el AUTO_INCREMENT);
  - `crearSiFalta` toma un lock compartido de `ferreteria` (`INSERT … SELECT`). Leyendo el código no hay ciclo con `/activo`, pero no se probó en concurrencia: queda como advisory.
- L14 (2026-10-28) T5 integrada en `302856b`. El writer aplicó estos ajustes antes de integrar:
  - sacó `wsfe.Ambiente` y usa `facturacion.model.Ambiente`;
  - los rechazos del detalle van en las `Obs` con R y sin CAE (ejemplo 4, p. 86); en `Errors` quedan 10007 (cabecera, sin detalle; ejemplo 3, p. 84) y el 602 de `FECompConsultar`;
  - el ambiente que no es SIMULADOR sigue siendo una excepción y no un R, para que el motor no libere el número por un error de configuración;
  - 10242 va en `Obs`, por inferencia: es una validación del campo;
  - el rollback de V33 sigue el molde seguro de B1.

  Se lanzaron en paralelo el `odd-verifier` de T5 y el writer de T6 (motor, árbol principal).
- L15 (2026-10-28) Luca, textual: «okey cuando termines con la rama dos cerra sesion y dejamos lo proxima para la otra session». Al cerrar T7 se cierra la sesión (Obsidian y resumen en Engram) y la rama 3 queda para la próxima.
- L16 (2026-10-28) `odd-verifier` (opus) sobre `302856b` (T5): sin bloqueantes; S1, S6 y S7 cumplidos. Lo que verificó:
  - el anexo completo, 140 casos;
  - las tolerancias justo en el límite y apenas afuera, con 1 y con 3 alícuotas;
  - la CbteFch en cambio de mes y de año, y en el concepto 2;
  - la combinación de varias fallas en orden;
  - que un rechazo no toca nada;
  - que `REQUIRES_NEW` sobrevive al rollback de quien llama;
  - 15 rondas de concurrencia con dos ferreterías y la misma CUIT, con delta de deadlocks 0;
  - un barrido de 20.000 desgloses al azar sin ningún rechazo;
  - V33 aditiva, out-of-order y con el rollback probado con el `mysql --force` real.

  Advisories:
  - A1: CbteTipo o PtoVta 0 dan `JpaSystemException`, porque el simulador toma la serie antes de validar, y un CbteTipo 999 deja una fila de serie huérfana;
  - A2: los importes negativos se aprueban, cuando ARCA da 10043 a 10047 o 10065;
  - A3: una C con ImpIVA ≠ 0 sin array se aprueba, y una A o B con neto sin array sale con 10061 en vez de 10070/10018;
  - A4: la tolerancia relativa es asimétrica (toma el informado como el valor real; es inferido);
  - A5: la ventana de las FCE y el CDI no están modelados;
  - A6: el «reinicio» se prueba con una instancia nueva sobre la misma base.

  Ninguno afecta lo que genera el motor. Las sondas se pasaron a `SimuladorWsfeBordesTest`, con el registro en memoria en lugar de `/tmp`.
- L17 (2026-10-28) T6 en `c2d1bf4`. Las sondas de T5 quedaron como `SimuladorWsfeBordesTest` en `6fb23ae` (7/0).

  Diseño:
  - `MotorFiscal` no es transaccional y es el único que llama al puerto. `PasosFiscales` es el único escritor, con pasos `@Transactional` cortos.
  - Entidades `@Immutable`, sin setters. Cada transición es un UPDATE nativo condicional, que si no toca una fila lanza `PasoDescartadoException`.
  - Locks en orden serie → registro → intento.
  - Lease de 180 s con presupuesto de 60 s. El bean no arranca si el lease no lo supera.
  - Primera sincronización con `FECompUltimoAutorizado` + `FECompConsultar(último)`.
  - EN_VERIFICACION se resuelve en la atención siguiente. Un ABIERTO huérfano pasa a EN_VERIFICACION.
  - La adopción exige `FeCaeDetRequest.equals`, el mismo ptoVta y CbteTipo, A, CAE y vencimiento.
  - `AltaFiscal(comprobanteId, ReceptorFiscal, renglones)`, con `FOR SHARE` sobre `config_arca`.
  - `WsfePorAmbiente` toma el simulador por `@Qualifier`.
  - `RelojControlable` en los tests.
  - La regla de tenant cubre todo `facturacion..`; antes tenía un punto ciego.

  Advisories del writer para las ramas 6 y 7:
  - ARCA adelante de la caché da 10016 sin resincronizar.
  - Un reenvío por N−1 contra un ARCA que procesó tarde deja un CAE huérfano.
  - Un alta junto a la liberación de la serie queda PENDIENTE hasta la próxima atención (la levanta el job).

  Suite frontend: 132 archivos verdes; `ng build` OK (el aviso de `dialogo-cobro` ya estaba).
- L18 (2026-10-28) Recheck de B1 de V32 sobre `1e07edf`: cerrado. Se probó en un MySQL 8.4.11 descartable con el cliente real:
  - con `--force` y en modo interactivo, con una fila en cualquiera de las 5 tablas o con datos en todas, el rollback da ERROR 3819 y 1051, no borra nada y la fila 32 queda;
  - una guarda vieja con `filas=0` más datos no habilita el DROP;
  - sin datos, el esquema queda igual al de V31;
  - `MigracionV32Test` da 107/0.

  Advisories nuevos:
  - con `sql_require_primary_key=ON`, la guarda no se crea y el rollback no hace nada (falla sin borrar). Antes de usarlo en Railway, hay que revisar esa variable;
  - `mysql --force` sale con código 0 aunque se niegue (ya pasaba).

  El primer verificador de V32 quedó colgado en un trabajo de fondo y se paró; el recheck lo hizo uno nuevo.
- L19 (2026-10-28) `odd-verifier` (opus) sobre `c2d1bf4` (T6).
  - Corridas: 36/0 focalizado; la concurrencia 9/0 en 6 corridas; el panel con el `FOR SHARE` nuevo 120/0.
  - Sondas: 27 propias. Cada regla de ArchUnit falló con su semilla.
  - S8, S2 (lo de T6) y S7: cumplidos.
  - B1 (S5): el reenvío por N−1 no pasaba por `VentanaCbteFch`.
    - Caso: venta del 23/10, envío el 28/10 (N−5), timeout, atención el 29/10.
    - Daba RECHAZADO por 10016 y un segundo `FECAESolicitar` a ciegas.
    - Corrección en `46bf0ec`, test primero: RED `reenvioConLaCbteFchFueraDeLaVentana` (RECHAZADO en vez de FUERA_DE_FECHA).
    - Ahora `PasosFiscales.fueraDeFechaSinReenvio`, en una transacción, renueva el lease, pasa EN_VERIFICACION a FUERA_DE_FECHA sin número ni CbteFch y cierra el intento NO_LLEGO.
    - GREEN: motor y ArchUnit 99/0.
  - Las sondas pasan a `MotorFiscalBordesIntegrationTest`: 23/0, y con el motor 38/0. Quedan afuera p1b, p10d y p11, que son advisories, y p6g, que cubre el test nuevo.
  - Advisories:
    - A1, fuera de dominio. Una llamada más larga que el lease deja RECHAZADO aunque ARCA lo autorizó, y la caché queda atrás (10016 en cadena). Nadie valida `simulador.latencia` contra `presupuesto-llamada`. Recomendación para la rama 6: ante una R con 10016, resincronizar o verificar en vez de liberar. Tampoco hay espera mínima antes de verificar.
    - A2, rama 3. `atender` dentro de una transacción de quien llama rompe S5 sin aviso: el intento no se ve durante la llamada y queda una conexión tomada. Hace falta una guarda (`isActualTransactionActive()`) o `REQUIRES_NEW`.
    - A3, rama 3. Si quien llama ya cargó la entidad `ConfigArca`, el `FOR SHARE` nativo devuelve la instancia vieja del contexto de persistencia y crea un registro con la facturación apagada.
    - A4. La regla de único escritor no ve `JdbcTemplate` ni un `@Modifying` de otro repositorio. Hoy no hay ninguno.
    - A5. La batería no exigía al menos un OCUPADA. `p12` lo exige ahora en el total de 15 rondas.
    - A6, rama 6. Una excepción del puerto sin traducir deja el intento ABIERTO. El adaptador real tiene que traducirlas.
- L20 (2026-10-28) ego-browser, panel del super admin en dev local, sobre `46bf0ec`:
  - Habilitar la facturación sin ambiente da «Para habilitar la facturación, la ferretería necesita un ambiente de ARCA y un punto de venta.».
  - Con el simulador pero sin datos fiscales da el aviso de los tres datos que faltan.
  - Con los datos cargados (por API, como Dueño), la facturación se habilita: la fila muestra «Sí · Simulador · Sí».
  - Con la facturación habilitada:
    - «Quitar simulador» se niega con «Primero deshabilitá la facturación…»;
    - «Quitar marca de prueba» se niega con «…quitale el simulador primero.».
  - Al deshabilitar, el simulador y la marca se quitan en ese orden.
  - El Tornillo y Prueba SRL quedaron como estaban, con los datos fiscales de El Tornillo otra vez vacíos.
  - Observación: a 1470 px, las columnas De prueba, Ambiente y Factura quedan fuera de vista y hay que desplazar la tabla.
- L21 (2026-10-28) Recheck de B1 de T6 sobre `46bf0ec`: cerrado.
  - En la base `c2d1bf4`, p6f, p6g y `reenvioConLaCbteFchFueraDeLaVentana` fallan; en `46bf0ec`, pasan.
  - Comando pedido, más las sondas del recheck: 122/0, y las 9 sondas también.
  - Lo vecino sigue igual:
    - el reenvío dentro de la ventana, incluso en N−5 a las 23:59 y de un mes al otro;
    - adopción y alertas;
    - el rollback completo de `fueraDeFechaSinReenvio`, descartado por intento, registro o lease cambiados.
  - `vencerSinReenvio` solo lo llama `PasosFiscales`. ArchUnit 48 y 26.
  - Sin advisories nuevos. Las sondas p6g y r1 a r7 quedaron como `MotorFiscalReenvioIntegrationTest` en `4e53955` (8/0).
- L22 (2026-10-28) Cierre de la rama.
  - Suite backend sobre `4e53955`: 5088/0, BUILD SUCCESS en 21:27 min. Con la rama 1 eran 4697 en 11:50 min; el tiempo extra coincide con un contenedor de otro proyecto corriendo a la vez.
  - El frontend no cambió desde `c2d1bf4`.
  - Pendientes que no son de esta rama:
    - la fecha mínima del inicio de actividades (A2 de la rama 1; hoy solo se exige que no sea nula);
    - los advisories de L17 y L19, para las ramas 3, 6 y 7;
    - revisar `sql_require_primary_key` antes de usar los rollbacks en Railway.
  - Merge y push: Luca, primero la rama 1 y después esta. La rama 3 queda para la próxima sesión (L15).
