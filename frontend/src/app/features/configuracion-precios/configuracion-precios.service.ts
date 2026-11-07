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

/** Un artículo cuyo precio cambiaría con los valores nuevos: el de hoy y el nuevo. */
export interface EjemploDePrecio {
  codigo: string;
  descripcion: string;
  precioActual: number;
  precioNuevo: number;
}

/** Cuántos artículos se recalcularían y unos pocos ejemplos de los que cambian de precio (vacío si ninguno). */
export interface ArticulosQueCambian {
  cantidad: number;
  ejemplos: EjemploDePrecio[];
}

@Injectable({ providedIn: 'root' })
export class ConfiguracionPreciosService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/configuracion-precios`;

  obtener(): Observable<ConfiguracionPrecios> {
    return this.http.get<ConfiguracionPrecios>(this.url);
  }

  /** Cuántos artículos se recalcularían con estos valores, con unos ejemplos. No guarda nada. */
  articulosQueCambian(nueva: ConfiguracionPrecios): Observable<ArticulosQueCambian> {
    return this.http
      .get<ArticulosQueCambian>(`${this.url}/articulos-que-cambian`, {
        params: {
          margenDefectoPorcentaje: nueva.margenDefectoPorcentaje,
          redondeoMultiplo: nueva.redondeoMultiplo,
        },
      })
      .pipe(map((r) => ({ cantidad: r.cantidad, ejemplos: r.ejemplos ?? [] })));
  }

  /** Guarda y el backend recalcula los precios que dependen de estos valores. */
  guardar(nueva: ConfiguracionPrecios): Observable<ConfiguracionPreciosGuardada> {
    return this.http.put<ConfiguracionPreciosGuardada>(this.url, nueva);
  }
}
