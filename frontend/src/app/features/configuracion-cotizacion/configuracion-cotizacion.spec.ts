import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { RecalculoCotizacion } from '../../core/cotizacion/cotizacion.models';
import { CotizacionService } from '../../core/cotizacion/cotizacion.service';
import { ConfiguracionCotizacion, ConfiguracionCotizacionRequest } from './configuracion-cotizacion.models';
import { PaginaConfiguracionCotizacion } from './configuracion-cotizacion';
import { ConfiguracionCotizacionService } from './configuracion-cotizacion.service';

describe('PaginaConfiguracionCotizacion', () => {
  let fixture: ComponentFixture<PaginaConfiguracionCotizacion>;
  let obtener: ReturnType<typeof vi.fn<() => Observable<ConfiguracionCotizacion>>>;
  let guardar: ReturnType<typeof vi.fn<(pedido: ConfiguracionCotizacionRequest) => Observable<ConfiguracionCotizacion>>>;
  let recalcular: ReturnType<typeof vi.fn<(confirmaDesvio?: boolean) => Observable<RecalculoCotizacion>>>;

  const configuracion: ConfiguracionCotizacion = {
    activa: true,
    dolarPrecios: 'USD:oficial',
    ladoPrecios: 'VENTA',
    visibles: ['USD:oficial'],
    horaRecalculo: '08:00',
    vigente: 1000,
    vigenteDesde: '2026-10-16T11:00:00Z',
    aConfirmar: null,
  };

  const mensajeAConfirmar =
    'El dólar oficial venta vino en $ 14,50, un 99 % distinto del anterior ($ 1.450,00). ' +
    'Los precios siguen con $ 1.450,00 hasta que lo confirmes.';
  const conDolarAConfirmar: ConfiguracionCotizacion = {
    ...configuracion,
    vigente: 1450,
    aConfirmar: { nuevo: 14.5, vigente: 1450, desvioPorcentaje: 99, mensaje: mensajeAConfirmar },
  };

  const errorHttp = (status: number, error: unknown) => new HttpErrorResponse({ status, error });

  async function crear(dueno = true): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [PaginaConfiguracionCotizacion],
      providers: [
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
        { provide: ConfiguracionCotizacionService, useValue: { obtener, guardar } },
        { provide: CotizacionService, useValue: { recalcular } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PaginaConfiguracionCotizacion);
    await refrescar();
    return fixture.nativeElement as HTMLElement;
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends HTMLElement>(selector: string) => el().querySelector<T>(selector)!;
  const texto = (e: Element | null) => e?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const botonRecalcular = () => q<HTMLButtonElement>('button.recalcular');

  async function hacerClick(boton: HTMLElement) {
    boton.click();
    await refrescar();
  }

  async function enviarFormulario() {
    q<HTMLFormElement>('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await refrescar();
  }

  async function marcar(selector: string, valor: boolean) {
    const input = q<HTMLInputElement>(selector);
    input.checked = valor;
    input.dispatchEvent(new Event('change', { bubbles: true }));
    await refrescar();
  }

  beforeEach(() => {
    obtener = vi.fn(() => of(configuracion));
    guardar = vi.fn((pedido) => of({ ...configuracion, ...pedido }));
    recalcular = vi.fn(() => of({ cotizacion: 1100, articulosRecalculados: 3 }));
  });

  it('el Dueño ve la pantalla «Cotización» con la configuración cargada en el formulario', async () => {
    await crear();

    expect(obtener).toHaveBeenCalledTimes(1);
    expect(texto(q('h1'))).toBe('Cotización');
    expect(q<HTMLSelectElement>('#dolarPrecios').value).toBe('USD:oficial');
    expect(q<HTMLInputElement>('#horaRecalculo').value).toBe('08:00');
  });

  it('el Vendedor no puede configurar: se le dice y no se pide nada al servidor', async () => {
    await crear(false);

    expect(texto(q('[role="status"]'))).toBe('Solo el Dueño puede configurar la cotización.');
    expect(obtener).not.toHaveBeenCalled();
    expect(el().querySelector('form')).toBeNull();
    expect(el().querySelector('button.recalcular')).toBeNull();
  });

  it('mientras carga dice «Cargando…»', async () => {
    const pendiente = new Subject<ConfiguracionCotizacion>();
    obtener = vi.fn(() => pendiente);
    await crear();

    expect(texto(el())).toContain('Cargando…');
    expect(el().querySelector('form')).toBeNull();

    pendiente.next(configuracion);
    await refrescar();

    expect(texto(el())).not.toContain('Cargando…');
    expect(el().querySelector('form')).not.toBeNull();
  });

  describe('carga fallida', () => {
    it('avisa y ofrece «Reintentar», que vuelve a pedir y muestra el formulario', async () => {
      obtener = vi
        .fn<() => Observable<ConfiguracionCotizacion>>()
        .mockReturnValueOnce(throwError(() => errorHttp(500, { message: 'x' })))
        .mockReturnValue(of(configuracion));
      await crear();

      expect(texto(q('[role="alert"]'))).toContain('No se pudo cargar la configuración.');
      expect(el().querySelector('form')).toBeNull();

      await hacerClick(q('button.reintentar'));

      expect(obtener).toHaveBeenCalledTimes(2);
      expect(el().querySelector('[role="alert"]')).toBeNull();
      expect(el().querySelector('form')).not.toBeNull();
    });
  });

  describe('guardar', () => {
    it('manda el pedido completo al PUT y avisa que se guardó', async () => {
      await crear();
      await marcar('input[type="checkbox"][value="USD:blue"]', true);

      await enviarFormulario();

      expect(guardar).toHaveBeenCalledWith({
        activa: true,
        dolarPrecios: 'USD:oficial',
        ladoPrecios: 'VENTA',
        visibles: ['USD:oficial', 'USD:blue'],
        horaRecalculo: '08:00',
      });
      expect(texto(q('.alerta.exito'))).toBe('Se guardó la configuración.');
      expect(q('.alerta.exito').getAttribute('role')).toBe('status');
    });

    it('lo guardado queda en el formulario', async () => {
      await crear();
      await marcar('input[type="checkbox"][value="EUR:oficial"]', true);

      await enviarFormulario();

      expect(q<HTMLInputElement>('input[type="checkbox"][value="EUR:oficial"]').checked).toBe(true);
    });

    it('un 400 muestra el mensaje de cada campo y no dice que se guardó', async () => {
      guardar = vi.fn(() =>
        throwError(() =>
          errorHttp(400, {
            message: 'Datos inválidos',
            errors: {
              dolarPrecios: 'El dólar de los precios tiene que ser un tipo de dólar.',
              horaRecalculo: 'La hora tiene que tener el formato HH:mm, por ejemplo 08:00.',
            },
          }),
        ),
      );
      await crear();
      await marcar('input[type="checkbox"][value="USD:blue"]', true);

      await enviarFormulario();

      const alerta = q('[role="alert"]');
      expect(Array.from(alerta.querySelectorAll('li')).map((li) => texto(li))).toEqual([
        'El dólar de los precios tiene que ser un tipo de dólar.',
        'La hora tiene que tener el formato HH:mm, por ejemplo 08:00.',
      ]);
      expect(el().querySelector('.alerta.exito')).toBeNull();
    });

    it('cualquier otro error muestra su mensaje o uno genérico', async () => {
      guardar = vi.fn(() => throwError(() => errorHttp(403, { message: 'No tenés permiso.' })));
      await crear();
      await marcar('input[type="checkbox"][value="USD:blue"]', true);

      await enviarFormulario();
      expect(texto(q('[role="alert"]'))).toBe('No tenés permiso.');

      guardar = vi.fn(() => throwError(() => new HttpErrorResponse({ status: 0 })));
      (TestBed.inject(ConfiguracionCotizacionService) as unknown as { guardar: typeof guardar }).guardar = guardar;
      await enviarFormulario();
      expect(texto(q('[role="alert"]'))).toBe('No se pudo guardar la configuración.');
    });
  });

  describe('dólar a confirmar (más del 20 % de diferencia)', () => {
    it('sin dólar a confirmar no hay aviso ni botón «Confirmar»', async () => {
      await crear();

      expect(el().querySelector('.dolar-a-confirmar')).toBeNull();
      expect(el().querySelector('button.confirmar-dolar')).toBeNull();
    });

    it('el Dueño ve el aviso con el valor nuevo, el desvío y el dólar con el que siguen los precios', async () => {
      obtener = vi.fn(() => of(conDolarAConfirmar));
      await crear();

      const aviso = q('.recalculo .dolar-a-confirmar');
      expect(aviso.getAttribute('role')).toBe('alert');
      expect(texto(aviso.querySelector('p'))).toBe(mensajeAConfirmar);
      expect(texto(q('button.confirmar-dolar'))).toBe('Confirmar');
    });

    it('«Confirmar» recalcula con confirmaDesvio y el aviso se va al releer la configuración', async () => {
      obtener = vi.fn(() => of(conDolarAConfirmar));
      await crear();
      obtener.mockReturnValue(of({ ...configuracion, vigente: 14.5, aConfirmar: null }));
      recalcular = vi.fn(() => of({ cotizacion: 14.5, articulosRecalculados: 2 }));
      (TestBed.inject(CotizacionService) as unknown as { recalcular: typeof recalcular }).recalcular = recalcular;

      await hacerClick(q('button.confirmar-dolar'));

      expect(recalcular).toHaveBeenCalledExactlyOnceWith(true);
      expect(texto(q('.resultado'))).toBe('Se recalcularon 2 artículos con el dólar a 14,50.');
      expect(el().querySelector('.dolar-a-confirmar')).toBeNull();
    });

    it('«Recalcular ahora» no confirma: llama sin confirmaDesvio', async () => {
      obtener = vi.fn(() => of(conDolarAConfirmar));
      await crear();

      await hacerClick(botonRecalcular());

      expect(recalcular).toHaveBeenCalledExactlyOnceWith(false);
    });
  });

  describe('recálculo', () => {
    it('dice «El cambio se aplica en el próximo recálculo» con «Recalcular ahora» al lado, en el mismo bloque', async () => {
      await crear();

      const bloque = q('.recalculo');
      expect(texto(bloque)).toContain('El cambio se aplica en el próximo recálculo');
      expect(bloque.contains(botonRecalcular())).toBe(true);
      expect(texto(botonRecalcular())).toBe('Recalcular ahora');
    });

    it('«Recalcular ahora» llama al POST y dice cuántos artículos cambiaron y con qué cotización', async () => {
      await crear();

      await hacerClick(botonRecalcular());

      expect(recalcular).toHaveBeenCalledTimes(1);
      expect(texto(q('.resultado'))).toBe('Se recalcularon 3 artículos con el dólar a 1.100,00.');
      expect(q('.resultado').getAttribute('role')).toBe('status');
    });

    it('si el backend saltea artículos por sus ajustes, los lista como advertencia debajo del resultado', async () => {
      const aviso =
        'No se cambió el precio de TOR-1 (Tornillo): con sus ajustes quedaría en 0 o menos. Revisá sus ajustes.';
      recalcular = vi.fn(() => of({ cotizacion: 1100, articulosRecalculados: 2, avisos: [aviso] }));
      await crear();

      await hacerClick(botonRecalcular());

      expect(texto(q('.resultado'))).toBe('Se recalcularon 2 artículos con el dólar a 1.100,00.');
      expect(texto(q('.avisos-salteados'))).toContain(aviso);
      expect(q('.avisos-salteados').getAttribute('role')).toBe('alert');
    });

    it('sin artículos salteados no muestra la advertencia', async () => {
      await crear();

      await hacerClick(botonRecalcular());

      expect(el().querySelector('.avisos-salteados')).toBeNull();
    });

    it('con un solo artículo, singular; sin cambios, lo dice', async () => {
      recalcular = vi.fn(() => of({ cotizacion: 1000, articulosRecalculados: 1 }));
      await crear();
      await hacerClick(botonRecalcular());
      expect(texto(q('.resultado'))).toBe('Se recalculó 1 artículo con el dólar a 1.000,00.');

      recalcular = vi.fn(() => of({ cotizacion: 1000, articulosRecalculados: 0 }));
      (TestBed.inject(CotizacionService) as unknown as { recalcular: typeof recalcular }).recalcular = recalcular;
      await hacerClick(botonRecalcular());
      expect(texto(q('.resultado'))).toBe('Ningún artículo cambió de precio con el dólar a 1.000,00.');
    });

    it('después de recalcular vuelve a leer la configuración: el dólar vigente se actualiza', async () => {
      obtener = vi
        .fn<() => Observable<ConfiguracionCotizacion>>()
        .mockReturnValueOnce(of(configuracion))
        .mockReturnValue(of({ ...configuracion, vigente: 1100, vigenteDesde: '2026-10-16T13:00:00Z' }));
      await crear();
      expect(texto(q('.vigente'))).toBe('Los precios en dólares se calcularon con el dólar a 1.000,00 (16/10 08:00).');

      await hacerClick(botonRecalcular());

      expect(obtener).toHaveBeenCalledTimes(2);
      expect(texto(q('.vigente'))).toBe('Los precios en dólares se calcularon con el dólar a 1.100,00 (16/10 10:00).');
    });

    it('si todavía no se fijó ningún dólar lo dice', async () => {
      obtener = vi.fn(() => of({ ...configuracion, vigente: null, vigenteDesde: null }));
      await crear();

      expect(texto(q('.vigente'))).toBe('Todavía no se calcularon precios con el dólar de esta ferretería.');
    });

    it('mientras recalcula el botón queda deshabilitado y no se puede pedir dos veces', async () => {
      const pendiente = new Subject<RecalculoCotizacion>();
      recalcular = vi.fn(() => pendiente);
      await crear();

      await hacerClick(botonRecalcular());
      expect(botonRecalcular().disabled).toBe(true);
      expect(texto(botonRecalcular())).toBe('Recalculando…');

      botonRecalcular().click();
      expect(recalcular).toHaveBeenCalledTimes(1);

      pendiente.next({ cotizacion: 1100, articulosRecalculados: 2 });
      pendiente.complete();
      await refrescar();

      expect(botonRecalcular().disabled).toBe(false);
      expect(texto(botonRecalcular())).toBe('Recalcular ahora');
    });

    it('un 409 del backend se muestra en la pantalla', async () => {
      recalcular = vi.fn(() =>
        throwError(() => errorHttp(409, { message: 'Todavía no hay cotización del dólar. Probá en unos minutos' })),
      );
      await crear();

      await hacerClick(botonRecalcular());

      expect(texto(q('.recalculo [role="alert"]'))).toBe('Todavía no hay cotización del dólar. Probá en unos minutos');
      expect(el().querySelector('.resultado')).toBeNull();
    });

    it('un resultado viejo se borra al volver a recalcular con error', async () => {
      await crear();
      await hacerClick(botonRecalcular());
      expect(el().querySelector('.resultado')).not.toBeNull();

      recalcular = vi.fn(() => throwError(() => errorHttp(409, { message: 'La cotización está apagada' })));
      (TestBed.inject(CotizacionService) as unknown as { recalcular: typeof recalcular }).recalcular = recalcular;
      await hacerClick(botonRecalcular());

      expect(el().querySelector('.resultado')).toBeNull();
      expect(texto(q('.recalculo [role="alert"]'))).toBe('La cotización está apagada');
    });

    it('con la cotización apagada el botón está deshabilitado y se explica por qué', async () => {
      obtener = vi.fn(() => of({ ...configuracion, activa: false }));
      await crear();

      expect(botonRecalcular().disabled).toBe(true);
      const motivo = q('#motivo-sin-recalculo');
      expect(texto(motivo)).toBe('La cotización está apagada. Activala y guardá para poder recalcular.');
      expect(botonRecalcular().getAttribute('aria-describedby')).toContain('motivo-sin-recalculo');

      botonRecalcular().click();
      expect(recalcular).not.toHaveBeenCalled();
    });

    it('con la cotización activada no hay motivo y el botón se puede usar', async () => {
      await crear();

      expect(botonRecalcular().disabled).toBe(false);
      expect(el().querySelector('#motivo-sin-recalculo')).toBeNull();
    });

    it('con cambios sin guardar el botón espera: se pide guardar antes, porque recalcula con lo guardado', async () => {
      await crear();
      await marcar('input[type="checkbox"][value="USD:blue"]', true);

      expect(botonRecalcular().disabled).toBe(true);
      expect(texto(q('#motivo-sin-recalculo'))).toBe('Guardá los cambios antes de recalcular.');

      await enviarFormulario();

      expect(botonRecalcular().disabled).toBe(false);
      expect(el().querySelector('#motivo-sin-recalculo')).toBeNull();
    });
  });
});
