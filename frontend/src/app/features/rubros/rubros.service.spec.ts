import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Rubro } from './rubro.models';
import { RubrosService } from './rubros.service';

describe('RubrosService', () => {
  let service: RubrosService;
  let http: HttpTestingController;

  const buloneria: Rubro = { id: 1, nombre: 'Bulonería', margenPorcentaje: 35 };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RubrosService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('actualiza un rubro con PUT /rubros/{id}', () => {
    let actualizado: Rubro | undefined;
    service.actualizar(1, { nombre: 'Bulonería', margenPorcentaje: 40 }).subscribe((r) => (actualizado = r));

    const req = http.expectOne((r) => r.url.endsWith('/rubros/1'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ nombre: 'Bulonería', margenPorcentaje: 40 });
    req.flush({ ...buloneria, margenPorcentaje: 40 });

    expect(actualizado?.margenPorcentaje).toBe(40);
  });

  it('cuenta los artículos que heredan el margen con GET /rubros/{id}/articulos-que-heredan', () => {
    let cantidad: number | undefined;
    service.articulosQueHeredan(1).subscribe((n) => (cantidad = n));

    const req = http.expectOne((r) => r.url.endsWith('/rubros/1/articulos-que-heredan'));
    expect(req.request.method).toBe('GET');
    req.flush({ cantidad: 23 });

    expect(cantidad).toBe(23);
  });
});
