import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CondicionIvaFerreteria } from '../admin-ferreterias/ferreteria-admin.models';

/**
 * Los datos del emisor que la factura imprime (CU-01 paso 1). El CUIT se carga en el alta y no cambia; domicilio, inicio
 * de actividades (`2015-03-01`) e Ingresos Brutos son null hasta que el Dueño los carga.
 */
export interface DatosFiscales {
  razonSocial: string;
  cuit: string;
  condicionIva: CondicionIvaFerreteria;
  domicilioComercial: string | null;
  inicioActividades: string | null;
  ingresosBrutos: string | null;
}

/** Lo que guarda el Dueño: todo menos el CUIT. */
export type DatosFiscalesRequest = Omit<DatosFiscales, 'cuit'>;

@Injectable({ providedIn: 'root' })
export class DatosFiscalesService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/datos-fiscales`;

  obtener(): Observable<DatosFiscales> {
    return this.http.get<DatosFiscales>(this.url);
  }

  guardar(datos: DatosFiscalesRequest): Observable<DatosFiscales> {
    return this.http.put<DatosFiscales>(this.url, datos);
  }
}
