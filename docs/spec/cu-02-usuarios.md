# CU-02 · Gestionar usuarios

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · caso de uso extracto de `spec.md:61-65` en la partición del 2026-11-10.

<!-- spec payload: spec.md:61-65 -->
**CU-02 Gestionar usuarios** (Dueño)

1. Crea, edita o desactiva usuarios con rol Dueño o Vendedor.
2. Un Dueño no puede desactivarse ni cambiarse el rol a sí mismo, y la ferretería conserva siempre al menos un Dueño activo.
3. Una cuenta marcada como demo no permite cambiar el email ni cambiar o resetear la contraseña. Sin cambiar el email, se puede editar el resto con las reglas habituales; la cuenta demo no ve «Cambiar mi contraseña».
<!-- fin payload: spec.md:61-65 -->

**Agregado móvil: ficha de usuario** (≤639.98 px, solo Dueño).
«Nuevo» flota sobre la barra de destinos. Alta y edición abren a pantalla completa,
con «Volver» arriba y «Agregar» o «Guardar cambios» fijos al pie.
El rol se elige con un control segmentado; en escritorio se conserva el selector.
No cambian las reglas de negocio, los permisos de cada rol ni los mensajes.

