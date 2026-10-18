import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { CajaVigente } from '../caja/caja.models';
import { CajaService } from '../caja/caja.service';
import { FormaPago } from '../formas-pago/forma-pago.models';
import { FormasPagoService } from '../formas-pago/formas-pago.service';
import { EmitirPresupuestoRequest, PresupuestoEmitido } from '../presupuestos/presupuesto.models';
import { PresupuestosService } from '../presupuestos/presupuestos.service';
import { EmitirRemitoRequest, RemitoEmitido } from '../remitos/remito.models';
import { RemitosService } from '../remitos/remitos.service';
import { ArticuloMostrador, BusquedaMostrador } from './mostrador.models';
import { Mostrador } from './mostrador';
import { MostradorService } from './mostrador.service';
import { GENERAR_CLAVE } from './ticket.store';
import { VentaService } from './venta.service';
import { ConfirmarVentaRequest, VentaCobrada } from './venta.models';

registerLocaleData(localeEsAr);

const formaDe = (id: number, nombre: string, medio: FormaPago['medio'], recargoPorcentaje = 0): FormaPago => ({
  id,
  nombre,
  medio,
  recargoPorcentaje,
  activa: true,
  orden: id,
});

/** Las formas de siempre, sin recargo: Efectivo 1, Débito 2, Crédito 3, Transferencia 4. */
const FORMAS_BASE: FormaPago[] = [
  formaDe(1, 'Efectivo', 'EFECTIVO'),
  formaDe(2, 'Débito', 'DEBITO'),
  formaDe(3, 'Crédito', 'CREDITO'),
  formaDe(4, 'Transferencia', 'TRANSFERENCIA'),
];

describe('Mostrador', () => {
  const articulo = (id: number, cambios: Partial<ArticuloMostrador> = {}): ArticuloMostrador => ({
    id,
    codigoInterno: `COD-${id}`,
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: `Artículo ${id}`,
    precioFinal: 170,
    unidad: 'UNIDAD',
    stockActual: 10,
    stockMinimo: 5,
    ubicacion: 'Pasillo 1',
    activo: true,
    ...cambios,
  });

  const lista = (items: ArticuloMostrador[], hayMas = false): BusquedaMostrador => ({
    tipo: 'LISTA',
    items,
    hayMas,
  });

  /** Una llamada al servicio falso: la respuesta la da el test cuando quiere. */
  interface Llamada {
    q: string;
    respuesta: Subject<BusquedaMostrador>;
  }

  /** Un POST de venta al servicio falso: la respuesta la da el test cuando quiere. */
  interface Cobro {
    pedido: ConfirmarVentaRequest;
    respuesta: Subject<VentaCobrada>;
  }

  /** Un POST de presupuesto al servicio falso: la respuesta la da el test cuando quiere. */
  interface Guardado {
    pedido: EmitirPresupuestoRequest;
    respuesta: Subject<PresupuestoEmitido>;
  }

  /** Un POST de remito al servicio falso: la respuesta la da el test cuando quiere. */
  interface Emision {
    pedido: EmitirRemitoRequest;
    respuesta: Subject<RemitoEmitido>;
  }

  let fixture: ComponentFixture<Mostrador>;
  let llamadas: Llamada[];
  let cobros: Cobro[];
  let guardados: Guardado[];
  let emitidos: Emision[];
  let claves: number;
  let cajas: { actual: ReturnType<typeof vi.fn> };
  let formasApi: { listar: ReturnType<typeof vi.fn> };

  /** CU-10: por defecto la caja está abierta hoy, como la encuentra el vendedor al empezar. */
  const cajaAbierta: CajaVigente = {
    estado: 'ABIERTA',
    caja: { id: 4, estado: 'ABIERTA', fechaApertura: '2026-10-16T08:00:00', abiertaPor: 'Ana', efectivoInicial: 0 },
    efectivoPropuesto: 0,
  };

  async function crear(vigente: CajaVigente = cajaAbierta, formas: FormaPago[] = FORMAS_BASE) {
    // jsdom no implementa scrollIntoView.
    Element.prototype.scrollIntoView =
      vi.fn() as unknown as typeof Element.prototype.scrollIntoView;
    llamadas = [];
    cobros = [];
    guardados = [];
    emitidos = [];
    claves = 0;
    const servicio = {
      buscar: vi.fn((q: string) => {
        const respuesta = new Subject<BusquedaMostrador>();
        llamadas.push({ q, respuesta });
        return respuesta;
      }),
    };
    const ventas = {
      cobrar: vi.fn((pedido: ConfirmarVentaRequest) => {
        const respuesta = new Subject<VentaCobrada>();
        cobros.push({ pedido, respuesta });
        return respuesta;
      }),
    };
    const presupuestosApi = {
      emitir: vi.fn((pedido: EmitirPresupuestoRequest) => {
        const respuesta = new Subject<PresupuestoEmitido>();
        guardados.push({ pedido, respuesta });
        return respuesta;
      }),
    };
    const remitosApi = {
      emitir: vi.fn((pedido: EmitirRemitoRequest) => {
        const respuesta = new Subject<RemitoEmitido>();
        emitidos.push({ pedido, respuesta });
        return respuesta;
      }),
    };
    cajas = { actual: vi.fn(() => of(vigente)) };
    formasApi = { listar: vi.fn(() => of(formas)) };
    const sesion = signal({
      userId: 7,
      nombre: 'Ana',
      email: 'ana@x.com',
      rol: 'VENDEDOR',
      ferreteriaId: 3,
      ferreteriaNombre: 'Tornillo',
    });
    await TestBed.configureTestingModule({
      imports: [Mostrador],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: MostradorService, useValue: servicio },
        { provide: VentaService, useValue: ventas },
        { provide: PresupuestosService, useValue: presupuestosApi },
        { provide: RemitosService, useValue: remitosApi },
        { provide: CajaService, useValue: cajas },
        { provide: FormasPagoService, useValue: formasApi },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly() } },
        { provide: GENERAR_CLAVE, useValue: () => `clave-${++claves}` },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Mostrador);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const campo = () => el().querySelector('input')!;
  const filas = () => Array.from(el().querySelectorAll<HTMLElement>('[role="option"]'));

  async function escribir(valor: string) {
    campo().value = valor;
    campo().dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function enter() {
    campo().dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
    );
    await refrescar();
  }

  async function avanzar(ms: number) {
    vi.advanceTimersByTime(ms);
    await refrescar();
  }

  async function responder(indice: number, resultado: BusquedaMostrador) {
    llamadas[indice].respuesta.next(resultado);
    llamadas[indice].respuesta.complete();
    await refrescar();
  }

  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
  });
  afterEach(() => vi.useRealTimers());

  const renglonesTicket = () =>
    Array.from(el().querySelectorAll<HTMLElement>('app-ticket-lista .renglon'));

  describe('búsqueda', () => {
    it('tiene el foco en el campo al abrir la pantalla', async () => {
      await crear();

      expect(document.activeElement).toBe(campo());
    });

    it('muestra la botonera con los atajos en lugar de la pista fija (R-038)', async () => {
      await crear();

      const botones = Array.from(el().querySelectorAll('app-botonera button')).map((b) =>
        b.textContent!.replace(/\s+/g, ' ').trim(),
      );
      expect(botones).toEqual([
        'F2 Buscar',
        'F3 Cantidad',
        'F7 Descuento',
        'F6 Dto. total',
        'F4 Cobrar',
        'F8 Presupuesto',
        'Remito',
        'F10 Quitar',
        'Vaciar',
      ]);
      expect(el().textContent).not.toContain('F2 buscar · Esc limpiar');
    });

    it('la botonera va en la barra de acciones de arriba, con el título de la pantalla', async () => {
      await crear();

      const barra = el().querySelector('app-barra-acciones')!;
      expect(barra).not.toBeNull();
      expect(barra.querySelector('h1')?.textContent?.trim()).toBe('Mostrador');
      expect(barra.querySelector('app-botonera [role="toolbar"]')?.getAttribute('aria-label')).toBe(
        'Acciones del mostrador',
      );
    });

    it('al tipear busca tras 250 ms sin teclear, una sola vez y con el último texto', async () => {
      await crear();

      await escribir('tor');
      await avanzar(100);
      await escribir('torn');
      await avanzar(249);
      expect(llamadas).toHaveLength(0);
      await avanzar(1);

      expect(llamadas.map((l) => l.q)).toEqual(['torn']);
    });

    it('Enter busca de inmediato y cancela el debounce: no hay llamada duplicada', async () => {
      await crear();
      await escribir('7790001');

      await enter();
      expect(llamadas.map((l) => l.q)).toEqual(['7790001']);
      await avanzar(500);

      expect(llamadas).toHaveLength(1);
    });

    it('Enter con el campo vacío o en blanco no busca ni muestra error', async () => {
      await crear();

      await enter();
      await escribir('   ');
      await enter();
      await avanzar(500);

      expect(llamadas).toHaveLength(0);
      expect(el().querySelector('[role="alert"]')).toBeNull();
    });

    it('al borrar todo el texto limpia el resultado sin llamar al backend', async () => {
      await crear();
      await escribir('tor');
      await avanzar(250);
      await responder(0, lista([articulo(1), articulo(2)]));
      expect(filas()).toHaveLength(2);

      await escribir('');
      await avanzar(500);

      expect(filas()).toHaveLength(0);
      expect(llamadas).toHaveLength(1);
    });

    it('borrar el texto cancela la búsqueda pendiente', async () => {
      await crear();
      await escribir('tor');

      await escribir('');
      await avanzar(500);

      expect(llamadas).toHaveLength(0);
    });

    it('descarta una respuesta vieja que llega después de una búsqueda más nueva', async () => {
      await crear();
      await escribir('tor');
      await avanzar(250);
      await escribir('torn');
      await avanzar(250);
      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'torn']);

      await responder(1, lista([articulo(2, { descripcion: 'Tornillo' })]));
      await responder(0, lista([articulo(1, { descripcion: 'Tornado' }), articulo(3)]));

      expect(filas()).toHaveLength(1);
      expect(filas()[0].textContent).toContain('Tornillo');
    });
  });

  describe('escaneo con el lector sobre texto tipeado (R-016, decisión 14)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({
      tipo: 'EXACTO',
      items: [a],
      hayMas: false,
    });

    /** Tipea tecla por tecla (keydown + input) con `gap` ms entre una y otra. */
    async function teclear(texto: string, gap: number) {
      for (const caracter of texto) {
        vi.advanceTimersByTime(gap);
        campo().dispatchEvent(
          new KeyboardEvent('keydown', { key: caracter, bubbles: true, cancelable: true }),
        );
        campo().value += caracter;
        campo().dispatchEvent(new Event('input'));
        await refrescar();
      }
    }

    async function enterTras(gap: number) {
      vi.advanceTimersByTime(gap);
      await enter();
    }

    /** El vendedor tipeó «tor» despacio y ya está la LISTA en pantalla. */
    async function conListaDeTor() {
      await crear();
      await teclear('tor', 150);
      await avanzar(250);
      await responder(0, lista([articulo(1), articulo(2)]));
      expect(filas()).toHaveLength(2);
    }

    it('«tor» tipeado despacio, LISTA, y un escaneo rápido de 7790001 + Enter busca solo 7790001 y muestra la ficha', async () => {
      await conListaDeTor();

      await avanzar(1000);
      await teclear('7790001', 10);
      await enterTras(10);
      expect(llamadas.map((l) => l.q)).toEqual(['tor', '7790001']);
      await responder(1, exacto(articulo(7, { descripcion: 'Tornillo 1/4' })));

      expect(el().querySelector('app-ficha-articulo')!.textContent).toContain('Tornillo 1/4');
      expect(filas()).toHaveLength(0);
      expect(campo().value).toBe('7790001');
      expect(campo().selectionStart).toBe(0);
      expect(campo().selectionEnd).toBe('7790001'.length);
    });

    it('con otro texto previo y otro código también reemplaza (triangulación)', async () => {
      await crear();
      await teclear('martillo', 120);
      await avanzar(300);
      await responder(0, lista([articulo(1)]));

      await avanzar(2000);
      await teclear('TOR-014', 8);
      await enterTras(8);

      expect(llamadas.map((l) => l.q)).toEqual(['martillo', 'TOR-014']);
    });

    it('no hay llamadas intermedias ni duplicadas por el debounce durante la ráfaga', async () => {
      await conListaDeTor();

      await avanzar(1000);
      await teclear('7790001', 10);
      await enterTras(10);
      await avanzar(1000);

      expect(llamadas.map((l) => l.q)).toEqual(['tor', '7790001']);
    });

    it('la respuesta tardía de una búsqueda anterior en vuelo no pisa el resultado del escaneo', async () => {
      await crear();
      await teclear('tor', 150);
      await avanzar(250);
      expect(llamadas.map((l) => l.q)).toEqual(['tor']);

      await avanzar(500);
      await teclear('7790001', 10);
      await enterTras(10);
      await responder(1, exacto(articulo(7, { descripcion: 'Tornillo 1/4' })));
      await responder(0, lista([articulo(1), articulo(2)]));

      expect(llamadas.map((l) => l.q)).toEqual(['tor', '7790001']);
      expect(el().querySelector('app-ficha-articulo')!.textContent).toContain('Tornillo 1/4');
      expect(filas()).toHaveLength(0);
    });

    it('tipeo humano de «7790001» + Enter sobre «tor» no se toca: busca «tor7790001»', async () => {
      await conListaDeTor();

      await avanzar(1000);
      await teclear('7790001', 120);
      await enterTras(120);

      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'tor7790001']);
    });

    it('un escaneo con el campo vacío sigue buscando solo el código', async () => {
      await crear();

      await teclear('7790001', 10);
      await enterTras(10);

      expect(llamadas.map((l) => l.q)).toEqual(['7790001']);
    });
  });

  describe('ficha y lista con teclado', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({
      tipo: 'EXACTO',
      items: [a],
      hayMas: false,
    });

    const flecha = async (key: 'ArrowDown' | 'ArrowUp') => {
      campo().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      await refrescar();
    };

    const resaltada = () => filas().findIndex((f) => f.getAttribute('aria-selected') === 'true');
    const ficha = () => el().querySelector('app-ficha-articulo');

    async function buscarLista(items: ArticuloMostrador[], q = 'tor') {
      await escribir(q);
      await enter();
      await responder(llamadas.length - 1, lista(items));
    }

    it('un escaneo con Enter hace una llamada y muestra la ficha directa para un EXACTO', async () => {
      await crear();
      await escribir('7790001');

      await enter();
      await responder(0, exacto(articulo(1, { descripcion: 'Tornillo 1/4', precioFinal: 1234.5 })));

      expect(llamadas.map((l) => l.q)).toEqual(['7790001']);
      expect(ficha()).not.toBeNull();
      expect(ficha()!.textContent).toContain('Tornillo 1/4');
      expect(ficha()!.textContent).toContain('1.234,50');
      expect(filas()).toHaveLength(0);
      // CU-07: el escaneo con Enter además agrega 1 unidad al ticket.
      expect(renglonesTicket()).toHaveLength(1);
      expect(renglonesTicket()[0].textContent).toContain('Tornillo 1/4');
    });

    it('tras un EXACTO por Enter deja el texto seleccionado para que el próximo escaneo lo reemplace', async () => {
      await crear();
      await escribir('7790001');
      await enter();

      await responder(0, exacto(articulo(1)));

      expect(campo().selectionStart).toBe(0);
      expect(campo().selectionEnd).toBe('7790001'.length);
    });

    it('una LISTA muestra las filas con precio, stock y ubicación y la primera resaltada', async () => {
      await crear();

      await buscarLista([
        articulo(1, {
          precioFinal: 1234.5,
          stockActual: 2.5,
          unidad: 'METRO',
          ubicacion: 'Pasillo 2',
        }),
        articulo(2),
        articulo(3),
      ]);

      expect(filas()).toHaveLength(3);
      expect(resaltada()).toBe(0);
      expect(filas()[0].textContent).toContain('1.234,50');
      expect(filas()[0].textContent).toContain('2,5 metros');
      expect(filas()[0].textContent).toContain('Pasillo 2');
      // La ficha acompaña a la fila resaltada (H-17).
      expect(ficha()).not.toBeNull();
    });

    it('flecha abajo dos veces y Enter abren la ficha de la tercera fila', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2), articulo(3, { descripcion: 'Tercero' })]);

      await flecha('ArrowDown');
      await flecha('ArrowDown');
      expect(resaltada()).toBe(2);
      await enter();

      expect(ficha()!.textContent).toContain('Tercero');
      expect(llamadas).toHaveLength(1);
      expect(filas()).toHaveLength(0);
    });

    it('flecha arriba en la primera fila y flecha abajo en la última se quedan donde están', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)]);

      await flecha('ArrowUp');
      expect(resaltada()).toBe(0);
      await flecha('ArrowDown');
      await flecha('ArrowDown');
      expect(resaltada()).toBe(1);
      await flecha('ArrowUp');

      expect(resaltada()).toBe(0);
    });

    it('con una sola fila (LISTA de uno) la muestra resaltada y Enter abre la ficha', async () => {
      await crear();
      await buscarLista([articulo(7, { descripcion: 'Martillo', ubicacion: 'Estante 3' })]);

      expect(filas()).toHaveLength(1);
      expect(resaltada()).toBe(0);
      expect(filas()[0].textContent).toContain('Estante 3');
      expect(ficha()).not.toBeNull();
      await enter();

      expect(ficha()!.textContent).toContain('Martillo');
    });

    it('si se editó el texto desde la última búsqueda, Enter busca de nuevo en vez de abrir la fila', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)], 'tor');

      await escribir('torn');
      await enter();

      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'torn']);
      // No abrió ninguna fila: sigue la lista (con su resaltada en la ficha) y nada entró al ticket.
      expect(filas()).toHaveLength(2);
      expect(renglonesTicket()).toHaveLength(0);
    });

    it('con la ficha abierta las flechas no mueven nada', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)]);
      await enter();
      expect(ficha()).not.toBeNull();

      await flecha('ArrowDown');
      await flecha('ArrowDown');
      await flecha('ArrowUp');

      expect(ficha()).not.toBeNull();
      expect(filas()).toHaveLength(0);
    });

    it('con la ficha abierta, Enter vuelve a buscar el mismo texto para refrescar el stock', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)]);
      await enter();

      await enter();

      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'tor']);
    });

    it('el foco queda siempre en el campo al buscar, mover y abrir', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)]);
      expect(document.activeElement).toBe(campo());

      await flecha('ArrowDown');
      expect(document.activeElement).toBe(campo());
      await enter();

      expect(document.activeElement).toBe(campo());
    });

    it('un clic en una fila abre su ficha', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2, { descripcion: 'Segundo' })]);

      filas()[1].click();
      await refrescar();

      expect(ficha()!.textContent).toContain('Segundo');
    });
  });

  describe('ticket: Enter y escaneos (CU-07, R-023 a R-026)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({
      tipo: 'EXACTO',
      items: [a],
      hayMas: false,
    });
    const sinResultados: BusquedaMostrador = { tipo: 'SIN_RESULTADOS', items: [], hayMas: false };
    const mensaje = () => el().querySelector('.mensaje')?.textContent?.trim() ?? null;

    const flecha = async (key: 'ArrowDown' | 'ArrowUp') => {
      campo().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      await refrescar();
    };

    /** Escanea `codigo`: lo escribe, Enter, y el servidor responde `resultado`. */
    async function escanear(codigo: string, resultado: BusquedaMostrador) {
      await escribir(codigo);
      await enter();
      await responder(llamadas.length - 1, resultado);
    }

    const descripciones = () =>
      renglonesTicket().map((r) => r.querySelector('.descripcion')!.textContent!.trim());
    const cantidades = () =>
      renglonesTicket().map((r) => r.querySelector('.cantidad')!.textContent!.trim());

    it('«7790001» + Enter deja 1 renglón de 1 unidad', async () => {
      await crear();

      await escanear('7790001', exacto(articulo(1, { descripcion: 'Tornillo' })));

      expect(descripciones()).toEqual(['Tornillo']);
      expect(cantidades()).toEqual(['1']);
    });

    it('un EXACTO que llega por el debounce al tipear no agrega nada al ticket', async () => {
      await crear();

      await escribir('TOR-014');
      await avanzar(250);
      await responder(0, exacto(articulo(1)));

      expect(el().querySelector('app-ficha-articulo')).not.toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
    });

    it('una lista de 3 filas, flecha abajo y Enter agregan 1 unidad de la 2ª fila', async () => {
      await crear();
      await escribir('tor');
      await enter();
      await responder(0, lista([articulo(1), articulo(2, { descripcion: 'Segundo' }), articulo(3)]));

      await flecha('ArrowDown');
      await enter();

      expect(descripciones()).toEqual(['Segundo']);
      expect(cantidades()).toEqual(['1']);
      expect(llamadas).toHaveLength(1);
    });

    it('un clic en una fila de la lista también la agrega al ticket', async () => {
      await crear();
      await escribir('tor');
      await enter();
      await responder(0, lista([articulo(1), articulo(2, { descripcion: 'Segundo' })]));

      filas()[1].click();
      await refrescar();

      expect(descripciones()).toEqual(['Segundo']);
    });

    it('Enter con el debounce pendiente es una sola llamada y un solo renglón', async () => {
      await crear();
      await escribir('7790001');

      await enter();
      await avanzar(500);
      await responder(0, exacto(articulo(1)));
      await avanzar(500);

      expect(llamadas).toHaveLength(1);
      expect(renglonesTicket()).toHaveLength(1);
      expect(cantidades()).toEqual(['1']);
    });

    it('el mismo código 3 veces es 1 renglón de 3 unidades', async () => {
      await crear();

      await escanear('7790001', exacto(articulo(1)));
      await escanear('7790001', exacto(articulo(1)));
      await escanear('7790001', exacto(articulo(1)));

      expect(renglonesTicket()).toHaveLength(1);
      expect(cantidades()).toEqual(['3']);
    });

    it('dos escaneos seguidos (20 ms) quedan en orden [A, B] aunque B esté listo antes', async () => {
      await crear();

      await escribir('A');
      await enter();
      vi.advanceTimersByTime(20);
      await escribir('B');
      await enter();
      // El escaneo de B espera su turno: no se pierde ni cancela al de A.
      expect(llamadas.map((l) => l.q)).toEqual(['A']);
      await responder(0, exacto(articulo(1, { descripcion: 'A' })));
      expect(llamadas.map((l) => l.q)).toEqual(['A', 'B']);
      await responder(1, exacto(articulo(2, { descripcion: 'B' })));

      expect(descripciones()).toEqual(['A', 'B']);
      expect(cantidades()).toEqual(['1', '1']);
    });

    it('5 escaneos seguidos de artículos distintos dan 5 renglones', async () => {
      await crear();
      for (let i = 1; i <= 5; i++) {
        await escribir(`COD${i}`);
        await enter();
      }

      for (let i = 0; i < 5; i++) {
        await responder(i, exacto(articulo(i + 1)));
      }

      expect(llamadas).toHaveLength(5);
      expect(descripciones()).toEqual([1, 2, 3, 4, 5].map((i) => `Artículo ${i}`));
    });

    it('el mismo artículo dos veces seguidas, sin esperar la respuesta, da 1 renglón de 2 unidades', async () => {
      await crear();
      await escribir('7790001');
      await enter();
      await enter();

      await responder(0, exacto(articulo(1)));
      await responder(1, exacto(articulo(1)));

      expect(renglonesTicket()).toHaveLength(1);
      expect(cantidades()).toEqual(['2']);
    });

    it('un escaneo cuya respuesta llega tarde, después de tipear otra cosa, igual se agrega', async () => {
      await crear();
      await escribir('7790001');
      await enter();

      await escribir('torn');
      await responder(0, exacto(articulo(1, { descripcion: 'Tornillo' })));

      expect(descripciones()).toEqual(['Tornillo']);
      // La pantalla es de lo último que se tipeó: la ficha del escaneo viejo no la pisa.
      expect(el().querySelector('app-ficha-articulo')).toBeNull();
    });

    it('un dado de baja elegido de la lista no se agrega y avisa en rojo', async () => {
      await crear();
      await escanear('A', exacto(articulo(1)));

      await escanear('BAJA', lista([articulo(9, { descripcion: 'Taladro viejo', activo: false })]));
      await enter();

      expect(renglonesTicket()).toHaveLength(1);
      const aviso = el().querySelector('.aviso')!;
      expect(aviso.textContent!.trim()).toBe('"Taladro viejo" está dado de baja.');
      expect(aviso.classList.contains('error')).toBe(true);
      expect(aviso.getAttribute('role')).toBe('alert');
    });

    it('un EXACTO dado de baja (no debería llegar) tampoco se agrega', async () => {
      await crear();

      await escanear('BAJA', exacto(articulo(9, { descripcion: 'Taladro viejo', activo: false })));

      expect(renglonesTicket()).toHaveLength(0);
      expect(el().querySelector('.aviso')!.textContent!.trim()).toBe('"Taladro viejo" está dado de baja.');
    });

    it('un artículo a $0 no se agrega y avisa en rojo', async () => {
      await crear();

      await escanear('ARA', lista([articulo(8, { descripcion: 'Arandela suelta', precioFinal: 0 })]));
      await enter();

      expect(renglonesTicket()).toHaveLength(0);
      const aviso = el().querySelector('.aviso')!;
      expect(aviso.textContent!.trim()).toBe('"Arandela suelta" no tiene precio.');
      expect(aviso.classList.contains('error')).toBe(true);
    });

    it('después del aviso de baja, agregar un activo lo saca', async () => {
      await crear();
      await escanear('BAJA', lista([articulo(9, { descripcion: 'Taladro viejo', activo: false })]));
      await enter();

      await escanear('A', exacto(articulo(1)));

      expect(renglonesTicket()).toHaveLength(1);
      expect(el().querySelector('.aviso')).toBeNull();
    });

    it('SIN_RESULTADOS no agrega y muestra el mensaje de no encontrado', async () => {
      await crear();
      await escanear('A', exacto(articulo(1)));

      await escanear('NADA', sinResultados);

      expect(renglonesTicket()).toHaveLength(1);
      expect(mensaje()).toBe('No encontramos ningún artículo con ese código o descripción.');
    });

    it('un error 5xx al escanear no agrega, avisa y el próximo escaneo anda', async () => {
      await crear();
      await escanear('A', exacto(articulo(1)));
      await escribir('B');
      await enter();

      llamadas[1].respuesta.error(new HttpErrorResponse({ status: 500 }));
      await refrescar();
      expect(renglonesTicket()).toHaveLength(1);
      expect(el().querySelector('[role="alert"]')!.textContent).toContain(
        'No se pudo buscar. Probá de nuevo.',
      );

      await escanear('C', exacto(articulo(3)));

      expect(renglonesTicket()).toHaveLength(2);
    });

    it('sin lista a la vista, las flechas mueven el renglón elegido del ticket', async () => {
      await crear();
      await escanear('A', exacto(articulo(1)));
      await escanear('B', exacto(articulo(2)));
      const elegido = () =>
        renglonesTicket().findIndex((r) => r.getAttribute('aria-current') === 'true');
      expect(elegido()).toBe(1);

      await flecha('ArrowUp');
      expect(elegido()).toBe(0);
      await flecha('ArrowUp');
      expect(elegido()).toBe(0);
      await flecha('ArrowDown');

      expect(elegido()).toBe(1);
    });

    it('un renglón sin stock suficiente muestra la advertencia en el ticket', async () => {
      await crear();

      await escanear('A', exacto(articulo(1, { stockActual: 0 })));

      expect(renglonesTicket()[0].textContent).toContain('Sin stock suficiente');
    });

    it('el ticket total aparece en el formato de montos', async () => {
      await crear();

      await escanear('A', exacto(articulo(1, { precioFinal: 1210 })));
      await escanear('A', exacto(articulo(1, { precioFinal: 1210 })));

      expect(el().querySelector('.total')!.textContent!.replace(/\s/g, ' ')).toContain(
        '$ 2.420,00',
      );
    });

    it('debajo del total cuenta renglones (no «artículos»: 2 unidades + 2,5 m no son 3 de nada)', async () => {
      await crear();

      await escanear('A', exacto(articulo(1)));
      await escanear('A', exacto(articulo(1)));
      await escanear('B', exacto(articulo(2)));

      expect(el().querySelector('.conteo')!.textContent!.replace(/\s+/g, ' ').trim()).toBe(
        '2 renglones',
      );
    });
  });

  describe('teclas, diálogos y foco (CU-07, R-028 a R-032, R-038, R-039)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({
      tipo: 'EXACTO',
      items: [a],
      hayMas: false,
    });

    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const dentroDelDialogo = () => !!dialogo()?.contains(document.activeElement);
    const total = () => el().querySelector('.total')!.textContent!.replace(/\s/g, ' ').trim();
    const descripciones = () =>
      renglonesTicket().map((r) => r.querySelector('.descripcion')!.textContent!.trim());
    const cantidades = () =>
      renglonesTicket().map((r) => r.querySelector('.cantidad')!.textContent!.trim());
    const botonera = (etiqueta: string) =>
      Array.from(el().querySelectorAll<HTMLButtonElement>('app-botonera button')).find((b) =>
        b.textContent!.includes(etiqueta),
      )!;
    const botonDelDialogo = (texto: string) =>
      Array.from(dialogo()!.querySelectorAll<HTMLButtonElement>('button')).find(
        (b) => b.textContent!.trim() === texto,
      )!;

    async function tecla(key: string, destino: HTMLElement = document.activeElement as HTMLElement) {
      const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      destino.dispatchEvent(evento);
      await refrescar();
      return evento;
    }

    async function escanear(codigo: string, a: ArticuloMostrador) {
      await escribir(codigo);
      await enter();
      await responder(llamadas.length - 1, exacto(a));
    }

    /** Ticket con «Artículo 1» ($ 100) y «Artículo 2» ($ 200,50), elegido el último. */
    async function conTicket() {
      await crear();
      await escanear('A', articulo(1, { precioFinal: 100 }));
      await escanear('B', articulo(2, { precioFinal: 200.5, unidad: 'METRO' }));
    }

    async function escribirEnDialogo(valor: string) {
      const input = dialogo()!.querySelector('input')!;
      input.value = valor;
      input.dispatchEvent(new Event('input'));
      await refrescar();
    }

    it('F3 con el ticket vacío no hace nada, pero anula la acción del navegador', async () => {
      await crear();

      const evento = await tecla('F3');

      expect(dialogo()).toBeNull();
      expect(evento.defaultPrevented).toBe(true);
    });

    it('F3 abre la cantidad del último renglón con el foco adentro y la cantidad preseleccionada', async () => {
      await conTicket();

      await tecla('F3');

      expect(dialogo()!.textContent).toContain('Artículo 2');
      expect(dentroDelDialogo()).toBe(true);
      expect(dialogo()!.querySelector('input')!.value).toBe('1');
    });

    it('F3 + 4 + Enter cambia la cantidad y recalcula el total', async () => {
      await conTicket();
      await tecla('F3');

      await escribirEnDialogo('4');
      await tecla('Enter');

      expect(dialogo()).toBeNull();
      expect(cantidades()).toEqual(['1', '4 m']);
      expect(total()).toBe('$ 902,00');
    });

    it('F3 usa el renglón elegido con las flechas, no el último', async () => {
      await conTicket();
      await tecla('ArrowUp', campo());

      await tecla('F3');

      expect(dialogo()!.textContent).toContain('Artículo 1');
    });

    it('una cantidad inválida no cierra el diálogo ni cambia el ticket', async () => {
      await conTicket();
      await tecla('F3');

      await escribirEnDialogo('0');
      await tecla('Enter');

      expect(dialogo()).not.toBeNull();
      expect(dialogo()!.textContent).toContain('La cantidad tiene que ser mayor que 0.');
      expect(cantidades()).toEqual(['1', '1 m']);
    });

    it('F3 + Esc cancela sin cambiar la cantidad', async () => {
      await conTicket();
      await tecla('F3');
      await escribirEnDialogo('9');

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(cantidades()).toEqual(['1', '1 m']);
    });

    it('F10 + Enter quita el renglón y baja el total', async () => {
      await conTicket();

      const evento = await tecla('F10');
      expect(evento.defaultPrevented).toBe(true);
      expect(dialogo()!.textContent).toContain('¿Quitar «Artículo 2» del ticket?');
      expect(dentroDelDialogo()).toBe(true);
      await tecla('Enter');

      expect(dialogo()).toBeNull();
      expect(descripciones()).toEqual(['Artículo 1']);
      expect(total()).toBe('$ 100,00');
    });

    it('F10 + Esc no quita nada', async () => {
      await conTicket();
      await tecla('F10');

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(descripciones()).toEqual(['Artículo 1', 'Artículo 2']);
    });

    it('F10 con el ticket vacío no abre nada y anula la acción del navegador', async () => {
      await crear();

      const evento = await tecla('F10');

      expect(dialogo()).toBeNull();
      expect(evento.defaultPrevented).toBe(true);
    });

    it('«Vaciar» pide confirmación con el foco en Cancelar; cancelar no cambia nada', async () => {
      await conTicket();

      botonera('Vaciar').click();
      await refrescar();

      expect(dialogo()!.querySelector('h2')!.textContent).toContain('¿Vaciar el ticket?');
      expect(dialogo()!.textContent).toContain('Se pierden 2 renglones.');
      expect(document.activeElement).toBe(botonDelDialogo('Cancelar'));
      botonDelDialogo('Cancelar').click();
      await refrescar();
      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(2);
    });

    it('confirmar «Vaciar» limpia el ticket y el localStorage y renueva la clave', async () => {
      await conTicket();
      expect(JSON.parse(localStorage.getItem('ticket:3:7')!).clave).toBe('clave-1');

      botonera('Vaciar').click();
      await refrescar();
      botonDelDialogo('Vaciar').click();
      await refrescar();

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
      expect(localStorage.getItem('ticket:3:7')).toBeNull();
      await escanear('C', articulo(3));
      expect(JSON.parse(localStorage.getItem('ticket:3:7')!).clave).toBe('clave-2');
    });

    it('ninguna tecla (Esc, Supr, Backspace, las F) vacía el ticket ni abre «Vaciar»', async () => {
      await conTicket();
      const teclas = ['Escape', 'Delete', 'Backspace', ...Array.from({ length: 12 }, (_, i) => `F${i + 1}`)];

      for (const key of teclas) {
        await tecla(key, campo());
        // Los diálogos que abren F3, F4 y F10 se cierran con Esc, que tampoco toca el ticket.
        if (dialogo()) {
          expect(dialogo()!.textContent).not.toContain('Vaciar el ticket');
          await tecla('Escape');
        }
      }

      expect(descripciones()).toEqual(['Artículo 1', 'Artículo 2']);
    });

    it('Esc sin diálogo limpia el buscador y 5 Esc seguidos dejan el ticket intacto', async () => {
      await conTicket();
      await escribir('algo');

      for (let i = 0; i < 5; i++) await tecla('Escape', campo());

      expect(campo().value).toBe('');
      expect(descripciones()).toEqual(['Artículo 1', 'Artículo 2']);
      expect(total()).toBe('$ 300,50');
    });

    it('Esc con un diálogo abierto lo cierra primero y no toca el buscador', async () => {
      await conTicket();
      await escribir('algo');
      await tecla('F4', campo());
      expect(dialogo()).not.toBeNull();

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(campo().value).toBe('algo');
      expect(renglonesTicket()).toHaveLength(2);
    });

    it('con un diálogo abierto, F2, F3, F4 y F10 se anulan pero no hacen nada', async () => {
      await conTicket();
      await tecla('F3');

      for (const key of ['F2', 'F3', 'F4', 'F10']) {
        const evento = await tecla(key);
        expect(evento.defaultPrevented).toBe(true);
      }

      expect(el().querySelectorAll('[role="dialog"], [role="alertdialog"]')).toHaveLength(1);
      expect(dialogo()!.textContent).toContain('Cantidad de Artículo 2');
      expect(dentroDelDialogo()).toBe(true);
    });

    it('al cerrar un diálogo el foco vuelve al buscador', async () => {
      await conTicket();
      await tecla('F10');
      expect(dentroDelDialogo()).toBe(true);

      await tecla('Escape');

      expect(document.activeElement).toBe(campo());
    });

    it('el foco que el buscador recupera con retraso no le roba el foco al diálogo recién abierto (H-18)', async () => {
      await conTicket();
      // El buscador pierde el foco sin destino: programa recuperarlo en el próximo turno.
      campo().blur();
      await tecla('F10');
      expect(dentroDelDialogo()).toBe(true);

      await avanzar(5);

      expect(dentroDelDialogo()).toBe(true);
      expect(document.activeElement).not.toBe(campo());
      await tecla('Enter');
      expect(dialogo()).toBeNull();
      expect(descripciones()).toEqual(['Artículo 1']);
    });

    it('Esc, ↓ y F10 seguidos dejan el foco en el diálogo y Enter confirma (H-18)', async () => {
      await conTicket();
      await tecla('ArrowUp', campo());

      await tecla('Escape', campo());
      await tecla('ArrowDown', campo());
      await tecla('F10');
      await avanzar(5);

      expect(dentroDelDialogo()).toBe(true);
      await tecla('Enter');
      expect(dialogo()).toBeNull();
      expect(descripciones()).toEqual(['Artículo 1']);
    });

    it('un escaneo con un diálogo abierto se descarta y avisa', async () => {
      await conTicket();
      await tecla('F3');
      const antes = llamadas.length;
      const input = dialogo()!.querySelector('input')!;

      for (const c of '7790001') {
        vi.advanceTimersByTime(5);
        input.dispatchEvent(new KeyboardEvent('keydown', { key: c, bubbles: true, cancelable: true }));
        input.value += c;
      }
      vi.advanceTimersByTime(5);
      await tecla('Enter', input);

      expect(el().textContent).toContain('Cerrá el diálogo antes de escanear.');
      expect(llamadas).toHaveLength(antes);
      expect(renglonesTicket()).toHaveLength(2);
      expect(cantidades()).toEqual(['1', '1 m']);
      expect(dialogo()).not.toBeNull();
    });

    it('una respuesta que llega con el diálogo abierto no le roba el foco', async () => {
      await conTicket();
      await escribir('C');
      await enter();
      await tecla('F3', campo());

      await responder(llamadas.length - 1, exacto(articulo(3)));

      expect(dentroDelDialogo()).toBe(true);
      expect(renglonesTicket()).toHaveLength(3);
    });

    it('la botonera dispara lo mismo que la tecla', async () => {
      await conTicket();

      botonera('F3').click();
      await refrescar();
      expect(dialogo()!.textContent).toContain('Cantidad de Artículo 2');
      await tecla('Escape');

      botonera('F10').click();
      await refrescar();
      expect(dialogo()!.textContent).toContain('¿Quitar «Artículo 2» del ticket?');
      await tecla('Escape');

      botonera('F4').click();
      await refrescar();
      expect(dialogo()!.textContent).toContain('Cobrar');
      await tecla('Escape');

      botonera('F2').click();
      await refrescar();
      expect(document.activeElement).toBe(campo());
    });

    it('con un diálogo abierto Tab no sale de él, y al cerrarlo el foco vuelve al código y no al botón que lo abrió', async () => {
      await conTicket();
      botonera('F4').focus();
      botonera('F4').click();
      await refrescar();

      const cobrar = botonDelDialogo('Cobrar');
      cobrar.focus();
      await tecla('Tab');
      expect(dentroDelDialogo()).toBe(true);

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(document.activeElement).toBe(campo());
    });

    it('con el ticket vacío la botonera deshabilita cantidad, cobrar, quitar y vaciar', async () => {
      await crear();

      expect(botonera('F3').disabled).toBe(true);
      expect(botonera('F4').disabled).toBe(true);
      expect(botonera('F10').disabled).toBe(true);
      expect(botonera('Vaciar').disabled).toBe(true);
      expect(botonera('F2').disabled).toBe(false);
    });
  });

  describe('cobro (CU-07, R-034, R-035)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({
      tipo: 'EXACTO',
      items: [a],
      hayMas: false,
    });

    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const avisos = () => normal(el().querySelector('.aviso')?.textContent);
    const descripciones = () =>
      renglonesTicket().map((r) => r.querySelector('.descripcion')!.textContent!.trim());
    const botonDelDialogo = (texto: string) =>
      Array.from(dialogo()!.querySelectorAll<HTMLButtonElement>('button')).find(
        (b) => b.textContent!.trim() === texto,
      )!;

    async function tecla(key: string, destino: HTMLElement = document.activeElement as HTMLElement) {
      destino.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      await refrescar();
    }

    async function escanear(codigo: string, a: ArticuloMostrador) {
      await escribir(codigo);
      await enter();
      await responder(llamadas.length - 1, exacto(a));
    }

    const martillo = (cambios: Partial<ArticuloMostrador> = {}) =>
      articulo(1, { descripcion: 'Martillo', precioFinal: 1500, ...cambios });

    /** Ticket de 2 martillos ($ 1.500,00 c/u = $ 3.000,00) listo para cobrar. */
    async function conMartillos() {
      await crear();
      await escanear('M', martillo());
      await escanear('M', martillo());
    }

    function venta(numero: number, total: number, sinStock: VentaCobrada['venta']['sinStock'] = []) {
      return {
        venta: {
          ventaId: 99,
          numero,
          total,
          fecha: '2026-10-15T10:00:00',
          medioPago: 'EFECTIVO',
          renglones: [],
          sinStock,
        },
        repetida: false,
      } as VentaCobrada;
    }

    async function cobrarOk(indice: number, v: VentaCobrada) {
      cobros[indice].respuesta.next(v);
      cobros[indice].respuesta.complete();
      await refrescar();
    }

    async function fallarCobro(indice: number, status: number, cuerpo: unknown = null) {
      cobros[indice].respuesta.error(new HttpErrorResponse({ status, error: cuerpo }));
      await refrescar();
    }

    const rechazo = (status: number, motivo: string, articulos: unknown[], extra = {}) => ({
      status,
      message: 'rechazo',
      timestamp: '2026-10-15T10:00:00',
      motivo,
      articulos,
      ...extra,
    });

    it('F4 + Enter envía la venta en efectivo con la clave del ticket', async () => {
      await conMartillos();

      await tecla('F4');
      await tecla('Enter');

      expect(cobros).toHaveLength(1);
      expect(cobros[0].pedido).toEqual({
        clave: 'clave-1',
        renglones: [{ articuloId: 1, cantidad: 2, precioUnitario: 1500 }],
        totalMostrado: 3000,
        formaPagoId: 1,
      });
    });

    it('el diálogo de cobro muestra cuántos renglones tiene el ticket', async () => {
      await conMartillos();

      await tecla('F4');

      expect(normal(dialogo()!.querySelector('.conteo')?.textContent)).toBe('1 renglón');
    });

    it('F4, → y Enter venden con DÉBITO', async () => {
      await conMartillos();

      await tecla('F4');
      await tecla('ArrowRight');
      await tecla('Enter');

      expect(cobros[0].pedido.formaPagoId).toBe(2);
    });

    describe('formas de pago con recargo (GDS-11)', () => {
      const visa6 = formaDe(7, 'Visa 6 cuotas', 'CREDITO', 10);
      const conVisa = [...FORMAS_BASE, visa6];

      /** Ticket de 2 martillos ($ 3.000,00) con esas formas. */
      async function conMartillosYFormas(formas: FormaPago[]) {
        await crear(cajaAbierta, formas);
        await escanear('M', martillo());
        await escanear('M', martillo());
      }

      const opcionesDelCobro = () =>
        Array.from(dialogo()!.querySelectorAll('button[role="radio"]')).map((b) => normal(b.textContent));

      function elegirOpcion(texto: string) {
        Array.from(dialogo()!.querySelectorAll<HTMLButtonElement>('button[role="radio"]'))
          .find((b) => b.textContent!.includes(texto))!
          .click();
      }

      it('lee las formas activas al abrir y las ofrece en el cobro, en su orden', async () => {
        await conMartillosYFormas(conVisa);

        await tecla('F4');

        expect(formasApi.listar).toHaveBeenCalledWith(true);
        expect(opcionesDelCobro()).toEqual([
          'Efectivo',
          'Débito',
          'Crédito',
          'Transferencia',
          'Visa 6 cuotas +10 %',
          'Mixto',
        ]);
      });

      it('F4 vuelve a leer las formas: si el Dueño cambió un recargo, el cobro ya lo trae', async () => {
        await conMartillosYFormas(conVisa);
        formasApi.listar.mockReturnValue(of([...FORMAS_BASE, formaDe(7, 'Visa 6 cuotas', 'CREDITO', 25)]));

        await tecla('F4');
        elegirOpcion('Visa 6 cuotas');
        await refrescar();

        expect(formasApi.listar).toHaveBeenCalledTimes(2);
        expect(normal(dialogo()!.querySelector('.total')?.textContent)).toBe('$ 3.750,00');
      });

      it('sin formas cargadas (no se pudieron leer) F4 avisa, no abre el cobro y las vuelve a pedir', async () => {
        await crear(cajaAbierta, []);
        await escanear('M', martillo());

        await tecla('F4');

        expect(dialogo()).toBeNull();
        expect(normal(el().textContent)).toContain('No se pudieron cargar las formas de pago.');
        expect(formasApi.listar).toHaveBeenCalledTimes(2);
      });

      it('con una forma con recargo manda su id y el total mostrado con el recargo, sin tocar los renglones', async () => {
        await conMartillosYFormas(conVisa);

        await tecla('F4');
        elegirOpcion('Visa 6 cuotas');
        await refrescar();
        expect(normal(dialogo()!.querySelector('.total')?.textContent)).toBe('$ 3.300,00');
        await tecla('Enter');

        expect(cobros).toHaveLength(1);
        expect(cobros[0].pedido).toEqual({
          clave: 'clave-1',
          renglones: [{ articuloId: 1, cantidad: 2, precioUnitario: 1500 }],
          totalMostrado: 3300,
          formaPagoId: 7,
        });
      });

      it('el aviso de venta cobrada muestra el total con el recargo', async () => {
        await conMartillosYFormas(conVisa);
        await tecla('F4');
        elegirOpcion('Visa 6 cuotas');
        await refrescar();
        await tecla('Enter');

        await cobrarOk(0, venta(12, 3300));

        expect(normal(el().textContent)).toContain('Venta N.º 12 cobrada: $ 3.300,00');
      });

      it('el pago mixto manda las partes sin recargo y el total mostrado con el recargo de la parte que lo tiene', async () => {
        await conMartillosYFormas(conVisa);
        await tecla('F4');
        elegirOpcion('Mixto');
        await refrescar();
        const cargar = (id: number, texto: string) => {
          const campo = dialogo()!.querySelector<HTMLInputElement>(`input[data-forma="${id}"]`)!;
          campo.value = texto;
          campo.dispatchEvent(new Event('input', { bubbles: true }));
        };
        cargar(1, '1000');
        cargar(7, '2000');
        await refrescar();

        await tecla('Enter');

        expect(cobros[0].pedido.formaPagoId).toBeUndefined();
        expect(cobros[0].pedido.pagos).toEqual([
          { formaPagoId: 1, monto: 1000 },
          { formaPagoId: 7, monto: 2000 },
        ]);
        expect(cobros[0].pedido.totalMostrado).toBe(3200);
      });

      it('un 409 vuelve a leer las formas: el total pudo cambiar por un recargo nuevo', async () => {
        await conMartillosYFormas(conVisa);
        await tecla('F4');
        elegirOpcion('Visa 6 cuotas');
        await refrescar();
        await tecla('Enter');
        formasApi.listar.mockClear();

        await fallarCobro(0, 409, rechazo(409, 'PRECIOS_CAMBIADOS', [], { total: 3750 }));

        expect(formasApi.listar).toHaveBeenCalledTimes(1);
        expect(dialogo()).not.toBeNull();
      });

      it('un 400 por una forma de pago inválida vuelve a leer las formas y deja el cobro abierto con el mensaje', async () => {
        await conMartillosYFormas(conVisa);
        await tecla('F4');
        elegirOpcion('Visa 6 cuotas');
        await refrescar();
        await tecla('Enter');
        formasApi.listar.mockClear();
        formasApi.listar.mockReturnValue(of(FORMAS_BASE));

        await fallarCobro(0, 400, {
          status: 400,
          message: 'Datos inválidos',
          errors: { formaPagoId: 'La forma de pago no es válida.' },
        });

        expect(formasApi.listar).toHaveBeenCalledTimes(1);
        expect(normal(dialogo()!.textContent)).toContain('La forma de pago no es válida.');
        expect(opcionesDelCobro()).not.toContain('Visa 6 cuotas +10 %');
      });
    });

    it('un 201 cierra el diálogo, vacía el ticket y avisa con el número y el total', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await cobrarOk(0, venta(12, 3630));

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
      expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.630,00');
      expect(el().querySelector('.aviso')!.getAttribute('role')).toBe('status');
      expect(localStorage.getItem('ticket:3:7')).toBeNull();
      expect(document.activeElement).toBe(campo());
    });

    it('un escaneo que vuelve con el POST en vuelo queda en el ticket con clave nueva (W-1)', async () => {
      await conMartillos();
      await escribir('S');
      await enter();
      await tecla('F4');
      await tecla('Enter');
      expect(cobros).toHaveLength(1);

      await responder(
        llamadas.length - 1,
        exacto(articulo(2, { descripcion: 'Serrucho', precioFinal: 100 })),
      );
      await cobrarOk(0, venta(12, 3000));

      expect(descripciones()).toEqual(['Serrucho']);
      expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.000,00');
      const guardado = JSON.parse(localStorage.getItem('ticket:3:7')!);
      expect(guardado.clave).toBe('clave-2');
      expect(guardado.renglones.map((r: { articuloId: number }) => r.articuloId)).toEqual([2]);
    });

    it('si el escaneo en vuelo era del mismo artículo, queda solo la unidad de más (W-1)', async () => {
      await conMartillos();
      await escribir('M');
      await enter();
      await tecla('F4');
      await tecla('Enter');

      await responder(llamadas.length - 1, exacto(martillo()));
      await cobrarOk(0, venta(12, 3000));

      expect(descripciones()).toEqual(['Martillo']);
      expect(el().querySelector('app-ticket-lista .cantidad')!.textContent!.trim()).toBe('1');
      await tecla('F4');
      await tecla('Enter');
      expect(cobros[1].pedido.clave).toBe('clave-2');
      expect(cobros[1].pedido.renglones[0].cantidad).toBe(1);
    });

    describe('aviso de venta cobrada (se oculta a los 5 s)', () => {
      async function cobrarYa(indice: number, numero: number) {
        await tecla('F4');
        await tecla('Enter');
        await cobrarOk(indice, venta(numero, 3000));
      }

      it('se oculta a los 5 segundos y no antes', async () => {
        await conMartillos();
        await cobrarYa(0, 12);

        await avanzar(4999);
        expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.000,00');
        await avanzar(1);
        expect(el().querySelector('.aviso')).toBeNull();
      });

      it('escanear no lo borra antes de tiempo', async () => {
        await conMartillos();
        await cobrarYa(0, 12);

        await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
        await avanzar(2000);

        expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.000,00');
      });

      it('una venta nueva antes de los 5 s arranca su propio plazo', async () => {
        await conMartillos();
        await cobrarYa(0, 12);
        await avanzar(3000);
        await escanear('M', martillo());
        await cobrarYa(1, 13);

        await avanzar(3000);
        expect(avisos()).toBe('Venta N.º 13 cobrada: $ 3.000,00');
        await avanzar(2000);
        expect(el().querySelector('.aviso')).toBeNull();
      });

      it('el temporizador viejo no borra un aviso distinto que apareció después', async () => {
        await conMartillos();
        await cobrarYa(0, 12);
        await avanzar(2000);
        await tecla('F4');
        expect(avisos()).toBe('El ticket está vacío.');

        await avanzar(3500);

        expect(avisos()).toBe('El ticket está vacío.');
      });

      it('al destruir el componente no queda el temporizador', async () => {
        await conMartillos();
        await cobrarYa(0, 12);

        const pendientes = vi.getTimerCount();

        fixture.destroy();

        expect(vi.getTimerCount()).toBe(pendientes - 1);
      });
    });

    describe('vuelto en el aviso de venta cobrada (H-05)', () => {
      async function cobrarRecibiendo(recibido: string | null, numero = 2) {
        await tecla('F4');
        if (recibido !== null) {
          const input = dialogo()!.querySelector('input')!;
          input.value = recibido;
          input.dispatchEvent(new Event('input'));
          await refrescar();
        }
        await tecla('Enter');
        await cobrarOk(0, venta(numero, 3000));
      }

      it('con vuelto muestra «Venta N.º 2 cobrada · Vuelto $ 565,00» con el vuelto destacado', async () => {
        await conMartillos();

        await cobrarRecibiendo('3565');

        expect(avisos()).toBe('Venta N.º 2 cobrada · Vuelto $ 565,00');
        expect(normal(el().querySelector('.aviso .vuelto')?.textContent)).toBe('$ 565,00');
      });

      it('con vuelto el aviso no se oculta a los 5 s: queda hasta el próximo escaneo', async () => {
        await conMartillos();
        await cobrarRecibiendo('3565');

        await avanzar(60000);
        expect(avisos()).toBe('Venta N.º 2 cobrada · Vuelto $ 565,00');

        await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
        expect(el().querySelector('.aviso')).toBeNull();
      });

      it('con vuelto, Esc despeja el aviso', async () => {
        await conMartillos();
        await cobrarRecibiendo('3565');

        await tecla('Escape', campo());

        expect(el().querySelector('.aviso')).toBeNull();
      });

      it('con el importe exacto no hay vuelto y el aviso se oculta a los 5 s', async () => {
        await conMartillos();
        await cobrarRecibiendo(null);

        expect(avisos()).toBe('Venta N.º 2 cobrada: $ 3.000,00');
        expect(el().querySelector('.aviso .vuelto')).toBeNull();
        await avanzar(5000);
        expect(el().querySelector('.aviso')).toBeNull();
      });

      it('con otro medio de pago no hay vuelto', async () => {
        await conMartillos();
        await tecla('F4');
        await tecla('ArrowRight');
        await tecla('Enter');
        await cobrarOk(0, venta(2, 3000));

        expect(avisos()).toBe('Venta N.º 2 cobrada: $ 3.000,00');
      });
    });

    describe('aviso de sin stock (H-17)', () => {
      async function cobrarSinStock() {
        await conMartillos();
        await tecla('F4');
        await tecla('Enter');
        await cobrarOk(
          0,
          venta(12, 3000, [{ articuloId: 1, descripcion: 'Taladro', stockActual: -4 }]),
        );
      }

      it('va aparte de la venta, como advertencia y no como éxito', async () => {
        await cobrarSinStock();

        const advertencia = el().querySelector('.aviso.advertencia');
        expect(normal(advertencia?.textContent)).toBe('Sin stock: "Taladro" quedó en -4.');
        expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.000,00');
      });

      it('no se oculta solo a los 5 s: queda hasta el próximo escaneo', async () => {
        await cobrarSinStock();

        await avanzar(60000);
        expect(el().querySelector('.aviso.advertencia')).not.toBeNull();

        await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
        expect(el().querySelector('.aviso')).toBeNull();
      });
    });

    it('el aviso no bloquea el siguiente escaneo y el ticket nuevo usa otra clave', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');
      await cobrarOk(0, venta(12, 3630));

      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));

      expect(descripciones()).toEqual(['Serrucho']);
      expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.630,00');
      await tecla('F4');
      await tecla('Enter');
      expect(cobros[1].pedido.clave).toBe('clave-2');
    });

    it('una venta repetida (200) se trata igual que una nueva', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await cobrarOk(0, { ...venta(12, 3000), repetida: true });

      expect(renglonesTicket()).toHaveLength(0);
      expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.000,00');
    });

    it('la advertencia de sinStock de la respuesta se muestra junto al aviso', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await cobrarOk(
        0,
        venta(12, 3000, [{ articuloId: 1, descripcion: 'Martillo', stockActual: -3 }]),
      );

      expect(avisos()).toContain('Venta N.º 12 cobrada: $ 3.000,00');
      expect(normal(el().textContent)).toContain('Sin stock: "Martillo" quedó en -3.');
    });

    it('un doble Enter envía un solo POST', async () => {
      await conMartillos();
      await tecla('F4');

      await tecla('Enter');
      await tecla('Enter');

      expect(cobros).toHaveLength(1);
    });

    it('mientras la venta está en vuelo, Esc no cierra el diálogo', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await tecla('Escape');

      expect(dialogo()).not.toBeNull();
      expect(renglonesTicket()).toHaveLength(1);
    });

    it('un 409 de precios actualiza el renglón, avisa, muestra el total nuevo y Enter confirma de nuevo', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(
        0,
        409,
        rechazo(
          409,
          'PRECIOS_CAMBIADOS',
          [
            {
              articuloId: 1,
              descripcion: 'Martillo',
              precioMostrado: 1500,
              precioActual: 1650,
              totalRenglon: 3300,
            },
          ],
          { total: 3300 },
        ),
      );

      expect(dialogo()).not.toBeNull();
      expect(normal(dialogo()!.textContent)).toContain('$ 3.300,00');
      expect(normal(dialogo()!.textContent)).toContain(
        'Cambió el precio de "Martillo": ahora $ 1.650,00.',
      );
      expect(normal(renglonesTicket()[0].textContent)).toContain('$ 1.650,00');
      expect(dialogo()!.querySelector('input')!.value).toBe('3300,00');

      await tecla('Enter');

      expect(cobros).toHaveLength(2);
      expect(cobros[1].pedido.clave).toBe('clave-1');
      expect(cobros[1].pedido.renglones[0].precioUnitario).toBe(1650);
      expect(cobros[1].pedido.totalMostrado).toBe(3300);
    });

    it('un 409 con varios precios nuevos los cuenta', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(
        0,
        409,
        rechazo(
          409,
          'PRECIOS_CAMBIADOS',
          [
            { articuloId: 1, precioMostrado: 1500, precioActual: 1650, totalRenglon: 1650 },
            { articuloId: 2, precioMostrado: 100, precioActual: 110, totalRenglon: 110 },
          ],
          { total: 1760 },
        ),
      );

      expect(normal(dialogo()!.textContent)).toContain(
        'Cambiaron los precios de 2 artículos. Revisá el total y confirmá de nuevo.',
      );
      expect(normal(dialogo()!.textContent)).toContain('$ 1.760,00');
    });

    it('un 422 por baja conserva el ticket, cierra el diálogo, marca el renglón y deja quitarlo', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 422, {
        ...rechazo(422, 'ARTICULO_DADO_DE_BAJA', [{ articuloId: 1, descripcion: 'Martillo' }]),
        message: '"Martillo" fue dado de baja. Sacalo del ticket para seguir.',
      });

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(2);
      expect(avisos()).toBe('"Martillo" fue dado de baja. Sacalo del ticket para seguir.');
      expect(el().querySelector('.aviso')!.getAttribute('role')).toBe('alert');
      expect(normal(renglonesTicket()[0].textContent)).toContain('Artículo dado de baja');
      expect(renglonesTicket()[1].querySelector('.problema')).toBeNull();
      expect(document.activeElement).toBe(campo());

      // El renglón con problema quedó elegido: F10 propone quitar ese, sin tocar las flechas (H-06).
      await tecla('F10');
      expect(dialogo()!.textContent).toContain('¿Quitar «Martillo» del ticket?');
      await tecla('Enter');
      expect(descripciones()).toEqual(['Serrucho']);
    });

    it('un 422 por artículo sin precio conserva el ticket y marca el renglón', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 422, {
        ...rechazo(422, 'ARTICULO_SIN_PRECIO', [{ articuloId: 1, descripcion: 'Martillo' }]),
        message: '"Martillo" no tiene precio. Sacalo del ticket y pedile al dueño que le cargue el costo.',
      });

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(2);
      expect(avisos()).toBe('"Martillo" no tiene precio. Sacalo del ticket y pedile al dueño que le cargue el costo.');
      expect(normal(renglonesTicket()[0].textContent)).toContain('Sin precio');
      expect(renglonesTicket()[1].querySelector('.problema')).toBeNull();
    });

    it('un 404 también deja elegido el renglón inexistente (H-06)', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 404, rechazo(404, 'ARTICULO_INEXISTENTE', [{ articuloId: 1 }]));

      await tecla('F10');
      expect(dialogo()!.textContent).toContain('¿Quitar «Martillo» del ticket?');
    });

    it('un 404 ubica el renglón por id y lo nombra con la descripción del ticket', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 404, {
        ...rechazo(404, 'ARTICULO_INEXISTENTE', [{ articuloId: 2 }]),
        message: 'Hay un artículo del ticket que ya no está disponible',
      });

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(2);
      expect(avisos()).toBe('"Serrucho" ya no está disponible. Sacalo del ticket para seguir.');
      expect(normal(renglonesTicket()[1].textContent)).toContain('Ya no está disponible');
      expect(renglonesTicket()[0].querySelector('.problema')).toBeNull();
    });

    it('un 422 por contenido distinto muestra el mensaje del servidor y conserva el ticket', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 422, {
        ...rechazo(422, 'CLAVE_CON_OTRO_CONTENIDO', []),
        message: 'Esta venta ya se registró con otros datos',
      });

      expect(dialogo()).toBeNull();
      expect(avisos()).toBe('Esta venta ya se registró con otros datos');
      expect(renglonesTicket()).toHaveLength(1);
    });

    it.each([
      ['un error de red', 0],
      ['un 500', 500],
    ])('%s conserva ticket y clave, avisa en el diálogo y el reintento usa la misma clave', async (_n, status) => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, status, null);

      expect(dialogo()).not.toBeNull();
      expect(normal(dialogo()!.textContent)).toContain('No se pudo cobrar. Probá de nuevo.');
      expect(renglonesTicket()).toHaveLength(1);
      await tecla('Enter');
      expect(cobros).toHaveLength(2);
      expect(cobros[1].pedido.clave).toBe('clave-1');
    });

    it('un 400 por un renglón muestra el mensaje del servidor, marca ese renglón y conserva ticket y clave', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 400, {
        status: 400,
        message: 'Datos inválidos',
        errors: { 'renglones[1].cantidad': 'La cantidad debe ser mayor a 0' },
      });

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(2);
      expect(avisos()).toBe('La cantidad debe ser mayor a 0');
      expect(el().querySelector('.aviso')!.getAttribute('role')).toBe('alert');
      expect(normal(renglonesTicket()[1].textContent)).toContain('La cantidad debe ser mayor a 0');
      expect(renglonesTicket()[0].querySelector('.problema')).toBeNull();

      await tecla('F4');
      await tecla('Enter');
      expect(cobros[1].pedido.clave).toBe('clave-1');
    });

    it('un 400 que no es de un renglón muestra los mensajes del servidor en el diálogo', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 400, {
        status: 400,
        message: 'Datos inválidos',
        errors: { formaPagoId: 'La forma de pago no es válida.' },
      });

      expect(dialogo()).not.toBeNull();
      expect(normal(dialogo()!.textContent)).toContain('La forma de pago no es válida.');
      expect(normal(dialogo()!.textContent)).not.toContain('No se pudo cobrar');
      expect(renglonesTicket()[0].querySelector('.problema')).toBeNull();
    });

    it('un 400 sin errores por campo muestra el mensaje del servidor', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 400, { status: 400, message: 'El cuerpo del pedido no es válido' });

      expect(normal(dialogo()!.textContent)).toContain('El cuerpo del pedido no es válido');
    });

    it('un 400 con un cuerpo que no se entiende cae al mensaje genérico', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 400, null);

      expect(normal(dialogo()!.textContent)).toContain('No se pudo cobrar. Probá de nuevo.');
    });

    it('cancelar el diálogo después de un error deja el ticket para volver a intentar', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');
      await fallarCobro(0, 500);

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(1);
      expect(document.activeElement).toBe(campo());
    });
  });

  describe('aviso al salir (CU-07, R-037)', () => {
    const salir = () => {
      const evento = new Event('beforeunload', { cancelable: true }) as BeforeUnloadEvent;
      window.dispatchEvent(evento);
      return evento;
    };

    async function escanear(codigo: string, a: ArticuloMostrador) {
      await escribir(codigo);
      await enter();
      await responder(llamadas.length - 1, { tipo: 'EXACTO', items: [a], hayMas: false });
    }

    it('con un renglón pide confirmación al cerrar o recargar', async () => {
      await crear();
      await escanear('A', articulo(1));

      const evento = salir();

      expect(evento.defaultPrevented).toBe(true);
      expect(evento.returnValue).toBe(false);
    });

    it('con el ticket vacío no pide confirmación', async () => {
      await crear();

      const evento = salir();

      expect(evento.defaultPrevented).toBe(false);
    });

    it('recién cobrado no pide confirmación', async () => {
      await crear();
      await escanear('A', articulo(1));
      campo().dispatchEvent(new KeyboardEvent('keydown', { key: 'F4', bubbles: true, cancelable: true }));
      await refrescar();
      document.activeElement!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
      );
      await refrescar();
      cobros[0].respuesta.next({
        venta: { ventaId: 1, numero: 1, total: 170, fecha: '', medioPago: 'EFECTIVO', renglones: [], sinStock: [] },
        repetida: false,
      });
      await refrescar();

      expect(salir().defaultPrevented).toBe(false);
    });

    it('no bloquea la navegación interna: no registra nada del lado del router', async () => {
      await crear();
      await escanear('A', articulo(1));

      // Cambiar de ruta no dispara beforeunload: solo el cierre o la recarga de la página lo hacen.
      expect(el().querySelector('a[href]')).toBeNull();
    });
  });

  describe('meta de la fase (CU-07, R-042)', () => {
    it('5 escaneos + F4 + Enter dan un POST con 5 renglones en efectivo y dejan el ticket vacío', async () => {
      await crear();
      for (let i = 1; i <= 5; i++) {
        await escribir(`COD${i}`);
        await enter();
        await responder(llamadas.length - 1, {
          tipo: 'EXACTO',
          items: [articulo(i, { precioFinal: 100 * i })],
          hayMas: false,
        });
      }

      campo().dispatchEvent(new KeyboardEvent('keydown', { key: 'F4', bubbles: true, cancelable: true }));
      await refrescar();
      document.activeElement!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
      );
      await refrescar();

      expect(cobros).toHaveLength(1);
      expect(cobros[0].pedido.renglones.map((r) => r.articuloId)).toEqual([1, 2, 3, 4, 5]);
      expect(cobros[0].pedido.formaPagoId).toBe(1);
      expect(cobros[0].pedido.totalMostrado).toBe(1500);

      cobros[0].respuesta.next({
        venta: { ventaId: 1, numero: 7, total: 1500, fecha: '', medioPago: 'EFECTIVO', renglones: [], sinStock: [] },
        repetida: false,
      });
      await refrescar();

      expect(renglonesTicket()).toHaveLength(0);
      expect(document.activeElement).toBe(campo());
    });
  });

  describe('atajos', () => {
    let boton: HTMLButtonElement;

    beforeEach(() => {
      boton = document.createElement('button');
      document.body.append(boton);
    });

    afterEach(() => boton.remove());

    const tecla = async (key: string, destino: HTMLElement = campo()) => {
      const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      destino.dispatchEvent(evento);
      await refrescar();
      return evento;
    };

    const ficha = () => el().querySelector('app-ficha-articulo');

    it('F2 con el foco en un botón lo pasa al campo y evita la acción del navegador', async () => {
      await crear();
      boton.focus();
      expect(document.activeElement).toBe(boton);

      const evento = await tecla('F2', boton);

      expect(document.activeElement).toBe(campo());
      expect(evento.defaultPrevented).toBe(true);
    });

    it('F2 no tiene otro efecto: no borra el texto, no busca ni cierra la ficha', async () => {
      await crear();
      await escribir('7790001');
      await tecla('Enter');
      await responder(0, { tipo: 'EXACTO', items: [articulo(1)], hayMas: false });
      boton.focus();

      await tecla('F2', boton);
      await avanzar(500);

      expect(campo().value).toBe('7790001');
      expect(llamadas).toHaveLength(1);
      expect(ficha()).not.toBeNull();
    });

    it('Esc con la ficha visible y texto escrito deja todo vacío y el foco en el campo', async () => {
      await crear();
      await escribir('7790001');
      await tecla('Enter');
      await responder(0, { tipo: 'EXACTO', items: [articulo(1)], hayMas: false });
      expect(ficha()).not.toBeNull();
      boton.focus();

      const evento = await tecla('Escape', boton);

      expect(campo().value).toBe('');
      expect(ficha()).toBeNull();
      expect(filas()).toHaveLength(0);
      expect(el().querySelector('[role="alert"]')).toBeNull();
      expect(document.activeElement).toBe(campo());
      expect(evento.defaultPrevented).toBe(true);
    });

    it('Esc limpia también una lista y cancela la búsqueda pendiente o en vuelo', async () => {
      await crear();
      await escribir('tor');
      await avanzar(250);
      await responder(0, lista([articulo(1), articulo(2)]));
      expect(filas()).toHaveLength(2);
      await escribir('torn');
      await avanzar(250);
      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'torn']);

      await tecla('Escape');
      await responder(1, lista([articulo(3)]));
      await avanzar(500);

      expect(campo().value).toBe('');
      expect(filas()).toHaveLength(0);
      expect(llamadas).toHaveLength(2);
    });

    it('F8 con el ticket vacío avisa y anula la acción del navegador, como F4', async () => {
      await crear();
      await escribir('tor');
      await avanzar(250);
      await responder(0, lista([articulo(1)]));

      const evento = await tecla('F8');
      await avanzar(500);

      expect(evento.defaultPrevented).toBe(true);
      expect(el().textContent).toContain('El ticket está vacío.');
      expect(el().querySelector('[role="dialog"], [role="alertdialog"]')).toBeNull();
      expect(campo().value).toBe('tor');
      expect(guardados).toHaveLength(0);
    });

    it('F4 ya no es inerte: con el ticket vacío avisa y anula la acción del navegador (R-032)', async () => {
      await crear();
      await escribir('tor');
      await avanzar(250);
      await responder(0, lista([articulo(1)]));

      const evento = await tecla('F4');
      await avanzar(500);

      expect(evento.defaultPrevented).toBe(true);
      expect(el().textContent).toContain('El ticket está vacío.');
      expect(el().querySelector('[role="dialog"], [role="alertdialog"]')).toBeNull();
      expect(campo().value).toBe('tor');
      expect(filas()).toHaveLength(1);
      expect(llamadas).toHaveLength(1);
    });
  });

  describe('mensajes y errores', () => {
    let boton: HTMLButtonElement;
    let enlace: HTMLAnchorElement;

    beforeEach(() => {
      boton = document.createElement('button');
      enlace = document.createElement('a');
      enlace.href = '/rubros';
      document.body.append(boton, enlace);
    });

    afterEach(() => {
      boton.remove();
      enlace.remove();
    });

    const mensaje = () => el().querySelector('.mensaje')?.textContent?.trim() ?? null;
    const rolDelMensaje = () => el().querySelector('.mensaje')?.getAttribute('role') ?? null;
    const errorHttp = (status: number) => new HttpErrorResponse({ status });

    async function fallar(indice: number, status = 500) {
      llamadas[indice].respuesta.error(errorHttp(status));
      await refrescar();
    }

    async function buscar(q: string) {
      await escribir(q);
      await enter();
    }

    it('SIN_RESULTADOS se ve como advertencia roja, para que se note con el lector (H-17)', async () => {
      await crear();
      await buscar('martillo');

      await responder(0, { tipo: 'SIN_RESULTADOS', items: [], hayMas: false });

      expect(el().querySelector('.mensaje')!.classList.contains('error')).toBe(true);
    });

    it('SIN_RESULTADOS con texto muestra el mensaje de no encontrado', async () => {
      await crear();
      await buscar('martillo');

      await responder(0, { tipo: 'SIN_RESULTADOS', items: [], hayMas: false });

      expect(mensaje()).toBe('No encontramos ningún artículo con ese código o descripción.');
      expect(el().textContent).not.toContain('Artículo dado de baja');
    });

    it('«no encontrado» se anuncia como status (no interrumpe al que tipea); el error de red, como alert', async () => {
      await crear();
      await buscar('martillo');
      await responder(0, { tipo: 'SIN_RESULTADOS', items: [], hayMas: false });
      expect(rolDelMensaje()).toBe('status');

      await buscar('red');
      await fallar(1);
      expect(rolDelMensaje()).toBe('alert');
    });

    it('un mensaje desaparece cuando la búsqueda siguiente trae resultados', async () => {
      await crear();
      await buscar('martillo');
      await responder(0, { tipo: 'SIN_RESULTADOS', items: [], hayMas: false });
      expect(mensaje()).not.toBeNull();

      await buscar('tornillo');
      await responder(1, lista([articulo(1)]));

      expect(mensaje()).toBeNull();
      expect(filas()).toHaveLength(1);
    });

    it('una LISTA con hayMas muestra el aviso junto a las 20 filas', async () => {
      await crear();
      await buscar('tor');
      const veinte = Array.from({ length: 20 }, (_, i) => articulo(i + 1));

      await responder(0, lista(veinte, true));

      expect(filas()).toHaveLength(20);
      expect(el().textContent).toContain(
        'Hay más resultados. Escribí más para afinar la búsqueda.',
      );
    });

    it('un error 500 muestra «No se pudo buscar. Probá de nuevo.» y el foco sigue en el campo', async () => {
      await crear();
      await buscar('tor');

      await fallar(0);

      expect(mensaje()).toBe('No se pudo buscar. Probá de nuevo.');
      expect(document.activeElement).toBe(campo());
    });

    it('un error de red muestra el mismo mensaje', async () => {
      await crear();
      await buscar('tor');

      await fallar(0, 0);

      expect(mensaje()).toBe('No se pudo buscar. Probá de nuevo.');
    });

    it('después de un error la pantalla sigue andando: la búsqueda siguiente funciona', async () => {
      await crear();
      await buscar('tor');
      await fallar(0);

      await buscar('torn');
      await responder(1, lista([articulo(2, { descripcion: 'Tornillo' })]));

      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'torn']);
      expect(mensaje()).toBeNull();
      expect(filas()).toHaveLength(1);
    });

    it('un error saca el resultado anterior de la pantalla', async () => {
      await crear();
      await buscar('tor');
      await responder(0, lista([articulo(1)]));
      expect(filas()).toHaveLength(1);

      await buscar('torn');
      await fallar(1);

      expect(filas()).toHaveLength(0);
      expect(mensaje()).toBe('No se pudo buscar. Probá de nuevo.');
    });

    it('un 401 no muestra mensaje propio: lo trata el interceptor de sesión', async () => {
      await crear();
      await buscar('tor');

      await fallar(0, 401);

      expect(mensaje()).toBeNull();
    });

    it('tras cada búsqueda, con resultado o con error, el campo recupera el foco', async () => {
      await crear();
      await buscar('tor');
      boton.focus();
      await responder(0, lista([articulo(1)]));
      expect(document.activeElement).toBe(campo());

      await buscar('torn');
      boton.focus();
      await fallar(1);

      expect(document.activeElement).toBe(campo());
    });

    it('un clic en una parte vacía de la pantalla devuelve el foco al campo', async () => {
      await crear();

      campo().blur();
      await avanzar(0);

      expect(document.activeElement).toBe(campo());
    });

    it.each([
      ['un botón (Salir)', () => boton],
      ['un enlace (Rubros)', () => enlace],
    ])('el foco que pasa a %s no se le roba', async (_nombre, destino) => {
      await crear();

      destino().focus();
      await avanzar(0);

      expect(document.activeElement).toBe(destino());
    });
  });

  describe('la ficha sigue lo que el vendedor tiene elegido (H-17, H-19)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({
      tipo: 'EXACTO',
      items: [a],
      hayMas: false,
    });
    const nombreFicha = () =>
      el().querySelector('app-ficha-articulo .descripcion')?.textContent?.trim() ?? null;
    const flecha = async (key: 'ArrowDown' | 'ArrowUp') => {
      campo().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      await refrescar();
    };

    async function escanear(codigo: string, a: ArticuloMostrador) {
      await escribir(codigo);
      await enter();
      await responder(llamadas.length - 1, exacto(a));
    }

    async function buscarLista(items: ArticuloMostrador[]) {
      await escribir('tor');
      await enter();
      await responder(llamadas.length - 1, lista(items));
    }

    it('↑ y ↓ sobre el ticket cambian la ficha al renglón elegido', async () => {
      await crear();
      await escanear('A', articulo(1, { ubicacion: 'Pasillo 9' }));
      await escanear('B', articulo(2));
      expect(nombreFicha()).toBe('Artículo 2');

      await flecha('ArrowUp');
      expect(nombreFicha()).toBe('Artículo 1');
      expect(el().querySelector('app-ficha-articulo .ubicacion')!.textContent).toContain('Pasillo 9');

      await flecha('ArrowDown');
      expect(nombreFicha()).toBe('Artículo 2');
    });

    it('con la lista de resultados abierta, la ficha muestra el resultado resaltado', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2), articulo(3)]);
      expect(nombreFicha()).toBe('Artículo 1');

      await flecha('ArrowDown');

      expect(nombreFicha()).toBe('Artículo 2');
      expect(renglonesTicket()).toHaveLength(0);
    });

    it('al elegir de la lista la ficha queda en el artículo agregado', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)]);
      await flecha('ArrowDown');

      await enter();

      expect(nombreFicha()).toBe('Artículo 2');
      expect(filas()).toHaveLength(0);
    });

    it('con el ticket vacío y la lista abierta no asoma «El ticket está vacío» debajo', async () => {
      await crear();
      expect(el().querySelector('app-ticket-lista .vacio')).not.toBeNull();

      await buscarLista([articulo(1), articulo(2)]);

      expect(el().querySelector('app-ticket-lista .vacio')).toBeNull();
    });
  });

  describe('descuentos (GDS-12)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const dialogo = () => el().querySelector<HTMLElement>('app-dialogo-descuento [role="dialog"]');
    const campoDescuento = () => el().querySelector<HTMLInputElement>('app-dialogo-descuento input')!;
    const totalGrande = () => normal(el().querySelector('app-totales-ticket .total')?.textContent);

    async function tecla(key: string) {
      (document.activeElement as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }),
      );
      await refrescar();
    }

    async function conUnArticulo() {
      await crear();
      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1, { precioFinal: 1500 })));
    }

    async function descuentoDelDialogo(valor: string) {
      campoDescuento().value = valor;
      campoDescuento().dispatchEvent(new Event('input'));
      await refrescar();
      await tecla('Enter');
    }

    it('F7 abre el descuento del renglón elegido y al confirmar el renglón lo dice y el total baja', async () => {
      await conUnArticulo();

      await tecla('F7');

      expect(normal(dialogo()?.querySelector('h2')?.textContent)).toBe('Descuento de Artículo 1');
      await descuentoDelDialogo('10');
      expect(dialogo()).toBeNull();
      expect(normal(el().querySelector('app-ticket-lista .descuento')?.textContent)).toBe('10 % de descuento');
      expect(totalGrande()).toBe('$ 1.350,00');
    });

    it('F6 abre el descuento de todo el ticket y al confirmar baja el total', async () => {
      await conUnArticulo();

      await tecla('F6');

      expect(normal(dialogo()?.querySelector('h2')?.textContent)).toBe('Descuento de todo el ticket');
      await descuentoDelDialogo('10');
      expect(dialogo()).toBeNull();
      expect(totalGrande()).toBe('$ 1.350,00');
      expect(normal(el().querySelector('app-totales-ticket .descuento')?.textContent)).toBe('Descuento -$ 150,00');
    });

    it('Esc cierra el diálogo sin aplicar nada', async () => {
      await conUnArticulo();
      await tecla('F7');

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(totalGrande()).toBe('$ 1.500,00');
    });

    it('sin renglones los descuentos no abren nada: avisan que el ticket está vacío y los botones están apagados', async () => {
      await crear();

      await tecla('F7');
      await tecla('F6');

      expect(dialogo()).toBeNull();
      expect(normal(el().querySelector('.aviso')?.textContent)).toBe('El ticket está vacío.');
      const botones = Array.from(el().querySelectorAll<HTMLButtonElement>('app-botonera button'));
      for (const texto of ['Descuento', 'Dto. total']) {
        expect(botones.find((b) => normal(b.textContent).includes(texto))!.disabled).toBe(true);
      }
    });

    it('un descuento inválido se avisa en el diálogo y no cierra', async () => {
      await conUnArticulo();
      await tecla('F7');

      await descuentoDelDialogo('100');

      expect(dialogo()).not.toBeNull();
      expect(normal(el().querySelector('app-dialogo-descuento [role="alert"]')?.textContent)).toBe(
        'El descuento tiene que estar entre 0 y 99,99 %.',
      );
      expect(totalGrande()).toBe('$ 1.500,00');
    });

    it('al cobrar manda los dos descuentos y el total ya descontado', async () => {
      await conUnArticulo();
      await tecla('F7');
      await descuentoDelDialogo('10');
      await tecla('F6');
      await descuentoDelDialogo('10');

      await tecla('F4');
      await tecla('Enter');

      expect(cobros).toHaveLength(1);
      expect(cobros[0].pedido.renglones[0].descuentoPorcentaje).toBe(10);
      expect(cobros[0].pedido.descuentoPorcentaje).toBe(10);
      expect(cobros[0].pedido.totalMostrado).toBe(1215);
    });
  });

  describe('pago mixto (GDS-07)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();

    async function tecla(key: string) {
      (document.activeElement as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }),
      );
      await refrescar();
    }

    async function cobrandoUnArticulo() {
      await crear();
      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1, { precioFinal: 1500 })));
      await tecla('F4');
      Array.from(el().querySelectorAll<HTMLButtonElement>('button[role="radio"]'))
        .find((b) => b.textContent!.includes('Mixto'))!
        .click();
      await refrescar();
    }

    function cargar(formaPagoId: number, texto: string) {
      const campoMonto = el().querySelector<HTMLInputElement>(`input[data-forma="${formaPagoId}"]`)!;
      campoMonto.value = texto;
      campoMonto.dispatchEvent(new Event('input', { bubbles: true }));
    }

    it('cobra con dos formas: manda la lista de pagos y no la forma única', async () => {
      await cobrandoUnArticulo();
      cargar(1, '500');
      await refrescar();

      await tecla('Enter');
      await tecla('Enter');

      expect(cobros).toHaveLength(1);
      expect(cobros[0].pedido.formaPagoId).toBeUndefined();
      expect(cobros[0].pedido.pagos).toEqual([
        { formaPagoId: 1, monto: 500 },
        { formaPagoId: 2, monto: 1000 },
      ]);
      expect(cobros[0].pedido.totalMostrado).toBe(1500);
    });

    it('no cobra si los pagos no suman el total', async () => {
      await cobrandoUnArticulo();
      cargar(1, '500');
      await refrescar();

      (el().querySelector('app-dialogo-cobro .cobrar') as HTMLButtonElement).click();
      await refrescar();

      expect(cobros).toHaveLength(0);
      expect(normal(el().querySelector('app-dialogo-cobro [role="alert"]')?.textContent)).toBe(
        'Los pagos tienen que sumar el total.',
      );
    });
  });

  describe('caja (CU-10)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const avisoCaja = () => el().querySelector<HTMLElement>('.aviso-caja');
    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const sinCaja: CajaVigente = { estado: 'SIN_CAJA', caja: null, efectivoPropuesto: 0 };
    const deAyer: CajaVigente = {
      estado: 'DE_OTRO_DIA',
      caja: { id: 3, estado: 'DE_OTRO_DIA', fechaApertura: '2026-10-15T08:00:00', abiertaPor: 'Ana', efectivoInicial: 0 },
      efectivoPropuesto: 0,
    };

    async function tecla(key: string) {
      (document.activeElement as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }),
      );
      await refrescar();
    }

    async function conUnArticulo(vigente: CajaVigente) {
      await crear(vigente);
      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1, { precioFinal: 1500 })));
    }

    it('con la caja abierta hoy no hay aviso de caja y se cobra como siempre', async () => {
      await conUnArticulo(cajaAbierta);

      expect(avisoCaja()).toBeNull();
      await tecla('F4');
      expect(dialogo()).not.toBeNull();
    });

    it('sin caja abierta avisa «Abrí la caja para vender» con un botón que lleva a abrirla', async () => {
      await crear(sinCaja);

      expect(normal(avisoCaja()?.textContent)).toContain('Abrí la caja para vender.');
      const enlace = avisoCaja()!.querySelector<HTMLAnchorElement>('a')!;
      expect(normal(enlace.textContent)).toBe('Abrir la caja');
      expect(enlace.getAttribute('href')).toBe('/caja');
    });

    it('sin caja abierta F4 no abre el cobro: avisa y no manda nada', async () => {
      await conUnArticulo(sinCaja);

      await tecla('F4');

      expect(dialogo()).toBeNull();
      expect(cobros).toHaveLength(0);
      expect(normal(el().querySelector('.aviso')?.textContent)).toBe('Abrí la caja para vender.');
    });

    it('con la caja de otro día avisa que hay que cerrarla y no deja cobrar', async () => {
      await conUnArticulo(deAyer);

      expect(normal(avisoCaja()?.textContent)).toContain(
        'La caja del 15/10 sigue abierta. Cerrala antes de vender.',
      );
      expect(normal(avisoCaja()!.querySelector('a')!.textContent)).toBe('Cerrar la caja');
      await tecla('F4');
      expect(dialogo()).toBeNull();
      expect(cobros).toHaveLength(0);
    });

    it('sin caja el botón Cobrar de la botonera queda deshabilitado', async () => {
      await conUnArticulo(sinCaja);

      const cobrar = Array.from(el().querySelectorAll<HTMLButtonElement>('app-botonera button')).find((b) =>
        normal(b.textContent).includes('Cobrar'),
      )!;
      expect(cobrar.disabled).toBe(true);
    });

    it('si el backend rechaza por CAJA_CERRADA, cierra el cobro, conserva el ticket y muestra el aviso de caja', async () => {
      await conUnArticulo(cajaAbierta);
      await tecla('F4');
      await tecla('Enter');
      cajas.actual.mockReturnValue(of(sinCaja));

      cobros[0].respuesta.error(
        new HttpErrorResponse({
          status: 422,
          error: { status: 422, message: 'Abrí la caja para vender.', motivo: 'CAJA_CERRADA', articulos: [] },
        }),
      );
      await refrescar();

      expect(dialogo()).toBeNull();
      expect(el().querySelectorAll('app-ticket-lista .descripcion')).toHaveLength(1);
      expect(normal(el().querySelector('.aviso')?.textContent)).toBe('Abrí la caja para vender.');
      expect(normal(avisoCaja()?.textContent)).toContain('Abrí la caja para vender.');
    });
  });

  describe('presupuesto (GDS-13, PP-007)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const dialogoPresupuesto = () => el().querySelector<HTMLElement>('app-dialogo-presupuesto [role="dialog"]');
    const aviso = () => normal(el().querySelector('.aviso')?.textContent);
    const totalGrande = () => normal(el().querySelector('app-totales-ticket .total')?.textContent);
    const sinCaja: CajaVigente = { estado: 'SIN_CAJA', caja: null, efectivoPropuesto: 0 };

    async function tecla(key: string, destino: HTMLElement = document.activeElement as HTMLElement) {
      const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      destino.dispatchEvent(evento);
      await refrescar();
      return evento;
    }

    async function conMartillos(vigente: CajaVigente = cajaAbierta) {
      await crear(vigente);
      for (let i = 0; i < 2; i++) {
        await escribir('M');
        await enter();
        await responder(llamadas.length - 1, exacto(articulo(1, { descripcion: 'Martillo', precioFinal: 1500 })));
      }
    }

    function presupuestoGuardado(numero: number, total: number): PresupuestoEmitido {
      return {
        presupuesto: {
          presupuestoId: 8,
          numero,
          estado: 'PENDIENTE',
          fecha: '2026-10-18T10:00:00',
          vence: '2026-11-02',
          vencido: false,
          subtotal: total,
          descuentoPorcentaje: 0,
          descuento: 0,
          neto: 0,
          iva: 0,
          total,
          renglones: [],
        },
        repetido: false,
      };
    }

    async function guardarOk(indice: number, p: PresupuestoEmitido) {
      guardados[indice].respuesta.next(p);
      guardados[indice].respuesta.complete();
      await refrescar();
    }

    async function fallarGuardado(indice: number, status: number, cuerpo: unknown = null) {
      guardados[indice].respuesta.error(new HttpErrorResponse({ status, error: cuerpo }));
      await refrescar();
    }

    const rechazo = (status: number, motivo: string, articulos: unknown[], message = 'rechazo') => ({
      status,
      message,
      timestamp: '2026-10-18T10:00:00',
      motivo,
      articulos,
    });

    it('F8 con renglones abre «Guardar presupuesto», anula la acción del navegador y no manda nada todavía', async () => {
      await conMartillos();

      const evento = await tecla('F8');

      expect(evento.defaultPrevented).toBe(true);
      expect(normal(dialogoPresupuesto()?.querySelector('h2')?.textContent)).toBe('Guardar presupuesto');
      expect(guardados).toHaveLength(0);
    });

    it('el botón F8 Presupuesto abre lo mismo, y está apagado con el ticket vacío', async () => {
      await crear();
      const boton = () =>
        Array.from(el().querySelectorAll<HTMLButtonElement>('app-botonera button')).find((b) =>
          normal(b.textContent).includes('Presupuesto'),
        )!;
      expect(boton().disabled).toBe(true);

      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1)));
      expect(boton().disabled).toBe(false);
      boton().click();
      await refrescar();

      expect(dialogoPresupuesto()).not.toBeNull();
    });

    it('no exige caja abierta: sin caja F8 abre el diálogo y el botón está habilitado', async () => {
      await conMartillos(sinCaja);

      await tecla('F8');

      expect(dialogoPresupuesto()).not.toBeNull();
    });

    it('Enter envía el presupuesto con la clave del ticket, el total sin recargo y 15 días', async () => {
      await conMartillos();

      await tecla('F8');
      await tecla('Enter');

      expect(guardados).toHaveLength(1);
      expect(guardados[0].pedido).toEqual({
        clave: 'clave-1',
        renglones: [{ articuloId: 1, cantidad: 2, precioUnitario: 1500 }],
        totalMostrado: 3000,
        diasValidez: 15,
      });
    });

    it('manda el cliente y las observaciones que se escribieron en el diálogo', async () => {
      await conMartillos();
      await tecla('F8');
      const cliente = dialogoPresupuesto()!.querySelector<HTMLInputElement>('input[name="cliente"]')!;
      cliente.value = 'Obra Perez';
      cliente.dispatchEvent(new Event('input'));
      const validez = dialogoPresupuesto()!.querySelector<HTMLInputElement>('input[name="validez"]')!;
      validez.value = '30';
      validez.dispatchEvent(new Event('input'));

      await tecla('Enter');

      expect(guardados[0].pedido).toMatchObject({ clienteNombre: 'Obra Perez', diasValidez: 30 });
    });

    it('el pedido lleva los descuentos del ticket y el total ya descontado', async () => {
      await conMartillos();
      await tecla('F6');
      const campoDescuento = el().querySelector<HTMLInputElement>('app-dialogo-descuento input')!;
      campoDescuento.value = '10';
      campoDescuento.dispatchEvent(new Event('input'));
      await refrescar();
      await tecla('Enter');

      await tecla('F8');
      await tecla('Enter');

      expect(guardados[0].pedido.descuentoPorcentaje).toBe(10);
      expect(guardados[0].pedido.totalMostrado).toBe(2700);
    });

    it('al guardarse vacía el ticket, cierra el diálogo, avisa el número y devuelve el foco al buscador', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await guardarOk(0, presupuestoGuardado(12, 3000));

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
      expect(aviso()).toBe('Presupuesto N.º 12 guardado: $ 3.000,00');
      expect(document.activeElement).toBe(campo());
      expect(localStorage.getItem('ticket:3:7')).toBeNull();
    });

    it('el aviso de presupuesto guardado se va solo a los 5 segundos', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');
      await guardarOk(0, presupuestoGuardado(12, 3000));
      expect(aviso()).toContain('Presupuesto N.º 12 guardado');

      await avanzar(5000);

      expect(aviso()).toBe('');
    });

    it('con el POST en curso Esc no cierra el diálogo y Enter no manda otro', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await tecla('Escape');
      await tecla('Enter');

      expect(dialogoPresupuesto()).not.toBeNull();
      expect(guardados).toHaveLength(1);
    });

    it('Esc cierra el diálogo sin guardar y conserva el ticket', async () => {
      await conMartillos();
      await tecla('F8');

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(guardados).toHaveLength(0);
      expect(renglonesTicket()).toHaveLength(1);
    });

    it('con otro diálogo abierto F8 no abre el del presupuesto', async () => {
      await conMartillos();
      await tecla('F4');

      await tecla('F8');

      expect(dialogoPresupuesto()).toBeNull();
      expect(dialogo()).not.toBeNull();
      expect(guardados).toHaveLength(0);
    });

    it('si cambió un precio: actualiza el ticket, avisa en el diálogo y al volver a guardar manda el total nuevo con la misma clave', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await fallarGuardado(0, 409, {
        ...rechazo(409, 'PRECIOS_CAMBIADOS', [{ articuloId: 1, descripcion: 'Martillo', precioMostrado: 1500, precioActual: 1800 }]),
        total: 3600,
      });

      expect(dialogoPresupuesto()).not.toBeNull();
      expect(normal(dialogoPresupuesto()!.querySelector('[role="status"]')?.textContent)).toBe(
        'Cambió el precio de "Martillo": ahora $ 1.800,00.',
      );
      expect(totalGrande()).toBe('$ 3.600,00');
      await tecla('Enter', dialogoPresupuesto()!.querySelector('input')!);
      expect(guardados).toHaveLength(2);
      expect(guardados[1].pedido.clave).toBe('clave-1');
      expect(guardados[1].pedido.totalMostrado).toBe(3600);
    });

    it('si un artículo ya no se puede vender: cierra el diálogo, conserva el ticket, marca el renglón y avisa', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await fallarGuardado(
        0,
        422,
        rechazo(422, 'ARTICULO_DADO_DE_BAJA', [{ articuloId: 1, descripcion: 'Martillo' }], '"Martillo" fue dado de baja. Sacalo del ticket para seguir.'),
      );

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(1);
      expect(renglonesTicket()[0].textContent).toContain('Artículo dado de baja');
      expect(aviso()).toBe('"Martillo" fue dado de baja. Sacalo del ticket para seguir.');
    });

    it('si la clave ya se usó con otro contenido: cierra el diálogo y avisa con el mensaje del servidor', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await fallarGuardado(0, 422, rechazo(422, 'CLAVE_CON_OTRO_CONTENIDO', [], 'Este presupuesto ya se emitió con otros datos'));

      expect(dialogo()).toBeNull();
      expect(aviso()).toBe('Este presupuesto ya se emitió con otros datos');
    });

    it('un 400 muestra el mensaje del servidor dentro del diálogo y se puede volver a intentar', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await fallarGuardado(0, 400, { status: 400, message: 'Datos inválidos', errors: { clienteNombre: 'El nombre del cliente admite hasta 80 caracteres.' } });

      expect(normal(dialogoPresupuesto()!.querySelector('[role="status"]')?.textContent)).toBe(
        'El nombre del cliente admite hasta 80 caracteres.',
      );
      await tecla('Enter', dialogoPresupuesto()!.querySelector('input')!);
      expect(guardados).toHaveLength(2);
    });

    it('un error de red deja el diálogo abierto con un aviso para reintentar y no pierde el ticket', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await fallarGuardado(0, 500);

      expect(normal(dialogoPresupuesto()!.querySelector('[role="status"]')?.textContent)).toBe(
        'No se pudo guardar el presupuesto. Probá de nuevo.',
      );
      expect(renglonesTicket()).toHaveLength(1);
    });
  });

  describe('remito (G55, RM-007)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const dialogoRemito = () => el().querySelector<HTMLElement>('app-dialogo-remito [role="dialog"]');
    const aviso = () => normal(el().querySelector('.aviso')?.textContent);
    const advertencia = () => normal(el().querySelector('.aviso.advertencia')?.textContent);
    const totalGrande = () => normal(el().querySelector('app-totales-ticket .total')?.textContent);
    const sinCaja: CajaVigente = { estado: 'SIN_CAJA', caja: null, efectivoPropuesto: 0 };
    const botonRemito = () =>
      Array.from(el().querySelectorAll<HTMLButtonElement>('app-botonera button')).find((b) =>
        normal(b.textContent).includes('Remito'),
      )!;

    async function tecla(key: string, destino: HTMLElement = document.activeElement as HTMLElement) {
      const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      destino.dispatchEvent(evento);
      await refrescar();
      return evento;
    }

    async function conMartillos(vigente: CajaVigente = cajaAbierta) {
      await crear(vigente);
      for (let i = 0; i < 2; i++) {
        await escribir('M');
        await enter();
        await responder(llamadas.length - 1, exacto(articulo(1, { descripcion: 'Martillo', precioFinal: 1500 })));
      }
    }

    async function abrirRemito() {
      botonRemito().click();
      await refrescar();
    }

    function remitoEmitido(numero: number, total: number, sinStock: RemitoEmitido['remito']['sinStock'] = []): RemitoEmitido {
      return {
        remito: {
          remitoId: 8,
          numero,
          estado: 'EMITIDO',
          fecha: '2026-10-18T10:00:00',
          subtotal: total,
          descuentoPorcentaje: 0,
          descuento: 0,
          neto: 0,
          iva: 0,
          total,
          renglones: [],
          sinStock,
        },
        repetido: false,
      };
    }

    async function emitirOk(indice: number, r: RemitoEmitido) {
      emitidos[indice].respuesta.next(r);
      emitidos[indice].respuesta.complete();
      await refrescar();
    }

    async function fallarEmision(indice: number, status: number, cuerpo: unknown = null) {
      emitidos[indice].respuesta.error(new HttpErrorResponse({ status, error: cuerpo }));
      await refrescar();
    }

    const rechazo = (status: number, motivo: string, articulos: unknown[], message = 'rechazo') => ({
      status,
      message,
      timestamp: '2026-10-18T10:00:00',
      motivo,
      articulos,
    });

    it('el botón Remito abre «Emitir remito» y no manda nada todavía; está apagado con el ticket vacío', async () => {
      await crear();
      expect(botonRemito().disabled).toBe(true);

      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1)));
      expect(botonRemito().disabled).toBe(false);
      await abrirRemito();

      expect(normal(dialogoRemito()?.querySelector('h2')?.textContent)).toBe('Emitir remito');
      expect(emitidos).toHaveLength(0);
    });

    it('F8 sigue siendo el presupuesto: ninguna tecla abre el remito', async () => {
      await conMartillos();

      await tecla('F8');

      expect(dialogoRemito()).toBeNull();
      expect(el().querySelector('app-dialogo-presupuesto')).not.toBeNull();
    });

    it('no exige caja abierta: sin caja el botón está habilitado y abre el diálogo', async () => {
      await conMartillos(sinCaja);

      await abrirRemito();

      expect(dialogoRemito()).not.toBeNull();
    });

    it('Enter envía el remito con la clave del ticket y el total sin recargo, sin validez', async () => {
      await conMartillos();

      await abrirRemito();
      await tecla('Enter');

      expect(emitidos).toHaveLength(1);
      expect(emitidos[0].pedido).toEqual({
        clave: 'clave-1',
        renglones: [{ articuloId: 1, cantidad: 2, precioUnitario: 1500 }],
        totalMostrado: 3000,
      });
    });

    it('manda el cliente y las observaciones que se escribieron en el diálogo', async () => {
      await conMartillos();
      await abrirRemito();
      const cliente = dialogoRemito()!.querySelector<HTMLInputElement>('input[name="cliente"]')!;
      cliente.value = 'Obra Perez';
      cliente.dispatchEvent(new Event('input'));
      const observaciones = dialogoRemito()!.querySelector<HTMLInputElement>('input[name="observaciones"]')!;
      observaciones.value = 'Entrega el viernes';
      observaciones.dispatchEvent(new Event('input'));

      await tecla('Enter');

      expect(emitidos[0].pedido).toMatchObject({ clienteNombre: 'Obra Perez', observaciones: 'Entrega el viernes' });
    });

    it('el pedido lleva los descuentos del ticket y el total ya descontado', async () => {
      await conMartillos();
      await tecla('F6');
      const campoDescuento = el().querySelector<HTMLInputElement>('app-dialogo-descuento input')!;
      campoDescuento.value = '10';
      campoDescuento.dispatchEvent(new Event('input'));
      await refrescar();
      await tecla('Enter');

      await abrirRemito();
      await tecla('Enter');

      expect(emitidos[0].pedido.descuentoPorcentaje).toBe(10);
      expect(emitidos[0].pedido.totalMostrado).toBe(2700);
    });

    it('al emitirse vacía el ticket, cierra el diálogo, avisa el número y devuelve el foco al buscador', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await emitirOk(0, remitoEmitido(5, 3000));

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
      expect(aviso()).toBe('Remito N.º 5 emitido: $ 3.000,00');
      expect(document.activeElement).toBe(campo());
      expect(localStorage.getItem('ticket:3:7')).toBeNull();
    });

    it('el aviso de remito emitido se va solo a los 5 segundos', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');
      await emitirOk(0, remitoEmitido(5, 3000));
      expect(aviso()).toContain('Remito N.º 5 emitido');

      await avanzar(5000);

      expect(aviso()).toBe('');
    });

    it('si quedó un artículo sin stock lo avisa aparte y el aviso queda hasta el próximo renglón', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await emitirOk(0, remitoEmitido(5, 3000, [{ articuloId: 1, descripcion: 'Martillo', stockActual: -1.5 }]));
      await avanzar(6000);

      expect(aviso()).toContain('Remito N.º 5 emitido');
      expect(advertencia()).toBe('Sin stock: "Martillo" quedó en -1,5.');
    });

    it('con el POST en curso Esc no cierra el diálogo y Enter no manda otro', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await tecla('Escape');
      await tecla('Enter');

      expect(dialogoRemito()).not.toBeNull();
      expect(emitidos).toHaveLength(1);
    });

    it('Esc cierra el diálogo sin emitir y conserva el ticket', async () => {
      await conMartillos();
      await abrirRemito();

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(emitidos).toHaveLength(0);
      expect(renglonesTicket()).toHaveLength(1);
    });

    it('con otro diálogo abierto el botón Remito no abre el del remito', async () => {
      await conMartillos();
      await tecla('F4');

      botonRemito().click();
      await refrescar();

      expect(dialogoRemito()).toBeNull();
      expect(dialogo()).not.toBeNull();
      expect(emitidos).toHaveLength(0);
    });

    it('si cambió un precio: actualiza el ticket, avisa en el diálogo y al volver a emitir manda el total nuevo con la misma clave', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await fallarEmision(0, 409, {
        ...rechazo(409, 'PRECIOS_CAMBIADOS', [{ articuloId: 1, descripcion: 'Martillo', precioMostrado: 1500, precioActual: 1800 }]),
        total: 3600,
      });

      expect(dialogoRemito()).not.toBeNull();
      expect(normal(dialogoRemito()!.querySelector('[role="status"]')?.textContent)).toBe(
        'Cambió el precio de "Martillo": ahora $ 1.800,00.',
      );
      expect(totalGrande()).toBe('$ 3.600,00');
      await tecla('Enter', dialogoRemito()!.querySelector('input')!);
      expect(emitidos).toHaveLength(2);
      expect(emitidos[1].pedido.clave).toBe('clave-1');
      expect(emitidos[1].pedido.totalMostrado).toBe(3600);
    });

    it('si un artículo ya no se puede vender: cierra el diálogo, conserva el ticket, marca el renglón y avisa', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await fallarEmision(
        0,
        422,
        rechazo(422, 'ARTICULO_DADO_DE_BAJA', [{ articuloId: 1, descripcion: 'Martillo' }], '"Martillo" fue dado de baja. Sacalo del ticket para seguir.'),
      );

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(1);
      expect(renglonesTicket()[0].textContent).toContain('Artículo dado de baja');
      expect(aviso()).toBe('"Martillo" fue dado de baja. Sacalo del ticket para seguir.');
    });

    it('si la clave ya se usó con otro contenido: cierra el diálogo y avisa con el mensaje del servidor', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await fallarEmision(0, 422, rechazo(422, 'CLAVE_CON_OTRO_CONTENIDO', [], 'Este remito ya se emitió con otros datos'));

      expect(dialogo()).toBeNull();
      expect(aviso()).toBe('Este remito ya se emitió con otros datos');
    });

    it('un 400 muestra el mensaje del servidor dentro del diálogo y se puede volver a intentar', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await fallarEmision(0, 400, { status: 400, message: 'Datos inválidos', errors: { clienteNombre: 'El nombre del cliente admite hasta 80 caracteres.' } });

      expect(normal(dialogoRemito()!.querySelector('[role="status"]')?.textContent)).toBe(
        'El nombre del cliente admite hasta 80 caracteres.',
      );
      await tecla('Enter', dialogoRemito()!.querySelector('input')!);
      expect(emitidos).toHaveLength(2);
    });

    it('un error de red deja el diálogo abierto con un aviso para reintentar y no pierde el ticket', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await fallarEmision(0, 500);

      expect(normal(dialogoRemito()!.querySelector('[role="status"]')?.textContent)).toBe(
        'No se pudo emitir el remito. Probá de nuevo.',
      );
      expect(renglonesTicket()).toHaveLength(1);
    });
  });

  describe('venta desde un presupuesto (GDS-13, PP-006)', () => {
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const aviso = () => normal(el().querySelector('.aviso')?.textContent);
    const origen = () => el().querySelector('.origen');

    async function tecla(key: string) {
      (document.activeElement as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }),
      );
      await refrescar();
    }

    /** Un ticket que dejó «Pasar a venta»: 2 martillos de $ 1.500,00, del presupuesto N.º 12. */
    function ticketDelPresupuesto(extra: Record<string, unknown> = { presupuestoId: 8, presupuestoNumero: 12 }) {
      localStorage.setItem(
        'ticket:3:7',
        JSON.stringify({
          v: 1,
          clave: 'clave-p',
          elegido: 0,
          renglones: [
            {
              articuloId: 1,
              codigoInterno: 'COD-1',
              descripcion: 'Martillo',
              unidad: 'UNIDAD',
              precioCentavos: 150000,
              cantidadMilesimas: 2000,
              stockActual: 10,
              stockMinimo: 5,
            },
          ],
          ...extra,
        }),
      );
    }

    async function fallarCobro(status: number, cuerpo: unknown) {
      cobros[0].respuesta.error(new HttpErrorResponse({ status, error: cuerpo }));
      await refrescar();
    }

    it('un ticket que viene de un presupuesto dice de cuál', async () => {
      ticketDelPresupuesto();

      await crear();

      expect(normal(origen()?.textContent)).toBe('Venta del presupuesto N.º 12');
    });

    it('un ticket común no dice nada de presupuestos', async () => {
      ticketDelPresupuesto({});

      await crear();

      expect(origen()).toBeNull();
    });

    it('al cobrar manda el presupuesto del que sale la venta con la clave del ticket', async () => {
      ticketDelPresupuesto();
      await crear();

      await tecla('F4');
      await tecla('Enter');

      expect(cobros[0].pedido).toEqual({
        clave: 'clave-p',
        renglones: [{ articuloId: 1, cantidad: 2, precioUnitario: 1500 }],
        totalMostrado: 3000,
        formaPagoId: 1,
        presupuestoId: 8,
      });
    });

    it('al cobrarse el ticket se vacía y deja de decir de qué presupuesto era', async () => {
      ticketDelPresupuesto();
      await crear();
      await tecla('F4');
      await tecla('Enter');

      cobros[0].respuesta.next({
        venta: { ventaId: 99, numero: 5, total: 3000, fecha: '2026-10-18T10:00:00', renglones: [], sinStock: [], presupuestoId: 8 },
        repetida: false,
      } as VentaCobrada);
      cobros[0].respuesta.complete();
      await refrescar();

      expect(origen()).toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
    });

    it.each([
      [409, 'Ese presupuesto fue rechazado.'],
      [409, 'Ese presupuesto ya se facturó.'],
      [404, 'Presupuesto no encontrado'],
    ])(
      'si el servidor responde %i «%s»: cierra el cobro, suelta el presupuesto, conserva el ticket y avisa',
      async (status, mensaje) => {
        ticketDelPresupuesto();
        await crear();
        await tecla('F4');
        await tecla('Enter');

        await fallarCobro(status, { status, message: mensaje, timestamp: '2026-10-18T10:00:00' });

        expect(dialogo()).toBeNull();
        expect(origen()).toBeNull();
        expect(renglonesTicket()).toHaveLength(1);
        expect(aviso()).toBe(`${mensaje} El ticket sigue como una venta común.`);
      },
    );

    it('después de soltarlo, el reintento vende sin el presupuesto y con la misma clave', async () => {
      ticketDelPresupuesto();
      await crear();
      await tecla('F4');
      await tecla('Enter');
      await fallarCobro(409, { status: 409, message: 'Ese presupuesto fue rechazado.' });

      await tecla('F4');
      await tecla('Enter');

      expect(cobros).toHaveLength(2);
      expect(cobros[1].pedido.presupuestoId).toBeUndefined();
      expect(cobros[1].pedido.clave).toBe('clave-p');
    });

    it('un 409 en un ticket que no viene de un presupuesto sigue siendo un error de cobro común', async () => {
      ticketDelPresupuesto({});
      await crear();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(409, { status: 409, message: 'Conflicto' });

      expect(dialogo()).not.toBeNull();
      expect(el().textContent).toContain('No se pudo cobrar. Probá de nuevo.');
    });
  });
});
