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

- **Backend**: el jar arranca en el perfil `prod`, sin datos demo, con la zona `America/Argentina/Buenos_Aires` fija en la imagen. Variables obligatorias: `JWT_SECRET` (32 bytes o más), `ARCA_CLAVE_MAESTRA` (32 bytes en Base64: `openssl rand -base64 32`), `DB_URL`, `DB_USER`, `DB_PASSWORD` y `CORS_ORIGINS` (el dominio del frontend; con otro `Origin` el backend responde 403). Sin `JWT_SECRET` o sin `ARCA_CLAVE_MAESTRA` no arranca. `ARCA_CLAVE_MAESTRA` cifra las claves privadas de los certificados de ARCA: guardá una copia fuera de Railway antes de sellarla, porque si se pierde ninguna ferretería puede volver a firmar y todas tienen que pedir un certificado nuevo. Para rotarla, la nueva va en `ARCA_CLAVE_MAESTRA` y la vieja en `ARCA_CLAVE_MAESTRA_ANTERIOR`. Nada se recifra: la vieja se saca recién cuando todas las ferreterías renovaron el certificado (dura dos años), y no se rota otra vez antes, porque hay un solo lugar para la anterior (`docs/spec.md`, «Seguridad»). En cada deploy Railway le manda SIGTERM al contenedor viejo y SIGKILL pasados los `deploy.drainingSeconds` de `backend/railway.json` (70 s): cubren el apagado ordenado de `application.yml` (20 s para los pedidos web en curso, 20 s para los pedidos de CAE y 20 s para las tareas programadas, una espera detrás de la otra) más un margen, y `ApagadoOrdenadoTest` falla si alguna espera sube y el drenaje no.
- **Frontend**: Caddy sirve el build de Angular y reenvía `/api/*` al backend por la red privada (`backend.railway.internal:8080`, se cambia con `BACKEND_HOST`). El front le pide la API a `/api` en su mismo origen; en local hace lo mismo el proxy de `ng serve` (`frontend/proxy.conf.json`).
- **Primer usuario**: la base de producción arranca sin usuarios. El primer `SUPER_ADMIN` se crea una sola vez con un `INSERT` en `usuario` (`ferreteria_id` NULL, rol `SUPER_ADMIN`) y el hash de `htpasswd -nBC 10 "" | tr -d ':\n'` (sin el `tr`, la salida empieza con `:` y el login da 401).

Antes de cada deploy, probá la imagen real del backend: `cd backend && ./mvnw test -Pimagen`. Construye `backend/Dockerfile` con `docker build`, la levanta en perfil `prod` contra un MySQL 8.4 vacío y verifica health, zona horaria, login, la lectura de un PDF desde el jar, la generación de un pedido de certificado de ARCA (cifrado y BouncyCastle dentro del jar) y, al final, que con un SIGTERM se apague ordenada y salga antes del drenaje. Tarda unos minutos la primera vez; después BuildKit reusa las capas, y la imagen de cada corrida se borra al terminar. `./mvnw test` no lo corre, y `./mvnw test -Dtest=ImagenDeProduccionTest` sin `-Pimagen` falla con «No tests were executed!». Desde IntelliJ, «Run All Tests» sí lo incluye. La imagen se construye para `linux/amd64`, como en Railway; en una Mac con Apple Silicon Docker emula x86, así que la corrida tarda unos 2 minutos.

```bash
railway up ./backend --path-as-root --service backend --ci
railway up ./frontend --path-as-root --service frontend --ci
```

## Documentación

- `docs/spec.md`: especificación del MVP (casos de uso, reglas de negocio, modelo de datos, ARCA, plan).
- `CLAUDE.md`: reglas de arquitectura y convenciones para trabajar en el repo.
