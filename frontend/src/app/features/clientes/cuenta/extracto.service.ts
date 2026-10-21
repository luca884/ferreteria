import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Extracto, FiltroExtracto, TAMANIO_PAGINA_EXTRACTO, filtroAParams, paramsDeImpresion } from './extracto.models';

/** Extracto de la cuenta corriente de un cliente (solo el Dueño). Una sola foto consistente por pedido, sin locks. */
@Injectable({ providedIn: 'root' })
export class ExtractoService {
  private readonly http = inject(HttpClient);

  private url(clienteId: number): string {
    return `${environment.apiUrl}/clientes/${clienteId}/cuenta-corriente/extracto`;
  }

  /** Una página de la pantalla (`page` desde 0, 20 por página), con el saldo real de cada línea aunque se filtre. */
  extracto(clienteId: number, filtro: FiltroExtracto, page: number): Observable<Extracto> {
    return this.http.get<Extracto>(this.url(clienteId), {
      params: filtroAParams(filtro, page, TAMANIO_PAGINA_EXTRACTO),
    });
  }

  /**
   * Todo el período filtrado en UN pedido (`impresion=true`, sin paginar): hasta TOPE_IMPRESION líneas; si hay más,
   * `truncado`. Los errores 400, 403 y 404 llegan como `HttpErrorResponse`.
   */
  paraImprimir(clienteId: number, filtro: FiltroExtracto): Observable<Extracto> {
    return this.http.get<Extracto>(this.url(clienteId), { params: paramsDeImpresion(filtro) });
  }
}
