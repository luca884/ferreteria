import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Proveedor } from './proveedor.models';
import { ProveedoresService } from './proveedores.service';

describe('ProveedoresService', () => {
  let service: ProveedoresService;
  let http: HttpTestingController;

  const sinteplast: Proveedor = { id: 1, nombre: 'Sinteplast', cuit: null, activo: true };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ProveedoresService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lista los proveedores con GET /proveedores', () => {
    let recibidos: Proveedor[] = [];
    service.listar().subscribe((lista) => (recibidos = lista));

    const req = http.expectOne((r) => r.url.endsWith('/proveedores'));
    expect(req.request.method).toBe('GET');
    req.flush([sinteplast]);

    expect(recibidos).toEqual([sinteplast]);
  });

  it('crea un proveedor con POST /proveedores', () => {
    let creado: Proveedor | undefined;
    service
      .crear({ nombre: 'Sinteplast', cuit: null, activo: true })
      .subscribe((p) => (creado = p));

    const req = http.expectOne((r) => r.url.endsWith('/proveedores'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ nombre: 'Sinteplast', cuit: null, activo: true });
    req.flush(sinteplast);

    expect(creado).toEqual(sinteplast);
  });

  it('actualiza un proveedor con PUT /proveedores/{id}', () => {
    const editado: Proveedor = { ...sinteplast, nombre: 'Sinteplast SA', cuit: '30-12345678-9' };
    let actualizado: Proveedor | undefined;
    service
      .actualizar(1, { nombre: 'Sinteplast SA', cuit: '30-12345678-9', activo: true })
      .subscribe((p) => (actualizado = p));

    const req = http.expectOne((r) => r.url.endsWith('/proveedores/1'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      nombre: 'Sinteplast SA',
      cuit: '30-12345678-9',
      activo: true,
    });
    req.flush(editado);

    expect(actualizado).toEqual(editado);
  });

  it('desactiva un proveedor con DELETE /proveedores/{id}', () => {
    let terminado = false;
    service.desactivar(1).subscribe(() => (terminado = true));

    const req = http.expectOne((r) => r.url.endsWith('/proveedores/1'));
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(terminado).toBe(true);
  });
});
