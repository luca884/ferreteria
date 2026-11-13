import { afterEach, describe, expect, it, vi } from 'vitest';
import { MOTIVO_A_LA_VISTA_MS, MotivoDeAccion, ariaDe } from './motivo-de-accion';

describe('MotivoDeAccion', () => {
  afterEach(() => vi.useRealTimers());

  it('sin motivo no toca lo que se ve y deja seguir la acción', () => {
    const motivo = new MotivoDeAccion();

    expect(motivo.mostrar(null)).toBe(false);
    expect(motivo.mostrar(undefined)).toBe(false);
    expect(motivo.visible()).toBeNull();
  });

  it('con motivo lo muestra y avisa que la acción no sigue', () => {
    const motivo = new MotivoDeAccion();

    expect(motivo.mostrar('Elegí un rubro de la lista para editarlo.')).toBe(true);
    expect(motivo.visible()).toBe('Elegí un rubro de la lista para editarlo.');
  });

  it('el motivo se va solo pasado el tiempo a la vista', () => {
    vi.useFakeTimers();
    const motivo = new MotivoDeAccion();
    motivo.mostrar('Elegí un rubro de la lista para editarlo.');

    vi.advanceTimersByTime(MOTIVO_A_LA_VISTA_MS - 1);
    expect(motivo.visible()).toBe('Elegí un rubro de la lista para editarlo.');

    vi.advanceTimersByTime(1);
    expect(motivo.visible()).toBeNull();
  });

  it('un motivo nuevo reemplaza al anterior y reinicia el tiempo, sin pisarse entre sí', () => {
    vi.useFakeTimers();
    const motivo = new MotivoDeAccion();
    motivo.mostrar('Primero.');
    vi.advanceTimersByTime(MOTIVO_A_LA_VISTA_MS - 1);

    motivo.mostrar('Segundo.');
    vi.advanceTimersByTime(MOTIVO_A_LA_VISTA_MS - 1);

    // El reloj del primero no lo borra antes de tiempo.
    expect(motivo.visible()).toBe('Segundo.');
    vi.advanceTimersByTime(1);
    expect(motivo.visible()).toBeNull();
  });

  it('limpiar lo saca antes de que se vaya solo', () => {
    vi.useFakeTimers();
    const motivo = new MotivoDeAccion();
    motivo.mostrar('Elegí un rubro de la lista para editarlo.');

    motivo.limpiar();

    expect(motivo.visible()).toBeNull();
    vi.advanceTimersByTime(MOTIVO_A_LA_VISTA_MS);
    expect(motivo.visible()).toBeNull();
  });

  it('ariaDe apaga el botón con el string «true» y lo deja prendido sin atributo', () => {
    expect(ariaDe(null)).toBeNull();
    expect(ariaDe('Elegí un rubro de la lista para editarlo.')).toBe('true');
  });
});
