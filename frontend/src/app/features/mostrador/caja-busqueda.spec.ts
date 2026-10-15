import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { CajaBusqueda } from './caja-busqueda';

describe('CajaBusqueda', () => {
  let fixture: ComponentFixture<CajaBusqueda>;
  let enters: number;
  let movimientos: (1 | -1)[];
  // Elementos externos al componente, para probar adónde va el foco.
  let boton: HTMLButtonElement;
  let enlace: HTMLAnchorElement;
  let otroCampo: HTMLInputElement;
  let parrafo: HTMLParagraphElement;

  const campo = () => (fixture.nativeElement as HTMLElement).querySelector('input')!;

  async function crear() {
    await TestBed.configureTestingModule({ imports: [CajaBusqueda] }).compileComponents();
    fixture = TestBed.createComponent(CajaBusqueda);
    enters = 0;
    movimientos = [];
    fixture.componentInstance.enter.subscribe(() => enters++);
    fixture.componentInstance.mover.subscribe((d) => movimientos.push(d));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(() => {
    vi.useFakeTimers();
    boton = document.createElement('button');
    enlace = document.createElement('a');
    enlace.href = '/rubros';
    otroCampo = document.createElement('input');
    parrafo = document.createElement('p');
    parrafo.tabIndex = -1;
    document.body.append(boton, enlace, otroCampo, parrafo);
  });

  afterEach(() => {
    boton.remove();
    enlace.remove();
    otroCampo.remove();
    parrafo.remove();
    vi.useRealTimers();
  });

  it('tiene el foco apenas se renderiza', async () => {
    await crear();

    expect(document.activeElement).toBe(campo());
  });

  it('declara el campo como buscador sin autocompletar ni corrector', async () => {
    await crear();

    expect(campo().getAttribute('autocomplete')).toBe('off');
    expect(campo().getAttribute('spellcheck')).toBe('false');
    expect(campo().getAttribute('aria-label')).toBe('Buscar artículo');
  });

  it('expone aria-controls y aria-activedescendant según los inputs', async () => {
    await crear();
    expect(campo().hasAttribute('aria-controls')).toBe(false);
    expect(campo().hasAttribute('aria-activedescendant')).toBe(false);

    fixture.componentRef.setInput('controla', 'resultados');
    fixture.componentRef.setInput('opcionActiva', 'resultado-2');
    fixture.detectChanges();

    expect(campo().getAttribute('aria-controls')).toBe('resultados');
    expect(campo().getAttribute('aria-activedescendant')).toBe('resultado-2');
  });

  it('es un combobox con autocompletado en lista, expandido solo con la lista a la vista', async () => {
    await crear();

    expect(campo().getAttribute('role')).toBe('combobox');
    expect(campo().getAttribute('aria-autocomplete')).toBe('list');
    expect(campo().getAttribute('aria-expanded')).toBe('false');

    fixture.componentRef.setInput('controla', 'resultados');
    fixture.detectChanges();

    expect(campo().getAttribute('aria-expanded')).toBe('true');
  });

  it('el placeholder termina en «…»', async () => {
    await crear();

    expect(campo().getAttribute('placeholder')).toMatch(/…$/);
  });

  it('refleja el texto del model y lo actualiza al tipear', async () => {
    await crear();
    fixture.componentRef.setInput('texto', 'tornillo');
    fixture.detectChanges();
    expect(campo().value).toBe('tornillo');

    campo().value = 'martillo';
    campo().dispatchEvent(new Event('input'));

    expect(fixture.componentInstance.texto()).toBe('martillo');
  });

  it('recupera el foco cuando pasa al fondo de la pantalla (sin destino)', async () => {
    await crear();

    campo().blur();
    expect(document.activeElement).toBe(document.body);
    vi.advanceTimersByTime(0);

    expect(document.activeElement).toBe(campo());
  });

  it('recupera el foco cuando pasa a un elemento no interactivo', async () => {
    await crear();

    parrafo.focus();
    expect(document.activeElement).toBe(parrafo);
    vi.advanceTimersByTime(0);

    expect(document.activeElement).toBe(campo());
  });

  it.each([
    ['un botón', () => boton],
    ['un enlace', () => enlace],
    ['otro campo de texto', () => otroCampo],
  ])('no roba el foco cuando pasa a %s', async (_nombre, destino) => {
    await crear();

    destino().focus();
    vi.advanceTimersByTime(0);

    expect(document.activeElement).toBe(destino());
  });

  it('Enter emite enter y evita la acción por defecto', async () => {
    await crear();
    const evento = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true });

    campo().dispatchEvent(evento);

    expect(enters).toBe(1);
    expect(evento.defaultPrevented).toBe(true);
  });

  it('flecha abajo emite mover(1) y flecha arriba mover(-1), sin acción por defecto', async () => {
    await crear();
    const abajo = new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true });
    const arriba = new KeyboardEvent('keydown', { key: 'ArrowUp', cancelable: true });

    campo().dispatchEvent(abajo);
    campo().dispatchEvent(arriba);

    expect(movimientos).toEqual([1, -1]);
    expect(abajo.defaultPrevented).toBe(true);
    expect(arriba.defaultPrevented).toBe(true);
    expect(enters).toBe(0);
  });

  it('otras teclas no emiten nada ni se cancelan', async () => {
    await crear();
    const letra = new KeyboardEvent('keydown', { key: 'a', cancelable: true });

    campo().dispatchEvent(letra);

    expect(enters).toBe(0);
    expect(movimientos).toEqual([]);
    expect(letra.defaultPrevented).toBe(false);
  });

  describe('escaneo (ráfaga de teclas rápidas + Enter)', () => {
    /** Tipea `texto` tecla por tecla, con `gap` ms entre una y otra, como el navegador: keydown e input. */
    function tipear(texto: string, gap: number) {
      for (const caracter of texto) {
        vi.advanceTimersByTime(gap);
        campo().dispatchEvent(new KeyboardEvent('keydown', { key: caracter, cancelable: true }));
        campo().value += caracter;
        campo().dispatchEvent(new Event('input'));
      }
    }

    function apretarEnter(gap: number) {
      vi.advanceTimersByTime(gap);
      campo().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    }

    it('una ráfaga rápida + Enter reemplaza el texto previo tipeado a mano, ya antes de emitir enter', async () => {
      await crear();
      let textoAlEnter = '';
      fixture.componentInstance.enter.subscribe(
        () => (textoAlEnter = fixture.componentInstance.texto()),
      );
      tipear('tor', 150);
      expect(fixture.componentInstance.texto()).toBe('tor');

      vi.advanceTimersByTime(500);
      tipear('7790001', 10);
      apretarEnter(10);

      expect(textoAlEnter).toBe('7790001');
      expect(fixture.componentInstance.texto()).toBe('7790001');
      expect(enters).toBe(1);
    });

    it('con otro texto previo y otro código, también reemplaza (triangulación)', async () => {
      await crear();
      tipear('martillo', 120);

      vi.advanceTimersByTime(1000);
      tipear('TOR-014', 8);
      apretarEnter(8);

      expect(fixture.componentInstance.texto()).toBe('TOR-014');
    });

    it('una ráfaga sin texto previo deja el código tal cual', async () => {
      await crear();

      tipear('7790001', 10);
      apretarEnter(10);

      expect(fixture.componentInstance.texto()).toBe('7790001');
      expect(enters).toBe(1);
    });

    it('el tipeo humano (teclas lentas) + Enter no toca el texto', async () => {
      await crear();

      tipear('tornillo 1/4', 120);
      apretarEnter(120);

      expect(fixture.componentInstance.texto()).toBe('tornillo 1/4');
      expect(enters).toBe(1);
    });

    it('una ráfaga corta (menos de 4 teclas) con texto previo no reemplaza nada', async () => {
      await crear();
      tipear('tor', 150);

      vi.advanceTimersByTime(500);
      tipear('779', 10);
      apretarEnter(10);

      expect(fixture.componentInstance.texto()).toBe('tor779');
    });

    it('la tecla Shift (códigos en mayúscula) no corta la ráfaga', async () => {
      await crear();
      tipear('tor', 150);

      vi.advanceTimersByTime(500);
      for (const caracter of 'TOR-014') {
        vi.advanceTimersByTime(5);
        campo().dispatchEvent(new KeyboardEvent('keydown', { key: 'Shift' }));
        vi.advanceTimersByTime(5);
        campo().dispatchEvent(new KeyboardEvent('keydown', { key: caracter, shiftKey: true }));
        campo().value += caracter;
        campo().dispatchEvent(new Event('input'));
      }
      apretarEnter(5);

      expect(fixture.componentInstance.texto()).toBe('TOR-014');
    });

    it('un Backspace en medio de la ráfaga la corta: es una persona editando', async () => {
      await crear();
      tipear('tor', 150);

      vi.advanceTimersByTime(500);
      tipear('7790', 10);
      vi.advanceTimersByTime(10);
      campo().dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }));
      apretarEnter(10);

      expect(fixture.componentInstance.texto()).toBe('tor7790');
    });
  });

  it('enfocar() devuelve el foco al campo', async () => {
    await crear();
    boton.focus();

    fixture.componentInstance.enfocar();

    expect(document.activeElement).toBe(campo());
  });

  it('seleccionarTodo() selecciona el texto para que el próximo escaneo lo reemplace', async () => {
    await crear();
    fixture.componentRef.setInput('texto', '7790001');
    fixture.detectChanges();

    fixture.componentInstance.seleccionarTodo();

    expect(campo().selectionStart).toBe(0);
    expect(campo().selectionEnd).toBe('7790001'.length);
  });
});
