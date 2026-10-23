import { registerLocaleData } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Reportes } from './reportes';
import { Resumen } from './reportes.models';
import { ReportesService } from './reportes.service';

registerLocaleData(localeEsAr);

describe('Reportes de ventas', () => {
  const resumen: Resumen = {
    desde: '2026-10-22',
    hasta: '2026-10-22',
    cantidad: 3,
    total: 345,
    neto: 285.13,
    iva: 59.87,
    costo: 100,
    ganancia: 102.48,
    ventasSinCosto: 1,
  };

  const filas: Record<string, unknown[]> = {
    'por-usuario': [{ usuarioId: 2, nombre: 'Vendedor Ventas', cantidad: 2, total: 300, ganancia: 85.29, ventasSinCosto: 1 }],
    'por-medio-pago': [{ formaPago: 'Efectivo', medio: 'EFECTIVO', cantidad: 2, monto: 300 }],
    'por-periodo': [{ periodo: '2026-10-22', cantidad: 3, total: 345, ganancia: 102.48, ventasSinCosto: 1 }],
    'por-articulo': [
      { articuloId: 7, descripcion: 'Tornillo', cantidad: 3, total: 300, ganancia: 85.29, renglonesSinCosto: 1, renglones: 2 },
    ],
    'por-rubro': [{ rubro: 'Bulones', renglones: 2, total: 300, ganancia: 85.29, renglonesSinCosto: 1 }],
    'con-descuento': [
      {
        ventaId: 9,
        numero: 15,
        fecha: '2026-10-22T10:30:00',
        usuario: 'Dueno Ventas',
        cliente: null,
        descuentoPorcentaje: 10,
        descuento: 5,
        total: 45,
      },
    ],
  };

  let fixture: ComponentFixture<Reportes>;
  let servicio: Record<'resumen' | 'filas', ReturnType<typeof vi.fn>>;

  async function crear(dueno = true) {
    servicio = {
      resumen: vi.fn(() => of(resumen)),
      filas: vi.fn((tipo: string) => of({ desde: '2026-10-22', hasta: '2026-10-22', filas: filas[tipo] })),
    };
    await TestBed.configureTestingModule({
      imports: [Reportes],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ReportesService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Reportes);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const select = (id: string) => el().querySelector<HTMLSelectElement>(`#${id}`)!;
  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
  const primeraFila = () =>
    Array.from(el().querySelectorAll('tbody tr:first-child td'))
      .map((td) => normal(td.textContent))
      .join(' ');

  async function elegir(tipo: string) {
    select('reporte').value = tipo;
    select('reporte').dispatchEvent(new Event('change'));
    fixture.detectChanges();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('arranca con el resumen de hoy: ventas, costo, IVA, ganancia, comprobantes y el aviso de las ventas sin costo', async () => {
    await crear();

    expect(servicio['resumen']).toHaveBeenCalledWith(input('desde').value, input('hasta').value);
    expect(input('desde').value).toBe(input('hasta').value);
    const texto = Array.from(el().querySelectorAll('.resumen > div'))
      .map((d) => `${normal(d.querySelector('dt')?.textContent)} ${normal(d.querySelector('dd')?.textContent)}`)
      .join(' · ');
    expect(texto).toContain('Ventas $ 345,00');
    expect(texto).toContain('Costo $ 100,00');
    expect(texto).toContain('IVA $ 59,87');
    expect(texto).toContain('Ganancia $ 102,48');
    expect(texto).toContain('Comprobantes 3');
    expect(normal(el().querySelector('.aviso-costo')?.textContent)).toBe(
      '1 venta sin costo registrado: la ganancia no la incluye.',
    );
  });

  it('ofrece los seis reportes, como el menú Ventas de GDS', async () => {
    await crear();

    const opciones = Array.from(select('reporte').options).map((o) => o.textContent?.trim());
    expect(opciones).toEqual([
      'Resumen',
      'Por usuario',
      'Por medio de pago',
      'Por día o mes',
      'Por artículo',
      'Por rubro',
      'Con descuento',
    ]);
  });

  it('por usuario muestra cada usuario con cantidad, total y ganancia', async () => {
    await crear();
    await elegir('por-usuario');

    expect(servicio['filas']).toHaveBeenCalledWith('por-usuario', input('desde').value, input('hasta').value, undefined);
    const fila = primeraFila();
    expect(fila).toBe('Vendedor Ventas 2 $ 300,00 $ 85,29');
    expect(normal(el().querySelector('.aviso-costo')?.textContent)).toBe(
      '1 venta sin costo registrado: la ganancia no la incluye.',
    );
  });

  it('por medio de pago muestra la forma de pago, los pagos y el monto', async () => {
    await crear();
    await elegir('por-medio-pago');

    expect(primeraFila()).toBe('Efectivo 2 $ 300,00');
  });

  it('por día o mes deja elegir cómo agrupar y lo manda', async () => {
    await crear();
    select('reporte').value = 'por-periodo';
    select('reporte').dispatchEvent(new Event('change'));
    fixture.detectChanges();
    select('agrupar').value = 'MES';
    select('agrupar').dispatchEvent(new Event('change'));
    await elegir('por-periodo');

    expect(servicio['filas']).toHaveBeenCalledWith('por-periodo', input('desde').value, input('hasta').value, 'MES');
    expect(primeraFila()).toBe('22/10/2026 3 $ 345,00 $ 102,48');
  });

  it('por artículo muestra la cantidad vendida y avisa los renglones sin costo', async () => {
    await crear();
    await elegir('por-articulo');

    expect(primeraFila()).toBe('Tornillo 3 $ 300,00 $ 85,29');
    expect(normal(el().querySelector('.aviso-costo')?.textContent)).toBe(
      '1 renglón sin costo registrado: la ganancia no lo incluye.',
    );
  });

  it('por rubro muestra el rubro con sus renglones', async () => {
    await crear();
    await elegir('por-rubro');

    expect(primeraFila()).toBe('Bulones 2 $ 300,00 $ 85,29');
  });

  it('con descuento muestra la venta, quién la hizo, el descuento y el total', async () => {
    await crear();
    await elegir('con-descuento');

    expect(primeraFila()).toBe(
      '15 22/10 10:30 Dueno Ventas Consumidor Final 10 % $ 5,00 $ 45,00',
    );
  });

  it('sin filas dice que no hubo ventas en el rango', async () => {
    await crear();
    servicio['filas'].mockReturnValue(of({ desde: '2026-10-22', hasta: '2026-10-22', filas: [] }));
    await elegir('por-usuario');

    expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('No hubo ventas entre esas fechas.');
  });

  it('«Hasta» antes de «Desde» se avisa sin pedir nada al servidor', async () => {
    await crear();
    servicio['resumen'].mockClear();
    input('desde').value = '2026-10-22';
    input('desde').dispatchEvent(new Event('input'));
    input('hasta').value = '2026-10-21';
    input('hasta').dispatchEvent(new Event('input'));
    await elegir('resumen');

    expect(servicio['resumen']).not.toHaveBeenCalled();
    expect(normal(el().querySelector('[role="alert"]')?.textContent)).toBe(
      'La fecha «Hasta» no puede ser anterior a «Desde».',
    );
  });

  it('un error del servidor se muestra con su mensaje', async () => {
    await crear();
    servicio['filas'].mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { message: 'Datos inválidos', errors: { desde: 'El reporte admite hasta 366 días.' } } })),
    );
    await elegir('por-rubro');

    expect(normal(el().querySelector('[role="alert"]')?.textContent)).toBe('El reporte admite hasta 366 días.');
  });

  it('el vendedor ve que los reportes son del dueño y no se pide nada', async () => {
    await crear(false);

    expect(servicio['resumen']).not.toHaveBeenCalled();
    expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('Los reportes son solo del dueño.');
  });
});
