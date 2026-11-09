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
import { BorradorDeFactura, CompraResponse, PlanCompra } from './compra.models';
import { ComprasService } from './compras.service';
import { NuevaCompra } from './nueva-compra';

registerLocaleData(localeEsAr);

describe('NuevaCompra', () => {
  let fixture: ComponentFixture<NuevaCompra>;
  let api: {
    vistaPrevia: ReturnType<typeof vi.fn>;
    registrar: ReturnType<typeof vi.fn>;
    leerFactura: ReturnType<typeof vi.fn>;
  };
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
        advertencia: 'El costo sube un 110 % (de $ 100,00 a $ 210,00). Revisá que sea correcto.',
        costoRealAnterior: 100,
        costoRealNuevo: 210,
        stockAnterior: 0,
        stockNuevo: 20,
        alicuotaIva: 21,
      },
    ],
    recargos: [],
    subtotalSinIva: 2200,
    recargosImporte: 0,
    iva: [{ porcentaje: 21, base: 2200, importe: 462 }],
    total: 2662,
  };

  const borrador: BorradorDeFactura = {
    proveedorId: 4,
    proveedorNombre: 'Bulonera Sur',
    cuits: ['30657162163'],
    tipo: 'FA',
    puntoVenta: 13,
    numero: 605684,
    fecha: '2026-09-24',
    recargos: [{ nombre: 'Embalaje y carga', porcentaje: 4.44, importe: 75835.18 }],
    renglones: [
      {
        codigo: 'TOR-P',
        descripcion: 'TORNILLO 1/4',
        cantidad: 12,
        costoUnitario: 210.5,
        neto: 2526,
        articuloId: 1,
        articuloDescripcion: 'Tornillo 1/4',
        articuloCodigoInterno: 'TOR-1',
        articuloMoneda: 'ARS',
        cantidadNoEntera: false,
      },
      {
        codigo: 'X-99',
        descripcion: 'PIEZA RARA',
        cantidad: 3,
        costoUnitario: 1500,
        neto: 4500,
        articuloId: null,
        articuloDescripcion: null,
        articuloCodigoInterno: null,
        articuloMoneda: null,
        cantidadNoEntera: false,
      },
    ],
    sumaNetos: 1707999.65,
    control: 'CUADRA',
    cantidadNoEntera: false,
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
      leerFactura: vi.fn(() => of(borrador)),
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

  const porEtiqueta = (etiqueta: string) => campo<HTMLButtonElement>(`button[aria-label="${etiqueta}"]`);

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
    [...el().querySelectorAll<HTMLElement>('.resultados [role=option]')][0].click();
    await refrescar();
    await escribir('input[name="cantidad-0"]', '20');
    await escribir('input[name="costo-0"]', '210');
  }

  it('al Vendedor le avisa que es del Dueño y no pide nada', async () => {
    await crear('VENDEDOR');

    expect(el().textContent).toContain('Solo el Dueño puede registrar compras.');
    expect(proveedores.listar).not.toHaveBeenCalled();
  });

  it('cada campo del encabezado tiene nombre accesible', async () => {
    await crear();
    const nombre = (selector: string) => campo<HTMLInputElement>(selector).labels?.[0]?.textContent?.trim();
    expect(nombre('select[name=proveedor]')).toMatch(/^Proveedor/);
    expect(nombre('select[name=tipo]')).toMatch(/^Tipo/);
    expect(nombre('input[name=puntoVenta]')).toBe('Punto de venta');
    expect(nombre('input[name=numero]')).toBe('Número');
    expect(nombre('input[name=fecha]')).toBe('Fecha');
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

    el().querySelector<HTMLElement>('.resultados [role=option]')!.click();
    await refrescar();
    el().querySelector<HTMLElement>('.resultados [role=option]')!.click();
    await refrescar();

    expect(el().querySelectorAll('table.renglones tbody tr')).toHaveLength(1);
    expect(campo<HTMLInputElement>('input[name="cantidad-0"]').value).toBe('1');
    expect(campo<HTMLInputElement>('input[name="costo-0"]').value).toBe('100,00');
    expect(el().textContent).toContain('Tornillo 1/4 ya está en la compra.');
  });

  describe('resultados de la búsqueda (arreglos-compras, S5)', () => {
    const clavo = { ...tornillo, id: 2, codigoInterno: 'CLA-1', descripcion: 'Clavo 1 pulgada', costo: 578378, stockActual: 12 };
    const barreta = { ...tornillo, id: 3, codigoInterno: 'BAR-1', descripcion: 'Barreta sacaclavos', costo: 9500, stockActual: 0 };
    const opciones = () => [...el().querySelectorAll<HTMLElement>('.resultados [role=option]')];
    const caja = () => campo<HTMLInputElement>('input[name=busqueda]');

    async function buscarClavo(totalElements = 2) {
      articulos.buscar.mockReturnValue(of({ items: [clavo, barreta], page: 0, size: 8, totalElements, totalPages: 1 }));
      await escribir('input[name=busqueda]', 'clavo');
      campo<HTMLFormElement>('form.buscador').dispatchEvent(new Event('submit'));
      await refrescar();
    }

    async function tecla(key: string) {
      const evento = new KeyboardEvent('keydown', { key, cancelable: true });
      caja().dispatchEvent(evento);
      await refrescar();
      return evento;
    }

    it('es una lista alineada: código, descripción, costo y stock en columnas', async () => {
      await crear();
      await buscarClavo();

      expect([...el().querySelectorAll('.resultados-encabezado span')].map((s) => s.textContent?.trim())).toEqual([
        'Código',
        'Descripción',
        'Costo',
        'Stock',
      ]);
      const celdas = (o: HTMLElement) =>
        [...o.querySelectorAll('span')].map((s) => s.textContent?.replace(/\s+/g, ' ').trim());
      expect(celdas(opciones()[0])).toEqual(['CLA-1', 'Clavo 1 pulgada', '$ 578.378,00', '12 unidades']);
      expect(celdas(opciones()[1])).toEqual(['BAR-1', 'Barreta sacaclavos', '$ 9.500,00', '0 unidades']);
      expect(el().querySelector('.resultados [role=listbox]')).not.toBeNull();
    });

    it('el costo dice por qué unidad es: «/ kg»; por pieza no lleva (#36)', async () => {
      await crear();
      articulos.buscar.mockReturnValue(
        of({ items: [{ ...clavo, unidad: 'KILO' }, barreta], page: 0, size: 8, totalElements: 2, totalPages: 1 }),
      );
      await escribir('input[name=busqueda]', 'clavo');
      campo<HTMLFormElement>('form.buscador').dispatchEvent(new Event('submit'));
      await refrescar();

      const costos = opciones().map((o) => o.querySelectorAll('span')[2].textContent!.replace(/\s+/g, ' ').trim());
      expect(costos).toEqual(['$ 578.378,00 / kg', '$ 9.500,00']);
    });

    it('con más resultados que los que se ven, dice cuántos hay: «8 de N: escribí más para achicar.»', async () => {
      await crear();
      await buscarClavo(27);

      expect(el().querySelector('.resultados-cantidad')?.textContent?.trim()).toBe('2 de 27: escribí más para achicar.');
    });

    it('si se ven todos, no avisa la cantidad', async () => {
      await crear();
      await buscarClavo(2);

      expect(el().querySelector('.resultados-cantidad')).toBeNull();
    });

    it('las flechas mueven la opción activa y Enter agrega esa, sin buscar de nuevo', async () => {
      await crear();
      await buscarClavo();
      articulos.buscar.mockClear();

      // Bugs de la guía, S23: tras buscar queda marcada la primera, como en el Mostrador.
      expect(opciones().map((o) => o.getAttribute('aria-selected'))).toEqual(['true', 'false']);
      await tecla('ArrowDown');
      await tecla('ArrowDown');
      expect(opciones().map((o) => o.getAttribute('aria-selected'))).toEqual(['false', 'true']);
      expect(caja().getAttribute('aria-activedescendant')).toBe(opciones()[1].id);
      await tecla('ArrowUp');
      expect(opciones().map((o) => o.getAttribute('aria-selected'))).toEqual(['true', 'false']);

      const enter = await tecla('Enter');

      expect(enter.defaultPrevented).toBe(true);
      expect(articulos.buscar).not.toHaveBeenCalled();
      expect(el().querySelector('table.renglones tbody tr')?.textContent).toContain('CLA-1 · Clavo 1 pulgada');
    });

    // Bugs de la guía, S23: antes Enter volvía a buscar hasta apretar ↓; ahora, con los resultados de la búsqueda
    // actual, Enter agrega el primero, como en el Mostrador.
    it('con resultados, Enter agrega el primero sin apretar ↓ y sin buscar de nuevo', async () => {
      await crear();
      await buscarClavo();
      articulos.buscar.mockClear();

      expect(caja().getAttribute('aria-activedescendant')).toBe(opciones()[0].id);
      const enter = await tecla('Enter');

      expect(enter.defaultPrevented).toBe(true);
      expect(articulos.buscar).not.toHaveBeenCalled();
      const renglones = el().querySelectorAll('table.renglones tbody tr');
      expect(renglones).toHaveLength(1);
      expect(renglones[0].textContent).toContain('CLA-1 · Clavo 1 pulgada');
    });

    // Bugs de la guía, S23: si el texto cambió desde la última búsqueda, los resultados a la vista son de otra
    // cosa: Enter busca lo nuevo, aunque se haya marcado una opción con las flechas.
    it('si el texto cambió desde la última búsqueda, Enter busca en vez de agregar', async () => {
      await crear();
      await buscarClavo();
      await escribir('input[name=busqueda]', 'clavos');
      await tecla('ArrowDown');

      const enter = await tecla('Enter');

      expect(enter.defaultPrevented).toBe(false);
      expect(el().querySelectorAll('table.renglones tbody tr')).toHaveLength(0);
    });

    it('al escribir otra cosa, ninguna opción queda activa', async () => {
      await crear();
      await buscarClavo();
      await tecla('ArrowDown');

      await escribir('input[name=busqueda]', 'clavos');

      expect(opciones().map((o) => o.getAttribute('aria-selected'))).toEqual(['false', 'false']);
    });
  });

  describe('Enter con un código exacto (lector)', () => {
    async function escanear(codigo: string) {
      await escribir('input[name=busqueda]', codigo);
      campo<HTMLFormElement>('form.buscador').dispatchEvent(new Event('submit'));
      await refrescar();
    }

    it('agrega el artículo sin clic y deja el buscador vacío y sin resultados', async () => {
      await crear();

      await escanear('tor-1');

      expect(el().querySelectorAll('table.renglones tbody tr')).toHaveLength(1);
      expect(campo<HTMLInputElement>('input[name="cantidad-0"]').value).toBe('1');
      expect(campo<HTMLInputElement>('input[name=busqueda]').value).toBe('');
      expect(el().querySelector('.resultados')).toBeNull();
    });

    it('también con el código de barras o el del proveedor', async () => {
      await crear();
      articulos.buscar.mockReturnValue(
        of({
          items: [{ ...tornillo, codigoBarras: '7790000000011', codigoProveedor: 'DBS-100' }],
          page: 0,
          size: 8,
          totalElements: 1,
          totalPages: 1,
        }),
      );

      await escanear('7790000000011');
      await escanear('DBS-100');

      expect(el().querySelectorAll('table.renglones tbody tr')).toHaveLength(1);
      expect(campo<HTMLInputElement>('input[name="cantidad-0"]').value).toBe('2');
    });

    it('escanear de nuevo el mismo artículo suma 1 a la cantidad', async () => {
      await crear();

      await escanear('TOR-1');
      await escanear('TOR-1');

      expect(el().querySelectorAll('table.renglones tbody tr')).toHaveLength(1);
      expect(campo<HTMLInputElement>('input[name="cantidad-0"]').value).toBe('2');
    });

    it('sin coincidencia exacta muestra los resultados para elegir', async () => {
      await crear();

      await escanear('TOR');

      expect(el().querySelectorAll('table.renglones tbody tr')).toHaveLength(0);
      expect(el().querySelector('.resultados')?.textContent).toContain('Tornillo 1/4');
    });
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

  it('el costo lleva la máscara de importes: 50000 se ve 50.000 y se manda como cincuenta mil; el punto no entra', async () => {
    await crear();
    await cargarCompra();

    await escribir('input[name="costo-0"]', '50000');
    expect(campo<HTMLInputElement>('input[name="costo-0"]').value).toBe('50.000');
    boton('Ver vista previa')!.click();
    await refrescar();
    expect(api.vistaPrevia).toHaveBeenLastCalledWith(
      expect.objectContaining({ renglones: [{ articuloId: 1, cantidad: 20, costoUnitario: 50000 }] }),
    );

    await escribir('input[name="costo-0"]', '12.5');
    expect(campo<HTMLInputElement>('input[name="costo-0"]').value).toBe('125');
    boton('Ver vista previa')!.click();
    await refrescar();
    expect(api.vistaPrevia).toHaveBeenLastCalledWith(
      expect.objectContaining({ renglones: [{ articuloId: 1, cantidad: 20, costoUnitario: 125 }] }),
    );
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

  const entradaPdf = () => campo<HTMLInputElement>('app-selector-archivo input[type=file]');
  const nombrePdf = () => el().querySelector('app-selector-archivo .nombre-archivo')?.textContent?.trim();

  async function subirPdf(nombre = 'factura.pdf') {
    const input = entradaPdf();
    Object.defineProperty(input, 'files', { value: [new File(['%PDF'], nombre, { type: 'application/pdf' })], configurable: true });
    input.dispatchEvent(new Event('change'));
    await refrescar();
  }

  describe('lenguaje visual (arreglos-compras, S3)', () => {
    it('no usa recuadros fieldset: cada parte es una sección con su título', async () => {
      await crear();

      expect(el().querySelectorAll('fieldset').length).toBe(0);
      expect([...el().querySelectorAll('section.paso > h2')].map((h) => h.textContent?.trim())).toEqual([
        'Comprobante',
        'Artículos',
        'Recargos del costo',
      ]);
    });

    it('«Ver vista previa» es el botón principal', async () => {
      await crear();

      expect(boton('Ver vista previa')!.classList).toContain('primario');
    });

    it('«Ver compras» es un botón de la barra, como los de las otras pantallas (no un link suelto)', async () => {
      await crear();

      const verCompras = el().querySelector<HTMLAnchorElement>('app-barra-acciones a[href="/compras"]')!;
      expect(verCompras.textContent?.trim()).toBe('Ver compras');
      expect([...verCompras.classList]).toEqual(expect.arrayContaining(['btn', 'accion-barra']));
    });
  });

  describe('factura en PDF', () => {
    it('el selector solo acepta PDF', async () => {
      await crear();

      expect(entradaPdf().accept).toContain('.pdf');
    });

    it('el selector está en castellano y, después de leer, muestra el nombre del PDF (arreglos-compras, S3 y S7)', async () => {
      await crear();

      expect(el().querySelector('app-selector-archivo .rotulo')?.textContent?.trim()).toBe('Leer factura en PDF');
      expect(el().querySelector('app-selector-archivo button')?.textContent?.trim()).toBe('Elegir archivo…');
      expect(nombrePdf()).toBe('Ningún archivo elegido');
      expect(entradaPdf().hidden).toBe(true);

      await subirPdf('factura-bulonera-1234.pdf');

      expect(nombrePdf()).toBe('factura-bulonera-1234.pdf');
    });

    it('al registrar la compra, el selector vuelve a «Ningún archivo elegido»', async () => {
      await crear();
      api.leerFactura.mockReturnValue(
        of({ ...borrador, renglones: borrador.renglones.filter((r) => r.articuloId !== null) }),
      );
      await subirPdf('factura-bulonera-1234.pdf');
      boton('Ver vista previa')!.click();
      await refrescar();

      boton('Confirmar compra')!.click();
      await refrescar();

      expect(nombrePdf()).toBe('Ningún archivo elegido');
    });

    it('muestra «Leyendo la factura…» mientras espera y después completa el formulario', async () => {
      await crear();
      const respuesta = new Subject<BorradorDeFactura>();
      api.leerFactura.mockReturnValue(respuesta);

      await subirPdf();
      expect(el().textContent).toContain('Leyendo la factura…');

      respuesta.next(borrador);
      await refrescar();

      expect(el().textContent).not.toContain('Leyendo la factura…');
      expect(campo<HTMLSelectElement>('select[name=proveedor]').value).toBe('4');
      expect(campo<HTMLSelectElement>('select[name=tipo]').value).toBe('FA');
      expect(campo<HTMLInputElement>('input[name=puntoVenta]').value).toBe('13');
      expect(campo<HTMLInputElement>('input[name=numero]').value).toBe('605684');
      expect(campo<HTMLInputElement>('input[name=fecha]').value).toBe('2026-09-24');
      expect(campo<HTMLInputElement>('input[name="cantidad-0"]').value).toBe('12');
      expect(campo<HTMLInputElement>('input[name="costo-0"]').value).toBe('210,50');
      expect(el().querySelector('table.renglones')!.textContent).toContain('Tornillo 1/4');
      expect(campo<HTMLInputElement>('input[name="recargo-nombre-0"]').value).toBe('Embalaje y carga');
      expect(campo<HTMLInputElement>('input[name="recargo-porcentaje-0"]').value).toBe('4,44');
    });

    it('lista aparte el renglón sin artículo y no deja ver la vista previa hasta resolverlo', async () => {
      await crear();
      await subirPdf();

      const sin = el().querySelector('.sin-articulo')!.textContent!;
      expect(sin).toContain('Sin artículo: X-99 — PIEZA RARA (3 × $');
      expect(sin).toContain('1.500,00');

      boton('Ver vista previa')!.click();
      await refrescar();
      expect(api.vistaPrevia).not.toHaveBeenCalled();
      expect(el().querySelector('[role=alert]')!.textContent).toContain('Resolvé los renglones sin artículo.');
    });

    it('«Quitar» saca el renglón sin artículo y la vista previa ya se puede pedir', async () => {
      await crear();
      await subirPdf();

      porEtiqueta('Quitar sin artículo X-99').click();
      await refrescar();
      boton('Ver vista previa')!.click();
      await refrescar();

      expect(el().querySelector('.sin-articulo')).toBeNull();
      expect(api.vistaPrevia).toHaveBeenCalledTimes(1);
      expect(api.vistaPrevia.mock.calls[0][0]).toEqual({
        proveedorId: 4,
        tipo: 'FA',
        puntoVenta: 13,
        numero: 605684,
        fecha: '2026-09-24',
        renglones: [{ articuloId: 1, cantidad: 12, costoUnitario: 210.5 }],
        recargos: [{ nombre: 'Embalaje y carga', porcentaje: 4.44 }],
      });
    });

    it('«Elegir artículo» agrega el artículo elegido con la cantidad y el costo de la factura', async () => {
      await crear();
      await subirPdf();
      articulos.buscar.mockReturnValue(
        of({ items: [{ ...tornillo, id: 2, codigoInterno: 'RAR-1', descripcion: 'Pieza rara' }], page: 0, size: 8, totalElements: 1, totalPages: 1 }),
      );

      boton('Elegir artículo')!.click();
      await refrescar();
      await escribir('input[name=busqueda]', 'rara');
      campo<HTMLFormElement>('form.buscador').dispatchEvent(new Event('submit'));
      await refrescar();
      el().querySelector<HTMLElement>('.resultados [role=option]')!.click();
      await refrescar();

      expect(el().querySelector('.sin-articulo')).toBeNull();
      expect(campo<HTMLInputElement>('input[name="cantidad-1"]').value).toBe('3');
      expect(campo<HTMLInputElement>('input[name="costo-1"]').value).toBe('1.500,00');
      // El renglón resuelto muestra el código de la factura, para cotejar con el papel.
      const resuelto = el().querySelectorAll('table.renglones tbody tr')[1];
      expect(resuelto.querySelector('.codigo-proveedor')?.textContent?.trim()).toBe('Cód. proveedor X-99');
    });

    describe('resolver un renglón sin artículo (arreglos-compras, S8)', () => {
      const alerta = () => el().querySelector('[role=alert]')?.textContent ?? '';

      async function intentarVistaPrevia() {
        boton('Ver vista previa')!.click();
        await refrescar();
      }

      it('«Quitar» borra el cartel «Resolvé los renglones sin artículo.» sin tocar otro error', async () => {
        await crear();
        await subirPdf();
        await escribir('input[name=puntoVenta]', '');
        await intentarVistaPrevia();
        expect(alerta()).toContain('Resolvé los renglones sin artículo.');

        porEtiqueta('Quitar sin artículo X-99').click();
        await refrescar();

        expect(alerta()).not.toContain('Resolvé los renglones sin artículo.');
        expect(alerta()).toContain('El punto de venta tiene que ser un número entero.');
      });

      it('al elegir el artículo del último renglón sin artículo el cartel se va', async () => {
        await crear();
        await subirPdf();
        await intentarVistaPrevia();
        articulos.buscar.mockReturnValue(
          of({ items: [{ ...tornillo, id: 2, codigoInterno: 'RAR-1', descripcion: 'Pieza rara' }], page: 0, size: 8, totalElements: 1, totalPages: 1 }),
        );

        boton('Elegir artículo')!.click();
        await refrescar();
        await escribir('input[name=busqueda]', 'rara');
        campo<HTMLFormElement>('form.buscador').dispatchEvent(new Event('submit'));
        await refrescar();
        el().querySelector<HTMLElement>('.resultados [role=option]')!.click();
        await refrescar();

        expect(el().querySelector('[role=alert]')).toBeNull();
      });

      it('«Crear artículo» abre el alta en otra pestaña con la descripción, el código, el proveedor y el costo de la factura', async () => {
        await crear();
        await subirPdf();
        const abrir = vi.spyOn(window, 'open').mockReturnValue(null);

        boton('Crear artículo')!.click();

        expect(abrir).toHaveBeenCalledTimes(1);
        const [url, destino, opciones] = abrir.mock.calls[0];
        const direccion = new URL(String(url), 'http://localhost');
        expect(direccion.pathname).toBe('/articulos/nuevo');
        expect(Object.fromEntries(direccion.searchParams)).toEqual({
          descripcion: 'PIEZA RARA',
          codigoProveedor: 'X-99',
          proveedorId: '4',
          costo: '1500',
        });
        expect(destino).toBe('_blank');
        expect(opciones).toBe('noopener');
        // La compra sigue en pantalla: el renglón sin artículo no se quitó.
        expect(el().querySelector('.sin-articulo')).not.toBeNull();
        abrir.mockRestore();
      });

      it('sin proveedor elegido «Crear artículo» no manda el proveedor', async () => {
        await crear();
        await subirPdf();
        await escribir('select[name=proveedor]', '');
        const abrir = vi.spyOn(window, 'open').mockReturnValue(null);

        boton('Crear artículo')!.click();

        const direccion = new URL(String(abrir.mock.calls[0][0]), 'http://localhost');
        expect(direccion.searchParams.has('proveedorId')).toBe(false);
        abrir.mockRestore();
      });
    });

    describe('códigos de los renglones (arreglos-compras, S7)', () => {
      const articuloDe = (fila: number) => el().querySelectorAll('table.renglones tbody tr')[fila].querySelector('td')!;

      it('un renglón leído muestra el código interno y, chico al lado, el del proveedor', async () => {
        await crear();
        await subirPdf();

        expect(articuloDe(0).textContent).toContain('TOR-1 · Tornillo 1/4');
        expect(articuloDe(0).textContent).not.toContain('TOR-P ·');
        expect(articuloDe(0).querySelector('.codigo-proveedor')?.textContent?.trim()).toBe('Cód. proveedor TOR-P');
      });

      it('un artículo en dólares leído del PDF queda en dólares', async () => {
        await crear();
        api.leerFactura.mockReturnValue(
          of({ ...borrador, renglones: [{ ...borrador.renglones[0], articuloMoneda: 'USD' }] }),
        );
        await subirPdf();

        expect(articuloDe(0).textContent).toContain('(USD)');
        expect(el().querySelector('table.renglones app-campo-moneda .signo')?.textContent?.trim()).toBe('US$');
      });

      it('el costo es un campo de plata: «$» adentro y dos decimales (arreglos-compras, S6)', async () => {
        await crear();
        await subirPdf();

        expect(el().querySelector('table.renglones app-campo-moneda .signo')?.textContent?.trim()).toBe('$');
        expect(campo<HTMLInputElement>('input[name="costo-0"]').value).toBe('210,50');
        // El porcentaje del recargo no es plata: sin signo y como se leyó.
        expect(campo<HTMLInputElement>('input[name="recargo-porcentaje-0"]').closest('app-campo-moneda')).toBeNull();
        expect(campo<HTMLInputElement>('input[name="recargo-porcentaje-0"]').value).toBe('4,44');
      });

      it('agregado a mano: muestra el código del proveedor si es distinto del interno, y si es el mismo no lo repite', async () => {
        await crear();
        articulos.buscar.mockReturnValue(
          of({
            items: [
              { ...tornillo, codigoProveedor: 'TOR-P' },
              { ...tornillo, id: 2, codigoInterno: 'DBS-100', codigoProveedor: 'DBS-100', descripcion: 'Bulón' },
            ],
            page: 0,
            size: 8,
            totalElements: 2,
            totalPages: 1,
          }),
        );
        await escribir('input[name=busqueda]', 'tor');
        campo<HTMLFormElement>('form.buscador').dispatchEvent(new Event('submit'));
        await refrescar();
        el().querySelectorAll<HTMLElement>('.resultados [role=option]')[0].click();
        await refrescar();
        el().querySelectorAll<HTMLElement>('.resultados [role=option]')[1].click();
        await refrescar();

        expect(articuloDe(0).querySelector('.codigo-proveedor')?.textContent?.trim()).toBe('Cód. proveedor TOR-P');
        expect(articuloDe(1).querySelector('.codigo-proveedor')).toBeNull();
      });
    });

    it('avisa si la lectura cuadra con el total de la factura', async () => {
      await crear();
      await subirPdf();

      expect(el().querySelector('.control-factura')!.textContent).toContain(
        'La lectura cuadra con el total de la factura ($',
      );
      expect(el().querySelector('.control-factura')!.textContent).toContain('1.707.999,65 + recargos).');
    });

    it('sin control, pide revisar los renglones antes de confirmar', async () => {
      await crear();
      api.leerFactura.mockReturnValue(of({ ...borrador, control: 'SIN_CONTROL' }));
      await subirPdf();

      expect(el().querySelector('.control-factura')!.textContent).toContain(
        'No pudimos comprobar el total de la factura: revisá los renglones antes de confirmar.',
      );
    });

    it('sin proveedor para el CUIT lo avisa y deja elegirlo', async () => {
      await crear();
      api.leerFactura.mockReturnValue(of({ ...borrador, proveedorId: null, proveedorNombre: null, cuits: ['30657162163', '20111111112'] }));
      await subirPdf();

      expect(campo<HTMLSelectElement>('select[name=proveedor]').value).toBe('');
      expect(el().textContent).toContain('No encontramos un proveedor con el CUIT 30657162163, 20111111112. Elegilo de la lista.');
    });

    it('marca el renglón con cantidad no entera', async () => {
      await crear();
      api.leerFactura.mockReturnValue(
        of({ ...borrador, renglones: [{ ...borrador.renglones[0], cantidad: 1.5, cantidadNoEntera: true }] }),
      );
      await subirPdf();

      expect(el().querySelector('table.renglones .aviso-fila')!.textContent).toContain('La cantidad no es entera');
    });

    it('muestra el mensaje del backend si no se puede leer el PDF', async () => {
      await crear();
      api.leerFactura.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 400, error: { message: 'El PDF no tiene texto para leer (parece escaneado). Cargá la compra a mano.' } })),
      );
      await subirPdf();

      expect(el().querySelector('[role=alert]')!.textContent).toContain('El PDF no tiene texto para leer');
    });

    it('con una lista leída como factura avisa, no carga renglones y deja cargar a mano o subir otro PDF (bugs-relevamiento-celular, S2 y S12)', async () => {
      await crear();
      api.leerFactura.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 400, error: { message: 'La factura trae más de 500 renglones: no parece una factura. Cargá la compra a mano.' } })),
      );
      await subirPdf('LISTA 336.pdf');

      expect(el().querySelector('[role=alert]')!.textContent).toContain('no parece una factura');
      expect(el().querySelectorAll('table.renglones tbody tr')).toHaveLength(0);
      expect(el().textContent).toContain('Todavía no agregaste artículos.');
      expect(campo<HTMLSelectElement>('select[name=proveedor]').disabled).toBe(false);
      expect(entradaPdf().disabled).toBe(false);

      await subirPdf('factura.pdf');

      expect(api.leerFactura).toHaveBeenCalledTimes(2);
      expect(el().querySelector('[role=alert]')).toBeNull();
      expect(el().querySelector('table.renglones')!.textContent).toContain('Tornillo 1/4');
    });
  });

  describe('recargos del costo', () => {
    it('una compra a mano manda el mismo pedido de siempre, sin la clave recargos', async () => {
      await crear();
      await cargarCompra();

      boton('Ver vista previa')!.click();
      await refrescar();

      expect('recargos' in api.vistaPrevia.mock.calls[0][0]).toBe(false);
    });

    it('«Agregar» suma un recargo (hasta 5), «Quitar» lo saca y se mandan en la vista previa y al confirmar', async () => {
      await crear();
      await cargarCompra();
      for (let i = 0; i < 5; i++) boton('Agregar recargo')!.click();
      await refrescar();
      expect(el().querySelectorAll('input[name^="recargo-nombre-"]')).toHaveLength(5);
      expect(boton('Agregar recargo')!.disabled).toBe(true);
      for (let i = 4; i >= 1; i--) {
        porEtiqueta(`Quitar el recargo ${i + 1}`).click();
        await refrescar();
      }
      await escribir('input[name="recargo-nombre-0"]', 'Flete');
      await escribir('input[name="recargo-porcentaje-0"]', '-2,5');

      boton('Ver vista previa')!.click();
      await refrescar();
      boton('Confirmar compra')!.click();
      await refrescar();

      expect(api.vistaPrevia.mock.calls[0][0].recargos).toEqual([{ nombre: 'Flete', porcentaje: -2.5 }]);
      expect(api.registrar.mock.calls[0][0].recargos).toEqual([{ nombre: 'Flete', porcentaje: -2.5 }]);
    });

    it('muestra el error del servidor de los recargos junto al editor', async () => {
      await crear();
      await cargarCompra();
      api.vistaPrevia.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 400, error: { errors: { recargosCosto: 'Un recargo no puede ser 0 %.' } } })),
      );

      boton('Agregar recargo')!.click();
      await refrescar();
      await escribir('input[name="recargo-nombre-0"]', 'Flete');
      await escribir('input[name="recargo-porcentaje-0"]', '0');
      boton('Ver vista previa')!.click();
      await refrescar();

      expect(el().querySelector('.recargos-costo')!.textContent).toContain('Un recargo no puede ser 0 %.');
    });

    it('la vista previa muestra el costo real de antes y de ahora cuando la compra tiene recargos', async () => {
      await crear();
      await cargarCompra();
      api.vistaPrevia.mockReturnValue(
        of({
          ...plan,
          recargos: [{ nombre: 'Flete', porcentaje: 10 }],
          renglones: [{ ...plan.renglones[0], costoRealAnterior: 110, costoRealNuevo: 231 }],
        }),
      );

      boton('Ver vista previa')!.click();
      await refrescar();

      const vista = el().querySelector('section.vista-previa')!.textContent!;
      expect(vista).toContain('Costo real antes');
      expect(vista).toContain('Costo real nuevo');
      expect(vista).toContain('231,00');
    });

    /** Cada línea de los totales como «rótulo importe», con el espacio duro de Intl normalizado. */
    function lineasDeTotales(): string[] {
      return [...el().querySelectorAll('section.vista-previa .totales p')].map((linea) =>
        [...linea.children].map((c) => c.textContent!.trim()).join(' ').replace(/\s+/g, ' '),
      );
    }

    it('la vista previa muestra el stock de hoy y el que queda después de la compra', async () => {
      await crear();
      await cargarCompra();

      boton('Ver vista previa')!.click();
      await refrescar();

      const vista = el().querySelector('section.vista-previa')!;
      expect(vista.querySelector('thead')!.textContent).toContain('Stock antes → después');
      expect(vista.querySelector('tbody td.stock')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('0 → 20 unidades');
    });

    it('en la vista previa la cantidad y el stock llevan la unidad: «20,5 kilos», «0 → 20,5 kilos» (#36)', async () => {
      await crear();
      const porKilo = { ...plan.renglones[0], unidad: 'KILO' as const, cantidad: 20.5, stockAnterior: 0, stockNuevo: 20.5 };
      api.vistaPrevia.mockReturnValue(of({ ...plan, renglones: [porKilo] }));
      await cargarCompra();

      boton('Ver vista previa')!.click();
      await refrescar();

      const celdas = [...el().querySelectorAll('section.vista-previa tbody tr td')].map((td) =>
        td.textContent!.replace(/\s+/g, ' ').trim(),
      );
      expect(celdas[1]).toBe('20,5 kilos');
      expect(el().querySelector('section.vista-previa tbody td.stock')!.textContent!.replace(/\s+/g, ' ').trim()).toBe(
        '0 → 20,5 kilos',
      );
    });

    it('en la vista previa los costos y los precios dicen por qué unidad son; por pieza y el subtotal no llevan (#36)', async () => {
      await crear();
      const porKilo = {
        ...plan.renglones[0],
        articuloId: 2,
        codigoInterno: 'CLA-1',
        descripcion: 'Clavo punta París 2"',
        unidad: 'KILO' as const,
        costoAnterior: 3000,
        costoNuevo: 3300,
        costoRealAnterior: 3100,
        costoRealNuevo: 3400,
        precioAnterior: 4000,
        precioNuevo: 4400,
        subtotal: 66000,
      };
      api.vistaPrevia.mockReturnValue(
        of({ ...plan, recargos: [{ nombre: 'Flete', porcentaje: 10 }], renglones: [porKilo, plan.renglones[0]] }),
      );
      await cargarCompra();

      boton('Ver vista previa')!.click();
      await refrescar();

      // Artículo, cantidad, costo antes y nuevo, costo real antes y nuevo, precio antes y nuevo, stock y subtotal.
      const filas = [...el().querySelectorAll('section.vista-previa tbody tr')].map((tr) =>
        [...tr.querySelectorAll('td')].map((td) => td.textContent!.replace(/\s+/g, ' ').trim()),
      );
      expect(filas[0].slice(2, 8)).toEqual([
        '$ 3.000,00 / kg',
        '$ 3.300,00 / kg',
        '$ 3.100,00 / kg',
        '$ 3.400,00 / kg',
        '$ 4.000,00 / kg',
        '$ 4.400,00 / kg',
      ]);
      expect(filas[0][9]).toBe('$ 66.000,00');
      expect(filas[1].slice(2, 8)).toEqual(['$ 100,00', '$ 210,00', '$ 100,00', '$ 210,00', '$ 157,30', '$ 330,33']);
    });

    it('los totales muestran neto, recargos, subtotal sin IVA, el IVA de cada alícuota y el total', async () => {
      await crear();
      await cargarCompra();
      api.vistaPrevia.mockReturnValue(
        of({
          ...plan,
          recargos: [{ nombre: 'Flete', porcentaje: 10 }],
          neto: 2500,
          subtotalSinIva: 2750,
          recargosImporte: 250,
          iva: [
            { porcentaje: 10.5, base: 330, importe: 34.65 },
            { porcentaje: 21, base: 2420, importe: 508.2 },
          ],
          total: 3292.85,
        }),
      );

      boton('Ver vista previa')!.click();
      await refrescar();

      expect(lineasDeTotales()).toEqual([
        'Neto $ 2.500,00',
        'Recargos $ 250,00',
        'Subtotal sin IVA $ 2.750,00',
        'IVA 10,5 % $ 34,65',
        'IVA 21 % $ 508,20',
        'Total $ 3.292,85',
      ]);
    });

    it('sin IVA (Factura C o Remito) los totales no tienen líneas de IVA y no hay recargos si no los hay', async () => {
      await crear();
      await cargarCompra();
      api.vistaPrevia.mockReturnValue(of({ ...plan, iva: [], total: 2200 }));

      boton('Ver vista previa')!.click();
      await refrescar();

      expect(lineasDeTotales()).toEqual(['Neto $ 2.200,00', 'Subtotal sin IVA $ 2.200,00', 'Total $ 2.200,00']);
    });

    it('sin recargos la vista previa no muestra las columnas de costo real', async () => {
      await crear();
      await cargarCompra();

      boton('Ver vista previa')!.click();
      await refrescar();

      expect(el().querySelector('section.vista-previa')!.textContent).not.toContain('Costo real');
    });
  });
});
