import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Cotizacion, RecalculoCotizacion, VistaPreviaRecalculo } from './cotizacion.models';

@Injectable({ providedIn: 'root' })
export class CotizacionService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/cotizacion`;
  private readonly refrescos = new Subject<void>();

  /** Cada vez que algo cambió la cotización (se guardó la configuración, se recalculó): lo escucha el shell. */
  readonly refrescos$: Observable<void> = this.refrescos.asObservable();

  /** Lo que ve la ferretería del token. Solo lee la base del servidor: no llama a ninguna API externa. */
  obtener(): Observable<Cotizacion> {
    return this.http.get<Cotizacion>(this.url);
  }

  /**
   * «Recalcular ahora» (solo el Dueño): 409 si la cotización está apagada o todavía no hay ningún valor. Con
   * `confirmaDesvio` el Dueño acepta un dólar que se aleja más del aviso de la ferretería; con `dolar`, el que vio en la
   * vista previa: si cambió entre medio, el backend da 409 y no aplica nada.
   */
  recalcular(confirmaDesvio = false, dolar?: number): Observable<RecalculoCotizacion> {
    const params: Record<string, string | boolean> = {};
    if (confirmaDesvio) params['confirmaDesvio'] = true;
    if (dolar !== undefined) params['dolar'] = String(dolar);
    return this.http
      .post<RecalculoCotizacion>(`${this.url}/recalculo`, null, { params })
      .pipe(tap(() => this.refrescar()));
  }

  /** Cómo quedarían los precios en dólares con el último dólar (solo el Dueño). No cambia nada. */
  vistaPrevia(): Observable<VistaPreviaRecalculo> {
    return this.http.get<VistaPreviaRecalculo>(`${this.url}/recalculo/vista-previa`);
  }

  /** Avisa que la cotización cambió en el servidor: el menú lateral la vuelve a leer sin esperar su ciclo. */
  refrescar(): void {
    this.refrescos.next();
  }
}
