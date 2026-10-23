import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { StockBajoMinimoService } from './stock-bajo-minimo.service';

describe('StockBajoMinimoService', () => {
  let service: StockBajoMinimoService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(StockBajoMinimoService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('pide GET /articulos/bajo-minimo con el orden', () => {
    let filas: number | undefined;
    service.listar('RUBRO').subscribe((r) => (filas = r.length));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/bajo-minimo'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('orden')).toBe('RUBRO');
    req.flush([]);

    expect(filas).toBe(0);
  });
});
