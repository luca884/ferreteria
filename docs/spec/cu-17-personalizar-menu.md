# CU-17 · Personalizar el menú

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · caso de uso agregado el 2026-11-20 a pedido de Luca. Feature doc: [`odd/tasks/personalizar-menu.md`](../../odd/tasks/personalizar-menu.md).

**CU-17 Personalizar el menú** (Dueño)

El Dueño arma el menú lateral de su ferretería: elige qué grupos hay (Ventas, Configuración, los que él cree), qué
opciones lleva cada uno y en qué orden. Lo hace para su propio menú y para el de cada usuario.

## Achicar el menú (menu-colapsable)

Desde 1024 px, cada persona puede achicar el menú lateral con el botón de arriba a una columna de unos 64 px: el logo, el
Mostrador, un ícono por grupo (con el color del grupo), el avatar y «Salir». Tocar un grupo en la columna abre el menú ancho
con ese grupo desplegado. La elección se guarda en el navegador (`menu-lateral-colapsado:{usuarioId}`, una por usuario), no en el servidor: no es una
disposición del Dueño y no cambia el menú de nadie más. Sin dato, el menú abre ancho como siempre. Debajo de 1024 px el
menú sigue siendo el cajón y la barra de destinos de siempre.

## Qué se personaliza

El menú lateral con sus grupos desplegables y las opciones de adentro. **«Mostrador» queda fijo arriba**, fuera de los
grupos, como hoy. El Dueño puede:

1. **Mover opciones** entre grupos y **reordenarlas** dentro de un grupo.
2. **Crear, renombrar y borrar grupos propios.** Un grupo propio lleva un color elegido de una paleta cerrada.
3. **Ocultar opciones** que no usa. Ocultar saca la opción del menú; no la saca del sistema y se puede volver a mostrar.
4. **Renombrar y reordenar los grupos originales** (por ejemplo, que «Precios» pase a llamarse «Listas»).

## Qué menú ve cada usuario

Hay tres niveles, y gana el más específico:

1. **El ajuste del usuario**, si el Dueño le armó uno.
2. **La base de su rol** (una para el Dueño y otra para los Vendedores), si el Dueño la armó.
3. **El menú original** del sistema.

- Borrar el ajuste de un usuario lo deja con la base de su rol. Borrar la base de un rol lo deja con el menú original.
- Una opción nueva que el sistema agregue después de que el Dueño personalizó **no desaparece**: aparece en su grupo
  original, o en un grupo «Otras» si ese grupo ya no existe. Una opción guardada que el sistema ya no tiene se ignora.

## Reglas

- **Personalizar no cambia permisos.** Ocultar o mover una opción nunca da ni quita acceso. Las pantallas de solo Dueño
  siguen siendo de solo Dueño: un Vendedor que entra por URL a una pantalla que su menú no muestra ve el aviso de
  siempre y el servidor responde 403. Un menú oculto no es una barrera de seguridad.
- **El editor solo ofrece lo que el rol del usuario editado puede abrir.** Al armar el menú de un Vendedor no se
  ofrecen las pantallas de solo Dueño, y el servidor rechaza una disposición de Vendedor que las incluya.
- **Solo el Dueño edita**, el menú de cualquier usuario de **su** ferretería. El Vendedor no tiene la pantalla de edición
  y no puede cambiar su propio menú. El usuario de otra ferretería se trata como inexistente (404).
- **Cada usuario lee su menú ya resuelto** sin importar su rol. El super admin no tiene ferretería y no tiene menú
  personalizable.
- **Restaurar.** El Dueño puede restaurar el menú original de un rol o de un usuario, con confirmación.
- **Un grupo sin opciones visibles para el usuario no se muestra**, como hoy.

## Celular

La barra inferior sigue a la disposición: **Mostrador**, los **dos primeros grupos** que tengan opciones para ese
usuario y **«Más»**, que abre el cajón con el menú completo. Qué abre el botón de un grupo:

- **Ventas y los grupos que arma el Dueño abren su hub**: una pantalla con las opciones visibles del grupo, como hoy
  hace «Ventas».
- **Artículos abre directo la lista de artículos**, como hoy, mientras esa opción siga dentro de su grupo. Si el Dueño la
  mueve a otro grupo, el grupo abre su hub como los demás.

Con el menú original la barra no cambia: Mostrador, Ventas, Artículos y Más.

El editor se usa **desde la computadora**: en una pantalla chica (≤ 1023.98 px) muestra «Personalizá el menú desde la
computadora.» en lugar del editor.

## El editor

Pantalla «Personalizar menú» dentro de Configuración, solo Dueño.

- El editor abre en el menú del Dueño (la base de su rol). «Menú de» permite editar la base de Vendedores, la del Dueño
  o el ajuste de un usuario. La lista muestra el nombre, el rol y si está inactivo. El editor indica si el menú es
  propio, heredado del rol u original.
- Si editás la base de Vendedores o el menú de otro usuario, aparece el aviso «Estás editando el menú de … Tu menú no cambia.».
- Arrastrá las opciones para reordenarlas, pasarlas a otro grupo o soltarlas en «Ocultas». Para volver a mostrar una,
  arrastrala desde «Ocultas» a un grupo. «Personalizar menú» se puede mover, pero no ocultar.
- Arrastrá un grupo desde su encabezado para reordenarlo. Ahí podés cambiar el nombre y elegir uno de los cinco colores, que se llaman por su color (Turquesa, Naranja, Violeta, Verde y Rosa), no por la sección.
  «Agregar grupo» crea un grupo propio. Solo los grupos propios se pueden borrar, con confirmación; sus opciones
  vuelven a sus grupos originales. Un grupo original se puede dejar vacío.
- «Guardar» aplica los cambios cuando son válidos. «Descartar cambios» vuelve a lo cargado. Si cambiás «Menú de» con
  cambios pendientes, se pide confirmación para descartarlos.
- «Restaurar» pide confirmación, borra la personalización de ese nivel y muestra el menú que queda vigente: la base
  del rol (si se borró un ajuste de usuario y hay una base) o el original. Guardar y restaurar actualizan enseguida el
  menú del Dueño que está editando. Los demás usuarios ven el cambio cuando abren la app o recargan.

### Límites

- Hasta 10 grupos por disposición. El nombre tiene de 1 a 30 caracteres y no se puede repetir dentro de la misma
  disposición, aunque cambien las mayúsculas.

### Mensajes

- Nombre vacío: «Poné un nombre para el grupo.»
- Nombre repetido: «Ya hay un grupo con ese nombre.»
- Demasiados grupos: «Podés tener hasta 10 grupos.»
- Guardado: «Guardamos el menú.»
- Restaurado: «Volvió el menú original.»
- Nombre de más de 30 caracteres: «El nombre del grupo puede tener hasta 30 caracteres.»
- Si el servidor devuelve un error de validación, el editor muestra su mensaje:
  - Id inválido o reservado (`mostrador`): «El identificador del grupo no es válido.»
  - Id repetido: «Ya hay un grupo con ese identificador.»
  - Color inválido: «Elegí un color válido para el grupo.»
  - Clave desconocida: «Hay una opción de menú que no existe.»
  - Clave repetida, también si está en un grupo y en «Ocultas»: «Ya pusiste esa opción en el menú.»
  - Opción de Dueño en un menú de Vendedor: «El menú de un Vendedor no puede incluir opciones de solo Dueño.»
  - Intento de ocultar el editor: «No se puede ocultar «Personalizar menú».»
  - Disposición incompleta: «La disposición del menú no está completa.»

## Fuera de alcance

- Enlaces propios a pantallas externas o crear pantallas nuevas: solo se reordenan las que el sistema ya tiene.
- Que un Vendedor personalice su propio menú.
- Personalizar «Mostrador», la barra superior o el contenido de las pantallas.
- Armar el menú desde el celular.

## Modelo de datos y API

Ver [Modelo de datos → Disposición del menú (CU-17)](11-modelo-de-datos.md#disposición-del-menú-cu-17).
La ferretería sale del JWT, no del cuerpo ni de la ruta.

| Ruta | Quién | Operaciones |
| --- | --- | --- |
| `/api/menu` | Dueño y Vendedor | `GET`: devuelve el menú propio resuelto (ajuste, base del rol, original). El super admin recibe `403`. |
| `/api/menu/disposiciones/roles/{rol}` | Dueño | `GET`: devuelve la base del rol o el original. `PUT`: crea o reemplaza la base. `DELETE`: borra la base. Solo admite `DUENO` o `VENDEDOR`; otro rol da `404`. |
| `/api/menu/disposiciones/usuarios/{usuarioId}` | Dueño | `GET`: devuelve el menú resuelto para el usuario. `PUT`: crea o reemplaza su ajuste. `DELETE`: borra solo su ajuste. Un usuario inactivo conserva su ajuste; un usuario ajeno o inexistente da el mismo `404`. |

El `PUT` recibe la disposición sin envoltorio:

```json
{"grupos":[{"id":"ventas","rotulo":"Ventas","tono":"ventas","claves":["presupuestos","caja"]}],"ocultas":["remitos"]}
```

Los `GET` y `PUT` devuelven `200` con `{"origen":"USUARIO"|"ROL"|"ORIGINAL","disposicion":…}`. Para
`ORIGINAL` la disposición es `null`: el menú original vive en el frontend. Una disposición guardada ilegible también
devuelve `ORIGINAL`. `DELETE` devuelve `204` incluso si no había nada que borrar. Una disposición inválida da `400`;
para un Vendedor se rechazan las claves de solo Dueño tanto en los grupos como en las ocultas.
