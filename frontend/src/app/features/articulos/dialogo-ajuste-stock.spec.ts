import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
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
});
