import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { PaginaFacturacion } from './facturacion';
import { ConfiguracionFacturacion, CredencialArca, PedidoCertificado, PedidoPendiente } from './facturacion.models';
import { FacturacionService } from './facturacion.service';

describe('PaginaFacturacion', () => {
  let fixture: ComponentFixture<PaginaFacturacion>;
  let api: {
    configuracion: ReturnType<typeof vi.fn>;
    guardarPuntoVenta: ReturnType<typeof vi.fn>;
    generarPedido: ReturnType<typeof vi.fn>;
    pedido: ReturnType<typeof vi.fn>;
    subirCertificado: ReturnType<typeof vi.fn>;
    firmarConSimulador: ReturnType<typeof vi.fn>;
  };

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
  const PEM = '-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n';

  const config = (cambios: Partial<ConfiguracionFacturacion> = {}): ConfiguracionFacturacion => ({
    ambiente: 'HOMOLOGACION',
    puntoVenta: 3,
    puntoVentaEditable: true,
    motivoPuntoVentaTrabado: null,
    facturacionHabilitada: false,
    simulador: false,
    credencial: null,
    pedido: null,
    avisos: [],
    ...cambios,
  });

  const fallo = (status: number, error: object) => throwError(() => new HttpErrorResponse({ status, error }));

  async function crear(inicial: ConfiguracionFacturacion = config(), dueno = true, carga?: Observable<never>) {
    api = {
      configuracion: vi.fn(() => carga ?? of(inicial)),
      guardarPuntoVenta: vi.fn((puntoVenta: number) => of({ ...inicial, puntoVenta })),
      generarPedido: vi.fn(() => of(CSR)),
      pedido: vi.fn(() => of(CSR)),
      subirCertificado: vi.fn(() => of({ ...inicial, pedido: null, credencial: CREDENCIAL })),
      firmarConSimulador: vi.fn(() => of({ ...inicial, pedido: null, credencial: { ...CREDENCIAL, emisor: 'CA del simulador' } })),
    };
    await TestBed.configureTestingModule({
      imports: [PaginaFacturacion],
      providers: [
        { provide: FacturacionService, useValue: api },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
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

  /** Lo que llega por una promesa (FileReader, portapapeles): se espera revisando la pantalla en cada vuelta. */
  async function esperar(condicion: () => void) {
    await vi.waitFor(() => {
      fixture.detectChanges();
      condicion();
    });
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) => (el().querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const boton = (selector: string) => el().querySelector<HTMLButtonElement>(selector);

  async function click(selector: string) {
    boton(selector)!.click();
    await refrescar();
  }

  async function escribir(nombre: string, valor: string) {
    const campo = el().querySelector<HTMLInputElement>(`[name="${nombre}"]`)!;
    campo.value = valor;
    campo.dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function elegirArchivo(archivo: File) {
    const entrada = el().querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(entrada, 'files', { value: [archivo], configurable: true });
    entrada.dispatchEvent(new Event('change'));
    await refrescar();
  }

  describe('ambiente y punto de venta', () => {
    it('el título va en la barra; con el punto de venta trabado muestra el ambiente, el número y el motivo, sin campo', async () => {
      await crear(
        config({
          ambiente: 'SIMULADOR',
          puntoVenta: 1,
          puntoVentaEditable: false,
          motivoPuntoVentaTrabado: 'Con el simulador el punto de venta es 1.',
          simulador: true,
        }),
      );

      expect(texto('app-barra-acciones h1')).toBe('Facturación');
      expect(texto('.ambiente .valor')).toBe('Simulador');
      expect(texto('.punto-venta .valor')).toBe('1');
      expect(texto('.punto-venta .pista')).toBe('Con el simulador el punto de venta es 1.');
      expect(el().querySelector('[name="puntoVenta"]')).toBeNull();
    });

    it('sin ambiente avisa que lo fija el primer certificado', async () => {
      await crear(config({ ambiente: null, puntoVenta: null }));

      expect(texto('.ambiente')).toContain('Sin definir');
      expect(texto('.ambiente .pista')).toBe('Lo fija el primer certificado que subas.');
    });

    it('editable: guarda el número con PUT y confirma', async () => {
      await crear();
      expect(el().querySelector<HTMLInputElement>('[name="puntoVenta"]')!.value).toBe('3');

      await escribir('puntoVenta', '5');
      await click('button.guardar-punto-venta');

      expect(api.guardarPuntoVenta).toHaveBeenCalledExactlyOnceWith(5);
      expect(texto('.alerta.exito')).toBe('Se guardó el punto de venta.');
    });

    it('el 400 del servidor va en el campo y el 409 como aviso de la pantalla', async () => {
      await crear();
      api.guardarPuntoVenta.mockReturnValueOnce(
        fallo(400, { message: 'Datos inválidos', errors: { puntoVenta: 'El punto de venta va de 1 a 99998.' } }),
      );
      await escribir('puntoVenta', '0');
      await click('button.guardar-punto-venta');
      expect(texto('#puntoVenta-error')).toBe('El punto de venta va de 1 a 99998.');

      api.guardarPuntoVenta.mockReturnValueOnce(
        fallo(409, { message: 'Hay facturas en trámite con ARCA: el punto de venta no se puede cambiar.' }),
      );
      await click('button.guardar-punto-venta');
      expect(el().querySelector('#puntoVenta-error')).toBeNull();
      expect(texto('.error-accion')).toBe('Hay facturas en trámite con ARCA: el punto de venta no se puede cambiar.');
    });
  });

  describe('credencial', () => {
    it('muestra CUIT, emisor, ambiente, vigencia, huella y estado del certificado vigente', async () => {
      await crear(config({ credencial: CREDENCIAL }));

      const credencial = texto('.credencial');
      expect(credencial).toContain('30999999995');
      expect(credencial).toContain('Computadores Test');
      expect(credencial).toContain('Homologación');
      expect(credencial).toContain('Del 28/10/2026 al 27/10/2028');
      expect(texto('.credencial .huella')).toBe('AB:CD:EF:01');
      expect(texto('.credencial .estado')).toBe('Vigente');
      expect(el().querySelector('.detalle-estado')).toBeNull();
    });

    it('por vencer: «Vence el dd/mm/aaaa (en N días): renovalo.», en singular si falta un día', async () => {
      await crear(config({ credencial: { ...CREDENCIAL, vigenteHasta: '2026-11-26', estado: 'POR_VENCER', diasParaVencer: 29 } }));
      expect(texto('.credencial .estado')).toBe('Por vencer');
      expect(texto('.detalle-estado')).toBe('Vence el 26/11/2026 (en 29 días): renovalo.');

      TestBed.resetTestingModule();
      await crear(config({ credencial: { ...CREDENCIAL, vigenteHasta: '2026-10-29', estado: 'POR_VENCER', diasParaVencer: 1 } }));
      expect(texto('.detalle-estado')).toBe('Vence el 29/10/2026 (en 1 día): renovalo.');
    });

    it('vencida o ilegible: explica que no se factura y que hace falta un certificado nuevo', async () => {
      await crear(config({ credencial: { ...CREDENCIAL, vigenteHasta: '2026-10-20', estado: 'VENCIDA', diasParaVencer: -8 } }));
      expect(texto('.credencial .estado')).toBe('Vencida');
      expect(texto('.detalle-estado')).toBe(
        'Venció el 20/10/2026: no se puede facturar con ARCA hasta que subas un certificado nuevo.',
      );

      TestBed.resetTestingModule();
      await crear(config({ credencial: { ...CREDENCIAL, estado: 'ILEGIBLE' } }));
      expect(texto('.credencial .estado')).toBe('Ilegible');
      expect(texto('.detalle-estado')).toBe(
        'No se puede leer la clave guardada de este certificado, así que no se puede facturar con ARCA. ' +
          'Hace falta un certificado nuevo: generá un pedido y subí el certificado que te dé ARCA.',
      );
    });

    it('sin credencial: «Todavía no hay certificado de ARCA.»', async () => {
      await crear();

      expect(texto('.sin-credencial')).toBe('Todavía no hay certificado de ARCA.');
      expect(el().querySelector('.credencial')).toBeNull();
    });
  });

  describe('pedido de certificado', () => {
    let enlace: HTMLAnchorElement | undefined;
    let crearUrl: ReturnType<typeof vi.fn>;

    beforeEach(() => {
      enlace = undefined;
      crearUrl = vi.fn(() => 'blob:csr');
      Object.assign(URL, { createObjectURL: crearUrl, revokeObjectURL: vi.fn() });
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
        enlace = this;
      });
    });

    afterEach(() => vi.restoreAllMocks());

    it('guía los pasos en ARCA: clave fiscal, alias, .csr, .crt, renovación con «Agregar certificado» y wsfe', async () => {
      await crear();

      const guia = texto('ol.guia');
      expect(guia).toContain('clave fiscal');
      expect(guia).toContain('«Administración de certificados digitales»');
      expect(guia).toContain('WSASS');
      expect(guia).toContain('alias');
      expect(guia).toContain('(.csr)');
      expect(guia).toContain('(.crt)');
      expect(guia).toContain('«Agregar certificado»');
      expect(guia).toContain('«wsfe»');
      expect(guia).toContain('«Administrador de relaciones de clave fiscal»');
    });

    it('generar sin CUIT manda null; después ofrece bajar el .csr con su nombre y copiarlo, sin volver a pedirlo', async () => {
      await crear();
      api.configuracion.mockReturnValue(of(config({ pedido: PEDIDO })));
      const writeText = vi.fn(() => Promise.resolve());
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

      await click('button.generar-pedido');
      expect(api.generarPedido).toHaveBeenCalledExactlyOnceWith(null);
      expect(texto('.alerta.exito')).toBe('Se generó el pedido de certificado: bajalo y subilo en ARCA.');
      expect(texto('.pedido-pendiente')).toBe('Hay un pedido pendiente del 28/10 18:40, para la CUIT 30999999995.');

      await click('button.descargar-pedido');
      expect(texto('button.descargar-pedido')).toBe('Descargar pedido (.csr)');
      expect(enlace?.download).toBe('pedido-certificado-30999999995.csr');
      expect(await (crearUrl.mock.calls[0][0] as Blob).text()).toBe(CSR.csr);

      await click('button.copiar-pedido');
      await esperar(() => expect(texto('.alerta.exito')).toBe('Se copió el pedido.'));
      expect(writeText).toHaveBeenCalledWith(CSR.csr);
      expect(api.pedido).not.toHaveBeenCalled();

      writeText.mockReturnValueOnce(Promise.reject(new Error('sin permiso')));
      await click('button.copiar-pedido');
      await esperar(() => expect(texto('.error-accion')).toBe('No se pudo copiar: bajá el archivo.'));
    });

    it('con un pedido pendiente de antes, bajarlo lo pide con GET', async () => {
      await crear(config({ pedido: PEDIDO }));

      await click('button.descargar-pedido');

      expect(api.pedido).toHaveBeenCalledTimes(1);
      expect(enlace?.download).toBe('pedido-certificado-30999999995.csr');
    });

    it('generar otro con uno pendiente pide confirmación en la pantalla', async () => {
      await crear(config({ pedido: PEDIDO }));
      const confirmar = vi.spyOn(window, 'confirm');

      await click('button.generar-pedido');
      expect(api.generarPedido).not.toHaveBeenCalled();
      expect(confirmar).not.toHaveBeenCalled();
      expect(texto('[role="alertdialog"] h2')).toBe('¿Generar un pedido nuevo?');

      await click('[role="alertdialog"] button.confirmar');
      expect(api.generarPedido).toHaveBeenCalledTimes(1);
      expect(el().querySelector('[role="alertdialog"]')).toBeNull();
    });

    it('el CUIT del pedido va sin guiones y su 400 va en el campo; en producción no se ofrece', async () => {
      await crear();
      expect(texto('label[for="cuitPedido"]')).toBe('CUIT del certificado (solo para homologación)');
      api.generarPedido.mockReturnValueOnce(
        fallo(400, { message: 'Datos inválidos', errors: { cuit: 'El CUIT no es válido.' } }),
      );

      await escribir('cuitPedido', ' 20-12345678-9 ');
      await click('button.generar-pedido');

      expect(api.generarPedido).toHaveBeenCalledWith('20123456789');
      expect(texto('#cuitPedido-error')).toBe('El CUIT no es válido.');

      TestBed.resetTestingModule();
      await crear(config({ ambiente: 'PRODUCCION' }));
      expect(el().querySelector('[name="cuitPedido"]')).toBeNull();
    });

    it('si un certificado pasa la ferretería a producción, lo escrito en el CUIT ya no se manda y el rechazo se ve', async () => {
      await crear(config({ pedido: PEDIDO }));
      await escribir('cuitPedido', '20123456789');
      api.subirCertificado.mockReturnValueOnce(of(config({ ambiente: 'PRODUCCION', credencial: CREDENCIAL })));
      await elegirArchivo(new File([PEM], 'produccion.crt'));
      await esperar(() => expect(el().querySelector('[name="cuitPedido"]')).toBeNull());
      api.generarPedido.mockReturnValueOnce(fallo(400, { message: 'No se pudo generar el pedido.' }));

      await click('button.generar-pedido');

      expect(api.generarPedido).toHaveBeenCalledExactlyOnceWith(null);
      expect(texto('.error-accion')).toBe('No se pudo generar el pedido.');
    });
  });

  describe('subir el certificado', () => {
    it('lee el .crt y lo manda como texto; muestra la credencial nueva y los avisos de la respuesta', async () => {
      await crear(config({ pedido: PEDIDO }));
      const aviso = 'El certificado es de otra CUIT (20123456789): sirve solo para homologación.';
      api.subirCertificado.mockReturnValueOnce(of(config({ credencial: CREDENCIAL, avisos: [aviso] })));
      expect(texto('button.subir-certificado')).toBe('Subir certificado (.crt)');

      await elegirArchivo(new File([PEM], 'ferreteria.crt'));

      await esperar(() => expect(api.subirCertificado).toHaveBeenCalledExactlyOnceWith(PEM));
      await refrescar();
      expect(texto('.alerta.exito')).toBe('Se cargó el certificado de ARCA.');
      expect(texto('.avisos')).toBe(aviso);
      expect(texto('.credencial .estado')).toBe('Vigente');
    });

    it('no hay cómo subir una clave privada: el único archivo que se elige es el certificado', async () => {
      await crear(config({ pedido: PEDIDO }));

      const archivos = Array.from(el().querySelectorAll<HTMLInputElement>('input[type="file"]'));
      expect(archivos.map((a) => a.accept)).toEqual(['.crt,.pem,.cer']);
      const controles = Array.from(el().querySelectorAll('label, button')).map((c) => c.textContent ?? '');
      expect(controles.filter((c) => /clave/i.test(c))).toEqual([]);
    });

    it('un archivo de más de 16 KB no se manda', async () => {
      await crear(config({ pedido: PEDIDO }));

      await elegirArchivo(new File(['x'.repeat(16 * 1024 + 1)], 'grande.crt'));

      expect(texto('#certificado-error')).toBe('El certificado no puede pasar de 16 KB.');
      expect(api.subirCertificado).not.toHaveBeenCalled();
    });

    it('el 400 del servidor va en el campo y el 409 como aviso de la pantalla', async () => {
      await crear(config({ pedido: PEDIDO }));
      api.subirCertificado.mockReturnValueOnce(
        fallo(400, { message: 'Datos inválidos', errors: { certificado: 'El certificado no corresponde al pedido.' } }),
      );
      await elegirArchivo(new File([PEM], 'otro.crt'));
      await esperar(() => expect(texto('#certificado-error')).toBe('El certificado no corresponde al pedido.'));

      api.subirCertificado.mockReturnValueOnce(fallo(409, { message: 'No hay un pedido de certificado pendiente.' }));
      await elegirArchivo(new File([PEM], 'otro.crt'));
      await esperar(() => expect(texto('.error-accion')).toBe('No hay un pedido de certificado pendiente.'));
      expect(el().querySelector('#certificado-error')).toBeNull();
    });
  });

  describe('simulador', () => {
    it('con el simulador y un pedido pendiente, lo firma con un clic, rotulado como simulación', async () => {
      await crear(config({ ambiente: 'SIMULADOR', simulador: true, pedido: PEDIDO }));
      expect(texto('button.firmar-simulador')).toBe('Firmar con el simulador');
      expect(texto('.simulador .pista')).toContain('Solo para pruebas');

      await click('button.firmar-simulador');

      expect(api.firmarConSimulador).toHaveBeenCalledTimes(1);
      expect(texto('.alerta.exito')).toBe('El simulador firmó el pedido: el certificado quedó activo.');
      expect(texto('.credencial')).toContain('CA del simulador');
    });

    it('sin pedido pendiente no ofrece subir ni firmar; fuera del simulador no ofrece firmar', async () => {
      await crear(config({ ambiente: 'SIMULADOR', simulador: true }));
      expect(boton('button.firmar-simulador')).toBeNull();
      expect(boton('button.subir-certificado')).toBeNull();

      TestBed.resetTestingModule();
      await crear(config({ pedido: PEDIDO }));
      expect(boton('button.firmar-simulador')).toBeNull();
      expect(boton('button.subir-certificado')).not.toBeNull();
    });
  });

  it('si la carga falla, avisa y deja reintentar', async () => {
    await crear(config(), true, throwError(() => new HttpErrorResponse({ status: 500 })));

    expect(texto('.error')).toContain('No se pudo cargar la configuración de facturación.');
    api.configuracion.mockReturnValue(of(config({ credencial: CREDENCIAL })));
    await click('.reintentar');

    expect(texto('.credencial .estado')).toBe('Vigente');
  });

  it('el Vendedor ve el aviso y no pide nada', async () => {
    await crear(config(), false);

    expect(texto('.estado-vacio')).toBe('Solo el Dueño puede configurar la facturación.');
    expect(api.configuracion).not.toHaveBeenCalled();
  });
});
