import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PageResponse } from '../../core/api/page.models';
import {
  EmitirPresupuestoRequest,
  PresupuestoEmitido,
  PresupuestoResponse,
  PresupuestoResumen,
} from './presupuesto.models';
import { PresupuestosService } from './presupuestos.service';

describe('PresupuestosService', () => {
  let service: PresupuestosService;
  let http: HttpTestingController;

  const pedido: EmitirPresupuestoRequest = {
    clave: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
    renglones: [{ articuloId: 1, cantidad: 3, precioUnitario: 170 }],
    totalMostrado: 510,
    diasValidez: 15,
    clienteNombre: 'Obra Perez',
  };

  const presupuesto: PresupuestoResponse = {
    presupuestoId: 8,
    numero: 12,
    estado: 'PENDIENTE',
    fecha: '2026-10-18T16:03:00',
    vence: '2026-11-02',
    vencido: false,
    clienteNombre: 'Obra Perez',
    subtotal: 510,
    descuentoPorcentaje: 0,
    descuento: 0,
    neto: 421.49,
    iva: 88.51,
    total: 510,
    renglones: [
      {
        renglon: 1,
        articuloId: 1,
        descripcion: 'Tornillo',
        unidad: 'UNIDAD',
        cantidad: 3,
        precioUnitario: 170,
        alicuotaIva: 'IVA_21',
        bruto: 510,
        descuentoPorcentaje: 0,
        total: 510,
        neto: 421.49,
        iva: 88.51,
      },
    ],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(PresupuestosService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('hace POST a /presupuestos con el cuerpo del contrato, sin campos de más', () => {
    service.emitir(pedido).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/presupuestos'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(pedido);
    expect(Object.keys(req.request.body).sort()).toEqual([
      'clave',
      'clienteNombre',
      'diasValidez',
      'renglones',
      'totalMostrado',
    ]);
    req.flush(presupuesto, { status: 201, statusText: 'Created' });
  });

  it('201 es un presupuesto nuevo', () => {
    let r: PresupuestoEmitido | undefined;
    service.emitir(pedido).subscribe((x) => (r = x));
    http.expectOne((q) => q.url.endsWith('/presupuestos')).flush(presupuesto, { status: 201, statusText: 'Created' });
    expect(r).toEqual({ presupuesto, repetido: false });
  });

  it('200 es un presupuesto repetido (misma clave y contenido)', () => {
    let r: PresupuestoEmitido | undefined;
    service.emitir(pedido).subscribe((x) => (r = x));
    http.expectOne((q) => q.url.endsWith('/presupuestos')).flush(presupuesto, { status: 200, statusText: 'OK' });
    expect(r).toEqual({ presupuesto, repetido: true });
  });

  describe('listar', () => {
    const resumen: PresupuestoResumen = {
      presupuestoId: 8,
      numero: 12,
      estado: 'PENDIENTE',
      fecha: '2026-10-18T16:03:00',
      vence: '2026-11-02',
      vencido: false,
      clienteNombre: 'Obra Perez',
      total: 510,
    };
    const pagina: PageResponse<PresupuestoResumen> = { items: [resumen], page: 0, size: 20, totalElements: 1, totalPages: 1 };

    it('hace GET a /presupuestos con la página y sin filtros que no se eligieron', () => {
      let r: PageResponse<PresupuestoResumen> | undefined;
      service.listar({ page: 0 }).subscribe((x) => (r = x));

      const req = http.expectOne((q) => q.url.endsWith('/presupuestos'));
      expect(req.request.method).toBe('GET');
      expect(req.request.params.keys().sort()).toEqual(['page', 'size']);
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('20');
      req.flush(pagina);
      expect(r).toEqual(pagina);
    });

    it('manda el estado, el número y las fechas elegidos', () => {
      service.listar({ page: 2, size: 10, estado: 'ACEPTADO', numero: 12, desde: '2026-10-01', hasta: '2026-10-31' }).subscribe();

      const req = http.expectOne((q) => q.url.endsWith('/presupuestos'));
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('10');
      expect(req.request.params.get('estado')).toBe('ACEPTADO');
      expect(req.request.params.get('numero')).toBe('12');
      expect(req.request.params.get('desde')).toBe('2026-10-01');
      expect(req.request.params.get('hasta')).toBe('2026-10-31');
      req.flush(pagina);
    });
  });

  it('obtener hace GET a /presupuestos/{id}', () => {
    let r: PresupuestoResponse | undefined;
    service.obtener(8).subscribe((x) => (r = x));

    const req = http.expectOne((q) => q.url.endsWith('/presupuestos/8'));
    expect(req.request.method).toBe('GET');
    req.flush(presupuesto);
    expect(r).toEqual(presupuesto);
  });

  it('cambiarEstado hace PATCH a /presupuestos/{id}/estado con solo el estado y devuelve el detalle', () => {
    let r: PresupuestoResponse | undefined;
    service.cambiarEstado(8, 'ACEPTADO').subscribe((x) => (r = x));

    const req = http.expectOne((q) => q.url.endsWith('/presupuestos/8/estado'));
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ estado: 'ACEPTADO' });
    req.flush({ ...presupuesto, estado: 'ACEPTADO' });
    expect(r?.estado).toBe('ACEPTADO');
  });
});
