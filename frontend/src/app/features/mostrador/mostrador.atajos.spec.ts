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

  it('están F2, F3, F4, F6, F7, F8, F10 y Esc', () => {
    const teclas = ATAJOS.map((a) => a.tecla);
    for (const t of ['F2', 'F3', 'F4', 'F6', 'F7', 'F8', 'F10', 'Escape']) expect(teclas).toContain(t);
  });

  it('F9 no está (es del corte del cliente)', () => {
    expect(ATAJOS.map((a) => a.tecla)).not.toContain('F9');
  });

  describe('accionDeTecla', () => {
    it('traduce cada tecla a su acción', () => {
      expect(accionDeTecla('F2')).toBe('buscar');
      expect(accionDeTecla('F3')).toBe('cantidad');
      expect(accionDeTecla('F4')).toBe('cobrar');
      expect(accionDeTecla('F10')).toBe('quitar');
      expect(accionDeTecla('F7')).toBe('descuento');
      expect(accionDeTecla('F6')).toBe('descuentoTotal');
      expect(accionDeTecla('F8')).toBe('presupuesto');
      expect(accionDeTecla('Escape')).toBe('cerrar');
    });

    it('una tecla sin atajo da null', () => {
      for (const k of ['F5', 'F9', 'Delete', 'Enter', 'a', '']) expect(accionDeTecla(k)).toBeNull();
    });

    it('Remito está en la botonera pero sin tecla: F5 y F11 no sirven en el navegador, F8 y F9 ya son otras (RM-007)', () => {
      const remito = ATAJOS.find((a) => a.accion === 'remito')!;
      expect(remito).toMatchObject({ tecla: null, etiqueta: 'Remito', enBotonera: true });
      for (const k of ['F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'F7', 'F8', 'F9', 'F10', 'F11', 'Escape', 'Remito', 'remito', ''])
        expect(accionDeTecla(k)).not.toBe('remito');
    });

    it('Vaciar está en el mapa pero sin tecla, y ninguna tecla lo devuelve (R-030, R-038)', () => {
      const vaciar = ATAJOS.find((a) => a.accion === 'vaciar')!;
      expect(vaciar).toMatchObject({ tecla: null, etiqueta: 'Vaciar', enBotonera: true });
      for (const k of ['F1', 'F2', 'F3', 'F4', 'F10', 'Escape', 'Delete', 'Backspace', 'Vaciar', 'vaciar', ''])
        expect(accionDeTecla(k)).not.toBe('vaciar');
    });
  });

  describe('botonera', () => {
    it('muestra Buscar, Cantidad, los descuentos, Cobrar, Presupuesto, Remito (sin tecla), Quitar y Vaciar (sin tecla), y no Esc', () => {
      expect(atajosDeBotonera().map((a) => `${a.tecla ?? ''} ${a.etiqueta}`.trim())).toEqual([
        'F2 Buscar',
        'F3 Cantidad',
        'F7 Descuento',
        'F6 Dto. total',
        'F4 Cobrar',
        'F8 Presupuesto',
        'Remito',
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
