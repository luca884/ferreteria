import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import {
  Component,
  ElementRef,
  Injector,
  OnInit,
  WritableSignal,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { descargar } from '../../core/archivos/descarga';
import { AuthService } from '../../core/auth/auth.service';
import { cuitConGuiones } from '../../core/formato/cuit';
import { formatearFecha, formatearFechaHora } from '../../core/formato/fecha-hora';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { MotivoDeAccion, Motivos, ariaDe } from '../../shared/motivo-de-accion';
import { AMBIENTE_ARCA_LABELS } from '../admin-ferreterias/ferreteria-admin.models';
import { MENSAJE_CERTIFICADO_GRANDE, TAMANO_MAXIMO_CERTIFICADO, leerCertificado } from './archivo-certificado';
import {
  ConfiguracionFacturacion,
  CredencialArca,
  ESTADO_CREDENCIAL_LABELS,
  PASO_PRUEBA_LABELS,
  PedidoCertificado,
  PruebaConexion,
} from './facturacion.models';
import { FacturacionService } from './facturacion.service';

/** S5: lo que se explica al intentar subir o firmar un certificado antes de generar el pedido (el texto de la pantalla). */
const MOTIVO_SIN_PEDIDO = 'Primero generá el pedido de certificado.';
/** Las acciones de la sección del certificado que pueden estar apagadas por una regla de negocio (S5). */
type Accion = 'subirCertificado' | 'firmarSimulador';

/** Las tres pantallas del celular para obtener o renovar el certificado (artboard 05): la guía, el pedido y subir el .crt. */
type PasoCertificado = 'inicio' | 'pedido' | 'subir';
const TITULOS_DE_PASO: Record<PasoCertificado, string> = {
  inicio: 'Facturación',
  pedido: 'Pedido de certificado',
  subir: 'Subir certificado',
};

/** El detalle que acompaña al estado del certificado; null si está vigente y no hay nada que decir. */
function detalleDelEstado(credencial: CredencialArca): string | null {
  const vence = formatearFecha(credencial.vigenteHasta);
  switch (credencial.estado) {
    case 'VIGENTE':
      return null;
    case 'POR_VENCER': {
      const dias = credencial.diasParaVencer;
      return `Vence el ${vence} (en ${dias} ${dias === 1 ? 'día' : 'días'}): renovalo.`;
    }
    case 'VENCIDA':
      return `Venció el ${vence}: no se puede facturar con ARCA hasta que subas un certificado nuevo.`;
    case 'ILEGIBLE':
      return (
        'No se puede leer la clave guardada de este certificado, así que no se puede facturar con ARCA. ' +
        'Hace falta un certificado nuevo: generá un pedido y subí el certificado que te dé ARCA.'
      );
  }
}

/**
 * Configuración › Facturación (solo el Dueño, CU-01 pasos 2 a 4): el ambiente y el punto de venta, el certificado de ARCA y
 * su vencimiento, cómo obtenerlo y «Probar conexión». El sistema genera la clave y el pedido (CSR); la clave privada nace
 * cifrada en el servidor y nunca pasa por acá (D8): el Dueño baja el .csr, lo lleva a ARCA y sube el .crt que le dan.
 *
 * En el celular (movil-sectores T4, S2 y S7) la configuración son tarjetas y obtener o renovar el certificado son tres
 * pantallas completas con «Volver» y la acción fija abajo. Es la misma sección con el mismo estado de hoy: `certificadoAbierto`
 * y `paso` sólo los lee el CSS del celular (todas las pantallas están siempre en el DOM; el escritorio las ve juntas).
 */
@Component({
  selector: 'app-facturacion',
  imports: [ReactiveFormsModule, NgTemplateOutlet, BarraAcciones, Confirmacion],
  templateUrl: './facturacion.html',
  styleUrl: './facturacion.css',
})
export class PaginaFacturacion implements OnInit {
  private readonly service = inject(FacturacionService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  protected readonly auth = inject(AuthService);

  protected readonly ambientes = AMBIENTE_ARCA_LABELS;
  protected readonly estados = ESTADO_CREDENCIAL_LABELS;
  protected readonly pasos = PASO_PRUEBA_LABELS;
  protected readonly fecha = formatearFecha;
  protected readonly fechaHora = formatearFechaHora;
  protected readonly cuitConGuiones = cuitConGuiones;
  protected readonly detalleDelEstado = detalleDelEstado;

  protected readonly formPuntoVenta = new FormGroup({ puntoVenta: new FormControl<number | null>(null) });
  protected readonly cuitPedido = new FormControl('', { nonNullable: true });

  protected readonly config = signal<ConfiguracionFacturacion | null>(null);
  /** Lo que dice el mostrador (la regla real del servidor); null si no se pudo preguntar: queda la configuración. */
  protected readonly emite = signal<boolean | null>(null);
  /** El campo del CUIT del pedido solo existe fuera de producción y del simulador: lo que se manda es lo que se ve. */
  protected readonly pideCuit = computed(() => {
    const c = this.config();
    return c !== null && c.ambiente !== 'PRODUCCION' && !c.simulador;
  });
  protected readonly cargando = signal(true);
  protected readonly cargaFallida = signal(false);
  /** Hay un pedido al servidor en curso: las acciones esperan. */
  protected readonly ocupado = signal(false);
  protected readonly exito = signal<string | null>(null);
  /** Lo que el servidor rechazó sin ser de un campo (409, red): un aviso arriba de la pantalla. */
  protected readonly error = signal<string | null>(null);
  protected readonly errorPuntoVenta = signal<string | null>(null);
  protected readonly errorCuit = signal<string | null>(null);
  protected readonly errorCertificado = signal<string | null>(null);
  /** Hay un pedido pendiente y el Dueño pidió otro: se confirma antes de reemplazarlo. */
  protected readonly confirmandoPedido = signal(false);
  /** «Probar conexión» en curso: además de `ocupado`, el botón dice «Probando…». */
  protected readonly probando = signal(false);
  /** El resultado de la última prueba de conexión; null antes de probar o si el servidor no la dejó correr. */
  protected readonly prueba = signal<PruebaConexion | null>(null);
  /** Por qué no se pudo probar (409: falta el ambiente, el punto de venta o el certificado; red), junto al resultado. */
  protected readonly errorPrueba = signal<string | null>(null);

  /** El flujo del certificado está abierto a pantalla completa (sólo el celular lo abre: el botón de la tarjeta es suyo). */
  protected readonly certificadoAbierto = signal(false);
  /** La pantalla del flujo que se ve en el celular. */
  protected readonly paso = signal<PasoCertificado>('inicio');
  protected readonly tituloPaso = computed(() => TITULOS_DE_PASO[this.paso()]);

  /** El último pedido generado o bajado: descargarlo o copiarlo otra vez no lo vuelve a pedir. */
  private readonly pedidoBajado = signal<PedidoCertificado | null>(null);
  protected readonly hayPedido = computed(() => this.config()?.pedido != null || this.pedidoBajado() !== null);
  /** Por qué las acciones del certificado no se pueden usar: sin pedido no hay con qué (S5). */
  protected readonly motivos = computed<Motivos<Accion>>(() => {
    const motivo = this.hayPedido() ? null : MOTIVO_SIN_PEDIDO;
    return { subirCertificado: motivo, firmarSimulador: motivo };
  });
  /** El motivo a la vista de la última acción que no se pudo usar, o null. Se anuncia con role="status" (S5). */
  protected readonly motivo = new MotivoDeAccion(() => this.motivos());
  /** Para la plantilla: 'true' apaga el botón, null lo deja prendido. */
  protected readonly ariaDe = ariaDe;
  /** El texto de la línea que explica por qué no hay con qué, uno solo para la línea y para el motivo. */
  protected readonly MOTIVO_SIN_PEDIDO = MOTIVO_SIN_PEDIDO;

  ngOnInit(): void {
    if (this.auth.esDueno()) this.cargar();
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.cargaFallida.set(false);
    this.service.configuracion().subscribe({
      next: (config) => {
        this.mostrar(config);
        this.cargando.set(false);
      },
      error: () => {
        this.cargando.set(false);
        this.cargaFallida.set(true);
      },
    });
  }

  /** «Renovar certificado» / «Obtener certificado» de la tarjeta (sólo el celular): abre el flujo en la primera pantalla. */
  protected abrirCertificado(): void {
    this.paso.set('inicio');
    this.certificadoAbierto.set(true);
    this.enfocar('section.pedido .ficha-barra .ficha-volver');
  }

  /** Cierra el flujo sin tocar nada de lo hecho y devuelve el foco al botón de la tarjeta. */
  private cerrarCertificado(): void {
    if (!this.certificadoAbierto()) return;
    this.certificadoAbierto.set(false);
    this.paso.set('inicio');
    this.enfocar('.abrir-certificado');
  }

  /** La flecha del flujo: retrocede de a una pantalla y en la primera lo cierra. */
  protected volverDelCertificado(): void {
    switch (this.paso()) {
      case 'subir':
        this.irAPaso('pedido');
        break;
      case 'pedido':
        this.irAPaso('inicio');
        break;
      case 'inicio':
        this.cerrarCertificado();
        break;
    }
  }

  /** Cambia de pantalla del flujo: la nueva se ve desde arriba y el foco pasa a la flecha (el botón que se tocó ya no está). */
  protected irAPaso(paso: PasoCertificado): void {
    this.paso.set(paso);
    afterNextRender(
      () => {
        const seccion = this.host.nativeElement.querySelector<HTMLElement>('section.pedido');
        if (seccion) seccion.scrollTop = 0;
      },
      { injector: this.injector },
    );
    // Sólo con el flujo abierto (en el escritorio, generar el pedido no mueve el foco de donde está).
    if (this.certificadoAbierto()) this.enfocar('section.pedido .ficha-barra .ficha-volver');
  }

  /** Después de pintar, enfoca el primer elemento que coincida. */
  private enfocar(selector: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }

  protected guardarPuntoVenta(): void {
    if (this.ocupado()) return;
    this.limpiar();
    this.ocupado.set(true);
    this.service.guardarPuntoVenta(this.formPuntoVenta.controls.puntoVenta.value).subscribe({
      next: (config) => this.terminar(config, 'Se guardó el punto de venta.'),
      error: (err: unknown) => this.alFallar(err, this.errorPuntoVenta, 'No se pudo guardar el punto de venta.'),
    });
  }

  /** «Generar pedido de certificado»: si ya hay uno pendiente, primero se confirma (el nuevo lo reemplaza). */
  protected pedirGenerar(): void {
    if (this.ocupado()) return;
    if (this.config()?.pedido) this.confirmandoPedido.set(true);
    else this.generarPedido();
  }

  protected generarPedido(): void {
    this.confirmandoPedido.set(false);
    this.limpiar();
    // En producción y en el simulador el campo no está (el pedido lleva la CUIT de la ferretería): no se manda lo que hubiera quedado escrito.
    const conCuit = this.pideCuit();
    const cuit = conCuit ? this.cuitPedido.value.replace(/[\s.-]/g, '') : '';
    this.ocupado.set(true);
    this.service.generarPedido(cuit === '' ? null : cuit).subscribe({
      next: (pedido) => {
        this.ocupado.set(false);
        this.pedidoBajado.set(pedido);
        this.exito.set('Se generó el pedido de certificado: bajalo y subilo en ARCA.');
        // En el celular, con el pedido hecho el flujo pasa a la pantalla del pedido (donde se baja o se copia).
        this.irAPaso('pedido');
        // La respuesta trae solo el CSR: la configuración se vuelve a leer para mostrar el pedido pendiente.
        this.service.configuracion().subscribe({ next: (config) => this.mostrar(config), error: () => undefined });
      },
      error: (err: unknown) =>
        this.alFallar(err, conCuit ? this.errorCuit : null, 'No se pudo generar el pedido de certificado.'),
    });
  }

  protected descargarPedido(): void {
    this.conPedido((pedido) => {
      const archivo = new Blob([pedido.csr], { type: 'application/pkcs10' });
      descargar(new HttpResponse({ body: archivo }), pedido.archivo);
    });
  }

  protected copiarPedido(): void {
    this.conPedido((pedido) => {
      // Sin portapapeles (página sin HTTPS) writeText no existe: el error cae en el mismo aviso.
      Promise.resolve()
        .then(() => navigator.clipboard.writeText(pedido.csr))
        .then(
          () => this.exito.set('Se copió el pedido.'),
          () => this.error.set('No se pudo copiar: bajá el archivo.'),
        );
    });
  }

  /** «Subir certificado (.crt)» abre el selector de archivos; sin pedido, avisa el motivo (S5). */
  protected pedirCertificado(entrada: HTMLInputElement): void {
    if (this.motivo.mostrar('subirCertificado')) return;
    this.motivo.limpiar();
    entrada.click();
  }

  /** «Subir certificado (.crt)» abre el selector de archivos; al elegir uno, se sube. */
  protected async alElegirCertificado(entrada: HTMLInputElement): Promise<void> {
    const archivo = entrada.files?.[0];
    // Vaciarlo permite volver a elegir el mismo archivo después de un rechazo.
    entrada.value = '';
    if (!archivo || this.ocupado()) return;
    this.limpiar();
    if (archivo.size > TAMANO_MAXIMO_CERTIFICADO) {
      this.errorCertificado.set(MENSAJE_CERTIFICADO_GRANDE);
      return;
    }
    this.ocupado.set(true);
    let certificado: string;
    try {
      certificado = await leerCertificado(archivo);
    } catch {
      this.ocupado.set(false);
      this.errorCertificado.set('No se pudo leer el archivo.');
      return;
    }
    this.service.subirCertificado(certificado).subscribe({
      next: (config) => {
        this.pedidoBajado.set(null);
        this.cerrarCertificado();
        this.terminar(config, 'Se cargó el certificado de ARCA.');
      },
      error: (err: unknown) => this.alFallar(err, this.errorCertificado, 'No se pudo cargar el certificado.'),
    });
  }

  protected firmarConSimulador(): void {
    // S5: sin pedido pendiente el botón avisa el motivo en vez de quedar apagado sin explicación.
    if (this.motivo.mostrar('firmarSimulador')) return;
    if (this.ocupado()) return;
    this.motivo.limpiar();
    this.limpiar();
    this.ocupado.set(true);
    this.service.firmarConSimulador().subscribe({
      next: (config) => {
        this.pedidoBajado.set(null);
        this.cerrarCertificado();
        this.terminar(config, 'El simulador firmó el pedido: el certificado quedó activo.');
      },
      error: (err: unknown) => this.alFallar(err, null, 'No se pudo firmar el pedido con el simulador.'),
    });
  }

  /** «Probar conexión» (CU-01 paso 4): muestra cada paso; un OK completo levanta el bloqueo de ARCA. */
  protected probarConexion(): void {
    if (this.ocupado()) return;
    this.limpiar();
    this.prueba.set(null);
    this.errorPrueba.set(null);
    this.ocupado.set(true);
    this.probando.set(true);
    this.service.probarConexion().subscribe({
      next: (prueba) => {
        this.terminarPrueba();
        this.prueba.set(prueba);
        // El aviso del bloqueo viene en la configuración: se vuelve a leer para que desaparezca.
        if (prueba.ok) this.service.configuracion().subscribe({ next: (config) => this.mostrar(config), error: () => undefined });
      },
      error: (err: unknown) => {
        this.terminarPrueba();
        this.errorPrueba.set(mensajeDe(err, 'No se pudo probar la conexión.'));
      },
    });
  }

  private terminarPrueba(): void {
    this.ocupado.set(false);
    this.probando.set(false);
  }

  /** Usa el pedido que ya se tiene o lo baja del servidor (un pedido pendiente de otra sesión). */
  private conPedido(hacer: (pedido: PedidoCertificado) => void): void {
    if (this.ocupado()) return;
    this.limpiar();
    const bajado = this.pedidoBajado();
    if (bajado) {
      hacer(bajado);
      return;
    }
    this.ocupado.set(true);
    this.service.pedido().subscribe({
      next: (pedido) => {
        this.ocupado.set(false);
        this.pedidoBajado.set(pedido);
        hacer(pedido);
      },
      error: (err: unknown) => this.alFallar(err, null, 'No se pudo bajar el pedido de certificado.'),
    });
  }

  private terminar(config: ConfiguracionFacturacion, mensaje: string): void {
    this.ocupado.set(false);
    this.mostrar(config);
    this.exito.set(mensaje);
  }

  /** Un 400 va en su campo; lo demás (409, red) es un aviso de la pantalla con el mensaje del servidor. */
  private alFallar(err: unknown, campo: WritableSignal<string | null> | null, porDefecto: string): void {
    this.ocupado.set(false);
    if (campo && err instanceof HttpErrorResponse && err.status === 400) {
      campo.set(erroresDeCampos(err)[0] ?? mensajeDe(err, porDefecto));
    } else {
      this.error.set(mensajeDe(err, porDefecto));
    }
  }

  private limpiar(): void {
    this.exito.set(null);
    this.error.set(null);
    this.errorPuntoVenta.set(null);
    this.errorCuit.set(null);
    this.errorCertificado.set(null);
  }

  private mostrar(config: ConfiguracionFacturacion): void {
    this.config.set(config);
    this.formPuntoVenta.reset({ puntoVenta: config.puntoVenta });
    // Cada cambio de la configuración (certificado, punto de venta, prueba de conexión) puede cambiar si se emite.
    this.service.emision().subscribe({
      next: (emision) => this.emite.set(emision.factura),
      error: () => this.emite.set(null),
    });
  }
}
