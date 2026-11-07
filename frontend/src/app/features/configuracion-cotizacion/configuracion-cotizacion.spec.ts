import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { RecalculoCotizacion, VistaPreviaRecalculo } from '../../core/cotizacion/cotizacion.models';
import { CotizacionService } from '../../core/cotizacion/cotizacion.service';
import { ConfiguracionCotizacion, ConfiguracionCotizacionRequest } from './configuracion-cotizacion.models';
import { PaginaConfiguracionCotizacion } from './configuracion-cotizacion';
import { ConfiguracionCotizacionService } from './configuracion-cotizacion.service';

describe('PaginaConfiguracionCotizacion', () => {
  let fixture: ComponentFixture<PaginaConfiguracionCotizacion>;
  let obtener: ReturnType<typeof vi.fn<() => Observable<ConfiguracionCotizacion>>>;
  let guardar: ReturnType<typeof vi.fn<(pedido: ConfiguracionCotizacionRequest) => Observable<ConfiguracionCotizacion>>>;
  let recalcular: ReturnType<
    typeof vi.fn<(confirmaDesvio?: boolean, dolar?: number) => Observable<RecalculoCotizacion>>
  >;
  let vistaPrevia: ReturnType<typeof vi.fn<() => Observable<VistaPreviaRecalculo>>>;

  const configuracion: ConfiguracionCotizacion = {
    activa: true,
    dolarPrecios: 'USD:oficial',
    ladoPrecios: 'VENTA',
    visibles: ['USD:oficial'],
    horaRecalculo: '08:00',
    umbralDesvio: 5,
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

  const vista: VistaPreviaRecalculo = {
    dolar: 1100,
    vigente: 1000,
    superaUmbral: false,
    aviso: null,
    articulosQueCambian: 1,
    articulos: [
      { id: 1, codigoInterno: 'TAL-1', descripcion: 'Taladro', activo: true, precioActual: 15730, precioNuevo: 17310, diferencia: 1580 },
      { id: 2, codigoInterno: 'TOR-1', descripcion: 'Tornillo', activo: false, precioActual: 100, precioNuevo: 100, diferencia: 0 },
    ],
    avisos: [],
  };
  const vistaConDesvio: VistaPreviaRecalculo = {
    ...vista,
    dolar: 14.5,
    vigente: 1450,
    superaUmbral: true,
    aviso: mensajeAConfirmar,
  };

  const errorHttp = (status: number, error: unknown) => new HttpErrorResponse({ status, error });

  async function crear(dueno = true): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [PaginaConfiguracionCotizacion],
      providers: [
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
        { provide: ConfiguracionCotizacionService, useValue: { obtener, guardar } },
        { provide: CotizacionService, useValue: { recalcular, vistaPrevia } },
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

  const botonAplicar = () => q<HTMLButtonElement>('button.aplicar-recalculo');

  /** «Recalcular ahora» abre la vista previa; «Aplicar» recién llama al POST. */
  async function recalcularYAplicar() {
    await hacerClick(botonRecalcular());
    await hacerClick(botonAplicar());
  }

  function reemplazarEnServicio(cambios: Partial<{ recalcular: typeof recalcular; vistaPrevia: typeof vistaPrevia }>) {
    Object.assign(TestBed.inject(CotizacionService), cambios);
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
    vistaPrevia = vi.fn(() => of(vista));
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
        umbralDesvio: 5,
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

  describe('dólar a confirmar (supera el aviso de la ferretería)', () => {
    it('sin dólar a confirmar no hay aviso ni botón para revisarlo', async () => {
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
      expect(texto(q('button.confirmar-dolar'))).toBe('Ver vista previa');
    });

    it('«Ver vista previa» no aplica nada: muestra cómo quedarían los precios con el aviso', async () => {
      obtener = vi.fn(() => of(conDolarAConfirmar));
      vistaPrevia = vi.fn(() => of(vistaConDesvio));
      await crear();

      await hacerClick(q('button.confirmar-dolar'));

      expect(vistaPrevia).toHaveBeenCalledTimes(1);
      expect(recalcular).not.toHaveBeenCalled();
      expect(texto(q('.vista-previa .aviso-desvio'))).toBe(mensajeAConfirmar);
      expect(texto(botonAplicar())).toBe('Confirmar y aplicar');
    });

    it('«Confirmar y aplicar» recalcula con confirmaDesvio y el dólar visto, y el aviso se va al releer', async () => {
      obtener = vi.fn(() => of(conDolarAConfirmar));
      vistaPrevia = vi.fn(() => of(vistaConDesvio));
      await crear();
      obtener.mockReturnValue(of({ ...configuracion, vigente: 14.5, aConfirmar: null }));
      reemplazarEnServicio({ recalcular: vi.fn(() => of({ cotizacion: 14.5, articulosRecalculados: 2 })) });
      const servicio = TestBed.inject(CotizacionService) as unknown as { recalcular: typeof recalcular };

      await hacerClick(q('button.confirmar-dolar'));
      await hacerClick(botonAplicar());

      expect(servicio.recalcular).toHaveBeenCalledExactlyOnceWith(true, 14.5);
      expect(texto(q('.resultado'))).toBe('Se recalcularon 2 artículos con el dólar a 14,50.');
      expect(el().querySelector('.dolar-a-confirmar')).toBeNull();
      expect(el().querySelector('.vista-previa')).toBeNull();
    });
  });

  describe('vista previa de «Recalcular ahora»', () => {
    it('«Recalcular ahora» pide la vista previa y no cambia ningún precio todavía', async () => {
      await crear();

      await hacerClick(botonRecalcular());

      expect(vistaPrevia).toHaveBeenCalledTimes(1);
      expect(recalcular).not.toHaveBeenCalled();
      expect(el().querySelector('.resultado')).toBeNull();
    });

    it('muestra solo los artículos que cambian de precio: código, descripción, precio actual, nuevo y diferencia', async () => {
      await crear();

      await hacerClick(botonRecalcular());

      expect(texto(q('.vista-previa .resumen'))).toBe(
        'Con el dólar a 1.100,00 cambia el precio de 1 de los 2 artículos en dólares.',
      );
      const encabezados = Array.from(el().querySelectorAll('.vista-previa th')).map(texto);
      expect(encabezados).toEqual(['Código', 'Descripción', 'Precio actual', 'Precio nuevo', 'Diferencia']);
      const filas = Array.from(el().querySelectorAll('.vista-previa tbody tr'));
      expect(filas).toHaveLength(1);
      expect(Array.from(filas[0].querySelectorAll('td')).map(texto)).toEqual([
        'TAL-1',
        'Taladro',
        '$ 15.730,00',
        '$ 17.310,00',
        '+$ 1.580,00',
      ]);
      expect(el().querySelector('.vista-previa .sin-cambios')).toBeNull();
      expect(el().querySelector('.vista-previa .aviso-desvio')).toBeNull();
      expect(texto(botonAplicar())).toBe('Aplicar');
    });

    it('un inactivo que cambia de precio aparece marcado', async () => {
      vistaPrevia = vi.fn(() =>
        of({
          ...vista,
          articulosQueCambian: 1,
          articulos: [{ ...vista.articulos[1], precioNuevo: 110, diferencia: 10 }],
        }),
      );
      await crear();

      await hacerClick(botonRecalcular());

      const filas = Array.from(el().querySelectorAll('.vista-previa tbody tr'));
      expect(Array.from(filas[0].querySelectorAll('td')).map(texto)).toEqual([
        'TOR-1',
        'Tornillo (inactivo)',
        '$ 100,00',
        '$ 110,00',
        '+$ 10,00',
      ]);
    });

    it('si no cambia ningún precio lo dice y no lista los artículos', async () => {
      vistaPrevia = vi.fn(() =>
        of({ ...vista, dolar: 1000, articulosQueCambian: 0, articulos: [vista.articulos[1]] }),
      );
      await crear();

      await hacerClick(botonRecalcular());

      expect(texto(q('.vista-previa .resumen'))).toBe(
        'Con el dólar a 1.000,00 cambia el precio de 0 del único artículo en dólares.',
      );
      expect(texto(q('.vista-previa .sin-cambios'))).toBe('No cambia ningún precio.');
      expect(el().querySelector('.vista-previa table')).toBeNull();
    });

    it('sin artículos en dólares lo dice y no ofrece aplicar', async () => {
      vistaPrevia = vi.fn(() => of({ ...vista, articulos: [], articulosQueCambian: 0 }));
      await crear();

      await hacerClick(botonRecalcular());

      expect(texto(q('.vista-previa .resumen'))).toBe('No hay artículos cargados en dólares.');
      expect(el().querySelector('button.aplicar-recalculo')).toBeNull();
    });

    it('«Aplicar» recalcula con el dólar que se vio, sin confirmaDesvio', async () => {
      await crear();

      await recalcularYAplicar();

      expect(recalcular).toHaveBeenCalledExactlyOnceWith(false, 1100);
      expect(el().querySelector('.vista-previa')).toBeNull();
    });

    it('«Cancelar» cierra la vista previa sin aplicar nada', async () => {
      await crear();
      await hacerClick(botonRecalcular());

      await hacerClick(q('button.cancelar-recalculo'));

      expect(el().querySelector('.vista-previa')).toBeNull();
      expect(recalcular).not.toHaveBeenCalled();
    });

    it('lista los artículos que conservarían su precio por sus ajustes', async () => {
      const aviso = 'No se cambió el precio de TOR-1 (Tornillo): con sus ajustes quedaría en 0 o menos.';
      vistaPrevia = vi.fn(() => of({ ...vista, avisos: [aviso] }));
      await crear();

      await hacerClick(botonRecalcular());

      expect(texto(q('.vista-previa .avisos-vista-previa'))).toContain(aviso);
    });

    it('un 409 al pedir la vista previa se muestra en la pantalla', async () => {
      vistaPrevia = vi.fn(() => throwError(() => errorHttp(409, { message: 'La cotización está apagada' })));
      await crear();

      await hacerClick(botonRecalcular());

      expect(texto(q('.recalculo [role="alert"]'))).toBe('La cotización está apagada');
      expect(el().querySelector('.vista-previa')).toBeNull();
    });

    it('si el dólar cambió desde la vista previa, se muestra el 409 y se cierra la vista previa', async () => {
      const mensaje = 'El dólar cambió desde la vista previa ($ 1.100,00 → $ 1.120,00). Revisala de nuevo.';
      recalcular = vi.fn(() => throwError(() => errorHttp(409, { message: mensaje })));
      await crear();

      await recalcularYAplicar();

      expect(texto(q('.recalculo [role="alert"]'))).toBe(mensaje);
      expect(el().querySelector('.vista-previa')).toBeNull();
    });
  });

  describe('recálculo', () => {
    it('explica que los precios se recalculan solos cada día a la hora guardada, con «Recalcular ahora» al lado', async () => {
      await crear();

      const bloque = q('.recalculo');
      expect(texto(q('.recalculo-texto'))).toBe(
        'Los precios en dólares se recalculan solos todos los días a las 08:00, también después de cambiar el dólar ' +
          'o el valor que usan. Si no querés esperar, podés recalcular ahora: antes de cambiar nada vas a ver cómo ' +
          'quedan los precios.',
      );
      expect(texto(bloque)).not.toContain('El cambio se aplica');
      expect(bloque.contains(botonRecalcular())).toBe(true);
      expect(texto(botonRecalcular())).toBe('Recalcular ahora');
    });

    it('al aplicar la vista previa llama al POST y dice cuántos artículos cambiaron y con qué cotización', async () => {
      await crear();

      await recalcularYAplicar();

      expect(recalcular).toHaveBeenCalledTimes(1);
      expect(texto(q('.resultado'))).toBe('Se recalcularon 3 artículos con el dólar a 1.100,00.');
      expect(q('.resultado').getAttribute('role')).toBe('status');
    });

    it('si el backend saltea artículos por sus ajustes, los lista como advertencia debajo del resultado', async () => {
      const aviso =
        'No se cambió el precio de TOR-1 (Tornillo): con sus ajustes quedaría en 0 o menos. Revisá sus ajustes.';
      recalcular = vi.fn(() => of({ cotizacion: 1100, articulosRecalculados: 2, avisos: [aviso] }));
      await crear();

      await recalcularYAplicar();

      expect(texto(q('.resultado'))).toBe('Se recalcularon 2 artículos con el dólar a 1.100,00.');
      expect(texto(q('.avisos-salteados'))).toContain(aviso);
      expect(q('.avisos-salteados').getAttribute('role')).toBe('alert');
    });

    it('sin artículos salteados no muestra la advertencia', async () => {
      await crear();

      await recalcularYAplicar();

      expect(el().querySelector('.avisos-salteados')).toBeNull();
    });

    it('con un solo artículo, singular; sin cambios, lo dice', async () => {
      recalcular = vi.fn(() => of({ cotizacion: 1000, articulosRecalculados: 1 }));
      await crear();
      await recalcularYAplicar();
      expect(texto(q('.resultado'))).toBe('Se recalculó 1 artículo con el dólar a 1.000,00.');

      reemplazarEnServicio({ recalcular: vi.fn(() => of({ cotizacion: 1000, articulosRecalculados: 0 })) });
      await recalcularYAplicar();
      expect(texto(q('.resultado'))).toBe('Ningún artículo cambió de precio con el dólar a 1.000,00.');
    });

    it('después de recalcular vuelve a leer la configuración: el dólar vigente se actualiza', async () => {
      obtener = vi
        .fn<() => Observable<ConfiguracionCotizacion>>()
        .mockReturnValueOnce(of(configuracion))
        .mockReturnValue(of({ ...configuracion, vigente: 1100, vigenteDesde: '2026-10-16T13:00:00Z' }));
      await crear();
      expect(texto(q('.vigente'))).toBe('Los precios en dólares se recalcularon el 16/10 08:00 con el dólar a 1.000,00.');

      await recalcularYAplicar();

      expect(obtener).toHaveBeenCalledTimes(2);
      expect(texto(q('.vigente'))).toBe('Los precios en dólares se recalcularon el 16/10 10:00 con el dólar a 1.100,00.');
    });

    it('si todavía no se fijó ningún dólar lo dice', async () => {
      obtener = vi.fn(() => of({ ...configuracion, vigente: null, vigenteDesde: null }));
      await crear();

      expect(texto(q('.vigente'))).toBe('Todavía no se calcularon precios con el dólar de esta ferretería.');
    });

    it('sin dólar fijado pero con uno publicado dice que calcula con ese, sin fecha (QA 05/11)', async () => {
      obtener = vi.fn(() => of({ ...configuracion, vigente: 1050, vigenteDesde: null }));
      await crear();

      expect(texto(q('.vigente'))).toBe('Los precios en dólares se calculan con el último dólar publicado, a 1.050,00.');
    });

    it('mientras pide la vista previa el botón queda deshabilitado y no se puede pedir dos veces', async () => {
      const pendiente = new Subject<VistaPreviaRecalculo>();
      vistaPrevia = vi.fn(() => pendiente);
      await crear();

      await hacerClick(botonRecalcular());
      expect(botonRecalcular().disabled).toBe(true);
      expect(texto(botonRecalcular())).toBe('Preparando vista previa…');
      botonRecalcular().click();
      expect(vistaPrevia).toHaveBeenCalledTimes(1);

      pendiente.next(vista);
      pendiente.complete();
      await refrescar();
      expect(el().querySelector('.vista-previa')).not.toBeNull();
    });

    it('mientras aplica, «Aplicar» y «Recalcular ahora» quedan deshabilitados y no se puede pedir dos veces', async () => {
      const pendiente = new Subject<RecalculoCotizacion>();
      recalcular = vi.fn(() => pendiente);
      await crear();

      await recalcularYAplicar();
      expect(botonAplicar().disabled).toBe(true);
      expect(texto(botonAplicar())).toBe('Aplicando…');
      expect(botonRecalcular().disabled).toBe(true);

      botonAplicar().click();
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

      await recalcularYAplicar();

      expect(texto(q('.recalculo [role="alert"]'))).toBe('Todavía no hay cotización del dólar. Probá en unos minutos');
      expect(el().querySelector('.resultado')).toBeNull();
    });

    it('un resultado viejo se borra al volver a recalcular con error', async () => {
      await crear();
      await recalcularYAplicar();
      expect(el().querySelector('.resultado')).not.toBeNull();

      reemplazarEnServicio({
        vistaPrevia: vi.fn(() => throwError(() => errorHttp(409, { message: 'La cotización está apagada' }))),
      });
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
      expect(texto(q('.recalculo-texto'))).toBe('Con la cotización apagada, los precios en dólares no se recalculan solos.');

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
