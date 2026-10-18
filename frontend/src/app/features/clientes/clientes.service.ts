import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../core/api/page.models';
import { Cliente, ClienteRequest, FiltroClientes } from './cliente.models';

@Injectable({ providedIn: 'root' })
export class ClientesService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/clientes`;

  /** Por razón social (contiene) o documento (empieza con), ordenados por razón social. Los leen el Dueño y el Vendedor. */
  buscar(filtro: FiltroClientes = {}): Observable<PageResponse<Cliente>> {
    let params = new HttpParams();
    const q = filtro.q?.trim();
    if (q) params = params.set('q', q);
    if (filtro.soloActivos) params = params.set('activos', 'true');
    if (filtro.page !== undefined) params = params.set('page', filtro.page);
    if (filtro.size !== undefined) params = params.set('size', filtro.size);
    return this.http.get<PageResponse<Cliente>>(this.url, { params });
  }

  obtener(id: number): Observable<Cliente> {
    return this.http.get<Cliente>(`${this.url}/${id}`);
  }

  crear(request: ClienteRequest): Observable<Cliente> {
    return this.http.post<Cliente>(this.url, request);
  }

  actualizar(id: number, request: ClienteRequest): Observable<Cliente> {
    return this.http.put<Cliente>(`${this.url}/${id}`, request);
  }

  /** Solo el Dueño. Un cliente desactivado deja de poder elegirse en el mostrador. */
  cambiarActivo(id: number, activo: boolean): Observable<Cliente> {
    return this.http.patch<Cliente>(`${this.url}/${id}/activo`, { activo });
  }
}
