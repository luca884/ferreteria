import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { DetallePantalla } from './detalle-pantalla';

@Component({
  imports: [DetallePantalla],
  template: `
    <button id="origen" type="button">Origen</button>
    <app-detalle-pantalla [titulo]="titulo()" (cerrar)="cerrar()">
      <button mas type="button" id="mas">⋯</button>
      <p class="contenido">Contenido</p>
      @if (conPie()) {
        <button pie type="button" id="pie">Imprimir</button>
      }
    </app-detalle-pantalla>
  `,
})
class Anfitrion {
  readonly titulo = signal('Venta N.º 5');
  readonly conPie = signal(true);
  readonly cerrar = vi.fn();
}

describe('DetallePantalla', () => {
  let fixture: ComponentFixture<Anfitrion>;
  const el = () => fixture.nativeElement as HTMLElement;
  const q = (selector: string) => el().querySelector<HTMLElement>(selector);

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Anfitrion] }).compileComponents();
    fixture = TestBed.createComponent(Anfitrion);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  it('es un diálogo modal con el título, «Volver», el ⋯ en la barra, el contenido en el cuerpo y las acciones en el pie', () => {
    const dialogo = q('[role="dialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(dialogo.getAttribute('aria-labelledby')).toBe(q('h2')!.id);
    expect(q('h2')!.textContent?.trim()).toBe('Venta N.º 5');
    expect(q('.ficha-barra .ficha-volver')?.getAttribute('aria-label')).toBe('Volver');
    expect(q('.ficha-barra #mas')).not.toBeNull();
    expect(q('.detalle-pantalla-cuerpo .contenido')).not.toBeNull();
    expect(q('.detalle-pantalla-pie #pie')).not.toBeNull();
  });

  it('abre con el foco en «Volver»', () => {
    expect(document.activeElement).toBe(q('.ficha-volver'));
  });

  it('«Volver» y Esc piden cerrar; Esc ya atendido por otro no cierra', () => {
    q('.ficha-volver')!.click();
    expect(fixture.componentInstance.cerrar).toHaveBeenCalledTimes(1);

    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    expect(fixture.componentInstance.cerrar).toHaveBeenCalledTimes(2);

    const atendido = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    atendido.preventDefault();
    document.body.dispatchEvent(atendido);
    expect(fixture.componentInstance.cerrar).toHaveBeenCalledTimes(2);
  });

  it('un diálogo abierto encima se queda con su Esc', () => {
    const encima = document.createElement('div');
    encima.setAttribute('role', 'dialog');
    encima.setAttribute('aria-modal', 'true');
    document.body.appendChild(encima);
    const campo = document.createElement('input');
    encima.appendChild(campo);

    campo.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));

    expect(fixture.componentInstance.cerrar).not.toHaveBeenCalled();
    encima.remove();
  });

  it('sin acciones el pie queda vacío (el CSS lo esconde)', () => {
    fixture.componentInstance.conPie.set(false);
    fixture.detectChanges();

    expect(q('.detalle-pantalla-pie')!.children).toHaveLength(0);
  });
});
