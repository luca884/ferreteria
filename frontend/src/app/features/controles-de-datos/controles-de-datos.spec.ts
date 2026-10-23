import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { formatearCosto, formatearMonto } from '../../core/formato/monto';
import { ControlesDeDatos } from './controles-de-datos';
import { ArticuloEnControl } from './controles-de-datos.models';
import { ControlesDeDatosService } from './controles-de-datos.service';

describe('ControlesDeDatos', () => {
  const filas: ArticuloEnControl[] = [
    {
      articuloId: 2,
      codigoInterno: 'A-002',
      descripcion: 'Abrazadera',
      rubro: null,
      proveedor: null,
      costo: 0,
      moneda: 'ARS',
      alicuotaIva: 'IVA_21',
      precioFinal: 50,
      precioManual: false,
    },
    {
      articuloId: 5,
      codigoInterno: 'S-005',
      descripcion: 'Sierra USD',
      rubro: 'Bulones',
      proveedor: 'Bulonera Sur',
      costo: 1.5,
      moneda: 'USD',
      alicuotaIva: 'IVA_10_5',
      precioFinal: 1000,
      precioManual: true,
    },
  ];

  let fixture: ComponentFixture<ControlesDeDatos>;
  let listar: ReturnType<typeof vi.fn>;

  async function crear(dueno = true, respuesta = vi.fn(() => of(filas))) {
    listar = respuesta;
    await TestBed.configureTestingModule({
      imports: [ControlesDeDatos],
      providers: [
        provideRouter([]),
        { provide: ControlesDeDatosService, useValue: { listar } },
        {
          provide: AuthService,
          useValue: { esDueno: signal(dueno), sesion: signal({ ferreteriaNombre: 'Ferretería El Tornillo' }) },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ControlesDeDatos);
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
  const celdas = (fila: number) =>
    // Junta solo los espacios del template: los montos llevan un espacio duro después del signo.
    Array.from(filasTabla()[fila].querySelectorAll('td')).map((c) => c.textContent?.replace(/[ \n]+/g, ' ').trim());

  afterEach(() => vi.restoreAllMocks());

  it('el título va en la barra y arranca por el control de costo en cero, el primero de GDS', async () => {
    await crear();

    expect(texto('app-barra-acciones h1')).toBe('Controles de datos');
    expect(listar).toHaveBeenCalledWith('COSTO_CERO');
    const opciones = Array.from(el().querySelectorAll<HTMLOptionElement>('select[name="control"] option'));
    expect(opciones.map((o) => o.textContent?.trim())).toEqual([
      'Costo en cero',
      'Sin rubro',
      'Margen negativo',
      'IVA en 0 %',
    ]);
  });

  it('lista cada artículo con código, descripción, rubro, proveedor, costo, IVA y precio (marca el manual)', async () => {
    await crear();

    expect(filasTabla()).toHaveLength(2);
    expect(celdas(0)).toEqual(['A-002', 'Abrazadera', '—', '—', formatearCosto(0, 'ARS'), '21 %', formatearMonto(50)]);
    expect(celdas(1)).toEqual([
      'S-005',
      'Sierra USD',
      'Bulones',
      'Bulonera Sur',
      formatearCosto(1.5, 'USD'),
      '10,5 %',
      `${formatearMonto(1000)} manual`,
    ]);
  });

  it('cada fila lleva a la ficha del artículo para corregirlo', async () => {
    await crear();

    const enlace = filasTabla()[1].querySelector<HTMLAnchorElement>('a')!;
    expect(enlace.textContent?.trim()).toBe('Sierra USD');
    expect(enlace.getAttribute('href')).toBe('/articulos/5');
  });

  it('el impreso lleva el título, la ferretería, el control con su explicación y la fecha en que se emitió', async () => {
    await crear();

    const impreso = el().querySelector('.controles-imprimible')!;
    expect(impreso.querySelector('h2')?.textContent?.trim()).toBe('Controles de datos');
    expect(texto('.controles-imprimible .ferreteria')).toBe('Ferretería El Tornillo');
    expect(texto('.controles-imprimible .control')).toBe('Costo en cero: artículos activos con el costo en $ 0.');
    expect(texto('.controles-imprimible .emitido')).toMatch(/^Emitido el \d{2}\/\d{2}\/\d{4} \d{2}:\d{2}$/);
    expect(impreso.querySelector('table')).not.toBeNull();
  });

  it('cambiar el control vuelve a pedir el listado de ese control', async () => {
    await crear();
    const control = el().querySelector<HTMLSelectElement>('select[name="control"]')!;

    control.value = 'MARGEN_NEGATIVO';
    control.dispatchEvent(new Event('change'));
    await refrescar();

    expect(listar).toHaveBeenLastCalledWith('MARGEN_NEGATIVO');
    expect(texto('.controles-imprimible .control')).toBe(
      'Margen negativo: el costo supera el precio sin IVA, se vende a pérdida.',
    );
  });

  it('Imprimir de la barra imprime el listado', async () => {
    await crear();
    const imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);

    el().querySelector<HTMLButtonElement>('app-barra-acciones button.imprimir')!.click();

    expect(imprimir).toHaveBeenCalledOnce();
  });

  it('sin artículos en el control lo dice', async () => {
    await crear(true, vi.fn(() => of([])));

    expect(texto('.estado-vacio')).toBe('Ningún artículo cae en este control.');
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

    expect(texto('.estado-vacio')).toBe('Los controles de datos son solo del Dueño.');
    expect(listar).not.toHaveBeenCalled();
    expect(el().querySelector('button.imprimir')).toBeNull();
  });
});
