import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ConfiguracionFacturacion, PedidoCertificado } from './facturacion.models';
import { FacturacionService } from './facturacion.service';

describe('FacturacionService', () => {
  let service: FacturacionService;
  let http: HttpTestingController;

  const config: ConfiguracionFacturacion = {
    ambiente: 'SIMULADOR',
    puntoVenta: 1,
    puntoVentaEditable: false,
    motivoPuntoVentaTrabado: 'Con el simulador el punto de venta es 1.',
    facturacionHabilitada: true,
    simulador: true,
    credencial: null,
    pedido: null,
    avisos: [],
  };
  const pedido: PedidoCertificado = {
    csr: '-----BEGIN CERTIFICATE REQUEST-----\nMIIB\n-----END CERTIFICATE REQUEST-----\n',
    archivo: 'pedido-certificado-30999999995.csr',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(FacturacionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lee la configuración con GET /facturacion/configuracion', () => {
    let leida: ConfiguracionFacturacion | undefined;
    service.configuracion().subscribe((c) => (leida = c));

    const req = http.expectOne((r) => r.url.endsWith('/facturacion/configuracion'));
    expect(req.request.method).toBe('GET');
    req.flush(config);

    expect(leida).toEqual(config);
  });

  it('guarda el punto de venta con PUT /facturacion/punto-venta', () => {
    service.guardarPuntoVenta(3).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/facturacion/punto-venta'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ puntoVenta: 3 });
    req.flush(config);
  });

  it('genera el pedido con POST y lo vuelve a bajar con GET /facturacion/pedido-certificado', () => {
    let generado: PedidoCertificado | undefined;
    service.generarPedido(null).subscribe((p) => (generado = p));
    const alta = http.expectOne((r) => r.url.endsWith('/facturacion/pedido-certificado'));
    expect(alta.request.method).toBe('POST');
    expect(alta.request.body).toEqual({ cuit: null });
    alta.flush(pedido);
    expect(generado).toEqual(pedido);

    let bajado: PedidoCertificado | undefined;
    service.pedido().subscribe((p) => (bajado = p));
    const lectura = http.expectOne((r) => r.url.endsWith('/facturacion/pedido-certificado'));
    expect(lectura.request.method).toBe('GET');
    lectura.flush(pedido);
    expect(bajado).toEqual(pedido);
  });

  it('sube el certificado como texto con POST /facturacion/certificado', () => {
    service.subirCertificado('-----BEGIN CERTIFICATE-----').subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/facturacion/certificado'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ certificado: '-----BEGIN CERTIFICATE-----' });
    req.flush(config);
  });

  it('firma con el simulador con POST /facturacion/certificado/simulador', () => {
    service.firmarConSimulador().subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/facturacion/certificado/simulador'));
    expect(req.request.method).toBe('POST');
    req.flush(config);
  });
});
