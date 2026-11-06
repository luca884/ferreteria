# bajos-qa

Objetivo: arreglar los hallazgos bajos del QA funcional del 05/11 · Por qué: `wiki/qa-funcional-2026-11-05.md` del vault, «Bajos repetidos» y «Bajos de una sola ferretería».
Rama: `fix/bajos-qa` desde `f0c8b01` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / `./mvnw test`; frontend `npx ng test --watch=false [--include=<spec>]`, `npx ng build` · Mirror Engram: `odd/bajos-qa/tasks` (ok)
Ruteo: caso 3 (Opus investiga y planifica; arreglos mecánicos) · Pronóstico: ~450 líneas cambiadas · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: los 12 bajos de la nota. Fuera de alcance: las «Decisiones de producto que salieron» de la misma nota; el 404 de ventas, presupuestos y remitos (mismo texto genérico, no lo marcó el QA).
Restricciones: reglas de arquitectura de `CLAUDE.md`; mensajes en español rioplatense; el ticket guardado sigue en `v: 1` y lee los ya guardados.

- S1: "«Enter edita» en Admin › Ferreterías no hace nada (A, B, C; callback vacío en `admin-ferreterias.ts`). La spec dice que el super admin no edita después del alta: sobra el texto." — Aceptación: la ayuda de la lista ya no dice «Enter edita». — Checks: `admin-ferreterias.spec.ts`
- S2: "Después de crear una ferretería, el formulario vacío muestra «La razón social es obligatoria.» (A, C); el doble clic manda dos pedidos y deja «Ya hay una ferretería con ese CUIT.» sobre el formulario limpio (B), sin duplicar." — Aceptación: tras el alta, el formulario vacío no muestra errores; un segundo envío mientras se guarda no sale. — Checks: `admin-ferreterias.spec.ts`
- S3: "Cotización dice «Todavía no se calcularon precios con el dólar» después de una importación en USD que ya los calculó (A, B, C)." — Aceptación: a definir después de reproducirlo (por lectura no cierra: L2). — Checks: a definir
- S4: "El extracto impreso de cuenta corriente muestra el CUIT; CU-12.6 dice «no su documento» (A, C)." — Aceptación: el impreso no muestra el documento del cliente (`docs/spec.md:233`). Supuesto: gana la spec sobre CC-099 (código y spec son del mismo día, 21/10); el backend sigue mandando el campo. — Checks: `extracto-imprimible.spec.ts`
- S5: "Una compra de otra ferretería dice «No se pudo cargar la compra.» en vez de «no encontrada» (A, B)." — Aceptación: con 404, «No se encontró la compra.» (supuesto: el texto); otro error sigue con «No se pudo cargar la compra.». — Checks: `compras.spec.ts`
- S6: "«¿Cambiar el margen y el redondeo?» aunque cambie uno solo (B, C)." — Aceptación (supuesto: los textos): «¿Cambiar el margen?», «¿Cambiar el redondeo?» o «¿Cambiar el margen y el redondeo?» según lo que cambió. — Checks: `configuracion-precios.spec.ts`
- S7: "Cliente elegido con F9 con el ticket vacío se pierde al recargar (B-3)." — Aceptación: con el ticket vacío y un cliente elegido, un F5 conserva el cliente; vaciar y cobrar lo siguen limpiando. — Checks: `ticket.store.spec.ts`, `ticket.storage.spec.ts`
- S8: "El Dueño ve solo «Disponible» al elegir Cuenta corriente; CU-07.4 pide saldo, límite y disponible (C-5)." — Aceptación: el Dueño ve saldo, límite y disponible (`docs/spec.md:119`); el Vendedor sigue sin cifras. — Checks: `dialogo-cobro.spec.ts`, `mostrador.spec.ts`
- S9: "El aviso «pasa tu tope» queda pegado al renglón después de bajar el descuento (A-5)." — Aceptación: al cambiar el descuento del renglón o el general se borra la marca de tope; las de dado de baja, sin precio o inexistente quedan. — Checks: `ticket.spec.ts`
- S10: "Artículo dado de baja sigue en la búsqueda del mostrador (marcado y sin poder agregarse), aunque el diálogo de baja dice que deja de aparecer (C-7)." — Aceptación (supuesto: se mantiene el diseño H06 de `BusquedaMostradorService.java:18-19`): el diálogo de baja dice que en el mostrador aparece marcado y no se puede vender. — Checks: `articulos.spec.ts`
- S11: "El alta rápida de cliente (F9) no pide condición de IVA: no sirve para una Factura A desde el mostrador (A-12)." Luca eligió «Condición de IVA (Recomendado)»: "Suma «Condición frente al IVA», con Consumidor Final por defecto. Si elegís Responsable inscripto, Monotributista o Exento, pide CUIT y domicilio, que es lo que la spec exige para esas condiciones." — Aceptación: el alta rápida crea el cliente con la condición elegida; con RI, Monotributista o Exento exige CUIT y domicilio (mismas reglas que el alta completa, `docs/spec.md:194`). — Checks: `dialogo-cliente.spec.ts`
- S12: "En el pago mixto el resto se sugiere con Enter y no con Tab (B-4; ya estaba en el QA del 04/11)." QA del 04/11: "con todo completo, Enter no cobró". — Aceptación (como GDS, G53: «Enter pasa al medio siguiente»): Tab sigue siendo la navegación normal; con todo completo, Enter cobra cuando el cobro tiene un solo botón «Cobrar» (ya cubierto: «con todo cubierto, Enter confirma los pagos…»); con la facturación activa, Enter no cobra a propósito, se elige con un clic si se factura (ya cubierto: «en el pago mixto completo Enter tampoco cobra; el botón sí…»). Sin cambio de código. — Checks: `dialogo-cobro.spec.ts`

## Tasks

- [ ] T1 · S1 S2 · ruta: inline · Aceptación: Admin › Ferreterías sin texto de más ni doble alta · Evidencia: — · Risk: none
- [ ] T2 · S4 S5 S6 S10 · ruta: inline · Aceptación: textos del extracto, compra, margen y baja · Evidencia: — · Risk: none
- [ ] T3 · S7 S9 · ruta: inline · Aceptación: ticket con cliente sin renglones y marca de tope · Evidencia: — · Risk: none
- [ ] T4 · S8 S12 · ruta: inline · Aceptación: cobro con cuenta corriente y mixto · Evidencia: — · Risk: none
- [ ] T5 · S11 · ruta: inline · Aceptación: alta rápida con condición de IVA · Evidencia: — · Risk: none
- [ ] T6 · S3 · ruta: inline · Aceptación: reproducir y arreglar la cotización · Evidencia: — · Risk: a definir
- [ ] T7 · todas · ruta: inline · Aceptación: suites, `ng build`, punta a punta y capturas de la guía si cambió una pantalla · Evidencia: — · Risk: a definir

## Log

- L1 (2026-11-06) Pedido original, textual:
  > seguimos con los bajos
- L2 (2026-11-06) Exploración (dos `odd-explorer`): S1 `admin-ferreterias.html:115` y callback vacío `admin-ferreterias.ts:93`; S2 `guardar()` sin guarda en vuelo (sí la tiene `configuracion-precios.ts`); S3 el texto sale de `configuracion-cotizacion.ts:158` con `vigente === null`, `DolarDePreciosService.vigente()` cae al último dólar guardado y la importación solo lo lee: por lectura no cierra; S4 `extracto-imprimible.html:23-28` contra `docs/spec.md:233`; S5 `compras.ts:14` un solo mensaje para todo error; S6 `configuracion-precios.html:117` título fijo; S7 `ticket.store.ts:50-55` borra el storage sin renglones y `ticket.storage.ts` rechaza la clave vacía; S8 `dialogo-cobro.ts:730-744`, ya tiene `limite`, `saldo` y `disponible`; S9 `aplicarDescuentoRenglon`/`Global` (`ticket.ts:97-108`) conservan `problema`; S10 `articulos.html:229` contra `BusquedaMostradorService.java:18-19` (H06); S11 `dialogo-cliente.ts:448` fija `CONSUMIDOR_FINAL`; S12 `enterEnMixto` (`dialogo-cobro.ts:903-928`), GDS G53 usa Enter.
- L3 (2026-11-06) Decisión de Luca sobre S11: «Condición de IVA (Recomendado)». Lo demás, como GDS o supuestos marcados arriba.
