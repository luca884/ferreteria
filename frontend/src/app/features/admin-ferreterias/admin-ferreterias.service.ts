import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AltaFerreteriaRequest, EstadoGeneral, FerreteriaAdmin } from './ferreteria-admin.models';

/** Las ferreterías de la plataforma. Todo el recurso es solo del super admin; no trae ningún dato de ventas. */
@Injectable({ providedIn: 'root' })
export class AdminFerreteriasService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/admin/ferreterias`;

  /** Totales de activas y suspendidas, y cada ferretería (también las suspendidas) por razón social. */
  listar(): Observable<EstadoGeneral> {
    return this.http.get<EstadoGeneral>(this.url);
  }

  /** Alta de la ferretería con su primer Dueño, en una sola operación. */
  alta(request: AltaFerreteriaRequest): Observable<FerreteriaAdmin> {
    return this.http.post<FerreteriaAdmin>(this.url, request);
  }

  /** Suspender corta los logins y los tokens de sus usuarios sin tocar ningún dato; reactivar los devuelve. */
  cambiarActivo(id: number, activo: boolean): Observable<FerreteriaAdmin> {
    return this.http.patch<FerreteriaAdmin>(`${this.url}/${id}/activo`, { activo });
  }

  /** Pone o saca la marca «de prueba». Con el simulador asignado no se saca (422 con el motivo). */
  cambiarDePrueba(id: number, dePrueba: boolean): Observable<FerreteriaAdmin> {
    return this.http.patch<FerreteriaAdmin>(`${this.url}/${id}/de-prueba`, { dePrueba });
  }

  /** Asigna el simulador (punto de venta 1) o lo quita. Si el cerrojo del ambiente lo impide, 422 con el motivo. */
  cambiarSimulador(id: number, simulador: boolean): Observable<FerreteriaAdmin> {
    return this.http.patch<FerreteriaAdmin>(`${this.url}/${id}/simulador`, { simulador });
  }

  /** Prende la facturación (exige ambiente y los datos fiscales completos; si no, 422) o la apaga. */
  cambiarFacturacion(id: number, habilitada: boolean): Observable<FerreteriaAdmin> {
    return this.http.patch<FerreteriaAdmin>(`${this.url}/${id}/facturacion`, { habilitada });
  }

  /** El super admin le pone una contraseña nueva al Dueño de una ferretería, sin pedir la actual. */
  resetearContrasenaDueno(ferreteriaId: number, usuarioId: number, password: string): Observable<void> {
    return this.http.put<void>(`${this.url}/${ferreteriaId}/duenos/${usuarioId}/password`, { password });
  }
}
