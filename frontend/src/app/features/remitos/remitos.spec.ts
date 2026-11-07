import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { Articulo } from '../articulos/articulo.models';
import { ArticulosService } from '../articulos/articulos.service';
import { Cliente } from '../clientes/cliente.models';
import { ClientesService } from '../clientes/clientes.service';
import { GENERAR_CLAVE } from '../mostrador/ticket.store';
import { EmisorService } from '../../shared/documento-no-fiscal/emisor.service';
import { emisorDePrueba } from '../../shared/documento-no-fiscal/documento-no-fiscal.testing';
import { EstadoRemito, RemitoResponse, RemitoResumen } from './remito.models';
import { Remitos } from './remitos';
import { RemitosService } from './remitos.service';

registerLocaleData(localeEsAr);

describe('Remitos', () => {
  let fixture: ComponentFixture<Remitos>;
  let api: {
    listar: ReturnType<typeof vi.fn>;
    obtener: ReturnType<typeof vi.fn>;
    anular: ReturnType<typeof vi.fn>;
  };
  let articulos: { obtener: ReturnType<typeof vi.fn> };
  let clientes: { obtener: ReturnType<typeof vi.fn> };
  let navegar: ReturnType<typeof vi.fn>;
  let emisor: { obtener: ReturnType<typeof vi.fn> };

  const resumen = (numero: number, cambios: Partial<RemitoResumen> = {}): RemitoResumen => ({
    remitoId: numero * 10,
    numero,
    estado: 'EMITIDO',
    fecha: '2026-10-18T16:03:00',
    clienteNombre: `Cliente ${numero}`,
    total: 3000,
    ...cambios,
  });

  const pagina = (
    items: RemitoResumen[],
    cambios: Partial<PageResponse<RemitoResumen>> = {},
  ): PageResponse<RemitoResumen> => ({
    items,
    page: 0,
    size: 20,
    totalElements: items.length,
    totalPages: 1,
    ...cambios,
  });

  const detalleDe = (numero: number, cambios: Partial<RemitoResponse> = {}): RemitoResponse => ({
    remitoId: numero * 10,
    numero,
    estado: 'EMITIDO',
    fecha: '2026-10-18T16:03:00',
    clienteNombre: `Cliente ${numero}`,
    observaciones: 'Entrega el viernes',
    subtotal: 3000,
    descuentoPorcentaje: 0,
    descuento: 0,
    neto: 2479.34,
    iva: 520.66,
    total: 3000,
    renglones: [
      {
        renglon: 1,
        articuloId: 1,
        descripcion: 'Martillo',
        unidad: 'UNIDAD',
        cantidad: 2,
        precioUnitario: 1500,
        alicuotaIva: 'IVA_21',
        bruto: 3000,
        descuentoPorcentaje: 0,
        total: 3000,
        neto: 2479.34,
        iva: 520.66,
      },
    ],
    ...cambios,
  });

  const articuloActual = (cambios: Partial<Articulo> = {}) =>
    ({
      id: 1,
      codigoInterno: 'COD-1',
      codigoBarras: null,
      codigoProveedor: null,
      descripcion: 'Martillo',
      precioFinal: 1800,
      unidad: 'UNIDAD',
      stockActual: 10,
      stockMinimo: 5,
      ubicacion: null,
      activo: true,
      ...cambios,
    }) as Articulo;

  async function crear(items: RemitoResumen[] = [resumen(5), resumen(4)], rol = 'VENDEDOR') {
    api = {
      listar: vi.fn(() => of(pagina(items))),
      obtener: vi.fn((id: number) => of(detalleDe(id / 10))),
      anular: vi.fn((id: number) => of(detalleDe(id / 10, { estado: 'ANULADO' }))),
    };
    articulos = { obtener: vi.fn(() => of(articuloActual())) };
    // La condición de IVA de hoy del cliente del documento: con ella el cobro sabe la letra desde el primer momento.
    clientes = {
      obtener: vi.fn((id: number) => of({ id, razonSocial: 'Ferretería Norte SRL', condicionIva: 'RESPONSABLE_INSCRIPTO' } as Cliente)),
    };
    emisor = { obtener: vi.fn(() => of(emisorDePrueba())) };
    const sesion = signal({ userId: 7, nombre: 'Ana', email: 'ana@x.com', rol, ferreteriaId: 3, ferreteriaNombre: 'Tornillo' });
    await TestBed.configureTestingModule({
      imports: [Remitos],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: RemitosService, useValue: api },
        { provide: ArticulosService, useValue: articulos },
        { provide: ClientesService, useValue: clientes },
        { provide: EmisorService, useValue: emisor },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly(), esDueno: () => rol === 'DUENO' } },
        { provide: GENERAR_CLAVE, useValue: () => 'clave-nueva' },
      ],
    }).compileComponents();
    navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true) as unknown as ReturnType<typeof vi.fn>;
    fixture = TestBed.createComponent(Remitos);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const filas = () => Array.from(el().querySelectorAll<HTMLElement>('table.remitos tbody tr'));
  const celdas = (fila: HTMLElement) => Array.from(fila.querySelectorAll('td')).map((c) => normal(c.textContent));
  const detalle = () => el().querySelector<HTMLElement>('section.detalle');
  const boton = (texto: string, dentro: ParentNode = el()) =>
    Array.from(dentro.querySelectorAll<HTMLButtonElement>('button')).find((b) => normal(b.textContent) === texto);
  const alerta = () => normal(el().querySelector('[role="alert"]')?.textContent);

  async function abrirDetalle(indice = 0) {
    filas()[indice].click();
    await refrescar();
  }

  async function click(b: HTMLButtonElement | undefined) {
    expect(b, 'el botón existe').toBeDefined();
    b!.click();
    await refrescar();
  }

  function filtrar(nombre: string, valor: string) {
    const campo = el().querySelector<HTMLInputElement | HTMLSelectElement>(`form.filtros [name="${nombre}"]`)!;
    campo.value = valor;
    campo.dispatchEvent(new Event(campo instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  }

  async function buscar() {
    el().querySelector<HTMLFormElement>('form.filtros')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await refrescar();
  }

  beforeEach(() => localStorage.clear());

  describe('listado', () => {
    it('al abrir trae la primera página y muestra cada remito', async () => {
      await crear();

      expect(api.listar).toHaveBeenCalledWith({ page: 0 });
      expect(filas()).toHaveLength(2);
      expect(celdas(filas()[0])).toEqual(['5', '18/10 16:03', 'Cliente 5', '$ 3.000,00', 'Emitido']);
    });

    it('un remito sin cliente muestra un guión', async () => {
      await crear([resumen(5, { clienteNombre: undefined })]);

      expect(celdas(filas()[0])[2]).toBe('—');
    });

    it('muestra el estado de cada uno', async () => {
      await crear([resumen(7, { estado: 'FACTURADO' }), resumen(6, { estado: 'ANULADO' })]);

      expect(celdas(filas()[0])[4]).toBe('Facturado');
      expect(celdas(filas()[1])[4]).toBe('Anulado');
    });

    it('sin remitos avisa que todavía no hay', async () => {
      await crear([]);

      expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('Todavía no hay remitos.');
      expect(filas()).toHaveLength(0);
    });

    it('si no se pudo leer avisa y Reintentar vuelve a pedir', async () => {
      await crear();
      api.listar.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
      await buscar();

      expect(alerta()).toContain('No se pudieron cargar los remitos.');

      await click(boton('Reintentar'));

      expect(filas()).toHaveLength(2);
    });

    it('pagina: Siguiente pide la página 1 y Anterior vuelve a la 0', async () => {
      await crear();
      api.listar.mockReturnValue(of(pagina([resumen(5)], { page: 0, totalPages: 2, totalElements: 21 })));
      await buscar();
      expect(normal(el().querySelector('nav.paginas')?.textContent)).toContain('Página 1 de 2');

      api.listar.mockReturnValue(of(pagina([resumen(1)], { page: 1, totalPages: 2, totalElements: 21 })));
      await click(boton('Siguiente'));
      expect(api.listar).toHaveBeenLastCalledWith({ page: 1 });

      await click(boton('Anterior'));
      expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
    });

    it('el paginador va arriba de la tabla: no hay que bajar hasta el final para pasar de página', async () => {
      await crear();
      api.listar.mockReturnValue(of(pagina([resumen(5)], { page: 0, totalPages: 2, totalElements: 21 })));
      await buscar();

      const paginador = el().querySelector('nav.paginas')!;
      const tabla = el().querySelector('table.remitos')!;
      expect(paginador.compareDocumentPosition(tabla) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });
  });

  describe('filtros', () => {
    it('busca por estado, número y fechas, siempre desde la página 0', async () => {
      await crear();
      filtrar('estado', 'ANULADO');
      filtrar('numero', '5');
      filtrar('desde', '2026-10-01');
      filtrar('hasta', '2026-10-31');

      await buscar();

      expect(api.listar).toHaveBeenLastCalledWith({
        page: 0,
        estado: 'ANULADO',
        numero: 5,
        desde: '2026-10-01',
        hasta: '2026-10-31',
      });
    });

    it('los estados del filtro son los del remito', async () => {
      await crear();

      const opciones = Array.from(el().querySelectorAll('form.filtros [name="estado"] option')).map((o) => normal(o.textContent));

      expect(opciones).toEqual(['Todos', 'Emitido', 'Facturado', 'Anulado']);
    });

    it('un número que no es un entero positivo no se manda y avisa', async () => {
      await crear();
      filtrar('numero', 'abc');

      await buscar();

      expect(alerta()).toContain('El número tiene que ser un entero.');
      expect(api.listar).toHaveBeenCalledTimes(1);
    });

    it('Limpiar saca los filtros y vuelve a pedir todo', async () => {
      await crear();
      filtrar('estado', 'ANULADO');
      await buscar();

      await click(boton('Limpiar'));

      expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
      expect(el().querySelector<HTMLSelectElement>('form.filtros [name="estado"]')!.value).toBe('');
    });

    it('con filtros y sin resultados dice que no hay con esos filtros', async () => {
      await crear();
      filtrar('estado', 'FACTURADO');
      api.listar.mockReturnValue(of(pagina([])));

      await buscar();

      expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('No hay remitos con esos filtros.');
    });
  });

  describe('detalle', () => {
    it('al elegir uno lo trae y muestra el cliente, las observaciones, los renglones y los totales', async () => {
      await crear();

      await abrirDetalle(0);

      expect(api.obtener).toHaveBeenCalledWith(50);
      const d = detalle()!;
      expect(normal(d.querySelector('h2')?.textContent)).toBe('Remito N.º 5');
      expect(d.textContent).toContain('Cliente 5');
      expect(d.textContent).toContain('Entrega el viernes');
      const renglon = d.querySelector('table.renglones tbody tr')!;
      expect(normal(renglon.textContent)).toContain('Martillo');
      expect(normal(renglon.textContent)).toContain('$ 1.500,00');
      expect(normal(d.querySelector('.totales')?.textContent)).toContain('Total $ 3.000,00');
    });

    it('con descuento muestra cuánto se descontó', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(5, { subtotal: 3000, descuentoPorcentaje: 10, descuento: 300, total: 2700 })));

      await abrirDetalle(0);

      expect(normal(detalle()!.querySelector('.totales')?.textContent)).toContain('Descuento (10 %) -$ 300,00');
    });

    it('con descuento por renglón no muestra un porcentaje general que no es el del importe', async () => {
      await crear();
      const base = detalleDe(5);
      api.obtener.mockReturnValue(
        of({
          ...base,
          descuento: 300,
          total: 2700,
          renglones: [{ ...base.renglones[0], descuentoPorcentaje: 10, total: 2700 }],
        }),
      );

      await abrirDetalle(0);

      const totales = normal(detalle()!.querySelector('.totales')?.textContent);
      expect(totales).toContain('Descuento -$ 300,00');
      expect(totales).not.toContain('%');
    });

    it('si el filtro deja afuera el remito elegido, cierra su detalle', async () => {
      await crear();
      await abrirDetalle(0);
      expect(detalle()).not.toBeNull();

      api.listar.mockReturnValue(of(pagina([resumen(4)])));
      filtrar('numero', '4');
      await buscar();

      expect(detalle()).toBeNull();
    });

    it('si el remito elegido sigue en la lista nueva, deja su detalle abierto', async () => {
      await crear();
      await abrirDetalle(0);

      api.listar.mockReturnValue(of(pagina([resumen(5)])));
      filtrar('numero', '5');
      await buscar();

      expect(normal(detalle()?.querySelector('h2')?.textContent)).toBe('Remito N.º 5');
    });

    it('si no se pudo traer avisa dentro del detalle', async () => {
      await crear();
      api.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

      await abrirDetalle(0);

      expect(alerta()).toContain('No se pudo cargar el remito.');
    });

    it('con un id desconocido o de otra ferretería (404) dice que no se encontró el remito (QA 05/11)', async () => {
      await crear();
      api.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));

      await abrirDetalle(0);

      expect(alerta()).toContain('No se encontró el remito.');
    });

    it('si se elige otro mientras el primero carga, vale el último', async () => {
      await crear();
      const lento = new Subject<RemitoResponse>();
      api.obtener.mockReturnValueOnce(lento);
      await abrirDetalle(0);

      await abrirDetalle(1);
      lento.next(detalleDe(5));
      await refrescar();

      expect(normal(detalle()!.querySelector('h2')?.textContent)).toBe('Remito N.º 4');
    });

    it.each<[EstadoRemito, string, string[]]>([
      ['EMITIDO', 'VENDEDOR', ['Imprimir', 'Pasar a venta']],
      ['EMITIDO', 'DUENO', ['Imprimir', 'Anular', 'Pasar a venta']],
      ['FACTURADO', 'DUENO', ['Imprimir']],
      ['FACTURADO', 'VENDEDOR', ['Imprimir']],
      ['ANULADO', 'DUENO', ['Imprimir']],
    ])('un %s para el %s ofrece %j', async (estado, rol, esperados) => {
      await crear([resumen(5), resumen(4)], rol);
      api.obtener.mockReturnValue(of(detalleDe(5, { estado })));

      await abrirDetalle(0);

      const acciones = Array.from(detalle()!.querySelectorAll<HTMLButtonElement>('.acciones button')).map((b) => normal(b.textContent));
      expect(acciones).toEqual(esperados);
    });

    it('uno facturado dice que ya se facturó', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(5, { estado: 'FACTURADO' })));

      await abrirDetalle(0);

      expect(normal(detalle()!.querySelector('.nota-estado')?.textContent)).toBe('Ya se facturó.');
    });

    it('uno anulado dice que el stock ya volvió', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(5, { estado: 'ANULADO' })));

      await abrirDetalle(0);

      expect(normal(detalle()!.querySelector('.nota-estado')?.textContent)).toBe('Está anulado: el stock ya volvió.');
    });
  });

  describe('anular', () => {
    it('pide confirmar antes: avisa que devuelve el stock y no anula todavía', async () => {
      await crear([resumen(5)], 'DUENO');
      await abrirDetalle(0);

      await click(boton('Anular', detalle()!));

      expect(normal(el().querySelector('.confirmacion')?.textContent)).toContain(
        'Anular devuelve al stock lo que salió con el remito N.º 5.',
      );
      expect(api.anular).not.toHaveBeenCalled();
    });

    it('Cancelar lo deja como estaba', async () => {
      await crear([resumen(5)], 'DUENO');
      await abrirDetalle(0);
      await click(boton('Anular', detalle()!));

      await click(boton('Cancelar', el().querySelector<HTMLElement>('.confirmacion')!));

      expect(el().querySelector('.confirmacion')).toBeNull();
      expect(api.anular).not.toHaveBeenCalled();
      expect(boton('Anular', detalle()!)).toBeDefined();
    });

    it('Anular el remito lo anula, actualiza el detalle y la fila del listado', async () => {
      await crear([resumen(5)], 'DUENO');
      await abrirDetalle(0);
      await click(boton('Anular', detalle()!));

      await click(boton('Anular el remito', el().querySelector<HTMLElement>('.confirmacion')!));

      expect(api.anular).toHaveBeenCalledWith(50);
      expect(normal(detalle()!.querySelector('.estado')?.textContent)).toBe('Anulado');
      expect(celdas(filas()[0])[4]).toBe('Anulado');
      expect(el().querySelector('.confirmacion')).toBeNull();
      expect(Array.from(detalle()!.querySelectorAll('.acciones button')).map((b) => normal(b.textContent))).toEqual(['Imprimir']);
    });

    it('si ya se facturó (409) muestra el mensaje del servidor y vuelve a leer el remito', async () => {
      await crear([resumen(5)], 'DUENO');
      await abrirDetalle(0);
      await click(boton('Anular', detalle()!));
      api.anular.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'Ese remito ya se facturó.' } })),
      );
      api.obtener.mockReturnValue(of(detalleDe(5, { estado: 'FACTURADO' })));

      await click(boton('Anular el remito', el().querySelector<HTMLElement>('.confirmacion')!));

      expect(alerta()).toContain('Ese remito ya se facturó.');
      expect(Array.from(detalle()!.querySelectorAll('.acciones button')).map((b) => normal(b.textContent))).toEqual(['Imprimir']);
      expect(celdas(filas()[0])[4]).toBe('Facturado');
    });

    it('un error de red avisa para reintentar', async () => {
      await crear([resumen(5)], 'DUENO');
      await abrirDetalle(0);
      await click(boton('Anular', detalle()!));
      api.anular.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

      await click(boton('Anular el remito', el().querySelector<HTMLElement>('.confirmacion')!));

      expect(alerta()).toContain('No se pudo anular el remito. Probá de nuevo.');
    });

    it('con la anulación en curso los botones no se pueden apretar dos veces', async () => {
      await crear([resumen(5)], 'DUENO');
      await abrirDetalle(0);
      await click(boton('Anular', detalle()!));
      api.anular.mockReturnValue(new Subject<RemitoResponse>());

      await click(boton('Anular el remito', el().querySelector<HTMLElement>('.confirmacion')!));

      const botones = Array.from(el().querySelectorAll<HTMLButtonElement>('.confirmacion button'));
      expect(botones.every((b) => b.disabled)).toBe(true);
    });
  });

  describe('pasar a venta', () => {
    const ticketGuardado = () => JSON.parse(localStorage.getItem('ticket:3:7') ?? 'null');

    async function pasar() {
      await crear();
      await abrirDetalle(0);
      await click(boton('Pasar a venta', detalle()!));
    }

    it('si el documento tiene un cliente de la tabla, lo trae al ticket (CL-005)', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(5, { clienteId: 7, clienteNombre: 'Ferretería Norte SRL' })));
      await abrirDetalle(0);

      await click(boton('Pasar a venta', detalle()!));

      expect(ticketGuardado()).toMatchObject({ clienteId: 7, clienteNombre: 'Ferretería Norte SRL' });
    });

    it('también lee la condición de IVA de hoy del cliente y la deja en el ticket (para que el cobro diga la letra)', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(5, { clienteId: 7, clienteNombre: 'Ferretería Norte SRL' })));
      await abrirDetalle(0);

      await click(boton('Pasar a venta', detalle()!));

      expect(clientes.obtener).toHaveBeenCalledExactlyOnceWith(7);
      expect(ticketGuardado()).toMatchObject({ clienteId: 7, clienteCondicionIva: 'RESPONSABLE_INSCRIPTO' });
    });

    it('si el cliente no se puede leer, el ticket se arma igual, sin condición, y se abre el mostrador', async () => {
      await crear();
      clientes.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
      api.obtener.mockReturnValue(of(detalleDe(5, { clienteId: 7, clienteNombre: 'Ferretería Norte SRL' })));
      await abrirDetalle(0);

      await click(boton('Pasar a venta', detalle()!));

      expect(ticketGuardado()).toMatchObject({ clienteId: 7, clienteNombre: 'Ferretería Norte SRL' });
      expect(ticketGuardado().clienteCondicionIva).toBeUndefined();
      expect(navegar).toHaveBeenCalledWith('/mostrador');
    });

    it('con el cliente de texto libre (sin clienteId) el ticket queda sin cliente: Consumidor Final', async () => {
      await pasar();

      expect(ticketGuardado().clienteId).toBeUndefined();
      expect(ticketGuardado().clienteNombre).toBeUndefined();
      expect(clientes.obtener).not.toHaveBeenCalled();
    });

    it('lee el precio de hoy de cada artículo, deja el ticket guardado con el remito y abre el mostrador', async () => {
      await pasar();

      expect(articulos.obtener).toHaveBeenCalledWith(1);
      expect(ticketGuardado()).toMatchObject({
        v: 1,
        clave: 'clave-nueva',
        remitoId: 50,
        remitoNumero: 5,
        renglones: [{ articuloId: 1, descripcion: 'Martillo', precioCentavos: 180000, cantidadMilesimas: 2000 }],
      });
      expect(ticketGuardado().presupuestoId).toBeUndefined();
      expect(navegar).toHaveBeenCalledWith('/mostrador');
    });

    it('con una oferta vigente hoy, el renglón lleva el precio de oferta, como en la búsqueda del mostrador', async () => {
      await crear();
      articulos.obtener.mockReturnValue(
        of(articuloActual({ oferta: { tipo: 'PRECIO_FIJO', valor: 1500, hasta: null, vigente: true, precioOferta: 1500 } })),
      );
      await abrirDetalle(0);

      await click(boton('Pasar a venta', detalle()!));

      expect(ticketGuardado().renglones).toMatchObject([{ articuloId: 1, precioCentavos: 150000 }]);
    });

    it('con una oferta vencida, el renglón lleva el precio normal', async () => {
      await crear();
      articulos.obtener.mockReturnValue(
        of(articuloActual({ oferta: { tipo: 'PRECIO_FIJO', valor: 1500, hasta: '2026-01-31', vigente: false, precioOferta: null } })),
      );
      await abrirDetalle(0);

      await click(boton('Pasar a venta', detalle()!));

      expect(ticketGuardado().renglones).toMatchObject([{ articuloId: 1, precioCentavos: 180000 }]);
    });

    it('pide cada artículo una sola vez', async () => {
      await crear();
      api.obtener.mockReturnValue(
        of(detalleDe(5, { renglones: [detalleDe(5).renglones[0], { ...detalleDe(5).renglones[0], renglon: 2 }] })),
      );
      await abrirDetalle(0);

      await click(boton('Pasar a venta', detalle()!));

      expect(articulos.obtener).toHaveBeenCalledTimes(1);
    });

    it('un artículo que ya no existe (404) queda en el ticket marcado y igual se abre el mostrador', async () => {
      await crear();
      articulos.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
      await abrirDetalle(0);

      await click(boton('Pasar a venta', detalle()!));

      expect(ticketGuardado().renglones[0]).toMatchObject({ descripcion: 'Martillo', problema: 'Ya no está disponible' });
      expect(navegar).toHaveBeenCalledWith('/mostrador');
    });

    it('un artículo dado de baja queda marcado', async () => {
      await crear();
      articulos.obtener.mockReturnValue(of(articuloActual({ activo: false })));
      await abrirDetalle(0);

      await click(boton('Pasar a venta', detalle()!));

      expect(ticketGuardado().renglones[0].problema).toBe('Artículo dado de baja');
    });

    it('si no se pueden leer los precios (error de red) avisa, no toca el ticket y no abre el mostrador', async () => {
      await crear();
      articulos.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
      await abrirDetalle(0);

      await click(boton('Pasar a venta', detalle()!));

      expect(alerta()).toContain('No se pudieron leer los precios de hoy. Probá de nuevo.');
      expect(ticketGuardado()).toBeNull();
      expect(navegar).not.toHaveBeenCalled();
    });

    describe('con un ticket en curso en el mostrador', () => {
      const ticketEnCurso = () =>
        localStorage.setItem(
          'ticket:3:7',
          JSON.stringify({
            v: 1,
            clave: 'clave-vieja',
            elegido: 0,
            renglones: [
              { articuloId: 9, codigoInterno: 'Z', descripcion: 'Clavo', unidad: 'UNIDAD', precioCentavos: 100, cantidadMilesimas: 1000, stockActual: 1, stockMinimo: 0 },
            ],
          }),
        );

      async function pedirPasar() {
        ticketEnCurso();
        await crear();
        await abrirDetalle(0);
        await click(boton('Pasar a venta', detalle()!));
      }

      it('antes de reemplazarlo pregunta, sin leer precios ni tocar nada', async () => {
        await pedirPasar();

        expect(normal(el().querySelector('.reemplazo')?.textContent)).toContain('El mostrador ya tiene un ticket con 1 renglón.');
        expect(articulos.obtener).not.toHaveBeenCalled();
        expect(ticketGuardado().clave).toBe('clave-vieja');
        expect(navegar).not.toHaveBeenCalled();
      });

      it('Cancelar lo deja como estaba', async () => {
        await pedirPasar();

        await click(boton('Cancelar', el().querySelector<HTMLElement>('.reemplazo')!));

        expect(el().querySelector('.reemplazo')).toBeNull();
        expect(ticketGuardado().clave).toBe('clave-vieja');
        expect(navegar).not.toHaveBeenCalled();
      });

      it('Reemplazar el ticket lo cambia por el del remito y abre el mostrador', async () => {
        await pedirPasar();

        await click(boton('Reemplazar el ticket', el().querySelector<HTMLElement>('.reemplazo')!));

        expect(ticketGuardado()).toMatchObject({ clave: 'clave-nueva', remitoId: 50 });
        expect(navegar).toHaveBeenCalledWith('/mostrador');
      });
    });

    it('un ticket guardado vacío o roto no hace preguntar', async () => {
      localStorage.setItem('ticket:3:7', '{roto');
      await pasar();

      expect(el().querySelector('.reemplazo')).toBeNull();
      expect(navegar).toHaveBeenCalledWith('/mostrador');
    });
  });

  describe('imprimir', () => {
    const dialogo = () => el().querySelector<HTMLElement>('app-documento-no-fiscal-imprimible');

    it('«Imprimir» lee el emisor y abre la hoja del remito con su número, el emisor y la X', async () => {
      await crear();
      await abrirDetalle(0);

      await click(boton('Imprimir', detalle()!));

      expect(emisor.obtener).toHaveBeenCalledTimes(1);
      expect(dialogo()).not.toBeNull();
      expect(normal(dialogo()!.querySelector('#documento-titulo')?.textContent)).toBe('Remito N.º 5');
      expect(normal(dialogo()!.textContent)).toContain('Tornillo SA');
      expect(normal(dialogo()!.querySelector('.letra')?.textContent)).toBe('X');
      expect(normal(dialogo()!.textContent)).toContain('Martillo');
    });

    it('Esc cierra la hoja y deja el detalle', async () => {
      await crear();
      await abrirDetalle(0);
      await click(boton('Imprimir', detalle()!));

      dialogo()!.querySelector('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await refrescar();

      expect(dialogo()).toBeNull();
      expect(detalle()).not.toBeNull();
    });

    it('si el emisor no se pudo leer, avisa en el detalle y no abre la hoja', async () => {
      await crear();
      emisor.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
      await abrirDetalle(0);

      await click(boton('Imprimir', detalle()!));

      expect(alerta()).toBe('No se pudieron leer los datos de la ferretería. Probá de nuevo.');
      expect(dialogo()).toBeNull();
    });

    it('un remito facturado en una venta lo dice, con la factura si viene', async () => {
      await crear();
      api.obtener.mockReturnValue(
        of(detalleDe(5, { estado: 'FACTURADO', facturadoPor: { ventaId: 40, numero: 33, factura: 'Factura B 00001-00000012' } })),
      );

      await abrirDetalle(0);

      expect(normal(detalle()!.querySelector('.nota-estado')?.textContent)).toBe(
        'Facturado en la venta N.º 33 · Factura B 00001-00000012',
      );
      await click(boton('Imprimir', detalle()!));
      expect(normal(dialogo()!.querySelector('.facturado-por')?.textContent)).toBe(
        'Facturado en la venta N.º 33 · Factura B 00001-00000012',
      );
    });

    it('un remito facturado en una venta sin factura válida dice solo la venta', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(5, { estado: 'FACTURADO', facturadoPor: { ventaId: 40, numero: 33 } })));

      await abrirDetalle(0);

      expect(normal(detalle()!.querySelector('.nota-estado')?.textContent)).toBe('Facturado en la venta N.º 33');
    });
  });
});
