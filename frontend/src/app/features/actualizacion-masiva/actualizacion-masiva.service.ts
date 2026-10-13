import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AplicarPedidoMasivo,
  PedidoMasivo,
  ResultadoMasivo,
  VistaPreviaMasiva,
} from './actualizacion-masiva.models';

/**
 * Los dos pedidos son JSON; no se setea Content-Type a mano (HttpClient lo agrega al serializar el cuerpo).
 * La vista previa no escribe nada; aplicar sí, y lleva la huella de la vista previa que el Dueño vio.
 */
@Injectable({ providedIn: 'root' })
export class ActualizacionMasivaService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/actualizacion-masiva`;

  vistaPrevia(pedido: PedidoMasivo): Observable<VistaPreviaMasiva> {
    return this.http.post<VistaPreviaMasiva>(`${this.url}/vista-previa`, pedido);
  }

  aplicar(pedido: AplicarPedidoMasivo): Observable<ResultadoMasivo> {
    return this.http.post<ResultadoMasivo>(this.url, pedido);
  }
}
