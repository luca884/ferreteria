import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { BusquedaMostrador } from './mostrador.models';

@Injectable({ providedIn: 'root' })
export class MostradorService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/articulos/mostrador`;

  buscar(q: string): Observable<BusquedaMostrador> {
    const params = new HttpParams().set('q', q.trim());
    return this.http.get<BusquedaMostrador>(this.url, { params });
  }
}
