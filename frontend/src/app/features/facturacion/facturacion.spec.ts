import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { PaginaFacturacion } from './facturacion';
import {
  ConfiguracionFacturacion,
  CredencialArca,
  PedidoCertificado,
  PedidoPendiente,
  PruebaConexion,
} from './facturacion.models';
import { FacturacionService } from './facturacion.service';
import { FacturacionDelMostrador } from '../mostrador/venta.models';

describe('PaginaFacturacion', () => {
  let fixture: ComponentFixture<PaginaFacturacion>;
  let api: {
    configuracion: ReturnType<typeof vi.fn>;
    guardarPuntoVenta: ReturnType<typeof vi.fn>;
    generarPedido: ReturnType<typeof vi.fn>;
    pedido: ReturnType<typeof vi.fn>;
    subirCertificado: ReturnType<typeof vi.fn>;
    firmarConSimulador: ReturnType<typeof vi.fn>;
    probarConexion: ReturnType<typeof vi.fn>;
    emision: ReturnType<typeof vi.fn>;
  };
  /** Lo que dice el mostrador cuando la ferretería sí emite (GET /api/ventas/facturacion). */
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
  const PEM = '-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n';
  /** «Probar conexión» completa (C1 de la rama 6), en el simulador: los cuatro pasos OK y el último número de A y B. */
  const PRUEBA_OK: PruebaConexion = {
    ambiente: 'SIMULADOR',
    ok: true,
    aviso: 'Simulador: no se probó contra ARCA',
    pasos: [
      { paso: 'SERVIDORES', ok: true, detalle: 'AppServer OK, DbServer OK, AuthServer OK' },
      { paso: 'AUTENTICACION', ok: true, detalle: 'Ticket de acceso vigente hasta 29/10/2026 18:40' },
      { paso: 'PUNTO_DE_VENTA', ok: true, detalle: 'Punto de venta 1: CAE, habilitado' },
      { paso: 'ULTIMOS_NUMEROS', ok: true, detalle: 'Factura A y Factura B' },
    ],
    ultimos: [
      { cbteTipo: 1, comprobante: 'Factura A', numero: 12 },
      { cbteTipo: 6, comprobante: 'Factura B', numero: 345 },
    ],
  };
  /** Una prueba que se corta en la autenticación: los pasos siguientes no figuran (C1). */
  const PRUEBA_FALLIDA: PruebaConexion = {
    ambiente: 'HOMOLOGACION',
    ok: false,
    aviso: null,
    pasos: [
      { paso: 'SERVIDORES', ok: true, detalle: 'AppServer OK, DbServer OK, AuthServer OK' },
      { paso: 'AUTENTICACION', ok: false, detalle: 'ARCA rechazó el certificado: Certificado expirado (cms.cert.expired)' },
    ],
    ultimos: [],
  };
  /** Uno de los dos textos del bloqueo de C4: largo, con el faultstring de ARCA y su código. */
  const AVISO_BLOQUEO =
    'ARCA rechazó el certificado: Certificado no emitido por AC de confianza (cms.cert.untrusted). ' +
    'La facturación está frenada hasta que cambies el certificado o pruebes la conexión.';

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

  async function crear(
    inicial: ConfiguracionFacturacion = config(),
    dueno = true,
    carga?: Observable<never>,
    emision: Observable<FacturacionDelMostrador> = of(EMITE),
  ) {
    api = {
      emision: vi.fn(() => emision),
      configuracion: vi.fn(() => carga ?? of(inicial)),
      guardarPuntoVenta: vi.fn((puntoVenta: number) => of({ ...inicial, puntoVenta })),
      generarPedido: vi.fn(() => of(CSR)),
      pedido: vi.fn(() => of(CSR)),
      subirCertificado: vi.fn(() => of({ ...inicial, pedido: null, credencial: CREDENCIAL })),
      firmarConSimulador: vi.fn(() => of({ ...inicial, pedido: null, credencial: { ...CREDENCIAL, emisor: 'CA del simulador' } })),
      probarConexion: vi.fn(() => of(PRUEBA_OK)),
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
      expect(credencial).toContain('30-99999999-5');
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
      expect(texto('.pedido-pendiente')).toBe('Hay un pedido pendiente del 28/10 18:40, para la CUIT 30-99999999-5.');

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

    it('si la ferretería pasa al simulador, lo escrito en el CUIT (que ya no se ve) no se manda', async () => {
      await crear();
      await escribir('cuitPedido', '20123456789');
      api.guardarPuntoVenta.mockReturnValueOnce(of(config({ ambiente: 'SIMULADOR', simulador: true, puntoVenta: 1 })));
      await click('button.guardar-punto-venta');
      expect(el().querySelector('[name="cuitPedido"]')).toBeNull();

      await click('button.generar-pedido');

      expect(api.generarPedido).toHaveBeenCalledExactlyOnceWith(null);
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

  describe('estado de la facturación', () => {
    it('habilitada: lo dice arriba de todo', async () => {
      await crear(config({ facturacionHabilitada: true }));

      expect(texto('.estado-facturacion')).toBe('Facturación habilitada: las ventas facturables salen con factura.');
      expect(el().querySelector('.estado-facturacion')!.compareDocumentPosition(el().querySelector('.secciones')!)).toBe(
        Node.DOCUMENT_POSITION_FOLLOWING,
      );
    });

    it('habilitada en el simulador: aclara que las facturas no tienen validez fiscal', async () => {
      await crear(config({ ambiente: 'SIMULADOR', simulador: true, facturacionHabilitada: true }));

      expect(texto('.estado-facturacion')).toBe(
        'Facturación habilitada: las ventas facturables salen con factura. Es el simulador: las facturas no tienen validez fiscal.',
      );
    });

    it('habilitada pero el mostrador no emite (falta el certificado, está vencido o ARCA frenó): no dice que salen con factura', async () => {
      await crear(config({ facturacionHabilitada: true }), true, undefined, of({ ...EMITE, factura: false }));

      expect(texto('.estado-facturacion')).toBe(
        'Facturación habilitada, pero todavía no emite: las ventas facturables se cobran igual y quedan sin factura hasta que el certificado, el ambiente y el punto de venta estén en orden.',
      );
      expect(el().querySelector('.estado-facturacion')!.classList).toContain('advertencia');
      expect(el().querySelector('.estado-facturacion')!.classList).not.toContain('exito');
    });

    it('lo que subir un certificado cambia se vuelve a preguntar: pasa de «todavía no emite» a «salen con factura»', async () => {
      await crear(config({ facturacionHabilitada: true, pedido: PEDIDO }), true, undefined, of({ ...EMITE, factura: false }));
      expect(texto('.estado-facturacion')).toContain('todavía no emite');

      api.emision.mockReturnValue(of(EMITE));
      api.subirCertificado.mockReturnValueOnce(of(config({ facturacionHabilitada: true, credencial: CREDENCIAL })));
      await elegirArchivo(new File([PEM], 'vigente.crt'));
      await esperar(() => expect(texto('.estado-facturacion')).toBe('Facturación habilitada: las ventas facturables salen con factura.'));
    });

    it('si no se puede preguntar al mostrador, queda lo que dice la configuración', async () => {
      await crear(config({ facturacionHabilitada: true }), true, undefined, fallo(500, { message: 'Error' }));

      expect(texto('.estado-facturacion')).toBe('Facturación habilitada: las ventas facturables salen con factura.');
    });

    it('no habilitada: dice que las ventas quedan sin factura y quién la habilita', async () => {
      await crear(config({ facturacionHabilitada: false }));

      expect(texto('.estado-facturacion')).toBe(
        'Facturación no habilitada: las ventas facturables se cobran igual y quedan sin factura. La habilita el administrador del sistema, con los datos de la ferretería completos y el ambiente asignado.',
      );
    });
  });

  describe('simulador', () => {
    it('no muestra los pasos de ARCA ni el CUIT de homologación: dice que alcanza con firmar con el simulador', async () => {
      await crear(config({ ambiente: 'SIMULADOR', simulador: true }));

      expect(el().querySelector('ol.guia')).toBeNull();
      expect(el().querySelector('[name="cuitPedido"]')).toBeNull();
      expect(texto('.guia-simulador')).toBe(
        'En el simulador no hace falta ir a ARCA: generá el pedido y firmalo con «Firmar con el simulador».',
      );
      expect(boton('button.generar-pedido')).not.toBeNull();
    });

    it('fuera del simulador no aparece esa línea', async () => {
      await crear();

      expect(el().querySelector('.guia-simulador')).toBeNull();
      expect(el().querySelector('ol.guia')).not.toBeNull();
    });

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

  describe('probar conexión', () => {
    const filas = (selector: string) =>
      Array.from(el().querySelectorAll(selector)).map((f) => (f.textContent ?? '').replace(/\s+/g, ' ').trim());

    it('el botón «Probar conexión» está solo cuando la configuración tiene ambiente', async () => {
      await crear(config({ ambiente: null, puntoVenta: null }));
      expect(boton('button.probar-conexion')).toBeNull();

      TestBed.resetTestingModule();
      await crear();
      expect(texto('button.probar-conexion')).toBe('Probar conexión');
    });

    it('mientras espera, el botón queda deshabilitado con «Probando…»', async () => {
      await crear();
      const respuesta = new Subject<PruebaConexion>();
      api.probarConexion.mockReturnValueOnce(respuesta);

      await click('button.probar-conexion');
      expect(api.probarConexion).toHaveBeenCalledTimes(1);
      expect(boton('button.probar-conexion')!.disabled).toBe(true);
      expect(texto('button.probar-conexion')).toBe('Probando…');

      respuesta.next(PRUEBA_OK);
      respuesta.complete();
      await refrescar();
      expect(boton('button.probar-conexion')!.disabled).toBe(false);
      expect(texto('button.probar-conexion')).toBe('Probar conexión');
    });

    it('muestra cada paso con su rótulo, su resultado y su detalle, los últimos números y el aviso del simulador', async () => {
      await crear(config({ ambiente: 'SIMULADOR', simulador: true, puntoVenta: 1 }));

      await click('button.probar-conexion');

      expect(el().querySelector('.resultado-conexion')!.getAttribute('aria-live')).toBe('polite');
      expect(texto('.resumen-prueba')).toBe('La prueba de conexión salió bien.');
      expect(filas('.paso')).toEqual([
        'Servidores de ARCA OK AppServer OK, DbServer OK, AuthServer OK',
        'Autenticación (WSAA) OK Ticket de acceso vigente hasta 29/10/2026 18:40',
        'Punto de venta OK Punto de venta 1: CAE, habilitado',
        'Últimos números OK Factura A y Factura B',
      ]);
      expect(filas('.ultimos thead th')).toEqual(['Comprobante', 'Último número']);
      const celdas = Array.from(el().querySelectorAll('.ultimos tbody tr')).map((fila) =>
        Array.from(fila.children).map((celda) => (celda.textContent ?? '').trim()),
      );
      expect(celdas).toEqual([
        ['Factura A', '12'],
        ['Factura B', '345'],
      ]);
      expect(texto('.aviso-prueba')).toBe('Simulador: no se probó contra ARCA');

      api.probarConexion.mockReturnValueOnce(of(PRUEBA_FALLIDA));
      await click('button.probar-conexion');

      expect(texto('.resumen-prueba')).toBe('La prueba de conexión falló.');
      expect(filas('.paso')).toEqual([
        'Servidores de ARCA OK AppServer OK, DbServer OK, AuthServer OK',
        'Autenticación (WSAA) Falló ARCA rechazó el certificado: Certificado expirado (cms.cert.expired)',
      ]);
      expect(el().querySelector('.ultimos')).toBeNull();
      expect(el().querySelector('.aviso-prueba')).toBeNull();
    });

    it('un 409 muestra el mensaje del servidor tal cual', async () => {
      await crear();
      api.probarConexion.mockReturnValueOnce(
        fallo(409, { message: 'Para probar la conexión hace falta un certificado vigente.' }),
      );

      await click('button.probar-conexion');

      expect(texto('.resultado-conexion .error-prueba')).toBe('Para probar la conexión hace falta un certificado vigente.');
      expect(el().querySelector('.paso')).toBeNull();
      expect(boton('button.probar-conexion')!.disabled).toBe(false);
    });

    it('después de un OK completo vuelve a pedir la configuración, que ya no trae el bloqueo; si un paso falla, no', async () => {
      await crear(config({ avisos: [AVISO_BLOQUEO] }));
      api.probarConexion.mockReturnValueOnce(of(PRUEBA_FALLIDA));

      await click('button.probar-conexion');
      expect(api.configuracion).toHaveBeenCalledTimes(1);
      expect(texto('.avisos')).toBe(AVISO_BLOQUEO);

      api.configuracion.mockReturnValue(of(config()));
      await click('button.probar-conexion');
      expect(api.configuracion).toHaveBeenCalledTimes(2);
      expect(el().querySelector('.avisos')).toBeNull();
      expect(texto('.resumen-prueba')).toBe('La prueba de conexión salió bien.');
    });
  });

  it('el aviso largo del bloqueo de ARCA (C4) se ve entero, arriba de las secciones', async () => {
    await crear(config({ avisos: [AVISO_BLOQUEO] }));

    const avisos = el().querySelector('.avisos')!;
    expect(avisos.getAttribute('role')).toBe('status');
    expect(texto('.avisos')).toBe(AVISO_BLOQUEO);
    const secciones = el().querySelector('.secciones')!;
    expect(avisos.compareDocumentPosition(secciones) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
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
