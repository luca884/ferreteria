import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ConfirmarVentaRequest, VentaCobrada, VentaRechazada, VentaResponse } from './venta.models';

@Injectable({ providedIn: 'root' })
export class VentaService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/ventas`;

  /**
   * Confirma la venta. 201 es nueva y 200 es la misma clave ya registrada con el mismo contenido. Los
   * rechazos (404, 409, 422) llegan como `HttpErrorResponse`; `rechazoDe` extrae su cuerpo tipado.
   */
  cobrar(pedido: ConfirmarVentaRequest): Observable<VentaCobrada> {
    return this.http.post<VentaResponse>(this.url, pedido, { observe: 'response' }).pipe(
      map((res) => ({ venta: res.body as VentaResponse, repetida: res.status === 200 })),
    );
  }
}

const MOTIVOS = [
  'ARTICULO_INEXISTENTE',
  'ARTICULO_DADO_DE_BAJA',
  'PRECIOS_CAMBIADOS',
  'CLAVE_CON_OTRO_CONTENIDO',
];

/** El cuerpo de un 404, 409 o 422 de venta, o null si el error es otra cosa (red, 5xx, 400). */
export function rechazoDe(err: unknown): VentaRechazada | null {
  if (!(err instanceof HttpErrorResponse)) return null;
  if (err.status !== 404 && err.status !== 409 && err.status !== 422) return null;
  const cuerpo = err.error as Partial<VentaRechazada> | null;
  if (typeof cuerpo !== 'object' || cuerpo === null) return null;
  if (typeof cuerpo.motivo !== 'string' || !MOTIVOS.includes(cuerpo.motivo)) return null;
  return cuerpo as VentaRechazada;
}
