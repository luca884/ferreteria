import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ConfiguracionCotizacion, ConfiguracionCotizacionRequest } from './configuracion-cotizacion.models';
import { FormularioCotizacion } from './formulario-cotizacion';

describe('FormularioCotizacion', () => {
  let fixture: ComponentFixture<FormularioCotizacion>;
  let guardados: ConfiguracionCotizacionRequest[];
  let cambios: boolean[];

  const configuracion: ConfiguracionCotizacion = {
    activa: true,
    dolarPrecios: 'USD:oficial',
    ladoPrecios: 'VENTA',
    visibles: ['USD:oficial', 'EUR:oficial'],
    horaRecalculo: '08:00',
    umbralDesvio: 5,
    vigente: 1000,
    vigenteDesde: '2026-10-16T11:00:00Z',
    aConfirmar: null,
  };

  async function crear(valor: ConfiguracionCotizacion = configuracion, guardando = false): Promise<HTMLElement> {
    await TestBed.configureTestingModule({ imports: [FormularioCotizacion] }).compileComponents();
    fixture = TestBed.createComponent(FormularioCotizacion);
    guardados = [];
    cambios = [];
    fixture.componentInstance.guardar.subscribe((pedido) => guardados.push(pedido));
    fixture.componentInstance.cambios.subscribe((sucio) => cambios.push(sucio));
    fixture.componentRef.setInput('configuracion', valor);
    fixture.componentRef.setInput('guardando', guardando);
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

  const casilla = (codigo: string) => q<HTMLInputElement>(`input[type="checkbox"][value="${codigo}"]`);
  const lado = (valor: string) => q<HTMLInputElement>(`input[type="radio"][name="ladoPrecios"][value="${valor}"]`);

  async function elegir(select: HTMLSelectElement, valor: string) {
    select.value = valor;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await refrescar();
  }

  async function marcar(input: HTMLInputElement, valor: boolean) {
    input.checked = valor;
    input.dispatchEvent(new Event('change', { bubbles: true }));
    await refrescar();
  }

  async function escribir(input: HTMLInputElement, valor: string) {
    input.value = valor;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await refrescar();
  }

  async function enviar() {
    q<HTMLFormElement>('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await refrescar();
  }

  it('carga la configuración: activada, dólar de precios, lado, visibles y hora', async () => {
    await crear();

    expect(q<HTMLInputElement>('#activa').checked).toBe(true);
    expect(q<HTMLSelectElement>('#dolarPrecios').value).toBe('USD:oficial');
    expect(lado('VENTA').checked).toBe(true);
    expect(lado('COMPRA').checked).toBe(false);
    expect(casilla('USD:oficial').checked).toBe(true);
    expect(casilla('EUR:oficial').checked).toBe(true);
    expect(casilla('USD:blue').checked).toBe(false);
    expect(q<HTMLInputElement>('#horaRecalculo').value).toBe('08:00');
  });

  it('con otra configuración muestra otros valores (desactivada, compra, MEP)', async () => {
    await crear({
      ...configuracion,
      activa: false,
      dolarPrecios: 'USD:bolsa',
      ladoPrecios: 'COMPRA',
      visibles: ['USD:bolsa'],
      horaRecalculo: '09:30',
    });

    expect(q<HTMLInputElement>('#activa').checked).toBe(false);
    expect(q<HTMLSelectElement>('#dolarPrecios').value).toBe('USD:bolsa');
    expect(lado('COMPRA').checked).toBe(true);
    expect(casilla('USD:oficial').checked).toBe(false);
    expect(casilla('USD:bolsa').checked).toBe(true);
    expect(q<HTMLInputElement>('#horaRecalculo').value).toBe('09:30');
  });

  it('se pueden mostrar varios tipos a la vez: una casilla por cada tipo del catálogo', async () => {
    await crear();

    const casillas = Array.from(el().querySelectorAll<HTMLInputElement>('fieldset.visibles input[type="checkbox"]'));
    expect(casillas.map((c) => c.value)).toEqual([
      'USD:oficial',
      'USD:blue',
      'USD:bolsa',
      'USD:contadoconliqui',
      'USD:mayorista',
      'USD:tarjeta',
      'USD:cripto',
      'EUR:oficial',
      'BRL:oficial',
    ]);
  });

  it('el dólar de precios es UNO solo y de dólar: euro y real no son opciones', async () => {
    await crear();

    const opciones = Array.from(q<HTMLSelectElement>('#dolarPrecios').options).map((o) => o.value);
    expect(opciones).toHaveLength(7);
    expect(opciones.every((codigo) => codigo.startsWith('USD:'))).toBe(true);
    expect(opciones).not.toContain('EUR:oficial');
    expect(opciones).not.toContain('BRL:oficial');
  });

  it('euro y real se ofrecen solo para mostrar, y se dice', async () => {
    await crear();

    expect(texto(q('#visibles-pista'))).toContain('El euro y el real son solo para mostrar');
    expect(texto(q('label[for="visible-EUR:oficial"]'))).toBe('Euro');
    expect(texto(q('label[for="visible-BRL:oficial"]'))).toBe('Real');
  });

  it('guardar emite la configuración editada: dólar, lado, visibles, hora y activada', async () => {
    await crear();

    await elegir(q<HTMLSelectElement>('#dolarPrecios'), 'USD:bolsa');
    await marcar(lado('COMPRA'), true);
    await marcar(casilla('USD:oficial'), false);
    await marcar(casilla('USD:blue'), true);
    await escribir(q<HTMLInputElement>('#horaRecalculo'), '09:30');
    await marcar(q<HTMLInputElement>('#activa'), false);
    await enviar();

    expect(guardados).toEqual([
      {
        activa: false,
        dolarPrecios: 'USD:bolsa',
        ladoPrecios: 'COMPRA',
        visibles: ['USD:blue', 'EUR:oficial'],
        horaRecalculo: '09:30',
        umbralDesvio: 5,
      },
    ]);
  });

  it('sin tocar nada, guardar emite lo mismo que llegó (sin el vigente)', async () => {
    await crear();

    await enviar();

    expect(guardados).toEqual([
      {
        activa: true,
        dolarPrecios: 'USD:oficial',
        ladoPrecios: 'VENTA',
        visibles: ['USD:oficial', 'EUR:oficial'],
        horaRecalculo: '08:00',
        umbralDesvio: 5,
      },
    ]);
  });

  it('las visibles se mandan en el orden del catálogo, aunque se hayan marcado en otro orden', async () => {
    await crear({ ...configuracion, visibles: ['USD:oficial'] });

    await marcar(casilla('BRL:oficial'), true);
    await marcar(casilla('USD:tarjeta'), true);
    await enviar();

    expect(guardados[0].visibles).toEqual(['USD:oficial', 'USD:tarjeta', 'BRL:oficial']);
  });

  it('sin ninguna cotización para mostrar no guarda y lo explica junto al grupo', async () => {
    await crear({ ...configuracion, visibles: ['USD:oficial'] });
    await marcar(casilla('USD:oficial'), false);

    await enviar();

    expect(guardados).toEqual([]);
    const error = q('#visibles-error');
    expect(texto(error)).toBe('Elegí al menos una cotización para mostrar.');
    expect(q('fieldset.visibles').getAttribute('aria-describedby')).toContain('visibles-error');
    expect(q('fieldset.visibles').getAttribute('aria-invalid')).toBe('true');
  });

  it('sin hora no guarda y lo explica en el campo', async () => {
    await crear();
    await escribir(q<HTMLInputElement>('#horaRecalculo'), '');

    await enviar();

    expect(guardados).toEqual([]);
    expect(texto(q('#horaRecalculo-error'))).toBe('Indicá la hora del recálculo, por ejemplo 08:00.');
    expect(q('#horaRecalculo').getAttribute('aria-invalid')).toBe('true');
  });

  describe('hora del recálculo en 24 h', () => {
    it('es un campo de texto que muestra la hora como en Argentina, sin AM ni PM', async () => {
      await crear({ ...configuracion, horaRecalculo: '20:30' });

      const hora = q<HTMLInputElement>('#horaRecalculo');
      expect(hora.type).toBe('text');
      expect(hora.getAttribute('inputmode')).toBe('numeric');
      expect(hora.getAttribute('placeholder')).toBe('08:00');
      expect(hora.value).toBe('20:30');
    });

    it('acepta la hora con un solo dígito y la manda con dos', async () => {
      await crear();
      await escribir(q<HTMLInputElement>('#horaRecalculo'), '8:15');

      await enviar();

      expect(guardados[0].horaRecalculo).toBe('08:15');
    });

    it('acepta de 00:00 a 23:59', async () => {
      for (const [escrita, guardada] of [['00:00', '00:00'], ['23:59', '23:59'], ['0:05', '00:05']]) {
        await crear();
        await escribir(q<HTMLInputElement>('#horaRecalculo'), escrita);

        await enviar();

        expect(guardados[0]?.horaRecalculo, escrita).toBe(guardada);
        TestBed.resetTestingModule();
      }
    });

    it('una hora que no existe no guarda y muestra el mismo error', async () => {
      for (const invalida of ['25:00', '24:00', '8 AM', '08:60', '0800']) {
        await crear();
        await escribir(q<HTMLInputElement>('#horaRecalculo'), invalida);

        await enviar();

        expect(guardados, invalida).toEqual([]);
        expect(texto(q('#horaRecalculo-error'))).toBe('Indicá la hora del recálculo, por ejemplo 08:00.');
        expect(q('#horaRecalculo').getAttribute('aria-invalid')).toBe('true');
        TestBed.resetTestingModule();
      }
    });
  });

  it('cada lado explica cuál es, sin hablar del «vendedor»', async () => {
    await crear();

    expect(texto(q('label[for="lado-VENTA"]'))).toBe(
      'Venta: el valor más alto, el que pagás si comprás dólares (recomendado)',
    );
    expect(texto(q('label[for="lado-COMPRA"]'))).toBe('Compra: el valor más bajo, el que te pagan si vendés dólares');
    expect(el().querySelector('#lado-pista')).toBeNull();
    expect(el().textContent).not.toContain('vendedor');
    expect(q('fieldset.lado').hasAttribute('aria-describedby')).toBe(false);
  });

  describe('aviso del dólar', () => {
    it('muestra el porcentaje guardado y explica qué hace', async () => {
      await crear({ ...configuracion, umbralDesvio: 7.5 });

      expect(q<HTMLInputElement>('#umbralDesvio').value).toBe('7.5');
      expect(texto(q('label[for="umbralDesvio"]'))).toBe('Avisar si el dólar cambia más de (%)');
      expect(texto(q('#umbralDesvio-pista'))).toBe(
        'Si el dólar nuevo se aleja más de este porcentaje del anterior, los precios no cambian hasta que lo confirmes.',
      );
    });

    it('guardar emite el porcentaje editado', async () => {
      await crear();

      await escribir(q<HTMLInputElement>('#umbralDesvio'), '8');
      await enviar();

      expect(guardados[0].umbralDesvio).toBe(8);
    });

    for (const invalido of ['', '0.5', '60']) {
      it(`con «${invalido}» no guarda y lo explica en el campo`, async () => {
        await crear();
        await escribir(q<HTMLInputElement>('#umbralDesvio'), invalido);

        await enviar();

        expect(guardados).toEqual([]);
        expect(texto(q('#umbralDesvio-error'))).toBe('El aviso del dólar tiene que estar entre 1 % y 50 %.');
        expect(q('#umbralDesvio').getAttribute('aria-invalid')).toBe('true');
      });
    }
  });

  it('el error de visibles desaparece al marcar una', async () => {
    await crear({ ...configuracion, visibles: ['USD:oficial'] });
    await marcar(casilla('USD:oficial'), false);
    await enviar();
    expect(el().querySelector('#visibles-error')).not.toBeNull();

    await marcar(casilla('USD:blue'), true);

    expect(el().querySelector('#visibles-error')).toBeNull();
  });

  it('cada control tiene su etiqueta', async () => {
    await crear();

    expect(texto(q('label[for="activa"]'))).toBe('Cotización activada');
    expect(texto(q('label[for="dolarPrecios"]'))).toBe('Dólar en el que cotizan los precios');
    expect(texto(q('label[for="horaRecalculo"]'))).toBe('Hora del recálculo diario');
    expect(texto(q('fieldset.lado legend'))).toBe('Valor que usan los precios');
    expect(texto(q('label[for="lado-VENTA"]'))).toContain('Venta');
    expect(texto(q('label[for="lado-COMPRA"]'))).toContain('Compra');
    expect(texto(q('fieldset.visibles legend'))).toBe('Qué cotizaciones se muestran');
    expect(texto(q('label[for="visible-USD:blue"]'))).toBe('Dólar blue');
  });

  it('avisa si hay cambios sin guardar y deja de avisar cuando llega la configuración guardada', async () => {
    await crear();
    expect(cambios).toEqual([]);

    await marcar(casilla('USD:blue'), true);
    expect(cambios.at(-1)).toBe(true);

    fixture.componentRef.setInput('configuracion', { ...configuracion, visibles: ['USD:oficial', 'USD:blue', 'EUR:oficial'] });
    await refrescar();

    expect(cambios.at(-1)).toBe(false);
    expect(casilla('USD:blue').checked).toBe(true);
  });

  it('mientras guarda, el botón queda deshabilitado', async () => {
    await crear(configuracion, true);

    expect(q<HTMLButtonElement>('button[type="submit"]').disabled).toBe(true);
  });

  it('el botón dice «Guardar» y está habilitado si no se está guardando', async () => {
    await crear();

    const boton = q<HTMLButtonElement>('button[type="submit"]');
    expect(texto(boton)).toBe('Guardar');
    expect(boton.disabled).toBe(false);
  });
});
