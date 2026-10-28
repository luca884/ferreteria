import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminFerreteriasService } from './admin-ferreterias.service';
import {
  AltaFerreteriaRequest,
  EstadoGeneral,
  FerreteriaAdmin,
} from './ferreteria-admin.models';

describe('AdminFerreteriasService', () => {
  let service: AdminFerreteriasService;
  let http: HttpTestingController;

  const tornillo: FerreteriaAdmin = {
    id: 3,
    razonSocial: 'El Tornillo SA',
    cuit: '30-12345678-1',
    condicionIva: 'RESPONSABLE_INSCRIPTO',
    estado: 'ACTIVA',
    creadoEn: '2026-10-21T10:00:00',
    duenosActivos: 1,
    vendedoresActivos: 2,
    duenoId: 7,
    emailDueno: 'dueno@tornillo.demo',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AdminFerreteriasService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lista el estado general con GET /admin/ferreterias', () => {
    const estado: EstadoGeneral = { activas: 1, suspendidas: 0, ferreterias: [tornillo] };
    let recibido: EstadoGeneral | undefined;
    service.listar().subscribe((e) => (recibido = e));

    const req = http.expectOne((r) => r.url.endsWith('/admin/ferreterias'));
    expect(req.request.method).toBe('GET');
    req.flush(estado);

    expect(recibido).toEqual(estado);
  });

  it('da de alta con POST /admin/ferreterias, con el Dueño anidado', () => {
    const request: AltaFerreteriaRequest = {
      razonSocial: 'El Tornillo SA',
      cuit: '30-12345678-1',
      condicionIva: 'RESPONSABLE_INSCRIPTO',
      dueno: { nombre: 'Ana', email: 'ana@tornillo.demo', password: 'clave1234' },
    };
    let creada: FerreteriaAdmin | undefined;
    service.alta(request).subscribe((f) => (creada = f));

    const req = http.expectOne((r) => r.url.endsWith('/admin/ferreterias'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(tornillo);

    expect(creada?.id).toBe(3);
  });

  it('suspende o reactiva con PATCH /admin/ferreterias/{id}/activo', () => {
    let devuelta: FerreteriaAdmin | undefined;
    service.cambiarActivo(3, false).subscribe((f) => (devuelta = f));

    const req = http.expectOne((r) => r.url.endsWith('/admin/ferreterias/3/activo'));
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ activo: false });
    req.flush({ ...tornillo, estado: 'SUSPENDIDA' });

    expect(devuelta?.estado).toBe('SUSPENDIDA');
  });

  it('resetea la contraseña del Dueño con PUT /admin/ferreterias/{id}/duenos/{uid}/password', () => {
    let listo = false;
    service.resetearContrasenaDueno(3, 7, 'nueva1234').subscribe(() => (listo = true));

    const req = http.expectOne((r) => r.url.endsWith('/admin/ferreterias/3/duenos/7/password'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ password: 'nueva1234' });
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(listo).toBe(true);
  });
});
