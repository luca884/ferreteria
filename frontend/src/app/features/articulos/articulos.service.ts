import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../core/api/page.models';
import {
  AjusteStockRequest,
  AjusteStockResponse,
  AjustesRequest,
  Articulo,
  ArticuloRequest,
  ClientesDelArticulo,
  ConfiguracionPrecios,
  DesglosePrecio,
  FiltroMovimientos,
  MovimientoStock,
} from './articulo.models';

@Injectable({ providedIn: 'root' })
export class ArticulosService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/articulos`;

  buscar(
    q: string,
    page: number,
    size: number,
    incluirInactivos: boolean,
  ): Observable<PageResponse<Articulo>> {
    let params = new HttpParams()
      .set('page', page)
      .set('size', size)
      .set('incluirInactivos', incluirInactivos);
    const termino = q.trim();
    if (termino !== '') params = params.set('q', termino);
    return this.http.get<PageResponse<Articulo>>(this.url, { params });
  }

  /** CSV con todos los artículos de esa búsqueda (solo el Dueño); el nombre viene en Content-Disposition. */
  exportar(q: string, incluirInactivos: boolean): Observable<HttpResponse<Blob>> {
    let params = new HttpParams().set('incluirInactivos', incluirInactivos);
    const termino = q.trim();
    if (termino !== '') params = params.set('q', termino);
    return this.http.get(`${this.url}/exportacion`, { params, observe: 'response', responseType: 'blob' });
  }

  obtener(id: number): Observable<Articulo> {
    return this.http.get<Articulo>(`${this.url}/${id}`);
  }

  crear(request: ArticuloRequest): Observable<Articulo> {
    return this.http.post<Articulo>(this.url, request);
  }

  /** El código interno sugerido para el alta (último cargado a mano más uno); null si no hay sugerencia. Solo el Dueño. */
  codigoSiguiente(): Observable<string | null> {
    return this.http
      .get<{ codigo: string | null }>(`${this.url}/codigo-siguiente`)
      .pipe(map((respuesta) => respuesta.codigo));
  }

  actualizar(id: number, request: ArticuloRequest): Observable<Articulo> {
    return this.http.put<Articulo>(`${this.url}/${id}`, request);
  }

  /** De dónde sale el precio, paso a paso (Dueño y Vendedor, solo lectura). */
  desglose(id: number): Observable<DesglosePrecio> {
    return this.http.get<DesglosePrecio>(`${this.url}/${id}/desglose`);
  }

  /** Los tres ajustes del artículo (solo el Dueño); es un pedido propio, aparte del PUT del artículo. */
  guardarAjustes(id: number, request: AjustesRequest): Observable<Articulo> {
    return this.http.put<Articulo>(`${this.url}/${id}/ajustes`, request);
  }

  desactivar(id: number): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`);
  }

  reactivar(id: number): Observable<Articulo> {
    return this.http.post<Articulo>(`${this.url}/${id}/reactivacion`, null);
  }

  /** CU-09: se manda el stock real contado; el backend calcula la diferencia y deja el movimiento. */
  ajustarStock(id: number, ajuste: AjusteStockRequest): Observable<AjusteStockResponse> {
    return this.http.post<AjusteStockResponse>(`${this.url}/${id}/ajustes-stock`, ajuste);
  }

  /** G29: movimientos de stock del artículo con su saldo, los más nuevos primero (solo el Dueño). */
  movimientos(id: number, filtro: FiltroMovimientos): Observable<PageResponse<MovimientoStock>> {
    let params = new HttpParams().set('page', filtro.page).set('size', 20);
    if (filtro.desde) params = params.set('desde', filtro.desde);
    if (filtro.hasta) params = params.set('hasta', filtro.hasta);
    return this.http.get<PageResponse<MovimientoStock>>(`${this.url}/${id}/movimientos-stock`, { params });
  }

  /** G29: clientes que compraron el artículo, la compra más nueva primero (solo el Dueño). */
  clientes(id: number, page: number): Observable<ClientesDelArticulo> {
    const params = new HttpParams().set('page', page).set('size', 20);
    return this.http.get<ClientesDelArticulo>(`${this.url}/${id}/clientes`, { params });
  }

  /** Margen por defecto y múltiplo de redondeo de la ferretería: para el precio final en vivo. */
  configuracionPrecios(): Observable<ConfiguracionPrecios> {
    return this.http.get<ConfiguracionPrecios>(`${environment.apiUrl}/configuracion-precios`);
  }
}
