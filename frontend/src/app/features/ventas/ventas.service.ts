import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../core/api/page.models';
import { FiltroVentas, VentaConsultada, VentaResumen } from './ventas.models';

const TAMANIO_POR_DEFECTO = 20;

/** Consulta de las ventas hechas (G60). Solo el Dueño: al Vendedor el servidor le responde 403. */
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
    return this.http.get<PageResponse<VentaResumen>>(this.url, { params });
  }

  obtener(id: number): Observable<VentaConsultada> {
    return this.http.get<VentaConsultada>(`${this.url}/${id}`);
  }
}
