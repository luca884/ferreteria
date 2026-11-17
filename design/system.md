# Sistema de diseño — Ferretería

Sincronizado el 2026-11-14 desde `frontend/src/styles.css` (bloque `:root`, líneas 1-120) y
`frontend/src/index.html` (carga de la tipografía). Sólo se registran valores que existen en
esas fuentes: no hay tokens inventados.

## Color

Fuente: `frontend/src/styles.css` → `:root`.

### Acento (marca)

| Token | Valor | Uso |
| --- | --- | --- |
| `--acento` | `#2563eb` | acción principal, foco, 5,2:1 sobre blanco |
| `--acento-fuerte` | `#1d4ed8` | hover de la acción principal, `--info` |
| `--acento-profundo` | `#1e3a8a` | fondo del TOTAL y del cobro |
| `--acento-suave` | `#eff6ff` | fondo teñido (con `--info-fondo`) |
| `--acento-anillo` | `#dbeafe` | anillo de foco |
| `--acento-borde` | `#bfdbfe` | borde de estados informativos |
| `--acento-texto` | `#ffffff` | texto sobre acento |
| `--profundo-texto-tenue` | `#bfdbfe` | texto secundario sobre `--acento-profundo` |

### Neutros (una sola familia de grises fríos)

| Token | Valor | Uso |
| --- | --- | --- |
| `--fondo` | `#f4f6fa` | fondo de la app |
| `--superficie` | `#ffffff` | tarjetas, tablas, diálogos |
| `--superficie-2` | `#f8fafc` | encabezado de tabla, filas alternas |
| `--superficie-3` | `#f1f5f9` | campos deshabilitados, chips |
| `--borde` | `#e2e8f0` | borde estándar |
| `--borde-suave` | `#f1f5f9` | separador interno |
| `--borde-fuerte` | `#cbd5e1` | borde de control activo |
| `--texto` | `#0f172a` | texto principal |
| `--texto-menu` | `#334155` | ítems de navegación |
| `--texto-suave` | `#475569` | texto secundario |
| `--texto-tenue` | `#5b6b82` | texto chico, ≥ 4,9:1 sobre blanco, `--fondo` y `--superficie-3` |
| `--texto-deshabilitado` | `#94a3b8` | controles apagados |

### Estados

| Token | Valor |
| --- | --- |
| `--error` / `--error-fondo` / `--error-borde` | `#b91c1c` / `#fef2f2` / `#fecaca` |
| `--exito` / `--exito-fondo` / `--exito-borde` | `#047857` / `#ecfdf5` / `#a7f3d0` |
| `--advertencia` / `--advertencia-fondo` / `--advertencia-borde` | `#92400e` / `#fffbeb` / `#fde68a` |
| `--advertencia-insignia` / `--advertencia-marca` | `#fef3c7` / `#d97706` |
| `--info` / `--info-fondo` / `--info-borde` | derivan de `--acento-fuerte` / `--acento-suave` / `--acento-borde` |

## Tipografía

Fuente: `frontend/src/styles.css` (`--fuente`, `--fuente-mono`, escala `--texto-*`) y
`frontend/src/index.html` (carga de Geist y Geist Mono desde Google Fonts).

| Token | Valor |
| --- | --- |
| `--fuente` | `'Geist', system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif` |
| `--fuente-mono` | `'Geist Mono', ui-monospace, 'Cascadia Mono', Consolas, 'SFMono-Regular', Menlo, monospace` |
| `--texto-2xs` | 0.6875rem (11px) |
| `--texto-xs` | 0.75rem (12px) |
| `--texto-sm` | 0.8125rem (13px) |
| `--texto-base` | 0.875rem (14px) — el cuerpo por defecto |
| `--texto-md` | 0.9375rem (15px) |
| `--texto-lg` | 1.125rem (18px) |
| `--texto-xl` | 1.375rem (22px) |

Regla de los artboards: usan la pila del sistema (la primera opción de respaldo de
`--fuente` y `--fuente-mono`), porque un artboard no puede pedir fuentes externas. La
tipografía de Geist sólo se ve en la app real.

## Espacios, radios, sombras

Fuente: `frontend/src/styles.css`.

- Escala de espacio: `--e-1` 4px, `--e-2` 8px, `--e-3` 12px, `--e-4` 16px, `--e-5` 20px,
  `--e-6` 24px, `--e-8` 32px.
- Radios: `--radio-sm` 4px (adentro), `--radio` 8px, `--radio-lg` 10px, `--radio-xl` 14px
  (contenedores) y pastilla completa 999px, que el proyecto ya usa en `.pastilla`
  (`frontend/src/styles.css:1469`). Los artboards la declaran como `--radio-pastilla`
  porque el bloque `:root` del proyecto no la tiene con nombre propio.
- Sombras teñidas con el gris de la paleta (`rgb(15 23 42 / …)`, nunca negro):
  `--sombra-1` `0 1px 2px /0.05`, `--sombra-2` `0 8px 24px /0.1`, `--sombra-3`
  `0 24px 60px /0.3`. Velo de diálogo: `--velo` `rgb(15 23 42 / 0.45)`.

## Marco y densidad

Fuente: `frontend/src/styles.css`.

- `--ancho-menu` 212px, `--alto-barra` 58px, `--pantalla-x` 16px, `--pantalla-y` 14px.
- Anchos de contenido: `--ancho-pantalla` 1400px (tablas), `--ancho-medio` 880px,
  `--ancho-formulario` 640px.
- Densidad: `--alto-control` **36px**, `--celda-y` 10px, `--celda-x` 12px.

**Nota de móvil**: `--alto-control` 36px y las celdas de 10px son densidad de escritorio.
En pantalla táctil el objetivo es ~44×44 px, así que el móvil necesita su propia densidad
en vez de heredar estos valores.

## Componentes observados

Fuente: los templates de `frontend/src/app/` y `frontend/src/styles.css`.

- **Barra de acciones** (`shared/barra-acciones.*`): fila horizontal con botones de acción
  sobre `--superficie`, siempre a todo el ancho; no envuelve, se desliza.
- **Tabla** (`styles.css`, reglas `table`): encabezado en `--superficie-2` con texto
  `--texto-2xs` en mayúsculas y `--texto-tenue`; celdas con `--celda-y`/`--celda-x`;
  importes con `--fuente-mono` y `font-variant-numeric: tabular-nums`.
- **Alerta** (`.alerta`): fondo de estado + borde + texto del mismo estado; variantes
  `exito`, `error`, `advertencia`.
- **Diálogo** (`shared/dialogo-detalle.*`): overlay con `--velo` y `--sombra-3`, cierra con
  Esc y con ✕, trampa de foco y foco inicial.
- **Motivo de acción** (`shared/motivo-de-accion.ts`): un botón apagado por regla de
  negocio lleva `aria-disabled="true"` (no `disabled`) + `aria-describedby` a un texto con
  `role="status"`; el clic explica el motivo en vez de ejecutar.
- **Botón primario**: fondo `--acento`, texto `--acento-texto`, borde `--acento-fuerte`;
  secundario: fondo `--superficie` con borde `--borde-fuerte`.

## Huecos conocidos del sistema

- No hay tokens de densidad móvil (alto de control, tamaño de fila, separación táctil).
- No hay patrón definido de navegación para pantalla angosta: el menú lateral se convierte
  en un drawer de 320px y la barra de acciones se desliza horizontalmente sin indicación.
- Las tablas no tienen un patrón móvil: `stock-bajo-minimo` mide 1267px en una pantalla de
  390px y sólo dos columnas quedan a la vista.
