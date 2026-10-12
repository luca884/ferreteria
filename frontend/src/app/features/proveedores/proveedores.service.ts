import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Proveedor, ProveedorRequest } from './proveedor.models';

@Injectable({ providedIn: 'root' })
export class ProveedoresService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/proveedores`;

  listar(): Observable<Proveedor[]> {
    return this.http.get<Proveedor[]>(this.url);
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
