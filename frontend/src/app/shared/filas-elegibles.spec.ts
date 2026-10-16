import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { FilasElegibles } from './filas-elegibles';

interface Fila {
  id: number;
  nombre: string;
}

@Component({
  template: `
    <table>
      <tbody>
        @for (fila of lista(); track fila.id; let i = $index) {
          <tr
            [attr.aria-selected]="filas.elegidoId() === fila.id"
            [attr.tabindex]="filas.esTabulable(fila, i) ? 0 : -1"
            (click)="filas.elegir(fila)"
            (keydown)="filas.alTeclear($event, fila, i)"
          >
            <td>{{ fila.nombre }}</td>
          </tr>
        }
      </tbody>
    </table>
  `,
})
class Anfitrion {
  readonly lista = signal<Fila[]>([
    { id: 7, nombre: 'Uno' },
    { id: 8, nombre: 'Dos' },
    { id: 9, nombre: 'Tres' },
  ]);
  readonly habilitado = signal(true);
  readonly alEnter = vi.fn();
  readonly filas = new FilasElegibles(this.lista, this.habilitado, (fila) => this.alEnter(fila));
}

describe('FilasElegibles', () => {
  let fixture: ComponentFixture<Anfitrion>;
  const anfitrion = () => fixture.componentInstance;
  const filas = () => Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLTableRowElement>('tbody tr'));
  const tabindex = () => filas().map((f) => f.getAttribute('tabindex'));

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function tecla(fila: number, key: string): Promise<KeyboardEvent> {
    const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
    filas()[fila].dispatchEvent(evento);
    await refrescar();
    return evento;
  }

  beforeEach(async () => {
    fixture = TestBed.createComponent(Anfitrion);
    document.body.appendChild(fixture.nativeElement);
    await refrescar();
  });

  afterEach(() => (fixture.nativeElement as HTMLElement).remove());

  it('sin elegida, solo la primera fila entra en el orden de Tab', () => {
    expect(anfitrion().filas.elegido()).toBeNull();
    expect(tabindex()).toEqual(['0', '-1', '-1']);
  });

  it('clic elige la fila y la deja como la única tabulable', async () => {
    filas()[1].click();
    await refrescar();

    expect(anfitrion().filas.elegido()?.nombre).toBe('Dos');
    expect(tabindex()).toEqual(['-1', '0', '-1']);
  });

  it('↓ y ↑ mueven la elección y el foco sin salir de la lista', async () => {
    filas()[0].focus();
    expect((await tecla(0, 'ArrowDown')).defaultPrevented).toBe(true);
    expect(anfitrion().filas.elegidoId()).toBe(8);
    expect(document.activeElement).toBe(filas()[1]);

    await tecla(1, 'ArrowDown');
    await tecla(2, 'ArrowDown');
    expect(anfitrion().filas.elegidoId()).toBe(9);

    await tecla(2, 'ArrowUp');
    expect(anfitrion().filas.elegidoId()).toBe(8);
    expect(document.activeElement).toBe(filas()[1]);
  });

  it('Enter llama a la acción con la fila', async () => {
    const evento = await tecla(2, 'Enter');

    expect(evento.defaultPrevented).toBe(true);
    expect(anfitrion().alEnter).toHaveBeenCalledWith({ id: 9, nombre: 'Tres' });
  });

  it('otras teclas no hacen nada', async () => {
    const evento = await tecla(0, 'a');

    expect(evento.defaultPrevented).toBe(false);
    expect(anfitrion().filas.elegidoId()).toBeNull();
  });

  it('deshabilitado no elige ni responde al teclado', async () => {
    anfitrion().habilitado.set(false);
    filas()[1].click();
    await tecla(1, 'ArrowDown');
    await tecla(1, 'Enter');

    expect(anfitrion().filas.elegidoId()).toBeNull();
    expect(anfitrion().alEnter).not.toHaveBeenCalled();
  });

  it('la elegida que ya no está en la lista deja de estar elegida', async () => {
    filas()[2].click();
    anfitrion().lista.set([{ id: 7, nombre: 'Uno' }]);
    await refrescar();

    expect(anfitrion().filas.elegido()).toBeNull();
    expect(tabindex()).toEqual(['0']);
  });
});
