import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { vi } from 'vitest';
import { ArticuloMostrador, BusquedaMostrador } from './mostrador.models';
import { Mostrador } from './mostrador';
import { MostradorService } from './mostrador.service';

registerLocaleData(localeEsAr);

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

  let fixture: ComponentFixture<Mostrador>;
  let llamadas: Llamada[];

  async function crear() {
    // jsdom no implementa scrollIntoView.
    Element.prototype.scrollIntoView =
      vi.fn() as unknown as typeof Element.prototype.scrollIntoView;
    llamadas = [];
    const servicio = {
      buscar: vi.fn((q: string) => {
        const respuesta = new Subject<BusquedaMostrador>();
        llamadas.push({ q, respuesta });
        return respuesta;
      }),
    };
    await TestBed.configureTestingModule({
      imports: [Mostrador],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: MostradorService, useValue: servicio },
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

  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  describe('búsqueda', () => {
    it('tiene el foco en el campo al abrir la pantalla', async () => {
      await crear();

      expect(document.activeElement).toBe(campo());
    });

    it('muestra una pista visible con los atajos disponibles', async () => {
      await crear();

      expect(el().textContent).toContain('F2 buscar · Esc limpiar · ↑↓ elegir · Enter abrir');
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
      expect(filas()[0].textContent).toContain('2,5 Metro');
      expect(filas()[0].textContent).toContain('Pasillo 2');
      expect(ficha()).toBeNull();
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
      expect(ficha()).toBeNull();
      await enter();

      expect(ficha()!.textContent).toContain('Martillo');
    });

    it('si se editó el texto desde la última búsqueda, Enter busca de nuevo en vez de abrir la fila', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)], 'tor');

      await escribir('torn');
      await enter();

      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'torn']);
      expect(ficha()).toBeNull();
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

    it.each(['F4', 'F6'])('%s no hace nada en este change', async (key) => {
      await crear();
      await escribir('tor');
      await avanzar(250);
      await responder(0, lista([articulo(1)]));

      const evento = await tecla(key);
      await avanzar(500);

      expect(evento.defaultPrevented).toBe(false);
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

    const mensaje = () => el().querySelector('[role="alert"]')?.textContent?.trim() ?? null;
    const errorHttp = (status: number) => new HttpErrorResponse({ status });

    async function fallar(indice: number, status = 500) {
      llamadas[indice].respuesta.error(errorHttp(status));
      await refrescar();
    }

    async function buscar(q: string) {
      await escribir(q);
      await enter();
    }

    it('DADO_DE_BAJA muestra «Artículo dado de baja», no «no encontrado»', async () => {
      await crear();
      await buscar('7790003');

      await responder(0, { tipo: 'DADO_DE_BAJA', items: [], hayMas: false });

      expect(mensaje()).toBe('Artículo dado de baja');
      expect(el().textContent).not.toContain('No encontramos');
      expect(filas()).toHaveLength(0);
    });

    it('SIN_RESULTADOS con texto muestra el mensaje de no encontrado', async () => {
      await crear();
      await buscar('martillo');

      await responder(0, { tipo: 'SIN_RESULTADOS', items: [], hayMas: false });

      expect(mensaje()).toBe('No encontramos ningún artículo con ese código o descripción.');
      expect(el().textContent).not.toContain('Artículo dado de baja');
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
});
