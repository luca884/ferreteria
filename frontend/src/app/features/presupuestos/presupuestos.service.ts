import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { EmitirPresupuestoRequest, PresupuestoEmitido, PresupuestoResponse } from './presupuesto.models';

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
}
