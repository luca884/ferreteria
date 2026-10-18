package com.ferreteria.clientes.service;

import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.CondicionIvaCliente;
import com.ferreteria.clientes.model.DocumentoCliente;
import com.ferreteria.clientes.model.DocumentoInvalidoException;
import com.ferreteria.clientes.model.TipoDocumento;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Los clientes de la ferretería (CL-001 a CL-003). El documento se valida y se guarda solo con dígitos; es único por
 * ferretería entre los cargados. Un cliente de otra ferretería da 404, igual que uno que no existe. No se borran:
 * se desactivan.
 */
@Service
public class ClienteService {

    static final int TAMANO_MAXIMO = 100;
    private static final String NO_ENCONTRADO = "Cliente no encontrado";
    private static final String DOCUMENTO_REPETIDO = "Ya hay un cliente con ese documento.";

    private final ClienteRepository repositorio;

    public ClienteService(ClienteRepository repositorio) {
        this.repositorio = repositorio;
    }

    /** Por razón social (contiene) o documento (empieza con); sin {@code q}, todos. Ordenados por razón social. */
    @Transactional(readOnly = true)
    public Page<Cliente> buscar(String q, boolean soloActivos, int page, int size) {
        PageRequest pagina = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO));
        String texto = q == null ? "" : q.trim();
        if (texto.isEmpty()) {
            return repositorio.todosScoped(soloActivos, pagina);
        }
        String digitos = texto.replaceAll("[\\s.\\-]", "");
        return repositorio.buscarScoped("%" + escapar(texto) + "%", digitos.matches("\\d+") ? digitos : "", soloActivos,
                pagina);
    }

    @Transactional(readOnly = true)
    public Cliente obtener(Long id) {
        return repositorio.findScoped(id).orElseThrow(() -> new ResourceNotFoundException(NO_ENCONTRADO));
    }

    @Transactional
    public Cliente crear(DatosCliente datos) {
        Documento documento = documentoDe(datos);
        if (documento.numero() != null && repositorio.existeDocumentoScoped(documento.numero())) {
            throw new ConflictException(DOCUMENTO_REPETIDO);
        }
        Cliente cliente = Cliente.de(TenantContext.requireFerreteriaId(), datos.razonSocial().trim(), documento.tipo(),
                documento.numero(), condicionDe(datos), vacioANulo(datos.domicilio()), vacioANulo(datos.telefono()),
                vacioANulo(datos.whatsapp()), vacioANulo(datos.email()));
        return guardar(cliente);
    }

    @Transactional
    public Cliente actualizar(Long id, DatosCliente datos) {
        Cliente cliente = obtener(id);
        Documento documento = documentoDe(datos);
        if (documento.numero() != null && repositorio.existeOtroConDocumentoScoped(documento.numero(), id)) {
            throw new ConflictException(DOCUMENTO_REPETIDO);
        }
        cliente.actualizar(datos.razonSocial().trim(), documento.tipo(), documento.numero(), condicionDe(datos),
                vacioANulo(datos.domicilio()), vacioANulo(datos.telefono()), vacioANulo(datos.whatsapp()),
                vacioANulo(datos.email()));
        return guardar(cliente);
    }

    @Transactional
    public Cliente cambiarActivo(Long id, boolean activo) {
        Cliente cliente = obtener(id);
        cliente.cambiarActivo(activo);
        return cliente;
    }

    private record Documento(TipoDocumento tipo, String numero) {}

    /** El tipo y el número van juntos o no va ninguno; si van, el número se valida y queda solo con dígitos. */
    private Documento documentoDe(DatosCliente datos) {
        String numero = vacioANulo(datos.numeroDocumento());
        if (datos.tipoDocumento() == null && numero == null) {
            return new Documento(null, null);
        }
        if (datos.tipoDocumento() == null) {
            throw new DatosInvalidosException(Map.of("tipoDocumento", "Elegí el tipo de documento."));
        }
        if (numero == null) {
            throw new DatosInvalidosException(Map.of("numeroDocumento", "Falta el número de documento."));
        }
        try {
            return new Documento(datos.tipoDocumento(), DocumentoCliente.normalizar(datos.tipoDocumento(), numero));
        } catch (DocumentoInvalidoException e) {
            throw new DatosInvalidosException(Map.of("numeroDocumento", e.getMessage()));
        }
    }

    private static CondicionIvaCliente condicionDe(DatosCliente datos) {
        return datos.condicionIva() != null ? datos.condicionIva() : CondicionIvaCliente.CONSUMIDOR_FINAL;
    }

    /** Si dos pedidos cargan el mismo documento a la vez, el segundo pierde contra la clave única: también es 409. */
    private Cliente guardar(Cliente cliente) {
        try {
            return repositorio.saveAndFlush(cliente);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(DOCUMENTO_REPETIDO);
        }
    }

    private static String vacioANulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    /** Los comodines de LIKE escritos por el usuario valen como texto. */
    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
