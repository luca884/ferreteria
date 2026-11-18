import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { MontoPipe } from '../../../core/formato/monto';
import { CampoMoneda } from '../../../shared/campo-moneda';
import { ImporteInput } from '../../../shared/importe-input';
import { esCelular } from '../../../shared/es-celular';
import { TrampaFoco } from '../../../shared/trampa-foco';
import { fechaHoraLocal } from '../../caja/caja.models';
import { aCentavos } from '../../mostrador/centavos';
import {
  AjusteRequest,
  TIPO_AJUSTE_LABELS,
  TipoAjuste,
  saldoTrasAjuste,
  textoDeSaldo,
  validarImporteAjuste,
} from './ajuste.models';
import {
  MOTIVO_MAXIMO,
  PendienteCuenta,
  aCuentaCentavos,
  filasDeImputacion,
  leerImporte,
  renglonesDeImputacion,
  sugerirFifo,
  validarMotivo,
} from './cobranza.models';

/** Lo que confirma el diálogo: el contenedor le suma la clave de idempotencia. Un Recargo no lleva `imputaciones`. */
export type PedidoAjuste = Omit<AjusteRequest, 'clave'>;

type Paso = 'datos' | 'confirmar';

/**
 * Recargo o Descuento de la cuenta corriente de un cliente (CU-12, GDS-20, G59). Presentacional, en dos pasos: los datos
 * (importe y motivo de 5 a 200 caracteres; el Descuento además reparte el importe en orden FIFO entre las partidas con deuda,
 * ventas y Recargos, editable, y lo que no se imputa queda «a cuenta») y una confirmación explícita que dice el efecto en
 * el saldo («El saldo pasa de $ X a $ Y.»). Enter avanza o confirma, Esc cierra; mientras `enVuelo` ignora Enter. Si el
 * envío quedó sin confirmar (`congelado`: red o 5xx) el contenido no se puede tocar —el reintento lleva la misma clave— y
 * la única salida para cambiarlo es `reiniciar`. Un 400 por campo (`errores`) o una deuda que cambió (`pendientes` nuevos)
 * devuelven a los datos para corregir.
 */
@Component({
  selector: 'app-dialogo-ajuste',
  imports: [CampoMoneda, ImporteInput, MontoPipe, TrampaFoco],
  templateUrl: './dialogo-ajuste.html',
  styleUrl: './dialogo-ajuste.css',
})
export class DialogoAjuste {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  /**
   * En el celular es una pantalla completa (movil-sectores T12b): la flecha «Volver» arriba y la acción principal en un pie
   * fijo. Se lee al abrir: no sigue la rotación. Es sólo cómo se ve: el estado, la validación, los manejadores y lo que se
   * confirma son los de siempre, y en la tablet y el escritorio el DOM no cambia.
   */
  protected readonly celular = esCelular();

  readonly clienteNombre = input.required<string>();
  readonly tipo = input.required<TipoAjuste>();
  /** El saldo actual del cliente en pesos (negativo = a favor): sobre él se dice el efecto del ajuste. */
  readonly saldo = input(0);
  /** Las partidas con deuda (ventas y Recargos) en orden FIFO, como las manda el servidor. Si cambian (409) se vuelve a sugerir. */
  readonly pendientes = input<readonly PendienteCuenta[]>([]);
  /** Lo que el cliente ya tiene a cuenta, sin imputar (en pesos). */
  readonly aCuenta = input(0);
  /** El contenedor lo prende mientras el POST está en curso. */
  readonly enVuelo = input(false);
  /** Un aviso que no es un error: «Las deudas cambiaron…». */
  readonly aviso = input<string | null>(null);
  /** El rechazo del servidor (cliente desactivado, sistema ocupado, red…). */
  readonly error = input<string | null>(null);
  /** Los errores de un 400 por campo (`importe`, `motivo`): se muestran junto a su campo. */
  readonly errores = input<Readonly<Record<string, string>>>({});
  /** El último envío no se pudo confirmar: importe, motivo y filas quedan fijos hasta reintentar o reiniciar. */
  readonly congelado = input(false);

  readonly confirmar = output<PedidoAjuste>();
  readonly cancelar = output<void>();
  /** «Cancelar y empezar de nuevo»: el contenedor suelta el envío pendiente (clave nueva) y el contenido vuelve a ser editable. */
  readonly reiniciar = output<void>();

  protected readonly maximo = MOTIVO_MAXIMO;
  protected readonly fecha = fechaHoraLocal;

  protected readonly paso = signal<Paso>('datos');
  protected readonly importeTexto = signal('');
  protected readonly motivoTexto = signal('');
  /** Lo escrito a mano en cada fila, por clave de partida (`C-7`, `A-3`); null mientras se usa la sugerencia FIFO. */
  private readonly editadas = signal<Record<string, string> | null>(null);
  /** Se intentó continuar con errores: recién ahí se avisa lo que falta. */
  private readonly intentado = signal(false);
  /** Los errores del servidor que siguen vigentes: se sueltan apenas el usuario toca el campo. */
  private readonly erroresServidor = signal<Record<string, string>>({});
  /** Entre el Enter y el siguiente pintado, para que un segundo Enter no mande el ajuste dos veces. */
  private bloqueado = false;

  private readonly campoImporte = viewChild<ElementRef<HTMLInputElement>>('campoImporte');
  private readonly botonConfirmar = viewChild<ElementRef<HTMLButtonElement>>('botonConfirmar');

  protected readonly etiqueta = computed(() => TIPO_AJUSTE_LABELS[this.tipo()]);
  protected readonly esDescuento = computed(() => this.tipo() === 'DESCUENTO');

  /** El importe en centavos; null si está vacío o no es un número. */
  protected readonly importeCentavos = computed<number | null>(() => {
    const texto = this.importeTexto().trim();
    return texto === '' ? null : leerImporte(texto);
  });

  protected readonly errorImporte = computed<string | null>(() => {
    const texto = this.importeTexto();
    const local = texto.trim() === '' && !this.intentado() ? null : validarImporteAjuste(texto);
    return local ?? this.erroresServidor()['importe'] ?? null;
  });

  protected readonly errorMotivo = computed<string | null>(() => {
    const local = this.intentado() ? validarMotivo(this.motivoTexto(), 'del ajuste') : null;
    return local ?? this.erroresServidor()['motivo'] ?? null;
  });

  private readonly sugerencia = computed(() => sugerirFifo(this.pendientes(), this.importeValido() ?? 0));

  /** El importe en centavos si vale (hasta 2 decimales, mayor que cero y dentro del tope); si no, null. */
  private readonly importeValido = computed(() =>
    validarImporteAjuste(this.importeTexto()) === null ? this.importeCentavos() : null,
  );

  /** Las filas de imputación (solo el Descuento imputa): el texto que se ve, su importe en centavos y su error en línea. */
  protected readonly filas = computed(() =>
    this.esDescuento() ? filasDeImputacion(this.pendientes(), this.editadas(), this.sugerencia().imputaciones) : [],
  );

  private readonly imputadoCentavos = computed(() => this.filas().reduce((suma, f) => suma + f.centavos, 0));

  protected readonly errorImputado = computed<string | null>(() => {
    const importe = this.importeValido();
    return importe !== null && this.imputadoCentavos() > importe ? 'Lo imputado supera el importe.' : null;
  });

  /** Lo que queda sin imputar del importe tipeado; 0 si el importe todavía no es válido o el ajuste es un Recargo. */
  protected readonly aCuentaCentavos = computed(() => {
    const importe = this.importeValido();
    return !this.esDescuento() || importe === null
      ? 0
      : Math.max(0, aCuentaCentavos(importe, this.filas().map((f) => f.centavos)));
  });

  /** Las filas que reciben algo, para decirlo en la confirmación. */
  protected readonly imputadas = computed(() => this.filas().filter((f) => f.centavos > 0));

  private readonly datosValidos = computed(
    () =>
      this.importeValido() !== null &&
      validarMotivo(this.motivoTexto(), 'del ajuste') === null &&
      this.errorImputado() === null &&
      this.filas().every((f) => f.error === null),
  );

  /** «El saldo pasa de $ 1.200,00 a $ 1.700,00»: el efecto del ajuste, con «a favor» si queda a favor. */
  protected readonly saldoActualTexto = computed(() => textoDeSaldo(this.saldo()));
  protected readonly saldoNuevoTexto = computed(() => {
    const importe = this.importeValido();
    return importe === null ? '' : textoDeSaldo(saldoTrasAjuste(aCentavos(this.saldo()), this.tipo(), importe) / 100);
  });

  constructor() {
    // Si las deudas cambian (el 409 las refresca), lo editado ya no vale y lo que se iba a confirmar tampoco: se vuelve a
    // los datos con la sugerencia FIFO nueva para revisarla.
    effect(() => {
      this.pendientes();
      untracked(() => {
        this.editadas.set(null);
        if (this.paso() === 'confirmar') this.irA('datos');
      });
    });
    // Un 400 por campo vuelve a los datos con el error junto al campo.
    effect(() => {
      const errores = this.errores();
      untracked(() => {
        this.erroresServidor.set({ ...errores });
        if (errores['importe'] || errores['motivo']) this.irA('datos');
      });
    });
    // Al abrir, y al soltar el congelado (reiniciar, o un rechazo definitivo del reintento) «Cancelar y empezar de nuevo» se
    // destruye: si tenía el foco, caería al body y Esc y Enter (que escucha el diálogo) dejarían de llegarle.
    effect(() => {
      if (!this.congelado()) untracked(() => this.enfocarTrasPintar());
    });
  }

  private irA(paso: Paso): void {
    this.paso.set(paso);
    this.enfocarTrasPintar();
  }

  /** Lleva el foco al campo del paso en que está (el importe en los datos, el botón en la confirmación) cuando ya se pintó. */
  private enfocarTrasPintar(): void {
    afterNextRender(
      () => (this.paso() === 'datos' ? this.campoImporte() : this.botonConfirmar())?.nativeElement.focus(),
      { injector: this.injector },
    );
  }

  protected escribirImporte(texto: string): void {
    if (this.congelado()) return;
    this.importeTexto.set(texto);
    this.soltarErrorServidor('importe');
  }

  protected escribirMotivo(texto: string): void {
    if (this.congelado()) return;
    this.motivoTexto.set(texto);
    this.soltarErrorServidor('motivo');
  }

  private soltarErrorServidor(campo: string): void {
    if (!this.erroresServidor()[campo]) return;
    const { [campo]: _suelto, ...resto } = this.erroresServidor();
    this.erroresServidor.set(resto);
  }

  /** Editar una fila congela las demás tal como se ven: el importe ya no las vuelve a repartir. */
  protected escribirFila(clave: string, texto: string): void {
    if (this.congelado()) return;
    const base = this.editadas() ?? Object.fromEntries(this.filas().map((f) => [f.clave, f.texto]));
    this.editadas.set({ ...base, [clave]: texto });
  }

  protected imputarEnOrden(): void {
    if (this.congelado()) return;
    this.editadas.set(null);
  }

  /** Un clic en el fondo no debe sacar el foco del diálogo (iría al body y Esc y Enter dejarían de llegarle). */
  protected retenerFoco(evento: MouseEvent): void {
    if (evento.target === evento.currentTarget) evento.preventDefault();
  }

  protected alTeclear(evento: KeyboardEvent): void {
    switch (evento.key) {
      case 'Escape':
        evento.preventDefault();
        evento.stopPropagation();
        this.cancelar.emit();
        return;
      case 'Enter':
        // En un botón, Enter es su clic: Cancelar cancela y «Volver» vuelve, no se avanza ni se confirma el ajuste.
        if ((evento.target as HTMLElement).tagName === 'BUTTON') return;
        evento.preventDefault();
        evento.stopPropagation();
        if (this.paso() === 'datos') this.continuar();
        else this.confirmarAjuste();
        return;
    }
  }

  /** De los datos a la confirmación; con errores los marca y enfoca el primero. */
  protected continuar(): void {
    this.intentado.set(true);
    if (!this.datosValidos()) {
      this.enfocarPrimerError();
      return;
    }
    this.irA('confirmar');
  }

  /** De la confirmación de vuelta a los datos, con lo escrito. */
  protected volver(): void {
    if (this.congelado()) return;
    this.irA('datos');
  }

  protected confirmarAjuste(): void {
    if (this.enVuelo() || this.bloqueado || this.paso() !== 'confirmar') return;
    const importe = this.importeValido();
    if (importe === null || !this.datosValidos()) return;
    // Cuando el contenedor ya pintó, `enVuelo` dice si sigue en curso; si el rechazo fue inmediato, se puede reintentar.
    this.bloqueado = true;
    afterNextRender(() => (this.bloqueado = false), { injector: this.injector });
    const pedido: PedidoAjuste = { tipo: this.tipo(), importe: importe / 100, motivo: this.motivoTexto().trim() };
    this.confirmar.emit(this.esDescuento() ? { ...pedido, imputaciones: renglonesDeImputacion(this.filas()) } : pedido);
  }

  /** Enfoca el primer campo con error; si el error recién va a aparecer, después de pintar. */
  private enfocarPrimerError(): void {
    const enfocar = () => this.host.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
    if (this.host.nativeElement.querySelector('[aria-invalid="true"]')) enfocar();
    else afterNextRender(enfocar, { injector: this.injector });
  }
}
