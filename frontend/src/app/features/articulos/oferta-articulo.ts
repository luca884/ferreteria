import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { erroresPorCampo, mensajeDe } from '../../core/api/api-error';
import { formatearFecha } from '../../core/formato/fecha-hora';
import { MontoPipe, formatearMonto } from '../../core/formato/monto';
import { CampoMoneda } from '../../shared/campo-moneda';
import { ImporteInput } from '../../shared/importe-input';
import { Articulo, TIPOS_OFERTA, TIPO_OFERTA_LABELS, TipoOferta, precioDeOferta } from './articulo.models';
import { ArticulosService } from './articulos.service';

type Campo = 'tipo' | 'valor' | 'hasta';

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
  readonly cambiado = output<Articulo>();

  protected readonly tipos = TIPOS_OFERTA;
  protected readonly tipoLabels = TIPO_OFERTA_LABELS;

  protected readonly form = new FormGroup({
    tipo: new FormControl<TipoOferta>('PRECIO_FIJO', { nonNullable: true }),
    valor: new FormControl<number | null>(null),
    hasta: new FormControl<string | null>(null),
  });

  protected readonly guardando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly errores = signal<Partial<Record<Campo, string>>>({});

  private readonly valores = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

  protected readonly oferta = computed(() => this.articulo().oferta ?? null);

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

  protected readonly puedeGuardar = computed(() => {
    const valor = this.valores().valor;
    return !this.guardando() && valor !== null && valor !== undefined && valor > 0;
  });

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

  protected guardar(): void {
    if (!this.puedeGuardar()) return;
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
      },
    });
  }
}
