# Ferretería

Sistema web multi-tenant para ferreterías: mostrador, stock, listas de precios de proveedores y factura electrónica con ARCA.

## Arrancar en local

Necesitás Docker, Java 17+ y Node 22.22.3+ (o 24.15+).

```bash
docker compose up -d                       # MySQL en localhost:3307
cd backend && ./mvnw spring-boot:run       # API en http://localhost:8080
cd frontend && npm install && npm start    # Web en http://localhost:4200
```

Entrá con `dueno@tornillo.demo` / `demo1234`. Con `dueno@tuerca.demo` ves otra ferretería, con datos separados.

## Tests

```bash
cd backend && ./mvnw test                  # unitarios, ArchUnit y aislamiento con MySQL real (Testcontainers)
cd frontend && npx ng test --watch=false
```

## Deploy

Todo corre en Railway, en el proyecto `ferreteria` (región us-east), con tres servicios: `MySQL`, `backend` y `frontend`. Cada servicio se construye con el `Dockerfile` de su carpeta y su `railway.json`.

- **Backend**: el jar arranca en el perfil `prod`, sin datos demo, con la zona `America/Argentina/Buenos_Aires` fija en la imagen. Variables obligatorias: `JWT_SECRET` (32 bytes o más), `ARCA_CLAVE_MAESTRA` y `TOTP_CLAVE_MAESTRA` (32 bytes en Base64 cada una, distintas: `openssl rand -base64 32`), `DB_URL`, `DB_USER`, `DB_PASSWORD` y `CORS_ORIGINS` (el dominio del frontend; con otro `Origin` el backend responde 403). Sin `JWT_SECRET`, sin `ARCA_CLAVE_MAESTRA` o sin `TOTP_CLAVE_MAESTRA` no arranca. `TOTP_CLAVE_MAESTRA` cifra el secreto del código de la app del super admin: creala en Railway antes del deploy que trae el código de la app. Si cambia, el secreto guardado ya no se descifra y el super admin no puede entrar hasta resetearlo (ver «Código de la app del super admin»). `ARCA_CLAVE_MAESTRA` cifra las claves privadas de los certificados de ARCA: guardá una copia fuera de Railway antes de sellarla, porque si se pierde ninguna ferretería puede volver a firmar y todas tienen que pedir un certificado nuevo. Para rotarla, la nueva va en `ARCA_CLAVE_MAESTRA` y la vieja en `ARCA_CLAVE_MAESTRA_ANTERIOR`. Nada se recifra: la vieja se saca recién cuando todas las ferreterías renovaron el certificado (dura dos años), y no se rota otra vez antes, porque hay un solo lugar para la anterior (`docs/spec.md`, «Seguridad»). En cada deploy Railway le manda SIGTERM al contenedor viejo y SIGKILL pasados los `deploy.drainingSeconds` de `backend/railway.json` (70 s): cubren el apagado ordenado de `application.yml` (20 s para los pedidos web en curso, 20 s para los pedidos de CAE y 20 s para las tareas programadas, una espera detrás de la otra) más un margen, y `ApagadoOrdenadoTest` falla si alguna espera sube y el drenaje no.
- **Frontend**: Caddy sirve el build de Angular y reenvía `/api/*` al backend por la red privada (`backend.railway.internal:8080`, se cambia con `BACKEND_HOST`). El front le pide la API a `/api` en su mismo origen; en local hace lo mismo el proxy de `ng serve` (`frontend/proxy.conf.json`). Caddy le pasa al backend la IP del cliente en `X-Real-IP` (el límite de intentos del login cuenta por ella). Si el pedido viene de un proxy de confianza (`PROXIES_DE_CONFIANZA`; por defecto, los rangos privados y `100.64.0.0/10`, desde donde llega el borde de Railway), la toma del `X-Real-IP` que pone el borde, que pisa el que mande el cliente; si no, es la dirección de la conexión. `X-Forwarded-For` no sirve: delante del borde hay una CDN que agrega su propia IP, y esa IP rota. El `X-Forwarded-Proto` y el `X-Forwarded-Host` que le llegan al backend son los de Caddy (`http` y el host del pedido), no los del borde, como antes de confiar en él. Después de un deploy, confirmalo: 21 logins seguidos con `curl -H 'X-Real-IP: 1.2.3.<n>' -H 'X-Forwarded-For: 1.2.3.<n>'` (un `<n>` y un email distintos cada vez) tienen que terminar en 429, y el WARN del bloqueo en el log del backend tiene que mostrar tu IP pública. El WARN es lo que decide: los 429 solos también salen si todos los clientes comparten una IP del borde, y entonces el límite de 20 por minuto es para todo el sistema. Si en vez de tu IP muestra una `100.64.x.x` o la de una CDN, cambió el borde de Railway. Insistí con fallos de un mismo email hasta que salga el WARN de 5 fallos seguidos, que dice desde qué IP llegaron (si la IP rota, pueden hacer falta decenas), y revisá con eso `frontend/Caddyfile`.
- **Primer usuario**: la base de producción arranca sin usuarios. El primer `SUPER_ADMIN` se crea una sola vez con un `INSERT` en `usuario` (`ferreteria_id` NULL, rol `SUPER_ADMIN`) y el hash de `htpasswd -nBC 10 "" | tr -d ':\n'` (sin el `tr`, la salida empieza con `:` y el login da 401). Su primer login, con email y contraseña, muestra un código QR una sola vez: escanealo con Google Authenticator y escribí el código de 6 números que te muestra. Desde ahí entrás con email, contraseña y el código que muestra la app en ese momento (cambia cada 30 segundos).
- **Código de la app del super admin**: después del deploy que lo trae, el super admin que ya existía también ve el QR en su próximo login. Hacé ese login enseguida: hasta el primer código, quien tenga la contraseña puede escanear el QR en tu lugar, y desde que lo activás ya no se muestra más. Si perdés el celular o cambia `TOTP_CLAVE_MAESTRA`, se resetea por base, sin botón en la app: `UPDATE usuario SET totp_secreto = NULL, totp_activado_en = NULL, totp_ultimo_paso = NULL WHERE email = '<email>' AND rol = 'SUPER_ADMIN';` con el email del super admin. El próximo login vuelve a mostrar el QR, así que hacelo enseguida, como la primera vez, y borrá de Google Authenticator la cuenta vieja.

Antes de cada deploy, probá la imagen real del backend: `cd backend && ./mvnw test -Pimagen`. Construye `backend/Dockerfile` con `docker build`, la levanta en perfil `prod` contra un MySQL 8.4 vacío y verifica health, zona horaria, el login del super admin con el código de la app (el alta con el QR y el primer código), que sin `ARCA_CLAVE_MAESTRA` o sin `TOTP_CLAVE_MAESTRA` no arranque, la lectura de un PDF desde el jar, la generación de un pedido de certificado de ARCA (cifrado y BouncyCastle dentro del jar), «Probar conexión» contra el simulador de ARCA (la firma CMS, el XSD y el XML leídos desde el jar) y, al final, que con un SIGTERM se apague ordenada y salga antes del drenaje. Tarda unos minutos la primera vez; después BuildKit reusa las capas, y la imagen de cada corrida se borra al terminar. `./mvnw test` no lo corre, y `./mvnw test -Dtest=ImagenDeProduccionTest` sin `-Pimagen` falla con «No tests were executed!». Desde IntelliJ, «Run All Tests» sí lo incluye. La imagen se construye para `linux/amd64`, como en Railway; en una Mac con Apple Silicon Docker emula x86, así que la corrida tarda unos 2 minutos.

Para probar la red y el TLS contra ARCA de verdad: `cd backend && ./mvnw test -Parca-real`. Corre solo los tests con el tag `arca-real`: FEDummy contra homologación (wswhomo) y producción (servicios1), con el cliente y el transporte reales y las URL del `application.yml`, y que servicios1 negocie una suite ECDHE con el TLS del transporte. No usa certificado ni CUIT, porque FEDummy no pide autenticación. Necesita internet, así que `./mvnw test` y `-Pimagen` no lo corren, y `./mvnw test -Dtest=ArcaRealTest` sin `-Parca-real` falla con «No tests were executed!». Si falla, antes de tocar código fijate si ARCA está caída.

```bash
railway up ./backend --path-as-root --service backend --ci
railway up ./frontend --path-as-root --service frontend --ci
```

## Documentación

- `docs/spec.md`: especificación del MVP (casos de uso, reglas de negocio, modelo de datos, ARCA, plan).
- `CLAUDE.md`: reglas de arquitectura y convenciones para trabajar en el repo.
