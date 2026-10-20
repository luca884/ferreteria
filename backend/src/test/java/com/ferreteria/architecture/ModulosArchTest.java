package com.ferreteria.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.model.Imputacion;
import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.ImputacionRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.ventas.model.Recibo;
import com.ferreteria.ventas.repository.ReciboRepository;
import com.ferreteria.ventas.service.RegistroVenta;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Fronteras entre modulos (R-022). {@code ventas} y {@code stock} hablan con el catalogo SOLO por el puerto
 * {@code catalogo.api} (tipos del JDK): nunca por sus entidades, repositorios o servicios. {@code stock} no
 * conoce a {@code ventas}. Y todavia no existe {@code facturacion}: solo ese modulo hablara con ARCA y llega
 * en la fase 5.
 *
 * El modulo {@code cotizacion} (R-024) no conoce a catalogo, ventas ni stock; {@code catalogo} lo usa SOLO por
 * {@code cotizacion.api} (tipos del JDK). Y {@code ventas} y {@code stock} ni lo nombran: el mostrador y las ventas
 * leen el precio final en pesos guardado (R-021).
 *
 * Las reglas con {@code that()} fallan si no hay clases que cumplan el filtro (ArchUnit 1.x), asi que si
 * alguien renombra o borra {@code ventas} o {@code stock} el test se pone rojo en vez de pasar en vacio.
 */
class ModulosArchTest {

    private final JavaClasses clases = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ferreteria");

    @Test
    void ventasNoDependeDelCatalogoSalvoPorSuPuerto() {
        noClasses().that().resideInAPackage("com.ferreteria.ventas..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.catalogo..")
                        .and(not(resideInAPackage("com.ferreteria.catalogo.api.."))))
                .check(clases);
    }

    @Test
    void ventasNoDependeDeClientesSalvoPorSuPuerto() {
        noClasses().that().resideInAPackage("com.ferreteria.ventas..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.clientes..")
                        .and(not(resideInAPackage("com.ferreteria.clientes.api.."))))
                .check(clases);
    }

    @Test
    void clientesNoDependeDeVentasNiDeStock() {
        noClasses().that().resideInAPackage("com.ferreteria.clientes..")
                .should().dependOnClassesThat(resideInAnyPackage("com.ferreteria.ventas..", "com.ferreteria.stock.."))
                .check(clases);
    }

    @Test
    void stockNoDependeDelCatalogoSalvoPorSuPuerto() {
        noClasses().that().resideInAPackage("com.ferreteria.stock..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.catalogo..")
                        .and(not(resideInAPackage("com.ferreteria.catalogo.api.."))))
                .check(clases);
    }

    @Test
    void stockNoDependeDeVentas() {
        noClasses().that().resideInAPackage("com.ferreteria.stock..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.ventas.."))
                .check(clases);
    }

    @Test
    void cotizacionNoDependeDeCatalogoVentasNiStock() {
        noClasses().that().resideInAPackage("com.ferreteria.cotizacion..")
                .should().dependOnClassesThat(resideInAnyPackage(
                        "com.ferreteria.catalogo..", "com.ferreteria.ventas..", "com.ferreteria.stock.."))
                .check(clases);
    }

    @Test
    void catalogoUsaCotizacionSoloPorSuPuerto() {
        noClasses().that().resideInAPackage("com.ferreteria.catalogo..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.cotizacion..")
                        .and(not(resideInAPackage("com.ferreteria.cotizacion.api.."))))
                .check(clases);
    }

    @Test
    void ventasYStockNoConocenLaCotizacion() {
        noClasses().that().resideInAnyPackage("com.ferreteria.ventas..", "com.ferreteria.stock..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.cotizacion.."))
                .check(clases);
    }

    /**
     * {@code common} es la base que todos usan: no conoce a ningun modulo de negocio. Quedan fuera de la regla
     * {@code auth} y {@code tenant}, que son infraestructura transversal (el manejador global de errores traduce
     * {@code CredencialesInvalidasException} y {@code MissingTenantException}), no negocio.
     */
    @Test
    void commonNoDependeDeLosModulosDeNegocio() {
        noClasses().that().resideInAPackage("com.ferreteria.common..")
                .should().dependOnClassesThat(resideInAnyPackage(
                        "com.ferreteria.catalogo..", "com.ferreteria.ventas..", "com.ferreteria.stock..",
                        "com.ferreteria.cotizacion..", "com.ferreteria.ferreteria.."))
                .check(clases);
    }

    /**
     * La regla de arriba no alcanza: javac copia las constantes {@code static final String} en el sitio de uso, asi que
     * el bytecode de {@code common} no registra la dependencia con {@code ArticuloModificado} (lo comprobado al
     * escribirla: daba verde con el import mal puesto). Por eso se revisan tambien los {@code import} del codigo fuente.
     */
    @Test
    void commonNoImportaLosModulosDeNegocioEnElCodigoFuente() throws IOException {
        Pattern prohibido = Pattern.compile(
                "^import\\s+(static\\s+)?com\\.ferreteria\\.(catalogo|ventas|stock|cotizacion|ferreteria)\\.",
                Pattern.MULTILINE);
        try (Stream<Path> archivos = Files.walk(Path.of("src/main/java/com/ferreteria/common"))) {
            List<String> infractores = archivos.filter(a -> a.toString().endsWith(".java"))
                    .filter(a -> prohibido.matcher(leer(a)).find())
                    .map(Path::toString).toList();
            assertThat(infractores).isEmpty();
        }
    }

    private static String leer(Path archivo) {
        try {
            return Files.readString(archivo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void todaviaNoExisteElPaqueteFacturacion() {
        noClasses().should().resideInAPackage("com.ferreteria.facturacion..").check(clases);
    }

    @Test
    void catalogoNoDependeDeVentasNiStock() {
        noClasses().that().resideInAPackage("com.ferreteria.catalogo..")
                .should().dependOnClassesThat(resideInAnyPackage("com.ferreteria.ventas..", "com.ferreteria.stock.."))
                .check(clases);
    }

    @Test
    void elDominioDelCatalogoNoDependeDeLaWebNiDeLosDtos() {
        noClasses().that().resideInAPackage("com.ferreteria.catalogo.model..")
                .should().dependOnClassesThat(resideInAnyPackage(
                        "com.ferreteria.catalogo.controller..", "com.ferreteria.catalogo.dto..",
                        "org.springframework.web..", "jakarta.servlet.."))
                .check(clases);
    }

    // ---- Cuenta corriente de clientes (CC-001, CC-008): el puerto es chico y la escritura del saldo tiene una sola puerta ----

    /** (a) {@code clientes.api} solo habla JDK y consigo mismo: ni entidades, ni repositorios, ni auth, ni ventas. */
    @Test
    void elPuertoDeClientesSoloDependeDelJdkYDeSiMismo() {
        classes().that().resideInAPackage("com.ferreteria.clientes.api..")
                .should().onlyDependOnClassesThat(resideInAnyPackage("java..", "com.ferreteria.clientes.api.."))
                .check(clases);
    }

    /** (b) Solo CuentaCorrienteService escribe el saldo del cliente (el propio repositorio se llama a sí mismo en su default). */
    @Test
    void soloLaCuentaCorrienteEscribeElSaldo() {
        var llamadores = clases.get(ClienteRepository.class).getMethodCallsToSelf().stream()
                .filter(c -> c.getName().startsWith("sumarSaldo"))
                .map(JavaMethodCall::getOriginOwner)
                .filter(origen -> !origen.isEquivalentTo(ClienteRepository.class))
                .map(origen -> origen.getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(llamadores).containsExactly(CuentaCorrienteService.class.getName());
    }

    /** (c) El libro es append-only: la entidad no tiene setters y nadie borra con su repositorio. */
    @Test
    void elLibroDeCuentaCorrienteEsAppendOnly() {
        noMethods().that().areDeclaredIn(MovimientoCuentaCliente.class)
                .should().haveNameMatching("set[A-Z].*")
                .check(clases);

        noClasses().should().callMethodWhere(new com.tngtech.archunit.base.DescribedPredicate<JavaMethodCall>(
                "borrar o quitar renglones con MovimientoCuentaClienteRepository") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isAssignableTo(MovimientoCuentaClienteRepository.class)
                        && call.getName().matches("(delete|remove).*");
            }
        }).check(clases);
    }

    // ---- La venta a cuenta corriente (corte 3): ventas usa la cuenta SOLO por su puerto ----

    /** (e) RegistroVenta cobra a cuenta por el puerto {@code clientes.api}, y no conoce la implementación. */
    @Test
    void registroVentaUsaLaCuentaCorrientePorSuPuerto() {
        var dependencias = clases.get(RegistroVenta.class).getDirectDependenciesFromSelf().stream()
                .map(d -> d.getTargetClass().getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(dependencias).contains(CuentaCorrienteParaVenta.class.getName());
        assertThat(dependencias).doesNotContain(CuentaCorrienteService.class.getName());
    }

    /** (f) Ninguna clase de ventas toca los repositorios de clientes: ni el del cliente ni el del libro. */
    @Test
    void ventasNoUsaLosRepositoriosDeClientes() {
        noClasses().that().resideInAPackage("com.ferreteria.ventas..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.clientes.repository.."))
                .check(clases);
    }

    /** (g) Ni la implementación del servicio ni las entidades de la cuenta se usan desde ventas. */
    @Test
    void ventasNoDependeDeLaImplementacionDeLaCuenta() {
        noClasses().that().resideInAPackage("com.ferreteria.ventas..")
                .should().dependOnClassesThat(resideInAnyPackage("com.ferreteria.clientes.service..",
                        "com.ferreteria.clientes.model.."))
                .check(clases);
    }

    // ---- Cobranza (corte 2 de cuenta-corriente-tramo-3a): puerto nuevo, una sola puerta de escritura para libro e imputación ----

    private static final String PUERTO_COBRANZA = "com.ferreteria.clientes.api.CuentaCorrienteParaCobranza";

    /**
     * (h) El puerto de cobranza existe en {@code clientes.api} y, con sus records y su excepción, solo habla JDK y consigo
     * mismo (la regla (a) cubre todo el paquete; esta además falla si el puerto no existe).
     */
    @Test
    void elPuertoDeCobranzaExisteYSoloDependeDelJdkYDeSiMismo() {
        var puerto = clases.get(PUERTO_COBRANZA);
        assertThat(puerto.isInterface()).isTrue();
        assertThat(puerto.getPackageName()).isEqualTo("com.ferreteria.clientes.api");

        classes().that().resideInAPackage("com.ferreteria.clientes.api..")
                .and().haveSimpleNameStartingWith("CuentaCorrienteParaCobranza")
                .should().onlyDependOnClassesThat(resideInAnyPackage("java..", "com.ferreteria.clientes.api.."))
                .allowEmptyShould(false)
                .check(clases);
    }

    private static com.tngtech.archunit.base.DescribedPredicate<JavaMethodCall> escribeConSave(Class<?> repositorio) {
        return new com.tngtech.archunit.base.DescribedPredicate<>("escribir (save*) con " + repositorio.getSimpleName()) {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isAssignableTo(repositorio) && call.getName().startsWith("save");
            }
        };
    }

    /** (j) Solo CuentaCorrienteService escribe el libro y las imputaciones (extiende la regla (b) del saldo). */
    @Test
    void soloLaCuentaCorrienteEscribeElLibroYLasImputaciones() {
        for (Class<?> repositorio : List.of(MovimientoCuentaClienteRepository.class, ImputacionRepository.class)) {
            // save/saveAll son métodos heredados de Spring Data: getMethodCallsToSelf() no los ve, se mira por tipo del dueño.
            var llamadores = clases.stream().flatMap(c -> c.getMethodCallsFromSelf().stream())
                    .filter(escribeConSave(repositorio))
                    .map(JavaMethodCall::getOriginOwner)
                    .map(origen -> origen.getName())
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(llamadores).as("quién escribe con %s", repositorio.getSimpleName())
                    .containsExactly(CuentaCorrienteService.class.getName());

            noClasses().that().areNotAssignableTo(CuentaCorrienteService.class)
                    .should().callMethodWhere(escribeConSave(repositorio))
                    .check(clases);
        }
    }

    /** (k-Imputación) La imputación es append-only: la entidad no tiene setters y nadie borra con su repositorio. */
    @Test
    void lasImputacionesSonAppendOnly() {
        noMethods().that().areDeclaredIn(Imputacion.class)
                .should().haveNameMatching("set[A-Z].*")
                .check(clases);

        noClasses().should().callMethodWhere(new com.tngtech.archunit.base.DescribedPredicate<JavaMethodCall>(
                "borrar o quitar renglones con ImputacionRepository") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isAssignableTo(ImputacionRepository.class)
                        && call.getName().matches("(delete|remove).*");
            }
        }).check(clases);
    }

    // ---- Anulación de cobranza (corte 2 de cuenta-corriente-tramo-3b-1): una sola mutación de imputación, una sola puerta ----

    /** (h) Los records nuevos del puerto de anulación existen y, como todo {@code clientes.api}, solo hablan JDK. */
    @Test
    void losRecordsDeLaAnulacionEstanEnElPuertoYSoloDependenDelJdk() {
        for (String nombre : List.of(PUERTO_COBRANZA + "$AsientoAnulacion", PUERTO_COBRANZA + "$AnulacionAsentada")) {
            var record = clases.get(nombre);
            assertThat(record.getPackageName()).isEqualTo("com.ferreteria.clientes.api");
            assertThat(record.getDirectDependenciesFromSelf().stream().map(d -> d.getTargetClass().getPackageName())
                    .filter(p -> !p.startsWith("java.") && !p.equals("com.ferreteria.clientes.api")))
                    .as("dependencias fuera del JDK de %s", nombre).isEmpty();
        }
        assertThat(clases.get(PUERTO_COBRANZA).getMethods().stream().map(m -> m.getName()))
                .contains("asentarAnulacion");
    }

    private static com.tngtech.archunit.base.DescribedPredicate<JavaMethodCall> llamaALaFabrica(Class<?> entidad) {
        return new com.tngtech.archunit.base.DescribedPredicate<>("crear un asiento con la fábrica de " + entidad.getSimpleName()) {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(entidad) && java.util.Set.of("venta", "cobro", "anulacion", "de")
                        .contains(call.getName());
            }
        };
    }

    /**
     * (j, fábricas) Solo CuentaCorrienteService arma asientos del libro (venta, cobro y la {@code anulacion} nueva) y
     * imputaciones: la fábrica es la otra mitad de «una sola puerta de escritura» (el {@code save} es la primera).
     */
    @Test
    void soloLaCuentaCorrienteArmaLosAsientosDelLibroYLasImputaciones() {
        for (Class<?> entidad : List.of(MovimientoCuentaCliente.class, Imputacion.class)) {
            var llamadores = clases.stream().flatMap(c -> c.getMethodCallsFromSelf().stream())
                    .filter(llamaALaFabrica(entidad))
                    .map(JavaMethodCall::getOriginOwner)
                    .map(origen -> origen.getName())
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(llamadores).as("quién arma %s", entidad.getSimpleName())
                    .containsExactly(CuentaCorrienteService.class.getName());
        }
        // y la fábrica de la anulación existe en la entidad
        assertThat(clases.get(MovimientoCuentaCliente.class).getMethods().stream().map(m -> m.getName())).contains("anulacion");
    }

    /**
     * (k-Imputación-mod) CC-102: en {@code ImputacionRepository} el ÚNICO método {@code @Modifying} es
     * {@code desactivarDeRecibo} y solo {@code CuentaCorrienteService} lo invoca. Ningún otro repositorio de clientes declara
     * {@code @Modifying} salvo {@code ClienteRepository.sumarSaldo} (whitelist explícita: lo que existe hoy). Un
     * {@code delete*}/{@code remove*} sobre {@code ImputacionRepository} sigue prohibido (regla k-Imputación).
     */
    @Test
    void laUnicaMutacionDeImputacionEsLaBajaPorAnulacion() {
        var modificadores = clases.stream()
                .filter(c -> c.isInterface() && c.getPackageName().equals("com.ferreteria.clientes.repository"))
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(org.springframework.data.jpa.repository.Modifying.class))
                .map(m -> m.getOwner().getSimpleName() + "." + m.getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(modificadores).as("métodos @Modifying de los repositorios de clientes")
                .containsExactlyInAnyOrder("ClienteRepository.sumarSaldo", "ImputacionRepository.desactivarDeRecibo");

        var llamadores = clases.stream().flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().isEquivalentTo(ImputacionRepository.class)
                        && call.getName().startsWith("desactivarDeRecibo"))
                .map(JavaMethodCall::getOriginOwner)
                .filter(origen -> !origen.isEquivalentTo(ImputacionRepository.class))
                .map(origen -> origen.getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(llamadores).as("quién da de baja imputaciones").containsExactly(CuentaCorrienteService.class.getName());
    }

    // ---- Cobranza (corte 3 de cuenta-corriente-tramo-3a): ventas orquesta el recibo y llama al puerto ----

    private static final String REGISTRO_COBRANZA = "com.ferreteria.ventas.service.RegistroCobranza";

    /**
     * (i) RegistroCobranza cobra por el puerto {@code clientes.api.CuentaCorrienteParaCobranza} y NO conoce la implementación
     * (copia de la regla (e)). Falla si RegistroCobranza no existe: la regla no puede pasar en vacío.
     */
    @Test
    void registroCobranzaUsaLaCuentaCorrientePorSuPuerto() {
        var dependencias = clases.get(REGISTRO_COBRANZA).getDirectDependenciesFromSelf().stream()
                .map(d -> d.getTargetClass().getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(dependencias).contains(PUERTO_COBRANZA);
        assertThat(dependencias).doesNotContain(CuentaCorrienteService.class.getName());
    }

    /** (k-Recibo) El recibo es inmutable: la entidad no tiene setters y nadie borra con su repositorio. */
    @Test
    void losRecibosSonInmutablesYNadieLosBorra() {
        noMethods().that().areDeclaredIn(Recibo.class)
                .should().haveNameMatching("set[A-Z].*")
                .check(clases);

        noClasses().should().callMethodWhere(new com.tngtech.archunit.base.DescribedPredicate<JavaMethodCall>(
                "borrar o quitar renglones con ReciboRepository") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isAssignableTo(ReciboRepository.class)
                        && call.getName().matches("(delete|remove).*");
            }
        }).check(clases);
    }

    /**
     * (l) Las clases de cobranza de ventas (existen todas) no tocan repositorios, servicio ni modelo de clientes: solo su
     * puerto {@code clientes.api} (las reglas (f) y (g) ya lo exigen a todo ventas; esta además falla si falta alguna clase).
     */
    @Test
    void laCobranzaDeVentasNoDependeDeLaInternaDeClientes() {
        for (String nombre : List.of(REGISTRO_COBRANZA, "com.ferreteria.ventas.service.CobranzaService",
                "com.ferreteria.ventas.service.CobranzaPedido", "com.ferreteria.ventas.service.CobroRegistrado",
                "com.ferreteria.ventas.service.CobranzaRechazadaException")) {
            var dependencias = clases.get(nombre).getDirectDependenciesFromSelf().stream()
                    .map(d -> d.getTargetClass().getPackageName())
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(dependencias).as("dependencias de %s", nombre).noneMatch(p -> p.startsWith("com.ferreteria.clientes.repository")
                    || p.startsWith("com.ferreteria.clientes.service") || p.startsWith("com.ferreteria.clientes.model"));
        }
    }

    // ---- Anulación de cobranza (corte 3 de cuenta-corriente-tramo-3b-1): ventas orquesta recibo, caja y estado ----

    private static final String REGISTRO_ANULACION = "com.ferreteria.ventas.service.RegistroAnulacion";
    private static final String ANULACION_SERVICE = "com.ferreteria.ventas.service.AnulacionService";

    /**
     * (k-Recibo-mod) CC-102, CC-062: el recibo cambia solo por {@code Recibo.anular} y solo {@code RegistroAnulacion} lo llama
     * (bajo el lock del cliente, junto con el contra-asiento). {@code ReciboRepository} no declara {@code @Modifying} ni
     * {@code delete*}/{@code remove*}. Falla si {@code RegistroAnulacion} no existe: la regla no puede pasar en vacío.
     */
    @Test
    void soloRegistroAnulacionAnulaReciboYElRepositorioNoModificaEnMasa() {
        var llamadores = clases.stream().flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().isEquivalentTo(Recibo.class) && call.getName().equals("anular"))
                .map(JavaMethodCall::getOriginOwner)
                .filter(origen -> !origen.isEquivalentTo(Recibo.class))
                .map(origen -> origen.getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(llamadores).as("quién anula recibos").containsExactly(REGISTRO_ANULACION);
        assertThat(clases.get(Recibo.class).getMethods().stream().map(m -> m.getName())).contains("anular");

        var modificadores = clases.get(ReciboRepository.class).getMethods().stream()
                .filter(m -> m.isAnnotatedWith(org.springframework.data.jpa.repository.Modifying.class))
                .map(m -> m.getName())
                .toList();
        assertThat(modificadores).as("@Modifying en ReciboRepository").isEmpty();
        assertThat(clases.get(ReciboRepository.class).getMethods().stream().map(m -> m.getName()))
                .as("la regla no es vacía: ReciboRepository tiene consultas").contains("clienteDe", "sumasAnuladasDeCaja");
        noMethods().that().areDeclaredIn(ReciboRepository.class).should().haveNameMatching("(delete|remove).*").check(clases);
    }

    /** (i') RegistroAnulacion anula por el puerto {@code CuentaCorrienteParaCobranza} y NO conoce la implementación. */
    @Test
    void registroAnulacionUsaLaCuentaCorrientePorSuPuerto() {
        var dependencias = clases.get(REGISTRO_ANULACION).getDirectDependenciesFromSelf().stream()
                .map(d -> d.getTargetClass().getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(dependencias).contains(PUERTO_COBRANZA);
        assertThat(dependencias).doesNotContain(CuentaCorrienteService.class.getName());
    }

    /** (l') La anulación de ventas (existen las dos clases) no toca repositorios, servicio ni modelo de clientes: solo su puerto. */
    @Test
    void laAnulacionDeVentasNoDependeDeLaInternaDeClientes() {
        for (String nombre : List.of(REGISTRO_ANULACION, ANULACION_SERVICE)) {
            var dependencias = clases.get(nombre).getDirectDependenciesFromSelf().stream()
                    .map(d -> d.getTargetClass().getPackageName())
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(dependencias).as("dependencias de %s", nombre).noneMatch(p -> p.startsWith("com.ferreteria.clientes.repository")
                    || p.startsWith("com.ferreteria.clientes.service") || p.startsWith("com.ferreteria.clientes.model"));
        }
    }
}
