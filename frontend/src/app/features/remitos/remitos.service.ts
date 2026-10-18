import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { EmitirRemitoRequest, RemitoEmitido, RemitoResponse } from './remito.models';

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
}
