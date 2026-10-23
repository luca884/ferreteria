import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ArticuloEnControl, ControlDeDatos } from './controles-de-datos.models';

/** Controles de datos del catálogo (GDS-18, G81), solo del Dueño. Vienen todos, sin páginas: es un listado para imprimir. */
@Injectable({ providedIn: 'root' })
export class ControlesDeDatosService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/articulos/controles`;

  listar(control: ControlDeDatos): Observable<ArticuloEnControl[]> {
    return this.http.get<ArticuloEnControl[]>(this.url, { params: { control } });
  }
}
