import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { PaginaConfiguracionPrecios } from './configuracion-precios';
import { ConfiguracionPreciosService } from './configuracion-precios.service';

describe('PaginaConfiguracionPrecios', () => {
  const actual = { margenDefectoPorcentaje: 30, redondeoMultiplo: 0 };

  let fixture: ComponentFixture<PaginaConfiguracionPrecios>;
  let servicio: {
    obtener: ReturnType<typeof vi.fn>;
    articulosQueCambian: ReturnType<typeof vi.fn>;
    guardar: ReturnType<typeof vi.fn>;
  };

  const errorHttp = (status: number, error: object) => new HttpErrorResponse({ status, error });

  async function crear(dueno = true, obtener = vi.fn(() => of(actual))) {
    servicio = {
      obtener,
      articulosQueCambian: vi.fn(() => of(23)),
      guardar: vi.fn((c: typeof actual) => of({ ...c, articulosRecalculados: 5, avisos: [] })),
    };
    await TestBed.configureTestingModule({
      imports: [PaginaConfiguracionPrecios],
      providers: [
        { provide: ConfiguracionPreciosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PaginaConfiguracionPrecios);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
  const texto = (selector: string) => el().querySelector(selector)?.textContent?.trim();

  async function escribir(id: string, valor: string) {
    input(id).value = valor;
    input(id).dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function enviar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  async function clickEn(selector: string) {
    el().querySelector<HTMLButtonElement>(selector)!.click();
    await refrescar();
  }

  it('el título va en la barra de acciones de arriba', async () => {
    await crear();

    expect(texto('app-barra-acciones h1')).toBe('Margen y redondeo');
  });

  it('al Vendedor le avisa que es del Dueño y no pide nada', async () => {
    await crear(false);

    expect(texto('.estado-vacio')).toBe('Solo el Dueño puede configurar el margen y el redondeo.');
    expect(servicio.obtener).not.toHaveBeenCalled();
    expect(el().querySelector('form')).toBeNull();
  });

  it('precarga el margen por defecto y el redondeo guardados', async () => {
    await crear(true, vi.fn(() => of({ margenDefectoPorcentaje: 45.5, redondeoMultiplo: 0.5 })));

    expect(input('margen-defecto').value).toBe('45.5');
    expect(input('redondeo').value).toBe('0,50');
  });

  it('si no carga lo avisa y Reintentar vuelve a pedirla', async () => {
    const obtener = vi.fn().mockReturnValueOnce(throwError(() => errorHttp(500, {}))).mockReturnValue(of(actual));
    await crear(true, obtener);

    expect(texto('[role="alert"] span')).toBe('No se pudo cargar la configuración.');
    await clickEn('button.reintentar');

    expect(obtener).toHaveBeenCalledTimes(2);
    expect(input('margen-defecto').value).toBe('30');
  });

  it('si no cambió nada guarda sin contar ni preguntar', async () => {
    await crear();
    servicio.guardar.mockReturnValue(of({ ...actual, articulosRecalculados: 0, avisos: [] }));

    await enviar();

    expect(servicio.articulosQueCambian).not.toHaveBeenCalled();
    expect(servicio.guardar).toHaveBeenCalledWith(actual);
    expect(texto('.alerta.exito')).toBe('Se guardó la configuración.');
  });

  it('si cambia el margen cuenta los artículos con los valores nuevos y pide confirmación', async () => {
    await crear();
    await escribir('margen-defecto', '35');

    await enviar();

    expect(servicio.articulosQueCambian).toHaveBeenCalledWith({ margenDefectoPorcentaje: 35, redondeoMultiplo: 0 });
    expect(servicio.guardar).not.toHaveBeenCalled();
    expect(texto('app-confirmacion h2')).toBe('¿Cambiar el margen y el redondeo?');
    expect(texto('app-confirmacion p')).toBe('Se recalcula el precio de 23 artículos.');
  });

  it('el múltiplo del redondeo lleva la máscara de importes: 1000 se ve 1.000 y el control guarda 1000', async () => {
    await crear();
    await escribir('redondeo', '500');
    await escribir('redondeo', '5000');
    await escribir('redondeo', '1000');
    await enviar();

    expect(input('redondeo').value).toBe('1.000');
    expect(input('redondeo').type).toBe('text');
    expect(servicio.articulosQueCambian).toHaveBeenCalledWith({ margenDefectoPorcentaje: 30, redondeoMultiplo: 1000 });
  });

  it('al confirmar guarda y dice cuántos precios cambiaron', async () => {
    await crear();
    await escribir('redondeo', '0,5');
    await enviar();

    await clickEn('app-confirmacion button.confirmar');

    expect(servicio.guardar).toHaveBeenCalledWith({ margenDefectoPorcentaje: 30, redondeoMultiplo: 0.5 });
    expect(el().querySelector('app-confirmacion')).toBeNull();
    expect(texto('.alerta.exito')).toBe('Se guardó la configuración. Se recalcularon 5 precios.');
  });

  it('al cancelar no guarda', async () => {
    await crear();
    await escribir('margen-defecto', '35');
    await enviar();

    await clickEn('app-confirmacion button.cancelar');

    expect(servicio.guardar).not.toHaveBeenCalled();
    expect(el().querySelector('app-confirmacion')).toBeNull();
  });

  it('si ningún artículo cambia guarda sin preguntar', async () => {
    await crear();
    servicio.articulosQueCambian.mockReturnValue(of(0));
    servicio.guardar.mockReturnValue(of({ margenDefectoPorcentaje: 35, redondeoMultiplo: 0, articulosRecalculados: 0, avisos: [] }));
    await escribir('margen-defecto', '35');

    await enviar();

    expect(el().querySelector('app-confirmacion')).toBeNull();
    expect(servicio.guardar).toHaveBeenCalled();
    expect(texto('.alerta.exito')).toBe('Se guardó la configuración.');
  });

  it('dice 1 artículo y 1 precio en singular', async () => {
    await crear();
    servicio.articulosQueCambian.mockReturnValue(of(1));
    servicio.guardar.mockReturnValue(of({ margenDefectoPorcentaje: 35, redondeoMultiplo: 0, articulosRecalculados: 1, avisos: [] }));
    await escribir('margen-defecto', '35');
    await enviar();

    expect(texto('app-confirmacion p')).toBe('Se recalcula el precio de 1 artículo.');
    await clickEn('app-confirmacion button.confirmar');
    expect(texto('.alerta.exito')).toBe('Se guardó la configuración. Se recalculó 1 precio.');
  });

  it('si no se pudo contar pregunta igual, sin cifra', async () => {
    await crear();
    servicio.articulosQueCambian.mockReturnValue(throwError(() => errorHttp(500, {})));
    await escribir('margen-defecto', '35');

    await enviar();

    expect(texto('app-confirmacion p')).toBe('Se recalcula el precio de los artículos que dependen de estos valores.');
  });

  it('muestra los artículos que conservaron el precio por sus ajustes', async () => {
    await crear();
    servicio.articulosQueCambian.mockReturnValue(of(0));
    servicio.guardar.mockReturnValue(
      of({ margenDefectoPorcentaje: 35, redondeoMultiplo: 0, articulosRecalculados: 0, avisos: ['No se cambió el precio de A1.'] }),
    );
    await escribir('margen-defecto', '35');

    await enviar();

    expect(texto('.avisos-salteados li')).toBe('No se cambió el precio de A1.');
  });

  it('muestra el error del backend, como el 409 sin cotización', async () => {
    await crear();
    servicio.articulosQueCambian.mockReturnValue(of(0));
    servicio.guardar.mockReturnValue(
      throwError(() => errorHttp(409, { status: 409, message: 'Todavía no hay cotización del dólar. Probá en unos minutos' })),
    );
    await escribir('margen-defecto', '35');

    await enviar();

    expect(texto('.error[role="alert"]')).toBe('Todavía no hay cotización del dólar. Probá en unos minutos');
  });

  it('valida antes de pedir nada: obligatorios, sin negativos y hasta 1000', async () => {
    await crear();

    await escribir('margen-defecto', '-1');
    await escribir('redondeo', '');
    await enviar();

    expect(texto('#margen-defecto-error')).toBe('El margen no puede ser negativo.');
    expect(texto('#redondeo-error')).toBe('El redondeo es obligatorio.');
    expect(input('margen-defecto').getAttribute('aria-invalid')).toBe('true');
    await escribir('margen-defecto', '1000.01');
    expect(texto('#margen-defecto-error')).toBe('El margen no puede superar 1000 %.');
    expect(servicio.articulosQueCambian).not.toHaveBeenCalled();
    expect(servicio.guardar).not.toHaveBeenCalled();
  });

  it('explica qué hace cada valor y que 0 es sin redondeo', async () => {
    await crear();

    expect(texto('#margen-defecto-pista')).toBe('Lo usan los artículos sin margen propio y sin rubro.');
    expect(texto('#redondeo-pista')).toBe(
      'El precio final se redondea hacia arriba a un múltiplo de este valor. Con 0 no se redondea: el precio queda con centavos.',
    );
  });
});
