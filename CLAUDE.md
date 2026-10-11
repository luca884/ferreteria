# CLAUDE.md

Guía para agentes que trabajan en este repo.

## Qué es

SaaS multi-tenant de gestión para ferreterías de Mar del Plata: mostrador (venta rápida con lector de código de barras), stock, actualización masiva de precios desde las listas de los proveedores (Excel y PDF) y factura electrónica con ARCA. Cada ferretería es un tenant.

- **Spec del MVP**: `docs/spec.md`. Los 10 casos de uso (CU-01 a CU-10) y las reglas de negocio salen de ahí. Si algo no está en la spec, no se implementa sin preguntar.
- **Principio rector**: simple, práctico y que funcione. Si una ferretería real no lo pide, no entra.
- **Documentación y avance**: vault de Obsidian en `~/Projects/Obsidian/Projects/Ferreteria/`. Al cerrar una sesión, actualizar `estado-actual.md` de esa carpeta.

## Stack

- `backend/`: Java 17, Spring Boot 3.3, Spring Security (JWT con jjwt), JPA/Hibernate, MySQL 8.4, Flyway, Maven.
- `frontend/`: Angular 22 (standalone, signals, control flow nuevo), Vitest.
- Tests backend: JUnit 5, Mockito, Testcontainers (MySQL real), ArchUnit.
- Deploy previsto: Railway (backend + MySQL), Vercel o Cloudflare Pages (frontend).

## Comandos

```bash
# Base de datos local (puerto 3307)
docker compose up -d

# Backend (perfil dev: carga datos demo)
cd backend && ./mvnw spring-boot:run
cd backend && ./mvnw test            # incluye Testcontainers: necesita Docker corriendo

# Frontend
cd frontend && npm install
cd frontend && npm start             # http://localhost:4200
cd frontend && npx ng test --watch=false
cd frontend && npx ng build
```

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
- Un caso de uso por rama (`feat/cu-04-importar-lista`): primero tests del dominio, después endpoint, después pantalla.

## Estado

Fase 2 (esqueleto) en curso: auth con JWT, multi-tenant, rubros como primer módulo con aislamiento probado. Próximo: fase 3, artículos y precios (CU-03, CU-04, CU-05). Ver `docs/spec.md` → Plan de implementación.
