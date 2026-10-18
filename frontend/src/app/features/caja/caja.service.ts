import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../core/api/page.models';
import {
  CajaAbierta,
  CajaCerrada,
  CajaVigente,
  DetalleCaja,
  MovimientoCaja,
  MovimientoCajaRequest,
} from './caja.models';

/** Caja diaria (CU-10). La ferretería y el usuario los pone el backend desde el JWT. */
@Injectable({ providedIn: 'root' })
export class CajaService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/cajas`;

  /** La caja abierta (sin totales) y el efectivo propuesto para abrir. */
  actual(): Observable<CajaVigente> {
    return this.http.get<CajaVigente>(`${this.url}/actual`);
  }

  abrir(efectivoInicial: number): Observable<CajaAbierta> {
    return this.http.post<CajaAbierta>(this.url, { efectivoInicial });
  }

  registrarMovimiento(cajaId: number, movimiento: MovimientoCajaRequest): Observable<MovimientoCaja> {
    return this.http.post<MovimientoCaja>(`${this.url}/${cajaId}/movimientos`, movimiento);
  }

  /** Cierre ciego: manda solo el contado; la respuesta trae los totales, el esperado y la diferencia. */
  cerrar(cajaId: number, efectivoContado: number): Observable<DetalleCaja> {
    return this.http.post<DetalleCaja>(`${this.url}/${cajaId}/cierre`, { efectivoContado });
  }

  /** Solo el Dueño: la caja del día en vivo o una del historial. */
  detalle(cajaId: number): Observable<DetalleCaja> {
    return this.http.get<DetalleCaja>(`${this.url}/${cajaId}`);
  }

  /** Solo el Dueño: las cajas cerradas, más nuevas primero. */
  historial(page: number, size: number): Observable<PageResponse<CajaCerrada>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PageResponse<CajaCerrada>>(this.url, { params });
  }
}
