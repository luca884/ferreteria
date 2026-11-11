import { vi } from 'vitest';
import { AvisosALaVista } from './avisos-a-la-vista';

/**
 * jsdom no implementa `scrollIntoView`, así que se reemplaza por un espía: los tests miran a quién se lo llama y con
 * qué opciones. Se registra en el prototipo porque el helper recibe los nodos que inserta Angular, no elementos que el
 * test pueda preparar de antemano.
 */
const scroll = vi.fn<(opciones?: ScrollIntoViewOptions) => void>();
const prototipo = Element.prototype as unknown as { scrollIntoView?: (opciones?: ScrollIntoViewOptions) => void };

describe('AvisosALaVista', () => {
  let contenido: HTMLElement;
  let avisos: AvisosALaVista;

  /** El aviso traído a la vista, como elemento; null si no se llamó a nadie. */
  const traido = (llamada = 0): Element | null => (scroll.mock.contexts[llamada] as Element | undefined) ?? null;

  /** Un nodo de los que Angular inserta (por ejemplo, el `<p class="alerta">` de una pantalla). */
  function crear(html: string): HTMLElement {
    const molde = document.createElement('template');
    molde.innerHTML = html.trim();
    return molde.content.firstElementChild as HTMLElement;
  }

  /** Espera a que el MutationObserver entregue lo que tenga pendiente (es asincrónico, vía microtask). */
  async function esperarAlObservador(): Promise<void> {
    await new Promise((listo) => setTimeout(listo, 0));
  }

  beforeEach(() => {
    Object.defineProperty(Element.prototype, 'scrollIntoView', { value: scroll, configurable: true, writable: true });
    scroll.mockClear();
    contenido = document.createElement('main');
    document.body.appendChild(contenido);
    avisos = new AvisosALaVista();
  });

  afterEach(() => {
    avisos.desconectar();
    contenido.remove();
    scroll.mockReset();
    delete prototipo.scrollIntoView;
  });

  describe('revisar (lo que el observer le pasa a la clase)', () => {
    it('un `.alerta` insertado después de conectar se trae a la vista sin moverla más de lo necesario', () => {
      const aviso = crear('<p class="alerta exito" role="status">Se guardó «Corralón Sur».</p>');
      contenido.appendChild(aviso);

      avisos.revisar([aviso]);

      expect(scroll).toHaveBeenCalledTimes(1);
      expect(scroll).toHaveBeenCalledWith({ block: 'nearest' });
      expect(traido()).toBe(aviso);
    });

    it('los avisos de acción del mostrador, que son de otra clase, también se traen', () => {
      const sinCaja = crear('<div class="aviso-caja" role="status"><span>Abrí la caja para vender.</span></div>');
      const nota = crear('<p class="aviso" role="alert">El artículo está dado de baja.</p>');
      const urgente = crear('<p class="mensaje error" role="alert">No hay caja abierta.</p>');
      for (const aviso of [sinCaja, nota, urgente]) contenido.appendChild(aviso);

      avisos.revisar([sinCaja, nota, urgente]);

      expect(scroll.mock.contexts).toEqual([sinCaja, nota, urgente]);
    });

    it('un aviso que se inserta dentro de un bloque se trae igual', () => {
      const bloque = crear('<div class="resumen"><p class="alerta exito" role="status">Listo.</p></div>');
      contenido.appendChild(bloque);
      const aviso = bloque.querySelector('p')!;

      avisos.revisar([bloque]);

      expect(traido()).toBe(aviso);
    });

    it('una pista estática no mueve la pantalla: no es un aviso de acción', () => {
      const apagada = crear('<p class="aviso apagada">La cotización está apagada.</p>');
      const desactualizada = crear('<p class="aviso desactualizada">No se pudo actualizar la cotización.</p>');
      const noCreados = crear('<p class="aviso nuevos-no-creados">2 artículos nuevos no se crearon.</p>');
      const fila = crear('<span class="aviso-fila">La cantidad no es entera: revisala contra la factura.</span>');
      const efectivo = crear('<p class="aviso-caja">No cambia el efectivo de la caja.</p>');
      for (const pista of [apagada, desactualizada, noCreados, fila, efectivo]) contenido.appendChild(pista);

      avisos.revisar([apagada, desactualizada, noCreados, fila, efectivo]);

      expect(scroll).not.toHaveBeenCalled();
    });

    it('los nodos de texto que inserta Angular no son avisos y no rompen nada', () => {
      const texto = document.createTextNode('Se guardó «Corralón Sur».');
      contenido.appendChild(texto);

      expect(() => avisos.revisar([texto])).not.toThrow();
      expect(scroll).not.toHaveBeenCalled();
    });
  });

  describe('observer', () => {
    it('trae el aviso cuando Angular lo inserta en el contenido', async () => {
      avisos.conectar(contenido);
      const aviso = crear('<p class="alerta exito" role="status">Se guardó «Corralón Sur».</p>');

      contenido.appendChild(aviso);

      await vi.waitFor(() => expect(scroll.mock.contexts).toEqual([aviso]));
      expect(scroll).toHaveBeenCalledWith({ block: 'nearest' });
    });

    it('un aviso que ya estaba antes de conectar no se trae por el solo hecho de conectar', async () => {
      const aviso = crear('<p class="alerta exito" role="status">Se guardó «Corralón Sur».</p>');
      contenido.appendChild(aviso);

      avisos.conectar(contenido);
      await esperarAlObservador();

      expect(scroll).not.toHaveBeenCalled();
    });

    it('desconectar deja de traer avisos', async () => {
      avisos.conectar(contenido);
      avisos.desconectar();
      const aviso = crear('<p class="alerta exito" role="status">Se guardó «Corralón Sur».</p>');

      contenido.appendChild(aviso);
      await esperarAlObservador();

      expect(scroll).not.toHaveBeenCalled();
    });

    it('conectar dos veces deja un solo observer: el aviso se trae una vez', async () => {
      avisos.conectar(contenido);
      avisos.conectar(contenido);
      const aviso = crear('<p class="alerta exito" role="status">Se guardó «Corralón Sur».</p>');

      contenido.appendChild(aviso);

      await vi.waitFor(() => expect(scroll).toHaveBeenCalled());
      expect(scroll).toHaveBeenCalledTimes(1);
    });
  });
});
