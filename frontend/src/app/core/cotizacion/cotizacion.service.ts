import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Cotizacion, RecalculoCotizacion } from './cotizacion.models';

@Injectable({ providedIn: 'root' })
export class CotizacionService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/cotizacion`;

  /** Lo que ve la ferretería del token. Solo lee la base del servidor: no llama a ninguna API externa. */
  obtener(): Observable<Cotizacion> {
    return this.http.get<Cotizacion>(this.url);
  }

  /** «Recalcular ahora» (solo el Dueño): 409 si la cotización está apagada o todavía no hay ningún valor. */
  recalcular(): Observable<RecalculoCotizacion> {
    return this.http.post<RecalculoCotizacion>(`${this.url}/recalculo`, null);
  }
}
