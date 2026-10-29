# cu-07-contingencias

Objetivo: que ninguna venta facturable quede sin resolver. Un job reintenta los pendientes de CAE en orden; Ventas del Dueño tiene una bandeja de pendientes y rechazadas con «Reintentar» y «Dejar sin factura»; el mostrador avisa al Dueño y avisa cuando llega el CAE de una venta que salió con ticket provisorio. · Por qué: rama 7 de la fase 5 (`wiki/plan-facturacion.md` del vault, :473-524). Hoy un pendiente solo se retoma con la venta siguiente de la misma serie, y un rechazo queda para siempre sin acciones.
Rama: `feat/cu-07-contingencias` desde `5690b78` (`main`, con las ramas 1 a 6 en producción) · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Dtest=<Clase>` / `cd frontend && npx ng test --watch=false --include=<spec>` · Mirror Engram: `odd/cu-07-contingencias/tasks` (ok)
Ruteo: caso 5, Opus de punta a punta (Luca: «dale me parece bien», 2026-10-29). Los writers delegados corren con Opus. · Pronóstico: ~6.500 líneas cambiadas (el plan dice 2.000 a 3.000; la rama 6 pasó de 6.000 a ~10.000) · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance:
- La spec: D9 y D10 en «Contingencias» y en CU-16, la bandeja y los avisos.
- El job de reintento con ejecutor propio, tope por pasada, backoff por serie y el molde de tenant de `RecalculoDiarioJob`.
- En el motor: el 602 de FECompConsultar como «no existe», el 10016 por número (resincroniza y renumera) y por fecha (FUERA_DE_FECHA), y la atención con un límite de tiempo.
- La V36: el estado SIN_FACTURA con su motivo, el reintento del Dueño como un registro fiscal nuevo, el backoff de la serie y la marca de «salió con ticket provisorio». Rollback probado.
- La bandeja «Pendientes / Rechazadas» en Ventas del Dueño, con los errores de ARCA tal cual y explicados, y las observaciones de los autorizados.
- Las acciones de D10: «Reintentar» y «Dejar sin factura».
- Los avisos del mostrador: al Dueño (rechazadas, pendientes viejas, en revisión y facturación bloqueada) y el de «llegó el CAE» (Dueño desde Ventas; Vendedor solo de sus ventas en la caja abierta, D7).
- La batería de concurrencia con MySQL real y `Clock` fijo.

Fuera de alcance:
- El CAEA: si el contador confirma que la ferretería está en el Anexo de la RG 4290, va en una rama propia entre la 8 y la 11 (D9).
- Los duplicados electrónicos (RG 4290, art. 10): si el contador confirma que aplican, es otra rama antes de producción.
- La NC (rama 8).
- Resolver las alertas CAE_NO_COINCIDE y SERIE_DESINCRONIZADA: las ve el Dueño, las resuelve Luca a mano.

Restricciones (del mapa, L2):
- La ferretería sale solo del JWT, o del `TenantContext` que fija el job o el ejecutor fiscal. Una venta o un registro de otra ferretería es 404.
- `facturacion.api` usa solo tipos del JDK; nadie depende de `facturacion` salvo por `facturacion.api`; `facturacion` no depende de `ventas` (`ModulosArchTest:199-225`).
- `facturacion` no lee el reloj del sistema: todo con el `Clock` (`ModulosArchTest:295-316`).
- Solo `PasosFiscales` escribe los repositorios fiscales (`ModulosArchTest:329-347`, `containsExactly`). `ComprobanteFiscal` es `@Immutable`, con la copia congelada (`updatable=false`), y las alícuotas no se borran.
- Orden de locks: caja S → presupuesto → remito → cliente X → config_arca S → numerador X → comprobante → stock → hijos → registro fiscal (`RegistroVenta.java:65-81`); en el motor, serie → registro → intento (`PasosFiscales.java:42-43`).
- Ninguna llamada a ARCA dentro de una transacción abierta.
- Un estado terminal nuevo entra en `ConfigArcaRepository.registrosAbiertos` (`:96-104`), o traba el cambio de ambiente.
- Toda espera nueva de apagado suma su término a `ApagadoOrdenadoTest:47-60` y sube `drainingSeconds` de `backend/railway.json` (hoy 70, exacto).
- Cada commit con un ítem de riesgo va aislado y lo verifica `odd-verifier` (opus) al final.

- S1 (plan, docs): "Commit docs: pasa D9 y D10 a la spec (Contingencias y CU-16)." — Aceptación: la spec dice cómo se reintenta, qué hace la bandeja, las dos acciones con sus mensajes, los avisos y la contingencia mientras no conteste el contador.
- S2 (plan, job): "Job con ejecutor propio, porque el scheduler autoconfigurado tiene un solo hilo, compartido con el recálculo diario y las cotizaciones. La pasada tiene un tope de tiempo y usa el molde de tenant de RecalculoDiarioJob. Por cada serie: toma el lease o la saltea; recupera primero, procesa en FIFO y corta en el primer error transitorio; aplica backoff exponencial con tope" — Aceptación:
  - el job corre en su propio hilo y el hilo del scheduler solo lo dispara: el recálculo diario y las cotizaciones no se atrasan;
  - una pasada no se superpone con la anterior y respeta su tope (C5): entre una serie y otra, y entre un registro y el siguiente;
  - por ferretería activa y por cada serie con trabajo: una serie tomada se saltea; la recuperación va primero; FIFO; corta en el primer error transitorio;
  - el backoff es por serie y vive en la base (vale entre las dos JVM de un deploy); vuelve a cero cuando la serie autoriza;
  - una serie TRABADA o SERIE_PERDIDA no se reintenta hasta que la resuelva Luca.
- S3 (plan, motor): "trata el 602 de FECompConsultar como «no existe», no como transitorio; ante un 10016 por número, recupera y renumera; ante uno por fecha, pasa a FUERA_DE_FECHA." — Aceptación (C6):
  - un 602 no cuenta como error transitorio: al sincronizar la serie, el del último es «no existe» (sin fecha); en la verificación, con ARCA en N o más, es una alerta CAE_NO_COINCIDE sin renumerar (L7);
  - un 10016 no deja el registro RECHAZADO: se consulta FECompUltimoAutorizado; si el número enviado no era el próximo, la caché de la serie se resincroniza (también hacia atrás) y el registro vuelve a PENDIENTE sin número; si era el próximo, el problema es la fecha y pasa a FUERA_DE_FECHA;
  - cada caso con un test del motor contra el simulador.
- S4 (plan, bandeja): "Bandeja: el filtro «Pendientes / Rechazadas» en Ventas del Dueño, con el error de ARCA tal cual y explicado en criollo, y las observaciones de los autorizados." — Aceptación (C1):
  - el filtro de Ventas suma «Pendientes» y «Rechazadas»;
  - el detalle muestra cada error con el código y el texto de ARCA tal cual, más una explicación para los códigos conocidos, y las observaciones de una factura autorizada;
  - el Vendedor recibe 403 y otra ferretería no ve nada.
- S5 (plan, D10): "«Reintentar»: ventas vuelve a leer el cliente bajo lock y la ferretería, y facturacion vuelve a copiar los datos; la letra puede cambiar; fecha nueva, solo con la confirmación del Dueño; «Dejar sin factura», con motivo." — Respuesta de Luca a D10: «perfecto vamos con tus recomendaciones, lo que podamos usar del gds mejor» (2026-10-28). — Aceptación (C2, C3):
  - «Reintentar» solo sobre una venta RECHAZADO o FUERA_DE_FECHA; el registro viejo queda como historia, con sus errores, y nace uno nuevo PENDIENTE con la copia de hoy del cliente y de los datos de CU-01;
  - si la fecha original ya no entra en la ventana de ARCA, el reintento sin confirmar da 409 y no cambia nada; confirmado, el registro nuevo sale con la fecha de hoy. La fecha nunca cambia sola;
  - «Dejar sin factura» solo sobre RECHAZADO o FUERA_DE_FECHA, con motivo obligatorio; la venta queda marcada en Ventas y sale de la bandeja;
  - las dos acciones son solo del Dueño: el Vendedor recibe 403 y otra ferretería 404.
- S6 (plan, avisos): "Avisos: al Dueño en el mostrador, cuando hay rechazadas o pendientes viejas. Cuando llega el CAE de una venta que salió con ticket provisorio, un aviso para reimprimir la factura (el Dueño desde Ventas; el Vendedor según D7)." — Aceptación (C4, C7):
  - el mostrador del Dueño muestra cuántas hay rechazadas, pendientes viejas y en revisión, y si la facturación está bloqueada (el aviso que la rama 6 dejó para acá), con un enlace a la bandeja;
  - una venta que salió con ticket provisorio y después se autorizó se ve en Ventas con «Reimprimir factura»;
  - el Vendedor ve en el mostrador el aviso de sus ventas de la caja abierta que llegaron a CAE, con «Reimprimir factura», y nada de otras ventas (spec.md:126: «ventas que cobró ese Vendedor en la caja abierta, solo la hoja fiscal»).
- S7 (plan, contingencia; D9): "Contingencia según D9: sin CAEA, espera corta en línea, ticket X y reintento acotado; con CAEA, una rama nueva entre la 8 y la 11." y de D9 "reintento automático acotado, que vence a los 5 días corridos; el contador dice si en la B a consumidor final se corta en el día." — Aceptación: el job deja de reintentar un pendiente que ya no entra en la ventana de fechas; queda FUERA_DE_FECHA y aparece en la bandeja con el motivo.
- S8 (plan, batería): "Batería con MySQL real y Clock fijo: el job y el mostrador sobre el mismo pendiente, dos instancias del job y un apagado con un envío en vuelo." — Aceptación:
  - el job y el mostrador sobre el mismo pendiente hacen un solo FECAESolicitar;
  - dos instancias del job no duplican;
  - un apagado con un envío en vuelo se recupera en la pasada siguiente;
  - con dos ferreterías y `Clock` fijo: FIFO por serie, serie tomada salteada, corte en el primer transitorio y backoff respetado.
- S9 (plan, terminado): "En ego-browser: ARCA simulada caída, ticket X, el job autoriza, y aviso y reimpresión de la factura. Suites y ng build verdes. odd-verifier sin bloqueantes sobre cada commit de alto riesgo." — Aceptación: una forma de simular ARCA caída en dev que no exista en producción; el recorrido completo con ego-browser; suite backend, suite frontend, `ng build`, `-Pimagen` y verificador sin bloqueantes.
- S10 (plan, migración): "V36, solo si D10 suma estados o columnas (por ejemplo, «sin factura» con motivo): amplía el CHECK de estado del registro fiscal, sobre una tabla nueva con pocas filas. Rollback probado." — Aceptación: `MigracionV36Test` con el rollback con guarda, y la V36 en la lista de `MigracionV6Test:182-189`.
- S11 (confirmado por Luca el 2026-10-29): el umbral de «pendiente vieja» para el aviso del Dueño es 30 minutos desde la venta.
- S12 (confirmado por Luca el 2026-10-29): «Dejar sin factura» es definitivo (después no se puede reintentar), y el motivo es texto libre obligatorio de hasta 200 caracteres.
- S13 (confirmado por Luca el 2026-10-29): las ventas con alerta del sistema (CAE_NO_COINCIDE y SERIE_DESINCRONIZADA) aparecen en la bandeja como «En revisión», sin acciones para el Dueño, con el texto «Hay una diferencia con ARCA que tiene que revisar soporte.»; el job no las toca y quedan en el log con `ALERTA ARCA`.
- S14 (confirmado por Luca el 2026-10-29): el aviso «llegó el CAE» del Vendedor dura mientras esté abierta su caja y se descarta al reimprimir o al cerrarlo; el mostrador lo consulta cada minuto.
- S15 (supuesto, técnico, hasta que conteste el contador): «5 días corridos» es la ventana de ARCA que ya aplica `VentanaCbteFch` (N−5 por día calendario de Buenos Aires).

### Contratos

- **C1 · Bandeja (Dueño):**
  - `GET /api/ventas` suma el filtro `fiscal=PENDIENTES|RECHAZADAS` a los que ya tiene.
    - PENDIENTES: PENDIENTE, EN_CURSO, EN_VERIFICACION, CAE_NO_COINCIDE y SERIE_DESINCRONIZADA.
    - RECHAZADAS: RECHAZADO y FUERA_DE_FECHA.
  - `GET /api/ventas/{id}`: el resumen fiscal suma `errores: [{ codigo, mensaje, explicacion|null }]`, `observaciones: [{ codigo, mensaje }]` (los `codigo` son numéricos, como los de ARCA), `motivoSinFactura|null`, `sinFacturaPor|null` (el nombre del usuario), `sinFacturaEn|null` (fecha y hora de Buenos Aires sin zona, como `fecha`), `acciones: ["REINTENTAR", "DEJAR_SIN_FACTURA"]` (las que valen hoy), `fechaNuevaRequerida: boolean` y `reimprimir: boolean` (salió con ticket provisorio y ya está AUTORIZADO).
  - La lista lleva `reimprimir` y el estado fiscal en cada venta.
- **C2 · Reintentar (Dueño):** `POST /api/ventas/{id}/factura/reintentar`, cuerpo `{ "fechaDeHoy": false }`.
  - Responde como el cobro: el resumen fiscal después de la espera corta (`app.facturacion.venta.espera`).
  - Los 409 y el 422 van con el cuerpo de `VentaRechazadaResponse`: el código en `motivo` y el texto en `message`. Lo mismo en C3.
  - 409 `FECHA_NUEVA_REQUERIDA`: «La fecha de la venta ya no entra en ARCA. Confirmá que la factura salga con la fecha de hoy.»
  - 409 `SIN_FACTURA_RECHAZADA`: «Esta venta no tiene una factura rechazada para reintentar.»
  - 409 `FACTURACION_FRENADA`: «La facturación está frenada: <motivo>.» (deshabilitada, sin credencial vigente o bloqueada por ARCA).
  - 422 si el cliente de hoy no cumple D4, con el mismo mensaje que el cobro.
- **C3 · Dejar sin factura (Dueño):** `POST /api/ventas/{id}/factura/sin-factura`, cuerpo `{ "motivo": "…" }`.
  - 200 con el resumen fiscal (estado SIN_FACTURA y el motivo).
  - 400 en `motivo`: «Contá por qué la venta queda sin factura.» (vacío) o «El motivo tiene hasta 200 caracteres.».
  - 409 `SIN_FACTURA_RECHAZADA`, igual que C2.
- **C4 · Avisos del Dueño:** `GET /api/facturacion/avisos-mostrador`, `hasRole('DUENO')`.
  - 200: `{ "rechazadas": 0, "pendientesViejas": 0, "enRevision": 0, "bloqueo": "<texto de C4 de la rama 6>"|null }`.
  - El mostrador lo lee al abrir y cada minuto, y muestra solo lo que no es cero, con un enlace a la bandeja.
- **C5 · Configuración del job (`app.facturacion.reintento`, en el yml):**
  - `cada: 60s` (entre el fin de una pasada y la siguiente), `tope-pasada: 120s`;
  - `backoff-inicial: 60s`, `backoff-tope: 30m` (se duplica en cada pasada con error transitorio);
  - se valida al arrancar: `tope-pasada ≥ motor.presupuesto-llamada` y `backoff-tope ≥ backoff-inicial`.
- **C6 · Motor:** 602 y 10016 como en S3. El código y el texto de ARCA se guardan tal cual en el intento.
- **C7 · Llegó el CAE (Vendedor y Dueño):** `GET /api/ventas/provisorias-autorizadas`.
  - 200: `[{ "ventaId": 1, "numero": 12, "factura": "Factura B 00001-00000045" }]`: las ventas que el usuario cobró en la caja abierta, que salieron con ticket provisorio y ya están AUTORIZADO.
  - Sin caja abierta, lista vacía.
  - La hoja se pide con `GET /api/ventas/{id}/factura` (Dueño y Vendedor; L6): responde lo mismo que `GET /api/ventas/{id}`, que es lo mismo que el cobro. El Vendedor solo puede pedir una venta de esta lista: cualquier otra, aunque exista, da 404 «Venta no encontrada». `GET /api/ventas/{id}` sigue siendo solo del Dueño.

## Tasks

- [x] T1 · S1, S11-S15 · ruta: inline · Aceptación: commit `docs:` con este doc y la spec (Contingencias, CU-07, CU-16 y el modelo de datos) · Evidencia: docs pasivos; lectura de vuelta: CU-07 (el job y la marca de provisorio; «Reimprimir» con el aviso del Vendedor), CU-16 puntos 7 y 8 (la bandeja, las dos acciones con sus mensajes, «En revisión» y los avisos del Dueño), el modelo (SIN_FACTURA, uno solo vigente, backoff de la serie), «Numeración» (10016 y 602) y «Contingencias» (D9 y el job); commit: ver el Log · Risk: none (docs)
- [x] T2 · S10, S5 (datos), S6 (marca de provisorio), S2 (backoff) · ruta: delegada (writer «backend», junto con T3, con su propio commit) · Aceptación:
  - la V36: SIN_FACTURA en el CHECK con sus columnas (motivo, usuario, fecha) y la coherencia; el registro fiscal versionado (varios por venta, uno solo vigente: el índice único pasa a la venta vigente); la marca de «salió con ticket provisorio»; el backoff de la serie;
  - el rollback con guarda;
  - `MigracionV36Test` y `MigracionV6Test`.
  - Evidencia (L7): RED `MigracionV36Test` 26 errores «No migration with a target version 36» + 1 falla, y `MigracionV6Test#historialDeMigraciones`; GREEN 27/0 y 14/0 · Risk: item 1 (cambia un índice único y el CHECK del registro fiscal)
- [x] T3 · S2 (atención con límite y backoff), S3, S5 (transiciones), S7, C6 · ruta: delegada (writer «backend») + arreglo del padre en el 602 (L7) · Aceptación:
  - `MotorFiscal.atender` con un límite de tiempo; el backoff de la serie en `PasosFiscales`;
  - 602 y 10016 como en C6;
  - las transiciones nuevas en `PasosFiscales` (SIN_FACTURA y el registro nuevo del reintento), `registrosAbiertos` al día, y todos los lectores leen el registro vigente;
  - la marca de provisorio cuando vence la espera de la venta.
  - Evidencia (L7): RED `MotorFiscalContingenciasIntegrationTest` 7/7 y los 3 de provisorio de `FacturacionParaVentaIntegrationTest`; el resto por 15 mutaciones temporales (17 tests fallaron, todo restaurado y comparado con `cmp`); GREEN Contingencias 7/0, FacturacionParaVenta 37/0, Acciones 11/0, Pasada 5/0, Política 2/0; `facturacion.**` + `ventas.**` + arch + RegistroVenta + migraciones 2165/0 (writer). Arreglo del padre en el 602: RED «expected: TRABADA but was: TERMINADA» → GREEN `MotorFiscal*IntegrationTest` + `ModulosArchTest` 113/0 · Risk: items 1, 3 y 4 (estados nuevos; un error de ARCA en la consulta de la verificación pasa de TRABADA a transitorio; la caché de la serie se resincroniza bajo lease)
- [x] T4 · S2, S7, S8, C5 · ruta: delegada (writer «backend», en un worktree aparte en paralelo con T5; el padre aplicó el parche) · Aceptación: el job con su ejecutor, la pasada con tope, el molde de tenant, el backoff, el apagado, y la batería de S8 · Evidencia (L9): RED con un esqueleto que compila: 16 de 18 en rojo; GREEN 18/18 (Programación 3, Programación integración 1, Job 11, Apagado 3); 6 mutaciones temporales, cada una mordida y restaurada; `facturacion.**` + arch + apagado + recálculo + cotizaciones 891/0 (writer); el padre, ya sobre T5: T4 + `AccionesDeFactura` + arch 103/0 · Risk: items 4, 5 y 6 (hilo nuevo y `SmartLifecycle` junto a la venta y la otra JVM; el apagado comparte la fase web; ningún test manda un SIGTERM real con el servidor)
- [x] T5 · S4, S5, S6 (backend), C1-C4, C7 · ruta: delegada (writer «backend», en paralelo con T4) · Aceptación: los endpoints, la explicación de los códigos conocidos, el contrato JDK de `facturacion.api` para el reintento (cliente X → config_arca S → registro), roles, aislamiento y el límite del Vendedor · Evidencia (L8): RED de los endpoints 21 fallas + 2 errores de 26 (404 en las rutas nuevas, `provisorias-autorizadas` leído como `{id}`, faltaban los campos de C1); `ExplicacionesArcaTest` RED de compilación → 16/16; `AccionesDelDuenoIntegrationTest#conLaFacturacionFrenada` RED de texto → GREEN; `ventas.**` + `facturacion.**` + arch 2025/0 (writer); el padre repitió Bandeja 7, Acciones 9, Provisorias 6, Avisos 4 y Explicaciones 16: 42/0 · Risk: items 2, 3 y 4 (acciones solo del Dueño y `/{id}/factura` abierto al Vendedor limitado; el detalle y la lista suman campos y cambia el texto de FACTURACION_FRENADA en `PasosFiscales`; el reintento contra el job no está probado junto)
- [x] T6 · S4, S5, S6 (frontend) · ruta: delegada (writer «frontend», en paralelo con T2-T3 contra C1-C4 y C7) + ajuste del padre (L6) · Aceptación: el filtro y la bandeja en Ventas, el detalle con errores y acciones (la confirmación de la fecha de hoy y el diálogo del motivo), los avisos del Dueño y el de «llegó el CAE» en el mostrador · Evidencia: RED→GREEN por regla (services TS2339/TS2307 → 28/28; `ventas.spec` 15 nuevos en rojo → 36/36; `dialogo-sin-factura` 6/6; `avisos-facturacion` 7/7; `avisos-cae` 7 en rojo contra un esqueleto → 7/7; `mostrador.spec` 8 de 9 en rojo → 320/320); frontend 141 archivos 3348/3348 y `ng build` OK (writer); ajuste del padre: RED TS2353/TS2339 → GREEN 51/51 en `ventas.spec`, `ventas.service.spec` y `avisos-cae.spec`. Falta ego-browser (T7) · Risk: item 1 bajo (clave nueva de localStorage `cae-descartados:{ferreteriaId}:{userId}`)
- [ ] T7 · S9 · ruta: inline (suites) + `odd-verifier` · Aceptación: ARCA simulada caída en dev, ego-browser (ticket X, el job autoriza, aviso y reimpresión; Reintentar y Dejar sin factura), suite backend, suite frontend, `ng build`, `-Pimagen` y verificador sin bloqueantes · Evidencia (L10): `CaidaSimulada` test-first (RED por compilación → GREEN `SimuladorEnElBordeIntegrationTest` 8/8); suite backend 5705/0; suite frontend 3349/3349 (la primera corrida, 1 en rojo en `mostrador.spec` por el doble de L6, corregido); `ng build` OK (el budget de `dialogo-cobro` ya estaba en `main`); `-Pimagen` 9/0; ego-browser en dev: venta 2260 con ARCA caída → ticket X, PENDIENTE con `ticket_provisorio`, el job `SIN_RESPUESTA` y backoff de la serie; ARCA vuelve → el job `TERMINADA` en la pasada siguiente, «Llegó el CAE de la venta N.º 2260» al Vendedor, «Reimprimir factura» abre la hoja y el aviso se va, Esc vuelve al buscador; venta 2261 a Consumidor Final sobre el tope → 10015 RECHAZADO, «1 rechazada» al Dueño con el link a la bandeja, el detalle con el error y su explicación, «Reintentar» → registro nuevo (el anterior reemplazado), «Dejar sin factura» → SIN_FACTURA con motivo, quién y cuándo, la bandeja vacía; log del backend sin ERROR ni secretos. Falta el verificador · Risk: items 1 a 5
- [ ] T8 · S2 (producción) · ruta: inline, después del deploy de Luca · Aceptación: los logs del job en producción sin errores y sin secretos · Evidencia: — · Risk: —

## Log

- L1 (2026-10-29) Pedido original, textual. Luca: «dale, arrancamos con la rama 7»; ante la propuesta de ruteo (caso 5): «dale me parece bien».
- L2 (2026-10-29) Exploración del código (`odd-explorer`):
  - **Registro fiscal.** Tabla en `V32__facturacion.sql:89-151`, estados en el CHECK `:136-137` (PENDIENTE, EN_CURSO, EN_VERIFICACION, AUTORIZADO, RECHAZADO, FUERA_DE_FECHA, CAE_NO_COINCIDE, SERIE_DESINCRONIZADA), coherencia `:138-143`, 1—1 con la venta por `uk_comprobante_fiscal_comprobante` `:127`. `errores` y `observaciones` son JSON y nadie los lee (`ComprobanteFiscal.java:217-218`). Sin columnas de intentos ni de próximo intento; cada intento es una fila de `intento_fiscal`. La entidad es `@Immutable` (`:28-31`).
  - **Transiciones**, todas en `ComprobanteFiscalRepository` y llamadas solo desde `PasosFiscales`: numerar `:73-80`, liberar `:86-92`, pasar `:98-105`, autorizar `:111-120`, rechazar `:128-136`, fuera de fecha `:142-160`. Nadie sale de RECHAZADO, FUERA_DE_FECHA ni de las dos alertas. Pendientes: solo `siguientePendiente` `:25-37` (una serie). `registrosAbiertos` en `ConfigArcaRepository.java:96-104`.
  - **Motor.** `MotorFiscal.atender` `:124-139` ya procesa una serie en FIFO bajo lease (`Turno.atender` `:154-192`) y termina en `Atencion` (TERMINADA, OCUPADA, SIN_RESPUESTA, TRABADA, SERIE_PERDIDA). Sin tope de tiempo ni backoff. Recuperación en `resolver` `:195-207` y `verificar` `:269-321`. Una R es RECHAZADO sin mirar códigos (`:251-257`); el 602 termina en CAE_NO_COINCIDE (inferido de `:294-306`); la caché `ultimo_autorizado` nunca retrocede (`SerieFiscalRepository.java:96-103`). `VentanaCbteFch` ya manda a FUERA_DE_FECHA un pendiente de más de 5 días cuando alguien atiende la serie.
  - **Scheduler.** `RecalculoDiarioJob.java:37-62` (molde de tenant), `ActualizacionCotizacionesJob.java:25-33`, `ProgramacionConfig.java:11-14` (`app.programacion.habilitada`), sin `TaskScheduler` propio. `EjecutorFiscalConfig.java:29-41` (8 hilos, cola 100, 20 s al apagar). `ApagadoOrdenadoTest:47-60` y `drainingSeconds` 70.
  - **Venta.** `FacturacionParaVentaImpl.autorizar` `:166-210` espera 6 s y abandona; nadie retoma salvo la venta siguiente. Ticket X en `HojaFiscal.java:46-47,109-114`. No hay endpoint del Vendedor para la hoja de otra venta ni polling (`VentaController.java:29-35,65-92`; `mostrador.ts:696-719`). Datos del receptor en `ClienteDelDocumento.java:59-64,104-108` y `FacturacionParaVentaImpl.java:304-326`; emisor en `PasosFiscales.emisor` `:199-207`.
  - **Frontend.** `features/ventas/ventas.ts:46-51` (filtros sin estado fiscal), `ventas.html:75-80,132-157`; aviso del Dueño al abrir el mostrador en `mostrador.ts:340,410-419`; aviso post-cobro `:683-706`.
  - **Migraciones.** La última es V35; V36 libre y reservada; sumarla en `MigracionV6Test:182-189`.
- L3 (2026-10-29) Decisiones de diseño (del padre, técnicas):
  - **El reintento del Dueño es un registro fiscal nuevo.** La entidad es `@Immutable`, la copia es `updatable=false` y las alícuotas no se borran: en vez de reescribir, el registro rechazado queda como historia y nace otro vigente. La V36 cambia el único por venta a «uno solo vigente».
  - **El 10016 se distingue con FECompUltimoAutorizado**, porque el código es el mismo para número y fecha.
  - **El backoff va en la serie**, en la base, porque el job procesa por serie y corta en el primer transitorio.
  - **«Salió con ticket provisorio» se guarda**: lo marca `PasosFiscales` cuando vence la espera de la venta. Deducirlo de las horas sería frágil.
  - Los supuestos S11 a S15 se le preguntan a Luca antes de implementarlos.
- L4 (2026-10-29) Luca confirmó los cuatro supuestos de producto: «confirmo los cuatro como están, continuá». S11 a S14 pasan a decisiones; S15 sigue como supuesto técnico hasta que conteste el contador.
- L5 (2026-10-29) T1 cerrada: la spec y este doc. Próximo paso: T2 (V36), con un writer «backend».
- L6 (2026-10-29) T6 (writer «frontend»). El contrato tenía cuatro huecos y el padre los cerró:
  - la hoja de C7: `GET /api/ventas/{id}` es solo del Dueño, así que se suma `GET /api/ventas/{id}/factura` para el Dueño y el Vendedor. Responde lo mismo que el cobro, y el Vendedor solo puede pedir sus ventas de C7 (cualquier otra da 404). No suma información: es la misma venta que el Vendedor ya cobró;
  - el código de los 409 va en `motivo` (`VentaRechazadaResponse`);
  - «Sin factura» suma quién y cuándo (`sinFacturaPor`, `sinFacturaEn`), como pide CU-16;
  - los `codigo` de ARCA son numéricos.
  - El padre ajustó el front a esto, test-first (`ventas.service.factura`, `avisos-cae` y el detalle con quién y cuándo).
  - Decisiones del writer:
    - después de cada acción se vuelven a leer la lista y el detalle;
    - el aviso «llegó el CAE» existe solo con la caja ABIERTA o DE_OTRO_DIA, y los descartados se guardan en localStorage (`cae-descartados:{ferreteriaId}:{userId}`), recortados a lo que devuelve el servidor;
    - las dos alertas se ven como «En revisión» en `ESTADO_FISCAL_LABELS`, y se suma `SIN_FACTURA: 'Sin factura'`;
    - el aviso del bloqueo enlaza a Facturación, y las cuentas a `/ventas?fiscal=…`.
- L7 (2026-10-29) T2 y T3 cerradas (un writer, dos commits).
  - **Esquema de la V36** (writer):
    - el versionado se marca con `reemplazado_en`, más un índice único funcional `uk_comprobante_fiscal_vigente (ferreteria_id, (CASE WHEN reemplazado_en IS NULL THEN comprobante_id END))`; solo un RECHAZADO o un FUERA_DE_FECHA puede quedar reemplazado;
    - la FK al comprobante queda con `ix_comprobante_fiscal_comprobante`;
    - SIN_FACTURA con `motivo_sin_factura` (VARCHAR 200, con TRIM no vacío), `usuario_sin_factura_id` (FK a usuario) y `sin_factura_en`, las tres juntas; conserva el resultado R;
    - `ticket_provisorio`, y en `serie_fiscal` `fallas_seguidas` y `proximo_reintento`, con un CHECK que los ata;
    - un índice para la bandeja, `ix_comprobante_fiscal_estado (ferreteria_id, estado, registrado_en)`;
    - el rollback guarda con un CHECK «sin historia» y encadena `serie_fiscal` con un PREPARE condicional.
  - **10016** (writer): se pregunta FECompUltimoAutorizado (y FECompConsultar del último para su fecha):
    - si el número no era el próximo, `renumerar`;
    - si era el próximo, `fueraDeFechaPorArca`;
    - si ARCA no contesta, el registro vuelve a PENDIENTE y la atención corta SIN_RESPUESTA.
    - El simulador ya emitía el 10016 por número y por fecha.
  - **Cambio de comportamiento** (writer): otro error de ARCA en la consulta de la verificación pasa de TRABADA a SIN_RESPUESTA (transitorio).
  - **Reintentar** (writer):
    - usa el `registrado_en` del vigente si entra en la ventana de la serie de la letra nueva; con `fechaDeHoy` usa hoy; si no entra, FECHA_NUEVA_REQUERIDA;
    - chequea SIN_FACTURA_RECHAZADA antes que FACTURACION_FRENADA;
    - no toma la serie: se apoya en el lock del cliente y en el índice del vigente.
  - **Arreglo del padre en el 602.** El writer renumeraba ante un 602 en la verificación con ARCA en N o más. Pero la numeración de ARCA es correlativa: si ARCA tarda en reflejar el N nuestro, renumerar duplica la factura de la venta, contra «nunca se reenvía a ciegas». Ahora es una alerta CAE_NO_COINCIDE sin renumerar (test cambiado primero; RED → GREEN). Se sacaron `PasosFiscales.noExiste` y `liberarDeVerificacion`, que quedaron sin uso. La spec y S3 dicen esto.
  - **Firmas para T4:**
    - `MotorFiscal.atender(Ambiente, int, int, Instant limite)`, con `Atencion.SIN_TIEMPO`;
    - `PasosFiscales.seriesConTrabajo(Instant)` → `List<ClaveDeSerie>` (sin las que están en backoff ni las que tienen alerta);
    - `PasosFiscales.registrarPasada(ClaveDeSerie, Atencion, Instant)`;
    - `PoliticaDeReintento` (`cada`, `topePasada`, `espera(int)`).
  - **Firmas para T5:**
    - `MotorFiscal.reintentar(AltaFiscal, boolean fechaDeHoy)`: MANDATORY, con el cliente ya tomado; el CAE se pide después del commit;
    - `MotorFiscal.dejarSinFactura(long comprobanteId, String motivo, long usuarioId)`;
    - `AccionFiscalImpedidaException` con `impedimento()`;
    - `ComprobanteFiscalRepository.vigenteDeScoped(long)`.
  - Próximo paso: T4 (el job) y T5 (los endpoints) en paralelo, con superficies separadas.
- L8 (2026-10-29) T5 cerrada (writer). Decisiones inferidas:
  - el reintento vuelve a leer el cliente con lock X, sin mirar si está activo (si fue borrado, 404; sin cliente, Consumidor Final);
  - SIN_FACTURA_RECHAZADA se responde antes de tomar el cliente y de D4; con la facturación frenada no se corre D4 y responde FACTURACION_FRENADA, con motivos cortos nuevos en `PasosFiscales` (`registrar` no cambia);
  - «REINTENTAR» se ofrece aunque la facturación esté frenada: el 409 explica por qué no sale;
  - en una venta no autorizada, `errores` junta los Errors y las Obs de ARCA (el simulador rechaza con Obs); en una autorizada, `observaciones` lleva las Obs; las `acciones` aparecen solo en RECHAZADO y FUERA_DE_FECHA;
  - `fechaNuevaRequerida` se calcula con la serie del registro y el `Clock`, solo para las rechazadas;
  - las pendientes viejas de C4 se miden desde el `registrado_en` del vigente;
  - el bloqueo de C4 reusa el texto de la rama 6;
  - `factura` de C7 con el punto de venta en 5 dígitos («Factura B 00001-00000045»), como el resto de la app; el padre corrigió el ejemplo del doc y el comentario del front;
  - C2 y C3 responden el `fiscal` del detalle;
  - `/{id}/factura`: el Dueño la pide de cualquier venta, el Vendedor solo de las de C7; el super admin, 403;
  - un deadlock en el reintento se reintenta hasta `VentaService.MAX_INTENTOS`, como el cobro;
  - la tabla de explicaciones cubre 10007, 10013, 10015, 10016, 10023, 10040, 10048, 10051, 10061, 10071, 10152, 10192, 10197, 10242, 10243 y 10246 (la del 10192 es inferida).
  - El padre corrigió el javadoc de `AccionFiscalImpedidaException.facturacionFrenada`.
- L9 (2026-10-29) T4 cerrada (writer, en un worktree que armó el padre: el aislado de la herramienta sale de `main`). Decisiones inferidas:
  - **Ejecutor:** `ProgramacionDelReintento` es un `SmartLifecycle` con un `ScheduledExecutorService` propio de un hilo (`facturacion-reintento`) y `scheduleWithFixedDelay(cada, cada)`. No es un bean a propósito: en Boot 3.3.13 el `taskScheduler` autoconfigurado tiene `@ConditionalOnMissingBean({TaskScheduler, ScheduledExecutorService})`, y un bean así dejaría a los `@Scheduled` del recálculo y las cotizaciones en otro lado. Un test lo fija. Respeta `app.programacion.habilitada`.
  - **Apagado sin sumar espera:** `stop(Runnable)` deja de tomar ferreterías y series y avisa cuando termina la pasada en curso. Está en la fase de `WebServerGracefulShutdownLifecycle`, así que la pasada se espera dentro de los 20 s de la fase web. `drainingSeconds` sigue en 70. Dentro de una serie, el motor sigue en FIFO hasta el límite aunque la app se esté apagando: cortarlo ahí exige cambiar `MotorFiscal`.
  - **Pasada:** try/catch por ferretería y por serie. Una excepción inesperada no suma backoff: se reintenta en la pasada siguiente.
  - Próximo paso: T7. Falta una forma de simular ARCA caída en dev (S9).
- L10 (2026-10-29) T7, recorrido y suites (padre). Decisiones inferidas:
  - **ARCA caída en dev (S9):** `facturacion.simulador.CaidaSimulada` es un `@Profile("dev")`. Mientras existe el archivo de `app.facturacion.simulador.caida` (default `${java.io.tmpdir}/ferreteria-arca-caida`, o `SIMULADOR_WSFE_CAIDA`), `SimuladorEnElBorde` contesta `PedidoNoLlegoException` a WSFEv1. WSAA sigue contestando. No hay endpoint: se prende con `touch` y se apaga con `rm`. Fuera de dev el bean no existe y el `Optional` queda vacío (un test lo fija).
  - **Rechazo en dev:** `SIMULADOR_WSFE_TOPE_RG4444=50000.00` y una venta mayor a Consumidor Final dan 10015.
  - **C7 en SIMULADOR:** el aviso dice «X · SIMULADO — sin validez fiscal», igual que la hoja (`BandejaFiscal.factura`, S6/S12). «Factura B 00001-…» sale solo en PRODUCCION autorizada.
  - **Doble de `mostrador.spec`:** expone `factura` (C7, `/{id}/factura`) en lugar de `obtener`. Faltó en el ajuste de L6.
