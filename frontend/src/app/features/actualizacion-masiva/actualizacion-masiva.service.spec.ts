import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import {
  AplicarPedidoMasivo,
  ConteosMasivos,
  PedidoMasivo,
  ResultadoMasivo,
  VistaPreviaMasiva,
} from './actualizacion-masiva.models';
import { ActualizacionMasivaService } from './actualizacion-masiva.service';

describe('ActualizacionMasivaService', () => {
  let service: ActualizacionMasivaService;
  let http: HttpTestingController;

  const conteos: ConteosMasivos = {
    total: 5,
    suben: 2,
    bajan: 0,
    sinCambioDePrecio: 2,
    conError: 1,
    pasanAMargenPropio: 0,
    conAdvertencia: 0,
    modificados: 3,
    yaEnElDestino: 0,
  };
  const pedido: PedidoMasivo = {
    proveedorId: 7,
    rubroId: 3,
    q: 'cable',
    incluirInactivos: true,
    modo: 'COSTO_PORCENTAJE',
    valor: 12.5,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ActualizacionMasivaService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('vistaPrevia: POST JSON a /actualizacion-masiva/vista-previa con todos los campos del pedido', () => {
    const respuesta: VistaPreviaMasiva = {
      conteos,
      cambios: [],
      errores: [],
      detalleRecortado: false,
      huella: 'abc123',
    };
    let recibida: VistaPreviaMasiva | undefined;
    service.vistaPrevia(pedido).subscribe((v) => (recibida = v));

    const req = http.expectOne(`${environment.apiUrl}/actualizacion-masiva/vista-previa`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      proveedorId: 7,
      rubroId: 3,
      q: 'cable',
      incluirInactivos: true,
      modo: 'COSTO_PORCENTAJE',
      valor: 12.5,
    });
    expect(typeof req.request.body.valor).toBe('number');
    expect(req.request.headers.has('Content-Type')).toBe(false);
    req.flush(respuesta);

    expect(recibida).toEqual(respuesta);
  });

  it('vistaPrevia: un filtro vacío viaja con proveedor, rubro y texto nulos', () => {
    const vacio: PedidoMasivo = {
      proveedorId: null,
      rubroId: null,
      q: null,
      incluirInactivos: false,
      modo: 'MARGEN',
      valor: 0,
    };
    service.vistaPrevia(vacio).subscribe();

    const req = http.expectOne(`${environment.apiUrl}/actualizacion-masiva/vista-previa`);
    expect(req.request.body).toEqual({
      proveedorId: null,
      rubroId: null,
      q: null,
      incluirInactivos: false,
      modo: 'MARGEN',
      valor: 0,
    });
    req.flush({ conteos, cambios: [], errores: [], detalleRecortado: false, huella: 'h' });
  });

  it('los modos del precio final viajan tal cual en la vista previa y al aplicar (CU-14)', () => {
    const modos: PedidoMasivo['modo'][] = ['PRECIO_PORCENTAJE', 'PRECIO_MONTO'];
    for (const modo of modos) {
      service.vistaPrevia({ ...pedido, modo, valor: -250 }).subscribe();
      service.aplicar({ ...pedido, modo, valor: -250, huella: 'h' }).subscribe();

      const previa = http.expectOne(`${environment.apiUrl}/actualizacion-masiva/vista-previa`);
      const aplicada = http.expectOne(`${environment.apiUrl}/actualizacion-masiva`);
      expect(previa.request.body).toEqual(expect.objectContaining({ modo, valor: -250 }));
      expect(aplicada.request.body).toEqual(expect.objectContaining({ modo, valor: -250, huella: 'h' }));
      previa.flush({ conteos, cambios: [], errores: [], detalleRecortado: false, huella: 'h' });
      aplicada.flush({ conteos, entradasDeHistorial: 0, cambios: [], detalleRecortado: false });
    }
  });

  it('aplicar: POST JSON a /actualizacion-masiva con el pedido y la huella', () => {
    const aplicar: AplicarPedidoMasivo = { ...pedido, valor: -20, huella: 'h-64' };
    const respuesta: ResultadoMasivo = { conteos, entradasDeHistorial: 3, cambios: [], detalleRecortado: false };
    let recibida: ResultadoMasivo | undefined;
    service.aplicar(aplicar).subscribe((r) => (recibida = r));

    const req = http.expectOne(`${environment.apiUrl}/actualizacion-masiva`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      proveedorId: 7,
      rubroId: 3,
      q: 'cable',
      incluirInactivos: true,
      modo: 'COSTO_PORCENTAJE',
      valor: -20,
      huella: 'h-64',
    });
    expect(req.request.headers.has('Content-Type')).toBe(false);
    req.flush(respuesta, { status: 201, statusText: 'Created' });

    expect(recibida).toEqual(respuesta);
  });

  it('vistaPrevia y aplicar van a URLs distintas (la vista previa no pega a la base)', () => {
    service.vistaPrevia(pedido).subscribe();
    service.aplicar({ ...pedido, huella: 'h' }).subscribe();

    const previa = http.expectOne(`${environment.apiUrl}/actualizacion-masiva/vista-previa`);
    const aplicada = http.expectOne(`${environment.apiUrl}/actualizacion-masiva`);
    expect(previa.request.body).not.toHaveProperty('huella');
    expect(aplicada.request.body).toHaveProperty('huella', 'h');
    previa.flush({ conteos, cambios: [], errores: [], detalleRecortado: false, huella: 'h' });
    aplicada.flush({ conteos, entradasDeHistorial: 0, cambios: [], detalleRecortado: false });
  });
});
