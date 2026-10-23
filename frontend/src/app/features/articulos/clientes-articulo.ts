import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { formatearFecha } from '../../core/formato/fecha-hora';
import { cantidadConUnidad } from '../../core/formato/unidad';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Articulo, ClientesDelArticulo } from './articulo.models';
import { ArticulosService } from './articulos.service';

const MENSAJE_LISTA = 'No se pudieron cargar los clientes.';

const NUMERO = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 3 });

/**
 * Clientes que compraron un artículo (G29), como el Ctrl+A del listado de artículos de GDS: cada cliente con cuántas
 * veces lo compró, cuánto llevó y la última compra. Las ventas sin cliente solo se cuentan al pie. Solo el Dueño.
 */
@Component({
  selector: 'app-clientes-articulo',
  imports: [BarraAcciones, RouterLink],
  templateUrl: './clientes-articulo.html',
  styleUrl: './clientes-articulo.css',
})
export class ClientesArticulo implements OnInit {
  private readonly api = inject(ArticulosService);
  protected readonly auth = inject(AuthService);
  protected readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  protected readonly fecha = formatearFecha;

  protected readonly articulo = signal<Articulo | null>(null);
  protected readonly pagina = signal<ClientesDelArticulo | null>(null);
  protected readonly cargando = signal(false);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    if (!this.auth.esDueno()) return;
    this.api.obtener(this.id).subscribe({ next: (a) => this.articulo.set(a), error: () => undefined });
    this.cargar(0);
  }

  /** Con la unidad del artículo cuando ya llegó («12,5 metros»); si no, solo el número. */
  protected cantidad(n: number): string {
    const a = this.articulo();
    return a ? cantidadConUnidad(n, a.unidad) : NUMERO.format(n);
  }

  protected sinCliente(n: number): string {
    return `Además hubo ${n} ${n === 1 ? 'venta' : 'ventas'} sin cliente (consumidor final).`;
  }

  protected cargar(numeroDePagina: number): void {
    this.cargando.set(true);
    this.error.set(null);
    this.api.clientes(this.id, numeroDePagina).subscribe({
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
}
