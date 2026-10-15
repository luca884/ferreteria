import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Proveedor, ProveedorRequest } from './proveedor.models';

@Injectable({ providedIn: 'root' })
export class ProveedoresService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/proveedores`;

  /** Solo los activos, salvo `incluirInactivos` (para ver y reactivar los dados de baja). */
  listar(incluirInactivos = false): Observable<Proveedor[]> {
    const params = incluirInactivos ? new HttpParams().set('incluirInactivos', true) : undefined;
    return this.http.get<Proveedor[]>(this.url, { params });
  }

  reactivar(id: number): Observable<Proveedor> {
    return this.http.post<Proveedor>(`${this.url}/${id}/reactivacion`, null);
  }

  crear(request: ProveedorRequest): Observable<Proveedor> {
    return this.http.post<Proveedor>(this.url, request);
  }

  actualizar(id: number, request: ProveedorRequest): Observable<Proveedor> {
    return this.http.put<Proveedor>(`${this.url}/${id}`, request);
  }

  desactivar(id: number): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`);
  }
}
