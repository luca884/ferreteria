import { ATAJOS, atajosDeBotonera, accionDeTecla } from './mostrador.atajos';

describe('mapa de atajos del mostrador', () => {
  it('las teclas son únicas', () => {
    const teclas = ATAJOS.flatMap((a) => (a.tecla === null ? [] : [a.tecla]));
    expect(new Set(teclas).size).toBe(teclas.length);
  });

  it('las acciones son únicas', () => {
    const acciones = ATAJOS.map((a) => a.accion);
    expect(new Set(acciones).size).toBe(acciones.length);
  });

  it('están F2, F3, F4, F10 y Esc', () => {
    const teclas = ATAJOS.map((a) => a.tecla);
    for (const t of ['F2', 'F3', 'F4', 'F10', 'Escape']) expect(teclas).toContain(t);
  });

  it('F7, F8 y F9 no están (son de los cortes 2 a 4)', () => {
    const teclas = ATAJOS.map((a) => a.tecla);
    for (const t of ['F7', 'F8', 'F9']) expect(teclas).not.toContain(t);
  });

  describe('accionDeTecla', () => {
    it('traduce cada tecla a su acción', () => {
      expect(accionDeTecla('F2')).toBe('buscar');
      expect(accionDeTecla('F3')).toBe('cantidad');
      expect(accionDeTecla('F4')).toBe('cobrar');
      expect(accionDeTecla('F10')).toBe('quitar');
      expect(accionDeTecla('Escape')).toBe('cerrar');
    });

    it('una tecla sin atajo da null', () => {
      for (const k of ['F5', 'F7', 'Delete', 'Enter', 'a', '']) expect(accionDeTecla(k)).toBeNull();
    });

    it('Vaciar está en el mapa pero sin tecla, y ninguna tecla lo devuelve (R-030, R-038)', () => {
      const vaciar = ATAJOS.find((a) => a.accion === 'vaciar')!;
      expect(vaciar).toMatchObject({ tecla: null, etiqueta: 'Vaciar', enBotonera: true });
      for (const k of ['F1', 'F2', 'F3', 'F4', 'F10', 'Escape', 'Delete', 'Backspace', 'Vaciar', 'vaciar', ''])
        expect(accionDeTecla(k)).not.toBe('vaciar');
    });
  });

  describe('botonera', () => {
    it('muestra Buscar, Cantidad, Cobrar, Quitar y Vaciar (sin tecla), y no Esc', () => {
      expect(atajosDeBotonera().map((a) => `${a.tecla ?? ''} ${a.etiqueta}`.trim())).toEqual([
        'F2 Buscar',
        'F3 Cantidad',
        'F4 Cobrar',
        'F10 Quitar',
        'Vaciar',
      ]);
    });

    it('sale del mismo mapa: cambiar F4 por F12 cambia manejador y botonera', () => {
      const cobrar = ATAJOS.find((a) => a.accion === 'cobrar')!;
      const original = cobrar.tecla;
      try {
        (cobrar as { tecla: string }).tecla = 'F12';
        expect(accionDeTecla('F12')).toBe('cobrar');
        expect(accionDeTecla('F4')).toBeNull();
        expect(atajosDeBotonera().find((a) => a.accion === 'cobrar')!.tecla).toBe('F12');
      } finally {
        (cobrar as { tecla: string | null }).tecla = original;
      }
    });
  });
});
