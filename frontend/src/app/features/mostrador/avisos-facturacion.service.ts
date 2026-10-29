import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AvisosFacturacion } from './avisos-facturacion.models';

/** Los avisos de facturación del mostrador del Dueño (C4). Al Vendedor el servidor le responde 403: no se le pide. */
@Injectable({ providedIn: 'root' })
export class AvisosFacturacionService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/facturacion/avisos-mostrador`;

  leer(): Observable<AvisosFacturacion> {
    return this.http.get<AvisosFacturacion>(this.url);
  }
}
