import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
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
}
