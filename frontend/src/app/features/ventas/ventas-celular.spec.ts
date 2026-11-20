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
import { documentoB, documentoX, fiscalDe } from '../../shared/factura/factura.testing';
import { ResumenFiscal } from '../../shared/factura/factura.models';
import { Ventas } from './ventas';
import { FiscalConsultado, VentaConsultada, VentaResumen } from './ventas.models';
import { VentasService } from './ventas.service';
import { resumenDePrueba, ventaDePrueba } from './ventas.testing';

registerLocaleData(localeEsAr);

/**
 * La consulta y el detalle de ventas en el celular (movil-sectores T11b, S1, S2, S5, S6, S7): sólo cambia cómo se ve. Los
 * pedidos al servidor, los textos y los errores corren en escritorio y en celular con los mismos literales (y por eso los
 * helpers abren filtros y ventas por la pantalla que haya, así esos tests también corren contra la base); los de
 * estructura miran lo propio del celular. En escritorio y en tablet (matchMedia de 1023.98 px) no aparece nada de eso.
 */
describe('Consulta y detalle de ventas en el celular (T11b)', () => {
  type Modo = 'escritorio' | 'tablet' | 'celular';
  let fixture: ComponentFixture<Ventas>;
  let api: Record<'listar' | 'obtener' | 'reintentar' | 'dejarSinFactura' | 'anular', ReturnType<typeof vi.fn>>;
  let router: Router;
  let parametros: BehaviorSubject<ParamMap>;
  /** La pantalla con la que se crea cuando el test no pide otra: los de pedidos corren en escritorio y en celular. */
  let modoPorDefecto: Modo = 'celular';

  const MENSAJE_FECHA = 'La fecha de la venta ya no entra en ARCA. Confirmá que la factura salga con la fecha de hoy.';
  const ERROR_10015 = {
    codigo: 10015,
    mensaje: 'El campo DocNro es inválido.',
    explicacion: 'El documento del cliente no es válido para ARCA: corregilo en Clientes y reintentá.',
  };

  const pagina = (items: VentaResumen[], cambios: Partial<PageResponse<VentaResumen>> = {}): PageResponse<VentaResumen> => ({
    items,
    page: 0,
    size: 20,
    totalElements: items.length,
    totalPages: 1,
    ...cambios,
  });
  const resumenFiscal = (estado: ResumenFiscal['estado'], cambios: Partial<ResumenFiscal> = {}): ResumenFiscal => ({
    letra: 'B',
    letraFiscal: 'B',
    puntoVenta: 1,
    numero: 14,
    estado,
    ambiente: 'PRODUCCION',
    validoComoFactura: true,
    reimprimir: false,
    ...cambios,
  });
  const rechazada = (cambios: Partial<FiscalConsultado> = {}): FiscalConsultado => ({
    ...fiscalDe(documentoX('SIN FACTURA — ARCA no la autorizó')),
    estado: 'RECHAZADO',
    numero: null,
    errores: [ERROR_10015],
    observaciones: [{ codigo: 10063, mensaje: 'Importe de IVA con diferencias de redondeo.' }],
    motivoSinFactura: null,
    acciones: ['REINTENTAR', 'DEJAR_SIN_FACTURA'],
    fechaNuevaRequerida: false,
    reimprimir: false,
    ...cambios,
  });
  const autorizada = (cambios: Partial<FiscalConsultado> = {}): FiscalConsultado => ({
    ...fiscalDe(documentoB()),
    acciones: [],
    reimprimir: false,
    ...cambios,
  });
  const error = (status: number, cuerpo: Record<string, unknown>) =>
    throwError(() => new HttpErrorResponse({ status, error: { status, ...cuerpo } }));

  async function crear(
    opciones: {
      items?: VentaResumen[];
      rol?: string;
      modo?: Modo;
      queryParams?: Record<string, string>;
      id?: string | null;
      venta?: VentaConsultada;
      lista?: PageResponse<VentaResumen>;
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
    const items = opciones.items ?? [resumenDePrueba(5), resumenDePrueba(4)];
    api = {
      listar: vi.fn(() => of(opciones.lista ?? pagina(items))),
      obtener: vi.fn((ventaId: number) => of(opciones.venta ?? ventaDePrueba({ ventaId, numero: ventaId / 10 }))),
      reintentar: vi.fn(() => of({})),
      dejarSinFactura: vi.fn(() => of({})),
      anular: vi.fn(() => of(ventaDePrueba({ ventaId: 140, numero: 14, tipo: 'NOTA_CREDITO' }))),
    };
    const sesion = signal({ userId: 7, nombre: 'Ana', email: 'a@x.com', rol, ferreteriaId: 3, ferreteriaNombre: 'Tornillo' });
    await TestBed.configureTestingModule({
      imports: [Ventas],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: VentasService, useValue: api },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly(), esDueno: () => rol === 'DUENO' } },
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(opciones.queryParams ?? {}) }, paramMap: parametros },
        },
      ],
    }).compileComponents();
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(Ventas);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => vi.unstubAllGlobals());

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends Element = HTMLElement>(selector: string, dentro: ParentNode = el()) => dentro.querySelector<T>(selector);
  const qa = (selector: string, dentro: ParentNode = el()) => Array.from(dentro.querySelectorAll<HTMLElement>(selector));
  const boton = (texto: string, dentro: ParentNode = el()) =>
    Array.from(dentro.querySelectorAll<HTMLButtonElement>('button')).find((b) => normal(b.textContent) === texto);
  const tecla = (key: string, destino: EventTarget = document.activeElement ?? document.body) =>
    destino.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
  /** Lo que se ve de la lista: tarjetas en el celular, filas de la tabla en escritorio y tablet. */
  const tarjetas = () => qa('.venta-tarjeta');
  const filasTabla = () => qa('table.ventas tbody tr');
  const hoja = () => q('app-hoja-inferior [role="dialog"]');
  const pantalla = () => q('app-detalle-pantalla [role="dialog"]');
  /** El detalle abierto, sea cual sea su forma. */
  const detalle = () => q('app-dialogo-detalle [role="dialog"]') ?? pantalla();
  const confirmacion = () => q('app-confirmacion [role="alertdialog"]');
  const dialogoMotivo = () => q('app-dialogo-sin-factura [role="dialog"]');
  const dialogoAnular = () => q('app-dialogo-anular [role="alertdialog"]');

  async function tocar(elemento: HTMLElement | null | undefined) {
    elemento!.click();
    await refrescar();
  }
  async function abrirVenta(indice = 0) {
    await tocar(tarjetas()[indice] ?? filasTabla()[indice]);
  }
  /** Los filtros: en el celular se abren con «Filtros»; en escritorio y tablet ya están en la página. */
  async function abrirFiltros() {
    if (!q('form.filtros')) await tocar(q('.herramientas-movil button.filtros'));
  }
  function campo(nombre: string, valor: string) {
    const input = q<HTMLInputElement | HTMLSelectElement>(`[name="${nombre}"]`)!;
    input.value = valor;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
  }
  async function enviarFiltros() {
    q<HTMLFormElement>('form.filtros')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await refrescar();
  }
  /** El DOM sin comentarios de Angular ni los números de los ids que se van sumando entre pantallas. */
  const dom = () =>
    el()
      .outerHTML.replace(/<!--[\s\S]*?-->/g, '')
      .replace(/(dialogo-detalle|hoja-inferior|dialogo-sin-factura|dialogo-anular)-\d+/g, '$1-N')
      .replace(/id="root\d+"/, 'id="root"');

  // ---------------------------------------------------------------------------------------------------- la lista

  describe('la lista', () => {
    it('en el celular cada venta es una tarjeta de dos líneas, sin tabla', async () => {
      await crear({
        items: [
          resumenDePrueba(5, { fiscal: resumenFiscal('AUTORIZADO') }),
          resumenDePrueba(4, { clienteNombre: undefined }),
        ],
      });

      expect(q('table.ventas')).toBeNull();
      expect(tarjetas()).toHaveLength(2);
      const primera = tarjetas()[0];
      expect(normal(q('.venta-linea', primera)?.textContent)).toBe('5 · Cliente 5');
      expect(normal(q('.venta-sub', primera)?.textContent)).toBe('B 00001-00000014 · 22/10/2026 16:05');
      expect(normal(q('.venta-total', primera)?.textContent)).toBe('$ 3.000,00');
      expect(q('.pastilla', primera)).toBeNull();
      // Sin cliente es Consumidor Final y sin factura la segunda línea es sólo la fecha.
      expect(normal(q('.venta-linea', tarjetas()[1])?.textContent)).toBe('4 · Consumidor Final');
      expect(normal(q('.venta-sub', tarjetas()[1])?.textContent)).toBe('22/10/2026 16:05');
    });

    it('las marcas de hoy son pastillas con los textos de hoy: no facturable, nota, anulada y estado de la factura', async () => {
      const anulacion = { notaId: 90, numero: 9, interna: false, motivo: null, usuario: 'Dueño', fecha: '2026-10-29T14:05:00' };
      await crear({
        items: [
          resumenDePrueba(9, { facturable: false }),
          resumenDePrueba(8, { tipo: 'NOTA_CREDITO', ventaAnulada: { id: 70, numero: 7 }, fiscal: resumenFiscal('AUTORIZADO', { numero: 3 }) }),
          resumenDePrueba(7, { tipo: 'NOTA_CREDITO' }),
          resumenDePrueba(6, { anulacion, fiscal: resumenFiscal('AUTORIZADO') }),
          resumenDePrueba(5, { fiscal: resumenFiscal('RECHAZADO', { letra: 'X', numero: null, validoComoFactura: false, ambiente: 'SIMULADOR' }) }),
          resumenDePrueba(4, { fiscal: resumenFiscal('AUTORIZADO', { reimprimir: true }) }),
        ],
      });

      const pastillas = tarjetas().map((t) => qa('.pastilla', t).map((p) => normal(p.textContent)));
      expect(pastillas).toEqual([
        ['No facturable'],
        ['Nota de crédito · Venta N.º 7'],
        ['Anulación'],
        ['Anulada'],
        ['Rechazada · Simulado'],
        ['Reimprimir factura'],
      ]);
      // La nota de crédito resta.
      expect(normal(q('.venta-total', tarjetas()[1])?.textContent)).toBe('-$ 3.000,00');
    });

    it('tocar una tarjeta abre el detalle de esa venta y la deja marcada', async () => {
      await crear();

      await abrirVenta(1);

      expect(api.obtener).toHaveBeenCalledWith(40);
      expect(tarjetas()[1].classList.contains('elegida')).toBe(true);
      expect(pantalla()).not.toBeNull();
    });

    it('con muchas páginas el paginador sigue pidiendo la anterior y la siguiente', async () => {
      await crear({ lista: pagina([resumenDePrueba(5)], { totalPages: 3, page: 1 }) });
      expect(normal(q('.paginas span')?.textContent)).toBe('Página 2 de 3');

      await tocar(boton('Siguiente'));
      expect(api.listar).toHaveBeenLastCalledWith({ page: 2 });
      await tocar(boton('Anterior'));
      expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
    });

    it('sin ventas o sin resultados dice lo de hoy', async () => {
      await crear({ items: [] });
      expect(normal(q('.estado-vacio')?.textContent)).toBe('Todavía no hay ventas.');
      expect(tarjetas()).toHaveLength(0);

      await abrirFiltros();
      campo('cliente', 'Pérez');
      await enviarFiltros();
      expect(normal(q('.estado-vacio')?.textContent)).toBe('No hay ventas con esos filtros.');
    });

    it('si la lista no carga lo avisa y «Reintentar» la vuelve a pedir; la lista que había sigue', async () => {
      await crear();
      api.listar.mockReturnValueOnce(throwError(() => new Error('caída')));
      await abrirFiltros();
      await enviarFiltros();

      expect(normal(q('.error.con-accion span')?.textContent)).toBe('No se pudieron cargar las ventas.');
      await tocar(boton('Reintentar', q('.error.con-accion')!));
      expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
      expect(q('.error.con-accion')).toBeNull();
    });

    it('el Vendedor sólo ve el aviso: no hay filtros, ni lista, ni se pide nada', async () => {
      await crear({ rol: 'VENDEDOR' });

      expect(normal(q('[role="status"]')?.textContent)).toBe('La consulta de ventas es solo del dueño.');
      expect(api.listar).not.toHaveBeenCalled();
      expect(q('form.filtros')).toBeNull();
      expect(q('.herramientas-movil')).toBeNull();
      expect(tarjetas()).toHaveLength(0);
      expect(q('app-hoja-inferior')).toBeNull();
    });

    it('en escritorio y en tablet la lista es la tabla de hoy y no hay nada del celular', async () => {
      const volcados: string[] = [];
      for (const modo of ['escritorio', 'tablet'] as const) {
        TestBed.resetTestingModule();
        vi.unstubAllGlobals();
        await crear({ modo, items: [resumenDePrueba(5, { fiscal: resumenFiscal('AUTORIZADO') }), resumenDePrueba(4)] });
        expect(filasTabla(), modo).toHaveLength(2);
        expect(tarjetas(), modo).toHaveLength(0);
        expect(q('app-hoja-inferior'), modo).toBeNull();
        expect(q('.chip-filtro'), modo).toBeNull();
        expect(q('form.filtros'), modo).not.toBeNull();
        volcados.push(dom());
      }
      expect(volcados[1]).toBe(volcados[0]);
    });
  });

  // ------------------------------------------------------------------------------------------------- los filtros

  describe('los filtros', () => {
    it('en el celular son una hoja inferior que se abre con «Filtros» y tiene los seis campos con Buscar y Limpiar', async () => {
      await crear();
      expect(q('form.filtros')).toBeNull();
      const abrir = q<HTMLButtonElement>('.herramientas-movil button.filtros')!;
      expect(abrir.getAttribute('aria-expanded')).toBe('false');

      await tocar(abrir);

      expect(abrir.getAttribute('aria-expanded')).toBe('true');
      expect(normal(q('app-hoja-inferior h2')?.textContent)).toBe('Filtros');
      const formulario = q('app-hoja-inferior form.filtros')!;
      expect(qa('label', formulario).map((l) => normal(l.childNodes[0]?.textContent))).toEqual([
        'N.º',
        'N.º de factura',
        'Cliente',
        'Desde',
        'Hasta',
        'Facturas',
      ]);
      expect(qa('button', formulario).map((b) => normal(b.textContent))).toEqual(['Buscar', 'Limpiar']);
    });

    it('en escritorio y en tablet el formulario sigue en la página, sin hoja', async () => {
      for (const modo of ['escritorio', 'tablet'] as const) {
        TestBed.resetTestingModule();
        vi.unstubAllGlobals();
        await crear({ modo });
        expect(q('form.filtros'), modo).not.toBeNull();
        expect(q('app-hoja-inferior'), modo).toBeNull();
      }
    });

    it('Buscar manda los seis filtros con los literales de hoy, cierra la hoja y la cuenta de «Filtros» sube a 6', async () => {
      await crear();
      await abrirFiltros();
      campo('numero', '5');
      campo('numeroFiscal', ' 00001-00000012 ');
      campo('cliente', ' Pérez ');
      campo('desde', '2026-10-01');
      campo('hasta', '2026-10-31');
      campo('fiscal', 'PENDIENTES');

      await enviarFiltros();

      expect(api.listar).toHaveBeenLastCalledWith({
        page: 0,
        numero: 5,
        cliente: 'Pérez',
        desde: '2026-10-01',
        hasta: '2026-10-31',
        numeroFiscal: '00001-00000012',
        fiscal: 'PENDIENTES',
      });
      expect(q('app-hoja-inferior')).toBeNull();
      expect(normal(q('.herramientas-movil button.filtros .cuenta')?.textContent)).toBe('6');
    });

    it('cada filtro solo manda lo suyo', async () => {
      const casos: [string, string, Record<string, unknown>][] = [
        ['numero', '12', { numero: 12 }],
        ['numeroFiscal', '1-12', { numeroFiscal: '1-12' }],
        ['cliente', 'Juan', { cliente: 'Juan' }],
        ['desde', '2026-10-05', { desde: '2026-10-05' }],
        ['hasta', '2026-10-06', { hasta: '2026-10-06' }],
        ['fiscal', 'RECHAZADAS', { fiscal: 'RECHAZADAS' }],
      ];
      for (const [nombre, valor, esperado] of casos) {
        TestBed.resetTestingModule();
        await crear();
        await abrirFiltros();
        campo(nombre, valor);
        await enviarFiltros();
        expect(api.listar, nombre).toHaveBeenLastCalledWith({ page: 0, ...esperado });
      }
    });

    it('un número que no es entero no se manda: el error queda a la vista adentro de la hoja, que sigue abierta con lo escrito', async () => {
      await crear();
      await abrirFiltros();
      api.listar.mockClear();
      campo('numero', '5x');
      campo('cliente', 'Pérez');

      await enviarFiltros();

      expect(api.listar).not.toHaveBeenCalled();
      const alerta = q('[role="alert"]')!;
      expect(normal(alerta.textContent)).toBe('El número tiene que ser un entero.');
      expect(q('form.filtros [name="numero"]') as HTMLInputElement | null).toHaveProperty('value', '5x');
      expect(q('form.filtros [name="cliente"]') as HTMLInputElement | null).toHaveProperty('value', 'Pérez');
      // En el celular la hoja tapa la página: el aviso tiene que estar adentro de la hoja.
      if (q('app-hoja-inferior')) expect(q('app-hoja-inferior')!.contains(alerta)).toBe(true);
    });

    it('un «Hasta» anterior a «Desde» no se manda, muestra el texto de siempre y enfoca «Hasta»', async () => {
      await crear();
      await abrirFiltros();
      api.listar.mockClear();
      campo('desde', '2026-10-10');
      campo('hasta', '2026-10-01');

      await enviarFiltros();

      expect(api.listar).not.toHaveBeenCalled();
      expect(normal(q('[role="alert"]')?.textContent)).toBe('La fecha «Hasta» no puede ser anterior a «Desde».');
      expect(document.activeElement).toBe(q('[name="hasta"]'));
    });

    it('si el servidor rechaza un filtro (400) muestra su mensaje en la página y la lista y los filtros de antes siguen', async () => {
      await crear();
      await abrirFiltros();
      campo('numeroFiscal', '00001-00000012');
      api.listar.mockReturnValue(of(pagina([resumenDePrueba(5)], { totalPages: 2 })));
      await enviarFiltros();

      const mensaje = 'Escribí el número de la factura como 12 o 00001-00000012.';
      api.listar.mockReturnValue(
        error(400, { message: 'Datos inválidos', errors: { numeroFiscal: mensaje } }),
      );
      await abrirFiltros();
      campo('numeroFiscal', 'doce');
      await enviarFiltros();

      expect(normal(q('[role="alert"]')?.textContent)).toBe(mensaje);
      expect(normal(el().textContent)).not.toContain('No se pudieron cargar las ventas.');
      expect(tarjetas().length + filasTabla().length).toBe(1);
      // Paginar no manda el número rechazado.
      api.listar.mockReturnValue(of(pagina([resumenDePrueba(5)], { totalPages: 2, page: 1 })));
      await tocar(boton('Siguiente'));
      expect(api.listar).toHaveBeenLastCalledWith({ page: 1, numeroFiscal: '00001-00000012' });
    });

    it('Limpiar vuelve a pedir sin filtros, vacía los campos y en el celular cierra la hoja', async () => {
      await crear();
      await abrirFiltros();
      campo('cliente', 'Pérez');
      campo('fiscal', 'RECHAZADAS');
      await enviarFiltros();
      await abrirFiltros();

      await tocar(boton('Limpiar'));

      expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
      expect(q('app-hoja-inferior')).toBeNull();
      expect(q('.chip-filtro')).toBeNull();
      await abrirFiltros();
      expect((q('[name="cliente"]') as HTMLInputElement).value).toBe('');
      expect((q('[name="fiscal"]') as HTMLSelectElement).value).toBe('');
    });

    it('Esc cierra la hoja sin buscar y lo escrito queda para la próxima vez', async () => {
      await crear();
      const abrir = q<HTMLButtonElement>('.herramientas-movil button.filtros')!;
      await tocar(abrir);
      expect(q('app-hoja-inferior')).not.toBeNull();
      campo('cliente', 'Pérez');
      api.listar.mockClear();

      tecla('Escape');
      await refrescar();

      expect(q('app-hoja-inferior')).toBeNull();
      expect(api.listar).not.toHaveBeenCalled();
      expect(normal(abrir.textContent)).toContain('Filtros');
      expect(normal(abrir.querySelector('.cuenta')?.textContent)).toBe('1');
      await tocar(abrir);
      expect((q('[name="cliente"]') as HTMLInputElement).value).toBe('Pérez');
    });

    it('la bandeja de ARCA es el filtro «Facturas»: el enlace `?fiscal=RECHAZADAS` abre la lista ya filtrada y el chip lo dice', async () => {
      await crear({ queryParams: { fiscal: 'RECHAZADAS' } });

      expect(api.listar).toHaveBeenCalledWith({ page: 0, fiscal: 'RECHAZADAS' });
      expect(normal(q('.chip-filtro')?.textContent)).toBe('Facturas: Rechazadas');
      await abrirFiltros();
      expect((q('[name="fiscal"]') as HTMLSelectElement).value).toBe('RECHAZADAS');
    });

    it('otro valor de `fiscal` en el enlace se ignora y no hay chip', async () => {
      await crear({ queryParams: { fiscal: 'TODAS' } });

      expect(api.listar).toHaveBeenCalledWith({ page: 0 });
      expect(q('.chip-filtro')).toBeNull();
    });

    it('el chip sigue al filtro que se aplicó y tocarlo abre los filtros', async () => {
      await crear();
      expect(q('.chip-filtro')).toBeNull();
      await abrirFiltros();
      campo('fiscal', 'PENDIENTES');
      // Elegido pero sin aplicar todavía: el chip dice lo que se ve en la lista.
      expect(q('.chip-filtro')).toBeNull();
      await enviarFiltros();

      expect(normal(q('.chip-filtro')?.textContent)).toBe('Facturas: Pendientes');
      await tocar(q('.chip-filtro'));
      expect(q('app-hoja-inferior form.filtros')).not.toBeNull();
    });
  });

  // ------------------------------------------------------------------------------------------------ el detalle

  describe('el detalle', () => {
    it('en el celular es una pantalla completa con «Volver», el título de la venta y el foco en «Volver»', async () => {
      await crear();
      await abrirVenta(0);

      expect(q('app-dialogo-detalle')).toBeNull();
      const p = pantalla()!;
      expect(p.getAttribute('aria-modal')).toBe('true');
      expect(normal(q('h2', p)?.textContent)).toBe('Venta N.º 5');
      expect(p.getAttribute('aria-labelledby')).toBe(q('h2', p)!.id);
      expect(q<HTMLButtonElement>('button.ficha-volver', p)?.getAttribute('aria-label')).toBe('Volver');
      expect(document.activeElement).toBe(q('button.ficha-volver', p));
      // La hoja de la venta es la de siempre.
      expect(normal(q('app-venta-hoja .totales .total', p)?.textContent)).toBe('Total $ 2.970,00');
      expect(q('app-venta-hoja table.renglones', p)).not.toBeNull();
    });

    it('«Volver» cierra el detalle sin pedir nada más', async () => {
      await crear();
      await abrirVenta(0);
      api.listar.mockClear();

      await tocar(q('app-detalle-pantalla button.ficha-volver'));

      expect(pantalla()).toBeNull();
      expect(api.obtener).toHaveBeenCalledTimes(1);
      expect(api.listar).not.toHaveBeenCalled();
      expect(tarjetas()[0].classList.contains('elegida')).toBe(false);
    });

    it('Esc cierra el detalle; con un diálogo abierto encima Esc es del diálogo', async () => {
      await crear({ venta: ventaDePrueba({ ventaId: 50, numero: 5, fiscal: rechazada() }) });
      await abrirVenta(0);

      await tocar(boton('Dejar sin factura', q('app-detalle-pantalla')!));
      expect(dialogoMotivo()).not.toBeNull();
      tecla('Escape', dialogoMotivo()!.querySelector('input')!);
      await refrescar();
      expect(dialogoMotivo()).toBeNull();
      expect(pantalla()).not.toBeNull();

      tecla('Escape');
      await refrescar();
      expect(pantalla()).toBeNull();
    });

    it('`/ventas/:id` abre esa venta aunque no esté en la lista y «Volver» vuelve a `/ventas`', async () => {
      await crear({ id: '50', venta: ventaDePrueba({ ventaId: 50, numero: 5 }) });
      const navegar = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

      expect(api.obtener).toHaveBeenCalledWith(50);
      expect(normal(q('h2', detalle()!)?.textContent)).toBe('Venta N.º 5');

      await tocar(q('app-detalle-pantalla button.ficha-volver') ?? q('.dialogo-detalle-equis'));

      expect(navegar).toHaveBeenCalledWith('/ventas');
      expect(detalle()).toBeNull();
    });

    it('un id ajeno (404) o un fallo del servidor muestra el aviso de siempre y deja volver', async () => {
      await crear();
      api.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
      await abrirVenta(0);
      expect(normal(q('[role="alert"]', detalle()!)?.textContent)).toBe('No se encontró la venta.');

      api.obtener.mockReturnValue(throwError(() => new Error('caída')));
      await abrirVenta(1);
      expect(normal(q('[role="alert"]', detalle()!)?.textContent)).toBe('No se pudo cargar la venta.');
      if (pantalla()) expect(q('button.ficha-volver', pantalla()!)).not.toBeNull();
    });

    it('en escritorio y en tablet el detalle es el pop-up de hoy, con Anular y Cerrar en el pie, y es idéntico en las dos', async () => {
      const venta = ventaDePrueba({ ventaId: 50, numero: 5, fiscal: rechazada() });
      const volcados: string[] = [];
      for (const modo of ['escritorio', 'tablet'] as const) {
        TestBed.resetTestingModule();
        vi.unstubAllGlobals();
        await crear({ modo, venta });
        await abrirVenta(0);
        expect(q('app-dialogo-detalle [role="dialog"]'), modo).not.toBeNull();
        expect(q('app-detalle-pantalla'), modo).toBeNull();
        expect(q('.mas-venta'), modo).toBeNull();
        const pie = q('.dialogo-detalle-pie')!;
        expect(qa('button', pie).map((b) => normal(b.textContent)), modo).toEqual(['Anular', 'Cerrar', 'Imprimir']);
        // La bandeja con sus botones sigue en el cuerpo, como hoy.
        expect(boton('Reintentar', q('.dialogo-detalle-cuerpo')!), modo).toBeDefined();
        volcados.push(dom());
      }
      expect(volcados[1]).toBe(volcados[0]);
    });
  });

  // --------------------------------------------------------------------------------------------- bandeja de ARCA

  const MODOS: Modo[] = ['escritorio', 'celular'];

  describe.each(MODOS)('la bandeja de ARCA en el detalle (%s)', (modo) => {
    beforeEach(() => (modoPorDefecto = modo));
    afterEach(() => (modoPorDefecto = 'celular'));

    const abrirRechazada = async (cambios: Partial<FiscalConsultado> = {}) => {
      await crear({ venta: ventaDePrueba({ ventaId: 50, numero: 5, fiscal: rechazada(cambios) }) });
      await abrirVenta(0);
    };
    /** Dónde están «Reintentar» y «Dejar sin factura»: en el celular, en el pie fijo; en escritorio, en el cuerpo. */
    const accion = (texto: string) => boton(texto, detalle()!);

    it.runIf(modo === 'celular')('en el celular muestra el estado, los errores y las observaciones de ARCA, y deja «Reintentar» y «Dejar sin factura» en el pie fijo', async () => {
      await abrirRechazada();

      const p = pantalla()!;
      expect(normal(q('.estado-factura', p)?.textContent)).toBe('Estado de la factura: Rechazada');
      expect(normal(q('.errores-arca h3', p)?.textContent)).toBe('Errores de ARCA');
      expect(normal(q('.errores-arca .codigo', p)?.textContent)).toBe('10015');
      expect(normal(q('.errores-arca .mensaje', p)?.textContent)).toBe('El campo DocNro es inválido.');
      expect(normal(q('.errores-arca .explicacion', p)?.textContent)).toBe(ERROR_10015.explicacion);
      expect(normal(q('.observaciones-arca h3', p)?.textContent)).toBe('Observaciones de ARCA');
      const pie = q('.detalle-pantalla-pie', p)!;
      expect(qa('button', pie).map((b) => normal(b.textContent))).toEqual(['Dejar sin factura', 'Reintentar']);
      expect(q('button.primario', pie)?.textContent?.trim()).toBe('Reintentar');
      // Los botones están sólo en el pie, no también en el cuerpo.
      expect(qa('.acciones-factura button', p)).toHaveLength(0);
    });

    it('«Reintentar» manda `fechaDeHoy: false`, dice «Autorizando con ARCA…» mientras espera y después refresca el detalle y la lista', async () => {
      await abrirRechazada();
      const respuesta = new Subject<unknown>();
      api.reintentar.mockReturnValue(respuesta);
      api.listar.mockClear();
      api.obtener.mockClear();

      await tocar(accion('Reintentar'));

      expect(api.reintentar).toHaveBeenCalledWith(50, false);
      expect(normal(q('[role="status"]', detalle()!)?.textContent)).toBe('Autorizando con ARCA…');
      expect(accion('Reintentar')!.disabled).toBe(true);
      expect(accion('Dejar sin factura')!.disabled).toBe(true);
      // En vuelo no se manda dos veces.
      accion('Reintentar')!.click();
      expect(api.reintentar).toHaveBeenCalledTimes(1);

      api.obtener.mockReturnValue(of(ventaDePrueba({ ventaId: 50, numero: 5, fiscal: fiscalDe(documentoB()) })));
      respuesta.next({});
      respuesta.complete();
      await refrescar();

      expect(api.listar).toHaveBeenCalledWith({ page: 0 });
      expect(api.obtener).toHaveBeenCalledWith(50);
      expect(normal(q('.linea-fiscal', detalle()!)?.textContent)).toBe('Factura B 00001-00000012 · Autorizada');
      expect(accion('Reintentar')).toBeUndefined();
      expect(normal(q('.resultado-accion', detalle()!)?.textContent)).toBe('Se reintentó la factura de la venta N.º 5.');
      expect(qa('.resultado-accion')).toHaveLength(1);
    });

    it('con `fechaNuevaRequerida` pide confirmar la fecha de hoy antes de mandar; Cancelar no manda y confirmar manda `fechaDeHoy: true`', async () => {
      await abrirRechazada({ estado: 'FUERA_DE_FECHA', fechaNuevaRequerida: true });

      await tocar(accion('Reintentar'));
      expect(api.reintentar).not.toHaveBeenCalled();
      expect(normal(q('h2', confirmacion()!)?.textContent)).toBe('¿Facturar con la fecha de hoy?');
      expect(normal(q('p', confirmacion()!)?.textContent)).toBe(MENSAJE_FECHA);
      await tocar(boton('Cancelar', confirmacion()!));
      expect(confirmacion()).toBeNull();
      expect(api.reintentar).not.toHaveBeenCalled();

      await tocar(accion('Reintentar'));
      await tocar(boton('Facturar con la fecha de hoy', confirmacion()!));
      expect(api.reintentar.mock.calls).toEqual([[50, true]]);
    });

    it('ante el 409 FECHA_NUEVA_REQUERIDA pide la confirmación con el texto del servidor y reintenta con la fecha de hoy', async () => {
      await abrirRechazada();
      api.reintentar.mockReturnValueOnce(error(409, { message: MENSAJE_FECHA, motivo: 'FECHA_NUEVA_REQUERIDA' }));

      await tocar(accion('Reintentar'));
      expect(normal(q('p', confirmacion()!)?.textContent)).toBe(MENSAJE_FECHA);
      await tocar(boton('Facturar con la fecha de hoy', confirmacion()!));

      expect(api.reintentar.mock.calls).toEqual([
        [50, false],
        [50, true],
      ]);
    });

    it('los otros 409 y el 422 de «Reintentar» quedan tal cual al lado de la acción: el detalle sigue, sin refrescar y con los botones prendidos', async () => {
      const casos: [number, string, string][] = [
        [409, 'FACTURACION_FRENADA', 'La facturación está frenada: falta el certificado.'],
        [422, 'CLIENTE_SIN_DATOS_FISCALES', 'Al cliente le falta el CUIT para facturarle.'],
      ];
      await abrirRechazada();
      api.listar.mockClear();
      api.obtener.mockClear();

      for (const [status, motivo, mensaje] of casos) {
        api.reintentar.mockReturnValueOnce(error(status, { message: mensaje, motivo, articulos: [] }));
        await tocar(accion('Reintentar'));

        const alerta = q('.acciones-factura .error-accion', detalle()!);
        expect(normal(alerta?.textContent), motivo).toBe(mensaje);
        expect(alerta?.getAttribute('role')).toBe('alert');
        expect(accion('Reintentar')!.disabled).toBe(false);
        expect(accion('Dejar sin factura')!.disabled).toBe(false);
        expect(confirmacion()).toBeNull();
      }
      // Un error sin cuerpo dice el texto genérico.
      api.reintentar.mockReturnValueOnce(throwError(() => new Error('caída')));
      await tocar(accion('Reintentar'));
      expect(normal(q('.error-accion', detalle()!)?.textContent)).toBe('No se pudo hacer. Probá de nuevo.');
      expect(api.listar).not.toHaveBeenCalled();
      expect(api.obtener).not.toHaveBeenCalled();
      expect(normal(q('.estado-factura', detalle()!)?.textContent)).toBe('Estado de la factura: Rechazada');
    });

    it('«Dejar sin factura» abre el diálogo del motivo y lo manda; un 400 queda en el diálogo con el motivo escrito', async () => {
      await abrirRechazada();

      await tocar(accion('Dejar sin factura'));
      expect(normal(q('h2', dialogoMotivo()!)?.textContent)).toBe('Dejar sin factura la venta N.º 5');
      const motivo = q<HTMLInputElement>('input', dialogoMotivo()!)!;
      motivo.value = 'Otro motivo';
      motivo.dispatchEvent(new Event('input'));
      api.dejarSinFactura.mockReturnValueOnce(
        error(400, { message: 'Datos inválidos', errors: { motivo: 'El motivo tiene hasta 200 caracteres.' } }),
      );
      await tocar(boton('Dejar sin factura', dialogoMotivo()!));
      expect(normal(q('[role="alert"]', dialogoMotivo()!)?.textContent)).toBe('El motivo tiene hasta 200 caracteres.');
      expect(q<HTMLInputElement>('input', dialogoMotivo()!)!.value).toBe('Otro motivo');

      motivo.value = '  El cliente no quiso factura ';
      motivo.dispatchEvent(new Event('input'));
      api.listar.mockClear();
      const sinFactura = rechazada({ estado: 'SIN_FACTURA', motivoSinFactura: 'El cliente no quiso factura', acciones: [] });
      api.obtener.mockReturnValue(of(ventaDePrueba({ ventaId: 50, numero: 5, fiscal: sinFactura })));
      await tocar(boton('Dejar sin factura', dialogoMotivo()!));

      expect(api.dejarSinFactura).toHaveBeenLastCalledWith(50, 'El cliente no quiso factura');
      expect(dialogoMotivo()).toBeNull();
      expect(api.listar).toHaveBeenCalledWith({ page: 0 });
      expect(normal(q('.motivo-sin-factura', detalle()!)?.textContent)).toBe('Motivo: El cliente no quiso factura');
      expect(normal(q('.resultado-accion', detalle()!)?.textContent)).toBe('La venta N.º 5 quedó sin factura.');
      expect(accion('Reintentar')).toBeUndefined();
    });

    it('una factura en revisión, el motivo de fuera de fecha y «Reimprimir» se leen como alertas y no ofrecen acciones', async () => {
      await crear({
        venta: ventaDePrueba({
          ventaId: 50,
          numero: 5,
          fiscal: autorizada({
            estado: 'CAE_NO_COINCIDE',
            reimprimir: true,
            motivoFueraDeFecha: 'ARCA ya no acepta la fecha de esta venta.',
          }),
        }),
      });
      await abrirVenta(0);

      const d = detalle()!;
      expect(normal(q('.en-revision', d)?.textContent)).toBe('Hay una diferencia con ARCA que tiene que revisar soporte.');
      expect(normal(q('.motivo-fuera-de-fecha', d)?.textContent)).toBe('ARCA ya no acepta la fecha de esta venta.');
      expect(normal(q('.aviso-reimprimir', d)?.textContent)).toBe(
        'Salió con ticket provisorio y ARCA ya la autorizó: reimprimí la factura para el cliente.',
      );
      expect(accion('Reintentar')).toBeUndefined();
      expect(accion('Dejar sin factura')).toBeUndefined();
    });

    it.runIf(modo === 'celular')('en el celular, con las acciones de la bandeja en el pie, «Imprimir» pasa al ⋯ y sigue imprimiendo', async () => {
      await abrirRechazada();
      expect(boton('Imprimir', q('.detalle-pantalla-pie')!)).toBeUndefined();

      await tocar(q('button.mas-venta'));
      expect(normal(q('app-hoja-inferior h2')?.textContent)).toBe('Venta N.º 5');
      await tocar(boton('Imprimir', hoja()!));

      expect(q('app-hoja-inferior')).toBeNull();
      expect(q('app-venta-imprimible, app-factura-imprimible')).not.toBeNull();
    });
  });

  // ------------------------------------------------------------------------------- imprimir, anular y los roles

  describe.each(MODOS)('imprimir y anular (%s)', (modo) => {
    beforeEach(() => (modoPorDefecto = modo));
    afterEach(() => (modoPorDefecto = 'celular'));

    it.runIf(modo === 'celular')('sin acciones de bandeja el pie lleva «Imprimir» (o «Reimprimir factura» si hay que reimprimirla) a todo el ancho', async () => {
      await crear({ venta: ventaDePrueba({ ventaId: 50, numero: 5, fiscal: autorizada() }) });
      await abrirVenta(0);
      const pie = q('.detalle-pantalla-pie')!;
      expect(qa('button', pie).map((b) => normal(b.textContent))).toEqual(['Imprimir']);
      expect(q('button', pie)!.classList.contains('primario')).toBe(true);

      await tocar(q('button', pie));
      expect(q('app-factura-imprimible')).not.toBeNull();

      TestBed.resetTestingModule();
      await crear({ venta: ventaDePrueba({ ventaId: 50, numero: 5, fiscal: autorizada({ reimprimir: true }) }) });
      await abrirVenta(0);
      expect(qa('button', q('.detalle-pantalla-pie')!).map((b) => normal(b.textContent))).toEqual(['Reimprimir factura']);
    });

    /** «Anular»: en el celular sale del ⋯ del detalle; en escritorio y tablet es el botón del pie del pop-up. */
    async function abrirAnular() {
      if (q('button.mas-venta')) {
        await tocar(q('button.mas-venta'));
        await tocar(boton('Anular', hoja()!));
      } else {
        await tocar(boton('Anular', detalle()!));
      }
    }

    it.runIf(modo === 'celular')('en el celular «Anular» sale del ⋯ y no está en el pie; la hoja se cierra al elegirlo y abre el diálogo de hoy', async () => {
      await crear({ venta: ventaDePrueba({ ventaId: 50, numero: 5, fiscal: autorizada(), anulacion: null }) });
      await abrirVenta(0);
      expect(boton('Anular', q('.detalle-pantalla-pie')!)).toBeUndefined();

      await tocar(q('button.mas-venta'));
      expect(q('button.mas-venta')!.getAttribute('aria-label')).toBe('Más opciones de la venta');
      expect(q('button.mas-venta')!.getAttribute('aria-expanded')).toBe('true');
      expect(qa('.accion-hoja', hoja()!).map((b) => normal(b.textContent))).toEqual(['Anular']);
      expect(q('.accion-hoja.peligro', hoja()!)).not.toBeNull();
      await tocar(boton('Anular', hoja()!));

      expect(q('app-hoja-inferior')).toBeNull();
      expect(dialogoAnular()).not.toBeNull();
      expect(pantalla()).not.toBeNull();
    });

    it('«Anular» abre el diálogo de hoy con su texto, manda lo de hoy y abre la nota; Cancelar no manda', async () => {
      await crear({ venta: ventaDePrueba({ ventaId: 50, numero: 5, fiscal: autorizada(), anulacion: null }) });
      await abrirVenta(0);

      await abrirAnular();
      expect(normal(q('h2', dialogoAnular()!)?.textContent)).toBe('¿Anular la venta N.º 5?');
      expect(api.anular).not.toHaveBeenCalled();
      await tocar(boton('Cancelar', dialogoAnular()!));
      expect(dialogoAnular()).toBeNull();
      expect(api.anular).not.toHaveBeenCalled();

      await abrirAnular();
      const nota = ventaDePrueba({ ventaId: 140, numero: 14, tipo: 'NOTA_CREDITO', ventaAnulada: { id: 50, numero: 5 }, fiscal: autorizada() });
      api.anular.mockReturnValue(of(nota));
      api.obtener.mockReturnValue(of(nota));
      api.listar.mockReturnValue(of(pagina([resumenDePrueba(14, { tipo: 'NOTA_CREDITO' }), resumenDePrueba(5)])));
      await tocar(boton('Anular', dialogoAnular()!));

      expect(api.anular).toHaveBeenCalledWith(50, null);
      expect(api.obtener).toHaveBeenLastCalledWith(140);
      expect(normal(q('h2', detalle()!)?.textContent)).toBe('Nota de crédito N.º 14');
      expect(normal(q('.resultado-accion', detalle()!)?.textContent)).toBe(
        'Se anuló la venta N.º 5 con la nota de crédito N.º 14.',
      );
    });

    it('sin la factura autorizada «Anular» pide el motivo y un rechazo queda en el diálogo con el motivo escrito', async () => {
      await crear({ venta: ventaDePrueba({ ventaId: 50, numero: 5, anulacion: null }) });
      await abrirVenta(0);
      await abrirAnular();

      const motivo = q<HTMLInputElement>('#motivo-anulacion', dialogoAnular()!)!;
      motivo.value = 'El cliente devolvió todo';
      motivo.dispatchEvent(new Event('input'));
      api.anular.mockReturnValueOnce(error(409, { message: 'Esperá el CAE de la factura para anularla.', motivo: 'ESPERAR_CAE' }));
      await tocar(boton('Anular', dialogoAnular()!));

      expect(api.anular).toHaveBeenCalledWith(50, 'El cliente devolvió todo');
      expect(normal(q('[role="alert"]', dialogoAnular()!)?.textContent)).toBe('Esperá el CAE de la factura para anularla.');
      expect(q<HTMLInputElement>('#motivo-anulacion', dialogoAnular()!)!.value).toBe('El cliente devolvió todo');
      expect(detalle()).not.toBeNull();
    });

    it('una nota de crédito y una venta anulada no se anulan: no hay ⋯ ni «Anular»', async () => {
      const nota = ventaDePrueba({ ventaId: 140, numero: 14, tipo: 'NOTA_CREDITO', ventaAnulada: { id: 50, numero: 5 }, fiscal: autorizada() });
      const anulada = ventaDePrueba({
        ventaId: 50,
        numero: 5,
        fiscal: autorizada(),
        anulacion: { notaId: 140, numero: 14, interna: false, motivo: null, usuario: 'Dueño', fecha: '2026-10-29T14:05:00' },
      });
      for (const venta of [nota, anulada]) {
        TestBed.resetTestingModule();
        await crear({ venta });
        await abrirVenta(0);
        expect(q('button.mas-venta')).toBeNull();
        expect(boton('Anular')).toBeUndefined();
      }
    });
  });
  // ------------------------------------------------------------------------------ anulaciones (T11c, riesgo alto)

  /**
   * «Anular» revierte una venta (stock, caja y una nota de crédito fiscal): el diálogo, sus textos, su validación y el
   * pedido son los de hoy en escritorio y en celular. En el celular el diálogo sale del ⋯ del detalle y sólo cambia su
   * acomodo (CSS): su marcado es el mismo.
   */
  describe.each(MODOS)('anular una venta (%s)', (modo) => {
    beforeEach(() => (modoPorDefecto = modo));
    afterEach(() => (modoPorDefecto = 'celular'));

    const TEXTO_NOTA =
      '¿Anular la venta N.º 5? Se emite una nota de crédito y vuelve el stock. La caja no cambia: si devolvés plata, cargá un egreso.';
    const TEXTO_INTERNA =
      '¿Anular la venta N.º 5? La venta no tiene factura: queda anulada con el motivo y vuelve el stock. La caja no cambia: si devolvés plata, cargá un egreso.';
    const CUENTA_CORRIENTE = ' La parte a cuenta corriente se descuenta de la deuda del cliente.';
    const nota = () =>
      ventaDePrueba({ ventaId: 140, numero: 14, tipo: 'NOTA_CREDITO', ventaAnulada: { id: 50, numero: 5 }, fiscal: autorizada() });

    /** Abre la venta 5 y su «Anular»: en el celular por el ⋯, en escritorio por el pie del pop-up. */
    async function abrirAnularDe(venta: VentaConsultada) {
      await crear({ venta });
      await abrirVenta(0);
      if (modo === 'celular') {
        await tocar(q('button.mas-venta'));
        await tocar(boton('Anular', hoja()!));
      } else {
        await tocar(boton('Anular', detalle()!));
      }
    }
    const conFactura = () => ventaDePrueba({ ventaId: 50, numero: 5, fiscal: autorizada(), anulacion: null });
    const sinFactura = () => ventaDePrueba({ ventaId: 50, numero: 5, anulacion: null });
    const textoDelDialogo = () => normal(`${q('h2', dialogoAnular()!)?.textContent} ${q('#dialogo-anular-mensaje')?.textContent}`);
    const escribirMotivo = (texto: string) => {
      const campo = q<HTMLInputElement>('#motivo-anulacion', dialogoAnular()!)!;
      campo.value = texto;
      campo.dispatchEvent(new Event('input'));
    };
    const anularDelDialogo = () => boton('Anular', dialogoAnular()!)!;
    const errorDelMotivo = () => normal(q('#error-motivo-anulacion', dialogoAnular()!)?.textContent);

    it('con la factura autorizada confirma con el texto de hoy, sin motivo, con el foco en «Cancelar», y manda `anular(50, null)` una sola vez', async () => {
      await abrirAnularDe(conFactura());

      expect(textoDelDialogo()).toBe(TEXTO_NOTA);
      expect(q('#motivo-anulacion', dialogoAnular()!)).toBeNull();
      expect(qa('.dialogo-acciones button', dialogoAnular()!).map((b) => normal(b.textContent))).toEqual(['Cancelar', 'Anular']);
      expect(normal(document.activeElement?.textContent)).toBe('Cancelar');
      expect(api.anular).not.toHaveBeenCalled();

      api.anular.mockReturnValue(of(nota()));
      api.obtener.mockReturnValue(of(nota()));
      api.listar.mockReturnValue(of(pagina([resumenDePrueba(14, { tipo: 'NOTA_CREDITO' }), resumenDePrueba(5)])));
      await tocar(anularDelDialogo());

      expect(api.anular).toHaveBeenCalledTimes(1);
      expect(api.anular).toHaveBeenCalledWith(50, null);
      expect(dialogoAnular()).toBeNull();
      expect(normal(q('.resultado-accion', detalle()!)?.textContent)).toBe('Se anuló la venta N.º 5 con la nota de crédito N.º 14.');
    });

    it('si cargó algo a cuenta corriente el texto suma que se descuenta de la deuda del cliente', async () => {
      const pagos = [{ formaPagoId: 4, formaPago: 'Cuenta corriente', medio: 'CUENTA_CORRIENTE', recargoPorcentaje: 0, recargo: 0, monto: 2970 }];
      await abrirAnularDe({ ...conFactura(), pagos } as VentaConsultada);

      expect(textoDelDialogo()).toBe(TEXTO_NOTA + CUENTA_CORRIENTE);
    });

    it('sin la factura autorizada pide el motivo (foco en el campo, como hoy), marca los errores con los textos de hoy y no manda nada hasta que vale', async () => {
      await abrirAnularDe(sinFactura());

      expect(textoDelDialogo()).toBe(TEXTO_INTERNA);
      expect(normal(q('label.campo-motivo', dialogoAnular()!)?.textContent)).toBe('Motivo');
      expect(document.activeElement?.id).toBe('motivo-anulacion');
      expect(errorDelMotivo()).toBe('');

      await tocar(anularDelDialogo());
      expect(api.anular).not.toHaveBeenCalled();
      expect(errorDelMotivo()).toBe('Contá por qué se anula la venta.');
      expect(q('#motivo-anulacion', dialogoAnular()!)!.getAttribute('aria-invalid')).toBe('true');

      escribirMotivo('     ');
      await tocar(anularDelDialogo());
      expect(api.anular).not.toHaveBeenCalled();
      expect(errorDelMotivo()).toBe('Contá por qué se anula la venta.');

      escribirMotivo('x'.repeat(201));
      await refrescar();
      expect(errorDelMotivo()).toBe('El motivo tiene hasta 200 caracteres.');
      expect(normal(q('.contador', dialogoAnular()!)?.textContent)).toBe('201/200');
      await tocar(anularDelDialogo());
      expect(api.anular).not.toHaveBeenCalled();
      expect(dialogoAnular()).not.toBeNull();
    });

    it('con un motivo válido manda `anular(50, motivo)` recortado, y con 200 caracteres justos también', async () => {
      await abrirAnularDe(sinFactura());
      api.anular.mockReturnValue(of(ventaDePrueba({ ventaId: 140, numero: 14, tipo: 'NOTA_CREDITO' })));

      escribirMotivo('  El cliente devolvió todo  ');
      await tocar(anularDelDialogo());

      expect(api.anular).toHaveBeenCalledTimes(1);
      expect(api.anular).toHaveBeenCalledWith(50, 'El cliente devolvió todo');

      TestBed.resetTestingModule();
      await abrirAnularDe(sinFactura());
      escribirMotivo('y'.repeat(200));
      await tocar(anularDelDialogo());
      expect(api.anular).toHaveBeenCalledWith(50, 'y'.repeat(200));
    });

    it('un rechazo deja el diálogo abierto con el motivo escrito y el error, el detalle intacto sin releer nada, y se puede reintentar con lo mismo', async () => {
      await abrirAnularDe(sinFactura());
      const titulo = normal(q('h2', detalle()!)?.textContent);
      escribirMotivo('El cliente devolvió todo');
      api.listar.mockClear();
      api.obtener.mockClear();
      api.anular.mockReturnValueOnce(error(409, { message: 'Esperá el CAE de la factura para anularla.', motivo: 'ESPERAR_CAE' }));

      await tocar(anularDelDialogo());

      expect(api.anular).toHaveBeenCalledWith(50, 'El cliente devolvió todo');
      expect(normal(q('[role="alert"]', dialogoAnular()!)?.textContent)).toBe('Esperá el CAE de la factura para anularla.');
      expect(q<HTMLInputElement>('#motivo-anulacion', dialogoAnular()!)!.value).toBe('El cliente devolvió todo');
      expect(anularDelDialogo().disabled).toBe(false);
      expect(boton('Cancelar', dialogoAnular()!)!.disabled).toBe(false);
      expect(normal(q('h2', detalle()!)?.textContent)).toBe(titulo);
      expect(q('.resultado-accion')).toBeNull();
      expect(api.listar).not.toHaveBeenCalled();
      expect(api.obtener).not.toHaveBeenCalled();

      const interna = ventaDePrueba({ ventaId: 140, numero: 14, tipo: 'NOTA_CREDITO', ventaAnulada: { id: 50, numero: 5 } });
      api.anular.mockReturnValue(of(interna));
      api.obtener.mockReturnValue(of(interna));
      api.listar.mockReturnValue(of(pagina([resumenDePrueba(14, { tipo: 'NOTA_CREDITO' }), resumenDePrueba(5)])));
      await tocar(anularDelDialogo());

      expect(api.anular).toHaveBeenCalledTimes(2);
      expect(api.anular).toHaveBeenLastCalledWith(50, 'El cliente devolvió todo');
      expect(dialogoAnular()).toBeNull();
      expect(normal(q('.resultado-accion', detalle()!)?.textContent)).toBe('Se anuló la venta N.º 5 con la anulación N.º 14.');
    });

    it('un error sin mensaje dice «No se pudo hacer. Probá de nuevo.» dentro del diálogo y un 400 de campos junta sus textos', async () => {
      await abrirAnularDe(conFactura());
      api.anular.mockReturnValueOnce(error(500, {}));
      await tocar(anularDelDialogo());
      expect(normal(q('[role="alert"]', dialogoAnular()!)?.textContent)).toBe('No se pudo hacer. Probá de nuevo.');

      api.anular.mockReturnValueOnce(error(400, { message: 'Datos inválidos', errors: { motivo: 'El motivo es obligatorio.' } }));
      await tocar(anularDelDialogo());
      expect(normal(q('[role="alert"]', dialogoAnular()!)?.textContent)).toBe('El motivo es obligatorio.');
      expect(api.anular).toHaveBeenCalledTimes(2);
      expect(detalle()).not.toBeNull();
    });

    it('mientras se anula los dos botones están apagados, nada más se manda y Esc no cierra; si falla vuelve a andar', async () => {
      await abrirAnularDe(conFactura());
      const pedido = new Subject<VentaConsultada>();
      api.anular.mockReturnValue(pedido);

      await tocar(anularDelDialogo());
      expect(anularDelDialogo().disabled).toBe(true);
      expect(boton('Cancelar', dialogoAnular()!)!.disabled).toBe(true);
      anularDelDialogo().click();
      boton('Cancelar', dialogoAnular()!)!.click();
      tecla('Escape');
      await refrescar();
      expect(api.anular).toHaveBeenCalledTimes(1);
      expect(dialogoAnular()).not.toBeNull();

      pedido.error(new HttpErrorResponse({ status: 422, error: { status: 422, message: 'La venta ya no se puede anular.' } }));
      await refrescar();
      expect(normal(q('[role="alert"]', dialogoAnular()!)?.textContent)).toBe('La venta ya no se puede anular.');
      expect(anularDelDialogo().disabled).toBe(false);
    });

    it('Cancelar, con o sin motivo escrito, no manda nada ni relee y deja el detalle abierto y sin un campo con el foco', async () => {
      await abrirAnularDe(sinFactura());
      escribirMotivo('algo a medias');
      api.listar.mockClear();
      api.obtener.mockClear();

      await tocar(boton('Cancelar', dialogoAnular()!));

      expect(dialogoAnular()).toBeNull();
      expect(api.anular).not.toHaveBeenCalled();
      expect(api.listar).not.toHaveBeenCalled();
      expect(api.obtener).not.toHaveBeenCalled();
      expect(detalle()).not.toBeNull();
      expect(['INPUT', 'TEXTAREA', 'SELECT']).not.toContain(document.activeElement?.tagName);

      // Al volver a abrirlo, el motivo arranca vacío.
      if (modo === 'celular') {
        await tocar(q('button.mas-venta'));
        await tocar(boton('Anular', hoja()!));
      } else {
        await tocar(boton('Anular', detalle()!));
      }
      expect(q<HTMLInputElement>('#motivo-anulacion', dialogoAnular()!)!.value).toBe('');
    });

    it('Esc cierra sólo el diálogo, sin mandar nada: el detalle sigue abierto', async () => {
      await abrirAnularDe(conFactura());

      tecla('Escape');
      await refrescar();

      expect(dialogoAnular()).toBeNull();
      expect(api.anular).not.toHaveBeenCalled();
      expect(detalle()).not.toBeNull();
      expect(['INPUT', 'TEXTAREA', 'SELECT']).not.toContain(document.activeElement?.tagName);
    });

    it('Enter en el campo del motivo confirma sólo con un motivo válido', async () => {
      await abrirAnularDe(sinFactura());

      tecla('Enter', q('#motivo-anulacion', dialogoAnular()!)!);
      await refrescar();
      expect(api.anular).not.toHaveBeenCalled();
      expect(errorDelMotivo()).toBe('Contá por qué se anula la venta.');

      escribirMotivo('Error de carga');
      tecla('Enter', q('#motivo-anulacion', dialogoAnular()!)!);
      await refrescar();
      expect(api.anular).toHaveBeenCalledTimes(1);
      expect(api.anular).toHaveBeenCalledWith(50, 'Error de carga');
    });

    it('una nota de crédito y una venta ya anulada no ofrecen «Anular»', async () => {
      const anulada = ventaDePrueba({
        ventaId: 50,
        numero: 5,
        fiscal: autorizada(),
        anulacion: { notaId: 140, numero: 14, interna: false, motivo: null, usuario: 'Dueño', fecha: '2026-10-29T14:05:00' },
      });
      for (const venta of [nota(), anulada]) {
        TestBed.resetTestingModule();
        await crear({ venta });
        await abrirVenta(0);
        expect(boton('Anular', detalle()!)).toBeUndefined();
        expect(q('button.mas-venta')).toBeNull();
        expect(dialogoAnular()).toBeNull();
      }
    });

    it('el Vendedor no llega a Ventas: no hay lista, ni detalle, ni «Anular», y no se pide nada', async () => {
      await crear({ rol: 'VENDEDOR' });

      expect(normal(q('.estado-vacio')?.textContent)).toBe('La consulta de ventas es solo del dueño.');
      expect(tarjetas()).toHaveLength(0);
      expect(boton('Anular')).toBeUndefined();
      expect(api.listar).not.toHaveBeenCalled();
      expect(api.anular).not.toHaveBeenCalled();
    });
  });

  describe('el diálogo de «Anular» en el celular', () => {
    /** El diálogo sin los números de los ids de cada apertura. */
    const volcadoDelDialogo = () => q('app-dialogo-anular')!.outerHTML.replace(/<!--[\s\S]*?-->/g, '').replace(/_ngcontent-[^"\s]+|ng-reflect-[^=]+="[^"]*"/g, '');

    async function abrir(modo: Modo, venta: VentaConsultada) {
      TestBed.resetTestingModule();
      vi.unstubAllGlobals();
      await crear({ modo, venta });
      await abrirVenta(0);
      if (modo === 'celular') {
        await tocar(q('button.mas-venta'));
        await tocar(boton('Anular', hoja()!));
      } else {
        await tocar(boton('Anular', detalle()!));
      }
    }

    it('es el mismo marcado que en escritorio y en tablet: sólo cambia el CSS, con el motivo y sin él', async () => {
      for (const venta of [
        ventaDePrueba({ ventaId: 50, numero: 5, fiscal: autorizada(), anulacion: null }),
        ventaDePrueba({ ventaId: 50, numero: 5, anulacion: null }),
      ]) {
        const volcados: string[] = [];
        for (const modo of ['escritorio', 'tablet', 'celular'] as const) {
          await abrir(modo, venta);
          volcados.push(volcadoDelDialogo());
        }
        expect(volcados[1]).toBe(volcados[0]);
        expect(volcados[2]).toBe(volcados[0]);
      }
    });

    it('sale del ⋯ del detalle (no del pie), la hoja se cierra, la pantalla del detalle queda abajo y el ⋯ sigue andando al cancelar', async () => {
      await abrir('celular', ventaDePrueba({ ventaId: 50, numero: 5, fiscal: autorizada(), anulacion: null }));

      expect(q('app-hoja-inferior')).toBeNull();
      expect(pantalla()).not.toBeNull();
      expect(dialogoAnular()!.getAttribute('aria-modal')).toBe('true');

      await tocar(boton('Cancelar', dialogoAnular()!));
      expect(dialogoAnular()).toBeNull();
      expect(pantalla()).not.toBeNull();
      await tocar(q('button.mas-venta'));
      expect(boton('Anular', hoja()!)).toBeDefined();
      expect(api.anular).not.toHaveBeenCalled();
    });
  });
});
