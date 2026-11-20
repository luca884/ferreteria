import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { ArticulosService } from '../articulos/articulos.service';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { BorradorDeFactura, PlanCompra } from './compra.models';
import { ComprasService } from './compras.service';
import { NuevaCompra } from './nueva-compra';

registerLocaleData(localeEsAr);

describe('Nueva compra en el celular (T16)', () => {
  let fixture: ComponentFixture<NuevaCompra>;
  const vistaPrevia = vi.fn();
  const leerFactura = vi.fn();
  const listar = vi.fn(() => of([{ id: 4, nombre: 'Bulonera Sur', activo: true }]));
  const q = <T extends HTMLElement>(selector: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<T>(selector);
  const secciones = () => [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.compra-paso-titulo button')];
  const expansion = () => secciones().map((boton) => boton.getAttribute('aria-expanded'));
  const ver = () =>
    [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Ver vista previa')!;

  function simularPantalla(celular: boolean) {
    vi.stubGlobal('matchMedia', (consulta: string) => ({ matches: celular && consulta.includes('639.98px'), media: consulta }));
  }

  async function crear(celular = true) {
    if (celular) simularPantalla(true);
    vistaPrevia.mockReset();
    leerFactura.mockReset();
    listar.mockClear();
    await TestBed.configureTestingModule({
      imports: [NuevaCompra],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: AuthService, useValue: { esDueno: () => true } },
        { provide: ArticulosService, useValue: {} },
        { provide: ComprasService, useValue: { vistaPrevia, leerFactura } },
        { provide: ProveedoresService, useValue: { listar } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(NuevaCompra);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function tocar(boton: HTMLButtonElement) {
    boton.click();
    await refrescar();
  }

  async function cargarPorPdf(renglones?: BorradorDeFactura['renglones']) {
    const borrador: BorradorDeFactura = {
      proveedorId: 4, proveedorNombre: 'Bulonera Sur', cuits: [], tipo: 'FA', puntoVenta: 1, numero: 4512,
      fecha: '2026-11-15', recargos: [{ nombre: 'Embalaje', porcentaje: 2, importe: 2 }],
      renglones: renglones ?? [{
        codigo: 'TOR-1', descripcion: 'Tornillo', cantidad: 2, costoUnitario: 50, neto: 100, articuloId: 1,
        articuloDescripcion: 'Tornillo', articuloCodigoInterno: 'TOR-1', articuloMoneda: 'ARS', cantidadNoEntera: false,
      }],
      sumaNetos: 100, control: 'CUADRA', cantidadNoEntera: false,
    };
    leerFactura.mockReturnValue(of(borrador));
    const entrada = q<HTMLInputElement>('app-selector-archivo input[type="file"]')!;
    const archivo = new File(['factura'], 'factura.pdf', { type: 'application/pdf' });
    const abrir = vi.spyOn(entrada, 'click').mockImplementation(() => {
      Object.defineProperty(entrada, 'files', { value: [archivo] });
      entrada.dispatchEvent(new Event('change'));
    });
    await tocar(q<HTMLButtonElement>('app-selector-archivo .elegir-archivo')!);
    expect(abrir).toHaveBeenCalledOnce();
    expect(leerFactura).toHaveBeenCalledExactlyOnceWith(archivo);
  }

  afterEach(() => vi.unstubAllGlobals());

  it('abre sólo Comprobante al entrar y pliega cada tarjeta sin perder los campos', async () => {
    await crear();
    expect(secciones().map((boton) => boton.firstElementChild?.textContent?.trim())).toEqual([
      'Comprobante', 'Artículos', 'Recargos del costo',
    ]);
    expect(expansion()).toEqual(['true', 'false', 'false']);
    expect(q('.compra-paso-titulo .compra-paso-resumen')?.textContent?.trim()).toBe('0');
    expect(q('.recargos-costo .compra-paso-resumen')?.textContent?.trim()).toBe('ninguno');
    const numero = q<HTMLInputElement>('input[name="numero"]')!;
    numero.value = '4512';
    numero.dispatchEvent(new Event('input'));
    await tocar(secciones()[0]);
    expect(expansion()).toEqual(['false', 'false', 'false']);
    await tocar(secciones()[1]);
    expect(expansion()).toEqual(['false', 'true', 'false']);
    await tocar(secciones()[2]);
    expect(expansion()).toEqual(['false', 'true', 'true']);
    await tocar(secciones()[0]);
    expect(expansion()).toEqual(['true', 'true', 'true']);
    expect(q('input[name="numero"]')).toBe(numero);
    expect(numero.value).toBe('4512');
  });

  it('el pie sigue validando al tocarlo vacío y pide la misma vista previa con el PDF cargado', async () => {
    await crear();
    expect(ver().parentElement?.matches('.ficha-pie.en-pagina')).toBe(true);
    expect(ver().disabled).toBe(false);
    await tocar(ver());
    expect(q('[role="alert"]')?.textContent).toContain('Elegí el proveedor.');
    expect(vistaPrevia).not.toHaveBeenCalled();

    await cargarPorPdf();
    expect(q('.compra-paso-titulo .compra-paso-resumen')?.textContent?.trim()).toBe('1');
    expect(q('.recargos-costo .compra-paso-resumen')?.textContent?.trim()).toBe('1');
    expect(expansion()).toEqual(['true', 'false', 'false']);

    vistaPrevia.mockReturnValue(new Subject<PlanCompra>());
    await tocar(ver());
    expect(vistaPrevia).toHaveBeenCalledExactlyOnceWith({
      proveedorId: 4, tipo: 'FA', puntoVenta: 1, numero: 4512, fecha: '2026-11-15',
      renglones: [{ articuloId: 1, cantidad: 2, costoUnitario: 50 }], recargos: [{ nombre: 'Embalaje', porcentaje: 2 }],
    });
    expect(ver().disabled).toBe(true);
  });

  it('un error de recargos del servidor abre su sección plegada, porque es el único lugar donde se muestra', async () => {
    await crear();
    await cargarPorPdf();
    expect(expansion()).toEqual(['true', 'false', 'false']);
    vistaPrevia.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { errors: { recargosCosto: 'Un recargo no puede ser 0 %.' } } })),
    );
    await tocar(ver());
    expect(expansion()).toEqual(['true', 'false', 'true']);
    expect(q('.recargos-costo .campo-error')?.textContent).toContain('Un recargo no puede ser 0 %.');
  });

  it('en escritorio conserva los títulos, las secciones abiertas y la validación en línea', async () => {
    await crear(false);
    expect(secciones()).toHaveLength(0);
    expect(q('.compra-paso-titulo, .ficha-pie, .compra-paso-resumen')).toBeNull();
    expect([...(fixture.nativeElement as HTMLElement).querySelectorAll('.paso > h2')].map((h) => h.textContent)).toEqual([
      'Comprobante', 'Artículos', 'Recargos del costo',
    ]);
    expect(q('.paso > .encabezado')).not.toBeNull();
    expect(q('.paso > .buscador')).not.toBeNull();
    expect(q('.paso.recargos-costo > button')).not.toBeNull();
    expect(ver().parentElement?.className).toBe('acciones');
    expect(ver().disabled).toBe(false);
    await tocar(ver());
    expect(q('[role="alert"]')?.textContent).toContain('Elegí el proveedor.');
    expect(vistaPrevia).not.toHaveBeenCalled();
  });

  it.each([true, false])('mantiene la tabla y la edición de renglones (celular: %s)', async (celular) => {
    await crear(celular);
    await cargarPorPdf([{
      codigo: 'PROV-1', descripcion: 'Tornillo', cantidad: 2, costoUnitario: 50, neto: 100, articuloId: 1,
      articuloDescripcion: 'Tornillo', articuloCodigoInterno: 'TOR-1', articuloMoneda: 'USD', cantidadNoEntera: true,
    }]);
    if (celular) await tocar(secciones()[1]);
    const tabla = q('table.renglones')!;
    const fila = tabla.querySelector<HTMLTableRowElement>('tbody tr')!;
    expect(tabla.querySelector('caption')?.textContent).toBe('Renglones de la compra');
    expect(tabla.querySelector('thead th[scope="col"]')?.textContent).toBe('Artículo');
    expect(fila.getAttribute('aria-label')).toBe(celular ? 'Tornillo (USD)' : null);
    expect(fila.cells[0].textContent).toContain('TOR-1 · Tornillo (USD)');
    expect(fila.querySelector('.codigo-proveedor')?.textContent).toBe('Cód. proveedor PROV-1');
    expect(fila.querySelector('.aviso-fila')?.textContent).toContain('La cantidad no es entera');
    expect([...fila.querySelectorAll('.compra-renglon-etiqueta')].map((e) => e.textContent)).toEqual(
      celular ? ['Cantidad', 'Costo unitario sin IVA'] : [],
    );
    const cantidad = q<HTMLInputElement>('input[name="cantidad-0"][aria-label="Cantidad de Tornillo"]')!;
    const costo = q<HTMLInputElement>('input[name="costo-0"][aria-label="Costo de Tornillo"]')!;
    for (const [campo, valor] of [[cantidad, '3'], [costo, '55,25']] as const) {
      campo.value = valor;
      campo.dispatchEvent(new Event('input'));
      await refrescar();
    }
    vistaPrevia.mockReturnValue(new Subject<PlanCompra>());
    await tocar(ver());
    expect(vistaPrevia).toHaveBeenCalledExactlyOnceWith({
      proveedorId: 4, tipo: 'FA', puntoVenta: 1, numero: 4512, fecha: '2026-11-15',
      renglones: [{ articuloId: 1, cantidad: 3, costoUnitario: 55.25 }], recargos: [{ nombre: 'Embalaje', porcentaje: 2 }],
    });
    await tocar(fila.querySelector('button')!);
    expect(tabla.querySelector('tbody tr')).toBeNull();
    expect(q('.estado-vacio')?.textContent).toBe('Todavía no agregaste artículos.');
  });

  it('mantiene las tres acciones y el aviso de un renglón sin artículo', async () => {
    await crear();
    await cargarPorPdf([{
      codigo: 'PROV-2', descripcion: 'Tuerca', cantidad: 2.5, costoUnitario: 40, neto: 100, articuloId: null,
      articuloDescripcion: null, articuloCodigoInterno: null, articuloMoneda: null, cantidadNoEntera: true,
    }]);
    await tocar(secciones()[1]);
    const fila = q('ul[aria-label="Renglones sin artículo"] li')!;
    expect(fila.textContent).toContain('Sin artículo: PROV-2 — Tuerca');
    expect(fila.querySelector('.aviso-fila')?.textContent).toContain('La cantidad no es entera');
    const botones = [...fila.querySelectorAll('button')];
    expect(botones.map((b) => b.textContent?.trim())).toEqual(['Elegir artículo', 'Crear artículo', 'Quitar']);
    await tocar(botones[0]);
    expect(fila.classList.contains('resolviendo')).toBe(true);
    expect(botones[2].getAttribute('aria-label')).toBe('Quitar sin artículo PROV-2');
    await tocar(botones[2]);
    expect(q('.sin-articulo')).toBeNull();
  });

  it('mantiene los nombres de los campos de recargo y explica el tope de 5', async () => {
    await crear();
    await tocar(secciones()[2]);
    const agregar = q<HTMLButtonElement>('.recargos-costo > button')!;
    for (let i = 0; i < 5; i++) {
      await tocar(agregar);
      expect(q(`input[name="recargo-nombre-${i}"]`)).not.toBeNull();
      expect(q(`input[name="recargo-porcentaje-${i}"]`)).not.toBeNull();
    }
    expect(agregar.getAttribute('aria-disabled')).toBe('true');
    await tocar(agregar);
    const aviso = q('[role="status"].motivo-accion');
    expect(aviso?.textContent?.trim()).toBe('Ya cargaste el máximo de recargos (5).');
    expect(agregar.previousElementSibling).toBe(aviso);
    expect(q('input[name="recargo-nombre-5"]')).toBeNull();
    await tocar(q<HTMLButtonElement>('button[aria-label="Quitar el recargo 5"]')!);
    expect(agregar.getAttribute('aria-disabled')).not.toBe('true');
    expect(q('input[name="recargo-nombre-4"]')).toBeNull();
  });

  it('al cruzar el corte cambia la presentación sin reiniciar los datos', async () => {
    await crear();
    await tocar(secciones()[0]);
    const fecha = q<HTMLInputElement>('input[name="fecha"]');
    simularPantalla(false);
    window.dispatchEvent(new Event('resize'));
    await refrescar();
    expect(secciones()).toHaveLength(0);
    expect(ver().parentElement?.className).toBe('acciones');
    expect(q('input[name="fecha"]')).toBe(fecha);
    simularPantalla(true);
    window.dispatchEvent(new Event('resize'));
    await refrescar();
    expect(expansion()).toEqual(['false', 'false', 'false']);
    expect(q('input[name="fecha"]')).toBe(fecha);
    expect(listar).toHaveBeenCalledOnce();
  });
});
