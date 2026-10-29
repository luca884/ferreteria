import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Component, OnInit, WritableSignal, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { descargar } from '../../core/archivos/descarga';
import { AuthService } from '../../core/auth/auth.service';
import { formatearFecha, formatearFechaHora } from '../../core/formato/fecha-hora';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { AMBIENTE_ARCA_LABELS } from '../admin-ferreterias/ferreteria-admin.models';
import { MENSAJE_CERTIFICADO_GRANDE, TAMANO_MAXIMO_CERTIFICADO, leerCertificado } from './archivo-certificado';
import {
  ConfiguracionFacturacion,
  CredencialArca,
  ESTADO_CREDENCIAL_LABELS,
  PedidoCertificado,
} from './facturacion.models';
import { FacturacionService } from './facturacion.service';

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
 * Configuración › Facturación (solo el Dueño, CU-01 pasos 2 y 3): el ambiente y el punto de venta, el certificado de ARCA y
 * su vencimiento, y cómo obtenerlo. El sistema genera la clave y el pedido (CSR); la clave privada nace cifrada en el
 * servidor y nunca pasa por acá (D8): el Dueño baja el .csr, lo lleva a ARCA y sube el .crt que le dan.
 */
@Component({
  selector: 'app-facturacion',
  imports: [ReactiveFormsModule, BarraAcciones, Confirmacion],
  templateUrl: './facturacion.html',
  styleUrl: './facturacion.css',
})
export class PaginaFacturacion implements OnInit {
  private readonly service = inject(FacturacionService);
  protected readonly auth = inject(AuthService);

  protected readonly ambientes = AMBIENTE_ARCA_LABELS;
  protected readonly estados = ESTADO_CREDENCIAL_LABELS;
  protected readonly fecha = formatearFecha;
  protected readonly fechaHora = formatearFechaHora;
  protected readonly detalleDelEstado = detalleDelEstado;

  protected readonly formPuntoVenta = new FormGroup({ puntoVenta: new FormControl<number | null>(null) });
  protected readonly cuitPedido = new FormControl('', { nonNullable: true });

  protected readonly config = signal<ConfiguracionFacturacion | null>(null);
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

  /** El último pedido generado o bajado: descargarlo o copiarlo otra vez no lo vuelve a pedir. */
  private readonly pedidoBajado = signal<PedidoCertificado | null>(null);
  protected readonly hayPedido = computed(() => this.config()?.pedido != null || this.pedidoBajado() !== null);

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
    // En producción el campo no está (el pedido lleva la CUIT de la ferretería): no se manda lo que hubiera quedado escrito.
    const conCuit = this.config()?.ambiente !== 'PRODUCCION';
    const cuit = conCuit ? this.cuitPedido.value.replace(/[\s.-]/g, '') : '';
    this.ocupado.set(true);
    this.service.generarPedido(cuit === '' ? null : cuit).subscribe({
      next: (pedido) => {
        this.ocupado.set(false);
        this.pedidoBajado.set(pedido);
        this.exito.set('Se generó el pedido de certificado: bajalo y subilo en ARCA.');
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
        this.terminar(config, 'Se cargó el certificado de ARCA.');
      },
      error: (err: unknown) => this.alFallar(err, this.errorCertificado, 'No se pudo cargar el certificado.'),
    });
  }

  protected firmarConSimulador(): void {
    if (this.ocupado()) return;
    this.limpiar();
    this.ocupado.set(true);
    this.service.firmarConSimulador().subscribe({
      next: (config) => {
        this.pedidoBajado.set(null);
        this.terminar(config, 'El simulador firmó el pedido: el certificado quedó activo.');
      },
      error: (err: unknown) => this.alFallar(err, null, 'No se pudo firmar el pedido con el simulador.'),
    });
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
  }
}
