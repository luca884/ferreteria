package com.ferreteria.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.model.AjusteCuenta;
import com.ferreteria.clientes.model.Imputacion;
import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.clientes.repository.AjusteCuentaRepository;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.ImputacionRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.clientes.service.RegistroAjuste;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.facturacion.api.ConfigArcaParaPlataforma;
import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.ComprobanteFiscalAlicuota;
import com.ferreteria.facturacion.model.IntentoFiscal;
import com.ferreteria.facturacion.motor.MotorFiscal;
import com.ferreteria.facturacion.motor.PasosFiscales;
import com.ferreteria.facturacion.repository.ComprobanteFiscalAlicuotaRepository;
import com.ferreteria.facturacion.repository.ComprobanteFiscalRepository;
import com.ferreteria.facturacion.repository.IntentoFiscalRepository;
import com.ferreteria.facturacion.simulador.SimuladorWsfe;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import com.ferreteria.plataforma.service.FerreteriasAdminService;
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
 * conoce a {@code ventas}. Solo {@code facturacion} habla con ARCA (CU-07, S8): no conoce a ventas, clientes, stock
 * ni catalogo, los demas la usan solo por {@code facturacion.api} (tipos del JDK) y toma la hora del {@code Clock}
 * inyectado, nunca del reloj del sistema.
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

    /** Compras (CU-13) cambia costos y suma stock SOLO por {@code catalogo.api} y el servicio de stock. */
    @Test
    void comprasNoDependeDelCatalogoSalvoPorSuPuerto() {
        noClasses().that().resideInAPackage("com.ferreteria.compras..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.catalogo..")
                        .and(not(resideInAPackage("com.ferreteria.catalogo.api.."))))
                .check(clases);
    }

    @Test
    void comprasNoDependeDeVentasClientesNiCotizacion() {
        noClasses().that().resideInAPackage("com.ferreteria.compras..")
                .should().dependOnClassesThat(resideInAnyPackage(
                        "com.ferreteria.ventas..", "com.ferreteria.clientes..", "com.ferreteria.cotizacion.."))
                .check(clases);
    }

    @Test
    void nadieDependeDeCompras() {
        noClasses().that().resideInAnyPackage("com.ferreteria.catalogo..", "com.ferreteria.stock..",
                        "com.ferreteria.ventas..", "com.ferreteria.clientes..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.compras.."))
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

    // ---- Facturacion (CU-07, rama 2, S8): el unico modulo que habla con ARCA, con un puerto chico y sin reloj propio ----

    /** {@code facturacion} no conoce el negocio que le pide facturas: quien la llama traduce a sus tipos. */
    @Test
    void facturacionNoDependeDeVentasClientesStockNiCatalogo() {
        noClasses().that().resideInAPackage("com.ferreteria.facturacion..")
                .should().dependOnClassesThat(resideInAnyPackage("com.ferreteria.ventas..", "com.ferreteria.clientes..",
                        "com.ferreteria.stock..", "com.ferreteria.catalogo.."))
                .check(clases);
    }

    /** Fuera de {@code facturacion} nadie usa sus entidades, servicios ni modelo: solo su puerto {@code facturacion.api}. */
    @Test
    void nadieDependeDeFacturacionSalvoPorSuPuerto() {
        noClasses().that().resideOutsideOfPackage("com.ferreteria.facturacion..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.facturacion..")
                        .and(not(resideInAPackage("com.ferreteria.facturacion.api.."))))
                .check(clases);
    }

    /** {@code facturacion.api} solo habla JDK y consigo mismo (molde de {@code clientes.api} y {@code auth.api}). */
    @Test
    void elPuertoDeFacturacionSoloDependeDelJdk() {
        classes().that().resideInAPackage("com.ferreteria.facturacion.api..")
                .should().onlyDependOnClassesThat(resideInAnyPackage("java..", "com.ferreteria.facturacion.api.."))
                // Existe desde T4 (ConfigArcaParaPlataforma, para el panel del super admin): la regla no puede quedar vacia.
                .allowEmptyShould(false)
                .check(clases);
    }

    /**
     * El puerto del panel del super admin ({@code ConfigArcaParaPlataforma}: marca «de prueba», simulador y facturacion
     * habilitada, por id explicito y sin tenant) lo usa solo {@code plataforma}; {@code facturacion} lo implementa. La
     * venta (rama 3) lee su configuracion por otro puerto, con la ferreteria del JWT.
     */
    @Test
    void elPuertoDeConfigArcaParaPlataformaSoloLoUsaPlataforma() {
        noClasses().that().resideOutsideOfPackages("com.ferreteria.plataforma..", "com.ferreteria.facturacion..")
                .should().dependOnClassesThat().belongToAnyOf(ConfigArcaParaPlataforma.class)
                .check(clases);
        // la regla no es vacia: la plataforma lo usa
        assertThat(clases.get(FerreteriasAdminService.class).getDirectDependenciesFromSelf())
                .anyMatch(d -> d.getTargetClass().isEquivalentTo(ConfigArcaParaPlataforma.class));
    }

    /**
     * Solo {@code facturacion} conoce a ARCA: ningun otro paquete nombra sus hosts. Un host es un {@code String}, no un
     * tipo, asi que el bytecode no registra la dependencia (y javac copia las constantes en el sitio de uso, ver la regla
     * de {@code common}): se revisa el codigo fuente.
     */
    @Test
    void soloFacturacionConoceLosHostsDeArca() throws IOException {
        Pattern hostDeArca = Pattern.compile("afip\\.gov\\.ar|arca\\.gob\\.ar", Pattern.CASE_INSENSITIVE);
        Path facturacion = Path.of("src/main/java/com/ferreteria/facturacion");
        try (Stream<Path> archivos = Files.walk(Path.of("src/main/java"))) {
            List<Path> revisados = archivos.filter(a -> a.toString().endsWith(".java"))
                    .filter(a -> !a.startsWith(facturacion))
                    .toList();
            assertThat(revisados).as("archivos revisados fuera de facturacion").isNotEmpty();
            assertThat(revisados.stream().filter(a -> hostDeArca.matcher(leer(a)).find()).map(Path::toString).toList())
                    .as("archivos fuera de facturacion que nombran un host de ARCA").isEmpty();
        }
    }

    /**
     * El QR de ARCA lo arma {@code facturacion} (cu-07-factura-mostrador S7): nadie mas usa ZXing, y {@code facturacion} si
     * (la regla no queda vacia). Quien imprime solo dibuja la matriz que le llega por el puerto.
     */
    @Test
    void soloFacturacionArmaElQrConZxing() {
        noClasses().that().resideOutsideOfPackage("com.ferreteria.facturacion..")
                .should().dependOnClassesThat(resideInAPackage("com.google.zxing.."))
                .check(clases);
        assertThat(clases.stream().filter(c -> c.getPackageName().startsWith("com.ferreteria.facturacion"))
                .flatMap(c -> c.getDirectDependenciesFromSelf().stream()))
                .anyMatch(d -> d.getTargetClass().getPackageName().startsWith("com.google.zxing"));
    }

    /**
     * BouncyCastle arma el pedido de certificado (PKCS#10) y la CA del simulador, donde el JDK no llega (rama 5): solo lo usa
     * {@code facturacion.credencial} (la regla no queda vacia), y nadie registra un proveedor de seguridad
     * ({@code Security.addProvider} ni {@code insertProviderAt}): todo corre con los proveedores del JDK.
     */
    @Test
    void soloLaCredencialDeArcaUsaBouncyCastleYNadieRegistraProveedores() {
        noClasses().that().resideOutsideOfPackage("com.ferreteria.facturacion.credencial..")
                .should().dependOnClassesThat(resideInAPackage("org.bouncycastle.."))
                .check(clases);
        noClasses().should().callMethod(java.security.Security.class, "addProvider", java.security.Provider.class)
                .orShould().callMethod(java.security.Security.class, "insertProviderAt", java.security.Provider.class,
                        int.class)
                .check(clases);
        assertThat(clases.stream().filter(c -> c.getPackageName().equals("com.ferreteria.facturacion.credencial"))
                .flatMap(c -> c.getDirectDependenciesFromSelf().stream()))
                .anyMatch(d -> d.getTargetClass().getPackageName().startsWith("org.bouncycastle"));
    }

    /**
     * {@code facturacion} toma la hora del {@code Clock} inyectado: no llama a {@code HoraArgentina.ahora()} ni
     * {@code hoy()}, ni a ningun {@code now(...)} de {@code java.time} que no reciba un {@code Clock}
     * ({@code LocalDateTime.now()}, {@code LocalDate.now()}, {@code Instant.now()}, y tambien {@code now(ZoneId)}).
     */
    @Test
    void facturacionNoLeeElRelojDelSistema() {
        noClasses().that().resideInAPackage("com.ferreteria.facturacion..")
                .should().callMethodWhere(new com.tngtech.archunit.base.DescribedPredicate<JavaMethodCall>(
                        "leer el reloj del sistema (HoraArgentina.ahora/hoy o un now() de java.time sin Clock)") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        if (call.getTargetOwner().isEquivalentTo(HoraArgentina.class)) {
                            return List.of("ahora", "hoy").contains(call.getName());
                        }
                        return call.getTargetOwner().getPackageName().equals("java.time") && call.getName().equals("now")
                                && !call.getTarget().getRawParameterTypes().stream().map(t -> t.getName()).toList()
                                        .equals(List.of(java.time.Clock.class.getName()));
                    }
                })
                .check(clases);
    }

    // ---- Facturacion, motor (CU-07, rama 2, S5 y S8): un unico escritor del registro fiscal y el simulador detras del puerto ----

    private static final List<Class<?>> REPOSITORIOS_DEL_REGISTRO_FISCAL = List.of(ComprobanteFiscalRepository.class,
            ComprobanteFiscalAlicuotaRepository.class, IntentoFiscalRepository.class);

    /**
     * El registro fiscal tiene un unico escritor, el motor ({@code PasosFiscales}, que hace cada paso en su transaccion
     * corta): nadie mas guarda, borra ni cambia (UPDATE con {@code @Modifying}, directo o por su {@code *Scoped}) el
     * registro, su desglose ni sus intentos, y las entidades no tienen setters (ni del CAE ni del estado). Los
     * repositorios no declaran borrados. Molde de {@code losAjustesSonInmutablesYSoloRegistroAjusteLosGuarda}.
     */
    @Test
    void elRegistroFiscalTieneUnUnicoEscritorYNoTieneSetters() {
        for (Class<?> entidad : List.of(ComprobanteFiscal.class, ComprobanteFiscalAlicuota.class, IntentoFiscal.class)) {
            noMethods().that().areDeclaredIn(entidad).should().haveNameMatching("set[A-Z].*").check(clases);
        }

        var escritores = clases.stream().flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> REPOSITORIOS_DEL_REGISTRO_FISCAL.stream()
                        .anyMatch(r -> call.getTargetOwner().isAssignableTo(r)))
                .filter(ModulosArchTest::escribe)
                .map(JavaMethodCall::getOriginOwner)
                .filter(origen -> REPOSITORIOS_DEL_REGISTRO_FISCAL.stream().noneMatch(origen::isEquivalentTo))
                .map(origen -> origen.getName())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(escritores).as("quien escribe el registro fiscal").containsExactly(PasosFiscales.class.getName());

        for (Class<?> repositorio : REPOSITORIOS_DEL_REGISTRO_FISCAL) {
            noMethods().that().areDeclaredIn(repositorio).should().haveNameMatching("(delete|remove).*").check(clases);
        }
    }

    /**
     * Una llamada que escribe: {@code save*}/{@code delete*}/{@code remove*}, un metodo {@code @Modifying} o un
     * {@code default} del repositorio que llama a uno {@code @Modifying} (los {@code *Scoped}).
     */
    private static boolean escribe(JavaMethodCall call) {
        if (call.getName().matches("(save|delete|remove).*")) {
            return true;
        }
        return call.getTarget().resolveMember().map(m -> esModifying(m) || m.getMethodCallsFromSelf().stream()
                .anyMatch(interna -> interna.getTarget().resolveMember().map(ModulosArchTest::esModifying).orElse(false)))
                .orElse(false);
    }

    private static boolean esModifying(com.tngtech.archunit.core.domain.JavaMethod metodo) {
        return metodo.isAnnotatedWith(org.springframework.data.jpa.repository.Modifying.class);
    }

    /**
     * Fuera de {@code facturacion.simulador} nadie depende de {@code SimuladorWsfe} ni de los repositorios del simulador: el
     * motor habla con el puerto {@code ServicioWsfe} (el adaptador se elige por el ambiente del registro). Y la llamada va
     * sin transaccion: el motor ({@code MotorFiscal}) no es transaccional, y el que tiene las transacciones
     * ({@code PasosFiscales}) no conoce el puerto.
     */
    @Test
    void nadieFueraDelSimuladorDependeDeElYElMotorUsaElPuerto() {
        com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass> delSimulador =
                new com.tngtech.archunit.base.DescribedPredicate<>("SimuladorWsfe o un repositorio del simulador") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaClass clase) {
                        return clase.isEquivalentTo(SimuladorWsfe.class)
                                || (clase.getPackageName().startsWith("com.ferreteria.facturacion.simulador")
                                        && clase.isAssignableTo(org.springframework.data.repository.Repository.class));
                    }
                };
        noClasses().that().resideOutsideOfPackage("com.ferreteria.facturacion.simulador..")
                .should().dependOnClassesThat(delSimulador)
                .check(clases);

        // El motor es MotorFiscal y sus clases anidadas (la atencion de una serie vive en MotorFiscal$Turno).
        String motor = Pattern.quote(MotorFiscal.class.getName()) + "(\\$.*)?";
        assertThat(clases.stream().filter(c -> c.getName().matches(motor))
                .flatMap(c -> c.getDirectDependenciesFromSelf().stream()))
                .as("el motor usa el puerto").anyMatch(d -> d.getTargetClass().isEquivalentTo(ServicioWsfe.class));
        assertThat(clases.get(PasosFiscales.class).getDirectDependenciesFromSelf())
                .as("los pasos con transaccion no conocen el puerto")
                .noneMatch(d -> d.getTargetClass().isEquivalentTo(ServicioWsfe.class));
        noClasses().that().haveNameMatching(motor)
                .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                .allowEmptyShould(false)
                .check(clases);
        noMethods().that().areDeclaredInClassesThat().haveNameMatching(motor)
                .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                .allowEmptyShould(false)
                .check(clases);
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
                return call.getTargetOwner().isEquivalentTo(entidad) && java.util.Set.of("venta", "cobro", "anulacion", "ajuste", "nc", "de")
                        .contains(call.getName()); // "ajuste": el renglón AJUSTE del libro (GDS-20); "nc": el de la nota de crédito (CU-08)
            }
        };
    }

    /**
     * (j, fábricas) Solo CuentaCorrienteService arma asientos del libro (venta, cobro, la {@code anulacion}, el ajuste y la
     * {@code nc} de la nota de crédito) e imputaciones: la fábrica es la otra mitad de «una sola puerta de escritura» (el {@code save} es la primera).
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
        // y las fábricas de la anulación y de la nota de crédito existen en la entidad
        assertThat(clases.get(MovimientoCuentaCliente.class).getMethods().stream().map(m -> m.getName())).contains("anulacion");
        assertThat(clases.get(MovimientoCuentaCliente.class).getMethods().stream().map(m -> m.getName())).contains("nc");
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
                // GDS-20: NumeradorAjusteCuentaRepository.incrementar es el UPSERT del numerador de ajustes (mismo molde que el
                // del recibo, que vive en ventas); no toca el libro ni las imputaciones.
                .containsExactlyInAnyOrder("ClienteRepository.sumarSaldo", "ImputacionRepository.desactivarDeRecibo",
                        "NumeradorAjusteCuentaRepository.incrementar");

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
     * (k-Ajuste, GDS-20) El ajuste manual de la cuenta es inmutable: la entidad no tiene setters, nadie borra con su
     * repositorio y solo {@code RegistroAjuste} lo guarda (junto con su renglón del libro, bajo el lock del cliente).
     */
    @Test
    void losAjustesSonInmutablesYSoloRegistroAjusteLosGuarda() {
        noMethods().that().areDeclaredIn(AjusteCuenta.class)
                .should().haveNameMatching("set[A-Z].*")
                .check(clases);

        noClasses().should().callMethodWhere(new com.tngtech.archunit.base.DescribedPredicate<JavaMethodCall>(
                "borrar o quitar ajustes con AjusteCuentaRepository") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isAssignableTo(AjusteCuentaRepository.class)
                        && call.getName().matches("(delete|remove).*");
            }
        }).check(clases);

        var guardadores = clases.stream().flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().isAssignableTo(AjusteCuentaRepository.class)
                        && call.getName().startsWith("save"))
                .map(JavaMethodCall::getOriginOwner)
                .map(origen -> origen.getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(guardadores).as("quién guarda ajustes").containsExactly(RegistroAjuste.class.getName());
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

    // ---- Extracto (corte 5 de cuenta-corriente-tramo-3b-1): lectura pura, sin locks, sin ventas ----

    private static final String EXTRACTO_CUENTA = "com.ferreteria.clientes.service.ExtractoCuenta";

    /**
     * (m) CC-079, CC-102: {@code ExtractoCuenta} solo LEE. No depende de los repositorios de escritura (imputaciones, el único
     * otro repositorio de la cuenta), no llama a nada que escriba o bloquee ({@code save*}, {@code delete*}, {@code bloquear*},
     * {@code sumarSaldo*}, {@code desactivar*}) en ningún repositorio, ni a la cuenta corriente que escribe (puerto o servicio), y
     * es una transacción {@code readOnly}. Falla si la clase no existe: la regla no puede pasar en vacío.
     */
    @Test
    void elExtractoSoloLeeYNoTomaLocks() {
        var extracto = clases.get(EXTRACTO_CUENTA);
        var dependencias = extracto.getDirectDependenciesFromSelf().stream()
                .map(d -> d.getTargetClass().getName()).collect(java.util.stream.Collectors.toSet());

        assertThat(dependencias).contains(ClienteRepository.class.getName(), MovimientoCuentaClienteRepository.class.getName());
        assertThat(dependencias).doesNotContain(ImputacionRepository.class.getName(), CuentaCorrienteService.class.getName(),
                CuentaCorrienteParaVenta.class.getName(), PUERTO_COBRANZA);

        var prohibidas = extracto.getMethodCallsFromSelf().stream()
                .filter(c -> c.getTargetOwner().getPackageName().equals("com.ferreteria.clientes.repository"))
                .map(JavaMethodCall::getName)
                .filter(n -> n.matches("(save|delete|remove|bloquear|sumarSaldo|desactivar|flush).*"))
                .toList();
        assertThat(prohibidas).as("llamadas de ExtractoCuenta que escriben o bloquean").isEmpty();

        var transaccional = extracto.getAnnotationOfType(org.springframework.transaction.annotation.Transactional.class);
        assertThat(transaccional.readOnly()).as("@Transactional(readOnly = true)").isTrue();
        assertThat(transaccional.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
    }

    /** (n) CC-072, CC-078: el extracto y sus DTOs de clientes no leen {@code ventas}: «anulado» sale del propio libro (EXISTS). */
    @Test
    void elExtractoNoLeeVentas() {
        classes().that().haveFullyQualifiedName(EXTRACTO_CUENTA)
                .or().haveSimpleNameStartingWith("FiltroExtracto")
                .or().haveSimpleNameStartingWith("ExtractoResponse")
                .or().haveSimpleNameStartingWith("LineaExtractoResponse")
                .should().onlyDependOnClassesThat(resideInAnyPackage("java..", "org.springframework..", "com.ferreteria.clientes..",
                        "com.ferreteria.common..", "com.ferreteria.tenant..", "jakarta..", "com.fasterxml.."))
                .allowEmptyShould(false)
                .check(clases);
    }

    // ---- Plataforma (super admin, C-108): no conoce el negocio y habla con auth solo por su puerto ----

    /** (a) R-109: el paquete {@code plataforma} no depende de ventas, catalogo, clientes, stock ni cotizacion. */
    @Test
    void plataformaNoDependeDeNegocio() {
        noClasses().that().resideInAPackage("com.ferreteria.plataforma..")
                .should().dependOnClassesThat(resideInAnyPackage("com.ferreteria.ventas..", "com.ferreteria.catalogo..",
                        "com.ferreteria.clientes..", "com.ferreteria.stock..", "com.ferreteria.cotizacion.."))
                .allowEmptyShould(false)
                .check(clases);
    }

    /** (b) {@code plataforma} usa {@code auth} solo por {@code auth.api}: nunca por sus entidades, repositorios o servicios. */
    @Test
    void plataformaUsaAuthSoloPorSuPuerto() {
        noClasses().that().resideInAPackage("com.ferreteria.plataforma..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.auth..")
                        .and(not(resideInAPackage("com.ferreteria.auth.api.."))))
                .allowEmptyShould(false)
                .check(clases);
    }

    /** (c) {@code auth.api} solo habla JDK y consigo mismo. */
    @Test
    void elPuertoDeAuthSoloDependeDelJdk() {
        classes().that().resideInAPackage("com.ferreteria.auth.api..")
                .should().onlyDependOnClassesThat(resideInAnyPackage("java..", "com.ferreteria.auth.api.."))
                .allowEmptyShould(false)
                .check(clases);
    }

    /** (d) Nadie fuera de {@code plataforma} depende de ella. */
    @Test
    void nadieDependeDePlataforma() {
        noClasses().that().resideOutsideOfPackage("com.ferreteria.plataforma..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.plataforma.."))
                .check(clases);
        assertThat(clases.containPackage("com.ferreteria.plataforma")).as("existe el paquete plataforma").isTrue();
    }
}
