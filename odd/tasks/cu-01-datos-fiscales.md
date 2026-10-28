# cu-01-datos-fiscales

Objetivo: el Dueño carga y edita los datos del emisor que va a imprimir la factura (razón social, condición de IVA, domicilio comercial, inicio de actividades e Ingresos Brutos), con el CUIT fijo. El super admin deja de editarlos. · Por qué: es la rama 1 de la fase 5 (`wiki/plan-facturacion.md` del vault); la RG 1415 pide esos datos en cada comprobante y hoy la ferretería no los tiene.
Rama: `feat/cu-01-datos-fiscales` desde `c9e3dfe` · Entrega: single-pr · Runner: `cd backend && ./mvnw test -Dtest=<Clase>` / `cd frontend && npx ng test --watch=false --include=<spec>` · Mirror Engram: `odd/cu-01-datos-fiscales/tasks` (ok)
Ruteo: caso 5 (Opus de punta a punta: migración, permisos y contrato del panel del super admin) · Pronóstico: ~1.200 líneas cambiadas · Heurística por tarea: ~400 (no es límite)

## Specs

Alcance: datos fiscales del emisor (CU-01 paso 1) para el Dueño; el super admin solo los carga en el alta. · Fuera de alcance: punto de venta, ambiente, certificado y habilitación de la facturación (ramas 2 y 5 del plan); trabar cambios por ventas pendientes de CAE (la venta congela los datos, rama 3); la variante de factura A habilitada (pregunta de la fase 0); varias sucursales.
Restricciones: la ferretería sale solo del JWT; un recurso de otra ferretería no se ve; mensajes en español rioplatense; migración aditiva con rollback probado.

- S1: D1, propuesta del agente aprobada por Luca el 2026-10-28 («dale me parece bien»), textual: "Hoy la razón social y la condición de IVA las edita solo el super admin. Te propongo: El super admin carga razón social, CUIT y condición de IVA únicamente en el alta. Después, el Dueño es el único que edita la razón social, la condición de IVA, el domicilio comercial, el inicio de actividades y un dato nuevo: Ingresos Brutos (número, Convenio Multilateral, Exento o No contribuyente). El CUIT queda fijo." — Aceptación: el Dueño lee y guarda los cinco datos y ve el CUIT sin poder cambiarlo; el super admin ya no tiene la edición (ni endpoint ni pantalla) y sigue viendo razón social, CUIT y condición de IVA en su panel. — Checks: `DatosFiscalesIntegrationTest`, `FerreteriasAdminIntegrationTest`, specs de Vitest de la pantalla y del panel.
- S2: criterio de Luca para toda la fase, textual: "perfecto vamos con tus recomendaciones, lo que podamos usar del gds mejor" — Aceptación: las dudas de producto de la rama se resuelven como GDS cuando GDS lo muestra; si no, se anotan como supuesto.
- S3 (supuesto, del plan revisado): la pantalla «Datos de la ferretería» va en un grupo nuevo «Facturación» del menú del Dueño y en «Primeros pasos»; aclara que el domicilio y el inicio de actividades son los del local donde se factura; al guardar, la sesión se refresca para que el nombre de la ferretería cambie en el menú.
- S4 (supuesto): domicilio, inicio de actividades e Ingresos Brutos son opcionales al guardar (columnas nullable). Completos van a ser requisito para habilitar la facturación (D3, rama 2). Validaciones: razón social obligatoria hasta 150; condición de IVA obligatoria; domicilio hasta 200; Ingresos Brutos hasta 30; inicio de actividades no puede ser futuro (fecha de Buenos Aires). Un texto en blanco se guarda como vacío (NULL).
- S5 (supuesto): el Vendedor no ve la pantalla (aviso, sin datos) y recibe 403 del API, igual que las otras pantallas de Configuración; el super admin recibe 403.

## Tasks

- [ ] T1 · S1 · ruta: inline · Aceptación: `docs/spec.md` dice quién edita cada dato (Actores, CU-01 paso 1, Tenant con Ingresos Brutos) · Evidencia: — · Risk: none (docs)
- [ ] T2 · S1, S4 · ruta: inline · Aceptación: V31 aditiva suma `domicilio_comercial`, `inicio_actividades` e `ingresos_brutos` nullable; `MigracionV31Test` (columnas, filas existentes intactas, datos demo, rollback con guarda) y `MigracionV6Test` con la 31 · Evidencia: — · Risk: item 1 (migración) → commit propio
- [ ] T3 · S1, S4, S5 · ruta: inline · Aceptación: `GET/PUT /api/datos-fiscales` del Dueño con la ferretería del JWT; CUIT de solo lectura; validaciones; Vendedor y super admin 403 antes de leer el cuerpo; otra ferretería no ve ni toca · Evidencia: — · Risk: item 2 (endpoint con rol y tenant)
- [ ] T4 · S1 · ruta: inline · Aceptación: sale `PUT /api/admin/ferreterias/{id}` y la edición del panel; el alta y la lista del super admin siguen igual · Evidencia: — · Risk: item 2 (permisos), item 3 (contrato que consumía el panel, cambio pedido)
- [ ] T5 · S1, S3, S5 · ruta: inline · Aceptación: pantalla «Datos de la ferretería» (grupo «Facturación» y «Primeros pasos»), CUIT a la vista sin editar, refresco de sesión al guardar; specs de Vitest · Evidencia: — · Risk: none
- [ ] T6 · todas · ruta: inline (suites) + `odd-verifier` (Risk items 1 y 2) · Aceptación: suite backend, suite frontend y `ng build` verdes; ego-browser como Dueño y como Vendedor; verificador sin bloqueantes · Evidencia: — · Risk: —

## Log

- L1 (2026-10-28) Pedido original, textual. Luca, ante la pregunta D1: «dale me parece bien y haceme todas las preguntas de una». Antes, sobre el plan: «revisa de nuevo el plan con este nuevo grado de effort, si te parece bien continuamos con ese». Después, sobre D2 a D13: «perfecto vamos con tus recomendaciones, lo que podamos usar del gds mejor».
- L2 (2026-10-28) Exploración:
  - `Ferreteria.java:27-73`: razón social, CUIT (`updatable=false`) y condición de IVA; `actualizarDatos` lo usa solo el super admin (`FerreteriasAdminService.java:92-94`, `PUT /api/admin/ferreterias/{id}` en `FerreteriasAdminController.java:42-45`).
  - Molde del Dueño: `ConfiguracionVentasController`/`Service` (ferretería del token con `TenantContext.requireFerreteriaId()`), `SecurityConfig.java:46-73` (rol antes de leer el cuerpo).
  - Tests a tocar: `FerreteriasAdminIntegrationTest.java:136-166` (editar), `FerreteriaTest.java:28-32`, `MigracionV6Test.java:182-189`. Molde de migración: `MigracionV27Test`; rollback con guarda: `rollback-v28-ajustes-cuenta-corriente.sql`.
  - Frontend: `features/configuracion-ventas/*` (molde de pantalla), `layout/shell.html:100-150` (grupos del menú), `features/mostrador/primeros-pasos.html`, `features/admin-ferreterias/*` (edición en `admin-ferreterias.ts:155-193`), `AuthService.refrescarSesion()`.
