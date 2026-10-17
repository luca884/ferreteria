import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Cotizacion, TipoCotizacion } from '../core/cotizacion/cotizacion.models';
import { CotizacionLateral } from './cotizacion-lateral';

describe('CotizacionLateral', () => {
  let fixture: ComponentFixture<CotizacionLateral>;

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

  async function crear(valor: Cotizacion | null, esDueno = false): Promise<HTMLElement> {
    await TestBed.configureTestingModule({ imports: [CotizacionLateral], providers: [provideRouter([])] }).compileComponents();
    fixture = TestBed.createComponent(CotizacionLateral);
    fixture.componentRef.setInput('cotizacion', valor);
    fixture.componentRef.setInput('esDueno', esDueno);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const texto = (e: Element | null) => e?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const lineas = (el: HTMLElement) => Array.from(el.querySelectorAll('.linea')).map((l) => texto(l));

  it('muestra «Oficial venta 1.000,00» y la fecha y hora del valor «16/10 08:00»', async () => {
    const el = await crear(cotizacion());

    expect(lineas(el)).toContain('Oficial venta 1.000,00 Precios');
    expect(texto(el.querySelector('.fecha'))).toBe('16/10 08:00');
  });

  describe('dólar a confirmar (H09)', () => {
    const aConfirmar = () =>
      cotizacion({
        visibles: [{ ...oficial, venta: 14.5 }],
        precios: { codigo: 'USD:oficial', lado: 'VENTA', valor: 1535, desde: '2026-10-16T11:00:00Z', aConfirmar: 14.5 },
      });

    it('la línea de los precios muestra el dólar aplicado, no el crudo, y marca el nuevo «a confirmar»', async () => {
      const el = await crear(aConfirmar());

      expect(lineas(el)).toContain('Oficial venta 1.535,00 Precios 14,50 a confirmar');
    });

    it('la otra línea del mismo tipo sigue con su valor crudo y sin marca', async () => {
      const el = await crear(aConfirmar());

      expect(lineas(el)).toContain('Oficial compra 950,00');
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

  it('muestra la compra y la venta de cada tipo', async () => {
    const el = await crear(cotizacion());

    expect(lineas(el)).toEqual(['Oficial compra 950,00', 'Oficial venta 1.000,00 Precios']);
  });

  it('marca el lado del dólar de los precios: con compra, la marca va en la compra', async () => {
    const el = await crear(
      cotizacion({ precios: { codigo: 'USD:oficial', lado: 'COMPRA', valor: 950, desde: null, aConfirmar: null } }),
    );

    expect(lineas(el)).toEqual(['Oficial compra 950,00 Precios', 'Oficial venta 1.000,00']);
    expect(el.querySelectorAll('.marca').length).toBe(1);
  });

  it('muestra siempre la fecha y la hora de cada valor, en cada tipo', async () => {
    const el = await crear(cotizacion({ visibles: [oficial, euro] }));

    expect(Array.from(el.querySelectorAll('.fecha')).map((f) => texto(f))).toEqual(['16/10 08:00', '16/10 09:00']);
  });

  it('muestra euro y real si son visibles, sin marca de dólar de precios', async () => {
    const real: TipoCotizacion = { ...euro, codigo: 'BRL:oficial', nombre: 'Real', compra: 190, venta: 200 };
    const el = await crear(cotizacion({ visibles: [oficial, euro, real] }));

    expect(lineas(el)).toEqual([
      'Oficial compra 950,00',
      'Oficial venta 1.000,00 Precios',
      'Euro compra 1.080,00',
      'Euro venta 1.130,50',
      'Real compra 190,00',
      'Real venta 200,00',
    ]);
    expect(el.querySelectorAll('.marca').length).toBe(1);
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

    expect(texto(el)).toContain('Euro');
    expect(texto(el)).toContain('Todavía no hay datos');
    expect(el.querySelector('.fecha')).toBeNull();
    expect(el.querySelector('.linea')).toBeNull();
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

      expect(texto(el.querySelector('.fecha'))).toBe('09/10 19:00');
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

  describe('enlace «Configurar»', () => {
    it('el Dueño lo ve y lleva a la configuración de cotización', async () => {
      const el = await crear(cotizacion(), true);

      const enlace = el.querySelector('a');
      expect(texto(enlace)).toBe('Configurar');
      expect(enlace!.getAttribute('href')).toBe('/configuracion/cotizacion');
    });

    it('el Vendedor ve la cotización pero no el enlace', async () => {
      const el = await crear(cotizacion(), false);

      expect(lineas(el)).toContain('Oficial venta 1.000,00 Precios');
      expect(el.querySelector('a')).toBeNull();
    });
  });

  it('sin respuesta todavía dice que no hay datos por ahora', async () => {
    const el = await crear(null);

    expect(texto(el)).toContain('Sin datos de cotización por ahora.');
    expect(el.querySelector('.linea')).toBeNull();
  });

  it('el bloque tiene un título «Cotización» y es una región con nombre', async () => {
    const el = await crear(cotizacion());

    const region = el.querySelector('section')!;
    const titulo = el.querySelector('h2')!;
    expect(texto(titulo)).toBe('Cotización');
    expect(region.getAttribute('aria-labelledby')).toBe(titulo.id);
  });
});
