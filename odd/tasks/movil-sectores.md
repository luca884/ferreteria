# Móvil por sector: implementar los diseños aprobados

Llevar a la app los diseños móviles de `design/movil-*` (Artículos, Precios, Ventas, Compras, Configuración y
Mostrador), con la dirección «barra al pulgar» que ya dejó `movil-pulgar`. Sólo visual: sin backend ni lógica.
Rama `feat/movil-sectores`, apilada: `main` ← `feat/movil-pulgar` (#53) ← `docs/disenos-movil` ← esta.

## Specs

S1 · El alcance es visual. Literal de Luca: «no busco cambiar logico de negocio o cosas del backend para movil,
solamente es la parte visua que se vea bien y util en el celular». Las excepciones que eligió son dos, ambas en
Artículos: el escáner por cámara y la selección múltiple por mantener apretado
(«agregarle el mantener un articulo y seleccionar varios como tiene la opcion 3, eso agrgarlo»).

S2 · Los seis sectores están diseñados y Luca los revisó y aprobó: `design/movil-articulos` (12),
`movil-precios` (8), `movil-ventas` (13), `movil-compras` (11), `movil-configuracion` (6) y `movil-mostrador` (7).
El artboard es la referencia de cada pantalla; los textos, reglas y roles son los de hoy.

S3 · El destino «Ventas» de la barra es un hub. Decisión de Luca: «Dejar el hub de Ventas». Hoy la barra del
Vendedor no tiene «Ventas» (`shell.ts` `DESTINOS_AL_PULGAR`, `menu.ts:55` `soloDueno`) y la del Dueño abre
`/ventas`. Pasa a ser el hub para los dos roles (Vendedor: Caja, Presupuestos, Remitos, Clientes; Dueño suma
Consulta de ventas y Reportes), sin dar acceso a nada que el rol no tenga hoy.

S4 · Los renglones con detalle usan la opción B, el renglón que se despliega. Literal: «vamos con la B el que
sde despliega». Referencia: `design/movil-compras/10-opcion-b-renglon-desplegable.html`.

S5 · El escritorio no cambia (S6 de `movil-pulgar`): toda regla nueva va dentro de `@media (max-width: 639.98px)`
y se mide a 390 px. Todo control táctil mide al menos 44×44 px y un botón apagado explica por qué
(`shared/motivo-de-accion.ts`).

S6 · Cada sector se hace completo antes de pasar al siguiente. Literal: «no quiero que sigamos con otro sector,
terminemos de disenar cada flujo».

S7 · Las altas, fichas y formularios largos pasan a pantalla completa con la acción fija abajo, y las acciones
de un renglón o de todo el ticket a una hoja inferior. Son los dos patrones compartidos de los diseños.

## Tasks

- [x] T1 · S5, S7 · cimientos compartidos: hoja inferior (reusa `trampa-foco`), botón flotante «Nuevo», barra de acción fija, `--alto-tactil` en `:root`. · Route: inline · Commit: f9a38cfc
- [x] T2 · S3 · hub «Ventas» y «Más»: ruta y componente por rol, la barra pasa a 4 destinos para el Vendedor, pastilla de la bandeja ARCA. Risk: item 2 (permisos): commit propio. · Route: inline · Commit: este commit (ver `git log`)
- [ ] T3 · S1, S2, S6 · Configuración (1/2): formas de pago, Consumidor Final, datos de la ferretería y usuarios en ficha completa. · Route: inline · Commit: —
- [ ] T4 · S1, S2, S6 · Configuración (2/2): facturación en tarjetas y el certificado en tres pantallas. · Route: inline · Commit: —
- [ ] T5 · S2, S6, S7 · Artículos (1/3): lista, ficha, alta, oferta, stock y movimientos en pantalla completa. · Route: inline · Commit: —
- [ ] T6 · S1, S6 · Artículos (2/3): selección múltiple por mantener apretado → Etiquetas. Comportamiento nuevo. · Route: inline · Commit: —
- [ ] T7 · S1 · Artículos (3/3): escáner por cámara en el buscador, con permiso negado → sigue a mano. Comportamiento nuevo, commit propio. · Route: inline · Commit: —
- [ ] T8 · S2, S7 · Mostrador (1/3): TOTAL y «Cobrar» fijos, hojas de renglón y «Más acciones», cliente en pantalla completa. · Route: inline · Commit: —
- [ ] T9 · S2, S5 · Mostrador (2/3): el cobro a pantalla completa (efectivo, recargo, vuelto, mixto, cuenta corriente, factura con ARCA). Risk: item 6 (el flujo de plata, y hoy el modal cubre <1024 px): commit propio. · Route: inline · Commit: —
- [ ] T10 · S2, S6 · Mostrador (3/3): presupuesto y remito en pantalla completa. · Route: inline · Commit: —
- [ ] T11 · S2, S6 · Ventas (1/2): caja, historial, reportes, anulaciones, consulta y detalle con la bandeja ARCA. · Route: inline · Commit: —
- [ ] T12 · S2, S6 · Ventas (2/2): clientes, cuenta corriente y cobranza, presupuestos y remitos. · Route: inline · Commit: —
- [ ] T13 · S2, S6 · Precios (1/2): importaciones y actualización masiva en fila apilada y el mapeo de columnas con muestra y hoja. · Route: inline · Commit: —
- [ ] T14 · S2, S6 · Precios (2/2): margen y redondeo, y cotización. · Route: inline · Commit: —
- [ ] T15 · S2, S6 · Compras (1/2): proveedores, ficha, baja y reactivación, lista y detalle de compras. · Route: inline · Commit: —
- [ ] T16 · S2, S4, S6 · Compras (2/2): nueva compra en tres pasos con el renglón desplegable (B). · Route: inline · Commit: —
- [ ] T17 · S5 · cierre: `design/system.md` (de propuesta a implementado), secciones «Agregado móvil» en los CU que cambian el flujo táctil, y medición final de los seis sectores a 360, 390, 768, 1024 y 1280 px. · Route: inline · Commit: —

## Estrategia de entrega

- Cada tarea es un slice de ~400 líneas como máximo; las que dicen «commit propio» van solas. Un commit por tarea, con tests y docs.
- Verificación: Vitest para comportamiento (hoja, selección, hub por rol) y para las reglas de CSS dentro de `max-width: 639.98px`; el layout se mide en el navegador a 390 px porque Vitest no evalúa media queries (`odd/tasks/responsive.md`).
- Deuda heredada que conviene resolver al pasar: los presupuestos de CSS de `mostrador.css` (6,91 kB, error duro a 8 kB) y los selectores globales de T5 de `movil-pulgar` (L14).
- Push, PR y merge los hace Luca. Cadena de PR: #53 → `docs/disenos-movil` → esta.

## Log

L1 · «prepara las ramas y luego hace el plan de la implemntacoin en ood task»
L2 · Contexto de L1: los seis sectores están diseñados y aprobados (`design/movil-*`); no hay nada implementado de ellos. `movil-pulgar` (T1-T8) dejó la barra inferior, la fila apilada y los filtros plegados.
L3 · Ramas: `docs/disenos-movil` ya era `feat/movil-pulgar` más 14 commits sólo de `design/` y `docs/spec` (no tocan código), así que no hubo que partirla: se apila su PR sobre #53 y esta rama sale de ella. Un intento de armar los diseños sobre `main` falló porque `design/system.*` entra con `movil-pulgar`.
L4 · Decisión de Luca: «Dejar el hub de Ventas» (ver S3).
L5 · T1 y T2 hechos, con tests (suite del frontend verde, build sin errores). Pendiente: la medición a 390 px en el navegador de los cimientos de T1 y del hub de T2; no se pudo porque Docker estaba apagado y sin backend no hay login. Se hace en la primera pantalla que use la hoja, el botón flotante o la barra fija (T3), con los demos levantados.
L6 · Decisión de implementación de T2: el hub vive en `/area-ventas` (nueva, los dos roles) y NO en `/ventas`, que sigue siendo la consulta de ventas del Dueño con su URL y sus enlaces (`/ventas?fiscal=…`, `/ventas/:id`). El hub es una vista de `gruposPara(rol)`, así que no puede mostrar nada que el rol no tenga. «Ventas» queda marcado en la barra en cualquier pantalla del grupo. Orden de las filas: el del menú (el artboard tiene Caja y Ventas primero, pero su nota dice «el orden del menú de hoy»). Sin estado de la caja en la fila de Caja («Abierta desde las 08:02»): sería lógica nueva (S1).
