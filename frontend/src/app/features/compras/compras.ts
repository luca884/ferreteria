import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe } from '../../core/formato/monto';
import { cantidadConUnidad, porUnidad } from '../../core/formato/unidad';
import { BarraAcciones } from '../../shared/barra-acciones';
import { DialogoDetalle } from '../../shared/dialogo-detalle';
import { origenDeLaNavegacion } from '../../shared/documento-por-ruta';
import { HASTA_ANTERIOR_A_DESDE, rangoAlReves } from '../../shared/rango-de-fechas';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { CompraResponse, CompraResumen, FiltroCompras } from './compra.models';
import { ComprasService } from './compras.service';

const MENSAJE_LISTA = 'No se pudieron cargar las compras.';
const MENSAJE_DETALLE = 'No se pudo cargar la compra.';
/** Una compra que no existe o es de otra ferretería: el servidor da 404 en los dos casos (regla 3 de CLAUDE.md). */
const MENSAJE_NO_ENCONTRADA = 'No se encontró la compra.';

/** «20/10/2026» de una fecha «aaaa-mm-dd», leída del texto: no pasa por la zona del navegador. */
export function fechaDeCompra(fecha: string): string {
  const [anio, mes, dia] = fecha.slice(0, 10).split('-');
  return `${dia}/${mes}/${anio}`;
}

/**
 * Compras al proveedor (CU-13): la lista con sus filtros (proveedor y fechas) y el detalle de una, con el costo y el
 * precio de cada artículo antes y después de la compra. Solo el Dueño.
 */
@Component({
  selector: 'app-compras',
  imports: [BarraAcciones, DecimalPipe, DialogoDetalle, MontoPipe, RouterLink],
  templateUrl: './compras.html',
  styleUrl: './compras.css',
})
export class Compras implements OnInit {
  protected readonly cantidadConUnidad = cantidadConUnidad;
  protected readonly porUnidad = porUnidad;
  private readonly api = inject(ComprasService);
  private readonly proveedoresApi = inject(ProveedoresService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);
  protected readonly fecha = fechaDeCompra;

  protected readonly proveedores = signal<Proveedor[]>([]);
  protected readonly pagina = signal<PageResponse<CompraResumen> | null>(null);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  /** Lo que eligió el usuario en los filtros (todavía sin aplicar). */
  protected readonly proveedorId = signal('');
  protected readonly desde = signal('');
  protected readonly hasta = signal('');
  /** El rango al revés: se avisa, no se busca y se enfoca «Hasta» (bugs-relevamiento-celular, S4 y S12). */
  protected readonly errorFiltros = signal<string | null>(null);
  private readonly campoHasta = viewChild<ElementRef<HTMLInputElement>>('campoHasta');
  private aplicados: Omit<FiltroCompras, 'page'> = {};
  protected readonly filtrada = signal(false);

  protected readonly elegida = signal<number | null>(null);
  protected readonly detalle = signal<CompraResponse | null>(null);
  protected readonly errorDetalle = signal<string | null>(null);
  /** La compra se abrió por `/compras/:id` (la ficha del artículo): al cerrarla se vuelve a `/compras`. */
  private abiertaPorRuta = false;
  /**
   * El documento desde cuyo link se abrió (la ficha del artículo): al cerrar se vuelve ahí (bugs-relevamiento-celular,
   * S7). Se lee al crear la pantalla, que es durante la navegación; en `ngOnInit` la navegación ya terminó.
   */
  private origen: string | null = origenDeLaNavegacion(inject(Router));

  /** El título del pop-up: de la fila mientras el detalle carga, para que se sepa qué compra se abrió. */
  protected readonly tituloDetalle = computed(() => {
    const id = this.elegida();
    const comprobante = this.detalle()?.comprobante ?? this.pagina()?.items.find((c) => c.id === id)?.comprobante;
    return comprobante ?? 'Detalle de la compra';
  });

  ngOnInit(): void {
    if (!this.auth.esDueno()) return;
    // Con los dados de baja: una compra vieja puede ser de un proveedor que ya no está activo.
    this.proveedoresApi.listar(true).subscribe({ next: (lista) => this.proveedores.set(lista), error: () => undefined });
    this.cargar(0);
    // `/compras/:id` abre esa compra, como si se hubiera elegido en la lista (un id ajeno o inexistente da el aviso de siempre).
    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (Number.isInteger(id) && id > 0) {
      this.abiertaPorRuta = true;
      this.abrir(id);
    }
  }

  protected cargar(numeroDePagina: number): void {
    this.cargando.set(true);
    this.error.set(null);
    this.api.listar({ page: numeroDePagina, ...this.aplicados }).subscribe({
      next: (pagina) => {
        this.pagina.set(pagina);
        this.cargando.set(false);
      },
      error: () => {
        this.cargando.set(false);
        this.error.set(MENSAJE_LISTA);
      },
    });
  }

  protected buscar(evento: Event): void {
    evento.preventDefault();
    if (rangoAlReves(this.desde(), this.hasta())) {
      this.errorFiltros.set(HASTA_ANTERIOR_A_DESDE);
      this.campoHasta()?.nativeElement.focus();
      return;
    }
    this.errorFiltros.set(null);
    this.aplicados = {
      ...(this.proveedorId() ? { proveedorId: Number(this.proveedorId()) } : {}),
      ...(this.desde() ? { desde: this.desde() } : {}),
      ...(this.hasta() ? { hasta: this.hasta() } : {}),
    };
    this.filtrada.set(Object.keys(this.aplicados).length > 0);
    this.cargar(0);
  }

  protected limpiar(): void {
    this.proveedorId.set('');
    this.desde.set('');
    this.hasta.set('');
    this.errorFiltros.set(null);
    this.aplicados = {};
    this.filtrada.set(false);
    this.cargar(0);
  }

  protected cerrarDetalle(): void {
    this.elegida.set(null);
    this.detalle.set(null);
    this.errorDetalle.set(null);
    // Abierta por `/compras/:id`: la dirección deja de nombrar una compra que ya no se ve. Si vino del link de la ficha
    // del artículo, vuelve a la ficha (bugs-relevamiento-celular, S7).
    if (this.abiertaPorRuta) {
      this.abiertaPorRuta = false;
      void this.router.navigateByUrl(this.origen ?? '/compras');
      this.origen = null;
    }
  }

  protected elegir(fila: CompraResumen): void {
    this.abrir(fila.id);
  }

  private abrir(id: number): void {
    this.elegida.set(id);
    this.detalle.set(null);
    this.errorDetalle.set(null);
    this.api.obtener(id).subscribe({
      next: (compra) => {
        // Si mientras tanto se eligió otra, esta respuesta ya no importa.
        if (this.elegida() === id) this.detalle.set(compra);
      },
      error: (err: unknown) => {
        if (this.elegida() !== id) return;
        const noEsta = err instanceof HttpErrorResponse && err.status === 404;
        this.errorDetalle.set(noEsta ? MENSAJE_NO_ENCONTRADA : MENSAJE_DETALLE);
      },
    });
  }
}
