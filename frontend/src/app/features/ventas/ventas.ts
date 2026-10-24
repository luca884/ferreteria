import { Component, OnInit, inject, signal } from '@angular/core';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { fechaCompletaLocal } from '../clientes/cuenta/cobranza.models';
import { VentaHoja } from './venta-hoja';
import { VentaImprimible } from './venta-imprimible';
import { FiltroVentas, VentaConsultada, VentaResumen, clienteDeVenta } from './ventas.models';
import { VentasService } from './ventas.service';

const MENSAJE_LISTA = 'No se pudieron cargar las ventas.';
const MENSAJE_DETALLE = 'No se pudo cargar la venta.';
const MENSAJE_NUMERO = 'El número tiene que ser un entero.';

/**
 * Consulta y reimpresión de ventas (G60), como la «Consulta de Facturas» de GDS: busca por número, fecha o cliente, muestra
 * la venta elegida y la reimprime. Sin anular ni borrar (GDS-15): anular es la nota de crédito (CU-08, fase 5). Solo el
 * Dueño: con el listado se podría sumar el efectivo del día y el cierre de caja dejaría de ser ciego.
 */
@Component({
  selector: 'app-ventas',
  imports: [BarraAcciones, MontoPipe, VentaHoja, VentaImprimible],
  templateUrl: './ventas.html',
  styleUrl: './ventas.css',
})
export class Ventas implements OnInit {
  private readonly api = inject(VentasService);
  protected readonly auth = inject(AuthService);

  protected readonly fecha = fechaCompletaLocal;
  protected readonly cliente = clienteDeVenta;

  protected readonly pagina = signal<PageResponse<VentaResumen> | null>(null);
  protected readonly cargando = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Lo que escribió el usuario en los filtros (todavía sin aplicar). */
  protected readonly numero = signal('');
  protected readonly clienteBuscado = signal('');
  protected readonly desde = signal('');
  protected readonly hasta = signal('');
  protected readonly errorFiltros = signal<string | null>(null);
  /** Los filtros aplicados a la lista que se ve (sin `page`). */
  private aplicados: Omit<FiltroVentas, 'page'> = {};
  protected readonly filtrada = signal(false);

  protected readonly elegida = signal<number | null>(null);
  protected readonly detalle = signal<VentaConsultada | null>(null);
  protected readonly errorDetalle = signal<string | null>(null);
  protected readonly imprimiendo = signal(false);

  ngOnInit(): void {
    if (this.auth.esDueno()) this.cargar(0);
  }

  protected cargar(numeroDePagina: number): void {
    this.cargando.set(true);
    this.error.set(null);
    this.api.listar({ page: numeroDePagina, ...this.aplicados }).subscribe({
      next: (pagina) => {
        this.pagina.set(pagina);
        this.cargando.set(false);
        // Un detalle abierto de una venta que la lista nueva ya no muestra confunde: se cierra.
        const elegida = this.elegida();
        if (elegida !== null && !pagina.items.some((v) => v.ventaId === elegida)) this.cerrarDetalle();
      },
      error: () => {
        this.cargando.set(false);
        this.error.set(MENSAJE_LISTA);
      },
    });
  }

  /** Aplica los filtros y vuelve a la primera página. Un número que no es un entero positivo no se manda. */
  protected buscar(evento: Event): void {
    evento.preventDefault();
    const numero = this.numero().trim();
    if (numero !== '' && !/^[1-9]\d{0,17}$/.test(numero)) {
      this.errorFiltros.set(MENSAJE_NUMERO);
      return;
    }
    this.errorFiltros.set(null);
    const cliente = this.clienteBuscado().trim();
    this.aplicados = {
      ...(numero ? { numero: Number(numero) } : {}),
      ...(cliente ? { cliente } : {}),
      ...(this.desde() ? { desde: this.desde() } : {}),
      ...(this.hasta() ? { hasta: this.hasta() } : {}),
    };
    this.filtrada.set(Object.keys(this.aplicados).length > 0);
    this.cargar(0);
  }

  protected limpiar(): void {
    this.numero.set('');
    this.clienteBuscado.set('');
    this.desde.set('');
    this.hasta.set('');
    this.errorFiltros.set(null);
    this.aplicados = {};
    this.filtrada.set(false);
    this.cargar(0);
  }

  private cerrarDetalle(): void {
    this.elegida.set(null);
    this.detalle.set(null);
    this.errorDetalle.set(null);
    this.imprimiendo.set(false);
  }

  protected elegir(fila: VentaResumen): void {
    const id = fila.ventaId;
    this.elegida.set(id);
    this.detalle.set(null);
    this.errorDetalle.set(null);
    this.imprimiendo.set(false);
    this.api.obtener(id).subscribe({
      next: (venta) => {
        // Si mientras tanto se eligió otra, esta respuesta ya no importa.
        if (this.elegida() === id) this.detalle.set(venta);
      },
      error: () => {
        if (this.elegida() === id) this.errorDetalle.set(MENSAJE_DETALLE);
      },
    });
  }
}
