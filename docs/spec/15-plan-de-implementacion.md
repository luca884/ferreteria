# Plan de implementación

> Parte de la especificación del MVP de Ferretería SaaS. Índice: [../spec.md](../spec.md) · sección extracto de `spec.md:623-661` en la partición del 2026-11-10.

<!-- spec payload: spec.md:623-661 -->
## Plan de implementación

> **Plan original, conservado como registro.** Describe el orden en que se encaró el proyecto, no su estado actual: los casos de uso de las fases 2 a 6 ya están implementados (ver el índice en [../spec.md](../spec.md)). Las referencias a ramas y al vault son de ese momento.

Son siete fases (de la 0 a la 6) en orden de riesgo y de dolor del cliente. La fase 0 y la prueba con ARCA corren en paralelo porque ARCA es lo único que puede trabar el proyecto entero. Desde el 2026-10-28 (plan de facturación), esa prueba es el simulador de la fase 5 más la homologación con la piloto.

0. **Validación con ferreterías** (1 semana, en paralelo con la fase 1)
   - Visitar 3 o 4 ferreterías con las preguntas de la sección siguiente.
   - Conseguir un Excel real de lista de proveedor y una exportación real de artículos.
   - Resultado: esta lista de casos de uso ajustada y una ferretería piloto comprometida.
1. **ARCA: simulador y homologación con la piloto** (reemplaza a la prueba técnica en homologación; plan de facturación del 2026-10-28)
   - Sin proyecto aparte: el riesgo de ARCA se ataca con un simulador que copia el manual WSFEv1 4.8 (rama 2 de la fase 5) y con el cliente real probado sin CUIT (rama 6). La homologación necesita el certificado de una piloto y va en la rama 11.
   - Terminado cuando: con el certificado de la piloto, la suite de contrato pasa contra homologación y obtiene un CAE real de homologación de una factura B (rama 11).
2. **Esqueleto** (3 a 5 días)
   - Repo, Spring Boot + Angular, auth y multi-tenant traídos de Sendo, Flyway, Docker Compose y deploy en Railway.
   - Terminado cuando: un usuario de cada uno de dos tenants entra y no ve nada del otro (con test).
3. **Artículos y precios** (CU-03, CU-04, CU-05) (2 a 3 semanas)
   - Terminado cuando: el Excel real de la fase 0 se importa con vista previa y se aplica sin errores.
4. **Mostrador** (CU-06, CU-07 sin ARCA, CU-10) (2 semanas)
   - Búsqueda con lector de código de barras, ticket, medios de pago, presupuesto y caja.
   - Terminado cuando: una venta de 5 artículos se cierra en menos de 15 segundos solo con teclado y lector.
5. **Facturación electrónica** (CU-01, CU-07 completo, CU-08) (unas 18.000 a 28.000 líneas: las 2 semanas originales quedan cortas)
   - Once ramas, en este orden (detalle en `wiki/plan-facturacion.md` del vault):
     - Rama 1, `feat/cu-01-datos-fiscales`: CU-01 paso 1, datos fiscales de la ferretería.
     - Rama 2, `feat/cu-07-motor-fiscal`: CU-07 pasos 3 y 5 sin pantalla: módulo `facturacion`, registro fiscal, numeración y simulador. Queda apagada en producción.
     - Rama 3, `feat/cu-07-venta-facturada`: la venta crea el registro fiscal y pide el CAE.
     - Rama 4, `feat/cu-07-factura-mostrador`: CU-07 paso 5 y CU-16, la factura impresa con QR en el mostrador.
     - Rama 5, `feat/cu-01-certificado-arca`: CU-01 pasos 2 y 3, punto de venta, certificado y clave cifrada. Puede empezar apenas la 2 fije el puerto, en paralelo con la 3 y la 4.
     - Rama 6, `feat/cu-01-conexion-arca`: CU-01 paso 4, WSAA y WSFEv1 reales, y el simulador en el borde del transporte.
     - Rama 7, `feat/cu-07-contingencias`: reintento automático, bandeja de rechazados y contingencia.
     - Rama 8, `feat/cu-08-nota-de-credito`: nota de crédito con CAE sobre una factura autorizada.
     - Rama 9, `feat/cu-07-remito-presupuesto-imprimible`: remito y presupuesto imprimibles. Puede ir en cualquier momento después de la 4.
     - Rama 10, `feat/cu-08-nc-cuenta-corriente`: CU-08 sobre cuenta corriente.
     - Rama 11, `feat/cu-07-arca-real-piloto`: homologación con la piloto, que se adelanta apenas haya una piloto con certificado y la 6 esté en producción, y paso a producción, que espera la 7, la 8 y al contador.
   - Siguen abiertos con el contador el CAEA y los duplicados electrónicos: si alguno entra, es una rama más antes del paso a producción.
6. **Stock, pulido y piloto** (CU-02, CU-09) (2 semanas)
   - Ajustes de stock, gestión de usuarios, carga inicial de datos del piloto y paso a producción en ARCA (rama 11 de la fase 5).

**Forma de trabajo:** la spec vive en `docs/spec/` (índice en `docs/spec.md`) y el `AGENTS.md` apunta a ella. Cada caso de uso se implementa en una rama propia: primero los tests del dominio, después el endpoint y después la pantalla.
<!-- fin payload: spec.md:623-661 -->

