import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Rubro } from './rubro.models';
import { Rubros } from './rubros';
import { RubrosService } from './rubros.service';

describe('Rubros', () => {
  const buloneria: Rubro = { id: 1, nombre: 'Bulonería', margenPorcentaje: 35 };
  const pinturas: Rubro = { id: 2, nombre: 'Pinturas', margenPorcentaje: 40 };

  let fixture: ComponentFixture<Rubros>;
  let servicio: {
    listar: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    actualizar: ReturnType<typeof vi.fn>;
    articulosQueHeredan: ReturnType<typeof vi.fn>;
  };

  const errorHttp = (status: number, message: string) =>
    new HttpErrorResponse({ status, error: { status, message } });

  async function crear(lista: Rubro[], dueno = false) {
    servicio = {
      listar: vi.fn(() => of(lista)),
      crear: vi.fn(),
      actualizar: vi.fn((id: number, r: { nombre: string; margenPorcentaje: number }) => of({ id, ...r })),
      articulosQueHeredan: vi.fn(() => of(23)),
    };
    await TestBed.configureTestingModule({
      imports: [Rubros],
      providers: [
        { provide: RubrosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Rubros);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const filas = () => Array.from(el().querySelectorAll<HTMLTableRowElement>('tbody tr'));
  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;

  async function escribir(id: string, valor: string) {
    input(id).value = valor;
    input(id).dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function enviar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  async function clickEn(selector: string, indice = 0) {
    el().querySelectorAll<HTMLButtonElement>(selector)[indice].click();
    await refrescar();
  }

  const barra = (selector: string) => el().querySelector<HTMLButtonElement>(`app-barra-acciones ${selector}`);
  const elegidas = () => filas().filter((f) => f.getAttribute('aria-selected') === 'true');

  /** Clic en la fila: la elige. */
  async function elegir(fila: number) {
    (filas()[fila].querySelector('td') as HTMLElement).click();
    await refrescar();
  }

  /** Elige la fila y toca Editar en la barra. */
  async function editarFila(fila: number) {
    await elegir(fila);
    barra('button.editar')!.click();
    await refrescar();
  }

  async function teclaEnFila(fila: number, key: string) {
    filas()[fila].dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    await refrescar();
  }

  /** Los textos a los que apunta el aria-describedby del campo. */
  const descripciones = (id: string) =>
    (input(id).getAttribute('aria-describedby') ?? '')
      .split(' ')
      .filter((ref) => ref !== '')
      .map((ref) => el().querySelector(`#${ref}`)?.textContent?.trim());

  it('el título va en la barra de acciones de arriba', async () => {
    await crear([buloneria]);

    expect(el().querySelector('app-barra-acciones h1')?.textContent?.trim()).toBe('Rubros');
  });

  it('lista los rubros con su margen', async () => {
    await crear([buloneria, pinturas]);

    const textos = filas().map((f) => f.textContent ?? '');
    expect(textos).toHaveLength(2);
    expect(textos[0]).toContain('Bulonería');
    expect(textos[0]).toContain('35 %');
  });

  it('la barra de estado cuenta los rubros', async () => {
    await crear([buloneria, pinturas]);
    expect(el().querySelector('.barra-estado')?.textContent?.trim()).toBe('2 rubros');

    TestBed.resetTestingModule();
    await crear([buloneria]);
    expect(el().querySelector('.barra-estado')?.textContent?.trim()).toBe('1 rubro');
  });

  it('el Vendedor no ve el alta ni Editar, y las filas no se eligen', async () => {
    await crear([buloneria]);

    expect(el().querySelector('form')).toBeNull();
    expect(el().querySelector('button.editar')).toBeNull();
    expect(el().querySelector('button.nuevo')).toBeNull();
    await elegir(0);
    expect(filas()[0].getAttribute('aria-selected')).toBeNull();
    expect(filas()[0].getAttribute('tabindex')).toBeNull();
  });

  describe('barra de acciones sobre la fila elegida', () => {
    it('el Dueño ve Nuevo y Editar en la barra y ningún botón en las filas', async () => {
      await crear([buloneria, pinturas], true);

      expect(barra('button.nuevo')).not.toBeNull();
      expect(barra('button.editar')).not.toBeNull();
      expect(el().querySelectorAll('tbody button')).toHaveLength(0);
    });

    it('sin fila elegida Editar está deshabilitado; al elegir una se habilita y queda marcada', async () => {
      await crear([buloneria, pinturas], true);
      expect(barra('button.editar')!.disabled).toBe(true);

      await elegir(1);

      expect(elegidas()).toEqual([filas()[1]]);
      expect(barra('button.editar')!.disabled).toBe(false);
    });

    it('Enter o doble clic en la fila la editan', async () => {
      await crear([buloneria, pinturas], true);

      await teclaEnFila(1, 'Enter');
      expect(input('nombre').value).toBe('Pinturas');

      filas()[0].dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
      await refrescar();
      expect(input('nombre').value).toBe('Bulonería');
    });

    it('↓ y ↑ mueven la fila elegida y el foco; Tab entra por la elegida', async () => {
      await crear([buloneria, pinturas], true);
      expect(filas().map((f) => f.getAttribute('tabindex'))).toEqual(['0', '-1']);
      filas()[0].focus();
      await refrescar();
      expect(elegidas()).toEqual([filas()[0]]);

      await teclaEnFila(0, 'ArrowDown');
      expect(elegidas()).toEqual([filas()[1]]);
      expect(document.activeElement).toBe(filas()[1]);
      expect(filas().map((f) => f.getAttribute('tabindex'))).toEqual(['-1', '0']);

      await teclaEnFila(1, 'ArrowUp');
      expect(elegidas()).toEqual([filas()[0]]);
    });

    it('Nuevo deja el formulario en alta y enfoca el nombre', async () => {
      await crear([buloneria], true);
      await editarFila(0);
      expect(el().querySelector('form h2')?.textContent).toContain('Editar rubro');

      barra('button.nuevo')!.click();
      await refrescar();

      expect(el().querySelector('form h2')?.textContent).toContain('Nuevo rubro');
      expect(input('nombre').value).toBe('');
      expect(document.activeElement).toBe(input('nombre'));
    });
  });

  describe('campos', () => {
    it('no autocompletan ni corrigen, tienen nombre y el ejemplo termina en «…»', async () => {
      await crear([], true);

      for (const id of ['nombre', 'margen']) {
        expect(input(id).getAttribute('autocomplete')).toBe('off');
        expect(input(id).name).not.toBe('');
      }
      expect(input('nombre').getAttribute('spellcheck')).toBe('false');
      expect(input('nombre').placeholder.endsWith('…')).toBe(true);
      expect(input('margen').getAttribute('inputmode')).toBe('decimal');
    });

    it('al enviar con errores marca los inválidos, enlaza sus mensajes y enfoca el primero', async () => {
      await crear([], true);
      await escribir('margen', '-5');

      await enviar();

      expect(input('nombre').getAttribute('aria-invalid')).toBe('true');
      expect(descripciones('nombre')).toEqual(['El nombre es obligatorio.']);
      expect(input('margen').getAttribute('aria-invalid')).toBe('true');
      expect(descripciones('margen')).toEqual(['El margen no puede ser negativo.']);
      expect(document.activeElement).toBe(input('nombre'));
    });

    it('al editar, la pista del margen queda enlazada al campo', async () => {
      await crear([buloneria], true);

      await editarFila(0);

      expect(descripciones('margen')).toEqual([
        'Si cambiás el margen, se recalcula el precio de los artículos que lo usan.',
      ]);
    });
  });

  describe('validaciones', () => {
    it('nombre vacío y margen negativo: cada mensaje junto a su campo, con punto final, y no manda nada', async () => {
      await crear([], true);
      await escribir('nombre', '   ');
      await escribir('margen', '-5');

      await enviar();

      expect(el().querySelector('.campo.nombre .campo-error')?.textContent?.trim()).toBe('El nombre es obligatorio.');
      expect(el().querySelector('.campo.margen .campo-error')?.textContent?.trim()).toBe(
        'El margen no puede ser negativo.',
      );
      expect(servicio.crear).not.toHaveBeenCalled();
    });

    it('margen vacío o mayor a 1000', async () => {
      await crear([], true);
      await escribir('nombre', 'Electricidad');
      await escribir('margen', '');
      await enviar();
      expect(el().querySelector('.campo.margen .campo-error')?.textContent?.trim()).toBe('El margen es obligatorio.');

      await escribir('margen', '1001');
      expect(el().querySelector('.campo.margen .campo-error')?.textContent?.trim()).toBe(
        'El margen no puede superar 1000 %.',
      );
      expect(servicio.crear).not.toHaveBeenCalled();
    });

    it('el aviso de error del backend se borra al escribir', async () => {
      await crear([buloneria], true);
      servicio.crear.mockReturnValue(throwError(() => errorHttp(409, 'Ya existe un rubro llamado Bulonería.')));
      await escribir('nombre', 'Bulonería');

      await enviar();
      expect(el().querySelector('[role="alert"]')?.textContent).toContain('Ya existe un rubro llamado Bulonería.');

      await escribir('nombre', 'Bulonería fina');
      expect(el().querySelector('[role="alert"]')).toBeNull();
    });
  });

  describe('edición', () => {
    it('Editar precarga el rubro; guardar solo el nombre no pregunta nada', async () => {
      await crear([buloneria, pinturas], true);

      await editarFila(0);
      expect(el().querySelector('form h2')?.textContent).toContain('Editar rubro');
      expect(input('nombre').value).toBe('Bulonería');
      expect(input('margen').value).toBe('35');

      await escribir('nombre', 'Bulonería y tornillería');
      await enviar();

      expect(servicio.articulosQueHeredan).not.toHaveBeenCalled();
      expect(servicio.actualizar).toHaveBeenCalledWith(1, { nombre: 'Bulonería y tornillería', margenPorcentaje: 35 });
      expect(filas()[0].textContent).toContain('Bulonería y tornillería');
      expect(input('nombre').value).toBe('');
      expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se guardó «Bulonería y tornillería».');
    });

    it('si cambia el margen avisa cuántos artículos cambian de precio antes de guardar', async () => {
      await crear([buloneria], true);
      await editarFila(0);
      await escribir('margen', '40');

      await enviar();

      expect(servicio.articulosQueHeredan).toHaveBeenCalledWith(1);
      expect(servicio.actualizar).not.toHaveBeenCalled();
      const dialogo = el().querySelector('app-confirmacion')!;
      expect(dialogo.querySelector('h2')?.textContent).toContain('¿Cambiar el margen de Bulonería de 35 % a 40 %?');
      expect(dialogo.querySelector('p')?.textContent).toContain(
        'Se recalcula el precio de 23 artículos que usan el margen del rubro.',
      );

      await clickEn('app-confirmacion button.confirmar');

      expect(servicio.actualizar).toHaveBeenCalledWith(1, { nombre: 'Bulonería', margenPorcentaje: 40 });
      expect(filas()[0].textContent).toContain('40 %');
      expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se recalcularon 23 precios.');
    });

    it('cancelar el aviso no guarda y deja el formulario como estaba', async () => {
      await crear([buloneria], true);
      await editarFila(0);
      await escribir('margen', '40');
      await enviar();

      await clickEn('app-confirmacion button.cancelar');

      expect(servicio.actualizar).not.toHaveBeenCalled();
      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(input('margen').value).toBe('40');
    });

    it('si ningún artículo hereda el margen guarda sin preguntar', async () => {
      await crear([buloneria], true);
      servicio.articulosQueHeredan.mockReturnValue(of(0));
      await editarFila(0);
      await escribir('margen', '40');

      await enviar();

      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(servicio.actualizar).toHaveBeenCalled();
    });

    it('un solo artículo va en singular', async () => {
      await crear([buloneria], true);
      servicio.articulosQueHeredan.mockReturnValue(of(1));
      await editarFila(0);
      await escribir('margen', '40');

      await enviar();

      expect(el().querySelector('app-confirmacion p')?.textContent).toContain(
        'Se recalcula el precio de 1 artículo que usa el margen del rubro.',
      );
    });

    it('si no se puede contar, igual avisa con un texto claro', async () => {
      await crear([buloneria], true);
      servicio.articulosQueHeredan.mockReturnValue(throwError(() => errorHttp(500, 'x')));
      await editarFila(0);
      await escribir('margen', '40');

      await enviar();

      expect(el().querySelector('app-confirmacion p')?.textContent).toContain(
        'Se recalcula el precio de los artículos que usan el margen del rubro.',
      );
    });

    it('Cancelar la edición vuelve al alta', async () => {
      await crear([buloneria], true);
      await editarFila(0);

      await clickEn('button.cancelar');

      expect(el().querySelector('form h2')?.textContent).toContain('Nuevo rubro');
      expect(input('nombre').value).toBe('');
      expect(servicio.actualizar).not.toHaveBeenCalled();
    });

    it('muestra el mensaje del backend si la edición falla', async () => {
      await crear([buloneria, pinturas], true);
      servicio.actualizar.mockReturnValue(throwError(() => errorHttp(409, 'Ya existe un rubro llamado Pinturas.')));
      await editarFila(0);
      await escribir('nombre', 'Pinturas');

      await enviar();

      expect(el().querySelector('[role="alert"]')?.textContent).toContain('Ya existe un rubro llamado Pinturas.');
    });
  });
});
