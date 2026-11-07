import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, ParamMap, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';
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
import { EstadoPresupuesto, PresupuestoResponse, PresupuestoResumen } from './presupuesto.models';
import { Presupuestos } from './presupuestos';
import { PresupuestosService } from './presupuestos.service';

registerLocaleData(localeEsAr);

describe('Presupuestos', () => {
  let fixture: ComponentFixture<Presupuestos>;
  let api: {
    listar: ReturnType<typeof vi.fn>;
    obtener: ReturnType<typeof vi.fn>;
    cambiarEstado: ReturnType<typeof vi.fn>;
  };
  let articulos: { obtener: ReturnType<typeof vi.fn> };
  let clientes: { obtener: ReturnType<typeof vi.fn> };
  let navegar: ReturnType<typeof vi.fn>;
  /** El `:id` de `/presupuestos/:id`: emitir otro es navegar a otro con la pantalla ya abierta. */
  let parametros: BehaviorSubject<ParamMap>;
  let emisor: { obtener: ReturnType<typeof vi.fn> };

  const resumen = (numero: number, cambios: Partial<PresupuestoResumen> = {}): PresupuestoResumen => ({
    presupuestoId: numero * 10,
    numero,
    estado: 'PENDIENTE',
    fecha: '2026-10-18T16:03:00',
    vence: '2026-11-02',
    vencido: false,
    clienteNombre: `Cliente ${numero}`,
    total: 3000,
    ...cambios,
  });

  const pagina = (
    items: PresupuestoResumen[],
    cambios: Partial<PageResponse<PresupuestoResumen>> = {},
  ): PageResponse<PresupuestoResumen> => ({
    items,
    page: 0,
    size: 20,
    totalElements: items.length,
    totalPages: 1,
    ...cambios,
  });

  const detalleDe = (numero: number, cambios: Partial<PresupuestoResponse> = {}): PresupuestoResponse => ({
    presupuestoId: numero * 10,
    numero,
    estado: 'PENDIENTE',
    fecha: '2026-10-18T16:03:00',
    vence: '2026-11-02',
    vencido: false,
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

  async function crear(items: PresupuestoResumen[] = [resumen(12), resumen(11)], rol = 'VENDEDOR', id: string | null = null) {
    parametros = new BehaviorSubject<ParamMap>(convertToParamMap(id === null ? {} : { id }));
    api = {
      listar: vi.fn(() => of(pagina(items))),
      obtener: vi.fn((id: number) => of(detalleDe(id / 10))),
      cambiarEstado: vi.fn((id: number, estado: EstadoPresupuesto) => of(detalleDe(id / 10, { estado }))),
    };
    articulos = { obtener: vi.fn(() => of(articuloActual())) };
    // La condición de IVA de hoy del cliente del documento: con ella el cobro sabe la letra desde el primer momento.
    clientes = {
      obtener: vi.fn((id: number) => of({ id, razonSocial: 'Ferretería Norte SRL', condicionIva: 'RESPONSABLE_INSCRIPTO' } as Cliente)),
    };
    emisor = { obtener: vi.fn(() => of(emisorDePrueba())) };
    const sesion = signal({ userId: 7, nombre: 'Ana', email: 'ana@x.com', rol, ferreteriaId: 3, ferreteriaNombre: 'Tornillo' });
    await TestBed.configureTestingModule({
      imports: [Presupuestos],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: parametros } },
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: PresupuestosService, useValue: api },
        { provide: ArticulosService, useValue: articulos },
        { provide: ClientesService, useValue: clientes },
        { provide: EmisorService, useValue: emisor },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly(), esDueno: () => rol === 'DUENO' } },
        { provide: GENERAR_CLAVE, useValue: () => 'clave-nueva' },
      ],
    }).compileComponents();
    navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true) as unknown as ReturnType<typeof vi.fn>;
    fixture = TestBed.createComponent(Presupuestos);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const filas = () => Array.from(el().querySelectorAll<HTMLElement>('table.presupuestos tbody tr'));
  const celdas = (fila: HTMLElement) => Array.from(fila.querySelectorAll('td')).map((c) => normal(c.textContent));
  /** El detalle del presupuesto elegido: el pop-up (detalle-en-popup). */
  const detalle = () => el().querySelector<HTMLElement>('app-dialogo-detalle [role="dialog"]');
  /** Las acciones del pie del pop-up, sin «Cerrar» (que tiene siempre). */
  const acciones = () =>
    Array.from(detalle()!.querySelectorAll<HTMLButtonElement>('.dialogo-detalle-pie .pie-acciones button:not(.cerrar)'));
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
    it('explica de dónde salen los presupuestos y qué se hace acá', async () => {
      await crear();

      const ayudas = Array.from(el().querySelectorAll('p.ayuda')).map((p) => normal(p.textContent));
      expect(ayudas).toContain(
        'Los presupuestos se arman en el Mostrador con F8. Acá los consultás, los imprimís, los marcás como aceptados o rechazados y los pasás a venta con los precios de hoy.',
      );
    });

    it('al abrir trae la primera página y muestra cada presupuesto', async () => {
      await crear();

      expect(api.listar).toHaveBeenCalledWith({ page: 0 });
      expect(filas()).toHaveLength(2);
      expect(celdas(filas()[0])).toEqual(['12', '18/10 16:03', 'Cliente 12', '$ 3.000,00', '02/11/2026', 'Pendiente']);
    });

    it('un presupuesto sin cliente muestra un guión', async () => {
      await crear([resumen(5, { clienteNombre: undefined })]);

      expect(celdas(filas()[0])[2]).toBe('—');
    });

    it('marca como vencido al pendiente o aceptado cuyo vencimiento pasó, y el estado sigue siendo el guardado', async () => {
      await crear([resumen(7, { vencido: true }), resumen(6, { estado: 'RECHAZADO' })]);

      expect(celdas(filas()[0])[5]).toBe('Pendiente · Vencido');
      expect(celdas(filas()[1])[5]).toBe('Rechazado');
    });

    it('sin presupuestos avisa que todavía no hay', async () => {
      await crear([]);

      expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('Todavía no hay presupuestos.');
      expect(filas()).toHaveLength(0);
    });

    it('si no se pudo leer avisa y Reintentar vuelve a pedir', async () => {
      await crear();
      api.listar.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
      await buscar();

      expect(alerta()).toContain('No se pudieron cargar los presupuestos.');

      await click(boton('Reintentar'));

      expect(filas()).toHaveLength(2);
    });

    it('pagina: Siguiente pide la página 1 y Anterior vuelve a la 0', async () => {
      await crear();
      api.listar.mockReturnValue(of(pagina([resumen(12)], { page: 0, totalPages: 2, totalElements: 21 })));
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
      api.listar.mockReturnValue(of(pagina([resumen(12)], { page: 0, totalPages: 2, totalElements: 21 })));
      await buscar();

      const paginador = el().querySelector('nav.paginas')!;
      const tabla = el().querySelector('table.presupuestos')!;
      expect(paginador.compareDocumentPosition(tabla) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });
  });

  describe('filtros', () => {
    it('busca por estado, número y fechas, siempre desde la página 0', async () => {
      await crear();
      filtrar('estado', 'ACEPTADO');
      filtrar('numero', '12');
      filtrar('desde', '2026-10-01');
      filtrar('hasta', '2026-10-31');

      await buscar();

      expect(api.listar).toHaveBeenLastCalledWith({
        page: 0,
        estado: 'ACEPTADO',
        numero: 12,
        desde: '2026-10-01',
        hasta: '2026-10-31',
      });
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
      filtrar('estado', 'ACEPTADO');
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

      expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('No hay presupuestos con esos filtros.');
    });
  });

  describe('detalle', () => {
    it('al elegir uno lo trae y muestra el cliente, las observaciones, los renglones y los totales', async () => {
      await crear();

      await abrirDetalle(0);

      expect(api.obtener).toHaveBeenCalledWith(120);
      const d = detalle()!;
      expect(normal(d.querySelector('h2')?.textContent)).toBe('Presupuesto N.º 12');
      expect(d.textContent).toContain('Cliente 12');
      expect(d.textContent).toContain('Entrega el viernes');
      expect(d.textContent).toContain('Vence 02/11/2026');
      const renglon = d.querySelector('table.renglones tbody tr')!;
      expect(normal(renglon.textContent)).toContain('Martillo');
      expect(normal(renglon.textContent)).toContain('$ 1.500,00');
      expect(normal(d.querySelector('.totales')?.textContent)).toContain('Total $ 3.000,00');
    });

    it('el detalle se abre en un pop-up encima de la lista, con el título y el estado arriba y «Pasar a venta» como principal (#59)', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(12, { vencido: true })));

      await abrirDetalle(0);

      const pop = detalle()!;
      expect(pop.getAttribute('aria-modal')).toBe('true');
      expect(pop.closest('.dialogo-fondo')).not.toBeNull();
      const cabecera = pop.querySelector('.dialogo-detalle-cabecera')!;
      expect(normal(cabecera.querySelector('h2')?.textContent)).toBe('Presupuesto N.º 12');
      expect(normal(cabecera.querySelector('.estado')?.textContent)).toBe('Pendiente');
      expect(normal(cabecera.querySelector('.vencido')?.textContent)).toBe('Vencido');
      expect(pop.querySelector('.dialogo-detalle-cuerpo table.renglones')).not.toBeNull();
      expect(pop.querySelector('.dialogo-detalle-pie .pie-peligro button')).toBeNull();
      const pasar = boton('Pasar a venta', pop)!;
      expect(pasar.closest('.pie-acciones')).not.toBeNull();
      expect(pasar.classList).toContain('primario');
      expect(filas()).toHaveLength(2);
      expect(document.activeElement).toBe(pop.querySelector('button[aria-label="Cerrar"]'));
    });

    it('mientras el detalle carga, el pop-up ya se titula con el presupuesto de la fila', async () => {
      await crear();
      api.obtener.mockReturnValue(new Subject<PresupuestoResponse>());

      await abrirDetalle(1);

      expect(normal(detalle()!.querySelector('h2')?.textContent)).toBe('Presupuesto N.º 11');
      expect(normal(detalle()!.querySelector('.dialogo-detalle-cuerpo')?.textContent)).toBe('Cargando…');
    });

    it('Esc y la ✕ cierran el pop-up; la lista queda como estaba', async () => {
      await crear();
      await abrirDetalle(0);

      (document.activeElement ?? document.body).dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );
      await refrescar();
      expect(detalle()).toBeNull();

      await abrirDetalle(1);
      await click(detalle()!.querySelector<HTMLButtonElement>('button[aria-label="Cerrar"]')!);
      expect(detalle()).toBeNull();
      expect(filas()).toHaveLength(2);
      expect(api.listar).toHaveBeenCalledTimes(1);
    });

    it('con descuento muestra cuánto se descontó', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(12, { subtotal: 3000, descuentoPorcentaje: 10, descuento: 300, total: 2700 })));

      await abrirDetalle(0);

      expect(normal(detalle()!.querySelector('.totales')?.textContent)).toContain('Descuento (10 %) -$ 300,00');
    });

    it('con descuento por renglón no muestra un porcentaje general que no es el del importe', async () => {
      await crear();
      const base = detalleDe(12);
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

    it('si el filtro deja afuera el presupuesto elegido, cierra su detalle', async () => {
      await crear();
      await abrirDetalle(0);
      expect(detalle()).not.toBeNull();

      api.listar.mockReturnValue(of(pagina([resumen(11)])));
      filtrar('numero', '11');
      await buscar();

      expect(detalle()).toBeNull();
    });

    it('si el presupuesto elegido sigue en la lista nueva, deja su detalle abierto', async () => {
      await crear();
      await abrirDetalle(0);

      api.listar.mockReturnValue(of(pagina([resumen(12)])));
      filtrar('numero', '12');
      await buscar();

      expect(normal(detalle()?.querySelector('h2')?.textContent)).toBe('Presupuesto N.º 12');
    });

    it('si no se pudo traer avisa dentro del detalle', async () => {
      await crear();
      api.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

      await abrirDetalle(0);

      expect(alerta()).toContain('No se pudo cargar el presupuesto.');
    });

    it('con un id desconocido o de otra ferretería (404) dice que no se encontró el presupuesto (QA 05/11)', async () => {
      await crear();
      api.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));

      await abrirDetalle(0);

      expect(alerta()).toContain('No se encontró el presupuesto.');
    });

    it('si se elige otro mientras el primero carga, vale el último', async () => {
      await crear();
      const lento = new Subject<PresupuestoResponse>();
      api.obtener.mockReturnValueOnce(lento);
      await abrirDetalle(0);

      await abrirDetalle(1);
      lento.next(detalleDe(12));
      await refrescar();

      expect(normal(detalle()!.querySelector('h2')?.textContent)).toBe('Presupuesto N.º 11');
    });

    it.each<[EstadoPresupuesto, string[]]>([
      ['PENDIENTE', ['Imprimir', 'Aceptar', 'Rechazar', 'Pasar a venta']],
      ['ACEPTADO', ['Imprimir', 'Rechazar', 'Volver a pendiente', 'Pasar a venta']],
      ['RECHAZADO', ['Imprimir']],
      ['FACTURADO', ['Imprimir']],
    ])('al Vendedor, un %s le ofrece %j', async (estado, esperados) => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(12, { estado })));

      await abrirDetalle(0);

      expect(acciones().map((b) => normal(b.textContent))).toEqual(esperados);
    });

    it('al Dueño, un RECHAZADO le ofrece reactivarlo: un rechazado lo reactiva solo el Dueño', async () => {
      await crear(undefined, 'DUENO');
      api.obtener.mockReturnValue(of(detalleDe(12, { estado: 'RECHAZADO' })));

      await abrirDetalle(0);

      expect(acciones().map((b) => normal(b.textContent))).toEqual(['Imprimir', 'Aceptar', 'Volver a pendiente']);
    });

    it('uno vencido se puede pasar a venta: el precio de hoy no depende del vencimiento', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(12, { vencido: true })));

      await abrirDetalle(0);

      expect(detalle()!.textContent).toContain('Vencido');
      expect(boton('Pasar a venta', detalle()!)).toBeDefined();
    });

    it('uno pasado a venta dice «Vendido» y en qué venta, sin hablar de factura si la venta no tiene (arreglos-confusos, S5)', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(12, { estado: 'FACTURADO', facturadoPor: { ventaId: 80, numero: 19 } })));

      await abrirDetalle(0);

      expect(normal(detalle()!.querySelector('.estado')?.textContent)).toBe('Vendido');
      expect(normal(detalle()!.querySelector('.nota-estado')?.textContent)).toBe('Vendido en la venta N.º 19');
      expect(detalle()!.textContent).not.toContain('Facturado');
      expect(detalle()!.textContent).not.toContain('facturó');
    });

    it('si la venta tiene una factura válida, la suma a la nota: «Vendido en la venta N.º 19 · Factura B 00001-00000012» (arreglos-confusos, S5)', async () => {
      await crear();
      api.obtener.mockReturnValue(
        of(
          detalleDe(12, {
            estado: 'FACTURADO',
            facturadoPor: { ventaId: 80, numero: 19, factura: 'Factura B 00001-00000012' },
          }),
        ),
      );

      await abrirDetalle(0);

      expect(normal(detalle()!.querySelector('.nota-estado')?.textContent)).toBe(
        'Vendido en la venta N.º 19 · Factura B 00001-00000012',
      );
    });

    it('si el servidor no manda la venta, dice «Ya se vendió.» (arreglos-confusos, S5)', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(12, { estado: 'FACTURADO' })));

      await abrirDetalle(0);

      expect(normal(detalle()!.querySelector('.nota-estado')?.textContent)).toBe('Ya se vendió.');
    });

    it('uno que no se pasó a venta no lleva nota de venta', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(12, { estado: 'ACEPTADO' })));

      await abrirDetalle(0);

      expect(detalle()!.querySelector('.nota-estado')).toBeNull();
    });
  });

  describe('cambiar el estado', () => {
    it('Aceptar pasa a aceptado, actualiza el detalle y la fila del listado', async () => {
      await crear();
      await abrirDetalle(0);

      await click(boton('Aceptar', detalle()!));

      expect(api.cambiarEstado).toHaveBeenCalledWith(120, 'ACEPTADO');
      expect(normal(detalle()!.querySelector('.estado')?.textContent)).toBe('Aceptado');
      expect(celdas(filas()[0])[5]).toBe('Aceptado');
      expect(boton('Aceptar', detalle()!)).toBeUndefined();
    });

    it('Rechazar y Volver a pendiente mandan su estado (el Dueño, que es quien reactiva un rechazado)', async () => {
      await crear(undefined, 'DUENO');
      await abrirDetalle(0);

      await click(boton('Rechazar', detalle()!));
      expect(api.cambiarEstado).toHaveBeenLastCalledWith(120, 'RECHAZADO');
      await click(boton('Volver a pendiente', detalle()!));

      expect(api.cambiarEstado).toHaveBeenLastCalledWith(120, 'PENDIENTE');
    });

    it('si ya se facturó (409) muestra el mensaje del servidor y vuelve a leer el presupuesto', async () => {
      await crear();
      await abrirDetalle(0);
      api.cambiarEstado.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'Ese presupuesto ya se facturó.' } })),
      );
      api.obtener.mockReturnValue(of(detalleDe(12, { estado: 'FACTURADO' })));

      await click(boton('Aceptar', detalle()!));

      expect(alerta()).toContain('Ese presupuesto ya se facturó.');
      expect(acciones().map((b) => normal(b.textContent))).toEqual(['Imprimir']);
    });

    it('con el cambio en curso los botones no se pueden apretar dos veces', async () => {
      await crear();
      await abrirDetalle(0);
      const respuesta = new Subject<PresupuestoResponse>();
      api.cambiarEstado.mockReturnValue(respuesta);

      await click(boton('Aceptar', detalle()!));

      expect(acciones().every((b) => b.disabled)).toBe(true);
    });
  });

  describe('pasar a venta', () => {
    const ticketGuardado = () => JSON.parse(localStorage.getItem('ticket:3:7') ?? 'null');

    async function pasar() {
      await crear();
      await abrirDetalle(0);
      await click(boton('Pasar a venta', detalle()!));
    }

    it('lee el precio de hoy de cada artículo, deja el ticket guardado con el presupuesto y abre el mostrador', async () => {
      await pasar();

      expect(articulos.obtener).toHaveBeenCalledWith(1);
      expect(ticketGuardado()).toMatchObject({
        v: 1,
        clave: 'clave-nueva',
        presupuestoId: 120,
        presupuestoNumero: 12,
        renglones: [{ articuloId: 1, descripcion: 'Martillo', precioCentavos: 180000, cantidadMilesimas: 2000 }],
      });
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

    it('si el documento tiene un cliente de la tabla, lo trae al ticket (CL-005)', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(12, { clienteId: 7, clienteNombre: 'Ferretería Norte SRL' })));
      await abrirDetalle(0);

      await click(boton('Pasar a venta', detalle()!));

      expect(ticketGuardado()).toMatchObject({ clienteId: 7, clienteNombre: 'Ferretería Norte SRL' });
    });

    it('también lee la condición de IVA de hoy del cliente y la deja en el ticket (para que el cobro diga la letra)', async () => {
      await crear();
      api.obtener.mockReturnValue(of(detalleDe(12, { clienteId: 7, clienteNombre: 'Ferretería Norte SRL' })));
      await abrirDetalle(0);

      await click(boton('Pasar a venta', detalle()!));

      expect(clientes.obtener).toHaveBeenCalledExactlyOnceWith(7);
      expect(ticketGuardado()).toMatchObject({ clienteId: 7, clienteCondicionIva: 'RESPONSABLE_INSCRIPTO' });
    });

    it('si el cliente no se puede leer, el ticket se arma igual, sin condición, y se abre el mostrador', async () => {
      await crear();
      clientes.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
      api.obtener.mockReturnValue(of(detalleDe(12, { clienteId: 7, clienteNombre: 'Ferretería Norte SRL' })));
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

    it('pide cada artículo una sola vez', async () => {
      await crear();
      api.obtener.mockReturnValue(
        of(detalleDe(12, { renglones: [detalleDe(12).renglones[0], { ...detalleDe(12).renglones[0], renglon: 2 }] })),
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
        // La pregunta va en el cuerpo del pop-up con el foco en Cancelar (así queda a la vista) y, mientras tanto, el pie
        // no ofrece otras acciones.
        const reemplazo = detalle()!.querySelector<HTMLElement>('.dialogo-detalle-cuerpo .reemplazo')!;
        expect(document.activeElement).toBe(boton('Cancelar', reemplazo));
        expect(acciones()).toEqual([]);
        expect(articulos.obtener).not.toHaveBeenCalled();
        expect(ticketGuardado().clave).toBe('clave-vieja');
        expect(navegar).not.toHaveBeenCalled();
      });

      it('Esc cancela la pregunta sin cerrar el detalle', async () => {
        await pedirPasar();

        (document.activeElement ?? document.body).dispatchEvent(
          new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
        );
        await refrescar();

        expect(el().querySelector('.reemplazo')).toBeNull();
        expect(detalle()).not.toBeNull();
        expect(ticketGuardado().clave).toBe('clave-vieja');
      });

      it('Cancelar lo deja como estaba', async () => {
        await pedirPasar();

        await click(boton('Cancelar', el().querySelector<HTMLElement>('.reemplazo')!));

        expect(el().querySelector('.reemplazo')).toBeNull();
        expect(ticketGuardado().clave).toBe('clave-vieja');
        expect(navegar).not.toHaveBeenCalled();
      });

      it('Reemplazar el ticket lo cambia por el del presupuesto y abre el mostrador', async () => {
        await pedirPasar();

        await click(boton('Reemplazar el ticket', el().querySelector<HTMLElement>('.reemplazo')!));

        expect(ticketGuardado()).toMatchObject({ clave: 'clave-nueva', presupuestoId: 120 });
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

    it('«Imprimir» lee el emisor y abre la hoja del presupuesto con su número, «Válido hasta» y la X', async () => {
      await crear();
      await abrirDetalle(0);

      await click(boton('Imprimir', detalle()!));

      expect(emisor.obtener).toHaveBeenCalledTimes(1);
      expect(dialogo()).not.toBeNull();
      expect(normal(dialogo()!.querySelector('#documento-titulo')?.textContent)).toBe('Presupuesto N.º 12');
      expect(normal(dialogo()!.querySelector('.valido-hasta')?.textContent)).toBe('Válido hasta 02/11/2026');
      expect(normal(dialogo()!.querySelector('.letra')?.textContent)).toBe('X');
      expect(normal(dialogo()!.textContent)).toContain('Tornillo SA');
    });

    it('el Vendedor y el Dueño lo imprimen; Esc cierra la hoja y deja el detalle', async () => {
      await crear([resumen(12), resumen(11)], 'DUENO');
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
  });

  describe('un presupuesto elegido desde la dirección (informacion-y-links, S2)', () => {
    const esc = () =>
      (document.activeElement ?? document.body).dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );

    it('`/presupuestos/:id` abre ese presupuesto aunque no esté en la página de la lista, y la lista no lo cierra', async () => {
      await crear([resumen(12)], 'VENDEDOR', '90');

      expect(api.obtener).toHaveBeenCalledWith(90);
      expect(normal(detalle()!.querySelector('h2')!.textContent)).toBe('Presupuesto N.º 9');
      expect(filas()).toHaveLength(1);
    });

    it('cerrarlo deja la lista sin el presupuesto en la dirección', async () => {
      await crear([resumen(12)], 'VENDEDOR', '90');

      esc();
      await refrescar();

      expect(detalle()).toBeNull();
      expect(navegar).toHaveBeenCalledWith('/presupuestos');
    });

    it('abierto desde la lista, cerrarlo no cambia la dirección', async () => {
      await crear();
      filas()[0].click();
      await refrescar();

      esc();
      await refrescar();

      expect(detalle()).toBeNull();
      expect(navegar).not.toHaveBeenCalled();
    });

    it('uno de otra ferretería o inexistente (404) da el aviso de siempre', async () => {
      await crear([resumen(12)]);
      api.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
      parametros.next(convertToParamMap({ id: '77' }));
      await refrescar();

      expect(normal(detalle()!.querySelector('.error')!.textContent)).toBe('No se encontró el presupuesto.');
    });

    it('otro en la dirección con la pantalla abierta abre ese', async () => {
      await crear([resumen(12)], 'VENDEDOR', '90');
      parametros.next(convertToParamMap({ id: '120' }));
      await refrescar();

      expect(api.obtener).toHaveBeenLastCalledWith(120);
      expect(normal(detalle()!.querySelector('h2')!.textContent)).toBe('Presupuesto N.º 12');
    });
  });

  describe('unidades y el link a la venta (informacion-y-links, S2 y S3)', () => {
    it('la cantidad sale con su unidad', async () => {
      await crear();
      const base = detalleDe(12);
      api.obtener.mockReturnValue(
        of({
          ...base,
          renglones: [
            { ...base.renglones[0], descripcion: 'Cable', unidad: 'METRO', cantidad: 25.5 },
            { ...base.renglones[0], renglon: 2, cantidad: 1 },
          ],
        }),
      );

      await abrirDetalle(0);

      const cantidades = Array.from(detalle()!.querySelectorAll('.renglones tbody tr')).map((tr) =>
        normal(tr.querySelectorAll('td')[1].textContent),
      );
      expect(cantidades).toEqual(['25,5 metros', '1 unidad']);
    });

    it('para el Dueño, «Vendido en la venta N.º n» es un link al pop-up de esa venta; para el Vendedor, texto', async () => {
      await crear(undefined, 'DUENO');
      api.obtener.mockReturnValue(of(detalleDe(12, { estado: 'FACTURADO', facturadoPor: { ventaId: 80, numero: 19 } })));
      await abrirDetalle(0);

      expect(normal(detalle()!.querySelector('.nota-estado')!.textContent)).toBe('Vendido en la venta N.º 19');
      expect(detalle()!.querySelector('.nota-estado a')!.getAttribute('href')).toBe('/ventas/80');

      TestBed.resetTestingModule();
      await crear(undefined, 'VENDEDOR');
      api.obtener.mockReturnValue(of(detalleDe(12, { estado: 'FACTURADO', facturadoPor: { ventaId: 80, numero: 19 } })));
      await abrirDetalle(0);

      expect(detalle()!.querySelector('.nota-estado a')).toBeNull();
    });
  });
});
