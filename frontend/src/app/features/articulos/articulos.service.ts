import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../core/api/page.models';
import { Articulo, ArticuloRequest } from './articulo.models';

@Injectable({ providedIn: 'root' })
export class ArticulosService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/articulos`;

  buscar(
    q: string,
    page: number,
    size: number,
    incluirInactivos: boolean,
  ): Observable<PageResponse<Articulo>> {
    let params = new HttpParams()
      .set('page', page)
      .set('size', size)
      .set('incluirInactivos', incluirInactivos);
    const termino = q.trim();
    if (termino !== '') params = params.set('q', termino);
    return this.http.get<PageResponse<Articulo>>(this.url, { params });
  }

  obtener(id: number): Observable<Articulo> {
    return this.http.get<Articulo>(`${this.url}/${id}`);
  }

  crear(request: ArticuloRequest): Observable<Articulo> {
    return this.http.post<Articulo>(this.url, request);
  }

  actualizar(id: number, request: ArticuloRequest): Observable<Articulo> {
    return this.http.put<Articulo>(`${this.url}/${id}`, request);
  }

  desactivar(id: number): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`);
  }
}
