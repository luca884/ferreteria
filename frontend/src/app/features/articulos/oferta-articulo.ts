import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { erroresPorCampo, mensajeDe } from '../../core/api/api-error';
import { formatearFecha } from '../../core/formato/fecha-hora';
import { MontoPipe, formatearMonto } from '../../core/formato/monto';
import { CampoMoneda } from '../../shared/campo-moneda';
import { ImporteInput } from '../../shared/importe-input';
import { MotivoDeAccion, Motivos, ariaDe } from '../../shared/motivo-de-accion';
import { Articulo, TIPOS_OFERTA, TIPO_OFERTA_LABELS, TipoOferta, precioDeOferta } from './articulo.models';
import { ArticulosService } from './articulos.service';

type Campo = 'tipo' | 'valor' | 'hasta';

/** S5: lo que se explica al intentar guardar la oferta sin un valor mayor a 0. */
const MOTIVO_SIN_VALOR = 'Completá el porcentaje o el precio de oferta.';

/** Hoy en Buenos Aires como AAAA-MM-DD (la oferta vale hasta ese día inclusive, hora argentina). */
function hoyEnArgentina(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Argentina/Buenos_Aires' }).format(new Date());
}

/**
 * La oferta del artículo (ofertas, S1 y S3): el resumen para todos y, para el Dueño, el formulario para ponerla o
 * sacarla. Guarda con su propio pedido, aparte del PUT del artículo, y avisa el artículo que devolvió el servidor
 * (con la versión nueva) para que la ficha lo use en el próximo guardado.
 */
@Component({
  selector: 'app-oferta-articulo',
  imports: [CampoMoneda, ImporteInput, ReactiveFormsModule, MontoPipe],
  templateUrl: './oferta-articulo.html',
  styleUrl: './oferta-articulo.css',
})
export class OfertaArticulo {
  private readonly service = inject(ArticulosService);

  readonly articulo = input.required<Articulo>();
  /** Solo el Dueño pone o saca ofertas; el Vendedor ve el resumen. */
  readonly editable = input(false);
  /**
   * La ficha del celular (movil-sectores T5, 5d): la sección es una tarjeta plegable como las de la ficha y el tipo se
   * elige con tres botones de 44 px. Es el mismo estado y el mismo guardado; el escritorio no cambia.
   */
  readonly celular = input(false);
  readonly cambiado = output<Articulo>();

  protected readonly tipos = TIPOS_OFERTA;
  protected readonly tipoLabels = TIPO_OFERTA_LABELS;

  protected readonly form = new FormGroup({
    tipo: new FormControl<TipoOferta>('PRECIO_FIJO', { nonNullable: true }),
    valor: new FormControl<number | null>(null),
    hasta: new FormControl<string | null>(null),
  });

  /** La tarjeta del celular arranca plegada; se abre al tocarla y sola cuando falla el guardado. */
  protected readonly abierta = signal(false);
  protected readonly guardando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly errores = signal<Partial<Record<Campo, string>>>({});

  private readonly valores = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

  protected readonly oferta = computed(() => this.articulo().oferta ?? null);

  /** El dato clave de la tarjeta plegada del celular: el precio de la oferta vigente, si hay. */
  protected readonly datoPlegada = computed(() => {
    const oferta = this.oferta();
    return oferta?.vigente && oferta.precioOferta !== null ? formatearMonto(oferta.precioOferta) : '';
  });

  /** El precio que dejaría lo escrito sobre el precio normal; undefined sin un valor mayor a 0. */
  protected readonly precioResultante = computed(() => {
    const { tipo, valor } = this.valores();
    if (valor === null || valor === undefined || !(valor > 0) || !tipo) return undefined;
    return precioDeOferta(this.articulo().precioFinal, tipo, valor);
  });

  protected readonly resumen = computed(() => {
    const oferta = this.oferta();
    if (!oferta) return null;
    if (oferta.vigente && oferta.precioOferta !== null) {
      const fin = oferta.hasta ? ` hasta el ${formatearFecha(oferta.hasta)}` : ', sin fecha de fin';
      return (
        `En oferta a ${formatearMonto(oferta.precioOferta)} ` +
        `(precio normal ${formatearMonto(this.articulo().precioFinal)})${fin}.`
      );
    }
    if (oferta.hasta && oferta.hasta < hoyEnArgentina()) {
      return `La oferta venció el ${formatearFecha(oferta.hasta)}.`;
    }
    return 'La oferta no baja el precio normal de hoy, así que no se aplica.';
  });

  /** El valor escrito no sirve como oferta: falta o no es mayor a 0 (la parte de la regla que no es transitoria). */
  private readonly sinValorValido = computed(() => {
    const valor = this.valores().valor;
    return valor === null || valor === undefined || !(valor > 0);
  });

  /**
   * Por qué la acción no se puede usar: null si se puede (S5). Es la misma tabla que apaga el botón con `aria-disabled`
   * y la que se explica al intentar, así que la regla y su texto no se desalinean.
   */
  protected readonly motivos = computed<Motivos<'guardar'>>(() => ({
    guardar: this.sinValorValido() ? MOTIVO_SIN_VALOR : null,
  }));
  /** El motivo a la vista de la última acción que no se pudo usar, o null. Se anuncia con role="status" (S5). */
  protected readonly motivo = new MotivoDeAccion(() => this.motivos());
  /** Para la plantilla: 'true' apaga el botón, null lo deja prendido. */
  protected readonly ariaDe = ariaDe;

  constructor() {
    // Arranca con la oferta guardada (o vacío) y se vuelve a cargar cada vez que el servidor devuelve el artículo.
    effect(() => {
      const oferta = this.oferta();
      untracked(() =>
        this.form.setValue({
          tipo: oferta?.tipo ?? 'PRECIO_FIJO',
          valor: oferta?.valor ?? null,
          hasta: oferta?.hasta ?? null,
        }),
      );
    });
  }

  /** El tipo elegido con los botones del celular: escribe el mismo control del formulario que el select. */
  protected elegirTipo(tipo: TipoOferta): void {
    const control = this.form.controls.tipo;
    control.setValue(tipo);
    control.markAsDirty();
  }

  /** Como un grupo de radios nativo: las flechas eligen el tipo anterior o el siguiente (dando la vuelta) y llevan el foco. */
  protected alTeclearTipo(evento: KeyboardEvent, indice: number): void {
    const paso = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[evento.key];
    if (paso === undefined) return;
    evento.preventDefault();
    const destino = (indice + paso + this.tipos.length) % this.tipos.length;
    this.elegirTipo(this.tipos[destino]);
    (evento.currentTarget as HTMLElement).parentElement?.querySelectorAll('button')[destino]?.focus();
  }

  protected guardar(): void {
    // S5: sin un valor mayor a 0 el botón avisa el motivo en vez de quedar apagado sin explicación.
    if (this.motivo.mostrar('guardar')) return;
    // Un envío por vez: el `disabled` transitorio llega recién en el próximo render.
    if (this.guardando()) return;
    this.motivo.limpiar();
    const { tipo, valor, hasta } = this.form.getRawValue();
    const version = this.articulo().version;
    this.enviar(
      this.service.ponerOferta(this.articulo().id, {
        tipo,
        valor: valor as number,
        hasta: hasta || null,
        ...(version !== undefined ? { version } : {}),
      }),
    );
  }

  protected sacar(): void {
    if (this.guardando()) return;
    this.enviar(this.service.quitarOferta(this.articulo().id));
  }

  private enviar(pedido: ReturnType<ArticulosService['quitarOferta']>): void {
    this.guardando.set(true);
    this.error.set(null);
    this.errores.set({});
    pedido.subscribe({
      next: (articulo) => {
        this.guardando.set(false);
        this.cambiado.emit(articulo);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        const porCampo = erroresPorCampo(err);
        const propios: Partial<Record<Campo, string>> = {};
        for (const campo of ['tipo', 'valor', 'hasta'] as const) {
          if (porCampo[campo]) propios[campo] = porCampo[campo];
        }
        this.errores.set(propios);
        if (Object.keys(propios).length === 0) {
          this.error.set(mensajeDe(err, 'No se pudo guardar la oferta.'));
        }
        // Un error en una tarjeta plegada no se vería.
        this.abierta.set(true);
      },
    });
  }
}
