import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** Desde qué importe la venta facturable a Consumidor Final necesita un cliente con documento (GDS-32). */
export interface ConfiguracionVentas {
  topeConsumidorFinal: number;
}

@Injectable({ providedIn: 'root' })
export class ConfiguracionVentasService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/configuracion-ventas`;

  obtener(): Observable<ConfiguracionVentas> {
    return this.http.get<ConfiguracionVentas>(this.url);
  }

  guardar(nueva: ConfiguracionVentas): Observable<ConfiguracionVentas> {
    return this.http.put<ConfiguracionVentas>(this.url, nueva);
  }
}
