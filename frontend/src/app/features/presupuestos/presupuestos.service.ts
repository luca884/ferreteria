import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../core/api/page.models';
import {
  EmitirPresupuestoRequest,
  EstadoManual,
  FiltroPresupuestos,
  PresupuestoEmitido,
  PresupuestoResponse,
  PresupuestoResumen,
} from './presupuesto.models';

const TAMANIO_POR_DEFECTO = 20;

@Injectable({ providedIn: 'root' })
export class PresupuestosService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/presupuestos`;

  /**
   * Guarda el presupuesto. 201 es nuevo y 200 es la misma clave ya registrada con el mismo contenido. Los rechazos
   * (404, 409, 422) son los de la venta: llegan como `HttpErrorResponse` y `rechazoDe` extrae su cuerpo tipado.
   */
  emitir(pedido: EmitirPresupuestoRequest): Observable<PresupuestoEmitido> {
    return this.http.post<PresupuestoResponse>(this.url, pedido, { observe: 'response' }).pipe(
      map((res) => ({ presupuesto: res.body as PresupuestoResponse, repetido: res.status === 200 })),
    );
  }

  /** Los de la ferretería, más nuevos primero. Un filtro sin elegir no se manda. */
  listar(filtro: FiltroPresupuestos): Observable<PageResponse<PresupuestoResumen>> {
    let params = new HttpParams()
      .set('page', filtro.page)
      .set('size', filtro.size ?? TAMANIO_POR_DEFECTO);
    if (filtro.estado) params = params.set('estado', filtro.estado);
    if (filtro.numero !== undefined) params = params.set('numero', filtro.numero);
    if (filtro.desde) params = params.set('desde', filtro.desde);
    if (filtro.hasta) params = params.set('hasta', filtro.hasta);
    return this.http.get<PageResponse<PresupuestoResumen>>(this.url, { params });
  }

  obtener(id: number): Observable<PresupuestoResponse> {
    return this.http.get<PresupuestoResponse>(`${this.url}/${id}`);
  }

  /** Pasa a pendiente, aceptado o rechazado. Uno ya facturado responde 409 con su mensaje. */
  cambiarEstado(id: number, estado: EstadoManual): Observable<PresupuestoResponse> {
    return this.http.patch<PresupuestoResponse>(`${this.url}/${id}/estado`, { estado });
  }
}
