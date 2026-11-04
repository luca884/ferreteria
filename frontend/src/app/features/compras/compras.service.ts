import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../core/api/page.models';
import { BorradorDeFactura, CompraRegistrada, CompraRequest, CompraResponse, CompraResumen, FiltroCompras, PlanCompra } from './compra.models';

const TAMANIO_POR_DEFECTO = 20;

/** Compras al proveedor (CU-13). Todo es del Dueño: al Vendedor el backend le responde 403. */
@Injectable({ providedIn: 'root' })
export class ComprasService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/compras`;

  /** Lo que haría la compra con cada artículo, sin guardar nada. Valida igual que registrar (404, 400, 409). */
  vistaPrevia(pedido: CompraRequest): Observable<PlanCompra> {
    return this.http.post<PlanCompra>(`${this.url}/vista-previa`, pedido);
  }

  /** Registra la compra. 201 es nueva y 200 es la misma clave ya registrada con el mismo contenido. */
  registrar(pedido: CompraRequest): Observable<CompraRegistrada> {
    return this.http
      .post<CompraResponse>(this.url, pedido, { observe: 'response' })
      .pipe(map((res) => ({ compra: res.body as CompraResponse, repetida: res.status === 200 })));
  }

  /** Lee la factura del proveedor en PDF y devuelve el borrador de la compra, sin guardar nada. */
  leerFactura(archivo: File): Observable<BorradorDeFactura> {
    const datos = new FormData();
    datos.append('archivo', archivo);
    return this.http.post<BorradorDeFactura>(`${this.url}/factura-pdf`, datos);
  }

  /** Las de la ferretería, más nuevas primero. Un filtro sin elegir no se manda. */
  listar(filtro: FiltroCompras): Observable<PageResponse<CompraResumen>> {
    let params = new HttpParams().set('page', filtro.page).set('size', filtro.size ?? TAMANIO_POR_DEFECTO);
    if (filtro.proveedorId !== undefined) params = params.set('proveedorId', filtro.proveedorId);
    if (filtro.desde) params = params.set('desde', filtro.desde);
    if (filtro.hasta) params = params.set('hasta', filtro.hasta);
    return this.http.get<PageResponse<CompraResumen>>(this.url, { params });
  }

  obtener(id: number): Observable<CompraResponse> {
    return this.http.get<CompraResponse>(`${this.url}/${id}`);
  }
}
