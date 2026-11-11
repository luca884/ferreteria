# AGENTS.md

Guía para agentes que trabajan en este repo.

Harness: **Gentle Shell + Gentle AI** (Pi). Memoria persistente en Engram con `project: "ferreteria"`.

## Qué es

SaaS multi-tenant de gestión para ferreterías de Mar del Plata: mostrador (venta rápida con lector de código de barras), stock, actualización masiva de precios desde las listas de los proveedores (Excel y PDF) y factura electrónica con ARCA. Cada ferretería es un tenant.

- **Spec del MVP**: [`docs/spec.md`](docs/spec.md) es el índice; el contenido vive en `docs/spec/`, un archivo por caso de uso (16) más las secciones transversales (reglas de negocio, modelo de datos, ARCA, arquitectura). Leé **sólo** el archivo del CU que vas a tocar; no hace falta leer la spec entera. Si algo no está en la spec, no se implementa sin preguntar.
- **Principio rector**: simple, práctico y que funcione. Si una ferretería real no lo pide, no entra.

## Stack

- `backend/`: Java 17, Spring Boot 3.3, Spring Security (JWT con jjwt), JPA/Hibernate, MySQL 8.4, Flyway, Maven.
- `frontend/`: Angular 22 (standalone, signals, control flow nuevo), Vitest.
- Tests backend: JUnit 5, Mockito, Testcontainers (MySQL real), ArchUnit.
- Deploy: todo en Railway (proyecto `ferreteria`, región us-east): servicios `backend`, `MySQL` y `frontend` (Caddy sirve el build de Angular y reenvía `/api` al backend por la red privada). Procedimiento en `README.md` → Deploy.

## Comandos

```bash
docker compose up -d                 # MySQL local (puerto 3307)

cd backend && ./mvnw spring-boot:run                     # perfil dev, carga datos demo
cd backend && ./mvnw test -Dtest=<Clase>#<metodo>        # focalizado
cd backend && ./mvnw test                                # suite: 3 JVM en paralelo, MySQL propia (~9 min)
cd backend && ./mvnw test -DforkCount=1                  # suite con una sola JVM
cd backend && ./mvnw test -Pimagen                       # imagen real; obligatorio antes de deploy y si cambia backend/Dockerfile

cd frontend && npm install
cd frontend && npm start                                 # http://localhost:4200
cd frontend && npx ng test --watch=false --include=<ruta/al/archivo.spec.ts>
cd frontend && npx ng test --watch=false
cd frontend && npx ng build                              # si toca templates, rutas o configuración
```

Necesita Docker corriendo. **Nunca dos suites a la vez**; borrar solo contenedores propios.

Usuarios demo (perfil dev, contraseña `demo1234`): `dueno@tornillo.demo`, `vendedor@tornillo.demo`, `dueno@tuerca.demo`, `admin@demo.local`.

## Reglas de arquitectura (no negociables)

1. **Aislamiento por tenant.** Toda tabla de negocio tiene `ferreteria_id`. La ferretería del request sale SOLO del JWT (`TenantFilter` → `TenantContext`), nunca de un parámetro o header del cliente.
2. **Repositorios de datos de una ferretería extienden `TenantScopedRepository`** y se usan con sus métodos `*Scoped` (`findScoped`, `findAllScoped`, o métodos propios que llamen a `TenantContext.requireFerreteriaId()`). Los finders sin scope (`findById`, `findAll`…) están prohibidos por `TenantScopingArchTest`. Excepción documentada: `@UnscopedTenantAccess(reason = "...")`.
3. **Un recurso de otra ferretería devuelve 404**, igual que uno inexistente.
4. **Plata en `BigDecimal` / `DECIMAL`**. Nunca `double`.
5. **Módulos por paquete** (`auth`, `tenant`, `ferreteria`, `catalogo`, y después `ventas`, `stock`, `facturacion`, `documentos`). Solo `facturacion` habla con ARCA.
6. **Migraciones con Flyway**, nunca `ddl-auto` distinto de `validate`. Los datos demo van en `db/dev-data` (solo perfiles dev y test).
7. Todo endpoint nuevo de datos de una ferretería lleva un caso en un test de integración que pruebe que otra ferretería no lo ve.

## Convenciones

- Nombres del dominio en español (`Rubro`, `Articulo`, `Comprobante`), código técnico en inglés cuando es estándar (`Repository`, `Service`, `Controller`).
- Mensajes de error al usuario en español rioplatense.
- Commits en español: `tipo: descripción` (`feat`, `fix`, `refactor`, `test`, `docs`, `chore`).
- Un caso de uso por rama (`feat/cu-04-importar-lista`): primero tests del dominio, después endpoint, después pantalla. Esta rama gana sobre `feat/<feature>`.
- Feature docs: `odd/tasks/<feature>.md`, commiteados con la feature. Antes de abrir uno, mirá [`odd/tasks/INDEX.md`](odd/tasks/INDEX.md): una línea por tarea (86 hoy) para no leer la carpeta entera. Si cambia el estado de una tarea, regeneralo con `node odd/indice-tareas.mjs` (nunca a mano). Espejo Engram con `project: "ferreteria"`.

## Límites

- **Push a `main`, merge y deploy los hace Luca.** El agente deja la rama y el PR listos.
- **El estado del proyecto no vive en este archivo**: vive en el vault (`Projects/Ferreteria/estado-actual.md`) y en Engram. No lo dupliques acá.
