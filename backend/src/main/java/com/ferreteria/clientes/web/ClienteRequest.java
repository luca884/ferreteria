package com.ferreteria.clientes.web;

import com.ferreteria.clientes.model.CondicionIvaCliente;
import com.ferreteria.clientes.model.TipoDocumento;
import com.ferreteria.clientes.service.DatosCliente;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lo que manda el mostrador o la pantalla de clientes al crear o editar (CL-003). La ferretería sale del JWT. Acá solo
 * se valida la forma; el documento (que tipo y número vayan juntos, el dígito verificador) y el CUIT y el domicilio que
 * piden Responsable Inscripto, Monotributista o Exento (CU-11, D4) los valida el servicio.
 */
public record ClienteRequest(
        @NotBlank(message = "La razón social es obligatoria.")
        @Size(max = 120, message = "La razón social admite hasta 120 caracteres.")
        String razonSocial,

        TipoDocumento tipoDocumento,

        @Size(max = 20, message = "El número de documento es demasiado largo.")
        String numeroDocumento,

        /** Ausente es Consumidor Final. */
        CondicionIvaCliente condicionIva,

        @Size(max = 200, message = "El domicilio admite hasta 200 caracteres.")
        String domicilio,

        @Size(max = 30, message = "El teléfono admite hasta 30 caracteres.")
        String telefono,

        @Size(max = 30, message = "El WhatsApp admite hasta 30 caracteres.")
        String whatsapp,

        @Email(message = "El correo no es válido.")
        @Size(max = 120, message = "El correo admite hasta 120 caracteres.")
        String email) {

    public DatosCliente aDatos() {
        return new DatosCliente(razonSocial, tipoDocumento, numeroDocumento, condicionIva, domicilio, telefono, whatsapp,
                email);
    }
}
