import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BusquedaMostrador } from './mostrador.models';
import { MostradorService } from './mostrador.service';

describe('MostradorService', () => {
  let service: MostradorService;
  let http: HttpTestingController;

  const exacto: BusquedaMostrador = {
    tipo: 'EXACTO',
    items: [
      {
        id: 1,
        codigoInterno: 'TOR-001',
        codigoBarras: '7790001',
        codigoProveedor: null,
        descripcion: 'Tornillo hexagonal 1/4',
        precioFinal: 170,
        unidad: 'UNIDAD',
        stockActual: 12,
        stockMinimo: 5,
        ubicacion: 'Pasillo 2',
        activo: true,
      },
    ],
    hayMas: false,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MostradorService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('busca con GET /articulos/mostrador mandando q y devuelve el resultado tipado', () => {
    let recibido: BusquedaMostrador | undefined;
    service.buscar('7790001').subscribe((r) => (recibido = r));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/mostrador'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('7790001');
    req.flush(exacto);

    expect(recibido).toEqual(exacto);
  });

  it('manda q sin espacios sobrantes', () => {
    service.buscar('  tornillo 1/4 ').subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/articulos/mostrador'));
    expect(req.request.params.get('q')).toBe('tornillo 1/4');
    req.flush({ tipo: 'SIN_RESULTADOS', items: [], hayMas: false });
  });
});
