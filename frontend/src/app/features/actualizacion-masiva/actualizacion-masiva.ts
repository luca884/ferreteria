import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { MontoPipe } from '../../core/formato/monto';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { erroresDeCampos } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { Rubro } from '../rubros/rubro.models';
import { RubrosService } from '../rubros/rubros.service';
import {
  ConteosMasivos,
  ModoMasivo,
  PedidoMasivo,
  ResultadoMasivo,
  VistaPreviaMasiva,
} from './actualizacion-masiva.models';
import { ActualizacionMasivaService } from './actualizacion-masiva.service';

const MENSAJE_GENERICO = 'No se pudo completar la operación. Probá de nuevo.';
const MENSAJE_CONFLICTO =
  'Los artículos cambiaron desde la vista previa. Volvé a generar la vista previa y confirmá de nuevo.';

/** Errores con un motivo que el Dueño puede corregir: se muestra el mensaje del servidor. */
const ESTADOS_CON_MENSAJE = [400, 403, 404, 409];

/** Un número con coma o punto decimal y signo opcional; nada de notación científica, hexa ni miles. */
const NUMERO = /^[+-]?\d+(?:[.,]\d+)?$/;

@Component({
  selector: 'app-actualizacion-masiva',
  imports: [ReactiveFormsModule, DecimalPipe, MontoPipe],
  templateUrl: './actualizacion-masiva.html',
  styleUrl: './actualizacion-masiva.css',
})
export class ActualizacionMasiva implements OnInit {
  private readonly service = inject(ActualizacionMasivaService);
  private readonly proveedoresService = inject(ProveedoresService);
  private readonly rubrosService = inject(RubrosService);
  protected readonly auth = inject(AuthService);

  protected readonly proveedores = signal<Proveedor[]>([]);
  protected readonly rubros = signal<Rubro[]>([]);
  protected readonly vistaPrevia = signal<VistaPreviaMasiva | null>(null);
  /** El pedido exacto que originó la vista previa: es el que se aplica, aunque el formulario se toque. */
  private readonly pedidoVigente = signal<PedidoMasivo | null>(null);
  protected readonly generando = signal(false);
  protected readonly aplicando = signal(false);
  /** Con el filtro vacío, el primer clic abre una segunda confirmación y recién el segundo aplica. */
  protected readonly confirmandoTodo = signal(false);
  protected readonly resultado = signal<ResultadoMasivo | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Mensajes por campo de un 400 de validación del backend; si hay, se muestran en vez de `error`. */
  protected readonly erroresCampos = signal<string[]>([]);

  /** Filtro, modo y valor. El valor es texto: acepta coma o punto decimal y se envía como número. */
  protected readonly form = new FormGroup({
    proveedorId: new FormControl<number | null>(null),
    rubroId: new FormControl<number | null>(null),
    q: new FormControl('', { nonNullable: true }),
    incluirInactivos: new FormControl(false, { nonNullable: true }),
    modo: new FormControl<ModoMasivo | null>(null),
    valor: new FormControl('', { nonNullable: true }),
  });

  private readonly formulario = toSignal(this.form.valueChanges, {
    initialValue: this.form.getRawValue(),
  });

  protected readonly etiquetaDelValor = computed(() =>
    this.formulario().modo === 'MARGEN' ? 'Margen (%)' : 'Porcentaje (%)',
  );

  /** Hace falta el modo y un valor numérico, y que no haya otra vista previa en camino ni se esté aplicando. */
  protected readonly puedeGenerar = computed(() => {
    const f = this.formulario();
    return (
      (f.modo ?? null) !== null &&
      parsearValor(f.valor ?? '') !== null &&
      !this.generando() &&
      !this.aplicando()
    );
  });

  /** Sube con cada cambio: una respuesta en vuelo de un pedido viejo se descarta. */
  private version = 0;

  constructor() {
    // Cualquier cambio del filtro, el modo o el valor deja la vista previa vieja sin efecto.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.invalidar());
  }

  ngOnInit(): void {
    if (!this.auth.esDueno()) return;
    this.cargarSelectores();
  }

  protected tarjetasDe(conteos: ConteosMasivos): { etiqueta: string; valor: number }[] {
    return [
      { etiqueta: 'Artículos', valor: conteos.total },
      { etiqueta: 'Suben', valor: conteos.suben },
      { etiqueta: 'Bajan', valor: conteos.bajan },
      { etiqueta: 'Sin cambio de precio', valor: conteos.sinCambioDePrecio },
      { etiqueta: 'Con error', valor: conteos.conError },
      { etiqueta: 'Con advertencia', valor: conteos.conAdvertencia },
    ];
  }

  /** `uno` si n es 1; `varios` en cualquier otro caso (0 incluido). */
  protected plural(n: number, uno: string, varios: string): string {
    return n === 1 ? uno : varios;
  }

  protected generarVistaPrevia(): void {
    const pedido = this.armarPedido();
    if (pedido === null || this.generando() || this.aplicando()) return;
    // Pedir otra vista previa deja sin efecto la anterior y cualquier respuesta que todavía esté en camino.
    this.invalidar();
    const version = this.version;
    this.generando.set(true);
    this.service.vistaPrevia(pedido).subscribe({
      next: (vistaPrevia) => {
        if (version !== this.version) return;
        this.generando.set(false);
        this.pedidoVigente.set(pedido);
        this.vistaPrevia.set(vistaPrevia);
      },
      error: (err: unknown) => {
        if (version !== this.version) return;
        this.generando.set(false);
        this.mostrarError(err);
      },
    });
  }

  /**
   * Aplica lo que muestra la vista previa. Con el filtro vacío el primer clic solo abre la segunda
   * confirmación (alcanza a todo el catálogo). Con `modificados` en 0 no hay nada que aplicar.
   */
  protected confirmar(): void {
    const vistaPrevia = this.vistaPrevia();
    const pedido = this.pedidoVigente();
    if (this.aplicando() || vistaPrevia === null || pedido === null) return;
    if (vistaPrevia.conteos.modificados === 0) return;
    if (filtroVacio(pedido) && !this.confirmandoTodo()) {
      this.confirmandoTodo.set(true);
      return;
    }
    this.confirmandoTodo.set(false);
    this.limpiarMensajes();
    this.aplicando.set(true);
    this.service.aplicar({ ...pedido, huella: vistaPrevia.huella }).subscribe({
      next: (resultado) => {
        this.aplicando.set(false);
        this.descartarVistaPrevia();
        this.resultado.set(resultado);
      },
      error: (err: unknown) => {
        this.aplicando.set(false);
        // Sea cual sea el motivo, el botón vuelve recién con una vista previa nueva.
        this.descartarVistaPrevia();
        this.mostrarError(err);
      },
    });
  }

  protected cancelarConfirmacion(): void {
    this.confirmandoTodo.set(false);
  }

  /** Deja sin efecto la vista previa y lo que dependía de ella: solo se puede confirmar lo que se ve. */
  private invalidar(): void {
    this.version++;
    this.generando.set(false);
    this.descartarVistaPrevia();
    this.limpiarMensajes();
  }

  private descartarVistaPrevia(): void {
    this.vistaPrevia.set(null);
    this.pedidoVigente.set(null);
    this.confirmandoTodo.set(false);
  }

  /** Carga (o recarga) los dos selectores; un proveedor o rubro elegido que ya no existe vuelve a "Todos". */
  private cargarSelectores(): void {
    this.proveedoresService.listar().subscribe({
      next: (lista) => {
        this.proveedores.set(lista);
        soltarSiNoExiste(this.form.controls.proveedorId, lista);
      },
      error: () => this.avisar('No se pudieron cargar los proveedores.'),
    });
    this.rubrosService.listar().subscribe({
      next: (lista) => {
        this.rubros.set(lista);
        soltarSiNoExiste(this.form.controls.rubroId, lista);
      },
      error: () => this.avisar('No se pudieron cargar los rubros.'),
    });
  }

  private avisar(mensaje: string): void {
    this.erroresCampos.update((lista) => [...lista, mensaje]);
  }

  private limpiarMensajes(): void {
    this.error.set(null);
    this.erroresCampos.set([]);
    this.resultado.set(null);
  }

  private mostrarError(err: unknown): void {
    this.erroresCampos.set(erroresDeCampos(err));
    this.error.set(mensajeDe(err));
    // Un proveedor o rubro que no se encuentra suele ser uno que se borró: se refrescan las listas.
    if (err instanceof HttpErrorResponse && err.status === 404) this.cargarSelectores();
  }

  /** El pedido tal como lo escribió el Dueño; null si falta el modo o el valor no es un número. */
  private armarPedido(): PedidoMasivo | null {
    const f = this.form.getRawValue();
    const valor = parsearValor(f.valor);
    if (f.modo === null || valor === null) return null;
    return {
      proveedorId: f.proveedorId,
      rubroId: f.rubroId,
      q: f.q.trim() === '' ? null : f.q.trim(),
      incluirInactivos: f.incluirInactivos,
      modo: f.modo,
      valor,
    };
  }
}

/** Sin proveedor, sin rubro y sin texto: el cambio alcanza a todo el catálogo ("dados de baja" no cuenta). */
function filtroVacio(pedido: PedidoMasivo): boolean {
  return pedido.proveedorId === null && pedido.rubroId === null && pedido.q === null;
}

/** El valor escrito como número (coma o punto decimal); null si no es un número. */
function parsearValor(texto: string): number | null {
  const limpio = texto.trim();
  return NUMERO.test(limpio) ? Number(limpio.replace(',', '.')) : null;
}

/** Sin `emitEvent`: soltar la selección no debe descartar la vista previa ni borrar el mensaje de error. */
function soltarSiNoExiste(control: FormControl<number | null>, lista: { id: number }[]): void {
  if (control.value !== null && !lista.some((x) => x.id === control.value)) {
    control.setValue(null, { emitEvent: false });
  }
}

/**
 * El mensaje del servidor si el error es de los que el Dueño puede corregir (400, 403, 404, 409); el 409
 * tiene su texto propio si no vino ninguno. Cualquier otro error (500, sin conexión) lleva el genérico.
 */
function mensajeDe(err: unknown): string {
  if (!(err instanceof HttpErrorResponse) || !ESTADOS_CON_MENSAJE.includes(err.status)) {
    return MENSAJE_GENERICO;
  }
  const mensaje: unknown = err.error?.message;
  if (typeof mensaje === 'string' && mensaje.trim() !== '') return mensaje;
  return err.status === 409 ? MENSAJE_CONFLICTO : MENSAJE_GENERICO;
}
