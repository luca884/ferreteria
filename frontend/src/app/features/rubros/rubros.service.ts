import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Rubro, RubroRequest } from './rubro.models';

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

  /** Cuántos artículos activos heredan el margen del rubro: los que cambian de precio si cambia. */
  articulosQueHeredan(id: number): Observable<number> {
    return this.http
      .get<{ cantidad: number }>(`${this.url}/${id}/articulos-que-heredan`)
      .pipe(map((r) => r.cantidad));
  }
}
