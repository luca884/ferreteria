import { Component, ElementRef, Injector, afterNextRender, effect, inject, input, output, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { LADO_LABELS, LadoCotizacion } from '../../core/cotizacion/cotizacion.models';
import {
  ConfiguracionCotizacion,
  ConfiguracionCotizacionRequest,
  OPCIONES_COTIZACION,
} from './configuracion-cotizacion.models';

const LADOS: readonly LadoCotizacion[] = ['VENTA', 'COMPRA'];

const AYUDA_LADO: Record<LadoCotizacion, string> = {
  VENTA: 'Venta: el valor más alto, el que pagás si comprás dólares (recomendado)',
  COMPRA: 'Compra: el valor más bajo, el que te pagan si vendés dólares',
};

/** Hora en 24 h, con la hora de uno o dos dígitos: 8:15, 08:15, 20:30. */
const HORA_24 = /^([01]?\d|2[0-3]):[0-5]\d$/;

/** La hora con dos dígitos, como la espera el servidor: 8:15 → 08:15. */
function horaConDosDigitos(hora: string): string {
  return hora.trim().padStart(5, '0');
}

/**
 * Formulario de la configuración de cotización (presentacional): recibe la configuración guardada, emite el pedido
 * completo al guardar y avisa si hay cambios sin guardar. No habla con el servidor ni decide qué mostrar después.
 */
@Component({
  selector: 'app-formulario-cotizacion',
  imports: [ReactiveFormsModule],
  templateUrl: './formulario-cotizacion.html',
  styleUrl: './formulario-cotizacion.css',
})
export class FormularioCotizacion {
  readonly configuracion = input.required<ConfiguracionCotizacion>();
  readonly guardando = input(false);
  readonly guardar = output<ConfiguracionCotizacionRequest>();
  /** true cuando el formulario difiere de lo guardado; false cuando vuelve a coincidir (llegó lo guardado). */
  readonly cambios = output<boolean>();

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly opciones = OPCIONES_COTIZACION;
  protected readonly dolares = OPCIONES_COTIZACION.filter((o) => o.esDolar);
  protected readonly lados = LADOS;
  protected readonly ladoLabels = LADO_LABELS;
  protected readonly ayudaLado = AYUDA_LADO;

  protected readonly form = new FormGroup({
    activa: new FormControl(true, { nonNullable: true }),
    dolarPrecios: new FormControl('', { nonNullable: true }),
    ladoPrecios: new FormControl<LadoCotizacion>('VENTA', { nonNullable: true }),
    visibles: new FormGroup(
      Object.fromEntries(OPCIONES_COTIZACION.map((o) => [o.codigo, new FormControl(false, { nonNullable: true })])),
    ),
    // Texto y no <input type="time">: ese muestra «08:00 AM» según el navegador; acá se usa 24 h.
    horaRecalculo: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(HORA_24)],
    }),
    umbralDesvio: new FormControl<number | null>(null, {
      validators: [Validators.required, Validators.min(1), Validators.max(50)],
    }),
  });

  /** Recién después de apretar «Guardar» se muestran los errores: no se grita mientras se está tipeando. */
  protected intentoGuardar = false;
  private sucio = false;

  constructor() {
    // Lo guardado manda: cuando llega (carga inicial o después de guardar) el formulario vuelve a coincidir con él.
    effect(() => {
      const guardada = this.configuracion();
      untracked(() => {
        this.intentoGuardar = false;
        this.form.reset(valoresDe(guardada));
      });
    });
    this.form.valueChanges.subscribe(() => {
      if (this.form.dirty === this.sucio) return;
      this.sucio = this.form.dirty;
      this.cambios.emit(this.sucio);
    });
  }

  protected get sinVisibles(): boolean {
    return !Object.values(this.form.controls.visibles.getRawValue()).some(Boolean);
  }

  protected get errorVisibles(): boolean {
    return this.intentoGuardar && this.sinVisibles;
  }

  protected get errorHora(): boolean {
    return this.intentoGuardar && this.form.controls.horaRecalculo.invalid;
  }

  protected get errorUmbral(): boolean {
    return this.intentoGuardar && this.form.controls.umbralDesvio.invalid;
  }

  protected enviar(): void {
    this.intentoGuardar = true;
    if (this.sinVisibles || this.form.controls.horaRecalculo.invalid || this.form.controls.umbralDesvio.invalid) {
      afterNextRender(
        () => this.host.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus(),
        { injector: this.injector },
      );
      return;
    }
    const v = this.form.getRawValue();
    this.guardar.emit({
      activa: v.activa,
      dolarPrecios: v.dolarPrecios,
      ladoPrecios: v.ladoPrecios,
      // En el orden del catálogo, no en el orden en que se fueron marcando.
      visibles: OPCIONES_COTIZACION.filter((o) => v.visibles[o.codigo]).map((o) => o.codigo),
      horaRecalculo: horaConDosDigitos(v.horaRecalculo),
      // Validado arriba: no es null.
      umbralDesvio: Number(v.umbralDesvio),
    });
  }
}

function valoresDe(c: ConfiguracionCotizacion) {
  return {
    activa: c.activa,
    dolarPrecios: c.dolarPrecios,
    ladoPrecios: c.ladoPrecios,
    visibles: Object.fromEntries(OPCIONES_COTIZACION.map((o) => [o.codigo, c.visibles.includes(o.codigo)])),
    horaRecalculo: c.horaRecalculo,
    umbralDesvio: c.umbralDesvio,
  };
}
