import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../core/api/page.models';
import { FiltroVentas, VentaConsultada, VentaResumen } from './ventas.models';

const TAMANIO_POR_DEFECTO = 20;

/**
 * Consulta de las ventas hechas (G60) y las acciones de la bandeja (CU-16 punto 7). Solo el Dueño: al Vendedor el servidor
 * le responde 403, salvo la hoja fiscal de las ventas de su caja abierta a las que les llegó el CAE (C7).
 */
@Injectable({ providedIn: 'root' })
export class VentasService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/ventas`;

  /** Las de la ferretería, más nuevas primero. Un filtro sin elegir no se manda. */
  listar(filtro: FiltroVentas): Observable<PageResponse<VentaResumen>> {
    let params = new HttpParams().set('page', filtro.page).set('size', filtro.size ?? TAMANIO_POR_DEFECTO);
    if (filtro.numero !== undefined) params = params.set('numero', filtro.numero);
    if (filtro.cliente) params = params.set('cliente', filtro.cliente);
    if (filtro.desde) params = params.set('desde', filtro.desde);
    if (filtro.hasta) params = params.set('hasta', filtro.hasta);
    if (filtro.numeroFiscal) params = params.set('numeroFiscal', filtro.numeroFiscal);
    if (filtro.fiscal) params = params.set('fiscal', filtro.fiscal);
    return this.http.get<PageResponse<VentaResumen>>(this.url, { params });
  }

  /** El detalle de una venta, solo del Dueño (CU-16). */
  obtener(id: number): Observable<VentaConsultada> {
    return this.http.get<VentaConsultada>(`${this.url}/${id}`);
  }

  /**
   * La venta con su factura para reimprimirla cuando llega el CAE (C7). Responde lo mismo que el detalle, pero el
   * Vendedor solo puede pedir sus ventas de la caja abierta que salieron con ticket provisorio: cualquier otra da 404.
   */
  factura(id: number): Observable<VentaConsultada> {
    return this.http.get<VentaConsultada>(`${this.url}/${id}/factura`);
  }

  /**
   * «Reintentar» (C2): arma una factura nueva de una rechazada o fuera de fecha y espera el CAE como el cobro. Con
   * `fechaDeHoy` el Dueño confirmó que salga con la fecha de hoy. Los rechazos (409, 422) llegan como `HttpErrorResponse`.
   * La pantalla vuelve a leer la venta después: la respuesta no se usa.
   */
  reintentar(id: number, fechaDeHoy: boolean): Observable<unknown> {
    return this.http.post<unknown>(`${this.url}/${id}/factura/reintentar`, { fechaDeHoy });
  }

  /** «Dejar sin factura» (C3): definitivo, con el motivo. 400 si el motivo no vale; 409 si no hay rechazada. */
  dejarSinFactura(id: number, motivo: string): Observable<unknown> {
    return this.http.post<unknown>(`${this.url}/${id}/factura/sin-factura`, { motivo });
  }

  /**
   * «Anular» (CU-08): con la factura autorizada, la nota de crédito (sin motivo); sin CAE, la anulación interna con el
   * motivo. Responde 201 con la nota como la ve el detalle. 400 en `motivo`; 409 y 422 con `message` y `motivo`.
   */
  anular(id: number, motivo: string | null): Observable<VentaConsultada> {
    return this.http.post<VentaConsultada>(`${this.url}/${id}/anular`, motivo === null ? {} : { motivo });
  }
}
