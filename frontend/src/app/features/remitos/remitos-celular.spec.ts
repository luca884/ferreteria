import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, ParamMap, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Observable, Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { EmisorService } from '../../shared/documento-no-fiscal/emisor.service';
import { emisorDePrueba } from '../../shared/documento-no-fiscal/documento-no-fiscal.testing';
import { Articulo } from '../articulos/articulo.models';
import { ArticulosService } from '../articulos/articulos.service';
import { Cliente } from '../clientes/cliente.models';
import { ClientesService } from '../clientes/clientes.service';
import { GENERAR_CLAVE } from '../mostrador/ticket.store';
import { EstadoRemito, RemitoResponse, RemitoResumen } from './remito.models';
import { Remitos } from './remitos';
import { RemitosService } from './remitos.service';

registerLocaleData(localeEsAr);

/**
 * La lista y el detalle de remitos en el celular (movil-sectores T12c, S1, S2, S5, S6, S7): sólo cambia cómo se ve. Anular
 * un remito devuelve stock y pasarlo a venta arma el ticket del cobro: los pedidos, los textos, los errores y los roles
 * corren en el celular y en el escritorio con los mismos literales (y los helpers abren filtros, filas y acciones por la
 * pantalla que haya, así esos tests también corren contra la base); los de estructura miran lo propio del celular. En
 * escritorio y en tablet (matchMedia de 1023.98 px) no aparece nada de eso.
 */
describe('Remitos en el celular (T12c)', () => {
  type Modo = 'escritorio' | 'tablet' | 'celular';
  let fixture: ComponentFixture<Remitos>;
  let api: Record<'listar' | 'obtener' | 'anular', ReturnType<typeof vi.fn>>;
  let articulos: { obtener: ReturnType<typeof vi.fn> };
  let clientes: { obtener: ReturnType<typeof vi.fn> };
  let emisor: { obtener: ReturnType<typeof vi.fn> };
  let navegar: ReturnType<typeof vi.fn>;
  let parametros: BehaviorSubject<ParamMap>;
  let modoPorDefecto: Modo = 'celular';

  const resumen = (numero: number, cambios: Partial<RemitoResumen> = {}): RemitoResumen => ({
    remitoId: numero * 10,
    numero,
    estado: 'EMITIDO',
    fecha: '2026-10-18T16:03:00',
    clienteNombre: `Cliente ${numero}`,
    total: 3000,
    ...cambios,
  });

  const pagina = (items: RemitoResumen[], cambios: Partial<PageResponse<RemitoResumen>> = {}): PageResponse<RemitoResumen> => ({
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

  const articuloActual = () =>
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
    }) as Articulo;

  async function crear(
    opciones: {
      items?: RemitoResumen[];
      rol?: string;
      modo?: Modo;
      id?: string | null;
      lista?: PageResponse<RemitoResumen>;
      listar?: () => Observable<PageResponse<RemitoResumen>>;
    } = {},
  ) {
    const modo = opciones.modo ?? modoPorDefecto;
    const rol = opciones.rol ?? 'DUENO';
    if (modo === 'celular') {
      vi.stubGlobal('matchMedia', (c: string) => ({ matches: c.includes('639.98px'), media: c }));
    } else if (modo === 'tablet') {
      vi.stubGlobal('matchMedia', (c: string) => ({ matches: c.includes('1023.98px'), media: c }));
    }
    const id = opciones.id ?? null;
    parametros = new BehaviorSubject<ParamMap>(convertToParamMap(id === null ? {} : { id }));
    const items = opciones.items ?? [resumen(5), resumen(4)];
    api = {
      listar: vi.fn(opciones.listar ?? (() => of(opciones.lista ?? pagina(items)))),
      obtener: vi.fn((ident: number) => of(detalleDe(ident / 10))),
      anular: vi.fn((ident: number) => of(detalleDe(ident / 10, { estado: 'ANULADO' }))),
    };
    articulos = { obtener: vi.fn(() => of(articuloActual())) };
    clientes = {
      obtener: vi.fn((cid: number) => of({ id: cid, razonSocial: 'Ferretería Norte SRL', condicionIva: 'RESPONSABLE_INSCRIPTO' } as Cliente)),
    };
    emisor = { obtener: vi.fn(() => of(emisorDePrueba())) };
    const sesion = signal({ userId: 7, nombre: 'Ana', email: 'ana@x.com', rol, ferreteriaId: 3, ferreteriaNombre: 'Tornillo' });
    await TestBed.configureTestingModule({
      imports: [Remitos],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: parametros } },
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

  beforeEach(() => localStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends Element = HTMLElement>(selector: string, dentro: ParentNode = el()) => dentro.querySelector<T>(selector);
  const qa = (selector: string, dentro: ParentNode = el()) => Array.from(dentro.querySelectorAll<HTMLElement>(selector));
  const boton = (texto: string, dentro: ParentNode = el()) =>
    Array.from(dentro.querySelectorAll<HTMLButtonElement>('button')).find((b) => normal(b.textContent) === texto);
  const alerta = () => normal(q('[role="alert"]')?.textContent);
  const esc = () =>
    (document.activeElement ?? document.body).dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));

  const filas = () => {
    const tarjetas = qa('.documentos-celular .doc-tarjeta');
    return tarjetas.length > 0 ? tarjetas : qa('table.remitos tbody tr');
  };
  const raiz = () => q('app-detalle-pantalla [role="dialog"]') ?? q('app-dialogo-detalle [role="dialog"]');
  const hoja = () => q('app-hoja-inferior [role="dialog"]');
  const masOpciones = () => q<HTMLButtonElement>('button[aria-label="Más opciones del remito"]');
  /** La confirmación de anular: el modal compartido, fuera del detalle. */
  const confirmacion = () => q('app-confirmacion [role="alertdialog"]');

  async function click(b: HTMLElement | null | undefined) {
    expect(b, 'el botón existe').toBeTruthy();
    b!.click();
    await refrescar();
  }

  async function abrirDetalle(indice = 0) {
    await click(filas()[indice]);
  }

  async function abrirFiltros() {
    const b = q<HTMLButtonElement>('button.filtros-celular');
    if (b) await click(b);
  }

  function filtrar(nombre: string, valor: string) {
    const campo = q<HTMLInputElement | HTMLSelectElement>(`form.filtros [name="${nombre}"]`)!;
    campo.value = valor;
    campo.dispatchEvent(new Event(campo instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  }

  async function buscar() {
    q<HTMLFormElement>('form.filtros')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await refrescar();
  }

  /** Lo que el detalle ofrece: el pie y, si lo hay, lo que trae el ⋯ (se abre y se cierra para leerlo). */
  async function ofrece(): Promise<string[]> {
    const pie = qa('.detalle-pantalla-pie button, .dialogo-detalle-pie button:not(.cerrar)', raiz()!).map((b) => normal(b.textContent));
    const mas = masOpciones();
    if (!mas) return pie;
    await click(mas);
    const enLaHoja = qa('.accion-hoja', hoja()!).map((b) => normal(b.textContent));
    esc();
    await refrescar();
    return [...pie, ...enLaHoja];
  }

  /** Usa una acción del detalle, esté en el pie o en el ⋯. */
  async function usar(texto: string) {
    const directo = boton(texto, raiz()!);
    if (directo) return click(directo);
    await click(masOpciones());
    await click(boton(texto, hoja()!));
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Pedidos, textos y roles: iguales en el celular y en el escritorio, con literales. Pasan en la base.
  // ---------------------------------------------------------------------------------------------------------------
  describe.each(['celular', 'escritorio'] as const)('pedidos, textos y roles en %s', (modo) => {
    beforeEach(() => {
      modoPorDefecto = modo;
    });

    describe('la lista y los filtros', () => {
      it('al abrir pide la primera página sin filtros y muestra cada remito', async () => {
        await crear();

        expect(api.listar).toHaveBeenCalledTimes(1);
        expect(api.listar).toHaveBeenCalledWith({ page: 0 });
        expect(filas()).toHaveLength(2);
        expect(normal(filas()[0].textContent)).toContain('Cliente 5');
        expect(normal(filas()[0].textContent)).toContain('$ 3.000,00');
        expect(normal(filas()[0].textContent)).toContain('Emitido');
      });

      it('un remito sin cliente muestra un guión', async () => {
        await crear({ items: [resumen(5, { clienteNombre: undefined })] });

        expect(normal(filas()[0].textContent)).toContain('—');
      });

      it('sin remitos dice que todavía no hay; con filtros, que no hay con esos filtros', async () => {
        await crear({ items: [] });
        expect(normal(q('.estado-vacio')?.textContent)).toBe('Todavía no hay remitos.');

        await abrirFiltros();
        filtrar('estado', 'ANULADO');
        await buscar();

        expect(normal(q('.estado-vacio')?.textContent)).toBe('No hay remitos con esos filtros.');
      });

      it('los estados del filtro son los del remito', async () => {
        await crear();
        await abrirFiltros();

        const opciones = qa('form.filtros [name="estado"] option').map((o) => [o.getAttribute('value'), normal(o.textContent)]);
        expect(opciones).toEqual([
          ['', 'Todos'],
          ['EMITIDO', 'Emitido'],
          ['FACTURADO', 'Facturado'],
          ['ANULADO', 'Anulado'],
        ]);
      });

      it('busca por estado, número y fechas, siempre desde la página 0', async () => {
        await crear();
        await abrirFiltros();
        filtrar('estado', 'FACTURADO');
        filtrar('numero', ' 5 ');
        filtrar('desde', '2026-10-01');
        filtrar('hasta', '2026-10-31');

        await buscar();

        expect(api.listar).toHaveBeenLastCalledWith({
          page: 0,
          estado: 'FACTURADO',
          numero: 5,
          desde: '2026-10-01',
          hasta: '2026-10-31',
        });
      });

      it('un número que no es un entero positivo no se manda y avisa', async () => {
        await crear();
        await abrirFiltros();
        filtrar('numero', 'abc');

        await buscar();

        expect(alerta()).toBe('El número tiene que ser un entero.');
        expect(api.listar).toHaveBeenCalledTimes(1);
      });

      it('con «Hasta» antes de «Desde» avisa, no busca y enfoca «Hasta»; corregido, busca', async () => {
        await crear();
        await abrirFiltros();
        filtrar('desde', '2026-10-22');
        filtrar('hasta', '2026-10-21');

        await buscar();

        expect(api.listar).toHaveBeenCalledTimes(1);
        expect(alerta()).toBe('La fecha «Hasta» no puede ser anterior a «Desde».');
        expect(document.activeElement).toBe(q('input[name="hasta"]'));

        filtrar('hasta', '2026-10-22');
        await buscar();

        expect(api.listar).toHaveBeenLastCalledWith({ page: 0, desde: '2026-10-22', hasta: '2026-10-22' });
        expect(el().textContent).not.toContain('no puede ser anterior');
      });

      it('Limpiar saca los filtros y vuelve a pedir todo', async () => {
        await crear();
        await abrirFiltros();
        filtrar('estado', 'ANULADO');
        filtrar('numero', '5');
        await buscar();
        await abrirFiltros();

        await click(boton('Limpiar'));

        expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
        await abrirFiltros();
        expect(q<HTMLSelectElement>('form.filtros [name="estado"]')!.value).toBe('');
        expect(q<HTMLInputElement>('form.filtros [name="numero"]')!.value).toBe('');
      });

      it('si no se pudo leer avisa y Reintentar vuelve a pedir la misma página', async () => {
        await crear();
        api.listar.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
        await abrirFiltros();
        await buscar();

        expect(alerta()).toContain('No se pudieron cargar los remitos.');

        await click(boton('Reintentar'));

        expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
        expect(filas()).toHaveLength(2);
        expect(q('[role="alert"]')).toBeNull();
      });

      it('pagina con los filtros puestos: Siguiente pide la página 1 y Anterior vuelve a la 0', async () => {
        await crear({ lista: pagina([resumen(5)], { totalPages: 2, totalElements: 21 }) });
        await abrirFiltros();
        filtrar('estado', 'EMITIDO');
        await buscar();
        expect(normal(q('nav.paginas')?.textContent)).toContain('Página 1 de 2');

        api.listar.mockReturnValue(of(pagina([resumen(1)], { page: 1, totalPages: 2, totalElements: 21 })));
        await click(boton('Siguiente'));
        expect(api.listar).toHaveBeenLastCalledWith({ page: 1, estado: 'EMITIDO' });

        await click(boton('Anterior'));
        expect(api.listar).toHaveBeenLastCalledWith({ page: 0, estado: 'EMITIDO' });
      });

      it('el paginador va arriba de la lista', async () => {
        await crear({ lista: pagina([resumen(5)], { totalPages: 2, totalElements: 21 }) });

        const lista = q('table.remitos, .documentos-celular')!;
        expect(q('nav.paginas')!.compareDocumentPosition(lista) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      });

      it('el texto de ayuda de arriba es el de hoy', async () => {
        await crear();

        expect(qa('p.ayuda').map((p) => normal(p.textContent))).toContain(
          'Los remitos se emiten en el Mostrador con el botón «Remito». Acá los consultás, los imprimís, los anulás y los pasás a venta para cobrarlos.',
        );
      });
    });

    describe('el detalle', () => {
      it('al elegir uno lo pide por su id y muestra el cliente, quién lo emitió, las observaciones, los renglones y los totales', async () => {
        await crear();
        api.obtener.mockReturnValue(of(detalleDe(4, { usuario: 'Ana Vendedora' })));

        await abrirDetalle(1);

        expect(api.obtener).toHaveBeenCalledTimes(1);
        expect(api.obtener).toHaveBeenCalledWith(40);
        expect(normal(raiz()!.querySelector('h2')!.textContent)).toBe('Remito N.º 4');
        const texto = normal(raiz()!.textContent);
        expect(texto).toContain('Cliente Cliente 4');
        expect(texto).toContain('Emitió Ana Vendedora');
        expect(texto).toContain('Entrega el viernes');
        expect(texto).toContain('Martillo');
        expect(texto).toContain('2 unidades');
        expect(texto).toContain('Total $ 3.000,00');
      });

      it('mientras carga ya se titula con la fila y dice «Cargando…»', async () => {
        await crear();
        api.obtener.mockReturnValue(new Subject<RemitoResponse>());

        await abrirDetalle(1);

        expect(normal(raiz()!.querySelector('h2')!.textContent)).toBe('Remito N.º 4');
        expect(normal(raiz()!.textContent)).toContain('Cargando…');
      });

      it('con descuento muestra el subtotal y cuánto se descontó', async () => {
        await crear();
        api.obtener.mockReturnValue(of(detalleDe(5, { subtotal: 3333.33, descuentoPorcentaje: 10, descuento: 333.33 })));

        await abrirDetalle(0);

        const texto = normal(raiz()!.textContent);
        expect(texto).toContain('Subtotal $ 3.333,33');
        expect(texto).toContain('Descuento (10 %) −$ 333,33');
      });

      it('si no se pudo traer avisa dentro del detalle', async () => {
        await crear();
        api.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

        await abrirDetalle(0);

        expect(normal(raiz()!.querySelector('[role="alert"]')!.textContent)).toBe('No se pudo cargar el remito.');
      });

      it('si se elige otro mientras el primero carga, vale el último', async () => {
        await crear();
        const primero = new Subject<RemitoResponse>();
        api.obtener.mockReturnValueOnce(primero);
        await abrirDetalle(0);
        await abrirDetalle(1);

        primero.next(detalleDe(5));
        await refrescar();

        expect(normal(raiz()!.querySelector('h2')!.textContent)).toBe('Remito N.º 4');
      });

      it('si el filtro deja afuera el remito elegido, cierra su detalle', async () => {
        await crear();
        await abrirDetalle(0);
        api.listar.mockReturnValue(of(pagina([resumen(1)])));
        (fixture.componentInstance as unknown as { cargar(n: number): void }).cargar(0);
        await refrescar();

        expect(raiz()).toBeNull();
      });

      it('un facturado dice que ya se facturó y un anulado, que el stock ya volvió y quién lo anuló y cuándo', async () => {
        await crear();
        api.obtener.mockReturnValue(of(detalleDe(5, { estado: 'FACTURADO' })));
        await abrirDetalle(0);
        expect(normal(q('.nota-estado', raiz()!)!.textContent)).toBe('Ya se facturó.');

        api.obtener.mockReturnValue(of(detalleDe(5, { estado: 'ANULADO' })));
        await abrirDetalle(1);
        expect(normal(q('.nota-estado', raiz()!)!.textContent)).toBe('Está anulado: el stock ya volvió.');

        api.obtener.mockReturnValue(of(detalleDe(5, { estado: 'ANULADO', anuladoPor: 'Dueño Tornillo', fechaAnulacion: '2026-11-07T10:30:00' })));
        await abrirDetalle(0);
        expect(normal(q('.nota-estado', raiz()!)!.textContent)).toBe(
          'Está anulado: el stock ya volvió. Lo anuló Dueño Tornillo el 07/11/2026 10:30.',
        );
      });

      it('un facturado en una venta la nombra, con la factura si viene, y dice por artículo lo que volvió al stock', async () => {
        await crear({ rol: 'DUENO' });
        api.obtener.mockReturnValue(
          of(
            detalleDe(5, {
              estado: 'FACTURADO',
              facturadoPor: {
                ventaId: 40,
                numero: 33,
                factura: 'Factura B 00001-00000012',
                diferencias: [{ articuloId: 3, descripcion: 'Bulon 3/8', unidad: 'UNIDAD', remitido: 300, facturado: 250 }],
              },
            }),
          ),
        );

        await abrirDetalle(0);

        const nota = q('.nota-estado', raiz()!)!;
        expect(normal(nota.textContent)).toBe('Facturado en la venta N.º 33 · Factura B 00001-00000012');
        expect(nota.querySelector('a')!.getAttribute('href')).toBe('/ventas/40');
        expect(qa('.diferencias li', raiz()!).map((li) => normal(li.textContent))).toEqual([
          'Bulon 3/8: se facturaron 250 de 300 unidades; 50 unidades volvieron al stock.',
        ]);
        nota.querySelector<HTMLAnchorElement>('a')!.click();
        expect(navegar.mock.calls.at(-1)![1]).toMatchObject({ state: { origen: '/remitos/50' } });
      });

      it('el Vendedor ve la venta como texto, sin link', async () => {
        await crear({ rol: 'VENDEDOR' });
        api.obtener.mockReturnValue(of(detalleDe(5, { estado: 'FACTURADO', facturadoPor: { ventaId: 40, numero: 33 } })));

        await abrirDetalle(0);

        expect(normal(q('.nota-estado', raiz()!)!.textContent)).toBe('Facturado en la venta N.º 33');
        expect(q('.nota-estado a', raiz()!)).toBeNull();
      });
    });

    describe('cerrar el detalle', () => {
      it('Esc cierra el detalle y deja la lista como estaba, sin volver a pedirla y sin poner el foco en un campo', async () => {
        await crear();
        await abrirDetalle(0);

        esc();
        await refrescar();

        expect(raiz()).toBeNull();
        expect(filas()).toHaveLength(2);
        expect(api.listar).toHaveBeenCalledTimes(1);
        expect(['INPUT', 'TEXTAREA', 'SELECT']).not.toContain(document.activeElement?.tagName);
      });

      it('la ✕ o «Volver» cierran el detalle', async () => {
        await crear();
        await abrirDetalle(0);

        await click(q('app-detalle-pantalla button[aria-label="Volver"], app-dialogo-detalle button[aria-label="Cerrar"]'));

        expect(raiz()).toBeNull();
        expect(filas()).toHaveLength(2);
      });
    });

    describe('qué se ofrece según el estado y el rol', () => {
      it.each<[EstadoRemito, string, string[]]>([
        ['EMITIDO', 'VENDEDOR', ['Imprimir', 'Pasar a venta']],
        ['EMITIDO', 'DUENO', ['Anular', 'Imprimir', 'Pasar a venta']],
        ['FACTURADO', 'DUENO', ['Imprimir']],
        ['FACTURADO', 'VENDEDOR', ['Imprimir']],
        ['ANULADO', 'DUENO', ['Imprimir']],
        ['ANULADO', 'VENDEDOR', ['Imprimir']],
      ])('un %s para el %s ofrece %j', async (estado, rol, esperadas) => {
        await crear({ rol });
        api.obtener.mockReturnValue(of(detalleDe(5, { estado })));

        await abrirDetalle(0);

        expect([...(await ofrece())].sort()).toEqual([...esperadas].sort());
      });
    });

    describe('anular (devuelve el stock)', () => {
      async function pedirAnular() {
        await crear({ items: [resumen(5)], rol: 'DUENO' });
        await abrirDetalle(0);
        await usar('Anular');
      }

      it('pide confirmar antes: avisa que devuelve el stock y no anula todavía', async () => {
        await pedirAnular();

        expect(confirmacion()!.getAttribute('aria-modal')).toBe('true');
        expect(normal(confirmacion()!.querySelector('h2')?.textContent)).toBe('¿Anular el remito N.º 5?');
        expect(normal(confirmacion()!.textContent)).toContain(
          'Anular devuelve al stock lo que salió con el remito N.º 5. No se puede deshacer.',
        );
        const anular = boton('Anular el remito', confirmacion()!)!;
        expect(anular.classList).toContain('peligro');
        expect(api.anular).not.toHaveBeenCalled();
        expect(document.activeElement).toBe(boton('Cancelar', confirmacion()!));
      });

      it('Cancelar lo deja como estaba', async () => {
        await pedirAnular();

        await click(boton('Cancelar', confirmacion()!));

        expect(confirmacion()).toBeNull();
        expect(api.anular).not.toHaveBeenCalled();
        expect(raiz()).not.toBeNull();
        expect(await ofrece()).toContain('Anular');
      });

      it('Esc cancela la pregunta sin cerrar el detalle', async () => {
        await pedirAnular();

        esc();
        await refrescar();

        expect(confirmacion()).toBeNull();
        expect(raiz()).not.toBeNull();
        expect(api.anular).not.toHaveBeenCalled();
      });

      it('«Anular el remito» manda sólo el id, y el detalle y la fila pasan a anulado', async () => {
        await pedirAnular();

        await click(boton('Anular el remito', confirmacion()!));

        expect(api.anular).toHaveBeenCalledTimes(1);
        expect(api.anular).toHaveBeenCalledWith(50);
        expect(normal(raiz()!.querySelector('.estado')?.textContent)).toBe('Anulado');
        expect(confirmacion()).toBeNull();
        expect(await ofrece()).toEqual(['Imprimir']);
        esc();
        await refrescar();
        expect(normal(filas()[0].textContent)).toContain('Anulado');
      });

      it('si ya se facturó (409) muestra el mensaje del servidor, vuelve a leer el remito y deja de ofrecer anular', async () => {
        await pedirAnular();
        api.anular.mockReturnValue(
          throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'Ese remito ya se facturó.' } })),
        );
        api.obtener.mockReturnValue(of(detalleDe(5, { estado: 'FACTURADO' })));

        await click(boton('Anular el remito', confirmacion()!));

        expect(alerta()).toContain('Ese remito ya se facturó.');
        expect(api.obtener).toHaveBeenLastCalledWith(50);
        expect(api.obtener).toHaveBeenCalledTimes(2);
        expect(await ofrece()).toEqual(['Imprimir']);
        esc();
        await refrescar();
        expect(normal(filas()[0].textContent)).toContain('Facturado');
      });

      it('un error de red avisa para reintentar y el remito queda emitido', async () => {
        await pedirAnular();
        api.anular.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

        await click(boton('Anular el remito', confirmacion()!));

        expect(alerta()).toContain('No se pudo anular el remito. Probá de nuevo.');
        expect(normal(raiz()!.querySelector('.estado')?.textContent)).toBe('Emitido');
        expect(confirmacion()).toBeNull();
        expect([...(await ofrece())].sort()).toEqual(['Anular', 'Imprimir', 'Pasar a venta']);
      });

      it('con la anulación en curso los botones de la confirmación se apagan y el detalle no se cierra con Esc', async () => {
        await pedirAnular();
        api.anular.mockReturnValue(new Subject<RemitoResponse>());

        await click(boton('Anular el remito', confirmacion()!));

        expect(qa('button', confirmacion()!).every((b) => (b as HTMLButtonElement).disabled)).toBe(true);
        (document.activeElement as HTMLElement | null)?.blur();
        document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
        await refrescar();
        expect(raiz()).not.toBeNull();
        expect(confirmacion()).not.toBeNull();
        expect(api.anular).toHaveBeenCalledTimes(1);
      });

      it('el Vendedor no tiene cómo anular', async () => {
        await crear({ items: [resumen(5)], rol: 'VENDEDOR' });

        await abrirDetalle(0);

        expect(await ofrece()).not.toContain('Anular');
        expect(masOpciones()).toBeNull();
        expect(api.anular).not.toHaveBeenCalled();
      });
    });

    describe('pasar a venta', () => {
      const ticketGuardado = () => JSON.parse(localStorage.getItem('ticket:3:7') ?? 'null');

      it('lee el precio de hoy de cada artículo, deja el ticket guardado con el remito y abre el mostrador', async () => {
        await crear();
        await abrirDetalle(0);

        await usar('Pasar a venta');

        expect(articulos.obtener).toHaveBeenCalledTimes(1);
        expect(articulos.obtener).toHaveBeenCalledWith(1);
        expect(ticketGuardado()).toMatchObject({
          v: 1,
          clave: 'clave-nueva',
          remitoId: 50,
          remitoNumero: 5,
          renglones: [{ articuloId: 1, descripcion: 'Martillo', precioCentavos: 180000, cantidadMilesimas: 2000 }],
        });
        expect(navegar).toHaveBeenCalledWith('/mostrador');
      });

      it('con un cliente de la tabla lee su condición de IVA de hoy y lo trae al ticket', async () => {
        await crear();
        api.obtener.mockReturnValue(of(detalleDe(5, { clienteId: 44 })));
        await abrirDetalle(0);

        await usar('Pasar a venta');

        expect(clientes.obtener).toHaveBeenCalledWith(44);
        expect(ticketGuardado()).toMatchObject({ remitoId: 50, clienteId: 44 });
        expect(navegar).toHaveBeenCalledWith('/mostrador');
      });

      it('si no se pueden leer los precios avisa, no toca el ticket y no abre el mostrador', async () => {
        await crear();
        articulos.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
        await abrirDetalle(0);

        await usar('Pasar a venta');

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
        const reemplazo = () => q('.reemplazo')!;

        async function pedirPasar() {
          ticketEnCurso();
          await crear();
          await abrirDetalle(0);
          await usar('Pasar a venta');
        }

        it('antes de reemplazarlo pregunta con el texto de hoy, con el foco en Cancelar, sin leer precios ni tocar nada y sin otras acciones', async () => {
          await pedirPasar();

          expect(normal(reemplazo().querySelector('p')!.textContent)).toBe(
            'El mostrador ya tiene un ticket con 1 renglón. Si seguís, se reemplaza por el de este remito.',
          );
          expect(document.activeElement).toBe(boton('Cancelar', reemplazo()));
          expect(articulos.obtener).not.toHaveBeenCalled();
          expect(ticketGuardado().clave).toBe('clave-vieja');
          expect(navegar).not.toHaveBeenCalled();
          expect(await ofrece()).toEqual([]);
        });

        it('Esc cancela la pregunta sin cerrar el detalle', async () => {
          await pedirPasar();

          esc();
          await refrescar();

          expect(q('.reemplazo')).toBeNull();
          expect(raiz()).not.toBeNull();
          expect(ticketGuardado().clave).toBe('clave-vieja');
        });

        it('Cancelar lo deja como estaba', async () => {
          await pedirPasar();

          await click(boton('Cancelar', reemplazo()));

          expect(q('.reemplazo')).toBeNull();
          expect(ticketGuardado().clave).toBe('clave-vieja');
          expect(navegar).not.toHaveBeenCalled();
        });

        it('Reemplazar el ticket lo cambia por el del remito y abre el mostrador', async () => {
          await pedirPasar();

          await click(boton('Reemplazar el ticket', reemplazo()));

          expect(ticketGuardado()).toMatchObject({ clave: 'clave-nueva', remitoId: 50 });
          expect(navegar).toHaveBeenCalledWith('/mostrador');
        });
      });
    });

    describe('imprimir', () => {
      const dialogo = () => q('app-documento-no-fiscal-imprimible');

      it('«Imprimir» lee el emisor una vez y abre la hoja del remito', async () => {
        await crear();
        await abrirDetalle(0);

        await usar('Imprimir');

        expect(emisor.obtener).toHaveBeenCalledTimes(1);
        expect(normal(dialogo()!.querySelector('#documento-titulo')?.textContent)).toBe('Remito N.º 5');
        expect(normal(dialogo()!.textContent)).toContain('Tornillo SA');
      });

      it('Esc cierra la hoja y deja el detalle', async () => {
        await crear();
        await abrirDetalle(0);
        await usar('Imprimir');

        dialogo()!.querySelector('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        await refrescar();

        expect(dialogo()).toBeNull();
        expect(raiz()).not.toBeNull();
      });

      it('si el emisor no se pudo leer avisa en el detalle y no abre la hoja', async () => {
        await crear();
        emisor.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
        await abrirDetalle(0);

        await usar('Imprimir');

        expect(alerta()).toBe('No se pudieron leer los datos de la ferretería. Probá de nuevo.');
        expect(dialogo()).toBeNull();
      });
    });

    describe('un remito elegido desde la dirección', () => {
      it('`/remitos/:id` abre ese remito aunque no esté en la lista y la lista no lo cierra', async () => {
        await crear({ items: [resumen(5)], id: '90' });

        expect(api.obtener).toHaveBeenCalledWith(90);
        expect(normal(raiz()!.querySelector('h2')!.textContent)).toBe('Remito N.º 9');
        expect(filas()).toHaveLength(1);
      });

      it('cerrarlo vuelve a `/remitos`', async () => {
        await crear({ items: [resumen(5)], id: '90' });

        esc();
        await refrescar();

        expect(raiz()).toBeNull();
        expect(navegar).toHaveBeenCalledWith('/remitos');
      });

      it('abierto desde el link de otro documento, cerrarlo vuelve a ese documento', async () => {
        await crear({ items: [resumen(5)] });
        const router = TestBed.inject(Router);
        vi.spyOn(router, 'currentNavigation').mockReturnValue({ extras: { state: { origen: '/ventas/90' } } } as never);
        parametros.next(convertToParamMap({ id: '5' }));
        await refrescar();
        vi.mocked(router.currentNavigation).mockReturnValue(null);

        esc();
        await refrescar();

        expect(raiz()).toBeNull();
        expect(navegar).toHaveBeenCalledWith('/ventas/90');
      });

      it('abierto desde la lista, cerrarlo no cambia la dirección', async () => {
        await crear();
        await abrirDetalle(0);

        esc();
        await refrescar();

        expect(raiz()).toBeNull();
        expect(navegar).not.toHaveBeenCalled();
      });

      it('uno de otra ferretería o inexistente (404) da el aviso de siempre', async () => {
        await crear({ items: [resumen(5)] });
        api.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
        parametros.next(convertToParamMap({ id: '77' }));
        await refrescar();

        expect(normal(raiz()!.querySelector('[role="alert"]')!.textContent)).toBe('No se encontró el remito.');
      });

      it('otro en la dirección con la pantalla abierta abre ese', async () => {
        await crear({ items: [resumen(5)], id: '90' });
        parametros.next(convertToParamMap({ id: '50' }));
        await refrescar();

        expect(api.obtener).toHaveBeenLastCalledWith(50);
        expect(normal(raiz()!.querySelector('h2')!.textContent)).toBe('Remito N.º 5');
      });
    });
  });

  // ---------------------------------------------------------------------------------------------------------------
  // Estructura propia del celular.
  // ---------------------------------------------------------------------------------------------------------------
  describe('en el celular', () => {
    beforeEach(() => {
      modoPorDefecto = 'celular';
    });

    describe('la lista', () => {
      it('cada remito es una tarjeta de dos líneas y no hay tabla', async () => {
        await crear({ items: [resumen(5), resumen(4, { estado: 'ANULADO' }), resumen(3, { estado: 'FACTURADO' })] });

        expect(q('table')).toBeNull();
        const lista = q('ul.documentos-celular')!;
        expect(lista.getAttribute('aria-label')).toBe('Remitos, los más nuevos primero. Elegí uno para ver su detalle.');
        const tarjetas = qa('.doc-tarjeta', lista);
        expect(tarjetas).toHaveLength(3);
        expect(tarjetas[0].tagName).toBe('BUTTON');
        expect(tarjetas[0].getAttribute('aria-haspopup')).toBe('dialog');
        expect(normal(tarjetas[0].querySelector('.doc-linea')!.textContent)).toBe('N.º 5 · Cliente 5');
        expect(normal(tarjetas[0].querySelector('.doc-sub')!.textContent)).toBe('18/10 16:03');
        expect(normal(tarjetas[0].querySelector('.doc-total')!.textContent)).toBe('$ 3.000,00');
        expect(normal(tarjetas[0].querySelector('.pastilla')!.textContent)).toBe('Emitido');
        expect(tarjetas[0].querySelector('.pastilla')!.classList).toContain('pastilla-neutro');
        expect(tarjetas[1].querySelector('.pastilla')!.classList).toContain('pastilla-peligro');
        expect(normal(tarjetas[2].querySelector('.pastilla')!.textContent)).toBe('Facturado');
        expect(tarjetas[2].querySelector('.pastilla')!.classList).toContain('pastilla-exito');
      });

      it('un remito sin cliente lleva un guión y el elegido queda marcado', async () => {
        await crear({ items: [resumen(5, { clienteNombre: undefined }), resumen(4)] });
        expect(normal(q('.doc-linea')!.textContent)).toBe('N.º 5 · —');

        await abrirDetalle(1);

        expect(filas()[1].classList).toContain('elegida');
        expect(filas()[0].classList).not.toContain('elegida');
      });
    });

    describe('el estado como chips', () => {
      const chips = () => qa('.chips-estado button.chip-estado');

      it('Todos, Emitido, Facturado y Anulado, con Todos marcado', async () => {
        await crear();

        expect(q('.chips-estado')!.getAttribute('role')).toBe('group');
        expect(q('.chips-estado')!.getAttribute('aria-label')).toBe('Estado');
        expect(chips().map((c) => normal(c.textContent))).toEqual(['Todos', 'Emitido', 'Facturado', 'Anulado']);
        expect(chips().map((c) => c.getAttribute('aria-pressed'))).toEqual(['true', 'false', 'false', 'false']);
      });

      it('tocar un estado pide la lista de ese estado desde la página 0 y lo marca', async () => {
        await crear();

        await click(boton('Facturado', q('.chips-estado')!));

        expect(api.listar).toHaveBeenLastCalledWith({ page: 0, estado: 'FACTURADO' });
        expect(chips().map((c) => c.getAttribute('aria-pressed'))).toEqual(['false', 'false', 'true', 'false']);

        await click(boton('Anulado', q('.chips-estado')!));
        expect(api.listar).toHaveBeenLastCalledWith({ page: 0, estado: 'ANULADO' });
      });

      it('Todos saca el estado y el resto de los filtros aplicados queda', async () => {
        await crear();
        await abrirFiltros();
        filtrar('numero', '5');
        filtrar('estado', 'EMITIDO');
        await buscar();
        expect(api.listar).toHaveBeenLastCalledWith({ page: 0, estado: 'EMITIDO', numero: 5 });

        await click(boton('Todos', q('.chips-estado')!));

        expect(api.listar).toHaveBeenLastCalledWith({ page: 0, numero: 5 });
        expect(chips()[0].getAttribute('aria-pressed')).toBe('true');
      });

      it('con un número mal escrito en los filtros avisa en la página, no pide y no cambia lo marcado', async () => {
        await crear();
        await abrirFiltros();
        filtrar('numero', 'abc');
        esc();
        await refrescar();
        api.listar.mockClear();

        await click(boton('Anulado', q('.chips-estado')!));

        expect(api.listar).not.toHaveBeenCalled();
        expect(alerta()).toBe('El número tiene que ser un entero.');
        expect(chips()[0].getAttribute('aria-pressed')).toBe('true');
      });

      it('si el servidor no responde, el chip queda marcado y Reintentar repite el mismo pedido', async () => {
        await crear();
        api.listar.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));

        await click(boton('Anulado', q('.chips-estado')!));

        expect(alerta()).toContain('No se pudieron cargar los remitos.');
        expect(chips()[3].getAttribute('aria-pressed')).toBe('true');
        await click(boton('Reintentar'));
        expect(api.listar).toHaveBeenLastCalledWith({ page: 0, estado: 'ANULADO' });
      });
    });

    describe('la hoja de filtros', () => {
      it('hay un botón «Filtros» con la cuenta de los que están escritos y los campos sólo están dentro de la hoja', async () => {
        await crear();

        const filtros = q<HTMLButtonElement>('button.filtros-celular')!;
        expect(normal(filtros.textContent)).toBe('Filtros');
        expect(filtros.getAttribute('aria-haspopup')).toBe('dialog');
        expect(filtros.getAttribute('aria-expanded')).toBe('false');
        expect(q('form.filtros')).toBeNull();

        await click(filtros);

        expect(hoja()).not.toBeNull();
        expect(normal(q('app-hoja-inferior h2')!.textContent)).toBe('Filtros');
        expect(qa('form.filtros', hoja()!)).toHaveLength(1);
        expect(qa('form.filtros label', hoja()!).map((l) => normal(l.textContent))).toEqual(['Estado TodosEmitidoFacturadoAnulado', 'N.º', 'Desde', 'Hasta']);
        expect(qa('form.filtros button', hoja()!).map((b) => normal(b.textContent))).toEqual(['Buscar', 'Limpiar']);
        expect(q<HTMLButtonElement>('button.filtros-celular')!.getAttribute('aria-expanded')).toBe('true');
      });

      it('la cuenta suma los campos escritos', async () => {
        await crear();
        await abrirFiltros();
        filtrar('estado', 'EMITIDO');
        filtrar('desde', '2026-10-01');
        await refrescar();
        esc();
        await refrescar();

        expect(normal(q('button.filtros-celular .cuenta')!.textContent)).toBe('2');
        expect(normal(q('button.filtros-celular .sr-only')!.textContent)).toBe('2 filtros activos');
      });

      it('Buscar aplica y cierra la hoja; con un error, la hoja sigue abierta con el aviso adentro', async () => {
        await crear();
        await abrirFiltros();
        filtrar('numero', 'xx');
        await buscar();

        expect(hoja()).not.toBeNull();
        expect(normal(q('.error', hoja()!)!.textContent)).toBe('El número tiene que ser un entero.');

        filtrar('numero', '5');
        await buscar();

        expect(hoja()).toBeNull();
        expect(api.listar).toHaveBeenLastCalledWith({ page: 0, numero: 5 });
        expect(el().textContent).not.toContain('El número tiene que ser un entero.');
      });

      it('Limpiar cierra la hoja y pide todo', async () => {
        await crear();
        await abrirFiltros();
        filtrar('numero', '5');
        await buscar();
        await abrirFiltros();

        await click(boton('Limpiar', hoja()!));

        expect(hoja()).toBeNull();
        expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
      });

      it('Esc y el velo cierran la hoja sin aplicar nada, y el foco vuelve a «Filtros» y no a un campo', async () => {
        await crear();
        q<HTMLButtonElement>('button.filtros-celular')!.focus();
        await abrirFiltros();
        filtrar('numero', '5');

        esc();
        await refrescar();

        expect(hoja()).toBeNull();
        expect(api.listar).toHaveBeenCalledTimes(1);
        expect(document.activeElement).toBe(q('button.filtros-celular'));

        await abrirFiltros();
        await click(q('.hoja-inferior-fondo'));
        expect(hoja()).toBeNull();
        expect(api.listar).toHaveBeenCalledTimes(1);
      });
    });

    describe('el detalle en pantalla completa', () => {
      it('no es el pop-up: tiene «Volver», el título, el foco en «Volver» y el contenido de hoy', async () => {
        await crear();
        api.obtener.mockReturnValue(of(detalleDe(5, { usuario: 'Ana Vendedora' })));

        await abrirDetalle(0);

        expect(q('app-dialogo-detalle')).toBeNull();
        const pantalla = q('app-detalle-pantalla [role="dialog"]')!;
        expect(pantalla.getAttribute('aria-modal')).toBe('true');
        expect(normal(pantalla.querySelector('h2')!.textContent)).toBe('Remito N.º 5');
        expect(document.activeElement).toBe(pantalla.querySelector('button[aria-label="Volver"]'));
        const cuerpo = pantalla.querySelector('.detalle-pantalla-cuerpo')!;
        expect(normal(cuerpo.querySelector('.estado')!.textContent)).toBe('Emitido');
        expect(cuerpo.querySelector('.estado')!.classList).toContain('pastilla-neutro');
        expect(normal(cuerpo.querySelector('.datos')!.textContent)).toBe('18/10 16:03 · Cliente Cliente 5 · Emitió Ana Vendedora');
        expect(normal(cuerpo.querySelector('.observaciones')!.textContent)).toBe('Entrega el viernes');
        expect(cuerpo.querySelectorAll('table.renglones tbody tr')).toHaveLength(1);
        expect(normal(cuerpo.querySelector('.totales')!.textContent)).toBe('Total $ 3.000,00');
        expect(pantalla.querySelector('button.cerrar')).toBeNull();
      });

      it('«Volver» cierra el detalle y devuelve el foco a la tarjeta que lo abrió', async () => {
        await crear();
        const tarjeta = filas()[1];
        tarjeta.focus();
        await abrirDetalle(1);

        await click(q('button[aria-label="Volver"]'));

        expect(q('app-detalle-pantalla')).toBeNull();
        expect(document.activeElement).toBe(tarjeta);
      });

      it('al pie, «Imprimir» y «Pasar a venta» (la principal); «Anular» no está en el pie', async () => {
        await crear();

        await abrirDetalle(0);

        const pie = qa('.detalle-pantalla-pie > button');
        expect(pie.map((b) => normal(b.textContent))).toEqual(['Imprimir', 'Pasar a venta']);
        expect(pie[0].classList).toContain('secundario');
        expect(pie[1].classList).toContain('primario');
      });

      it('para el Dueño, el ⋯ abre una hoja «Remito N.º 5» con «Anular» en rojo; abre la confirmación de siempre y no anula todavía', async () => {
        await crear({ rol: 'DUENO' });
        await abrirDetalle(0);
        const mas = masOpciones()!;
        expect(mas.getAttribute('aria-haspopup')).toBe('dialog');

        await click(mas);

        expect(normal(q('app-hoja-inferior h2')!.textContent)).toBe('Remito N.º 5');
        const acciones = qa('.accion-hoja', hoja()!);
        expect(acciones.map((b) => normal(b.textContent))).toEqual(['Anular']);
        expect(acciones[0].classList).toContain('peligro');

        await click(acciones[0]);

        expect(hoja()).toBeNull();
        expect(normal(confirmacion()!.querySelector('h2')!.textContent)).toBe('¿Anular el remito N.º 5?');
        expect(api.anular).not.toHaveBeenCalled();
      });

      it('Esc en la hoja del ⋯ la cierra y deja el detalle abierto', async () => {
        await crear({ rol: 'DUENO' });
        await abrirDetalle(0);
        await click(masOpciones());

        esc();
        await refrescar();

        expect(hoja()).toBeNull();
        expect(q('app-detalle-pantalla')).not.toBeNull();
        expect(confirmacion()).toBeNull();
      });

      it('para el Vendedor, y para un remito facturado o anulado, no hay ⋯', async () => {
        await crear({ rol: 'VENDEDOR' });
        await abrirDetalle(0);
        expect(masOpciones()).toBeNull();
      });

      it('un remito anulado o facturado no tiene ⋯ ni siquiera para el Dueño', async () => {
        await crear({ rol: 'DUENO' });
        api.obtener.mockReturnValue(of(detalleDe(5, { estado: 'ANULADO' })));

        await abrirDetalle(0);

        expect(masOpciones()).toBeNull();
        expect(qa('.detalle-pantalla-pie > button').map((b) => normal(b.textContent))).toEqual(['Imprimir']);
      });

      it('mientras carga no hay ⋯ ni pie', async () => {
        await crear({ rol: 'DUENO' });
        api.obtener.mockReturnValue(new Subject<RemitoResponse>());

        await abrirDetalle(0);

        expect(masOpciones()).toBeNull();
        expect(qa('.detalle-pantalla-pie > button')).toHaveLength(0);
      });

      it('mientras se anula el pie se apaga y «Volver» no cierra la pantalla', async () => {
        await crear({ rol: 'DUENO' });
        await abrirDetalle(0);
        await usar('Anular');
        api.anular.mockReturnValue(new Subject<RemitoResponse>());
        await click(boton('Anular el remito', confirmacion()!));

        await click(q('button[aria-label="Volver"]'));

        expect(q('app-detalle-pantalla')).not.toBeNull();
        expect(api.anular).toHaveBeenCalledTimes(1);
      });

      describe('los botones del pie y del ⋯ se apagan con una acción en curso', () => {
        const pie = () => qa('.detalle-pantalla-pie > button', raiz()!) as HTMLButtonElement[];
        const deshabilitado = (texto: string) => pie().find((b) => normal(b.textContent) === texto)!.disabled;
        /** «Anular» de la hoja del ⋯: se abre para leerla y se cierra. */
        async function anularDeLaHojaDeshabilitado() {
          await click(masOpciones());
          const anular = qa('.accion-hoja', hoja()!).find((b) => normal(b.textContent) === 'Anular') as HTMLButtonElement;
          const apagado = anular.disabled;
          esc();
          await refrescar();
          return apagado;
        }

        it('sin nada en curso ninguno está apagado', async () => {
          await crear({ rol: 'DUENO' });
          await abrirDetalle(0);

          expect(pie().map((b) => normal(b.textContent))).toEqual(['Imprimir', 'Pasar a venta']);
          expect(deshabilitado('Imprimir')).toBe(false);
          expect(deshabilitado('Pasar a venta')).toBe(false);
          expect(await anularDeLaHojaDeshabilitado()).toBe(false);
        });

        it('con la anulación en curso el pie y el ⋯ no están (la confirmación sigue abierta), así que no hay con qué anular dos veces', async () => {
          await crear({ rol: 'DUENO' });
          await abrirDetalle(0);
          api.anular.mockReturnValue(new Subject<RemitoResponse>());
          await usar('Anular');
          await click(boton('Anular el remito', confirmacion()!));
          expect(api.anular).toHaveBeenCalledTimes(1);

          expect(pie()).toHaveLength(0);
          expect(masOpciones()).toBeNull();
          expect(qa('button', confirmacion()!).every((b) => (b as HTMLButtonElement).disabled)).toBe(true);
        });

        it('con el pasaje a venta en curso se apagan Imprimir, Pasar a venta y el «Anular» del ⋯', async () => {
          await crear({ rol: 'DUENO' });
          await abrirDetalle(0);
          articulos.obtener.mockReturnValue(new Subject<Articulo>());
          await click(boton('Pasar a venta', raiz()!));
          expect(articulos.obtener).toHaveBeenCalledTimes(1);

          expect(deshabilitado('Imprimir')).toBe(true);
          expect(deshabilitado('Pasar a venta')).toBe(true);
          expect(await anularDeLaHojaDeshabilitado()).toBe(true);
        });

        it('con la lectura del emisor en curso sólo se apaga Imprimir', async () => {
          await crear({ rol: 'DUENO' });
          await abrirDetalle(0);
          emisor.obtener.mockReturnValue(new Subject());
          await click(boton('Imprimir', raiz()!));
          expect(emisor.obtener).toHaveBeenCalledTimes(1);

          expect(deshabilitado('Imprimir')).toBe(true);
          expect(deshabilitado('Pasar a venta')).toBe(false);
          expect(await anularDeLaHojaDeshabilitado()).toBe(false);
        });
      });

      it('la pregunta de reemplazar el ticket está dentro de la pantalla, con sus dos botones', async () => {
        localStorage.setItem(
          'ticket:3:7',
          JSON.stringify({ v: 1, clave: 'v', elegido: 0, renglones: [{ articuloId: 9, codigoInterno: 'Z', descripcion: 'Clavo', unidad: 'UNIDAD', precioCentavos: 100, cantidadMilesimas: 1000, stockActual: 1, stockMinimo: 0 }] }),
        );
        await crear({ rol: 'DUENO' });
        await abrirDetalle(0);

        await click(boton('Pasar a venta', raiz()!));

        const pregunta = q('app-detalle-pantalla .detalle-pantalla-cuerpo .reemplazo')!;
        expect(qa('button', pregunta).map((b) => normal(b.textContent))).toEqual(['Cancelar', 'Reemplazar el ticket']);
        expect(qa('.detalle-pantalla-pie > button')).toHaveLength(0);
        expect(masOpciones()).toBeNull();
      });
    });
  });

  // ---------------------------------------------------------------------------------------------------------------
  // Escritorio y tablet: no cambia nada.
  // ---------------------------------------------------------------------------------------------------------------
  describe.each(['escritorio', 'tablet'] as const)('en %s no aparece nada del celular', (modo) => {
    it('la tabla, el formulario de filtros a la vista y el pop-up de siempre', async () => {
      await crear({ modo, rol: 'DUENO' });

      expect(q('.documentos-celular')).toBeNull();
      expect(q('.chips-estado')).toBeNull();
      expect(q('button.filtros-celular')).toBeNull();
      expect(q('app-hoja-inferior')).toBeNull();
      expect(qa('table.remitos tbody tr')).toHaveLength(2);
      expect(qa('form.filtros label').map((l) => normal(l.textContent).split(' ')[0])).toEqual(['Estado', 'N.º', 'Desde', 'Hasta']);

      await click(q('table.remitos tbody tr'));

      expect(q('app-detalle-pantalla')).toBeNull();
      expect(masOpciones()).toBeNull();
      const pop = q('app-dialogo-detalle [role="dialog"]')!;
      expect(normal(pop.querySelector('.dialogo-detalle-cabecera .estado')!.textContent)).toBe('Emitido');
      expect(qa('.dialogo-detalle-pie .pie-peligro button', pop).map((b) => normal(b.textContent))).toEqual(['Anular']);
      expect(qa('.dialogo-detalle-pie .pie-acciones button', pop).map((b) => normal(b.textContent))).toEqual(['Cerrar', 'Imprimir', 'Pasar a venta']);
      expect(document.activeElement).toBe(pop.querySelector('button[aria-label="Cerrar"]'));
    });
  });
});
