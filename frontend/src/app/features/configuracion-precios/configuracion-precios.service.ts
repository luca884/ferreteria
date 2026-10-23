import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ConfiguracionPrecios } from '../articulos/articulo.models';

/** La configuración ya guardada, cuántos precios cambiaron y los artículos que conservaron el suyo por sus ajustes. */
export interface ConfiguracionPreciosGuardada extends ConfiguracionPrecios {
  articulosRecalculados: number;
  avisos: string[];
}

@Injectable({ providedIn: 'root' })
export class ConfiguracionPreciosService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/configuracion-precios`;

  obtener(): Observable<ConfiguracionPrecios> {
    return this.http.get<ConfiguracionPrecios>(this.url);
  }

  /** Cuántos artículos se recalcularían con estos valores. No guarda nada. */
  articulosQueCambian(nueva: ConfiguracionPrecios): Observable<number> {
    return this.http
      .get<{ cantidad: number }>(`${this.url}/articulos-que-cambian`, {
        params: {
          margenDefectoPorcentaje: nueva.margenDefectoPorcentaje,
          redondeoMultiplo: nueva.redondeoMultiplo,
        },
      })
      .pipe(map((r) => r.cantidad));
  }

  /** Guarda y el backend recalcula los precios que dependen de estos valores. */
  guardar(nueva: ConfiguracionPrecios): Observable<ConfiguracionPreciosGuardada> {
    return this.http.put<ConfiguracionPreciosGuardada>(this.url, nueva);
  }
}
