import { HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { By } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { Avisos } from '../../core/avisos/avisos';
import { AuthService } from '../../core/auth/auth.service';
import { Sesion } from '../../core/auth/auth.models';
import { Articulo } from './articulo.models';
import { Articulos } from './articulos';
import { ArticulosService } from './articulos.service';
import { DialogoAjusteStock } from './dialogo-ajuste-stock';
import { RubrosService } from '../rubros/rubros.service';

registerLocaleData(localeEsAr);

const SESION = {
  userId: 7,
  nombre: 'Dueño',
  email: 'dueno@tornillo.demo',
  rol: 'DUENO',
  ferreteriaId: 1,
  ferreteriaNombre: 'El Tornillo',
} as Sesion;

const NBSP = ' ';

describe('Articulos', () => {
  const articulo = (id: number, cambios: Partial<Articulo> = {}): Articulo => ({
    id,
    codigoInterno: `COD-${id}`,
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: `Artículo ${id}`,
    rubroId: null,
    proveedorId: null,
    costo: 100,
    moneda: 'ARS',
    margenPorcentaje: 35,
    alicuotaIva: 'IVA_21',
    precioFinal: 170,
    unidad: 'UNIDAD',
    stockActual: 5,
    stockMinimo: 0,
    ubicacion: null,
    activo: true,
    ...cambios,
  });

  const pagina = (
    items: Articulo[],
    page = 0,
    totalPages = 1,
    totalElements = items.length,
  ): PageResponse<Articulo> => ({ items, page, size: 20, totalElements, totalPages });

  let fixture: ComponentFixture<Articulos>;
  let esDueno: ReturnType<typeof signal<boolean>>;
  let servicio: {
    buscar: ReturnType<typeof vi.fn>;
    desactivar: ReturnType<typeof vi.fn>;
    reactivar: ReturnType<typeof vi.fn>;
    ajustarStock: ReturnType<typeof vi.fn>;
    exportar: ReturnType<typeof vi.fn>;
  };
  let avisos: Avisos;
  /** Los rubros que conoce la lista para la línea de detalle del celular: el 7 es «Bazar». */
  const rubrosServicio = { listar: vi.fn(() => of([{ id: 7, nombre: 'Bazar', margenPorcentaje: 30 }])) };
  let navegar: ReturnType<typeof vi.spyOn>;

  const errorHttp = (status: number, message: string) =>
    new HttpErrorResponse({ status, error: { status, message } });

  /** `url` simula volver al listado con la búsqueda, la página y los inactivos en la dirección. */
  async function crearComponente(
    dueno: boolean,
    primera = pagina([articulo(1), articulo(2)]),
    avisoPendiente: string | null = null,
    url = '/',
  ) {
    esDueno = signal(dueno);
    servicio = {
      buscar: vi.fn(() => of(primera)),
      desactivar: vi.fn(() => of(undefined)),
      reactivar: vi.fn((id: number) => of(articulo(id, { activo: true }))),
      ajustarStock: vi.fn(),
      exportar: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [Articulos],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ArticulosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno, sesion: signal<Sesion | null>(SESION) } },
        { provide: RubrosService, useValue: rubrosServicio },
      ],
    }).compileComponents();
    const router = TestBed.inject(Router);
    await router.navigateByUrl(url);
    navegar = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    avisos = TestBed.inject(Avisos);
    if (avisoPendiente) avisos.dejar(avisoPendiente);
    fixture = TestBed.createComponent(Articulos);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const filas = () => Array.from(el().querySelectorAll<HTMLTableRowElement>('tbody tr'));
  const texto = (selector: string, fila = 0) =>
    (filas()[fila].querySelector(selector)?.textContent ?? '').trim();
  const barra = (selector: string) => el().querySelector<HTMLButtonElement>(`app-barra-acciones ${selector}`);

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function buscarTexto(valor: string) {
    const input = el().querySelector<HTMLInputElement>('#q')!;
    input.value = valor;
    input.dispatchEvent(new Event('input'));
    el().querySelector('form.busqueda')!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  async function clickEn(selector: string, indice = 0) {
    el().querySelectorAll<HTMLButtonElement>(selector)[indice].click();
    await refrescar();
  }

  /**
   * Las reglas del documento que tocan `selector`, con la consulta de medios que las envuelve ('' si no tiene): así se
   * puede afirmar que una regla es sólo del celular y que la base —tablet y escritorio— no se movió. Angular le agrega
   * a cada selector el atributo del componente, por eso la comparación es por `includes`.
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
        if (selectorText?.includes(selector)) {
          encontradas.push({ consulta, estilo: (regla as CSSStyleRule).style });
        }
      });
    };
    Array.from(document.styleSheets).forEach((hoja) => {
      try {
        recorrer(hoja.cssRules, '');
      } catch {
        // Una hoja que no se deja leer no es la de Artículos.
      }
    });
    return encontradas;
  }

  /** Clic en una celda de la fila: la elige. */
  async function elegir(fila: number) {
    (filas()[fila].querySelector('.descripcion') as HTMLElement).click();
    await refrescar();
  }

  async function teclaEnFila(fila: number, key: string) {
    filas()[fila].dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    await refrescar();
  }

  const elegidas = () => filas().filter((f) => f.getAttribute('aria-selected') === 'true');

  it('el título va en la barra de acciones de arriba', async () => {
    await crearComponente(false);

    expect(el().querySelector('app-barra-acciones h1')?.textContent?.trim()).toBe('Artículos');
  });

  it('carga la primera página al iniciar, sin búsqueda ni inactivos', async () => {
    await crearComponente(true);

    expect(servicio.buscar).toHaveBeenCalledTimes(1);
    expect(servicio.buscar).toHaveBeenCalledWith('', 0, 20, false, false);
    expect(filas()).toHaveLength(2);
  });

  it('muestra código, descripción, costo, margen, IVA, precio final y stock con su unidad', async () => {
    await crearComponente(
      true,
      pagina([
        articulo(1, {
          codigoInterno: 'TOR-001',
          descripcion: 'Tornillo hexagonal 1/4',
          costo: 100,
          margenPorcentaje: 35,
          alicuotaIva: 'IVA_10_5',
          precioFinal: 170,
          unidad: 'METRO',
          stockActual: 12.5,
        }),
      ]),
    );

    expect(texto('.codigo')).toBe('TOR-001');
    expect(texto('.descripcion')).toBe('Tornillo hexagonal 1/4');
    expect(texto('.costo')).toBe(`$${NBSP}100,00 / m`);
    expect(texto('.margen')).toBe(`35${NBSP}%`);
    expect(texto('.iva')).toBe('10,5 %');
    expect(texto('.precio')).toBe(`$${NBSP}170,00 / m`);
    expect(texto('.stock')).toBe('12,5 metros');
  });

  it('la columna «Última actualización» dice desde cuándo está al día el costo (GDS-05), para el Dueño y el Vendedor', async () => {
    const primera = pagina([articulo(1, { costoAl: '2026-05-20' }), articulo(2, { costoAl: null })]);
    await crearComponente(true, primera);

    expect(Array.from(el().querySelectorAll('thead th')).map((th) => th.textContent!.trim())).toContain('Última actualización');
    expect(texto('.costo-al')).toBe('20/05/2026');
    expect(texto('.costo-al', 1)).toBe('—');

    TestBed.resetTestingModule();
    await crearComponente(false, primera);
    expect(texto('.costo-al')).toBe('20/05/2026');
  });

  it('bloque 2, S5: la columna «Cód. proveedor» va después de «Código», para el Dueño y el Vendedor; sin código, un guion', async () => {
    const primera = pagina([articulo(1, { codigoProveedor: 'BS-4471' }), articulo(2, { codigoProveedor: null })]);
    await crearComponente(true, primera);

    const encabezados = Array.from(el().querySelectorAll('thead th')).map((th) => th.textContent!.trim());
    expect(encabezados.slice(0, 2)).toEqual(['Código', 'Cód. proveedor']);
    expect(texto('.codigo-proveedor')).toBe('BS-4471');
    expect(texto('.codigo-proveedor', 1)).toBe('—');

    TestBed.resetTestingModule();
    await crearComponente(false, primera);
    expect(texto('.codigo-proveedor')).toBe('BS-4471');
  });

  it('el stock lleva la unidad en singular o plural y el negativo va en rojo', async () => {
    await crearComponente(
      true,
      pagina([
        articulo(1, { stockActual: 1 }),
        articulo(2, { stockActual: 497 }),
        articulo(3, { stockActual: -3 }),
        articulo(4, { stockActual: 2, unidad: 'KILO' }),
      ]),
    );

    expect(texto('.stock', 0)).toBe('1 unidad');
    expect(texto('.stock', 1)).toBe('497 unidades');
    expect(texto('.stock', 2)).toBe('-3 unidades');
    expect(texto('.stock', 3)).toBe('2 kilos');
    expect(filas()[2].querySelector('.stock')!.classList).toContain('negativo');
    expect(filas()[1].querySelector('.stock')!.classList).not.toContain('negativo');
  });

  it('indica que hereda el margen cuando el artículo no tiene uno propio', async () => {
    await crearComponente(true, pagina([articulo(1, { margenPorcentaje: null })]));

    expect(texto('.margen')).toBe('Heredado');
  });

  it('muestra el costo de un artículo en dólares como US$ y el precio final siempre en pesos', async () => {
    await crearComponente(true, pagina([articulo(1, { costo: 10.8, moneda: 'USD', precioFinal: 1744 })]));

    expect(texto('.costo')).toBe(`US$${NBSP}10,80`);
    expect(texto('.precio')).toBe(`$${NBSP}1.744,00`);
  });

  it('muestra el costo de un artículo en pesos con $', async () => {
    await crearComponente(true, pagina([articulo(1, { costo: 1744, moneda: 'ARS' })]));

    expect(texto('.costo')).toBe(`$${NBSP}1.744,00`);
  });

  it('el Vendedor no ve las columnas de costo ni de margen (H07), sí el precio y el stock', async () => {
    await crearComponente(false);

    const encabezados = [...el().querySelectorAll('th')].map((th) => th.textContent!.trim());
    expect(encabezados).not.toContain('Costo');
    expect(encabezados).not.toContain('Margen');
    expect(el().querySelector('.costo')).toBeNull();
    expect(el().querySelector('.margen')).toBeNull();
    expect(el().querySelector('.precio')).not.toBeNull();
    expect(el().querySelector('.stock')).not.toBeNull();
  });

  it('avisa cuando no hay artículos', async () => {
    await crearComponente(true, pagina([], 0, 0, 0));

    expect(el().querySelector('table')).toBeNull();
    expect(el().textContent).toContain('Todavía no hay artículos.');
  });

  it('avisa cuando la búsqueda no encuentra nada', async () => {
    await crearComponente(true);
    servicio.buscar.mockReturnValue(of(pagina([], 0, 0, 0)));

    await buscarTexto('zzz');

    expect(el().textContent).toContain('No hay artículos que coincidan con «zzz».');
  });

  it('busca con Enter sin espacios sobrantes y vuelve a la primera página', async () => {
    await crearComponente(true, pagina([articulo(1)], 0, 3, 45));
    servicio.buscar.mockReturnValue(of(pagina([articulo(3)], 1, 3, 45)));
    await clickEn('button.siguiente');
    expect(servicio.buscar).toHaveBeenLastCalledWith('', 1, 20, false, false);

    servicio.buscar.mockReturnValue(of(pagina([articulo(9)], 0, 1, 1)));
    await buscarTexto('  torni ');

    expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 0, 20, false, false);
    expect(texto('.codigo')).toBe('COD-9');
  });

  it('el botón «Buscar» busca igual que Enter, como en Clientes', async () => {
    await crearComponente(true);
    servicio.buscar.mockReturnValue(of(pagina([articulo(9)], 0, 1, 1)));
    const input = el().querySelector<HTMLInputElement>('#q')!;
    input.value = 'torni';
    input.dispatchEvent(new Event('input'));

    const boton = el().querySelector<HTMLButtonElement>('form.busqueda button[type="submit"]')!;
    expect(boton.textContent?.trim()).toBe('Buscar');
    boton.click();
    await refrescar();

    expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 0, 20, false, false);
  });

  it('el buscador no autocompleta ni corrige y su ejemplo es el de Artículos', async () => {
    await crearComponente(true);

    const input = el().querySelector<HTMLInputElement>('#q')!;
    expect(input.name).toBe('q');
    expect(input.getAttribute('autocomplete')).toBe('off');
    expect(input.getAttribute('spellcheck')).toBe('false');
    // Literal a propósito: el texto es una decisión de producto y tiene que mostrar un ejemplo (hay precedente en
    // formulario-cotizacion.spec.ts con «08:00»). La aserción anterior sólo miraba el «…» del final, así que un
    // placeholder que se quedaba sin ejemplo —lo que pasó en S11— pasaba igual.
    expect(input.placeholder).toBe('Buscar: tornillo o TOR-001…');
  });

  it('pagina hacia adelante y hacia atrás conservando la búsqueda', async () => {
    await crearComponente(true, pagina([articulo(1)], 0, 3, 45));
    servicio.buscar.mockReturnValue(of(pagina([articulo(5)], 0, 3, 45)));
    await buscarTexto('torni');
    servicio.buscar.mockReturnValue(of(pagina([articulo(21)], 1, 3, 45)));

    await clickEn('button.siguiente');
    expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 1, 20, false, false);
    expect(texto('.codigo')).toBe('COD-21');

    servicio.buscar.mockReturnValue(of(pagina([articulo(5)], 0, 3, 45)));
    await clickEn('button.anterior');
    expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 0, 20, false, false);
    expect(texto('.codigo')).toBe('COD-5');
  });

  it('muestra en qué página está y deshabilita Anterior y Siguiente en los extremos', async () => {
    await crearComponente(true, pagina([articulo(1)], 0, 3, 45));
    const anterior = () => el().querySelector<HTMLButtonElement>('button.anterior')!;
    const siguiente = () => el().querySelector<HTMLButtonElement>('button.siguiente')!;

    expect(el().querySelector('.paginacion')?.textContent).toContain('Página 1 de 3');
    expect(el().querySelector('.paginacion')?.textContent).toContain('45 artículos');
    expect(anterior().disabled).toBe(true);
    expect(siguiente().disabled).toBe(false);

    servicio.buscar.mockReturnValue(of(pagina([articulo(41)], 2, 3, 45)));
    await clickEn('button.siguiente');
    servicio.buscar.mockReturnValue(of(pagina([articulo(41)], 2, 3, 45)));

    expect(el().querySelector('.paginacion')?.textContent).toContain('Página 3 de 3');
    expect(anterior().disabled).toBe(false);
    expect(siguiente().disabled).toBe(true);
  });

  it('el paginador va arriba de la tabla: no hay que bajar hasta el final para pasar de página', async () => {
    await crearComponente(true, pagina([articulo(1)], 0, 3, 45));

    const paginador = el().querySelector('nav.paginacion')!;
    const tabla = el().querySelector('table')!;
    expect(paginador.compareDocumentPosition(tabla) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('un solo artículo va en singular', async () => {
    await crearComponente(true, pagina([articulo(1)], 0, 1, 1));

    expect(el().querySelector('.paginacion')?.textContent).toContain('1 artículo');
    expect(el().querySelector('.paginacion')?.textContent).not.toContain('1 artículos');
  });

  it('no muestra la paginación cuando no hay artículos', async () => {
    await crearComponente(true, pagina([], 0, 0, 0));

    expect(el().querySelector('.paginacion')).toBeNull();
  });

  it('incluye los dados de baja al tildar la opción y los marca', async () => {
    await crearComponente(true);
    servicio.buscar.mockReturnValue(
      of(pagina([articulo(1), articulo(2, { activo: false })], 0, 1, 2)),
    );

    const check = el().querySelector<HTMLInputElement>('#inactivos')!;
    check.checked = true;
    check.dispatchEvent(new Event('change'));
    await refrescar();

    expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, true, false);
    expect(texto('.estado', 0)).toBe('Activo');
    expect(texto('.estado', 1)).toBe('De baja');
  });

  describe('búsqueda, página e inactivos en la dirección', () => {
    it('al entrar los lee de la dirección (volver de editar conserva el contexto)', async () => {
      await crearComponente(true, pagina([articulo(41)], 2, 3, 45), null, '/?q=torni&pagina=3&inactivos=1');

      expect(servicio.buscar).toHaveBeenCalledWith('torni', 2, 20, true, false);
      expect(el().querySelector<HTMLInputElement>('#q')!.value).toBe('torni');
      expect(el().querySelector<HTMLInputElement>('#inactivos')!.checked).toBe(true);
    });

    it('al volver de editar, la fila editada queda elegida y con el foco, y el elegido sale de la dirección', async () => {
      await crearComponente(true, pagina([articulo(7), articulo(8)], 1, 2, 22), null, '/?pagina=2&elegido=8');
      await refrescar();

      expect(elegidas()).toEqual([filas()[1]]);
      expect(document.activeElement).toBe(filas()[1]);
      expect(el().querySelector('.elegido')?.textContent?.replace(/\s+/g, ' ')).toContain('Elegido: Artículo 8');
      expect(navegar).toHaveBeenLastCalledWith([], {
        relativeTo: TestBed.inject(ActivatedRoute),
        queryParams: { pagina: 2 },
        replaceUrl: true,
      });
    });

    it('un elegido que no está en la página no elige ninguna fila', async () => {
      await crearComponente(true, pagina([articulo(7), articulo(8)]), null, '/?elegido=99');

      expect(elegidas()).toEqual([]);
      expect(filas().map((f) => f.getAttribute('tabindex'))).toEqual(['0', '-1']);
    });

    it('una página que no es un número arranca en la primera', async () => {
      await crearComponente(true, undefined, null, '/?pagina=abc');

      expect(servicio.buscar).toHaveBeenCalledWith('', 0, 20, false, false);
    });

    it('al buscar, paginar o tildar inactivos los deja en la dirección', async () => {
      await crearComponente(true, pagina([articulo(1)], 0, 3, 45));
      const route = TestBed.inject(ActivatedRoute);

      servicio.buscar.mockReturnValue(of(pagina([articulo(5)], 0, 3, 45)));
      await buscarTexto('torni');
      expect(navegar).toHaveBeenLastCalledWith([], {
        relativeTo: route,
        queryParams: { q: 'torni' },
        replaceUrl: true,
      });

      servicio.buscar.mockReturnValue(of(pagina([articulo(21)], 1, 3, 45)));
      await clickEn('button.siguiente');
      expect(navegar).toHaveBeenLastCalledWith([], {
        relativeTo: route,
        queryParams: { q: 'torni', pagina: 2 },
        replaceUrl: true,
      });

      servicio.buscar.mockReturnValue(of(pagina([articulo(5)], 0, 3, 45)));
      const check = el().querySelector<HTMLInputElement>('#inactivos')!;
      check.checked = true;
      check.dispatchEvent(new Event('change'));
      await refrescar();
      expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 0, 20, true, false);
      expect(navegar).toHaveBeenLastCalledWith([], {
        relativeTo: route,
        queryParams: { q: 'torni', inactivos: 1 },
        replaceUrl: true,
      });
    });
  });

  describe('ofertas (S3)', () => {
    const enOferta = (id: number, vigente = true): Articulo =>
      articulo(id, {
        precioFinal: 1000,
        oferta: {
          tipo: 'PRECIO_FIJO',
          valor: 800,
          hasta: null,
          vigente,
          precioOferta: vigente ? 800 : null,
        },
      });

    it('un artículo con oferta vigente lleva la marca y el precio de oferta con el normal tachado', async () => {
      await crearComponente(false, pagina([enOferta(1), articulo(2)]));

      expect(texto('.marca-oferta', 0)).toBe('Oferta');
      expect(texto('.precio .precio-oferta', 0)).toBe(`$${NBSP}800,00`);
      expect(texto('.precio s', 0)).toBe(`$${NBSP}1.000,00`);
      expect(filas()[1].querySelector('.marca-oferta')).toBeNull();
      expect(texto('.precio', 1)).toBe(`$${NBSP}170,00`);
    });

    it('una oferta vencida no se marca y se ve el precio normal', async () => {
      await crearComponente(false, pagina([enOferta(1, false)]));

      expect(filas()[0].querySelector('.marca-oferta')).toBeNull();
      expect(texto('.precio', 0)).toBe(`$${NBSP}1.000,00`);
    });

    it('«Solo en oferta» filtra desde la primera página y queda en la dirección', async () => {
      await crearComponente(false, pagina([articulo(1)], 0, 3, 45));
      const route = TestBed.inject(ActivatedRoute);
      servicio.buscar.mockReturnValue(of(pagina([enOferta(7)])));

      const check = el().querySelector<HTMLInputElement>('#en-oferta')!;
      check.checked = true;
      check.dispatchEvent(new Event('change'));
      await refrescar();

      expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, false, true);
      expect(navegar).toHaveBeenLastCalledWith([], {
        relativeTo: route,
        queryParams: { oferta: 1 },
        replaceUrl: true,
      });
    });

    it('al volver con ?oferta=1 arranca con el filtro tildado', async () => {
      await crearComponente(false, pagina([enOferta(7)]), null, '/?oferta=1');

      expect(el().querySelector<HTMLInputElement>('#en-oferta')!.checked).toBe(true);
      expect(servicio.buscar).toHaveBeenCalledWith('', 0, 20, false, true);
    });
  });

  describe('barra de acciones sobre la fila elegida', () => {
    it('el Dueño ve Nuevo, Editar, Ajustar stock y Dar de baja en la barra, no en las filas', async () => {
      await crearComponente(true);

      const nuevo = el().querySelector<HTMLAnchorElement>('app-barra-acciones a.nuevo');
      expect(nuevo).not.toBeNull();
      expect(nuevo!.textContent).toContain('Nuevo');
      expect(nuevo!.getAttribute('href')).toBe('/articulos/nuevo');
      expect(barra('button.editar')).not.toBeNull();
      expect(barra('button.ajustar-stock')).not.toBeNull();
      expect(barra('button.desactivar')?.textContent).toContain('Dar de baja');
      expect(barra('button.ver-ficha')).toBeNull();
      expect(el().querySelectorAll('tbody button, tbody a')).toHaveLength(0);
    });

    it('sin fila elegida, Editar, Ajustar stock y Dar de baja quedan apagados con aria-disabled', async () => {
      await crearComponente(true);

      expect(elegidas()).toHaveLength(0);
      for (const boton of [barra('button.editar')!, barra('button.ajustar-stock')!, barra('button.desactivar')!]) {
        expect(boton.getAttribute('aria-disabled')).toBe('true');
        expect(boton.hasAttribute('disabled')).toBe(false);
      }
    });

    it('clic en una fila la elige: resaltada, aria-selected, habilita las acciones y no navega', async () => {
      await crearComponente(true);

      await elegir(1);

      expect(elegidas()).toEqual([filas()[1]]);
      expect(filas()[0].getAttribute('aria-selected')).toBe('false');
      expect(barra('button.editar')!.hasAttribute('aria-disabled')).toBe(false);
      expect(barra('button.ajustar-stock')!.hasAttribute('aria-disabled')).toBe(false);
      expect(barra('button.desactivar')!.hasAttribute('aria-disabled')).toBe(false);
      expect(navegar).not.toHaveBeenCalledWith(['/articulos', 2], expect.anything());
    });

    it('la barra de estado dice cuál está elegido', async () => {
      await crearComponente(true);

      await elegir(1);

      expect(el().querySelector('.elegido')?.textContent?.replace(/\s+/g, ' ')).toContain('Elegido: Artículo 2');
    });

    it('Editar de la barra abre el formulario del elegido conservando la dirección', async () => {
      await crearComponente(true, pagina([articulo(7), articulo(8)]));
      await elegir(1);

      await clickEn('app-barra-acciones button.editar');

      expect(navegar).toHaveBeenCalledWith(['/articulos', 8], {
        queryParams: { elegido: 8 },
        queryParamsHandling: 'merge',
      });
    });

    it('Movimientos de la barra abre los movimientos de stock del elegido, aunque esté de baja (G29)', async () => {
      await crearComponente(true, pagina([articulo(7), articulo(8, { activo: false })]));
      expect(barra('button.movimientos')!.getAttribute('aria-disabled')).toBe('true');
      expect(barra('button.movimientos')!.hasAttribute('disabled')).toBe(false);

      await elegir(1);
      await clickEn('app-barra-acciones button.movimientos');

      expect(navegar).toHaveBeenCalledWith(['/articulos', 8, 'movimientos']);
    });

    it('el Vendedor no ve Movimientos en la barra', async () => {
      await crearComponente(false);

      expect(barra('button.movimientos')).toBeNull();
    });

    it('Clientes de la barra abre los clientes que compraron el elegido, aunque esté de baja (G29)', async () => {
      await crearComponente(true, pagina([articulo(7), articulo(8, { activo: false })]));
      expect(barra('button.clientes')!.getAttribute('aria-disabled')).toBe('true');
      expect(barra('button.clientes')!.hasAttribute('disabled')).toBe(false);

      await elegir(1);
      await clickEn('app-barra-acciones button.clientes');

      expect(navegar).toHaveBeenCalledWith(['/articulos', 8, 'clientes']);
    });

    it('el Vendedor no ve Clientes en la barra', async () => {
      await crearComponente(false);

      expect(barra('button.clientes')).toBeNull();
    });

    it('«Ver ficha» de la barra le abre al Vendedor la ficha del elegido, aunque esté de baja (bugs de la guía, S20)', async () => {
      await crearComponente(false, pagina([articulo(7), articulo(8, { activo: false })]));

      await elegir(1);
      await clickEn('app-barra-acciones button.ver-ficha');

      expect(navegar).toHaveBeenCalledWith(['/articulos', 8], {
        queryParams: { elegido: 8 },
        queryParamsHandling: 'merge',
      });
    });
  });

  describe('los motivos de la barra (S5)', () => {
    const motivoDeLaPantalla = () => el().querySelector('.motivo-accion');

    it('intentar Editar sin fila elegida explica el motivo y no navega', async () => {
      await crearComponente(true);
      expect(motivoDeLaPantalla()).toBeNull();

      await clickEn('app-barra-acciones button.editar');

      const motivo = motivoDeLaPantalla()!;
      expect(motivo.getAttribute('role')).toBe('status');
      expect(motivo.textContent?.trim()).toBe('Elegí un artículo de la lista para editarlo.');
      expect(navegar).not.toHaveBeenCalledWith(['/articulos', 1], expect.anything());
    });

    it('intentar Ajustar stock sin fila elegida explica el motivo y no abre el panel', async () => {
      await crearComponente(true);

      await clickEn('app-barra-acciones button.ajustar-stock');

      expect(motivoDeLaPantalla()?.textContent?.trim()).toBe('Elegí un artículo de la lista para ajustar el stock.');
      expect(el().querySelector('app-dialogo-ajuste-stock')).toBeNull();
    });

    it('con un artículo de baja, Ajustar stock explica el mismo motivo de la línea y no abre el panel', async () => {
      await crearComponente(true, pagina([articulo(1), articulo(2, { activo: false, descripcion: 'Tuerca vieja' })]));
      await elegir(1);

      await clickEn('app-barra-acciones button.ajustar-stock');

      const texto = '«Tuerca vieja» está dado de baja. Reactivalo para ajustar el stock.';
      expect(motivoDeLaPantalla()?.textContent?.trim()).toBe(texto);
      expect(el().querySelector('#motivo-sin-ajuste')?.textContent?.trim()).toBe(texto);
      expect(el().querySelector('app-dialogo-ajuste-stock')).toBeNull();
    });

    it('intentar Movimientos sin fila elegida explica el motivo y no navega', async () => {
      await crearComponente(true);

      await clickEn('app-barra-acciones button.movimientos');

      expect(motivoDeLaPantalla()?.textContent?.trim()).toBe('Elegí un artículo de la lista para ver sus movimientos.');
      expect(navegar).not.toHaveBeenCalledWith(['/articulos', 1, 'movimientos']);
    });

    it('intentar Clientes sin fila elegida explica el motivo y no navega', async () => {
      await crearComponente(true);

      await clickEn('app-barra-acciones button.clientes');

      expect(motivoDeLaPantalla()?.textContent?.trim()).toBe(
        'Elegí un artículo de la lista para ver qué clientes lo compraron.',
      );
      expect(navegar).not.toHaveBeenCalledWith(['/articulos', 1, 'clientes']);
    });

    it('intentar Dar de baja sin fila elegida explica el motivo y no abre la confirmación', async () => {
      await crearComponente(true);

      await clickEn('app-barra-acciones button.desactivar');

      expect(motivoDeLaPantalla()?.textContent?.trim()).toBe('Elegí un artículo de la lista para darlo de baja.');
      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(servicio.desactivar).not.toHaveBeenCalled();
    });

    it('el Vendedor: intentar Ver ficha sin fila elegida explica el motivo y no navega', async () => {
      await crearComponente(false);

      await clickEn('app-barra-acciones button.ver-ficha');

      expect(motivoDeLaPantalla()?.textContent?.trim()).toBe('Elegí un artículo de la lista para ver su ficha.');
      expect(navegar).not.toHaveBeenCalledWith(['/articulos', 1], expect.anything());
    });

    it('con el motivo a la vista, elegir una fila lo apaga solo y deja la acción usable', async () => {
      await crearComponente(true, pagina([articulo(7), articulo(8)]));
      await clickEn('app-barra-acciones button.movimientos');
      expect(motivoDeLaPantalla()).not.toBeNull();

      await elegir(1);

      // Nadie volvió a tocar el botón: el motivo se va porque la regla ya no rige.
      expect(motivoDeLaPantalla()).toBeNull();
      expect(barra('button.movimientos')!.hasAttribute('aria-disabled')).toBe(false);

      await clickEn('app-barra-acciones button.movimientos');

      expect(navegar).toHaveBeenCalledWith(['/articulos', 8, 'movimientos']);
    });
  });

  describe('exportar a CSV (GDS-19)', () => {
    let enlace: HTMLAnchorElement | undefined;

    beforeEach(() => {
      enlace = undefined;
      Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:csv'), revokeObjectURL: vi.fn() });
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
        enlace = this;
      });
    });

    afterEach(() => vi.restoreAllMocks());

    const archivo = () =>
      new HttpResponse({
        body: new Blob(['csv']),
        headers: new HttpHeaders({ 'Content-Disposition': 'attachment; filename="articulos-2026-10-23.csv"' }),
      });

    it('el Dueño tiene Exportar en la barra, habilitado sin elegir fila', async () => {
      await crearComponente(true);

      expect(barra('button.exportar')!.textContent).toContain('Exportar');
      expect(barra('button.exportar')!.disabled).toBe(false);
    });

    it('el Vendedor no ve Exportar', async () => {
      await crearComponente(false);

      expect(barra('button.exportar')).toBeNull();
    });

    it('baja lo que muestra la pantalla: la búsqueda aplicada y los inactivos, con el nombre del servidor', async () => {
      await crearComponente(true, pagina([articulo(1)]), null, '/?q=tuerca&inactivos=1');
      servicio.exportar.mockReturnValue(of(archivo()));

      await clickEn('app-barra-acciones button.exportar');

      expect(servicio.exportar).toHaveBeenCalledWith('tuerca', true, false);
      expect(enlace?.download).toBe('articulos-2026-10-23.csv');
    });

    it('con «Solo en oferta» tildado baja solo los artículos en oferta (bugs de la guía, S19)', async () => {
      await crearComponente(true, pagina([articulo(1)]), null, '/?oferta=1');
      servicio.exportar.mockReturnValue(of(archivo()));

      await clickEn('app-barra-acciones button.exportar');

      expect(servicio.exportar).toHaveBeenCalledWith('', false, true);
    });

    it('mientras baja, Exportar queda deshabilitado y dice Exportando…', async () => {
      await crearComponente(true);
      const pendiente = new Subject<HttpResponse<Blob>>();
      servicio.exportar.mockReturnValue(pendiente);

      await clickEn('app-barra-acciones button.exportar');

      expect(barra('button.exportar')!.disabled).toBe(true);
      expect(barra('button.exportar')!.textContent).toContain('Exportando…');
      pendiente.next(archivo());
      pendiente.complete();
      await refrescar();
      expect(barra('button.exportar')!.disabled).toBe(false);
      expect(barra('button.exportar')!.textContent).not.toContain('Exportando');
    });

    it('si falla, avisa y no baja nada', async () => {
      await crearComponente(true);
      servicio.exportar.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

      await clickEn('app-barra-acciones button.exportar');

      expect(el().querySelector('[role="alert"]')!.textContent).toContain('No se pudieron exportar los artículos.');
      expect(enlace).toBeUndefined();
      expect(barra('button.exportar')!.disabled).toBe(false);
    });

    it('elegido de baja: Reactivar en vez de Dar de baja y Ajustar stock deshabilitado con el motivo a la vista', async () => {
      await crearComponente(true, pagina([articulo(1), articulo(2, { activo: false, descripcion: 'Tuerca vieja' })]));

      await elegir(1);

      expect(barra('button.desactivar')).toBeNull();
      expect(barra('button.reactivar')).not.toBeNull();
      const ajustar = barra('button.ajustar-stock')!;
      expect(ajustar.getAttribute('aria-disabled')).toBe('true');
      expect(ajustar.hasAttribute('disabled')).toBe(false);
      const motivo = el().querySelector(`#${ajustar.getAttribute('aria-describedby')}`);
      expect(motivo?.textContent?.trim()).toBe('«Tuerca vieja» está dado de baja. Reactivalo para ajustar el stock.');
      // Bugs de la guía, S10.4/S12: el aviso va en su propia línea, fuera del paginador, que si no se partía en dos.
      expect(motivo!.closest('nav.paginacion')).toBeNull();
      expect(el().querySelector('nav.paginacion')!.textContent).not.toContain('dado de baja');

      await elegir(0);
      expect(barra('button.desactivar')).not.toBeNull();
      expect(barra('button.reactivar')).toBeNull();
      expect(barra('button.ajustar-stock')!.hasAttribute('aria-disabled')).toBe(false);
    });

    it('el Vendedor no ve las acciones del Dueño: en la barra solo tiene «Ver ficha» (bugs de la guía, S20)', async () => {
      await crearComponente(false, pagina([articulo(7), articulo(8, { activo: false, descripcion: 'Tuerca vieja' })]));

      await elegir(1);

      expect(el().querySelector('a.nuevo')).toBeNull();
      expect(el().querySelector('button.editar')).toBeNull();
      expect(el().querySelector('button.desactivar')).toBeNull();
      expect(el().querySelector('button.reactivar')).toBeNull();
      expect(el().querySelector('button.ajustar-stock')).toBeNull();
      expect(el().querySelector('button.movimientos')).toBeNull();
      expect(el().querySelector('button.clientes')).toBeNull();
      expect(el().querySelector('button.exportar')).toBeNull();
      // El ⋯ del celular (sin texto, sólo ícono) es de las acciones que no son de un artículo: no cuenta acá.
      const acciones = Array.from(el().querySelectorAll('app-barra-acciones .accion-barra:not(.mas-opciones)'));
      expect(acciones.map((a) => a.textContent?.trim())).toEqual(['Ver ficha']);
      expect(el().textContent).not.toContain('Dar de baja');
      expect(el().textContent).not.toContain('Reactivalo para ajustar el stock');
      expect(el().textContent).not.toContain('Acciones');
    });

    // Bugs de la guía, S20: antes el Vendedor no elegía filas y no tenía cómo abrir la ficha de solo lectura que ya
    // le muestra la ruta articulos/:id (H07). Ahora elige filas como el Dueño, para abrirla.
    it('el Vendedor elige filas como el Dueño: resaltada, aria-selected y en el orden de Tab', async () => {
      await crearComponente(false, pagina([articulo(7), articulo(8)]));
      expect(barra('button.ver-ficha')!.getAttribute('aria-disabled')).toBe('true');
      expect(barra('button.ver-ficha')!.hasAttribute('disabled')).toBe(false);
      expect(filas().map((f) => f.getAttribute('tabindex'))).toEqual(['0', '-1']);

      await elegir(1);

      expect(elegidas()).toEqual([filas()[1]]);
      expect(filas()[0].getAttribute('aria-selected')).toBe('false');
      expect(filas().map((f) => f.getAttribute('tabindex'))).toEqual(['-1', '0']);
      expect(el().querySelector('table')!.getAttribute('role')).toBe('grid');
      expect(barra('button.ver-ficha')!.hasAttribute('aria-disabled')).toBe(false);
      expect(el().querySelector('.elegido')?.textContent?.replace(/\s+/g, ' ')).toContain(
        'Elegido: Artículo 8 · Enter abre la ficha',
      );
      expect(navegar).not.toHaveBeenCalledWith(['/articulos', 8], expect.anything());
    });
  });

  describe('teclado y mouse en la grilla', () => {
    it('la primera fila es la que recibe el foco con Tab si no hay ninguna elegida', async () => {
      await crearComponente(true);

      expect(filas().map((f) => f.getAttribute('tabindex'))).toEqual(['0', '-1']);

      await elegir(1);
      expect(filas().map((f) => f.getAttribute('tabindex'))).toEqual(['-1', '0']);
    });

    it('enfocar una fila (Tab) la elige', async () => {
      await crearComponente(true);

      filas()[0].focus();
      await refrescar();

      expect(elegidas()).toEqual([filas()[0]]);
    });

    it('↓ y ↑ mueven la fila elegida y el foco, sin pasarse de los extremos', async () => {
      await crearComponente(true, pagina([articulo(1), articulo(2), articulo(3)]));
      filas()[0].focus();
      await refrescar();

      await teclaEnFila(0, 'ArrowDown');
      expect(elegidas()).toEqual([filas()[1]]);
      expect(document.activeElement).toBe(filas()[1]);

      await teclaEnFila(1, 'ArrowDown');
      await teclaEnFila(2, 'ArrowDown');
      expect(elegidas()).toEqual([filas()[2]]);

      await teclaEnFila(2, 'ArrowUp');
      expect(elegidas()).toEqual([filas()[1]]);
      expect(document.activeElement).toBe(filas()[1]);
    });

    it('Enter en una fila abre Editar', async () => {
      await crearComponente(true, pagina([articulo(7)]));

      await teclaEnFila(0, 'Enter');

      expect(navegar).toHaveBeenCalledWith(['/articulos', 7], {
        queryParams: { elegido: 7 },
        queryParamsHandling: 'merge',
      });
    });

    it('doble clic en la fila abre Editar', async () => {
      await crearComponente(true, pagina([articulo(7)]));

      filas()[0].querySelector('.descripcion')!.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
      await refrescar();

      expect(navegar).toHaveBeenCalledWith(['/articulos', 7], {
        queryParams: { elegido: 7 },
        queryParamsHandling: 'merge',
      });
    });

    // Bugs de la guía, S20: antes Enter y doble clic no hacían nada para el Vendedor; ahora abren la ficha de solo
    // lectura, con la misma dirección de vuelta que Editar.
    it('para el Vendedor Enter y doble clic abren la ficha de solo lectura', async () => {
      await crearComponente(false, pagina([articulo(7), articulo(8)]));

      await teclaEnFila(0, 'Enter');

      expect(navegar).toHaveBeenLastCalledWith(['/articulos', 7], {
        queryParams: { elegido: 7 },
        queryParamsHandling: 'merge',
      });

      filas()[1].querySelector('.descripcion')!.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
      await refrescar();

      expect(navegar).toHaveBeenLastCalledWith(['/articulos', 8], {
        queryParams: { elegido: 8 },
        queryParamsHandling: 'merge',
      });
    });
  });

  /** Elige la fila, toca «Dar de baja» en la barra y, en el diálogo propio, el botón indicado. */
  async function darDeBaja(fila: number, boton: 'confirmar' | 'cancelar' = 'confirmar') {
    await elegir(fila);
    await clickEn('app-barra-acciones button.desactivar');
    el().querySelector<HTMLButtonElement>(`app-confirmacion button.${boton}`)!.click();
    await refrescar();
  }

  it('el diálogo de baja dice lo que pasa en el mostrador: sale marcado y no se puede vender (QA 05/11, H06)', async () => {
    await crearComponente(true);

    await elegir(0);
    await clickEn('app-barra-acciones button.desactivar');

    const mensaje = el().querySelector('app-confirmacion p')?.textContent ?? '';
    expect(mensaje).toContain('En el mostrador sale marcado como dado de baja y no se puede vender.');
    expect(mensaje).not.toContain('en el mostrador, pero');
  });

  it('da de baja con el diálogo propio (no el confirm del navegador) y recarga la página actual', async () => {
    await crearComponente(true);
    const nativo = vi.spyOn(window, 'confirm');
    servicio.buscar.mockReturnValue(of(pagina([articulo(2)], 0, 1, 1)));

    await elegir(0);
    await clickEn('app-barra-acciones button.desactivar');
    expect(el().querySelector('app-confirmacion h2')?.textContent).toContain('¿Dar de baja Artículo 1?');
    el().querySelector<HTMLButtonElement>('app-confirmacion button.confirmar')!.click();
    await refrescar();

    expect(nativo).not.toHaveBeenCalled();
    expect(servicio.desactivar).toHaveBeenCalledWith(1);
    expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, false, false);
    expect(el().querySelector('app-confirmacion')).toBeNull();
    expect(filas()).toHaveLength(1);
    expect(texto('.codigo')).toBe('COD-2');
    expect(elegidas()).toHaveLength(0);
  });

  it('no da de baja si se cancela el diálogo', async () => {
    await crearComponente(true);

    await darDeBaja(0, 'cancelar');

    expect(servicio.desactivar).not.toHaveBeenCalled();
    expect(servicio.buscar).toHaveBeenCalledTimes(1);
    expect(el().querySelector('app-confirmacion')).toBeNull();
  });

  it('si la baja deja vacía la última página, vuelve a la anterior', async () => {
    await crearComponente(true, pagina([articulo(1)], 0, 2, 21));
    servicio.buscar.mockReturnValue(of(pagina([articulo(21)], 1, 2, 21)));
    await clickEn('button.siguiente');
    servicio.buscar.mockReset();
    servicio.buscar
      .mockReturnValueOnce(of(pagina([], 1, 1, 20)))
      .mockReturnValueOnce(of(pagina([articulo(1)], 0, 1, 20)));

    await darDeBaja(0);

    expect(servicio.buscar).toHaveBeenNthCalledWith(1, '', 1, 20, false, false);
    expect(servicio.buscar).toHaveBeenNthCalledWith(2, '', 0, 20, false, false);
    expect(texto('.codigo')).toBe('COD-1');
  });

  it('muestra el mensaje del backend si falla la baja', async () => {
    await crearComponente(true);
    servicio.desactivar.mockReturnValue(throwError(() => errorHttp(404, 'Artículo no encontrado')));

    await darDeBaja(0);

    expect(el().querySelector('.error')?.textContent).toContain('Artículo no encontrado');
    expect(filas()).toHaveLength(2);
  });

  it('usa un mensaje genérico si la baja falla sin mensaje del backend', async () => {
    await crearComponente(true);
    servicio.desactivar.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    await darDeBaja(0);

    expect(el().querySelector('.error')?.textContent).toContain(
      'No se pudo dar de baja el artículo.',
    );
  });

  describe('reactivar', () => {
    it('Reactivar de la barra recarga, avisa y deja el artículo elegido', async () => {
      await crearComponente(true, pagina([articulo(1), articulo(2, { activo: false })]));
      servicio.buscar.mockReturnValue(of(pagina([articulo(1), articulo(2)])));

      await elegir(1);
      await clickEn('app-barra-acciones button.reactivar');

      expect(servicio.reactivar).toHaveBeenCalledWith(2);
      expect(servicio.buscar).toHaveBeenCalledTimes(2);
      expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se reactivó «Artículo 2».');
      expect(elegidas()).toEqual([filas()[1]]);
      expect(barra('button.desactivar')).not.toBeNull();
    });

    it('si el código de barras ya lo usa otro activo, muestra el mensaje del backend', async () => {
      await crearComponente(true, pagina([articulo(2, { activo: false })]));
      servicio.reactivar.mockReturnValue(
        throwError(() => errorHttp(409, 'Ya existe un artículo activo con el código de barras 779.')),
      );

      await elegir(0);
      await clickEn('app-barra-acciones button.reactivar');

      expect(el().querySelector('.error')?.textContent).toContain('Ya existe un artículo activo');
    });

    it('el Vendedor no ve Reactivar', async () => {
      await crearComponente(false, pagina([articulo(2, { activo: false })]));

      expect(el().querySelector('button.reactivar')).toBeNull();
    });
  });

  describe('ajustar stock', () => {
    it('el Dueño abre el panel desde la barra y, al confirmar, la grilla muestra el stock nuevo', async () => {
      await crearComponente(true, pagina([articulo(1, { stockActual: 5 }), articulo(2)]));

      await elegir(0);
      await clickEn('app-barra-acciones button.ajustar-stock');
      const dialogo = fixture.debugElement.query(By.directive(DialogoAjusteStock));
      expect(dialogo).not.toBeNull();
      expect((dialogo.componentInstance as DialogoAjusteStock).articulo()).toEqual({
        id: 1,
        codigoInterno: 'COD-1',
        descripcion: 'Artículo 1',
        unidad: 'UNIDAD',
        stockActual: 5,
      });

      (dialogo.componentInstance as DialogoAjusteStock).ajustado.emit({
        articuloId: 1,
        stockAnterior: 5,
        diferencia: 45,
        stockActual: 50,
      });
      await refrescar();

      expect(fixture.debugElement.query(By.directive(DialogoAjusteStock))).toBeNull();
      expect(texto('.stock', 0)).toBe('50 unidades');
      expect(el().querySelector('.alerta.exito')?.textContent).toContain(
        'Stock de «Artículo 1» ajustado: ahora hay 50 unidades.',
      );
      expect(servicio.buscar).toHaveBeenCalledTimes(1);
    });

    it('cancelar cierra el panel sin cambiar nada', async () => {
      await crearComponente(true);
      await elegir(0);
      await clickEn('app-barra-acciones button.ajustar-stock');

      (fixture.debugElement.query(By.directive(DialogoAjusteStock)).componentInstance as DialogoAjusteStock).cancelar.emit();
      await refrescar();

      expect(fixture.debugElement.query(By.directive(DialogoAjusteStock))).toBeNull();
      expect(texto('.stock', 0)).toBe('5 unidades');
    });
  });

  describe('buscador', () => {
    it('la × del buscador (o borrar todo) vuelve a mostrar todos los artículos', async () => {
      await crearComponente(true);
      servicio.buscar.mockReturnValue(of(pagina([], 0, 0, 0)));
      await buscarTexto('zzzz');
      expect(el().textContent).toContain('No hay artículos que coincidan con «zzzz».');
      servicio.buscar.mockReturnValue(of(pagina([articulo(1)])));

      const input = el().querySelector<HTMLInputElement>('#q')!;
      input.value = '';
      input.dispatchEvent(new Event('input'));
      await refrescar();

      expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, false, false);
      expect(el().textContent).not.toContain('No hay artículos que coincidan');
      expect(filas()).toHaveLength(1);
    });

    it('borrar letras de una búsqueda no busca hasta Enter', async () => {
      await crearComponente(true);
      await buscarTexto('torni');
      const llamadas = servicio.buscar.mock.calls.length;

      const input = el().querySelector<HTMLInputElement>('#q')!;
      input.value = 'tor';
      input.dispatchEvent(new Event('input'));
      await refrescar();

      expect(servicio.buscar.mock.calls.length).toBe(llamadas);
    });
  });

  /*
   * móvil-pulgar, T2: en el celular el buscador sale de la barra que se desliza en horizontal, los filtros se pliegan
   * detrás de un botón con contador y la fila de la lista es una sola línea con el importe a la derecha. Vitest no
   * evalúa media queries, así que el spec vigila la estructura que dibuja el celular: el renglón propio con el
   * buscador, el panel de filtros y las celdas que la línea compacta deja afuera. El escritorio conserva la barra y la
   * grilla de siempre. El dibujo en sí (la grilla de la fila, los 44 px) está en articulos.css.
   */
  describe('celular: buscador, filtros y fila de una línea (móvil-pulgar, T2)', () => {
    const renglon = () => el().querySelector<HTMLElement>('.herramientas-movil');
    const chip = (nombre: 'dados-de-baja' | 'en-oferta') =>
      el().querySelector<HTMLButtonElement>(`.herramientas-movil button.chip.${nombre}`)!;

    it('el buscador va en su propio renglón, fuera de la barra que se desliza', async () => {
      await crearComponente(true);

      const buscador = renglon()?.querySelector('form.busqueda-movil');
      expect(buscador).not.toBeNull();
      // Fuera de la barra de acciones: adentro quedaba a la derecha del scroll horizontal y había que deslizar para verlo.
      expect(buscador!.closest('app-barra-acciones')).toBeNull();
      expect(buscador!.querySelector<HTMLInputElement>('#q-movil')).not.toBeNull();
      // En su propio renglón, arriba de la lista: entra en la primera pantalla sin deslizar nada.
      const lista = el().querySelector('table')!;
      expect(renglon()!.compareDocumentPosition(lista) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      // El buscador del escritorio no se movió: sigue adentro de la barra.
      expect(el().querySelector('app-barra-acciones form.busqueda #q')).not.toBeNull();
    });

    it('el buscador del celular busca igual que el de la barra', async () => {      await crearComponente(true);
      servicio.buscar.mockReturnValue(of(pagina([articulo(9)], 0, 1, 1)));

      const input = renglon()!.querySelector<HTMLInputElement>('#q-movil')!;
      input.value = '  torni ';
      input.dispatchEvent(new Event('input'));
      renglon()!.querySelector('form.busqueda-movil')!.dispatchEvent(new Event('submit'));
      await refrescar();

      expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 0, 20, false, false);
    });

    it('el buscador del celular arranca con la búsqueda de la dirección, como el de la barra', async () => {
      await crearComponente(true, pagina([articulo(1)]), null, '/?q=torni');

      expect(renglon()!.querySelector<HTMLInputElement>('#q-movil')!.value).toBe('torni');
    });

    it('la fila es una línea con código, descripción y precio, sin los datos que sólo importan en la ficha', async () => {
      const primera = pagina([
        articulo(1, {
          codigoInterno: 'TOR-001',
          descripcion: 'Tornillo hexagonal 1/4 x 2',
          precioFinal: 170,
          stockActual: 12,
        }),
      ]);
      await crearComponente(true, primera);

      const fila = filas()[0];
      const enLaLinea = Array.from(fila.querySelectorAll('td:not(.solo-ficha)')).map((celda) =>
        celda.getAttribute('data-etiqueta'),
      );
      // Código y descripción a la izquierda; el importe y el stock a la derecha (la grilla de la línea, en articulos.css).
      expect(enLaLinea).toEqual(['Código', 'Descripción', 'Precio final', 'Stock', 'Estado']);
      expect(fila.querySelector('.codigo')!.textContent!.trim()).toBe('TOR-001');
      expect(fila.querySelector('.descripcion')!.textContent).toContain('Tornillo hexagonal 1/4 x 2');
      expect(fila.querySelector('.precio')!.textContent!.trim()).toBe(`$${NBSP}170,00`);
      expect(fila.querySelector('.stock')!.textContent!.trim()).toBe('12 unidades');

      // Lo que sólo se mira en la ficha no se dibuja en el celular, pero sigue en la fila: la tabla ancha del escritorio
      // conserva todas sus columnas.
      const soloFicha = Array.from(fila.querySelectorAll('td.solo-ficha')).map((celda) =>
        celda.getAttribute('data-etiqueta'),
      );
      expect(soloFicha).toEqual(['Cód. proveedor', 'Costo', 'Margen', 'IVA', 'Última actualización']);
      expect(Array.from(el().querySelectorAll('thead th')).map((th) => th.textContent!.trim())).toEqual([
        'Código',
        'Cód. proveedor',
        'Descripción',
        'Costo',
        'Margen',
        'IVA',
        'Precio final',
        'Stock',
        'Última actualización',
        'Estado',
      ]);

      // El Vendedor no ve costo ni margen: su línea compacta es la misma, sin esas dos columnas.
      TestBed.resetTestingModule();
      await crearComponente(false, primera);
      expect(
        Array.from(filas()[0].querySelectorAll('td:not(.solo-ficha)')).map((celda) =>
          celda.getAttribute('data-etiqueta'),
        ),
      ).toEqual(['Código', 'Descripción', 'Precio final', 'Stock', 'Estado']);
      expect(
        Array.from(filas()[0].querySelectorAll('td.solo-ficha')).map((celda) => celda.getAttribute('data-etiqueta')),
      ).toEqual(['Cód. proveedor', 'IVA', 'Última actualización']);
    });

    it('los filtros son dos chips de 44 px que mueven los mismos filtros que los tildes de la barra', async () => {
      await crearComponente(true);

      const chips = Array.from(el().querySelectorAll<HTMLButtonElement>('.herramientas-movil .chips button.chip'));
      expect(chips.map((chip) => chip.textContent!.trim())).toEqual(['Dados de baja', 'Solo en oferta']);
      expect(chips.map((chip) => chip.getAttribute('aria-pressed'))).toEqual(['false', 'false']);
      // El panel plegado y su botón «Filtros» ya no están en el celular.
      expect(el().querySelector('.herramientas-movil button.filtros')).toBeNull();
      expect(el().querySelector('.filtros-panel')).toBeNull();

      chips[1].click();
      await refrescar();

      expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, false, true);
      expect(chip('en-oferta').getAttribute('aria-pressed')).toBe('true');
      expect(chip('dados-de-baja').getAttribute('aria-pressed')).toBe('false');
      // Es el mismo estado que el tilde de la barra de escritorio.
      expect(el().querySelector<HTMLInputElement>('#en-oferta')!.checked).toBe(true);

      chip('dados-de-baja').click();
      await refrescar();

      expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, true, true);
      expect(chip('dados-de-baja').getAttribute('aria-pressed')).toBe('true');
      expect(el().querySelector<HTMLInputElement>('#inactivos')!.checked).toBe(true);

      // Volver a tocar un chip apagado el filtro.
      chip('en-oferta').click();
      await refrescar();

      expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, true, false);
      expect(chip('en-oferta').getAttribute('aria-pressed')).toBe('false');
    });

    it('los chips arrancan apretados si los filtros venían puestos en la dirección', async () => {
      await crearComponente(true, pagina([articulo(1)]), null, '/?inactivos=1&oferta=1');

      expect(chip('dados-de-baja').getAttribute('aria-pressed')).toBe('true');
      expect(chip('en-oferta').getAttribute('aria-pressed')).toBe('true');
    });

    it('los chips miden 44 px y las reglas nuevas viven sólo en el celular', async () => {
      await crearComponente(true);

      const chips = reglasDe('.chip').filter((regla) => regla.estilo.getPropertyValue('min-height') === '44px');
      expect(chips.length).toBeGreaterThan(0);
      for (const regla of [...chips, ...reglasDe('.mas-opciones'), ...reglasDe('.conteo-movil')].filter(
        (regla) => regla.estilo.getPropertyValue('display') !== 'none',
      )) {
        expect(regla.consulta).toContain('max-width: 639.98px');
      }
      // Fuera del celular el ⋯, la cuenta y los chips no se dibujan.
      const base = [...reglasDe('.mas-opciones'), ...reglasDe('.conteo-movil'), ...reglasDe('.herramientas-movil')].filter(
        (regla) => regla.consulta === '',
      );
      expect(base.length).toBeGreaterThan(0);
      expect(base.every((regla) => regla.estilo.getPropertyValue('display') === 'none')).toBe(true);
    });

    const enElCelular = () =>
      vi.stubGlobal('matchMedia', (consulta: string) => ({ matches: consulta.includes('639.98px'), media: consulta }));

    afterEach(() => {
      vi.unstubAllGlobals();
      rubrosServicio.listar.mockClear();
    });

    it('la línea de detalle de la fila lleva el código y el rubro, sin cambiar el texto del escritorio', async () => {
      enElCelular();
      await crearComponente(
        true,
        pagina([
          articulo(1, { codigoInterno: 'TOR-001', rubroId: 7 }),
          articulo(2, { rubroId: 99 }),
          articulo(3, { rubroId: null }),
        ]),
      );

      // El rubro viaja en un atributo que pinta la línea del celular: el texto de la celda es el de siempre.
      expect(filas()[0].querySelector('.codigo')!.getAttribute('data-rubro')).toBe('Bazar');
      expect(texto('.codigo', 0)).toBe('TOR-001');
      // Sin rubro, o con uno que la lista de rubros no conoce, no se inventa nada.
      expect(filas()[1].querySelector('.codigo')!.hasAttribute('data-rubro')).toBe(false);
      expect(filas()[2].querySelector('.codigo')!.hasAttribute('data-rubro')).toBe(false);
      const meta = reglasDe('.codigo[data-rubro]::after');
      expect(meta).toHaveLength(1);
      expect(meta[0].consulta).toContain('max-width: 639.98px');
      expect(meta[0].estilo.getPropertyValue('content')).toContain('attr(data-rubro)');
    });

    it('si la lista de rubros no se puede leer, la fila queda sin rubro y el listado sigue andando', async () => {
      enElCelular();
      rubrosServicio.listar.mockReturnValueOnce(throwError(() => errorHttp(500, 'Error interno')));
      await crearComponente(true, pagina([articulo(1, { rubroId: 7 })]));

      expect(filas()).toHaveLength(1);
      expect(filas()[0].querySelector('.codigo')!.hasAttribute('data-rubro')).toBe(false);
      expect(el().querySelector('.error')).toBeNull();
    });

    it('en el escritorio no se piden los rubros: sólo los usa la línea de detalle del celular', async () => {
      await crearComponente(true, pagina([articulo(1, { rubroId: 7 })]));

      expect(rubrosServicio.listar).not.toHaveBeenCalled();
      expect(filas()[0].querySelector('.codigo')!.hasAttribute('data-rubro')).toBe(false);
    });

    it('el stock en cero va marcado en rojo; el que no es cero, no', async () => {
      await crearComponente(true, pagina([articulo(1, { stockActual: 0 }), articulo(2, { stockActual: 3 })]));

      expect(filas()[0].querySelector('.stock')!.classList).toContain('sin-stock');
      expect(filas()[1].querySelector('.stock')!.classList).not.toContain('sin-stock');
      const rojo = reglasDe('.stock.sin-stock').filter((regla) => regla.estilo.getPropertyValue('color') !== '');
      expect(rojo.length).toBeGreaterThan(0);
      expect(rojo.every((regla) => regla.consulta.includes('max-width: 639.98px'))).toBe(true);
    });

    it('el precio de oferta se ve en la fila, con el normal tachado', async () => {
      await crearComponente(
        true,
        pagina([
          articulo(1, {
            precioFinal: 200,
            oferta: { vigente: true, precioOferta: 150 } as Articulo['oferta'],
          }),
        ]),
      );

      const precio = filas()[0].querySelector('.precio')!;
      expect(precio.querySelector('.precio-oferta')!.textContent!.trim()).toBe(`$${NBSP}150,00`);
      expect(precio.querySelector('s.precio-normal')!.textContent!.trim()).toBe(`$${NBSP}200,00`);
      expect(filas()[0].querySelector('.marca-oferta')!.textContent!.trim()).toBe('Oferta');
    });

    it('la cuenta de artículos va arriba de la lista y el paginador sigue diciendo la página', async () => {
      await crearComponente(true, pagina([articulo(1)], 0, 3, 45));

      const conteo = el().querySelector('.conteo-movil')!;
      expect(conteo.textContent!.trim()).toBe('45 artículos');
      expect(conteo.compareDocumentPosition(el().querySelector('table')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      // En el escritorio el paginador conserva su texto de siempre.
      expect(el().querySelector('nav.paginacion [aria-live]')!.textContent!.replace(/\s+/g, ' ').trim()).toBe(
        'Página 1 de 3 · 45 artículos',
      );
      expect(el().querySelector('nav.paginacion .conteo-total')).not.toBeNull();
      const oculto = reglasDe('.conteo-total');
      expect(oculto.length).toBeGreaterThan(0);
      expect(oculto.every((regla) => regla.consulta.includes('max-width: 639.98px'))).toBe(true);

      TestBed.resetTestingModule();
      await crearComponente(true, pagina([articulo(1)], 0, 1, 1));
      expect(el().querySelector('.conteo-movil')!.textContent!.trim()).toBe('1 artículo');
    });

    /*
     * 5c: «Tocá la fila». La ficha lleva ahora las acciones del artículo, así que en el celular un toque en la fila la
     * abre y las acciones de la barra se esconden; el escritorio sigue eligiendo con un clic y abriendo con doble clic.
     */
    describe('tocar la fila (movil-sectores T5, 5c)', () => {
      const ABRE_LA_7 = [['/articulos', 7], { queryParams: { elegido: 7 }, queryParamsHandling: 'merge' }];

      it('en el celular un toque en la fila abre su ficha, con la dirección de vuelta', async () => {
        enElCelular();
        await crearComponente(true, pagina([articulo(7), articulo(8)]));
        navegar.mockClear();

        await elegir(0);

        expect(navegar).toHaveBeenCalledTimes(1);
        expect(navegar.mock.calls[0]).toEqual(ABRE_LA_7);
      });

      it('el Vendedor también abre la ficha de solo lectura con un toque', async () => {
        enElCelular();
        await crearComponente(false, pagina([articulo(7)]));
        navegar.mockClear();

        await elegir(0);

        expect(navegar.mock.calls[0]).toEqual(ABRE_LA_7);
      });

      it('en el escritorio un clic sólo elige la fila: no navega', async () => {
        await crearComponente(true, pagina([articulo(7), articulo(8)]));
        navegar.mockClear();

        await elegir(0);

        expect(navegar).not.toHaveBeenCalled();
        expect(filas()[0].getAttribute('aria-selected')).toBe('true');
        expect(el().querySelector('.elegido')!.textContent).toContain('Elegido');
      });

      it('en el escritorio el doble clic y Enter siguen abriendo la ficha', async () => {
        await crearComponente(true, pagina([articulo(7)]));

        filas()[0].querySelector('.descripcion')!.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
        await refrescar();

        expect(navegar.mock.calls.at(-1)).toEqual(ABRE_LA_7);
      });

      it('las acciones de un artículo siguen en la barra del DOM, y sólo en el celular se esconden', async () => {
        await crearComponente(true);

        const acciones = ['.editar', '.ajustar-stock', '.movimientos', '.clientes', '.desactivar'];
        for (const accion of acciones) expect(barra(accion)).not.toBeNull();
        const reglas = reglasDe('.editar').filter((regla) => regla.estilo.getPropertyValue('display') === 'none');
        expect(reglas.length).toBeGreaterThan(0);
        expect(reglas.every((regla) => regla.consulta.includes('max-width: 639.98px'))).toBe(true);
        // La regla que las esconde nombra todas las acciones de la fila, no sólo Editar.
        const textoDeLaRegla = Array.from(document.styleSheets)
          .flatMap((hoja) => Array.from(hoja.cssRules))
          .flatMap((regla) => Array.from((regla as CSSMediaRule).cssRules ?? []))
          .map((regla) => (regla as CSSStyleRule).selectorText ?? '')
          .find((selector) => selector.includes('.editar') && selector.includes('.desactivar'))!;
        for (const accion of [...acciones, '.reactivar', '.ver-ficha']) expect(textoDeLaRegla).toContain(accion);
      });
    });
  });

  /*
   * movil-sectores T5 (5a): el ⋯ de la barra con las acciones que no son de un artículo (en una hoja inferior) y el
   * botón flotante «Nuevo». Vitest no evalúa media queries: los dos están siempre en el DOM y el CSS los muestra sólo en
   * el celular (se mide en el navegador); acá se prueba lo que hacen y para quién están.
   */
  describe('celular: ⋯ de la barra y botón «Nuevo» (movil-sectores T5, 5a)', () => {
    const abrir = () => el().querySelector<HTMLButtonElement>('app-barra-acciones button.mas-opciones')!;
    const hoja = () => el().querySelector<HTMLElement>('app-hoja-inferior');
    const accionesDeLaHoja = () =>
      Array.from(el().querySelectorAll('app-hoja-inferior .accion-hoja')).map((a) =>
        a.querySelector('span')!.firstChild!.textContent!.trim(),
      );
    const flotante = () => el().querySelector<HTMLButtonElement>('button.boton-flotante');

    it('el ⋯ está en la barra con su nombre, es de 44×44 y arranca con la hoja cerrada', async () => {
      await crearComponente(true);

      expect(abrir().getAttribute('aria-label')).toBe('Más opciones de Artículos');
      expect(abrir().getAttribute('aria-expanded')).toBe('false');
      expect(hoja()).toBeNull();
      const medida = reglasDe('.mas-opciones').find((regla) => regla.estilo.getPropertyValue('width') === '44px')!;
      expect(medida.consulta).toContain('max-width: 639.98px');
      expect(medida.estilo.getPropertyValue('height')).toBe('44px');
    });

    it('el Dueño ve las cinco acciones, en el orden del artboard, y la hoja se abre y se cierra', async () => {
      await crearComponente(true);

      abrir().click();
      await refrescar();

      expect(hoja()!.querySelector('h2')!.textContent!.trim()).toBe('Artículos');
      expect(abrir().getAttribute('aria-expanded')).toBe('true');
      expect(accionesDeLaHoja()).toEqual([
        'Exportar a CSV',
        'Stock bajo mínimo',
        'Etiquetas',
        'Controles de datos',
        'Rubros',
      ]);

      // Un toque en el velo la cierra…
      el().querySelector<HTMLElement>('.hoja-inferior-fondo')!.click();
      await refrescar();
      expect(hoja()).toBeNull();
      expect(abrir().getAttribute('aria-expanded')).toBe('false');

      // …y Esc también.
      abrir().click();
      await refrescar();
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await refrescar();
      expect(hoja()).toBeNull();
    });

    it('el Vendedor sólo ve Rubros, que es lo único que tiene de esa lista', async () => {
      await crearComponente(false);

      abrir().click();
      await refrescar();

      expect(accionesDeLaHoja()).toEqual(['Rubros']);
    });

    it('cada acción lleva a la misma pantalla que hoy y cierra la hoja', async () => {
      const destinos: [string, string][] = [
        ['.stock-bajo-hoja', '/stock-bajo-minimo'],
        ['.etiquetas-hoja', '/etiquetas'],
        ['.controles-hoja', '/controles-de-datos'],
        ['.rubros-hoja', '/rubros'],
      ];
      await crearComponente(true);

      for (const [selector, ruta] of destinos) {
        navegar.mockClear();
        abrir().click();
        await refrescar();
        el().querySelector<HTMLButtonElement>(`app-hoja-inferior ${selector}`)!.click();
        await refrescar();

        expect(navegar).toHaveBeenCalledWith([ruta]);
        expect(hoja()).toBeNull();
      }
    });

    it('«Exportar a CSV» baja lo mismo que el botón de la barra y cierra la hoja', async () => {
      await crearComponente(true);
      servicio.exportar.mockReturnValue(new Subject());
      await buscarTexto('tornillo');

      abrir().click();
      await refrescar();
      el().querySelector<HTMLButtonElement>('app-hoja-inferior .exportar-hoja')!.click();
      await refrescar();

      expect(servicio.exportar).toHaveBeenCalledWith('tornillo', false, false);
      expect(hoja()).toBeNull();
      // Mientras baja, el botón de la barra queda apagado y la hoja, si se vuelve a abrir, dice lo mismo.
      expect(barra('button.exportar')!.disabled).toBe(true);
      abrir().click();
      await refrescar();
      const exportar = el().querySelector<HTMLButtonElement>('app-hoja-inferior .exportar-hoja')!;
      expect(exportar.getAttribute('aria-disabled')).toBe('true');
      expect(exportar.textContent).toContain('Exportando…');
      exportar.click();
      await refrescar();
      expect(servicio.exportar).toHaveBeenCalledTimes(1);
    });

    it('en el celular la barra no repite «Nuevo» ni «Exportar»: son del botón flotante y del ⋯', async () => {
      await crearComponente(true);

      for (const selector of ['a.nuevo', 'button.exportar']) {
        expect(barra(selector)!.classList).toContain('solo-escritorio');
      }
      const oculta = reglasDe('.solo-escritorio');
      expect(oculta.length).toBeGreaterThan(0);
      expect(oculta.every((regla) => regla.consulta.includes('max-width: 639.98px'))).toBe(true);
      // Lo del artículo elegido sigue en la barra (hasta que la ficha lo reciba, T5 5c).
      expect(barra('button.editar')!.classList).not.toContain('solo-escritorio');
    });

    it('el botón flotante «Nuevo» es sólo del Dueño y hace lo mismo que «Nuevo» de la barra', async () => {
      await crearComponente(true);

      expect(flotante()!.textContent!.trim()).toBe('Nuevo');
      expect(el().querySelector('.grilla-marco')!.classList).toContain('con-flotante');
      flotante()!.click();

      expect(navegar).toHaveBeenCalledWith(['/articulos/nuevo'], { queryParamsHandling: 'preserve' });
      expect(barra('a.nuevo')!.getAttribute('href')).toBe('/articulos/nuevo');

      TestBed.resetTestingModule();
      await crearComponente(false);
      expect(flotante()).toBeNull();
      expect(el().querySelector('.grilla-marco')!.classList).not.toContain('con-flotante');
    });
  });

  describe('paginador del celular: una línea al pulgar (móvil-pulgar, T5)', () => {
    const paginador = () => el().querySelector<HTMLElement>('nav.paginacion');
    const anterior = () => el().querySelector<HTMLButtonElement>('button.anterior')!;
    const siguiente = () => el().querySelector<HTMLButtonElement>('button.siguiente')!;
    const cuenta = () => paginador()?.querySelector<HTMLElement>('[aria-live]');

    it('los dos botones llevan la flecha y conservan su rótulo como nombre accesible', async () => {
      await crearComponente(true, pagina([articulo(1)], 0, 3, 45));

      // En el celular el botón queda en 44×44 (por `chico`, que es la clase que mide styles.css) y en la pantalla va la
      // flecha; el rótulo sigue en el DOM —oculto a la vista— y es lo que le da el nombre al botón.
      for (const [boton, rotulo] of [
        [anterior(), 'Anterior'],
        [siguiente(), 'Siguiente'],
      ] as const) {
        expect(boton.tagName).toBe('BUTTON');
        // Está en el orden de Tab: se llega con el teclado además de con el dedo.
        expect(boton.tabIndex).toBe(0);
        expect(boton.classList.contains('chico')).toBe(true);
        expect(boton.querySelector('.flecha-pagina')?.getAttribute('aria-hidden')).toBe('true');
        expect(boton.querySelector('.rotulo')?.textContent?.trim()).toBe(rotulo);
      }
      // La cuenta de la página va en el mismo renglón y se anuncia cuando cambia.
      expect(cuenta()?.getAttribute('aria-live')).toBe('polite');
      expect(paginador()?.textContent).toContain('Página 1 de 3');
    });

    it('la flecha avanza y vuelve, y la cuenta dice la página nueva', async () => {
      await crearComponente(true, pagina([articulo(1)], 0, 3, 45));
      servicio.buscar.mockReturnValue(of(pagina([articulo(21)], 1, 3, 45)));

      await clickEn('button.siguiente');

      expect(servicio.buscar).toHaveBeenLastCalledWith('', 1, 20, false, false);
      expect(cuenta()?.textContent).toContain('Página 2 de 3');

      servicio.buscar.mockReturnValue(of(pagina([articulo(1)], 0, 3, 45)));
      await clickEn('button.anterior');

      expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, false, false);
      expect(cuenta()?.textContent).toContain('Página 1 de 3');
    });

    it('el paginador es una sola línea en el celular y en el escritorio queda como hoy', async () => {
      await crearComponente(true, pagina([articulo(1)], 0, 3, 45));

      const reglas = reglasDe('.paginacion');
      const base = reglas.filter((regla) => regla.consulta === '');
      const celular = reglas.filter((regla) => regla.consulta.includes('max-width: 639.98px'));
      // La base hace wrap libre; el celular no parte el renglón: la cuenta y las dos flechas van juntas.
      expect(base.some((regla) => regla.estilo.getPropertyValue('flex-wrap') === 'wrap')).toBe(true);
      expect(celular.some((regla) => regla.estilo.getPropertyValue('flex-wrap') === 'nowrap')).toBe(true);
      // Y los dos botones miden lo del dedo.
      const botones = reglasDe('button.chico').filter((regla) => regla.consulta.includes('max-width: 639.98px'));
      expect(botones.some((regla) => regla.estilo.getPropertyValue('min-width') === '44px')).toBe(true);
      expect(botones.some((regla) => regla.estilo.getPropertyValue('min-height') === '44px')).toBe(true);
    });

    it('los chips son botones de verdad, en el orden de Tab y con su estado anunciado', async () => {
      await crearComponente(true);

      for (const nombre of ['dados-de-baja', 'en-oferta'] as const) {
        const boton = el().querySelector<HTMLButtonElement>(`.herramientas-movil button.chip.${nombre}`)!;
        expect(boton.tagName).toBe('BUTTON');
        expect(boton.type).toBe('button');
        expect(boton.tabIndex).toBe(0);
        expect(boton.hasAttribute('aria-pressed')).toBe(true);
      }
      expect(el().querySelector('.herramientas-movil .chips')!.getAttribute('role')).toBe('group');
    });
  });

  it('muestra el aviso que dejó el formulario al guardar', async () => {
    await crearComponente(true, undefined, 'Se guardó «Tornillo».');

    expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se guardó «Tornillo».');
    expect(avisos.tomar()).toBeNull();
  });

  it('muestra el mensaje del backend si falla la carga', async () => {
    esDueno = signal(true);
    servicio = {
      buscar: vi.fn(() => throwError(() => errorHttp(500, 'Error interno'))),
      desactivar: vi.fn(),
      reactivar: vi.fn(),
      ajustarStock: vi.fn(),
      exportar: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [Articulos],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ArticulosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno } },
        { provide: RubrosService, useValue: rubrosServicio },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Articulos);
    await refrescar();

    expect(el().querySelector('.error')?.textContent).toContain('Error interno');
    expect(el().querySelector('table')).toBeNull();
  });

  /*
   * Responsive, S5: por debajo de 640 px la fila se lee como una tarjeta «etiqueta: valor». Vitest no evalúa media
   * queries, así que el spec vigila el contrato de datos que la dibuja: el nombre de la columna en cada celda.
   */
  it('S5: cada celda lleva el nombre de su columna en data-etiqueta, para la tarjeta del celular', async () => {
    const primera = pagina([articulo(1, { codigoProveedor: 'BS-4471' }), articulo(2)]);
    await crearComponente(true, primera);

    for (const fila of filas()) {
      expect(Array.from(fila.querySelectorAll('td')).map((celda) => celda.getAttribute('data-etiqueta'))).toEqual(
        Array.from(el().querySelectorAll('thead th')).map((th) => th.textContent!.trim()),
      );
    }
    expect(el().querySelector('table')!.classList).toContain('tabla-tarjetas');

    // El Vendedor no ve costo ni margen: sus columnas son otras y tienen que seguir coincidiendo con sus celdas.
    TestBed.resetTestingModule();
    await crearComponente(false, primera);

    const encabezados = Array.from(el().querySelectorAll('thead th')).map((th) => th.textContent!.trim());
    expect(encabezados).not.toContain('Costo');
    for (const fila of filas()) {
      expect(Array.from(fila.querySelectorAll('td')).map((celda) => celda.getAttribute('data-etiqueta'))).toEqual(
        encabezados,
      );
    }
    expect(el().querySelector('table')!.classList).toContain('tabla-tarjetas');
  });

  it('usa un mensaje genérico si la carga falla sin mensaje del backend', async () => {
    esDueno = signal(false);
    servicio = {
      buscar: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 0 }))),
      desactivar: vi.fn(),
      reactivar: vi.fn(),
      ajustarStock: vi.fn(),
      exportar: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [Articulos],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ArticulosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno } },
        { provide: RubrosService, useValue: rubrosServicio },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Articulos);
    await refrescar();

    expect(el().querySelector('.error')?.textContent).toContain(
      'No se pudieron cargar los artículos.',
    );
  });

  /*
   * movil-sectores T6: selección múltiple por mantener apretado → «Agregar N a Etiquetas». Sólo del Dueño y sólo del
   * celular (S1): en el escritorio y para el Vendedor mantener apretado no hace nada.
   */
  // El CSS de la selección vive en styles.css, que Vitest no lee: se mide en el navegador a 390 y 360 px.
  describe('celular: selección múltiple → Etiquetas (movil-sectores T6)', () => {
    const enElCelular = () =>
      vi.stubGlobal('matchMedia', (consulta: string) => ({ matches: consulta.includes('639.98px'), media: consulta }));
    const lista = () => pagina([articulo(1), articulo(2), articulo(3), articulo(4, { activo: false })]);
    const GUARDADAS = 'etiquetas:1:7';

    beforeEach(() => {
      localStorage.clear();
      vi.useFakeTimers();
    });
    afterEach(() => {
      vi.useRealTimers();
      vi.unstubAllGlobals();
      localStorage.clear();
    });

    const pulsar = (fila: number, x = 100, y = 100) =>
      filas()[fila].dispatchEvent(
        new PointerEvent('pointerdown', { bubbles: true, clientX: x, clientY: y, pointerType: 'touch', isPrimary: true }),
      );
    const soltar = (fila: number) =>
      filas()[fila].dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerType: 'touch' }));
    const tocar = (fila: number) => (filas()[fila].querySelector('.descripcion') as HTMLElement).click();
    /** Mantiene apretada la fila 600 ms y suelta: el toque que el navegador manda después va con `tocar`. */
    const mantener = (fila: number) => {
      pulsar(fila);
      vi.advanceTimersByTime(600);
      fixture.detectChanges();
      soltar(fila);
      tocar(fila);
      fixture.detectChanges();
    };
    const barraSel = () => el().querySelector<HTMLElement>('.barra-seleccion');
    const marcadas = () => filas().flatMap((f, i) => (f.classList.contains('marcada') ? [i] : []));
    const pie = () => el().querySelector<HTMLButtonElement>('.pie-seleccion button.agregar')!;

    it('mantener apretada una fila entra al modo selección con esa fila elegida, y el toque que sigue no abre la ficha', async () => {
      enElCelular();
      await crearComponente(true, lista());
      navegar.mockClear();

      mantener(0);

      expect(barraSel()!.getAttribute('role')).toBe('status');
      expect(barraSel()!.querySelector('b')!.textContent!.trim()).toBe('1 elegido');
      expect(marcadas()).toEqual([0]);
      expect(navegar).not.toHaveBeenCalled();
      expect(pie().textContent!.trim()).toBe('Agregar 1 a Etiquetas');
    });

    it('un toque corto no entra al modo selección: abre la ficha como hasta hoy', async () => {
      enElCelular();
      await crearComponente(true, lista());
      navegar.mockClear();

      pulsar(0);
      vi.advanceTimersByTime(200);
      soltar(0);
      tocar(0);
      vi.advanceTimersByTime(1000);
      fixture.detectChanges();

      expect(barraSel()).toBeNull();
      expect(navegar.mock.calls[0]).toEqual([['/articulos', 1], { queryParams: { elegido: 1 }, queryParamsHandling: 'merge' }]);
    });

    it('si el dedo se mueve (es un scroll) la pulsación se cancela', async () => {
      enElCelular();
      await crearComponente(true, lista());

      pulsar(0, 100, 100);
      filas()[0].dispatchEvent(new PointerEvent('pointermove', { bubbles: true, clientX: 100, clientY: 140 }));
      vi.advanceTimersByTime(1000);
      fixture.detectChanges();

      expect(barraSel()).toBeNull();
    });

    it('en modo selección tocar suma o saca, cuenta bien y no abre la ficha', async () => {
      enElCelular();
      await crearComponente(true, lista());
      mantener(0);
      navegar.mockClear();

      tocar(1);
      tocar(2);
      fixture.detectChanges();
      expect(barraSel()!.querySelector('b')!.textContent!.trim()).toBe('3 elegidos');
      expect(marcadas()).toEqual([0, 1, 2]);
      expect(pie().textContent!.trim()).toBe('Agregar 3 a Etiquetas');

      tocar(1);
      fixture.detectChanges();
      expect(marcadas()).toEqual([0, 2]);
      expect(barraSel()!.querySelector('b')!.textContent!.trim()).toBe('2 elegidos');
      expect(navegar).not.toHaveBeenCalled();
    });

    it('«Elegir todos» marca los activos de la página; el dado de baja no tiene casilla y dice por qué', async () => {
      enElCelular();
      await crearComponente(true, lista());
      mantener(0);

      barraSel()!.querySelector<HTMLButtonElement>('button.elegir-todos')!.click();
      fixture.detectChanges();

      expect(marcadas()).toEqual([0, 1, 2]);
      expect(barraSel()!.querySelector('b')!.textContent!.trim()).toBe('3 elegidos');
      expect(filas()[3].querySelector('.casilla-fila')).toBeNull();
      expect(filas()[3].textContent).toContain('Dado de baja');
      tocar(3);
      fixture.detectChanges();
      expect(marcadas()).toEqual([0, 1, 2]);
    });

    it('un artículo dado de baja no entra al modo selección al mantenerlo apretado', async () => {
      enElCelular();
      await crearComponente(true, lista());

      mantener(3);

      expect(barraSel()).toBeNull();
    });

    it('la ✕ sale del modo selección sin tocar nada, y al volver a entrar no queda nada elegido', async () => {
      enElCelular();
      await crearComponente(true, lista());
      mantener(0);
      tocar(1);

      barraSel()!.querySelector<HTMLButtonElement>('button.salir-seleccion')!.click();
      fixture.detectChanges();

      expect(barraSel()).toBeNull();
      expect(localStorage.getItem(GUARDADAS)).toBeNull();
      mantener(2);
      expect(marcadas()).toEqual([2]);
    });

    it('«Agregar N a Etiquetas» guarda la lista, sale del modo y avisa con un atajo a Etiquetas', async () => {
      enElCelular();
      localStorage.setItem(GUARDADAS, JSON.stringify({ v: 1, items: [{ id: 9, copias: 2 }] }));
      await crearComponente(true, lista());
      mantener(0);
      tocar(1);
      navegar.mockClear();

      pie().click();
      fixture.detectChanges();

      expect(JSON.parse(localStorage.getItem(GUARDADAS)!).items).toEqual([
        { id: 9, copias: 2 },
        { id: 1, copias: 1 },
        { id: 2, copias: 1 },
      ]);
      expect(barraSel()).toBeNull();
      expect(el().querySelector('.alerta.exito')!.textContent).toContain(
        'Se agregaron 2 artículos a la lista de etiquetas.',
      );
      const atajo = el().querySelector<HTMLElement>('.aviso-etiquetas')!;
      expect(atajo.textContent).toContain('Lista de etiquetas: 3 artículos');
      atajo.querySelector<HTMLButtonElement>('button')!.click();
      expect(navegar).toHaveBeenCalledWith(['/etiquetas']);
    });

    it('con un solo artículo el aviso va en singular', async () => {
      enElCelular();
      await crearComponente(true, lista());
      mantener(0);

      pie().click();
      fixture.detectChanges();

      expect(el().querySelector('.alerta.exito')!.textContent).toContain('Se agregó 1 artículo a la lista de etiquetas.');
      expect(el().querySelector('.aviso-etiquetas')!.textContent).toContain('Lista de etiquetas: 1 artículo');
    });

    it('sin nada elegido el botón está apagado, dice por qué y no guarda', async () => {
      enElCelular();
      await crearComponente(true, lista());
      mantener(0);
      tocar(0);
      fixture.detectChanges();

      expect(barraSel()).not.toBeNull();
      expect(pie().getAttribute('aria-disabled')).toBe('true');
      expect(el().querySelector('.pie-seleccion')!.textContent).toContain('Elegí al menos un artículo.');
      pie().click();
      fixture.detectChanges();
      expect(localStorage.getItem(GUARDADAS)).toBeNull();
      expect(barraSel()).not.toBeNull();
    });

    it('cambiar de página sale del modo selección', async () => {
      enElCelular();
      await crearComponente(true, pagina([articulo(1), articulo(2)], 0, 2, 30));
      mantener(0);

      el().querySelector<HTMLButtonElement>('nav.paginacion button.siguiente')!.click();
      fixture.detectChanges();

      expect(barraSel()).toBeNull();
    });

    it('el Vendedor no tiene Etiquetas: mantener apretado no le hace nada', async () => {
      enElCelular();
      await crearComponente(false, lista());

      mantener(0);

      expect(barraSel()).toBeNull();
      expect(el().querySelector('.pista-seleccion')).toBeNull();
    });

    it('en el escritorio mantener apretado no hace nada', async () => {
      await crearComponente(true, lista());

      mantener(0);

      expect(barraSel()).toBeNull();
    });

    it('la pista «Mantené apretado…» se ve hasta usar la selección una vez y no vuelve', async () => {
      enElCelular();
      await crearComponente(true, lista());
      expect(el().querySelector('.pista-seleccion')!.textContent).toContain('Mantené apretado un artículo para elegir varios.');

      mantener(0);
      barraSel()!.querySelector<HTMLButtonElement>('button.salir-seleccion')!.click();
      fixture.detectChanges();
      expect(el().querySelector('.pista-seleccion')).toBeNull();

      TestBed.resetTestingModule();
      await crearComponente(true, lista());
      expect(el().querySelector('.pista-seleccion')).toBeNull();
    });

    it('en el celular el menú de contexto del navegador no aparece sobre una fila', async () => {
      enElCelular();
      await crearComponente(true, lista());

      const evento = new Event('contextmenu', { bubbles: true, cancelable: true });
      filas()[0].dispatchEvent(evento);

      expect(evento.defaultPrevented).toBe(true);
    });
  });
  /*
   * movil-sectores T7: escáner de código de barras por cámara en el buscador del celular (S1; L18, L19). Sólo en el
   * celular y sólo si el navegador trae `BarcodeDetector` y `getUserMedia`; un permiso negado nunca se recuerda. El CSS
   * (botón y pantalla) no se evalúa en Vitest: se mide en el navegador.
   */
  describe('celular: escáner por cámara (movil-sectores T7)', () => {
    const enElCelular = () =>
      vi.stubGlobal('matchMedia', (consulta: string) => ({ matches: consulta.includes('639.98px'), media: consulta }));
    const CODIGO = '7790001000123';
    const con = () => pagina([articulo(1), articulo(2, { descripcion: 'Bulón 3/8', codigoBarras: CODIGO, stockActual: 12 })]);

    let getUserMedia: ReturnType<typeof vi.fn>;
    let pista: { stop: ReturnType<typeof vi.fn>; getCapabilities: () => object; applyConstraints: () => Promise<void> };
    let porLeer: string[];

    /** Simula el navegador del celular: `BarcodeDetector` y `getUserMedia` (que concede o niega el permiso). */
    function instalarNavegador(
      opciones: { detector?: boolean; camara?: boolean; denegar?: boolean; formatos?: string[] } = {},
    ) {
      const { detector = true, camara = true, denegar = false, formatos } = opciones;
      pista = { stop: vi.fn(), getCapabilities: () => ({}), applyConstraints: async () => undefined };
      porLeer = [];
      getUserMedia = vi.fn(async () => {
        if (denegar) throw new DOMException('Permission denied', 'NotAllowedError');
        return { getTracks: () => [pista], getVideoTracks: () => [pista] };
      });
      if (detector) {
        vi.stubGlobal(
          'BarcodeDetector',
          class {
            static getSupportedFormats = formatos ? async () => formatos : undefined;
            detect = async () => (porLeer.length > 0 ? [{ rawValue: porLeer.shift()! }] : []);
          },
        );
      }
      if (camara) Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia }, configurable: true });
      // jsdom no reproduce video: el video «tiene imagen» y reproducir no hace nada.
      Object.defineProperty(HTMLMediaElement.prototype, 'readyState', { get: () => 4, configurable: true });
      vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
    }

    const tras = async () => {
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();
    };
    const lectura = async () => {
      await vi.advanceTimersByTimeAsync(600);
      fixture.detectChanges();
    };
    const botonEscanear = () => el().querySelector<HTMLButtonElement>('form.busqueda-movil button.escanear');
    const escaner = () => el().querySelector('app-escaner-camara');
    const enEscaner = (nombre: string) =>
      Array.from(el().querySelectorAll<HTMLButtonElement>('app-escaner-camara button')).find(
        (b) => (b.getAttribute('aria-label') ?? b.textContent ?? '').trim() === nombre,
      )!;
    async function abrirEscaner() {
      await tras(); // El navegador primero confirma qué formatos puede leer.
      botonEscanear()!.click();
      await tras();
    }
    const DENEGADA =
      'No pudimos usar la cámara. Si la bloqueaste, habilitala desde los permisos del navegador y volvé a intentar.';

    // jsdom no evalúa media queries y `articulos.css` esconde el buscador del celular por defecto: sin esto no se le puede
    // dar el foco. Es la misma regla que el CSS aplica a 390 px.
    let estiloDelCelular: HTMLStyleElement;

    beforeEach(() => {
      localStorage.clear();
      vi.useFakeTimers();
      estiloDelCelular = document.createElement('style');
      estiloDelCelular.textContent = '.herramientas-movil { display: block !important; }';
      document.head.appendChild(estiloDelCelular);
    });
    afterEach(() => {
      estiloDelCelular.remove();
      vi.useRealTimers();
      vi.unstubAllGlobals();
      vi.restoreAllMocks();
      Reflect.deleteProperty(navigator, 'mediaDevices');
      Reflect.deleteProperty(HTMLMediaElement.prototype, 'readyState');
      localStorage.clear();
    });

    it('con BarcodeDetector y getUserMedia, el celular muestra el botón de cámara dentro del buscador, de Dueño y de Vendedor', async () => {
      for (const dueno of [true, false]) {
        TestBed.resetTestingModule();
        enElCelular();
        instalarNavegador();
        await crearComponente(dueno, con());
        await tras();

        const boton = botonEscanear()!;
        expect(boton).not.toBeNull();
        expect(boton.getAttribute('type')).toBe('button');
        expect(boton.getAttribute('aria-label')).toBe('Escanear código de barras con la cámara');
        expect(el().querySelectorAll('button.escanear').length).toBe(1);
        expect(escaner()).toBeNull();
      }
    });

    it('sin BarcodeDetector no hay botón (se escribe a mano, como siempre)', async () => {
      enElCelular();
      instalarNavegador({ detector: false });
      await crearComponente(true, con());
      await tras();
      expect(botonEscanear()).toBeNull();
      expect(el().querySelector('#q-movil')).not.toBeNull();
    });

    it('sin formatos compatibles no ofrece el escáner y sigue disponible la búsqueda a mano', async () => {
      enElCelular();
      instalarNavegador({ formatos: ['qr_code'] });
      await crearComponente(true, con());
      await tras();

      expect(botonEscanear()).toBeNull();
      expect(el().querySelector('#q-movil')).not.toBeNull();
      expect(getUserMedia).not.toHaveBeenCalled();
    });

    it('sin getUserMedia no hay botón', async () => {
      enElCelular();
      instalarNavegador({ camara: false });
      await crearComponente(true, con());
      await tras();
      expect(botonEscanear()).toBeNull();
    });

    it('en el escritorio (no es el celular) no hay botón aunque el navegador tenga todo', async () => {
      instalarNavegador();
      await crearComponente(true, con());
      await tras();
      expect(botonEscanear()).toBeNull();
      expect(el().querySelector('app-escaner-camara')).toBeNull();
      expect(getUserMedia).not.toHaveBeenCalled();
    });

    it('al tocarlo abre el escáner y pide la cámara trasera; la lista queda como estaba', async () => {
      enElCelular();
      instalarNavegador();
      await crearComponente(true, con());
      const llamadasAntes = servicio.buscar.mock.calls.length;

      await abrirEscaner();

      expect(escaner()).not.toBeNull();
      expect(el().querySelector('app-escaner-camara [role="dialog"]')).not.toBeNull();
      expect(getUserMedia).toHaveBeenCalledTimes(1);
      const pedido = getUserMedia.mock.calls[0][0] as MediaStreamConstraints;
      expect((pedido.video as MediaTrackConstraints).facingMode).toEqual({ ideal: 'environment' });
      expect(servicio.buscar.mock.calls.length).toBe(llamadasAntes);
      expect(pista.stop).not.toHaveBeenCalled();
    });

    it('permiso negado: muestra el mensaje; al cerrar y volver a entrar la cámara se pide de nuevo y no se guarda nada', async () => {
      enElCelular();
      instalarNavegador({ denegar: true });
      const guardar = vi.spyOn(Storage.prototype, 'setItem');
      await crearComponente(true, con());

      await abrirEscaner();
      expect(el().querySelector('app-escaner-camara [role="alert"]')!.textContent!.trim()).toBe(DENEGADA);
      expect(getUserMedia).toHaveBeenCalledTimes(1);

      enEscaner('Cerrar el escáner').click();
      await tras();
      expect(escaner()).toBeNull();

      await abrirEscaner();
      expect(getUserMedia).toHaveBeenCalledTimes(2);
      expect(el().querySelector('app-escaner-camara [role="alert"]')!.textContent!.trim()).toBe(DENEGADA);

      enEscaner('Intentar de nuevo').click();
      await tras();
      expect(getUserMedia).toHaveBeenCalledTimes(3);

      expect(guardar).not.toHaveBeenCalled();
      expect(localStorage.length).toBe(0);
    });

    it('con un formato compatible lee un código, apaga la cámara y «Abrir ficha» navega con elegido', async () => {
      enElCelular();
      instalarNavegador({ formatos: ['ean_13', 'qr_code'] });
      await crearComponente(true, con());
      await abrirEscaner();
      navegar.mockClear();

      porLeer.push(CODIGO);
      await lectura();

      expect(pista.stop).toHaveBeenCalledTimes(1);
      expect(servicio.buscar.mock.calls.at(-1)![0]).toBe(CODIGO);
      const tarjeta = el().querySelector('app-escaner-camara .leido')!;
      expect(tarjeta.textContent).toContain('Bulón 3/8');
      expect(tarjeta.textContent).toContain('stock 12');

      enEscaner('Abrir ficha').click();
      await tras();

      expect(navegar).toHaveBeenCalledWith(['/articulos', 2], {
        queryParams: { elegido: 2 },
        queryParamsHandling: 'merge',
      });
      expect(escaner()).toBeNull();
    });

    it('un código que no es de ningún artículo lo dice y deja escribirlo a mano', async () => {
      enElCelular();
      instalarNavegador();
      await crearComponente(true, con());
      await abrirEscaner();

      porLeer.push('0000000000000');
      await lectura();

      expect(el().querySelector('app-escaner-camara')!.textContent).toContain(
        'No encontramos un artículo con el código 0000000000000.',
      );
      expect(enEscaner('Abrir ficha')).toBeUndefined();
      expect(enEscaner('Escanear de nuevo')).toBeDefined();
    });

    it('«Escribir el código a mano» cierra el escáner, completa el buscador con el código, busca y le da el foco', async () => {
      enElCelular();
      instalarNavegador();
      await crearComponente(true, con());
      await abrirEscaner();
      porLeer.push('0000000000000');
      await lectura();
      servicio.buscar.mockClear();

      enEscaner('Escribir el código a mano').click();
      await tras();
      await refrescar();

      expect(escaner()).toBeNull();
      const campo = el().querySelector<HTMLInputElement>('#q-movil')!;
      expect(campo.value).toBe('0000000000000');
      expect(servicio.buscar.mock.calls[0][0]).toBe('0000000000000');
      expect(document.activeElement).toBe(campo);
    });

    it('«Escribir el código a mano» desde un permiso negado sólo cierra y enfoca el buscador, sin tocar lo escrito', async () => {
      enElCelular();
      instalarNavegador({ denegar: true });
      await crearComponente(true, con(), null, '/?q=tornillo');
      await abrirEscaner();
      servicio.buscar.mockClear();

      enEscaner('Escribir el código a mano').click();
      await tras();

      expect(escaner()).toBeNull();
      const campo = el().querySelector<HTMLInputElement>('#q-movil')!;
      expect(campo.value).toBe('tornillo');
      expect(servicio.buscar).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(campo);
    });

    it('Esc y la ✕ cierran el escáner, apagan la cámara y devuelven el foco al botón de cámara', async () => {
      enElCelular();
      instalarNavegador();
      await crearComponente(true, con());
      await tras();
      botonEscanear()!.focus();
      await abrirEscaner();

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await tras();

      expect(escaner()).toBeNull();
      expect(pista.stop).toHaveBeenCalledTimes(1);
      expect(document.activeElement).toBe(botonEscanear());

      await abrirEscaner();
      enEscaner('Cerrar el escáner').click();
      await tras();
      expect(escaner()).toBeNull();
      expect(pista.stop).toHaveBeenCalledTimes(2);
    });

    it('al salir de la pantalla (navegar a otra) la cámara se apaga', async () => {
      enElCelular();
      instalarNavegador();
      await crearComponente(true, con());
      await abrirEscaner();
      expect(pista.stop).not.toHaveBeenCalled();

      fixture.destroy();

      expect(pista.stop).toHaveBeenCalledTimes(1);
    });

    it('si la pantalla deja de ser la del celular (se gira) con el escáner abierto, se cierra', async () => {
      let esCelular = true;
      const oyentes: (() => void)[] = [];
      vi.stubGlobal('matchMedia', (consulta: string) => ({
        get matches() {
          return esCelular && consulta.includes('639.98px');
        },
        media: consulta,
        addEventListener: (_: string, f: () => void) => oyentes.push(f),
        removeEventListener: () => undefined,
      }));
      instalarNavegador();
      await crearComponente(true, con());
      await abrirEscaner();

      esCelular = false;
      oyentes.forEach((f) => f());
      await tras();

      expect(escaner()).toBeNull();
      expect(pista.stop).toHaveBeenCalledTimes(1);
    });
  });
});
