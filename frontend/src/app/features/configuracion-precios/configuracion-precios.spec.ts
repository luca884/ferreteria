import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { PaginaConfiguracionPrecios } from './configuracion-precios';
import {
  ArticulosQueCambian,
  ConfiguracionPreciosService,
  EjemploDePrecio,
} from './configuracion-precios.service';

describe('PaginaConfiguracionPrecios', () => {
  const actual = { margenDefectoPorcentaje: 30, redondeoMultiplo: 0 };

  let fixture: ComponentFixture<PaginaConfiguracionPrecios>;
  let servicio: {
    obtener: ReturnType<typeof vi.fn>;
    articulosQueCambian: ReturnType<typeof vi.fn>;
    guardar: ReturnType<typeof vi.fn>;
  };

  const errorHttp = (status: number, error: object) => new HttpErrorResponse({ status, error });
  const conteo = (cantidad: number, ejemplos: EjemploDePrecio[] = []): ArticulosQueCambian => ({ cantidad, ejemplos });

  async function crear(dueno = true, obtener = vi.fn(() => of(actual))) {
    servicio = {
      obtener,
      articulosQueCambian: vi.fn(() => of(conteo(23))),
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
  const normal = (t: string | null | undefined) => t?.replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => normal(el().querySelector(selector)?.textContent);

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
    expect(texto('app-confirmacion h2')).toBe('¿Cambiar el margen?');
    expect(texto('app-confirmacion p')).toBe('Se recalcula el precio de 23 artículos.');
  });

  it('el título de la confirmación nombra solo lo que cambió (QA 05/11)', async () => {
    await crear();
    await escribir('redondeo', '10');
    await enviar();

    expect(texto('app-confirmacion h2')).toBe('¿Cambiar el redondeo?');

    el().querySelector<HTMLButtonElement>('app-confirmacion button.cancelar')!.click();
    await refrescar();
    await escribir('margen-defecto', '35');
    await enviar();

    expect(texto('app-confirmacion h2')).toBe('¿Cambiar el margen y el redondeo?');
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
    servicio.articulosQueCambian.mockReturnValue(of(conteo(0)));
    servicio.guardar.mockReturnValue(of({ margenDefectoPorcentaje: 35, redondeoMultiplo: 0, articulosRecalculados: 0, avisos: [] }));
    await escribir('margen-defecto', '35');

    await enviar();

    expect(el().querySelector('app-confirmacion')).toBeNull();
    expect(servicio.guardar).toHaveBeenCalled();
    expect(texto('.alerta.exito')).toBe('Se guardó la configuración.');
  });

  it('dice 1 artículo y 1 precio en singular', async () => {
    await crear();
    servicio.articulosQueCambian.mockReturnValue(of(conteo(1)));
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
    servicio.articulosQueCambian.mockReturnValue(of(conteo(0)));
    servicio.guardar.mockReturnValue(
      of({ margenDefectoPorcentaje: 35, redondeoMultiplo: 0, articulosRecalculados: 0, avisos: ['No se cambió el precio de A1.'] }),
    );
    await escribir('margen-defecto', '35');

    await enviar();

    expect(texto('.avisos-salteados li')).toBe('No se cambió el precio de A1.');
  });

  it('muestra el error del backend, como el 409 sin cotización', async () => {
    await crear();
    servicio.articulosQueCambian.mockReturnValue(of(conteo(0)));
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

  it('la cantidad de artículos lleva el punto de miles, como en el resto de la app', async () => {
    await crear();
    servicio.articulosQueCambian.mockReturnValue(of(conteo(4142)));
    await escribir('margen-defecto', '35');

    await enviar();

    expect(texto('app-confirmacion p')).toBe('Se recalcula el precio de 4.142 artículos.');
  });

  it('la confirmación muestra los ejemplos que manda el servidor, con el precio de hoy y el nuevo', async () => {
    await crear();
    servicio.articulosQueCambian.mockReturnValue(
      of(
        conteo(4142, [
          { codigo: 'TOR-001', descripcion: 'Tornillo 1/4', precioActual: 909788.6, precioNuevo: 909790 },
          { codigo: 'CLA-002', descripcion: 'Clavo 2"', precioActual: 1200, precioNuevo: 1210.5 },
        ]),
      ),
    );
    await escribir('redondeo', '10');

    await enviar();

    const encabezados = Array.from(el().querySelectorAll('app-confirmacion table.ejemplos th')).map((t) =>
      t.textContent?.trim(),
    );
    expect(encabezados).toEqual(['Código', 'Descripción', 'Precio de hoy', 'Precio nuevo']);
    const filas = Array.from(el().querySelectorAll('app-confirmacion table.ejemplos tbody tr')).map((tr) =>
      Array.from(tr.querySelectorAll('td')).map((td) => normal(td.textContent)),
    );
    expect(filas).toEqual([
      ['TOR-001', 'Tornillo 1/4', '$ 909.788,60', '$ 909.790,00'],
      ['CLA-002', 'Clavo 2"', '$ 1.200,00', '$ 1.210,50'],
    ]);
    expect(texto('app-confirmacion caption')).toBe('Por ejemplo');
  });

  it('sin ejemplos la confirmación no muestra la tabla', async () => {
    await crear();
    await escribir('margen-defecto', '35');

    await enviar();

    expect(el().querySelector('app-confirmacion')).not.toBeNull();
    expect(el().querySelector('app-confirmacion table')).toBeNull();
  });

  describe('ejemplo del redondeo', () => {
    const ejemplo = () => texto('#redondeo-ejemplo');

    it('con 10 y con 100 muestra cómo queda un precio, siempre hacia arriba', async () => {
      await crear();

      await escribir('redondeo', '10');
      expect(ejemplo()).toBe(
        'Con 10, un precio de $ 909.788,60 queda en $ 909.790,00. Siempre hacia arriba, así no perdés margen.',
      );
      expect(input('redondeo').getAttribute('aria-describedby')).toBe('redondeo-pista redondeo-ejemplo');

      await escribir('redondeo', '100');
      expect(ejemplo()).toBe(
        'Con 100, un precio de $ 909.788,60 queda en $ 909.800,00. Siempre hacia arriba, así no perdés margen.',
      );
    });

    it('con un múltiplo con decimales redondea igual que el servidor', async () => {
      await crear();

      await escribir('redondeo', '0,5');

      expect(ejemplo()).toBe(
        'Con 0,5, un precio de $ 909.788,60 queda en $ 909.789,00. Siempre hacia arriba, así no perdés margen.',
      );
    });

    it('con 0 no hay ejemplo: queda el texto de siempre', async () => {
      await crear();

      expect(el().querySelector('#redondeo-ejemplo')).toBeNull();
      expect(input('redondeo').getAttribute('aria-describedby')).toBe('redondeo-pista');
    });
  });
});
