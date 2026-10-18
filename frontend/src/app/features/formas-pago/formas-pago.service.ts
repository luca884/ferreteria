import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { FormaPago, FormaPagoRequest } from './forma-pago.models';

@Injectable({ providedIn: 'root' })
export class FormasPagoService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/formas-pago`;

  /** Todas, en el orden del Dueño; con `soloActivas`, las que se ofrecen al cobrar. Las leen el Dueño y el Vendedor. */
  listar(soloActivas = false): Observable<FormaPago[]> {
    const params = soloActivas ? new HttpParams().set('activas', 'true') : undefined;
    return this.http.get<FormaPago[]>(this.url, { params });
  }

  crear(request: FormaPagoRequest): Observable<FormaPago> {
    return this.http.post<FormaPago>(this.url, request);
  }

  actualizar(id: number, request: FormaPagoRequest): Observable<FormaPago> {
    return this.http.put<FormaPago>(`${this.url}/${id}`, request);
  }
}
