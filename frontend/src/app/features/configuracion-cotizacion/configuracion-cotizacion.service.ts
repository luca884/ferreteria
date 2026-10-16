import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CotizacionService } from '../../core/cotizacion/cotizacion.service';
import { ConfiguracionCotizacion, ConfiguracionCotizacionRequest } from './configuracion-cotizacion.models';

@Injectable({ providedIn: 'root' })
export class ConfiguracionCotizacionService {
  private readonly http = inject(HttpClient);
  private readonly cotizaciones = inject(CotizacionService);
  private readonly url = `${environment.apiUrl}/configuracion-cotizacion`;

  obtener(): Observable<ConfiguracionCotizacion> {
    return this.http.get<ConfiguracionCotizacion>(this.url);
  }

  /**
   * Guarda la configuración completa. No recalcula precios: el cambio rige desde el próximo recálculo. Si se guarda,
   * avisa al menú lateral para que muestre ya los tipos de cotización nuevos.
   */
  guardar(pedido: ConfiguracionCotizacionRequest): Observable<ConfiguracionCotizacion> {
    return this.http.put<ConfiguracionCotizacion>(this.url, pedido).pipe(tap(() => this.cotizaciones.refrescar()));
  }
}
