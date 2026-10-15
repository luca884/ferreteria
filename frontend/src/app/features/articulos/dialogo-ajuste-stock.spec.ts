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

  async function confirmar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('muestra el artículo y su stock actual con la unidad, y arranca en el stock real', async () => {
    await abrir(tornillo(497));

    expect(el().querySelector('[role="dialog"]')!.getAttribute('aria-modal')).toBe('true');
    expect(texto('h2')).toBe('Ajustar stock');
    expect(el().textContent).toContain('Tornillo hexagonal 1/4');
    expect(texto('.stock-actual dd')).toBe('497 unidades');
    expect(document.activeElement).toBe(el().querySelector('#stock-real'));
  });

  it('calcula la diferencia en vivo mientras se escribe el stock real', async () => {
    await abrir(tornillo(10));
    expect(texto('.diferencia dd')).toBe('—');

    await escribir('#stock-real', '12');
    expect(texto('.diferencia dd')).toBe('+2 unidades');
    expect(el().querySelector('.diferencia dd')!.classList).toContain('sube');

    await escribir('#stock-real', '9');
    expect(texto('.diferencia dd')).toBe('-1 unidad');
    expect(el().querySelector('.diferencia dd')!.classList).toContain('baja');

    await escribir('#stock-real', '10');
    expect(texto('.diferencia dd')).toBe('Sin diferencia');
  });

  it('acepta coma decimal en los artículos por metro', async () => {
    await abrir(tornillo(150, 'METRO'));

    await escribir('#stock-real', '145,5');

    expect(texto('.diferencia dd')).toBe('-4,5 metros');
  });

  it('desde un stock negativo, contar 0 suma lo que faltaba', async () => {
    await abrir(tornillo(-3));

    await escribir('#stock-real', '0');

    expect(texto('.diferencia dd')).toBe('+3 unidades');
  });

  it('con datos válidos manda el stock real, el motivo y el detalle, y avisa el resultado', async () => {
    await abrir(tornillo(10));
    await escribir('#stock-real', '12');
    await escribir('#motivo', 'INGRESO_MERCADERIA');
    await escribir('#detalle', '  remito 123 ');

    await confirmar();

    expect(servicio.ajustarStock).toHaveBeenCalledWith(7, {
      stockReal: 12,
      motivo: 'INGRESO_MERCADERIA',
      detalle: 'remito 123',
    });
    expect(ajustados).toEqual([{ articuloId: 7, stockAnterior: 10, diferencia: 2, stockActual: 12 }]);
  });

  it('sin detalle manda null', async () => {
    await abrir(tornillo(10));
    await escribir('#stock-real', '8');
    await escribir('#motivo', 'ROTURA');

    await confirmar();

    expect(servicio.ajustarStock).toHaveBeenCalledWith(7, { stockReal: 8, motivo: 'ROTURA', detalle: null });
  });

  it('muestra todos los errores juntos, cada uno junto a su campo, y no manda nada', async () => {
    await abrir(tornillo(10));
    await escribir('#stock-real', '2,5');
    await escribir('#motivo', 'OTRO');

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

  it('Esc y Cancelar cancelan sin ajustar', async () => {
    await abrir(tornillo(10));

    el().querySelector('[role="dialog"]')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    el().querySelector<HTMLButtonElement>('button.cancelar')!.click();

    expect(canceladas).toBe(2);
    expect(servicio.ajustarStock).not.toHaveBeenCalled();
  });
});
