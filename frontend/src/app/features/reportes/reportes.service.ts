import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Agrupar, Filas, FilasPorTipo, ReporteEnFilas, Resumen } from './reportes.models';

/** Reportes de ventas, solo del Dueño. Las fechas van como `2026-10-22`, días de Buenos Aires, las dos incluidas. */
@Injectable({ providedIn: 'root' })
export class ReportesService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/reportes/ventas`;

  resumen(desde: string, hasta: string): Observable<Resumen> {
    return this.http.get<Resumen>(`${this.url}/resumen`, { params: { desde, hasta } });
  }

  filas<T extends ReporteEnFilas>(
    tipo: T,
    desde: string,
    hasta: string,
    agrupar?: Agrupar,
  ): Observable<Filas<FilasPorTipo[T]>> {
    let params = new HttpParams().set('desde', desde).set('hasta', hasta);
    if (agrupar) params = params.set('agrupar', agrupar);
    return this.http.get<Filas<FilasPorTipo[T]>>(`${this.url}/${tipo}`, { params });
  }
}
