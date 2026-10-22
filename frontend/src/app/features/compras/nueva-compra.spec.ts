import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Articulo } from '../articulos/articulo.models';
import { ArticulosService } from '../articulos/articulos.service';
import { GENERAR_CLAVE } from '../mostrador/ticket.store';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { CompraResponse, PlanCompra } from './compra.models';
import { ComprasService } from './compras.service';
import { NuevaCompra } from './nueva-compra';

registerLocaleData(localeEsAr);

describe('NuevaCompra', () => {
  let fixture: ComponentFixture<NuevaCompra>;
  let api: { vistaPrevia: ReturnType<typeof vi.fn>; registrar: ReturnType<typeof vi.fn> };
  let articulos: { buscar: ReturnType<typeof vi.fn> };
  let proveedores: { listar: ReturnType<typeof vi.fn> };

  const tornillo = {
    id: 1,
    codigoInterno: 'TOR-1',
    descripcion: 'Tornillo 1/4',
    costo: 100,
    moneda: 'ARS',
    unidad: 'UNIDAD',
    precioFinal: 157.3,
    activo: true,
  } as Articulo;

  const plan: PlanCompra = {
    proveedorNombre: 'Bulonera Sur',
    moneda: 'ARS',
    neto: 2200,
    suben: 1,
    bajan: 0,
    iguales: 0,
    renglones: [
      {
        articuloId: 1,
        codigoInterno: 'TOR-1',
        descripcion: 'Tornillo 1/4',
        unidad: 'UNIDAD',
        cantidad: 20,
        costoAnterior: 100,
        costoNuevo: 210,
        precioAnterior: 157.3,
        precioNuevo: 330.33,
        subtotal: 4200,
        advertencia: 'El costo sube un 110 % (de 100,00 a 210,00). Revisá que sea correcto.',
      },
    ],
  };

  const compra = {
    id: 9,
    comprobante: 'Factura A 00003-00001234',
    proveedorNombre: 'Bulonera Sur',
  } as CompraResponse;

  async function crear(rol = 'DUENO') {
    api = {
      vistaPrevia: vi.fn(() => of(plan)),
      registrar: vi.fn(() => of({ compra, repetida: false })),
    };
    articulos = { buscar: vi.fn(() => of({ items: [tornillo], page: 0, size: 8, totalElements: 1, totalPages: 1 })) };
    proveedores = { listar: vi.fn(() => of([{ id: 4, nombre: 'Bulonera Sur', cuit: null, activo: true }])) };
    const sesion = signal({ userId: 7, nombre: 'Ana', email: 'ana@x.com', rol, ferreteriaId: 3, ferreteriaNombre: 'Tornillo' });
    await TestBed.configureTestingModule({
      imports: [NuevaCompra],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ComprasService, useValue: api },
        { provide: ArticulosService, useValue: articulos },
        { provide: ProveedoresService, useValue: proveedores },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly(), esDueno: () => rol === 'DUENO' } },
        { provide: GENERAR_CLAVE, useValue: vi.fn().mockReturnValueOnce('clave-1').mockReturnValueOnce('clave-2').mockReturnValue('clave-3') },
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

  const el = () => fixture.nativeElement as HTMLElement;
  const campo = <T extends HTMLElement>(selector: string) => el().querySelector(selector) as T;
  const boton = (texto: string) =>
    [...el().querySelectorAll('button')].find((b) => b.textContent?.trim() === texto) as HTMLButtonElement | undefined;

  async function escribir(selector: string, valor: string) {
    const input = campo<HTMLInputElement | HTMLSelectElement>(selector);
    input.value = valor;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
    await refrescar();
  }

  /** Encabezado completo y un renglón de 20 tornillos a 210. */
  async function cargarCompra() {
    await escribir('select[name=proveedor]', '4');
    await escribir('input[name=puntoVenta]', '3');
    await escribir('input[name=numero]', '1234');
    await escribir('input[name=fecha]', '2026-10-20');
    await escribir('input[name=busqueda]', 'tor');
    campo<HTMLFormElement>('form.buscador').dispatchEvent(new Event('submit'));
    await refrescar();
    [...el().querySelectorAll<HTMLButtonElement>('.resultados button')][0].click();
    await refrescar();
    await escribir('input[name="cantidad-0"]', '20');
    await escribir('input[name="costo-0"]', '210');
  }

  it('al Vendedor le avisa que es del Dueño y no pide nada', async () => {
    await crear('VENDEDOR');

    expect(el().textContent).toContain('Solo el Dueño puede registrar compras.');
    expect(proveedores.listar).not.toHaveBeenCalled();
  });

  it('carga los proveedores activos y busca artículos por texto', async () => {
    await crear();

    expect(proveedores.listar).toHaveBeenCalledWith();
    expect([...el().querySelectorAll('select[name=proveedor] option')].map((o) => o.textContent?.trim())).toContain(
      'Bulonera Sur',
    );
    await escribir('input[name=busqueda]', 'tor');
    campo<HTMLFormElement>('form.buscador').dispatchEvent(new Event('submit'));
    await refrescar();
    expect(articulos.buscar).toHaveBeenCalledWith('tor', 0, 8, false);
    expect(el().querySelector('.resultados')?.textContent).toContain('Tornillo 1/4');
  });

  it('agregar un artículo arma el renglón con cantidad 1 y su costo actual; repetirlo no lo duplica', async () => {
    await crear();
    await escribir('input[name=busqueda]', 'tor');
    campo<HTMLFormElement>('form.buscador').dispatchEvent(new Event('submit'));
    await refrescar();

    el().querySelector<HTMLButtonElement>('.resultados button')!.click();
    await refrescar();
    el().querySelector<HTMLButtonElement>('.resultados button')!.click();
    await refrescar();

    expect(el().querySelectorAll('table.renglones tbody tr')).toHaveLength(1);
    expect(campo<HTMLInputElement>('input[name="cantidad-0"]').value).toBe('1');
    expect(campo<HTMLInputElement>('input[name="costo-0"]').value).toBe('100');
    expect(el().textContent).toContain('Tornillo 1/4 ya está en la compra.');
  });

  it('la vista previa manda el encabezado y los renglones sin clave y muestra anteriores, nuevos y advertencias', async () => {
    await crear();
    await cargarCompra();

    boton('Ver vista previa')!.click();
    await refrescar();

    expect(api.vistaPrevia).toHaveBeenCalledWith({
      proveedorId: 4,
      tipo: 'FA',
      puntoVenta: 3,
      numero: 1234,
      fecha: '2026-10-20',
      renglones: [{ articuloId: 1, cantidad: 20, costoUnitario: 210 }],
    });
    const vista = el().querySelector('section.vista-previa')!.textContent!;
    expect(vista).toContain('1 sube');
    expect(vista).toContain('0 bajan');
    expect(vista).toContain('0 iguales');
    expect(vista).toContain('El costo sube un 110 %');
    expect(vista).toContain('330,33');
    expect(boton('Confirmar compra')).toBeDefined();
  });

  it('cambiar un renglón después de la vista previa la descarta', async () => {
    await crear();
    await cargarCompra();
    boton('Ver vista previa')!.click();
    await refrescar();

    await escribir('input[name="cantidad-0"]', '21');

    expect(el().querySelector('section.vista-previa')).toBeNull();
    expect(boton('Confirmar compra')).toBeUndefined();
  });

  it('confirmar registra con la clave, avisa el comprobante y deja el formulario limpio con otra clave', async () => {
    await crear();
    await cargarCompra();
    boton('Ver vista previa')!.click();
    await refrescar();

    boton('Confirmar compra')!.click();
    await refrescar();

    expect(api.registrar).toHaveBeenCalledWith(expect.objectContaining({ clave: 'clave-1', numero: 1234 }));
    expect(el().querySelector('[role=status].exito')?.textContent).toContain(
      'Compra registrada: Factura A 00003-00001234 de Bulonera Sur.',
    );
    expect(el().querySelectorAll('table.renglones tbody tr')).toHaveLength(0);
  });

  it('una edición mientras la vista previa está en vuelo descarta la respuesta: no se confirma lo que no se vio', async () => {
    await crear();
    await cargarCompra();
    const respuesta = new Subject<PlanCompra>();
    api.vistaPrevia.mockReturnValueOnce(respuesta);
    boton('Ver vista previa')!.click();
    await refrescar();

    await escribir('input[name="costo-0"]', '999');
    respuesta.next(plan);
    respuesta.complete();
    await refrescar();

    expect(el().querySelector('section.vista-previa')).toBeNull();
    expect(boton('Confirmar compra')).toBeUndefined();
    expect(boton('Ver vista previa')!.disabled).toBe(false);
  });

  it('si confirmar falla por la red, reintentar usa la misma clave; una vista previa nueva trae otra', async () => {
    await crear();
    await cargarCompra();
    boton('Ver vista previa')!.click();
    await refrescar();
    api.registrar.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));

    boton('Confirmar compra')!.click();
    await refrescar();
    expect(el().querySelector('[role=alert]')?.textContent).toContain('si ya se guardó, no se duplica');
    boton('Confirmar compra')!.click();
    await refrescar();

    expect(api.registrar.mock.calls.map(([pedido]) => pedido.clave)).toEqual(['clave-1', 'clave-1']);
    await cargarCompra();
    boton('Ver vista previa')!.click();
    await refrescar();
    boton('Confirmar compra')!.click();
    await refrescar();
    expect(api.registrar.mock.calls.at(-1)![0].clave).toBe('clave-2');
  });

  it('un rechazo de la vista previa muestra el mensaje del servidor y los de cada campo', async () => {
    await crear();
    await cargarCompra();
    api.vistaPrevia.mockReturnValueOnce(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { message: 'Esa compra ya está cargada: Factura A 00003-00001234 de Bulonera Sur.' },
          }),
      ),
    );
    boton('Ver vista previa')!.click();
    await refrescar();
    expect(el().querySelector('[role=alert]')?.textContent).toContain('Esa compra ya está cargada');

    api.vistaPrevia.mockReturnValueOnce(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Datos inválidos', errors: { 'renglones[0].cantidad': 'Tornillo 1/4 se compra por unidad: la cantidad tiene que ser entera.' } },
          }),
      ),
    );
    boton('Ver vista previa')!.click();
    await refrescar();
    expect(el().querySelector('[role=alert]')?.textContent).toContain('la cantidad tiene que ser entera');
  });

  it('sin proveedor o sin renglones no pide la vista previa y avisa qué falta', async () => {
    await crear();

    boton('Ver vista previa')!.click();
    await refrescar();

    expect(api.vistaPrevia).not.toHaveBeenCalled();
    expect(el().querySelector('[role=alert]')?.textContent).toContain('Elegí el proveedor.');
    expect(el().querySelector('[role=alert]')?.textContent).toContain('Agregá al menos un artículo.');
  });
});
