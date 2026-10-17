import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Cotizacion, RecalculoCotizacion } from './cotizacion.models';

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

  /** «Recalcular ahora» (solo el Dueño): 409 si la cotización está apagada o todavía no hay ningún valor. */
  /** Con `confirmaDesvio` el Dueño acepta un dólar que se aleja más del 20 % del vigente; sin eso, el backend da 409. */
  recalcular(confirmaDesvio = false): Observable<RecalculoCotizacion> {
    const params: Record<string, boolean> = confirmaDesvio ? { confirmaDesvio: true } : {};
    return this.http
      .post<RecalculoCotizacion>(`${this.url}/recalculo`, null, { params })
      .pipe(tap(() => this.refrescar()));
  }

  /** Avisa que la cotización cambió en el servidor: el menú lateral la vuelve a leer sin esperar su ciclo. */
  refrescar(): void {
    this.refrescos.next();
  }
}
