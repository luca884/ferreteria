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

- **Backend**: el jar arranca en el perfil `prod`, sin datos demo, con la zona `America/Argentina/Buenos_Aires` fija en la imagen. Variables obligatorias: `JWT_SECRET` (32 bytes o más), `DB_URL`, `DB_USER`, `DB_PASSWORD` y `CORS_ORIGINS` (el dominio del frontend; con otro `Origin` el backend responde 403). Sin `JWT_SECRET` no arranca.
- **Frontend**: Caddy sirve el build de Angular y reenvía `/api/*` al backend por la red privada (`backend.railway.internal:8080`, se cambia con `BACKEND_HOST`). El front le pide la API a `/api` en su mismo origen; en local hace lo mismo el proxy de `ng serve` (`frontend/proxy.conf.json`).
- **Primer usuario**: la base de producción arranca sin usuarios. El primer `SUPER_ADMIN` se crea una sola vez con un `INSERT` en `usuario` (`ferreteria_id` NULL, rol `SUPER_ADMIN`) y el hash de `htpasswd -nBC 10 "" | tr -d ':\n'` (sin el `tr`, la salida empieza con `:` y el login da 401).

Antes de cada deploy, probá la imagen real del backend: `cd backend && ./mvnw test -Pimagen`. Construye `backend/Dockerfile` con `docker build`, la levanta en perfil `prod` contra un MySQL 8.4 vacío y verifica health, zona horaria, login y la lectura de un PDF desde el jar. Tarda unos minutos la primera vez; después Docker reusa las capas (la imagen queda como `ferreteria-api:imagen-test`). `./mvnw test` no lo corre.

```bash
railway up ./backend --path-as-root --service backend --ci
railway up ./frontend --path-as-root --service frontend --ci
```

## Documentación

- `docs/spec.md`: especificación del MVP (casos de uso, reglas de negocio, modelo de datos, ARCA, plan).
- `CLAUDE.md`: reglas de arquitectura y convenciones para trabajar en el repo.
