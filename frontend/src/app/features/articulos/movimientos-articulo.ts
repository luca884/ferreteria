import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, OnInit, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { cantidadConUnidad } from '../../core/formato/unidad';
import { BarraAcciones } from '../../shared/barra-acciones';
import { HASTA_ANTERIOR_A_DESDE, rangoAlReves } from '../../shared/rango-de-fechas';
import { fechaCompletaLocal } from '../clientes/cuenta/cobranza.models';
import { Articulo, FiltroMovimientos, MovimientoStock, TipoMovimientoStock } from './articulo.models';
import { ArticulosService } from './articulos.service';

const MENSAJE_LISTA = 'No se pudieron cargar los movimientos.';
const MENSAJE_INEXISTENTE = 'El artículo no existe.';

const TIPOS: Record<TipoMovimientoStock, string> = {
  VENTA: 'Venta',
  AJUSTE: 'Ajuste',
  REMITO: 'Remito',
  ANULACION_REMITO: 'Anulación de remito',
  COMPRA: 'Compra',
  NOTA_CREDITO: 'Nota de crédito',
};

const NUMERO = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 3 });

/**
 * Movimientos de stock de un artículo (G29), como «Movimientos del Artículo» de GDS: cada entrada y salida con su
 * comprobante, quién la hizo y el saldo que quedó, filtrable por Desde y Hasta. Solo el Dueño, como la consulta de ventas.
 */
@Component({
  selector: 'app-movimientos-articulo',
  imports: [BarraAcciones, RouterLink],
  templateUrl: './movimientos-articulo.html',
  styleUrl: './movimientos-articulo.css',
})
export class MovimientosArticulo implements OnInit {
  private readonly api = inject(ArticulosService);
  protected readonly auth = inject(AuthService);
  protected readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  protected readonly fecha = fechaCompletaLocal;
  protected readonly tipo = (t: TipoMovimientoStock) => TIPOS[t];
  /** El saldo con la unidad del artículo («18,5 kilos»); si el artículo no cargó, el número solo. */
  protected numero(n: number): string {
    const articulo = this.articulo();
    return articulo ? cantidadConUnidad(n, articulo.unidad) : NUMERO.format(n);
  }
  /** La cantidad con signo y unidad: «+20 kilos», «-1,5 kilos». */
  protected conSigno(n: number): string {
    return n > 0 ? `+${this.numero(n)}` : this.numero(n);
  }

  protected readonly articulo = signal<Articulo | null>(null);
  protected readonly pagina = signal<PageResponse<MovimientoStock> | null>(null);
  protected readonly cargando = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Un artículo inexistente o de otra ferretería (404) no se arregla pidiendo de nuevo. */
  protected readonly reintentable = signal(true);

  /** Lo que escribió el usuario en los filtros (todavía sin aplicar). */
  protected readonly desde = signal('');
  protected readonly hasta = signal('');
  /** El rango al revés: se avisa, no se busca y se enfoca «Hasta» (bugs-relevamiento-celular, S4 y S12). */
  protected readonly errorFiltros = signal<string | null>(null);
  private readonly campoHasta = viewChild<ElementRef<HTMLInputElement>>('campoHasta');
  /** Los filtros aplicados a la lista que se ve (sin `page`). */
  private aplicados: Omit<FiltroMovimientos, 'page'> = {};
  protected readonly filtrada = signal(false);

  ngOnInit(): void {
    if (!this.auth.esDueno()) return;
    this.api.obtener(this.id).subscribe({ next: (a) => this.articulo.set(a), error: () => undefined });
    this.cargar(0);
  }

  protected stock(a: Articulo): string {
    return cantidadConUnidad(a.stockActual, a.unidad);
  }

  protected cargar(numeroDePagina: number): void {
    this.cargando.set(true);
    this.error.set(null);
    this.api.movimientos(this.id, { page: numeroDePagina, ...this.aplicados }).subscribe({
      next: (pagina) => {
        this.pagina.set(pagina);
        this.cargando.set(false);
      },
      error: (err: unknown) => {
        const inexistente = err instanceof HttpErrorResponse && err.status === 404;
        this.cargando.set(false);
        this.reintentable.set(!inexistente);
        this.error.set(inexistente ? MENSAJE_INEXISTENTE : MENSAJE_LISTA);
      },
    });
  }

  /** Aplica Desde y Hasta y vuelve a la primera página. */
  protected buscar(evento: Event): void {
    evento.preventDefault();
    if (rangoAlReves(this.desde(), this.hasta())) {
      this.errorFiltros.set(HASTA_ANTERIOR_A_DESDE);
      this.campoHasta()?.nativeElement.focus();
      return;
    }
    this.errorFiltros.set(null);
    this.aplicados = {
      ...(this.desde() ? { desde: this.desde() } : {}),
      ...(this.hasta() ? { hasta: this.hasta() } : {}),
    };
    this.filtrada.set(Object.keys(this.aplicados).length > 0);
    this.cargar(0);
  }

  protected limpiar(): void {
    this.desde.set('');
    this.hasta.set('');
    this.errorFiltros.set(null);
    this.aplicados = {};
    this.filtrada.set(false);
    this.cargar(0);
  }
}
