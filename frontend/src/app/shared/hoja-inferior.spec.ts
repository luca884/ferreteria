import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HojaInferior } from './hoja-inferior';

/** Una fila con «Más acciones» que abre la hoja, como Artículos o el Mostrador. */
@Component({
  imports: [HojaInferior],
  template: `
    <button id="origen" type="button">Más</button>
    @if (abierta()) {
      <app-hoja-inferior titulo="Tornillo 6mm" [subtitulo]="subtitulo()" (cerrar)="cerrar()">
        <button id="editar" type="button" class="accion-hoja">Editar</button>
        <button id="baja" type="button" class="accion-hoja peligro">Dar de baja</button>
        @if (anidado()) {
          <div id="anidado" role="dialog" aria-modal="true" tabindex="-1">Confirmar</div>
        }
      </app-hoja-inferior>
    }
  `,
})
class Anfitrion {
  readonly abierta = signal(true);
  readonly subtitulo = signal('ART-001');
  readonly anidado = signal(false);
  cerradas = 0;

  cerrar(): void {
    this.cerradas++;
    this.abierta.set(false);
  }
}

describe('HojaInferior', () => {
  let fixture: ComponentFixture<Anfitrion>;

  const el = () => fixture.nativeElement as HTMLElement;
  const anfitrion = () => fixture.componentInstance;
  const hoja = () => el().querySelector('[role="dialog"].hoja-inferior') as HTMLElement | null;
  const velo = () => el().querySelector('.hoja-inferior-fondo') as HTMLElement;
  const porId = (id: string) => el().querySelector(`#${id}`) as HTMLElement;

  function tecla(key: string, en: Element = document.activeElement ?? document.body): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
    en.dispatchEvent(evento);
    return evento;
  }

  async function abrir(): Promise<void> {
    fixture = TestBed.createComponent(Anfitrion);
    document.body.appendChild(fixture.nativeElement);
    porId('origen')?.focus();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Anfitrion] }).compileComponents();
  });

  afterEach(() => fixture.nativeElement.remove());

  it('es un diálogo modal con el título como nombre, el subtítulo y las acciones adentro', async () => {
    await abrir();

    expect(hoja()?.getAttribute('aria-modal')).toBe('true');
    const titulo = hoja()?.querySelector('h2') as HTMLElement;
    expect(titulo.textContent?.trim()).toBe('Tornillo 6mm');
    expect(hoja()?.getAttribute('aria-labelledby')).toBe(titulo.id);
    expect(hoja()?.querySelector('.hoja-inferior-subtitulo')?.textContent?.trim()).toBe('ART-001');
    expect(hoja()?.querySelectorAll('.accion-hoja').length).toBe(2);
  });

  it('sin subtítulo no dibuja el renglón vacío', async () => {
    await abrir();
    anfitrion().subtitulo.set('');
    fixture.detectChanges();

    expect(hoja()?.querySelector('.hoja-inferior-subtitulo')).toBeNull();
  });

  it('al abrir, el foco entra en la hoja', async () => {
    await abrir();

    expect(document.activeElement).toBe(hoja());
  });

  it('Esc cierra la hoja', async () => {
    await abrir();

    const evento = tecla('Escape');

    expect(anfitrion().cerradas).toBe(1);
    expect(evento.defaultPrevented).toBe(true);
  });

  it('con otro diálogo abierto encima, Esc es de ese y la hoja sigue', async () => {
    await abrir();
    anfitrion().anidado.set(true);
    fixture.detectChanges();

    tecla('Escape', porId('anidado'));

    expect(anfitrion().cerradas).toBe(0);
  });

  it('un toque en el velo cierra, y uno en la hoja no', async () => {
    await abrir();

    porId('editar').click();
    hoja()?.click();
    expect(anfitrion().cerradas).toBe(0);

    velo().click();
    expect(anfitrion().cerradas).toBe(1);
  });

  it('el velo no le saca el foco a la hoja (si no, Esc dejaría de llegarle)', async () => {
    await abrir();
    const evento = new MouseEvent('mousedown', { bubbles: true, cancelable: true });

    velo().dispatchEvent(evento);

    expect(evento.defaultPrevented).toBe(true);
  });

  it('Tab en la última acción vuelve a la primera: no se escapa de la hoja', async () => {
    await abrir();
    porId('baja').focus();

    const evento = tecla('Tab');

    expect(evento.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(porId('editar'));
  });

  it('al cerrar, el foco vuelve al botón desde el que se abrió', async () => {
    await abrir();

    anfitrion().cerrar();
    fixture.detectChanges();

    expect(hoja()).toBeNull();
    expect(document.activeElement).toBe(porId('origen'));
  });
});
