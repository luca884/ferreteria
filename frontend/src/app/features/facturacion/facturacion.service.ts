import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ConfiguracionFacturacion, PedidoCertificado } from './facturacion.models';

/** La API del Dueño para la facturación con ARCA (contrato C1). El Vendedor y el super admin reciben 403. */
@Injectable({ providedIn: 'root' })
export class FacturacionService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/facturacion`;

  configuracion(): Observable<ConfiguracionFacturacion> {
    return this.http.get<ConfiguracionFacturacion>(`${this.url}/configuracion`);
  }

  /** 400 fuera de 1 a 99998; 409 si está trabado (simulador o facturas en trámite). */
  guardarPuntoVenta(puntoVenta: number | null): Observable<ConfiguracionFacturacion> {
    return this.http.put<ConfiguracionFacturacion>(`${this.url}/punto-venta`, { puntoVenta });
  }

  /** `cuit` null usa el de la ferretería; otro solo se acepta fuera de producción. Reemplaza el pedido pendiente. */
  generarPedido(cuit: string | null): Observable<PedidoCertificado> {
    return this.http.post<PedidoCertificado>(`${this.url}/pedido-certificado`, { cuit });
  }

  /** El pedido pendiente; 404 si no hay. */
  pedido(): Observable<PedidoCertificado> {
    return this.http.get<PedidoCertificado>(`${this.url}/pedido-certificado`);
  }

  /** El .crt como texto: PEM tal cual o DER en Base64. 400 con el motivo; 409 sin pedido pendiente. */
  subirCertificado(certificado: string): Observable<ConfiguracionFacturacion> {
    return this.http.post<ConfiguracionFacturacion>(`${this.url}/certificado`, { certificado });
  }

  /** Solo en SIMULADOR y con pedido pendiente (si no, 409): la CA de simulación firma el pedido. */
  firmarConSimulador(): Observable<ConfiguracionFacturacion> {
    return this.http.post<ConfiguracionFacturacion>(`${this.url}/certificado/simulador`, null);
  }
}
