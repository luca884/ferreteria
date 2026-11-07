import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Rubro, RubroRequest, VistaPreviaMargen } from './rubro.models';

@Injectable({ providedIn: 'root' })
export class RubrosService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/rubros`;

  listar(): Observable<Rubro[]> {
    return this.http.get<Rubro[]>(this.url);
  }

  crear(request: RubroRequest): Observable<Rubro> {
    return this.http.post<Rubro>(this.url, request);
  }

  /** Si cambia el margen, el backend recalcula los artículos que lo heredan. */
  actualizar(id: number, request: RubroRequest): Observable<Rubro> {
    return this.http.put<Rubro>(`${this.url}/${id}`, request);
  }

  /**
   * Los artículos que cambian de precio si el margen del rubro pasa a `margenPorcentaje`, con el precio de hoy y el
   * nuevo (solo el Dueño). No guarda nada; sin cotización del dólar para algún artículo en USD responde 409.
   */
  vistaPreviaMargen(id: number, margenPorcentaje: number): Observable<VistaPreviaMargen> {
    return this.http.get<VistaPreviaMargen>(`${this.url}/${id}/vista-previa-margen`, { params: { margenPorcentaje } });
  }
}
