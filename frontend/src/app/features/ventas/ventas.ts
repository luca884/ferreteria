import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { FacturaImprimible } from '../../shared/factura/factura-imprimible';
import { ESTADO_FISCAL_LABELS, ResumenFiscal, VentaFacturada, numeroFiscal } from '../../shared/factura/factura.models';
import { fechaCompletaLocal } from '../clientes/cuenta/cobranza.models';
import { VentaHoja } from './venta-hoja';
import { VentaImprimible } from './venta-imprimible';
import { FiltroVentas, VentaConsultada, VentaResumen, clienteDeVenta } from './ventas.models';
import { VentasService } from './ventas.service';

const MENSAJE_LISTA = 'No se pudieron cargar las ventas.';
const MENSAJE_DETALLE = 'No se pudo cargar la venta.';
const MENSAJE_NUMERO = 'El número tiene que ser un entero.';

/** La marca del ambiente en la columna «Factura»: en producción no hay. */
const AMBIENTE_LABELS: Record<string, string> = { SIMULADOR: 'Simulado', HOMOLOGACION: 'Homologación' };

/**
 * Consulta y reimpresión de ventas (G60), como la «Consulta de Facturas» de GDS: busca por número, fecha o cliente, muestra
 * la venta elegida y la reimprime. Sin anular ni borrar (GDS-15): anular es la nota de crédito (CU-08, fase 5). Solo el
 * Dueño: con el listado se podría sumar el efectivo del día y el cierre de caja dejaría de ser ciego.
 */
@Component({
  selector: 'app-ventas',
  imports: [BarraAcciones, FacturaImprimible, MontoPipe, VentaHoja, VentaImprimible],
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
  protected readonly numeroFactura = signal('');
  protected readonly errorFiltros = signal<string | null>(null);
  /** Los filtros aplicados a la lista que se ve (sin `page`). */
  private aplicados: Omit<FiltroVentas, 'page'> = {};
  /** Los de la última lista que cargó: si el servidor rechaza un filtro nuevo (400), se vuelve a estos. */
  private vigentes: Omit<FiltroVentas, 'page'> = {};
  protected readonly filtrada = signal(false);

  protected readonly elegida = signal<number | null>(null);
  protected readonly detalle = signal<VentaConsultada | null>(null);
  protected readonly errorDetalle = signal<string | null>(null);
  protected readonly imprimiendo = signal(false);
  /** La venta elegida, si tiene registro fiscal con su documento: «Imprimir» imprime la factura (CU-16). */
  protected readonly facturada = computed<VentaFacturada | null>(() => {
    const venta = this.detalle();
    return venta?.fiscal?.documento ? { ...venta, fiscal: venta.fiscal } : null;
  });
  /**
   * La factura en una línea, arriba del detalle de siempre: «Factura B 00001-00000012 · Autorizada», o «X · <marca>» si
   * no es válida como factura. Sale solo del documento, como la hoja.
   */
  protected readonly lineaFiscal = computed<string | null>(() => {
    const documento = this.facturada()?.fiscal.documento;
    if (!documento) return null;
    if (!documento.validoComoFactura) return documento.marca ? `X · ${documento.marca}` : 'X';
    const { letra, puntoVenta, numero } = documento;
    const fiscal = puntoVenta != null && numero != null ? ` ${numeroFiscal(puntoVenta, numero)}` : '';
    return `Factura ${letra}${fiscal} · Autorizada`;
  });

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
        this.vigentes = this.aplicados;
        // Un detalle abierto de una venta que la lista nueva ya no muestra confunde: se cierra.
        const elegida = this.elegida();
        if (elegida !== null && !pagina.items.some((v) => v.ventaId === elegida)) this.cerrarDetalle();
      },
      error: (err: unknown) => {
        this.cargando.set(false);
        // Un filtro que el servidor no acepta (el número de factura, S15): su mensaje va con los filtros y la lista que se
        // ve sigue siendo la de los filtros anteriores.
        if (err instanceof HttpErrorResponse && err.status === 400) {
          const campos = erroresDeCampos(err);
          this.errorFiltros.set(campos.length > 0 ? campos.join(' ') : mensajeDe(err, MENSAJE_LISTA));
          this.aplicados = this.vigentes;
          this.filtrada.set(Object.keys(this.aplicados).length > 0);
          return;
        }
        this.error.set(MENSAJE_LISTA);
      },
    });
  }

  /** «B 00001-00000012» y, aparte, el estado si no está autorizada y el ambiente si no es producción. Vacía sin factura. */
  protected factura(fiscal: ResumenFiscal | null | undefined): { texto: string; marcas: string } | null {
    if (!fiscal) return null;
    const numero = fiscal.numero == null ? '' : ` ${numeroFiscal(fiscal.puntoVenta, fiscal.numero)}`;
    const marcas = [
      fiscal.estado === 'AUTORIZADO' ? null : ESTADO_FISCAL_LABELS[fiscal.estado],
      AMBIENTE_LABELS[fiscal.ambiente] ?? null,
    ].filter((m): m is string => m !== null);
    return { texto: `${fiscal.letra}${numero}`, marcas: marcas.join(' · ') };
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
    const numeroFiscal = this.numeroFactura().trim();
    this.aplicados = {
      ...(numero ? { numero: Number(numero) } : {}),
      ...(cliente ? { cliente } : {}),
      ...(this.desde() ? { desde: this.desde() } : {}),
      ...(this.hasta() ? { hasta: this.hasta() } : {}),
      ...(numeroFiscal ? { numeroFiscal } : {}),
    };
    this.filtrada.set(Object.keys(this.aplicados).length > 0);
    this.cargar(0);
  }

  protected limpiar(): void {
    this.numero.set('');
    this.clienteBuscado.set('');
    this.desde.set('');
    this.hasta.set('');
    this.numeroFactura.set('');
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
