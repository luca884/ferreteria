import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { formatearCosto } from '../../core/formato/monto';
import { StockBajoMinimo } from './stock-bajo-minimo';
import { ArticuloBajoMinimo } from './stock-bajo-minimo.models';
import { StockBajoMinimoService } from './stock-bajo-minimo.service';

describe('StockBajoMinimo', () => {
  const filas: ArticuloBajoMinimo[] = [
    {
      articuloId: 3,
      codigoInterno: 'C-003',
      codigoProveedor: null,
      descripcion: 'Arandela',
      rubro: null,
      proveedor: null,
      costo: 100,
      moneda: 'ARS',
      unidad: 'UNIDAD',
      stockActual: -3,
      stockMinimo: 5,
      aReponer: 8,
      ultimaCompraFecha: null,
      ultimaCompraProveedor: null,
    },
    {
      articuloId: 1,
      codigoInterno: 'A-001',
      codigoProveedor: 'AN-77',
      descripcion: 'Cable 2,5 mm',
      rubro: 'Pinturas',
      proveedor: 'Andina Norte',
      costo: 10.5,
      moneda: 'USD',
      unidad: 'METRO',
      stockActual: 12.5,
      stockMinimo: 50,
      aReponer: 37.5,
      ultimaCompraFecha: '2026-10-12',
      ultimaCompraProveedor: 'Bulonera Sur',
    },
  ];

  let fixture: ComponentFixture<StockBajoMinimo>;
  let listar: ReturnType<typeof vi.fn>;

  async function crear(dueno = true, respuesta = vi.fn(() => of(filas))) {
    listar = respuesta;
    await TestBed.configureTestingModule({
      imports: [StockBajoMinimo],
      providers: [
        provideRouter([]),
        { provide: StockBajoMinimoService, useValue: { listar } },
        {
          provide: AuthService,
          useValue: { esDueno: signal(dueno), sesion: signal({ ferreteriaNombre: 'Ferretería El Tornillo' }) },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(StockBajoMinimo);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) => el().querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  const filasTabla = () => Array.from(el().querySelectorAll('tbody tr'));
  const celdas = (fila: number) => Array.from(filasTabla()[fila].querySelectorAll('td')).map((c) => c.textContent?.trim());

  afterEach(() => vi.restoreAllMocks());

  it('el título va en la barra y pide el listado ordenado por descripción', async () => {
    await crear();

    expect(texto('app-barra-acciones h1')).toBe('Stock bajo mínimo');
    expect(listar).toHaveBeenCalledWith('DESCRIPCION');
  });

  it('lista cada artículo con código, código del proveedor, descripción, rubro, proveedor, última compra, costo, stock, mínimo y cuánto reponer', async () => {
    await crear();

    expect(Array.from(el().querySelectorAll('thead th')).map((th) => th.textContent?.trim())).toEqual([
      'Código',
      'Cód. proveedor',
      'Descripción',
      'Rubro',
      'Proveedor',
      'Última compra',
      'Costo',
      'Stock',
      'Mínimo',
      'A reponer',
    ]);
    expect(filasTabla()).toHaveLength(2);
    expect(celdas(0)).toEqual(['C-003', '—', 'Arandela', '—', '—', '—', formatearCosto(100, 'ARS'), '-3', '5', '8']);
    expect(celdas(1)).toEqual([
      'A-001',
      'AN-77',
      'Cable 2,5 mm',
      'Pinturas',
      'Andina Norte',
      '12/10/2026 · Bulonera Sur',
      formatearCosto(10.5, 'USD'),
      '12,5',
      '50',
      '37,5',
    ]);
  });

  it('el impreso lleva el título de GDS, la ferretería, el orden y la fecha en que se emitió', async () => {
    await crear();

    const impreso = el().querySelector('.stock-imprimible')!;
    expect(impreso.querySelector('h2')?.textContent?.trim()).toBe('Listado de artículos bajo stock mínimo');
    expect(texto('.stock-imprimible .ferreteria')).toBe('Ferretería El Tornillo');
    expect(texto('.stock-imprimible .orden')).toBe('Ordenado por descripción');
    expect(texto('.stock-imprimible .emitido')).toMatch(/^Emitido el \d{2}\/\d{2}\/\d{4} \d{2}:\d{2}$/);
    expect(impreso.querySelector('table')).not.toBeNull();
  });

  it('cambiar el orden vuelve a pedir el listado en ese orden', async () => {
    await crear();
    const orden = el().querySelector<HTMLSelectElement>('select[name="orden"]')!;

    orden.value = 'PROVEEDOR';
    orden.dispatchEvent(new Event('change'));
    await refrescar();

    expect(listar).toHaveBeenLastCalledWith('PROVEEDOR');
    expect(texto('.stock-imprimible .orden')).toBe('Ordenado por proveedor');
  });

  it('Imprimir de la barra imprime el listado', async () => {
    await crear();
    const imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);

    el().querySelector<HTMLButtonElement>('app-barra-acciones button.imprimir')!.click();

    expect(imprimir).toHaveBeenCalledOnce();
  });

  it('sin artículos bajo el mínimo lo dice', async () => {
    await crear(true, vi.fn(() => of([])));

    expect(texto('.estado-vacio')).toBe('No hay artículos bajo el stock mínimo.');
    expect(el().querySelector('tbody')).toBeNull();
  });

  it('si no carga lo avisa, no deja imprimir y Reintentar vuelve a pedir', async () => {
    const respuesta = vi
      .fn()
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })))
      .mockReturnValue(of(filas));
    await crear(true, respuesta);

    expect(texto('[role="alert"] span')).toBe('No se pudo cargar el listado.');
    expect(el().querySelector<HTMLButtonElement>('button.imprimir')!.disabled).toBe(true);
    el().querySelector<HTMLButtonElement>('button.reintentar')!.click();
    await refrescar();

    expect(filasTabla()).toHaveLength(2);
  });

  it('al Vendedor le avisa que es del Dueño y no pide nada', async () => {
    await crear(false);

    expect(texto('.estado-vacio')).toBe('El listado de stock bajo mínimo es solo del Dueño.');
    expect(listar).not.toHaveBeenCalled();
    expect(el().querySelector('button.imprimir')).toBeNull();
  });
});
