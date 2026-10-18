import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../core/api/page.models';
import { EmitirRemitoRequest, FiltroRemitos, RemitoEmitido, RemitoResponse, RemitoResumen } from './remito.models';

const TAMANIO_POR_DEFECTO = 20;

@Injectable({ providedIn: 'root' })
export class RemitosService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/remitos`;

  /**
   * Emite el remito. 201 es nuevo y 200 es la misma clave ya registrada con el mismo contenido. Los rechazos (404, 409,
   * 422) son los de la venta: llegan como `HttpErrorResponse` y `rechazoDe` extrae su cuerpo tipado.
   */
  emitir(pedido: EmitirRemitoRequest): Observable<RemitoEmitido> {
    return this.http
      .post<RemitoResponse>(this.url, pedido, { observe: 'response' })
      .pipe(map((res) => ({ remito: res.body as RemitoResponse, repetido: res.status === 200 })));
  }

  /** Los de la ferretería, más nuevos primero. Un filtro sin elegir no se manda. */
  listar(filtro: FiltroRemitos): Observable<PageResponse<RemitoResumen>> {
    let params = new HttpParams().set('page', filtro.page).set('size', filtro.size ?? TAMANIO_POR_DEFECTO);
    if (filtro.estado) params = params.set('estado', filtro.estado);
    if (filtro.numero !== undefined) params = params.set('numero', filtro.numero);
    if (filtro.desde) params = params.set('desde', filtro.desde);
    if (filtro.hasta) params = params.set('hasta', filtro.hasta);
    return this.http.get<PageResponse<RemitoResumen>>(this.url, { params });
  }

  obtener(id: number): Observable<RemitoResponse> {
    return this.http.get<RemitoResponse>(`${this.url}/${id}`);
  }

  /** Anula un remito emitido y devuelve el stock. Solo el Dueño; uno ya facturado responde 409 con su mensaje. */
  anular(id: number): Observable<RemitoResponse> {
    return this.http.patch<RemitoResponse>(`${this.url}/${id}/anular`, null);
  }
}
