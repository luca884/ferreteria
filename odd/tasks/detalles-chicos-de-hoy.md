# detalles-chicos-de-hoy

Objetivo: arreglar los cuatro detalles chicos vistos hoy en producción · Por qué: la advertencia de variación fuerte sin «$» ni unidad, la de la compra contra el costo de factura, códigos que se parten y botones de barra altos (L1).
Rama: `fix/detalles-chicos-de-hoy` desde `346e11c4` · Entrega: single-pr · Runner: backend `./mvnw test -Dtest=<Clase>` / suite `./mvnw test`; frontend `npx ng test --watch=false --include=<spec>` / suite `npx ng test --watch=false` / `npx ng build` · Mirror Engram: `odd/detalles-chicos-de-hoy/tasks` (ok)
Pronóstico: ~250 líneas · Modelo: Opus; T1 delegada a un `odd-implementer` Opus por el backstop de contexto

## Specs

Alcance: los cuatro detalles de L1. Fuera de alcance: la fusión de columnas de Nueva compra (decisión de Luca aparte) y medir la advertencia de la importación o del masivo sobre el costo real.
Restricciones: sin migraciones ni cambios de contrato JSON (solo cambia el texto de los avisos). Todo cambio visual se prueba con ego-browser a 1280 y 1440 px.

- S1: «los detalles visto hoy lo arreglamos tambien» (L1), al detalle «la advertencia del 60 % de la importación dice «de 769,50 a 1.231,20», sin «$» ni «/ m»» — Aceptación: la advertencia de variación fuerte dice los importes con su moneda y, si el artículo no va por unidad, con su sufijo: «El costo sube un 60 % (de $ 769,50 / m a $ 1.231,20 / m). Revisá que sea correcto.»; en dólares, «US$»; por unidad, sin sufijo — Checks: `./mvnw test -Dtest=VariacionFuerteTest,PlanificadorImportacionTest,PlanificadorMasivoTest,ArticulosParaCompraServiceTest,CompraServiceIntegrationTest`
- S1a (supuesto): vale para las tres advertencias que arma `VariacionFuerte.mensaje`: la importación, Actualizar precios y la compra, porque sus grillas ya muestran «$» y «/ kg». El precio va siempre en pesos.
- S2: misma cita, al detalle «la advertencia de variación fuerte de la compra compara el costo de la factura, no el costo real» — Aceptación: la advertencia de la compra compara `costoRealNuevo` contra `costoRealAnterior`, como el contador. Sin recargos (ni en la compra ni en el artículo) el texto queda igual que hoy. Con recargos dice «El costo real sube un N % (de $ A a $ B)» — Checks: `./mvnw test -Dtest=ArticulosParaCompraServiceTest,CompraServiceIntegrationTest,CompraRecargosIntegrationTest`
- S2a (supuesto): con recargos que se borran, el porcentaje se mide sobre el costo real (que es lo que mueve el precio) aunque las columnas de costo real no se vean.
- S3: misma cita, al detalle «los códigos largos («PR36-NUEVO1») se parten en el guion» — Aceptación: en las tablas de la importación (vista previa y «No están en la lista») el código no se parte, y la vista previa sigue entrando a 1280 px — Checks: ego-browser, `npx ng build`
- S4: misma cita, al detalle «los botones del encabezado («Ver compras», «Volver a la ficha») se ven como cajas altas» — Aceptación: en las barras donde ningún botón tiene ícono, los botones tienen el alto de un control común. Las barras con íconos (o mixtas) no cambian — Checks: ego-browser en Nueva compra, Compras, Movimientos, Clientes del artículo, Caja, Historial de cajas, Cuenta corriente y una barra con íconos

## Tasks

- [x] T1 · S1, S1a, S2, S2a · ruta: delegada (`odd-implementer` Opus, backstop de contexto) · Evidencia: RED 32/348; GREEN 567 + 29 tests, front 278/278 (L4) · commit `940153f4` · Risk: none. `VariacionFuerte` la comparten 3 módulos, pero el cambio de texto en los tres es S1a, la firma de 3 argumentos sigue, `Unidad` solo suma `abreviatura()` (su `name()` y el JSON no cambian) y los tres textos se vieron en la app (L5).
- [x] T2 · S3, S4 · ruta: inline (CSS) · Evidencia: `ng build` OK; ancho en T3 · commits `940153f4` y `8f45f688` · Risk: none
- [x] T3 · S1–S4 · ruta: worker de ego-browser · Evidencia: L5 y L7 · Risk: none

## Log

- L1 (2026-11-08) Luca, textual: «listo cuando se haga el deploy lo probamos con ego browser para ver que funione y los detalles visto hoy lo arreglamos tambien, tambien cuando estemos en el navegador me mostras la situacion que es decision mia y luego cerramos sesion, la proxima seguimos con el TOTP del super admin». Los detalles de hoy (`detalles-visuales-compra-y-masivo.md`, L3 y L6):
  > - la advertencia del 60 % de la importación dice «de 769,50 a 1.231,20», sin «$» ni «/ m»;
  > - los códigos largos («PR36-NUEVO1») se parten en el guion;
  > - los botones del encabezado («Ver compras», «Volver a la ficha») se ven como cajas altas;
  > - la advertencia de variación fuerte de la compra compara el costo de la factura, no el costo real.
- L2 (2026-11-08) Exploración (`odd-explorer`):
  - **S1.** El texto lo arma `VariacionFuerte.mensaje(Sujeto, anterior, nuevo)` (`catalogo/model/VariacionFuerte.java:41-53`) sin moneda ni unidad. Se llama desde `PlanificadorImportacion.java:224`, `PlanificadorMasivo.java:170-171`, `:200` y `:236`, y `ArticulosParaCompraService.java:164-165`. `ImporteArgentino.formato(valor, Moneda)` da «$ 1.234,50».
  - **S2.** `ArticulosParaCompraService.java:159-173` ya recibe `costoRealAnterior` y `costoRealNuevo`, pero compara el costo base.
  - **S3.** `vista-previa-tabla.html:74` e `importaciones.html:358-359`, con `td.codigo` sin `nowrap`. No va al global: `.codigo` también es la etiqueta imprimible.
  - **S4.** `.accion-barra` tiene `min-height: 54px` (`styles.css:480-492`), pensado para ícono más nombre; `.pagina-encabezado` no se usa. Regla propuesta: `app-barra-acciones:not(:has(svg, kbd)) .accion-barra { min-height: var(--alto-control) }`.
- L3 (2026-11-08) El PR #37 pasó en producción: «/ kg» en Actualizar precios, 977/977 a 1280, y Nueva compra con recargos, 1053/1053, sin scroll y con SUBTOTAL visible. No se cambió nada.
  - **Decisión de Luca sobre fusionar las columnas de Nueva compra**, vista en dos pestañas a 1280 px (actual y prototipo de 7 columnas): «lo dejamos asi».
  - **Propuesta para la rama del responsive.** Luca: «dale me parece bien».
    - En todas las tablas: ancho mínimo por columna, scroll horizontal dentro de la grilla (nunca en la página) y la primera columna fija.
    - En el celular: tarjetas, solo en las pantallas que tiene sentido usar desde ahí (consulta de precio y stock, ventas del día, reportes).
    - Se le muestra un prototipo de tarjetas en ego-browser.
- L4 (2026-11-08) T1 la hizo el `odd-implementer` (Opus).
  - **Cambios.**
    - `VariacionFuerte.mensaje(sujeto, anterior, nuevo, Moneda, Unidad)` es una sobrecarga nueva: la de 3 argumentos sigue igual.
    - Se agregan `Sujeto.COSTO_REAL` y `Unidad.abreviatura()`.
    - La importación y el masivo pasan la moneda y la unidad del artículo; el precio va siempre en ARS.
    - La compra compara el costo real. Dice «El costo real» si la compra o el artículo tienen recargos, y los del artículo se leen antes de que la compra los pise.
  - **RED.** 32 de 348 fallan con el texto esperado; por ejemplo, «(de 769,50 a 1.231,20)» en lugar de «(de $ 769,50 / m a $ 1.231,20 / m)».
  - **GREEN.**
    - Las 10 clases de Verification: 567 tests, 0 fallas.
    - `CompraFacturaPdfRegresionIntegrationTest`, `CompraControllerIntegrationTest` y `PrecioManualRegresionIntegrationTest`: 29 tests, 0 fallas.
    - Front: 278/278.
    - Control del padre: `VariacionFuerteTest` 16/16 y `CompraRecargosIntegrationTest` 10/10.
  - **T2** (CSS: `td.codigo` con `nowrap` en las dos tablas de la importación, y la regla de `.accion-barra` sin íconos en `styles.css`). `ng build` OK. La prueba es T3.
- L5 (2026-11-08) T3 en la app local, con ego-browser a 1280, sobre `940153f4`.
  - **S1.** Pasa:
    - importación: «El costo sube un 60 % (de $ 900,00 / m a $ 1.440,00 / m). Revisá que sea correcto.»;
    - Actualizar precios: «/ kg», «/ m» y sin sufijo por unidad; la grilla mide 962/962.
  - **S2.** Pasa. Con recargo nuevo dice «El costo real sube un 60 % (de $ 58.400,00 a $ 93.440,00)». Con recargos del artículo y compra sin recargos, no avisa por costo. El aviso de precio que salió se debió al SQL de la prueba, que no recalculó `precio_final`.
  - **S4.** Pasa: barras sin íconos a 36 px y barras con íconos a 54 px.
  - **S3.** Falla a medias: el código no se parte, pero la grilla quedó en 985/977. Se arregla con `padding-inline: 8px` solo en esa tabla (`8f45f688`); remedición pendiente.
  - **Base local intacta.**
  - **Pendientes nuevos, fuera de esta tanda:**
    - En celdas angostas, el aviso corta entre «de $» y el importe: `ImporteArgentino` usa espacio común y el front usa NBSP.
    - En Artículos a 1280, «Página 1 de 208 · 4149 artículos» se pisa con los checkboxes «Mostrar dados de baja» y «Solo en oferta».
- L6 (2026-11-08) Prototipo de tarjetas en el celular (producción, solo DOM, sin guardar nada), en tres pestañas de ego-browser para Luca: la compra como está, la compra en tarjetas y Artículos en tarjetas. Capturas en el scratchpad de la sesión (`celular/`).
- L7 (2026-11-08) S3 remedido sobre `8f45f688`: la grilla mide 977/977 a 1280 y 1137/1137 a 1440. «PR36-NUEVO1» va en una línea, cada importe con su sufijo en una línea, «Variación fuerte» debajo y el Detalle entero. Las columnas no quedan demasiado juntas.
  Cierre:
  - backend `./mvnw test` sobre `940153f4`: 6469 tests, 0 fallas;
  - frontend `npx ng test --watch=false` sobre `8f45f688`: 164 archivos pasan;
  - `ng build` OK.

  Supuestos que Luca tiene que confirmar:
  - S1a: el aviso nuevo también en Actualizar precios y en la compra;
  - S2a: con recargos que se borran, se mide el costo real.
