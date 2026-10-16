import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AplicarRequest,
  Importacion,
  Muestra,
  VistaPrevia,
  VistaPreviaRequest,
} from './importacion.models';

/**
 * Los tres pedidos que suben el archivo son multipart: la parte `archivo` y, salvo en la muestra, la parte `datos`
 * (JSON). No se setea Content-Type a mano: el navegador agrega el boundary.
 */
@Injectable({ providedIn: 'root' })
export class ImportacionesService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/importaciones`;

  /** Todas las hojas del archivo, con sus filas, y la propuesta de mapeo. */
  muestra(archivo: File): Observable<Muestra> {
    const form = new FormData();
    form.append('archivo', archivo, archivo.name);
    return this.http.post<Muestra>(`${this.url}/muestra`, form);
  }

  vistaPrevia(archivo: File, datos: VistaPreviaRequest): Observable<VistaPrevia> {
    return this.http.post<VistaPrevia>(`${this.url}/vista-previa`, conDatos(archivo, datos));
  }

  aplicar(archivo: File, datos: AplicarRequest): Observable<Importacion> {
    return this.http.post<Importacion>(this.url, conDatos(archivo, datos));
  }

  /** El historial de importaciones (solo el Dueño), la más reciente primero; con `proveedorId`, las de ese proveedor. */
  listar(proveedorId?: number): Observable<Importacion[]> {
    const params =
      proveedorId === undefined ? new HttpParams() : new HttpParams().set('proveedorId', proveedorId);
    return this.http.get<Importacion[]>(this.url, { params });
  }
}

function conDatos(archivo: File, datos: object): FormData {
  const form = new FormData();
  form.append('archivo', archivo, archivo.name);
  form.append('datos', new Blob([JSON.stringify(datos)], { type: 'application/json' }));
  return form;
}
