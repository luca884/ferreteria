import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Rubro, VistaPreviaMargen } from './rubro.models';
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

  it('pide la vista previa del margen nuevo con GET /rubros/{id}/vista-previa-margen?margenPorcentaje=', () => {
    const vista: VistaPreviaMargen = {
      cantidad: 1,
      articulos: [{ id: 7, codigo: 'TOR-1', descripcion: 'Tornillo', precioActual: 1640, precioNuevo: 1650 }],
      recortado: false,
    };
    let recibida: VistaPreviaMargen | undefined;
    service.vistaPreviaMargen(1, 40.5).subscribe((v) => (recibida = v));

    const req = http.expectOne((r) => r.url.endsWith('/rubros/1/vista-previa-margen'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('margenPorcentaje')).toBe('40.5');
    req.flush(vista);

    expect(recibida).toEqual(vista);
  });
});
