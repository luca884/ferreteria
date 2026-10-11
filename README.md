# Ferretería

Sistema web multi-tenant para ferreterías: mostrador, stock, listas de precios de proveedores y factura electrónica con ARCA.

## Arrancar en local

Necesitás Docker, Java 17+ y Node 22.22+ (o 24).

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

## Documentación

- `docs/spec.md`: especificación del MVP (casos de uso, reglas de negocio, modelo de datos, ARCA, plan).
- `CLAUDE.md`: reglas de arquitectura y convenciones para trabajar en el repo.
