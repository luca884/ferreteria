import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe, formatearMonto } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import {
  CajaVigente,
  DetalleCaja,
  TIPO_MOVIMIENTO_LABELS,
  TipoMovimientoCaja,
  avisoParaVender,
  fechaHoraLocal,
  tiposPara,
} from './caja.models';
import { CajaService } from './caja.service';
import { ResumenCaja } from './resumen-caja';

/** Un número escrito en un campo (acepta coma decimal); NaN si está vacío o no se entiende. */
function leerNumero(texto: string): number {
  const limpio = texto.trim().replace(',', '.');
  return limpio === '' ? NaN : Number(limpio);
}

/**
 * Caja del día (CU-10), para el Vendedor y el Dueño. Hay una sola caja abierta por ferretería: se abre con el efectivo
 * inicial, durante el día se cargan ingresos, gastos y retiros (este último solo el Dueño) y se cierra a ciegas: el que
 * cierra carga solo lo contado y recién entonces ve el esperado y la diferencia. El Dueño ve la caja en vivo.
 */
@Component({
  selector: 'app-caja',
  imports: [BarraAcciones, Confirmacion, MontoPipe, ResumenCaja, RouterLink],
  templateUrl: './caja.html',
  styleUrl: './caja.css',
})
export class Caja implements OnInit {
  private readonly cajas = inject(CajaService);
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
  protected readonly esDeOtroDia = computed(() => this.vigente()?.estado === 'DE_OTRO_DIA');
  protected readonly avisoOtroDia = computed(() => {
    const vigente = this.vigente();
    return vigente?.estado === 'DE_OTRO_DIA' ? avisoParaVender(vigente) : null;
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
    this.efectivoInicial.set(String(vigente.efectivoPropuesto));
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
    const efectivo = leerNumero(this.efectivoInicial());
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
        this.vigente.set({ estado: 'ABIERTA', caja: abierta, efectivoPropuesto: 0 });
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
    const importe = leerNumero(this.importe());
    const motivo = this.motivo().trim();
    this.errorImporte.set(importe > 0 ? null : 'El importe tiene que ser mayor a cero.');
    this.errorMotivo.set(motivo === '' ? 'Indicá el motivo.' : null);
    if (this.errorImporte() || this.errorMotivo()) return;
    this.trabajando.set(true);
    this.cajas.registrarMovimiento(caja.id, { tipo: this.tipo(), importe, motivo }).subscribe({
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
        this.error.set(this.textoDeError(err, 'No se pudo cargar el movimiento.'));
      },
    });
  }

  /** Paso 1 del cierre: valida lo contado y pide confirmación; no se manda nada todavía. */
  protected pedirCierre(evento: Event): void {
    evento.preventDefault();
    if (this.trabajando()) return;
    this.limpiarAvisos();
    const contado = leerNumero(this.contado());
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
        this.vigente.set({ estado: 'SIN_CAJA', caja: null, efectivoPropuesto: contado });
        this.efectivoInicial.set(String(contado));
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
  }

  private textoDeError(err: unknown, porDefecto: string): string {
    const campos = erroresDeCampos(err);
    return campos.length > 0 ? campos.join(' ') : mensajeDe(err, porDefecto);
  }
}
