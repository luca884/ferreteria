import { Component, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe, formatearMonto } from '../../core/formato/monto';
import { precioFinalCentavos } from '../../core/formato/precio-final';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { CampoMoneda } from '../../shared/campo-moneda';
import { esCelular } from '../../shared/es-celular';
import { HojaInferior } from '../../shared/hoja-inferior';
import { ImporteInput } from '../../shared/importe-input';
import { ConfiguracionPrecios } from '../articulos/articulo.models';
import {
  ConfiguracionPreciosGuardada,
  ConfiguracionPreciosService,
  EjemploDePrecio,
} from './configuracion-precios.service';

/**
 * Cambio pendiente de confirmar: cuántos artículos se recalculan (null si no se pudo contar), unos ejemplos de los que
 * cambian de precio y el título.
 */
interface CambioPendiente {
  nueva: ConfiguracionPrecios;
  articulos: number | null;
  ejemplos: EjemploDePrecio[];
  titulo: string;
}

const ENTERO = new Intl.NumberFormat('es-AR');
const MULTIPLO = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 });
/** El precio del ejemplo del redondeo: el del recorrido con Luca (S7). */
const PRECIO_DE_EJEMPLO = '909788.60';

/** El título nombra solo lo que cambió (QA 05/11); sin configuración guardada, los dos. */
function tituloCambio(anterior: ConfiguracionPrecios | null, nueva: ConfiguracionPrecios): string {
  const margen = anterior === null || anterior.margenDefectoPorcentaje !== nueva.margenDefectoPorcentaje;
  const redondeo = anterior === null || anterior.redondeoMultiplo !== nueva.redondeoMultiplo;
  if (margen && !redondeo) return '¿Cambiar el margen?';
  if (redondeo && !margen) return '¿Cambiar el redondeo?';
  return '¿Cambiar el margen y el redondeo?';
}

/**
 * Configuración › Margen y redondeo (solo el Dueño), como la ganancia por defecto y el redondeo de GDS. Guardar
 * recalcula los precios que dependen de estos valores, así que antes se avisa cuántos artículos cambian.
 */
@Component({
  selector: 'app-configuracion-precios',
  imports: [ReactiveFormsModule, BarraAcciones, Confirmacion, CampoMoneda, ImporteInput, MontoPipe, HojaInferior],
  templateUrl: './configuracion-precios.html',
  styleUrl: './configuracion-precios.css',
})
export class PaginaConfiguracionPrecios implements OnInit {
  private readonly service = inject(ConfiguracionPreciosService);
  protected readonly auth = inject(AuthService);
  protected readonly celular = esCelular();
  private readonly router = this.celular ? inject(Router) : null;
  private readonly guardarEnPie = viewChild<ElementRef<HTMLButtonElement>>('guardarEnPie');

  protected readonly form = inject(FormBuilder).nonNullable.group({
    margenDefectoPorcentaje: [0, [Validators.required, Validators.min(0), Validators.max(1000)]],
    redondeoMultiplo: [0, [Validators.required, Validators.min(0), Validators.max(1000)]],
  });

  protected readonly guardada = signal<ConfiguracionPrecios | null>(null);
  protected readonly cargando = signal(true);
  protected readonly cargaFallida = signal(false);
  protected readonly guardando = signal(false);
  protected readonly aviso = signal<string | null>(null);
  /** Artículos que conservaron su precio: sus ajustes lo dejarían en 0 o menos. */
  protected readonly avisosSalteados = signal<string[]>([]);
  /** Lo que el backend rechazó, un mensaje por campo (400 de validación). */
  protected readonly erroresGuardado = signal<string[]>([]);
  /** Cualquier otro error al guardar (409 sin cotización, sin conexión…). */
  protected readonly errorGuardado = signal<string | null>(null);
  protected readonly cambioPendiente = signal<CambioPendiente | null>(null);

  private readonly multiploEscrito = toSignal(this.form.controls.redondeoMultiplo.valueChanges, {
    initialValue: this.form.controls.redondeoMultiplo.value,
  });

  /**
   * Cómo queda un precio con el múltiplo escrito, con la misma cuenta que el servidor (hacia arriba). Con 0, vacío o un
   * valor inválido no hay ejemplo: queda la pista de siempre.
   */
  protected readonly ejemploRedondeo = computed(() => {
    const multiplo = Number(this.multiploEscrito());
    if (!Number.isFinite(multiplo) || multiplo <= 0 || this.form.controls.redondeoMultiplo.invalid) return null;
    // Con margen 0 e IVA 0, el precio final es el precio de ejemplo redondeado al múltiplo.
    const centavos = precioFinalCentavos(PRECIO_DE_EJEMPLO, '0', 'IVA_0', String(multiplo));
    if (centavos === null) return null;
    return (
      `Con ${MULTIPLO.format(multiplo)}, un precio de ${formatearMonto(Number(PRECIO_DE_EJEMPLO))} queda en ` +
      `${formatearMonto(Number(centavos) / 100)}. Siempre hacia arriba, así no perdés margen.`
    );
  });

  ngOnInit(): void {
    if (this.auth.esDueno()) this.cargar();
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.cargaFallida.set(false);
    this.service.obtener().subscribe({
      next: (configuracion) => {
        this.guardada.set(configuracion);
        this.form.reset(configuracion);
        this.cargando.set(false);
      },
      error: () => {
        this.cargando.set(false);
        this.cargaFallida.set(true);
      },
    });
  }

  /** No hay una ruta de Configuración: Volver y Cancelar salen a la lista de Artículos sin guardar. */
  protected salir(): void {
    if (!this.guardando()) void this.router?.navigateByUrl('/articulos');
  }

  protected cerrarHoja(): void {
    this.cambioPendiente.set(null);
    // Durante la consulta Guardar se deshabilita y puede perder el foco antes de que abra la hoja.
    this.guardarEnPie()?.nativeElement.focus();
  }

  protected guardar(): void {
    if (this.guardando()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.limpiarMensajes();
    const nueva = this.form.getRawValue();
    const anterior = this.guardada();
    if (
      anterior !== null &&
      anterior.margenDefectoPorcentaje === nueva.margenDefectoPorcentaje &&
      anterior.redondeoMultiplo === nueva.redondeoMultiplo
    ) {
      this.enviar(nueva);
      return;
    }
    // Cambia algún valor: se recalculan precios. Se avisa cuántos antes, como con el margen de un rubro.
    const titulo = tituloCambio(anterior, nueva);
    this.guardando.set(true);
    this.service.articulosQueCambian(nueva).subscribe({
      next: ({ cantidad, ejemplos }) => {
        this.guardando.set(false);
        if (cantidad === 0) this.enviar(nueva);
        else this.cambioPendiente.set({ nueva, articulos: cantidad, ejemplos, titulo });
      },
      error: () => {
        this.guardando.set(false);
        this.cambioPendiente.set({ nueva, articulos: null, ejemplos: [], titulo });
      },
    });
  }

  protected confirmarCambio(): void {
    const cambio = this.cambioPendiente();
    this.cambioPendiente.set(null);
    if (cambio) this.enviar(cambio.nueva);
  }

  protected mensajeCambio(cambio: CambioPendiente): string {
    if (cambio.articulos === null) return 'Se recalcula el precio de los artículos que dependen de estos valores.';
    return cambio.articulos === 1
      ? 'Se recalcula el precio de 1 artículo.'
      : `Se recalcula el precio de ${ENTERO.format(cambio.articulos)} artículos.`;
  }

  protected invalido(campo: 'margenDefectoPorcentaje' | 'redondeoMultiplo'): boolean {
    const control = this.form.controls[campo];
    return control.touched && control.invalid;
  }

  private enviar(nueva: ConfiguracionPrecios): void {
    this.guardando.set(true);
    this.service.guardar(nueva).subscribe({
      next: (resultado) => {
        this.guardando.set(false);
        const configuracion = {
          margenDefectoPorcentaje: resultado.margenDefectoPorcentaje,
          redondeoMultiplo: resultado.redondeoMultiplo,
        };
        this.guardada.set(configuracion);
        this.form.reset(configuracion);
        this.aviso.set(describirGuardado(resultado));
        this.avisosSalteados.set(resultado.avisos ?? []);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        const campos = erroresDeCampos(err);
        if (campos.length > 0) this.erroresGuardado.set(campos);
        else this.errorGuardado.set(mensajeDe(err, 'No se pudo guardar la configuración.'));
      },
    });
  }

  private limpiarMensajes(): void {
    this.aviso.set(null);
    this.avisosSalteados.set([]);
    this.erroresGuardado.set([]);
    this.errorGuardado.set(null);
  }
}

function describirGuardado(r: ConfiguracionPreciosGuardada): string {
  if (r.articulosRecalculados === 0) return 'Se guardó la configuración.';
  return r.articulosRecalculados === 1
    ? 'Se guardó la configuración. Se recalculó 1 precio.'
    : `Se guardó la configuración. Se recalcularon ${r.articulosRecalculados} precios.`;
}
