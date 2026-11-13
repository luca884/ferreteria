import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Subscription } from 'rxjs';
import { erroresDeCampos, mensajeDe } from '../../../core/api/api-error';
import { MontoPipe } from '../../../core/formato/monto';
import { MotivoDeAccion, Motivos, ariaDe } from '../../../shared/motivo-de-accion';
import { textoDeSaldo } from './ajuste.models';
import { fechaCompletaLocal } from './cobranza.models';
import {
  Extracto,
  FILTRO_VACIO,
  FiltroExtracto,
  LineaExtracto,
  TIPOS_MOVIMIENTO_CUENTA,
  TIPO_MOVIMIENTO_LABELS,
  TipoMovimiento,
  muestraMotivo,
  puedeAnular,
  puedeVerRecibo,
} from './extracto.models';
import { ExtractoImprimible } from './extracto-imprimible';
import { ExtractoService } from './extracto.service';

type Estado = 'cargando' | 'listo' | 'error';

const MENSAJE_CARGA = 'No se pudieron cargar los movimientos.';
const MENSAJE_IMPRESION = 'No se pudo preparar el extracto para imprimir. Probá de nuevo.';
/** S5: lo que se explica al intentar anular con el cliente desactivado (el mismo texto del aviso de la tabla). */
const MOTIVO_CLIENTE_INACTIVO = 'Reactivá el cliente para anular';
/** S5: lo que se explica al intentar imprimir sin un extracto o sin movimientos en el período. */
const MOTIVO_SIN_EXTRACTO = 'Todavía no hay extracto para imprimir.';
const MOTIVO_SIN_MOVIMIENTOS = 'No hay movimientos en el período elegido.';
/** Las acciones de la pantalla que pueden estar apagadas por una regla de negocio (S5). */
type Accion = 'anular' | 'imprimir';

/**
 * Los movimientos de la cuenta de un cliente (extracto, solo el Dueño): filtros por fecha y tipo, encabezado del período,
 * tabla con el más nuevo arriba y el saldo REAL de cada línea (aunque se filtre), y paginación. Contenedor hijo de
 * `CuentaCliente`: pide al `ExtractoService` y avisa por outputs qué quiere hacer el Dueño con una línea (`anular`,
 * `verRecibo`); el flujo lo maneja el padre. Recarga la página que se está viendo cuando cambia `version` (el padre la
 * incrementa tras anular) y empieza de cero cuando cambia el cliente.
 */
@Component({
  selector: 'app-movimientos-cuenta',
  imports: [MontoPipe, ExtractoImprimible],
  templateUrl: './movimientos-cuenta.html',
  styleUrl: './movimientos-cuenta.css',
})
export class MovimientosCuenta {
  private readonly servicio = inject(ExtractoService);

  readonly clienteId = input.required<number>();
  /** Un cliente desactivado no se puede anular (el backend lo rechaza con 422): «Anular» se deshabilita. */
  readonly clienteActivo = input(true);
  /** El padre lo incrementa para que la sección vuelva a pedir lo que está viendo. */
  readonly version = input(0);

  readonly anular = output<LineaExtracto>();
  readonly verRecibo = output<number>();

  protected readonly estado = signal<Estado>('cargando');
  protected readonly extracto = signal<Extracto | null>(null);
  protected readonly errorCarga = signal<string | null>(null);
  /** El 400 de un filtro (p. ej. desde > hasta): se muestra sin tirar abajo lo que ya se ve. */
  protected readonly errorFiltro = signal<string | null>(null);
  /** Lo que está escrito en el formulario; recién al filtrar pasa a ser el filtro pedido. */
  protected readonly borrador = signal<FiltroExtracto>(FILTRO_VACIO);

  protected readonly imprimiendo = signal(false);
  protected readonly extractoImpresion = signal<Extracto | null>(null);
  protected readonly errorImpresion = signal<string | null>(null);

  protected readonly tipos = TIPOS_MOVIMIENTO_CUENTA;
  protected readonly tipoLabels = TIPO_MOVIMIENTO_LABELS;
  protected readonly fecha = fechaCompletaLocal;
  protected readonly muestraMotivo = muestraMotivo;
  /** El saldo como arriba en la cuenta: «$ 500,00 a favor» si es negativo (#84). */
  protected readonly textoDeSaldo = textoDeSaldo;
  protected readonly puedeAnular = puedeAnular;
  protected readonly puedeVerRecibo = puedeVerRecibo;

  /** Hay algún cobro que se podría anular: solo entonces importa avisar que el cliente está desactivado. */
  protected readonly hayAnulables = computed(() => this.extracto()?.movimientos.items.some(puedeAnular) ?? false);
  /** Por qué cada acción está apagada por una regla de negocio: null en la que se puede usar (S5). */
  protected readonly motivos = computed<Motivos<Accion>>(() => {
    const extracto = this.extracto();
    return {
      anular: this.clienteActivo() ? null : MOTIVO_CLIENTE_INACTIVO,
      imprimir: extracto === null
        ? MOTIVO_SIN_EXTRACTO
        : extracto.movimientos.items.length === 0
          ? MOTIVO_SIN_MOVIMIENTOS
          : null,
    };
  });
  /** El motivo a la vista de la última acción que no se pudo usar, o null. Se anuncia con role="status" (S5). */
  protected readonly motivo = new MotivoDeAccion(() => this.motivos());
  /** Para la plantilla: 'true' apaga el botón, null lo deja prendido. */
  protected readonly ariaDe = ariaDe;
  /** El texto del aviso de cliente desactivado, uno solo para la línea y para el motivo de «Anular». */
  protected readonly MOTIVO_CLIENTE_INACTIVO = MOTIVO_CLIENTE_INACTIVO;

  /** Lo último que se pidió (para «Reintentar» y para recargar la misma página con el mismo filtro). */
  private pedido: { filtro: FiltroExtracto; page: number } = { filtro: FILTRO_VACIO, page: 0 };
  private lectura: Subscription | undefined;
  private clienteCargado: number | null = null;

  constructor() {
    // La primera corrida es la carga inicial; después, un cambio de `version` recarga lo que se está viendo y un cambio
    // de cliente empieza de cero (filtro vacío, página 0).
    effect(() => {
      const clienteId = this.clienteId();
      this.version();
      untracked(() => {
        if (clienteId !== this.clienteCargado) {
          this.clienteCargado = clienteId;
          this.borrador.set(FILTRO_VACIO);
          this.extracto.set(null);
          this.errorCarga.set(null);
          this.estado.set('cargando');
          this.cargar(FILTRO_VACIO, 0);
        } else {
          this.cargar(this.pedido.filtro, this.pedido.page);
        }
      });
    });
    inject(DestroyRef).onDestroy(() => this.lectura?.unsubscribe());
  }

  /**
   * Pide una página con un filtro. Lo que ya se ve se mantiene mientras recarga. El filtro pedido recién queda vigente
   * si el servidor lo acepta; un 400 (desde > hasta) deja lo que había como estaba y muestra el motivo del filtro.
   */
  protected cargar(filtro: FiltroExtracto, page: number): void {
    this.lectura?.unsubscribe();
    if (this.estado() !== 'listo' || this.extracto() === null) this.estado.set('cargando');
    this.errorFiltro.set(null);
    this.lectura = this.servicio.extracto(this.clienteId(), filtro, page).subscribe({
      next: (extracto) => {
        this.pedido = { filtro, page: extracto.movimientos.page };
        this.extracto.set(extracto);
        this.errorCarga.set(null);
        this.estado.set('listo');
      },
      error: (err: unknown) => this.fallar(err, filtro, page),
    });
  }

  private fallar(err: unknown, filtro: FiltroExtracto, page: number): void {
    if (err instanceof HttpErrorResponse && err.status === 400) {
      // El filtro inválido nunca queda como lo último pedido: «Reintentar» (si no hay tabla) repite el último pedido válido.
      this.errorFiltro.set(erroresDeCampos(err).join(' ') || mensajeDe(err, 'Revisá los filtros.'));
      if (this.extracto() !== null) {
        this.estado.set('listo');
      } else {
        this.errorCarga.update((actual) => actual ?? MENSAJE_CARGA);
        this.estado.set('error');
      }
      return;
    }
    this.pedido = { filtro, page };
    this.errorCarga.set(mensajeDe(err, MENSAJE_CARGA));
    this.estado.set('error');
  }

  protected reintentar(): void {
    this.cargar(this.pedido.filtro, this.pedido.page);
  }

  protected cambiar(campo: 'desde' | 'hasta', valor: string): void {
    this.borrador.update((b) => ({ ...b, [campo]: valor }));
  }

  protected cambiarTipo(valor: string): void {
    this.borrador.update((b) => ({ ...b, tipo: valor as TipoMovimiento | '' }));
  }

  /** Filtrar (botón o Enter en un campo): cualquier cambio de filtro vuelve a la página 0. */
  protected filtrar(evento: Event): void {
    evento.preventDefault();
    this.cargar(this.borrador(), 0);
  }

  protected limpiar(): void {
    this.borrador.set(FILTRO_VACIO);
    this.cargar(FILTRO_VACIO, 0);
  }

  protected irA(page: number): void {
    this.cargar(this.pedido.filtro, page);
  }

  /** «Anular» de un renglón: con el cliente desactivado avisa el motivo en vez de quedar apagado sin explicación (S5). */
  protected anularLinea(linea: LineaExtracto): void {
    if (this.motivo.mostrar('anular')) return;
    this.motivo.limpiar();
    this.anular.emit(linea);
  }

  /**
   * Imprimir: pide el período con el filtro APLICADO (el de la tabla) completo y en un solo pedido, y abre el extracto
   * imprimible. No usa `disabled` mientras espera: un botón deshabilitado pierde el foco y el diálogo no podría devolvérselo.
   */
  protected imprimir(): void {
    // S5: sin extracto o sin movimientos el botón avisa el motivo en vez de quedar apagado sin explicación.
    if (this.motivo.mostrar('imprimir')) return;
    if (this.imprimiendo()) return;
    this.motivo.limpiar();
    this.imprimiendo.set(true);
    this.errorImpresion.set(null);
    this.servicio.paraImprimir(this.clienteId(), this.pedido.filtro).subscribe({
      next: (extracto) => {
        this.imprimiendo.set(false);
        this.extractoImpresion.set(extracto);
      },
      error: () => {
        this.imprimiendo.set(false);
        this.errorImpresion.set(MENSAJE_IMPRESION);
      },
    });
  }
}
