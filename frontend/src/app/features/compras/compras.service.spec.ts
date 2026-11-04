import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CompraRegistrada, CompraRequest, CompraResponse } from './compra.models';
import { ComprasService } from './compras.service';

describe('ComprasService', () => {
  let service: ComprasService;
  let http: HttpTestingController;

  const pedido: CompraRequest = {
    clave: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
    proveedorId: 4,
    tipo: 'FA',
    puntoVenta: 3,
    numero: 1234,
    fecha: '2026-10-20',
    renglones: [{ articuloId: 1, cantidad: 20, costoUnitario: 110 }],
  };

  const compra = { id: 9, comprobante: 'Factura A 00003-00001234' } as CompraResponse;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ComprasService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('pide la vista previa con POST a /compras/vista-previa', () => {
    service.vistaPrevia(pedido).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/compras/vista-previa'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(pedido);
    req.flush({});
  });

  it('registra con POST a /compras: 201 es nueva y 200 es repetida', () => {
    let registradas: CompraRegistrada[] = [];
    service.registrar(pedido).subscribe((r) => (registradas = [...registradas, r]));
    http.expectOne((r) => r.url.endsWith('/compras') && r.method === 'POST').flush(compra, { status: 201, statusText: 'Created' });
    service.registrar(pedido).subscribe((r) => (registradas = [...registradas, r]));
    http.expectOne((r) => r.url.endsWith('/compras') && r.method === 'POST').flush(compra, { status: 200, statusText: 'OK' });

    expect(registradas).toEqual([
      { compra, repetida: false },
      { compra, repetida: true },
    ]);
  });

  it('lista con la página y solo los filtros elegidos', () => {
    service.listar({ page: 1, proveedorId: 4, desde: '2026-10-01' }).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/compras') && r.method === 'GET');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('20');
    expect(req.request.params.get('proveedorId')).toBe('4');
    expect(req.request.params.get('desde')).toBe('2026-10-01');
    expect(req.request.params.has('hasta')).toBe(false);
    req.flush({ items: [], page: 1, size: 20, totalElements: 0, totalPages: 0 });
  });

  it('obtiene una compra por id', () => {
    service.obtener(9).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/compras/9'));
    expect(req.request.method).toBe('GET');
    req.flush(compra);
  });

  it('lee la factura en PDF con un POST multipart a /compras/factura-pdf con el campo archivo', () => {
    const archivo = new File(['%PDF'], 'factura.pdf', { type: 'application/pdf' });
    service.leerFactura(archivo).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/compras/factura-pdf'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBe(true);
    expect((req.request.body as FormData).get('archivo')).toBe(archivo);
    req.flush({});
  });
});
