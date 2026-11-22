package com.ferreteria.menu;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.menu.dto.Disposicion;
import com.ferreteria.menu.dto.Disposicion.Grupo;
import com.ferreteria.menu.service.DisposicionValidador;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisposicionValidadorTest {

    @Test
    void elCatalogoTieneLasMismas23PantallasYPermisosQueElFrontend() {
        assertThat(DisposicionValidador.pantallas()).isEqualTo(Map.ofEntries(
                Map.entry("presupuestos", false), Map.entry("remitos", false), Map.entry("caja", false),
                Map.entry("clientes", false), Map.entry("ventas", true), Map.entry("reportes", true),
                Map.entry("articulos", false), Map.entry("rubros", false), Map.entry("stock-bajo-minimo", true),
                Map.entry("etiquetas", true), Map.entry("controles-de-datos", true), Map.entry("proveedores", false),
                Map.entry("compras", true), Map.entry("importaciones", true), Map.entry("actualizacion-masiva", true),
                Map.entry("margen-y-redondeo", true), Map.entry("cotizacion", true), Map.entry("formas-de-pago", true),
                Map.entry("consumidor-final", true), Map.entry("datos-de-la-ferreteria", true),
                Map.entry("facturacion", true), Map.entry("usuarios", true), Map.entry("personalizar-menu", true)));
    }

    @Test
    void permiteDiezGruposPeroNoOnce() {
        List<Grupo> grupos = IntStream.range(0, 11).mapToObj(i -> grupo("grupo-" + i, "Grupo " + i)).toList();
        var maxima = new Disposicion(grupos.subList(0, 10), List.of());
        assertThat(DisposicionValidador.validar(maxima, Rol.DUENO)).isEqualTo(maxima);
        rechaza(new Disposicion(grupos, List.of()), "Podés tener hasta 10 grupos.");
    }

    @Test
    void recortaElNombreYConservaElOrdenYLosTonosAdmitidos() {
        var grupos = List.of(
                new Grupo("a".repeat(40), "  " + "x".repeat(30) + "  ", "ventas", List.of("caja", "presupuestos")),
                new Grupo("0", " A ", "articulos", List.of("articulos")),
                new Grupo("-", "B", "compras", List.of()),
                new Grupo("a-b", "C", "precios", List.of()),
                new Grupo("d", "D", "configuracion", List.of()));
        var resultado = DisposicionValidador.validar(new Disposicion(grupos, List.of("remitos", "clientes")), Rol.VENDEDOR);
        assertThat(resultado).isEqualTo(new Disposicion(List.of(
                new Grupo("a".repeat(40), "x".repeat(30), "ventas", List.of("caja", "presupuestos")),
                new Grupo("0", "A", "articulos", List.of("articulos")),
                grupos.get(2), grupos.get(3), grupos.get(4)), List.of("remitos", "clientes")));
    }

    @Test
    void nombreVacio() {
        rechaza(conGrupos(grupo("ventas", " \t ")), "Poné un nombre para el grupo.");
    }

    @Test
    void nombreDemasiadoLargo() {
        rechaza(conGrupos(grupo("ventas", "x".repeat(31))), "El nombre del grupo puede tener hasta 30 caracteres.");
    }

    @Test
    void nombreRepetidoIgnoraMayusculasYEspaciosExternos() {
        rechaza(conGrupos(grupo("primero", " Ventas "), grupo("segundo", "vENTAS")), "Ya hay un grupo con ese nombre.");
    }

    @Test
    void idFueraDelFormato() {
        for (String id : List.of("", "A", "a_b", "a b", "á", "a".repeat(41))) {
            rechaza(conGrupos(grupo(id, "Ventas")), "El identificador del grupo no es válido.");
        }
    }

    @Test
    void idMostradorReservadoParaElDestinoFijo() {
        rechaza(conGrupos(grupo("mostrador", "Diario", "caja")), "El identificador del grupo no es válido.");
    }

    @Test
    void idRepetido() {
        rechaza(conGrupos(grupo("ventas", "Uno"), grupo("ventas", "Dos")), "Ya hay un grupo con ese identificador.");
    }

    @Test
    void tonoFueraDeLaPaleta() {
        rechaza(conGrupos(new Grupo("ventas", "Ventas", "azul", List.of())), "Elegí un color válido para el grupo.");
    }

    @Test
    void claveDesconocidaOQueNoSePersonalizaEnCualquierLista() {
        for (String clave : List.of("inventada", "mostrador")) {
            rechaza(conGrupos(grupo("ventas", "Ventas", clave)), "Hay una opción de menú que no existe.");
            rechaza(new Disposicion(List.of(), List.of(clave)), "Hay una opción de menú que no existe.");
        }
    }

    @Test
    void claveRepetidaEnGrupos() {
        rechaza(conGrupos(grupo("uno", "Uno", "caja"), grupo("dos", "Dos", "caja")),
                "Ya pusiste esa opción en el menú.");
        rechaza(conGrupos(grupo("uno", "Uno", "caja", "caja")), "Ya pusiste esa opción en el menú.");
    }

    @Test
    void claveEnGrupoYEnOcultasORepetidaEnOcultas() {
        rechaza(new Disposicion(List.of(grupo("ventas", "Ventas", "caja")), List.of("caja")),
                "Ya pusiste esa opción en el menú.");
        rechaza(new Disposicion(List.of(), List.of("caja", "caja")), "Ya pusiste esa opción en el menú.");
    }

    @Test
    void vendedorNoPuedeIncluirNiOcultarPantallasDeDueno() {
        for (Disposicion invalida : List.of(conGrupos(grupo("ventas", "Ventas", "ventas")),
                new Disposicion(List.of(), List.of("usuarios")))) {
            assertThatThrownBy(() -> DisposicionValidador.validar(invalida, Rol.VENDEDOR))
                    .isExactlyInstanceOf(BadRequestException.class)
                    .hasMessage("El menú de un Vendedor no puede incluir opciones de solo Dueño.");
            assertThat(DisposicionValidador.validar(invalida, Rol.DUENO)).isEqualTo(invalida);
        }
    }

    @Test
    void duenoPuedeMoverElEditorPeroNoOcultarlo() {
        var visible = conGrupos(grupo("propio", "Diario", "personalizar-menu"));
        assertThat(DisposicionValidador.validar(visible, Rol.DUENO)).isEqualTo(visible);
        var oculto = new Disposicion(List.of(), List.of("personalizar-menu"));
        for (Rol rol : List.of(Rol.DUENO, Rol.VENDEDOR)) {
            assertThatThrownBy(() -> DisposicionValidador.validar(oculto, rol))
                    .isExactlyInstanceOf(BadRequestException.class)
                    .hasMessage("No se puede ocultar «Personalizar menú».");
        }
        assertThatThrownBy(() -> DisposicionValidador.validar(visible, Rol.VENDEDOR))
                .isExactlyInstanceOf(BadRequestException.class)
                .hasMessage("El menú de un Vendedor no puede incluir opciones de solo Dueño.");
    }

    @Test
    void rechazaListasAusentesYElementosNulos() {
        for (Disposicion incompleta : List.of(new Disposicion(null, List.of()), new Disposicion(List.of(), null),
                new Disposicion(Collections.singletonList(null), List.of()),
                conGrupos(new Grupo("ventas", "Ventas", "ventas", null)),
                new Disposicion(List.of(), Collections.singletonList(null)))) {
            rechaza(incompleta, "La disposición del menú no está completa.");
        }
    }

    private static void rechaza(Disposicion disposicion, String mensaje) {
        assertThatThrownBy(() -> DisposicionValidador.validar(disposicion, Rol.DUENO))
                .isExactlyInstanceOf(BadRequestException.class).hasMessage(mensaje);
    }

    private static Disposicion conGrupos(Grupo... grupos) {
        return new Disposicion(List.of(grupos), List.of());
    }

    private static Grupo grupo(String id, String rotulo, String... claves) {
        return new Grupo(id, rotulo, "ventas", List.of(claves));
    }
}
