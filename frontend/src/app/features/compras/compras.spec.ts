import { HttpErrorResponse } from '@angular/common/http';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { CompraResponse, CompraResumen } from './compra.models';
import { Compras } from './compras';
import { ComprasService } from './compras.service';

registerLocaleData(localeEsAr);

describe('Compras', () => {
  let fixture: ComponentFixture<Compras>;
  let api: { listar: ReturnType<typeof vi.fn>; obtener: ReturnType<typeof vi.fn> };
  let proveedores: { listar: ReturnType<typeof vi.fn> };

  const resumen = (id: number, cambios: Partial<CompraResumen> = {}): CompraResumen => ({
    id,
    fecha: '2026-10-20',
    tipo: 'FA',
    comprobante: `Factura A 00003-0000000${id}`,
    proveedorId: 4,
    proveedorNombre: 'Bulonera Sur',
    moneda: 'ARS',
    neto: 2200,
    renglones: 2,
    ...cambios,
  });

  const pagina = (items: CompraResumen[], totalPages = 1): PageResponse<CompraResumen> => ({
    items,
    page: 0,
    size: 20,
    totalElements: items.length,
    totalPages,
  });

  const detalle = (id: number): CompraResponse => ({
    id,
    tipo: 'FA',
    puntoVenta: 3,
    numero: id,
    comprobante: `Factura A 00003-0000000${id}`,
    fecha: '2026-10-20',
    proveedorId: 4,
    proveedorNombre: 'Bulonera Sur',
    moneda: 'ARS',
    neto: 2200,
    registrada: '2026-10-21T10:15:00',
    recargos: [],
    renglones: [
      {
        renglon: 1,
        articuloId: 1,
        codigoInterno: 'TOR-1',
        descripcion: 'Tornillo 1/4',
        unidad: 'UNIDAD',
        cantidad: 20,
        costoUnitario: 110,
        subtotal: 2200,
        costoAnterior: 100,
        precioAnterior: 157.3,
        precioNuevo: 173.03,
      },
    ],
  });

  async function crear(
    items: CompraResumen[] = [resumen(2), resumen(1)],
    rol = 'DUENO',
    id: string | null = null,
    falla: number | null = null,
    origen: string | null = null,
  ) {
    api = {
      listar: vi.fn(() => of(pagina(items))),
      obtener: vi.fn((id: number) => (falla ? throwError(() => new HttpErrorResponse({ status: falla })) : of(detalle(id)))),
    };
    proveedores = { listar: vi.fn(() => of([{ id: 4, nombre: 'Bulonera Sur', cuit: null, activo: true }])) };
    const sesion = signal({ userId: 7, nombre: 'Ana', email: 'ana@x.com', rol, ferreteriaId: 3, ferreteriaNombre: 'Tornillo' });
    await TestBed.configureTestingModule({
      imports: [Compras],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(id === null ? {} : { id }) } } },
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ComprasService, useValue: api },
        { provide: ProveedoresService, useValue: proveedores },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly(), esDueno: () => rol === 'DUENO' } },
      ],
    }).compileComponents();
    // La navegación que abrió la pantalla desde el link de otro documento (bugs-relevamiento-celular, S7).
    if (origen) {
      vi.spyOn(TestBed.inject(Router), 'currentNavigation').mockReturnValue({ extras: { state: { origen } } } as never);
    }
    fixture = TestBed.createComponent(Compras);
    // Como en la app: cuando corre `ngOnInit` (el primer detectChanges), la navegación ya terminó.
    if (origen) vi.mocked(TestBed.inject(Router).currentNavigation).mockReturnValue(null);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;

  async function escribir(selector: string, valor: string) {
    const input = el().querySelector(selector) as HTMLInputElement | HTMLSelectElement;
    input.value = valor;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
    await refrescar();
  }

  it('al Vendedor le avisa que es del Dueño y no pide nada', async () => {
    await crear([], 'VENDEDOR');

    expect(el().textContent).toContain('Solo el Dueño puede ver las compras.');
    expect(api.listar).not.toHaveBeenCalled();
  });

  it('lista las compras con fecha, comprobante, proveedor, renglones y neto, y ofrece cargar una nueva', async () => {
    await crear();

    expect(api.listar).toHaveBeenCalledWith({ page: 0 });
    const filas = [...el().querySelectorAll('table.compras tbody tr')];
    expect(filas).toHaveLength(2);
    expect(filas[0].textContent).toContain('20/10/2026');
    expect(filas[0].textContent).toContain('Factura A 00003-00000002');
    expect(filas[0].textContent).toContain('Bulonera Sur');
    expect(filas[0].textContent).toContain('2.200,00');
    expect(el().querySelector('a[href="/compras/nueva"]')?.textContent).toContain('Nueva compra');
    // Botón de la barra, como «+ Nuevo» de Proveedores o Artículos (arreglos-compras, S3).
    expect([...el().querySelector('a[href="/compras/nueva"]')!.classList]).toEqual(
      expect.arrayContaining(['btn', 'accion-barra', 'primario']),
    );
  });

  it('filtra por proveedor y fechas, y limpiar vuelve a todas', async () => {
    await crear();
    await escribir('select[name=proveedor]', '4');
    await escribir('input[name=desde]', '2026-10-01');

    (el().querySelector('form.filtros') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await refrescar();
    expect(api.listar).toHaveBeenLastCalledWith({ page: 0, proveedorId: 4, desde: '2026-10-01' });

    [...el().querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Limpiar')!.click();
    await refrescar();
    expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
  });

  it('con «Hasta» antes de «Desde» avisa, no busca y enfoca «Hasta»; corregido, busca (bugs-relevamiento-celular, S4)', async () => {
    await crear();
    api.listar.mockClear();
    await escribir('input[name=desde]', '2026-10-22');
    await escribir('input[name=hasta]', '2026-10-21');

    (el().querySelector('form.filtros') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await refrescar();

    expect(api.listar).not.toHaveBeenCalled();
    expect(el().querySelector('[role="alert"]')?.textContent?.trim()).toBe('La fecha «Hasta» no puede ser anterior a «Desde».');
    expect(document.activeElement).toBe(el().querySelector('input[name="hasta"]'));

    await escribir('input[name=hasta]', '2026-10-22');
    (el().querySelector('form.filtros') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await refrescar();

    expect(api.listar).toHaveBeenLastCalledWith({ page: 0, desde: '2026-10-22', hasta: '2026-10-22' });
    expect(el().textContent).not.toContain('no puede ser anterior');
  });

  it('el paginador va arriba de la tabla: no hay que bajar hasta el final para pasar de página', async () => {
    await crear();
    api.listar.mockReturnValue(of(pagina([resumen(2), resumen(1)], 2)));
    (el().querySelector('form.filtros') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await refrescar();

    const paginador = el().querySelector('nav.paginas')!;
    const tabla = el().querySelector('table.compras')!;
    expect(paginador.compareDocumentPosition(tabla) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  // S4: la lista y el detalle ponen la tabla adentro de un marco que scrollea en horizontal, no la página.
  it('la lista y el detalle de la compra ponen la tabla dentro de su marco de scroll (responsive, S4)', async () => {
    await crear();

    expect(el().querySelector('table.compras')?.parentElement?.classList.contains('tabla-scroll')).toBe(true);

    (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
    await refrescar();

    const tablaDelDetalle = el().querySelector('app-dialogo-detalle table')!;
    expect(tablaDelDetalle.parentElement?.classList.contains('tabla-scroll')).toBe(true);
  });

  it('elegir una compra muestra sus renglones con el costo y el precio de antes y de después', async () => {
    await crear();

    (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
    await refrescar();

    expect(api.obtener).toHaveBeenCalledWith(2);
    const detalleTexto = el().querySelector('app-dialogo-detalle [role=dialog]')!.textContent!;
    expect(detalleTexto).toContain('Factura A 00003-00000002');
    expect(detalleTexto).toContain('Tornillo 1/4');
    expect(detalleTexto).toContain('100,00');
    expect(detalleTexto).toContain('110,00');
    expect(detalleTexto).toContain('157,30');
    expect(detalleTexto).toContain('173,03');
  });

  it('el detalle de la compra dice la cantidad con su unidad: «20 unidades», «20,5 kilos» (#36)', async () => {
    await crear();
    const conKilos = detalle(2);
    conKilos.renglones = [
      conKilos.renglones[0],
      { ...conKilos.renglones[0], renglon: 2, articuloId: 9, descripcion: 'Clavo punta París 2"', unidad: 'KILO', cantidad: 20.5 },
    ];
    api.obtener.mockReturnValue(of(conKilos));

    (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
    await refrescar();

    const cantidades = [...el().querySelectorAll('app-dialogo-detalle [role=dialog] tbody tr')].map((tr) =>
      tr.querySelectorAll('td')[1].textContent!.trim(),
    );
    expect(cantidades).toEqual(['20 unidades', '20,5 kilos']);
  });

  it('en el detalle, el costo y los precios dicen por qué unidad son; por pieza y el subtotal no llevan (#36)', async () => {
    await crear();
    const conKilos = detalle(2);
    conKilos.renglones = [
      conKilos.renglones[0],
      {
        ...conKilos.renglones[0],
        renglon: 2,
        articuloId: 9,
        descripcion: 'Clavo punta París 2"',
        unidad: 'KILO',
        cantidad: 20.5,
        costoAnterior: 3000,
        costoUnitario: 3300,
        precioAnterior: 4000,
        precioNuevo: 4400,
        subtotal: 67650,
      },
    ];
    api.obtener.mockReturnValue(of(conKilos));

    (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
    await refrescar();

    // Artículo, cantidad, costo antes, costo de la compra, precio antes, precio después y subtotal.
    const filas = [...el().querySelectorAll('app-dialogo-detalle [role=dialog] tbody tr')].map((tr) =>
      [...tr.querySelectorAll('td')].slice(2).map((td) => td.textContent!.replace(/\s+/g, ' ').trim()),
    );
    expect(filas).toEqual([
      ['$ 100,00', '$ 110,00', '$ 157,30', '$ 173,03', '$ 2.200,00'],
      ['$ 3.000,00 / kg', '$ 3.300,00 / kg', '$ 4.000,00 / kg', '$ 4.400,00 / kg', '$ 67.650,00'],
    ]);
  });

  it('sin compras avisa y si la carga falla ofrece reintentar', async () => {
    await crear([]);
    expect(el().textContent).toContain('Todavía no hay compras.');

    api.listar.mockReturnValueOnce(throwError(() => new Error('red')));
    (el().querySelector('form.filtros') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await refrescar();
    expect(el().querySelector('[role=alert]')?.textContent).toContain('No se pudieron cargar las compras.');
  });

  it('el detalle muestra los recargos de la compra con su porcentaje', async () => {
    await crear();
    api.obtener.mockReturnValue(of({ ...detalle(2), recargos: [{ nombre: 'Embalaje y carga', porcentaje: 4.44 }] }));

    (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
    await refrescar();

    expect(el().querySelector('app-dialogo-detalle [role=dialog] .recargos')!.textContent).toContain('Embalaje y carga 4,44 %');
  });

  it('con el parámetro id en la ruta abre el detalle de esa compra', async () => {
    await crear([resumen(2)], 'DUENO', '9');

    expect(api.obtener).toHaveBeenCalledWith(9);
    expect(el().querySelector('app-dialogo-detalle [role=dialog]')!.textContent).toContain('Factura A 00003-00000009');
  });

  it('con un id desconocido o de otra ferretería (404) dice que no se encontró la compra (QA 05/11)', async () => {
    await crear([resumen(2)], 'DUENO', '77', 404);

    expect(api.obtener).toHaveBeenCalledWith(77);
    expect(el().querySelector('app-dialogo-detalle [role=dialog] [role=alert]')!.textContent).toContain('No se encontró la compra.');
  });

  it('con otro error dice que no se pudo cargar la compra', async () => {
    await crear([resumen(2)], 'DUENO', '77', 500);

    expect(el().querySelector('app-dialogo-detalle [role=dialog] [role=alert]')!.textContent).toContain('No se pudo cargar la compra.');
  });

  describe('el detalle en un pop-up (detalle-en-popup)', () => {
    const popup = () => el().querySelector<HTMLElement>('app-dialogo-detalle [role="dialog"]');
    const escape = () =>
      (document.activeElement ?? document.body).dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );

    function espiarNavegacion() {
      return vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    }

    it('elegir una compra la abre en un pop-up encima de la lista, titulado con el comprobante y solo con Cerrar al pie', async () => {
      await crear();

      (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
      await refrescar();

      const pop = popup()!;
      expect(pop.getAttribute('aria-modal')).toBe('true');
      expect(pop.closest('.dialogo-fondo')).not.toBeNull();
      expect(pop.querySelector('.dialogo-detalle-cabecera h2')!.textContent!.trim()).toBe('Factura A 00003-00000002');
      expect(pop.querySelector('.dialogo-detalle-cuerpo table')).not.toBeNull();
      expect([...pop.querySelectorAll('.dialogo-detalle-pie button')].map((b) => b.textContent!.trim())).toEqual(['Cerrar']);
      expect(el().querySelectorAll('table.compras tbody tr')).toHaveLength(2);
      expect(document.activeElement).toBe(pop.querySelector('button[aria-label="Cerrar"]'));
    });

    it('mientras carga, el pop-up ya se titula con el comprobante de la fila', async () => {
      await crear();
      api.obtener.mockReturnValue(new Subject<CompraResponse>());

      (el().querySelectorAll('table.compras tbody tr button')[1] as HTMLButtonElement).click();
      await refrescar();

      expect(popup()!.querySelector('h2')!.textContent!.trim()).toBe('Factura A 00003-00000001');
      expect(popup()!.querySelector('.dialogo-detalle-cuerpo')!.textContent!.trim()).toBe('Cargando…');
    });

    it('abierta desde la lista, Esc y la ✕ la cierran sin cambiar de página', async () => {
      await crear();
      const navegar = espiarNavegacion();
      (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
      await refrescar();

      escape();
      await refrescar();
      expect(popup()).toBeNull();

      (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
      await refrescar();
      popup()!.querySelector<HTMLButtonElement>('button[aria-label="Cerrar"]')!.click();
      await refrescar();
      expect(popup()).toBeNull();
      expect(navegar).not.toHaveBeenCalled();
    });

    it('abierta por `/compras/:id` (la ficha del artículo), cerrarla vuelve a `/compras`', async () => {
      await crear([resumen(2)], 'DUENO', '9');
      const navegar = espiarNavegacion();
      expect(popup()).not.toBeNull();

      escape();
      await refrescar();

      expect(popup()).toBeNull();
      expect(navegar).toHaveBeenCalledWith('/compras');
    });

    it('abierta desde el link de la ficha del artículo, cerrarla vuelve a la ficha (bugs-relevamiento-celular, S7)', async () => {
      await crear([resumen(2)], 'DUENO', '9', null, '/articulos/15');
      const navegar = espiarNavegacion();
      expect(popup()).not.toBeNull();

      escape();
      await refrescar();

      expect(popup()).toBeNull();
      expect(navegar).toHaveBeenCalledWith('/articulos/15');
    });
  });

  /*
   * Responsive, S2/S4/T8: en el celular cada compra es una tarjeta apilada con el comprobante adelante. Vitest no evalúa
   * media queries, así que el spec vigila el contrato de datos que la dibuja (el nombre de la columna en cada celda) y
   * que la regla de la tarjeta viva sólo en la pantalla del celular: la tabla ancha no cambia.
   */
  describe('la fila apilada del celular (T8)', () => {
    /**
     * Las reglas de la hoja de Compras que tocan `selector`, con la consulta de medios que las envuelve ('' si no
     * tiene): así se afirma que una regla es sólo del celular y que la base —tablet y escritorio— no se movió. Angular
     * le agrega a cada selector el atributo del componente, por eso la comparación es por `includes`.
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
          // Angular deja espacios de más donde estaba el atributo: se normalizan para comparar el selector entero.
          const selectorText = (regla as CSSStyleRule).selectorText
            ?.replace(/\[_ng(content|host)-[^\]]+\]/g, '')
            .replace(/\s+/g, ' ');
          if (selectorText?.includes(selector)) encontradas.push({ consulta, estilo: (regla as CSSStyleRule).style });
        });
      };
      Array.from(document.styleSheets).forEach((hoja) => {
        try {
          recorrer(hoja.cssRules, '');
        } catch {
          // Una hoja que no se deja leer no es la de Compras.
        }
      });
      return encontradas;
    }

    it('cada celda lleva el nombre de su columna en data-etiqueta y el comprobante va primero', async () => {
      await crear();

      const filas = Array.from(el().querySelectorAll<HTMLElement>('table.compras tbody tr'));
      const cabeceras = Array.from(el().querySelectorAll('thead th')).map((th) => th.textContent!.trim());
      expect(el().querySelector('table.compras')!.classList).toContain('tabla-tarjetas');
      for (const fila of filas) {
        expect(Array.from(fila.querySelectorAll('td')).map((td) => td.getAttribute('data-etiqueta'))).toEqual(cabeceras);
      }

      // El comprobante es lo que identifica la compra y lo que se toca para abrir el detalle, aunque en la tabla vaya
      // segundo: la tarjeta lo pone arriba con `order`.
      const primera = filas[0];
      expect(primera.querySelector('td.comprobante button.enlace')!.textContent!.trim()).toBe('Factura A 00003-00000002');
      expect(primera.querySelector('td.fecha')!.textContent!.trim()).toBe('20/10/2026');
      expect(primera.querySelector('td.proveedor')!.textContent!.trim()).toBe('Bulonera Sur');
      expect(primera.querySelector('td.renglones')!.textContent!.trim()).toBe('2');
      expect(primera.querySelector('td.neto')!.textContent).toContain('2.200,00');
    });

    it('la tarjeta vive sólo en la pantalla del celular, el comprobante es su título y el botón mide el dedo', async () => {
      await crear();

      const tarjeta = reglasDe('.tabla-tarjetas');
      expect(tarjeta.length).toBeGreaterThan(0);
      expect(tarjeta.some((regla) => regla.estilo.getPropertyValue('display') === 'grid')).toBe(true);
      for (const regla of tarjeta) {
        expect(regla.consulta).toContain('screen');
        expect(regla.consulta).toContain('max-width: 639.98px');
      }

      // El comprobante sube al primer renglón, ocupa el ancho y no repite el nombre de la columna.
      const titulo = reglasDe('td.comprobante');
      expect(titulo.some((regla) => regla.estilo.getPropertyValue('order') === '-1')).toBe(true);
      expect(titulo.some((regla) => regla.estilo.getPropertyValue('grid-column') === '1 / -1')).toBe(true);
      const sinEtiqueta = reglasDe('td.comprobante::before');
      expect(sinEtiqueta).toHaveLength(1);
      expect(sinEtiqueta[0].estilo.getPropertyValue('content')).toBe('none');

      // S5: el botón que abre el detalle mide 44×44 y el foco se ve, también en la tarjeta.
      const accion = reglasDe('td.comprobante .enlace');
      expect(accion.some((regla) => regla.estilo.getPropertyValue('min-height') === '44px')).toBe(true);
      expect(accion.some((regla) => regla.estilo.getPropertyValue('min-width') === '44px')).toBe(true);
      const foco = reglasDe('td.comprobante .enlace:focus-visible');
      expect(foco).toHaveLength(1);
      expect(foco[0].estilo.getPropertyValue('outline')).toContain('var(--acento)');
    });
  });
});
