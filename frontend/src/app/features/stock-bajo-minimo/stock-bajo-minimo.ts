import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { formatearFecha } from '../../core/formato/fecha-hora';
import { formatearCosto } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { MotivoDeAccion, Motivos, ariaDe } from '../../shared/motivo-de-accion';
import { ArticuloBajoMinimo, ORDENES, OrdenBajoMinimo } from './stock-bajo-minimo.models';
import { StockBajoMinimoService } from './stock-bajo-minimo.service';

const MENSAJE_ERROR = 'No se pudo cargar el listado.';

/** S5: lo que se explica al intentar imprimir antes de que llegue el listado. */
const MOTIVO_SIN_DATOS = 'El listado todavía no está listo. Esperá un segundo y probá de nuevo.';

const NUMERO = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 3 });

const FORMATO_EMITIDO = new Intl.DateTimeFormat('es-AR', {
  timeZone: 'America/Argentina/Buenos_Aires',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** «23/10/2026 12:30»: el instante dicho en hora de Buenos Aires, que es la del negocio. */
function emitidoEl(ahora: Date): string {
  const p = Object.fromEntries(FORMATO_EMITIDO.formatToParts(ahora).map((parte) => [parte.type, parte.value]));
  return `${p['day']}/${p['month']}/${p['year']} ${p['hour']}:${p['minute']}`;
}

/**
 * Artículos bajo el stock mínimo (CU-09 punto 1), como el «Listado de Artículos bajo Stock Mínimo» de GDS (G72): se elige
 * el orden, se ve la lista con cuánto reponer y se imprime para salir a pedir. Solo el Dueño: muestra el costo. Al imprimir
 * solo sale `.stock-imprimible` (reglas `@media print` en styles.css).
 */
@Component({
  selector: 'app-stock-bajo-minimo',
  imports: [BarraAcciones],
  templateUrl: './stock-bajo-minimo.html',
  styleUrl: './stock-bajo-minimo.css',
})
export class StockBajoMinimo implements OnInit {
  private readonly api = inject(StockBajoMinimoService);
  protected readonly auth = inject(AuthService);

  protected readonly ordenes = ORDENES;
  protected readonly orden = signal<OrdenBajoMinimo>('DESCRIPCION');
  protected readonly filas = signal<ArticuloBajoMinimo[] | null>(null);
  protected readonly cargando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly emitido = signal(emitidoEl(new Date()));
  /** Por qué Imprimir no se puede usar: null cuando el listado ya está (S5). */
  protected readonly motivos = computed<Motivos<'imprimir'>>(() => ({
    imprimir: this.filas() === null ? MOTIVO_SIN_DATOS : null,
  }));
  /** El motivo a la vista de la última acción que no se pudo usar, o null. Se anuncia con role="status" (S5). */
  protected readonly motivo = new MotivoDeAccion(() => this.motivos());
  /** Para la plantilla: 'true' apaga el botón, null lo deja prendido. */
  protected readonly ariaDe = ariaDe;

  protected readonly ferreteria = computed(() => this.auth.sesion()?.ferreteriaNombre ?? null);
  protected readonly textoOrden = computed(
    () => this.ordenes.find((o) => o.valor === this.orden())!.texto.toLowerCase(),
  );

  protected readonly costo = (f: ArticuloBajoMinimo) => formatearCosto(f.costo, f.moneda);
  protected readonly numero = (n: number) => NUMERO.format(n);
  /**
   * «12/10/2026 · Bulonera Sur» (bloque 2, S6), en dos partes: la fecha no se parte y el proveedor puede bajar de
   * renglón (bugs de la guía, S11). Sin compras, null y la celda muestra un guion.
   */
  protected readonly ultimaCompra = (f: ArticuloBajoMinimo) =>
    f.ultimaCompraFecha ? { fecha: formatearFecha(f.ultimaCompraFecha), proveedor: f.ultimaCompraProveedor } : null;

  ngOnInit(): void {
    if (this.auth.esDueno()) this.cargar();
  }

  protected ordenar(valor: string): void {
    this.orden.set(valor as OrdenBajoMinimo);
    this.cargar();
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.error.set(null);
    this.api.listar(this.orden()).subscribe({
      next: (filas) => {
        this.filas.set(filas);
        this.emitido.set(emitidoEl(new Date()));
        this.cargando.set(false);
      },
      error: () => {
        this.filas.set(null);
        this.cargando.set(false);
        this.error.set(MENSAJE_ERROR);
      },
    });
  }

  protected imprimir(): void {
    // S5: sin listado el botón avisa el motivo en vez de quedar apagado sin explicación.
    if (this.motivo.mostrar('imprimir')) return;
    this.motivo.limpiar();
    window.print();
  }
}
