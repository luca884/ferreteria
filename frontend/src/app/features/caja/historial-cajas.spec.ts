import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { CajaCerrada, DetalleCaja } from './caja.models';
import { CajaService } from './caja.service';
import { HistorialCajas } from './historial-cajas';

registerLocaleData(localeEsAr);

describe('HistorialCajas (CU-10)', () => {
  const cerrada = (id: number, diferencia: number): CajaCerrada => ({
    id,
    fechaApertura: '2026-10-15T08:00:00',
    fechaCierre: '2026-10-15T20:00:00',
    abiertaPor: 'Ana',
    cerradaPor: 'Beto',
    efectivoInicial: 1000,
    diferenciaApertura: null,
    efectivoEsperado: 5000,
    efectivoContado: 5000 + diferencia,
    diferencia,
  });

  const detalle: DetalleCaja = {
    id: 8,
    estado: 'CERRADA',
    fechaApertura: '2026-10-15T08:00:00',
    abiertaPor: 'Ana',
    efectivoInicial: 1000,
    efectivoCierreAnterior: 1000,
    diferenciaApertura: 0,
    fechaCierre: '2026-10-15T20:00:00',
    cerradaPor: 'Beto',
    ventas: { EFECTIVO: 4000, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 },
    totalVentas: 4000,
    cobranzas: { EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 300, CUENTA_CORRIENTE: 0 },
    totalCobranzas: 1000,
    ingresos: 0,
    gastos: 0,
    retiros: 0,
    efectivoEsperado: 5000,
    efectivoContado: 5100,
    diferencia: 100,
    movimientos: [{ id: 1, tipo: 'GASTO', importe: 50, motivo: 'Luz', fecha: '2026-10-15T12:00:00', usuario: 'Ana' }],
  };

  let fixture: ComponentFixture<HistorialCajas>;
  let servicio: Record<'historial' | 'detalle', ReturnType<typeof vi.fn>>;

  async function crear(dueno: boolean, totalPages = 1) {
    servicio = {
      historial: vi.fn(() =>
        of({ items: [cerrada(8, 100), cerrada(7, -20)], page: 0, size: 20, totalElements: 2, totalPages }),
      ),
      detalle: vi.fn(() => of(detalle)),
    };
    await TestBed.configureTestingModule({
      imports: [HistorialCajas],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: CajaService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno), sesion: signal({ ferreteriaNombre: 'Ferretería El Tornillo' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(HistorialCajas);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();

  /**
   * Las reglas del CSS que mencionan `selector` y la consulta media en la que viven (`''` es la base). El
   * `_ngcontent-…` que Angular le agrega a cada selector al compilar el CSS del componente se saca, así se busca el
   * selector tal como está escrito en la hoja. Vitest no evalúa media queries: esto es lo que deja verlas.
   */
  function reglasDe(selector: string): { consulta: string; estilo: CSSStyleDeclaration }[] {
    const encontradas: { consulta: string; estilo: CSSStyleDeclaration }[] = [];
    const recorrer = (reglas: CSSRuleList, consulta: string) => {
      Array.from(reglas).forEach((regla) => {
        const media = (regla as CSSMediaRule).media;
        const hijas = (regla as CSSMediaRule).cssRules;
        if (media && hijas) {
          recorrer(hijas, [consulta, media.mediaText].filter(Boolean).join(' '));
          return;
        }
        const selectorText = (regla as CSSStyleRule).selectorText?.replace(/\[_ng(content|host)-[^\]]+\]/g, '');
        if (selectorText?.includes(selector)) encontradas.push({ consulta, estilo: (regla as CSSStyleRule).style });
      });
    };
    Array.from(document.styleSheets).forEach((hoja) => {
      try {
        recorrer(hoja.cssRules, '');
      } catch {
        // Una hoja que no se deja leer no es la del historial.
      }
    });
    return encontradas;
  }

  it('el dueño ve las cajas cerradas con su diferencia', async () => {
    await crear(true);

    expect(servicio.historial).toHaveBeenCalledWith(0, 20);
    const filas = Array.from(el().querySelectorAll('tbody tr'));
    expect(filas).toHaveLength(2);
    expect(normal(filas[0].textContent)).toContain('Sobran $ 100,00');
    expect(normal(filas[1].textContent)).toContain('Faltan $ 20,00');
  });

  it('el paginador va arriba de la tabla: no hay que bajar hasta el final para pasar de página', async () => {
    await crear(true, 2);

    const paginador = el().querySelector('nav.paginas')!;
    const tabla = el().querySelector('table.cajas')!;
    expect(paginador.compareDocumentPosition(tabla) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('al elegir una caja muestra su detalle con los movimientos y quién la abrió y cerró', async () => {
    await crear(true);

    (el().querySelector('tbody tr') as HTMLElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(servicio.detalle).toHaveBeenCalledWith(8);
    const resumen = normal(el().querySelector('app-resumen-caja')?.textContent);
    expect(resumen).toContain('Luz');
    expect(resumen).toContain('Abrió Ana');
    expect(resumen).toContain('Cerró Beto');
  });

  it('el detalle de una caja cerrada muestra sus cobranzas por medio (CC-053)', async () => {
    await crear(true);

    (el().querySelector('tbody tr') as HTMLElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const resumen = normal(el().querySelector('app-resumen-caja')?.textContent);
    expect(resumen).toContain('Cobranzas en efectivo $ 700,00');
    expect(resumen).toContain('Cobranzas por transferencia $ 300,00');
  });

  describe('el detalle en un pop-up (#73, #74)', () => {
    const popup = () => el().querySelector<HTMLElement>('app-dialogo-detalle [role="dialog"]');

    async function refrescar() {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    }

    async function abrir(indice = 0) {
      el().querySelectorAll<HTMLButtonElement>('table.cajas tbody tr button')[indice].click();
      await refrescar();
    }

    it('«Ver detalle» abre el resumen en un pop-up encima de la lista, titulado con la apertura de la caja', async () => {
      await crear(true);
      const apertura = normal(el().querySelector('tbody tr td')?.textContent);

      await abrir(0);

      const pop = popup()!;
      expect(pop.getAttribute('aria-modal')).toBe('true');
      expect(pop.closest('.dialogo-fondo')).not.toBeNull();
      expect(normal(pop.querySelector('.dialogo-detalle-cabecera h2')?.textContent)).toBe(`Caja del ${apertura}`);
      expect(pop.querySelector('.dialogo-detalle-cuerpo app-resumen-caja .caja-imprimible')).not.toBeNull();
      expect(el().querySelectorAll('table.cajas tbody tr')).toHaveLength(2);
      expect(document.activeElement).toBe(pop.querySelector('button[aria-label="Cerrar"]'));
    });

    it('«Imprimir» va al pie como principal (el resumen ya no lleva el suyo) e imprime la hoja', async () => {
      await crear(true);
      await abrir(0);
      const imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);

      const pop = popup()!;
      expect(pop.querySelector('app-resumen-caja button.imprimir')).toBeNull();
      const boton = pop.querySelector<HTMLButtonElement>('.dialogo-detalle-pie .pie-acciones button.primario')!;
      expect(normal(boton.textContent)).toBe('Imprimir');
      boton.click();

      expect(imprimir).toHaveBeenCalledOnce();
      imprimir.mockRestore();
    });

    it('Esc y la ✕ cierran el pop-up', async () => {
      await crear(true);
      await abrir(0);

      (document.activeElement ?? document.body).dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );
      await refrescar();
      expect(popup()).toBeNull();

      await abrir(1);
      popup()!.querySelector<HTMLButtonElement>('button[aria-label="Cerrar"]')!.click();
      await refrescar();
      expect(popup()).toBeNull();
      expect(el().querySelector('app-resumen-caja')).toBeNull();
    });

    it('mientras carga dice «Cargando…» y, si el detalle no se pudo traer, avisa adentro del pop-up', async () => {
      await crear(true);
      servicio.detalle.mockReturnValue(new Subject<DetalleCaja>());
      await abrir(0);
      expect(normal(popup()!.querySelector('.dialogo-detalle-cuerpo')?.textContent)).toBe('Cargando…');

      servicio.detalle.mockReturnValue(throwError(() => new Error('caída')));
      popup()!.querySelector<HTMLButtonElement>('button[aria-label="Cerrar"]')!.click();
      await refrescar();
      await abrir(1);

      expect(normal(popup()!.querySelector('.dialogo-detalle-cuerpo [role="alert"]')?.textContent)).toBe(
        'No se pudo cargar el detalle de la caja.',
      );
      expect(popup()!.querySelector('.dialogo-detalle-pie .pie-acciones button.primario')).toBeNull();
    });
  });

  /*
   * Responsive, S4/T6: en el celular cada caja es una tarjeta apilada con la fecha adelante, sus importes con etiqueta
   * y el botón del detalle en el tamaño del dedo. Vitest no evalúa media queries, así que el spec vigila el contrato de
   * datos que la dibuja y que la regla de la tarjeta viva sólo en la pantalla del celular (la tabla ancha no cambia).
   */
  describe('la fila apilada del celular (T6)', () => {
    it('cada celda lleva el nombre de su columna en data-etiqueta y la apertura va primero', async () => {
      await crear(true);

      const cabeceras = Array.from(el().querySelectorAll('thead th')).map((th) => normal(th.textContent));
      expect(el().querySelector('table.cajas')!.classList).toContain('tabla-tarjetas');
      const filas = Array.from(el().querySelectorAll('tbody tr'));
      for (const fila of filas) {
        expect(Array.from(fila.querySelectorAll('td')).map((td) => td.getAttribute('data-etiqueta'))).toEqual(cabeceras);
      }

      // La fecha con la que se identifica la caja, y los importes que se deciden en la tarjeta.
      const primera = filas[0];
      expect(primera.querySelector('td')!.classList).toContain('apertura');
      expect(normal(primera.querySelector('td.apertura')!.textContent)).toBe('15/10 08:00');
      expect(normal(primera.querySelector('td.esperado')!.textContent)).toContain('$ 5.000,00');
      expect(normal(primera.querySelector('td.contado')!.textContent)).toContain('$ 5.100,00');
      expect(normal(primera.querySelector('td.diferencia')!.textContent)).toBe('Sobran $ 100,00');
    });

    it('el botón del detalle mide 44×44 con anillo de foco y la tarjeta vive sólo en la pantalla del celular', async () => {
      await crear(true);

      const tarjeta = reglasDe('.tabla-tarjetas');
      expect(tarjeta.length).toBeGreaterThan(0);
      expect(tarjeta.some((regla) => regla.estilo.getPropertyValue('display') === 'grid')).toBe(true);
      // Ninguna regla de la tarjeta queda fuera del celular: ni en la base (tablet y escritorio) ni en papel.
      for (const regla of tarjeta) {
        expect(regla.consulta).toContain('max-width: 639.98px');
        expect(regla.consulta).not.toContain('print');
      }

      // El botón que abre el detalle, con el alto y el ancho del dedo y el anillo de foco del proyecto.
      const boton = reglasDe('button.detalle');
      expect(boton.some((regla) => regla.estilo.getPropertyValue('min-width') === '44px')).toBe(true);
      expect(boton.some((regla) => regla.estilo.getPropertyValue('min-height') === '44px')).toBe(true);
      const foco = reglasDe('button.detalle:focus-visible');
      expect(foco).toHaveLength(1);
      expect(foco[0].consulta).toContain('max-width: 639.98px');
      expect(foco[0].estilo.getPropertyValue('outline')).not.toBe('');

      // La fecha y el botón son el título y la acción de la tarjeta: no repiten el nombre de la columna.
      const sinEtiqueta = reglasDe('td:is(.apertura, .accion)::before');
      expect(sinEtiqueta).toHaveLength(1);
      expect(sinEtiqueta[0].estilo.getPropertyValue('content')).toBe('none');

      // Y sigue en la fila, donde estaba: la tarjeta no pierde ninguna columna.
      expect(el().querySelectorAll('table.cajas tbody tr button.detalle')).toHaveLength(2);
    });
  });

  it('el vendedor ve un aviso y no se pide el historial', async () => {
    await crear(false);

    expect(servicio.historial).not.toHaveBeenCalled();
    expect(normal(el().textContent)).toContain('El historial de cajas es solo del dueño.');
  });
});
