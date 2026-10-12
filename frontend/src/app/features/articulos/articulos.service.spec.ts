import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PageResponse } from '../../core/api/page.models';
import { Articulo, ArticuloRequest } from './articulo.models';
import { ArticulosService } from './articulos.service';

describe('ArticulosService', () => {
  let service: ArticulosService;
  let http: HttpTestingController;

  const tornillo: Articulo = {
    id: 1,
    codigoInterno: 'TOR-001',
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: 'Tornillo hexagonal 1/4',
    rubroId: null,
    proveedorId: null,
    costo: 100,
    margenPorcentaje: null,
    alicuotaIva: 'IVA_21',
    precioFinal: 170,
    unidad: 'UNIDAD',
    stockActual: 0,
    stockMinimo: 0,
    ubicacion: null,
    activo: true,
  };

  const pagina: PageResponse<Articulo> = {
    items: [tornillo],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
  };

  const request: ArticuloRequest = {
    codigoInterno: 'TOR-001',
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: 'Tornillo hexagonal 1/4',
    rubroId: null,
    proveedorId: null,
    costo: 100,
    margenPorcentaje: null,
    alicuotaIva: 'IVA_21',
    unidad: 'UNIDAD',
    stockMinimo: 0,
    ubicacion: null,
    activo: true,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ArticulosService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('busca con GET /articulos mandando q, page, size e incluirInactivos', () => {
    let recibida: PageResponse<Articulo> | undefined;
    service.buscar('torni', 2, 10, true).subscribe((p) => (recibida = p));

    const req = http.expectOne((r) => r.url.endsWith('/articulos'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('torni');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.params.get('incluirInactivos')).toBe('true');
    req.flush(pagina);

    expect(recibida).toEqual(pagina);
  });

  it('no manda q cuando la búsqueda está vacía o en blanco', () => {
    service.buscar('   ', 0, 20, false).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/articulos'));
    expect(req.request.params.has('q')).toBe(false);
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    expect(req.request.params.get('incluirInactivos')).toBe('false');
    req.flush(pagina);
  });

  it('manda q sin espacios sobrantes', () => {
    service.buscar('  mart-0 ', 0, 20, false).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/articulos'));
    expect(req.request.params.get('q')).toBe('mart-0');
    req.flush(pagina);
  });

  it('obtiene un artículo con GET /articulos/{id}', () => {
    let obtenido: Articulo | undefined;
    service.obtener(1).subscribe((a) => (obtenido = a));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/1'));
    expect(req.request.method).toBe('GET');
    req.flush(tornillo);

    expect(obtenido).toEqual(tornillo);
  });

  it('crea un artículo con POST /articulos', () => {
    let creado: Articulo | undefined;
    service.crear(request).subscribe((a) => (creado = a));

    const req = http.expectOne((r) => r.url.endsWith('/articulos'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(tornillo);

    expect(creado).toEqual(tornillo);
  });

  it('actualiza un artículo con PUT /articulos/{id}', () => {
    let actualizado: Articulo | undefined;
    service.actualizar(1, { ...request, costo: 200 }).subscribe((a) => (actualizado = a));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/1'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ ...request, costo: 200 });
    req.flush({ ...tornillo, costo: 200, precioFinal: 330 });

    expect(actualizado?.precioFinal).toBe(330);
  });

  it('desactiva un artículo con DELETE /articulos/{id}', () => {
    let terminado = false;
    service.desactivar(1).subscribe(() => (terminado = true));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/1'));
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(terminado).toBe(true);
  });
});
