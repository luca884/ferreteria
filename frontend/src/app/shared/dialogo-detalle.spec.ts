import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DialogoDetalle } from './dialogo-detalle';

/** Una lista con una fila que abre su detalle, como Ventas: Anular a la izquierda e Imprimir como principal. */
@Component({
  imports: [DialogoDetalle],
  template: `
    <button id="fila" type="button">Venta N.º 20</button>
    @if (abierto()) {
      <app-dialogo-detalle titulo="Venta N.º 20" (cerrar)="cerrar()">
        <p id="cuerpo">Juan Pérez · Contado</p>
        <input id="campo" />
        @if (anidado()) {
          <div id="anidado" role="dialog" aria-modal="true" tabindex="-1">Imprimir</div>
        }
        <button accion-peligro id="anular" type="button" class="peligro" (click)="anuladas = anuladas + 1">
          Anular
        </button>
        <button accion id="imprimir" type="button" class="primario" (click)="impresas = impresas + 1">
          Imprimir
        </button>
      </app-dialogo-detalle>
    }
  `,
})
class Anfitrion {
  readonly abierto = signal(true);
  readonly anidado = signal(false);
  cerradas = 0;
  anuladas = 0;
  impresas = 0;

  cerrar(): void {
    this.cerradas++;
    this.abierto.set(false);
  }
}

describe('DialogoDetalle', () => {
  let fixture: ComponentFixture<Anfitrion>;

  const el = () => fixture.nativeElement as HTMLElement;
  const anfitrion = () => fixture.componentInstance;
  const dialogo = () => el().querySelector('[role="dialog"].dialogo-detalle') as HTMLElement;
  const porId = (id: string) => el().querySelector(`#${id}`) as HTMLElement;
  const equis = () => el().querySelector('.dialogo-detalle-cabecera button[aria-label="Cerrar"]') as HTMLButtonElement;
  const botonCerrar = () => el().querySelector('.dialogo-detalle-pie button.cerrar') as HTMLButtonElement;

  function tecla(key: string, en: Element = document.activeElement ?? document.body, shiftKey = false): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true });
    en.dispatchEvent(evento);
    return evento;
  }

  async function estabilizar(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Anfitrion] }).compileComponents();
    fixture = TestBed.createComponent(Anfitrion);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    anfitrion().abierto.set(false);
    await estabilizar();
    porId('fila').focus();
    anfitrion().abierto.set(true);
    await estabilizar();
  });

  afterEach(() => fixture.nativeElement.remove());

  it('es un pop-up modal con el título arriba, el contenido en el cuerpo y las acciones al pie', () => {
    expect(dialogo().getAttribute('aria-modal')).toBe('true');
    expect(dialogo().classList).toContain('dialogo');
    expect(dialogo().parentElement!.classList).toContain('dialogo-fondo');
    const titulo = dialogo().querySelector('.dialogo-detalle-cabecera h2')!;
    expect(titulo.textContent!.trim()).toBe('Venta N.º 20');
    expect(dialogo().getAttribute('aria-labelledby')).toBe(titulo.id);
    expect(dialogo().querySelector('.dialogo-detalle-cuerpo #cuerpo')!.textContent).toContain('Juan Pérez');

    const pie = Array.from(dialogo().querySelectorAll('.dialogo-detalle-pie button')).map((b) => b.textContent!.trim());
    expect(pie).toEqual(['Anular', 'Cerrar', 'Imprimir']);
    expect(dialogo().querySelector('.dialogo-detalle-pie .pie-peligro #anular')).not.toBeNull();
    expect(dialogo().querySelector('.dialogo-detalle-pie .pie-acciones #imprimir')).not.toBeNull();
    expect(botonCerrar().dataset['tecla']).toBe('Esc');
    expect(botonCerrar().getAttribute('aria-keyshortcuts')).toBe('Escape');
  });

  it('al abrir, el foco va a la ✕', () => {
    expect(document.activeElement).toBe(equis());
  });

  it('Esc cierra, esté donde esté el foco adentro', async () => {
    const evento = tecla('Escape');

    expect(anfitrion().cerradas).toBe(1);
    expect(evento.defaultPrevented).toBe(true);

    anfitrion().abierto.set(true);
    await estabilizar();
    porId('campo').focus();
    tecla('Escape');

    expect(anfitrion().cerradas).toBe(2);
  });

  it('la ✕ y el botón Cerrar del pie cierran', async () => {
    equis().click();
    expect(anfitrion().cerradas).toBe(1);

    anfitrion().abierto.set(true);
    await estabilizar();
    botonCerrar().click();

    expect(anfitrion().cerradas).toBe(2);
  });

  it('Enter no dispara ninguna acción por defecto', () => {
    porId('campo').focus();

    const enCampo = tecla('Enter');
    const enDialogo = tecla('Enter', dialogo());

    expect(anfitrion().cerradas).toBe(0);
    expect(anfitrion().anuladas).toBe(0);
    expect(anfitrion().impresas).toBe(0);
    expect(enCampo.defaultPrevented).toBe(false);
    expect(enDialogo.defaultPrevented).toBe(false);
  });

  it('Tab no sale del pop-up: desde la última acción vuelve a la ✕, y Shift+Tab desde la ✕ va a la última', () => {
    porId('imprimir').focus();
    tecla('Tab');
    expect(document.activeElement).toBe(equis());

    tecla('Tab', equis(), true);
    expect(document.activeElement).toBe(porId('imprimir'));
  });

  it('al cerrar, el foco vuelve a la fila que lo abrió', async () => {
    tecla('Escape');
    await estabilizar();

    expect(dialogo()).toBeNull();
    expect(document.activeElement).toBe(porId('fila'));
  });

  it('con otro diálogo abierto adentro, Esc en ese no cierra el detalle', async () => {
    anfitrion().anidado.set(true);
    await estabilizar();
    porId('anidado').focus();

    tecla('Escape');

    expect(anfitrion().cerradas).toBe(0);
  });

  it('un clic en el velo no le saca el foco al pop-up (Esc tiene que seguir andando)', () => {
    const fondo = dialogo().parentElement!;
    const evento = new MouseEvent('mousedown', { bubbles: true, cancelable: true });

    fondo.dispatchEvent(evento);

    expect(evento.defaultPrevented).toBe(true);
    expect(anfitrion().cerradas).toBe(0);
  });
});
