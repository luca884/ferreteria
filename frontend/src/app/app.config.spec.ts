import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { appConfig } from './app.config';
import { AuthService } from './core/auth/auth.service';
import { Articulo } from './features/articulos/articulo.models';
import { Articulos } from './features/articulos/articulos';
import { ArticulosService } from './features/articulos/articulos.service';
import { Rubros } from './features/rubros/rubros';
import { RubrosService } from './features/rubros/rubros.service';

/** Con la configuración real de la app (no la de test por defecto): los números van en es-AR. */
const NBSP = '\u00a0';

describe('appConfig: locale es-AR', () => {
  const articulo: Articulo = {
    id: 1,
    codigoInterno: 'TOR-001',
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: 'Tornillo',
    rubroId: null,
    proveedorId: null,
    costo: 1234.5,
    margenPorcentaje: 35.5,
    alicuotaIva: 'IVA_21',
    precioFinal: 170,
    unidad: 'METRO',
    stockActual: 12.5,
    stockMinimo: 0,
    ubicacion: null,
    activo: true,
  };

  function configurar(extra: unknown[]) {
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        { provide: AuthService, useValue: { esDueno: signal(true) } },
        ...(extra as []),
      ],
    });
  }

  it('el listado de artículos muestra los precios con coma decimal y punto de miles', async () => {
    configurar([
      {
        provide: ArticulosService,
        useValue: {
          buscar: vi.fn(() =>
            of({ items: [articulo], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          ),
        },
      },
    ]);
    const fixture = TestBed.createComponent(Articulos);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const celda = (selector: string) => (el.querySelector(selector)?.textContent ?? '').trim();

    expect(celda('.precio')).toBe(`$${NBSP}170,00`);
    expect(celda('.costo')).toBe(`$${NBSP}1.234,50`);
    expect(celda('.margen')).toBe(`35,5${NBSP}%`);
    expect(celda('.stock')).toBe('12,5 metros');
  });

  it('el listado de rubros sigue mostrando el margen en es-AR', async () => {
    configurar([
      {
        provide: RubrosService,
        useValue: {
          listar: vi.fn(() => of([{ id: 1, nombre: 'Bulonería', margenPorcentaje: 32.5 }])),
        },
      },
    ]);
    const fixture = TestBed.createComponent(Rubros);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const fila = (fixture.nativeElement as HTMLElement).querySelector('tbody tr')!;
    expect(fila.textContent).toContain('Bulonería');
    expect(fila.textContent).toContain(`32,5${NBSP}%`);
  });
});
