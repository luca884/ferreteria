# CU-17 · Personalizar el menú

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · caso de uso agregado el 2026-11-20 a pedido de Luca. Feature doc: [`odd/tasks/personalizar-menu.md`](../../odd/tasks/personalizar-menu.md).

**CU-17 Personalizar el menú** (Dueño)

El Dueño arma el menú lateral de su ferretería: elige qué grupos hay (Ventas, Configuración, los que él cree), qué
opciones lleva cada uno y en qué orden. Lo hace para su propio menú y para el de cada usuario.

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
usuario y **«Más»**, que abre el cajón con el menú completo. Tocar un grupo de la barra abre una pantalla con las
opciones de ese grupo, como hoy hace «Ventas» (cada grupo tiene su entrada, no solo Ventas). Con el menú original la
barra no cambia: Mostrador, Ventas, Artículos y Más.

El editor se usa **desde la computadora**: en una pantalla chica (≤ 1023.98 px) muestra «Personalizá el menú desde la
computadora.» en lugar del editor.

## El editor

Pantalla «Personalizar menú» dentro de Configuración, solo Dueño.

- Un selector «Menú de» elige a quién se edita: los Vendedores (la base del rol), el Dueño (la base del rol) o un
  usuario puntual (su ajuste).
- Las opciones y los grupos se **arrastran y se sueltan**.
- «Guardar» aplica los cambios y «Restaurar» vuelve al nivel de abajo. Hasta guardar no cambia nada para nadie.
- Los cambios se ven en el menú del usuario editado la próxima vez que abre la app o recarga.

### Límites (propuestos, a confirmar)

- Hasta 10 grupos por disposición; el rótulo de un grupo va de 1 a 30 caracteres y no puede repetirse dentro de la
  misma disposición.

### Mensajes

- Nombre vacío: «Poné un nombre para el grupo.»
- Nombre repetido: «Ya hay un grupo con ese nombre.»
- Demasiados grupos: «Podés tener hasta 10 grupos.»
- Guardado: «Guardamos el menú.»
- Restaurado: «Volvió el menú original.»

## Fuera de alcance

- Enlaces propios a pantallas externas o crear pantallas nuevas: solo se reordenan las que el sistema ya tiene.
- Que un Vendedor personalice su propio menú.
- Personalizar «Mostrador», la barra superior o el contenido de las pantallas.
- Armar el menú desde el celular.

## Modelo de datos y API

Ver «Modelo de datos → Disposición del menú (CU-17)». Los endpoints, con su aislamiento por ferretería, salen del
feature doc y se documentan acá al cerrar la tarea de backend.
