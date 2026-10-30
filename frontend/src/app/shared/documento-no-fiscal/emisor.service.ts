import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DatosFiscales } from '../../features/datos-fiscales/datos-fiscales.service';

/**
 * Los datos del emisor para imprimir remitos y presupuestos (cu-07-remito-presupuesto-imprimible, S3): los lee el Dueño y
 * el Vendedor, solo lectura. Son los datos actuales de la ferretería: estos documentos no los congelan.
 */
@Injectable({ providedIn: 'root' })
export class EmisorService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/emisor`;

  obtener(): Observable<DatosFiscales> {
    return this.http.get<DatosFiscales>(this.url);
  }
}
