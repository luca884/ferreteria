import { Component, ElementRef, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { centavosDeImporte, textoDePlata } from '../../core/formato/importe';
import { MontoPipe, formatearMonto } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { CampoMoneda } from '../../shared/campo-moneda';
import { ImporteInput } from '../../shared/importe-input';
import {
  CajaVigente,
  DetalleCaja,
  EfectivoInsuficiente,
  MovimientoCajaRequest,
  TIPO_MOVIMIENTO_LABELS,
  TipoMovimientoCaja,
  avisoCajaDeOtroDia,
  fechaHoraLocal,
  tiposPara,
} from './caja.models';
import { CajaService } from './caja.service';
import { ResumenCaja } from './resumen-caja';

/** El detalle del 422 EFECTIVO_INSUFICIENTE, o null si el error es otro. */
function efectivoInsuficiente(err: unknown): EfectivoInsuficiente | null {
  if (!(err instanceof HttpErrorResponse) || err.error?.motivo !== 'EFECTIVO_INSUFICIENTE') return null;
  return (err.error.efectivo as EfectivoInsuficiente | undefined) ?? null;
}

/** Un importe escrito en un campo con máscara («50.000,5»), en pesos; NaN si está vacío o no se entiende. */
function leerImporteEnPesos(texto: string): number {
  const centavos = centavosDeImporte(texto);
  return centavos === null ? NaN : centavos / 100;
}

/**
 * Caja del día (CU-10), para el Vendedor y el Dueño. Hay una sola caja abierta por ferretería: se abre con el efectivo
 * inicial, durante el día se cargan ingresos, gastos y retiros (este último solo el Dueño) y se cierra a ciegas: el que
 * cierra carga solo lo contado y recién entonces ve el esperado y la diferencia. El Dueño ve la caja en vivo.
 */
@Component({
  selector: 'app-caja',
  imports: [BarraAcciones, Confirmacion, CampoMoneda, ImporteInput, MontoPipe, ResumenCaja, RouterLink],
  templateUrl: './caja.html',
  styleUrl: './caja.css',
})
export class Caja implements OnInit {
  private readonly cajas = inject(CajaService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly auth = inject(AuthService);

  protected readonly vigente = signal<CajaVigente | null>(null);
  protected readonly cargando = signal(true);
  protected readonly cargaFallida = signal(false);
  /** Lo que ve el Dueño de la caja abierta, en vivo. Nunca se carga para el Vendedor. */
  protected readonly enVivo = signal<DetalleCaja | null>(null);
  /** La respuesta del último cierre: trae el esperado y la diferencia que el cierre ciego tenía ocultos. */
  protected readonly cierre = signal<DetalleCaja | null>(null);

  protected readonly mensaje = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly trabajando = signal(false);

  protected readonly efectivoInicial = signal('');
  protected readonly errorApertura = signal<string | null>(null);

  protected readonly tipo = signal<TipoMovimientoCaja>('INGRESO');
  protected readonly importe = signal('');
  protected readonly motivo = signal('');
  protected readonly errorImporte = signal<string | null>(null);
  protected readonly errorMotivo = signal<string | null>(null);
  /** El gasto o retiro que no entra en el efectivo, esperando que el Dueño lo confirme (GDS-36). */
  protected readonly faltaEfectivo = signal<{
    mensaje: string;
    disponible: number;
    faltante: number;
    pedido: MovimientoCajaRequest;
  } | null>(null);

  protected readonly contado = signal('');
  protected readonly errorContado = signal<string | null>(null);
  /** El efectivo contado que espera confirmación para cerrar. */
  protected readonly confirmandoCierre = signal<number | null>(null);

  protected readonly tipos = computed(() =>
    tiposPara(this.auth.esDueno()).map((valor) => ({ valor, etiqueta: TIPO_MOVIMIENTO_LABELS[valor] })),
  );
  protected readonly tituloCierre = computed(() => {
    const contado = this.confirmandoCierre();
    return contado === null ? null : `¿Cerrar la caja con ${formatearMonto(contado)} contados?`;
  });
  protected readonly caja = computed(() => this.vigente()?.caja ?? null);
  /** Nunca se cerró una caja: no hay «último cierre» que proponer, se cuenta lo que hay. */
  protected readonly primeraApertura = computed(() => this.vigente()?.hayCierreAnterior === false);
  protected readonly esDeOtroDia = computed(() => this.vigente()?.estado === 'DE_OTRO_DIA');
  protected readonly avisoOtroDia = computed(() => {
    const vigente = this.vigente();
    return vigente ? avisoCajaDeOtroDia(vigente) : null;
  });
  protected readonly fechaHora = fechaHoraLocal;

  ngOnInit(): void {
    this.cargar();
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.cargaFallida.set(false);
    this.cajas.actual().subscribe({
      next: (vigente) => {
        this.cargando.set(false);
        this.aplicar(vigente);
      },
      error: () => {
        this.cargando.set(false);
        this.cargaFallida.set(true);
      },
    });
  }

  private aplicar(vigente: CajaVigente): void {
    this.vigente.set(vigente);
    this.efectivoInicial.set(textoDePlata(vigente.efectivoPropuesto));
    this.cargarEnVivo();
  }

  private cargarEnVivo(): void {
    const caja = this.caja();
    if (!this.auth.esDueno() || !caja) {
      this.enVivo.set(null);
      return;
    }
    this.cajas.detalle(caja.id).subscribe({
      next: (detalle) => this.enVivo.set(detalle),
      // El resumen en vivo es un extra: si falla, la caja se sigue operando.
      error: () => this.enVivo.set(null),
    });
  }

  protected abrir(evento: Event): void {
    evento.preventDefault();
    if (this.trabajando()) return;
    this.limpiarAvisos();
    const efectivo = leerImporteEnPesos(this.efectivoInicial());
    if (Number.isNaN(efectivo)) {
      this.errorApertura.set('Indicá el efectivo inicial.');
      return;
    }
    if (efectivo < 0) {
      this.errorApertura.set('El efectivo no puede ser negativo.');
      return;
    }
    this.errorApertura.set(null);
    this.trabajando.set(true);
    this.cajas.abrir(efectivo).subscribe({
      next: (abierta) => {
        this.trabajando.set(false);
        this.cierre.set(null);
        this.mensaje.set('Abriste la caja.');
        this.vigente.set({
          estado: 'ABIERTA',
          caja: abierta,
          efectivoPropuesto: 0,
          hayCierreAnterior: this.vigente()?.hayCierreAnterior ?? false,
        });
        this.cargarEnVivo();
      },
      error: (err: unknown) => {
        this.trabajando.set(false);
        this.error.set(this.textoDeError(err, 'No se pudo abrir la caja.'));
        // Si otro usuario ya la abrió, la pantalla pasa a esa caja.
        this.releer();
      },
    });
  }

  protected registrarMovimiento(evento: Event): void {
    evento.preventDefault();
    const caja = this.caja();
    if (!caja || this.trabajando()) return;
    this.limpiarAvisos();
    const importe = leerImporteEnPesos(this.importe());
    const motivo = this.motivo().trim();
    this.errorImporte.set(importe > 0 ? null : 'El importe tiene que ser mayor a cero.');
    this.errorMotivo.set(motivo === '' ? 'Indicá el motivo.' : null);
    if (this.errorImporte() || this.errorMotivo()) return;
    this.enviarMovimiento(caja.id, { tipo: this.tipo(), importe, motivo });
  }

  /** «Registrar igual»: el Dueño manda el mismo egreso confirmando que la caja queda en negativo. */
  protected confirmarSinEfectivo(): void {
    const caja = this.caja();
    const falta = this.faltaEfectivo();
    if (!caja || !falta || this.trabajando()) return;
    this.limpiarAvisos();
    this.enviarMovimiento(caja.id, { ...falta.pedido, confirmaSinEfectivo: true });
  }

  private enviarMovimiento(cajaId: number, pedido: MovimientoCajaRequest): void {
    this.trabajando.set(true);
    this.cajas.registrarMovimiento(cajaId, pedido).subscribe({
      next: (movimiento) => {
        this.trabajando.set(false);
        this.mensaje.set(
          `Se cargó el ${TIPO_MOVIMIENTO_LABELS[movimiento.tipo].toLowerCase()} de ${formatearMonto(movimiento.importe)}.`,
        );
        this.importe.set('');
        this.motivo.set('');
        this.cargarEnVivo();
      },
      error: (err: unknown) => {
        this.trabajando.set(false);
        const efectivo = efectivoInsuficiente(err);
        if (efectivo?.puedeConfirmar && efectivo.disponible !== undefined && efectivo.faltante !== undefined) {
          this.faltaEfectivo.set({
            mensaje: mensajeDe(err, ''),
            disponible: efectivo.disponible,
            faltante: efectivo.faltante,
            pedido,
          });
          return;
        }
        this.error.set(this.textoDeError(err, 'No se pudo cargar el movimiento.'));
      },
    });
  }

  /** Paso 1 del cierre: valida lo contado y pide confirmación; no se manda nada todavía. */
  protected pedirCierre(evento: Event): void {
    evento.preventDefault();
    if (this.trabajando()) return;
    this.limpiarAvisos();
    const contado = leerImporteEnPesos(this.contado());
    if (Number.isNaN(contado)) {
      this.errorContado.set('Indicá el efectivo contado.');
      return;
    }
    if (contado < 0) {
      this.errorContado.set('El efectivo no puede ser negativo.');
      return;
    }
    this.errorContado.set(null);
    this.confirmandoCierre.set(contado);
  }

  protected cancelarCierre(): void {
    this.confirmandoCierre.set(null);
  }

  protected cerrar(): void {
    const caja = this.caja();
    const contado = this.confirmandoCierre();
    if (!caja || contado === null || this.trabajando()) return;
    this.trabajando.set(true);
    this.cajas.cerrar(caja.id, contado).subscribe({
      next: (detalle) => {
        this.trabajando.set(false);
        this.confirmandoCierre.set(null);
        this.cierre.set(detalle);
        this.enVivo.set(null);
        this.contado.set('');
        this.mensaje.set('Cerraste la caja.');
        this.vigente.set({ estado: 'SIN_CAJA', caja: null, efectivoPropuesto: contado, hayCierreAnterior: true });
        this.efectivoInicial.set(textoDePlata(contado));
        // El aviso y «Abrir la caja» van arriba del resumen: se vuelve arriba para verlos sin subir a buscarlos (#92). La
        // pantalla scrollea en el contenedor del shell, no en la ventana; jsdom no implementa scrollIntoView.
        this.host.nativeElement.scrollIntoView?.({ block: 'start' });
      },
      error: (err: unknown) => {
        this.trabajando.set(false);
        this.confirmandoCierre.set(null);
        this.error.set(this.textoDeError(err, 'No se pudo cerrar la caja.'));
        this.releer();
      },
    });
  }

  /** Vuelve a leer la caja sin tapar el mensaje que se acaba de mostrar. */
  private releer(): void {
    this.cajas.actual().subscribe({ next: (vigente) => this.aplicar(vigente), error: () => undefined });
  }

  private limpiarAvisos(): void {
    this.mensaje.set(null);
    this.error.set(null);
    this.faltaEfectivo.set(null);
  }

  private textoDeError(err: unknown, porDefecto: string): string {
    const campos = erroresDeCampos(err);
    return campos.length > 0 ? campos.join(' ') : mensajeDe(err, porDefecto);
  }
}
