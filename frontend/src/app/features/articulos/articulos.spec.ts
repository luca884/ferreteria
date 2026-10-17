import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { By } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { Avisos } from '../../core/avisos/avisos';
import { AuthService } from '../../core/auth/auth.service';
import { Articulo } from './articulo.models';
import { Articulos } from './articulos';
import { ArticulosService } from './articulos.service';
import { DialogoAjusteStock } from './dialogo-ajuste-stock';

registerLocaleData(localeEsAr);

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
  };
  let avisos: Avisos;
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
    };
    await TestBed.configureTestingModule({
      imports: [Articulos],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ArticulosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno } },
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
    expect(servicio.buscar).toHaveBeenCalledWith('', 0, 20, false);
    expect(filas()).toHaveLength(2);
  });

  it('muestra código, descripción, costo, margen, IVA, precio final y stock con su unidad', async () => {
    await crearComponente(
      false,
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
    expect(texto('.costo')).toBe(`$${NBSP}100,00`);
    expect(texto('.margen')).toBe(`35${NBSP}%`);
    expect(texto('.iva')).toBe('10,5 %');
    expect(texto('.precio')).toBe(`$${NBSP}170,00`);
    expect(texto('.stock')).toBe('12,5 metros');
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
    await crearComponente(false, pagina([articulo(1, { costo: 10.8, moneda: 'USD', precioFinal: 1744 })]));

    expect(texto('.costo')).toBe(`US$${NBSP}10,80`);
    expect(texto('.precio')).toBe(`$${NBSP}1.744,00`);
  });

  it('muestra el costo de un artículo en pesos con $', async () => {
    await crearComponente(false, pagina([articulo(1, { costo: 1744, moneda: 'ARS' })]));

    expect(texto('.costo')).toBe(`$${NBSP}1.744,00`);
  });

  it('el Vendedor ve costo y margen', async () => {
    await crearComponente(false);

    expect(el().textContent).toContain('Costo');
    expect(el().textContent).toContain('Margen');
    expect(texto('.costo')).toBe(`$${NBSP}100,00`);
    expect(texto('.margen')).toBe(`35${NBSP}%`);
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
    expect(servicio.buscar).toHaveBeenLastCalledWith('', 1, 20, false);

    servicio.buscar.mockReturnValue(of(pagina([articulo(9)], 0, 1, 1)));
    await buscarTexto('  torni ');

    expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 0, 20, false);
    expect(texto('.codigo')).toBe('COD-9');
  });

  it('el buscador no autocompleta ni corrige y su ejemplo termina en «…»', async () => {
    await crearComponente(true);

    const input = el().querySelector<HTMLInputElement>('#q')!;
    expect(input.name).toBe('q');
    expect(input.getAttribute('autocomplete')).toBe('off');
    expect(input.getAttribute('spellcheck')).toBe('false');
    expect(input.placeholder.endsWith('…')).toBe(true);
  });

  it('pagina hacia adelante y hacia atrás conservando la búsqueda', async () => {
    await crearComponente(true, pagina([articulo(1)], 0, 3, 45));
    servicio.buscar.mockReturnValue(of(pagina([articulo(5)], 0, 3, 45)));
    await buscarTexto('torni');
    servicio.buscar.mockReturnValue(of(pagina([articulo(21)], 1, 3, 45)));

    await clickEn('button.siguiente');
    expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 1, 20, false);
    expect(texto('.codigo')).toBe('COD-21');

    servicio.buscar.mockReturnValue(of(pagina([articulo(5)], 0, 3, 45)));
    await clickEn('button.anterior');
    expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 0, 20, false);
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

    expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, true);
    expect(texto('.estado', 0)).toBe('Activo');
    expect(texto('.estado', 1)).toBe('De baja');
  });

  describe('búsqueda, página e inactivos en la dirección', () => {
    it('al entrar los lee de la dirección (volver de editar conserva el contexto)', async () => {
      await crearComponente(true, pagina([articulo(41)], 2, 3, 45), null, '/?q=torni&pagina=3&inactivos=1');

      expect(servicio.buscar).toHaveBeenCalledWith('torni', 2, 20, true);
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

      expect(servicio.buscar).toHaveBeenCalledWith('', 0, 20, false);
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
      expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 0, 20, true);
      expect(navegar).toHaveBeenLastCalledWith([], {
        relativeTo: route,
        queryParams: { q: 'torni', inactivos: 1 },
        replaceUrl: true,
      });
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
      expect(el().querySelectorAll('tbody button, tbody a')).toHaveLength(0);
    });

    it('sin fila elegida, Editar, Ajustar stock y Dar de baja quedan deshabilitados', async () => {
      await crearComponente(true);

      expect(elegidas()).toHaveLength(0);
      expect(barra('button.editar')!.disabled).toBe(true);
      expect(barra('button.ajustar-stock')!.disabled).toBe(true);
      expect(barra('button.desactivar')!.disabled).toBe(true);
    });

    it('clic en una fila la elige: resaltada, aria-selected, habilita las acciones y no navega', async () => {
      await crearComponente(true);

      await elegir(1);

      expect(elegidas()).toEqual([filas()[1]]);
      expect(filas()[0].getAttribute('aria-selected')).toBe('false');
      expect(barra('button.editar')!.disabled).toBe(false);
      expect(barra('button.ajustar-stock')!.disabled).toBe(false);
      expect(barra('button.desactivar')!.disabled).toBe(false);
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

    it('elegido de baja: Reactivar en vez de Dar de baja y Ajustar stock deshabilitado con el motivo a la vista', async () => {
      await crearComponente(true, pagina([articulo(1), articulo(2, { activo: false, descripcion: 'Tuerca vieja' })]));

      await elegir(1);

      expect(barra('button.desactivar')).toBeNull();
      expect(barra('button.reactivar')).not.toBeNull();
      const ajustar = barra('button.ajustar-stock')!;
      expect(ajustar.disabled).toBe(true);
      const motivo = el().querySelector(`#${ajustar.getAttribute('aria-describedby')}`);
      expect(motivo?.textContent?.trim()).toBe('«Tuerca vieja» está dado de baja. Reactivalo para ajustar el stock.');

      await elegir(0);
      expect(barra('button.desactivar')).not.toBeNull();
      expect(barra('button.reactivar')).toBeNull();
      expect(barra('button.ajustar-stock')!.disabled).toBe(false);
    });

    it('el Vendedor no ve ninguna acción ni elige filas', async () => {
      await crearComponente(false, pagina([articulo(7)]));

      expect(el().querySelector('a.nuevo')).toBeNull();
      expect(el().querySelector('button.editar')).toBeNull();
      expect(el().querySelector('button.desactivar')).toBeNull();
      expect(el().querySelector('button.ajustar-stock')).toBeNull();
      expect(el().textContent).not.toContain('Dar de baja');
      expect(el().textContent).not.toContain('Acciones');

      await elegir(0);
      expect(filas()[0].getAttribute('tabindex')).toBeNull();
      expect(filas()[0].getAttribute('aria-selected')).toBeNull();
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

    it('para el Vendedor Enter y doble clic no hacen nada', async () => {
      await crearComponente(false, pagina([articulo(7)]));

      await teclaEnFila(0, 'Enter');
      filas()[0].dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));

      expect(navegar).not.toHaveBeenCalledWith(['/articulos', 7], expect.anything());
    });
  });

  /** Elige la fila, toca «Dar de baja» en la barra y, en el diálogo propio, el botón indicado. */
  async function darDeBaja(fila: number, boton: 'confirmar' | 'cancelar' = 'confirmar') {
    await elegir(fila);
    await clickEn('app-barra-acciones button.desactivar');
    el().querySelector<HTMLButtonElement>(`app-confirmacion button.${boton}`)!.click();
    await refrescar();
  }

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
    expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, false);
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

    expect(servicio.buscar).toHaveBeenNthCalledWith(1, '', 1, 20, false);
    expect(servicio.buscar).toHaveBeenNthCalledWith(2, '', 0, 20, false);
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

      expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, false);
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
    };
    await TestBed.configureTestingModule({
      imports: [Articulos],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ArticulosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Articulos);
    await refrescar();

    expect(el().querySelector('.error')?.textContent).toContain('Error interno');
    expect(el().querySelector('table')).toBeNull();
  });

  it('usa un mensaje genérico si la carga falla sin mensaje del backend', async () => {
    esDueno = signal(false);
    servicio = {
      buscar: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 0 }))),
      desactivar: vi.fn(),
      reactivar: vi.fn(),
      ajustarStock: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [Articulos],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ArticulosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Articulos);
    await refrescar();

    expect(el().querySelector('.error')?.textContent).toContain(
      'No se pudieron cargar los artículos.',
    );
  });
});
