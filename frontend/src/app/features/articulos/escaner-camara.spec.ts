import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LOCALE_ID } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { Articulo } from './articulo.models';
import { ArticulosService } from './articulos.service';
import { CamaraEscaner, SesionCamara } from './camara-escaner';
import { EscanerCamara } from './escaner-camara';

registerLocaleData(localeEsAr);

/**
 * movil-sectores T7: la pantalla del escáner. La cámara (`CamaraEscaner`) se simula entera: lo que se prueba es qué ve
 * y qué puede hacer quien escanea, y que NUNCA se recuerde un permiso negado (L18).
 */
describe('EscanerCamara', () => {
  const articulo = (id: number, cambios: Partial<Articulo> = {}): Articulo =>
    ({
      id,
      codigoInterno: `COD-${id}`,
      codigoBarras: null,
      codigoProveedor: null,
      descripcion: `Artículo ${id}`,
      precioFinal: 1240,
      unidad: 'UNIDAD',
      stockActual: 12,
      activo: true,
      alicuotaIva: 'IVA_21',
      moneda: 'ARS',
      ...cambios,
    }) as Articulo;
  const pagina = (items: Articulo[]): PageResponse<Articulo> => ({
    items,
    page: 0,
    size: 20,
    totalElements: items.length,
    totalPages: 1,
  });

  const sesionFalsa = (cambios: Partial<SesionCamara> = {}) =>
    ({
      stream: {} as MediaStream,
      tieneLinterna: false,
      leer: vi.fn(async () => null),
      linterna: vi.fn(async () => undefined),
      detener: vi.fn(),
      ...cambios,
    }) as SesionCamara & { leer: ReturnType<typeof vi.fn>; detener: ReturnType<typeof vi.fn>; linterna: ReturnType<typeof vi.fn> };

  const DENEGADA =
    'No pudimos usar la cámara. Si la bloqueaste, habilitala desde los permisos del navegador y volvé a intentar.';

  let fixture: ComponentFixture<EscanerCamara>;
  let abrir: ReturnType<typeof vi.fn>;
  let buscar: ReturnType<typeof vi.fn>;
  const cerrar = vi.fn();
  const aMano = vi.fn();
  const ficha = vi.fn();

  async function crear(abrirImpl: () => Promise<SesionCamara>, buscarImpl: () => unknown = () => of(pagina([]))) {
    abrir = vi.fn(abrirImpl);
    buscar = vi.fn(buscarImpl);
    cerrar.mockClear();
    aMano.mockClear();
    ficha.mockClear();
    await TestBed.configureTestingModule({
      imports: [EscanerCamara],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: CamaraEscaner, useValue: { disponible: () => true, abrir } },
        { provide: ArticulosService, useValue: { buscar } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(EscanerCamara);
    fixture.componentInstance.cerrar.subscribe(cerrar);
    fixture.componentInstance.escribirAMano.subscribe(aMano);
    fixture.componentInstance.abrirFicha.subscribe(ficha);
    await mostrar();
  }

  async function mostrar() {
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();
  }
  const el = () => fixture.nativeElement as HTMLElement;
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => (b.getAttribute('aria-label') ?? b.textContent ?? '').trim() === nombre,
    );
  const clic = async (nombre: string) => {
    const b = boton(nombre);
    if (!b) throw new Error(`No hay botón «${nombre}»`);
    b.click();
    await mostrar();
  };
  const texto = () => (el().textContent ?? '').replace(/\s+/g, ' ');
  /** Deja pasar un ciclo de lectura (~250 ms). */
  const ciclo = async () => {
    await vi.advanceTimersByTimeAsync(300);
    fixture.detectChanges();
  };
  const denegada = () => Promise.reject(new DOMException('Permission denied', 'NotAllowedError'));

  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it('abre como un diálogo de pantalla completa con cerrar, la pista y la cámara pedida', async () => {
    const sesion = sesionFalsa();
    await crear(async () => sesion);

    const dialogo = el().querySelector('[role="dialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(abrir).toHaveBeenCalledTimes(1);
    expect(boton('Cerrar el escáner')).toBeDefined();
    expect(texto()).toContain('Apuntá al código de barras');
    expect(el().querySelector('video')).not.toBeNull();
    // La linterna sólo aparece si la pista la soporta.
    expect(boton('Encender la linterna')).toBeUndefined();
  });

  it('mientras el navegador pide el permiso avisa que está pidiendo acceso', async () => {
    await crear(() => new Promise<SesionCamara>(() => undefined));

    expect(el().querySelector('[role="status"]')!.textContent).toContain('Pidiendo permiso para usar la cámara');
    expect(texto()).not.toContain('Apuntá al código de barras');
  });

  describe('permiso negado, sin cámara y otras fallas (L18)', () => {
    it('negado: muestra el mensaje con «Intentar de nuevo» y «Escribir el código a mano», sin romper', async () => {
      await crear(denegada);

      const alerta = el().querySelector('[role="alert"]')!;
      expect(alerta.textContent!.trim()).toBe(DENEGADA);
      expect(boton('Intentar de nuevo')).toBeDefined();
      expect(boton('Escribir el código a mano')).toBeDefined();
      expect(boton('Cerrar el escáner')).toBeDefined();
      expect(texto()).not.toContain('Apuntá al código de barras');
    });

    it('«Intentar de nuevo» vuelve a pedir la cámara y, si ahora se permite, sigue escaneando', async () => {
      let intento = 0;
      const sesion = sesionFalsa();
      await crear(async () => {
        if (++intento === 1) throw new DOMException('x', 'NotAllowedError');
        return sesion;
      });
      expect(abrir).toHaveBeenCalledTimes(1);

      await clic('Intentar de nuevo');

      expect(abrir).toHaveBeenCalledTimes(2);
      expect(el().querySelector('[role="alert"]')).toBeNull();
      expect(texto()).toContain('Apuntá al código de barras');
    });

    it('«Intentar de nuevo» con el permiso todavía negado muestra el mensaje otra vez', async () => {
      await crear(denegada);
      await clic('Intentar de nuevo');
      expect(abrir).toHaveBeenCalledTimes(2);
      expect(el().querySelector('[role="alert"]')!.textContent!.trim()).toBe(DENEGADA);
    });

    it('cerrar y volver a entrar después de un permiso negado vuelve a pedirlo, y no se guarda nada', async () => {
      const guardar = vi.spyOn(Storage.prototype, 'setItem');
      const leer = vi.spyOn(Storage.prototype, 'getItem');
      await crear(denegada);
      fixture.destroy();
      TestBed.resetTestingModule();
      const primero = abrir;

      await crear(denegada);

      expect(primero).toHaveBeenCalledTimes(1);
      expect(abrir).toHaveBeenCalledTimes(1);
      expect(el().querySelector('[role="alert"]')!.textContent!.trim()).toBe(DENEGADA);
      expect(guardar).not.toHaveBeenCalled();
      expect(leer).not.toHaveBeenCalled();
      expect(localStorage.length).toBe(0);
      guardar.mockRestore();
      leer.mockRestore();
    });

    it('sin cámara: el mensaje es otro que el del permiso negado', async () => {
      await crear(() => Promise.reject(new DOMException('x', 'NotFoundError')));

      const mensaje = el().querySelector('[role="alert"]')!.textContent!.trim();
      expect(mensaje).toBe('No encontramos una cámara en este dispositivo. Escribí el código a mano.');
      expect(mensaje).not.toBe(DENEGADA);
      expect(boton('Escribir el código a mano')).toBeDefined();
    });

    it('cualquier otra falla tiene su propio mensaje', async () => {
      await crear(() => Promise.reject(new DOMException('x', 'NotReadableError')));

      expect(el().querySelector('[role="alert"]')!.textContent!.trim()).toBe(
        'No pudimos abrir la cámara. Puede que otra aplicación la esté usando. Probá de nuevo o escribí el código a mano.',
      );
    });

    it('«Escribir el código a mano» desde una falla avisa sin código (sólo cierra y enfoca el buscador)', async () => {
      await crear(denegada);

      await clic('Escribir el código a mano');

      expect(aMano).toHaveBeenCalledWith(null);
    });
  });

  describe('lectura', () => {
    const leyendo = (codigo = '7790001000123') => {
      const sesion = sesionFalsa();
      sesion.leer.mockResolvedValueOnce(null).mockResolvedValue(codigo);
      return sesion;
    };

    it('a la primera lectura apaga la cámara y busca el código con el servicio de artículos', async () => {
      const sesion = leyendo();
      await crear(async () => sesion);
      expect(sesion.detener).not.toHaveBeenCalled();

      await ciclo();
      await ciclo();

      expect(sesion.detener).toHaveBeenCalledTimes(1);
      expect(buscar).toHaveBeenCalledTimes(1);
      expect(buscar.mock.calls[0][0]).toBe('7790001000123');
      // Sigue sin leer después: el intervalo se cortó.
      const lecturas = sesion.leer.mock.calls.length;
      await ciclo();
      expect(sesion.leer.mock.calls.length).toBe(lecturas);
    });

    it('si la búsqueda trae el artículo, lo muestra con descripción, código, stock y precio final', async () => {
      const buscado = articulo(5, { descripcion: 'Bulón 3/8', codigoBarras: '7790001000123', stockActual: 12, precioFinal: 1240 });
      await crear(
        async () => leyendo(),
        () => of(pagina([articulo(4, { codigoBarras: '7790001000999' }), buscado])),
      );

      await ciclo();
      await ciclo();

      const tarjeta = el().querySelector('.leido')!;
      expect(tarjeta.getAttribute('role')).toBe('status');
      expect(tarjeta.textContent).toContain('Bulón 3/8');
      expect(tarjeta.textContent).toContain('7790001000123');
      expect(tarjeta.textContent).toContain('stock 12');
      expect(tarjeta.textContent).toMatch(/\$\s?1\.240,00/);
      expect(boton('Abrir ficha')).toBeDefined();
      expect(boton('Escanear de nuevo')).toBeDefined();
      expect(boton('Escribir el código a mano')).toBeDefined();
    });

    it('«Abrir ficha» entrega el artículo cuyo código de barras coincide, no el primero de la lista', async () => {
      const buscado = articulo(5, { codigoBarras: '7790001000123' });
      await crear(
        async () => leyendo(),
        () => of(pagina([articulo(4, { codigoBarras: '77900010001234' }), buscado])),
      );
      await ciclo();
      await ciclo();

      await clic('Abrir ficha');

      expect(ficha).toHaveBeenCalledTimes(1);
      expect(ficha).toHaveBeenCalledWith(buscado);
    });

    it('un UPC-A de 12 dígitos encuentra el EAN-13 con un 0 adelante', async () => {
      const buscado = articulo(5, { codigoBarras: '0123456789012' });
      await crear(
        async () => leyendo('123456789012'),
        () => of(pagina([buscado])),
      );
      await ciclo();
      await ciclo();

      await clic('Abrir ficha');
      expect(ficha).toHaveBeenCalledWith(buscado);
    });

    it('un artículo dado de baja se muestra marcado; entre varios con el mismo código gana el activo', async () => {
      const baja = articulo(4, { codigoBarras: '7790001000123', activo: false, descripcion: 'Viejo' });
      const activo = articulo(5, { codigoBarras: '7790001000123', descripcion: 'Nuevo' });
      await crear(
        async () => leyendo(),
        () => of(pagina([baja, activo])),
      );
      await ciclo();
      await ciclo();
      expect(el().querySelector('.leido')!.textContent).toContain('Nuevo');

      TestBed.resetTestingModule();
      await crear(
        async () => leyendo(),
        () => of(pagina([baja])),
      );
      await ciclo();
      await ciclo();
      expect(el().querySelector('.leido')!.textContent).toContain('Dado de baja');
    });

    it('si ningún artículo tiene ese código: «No encontramos un artículo con el código X.», sin llamar a abrir ficha', async () => {
      await crear(
        async () => leyendo(),
        () => of(pagina([articulo(4, { codigoBarras: '000' }), articulo(6, { codigoBarras: null })])),
      );
      await ciclo();
      await ciclo();

      expect(el().querySelector('[role="status"]')!.textContent!.trim()).toContain(
        'No encontramos un artículo con el código 7790001000123.',
      );
      expect(boton('Abrir ficha')).toBeUndefined();
      expect(boton('Escanear de nuevo')).toBeDefined();
      expect(boton('Escribir el código a mano')).toBeDefined();
    });

    it('«Escribir el código a mano» con un código leído lo entrega para completar el buscador', async () => {
      await crear(async () => leyendo());
      await ciclo();
      await ciclo();

      await clic('Escribir el código a mano');

      expect(aMano).toHaveBeenCalledWith('7790001000123');
    });

    it('«Escanear de nuevo» vuelve a pedir la cámara y a mirar', async () => {
      await crear(async () => leyendo());
      await ciclo();
      await ciclo();
      expect(abrir).toHaveBeenCalledTimes(1);

      await clic('Escanear de nuevo');

      expect(abrir).toHaveBeenCalledTimes(2);
      expect(texto()).toContain('Apuntá al código de barras');
    });

    it('si la búsqueda falla lo dice, sin colgarse, y deja escanear de nuevo o escribir a mano', async () => {
      await crear(
        async () => leyendo(),
        () => throwError(() => new Error('boom')),
      );
      await ciclo();
      await ciclo();

      expect(el().querySelector('[role="alert"]')!.textContent!.trim()).toBe(
        'No pudimos buscar el código 7790001000123. Probá de nuevo.',
      );
      expect(boton('Escanear de nuevo')).toBeDefined();
      expect(boton('Escribir el código a mano')).toBeDefined();
    });

    it('tras cinco fallas seguidas apaga la cámara, avisa y deja escribir el código a mano', async () => {
      const sesion = sesionFalsa();
      sesion.leer.mockRejectedValue(new Error('InvalidStateError'));
      await crear(async () => sesion);

      await vi.advanceTimersByTimeAsync(1000);
      fixture.detectChanges();
      expect(sesion.leer).toHaveBeenCalledTimes(4);
      expect(texto()).toContain('Apuntá al código de barras');

      await vi.advanceTimersByTimeAsync(250);
      fixture.detectChanges();
      expect(el().querySelector('[role="alert"]')?.textContent?.trim()).toBe(
        'No pudimos leer el código de barras. Probá de nuevo o escribilo a mano.',
      );
      expect(texto()).not.toContain('Apuntá al código de barras');
      expect(sesion.detener).toHaveBeenCalledTimes(1);
      expect(buscar).not.toHaveBeenCalled();
      await ciclo();
      expect(sesion.leer).toHaveBeenCalledTimes(5);

      await clic('Escribir el código a mano');
      expect(aMano).toHaveBeenCalledWith(null);
    });

    it('los cuadros sin código no cuentan como fallas y cortan la racha, sin impedir una lectura normal', async () => {
      const sesion = sesionFalsa();
      const error = new Error('InvalidStateError');
      for (let i = 0; i < 6; i++) sesion.leer.mockResolvedValueOnce(null);
      for (let i = 0; i < 4; i++) sesion.leer.mockRejectedValueOnce(error);
      sesion.leer.mockResolvedValueOnce(null);
      for (let i = 0; i < 4; i++) sesion.leer.mockRejectedValueOnce(error);
      sesion.leer.mockResolvedValueOnce('7790001000123');
      await crear(async () => sesion);

      await vi.advanceTimersByTimeAsync(3750);
      fixture.detectChanges();
      expect(sesion.leer).toHaveBeenCalledTimes(15);
      expect(texto()).toContain('Apuntá al código de barras');
      expect(sesion.detener).not.toHaveBeenCalled();

      await vi.advanceTimersByTimeAsync(250);
      expect(buscar).toHaveBeenCalledExactlyOnceWith('7790001000123', 0, 20, true);
      expect(sesion.detener).toHaveBeenCalledTimes(1);
    });

    it('una lectura que falla (el detector tira) no corta el escaneo', async () => {
      const sesion = sesionFalsa();
      sesion.leer.mockRejectedValueOnce(new Error('InvalidStateError')).mockResolvedValue('7790001000123');
      await crear(async () => sesion);

      await ciclo();
      await ciclo();

      expect(buscar).toHaveBeenCalledTimes(1);
    });
  });

  describe('la cámara se apaga siempre', () => {
    it('al cerrar la pantalla (destruirla, también al navegar a otra)', async () => {
      const sesion = sesionFalsa();
      await crear(async () => sesion);

      fixture.destroy();

      expect(sesion.detener).toHaveBeenCalledTimes(1);
    });

    it('si el permiso se concede después de cerrar, esa cámara se apaga en el acto', async () => {
      const sesion = sesionFalsa();
      let conceder!: (s: SesionCamara) => void;
      await crear(() => new Promise<SesionCamara>((resolve) => (conceder = resolve)));

      fixture.destroy();
      conceder(sesion);
      await vi.advanceTimersByTimeAsync(0);

      expect(sesion.detener).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(1000);
      expect(sesion.leer).not.toHaveBeenCalled();
    });

    it('al reintentar apaga la sesión anterior antes de abrir otra', async () => {
      const primera = sesionFalsa();
      primera.leer.mockResolvedValue('7790001000123');
      const segunda = sesionFalsa();
      const sesiones = [primera, segunda];
      await crear(async () => sesiones.shift()!);
      await ciclo();
      await clic('Escanear de nuevo');

      expect(primera.detener).toHaveBeenCalled();
      expect(segunda.detener).not.toHaveBeenCalled();
    });

    it('al destruirse con la búsqueda en curso no queda nada colgado', async () => {
      const respuesta = new Subject<PageResponse<Articulo>>();
      const sesion = sesionFalsa();
      sesion.leer.mockResolvedValue('7790001000123');
      await crear(
        async () => sesion,
        () => respuesta,
      );
      await ciclo();
      expect(respuesta.observed).toBe(true);

      fixture.destroy();

      expect(respuesta.observed).toBe(false);
    });
  });

  describe('teclado y controles', () => {
    it('Esc cierra el escáner', async () => {
      await crear(async () => sesionFalsa());

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

      expect(cerrar).toHaveBeenCalledTimes(1);
    });

    it('la ✕ cierra el escáner', async () => {
      await crear(async () => sesionFalsa());
      await clic('Cerrar el escáner');
      expect(cerrar).toHaveBeenCalledTimes(1);
    });

    it('el foco entra en el diálogo al abrir y Tab no se sale de él (trampa de foco)', async () => {
      await crear(denegada);

      const dialogo = el().querySelector<HTMLElement>('[role="dialog"]')!;
      expect(dialogo.contains(document.activeElement)).toBe(true);
      const controles = Array.from(dialogo.querySelectorAll<HTMLElement>('button'));
      controles[controles.length - 1].focus();
      const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
      document.dispatchEvent(tab);
      expect(tab.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(controles[0]);
    });

    it('la linterna aparece sólo si la pista la soporta, y alterna encendida y apagada', async () => {
      const sesion = sesionFalsa({ tieneLinterna: true });
      await crear(async () => sesion);

      await clic('Encender la linterna');
      expect(sesion.linterna).toHaveBeenLastCalledWith(true);
      expect(boton('Apagar la linterna')!.getAttribute('aria-pressed')).toBe('true');

      await clic('Apagar la linterna');
      expect(sesion.linterna).toHaveBeenLastCalledWith(false);
      expect(boton('Encender la linterna')).toBeDefined();
    });

    it('si la linterna no se puede prender, el escáner sigue andando', async () => {
      const sesion = sesionFalsa({ tieneLinterna: true });
      sesion.linterna.mockRejectedValue(new Error('no'));
      await crear(async () => sesion);

      await clic('Encender la linterna');

      expect(boton('Encender la linterna')).toBeDefined();
      expect(texto()).toContain('Apuntá al código de barras');
    });

    it('todos los controles miden al menos 44 px por regla (sólo se verifica que no son íconos sueltos sin nombre)', async () => {
      await crear(denegada);
      for (const b of Array.from(el().querySelectorAll('button'))) {
        expect((b.getAttribute('aria-label') ?? b.textContent ?? '').trim()).not.toBe('');
      }
    });
  });
});
