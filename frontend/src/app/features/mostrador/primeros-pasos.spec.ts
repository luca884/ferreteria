import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { Articulo } from '../articulos/articulo.models';
import { ArticulosService } from '../articulos/articulos.service';
import { PrimerosPasos } from './primeros-pasos';

describe('PrimerosPasos', () => {
  let fixture: ComponentFixture<PrimerosPasos>;
  let articulos: { buscar: ReturnType<typeof vi.fn> };

  const pagina = (totalElements: number): PageResponse<Articulo> => ({
    items: [],
    page: 0,
    size: 1,
    totalElements,
    totalPages: totalElements,
  });

  async function crear(rol: 'DUENO' | 'VENDEDOR', respuesta = of(pagina(0))) {
    articulos = { buscar: vi.fn(() => respuesta) };
    await TestBed.configureTestingModule({
      imports: [PrimerosPasos],
      providers: [
        provideRouter([]),
        { provide: ArticulosService, useValue: articulos },
        { provide: AuthService, useValue: { sesion: signal({ rol }).asReadonly() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PrimerosPasos);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const panel = () => el().querySelector('.primeros-pasos');
  const dicho = (e: Element | null | undefined) => (e?.textContent ?? '').replace(/\s+/g, ' ').trim();

  it('el Dueño de una ferretería sin artículos ve «Primeros pasos» con los siete pasos en orden, la facturación después de los datos de la ferretería', async () => {
    await crear('DUENO');

    expect(dicho(panel()?.querySelector('h2'))).toBe('Primeros pasos');
    const pasos = Array.from(panel()!.querySelectorAll('li'));
    expect(pasos.map((p) => dicho(p))).toEqual([
      'Datos de la ferretería',
      'Configurá la facturación con ARCA',
      'Margen y redondeo',
      'Importar una lista o cargar un artículo',
      'Formas de pago',
      'Usuarios',
      'Abrir la caja',
    ]);
    expect(Array.from(panel()!.querySelectorAll('a')).map((a) => [dicho(a), a.getAttribute('href')])).toEqual([
      ['Datos de la ferretería', '/configuracion/datos-fiscales'],
      ['Configurá la facturación con ARCA', '/configuracion/facturacion'],
      ['Margen y redondeo', '/configuracion/precios'],
      ['Importar una lista', '/importaciones'],
      ['cargar un artículo', '/articulos/nuevo'],
      ['Formas de pago', '/configuracion/formas-pago'],
      ['Usuarios', '/usuarios'],
      ['Abrir la caja', '/caja'],
    ]);
  });

  it('pregunta por un solo artículo, contando también los dados de baja', async () => {
    await crear('DUENO');

    expect(articulos.buscar).toHaveBeenCalledExactlyOnceWith('', 0, 1, true);
  });

  it('con un artículo o más no aparece', async () => {
    await crear('DUENO', of(pagina(1)));

    expect(panel()).toBeNull();
  });

  it('el Vendedor nunca lo ve y ni siquiera consulta los artículos', async () => {
    await crear('VENDEDOR');

    expect(panel()).toBeNull();
    expect(articulos.buscar).not.toHaveBeenCalled();
  });

  it('si no se pudo saber cuántos artículos hay, no aparece', async () => {
    await crear('DUENO', throwError(() => new Error('sin red')) as never);

    expect(panel()).toBeNull();
  });
});
