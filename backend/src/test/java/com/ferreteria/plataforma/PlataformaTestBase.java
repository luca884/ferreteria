package com.ferreteria.plataforma;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.ferreteria.auth.UsuariosTestBase;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Base de los tests del super admin: arma altas con CUIT válido y único, y entra como admin@demo.local. */
abstract class PlataformaTestBase extends UsuariosTestBase {

    static final String BASE = "/api/admin/ferreterias";
    private static final int[] PESOS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
    private static final AtomicInteger SECUENCIA = new AtomicInteger(40000000);

    protected String tokenSuperAdmin() throws Exception {
        return login("admin@demo.local");
    }

    /** Un CUIT 30-XXXXXXXX-D con el verificador correcto y que ningún otro test usa, escrito con guiones. */
    protected static String cuitNuevo() {
        String base = "30" + String.format("%08d", SECUENCIA.incrementAndGet());
        int suma = 0;
        for (int i = 0; i < PESOS.length; i++) {
            suma += (base.charAt(i) - '0') * PESOS[i];
        }
        int dv = 11 - suma % 11;
        if (dv == 11) {
            dv = 0;
        }
        if (dv == 10) {
            return cuitNuevo();
        }
        return base.substring(0, 2) + "-" + base.substring(2) + "-" + dv;
    }

    protected static String sinGuiones(String cuit) {
        return cuit.replace("-", "");
    }

    /** Cuerpo de alta válido; los tests lo rompen de a un campo. */
    protected ObjectNode altaValida(String cuit, String emailDueno) {
        ObjectNode cuerpo = objectMapper.createObjectNode();
        cuerpo.put("razonSocial", unico("Ferretería"));
        cuerpo.put("cuit", cuit);
        cuerpo.put("condicionIva", "RESPONSABLE_INSCRIPTO");
        ObjectNode dueno = cuerpo.putObject("dueno");
        dueno.put("nombre", "Dueña Nueva");
        dueno.put("email", emailDueno);
        dueno.put("password", "demo1234");
        return cuerpo;
    }

    protected ResultActions alta(String token, ObjectNode cuerpo) throws Exception {
        return postJson(BASE, token, cuerpo.toString());
    }

    protected ResultActions patchJson(String url, String token, String json) throws Exception {
        return mvc.perform(patch(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
