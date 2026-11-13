import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MOTIVO_A_LA_VISTA_MS, MotivoDeAccion, Motivos, ariaDe } from './motivo-de-accion';

describe('MotivoDeAccion', () => {
  afterEach(() => vi.useRealTimers());

  /** La tabla de motivos de la pantalla, que se puede cambiar entre lecturas (elegir una fila la vacía). */
  function crear(motivos: Motivos<'editar' | 'baja'> = {}) {
    let tabla: Motivos<'editar' | 'baja'> = motivos;
    const motivo = TestBed.runInInjectionContext(() => new MotivoDeAccion(() => tabla));
    return { motivo, cambia: (nueva: Motivos<'editar' | 'baja'>) => (tabla = nueva) };
  }

  it('una acción que se puede usar no muestra nada y deja seguir', () => {
    const { motivo } = crear({ editar: null, baja: null });

    expect(motivo.mostrar('editar')).toBe(false);
    expect(motivo.visible()).toBeNull();
  });

  it('una acción bloqueada muestra su motivo y avisa que no siga', () => {
    const { motivo } = crear({ editar: 'Elegí un rubro de la lista para editarlo.' });

    expect(motivo.mostrar('editar')).toBe(true);
    expect(motivo.visible()).toBe('Elegí un rubro de la lista para editarlo.');
  });

  it('el motivo se va solo pasado el tiempo a la vista', () => {
    vi.useFakeTimers();
    const { motivo } = crear({ editar: 'Elegí un rubro de la lista para editarlo.' });
    motivo.mostrar('editar');

    vi.advanceTimersByTime(MOTIVO_A_LA_VISTA_MS - 1);
    expect(motivo.visible()).toBe('Elegí un rubro de la lista para editarlo.');

    vi.advanceTimersByTime(1);
    expect(motivo.visible()).toBeNull();
  });

  it('deja de mostrarse en cuanto la acción se puede usar, sin esperar el reloj ni volver a intentarla', () => {
    const { motivo, cambia } = crear({ editar: 'Elegí un rubro de la lista para editarlo.' });
    motivo.mostrar('editar');

    // Como cuando el usuario elige la fila: la regla ya no rige.
    cambia({ editar: null });

    expect(motivo.visible()).toBeNull();
  });

  it('sigue mostrándose si lo que se habilita es otra acción', () => {
    const { motivo, cambia } = crear({ editar: 'Elegí un rubro de la lista para editarlo.', baja: null });
    motivo.mostrar('editar');

    cambia({ editar: 'Elegí un rubro de la lista para editarlo.', baja: null });

    expect(motivo.visible()).toBe('Elegí un rubro de la lista para editarlo.');
  });

  it('un motivo nuevo reemplaza al anterior y reinicia el tiempo', () => {
    vi.useFakeTimers();
    const { motivo } = crear({ editar: 'Elegí un rubro para editarlo.', baja: 'Elegí un rubro para darlo de baja.' });
    motivo.mostrar('editar');
    vi.advanceTimersByTime(MOTIVO_A_LA_VISTA_MS - 1);

    motivo.mostrar('baja');
    vi.advanceTimersByTime(MOTIVO_A_LA_VISTA_MS - 1);

    // El reloj del primero no lo borra antes de tiempo.
    expect(motivo.visible()).toBe('Elegí un rubro para darlo de baja.');
    vi.advanceTimersByTime(1);
    expect(motivo.visible()).toBeNull();
  });

  it('limpiar lo saca antes de que se vaya solo', () => {
    vi.useFakeTimers();
    const { motivo } = crear({ editar: 'Elegí un rubro de la lista para editarlo.' });
    motivo.mostrar('editar');

    motivo.limpiar();

    expect(motivo.visible()).toBeNull();
    vi.advanceTimersByTime(MOTIVO_A_LA_VISTA_MS);
    expect(motivo.visible()).toBeNull();
  });

  it('al cerrar la pantalla se cancela el reloj pendiente', () => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({ imports: [Pantalla] });
    const pantalla = TestBed.createComponent(Pantalla);
    const antes = vi.getTimerCount();
    pantalla.componentInstance.motivo.mostrar('editar');
    expect(vi.getTimerCount()).toBe(antes + 1);

    pantalla.destroy();

    expect(vi.getTimerCount()).toBe(antes);
  });

  it('ariaDe apaga el botón con el string «true» y lo deja prendido sin atributo', () => {
    expect(ariaDe(null)).toBeNull();
    expect(ariaDe(undefined)).toBeNull();
    expect(ariaDe('Elegí un rubro de la lista para editarlo.')).toBe('true');
  });
});

/** Lo mínimo para probar que el reloj se cancela con el componente, como lo crea una pantalla de verdad. */
@Component({ template: '' })
class Pantalla {
  readonly motivo = new MotivoDeAccion(() => ({ editar: 'Elegí un rubro de la lista para editarlo.' }));
}
