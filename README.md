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

- **Backend (Railway)**: el jar arranca en el perfil `prod`, sin datos demo. Variables obligatorias: `JWT_SECRET` (32 bytes o más), `DB_URL`, `DB_USER`, `DB_PASSWORD` y `CORS_ORIGINS`. Sin `JWT_SECRET` no arranca.
- **Frontend (Vercel)**: el front le pide la API a `/api` en su mismo origen. `frontend/vercel.json` reenvía `/api/*` a Railway: reemplazá `REEMPLAZAR-CON-URL-DE-RAILWAY` por la URL del backend. En local hace lo mismo el proxy de `ng serve` (`frontend/proxy.conf.json`).

## Documentación

- `docs/spec.md`: especificación del MVP (casos de uso, reglas de negocio, modelo de datos, ARCA, plan).
- `CLAUDE.md`: reglas de arquitectura y convenciones para trabajar en el repo.
