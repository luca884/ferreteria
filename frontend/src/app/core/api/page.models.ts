/** Pagina del servidor: misma forma que `PageResponse` del backend. `page` empieza en 0. */
export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
