import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EmitirPresupuestoRequest, PresupuestoEmitido, PresupuestoResponse } from './presupuesto.models';
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
});
