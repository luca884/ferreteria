import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AjusteStockResponse, Unidad } from './articulo.models';
import { ArticulosService } from './articulos.service';
import { ArticuloAAjustar, DialogoAjusteStock } from './dialogo-ajuste-stock';

describe('DialogoAjusteStock', () => {
  let fixture: ComponentFixture<DialogoAjusteStock>;
  let servicio: { ajustarStock: ReturnType<typeof vi.fn> };
  let ajustados: AjusteStockResponse[];
  let canceladas: number;

  const tornillo = (stockActual = 10, unidad: Unidad = 'UNIDAD'): ArticuloAAjustar => ({
    id: 7,
    descripcion: 'Tornillo hexagonal 1/4',
    unidad,
    stockActual,
  });

  // jsdom no implementa showModal ni close de <dialog>: se simulan para ver que el panel los use.
  let showModal: ReturnType<typeof vi.fn>;
  let cerrar: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    showModal = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    });
    cerrar = vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    });
    Object.assign(HTMLDialogElement.prototype, { showModal, close: cerrar });
  });
  afterEach(() => {
    const proto = HTMLDialogElement.prototype as unknown as Record<string, unknown>;
    delete proto['showModal'];
    delete proto['close'];
  });

  async function abrir(articulo: ArticuloAAjustar) {
    servicio = {
      ajustarStock: vi.fn(() =>
        of({ articuloId: 7, stockAnterior: articulo.stockActual, diferencia: 2, stockActual: 12 }),
      ),
    };
    await TestBed.configureTestingModule({
      imports: [DialogoAjusteStock],
      providers: [{ provide: ArticulosService, useValue: servicio }],
    }).compileComponents();
    fixture = TestBed.createComponent(DialogoAjusteStock);
    ajustados = [];
    canceladas = 0;
    fixture.componentRef.setInput('articulo', articulo);
    fixture.componentInstance.ajustado.subscribe((r) => ajustados.push(r));
    fixture.componentInstance.cancelar.subscribe(() => canceladas++);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) => (el().querySelector(selector)?.textContent ?? '').trim();

  async function escribir(selector: string, valor: string) {
    const campo = el().querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
    campo.value = valor;
    campo.dispatchEvent(new Event(campo instanceof HTMLSelectElement ? 'change' : 'input'));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function elegirModo(modo: 'STOCK_REAL' | 'CANTIDAD') {
    el().querySelector<HTMLButtonElement>(`[data-modo="${modo}"]`)!.click();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function elegirSigno(signo: 'ENTRA' | 'SALE') {
    el().querySelector<HTMLButtonElement>(`[data-signo="${signo}"]`)!.click();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const marcado = (selector: string) => el().querySelector(selector)!.getAttribute('aria-checked') === 'true';

  async function confirmar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('muestra el artículo y su stock actual con la unidad, y arranca en el stock real', async () => {
    await abrir(tornillo(497));

    expect(texto('h2')).toBe('Ajustar stock');
    expect(el().textContent).toContain('Tornillo hexagonal 1/4');
    expect(texto('.stock-actual')).toBe('497 unidades');
    expect(document.activeElement).toBe(el().querySelector('#stock-real'));
  });

  describe('panel lateral con <dialog> nativo', () => {
    const dialogo = () => el().querySelector<HTMLDialogElement>('dialog')!;

    it('se abre como modal nativo (trampa de foco y Esc del navegador) con el artículo como nombre', async () => {
      await abrir(tornillo(10));

      expect(dialogo()).not.toBeNull();
      expect(dialogo().classList).toContain('panel-lateral');
      expect(showModal).toHaveBeenCalledTimes(1);
      expect(dialogo().open).toBe(true);
      const nombre = (dialogo().getAttribute('aria-labelledby') ?? '')
        .split(' ')
        .map((id) => el().querySelector(`#${id}`)?.textContent?.trim())
        .join(' ');
      expect(nombre).toBe('Ajustar stock Tornillo hexagonal 1/4');
    });

    it('muestra el código interno y el stock de hoy debajo del nombre', async () => {
      await abrir({ ...tornillo(25), codigoInterno: 'PIN-104' });

      expect(texto('.referencia').replace(/\s+/g, ' ')).toBe('PIN-104 · hoy 25 unidades');
    });

    it('Esc (evento cancel del <dialog>) cancela sin que el navegador lo cierre por su cuenta', async () => {
      await abrir(tornillo(10));
      const evento = new Event('cancel', { cancelable: true });

      dialogo().dispatchEvent(evento);

      expect(evento.defaultPrevented).toBe(true);
      expect(canceladas).toBe(1);
    });

    it('al cerrarse devuelve el foco al botón que lo abrió', async () => {
      const abridor = document.createElement('button');
      document.body.appendChild(abridor);
      abridor.focus();

      await abrir(tornillo(10));
      expect(document.activeElement).not.toBe(abridor);
      fixture.destroy();

      expect(cerrar).toHaveBeenCalled();
      expect(document.activeElement).toBe(abridor);
      abridor.remove();
    });

    it('vista previa «25 → 49 unidades» al sumar', async () => {
      await abrir(tornillo(25));
      await escribir('#motivo', 'INGRESO_MERCADERIA');

      await escribir('#cantidad', '24');

      expect(texto('.vista-previa').replace(/\s+/g, ' ')).toBe('25 → 49 unidades');
    });

    it('vista previa del conteo: «10 → 12 unidades» y la diferencia', async () => {
      await abrir(tornillo(10));

      await escribir('#stock-real', '12');

      expect(texto('.vista-previa .desde').replace(/\s+/g, ' ')).toBe('10 →');
      expect(texto('.vista-previa .quedara')).toBe('12 unidades');
      expect(texto('.vista-previa .diferencia')).toBe('+2 unidades');
    });

    describe('con una cantidad que el artículo no acepta muestra «—» en vez del resultado', () => {
      it('1,5 en un artículo por unidad: «—» en vivo y también junto al error al confirmar', async () => {
        await abrir(tornillo(0));
        await escribir('#motivo', 'INGRESO_MERCADERIA');

        await escribir('#cantidad', '1,5');
        expect(texto('.vista-previa .quedara')).toBe('—');

        await confirmar();
        expect(texto('#cantidad-error')).toBe(
          'Este artículo se cuenta por unidad: la cantidad tiene que ser un número entero.',
        );
        expect(texto('.vista-previa .quedara')).toBe('—');
      });

      it('stock real con decimales en un artículo por unidad: «—» en el resultado y en la diferencia', async () => {
        await abrir(tornillo(10));

        await escribir('#stock-real', '12,5');

        expect(texto('.vista-previa .quedara')).toBe('—');
        expect(texto('.vista-previa .diferencia')).toBe('—');
      });

      it.each([
        ['más de 3 decimales', '2,1234'],
        ['más del máximo', '1000000000000'],
      ])('%s en metros', async (_caso, valor) => {
        await abrir(tornillo(150, 'METRO'));
        await escribir('#motivo', 'INGRESO_MERCADERIA');

        await escribir('#cantidad', valor);

        expect(texto('.vista-previa .quedara')).toBe('—');
      });

      it('stock real negativo', async () => {
        await abrir(tornillo(10));

        await escribir('#stock-real', '-3');

        expect(texto('.vista-previa .quedara')).toBe('—');
        expect(texto('.vista-previa .diferencia')).toBe('—');
      });

      it('sacar más de lo que hay no es un error de formato: sigue mostrando el resultado negativo', async () => {
        await abrir(tornillo(2));
        await escribir('#motivo', 'ROTURA');

        await escribir('#cantidad', '5');
        await confirmar();

        expect(texto('#cantidad-error')).toBe('No podés sacar 5: hay 2 unidades.');
        expect(texto('.vista-previa .quedara')).toBe('-3 unidades');
      });
    });
  });

  describe('grupos de opciones con flechas (roving tabindex)', () => {
    const tecla = (selector: string, key: string) => {
      el().querySelector(selector)!.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      fixture.detectChanges();
    };
    const tabindex = (selector: string) => el().querySelector(selector)!.getAttribute('tabindex');

    it('solo la opción marcada de cada grupo entra en el orden de tabulación', async () => {
      await abrir(tornillo(10));
      expect(tabindex('[data-modo="STOCK_REAL"]')).toBe('0');
      expect(tabindex('[data-modo="CANTIDAD"]')).toBe('-1');

      await elegirModo('CANTIDAD');

      expect(tabindex('[data-modo="CANTIDAD"]')).toBe('0');
      expect(tabindex('[data-modo="STOCK_REAL"]')).toBe('-1');
      expect(tabindex('[data-signo="ENTRA"]')).toBe('0');
      expect(tabindex('[data-signo="SALE"]')).toBe('-1');
    });

    it('← y → cambian el modo y llevan el foco a la opción elegida', async () => {
      await abrir(tornillo(10));

      tecla('[data-modo="STOCK_REAL"]', 'ArrowRight');
      expect(marcado('[data-modo="CANTIDAD"]')).toBe(true);
      expect(document.activeElement).toBe(el().querySelector('[data-modo="CANTIDAD"]'));

      tecla('[data-modo="CANTIDAD"]', 'ArrowLeft');
      expect(marcado('[data-modo="STOCK_REAL"]')).toBe(true);
      expect(document.activeElement).toBe(el().querySelector('[data-modo="STOCK_REAL"]'));

      tecla('[data-modo="STOCK_REAL"]', 'ArrowLeft');
      expect(marcado('[data-modo="CANTIDAD"]')).toBe(true);
    });

    it('← y → cambian entre Entra y Sale', async () => {
      await abrir(tornillo(10));
      await elegirModo('CANTIDAD');

      tecla('[data-signo="ENTRA"]', 'ArrowRight');
      expect(marcado('[data-signo="SALE"]')).toBe(true);
      expect(document.activeElement).toBe(el().querySelector('[data-signo="SALE"]'));

      tecla('[data-signo="SALE"]', 'ArrowLeft');
      expect(marcado('[data-signo="ENTRA"]')).toBe(true);
    });

    it('las flechas en los campos de texto no cambian el modo', async () => {
      await abrir(tornillo(10));

      tecla('#stock-real', 'ArrowRight');

      expect(marcado('[data-modo="STOCK_REAL"]')).toBe(true);
    });
  });

  it('calcula la diferencia en vivo mientras se escribe el stock real', async () => {
    await abrir(tornillo(10));
    expect(texto('.diferencia')).toBe('—');

    await escribir('#stock-real', '12');
    expect(texto('.diferencia')).toBe('+2 unidades');
    expect(el().querySelector('.diferencia')!.classList).toContain('sube');

    await escribir('#stock-real', '9');
    expect(texto('.diferencia')).toBe('-1 unidad');
    expect(el().querySelector('.diferencia')!.classList).toContain('baja');

    await escribir('#stock-real', '10');
    expect(texto('.diferencia')).toBe('Sin diferencia');
  });

  it('acepta coma decimal en los artículos por metro', async () => {
    await abrir(tornillo(150, 'METRO'));

    await escribir('#stock-real', '145,5');

    expect(texto('.diferencia')).toBe('-4,5 metros');
  });

  describe('«1.500» sin coma puede ser 1500 o 1,5: lo pregunta en vez de adivinar', () => {
    it('stock real: «—» en vivo y la pregunta al confirmar, sin mandar nada', async () => {
      await abrir(tornillo(10, 'KILO'));
      await escribir('#motivo', 'CONTEO');

      await escribir('#stock-real', '1.500');
      expect(texto('.diferencia')).toBe('—');

      await confirmar();
      expect(texto('#stock-real-error')).toBe('¿Quisiste decir 1500 o 1,5? Usá la coma para los decimales.');

      await escribir('#stock-real', '12.345');
      await confirmar();
      expect(texto('#stock-real-error')).toBe('¿Quisiste decir 12345 o 12,345? Usá la coma para los decimales.');

      await escribir('#stock-real', '2.000');
      await confirmar();
      expect(texto('#stock-real-error')).toBe('¿Quisiste decir 2000 o 2? Usá la coma para los decimales.');
      expect(servicio.ajustarStock).not.toHaveBeenCalled();
    });

    it('cantidad: «—» en vivo y la pregunta al confirmar, también en un artículo por unidad', async () => {
      await abrir(tornillo(3));
      await escribir('#motivo', 'INGRESO_MERCADERIA');

      await escribir('#cantidad', '1.500');
      expect(texto('.vista-previa .quedara')).toBe('—');

      await confirmar();
      expect(texto('#cantidad-error')).toBe('¿Quisiste decir 1500 o 1,5? Usá la coma para los decimales.');
      expect(servicio.ajustarStock).not.toHaveBeenCalled();
    });

    it('«1.000» en un artículo por unidad pregunta (antes mandaba 1 sin avisar)', async () => {
      await abrir(tornillo(1000));
      await escribir('#motivo', 'CONTEO');

      await escribir('#stock-real', '1.000');
      expect(texto('.diferencia')).toBe('—');

      await confirmar();
      expect(texto('#stock-real-error')).toBe('¿Quisiste decir 1000 o 1? Usá la coma para los decimales.');
      expect(servicio.ajustarStock).not.toHaveBeenCalled();
    });

    it('con espacios alrededor también pregunta', async () => {
      await abrir(tornillo(10, 'METRO'));
      await escribir('#motivo', 'CONTEO');

      await escribir('#stock-real', '  1.500  ');
      await confirmar();

      expect(texto('#stock-real-error')).toBe('¿Quisiste decir 1500 o 1,5? Usá la coma para los decimales.');
    });

    it('«-1.500» no pregunta: avisa que no puede ser negativo, lo haya querido como 1500 o como 1,5', async () => {
      await abrir(tornillo(10, 'KILO'));
      await escribir('#motivo', 'CONTEO');

      await escribir('#stock-real', '-1.500');
      expect(texto('.diferencia')).toBe('—');
      await confirmar();
      expect(texto('#stock-real-error')).toBe('El stock real no puede ser negativo.');

      await escribir('#stock-real', '  -2.000  ');
      await confirmar();
      expect(texto('#stock-real-error')).toBe('El stock real no puede ser negativo.');

      await escribir('#motivo', 'INGRESO_MERCADERIA');
      await elegirModo('CANTIDAD');
      await escribir('#cantidad', '-1.500');
      await confirmar();
      expect(texto('#cantidad-error')).toBe('Escribí la cantidad sin signo y elegí si entra o sale.');
      expect(servicio.ajustarStock).not.toHaveBeenCalled();
    });

    it('lo demás se lee como antes: «145.5», «1.500,25» y «0.250» (un 0 delante no es de miles)', async () => {
      await abrir(tornillo(1000, 'METRO'));

      await escribir('#stock-real', '145.5');
      expect(texto('.diferencia')).toBe('-854,5 metros');

      await escribir('#stock-real', '1.500,25');
      expect(texto('.diferencia')).toBe('+500,25 metros');

      await escribir('#stock-real', '1000.250');
      expect(texto('.diferencia')).toBe('+0,25 metros');

      await escribir('#stock-real', '0.250');
      expect(texto('.diferencia')).toBe('-999,75 metros');
    });
  });

  it('desde un stock negativo, contar 0 suma lo que faltaba', async () => {
    await abrir(tornillo(-3));

    await escribir('#stock-real', '0');

    expect(texto('.diferencia')).toBe('+3 unidades');
  });

  it('con datos válidos manda el stock real, el motivo y el detalle, y avisa el resultado', async () => {
    await abrir(tornillo(10));
    await escribir('#stock-real', '12');
    await escribir('#motivo', 'CONTEO');
    await escribir('#detalle', '  remito 123 ');

    await confirmar();

    expect(servicio.ajustarStock).toHaveBeenCalledWith(7, {
      modo: 'STOCK_REAL',
      stockReal: 12,
      motivo: 'CONTEO',
      detalle: 'remito 123',
    });
    expect(ajustados).toEqual([{ articuloId: 7, stockAnterior: 10, diferencia: 2, stockActual: 12 }]);
  });

  it('sin detalle manda null', async () => {
    await abrir(tornillo(10));
    await escribir('#stock-real', '8');
    await escribir('#motivo', 'CONTEO');

    await confirmar();

    expect(servicio.ajustarStock).toHaveBeenCalledWith(7, {
      modo: 'STOCK_REAL',
      stockReal: 8,
      motivo: 'CONTEO',
      detalle: null,
    });
  });

  it('muestra todos los errores juntos, cada uno junto a su campo, y no manda nada', async () => {
    await abrir(tornillo(10));
    await escribir('#motivo', 'OTRO');
    await elegirModo('STOCK_REAL');
    await escribir('#stock-real', '2,5');

    await confirmar();

    expect(texto('#stock-real-error')).toBe(
      'Este artículo se cuenta por unidad: el stock real tiene que ser un número entero.',
    );
    expect(texto('#detalle-error')).toBe('Contá el motivo del ajuste.');
    expect(el().querySelector('#stock-real')!.getAttribute('aria-invalid')).toBe('true');
    expect(servicio.ajustarStock).not.toHaveBeenCalled();
  });

  it('sin stock real ni motivo pide los dos', async () => {
    await abrir(tornillo(10));

    await confirmar();

    expect(texto('#stock-real-error')).toBe('Escribí el stock real que contaste.');
    expect(texto('#motivo-error')).toBe('Elegí el motivo del ajuste.');
    expect(servicio.ajustarStock).not.toHaveBeenCalled();
  });

  it('valida negativo, texto que no es número y más de 3 decimales', async () => {
    await abrir(tornillo(10, 'KILO'));
    await escribir('#motivo', 'CONTEO');

    await escribir('#stock-real', '-1');
    await confirmar();
    expect(texto('#stock-real-error')).toBe('El stock real no puede ser negativo.');

    await escribir('#stock-real', 'diez');
    await confirmar();
    expect(texto('#stock-real-error')).toBe('Escribí un número, por ejemplo 12 o 145,5.');

    await escribir('#stock-real', '1,2345');
    await confirmar();
    expect(texto('#stock-real-error')).toBe('El stock real admite hasta 3 decimales.');
    expect(servicio.ajustarStock).not.toHaveBeenCalled();
  });

  describe('las reglas de formato valen igual para el conteo y para sumar o restar', () => {
    const MAXIMO_TEXTO = 'el máximo es 999.999.999.999,999.';

    it('stock real: más del máximo y más de 3 decimales dan su error', async () => {
      await abrir(tornillo(10, 'METRO'));
      await escribir('#motivo', 'CONTEO');

      await escribir('#stock-real', '1000000000000');
      await confirmar();
      expect(texto('#stock-real-error')).toBe(`El stock real es demasiado grande: ${MAXIMO_TEXTO}`);

      await escribir('#stock-real', '2,1234');
      await confirmar();
      expect(texto('#stock-real-error')).toBe('El stock real admite hasta 3 decimales.');
      expect(servicio.ajustarStock).not.toHaveBeenCalled();
    });

    it('cantidad: más del máximo y más de 3 decimales dan su error', async () => {
      await abrir(tornillo(10, 'METRO'));
      await escribir('#motivo', 'INGRESO_MERCADERIA');

      await escribir('#cantidad', '1000000000000');
      await confirmar();
      expect(texto('#cantidad-error')).toBe(`La cantidad es demasiado grande: ${MAXIMO_TEXTO}`);

      await escribir('#cantidad', '2,1234');
      await confirmar();
      expect(texto('#cantidad-error')).toBe('La cantidad admite hasta 3 decimales.');
      expect(servicio.ajustarStock).not.toHaveBeenCalled();
    });

    it('«-0» es un número con signo: no es válido ni en la vista previa ni al confirmar (stock real)', async () => {
      await abrir(tornillo(10));
      await escribir('#motivo', 'CONTEO');

      await escribir('#stock-real', '-0');
      expect(texto('.vista-previa .quedara')).toBe('—');
      expect(texto('.vista-previa .diferencia')).toBe('—');
      await confirmar();

      expect(texto('#stock-real-error')).toBe('El stock real no puede ser negativo.');
      expect(servicio.ajustarStock).not.toHaveBeenCalled();
    });

    it('«-0» es un número con signo: no es válido ni en la vista previa ni al confirmar (cantidad)', async () => {
      await abrir(tornillo(10));
      await escribir('#motivo', 'INGRESO_MERCADERIA');

      await escribir('#cantidad', '-0');
      expect(texto('.vista-previa .quedara')).toBe('—');
      await confirmar();

      expect(texto('#cantidad-error')).toBe('Escribí la cantidad sin signo y elegí si entra o sale.');
      expect(servicio.ajustarStock).not.toHaveBeenCalled();
    });
  });

  describe('tope del stock resultante al sumar (como el backend)', () => {
    it('no deja mandar un ingreso que lo pasa del máximo y lo explica junto al campo', async () => {
      await abrir(tornillo(999_999_999_999, 'METRO'));
      await escribir('#motivo', 'INGRESO_MERCADERIA');
      await escribir('#cantidad', '5');

      await confirmar();

      expect(texto('#cantidad-error')).toBe(
        'El stock quedaría demasiado grande: el máximo es 999.999.999.999,999.',
      );
      expect(servicio.ajustarStock).not.toHaveBeenCalled();
    });

    it('un ingreso que llega justo al máximo se manda', async () => {
      await abrir(tornillo(999_999_999_999, 'METRO'));
      await escribir('#motivo', 'INGRESO_MERCADERIA');
      await escribir('#cantidad', '0,999');

      await confirmar();

      expect(el().querySelector('#cantidad-error')).toBeNull();
      expect(servicio.ajustarStock).toHaveBeenCalledWith(7, {
        modo: 'CANTIDAD',
        cantidad: 0.999,
        motivo: 'INGRESO_MERCADERIA',
        detalle: null,
      });
    });
  });

  it('si el stock real es igual al actual no hay nada que ajustar', async () => {
    await abrir(tornillo(10));
    await escribir('#stock-real', '10');
    await escribir('#motivo', 'CONTEO');

    await confirmar();

    expect(texto('#stock-real-error')).toBe('El stock real es igual al actual: no hay nada que ajustar.');
    expect(servicio.ajustarStock).not.toHaveBeenCalled();
  });

  it('al corregir un campo se borra su error', async () => {
    await abrir(tornillo(10));
    await confirmar();

    await escribir('#stock-real', '12');

    expect(el().querySelector('#stock-real-error')).toBeNull();
    expect(texto('#motivo-error')).toBe('Elegí el motivo del ajuste.');
  });

  it('muestra los errores por campo que devuelve el backend', async () => {
    await abrir(tornillo(10));
    servicio.ajustarStock.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Datos inválidos', errors: { stockReal: 'El stock real es igual al actual: no hay nada que ajustar.' } },
          }),
      ),
    );
    await escribir('#stock-real', '12');
    await escribir('#motivo', 'CONTEO');

    await confirmar();

    expect(texto('#stock-real-error')).toBe('El stock real es igual al actual: no hay nada que ajustar.');
    expect(ajustados).toEqual([]);
  });

  it('un error sin campos (404, sin conexión) se muestra arriba de los botones', async () => {
    await abrir(tornillo(10));
    servicio.ajustarStock.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 404, error: { message: 'Artículo no encontrado' } })),
    );
    await escribir('#stock-real', '12');
    await escribir('#motivo', 'CONTEO');

    await confirmar();

    expect(texto('.error[role="alert"]')).toBe('Artículo no encontrado');
  });

  describe('modo sumar o restar', () => {
    it('el motivo elige el modo por defecto y el selector lo deja cambiar', async () => {
      await abrir(tornillo(10));
      expect(marcado('[data-modo="STOCK_REAL"]')).toBe(true);

      await escribir('#motivo', 'INGRESO_MERCADERIA');
      expect(marcado('[data-modo="CANTIDAD"]')).toBe(true);
      expect(marcado('[data-signo="ENTRA"]')).toBe(true);
      expect(el().querySelector('#cantidad')).not.toBeNull();
      expect(el().querySelector('#stock-real')).toBeNull();

      await escribir('#motivo', 'ROTURA');
      expect(marcado('[data-modo="CANTIDAD"]')).toBe(true);
      expect(marcado('[data-signo="SALE"]')).toBe(true);

      await escribir('#motivo', 'CONTEO');
      expect(marcado('[data-modo="STOCK_REAL"]')).toBe(true);

      await escribir('#motivo', 'OTRO');
      expect(marcado('[data-modo="CANTIDAD"]')).toBe(true);
      expect(marcado('[data-signo="ENTRA"]')).toBe(true);

      await elegirModo('STOCK_REAL');
      expect(el().querySelector('#stock-real')).not.toBeNull();
    });

    it('muestra en vivo cuánto quedará: «Stock actual 10 → quedará 35»', async () => {
      await abrir(tornillo(10));
      await elegirModo('CANTIDAD');

      await escribir('#cantidad', '25');
      expect(texto('.quedara')).toBe('35 unidades');
      expect(texto('.stock-actual')).toBe('10 unidades');

      await elegirSigno('SALE');
      expect(texto('.quedara')).toBe('-15 unidades');
      expect(el().querySelector('.quedara')!.classList).toContain('baja');
    });

    it('manda la cantidad con signo según entre o salga', async () => {
      await abrir(tornillo(10));
      await escribir('#motivo', 'ROTURA');
      await escribir('#cantidad', '3');

      await confirmar();

      expect(servicio.ajustarStock).toHaveBeenCalledWith(7, {
        modo: 'CANTIDAD',
        cantidad: -3,
        motivo: 'ROTURA',
        detalle: null,
      });

      await elegirSigno('ENTRA');
      await confirmar();
      expect(servicio.ajustarStock).toHaveBeenLastCalledWith(7, {
        modo: 'CANTIDAD',
        cantidad: 3,
        motivo: 'ROTURA',
        detalle: null,
      });
    });

    it('con stock negativo por ventas, un ingreso parcial se manda aunque no llegue a 0', async () => {
      await abrir(tornillo(-4));
      await escribir('#motivo', 'INGRESO_MERCADERIA');
      await escribir('#cantidad', '2');

      expect(texto('.quedara')).toBe('-2 unidades');
      await confirmar();

      expect(texto('#cantidad-error')).toBe('');
      expect(servicio.ajustarStock).toHaveBeenCalledWith(7, {
        modo: 'CANTIDAD',
        cantidad: 2,
        motivo: 'INGRESO_MERCADERIA',
        detalle: null,
      });
    });

    it('acepta coma decimal en metros', async () => {
      await abrir(tornillo(150, 'METRO'));
      await escribir('#motivo', 'INGRESO_MERCADERIA');

      await escribir('#cantidad', '2,5');

      expect(texto('.quedara')).toBe('152,5 metros');
    });

    it('valida cantidad vacía, cero, con signo, decimales en UNIDAD y que no quede negativo', async () => {
      await abrir(tornillo(3));
      await escribir('#motivo', 'ROTURA');

      await confirmar();
      expect(texto('#cantidad-error')).toBe('Escribí la cantidad que entra o sale.');

      await escribir('#cantidad', '0');
      await confirmar();
      expect(texto('#cantidad-error')).toBe('La cantidad no puede ser cero.');

      await escribir('#cantidad', '-2');
      await confirmar();
      expect(texto('#cantidad-error')).toBe('Escribí la cantidad sin signo y elegí si entra o sale.');

      await escribir('#cantidad', '1,5');
      await confirmar();
      expect(texto('#cantidad-error')).toBe(
        'Este artículo se cuenta por unidad: la cantidad tiene que ser un número entero.',
      );

      await escribir('#cantidad', '5');
      await confirmar();
      expect(texto('#cantidad-error')).toBe('No podés sacar 5: hay 3 unidades.');
      expect(servicio.ajustarStock).not.toHaveBeenCalled();
    });

    it('muestra el error por campo cantidad que devuelve el backend', async () => {
      await abrir(tornillo(10));
      servicio.ajustarStock.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: { message: 'Datos inválidos', errors: { cantidad: 'No podés sacar 5: hay 3 unidades.' } },
            }),
        ),
      );
      await escribir('#motivo', 'ROTURA');
      await escribir('#cantidad', '5');

      await confirmar();

      expect(texto('#cantidad-error')).toBe('No podés sacar 5: hay 3 unidades.');
    });

    it('el error de un campo que no está a la vista se muestra arriba de los botones', async () => {
      await abrir(tornillo(10));
      servicio.ajustarStock.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: { message: 'Datos inválidos', errors: { stockReal: 'Indicá el stock real.' } },
            }),
        ),
      );
      await escribir('#motivo', 'INGRESO_MERCADERIA');
      await escribir('#cantidad', '5');

      await confirmar();

      expect(texto('.error[role="alert"]')).toBe('Indicá el stock real.');
    });

    it('un error por campo sin texto no se traga la respuesta: se muestra el mensaje general', async () => {
      await abrir(tornillo(10));
      servicio.ajustarStock.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: { message: 'Datos inválidos', errors: { cantidad: '' } },
            }),
        ),
      );
      await escribir('#motivo', 'INGRESO_MERCADERIA');
      await escribir('#cantidad', '5');

      await confirmar();

      expect(texto('.error[role="alert"]')).toBe('Datos inválidos');
      expect(el().querySelector('#cantidad-error')).toBeNull();
    });

    it('el 422 de un artículo dado de baja se muestra arriba de los botones', async () => {
      await abrir(tornillo(10));
      servicio.ajustarStock.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 422,
              error: { message: '"Tornillo" está dado de baja. Reactivalo para ajustar el stock.' },
            }),
        ),
      );
      await escribir('#motivo', 'INGRESO_MERCADERIA');
      await escribir('#cantidad', '5');

      await confirmar();

      expect(texto('.error[role="alert"]')).toBe('"Tornillo" está dado de baja. Reactivalo para ajustar el stock.');
    });
  });

  it('Esc y Cancelar cancelan sin ajustar', async () => {
    await abrir(tornillo(10));

    el().querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }));
    el().querySelector<HTMLButtonElement>('button.cancelar')!.click();

    expect(canceladas).toBe(2);
    expect(servicio.ajustarStock).not.toHaveBeenCalled();
  });
  /*
   * movil-sectores T5 (5e; S1, S5, S7): en el celular el ajuste es una pantalla completa (artboard 07 de movil-articulos):
   * barra «Volver», control segmentado para «Cómo cargarlo» y «Entra o sale», − y + junto a la cantidad y el pie fijo con
   * Cancelar y Guardar. Es el mismo formulario con el mismo estado y los mismos manejadores; el escritorio no cambia.
   * Vitest no lee styles.css: las medidas se verifican en el navegador a 390 y 360 px.
   */
  describe('pantalla completa en el celular (movil-sectores T5, 5e)', () => {
    const enElCelular = () =>
      vi.stubGlobal('matchMedia', (consulta: string) => ({ matches: consulta.includes('639.98px'), media: consulta }));
    afterEach(() => vi.unstubAllGlobals());

    const campo = () => el().querySelector<HTMLInputElement>('#cantidad, #stock-real')!;
    /** Las partes de la vista previa, separadas por «|» (entre ellas hay espacio de CSS, no de texto). */
    const queda = () =>
      Array.from(el().querySelectorAll('.vista-previa > .queda-rotulo, .vista-previa > .desde, .vista-previa .quedara, .vista-previa .diferencia'))
        .map((n) => n.textContent!.replace(/\s+/g, ' ').trim())
        .join('|');
    const boton = (selector: string) => el().querySelector<HTMLButtonElement>(selector)!;
    async function tocar(selector: string) {
      boton(selector).click();
      fixture.detectChanges();
      await fixture.whenStable();
    }

    describe('en el escritorio todo queda como estaba', () => {
      it.each([
        ['sin matchMedia', () => undefined],
        ['con una pantalla ancha', () => vi.stubGlobal('matchMedia', (c: string) => ({ matches: false, media: c }))],
      ])('%s: sigue el panel lateral, sin barra, sin − ni + y sin pie fijo', async (_caso, preparar) => {
        preparar();
        await abrir(tornillo(10));
        await elegirModo('CANTIDAD');

        const dialogo = el().querySelector('dialog')!;
        expect(dialogo.className).toBe('panel-lateral');
        expect(el().querySelector('header h2.sobretitulo')!.textContent!.trim()).toBe('Ajustar stock');
        expect(
          el().querySelector('.ficha-barra, .ficha-volver, .paso-boton, .con-paso, .ficha-pie, .queda-rotulo, .etq'),
        ).toBeNull();
        expect(el().querySelectorAll('.modo-detalle')).toHaveLength(2);
        expect(texto('.modos')).toContain('Entra o sale mercadería');
        expect(texto('.vista-previa').replace(/\s+/g, ' ')).toBe('10 → —');
        expect(texto('.signo.entra')).toBe('+ Entra');
        expect(el().querySelector('.acciones-panel')!.className).toBe('acciones-panel');
        expect(boton('.confirmar').textContent).toBe('Guardar ajuste');
      });

      it('mientras guarda, «Guardar ajuste» queda apagado de verdad (disabled) y no explica nada', async () => {
        await abrir(tornillo(10));
        const pendiente = new Subject<AjusteStockResponse>();
        servicio.ajustarStock.mockReturnValue(pendiente);
        await escribir('#stock-real', '12');
        await escribir('#motivo', 'CONTEO');

        await confirmar();

        expect(boton('.confirmar').disabled).toBe(true);
        expect(boton('.confirmar').hasAttribute('aria-disabled')).toBe(false);
        expect(el().querySelector('.motivo-accion')).toBeNull();
      });
    });

    it('al girar el teléfono con el ajuste abierto cambia de pantalla completa a panel lateral y al revés', async () => {
      let alCambiar: ((e: MediaQueryListEvent) => void) | undefined;
      const quitar = vi.fn();
      vi.stubGlobal('matchMedia', (consulta: string) => ({
        matches: true,
        media: consulta,
        addEventListener: (_tipo: string, f: (e: MediaQueryListEvent) => void) => (alCambiar = f),
        removeEventListener: quitar,
      }));
      await abrir(tornillo(10));
      expect(el().querySelector('.ficha-barra')).not.toBeNull();

      alCambiar!({ matches: false } as MediaQueryListEvent);
      fixture.detectChanges();
      expect(el().querySelector('.ficha-barra, .paso-boton')).toBeNull();
      expect(el().querySelector('dialog')!.className).toBe('panel-lateral');
      expect(el().querySelector('header h2.sobretitulo')).not.toBeNull();

      alCambiar!({ matches: true } as MediaQueryListEvent);
      fixture.detectChanges();
      expect(el().querySelector('.ficha-barra')).not.toBeNull();

      fixture.destroy();
      expect(quitar).toHaveBeenCalledWith('change', alCambiar);
    });

    describe('barra «Volver» y título', () => {
      it('es el mismo <dialog> modal, ahora a pantalla completa, con la barra arriba y un solo título', async () => {
        enElCelular();
        await abrir({ ...tornillo(12), codigoInterno: 'BUL-002' });

        const dialogo = el().querySelector<HTMLDialogElement>('dialog')!;
        expect(dialogo.classList).toContain('panel-lateral');
        expect(dialogo.classList).toContain('ajuste-celular');
        expect(showModal).toHaveBeenCalledTimes(1);
        expect(dialogo.open).toBe(true);
        expect(el().querySelectorAll('h2')).toHaveLength(1);
        const barra = el().querySelector('.ficha-barra')!;
        expect(barra.querySelector('h2')!.textContent!.trim()).toBe('Ajustar stock');
        expect(barra.querySelector('.ficha-volver')!.getAttribute('aria-label')).toBe('Volver');
        const nombre = (dialogo.getAttribute('aria-labelledby') ?? '')
          .split(' ')
          .map((id) => el().querySelector(`#${id}`)?.textContent?.trim())
          .join(' ');
        expect(nombre).toBe('Ajustar stock Tornillo hexagonal 1/4');
        expect(texto('.referencia').replace(/\s+/g, ' ')).toBe('BUL-002 · hoy 12 unidades');
      });

      it('«Volver» cancela sin ajustar, como Cancelar y Esc', async () => {
        enElCelular();
        await abrir(tornillo(10));

        await tocar('.ficha-volver');
        expect(canceladas).toBe(1);
        expect(boton('.ficha-volver').type).toBe('button');

        el().querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }));
        expect(canceladas).toBe(2);
        expect(servicio.ajustarStock).not.toHaveBeenCalled();
      });

      it('al abrir el foco va al campo numérico y al cerrar vuelve a quien abrió', async () => {
        enElCelular();
        const abridor = document.createElement('button');
        document.body.appendChild(abridor);
        abridor.focus();

        await abrir(tornillo(10));
        expect(document.activeElement).toBe(el().querySelector('#stock-real'));
        fixture.destroy();

        expect(cerrar).toHaveBeenCalled();
        expect(document.activeElement).toBe(abridor);
        abridor.remove();
      });
    });

    describe('«Cómo cargarlo» y «Entra o sale» como control segmentado', () => {
      it('rotula cada grupo y son los mismos botones de siempre, en el orden de tabulación del artboard', async () => {
        enElCelular();
        await abrir(tornillo(10));
        await escribir('#motivo', 'INGRESO_MERCADERIA');

        expect(texto('.modos .etq')).toBe('Cómo cargarlo');
        expect(texto('.signos .etq')).toBe('Entra o sale');
        expect(el().querySelector('.modos')!.getAttribute('aria-label')).toBe('Cómo cargar el ajuste');
        expect(el().querySelector('.signos')!.getAttribute('aria-label')).toBe('Entra o sale');
        const botones = Array.from(el().querySelectorAll('.modo, .signo')).map((b) => b.getAttribute('role'));
        expect(botones).toEqual(['radio', 'radio', 'radio', 'radio']);
        expect(texto('[data-modo="CANTIDAD"] span')).toBe('Sumar o restar');
        expect(texto('[data-modo="STOCK_REAL"] span')).toBe('Conteo');
        expect(texto('[data-signo="ENTRA"]')).toBe('+ Entra');
        expect(texto('[data-signo="SALE"]')).toBe('− Sale');
      });

      it('«Cómo cargarlo» cambia el mismo estado: modo, campo numérico vacío y errores del campo borrados', async () => {
        enElCelular();
        await abrir(tornillo(10));
        await escribir('#stock-real', '10');
        await confirmar();
        expect(texto('#stock-real-error')).toBe('El stock real es igual al actual: no hay nada que ajustar.');

        await tocar('[data-modo="CANTIDAD"]');

        expect(marcado('[data-modo="CANTIDAD"]')).toBe(true);
        expect(marcado('[data-modo="STOCK_REAL"]')).toBe(false);
        expect(el().querySelector('#stock-real')).toBeNull();
        expect(el().querySelector<HTMLInputElement>('#cantidad')!.value).toBe('');
        expect(document.activeElement).toBe(el().querySelector('#cantidad'));
        expect(texto('.modos .etq')).toBe('Cómo cargarlo');

        await tocar('[data-modo="STOCK_REAL"]');
        expect(el().querySelector('#cantidad')).toBeNull();
        expect(marcado('[data-modo="STOCK_REAL"]')).toBe(true);
      });

      it('«Entra o sale» cambia el signo: la cantidad, el «Queda» y lo que se manda siguen la elección', async () => {
        enElCelular();
        await abrir(tornillo(12));
        await escribir('#motivo', 'INGRESO_MERCADERIA');
        await escribir('#cantidad', '5');
        expect(texto('.quedara')).toBe('17 unidades');
        expect(texto('label[for="cantidad"]')).toBe('Cantidad que entra');

        await tocar('[data-signo="SALE"]');

        expect(marcado('[data-signo="SALE"]')).toBe(true);
        expect(texto('label[for="cantidad"]')).toBe('Cantidad que sale');
        expect(texto('.quedara')).toBe('7 unidades');
        await confirmar();
        expect(servicio.ajustarStock).toHaveBeenCalledWith(7, {
          modo: 'CANTIDAD',
          cantidad: -5,
          motivo: 'INGRESO_MERCADERIA',
          detalle: null,
        });
      });

      it('las flechas siguen moviendo la elección y el foco dentro de cada grupo', async () => {
        enElCelular();
        await abrir(tornillo(10));
        await escribir('#motivo', 'INGRESO_MERCADERIA');

        boton('[data-modo="CANTIDAD"]').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
        fixture.detectChanges();
        expect(marcado('[data-modo="STOCK_REAL"]')).toBe(true);
        expect(document.activeElement).toBe(boton('[data-modo="STOCK_REAL"]'));

        await tocar('[data-modo="CANTIDAD"]');
        boton('[data-signo="ENTRA"]').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
        fixture.detectChanges();
        expect(marcado('[data-signo="SALE"]')).toBe(true);
        expect(document.activeElement).toBe(boton('[data-signo="SALE"]'));
      });
    });

    describe('− y + junto a la cantidad', () => {
      const menos = '.paso-menos';
      const mas = '.paso-mas';

      it('rodean el mismo campo (el de antes), en ese orden, y no mandan el formulario', async () => {
        enElCelular();
        await abrir(tornillo(10));
        await escribir('#motivo', 'INGRESO_MERCADERIA');

        const hijos = Array.from(el().querySelector('.con-paso')!.children).map((n) => n.className.split(' ')[0] || n.tagName);
        expect(hijos).toEqual(['LABEL', 'paso-boton', 'numero', 'paso-boton']);
        expect(boton(menos).getAttribute('aria-label')).toBe('Restar uno');
        expect(boton(mas).getAttribute('aria-label')).toBe('Sumar uno');
        expect(boton(menos).type).toBe('button');
        expect(boton(mas).type).toBe('button');
        expect(campo().id).toBe('cantidad');

        await tocar(mas);
        expect(servicio.ajustarStock).not.toHaveBeenCalled();
      });

      it('+ suma uno y − resta uno sobre lo escrito, y el «Queda» y lo que se manda lo ven', async () => {
        enElCelular();
        await abrir(tornillo(12));
        await escribir('#motivo', 'INGRESO_MERCADERIA');

        await tocar(mas);
        expect(campo().value).toBe('1');
        for (let i = 0; i < 4; i++) await tocar(mas);
        expect(campo().value).toBe('5');
        expect(queda()).toBe('Queda|12 →|17 unidades');
        await tocar(menos);
        expect(campo().value).toBe('4');
        await tocar(mas);

        await confirmar();
        expect(servicio.ajustarStock).toHaveBeenCalledWith(7, {
          modo: 'CANTIDAD',
          cantidad: 5,
          motivo: 'INGRESO_MERCADERIA',
          detalle: null,
        });
        expect(ajustados).toHaveLength(1);
      });

      it('en el conteo cambian el stock real, no la cantidad', async () => {
        enElCelular();
        await abrir(tornillo(12));

        await tocar(mas);
        await tocar(mas);
        await tocar(mas);

        expect(campo().id).toBe('stock-real');
        expect(campo().value).toBe('3');
        expect(texto('.vista-previa .quedara')).toBe('3 unidades');
        expect(texto('.vista-previa .diferencia')).toBe('-9 unidades');
      });

      it('parten de lo que se escribió, también con coma decimal en los artículos por metro', async () => {
        enElCelular();
        await abrir(tornillo(150, 'METRO'));
        await escribir('#motivo', 'INGRESO_MERCADERIA');
        await escribir('#cantidad', '2,5');

        await tocar(mas);
        expect(campo().value).toBe('3,5');
        await tocar(menos);
        await tocar(menos);
        expect(campo().value).toBe('1,5');
        expect(texto('.quedara')).toBe('151,5 metros');
      });

      it('en un artículo por unidad un decimal escrito pasa al entero siguiente o anterior', async () => {
        enElCelular();
        await abrir(tornillo(10));
        await escribir('#motivo', 'INGRESO_MERCADERIA');
        await escribir('#cantidad', '1,5');

        await tocar(mas);
        expect(campo().value).toBe('2');

        await escribir('#cantidad', '1,5');
        await tocar(menos);
        expect(campo().value).toBe('1');
      });

      it('no bajan de cero: el − se apaga y explica por qué, con el texto de siempre', async () => {
        enElCelular();
        await abrir(tornillo(10));
        await escribir('#motivo', 'INGRESO_MERCADERIA');
        expect(boton(menos).getAttribute('aria-disabled')).toBe('true');
        expect(boton(mas).hasAttribute('aria-disabled')).toBe(false);

        await tocar(menos);

        expect(campo().value).toBe('');
        expect(texto('.motivo-accion')).toBe('Escribí la cantidad sin signo y elegí si entra o sale.');
        expect(el().querySelector('.motivo-accion')!.getAttribute('role')).toBe('status');

        await tocar(mas);
        expect(campo().value).toBe('1');
        expect(boton(menos).hasAttribute('aria-disabled')).toBe(false);
        expect(el().querySelector('.motivo-accion')).toBeNull();

        await tocar(menos);
        await tocar(menos);
        expect(campo().value).toBe('0');
        expect(boton(menos).getAttribute('aria-disabled')).toBe('true');
      });

      it('en el conteo el − explica con el texto del stock real', async () => {
        enElCelular();
        await abrir(tornillo(10));

        await tocar(menos);

        expect(campo().value).toBe('');
        expect(texto('.motivo-accion')).toBe('El stock real no puede ser negativo.');
      });

      it('no pasan del máximo del backend: el + se apaga y explica por qué', async () => {
        enElCelular();
        await abrir(tornillo(10, 'METRO'));
        await escribir('#motivo', 'INGRESO_MERCADERIA');
        await escribir('#cantidad', '999999999999,5');
        expect(boton(mas).getAttribute('aria-disabled')).toBe('true');

        await tocar(mas);

        expect(campo().value).toBe('999999999999,5');
        expect(texto('.motivo-accion')).toBe(
          'La cantidad es demasiado grande: el máximo es 999.999.999.999,999.',
        );

        await tocar(menos);
        expect(campo().value).toBe('999999999998,5');
        expect(boton(mas).hasAttribute('aria-disabled')).toBe(false);
      });

      it('al tocarlos se borra el error del campo, como al escribir, y siguen sin mandar nada', async () => {
        enElCelular();
        await abrir(tornillo(10));
        await escribir('#motivo', 'INGRESO_MERCADERIA');
        await confirmar();
        expect(texto('#cantidad-error')).toBe('Escribí la cantidad que entra o sale.');

        await tocar(mas);

        expect(el().querySelector('#cantidad-error')).toBeNull();
        expect(servicio.ajustarStock).not.toHaveBeenCalled();
      });
    });

    describe('«Queda» en vivo', () => {
      it('es la misma vista previa de hoy con el rótulo «Queda»: «Queda 12 → 17 unidades»', async () => {
        enElCelular();
        await abrir(tornillo(12));
        await escribir('#motivo', 'INGRESO_MERCADERIA');
        await escribir('#cantidad', '5');

        expect(queda()).toBe('Queda|12 →|17 unidades');
        expect(el().querySelector('.quedara')!.classList).toContain('sube');
      });

      it('en el conteo muestra también la diferencia, y con algo inválido sigue el «—» de siempre', async () => {
        enElCelular();
        await abrir(tornillo(12));
        await escribir('#stock-real', '9');
        expect(queda()).toBe('Queda|12 →|9 unidades|-3 unidades');
        expect(el().querySelector('.quedara')!.classList).toContain('baja');

        await escribir('#stock-real', '9,5');
        expect(texto('.vista-previa .quedara')).toBe('—');
      });
    });

    describe('pie fijo con Cancelar y Guardar ajuste', () => {
      it('Cancelar a la izquierda y Guardar ajuste a la derecha, los mismos botones de hoy', async () => {
        enElCelular();
        await abrir(tornillo(10));

        const pie = el().querySelector('.acciones-panel')!;
        expect(pie.classList).toContain('ficha-pie');
        const botones = Array.from(pie.querySelectorAll('button'));
        expect(botones.map((b) => b.textContent!.trim())).toEqual(['Cancelar', 'Guardar ajuste']);
        expect(botones.map((b) => b.type)).toEqual(['button', 'submit']);

        botones[0].click();
        expect(canceladas).toBe(1);
        expect(servicio.ajustarStock).not.toHaveBeenCalled();
      });

      it('Guardar ajuste valida como hoy (todos los errores juntos) y con datos válidos emite el mismo resultado', async () => {
        enElCelular();
        await abrir(tornillo(10));
        await confirmar();
        expect(texto('#stock-real-error')).toBe('Escribí el stock real que contaste.');
        expect(texto('#motivo-error')).toBe('Elegí el motivo del ajuste.');
        expect(servicio.ajustarStock).not.toHaveBeenCalled();

        await tocar('.paso-mas');
        await escribir('#stock-real', '12');
        await escribir('#motivo', 'CONTEO');
        await escribir('#detalle', '  remito 123 ');
        await confirmar();

        expect(servicio.ajustarStock).toHaveBeenCalledWith(7, {
          modo: 'STOCK_REAL',
          stockReal: 12,
          motivo: 'CONTEO',
          detalle: 'remito 123',
        });
        expect(ajustados).toEqual([{ articuloId: 7, stockAnterior: 10, diferencia: 2, stockActual: 12 }]);
      });

      it('mientras guarda queda apagado (aria-disabled), un segundo toque no repite el pedido y explica por qué', async () => {
        enElCelular();
        await abrir(tornillo(10));
        const pendiente = new Subject<AjusteStockResponse>();
        servicio.ajustarStock.mockReturnValue(pendiente);
        await escribir('#stock-real', '12');
        await escribir('#motivo', 'CONTEO');
        expect(boton('.confirmar').hasAttribute('aria-disabled')).toBe(false);

        await confirmar();
        expect(boton('.confirmar').getAttribute('aria-disabled')).toBe('true');
        expect(el().querySelector('.motivo-accion')).toBeNull();

        await confirmar();
        expect(servicio.ajustarStock).toHaveBeenCalledTimes(1);
        expect(texto('.motivo-accion')).toBe('Guardando el ajuste: esperá un momento.');

        pendiente.next({ articuloId: 7, stockAnterior: 10, diferencia: 2, stockActual: 12 });
        fixture.detectChanges();
        expect(boton('.confirmar').hasAttribute('aria-disabled')).toBe(false);
        expect(el().querySelector('.motivo-accion')).toBeNull();
      });

      it('el error general del backend se sigue viendo arriba del pie', async () => {
        enElCelular();
        await abrir(tornillo(10));
        servicio.ajustarStock.mockReturnValue(
          throwError(() => new HttpErrorResponse({ status: 404, error: { message: 'Artículo no encontrado' } })),
        );
        await escribir('#stock-real', '12');
        await escribir('#motivo', 'CONTEO');

        await confirmar();

        expect(texto('.error[role="alert"]')).toBe('Artículo no encontrado');
        expect(el().querySelector('.error[role="alert"]')!.compareDocumentPosition(el().querySelector('.acciones-panel')!)).toBe(
          Node.DOCUMENT_POSITION_FOLLOWING,
        );
      });
    });
  });
});
