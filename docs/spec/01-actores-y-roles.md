# Actores y roles

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · sección extracto de `spec.md:19-35` en la partición del 2026-11-10.

<!-- spec payload: spec.md:19-35 -->
## Actores y roles

El MVP tiene tres roles fijos. Nada de permisos configurables por ahora.

| Rol | Quién es | Puede |
| --- | --- | --- |
| Super admin | Vos (el proveedor del servicio) | Dar de alta ferreterías (razón social, CUIT y condición de IVA), suspenderlas, ver estado general. Después del alta no edita los datos de la ferretería: los edita el Dueño (CU-01). Marca una ferretería «de prueba», le asigna el simulador de ARCA y prende su «facturación habilitada» (CU-01). No ve datos de ventas |
| Dueño | El dueño o encargado de la ferretería | Todo dentro de su ferretería: configuración, ARCA (salvo el ambiente, que nunca elige), usuarios, precios, reportes, anulaciones |
| Vendedor | Quien atiende el mostrador | Buscar artículos, vender, facturar, consultar stock, abrir y cerrar la caja |

El super admin entra con email, contraseña y el código de 6 números de Google Authenticator (TOTP, RFC 6238: cambia cada 30 segundos; también vale el código anterior y el siguiente, por si el reloj del celular está corrido). La primera vez, después de la contraseña, la pantalla muestra un código QR una sola vez para cargarlo en la app; el primer código bueno lo activa, y desde ahí se pide el código en cada login. Un código usado no vale de nuevo, 5 códigos mal seguidos bloquean como el login y el secreto se guarda cifrado con su propia clave (`TOTP_CLAVE_MAESTRA`). Si pierde el celular, se resetea por base con el `UPDATE` del README (Deploy) y el próximo login vuelve a mostrar el QR. El Dueño y el Vendedor entran como siempre, con email y contraseña.

El Vendedor lee artículos, rubros, proveedores y formas de pago, pero no los escribe: el alta, la edición, la baja y la reactivación, la oferta y el ajuste de stock de un artículo, y la vista previa del margen de un rubro le dan 403 por ruta, también con un cuerpo o un parámetro inválido (el 403 va antes de la validación). Lo mismo las compras (CU-13).

**Actor externo:** ARCA (WSAA para autenticación, WSFEv1 para pedir el CAE). Hasta la conexión real, un simulador del WSFEv1 hace sus veces para la ferretería marcada «de prueba» (ver Integración ARCA).
<!-- fin payload: spec.md:19-35 -->

