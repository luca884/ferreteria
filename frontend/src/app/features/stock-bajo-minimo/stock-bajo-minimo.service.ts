import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ArticuloBajoMinimo, OrdenBajoMinimo } from './stock-bajo-minimo.models';

/** Artículos bajo el stock mínimo (CU-09, G72), solo del Dueño. Vienen todos, sin páginas: es un listado para imprimir. */
@Injectable({ providedIn: 'root' })
export class StockBajoMinimoService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/articulos/bajo-minimo`;

  listar(orden: OrdenBajoMinimo): Observable<ArticuloBajoMinimo[]> {
    return this.http.get<ArticuloBajoMinimo[]>(this.url, { params: { orden } });
  }
}
