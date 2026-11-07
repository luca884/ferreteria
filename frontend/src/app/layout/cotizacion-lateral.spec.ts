import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Cotizacion, TipoCotizacion } from '../core/cotizacion/cotizacion.models';
import { CotizacionLateral } from './cotizacion-lateral';

describe('CotizacionLateral', () => {
  let fixture: ComponentFixture<CotizacionLateral>;
  /** Donde el navegador recuerda si el bloque quedó plegado. */
  const CLAVE = 'cotizacion-lateral-abierta';

  const oficial: TipoCotizacion = {
    codigo: 'USD:oficial',
    nombre: 'Oficial',
    compra: 950,
    venta: 1000,
    fuente: 'DOLARAPI',
    // 11:00 UTC = 08:00 de Buenos Aires.
    actualizadaEn: '2026-10-16T11:00:00Z',
    obtenidaEn: '2026-10-16T12:30:00Z',
    desactualizada: false,
    esDePrecios: true,
  };

  const euro: TipoCotizacion = {
    codigo: 'EUR:oficial',
    nombre: 'Euro',
    compra: 1080,
    venta: 1130.5,
    fuente: 'DOLARAPI',
    actualizadaEn: '2026-10-16T12:00:00Z',
    obtenidaEn: '2026-10-16T12:30:00Z',
    desactualizada: false,
    esDePrecios: false,
  };

  const cotizacion = (cambios: Partial<Cotizacion> = {}): Cotizacion => ({
    activa: true,
    desactualizada: false,
    ultimaRespuesta: '2026-10-16T12:30:00Z',
    visibles: [oficial],
    precios: { codigo: 'USD:oficial', lado: 'VENTA', valor: 1000, desde: '2026-10-16T11:00:00Z', aConfirmar: null },
    ...cambios,
  });

  beforeEach(() => localStorage.removeItem(CLAVE));
  afterEach(() => localStorage.removeItem(CLAVE));

  async function crear(valor: Cotizacion | null, esDueno = false): Promise<HTMLElement> {
    await TestBed.configureTestingModule({ imports: [CotizacionLateral], providers: [provideRouter([])] }).compileComponents();
    fixture = TestBed.createComponent(CotizacionLateral);
    fixture.componentRef.setInput('cotizacion', valor);
    fixture.componentRef.setInput('esDueno', esDueno);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const texto = (e: Element | null) => e?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const resumen = (el: HTMLElement) => texto(el.querySelector('.resumen'));
  const filas = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('table.cotizaciones tbody tr.tipo')).map((tr) =>
      Array.from(tr.querySelectorAll('th, td')).map((c) => texto(c)),
    );
  const desplegar = (el: HTMLElement) => el.querySelector<HTMLButtonElement>('button.desplegar')!;

  describe('el dólar de los precios, arriba', () => {
    it('muestra «Dólar de los precios» con el tipo, el lado y el valor', async () => {
      const el = await crear(cotizacion());

      expect(resumen(el)).toBe('Dólar de los precios Oficial venta 1.000,00');
    });

    it('con el lado compra, dice compra y su valor', async () => {
      const el = await crear(
        cotizacion({ precios: { codigo: 'USD:oficial', lado: 'COMPRA', valor: 950, desde: null, aConfirmar: null } }),
      );

      expect(resumen(el)).toBe('Dólar de los precios Oficial compra 950,00');
    });

    it('si el dólar de los precios no está entre los visibles, lo nombra igual', async () => {
      const el = await crear(cotizacion({ visibles: [euro] }));

      expect(resumen(el)).toBe('Dólar de los precios Dólar venta 1.000,00');
    });

    it('sin dólar aplicado todavía no muestra el resumen', async () => {
      const el = await crear(
        cotizacion({ precios: { codigo: 'USD:oficial', lado: 'VENTA', valor: null, desde: null, aConfirmar: null } }),
      );

      expect(el.querySelector('.resumen')).toBeNull();
    });
  });

  describe('la tabla de las cotizaciones', () => {
    it('arranca desplegada: una línea por tipo con la compra y la venta', async () => {
      const el = await crear(cotizacion({ visibles: [oficial, euro] }));

      const encabezados = Array.from(el.querySelectorAll('table.cotizaciones thead th')).map((t) => texto(t));
      expect(encabezados).toEqual(['Moneda', 'Compra', 'Venta']);
      expect(filas(el)).toEqual([
        ['Oficial', '950,00', '1.000,00'],
        ['Euro', '1.080,00', '1.130,50'],
      ]);
      expect(desplegar(el).getAttribute('aria-expanded')).toBe('true');
      expect(texto(desplegar(el))).toBe('Ocultar las cotizaciones');
    });

    it('resalta el valor que usan los precios, y solo ese: con compra, la compra', async () => {
      const el = await crear(
        cotizacion({
          visibles: [oficial, euro],
          precios: { codigo: 'USD:oficial', lado: 'COMPRA', valor: 950, desde: null, aConfirmar: null },
        }),
      );

      const resaltados = Array.from(el.querySelectorAll('table.cotizaciones .de-precios')).map((c) => texto(c));
      expect(resaltados).toEqual(['950,00']);
    });

    it('muestra euro y real si son visibles, sin resaltarlos', async () => {
      const real: TipoCotizacion = { ...euro, codigo: 'BRL:oficial', nombre: 'Real', compra: 190, venta: 200 };
      const el = await crear(cotizacion({ visibles: [oficial, euro, real] }));

      expect(filas(el)).toEqual([
        ['Oficial', '950,00', '1.000,00'],
        ['Euro', '1.080,00', '1.130,50'],
        ['Real', '190,00', '200,00'],
      ]);
      expect(el.querySelectorAll('table.cotizaciones .de-precios').length).toBe(1);
    });

    it('una sola fecha, la más reciente, rotulada; la de cada tipo, al pasar el mouse por su línea', async () => {
      const el = await crear(cotizacion({ visibles: [oficial, euro] }));

      expect(Array.from(el.querySelectorAll('.fecha')).map((f) => texto(f))).toEqual(['Cotización del 16/10 09:00']);
      const titulos = Array.from(el.querySelectorAll('table.cotizaciones tbody tr.tipo')).map((tr) =>
        tr.getAttribute('title'),
      );
      expect(titulos).toEqual(['Oficial: cotización del 16/10 08:00', 'Euro: cotización del 16/10 09:00']);
    });

    it('un tipo sin valor guardado dice que todavía no hay datos, sin fecha', async () => {
      const sinDatos: TipoCotizacion = {
        ...euro,
        compra: null,
        venta: null,
        fuente: null,
        actualizadaEn: null,
        obtenidaEn: null,
      };
      const el = await crear(cotizacion({ visibles: [sinDatos] }));

      expect(filas(el)).toEqual([['Euro', 'Todavía no hay datos']]);
      expect(el.querySelector('.fecha')).toBeNull();
    });

    it('avisa la antigüedad de un tipo que ninguna fuente renovó, aunque el resto esté fresco (R-029)', async () => {
      const mep: TipoCotizacion = {
        ...oficial,
        codigo: 'USD:bolsa',
        nombre: 'MEP',
        compra: 1040,
        venta: 1050,
        // 10:00 UTC = 07:00 de Buenos Aires.
        obtenidaEn: '2026-10-16T10:00:00Z',
        desactualizada: true,
        esDePrecios: false,
      };
      const el = await crear(cotizacion({ visibles: [oficial, mep] }));

      const viejos = Array.from(el.querySelectorAll('.vieja')).map((v) => texto(v));
      expect(viejos).toEqual(['No se renueva desde el 16/10 07:00']);
    });
  });

  describe('plegar y desplegar', () => {
    it('al plegarla quedan el dólar de los precios y el botón para verlas; al desplegarla vuelve la tabla', async () => {
      const el = await crear(cotizacion({ visibles: [oficial, euro] }));

      desplegar(el).click();
      await refrescar();

      expect(desplegar(el).getAttribute('aria-expanded')).toBe('false');
      expect(texto(desplegar(el))).toBe('Ver las 2 cotizaciones');
      expect(el.querySelector('table.cotizaciones')).toBeNull();
      expect(el.querySelector('.fecha')).toBeNull();
      expect(resumen(el)).toBe('Dólar de los precios Oficial venta 1.000,00');

      desplegar(el).click();
      await refrescar();

      expect(desplegar(el).getAttribute('aria-expanded')).toBe('true');
      expect(filas(el)).toHaveLength(2);
    });

    it('el botón controla la tabla', async () => {
      const el = await crear(cotizacion());

      const id = desplegar(el).getAttribute('aria-controls');
      expect(id).toBeTruthy();
      expect(el.querySelector(`[id="${id}"]`)?.contains(el.querySelector('table.cotizaciones'))).toBe(true);
    });

    it('con un solo tipo, plegada dice «Ver la cotización»', async () => {
      const el = await crear(cotizacion());

      desplegar(el).click();
      await refrescar();

      expect(texto(desplegar(el))).toBe('Ver la cotización');
    });

    it('el navegador recuerda si quedó plegada', async () => {
      const el = await crear(cotizacion());

      desplegar(el).click();
      await refrescar();
      expect(localStorage.getItem(CLAVE)).toBe('false');

      desplegar(el).click();
      await refrescar();
      expect(localStorage.getItem(CLAVE)).toBe('true');
    });

    it('si la dejaste plegada, arranca plegada', async () => {
      localStorage.setItem(CLAVE, 'false');

      const el = await crear(cotizacion());

      expect(desplegar(el).getAttribute('aria-expanded')).toBe('false');
      expect(el.querySelector('table.cotizaciones')).toBeNull();
    });
  });

  describe('dólar a confirmar (H09)', () => {
    const aConfirmar = () =>
      cotizacion({
        visibles: [{ ...oficial, venta: 14.5 }],
        precios: { codigo: 'USD:oficial', lado: 'VENTA', valor: 1535, desde: '2026-10-16T11:00:00Z', aConfirmar: 14.5 },
      });

    it('el dólar de los precios muestra el aplicado, no el crudo, y marca el nuevo «a confirmar»', async () => {
      const el = await crear(aConfirmar());

      expect(resumen(el)).toBe('Dólar de los precios Oficial venta 1.535,00 14,50 a confirmar');
      expect(texto(el.querySelector('table.cotizaciones .de-precios'))).toBe('1.535,00');
    });

    it('el otro lado del mismo tipo sigue con su valor crudo', async () => {
      const el = await crear(aConfirmar());

      expect(filas(el)).toEqual([['Oficial', '950,00', '1.535,00']]);
    });

    it('sin valor a confirmar no hay marca', async () => {
      const el = await crear(cotizacion());

      expect(el.querySelector('.a-confirmar')).toBeNull();
    });

    it('el vendedor ve la marca pero no el porcentaje ni un botón para confirmar', async () => {
      const el = await crear(aConfirmar(), false);

      expect(texto(el.querySelector('.a-confirmar'))).toBe('14,50 a confirmar');
      expect(el.textContent).not.toContain('%');
      expect(el.querySelector('.a-confirmar a, .a-confirmar button, a.a-confirmar')).toBeNull();
    });

    it('para el dueño la marca lleva a la pantalla de Cotización para confirmarlo', async () => {
      const el = await crear(aConfirmar(), true);

      const enlace = el.querySelector<HTMLAnchorElement>('a.a-confirmar');
      expect(enlace?.getAttribute('href')).toBe('/configuracion/cotizacion');
    });
  });

  describe('aviso amarillo de cotización desactualizada', () => {
    it('aparece solo si la respuesta trae desactualizada, con la fecha y hora de la cotización que se usa', async () => {
      const el = await crear(cotizacion({ desactualizada: true, ultimaRespuesta: '2026-10-16T11:00:00Z' }));

      const aviso = el.querySelector('.aviso.desactualizada');
      expect(texto(aviso)).toBe('No se pudo actualizar la cotización. Se usa la del 16/10 08:00');
    });

    it('con la cotización de mercado del viernes y respuestas de hoy no hay aviso', async () => {
      const viernes = { ...oficial, actualizadaEn: '2026-10-09T22:00:00Z' };
      const el = await crear(cotizacion({ visibles: [viernes], desactualizada: false }));

      expect(texto(el.querySelector('.fecha'))).toBe('Cotización del 09/10 19:00');
      expect(el.querySelector('.aviso.desactualizada')).toBeNull();
    });

    it('se anuncia a quien usa lector de pantalla: vive en una región aria-live', async () => {
      const el = await crear(cotizacion({ desactualizada: true }));

      const region = el.querySelector('[aria-live="polite"]');
      expect(region).not.toBeNull();
      expect(region!.querySelector('.aviso.desactualizada')).not.toBeNull();
    });

    it('la región aria-live existe aunque no haya aviso, para que el aviso que llegue después se anuncie', async () => {
      const el = await crear(cotizacion());

      expect(el.querySelector('[aria-live="polite"]')).not.toBeNull();
      expect(el.querySelector('.aviso')).toBeNull();
    });
  });

  describe('cotización apagada', () => {
    it('avisa que los artículos en USD no se actualizan', async () => {
      const el = await crear(cotizacion({ activa: false }));

      expect(texto(el.querySelector('.aviso.apagada'))).toBe(
        'La cotización está apagada. Los artículos en USD no se actualizan.',
      );
    });

    it('activada no avisa', async () => {
      const el = await crear(cotizacion({ activa: true }));

      expect(el.querySelector('.aviso.apagada')).toBeNull();
    });
  });

  describe('enlace «Configurar»', () => {
    it('el Dueño lo ve y lleva a la configuración de cotización', async () => {
      const el = await crear(cotizacion(), true);

      const enlace = el.querySelector('a');
      expect(texto(enlace)).toBe('Configurar');
      expect(enlace!.getAttribute('href')).toBe('/configuracion/cotizacion');
    });

    it('el Vendedor ve la cotización pero no el enlace', async () => {
      const el = await crear(cotizacion(), false);

      expect(resumen(el)).toBe('Dólar de los precios Oficial venta 1.000,00');
      expect(el.querySelector('a')).toBeNull();
    });
  });

  it('sin respuesta todavía dice que no hay datos por ahora', async () => {
    const el = await crear(null);

    expect(texto(el)).toContain('Sin datos de cotización por ahora.');
    expect(el.querySelector('table')).toBeNull();
    expect(el.querySelector('button.desplegar')).toBeNull();
  });

  it('el bloque tiene un título «Cotización» y es una región con nombre', async () => {
    const el = await crear(cotizacion());

    const region = el.querySelector('section')!;
    const titulo = el.querySelector('h2')!;
    expect(texto(titulo)).toBe('Cotización');
    expect(region.getAttribute('aria-labelledby')).toBe(titulo.id);
  });
});
