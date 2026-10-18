Índice

1. [Resumen](#resumen)
2. [Cómo se probó](#metodo)
3. [Tests automáticos](#tests)
4. [Lo que anda bien](#bien)
5. [Hallazgos](#hallazgos)
6. [Tabla de estado](#tabla)
7. [Documentación](#docs)
8. [Mostrador / GDS](#gds)
9. [Plan sugerido](#plan)
10. [Anexo: reproducir](#anexo)

INFORME DE AUDITORÍA · 17/10/2026

# Ferretería SaaS: pruebas, bugs e inconsistencias

Revisión del repo `luca884/ferreteria` en dos rondas, cruzada con la investigación de GDS Ferretería Plus 7.53 (repo `mostrador-gestion`) y con el vault de Obsidian (`Projects/Ferreteria`).

Ronda 1: **main a3771e0** (hasta CU-06) Ronda 2: **main 45a5735** (+104 commits) Cambios en repos: **ninguno**, nada pusheado

## Resumen

El proyecto está bien construido. Los tests son muchos y serios, el aislamiento entre ferreterías aguantó todos los intentos de romperlo y la venta resiste concurrencia real. Los problemas que quedan no están en el núcleo: hay tres cosas que bloquean un deploy seguro, un bug operativo nuevo en el ajuste de stock y una lista de casos borde con mensajes engañosos. La documentación (spec, CLAUDE.md, README y vault) quedó bastante atrás del código.

Tests backend (ronda 2)2.620 / 2.6211 falla, depende del orden de ejecución

Tests frontend (ronda 2)1.565 / 1.56564 archivos, build OK

Hallazgos abiertos243 bloquean el deploy, 1 alto

Corregidos entre rondas2trabajo sin pushear y dígito del CUIT

### Lo más importante, en orden

1. **No se puede deployar tal cual:** el build de producción del frontend apunta a `http://localhost:8080` (H02).
2. **Un usuario dado de baja o una ferretería suspendida siguen operando** hasta 12 horas (H03).
3. **Si falta una variable en Railway, producción arranca con los datos demo** y la contraseña `demo1234` (H04).
4. **No se puede cargar mercadería a un artículo con stock negativo**, y la venta deja el stock en negativo todo el tiempo (H05).
5. **El vendedor ve el costo, el margen y el desglose de precios de todo el catálogo**, y la decisión de CU-06 dice lo contrario (H07).

## Cómo se probó

#### Entorno

- Contenedor Linux en la nube, Docker con MySQL 8.4 (`docker compose`, puerto 3307).
- Java 21 (OpenJDK) y Maven wrapper del repo.
- Node 24.15.0. Con Node 22.22.0 Angular no arranca (ver H20).
- Chromium headless con Playwright para las pruebas de pantalla.
- En la ronda 2 la base se recreó de cero (`down -v`) para tener solo los datos demo.

#### Qué se hizo

- Se corrió la suite completa de backend (Testcontainers) y de frontend, más el build.
- Se levantó la API con el perfil `dev` y se atacó con curl. Hubo datos inválidos, desbordes, roles sin permiso, recursos de otra ferretería y concurrencia de 30 ventas en paralelo.
- Se subieron listas tramposas (CSV y Excel): números en formato argentino y en formato inglés, códigos repetidos, fórmulas, archivos de 300.000 filas y archivos inflados.
- Se usó la app en el navegador como vendedor: lector simulado, ticket y pantallas.
- Hubo lectura y revisión de código, más tres agentes de apoyo: uno revisó ventas, stock y cotización, otro leyó el vault y el tercero la investigación de GDS.

Cada hallazgo indica cómo se verificó. **Probado** quiere decir que se reprodujo contra la API o en el navegador. **Revisión de código** quiere decir que se vio en el código y no se ejecutó.

## Tests automáticos

| Suite | Ronda 1 (a3771e0) | Ronda 2 (45a5735) | Observaciones |
| --- | --- | --- | --- |
| Backend (`./mvnw test`) | 1.056 ok · 87 clases | 2.620 ok · 1 falla · 215 clases | Incluye ArchUnit, aislamiento por tenant con MySQL real y tests de rendimiento. |
| Frontend (`ng test`, Vitest) | 436 ok · 24 archivos | 1.565 ok · 64 archivos | Sin fallos. |
| Build (`ng build`) | OK | OK | El bundle de producción contiene `localhost:8080` (H02). |

### El test que falla en la ronda 2

`MigracionV5Test.losExistentesQuedanEnPesos`: esperaba `40345` y recibió `40344` (`MigracionV5Test.java:64`). Corrido solo (`-Dtest=MigracionV5Test`) pasa. Cuenta los artículos de una base que comparten todos los tests de integración y que no se revierte, y algún test anterior deja un artículo en USD que no tiene el prefijo `V5-`. El resultado depende del orden en que JUnit ejecute las clases. Ver H15.

## Lo que anda bien

Esto se probó y conviene no romperlo:

#### Aislamiento y seguridad

- Un artículo, rubro o proveedor de otra ferretería devuelve 404 en todas las rutas probadas: lectura, alta, venta, ajuste de stock, filtros de masivo e importación.
- El rol se controla antes de leer el cuerpo: el vendedor recibe 403 en importación, actualización masiva y ajuste de stock.
- El super admin recibe 403 con el mensaje "requiere una ferretería" en los datos de tenant.
- Los enums no aceptan números (`fail-on-numbers-for-enums`).
- El CUIT de proveedor ahora valida el dígito verificador (corregido en la ronda 2).

#### Ventas (CU-07a)

- 30 ventas simultáneas del mismo artículo descontaron exactamente 30 (de 200 a 170), con 30 movimientos.
- La numeración quedó 1..35 sin huecos ni números repetidos, y la de la otra ferretería arrancó en 1.
- Diez envíos simultáneos con la misma clave dieron un solo comprobante: un 201 y nueve 200.
- La misma clave con otro contenido responde 422. Un precio cambiado responde 409 con el precio actual. Un artículo dado de baja responde 422.
- Una cantidad decimal en un artículo por unidad se rechaza. En el ejemplo de metros, 2,345 × $790 da $1.852,55 exacto.
- En cada renglón el neto más el IVA da el total. Todo es `BigDecimal` con HALF_UP.
- La venta con stock insuficiente se permite y avisa en `sinStock`, como pide la spec.

#### Catálogo y precios

- Los precios demo coinciden con la fórmula de la spec (costo × margen × IVA, redondeado al múltiplo).
- La herencia del margen funciona (artículo, rubro, ferretería) y la cascada del rubro deja historial.
- El bloqueo optimista anda: un PUT sin versión da 400 y uno con versión vieja da 409.
- Un artículo en USD con costo de US$100 queda en $241.460, con el dólar oficial venta de $1.535, margen 30 % e IVA 21 %.
- En la búsqueda del mostrador, `%`, `_` y `\` se escapan y no actúan como comodines. Los acentos y las mayúsculas no importan.

#### Importación, masivo y pantallas

- La importación detecta códigos repetidos, aunque cambien las mayúsculas. También costos negativos, texto en lugar de número, códigos de más de 40 caracteres y costo cero.
- Avisa cuando el costo varía mucho, por ejemplo "sube 525 %".
- La masiva usa una huella SHA-256: con una huella falsa responde 409 y sin huella, 400.
- En el mostrador, escanear dos veces suma la cantidad del renglón.
- No hubo errores en la consola del navegador. La cotización aparece en el menú lateral.
- Con dolarapi y bluelytics caídas, la app sigue con el último valor guardado (revisión de código).

## Hallazgos

Están ordenados por severidad. El estado indica qué pasó entre la ronda 1 y la ronda 2. Los números de línea corresponden a `45a5735`, salvo que se aclare.

H02Bloquea el deploySigue igualProbado

#### El build de producción del frontend apunta a localhost

Qué pasa

`frontend/src/environments/environment.ts` tiene `apiUrl: 'http://localhost:8080/api'`. Su propio comentario dice que en producción se reemplaza "ver angular.json > fileReplacements", pero en `angular.json` no hay ningún `fileReplacements` y no existe `environment.prod.ts`.

Evidencia

Después de `npx ng build`, `grep -rl "localhost:8080" dist/frontend/browser/` encuentra `main-*.js` en las dos rondas.

Impacto

Desplegado en Vercel o Cloudflare, el navegador del cliente le pide la API a su propia máquina y la app no funciona.

Arreglo

Crear `environment.prod.ts` con la URL de Railway y declarar `fileReplacements` en la configuración `production`. Otra opción es leer la URL de un `config.json` en tiempo de ejecución, así el mismo build sirve para staging y producción.

H03Bloquea el deploySigue igualProbado

#### Suspender una ferretería o desactivar un usuario no corta el acceso

Qué pasa

El login controla `Usuario.activo` y `Ferreteria.isActiva()` (`AuthService.login`), pero `JwtAuthenticationFilter` confía en el token y no vuelve a consultar la base. El token dura 12 horas (`JWT_EXPIRATION_MS` = 43.200.000).

Evidencia

Con un token tomado antes, se hizo `UPDATE ferreteria SET estado='SUSPENDIDA'` y `UPDATE usuario SET activo=false` para el vendedor. Después, `GET /api/articulos` del dueño respondió 200, el dueño pudo crear un rubro (201) y el vendedor desactivado buscó en el mostrador (200). Se reprodujo en las dos rondas.

Impacto

Un empleado despedido sigue vendiendo durante el turno. Una ferretería suspendida por falta de pago sigue usando el sistema. Un cambio de rol tampoco se aplica hasta que vence el token.

Arreglo

En el filtro, validar contra la base (o una caché de unos 60 s) que el usuario esté activo, que la ferretería esté activa y que el rol coincida. La alternativa es un token de vida corta con refresh y una columna `token_version` en el usuario.

H04Bloquea el deploySigue igualRevisión de código

#### Producción arranca con perfil dev si falta una variable

Qué pasa

`application.yml` tiene `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:dev}`. El perfil `dev` agrega `db/dev-data` a Flyway (usuarios demo con `demo1234`) y además usa el secreto JWT por defecto, `dev-secret-ferreteria-cambiar-en-produccion-…`, que está en el repo.

Impacto

Si en Railway te olvidás `SPRING_PROFILES_ACTIVE=prod`, la base de producción recibe los usuarios demo (incluido `admin@demo.local` como SUPER_ADMIN) y cualquiera que lea el repo puede firmar tokens válidos.

Arreglo

Que el default sea `prod` y que el desarrollo use `dev` de forma explícita (en el README o en el wrapper). También quitar el default de `app.jwt.secret` en `application.yml` y dejarlo solo en `application-dev.yml`.

H05AltoNuevo en ronda 2Probado

#### Con stock negativo no se puede cargar un ingreso de mercadería

Qué pasa

`stock/model/AjusteStock.java:77-80` (`stockResultante`) rechaza cualquier resultado menor que 0, aunque la cantidad sea positiva. El mensaje se arma con `cantidad.negate()` y sale al revés.

Evidencia

Se vendieron 10 taladros con stock 6 (permitido, quedó en −4). Después, `POST /api/articulos/3/ajustes-stock {"modo":"CANTIDAD","cantidad":2,"motivo":"INGRESO_MERCADERIA"}` respondió 400 con *"No podés sacar -2: hay -4 unidades."*

Impacto

Es diario. La spec permite vender sin stock, así que muchos artículos van a estar en negativo. Si llega un pedido parcial, el sistema solo acepta ingresos que dejen el stock en 0 o más. El modo STOCK_REAL tampoco sirve, porque no acepta un stock real negativo. Los tests `noQuedaNegativo` no cubren un stock inicial negativo.

Arreglo

Validar el "no podés sacar" solo cuando `cantidad < 0`. Agregar tests con stock inicial negativo en el modelo, el servicio y la integración.

H06MedioSigue igualProbado

#### Mostrador: un artículo dado de baja tapa la búsqueda por palabra

Qué pasa

`BusquedaMostradorService.buscar` pregunta por un dado de baja con ese código exacto (`hayDadoDeBajaConCodigoScoped`) antes de buscar coincidencias parciales. Si lo encuentra, devuelve `DADO_DE_BAJA` sin ítems.

Evidencia

Se creó un artículo inactivo con código interno `TORNILLO`. Al buscar "tornillo", la API respondió `{"tipo":"DADO_DE_BAJA","items":[]}` y la pantalla mostró solo "Artículo dado de baja", sin ningún tornillo activo. Se reprodujo en las dos rondas.

Impacto

Pasa con códigos cortos o comunes (`10`, `CINTA`, `CLAVO`) que alguna vez se dieron de baja. El vendedor cree que no hay stock de algo que sí está.

Arreglo

Mostrar el aviso de dado de baja solo si la búsqueda parcial no trae nada, o mostrar la lista con un aviso arriba. Otra opción es aplicar la regla solo cuando la consulta parece un código (sin espacios, con dígitos).

H07MedioSigue y se amplióProbado

#### El vendedor ve el costo, el margen y el desglose de todo el catálogo

Qué pasa

`catalogo/dto/ArticuloResponse.java:10` dice "Un unico DTO para Dueno y Vendedor: ambos ven costo y margen". `GET /api/articulos`, `/{id}` y el nuevo `/{id}/desglose` no tienen `@PreAuthorize`, y la ruta `/articulos` del frontend no tiene guard de rol.

Evidencia

Logueado como `vendedor@tornillo.demo`, la pantalla Artículos muestra las columnas COSTO y MARGEN (también costos en US$). `GET /api/articulos/1/desglose` devuelve el precio de lista, las bonificaciones y el IVA.

Contradicción

El vault dice dos cosas distintas: `cambios/cu-03-articulos.md` dice "El Vendedor lee todo, incluidos costo y margen" y `cambios/cu-06-buscar-articulo.md` dice "El Vendedor no ve costo ni margen". `estado-actual.md` lo deja como pregunta abierta.

Arreglo

Decidirlo de una vez. Si el vendedor no ve el costo: un DTO sin costo para su rol (o `@JsonView`), `/desglose` solo para el dueño y ocultar las columnas en el front.

H08MedioSigue y se amplióProbado / revisión de código

#### Un valor que desborda la base responde 409 "El dato ya existe"

Qué pasa

El alta de artículos no verifica que el precio calculado entre en `DECIMAL(15,2)`. La actualización masiva sí lo verifica (`PlanificadorMasivo.MAXIMO_DIGITOS_ENTEROS`). MySQL rechaza el valor (*Out of range value for column 'precio_final'*) y `GlobalExceptionHandler.integridad` convierte cualquier `DataIntegrityViolationException` en 409 con el texto "El dato ya existe o entra en conflicto con otro".

Evidencia

Probado: un artículo con costo 999.999.999.999 y margen 1000 % responde 409, y uno con costo US$ 99.999.999.999 también. Revisión de código: un ajuste de stock que supera `DECIMAL(15,3)` también da 409. En una venta es peor: `VentaService.java:142-144` lo toma como si otra venta con la misma clave hubiera ganado la carrera, la busca, no la encuentra y relanza la excepción.

Impacto

Solo pasa con valores absurdos (por ejemplo, un error de tipeo con varios ceros de más), pero el mensaje manda al usuario a buscar un duplicado que no existe.

Arreglo

Validar el tope en el dominio (como ya hace la masiva) y responder 400 con un mensaje claro. En el handler, distinguir la violación de UNIQUE (1062) del valor fuera de rango (1264).

H09MedioNuevo en ronda 2Revisión de código

#### Una cotización absurda se aplica a todos sin control

Qué pasa

`cotizacion/…/cliente/ClienteJson.java:64-70` acepta cualquier valor mayor que 0. No hay rango plausible ni comparación con el valor anterior. `RecalculoDiarioJob` recorre a las 08:00 todas las ferreterías activas y aplica el valor vigente.

Escenario

Si dolarapi devuelve `venta: 14.5` en lugar de 1450, todos los artículos en USD de todas las ferreterías quedan 100 veces más baratos y el historial lo registra como "Automático". Si las dos fuentes están caídas varios días, el recálculo usa el último valor sin avisar. Solo `GET /api/cotizacion` informa `desactualizada`. Si una fuente trae un valor que no entra en `DECIMAL(15,4)`, el error corta el ciclo antes de probar el respaldo (`ActualizadorDeCotizaciones.java:93`).

Lo que está bien

El cron usa la zona horaria de Buenos Aires, el reclamo del recálculo es idempotente y el `TenantContext` se limpia en un `finally` por cada ferretería.

Arreglo

Rechazar valores que se alejen más de un ±X % del anterior (por ejemplo 20 %) o que estén fuera de un rango fijo, y dejarlos para que el dueño los confirme. No recalcular con una cotización de más de N horas sin avisar en la pantalla.

H10MedioNuevo en ronda 2Revisión de código

#### La hora de la venta no tiene zona horaria

Qué pasa

`ventas/model/Comprobante.java:59` usa `LocalDateTime.now()`, que toma la zona de la JVM, y el código no la fija en ningún lado. `VentaResponse.fecha` sale sin offset, mientras que el resto del sistema usa `Instant` y la zona de Buenos Aires.

Impacto

En Railway la JVM suele correr en UTC: el ticket y cualquier reporte del día van a mostrar 3 horas de más, y una venta de las 22:00 cae en el día siguiente.

Arreglo

Guardar un `Instant` (o `LocalDateTime.now(ZoneId.of("America/Argentina/Buenos_Aires"))` de forma explícita) y devolverlo con offset.

H11Medio · decisión de negocioSigue igualProbado

#### El redondeo hacia arriba a $10 dispara el precio de los artículos baratos

Qué pasa

`PrecioCalculator.precioFinal` redondea con `CEILING` al múltiplo de la ferretería, que por defecto es 10.00 (`V2`).

Evidencia

Una arandela de costo $2, con margen 30 % e IVA 21 %, da un precio calculado de $3,15 y queda en **$10,00**, un 217 % más. Un artículo de costo US$0,01 queda en $30.

Contexto GDS

GDS usa 0,50 por defecto y lo aplica solo en las herramientas masivas. `docs/relevamiento-gds.md` (líneas 203 y 341) dice que el redondeo "coincide con el de ferreteria", y no es así.

Opciones

Un múltiplo por rubro o por rango de precio (por ejemplo $1 por debajo de $100 y $10 por encima), o redondeo al más cercano en lugar de hacia arriba. Hay que validarlo con una ferretería real (la bulonería suelta es justo el caso).

H12MedioSigue y se amplióProbado

#### Se puede crear y vender un artículo a $0

Evidencia

`POST /api/articulos` con `"costo":0` responde 201 con `precioFinal 0.00`. Después, `POST /api/ventas` de ese artículo a $0 responde 201 con `total 0.00` y descuenta stock.

Contraste

La importación y la masiva sí tratan el costo 0 como error. La investigación de GDS anotó "no vender con precio 0" (GDS-09) como algo que conviene copiar.

Arreglo

Rechazar el costo 0 en el alta manual, o al menos pedir confirmación, y bloquear o pedir confirmación del dueño para una venta con renglones en $0.

H13MedioSigue igualProbado

#### "0.250" en una lista se lee como 250

Qué pasa

`ParserImporte` trata cualquier `d{1,3}(.ddd)+` como separador de miles (`MILES_CON_PUNTO`), así que `0.250` pasa a 250.

Evidencia

En un CSV con `DBS-200;Bulon;0.250`, la vista previa calculó un costo de 250,00. Lo que lo mitiga es que sale la advertencia "El costo sube un 525 %".

Arreglo

Un número que empieza con `0.` no puede tener separador de miles: tratarlo como decimal. Mejor todavía, permitir elegir el formato numérico en el mapeo del proveedor (punto o coma decimal).

H14MedioSigue igualProbado

#### Un Excel válido con muchas filas dice "archivo dañado"

Evidencia

Un `.xlsx` válido de 300.000 filas (4,8 MB) responde *"No se pudo leer el archivo. Verificá que no esté dañado."* en `/muestra`. Uno de 25.000 filas sí da el mensaje correcto ("más de 20.000 filas"). Un archivo inflado con celdas de texto repetido (1,1 MB) también dice "dañado".

Causa probable

Se activa algún tope de protección (`MAXIMO_ENTRADA_ZIP_BYTES`, `MAXIMO_ARREGLO_POI_BYTES` o la relación de compresión de POI) y el error se traduce al mensaje genérico.

Arreglo

Mapear esas excepciones a "La lista es demasiado grande" y dejar "dañado" solo para errores de formato.

H15MedioNuevo en ronda 2Probado

#### Hay un test que falla según el orden de ejecución

Qué pasa

`MigracionV5Test.losExistentesQuedanEnPesos` cuenta los artículos de toda la base compartida, excluyendo solo el prefijo `V5-`. Si otra clase dejó antes un artículo en USD, falla (40344 de 40345).

Impacto

El CI puede quedar en rojo de forma aleatoria y enseña a ignorar fallos.

Arreglo

Contar solo los artículos de los datos demo (por ids o por `ferreteria_id` de la demo), o crear el escenario dentro del propio test.

H16BajoSigue igualProbado

#### Se puede usar un proveedor dado de baja

Evidencia

Con el proveedor 2 dado de baja (`DELETE /api/proveedores/2`, 204), `POST /api/articulos` con `proveedorId: 2` responde 201 y la vista previa de importación para ese proveedor responde 200.

Arreglo

Rechazar el alta o la importación sobre un proveedor inactivo con un mensaje que ofrezca reactivarlo, o al menos avisar.

H17BajoVisto en ronda 1Probado

#### La cascada del margen del rubro no recalcula los artículos inactivos

Evidencia

Al bajar el margen de Herramientas de 40 % a 10 %, el artículo inactivo `OLD-001` siguió en $5.090 (con el margen nuevo sería unos $3.630). La consulta está en `ArticuloRepository.heredanMargenDeRubroScoped` (`…ActivoTrue`).

Impacto

Al reactivarlo con un PUT se recalcula, pero la masiva con "incluir inactivos" y el listado de dados de baja muestran un precio viejo. Hay que decidir si es lo que se quiere y documentarlo.

H18BajoRevisión de código

#### Hay claves foráneas que no incluyen la ferretería

Qué pasa

`V4`: `item_comprobante.articulo_id`, `movimiento_stock.articulo_id` y `comprobante_id`, y `pago.comprobante_id` apuntan solo a `(id)`. Lo mismo pasa en `V2` con `articulo.rubro_id` y `proveedor_id`, y en `V6` con `articulo.ultima_importacion_id`.

Impacto

Hoy no se puede explotar, porque todos los ids pasan por búsquedas con scope. Pero la base no impide un vínculo entre ferreterías si algún servicio futuro se escribe mal.

Arreglo

Usar claves foráneas compuestas `(ferreteria_id, x_id)`, con un UNIQUE `(ferreteria_id, id)` en la tabla padre.

H19BajoRevisión de código

#### Ventana de carrera chica al confirmar una venta

Qué pasa

`RegistroVenta.validar` (líneas 222-236) lee los artículos sin lock, antes del lock del numerador. Si el dueño da de baja un artículo o cambia su precio justo en ese intervalo, la venta se registra con lo que vio el cajero.

Impacto

Es aceptable en la práctica, pero la regla "precio cambiado da 409" no se cumple de forma estricta en esa ventana. Conviene dejarlo documentado.

H20BajoSigue igualProbado

#### El README pide una versión de Node con la que Angular no arranca

Evidencia

El README dice "Node 22.22+ (o 24)". Con Node 22.22.0, `ng test` sale con código 3: *"The Angular CLI requires a minimum Node.js version of v22.22.3 or v24.15.0 or v26.0.0"*.

Arreglo

Poner "22.22.3+ o 24.15+" en el README y agregar `"engines"` en `package.json` y un `.nvmrc`.

H21BajoVisto en ronda 1Probado

#### No se puede configurar el margen por defecto ni el redondeo, y el super admin no tiene pantallas

Qué pasa

En la ronda 1 no había endpoint ni pantalla para `margen_defecto_porcentaje` y `redondeo_multiplo`: solo los defaults de V2, aunque la spec dice que son configurables. El super admin entra a "rubros" (`inicio-por-rol.ts`) y recibe 403 en todo. No hay alta ni suspensión de ferreterías.

Nota

En la ronda 2 no apareció ningún controlador nuevo de configuración de ferretería entre los listados, aunque no se revisó a fondo. Encaja con CU-01, que todavía no está.

H22BajoProbado

#### Detalles chicos de importación y mostrador

Fórmula como código

Una fila con código `=CMD()` se ofrece como artículo nuevo con ese código interno. Si algún día se exporta a Excel o CSV, es una inyección de fórmula. Convendría rechazar los códigos que empiezan con `= + - @`.

Códigos sin normalizar

En el mostrador, `TOR001` no encuentra `TOR-001`. La importación sí normaliza (`NormalizadorCodigo`), pero solo mayúsculas y acentos. Podría ignorar guiones y espacios en la búsqueda exacta.

Fórmulas sin valor guardado

Un Excel generado por un programa que no guarda el resultado de las fórmulas (por ejemplo `=10*3`) se lee como 0 y el error dice "costo calculado es 0". Con archivos guardados en Excel real no pasa.

"No están"

En la ronda 1, los artículos del proveedor sin código de proveedor aparecían siempre en "No están en la lista". No se volvió a verificar en la ronda 2.

H23BajoVisto en ronda 1Revisión de código

#### Importación y masiva verifican los cambios de forma distinta

Qué pasa

En la ronda 1, aplicar una importación (CU-04) solo comparaba los contadores con los de la vista previa, mientras que la masiva (CU-05) compara una huella SHA-256 del contenido. Otro archivo con los mismos contadores pero precios distintos se aplicaba igual. La importación se reescribió en la ronda 2 y este punto no se volvió a verificar.

H24BajoProbado

#### Ruido en los logs

Qué pasa

Al arrancar, `UserDetailsServiceAutoConfiguration` genera y loguea una contraseña (Spring arma un usuario en memoria que no se usa). Se puede excluir esa autoconfiguración. Además, cada carrera de clave de venta, aunque está bien manejada, deja un `ERROR` de Hibernate (*Duplicate entry … uk_comprobante_clave*) que va a ensuciar las alertas.

H01CorregidoProbado

#### Había semanas de trabajo que estaban solo en la máquina local

Ronda 1

En el remoto solo estaba `main` hasta CU-06. CU-07a, `ui-pulido` (CU-09) y `listas-y-moneda`, con las migraciones V4 a V6, no existían en GitHub, aunque el vault las daba por hechas.

Ronda 2

Están en `main` (104 commits, migraciones V4 a V8, módulos `ventas`, `stock` y `cotizacion`).

H25Corregido en parteProbado

#### Validación del CUIT de proveedor

Ronda 1

Se aceptaba cualquier CUIT con formato válido, incluido uno repetido de otro proveedor.

Ronda 2

Ahora valida el dígito verificador ("el último no coincide"). No se volvió a probar si acepta un CUIT válido repetido entre proveedores de la misma ferretería.

## Tabla de estado

| ID | Hallazgo | Severidad | Ronda 1 | Ronda 2 |
| --- | --- | --- | --- | --- |
| H01 | Trabajo sin pushear | Crítico | Encontrado | Corregido |
| H02 | Build de producción con localhost | Bloquea | Encontrado | Sigue |
| H03 | La suspensión y la baja no cortan el JWT | Bloquea | Encontrado | Sigue |
| H04 | Perfil dev por defecto | Bloquea | Encontrado | Sigue |
| H05 | Ingreso rechazado con stock negativo | Alto | No existía | Nuevo |
| H06 | Un dado de baja tapa la búsqueda | Medio | Encontrado | Sigue |
| H07 | El vendedor ve el costo | Medio | Encontrado | Sigue (+desglose) |
| H08 | Desborde con 409 engañoso | Medio | Encontrado | Sigue (+USD, stock y venta) |
| H09 | Cotización absurda sin control | Medio | No existía | Nuevo |
| H10 | Fecha de venta sin zona horaria | Medio | No existía | Nuevo |
| H11 | Redondeo de los artículos baratos | Medio | Encontrado | Sigue |
| H12 | Precio $0 en el alta y la venta | Medio | Encontrado (alta) | Sigue (+venta) |
| H13 | 0.250 se lee como 250 | Medio | Encontrado | Sigue |
| H14 | Excel grande dice "dañado" | Medio | Encontrado | Sigue |
| H15 | Test que depende del orden | Medio | No existía | Nuevo |
| H16 | Proveedor inactivo utilizable | Bajo | Encontrado | Sigue |
| H17 | La cascada ignora los inactivos | Bajo | Encontrado | No re-verificado |
| H18 | Claves foráneas sin ferretería | Bajo | Parcial (V2) | Sigue (+V4, V6) |
| H19 | Ventana de carrera en la venta | Bajo | — | Nuevo |
| H20 | Versión de Node en el README | Bajo | Encontrado | Sigue |
| H21 | Sin configuración de precios ni pantallas de super admin | Bajo | Encontrado | No re-verificado a fondo |
| H22 | Detalles de importación y mostrador | Bajo | Encontrado | Sigue (parcial) |
| H23 | Importación verifica solo contadores | Bajo | Encontrado | No re-verificado |
| H24 | Ruido en los logs | Bajo | Encontrado | Sigue |
| H25 | CUIT de proveedor | Bajo | Encontrado | Corregido en parte |

## Documentación: inconsistencias

En la ronda 2, `CLAUDE.md`, `README.md` y `docs/spec.md` no se tocaron en los 104 commits. Lo único nuevo en `docs/` es `prueba-teclas-f.html`. El vault se leyó en la ronda 1. En esa lectura, parte del trabajo que describía todavía no estaba en GitHub.

### CLAUDE.md y README del repo

- **Estado vencido:** dice "Fase 2 (esqueleto) en curso… Próximo: fase 3", pero `main` ya tiene CU-03 a CU-07a, CU-09 y moneda.
- **Lista de módulos incompleta:** nombra `auth, tenant, ferreteria, catalogo, ventas, stock, facturacion, documentos`. Falta `cotizacion`, que ya existe y tiene reglas de ArchUnit propias.
- **Regla de una rama por caso de uso:** `ui-pulido` juntó CU-09, reactivaciones y rediseño.
- **Versión de Node:** ver H20.

### docs/spec.md

- **Fórmula de CU-03:** CU-03 dice "costo × (1 + margen)", sin IVA, mientras que las reglas de negocio de la misma spec y el código suman el IVA.
- **Lo que el código ya tiene y la spec no:** la spec no menciona bonificaciones en cascada, lista con o sin IVA, `precio_lista_proveedor`, ajustes por artículo, moneda USD con cotización, ni los cortes de CU-07 (descuentos, pago mixto, recargo). Todo está en el código o decidido en el vault.
- **Modelo de Comprobante:** la spec pide FA/FB/FC/NC/presupuesto con estados pendiente/autorizado/rechazado y caja. El código usa `VENTA` con estado `SIN_FACTURAR` y sin caja.
- **Stock negativo:** la spec dice "se permite stock negativo con advertencia". El ajuste de CU-09 no lo deja (y eso es lo que produce H05).
- **PDF:** la spec prevé importar PDF después de Excel, y el vault (importación flexible, 2026-10-16) dice "nada de PDF".
- **Índices y nombres:** la spec habla de `tenant_id` y de un índice full-text. El código usa `ferreteria_id` y búsqueda con `LIKE` (decisión de CU-06).
- **Tests del frontend:** la spec dice Karma/Jasmine y el proyecto usa Vitest.
- **Plan por fases:** la fase 3 cerraba con "el Excel real de la fase 0 se importa sin errores". Las fases 0 (validación con ferreterías) y 1 (prueba de ARCA en homologación) no tienen evidencia de haberse hecho, y CU-09 se adelantó de la fase 6.

### Vault de Obsidian (Projects/Ferreteria)

- **`estado-actual.md` quedó atrás de `cambios/listas-y-moneda.md`:** uno dice "sin merge ni verify" y el otro "corte 1 cerrado, verify pasa".
- **La sección "Próximos pasos" está vencida:** sigue pidiendo "CU-07a, sdd-apply tanda 3", "definir de dónde sale el dólar" y "no hay pantalla para reactivar", y las tres cosas ya se hicieron.
- **El README del vault sigue en "fase 2 arrancada" (11/10).** Dice que `_canonical/` "se crea cuando arranque el código", pero no existe, y el CLAUDE.md del vault pide leerlo primero.
- **`wiki/moneda-y-cotizacion.md` se contradice:** abre con "todavía no está programado" y más abajo cita decisiones de la implementación.
- **`wiki/importacion-de-listas.md` se contradice:** el tope de 1.000 filas aparece como pendiente y también como resuelto.
- **`wiki/modelo-de-datos.md` no se actualiza desde el 11/10.** Le faltan el margen por defecto, el redondeo, las bonificaciones, `lista_incluye_iva`, `precio_lista_proveedor`, los contadores de importación, la moneda y las tablas de cotización, comprobante, pago y movimiento.
- **El vendedor y el costo:** `cu-03` y `cu-06` dicen cosas opuestas (ver H07).
- **Origen del historial:** `costo-y-precio.md` menciona un origen "cascada" que no existe, porque la cascada se registra como MASIVO.
- **Herencia del margen:** `costo-y-precio.md §4` habla de un rubro sin margen, pero el margen del rubro nunca es nulo.
- **`wiki/relevamiento-gds.md` está vencido:** pide revisar las bonificaciones (ya implementadas), describe la versión 7.51 cuando el repo tiene la 7.53 y deja el USD para después del MVP (ya está hecho).
- **Las 10 preguntas de `wiki/preguntas-validacion.md` siguen sin responder** y no hay rastro de visitas a ferreterías ni de una ferretería piloto.

## Mostrador / GDS: qué aporta y qué falta decidir

Del repo `mostrador-gestion` se usó solo la documentación, como pediste. La investigación de GDS Ferretería Plus 7.53 está en `documentacion/investigaciones/gds-ferreteria-plus-7/`: un README con bitácora de 1.874 líneas, `catalogo-funcional.md`, `informe-final.md` y capturas de menú. Es muy completa y es la mejor fuente que tenés sobre cómo trabaja una ferretería real.

### Cómo trabaja GDS en el día a día

#### Precios y costo

- Costo = `lista × (1−d1) × (1−d2) × (1−d3)`. El costo directo está bloqueado: "se calcula desde la solapa COSTO CALCULADO".
- Lista 1 = costo + % ganancia (30 % por defecto). Tiene cuatro listas: Efectivo, Tarjeta, Lista 3 y Lista 4.
- Con "Las listas son Precio neto" el IVA se suma al facturar; si no, el precio ya lo incluye.
- Pierde centavos al redondear el neto unitario: 4 × $500 dan un total de $1.999,98.
- La actualización masiva filtra por marca, rubro, proveedor y rango de códigos, y redondea a 0,50 por defecto.

#### Venta y caja

- Teclas de la venta: F3 cliente, F5 artículo, `*` cantidad, `/` precio, `+` descuento por renglón, `-` descuento global, Ctrl+R quitar.
- Pago mixto siempre, y con Enter pasa el resto al medio siguiente.
- Descuento con tope por usuario (80 % para ADMIN). Límite de venta a consumidor final sin datos: $5.000.
- La caja es una vista del día, sin apertura ni cierre, con ingresos, retiros y gastos por medio de pago.
- La cuenta corriente no imputa cobros a facturas: trabaja sobre los totales de la cuenta.

### Lo que tu spec ya cubre de GDS

Fórmula de precio con margen heredado, redondeo a múltiplo, historial de precios, stock por movimientos, comprobantes A/B/C, tope de consumidor final como parámetro, nota de crédito que repone stock, ajuste de stock con motivo, actualización masiva por proveedor o rubro, búsqueda con foco permanente y totales por medio de pago al cerrar la caja.

### Lo que una ferretería usa y la spec ignora o contradice

| Tema | GDS | Tu spec o código | Qué hacer |
| --- | --- | --- | --- |
| Medios de pago | Pago mixto con el resto automático | CU-07 dice "elige el medio de pago" (uno solo) y el modelo tiene `Pago` 1–N. El código acepta un solo `medioPago`. | Decidirlo antes del corte de pago mixto |
| "Presupuesto" | Cotización con estados que se convierte en venta con "Traer" | Venta sin factura | Cambiar el nombre o redefinirlo: son dos cosas distintas |
| Caja | Retiros, gastos e ingresos varios | CU-10 solo abre, cierra y cuenta | Sin movimientos manuales, la diferencia del arqueo no se puede explicar |
| Costo en el renglón | Ganancia histórica por venta | `ItemComprobante` no guarda el costo | Guardarlo ahora es barato; hacerlo después, no |
| Factura A | Desglose por renglón (pierde centavos) | Precio con IVA redondeado a $10 y sin regla de desglose | Definir el redondeo por renglón y que el total sea la suma de los renglones |
| Descuentos | Por renglón y global, con tope por usuario | Ausentes; el vault decidió "sin tope" | Con roles fijos no hay dónde poner un tope |
| Recargo por tarjeta o cuotas | Porcentaje por medio de pago digital | Listas múltiples fuera del MVP | Validar con un contador y con el piloto |
| Ingreso por remito | Compra con varios renglones que suma stock | CU-09 es un ajuste por artículo | Pantalla de ingreso de mercadería con varios renglones |
| Etiquetas | EAN-13, Code39, Code128, A4 de 4 y 5 columnas | No están | Pedido típico de un piloto |
| Precio 0 | Lo acepta (es un defecto) | También lo acepta (H12) | Bloquearlo |
| Marca y rango de códigos | Filtros de la masiva | No están | Puede quedar para después del MVP |

### Contradicciones entre la investigación de GDS y tu `docs/relevamiento-gds.md`

- **Redondeo:** tu relevamiento dice que el de GDS "coincide" con el tuyo. GDS usa 0,50 y solo en las herramientas masivas; vos usás $10 hacia arriba siempre. Además, la línea 314 dice que `PrecioCalculator` redondea "a 2 decimales", cuando redondea al múltiplo.
- **Stock negativo:** tu relevamiento (G90) dice que "la spec no lo dice", pero la spec sí lo permite con advertencia.
- **Unificar renglones:** se omite la condición de GDS, que unifica "solo si el precio no se modificó".
- **La compra y el precio de venta:** se afirma que no se recalcula, pero GDS tiene el interruptor "Modif. Precio desde Compras" tildado, que actualiza la lista 1. Falta aclarar el caso.
- **Citas desactualizadas:** las citas `R:NNN` apuntan al README de GDS del commit `20e20ed` y las líneas se movieron. Por ejemplo, el mensaje de costo bloqueado ahora está en 244-245.
- **La propia bitácora de GDS conserva afirmaciones ya corregidas** sin marcarlas: que el costo no se edita en la compra, que el permiso negado oculta el menú, el total de 24 permisos y "R" en lugar de Ctrl+R.
- **El importador de listas de GDS nunca se vio funcionar.** Tu CU-04 no tiene una referencia de GDS con qué compararse: hay que validarlo con listas reales.

## Plan sugerido

| Prioridad | Qué | Hallazgos | Esfuerzo estimado |
| --- | --- | --- | --- |
| Antes de deployar | Environment de producción del frontend, perfil `prod` por defecto sin secreto en `application.yml`, validar usuario y ferretería activos en cada request | H02, H03, H04 | Medio día a un día |
| Ya | Arreglar el ingreso con stock negativo y agregar sus tests | H05 | 1 a 2 horas |
| Ya | Aislar el test que depende del orden | H15 | 30 minutos |
| Próximo | Zona horaria de la venta y control de plausibilidad de la cotización | H10, H09 | Medio día |
| Próximo | Decidir si el vendedor ve el costo y cerrarlo en la API y el front | H07 | Unas horas, después de decidir |
| Próximo | Mostrador: el dado de baja no tapa la lista. Mensajes de 400 en lugar de 409 por desborde. Mensaje de Excel grande. Parseo de "0.xxx" | H06, H08, H13, H14 | Un día |
| Con la ferretería piloto | Regla de redondeo, precio 0, recargo por tarjeta, pago mixto y significado de "presupuesto" | H11, H12, sección GDS | Decisión de negocio |
| Mantenimiento | Actualizar `CLAUDE.md`, README, `spec.md`, `relevamiento-gds.md`, el `estado-actual` del vault, `modelo-de-datos` y crear `_canonical/` | Sección de documentación | Medio día |
| Cuando haya tiempo | Claves foráneas compuestas, proveedor inactivo, logs, códigos con fórmula, normalizar códigos en el mostrador | H16, H18, H22, H24 | Variable |

## Anexo: cómo reproducir

Base `http://localhost:8080` con el perfil `dev`. Todos los usuarios demo tienen la contraseña `demo1234`.

#### Token

```
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"dueno@tornillo.demo","password":"demo1234"}' | jq -r .token)
```

#### H03: la suspensión no corta

```
docker exec ferreteria-mysql mysql -uroot -proot ferreteria \
  -e "update ferreteria set estado='SUSPENDIDA' where id=1"
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/articulos -H "Authorization: Bearer $TOKEN"   # 200
```

#### H05: ingreso con stock negativo

```
# 1) vender más de lo que hay (HER-001, stock 6)
curl -s localhost:8080/api/ventas -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"clave":"'$(uuidgen)'","renglones":[{"articuloId":3,"cantidad":10,"precioUnitario":13560}],
       "totalMostrado":135600,"medioPago":"CREDITO"}'
# 2) cargar un ingreso parcial: 400 "No podés sacar -2: hay -4 unidades."
curl -s localhost:8080/api/articulos/3/ajustes-stock -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"modo":"CANTIDAD","cantidad":2,"motivo":"INGRESO_MERCADERIA"}'
```

#### H06: un dado de baja tapa la búsqueda

```
curl -s localhost:8080/api/articulos -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"codigoInterno":"TORNILLO","descripcion":"viejo","costo":1,"unidad":"UNIDAD","activo":false}'
curl -s -G localhost:8080/api/articulos/mostrador -H "Authorization: Bearer $TOKEN" --data-urlencode q=tornillo
# {"tipo":"DADO_DE_BAJA","items":[],"hayMas":false}
```

#### H08: desborde con 409

```
curl -s localhost:8080/api/articulos -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"codigoInterno":"BIG","descripcion":"x","costo":999999999999,"margenPorcentaje":1000,"unidad":"UNIDAD"}'
# 409 "El dato ya existe o entra en conflicto con otro"
```

#### H12: venta a $0

```
curl -s localhost:8080/api/articulos -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"codigoInterno":"CERO","descripcion":"cero","costo":0,"unidad":"UNIDAD"}'        # 201, precioFinal 0.00
# después: POST /api/ventas con ese artículo a precioUnitario 0 y totalMostrado 0 → 201
```

#### H13: 0.250 se lee como 250

```
printf 'Codigo;Descripcion;Precio\nDBS-200;Bulon;0.250\n' > lista.csv
curl -s localhost:8080/api/importaciones/vista-previa -H "Authorization: Bearer $TOKEN" \
  -F archivo=@lista.csv \
  -F 'datos={"proveedorId":1,"mapeo":{"hoja":0,"filaDesde":2,"columnaCodigo":0,"columnaDescripcion":1,"columnaCosto":2}};type=application/json'
# costoNuevo: 250.00
```

#### H02 y H15

```
cd frontend && npx ng build && grep -rl "localhost:8080" dist/frontend/browser/
cd backend && ./mvnw test                         # puede fallar MigracionV5Test
cd backend && ./mvnw test -Dtest=MigracionV5Test  # pasa
```

Revisión hecha el 17/10/2026 con Claude Code sobre `luca884/ferreteria` (a3771e0 → 45a5735), `luca884/mostrador-gestion` (solo documentación, 11e5a1a) y `luca884/obsidian` (Projects/Ferreteria). Los repos no se modificaron. Los datos de prueba vivieron en una base MySQL local y descartable.