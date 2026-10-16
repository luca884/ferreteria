import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { AuthService } from '../core/auth/auth.service';
import { Cotizacion } from '../core/cotizacion/cotizacion.models';
import { PaginaConfiguracionCotizacion } from '../features/configuracion-cotizacion/configuracion-cotizacion';
import { ConfiguracionCotizacion } from '../features/configuracion-cotizacion/configuracion-cotizacion.models';
import { Shell } from './shell';

/**
 * El menú lateral y la pantalla de configuración con los servicios de verdad (solo el HTTP es de mentira): lo que
 * se guarda o se recalcula en la pantalla se ve en el lateral sin esperar los 5 minutos del refresco.
 */
describe('refresco del menú lateral desde Configuración › Cotización', () => {
  let http: HttpTestingController;
  let shell: ComponentFixture<Shell>;
  let pagina: ComponentFixture<PaginaConfiguracionCotizacion>;

  const configuracion: ConfiguracionCotizacion = {
    activa: true,
    dolarPrecios: 'USD:oficial',
    ladoPrecios: 'VENTA',
    visibles: ['USD:oficial'],
    horaRecalculo: '08:00',
    vigente: 1000,
    vigenteDesde: '2026-10-16T11:00:00Z',
  };

  const fila = (codigo: string, nombre: string, venta: number) => ({
    codigo,
    nombre,
    compra: venta - 50,
    venta,
    fuente: 'DOLARAPI',
    actualizadaEn: '2026-10-16T11:00:00Z',
    obtenidaEn: '2026-10-16T12:30:00Z',
    desactualizada: false,
    esDePrecios: codigo === 'USD:oficial',
  });

  const soloOficial: Cotizacion = {
    activa: true,
    desactualizada: false,
    ultimaRespuesta: '2026-10-16T12:30:00Z',
    visibles: [fila('USD:oficial', 'Oficial', 1000)],
    precios: { codigo: 'USD:oficial', lado: 'VENTA', valor: 1000, desde: '2026-10-16T11:00:00Z' },
  };

  const conBlueYEuro: Cotizacion = {
    ...soloOficial,
    visibles: [fila('USD:oficial', 'Oficial', 1000), fila('USD:blue', 'Blue', 1200), fila('EUR:oficial', 'Euro', 1500)],
  };

  const texto = (e: Element | null) => e?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const lateral = () => texto((shell.nativeElement as HTMLElement).querySelector('app-cotizacion-lateral'));

  async function estabilizar() {
    shell.detectChanges();
    pagina.detectChanges();
    await shell.whenStable();
    await pagina.whenStable();
    shell.detectChanges();
    pagina.detectChanges();
  }

  const pedidosDeCotizacion = () => http.match((r) => r.method === 'GET' && r.url.endsWith('/cotizacion'));

  beforeEach(async () => {
    vi.useFakeTimers();
    await TestBed.configureTestingModule({
      imports: [Shell, PaginaConfiguracionCotizacion],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { esDueno: signal(true), sesion: signal(null), logout: () => undefined } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);

    shell = TestBed.createComponent(Shell);
    pagina = TestBed.createComponent(PaginaConfiguracionCotizacion);
    await estabilizar();
    // Carga inicial: el lateral lee la cotización y la pantalla lee la configuración.
    pedidosDeCotizacion().forEach((r) => r.flush(soloOficial));
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('/configuracion-cotizacion')).flush(configuracion);
    await estabilizar();
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function guardarConBlueYEuro() {
    const form = (pagina.nativeElement as HTMLElement).querySelector('form')!;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  }

  it('al guardar con éxito el lateral vuelve a pedir la cotización y muestra los tipos nuevos, sin esperar 5 minutos', async () => {
    expect(lateral()).toContain('Oficial venta 1.000,00');
    expect(lateral()).not.toContain('Blue');

    guardarConBlueYEuro();
    http
      .expectOne((r) => r.method === 'PUT' && r.url.endsWith('/configuracion-cotizacion'))
      .flush({ ...configuracion, visibles: ['USD:oficial', 'USD:blue', 'EUR:oficial'] });
    const releidos = pedidosDeCotizacion();
    expect(releidos.length).toBe(1);
    releidos[0].flush(conBlueYEuro);
    await estabilizar();

    expect(lateral()).toContain('Blue venta 1.200,00');
    expect(lateral()).toContain('Euro venta 1.500,00');
  });

  it('si el guardado falla el lateral no vuelve a pedir la cotización', async () => {
    guardarConBlueYEuro();
    http
      .expectOne((r) => r.method === 'PUT' && r.url.endsWith('/configuracion-cotizacion'))
      .flush({ message: 'x' }, { status: 500, statusText: 'Server Error' });
    await estabilizar();

    expect(pedidosDeCotizacion().length).toBe(0);
    expect(lateral()).not.toContain('Blue');
  });

  it('después de «Recalcular ahora» exitoso el lateral vuelve a pedir la cotización', async () => {
    (pagina.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button.recalcular')!.click();
    http
      .expectOne((r) => r.method === 'POST' && r.url.endsWith('/cotizacion/recalculo'))
      .flush({ cotizacion: 1100, articulosRecalculados: 3 });
    // La pantalla también relee la configuración.
    http.match((r) => r.method === 'GET' && r.url.endsWith('/configuracion-cotizacion')).forEach((r) => r.flush(configuracion));
    const releidos = pedidosDeCotizacion();
    expect(releidos.length).toBe(1);
    releidos[0].flush(conBlueYEuro);
    await estabilizar();

    expect(lateral()).toContain('Blue venta 1.200,00');
  });

  it('si «Recalcular ahora» falla el lateral no vuelve a pedir la cotización', async () => {
    (pagina.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button.recalcular')!.click();
    http
      .expectOne((r) => r.method === 'POST' && r.url.endsWith('/cotizacion/recalculo'))
      .flush({ message: 'apagada' }, { status: 409, statusText: 'Conflict' });
    await estabilizar();

    expect(pedidosDeCotizacion().length).toBe(0);
  });
});
