import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { FacturacionDelMostrador } from '../mostrador/venta.models';
import { PaginaFacturacion } from './facturacion';
import { ConfiguracionFacturacion, CredencialArca, PedidoCertificado, PedidoPendiente } from './facturacion.models';
import { FacturacionService } from './facturacion.service';

/*
 * movil-sectores T4 (S1, S2, S5, S6, S7): en el celular Facturación son tarjetas (artboard 04) y obtener o renovar el
 * certificado son TRES pantallas completas (artboard 05): la guía y «Generar pedido», el pedido (.csr) y subir el .crt.
 * Es la misma sección y el mismo estado de hoy: las tres pantallas están siempre en el DOM (el escritorio las ve juntas)
 * y el CSS del celular deja la activa. Vitest no evalúa media queries; las medidas se hacen en el navegador a 390 px.
 */
describe('PaginaFacturacion en el celular', () => {
  let fixture: ComponentFixture<PaginaFacturacion>;
  let api: {
    configuracion: ReturnType<typeof vi.fn>;
    generarPedido: ReturnType<typeof vi.fn>;
    pedido: ReturnType<typeof vi.fn>;
    subirCertificado: ReturnType<typeof vi.fn>;
    firmarConSimulador: ReturnType<typeof vi.fn>;
    emision: ReturnType<typeof vi.fn>;
    guardarPuntoVenta: ReturnType<typeof vi.fn>;
    probarConexion: ReturnType<typeof vi.fn>;
  };

  const EMITE: FacturacionDelMostrador = { factura: true, ambiente: 'HOMOLOGACION', letras: null };
  const CREDENCIAL: CredencialArca = {
    cuit: '30999999995',
    emisor: 'Computadores Test',
    ambiente: 'HOMOLOGACION',
    vigenteDesde: '2026-10-28',
    vigenteHasta: '2028-10-27',
    huella: 'AB:CD:EF:01',
    estado: 'VIGENTE',
    diasParaVencer: 729,
  };
  const PEDIDO: PedidoPendiente = { creadoEn: '2026-10-28T21:40:00Z', cuit: '30999999995', huella: '12:34:56' };
  const CSR: PedidoCertificado = {
    csr: '-----BEGIN CERTIFICATE REQUEST-----\nMIIC\n-----END CERTIFICATE REQUEST-----\n',
    archivo: 'pedido-certificado-30999999995.csr',
  };

  const config = (cambios: Partial<ConfiguracionFacturacion> = {}): ConfiguracionFacturacion => ({
    ambiente: 'HOMOLOGACION',
    puntoVenta: 3,
    puntoVentaEditable: true,
    motivoPuntoVentaTrabado: null,
    facturacionHabilitada: true,
    simulador: false,
    credencial: null,
    pedido: null,
    avisos: [],
    ...cambios,
  });

  async function crear(inicial: ConfiguracionFacturacion = config()) {
    api = {
      emision: vi.fn(() => of(EMITE)),
      configuracion: vi.fn(() => of(inicial)),
      guardarPuntoVenta: vi.fn(() => of(inicial)),
      generarPedido: vi.fn(() => of(CSR)),
      pedido: vi.fn(() => of(CSR)),
      subirCertificado: vi.fn(() => of({ ...inicial, pedido: null, credencial: CREDENCIAL })),
      firmarConSimulador: vi.fn(() => of({ ...inicial, pedido: null, credencial: CREDENCIAL })),
      probarConexion: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [PaginaFacturacion],
      providers: [
        { provide: FacturacionService, useValue: api },
        { provide: AuthService, useValue: { esDueno: signal(true) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PaginaFacturacion);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) => (el().querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const boton = (selector: string) => el().querySelector<HTMLButtonElement>(selector);
  const seccion = () => el().querySelector<HTMLElement>('section.pedido')!;
  const paso = () => seccion().dataset['paso'];
  const abierta = () => seccion().classList.contains('ficha-abierta');
  const volver = () => boton('section.pedido .ficha-barra .ficha-volver')!;
  const pie = () => Array.from(el().querySelectorAll('section.pedido .ficha-pie button')).map((b) => b.textContent!.trim());

  async function click(selector: string) {
    boton(selector)!.click();
    await refrescar();
  }

  /** Las reglas (de este componente) cuyo selector coincide, con la consulta de medios que las envuelve. */
  function reglasQue(patron: RegExp): { consulta: string; selector: string }[] {
    const reglas: { consulta: string; selector: string }[] = [];
    const recorrer = (lista: CSSRuleList, consulta: string) =>
      Array.from(lista).forEach((regla) => {
        const media = (regla as CSSMediaRule).media;
        if (media && (regla as CSSMediaRule).cssRules) {
          recorrer((regla as CSSMediaRule).cssRules, [consulta, media.mediaText].filter(Boolean).join(' '));
        } else if (patron.test((regla as CSSStyleRule).selectorText ?? '')) {
          reglas.push({ consulta, selector: (regla as CSSStyleRule).selectorText });
        }
      });
    Array.from(document.styleSheets).forEach((hoja) => {
      try {
        recorrer(hoja.cssRules, '');
      } catch {
        /* una hoja ilegible no es la del componente */
      }
    });
    return reglas;
  }

  describe('tarjetas', () => {
    it('la tarjeta del certificado tiene el botón que abre el flujo: «Renovar certificado» con uno, «Obtener certificado» sin', async () => {
      await crear(config({ credencial: CREDENCIAL }));
      expect(texto('section[aria-labelledby="titulo-credencial"] button.abrir-certificado')).toBe('Renovar certificado');

      TestBed.resetTestingModule();
      await crear(config({ credencial: null }));
      expect(texto('section[aria-labelledby="titulo-credencial"] button.abrir-certificado')).toBe('Obtener certificado');
    });

    it('el flujo del certificado empieza cerrado y el botón de la tarjeta lo abre en la primera pantalla', async () => {
      await crear();
      expect(abierta()).toBe(false);

      await click('button.abrir-certificado');

      expect(abierta()).toBe(true);
      expect(paso()).toBe('inicio');
    });

    it('el botón de la tarjeta y el pie del flujo son sólo del celular (cada uno con su regla base que lo esconde)', async () => {
      await crear();

      const reglas = reglasQue(/abrir-certificado|certificado-pie/);
      for (const clase of ['abrir-certificado', 'certificado-pie']) {
        expect(reglas.some((r) => r.selector.includes(clase) && r.consulta === '')).toBe(true);
      }
      for (const regla of reglas) {
        // Lo que se muestra o se mide en el celular va dentro de la consulta; el resto es la regla base que lo esconde.
        expect(regla.consulta === '' || regla.consulta.includes('max-width: 639.98px')).toBe(true);
      }
    });

    it('las tres pantallas están siempre en el DOM, con lo mismo de hoy: guía, CUIT, generar, subir y firmar', async () => {
      await crear(config({ simulador: false }));

      expect(el().querySelector('section.pedido ol.guia')).not.toBeNull();
      expect(el().querySelector('section.pedido .cuit-pedido input')).not.toBeNull();
      expect(texto('section.pedido button.generar-pedido')).toBe('Generar pedido de certificado');
      expect(texto('section.pedido button.subir-certificado')).toBe('Subir certificado (.crt)');
      expect(texto('section.pedido #certificado-pista')).toBe('El archivo que bajaste de ARCA (.crt, .pem o .cer), de hasta 16 KB.');

      TestBed.resetTestingModule();
      await crear(config({ simulador: true, ambiente: 'SIMULADOR', pedido: PEDIDO }));
      expect(texto('section.pedido button.firmar-simulador')).toBe('Firmar con el simulador');
      expect(el().querySelector('section.pedido button.descargar-pedido')).not.toBeNull();
      expect(el().querySelector('section.pedido button.copiar-pedido')).not.toBeNull();
    });
  });

  describe('tres pantallas del certificado', () => {
    it('pantalla 1: la barra con «Volver» y el título; el pie fijo trae «Generar pedido de certificado», que va al mismo manejador de hoy', async () => {
      await crear();
      await click('button.abrir-certificado');

      expect(seccion().querySelector('.ficha-barra.solo-celular')).not.toBeNull();
      expect(volver().getAttribute('aria-label')).toBe('Volver');
      expect(texto('section.pedido .ficha-barra h2')).toBe('Facturación');
      expect(pie()).toEqual(['Generar pedido de certificado']);

      await click('section.pedido .ficha-pie button');

      expect(api.generarPedido).toHaveBeenCalledExactlyOnceWith(null);
    });

    it('pantalla 1 con un pedido pendiente: «Generar» sigue pidiendo confirmación y se puede ir a verlo sin generar otro', async () => {
      await crear(config({ pedido: PEDIDO }));
      await click('button.abrir-certificado');

      expect(pie()).toEqual(['Generar pedido de certificado', 'Ver el pedido pendiente']);

      await click('section.pedido .ficha-pie .generar-pedido-pie');
      expect(api.generarPedido).not.toHaveBeenCalled();
      expect(texto('[role="alertdialog"] h2')).toBe('¿Generar un pedido nuevo?');
      await click('[role="alertdialog"] button.cancelar');

      await click('section.pedido .ficha-pie .ver-pedido');
      expect(paso()).toBe('pedido');
      expect(api.generarPedido).not.toHaveBeenCalled();
    });

    it('generar el pedido pasa a la pantalla 2: el aviso va adentro del flujo, el pie dice «Ya tengo el .crt» y Copiar y Descargar son los de hoy', async () => {
      await crear();
      api.configuracion.mockReturnValue(of(config({ pedido: PEDIDO })));
      const writeText = vi.fn(() => Promise.resolve());
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
      await click('button.abrir-certificado');

      await click('section.pedido .ficha-pie button');

      expect(paso()).toBe('pedido');
      expect(texto('section.pedido .ficha-barra h2')).toBe('Pedido de certificado');
      expect(pie()).toEqual(['Ya tengo el .crt']);
      expect(seccion().querySelector('.alerta.exito')!.textContent!.trim()).toBe(
        'Se generó el pedido de certificado: bajalo y subilo en ARCA.',
      );
      expect(el().querySelectorAll('.alerta.exito').length).toBe(2); // el estado de arriba y el aviso, sin duplicar
      expect(texto('section.pedido .pedido-pendiente')).toBe('Hay un pedido pendiente del 28/10 18:40, para la CUIT 30-99999999-5.');

      await click('section.pedido button.copiar-pedido');
      await vi.waitFor(() => {
        fixture.detectChanges();
        expect(seccion().textContent).toContain('Se copió el pedido.');
      });
      expect(writeText).toHaveBeenCalledWith(CSR.csr);
      expect(api.pedido).not.toHaveBeenCalled();
    });

    it('un rechazo al generar se ve adentro del flujo abierto y, con el flujo cerrado, arriba como siempre', async () => {
      await crear();
      api.generarPedido.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'No se puede generar ahora.' } })),
      );
      await click('button.abrir-certificado');
      await click('section.pedido .ficha-pie button');

      expect(paso()).toBe('inicio');
      expect(seccion().querySelector('.error-accion')!.textContent!.trim()).toBe('No se puede generar ahora.');
      expect(el().querySelectorAll('.error-accion').length).toBe(1);

      await click('section.pedido .ficha-barra .ficha-volver');
      expect(abierta()).toBe(false);
      expect(seccion().querySelector('.error-accion')).toBeNull();
      expect(texto('.error-accion')).toBe('No se puede generar ahora.');
    });

    it('«Ya tengo el .crt» lleva a la pantalla 3: subir (el botón de hoy, que abre el selector de archivos) y firmar con el simulador', async () => {
      await crear(config({ pedido: PEDIDO, simulador: true, ambiente: 'SIMULADOR' }));
      await click('button.abrir-certificado');
      await click('section.pedido .ficha-pie .ver-pedido');
      await click('section.pedido .ficha-pie .ya-tengo-crt');

      expect(paso()).toBe('subir');
      expect(texto('section.pedido .ficha-barra h2')).toBe('Subir certificado');
      expect(pie()).toEqual([]);
      const entrada = el().querySelector<HTMLInputElement>('input[type="file"]')!;
      const abrirSelector = vi.spyOn(entrada, 'click');

      await click('section.pedido button.subir-certificado');
      expect(abrirSelector).toHaveBeenCalledTimes(1);

      await click('section.pedido button.firmar-simulador');
      expect(api.firmarConSimulador).toHaveBeenCalledTimes(1);
    });

    it('«Volver» retrocede de a una pantalla (3 → 2 → 1) y en la primera cierra el flujo sin generar ni descartar nada', async () => {
      await crear(config({ pedido: PEDIDO }));
      await click('button.abrir-certificado');
      await click('section.pedido .ficha-pie .ver-pedido');
      await click('section.pedido .ficha-pie .ya-tengo-crt');
      expect(paso()).toBe('subir');

      await click('section.pedido .ficha-barra .ficha-volver');
      expect(paso()).toBe('pedido');
      await click('section.pedido .ficha-barra .ficha-volver');
      expect(paso()).toBe('inicio');
      expect(abierta()).toBe(true);
      await click('section.pedido .ficha-barra .ficha-volver');
      expect(abierta()).toBe(false);

      expect(api.generarPedido).not.toHaveBeenCalled();
      expect(api.subirCertificado).not.toHaveBeenCalled();
    });

    it('el certificado cargado (o firmado) cierra el flujo y el aviso queda arriba, con la credencial nueva en la tarjeta', async () => {
      await crear(config({ pedido: PEDIDO }));
      await click('button.abrir-certificado');
      await click('section.pedido .ficha-pie .ver-pedido');
      await click('section.pedido .ficha-pie .ya-tengo-crt');
      const entrada = el().querySelector<HTMLInputElement>('input[type="file"]')!;
      Object.defineProperty(entrada, 'files', {
        value: [new File(['-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n'], 'a.crt')],
        configurable: true,
      });

      entrada.dispatchEvent(new Event('change'));
      await vi.waitFor(() => {
        fixture.detectChanges();
        expect(api.subirCertificado).toHaveBeenCalledTimes(1);
      });
      await refrescar();

      expect(abierta()).toBe(false);
      expect(paso()).toBe('inicio');
      expect(texto('.alerta.exito')).not.toBe('');
      expect(el().textContent).toContain('Se cargó el certificado de ARCA.');
      expect(seccion().querySelector('.alerta')).toBeNull();
      expect(texto('section[aria-labelledby="titulo-credencial"] button.abrir-certificado')).toBe('Renovar certificado');
    });

    it('sin pedido, «Subir certificado» sigue explicando por qué no se puede, con la misma línea de hoy', async () => {
      await crear();
      await click('button.abrir-certificado');

      expect(texto('section.pedido #certificado-motivo')).toBe('Primero generá el pedido de certificado.');
      expect(boton('section.pedido button.subir-certificado')!.getAttribute('aria-disabled')).toBe('true');
    });
  });
});
