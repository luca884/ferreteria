import { Sesion } from '../../core/auth/auth.models';
import {
  borrarEtiquetasGuardadas,
  claveDeEtiquetas,
  guardarEtiquetas,
  leerEtiquetas,
  sumarEtiquetas,
} from './etiquetas.storage';

describe('etiquetas.storage', () => {
  const sesion = (userId: number, ferreteriaId: number | null = 1): Sesion => ({
    userId,
    nombre: 'Dueño',
    email: 'dueno@tornillo.demo',
    rol: ferreteriaId === null ? 'SUPER_ADMIN' : 'DUENO',
    ferreteriaId,
    ferreteriaNombre: ferreteriaId === null ? null : 'El Tornillo',
  });

  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('la clave es de la ferretería y el usuario; sin ferretería no hay clave', () => {
    expect(claveDeEtiquetas(sesion(7))).toBe('etiquetas:1:7');
    expect(claveDeEtiquetas(sesion(7, 2))).toBe('etiquetas:2:7');
    expect(claveDeEtiquetas(sesion(1, null))).toBeNull();
    expect(claveDeEtiquetas(null)).toBeNull();
  });

  it('guarda solo el id y las copias, y los lee en el mismo orden', () => {
    guardarEtiquetas('etiquetas:1:7', [
      { id: 5, copias: 3 },
      { id: 2, copias: 1 },
    ]);

    expect(JSON.parse(localStorage.getItem('etiquetas:1:7')!)).toEqual({
      v: 1,
      items: [
        { id: 5, copias: 3 },
        { id: 2, copias: 1 },
      ],
    });
    expect(leerEtiquetas('etiquetas:1:7')).toEqual([
      { id: 5, copias: 3 },
      { id: 2, copias: 1 },
    ]);
  });

  it('una lista vacía borra lo guardado', () => {
    guardarEtiquetas('etiquetas:1:7', [{ id: 5, copias: 3 }]);

    guardarEtiquetas('etiquetas:1:7', []);

    expect(localStorage.getItem('etiquetas:1:7')).toBeNull();
    expect(leerEtiquetas('etiquetas:1:7')).toEqual([]);
  });

  it('lo guardado roto se lee como lista vacía', () => {
    const rotos = [
      'no es json',
      '{"v":2,"items":[]}',
      '{"v":1}',
      '{"v":1,"items":[{"id":"5","copias":1}]}',
      '{"v":1,"items":[{"id":5,"copias":0}]}',
      '{"v":1,"items":[{"id":5,"copias":1000}]}',
      '{"v":1,"items":[{"id":5,"copias":1.5}]}',
      '{"v":1,"items":[{"id":5,"copias":1},{"id":5,"copias":2}]}',
    ];
    for (const roto of rotos) {
      localStorage.setItem('etiquetas:1:7', roto);
      expect(leerEtiquetas('etiquetas:1:7')).toEqual([]);
    }
  });

  it('al cerrar la sesión se borra solo la lista de ese usuario', () => {
    guardarEtiquetas('etiquetas:1:7', [{ id: 5, copias: 3 }]);
    guardarEtiquetas('etiquetas:1:8', [{ id: 6, copias: 1 }]);

    borrarEtiquetasGuardadas(sesion(7));

    expect(localStorage.getItem('etiquetas:1:7')).toBeNull();
    expect(leerEtiquetas('etiquetas:1:8')).toEqual([{ id: 6, copias: 1 }]);
  });

  describe('sumarEtiquetas (la selección múltiple de Artículos)', () => {
    it('agrega cada artículo con una copia, a continuación de lo que había, y devuelve cuántos quedan', () => {
      guardarEtiquetas('etiquetas:1:7', [{ id: 5, copias: 3 }]);

      const total = sumarEtiquetas('etiquetas:1:7', [8, 9]);

      expect(total).toBe(3);
      expect(leerEtiquetas('etiquetas:1:7')).toEqual([
        { id: 5, copias: 3 },
        { id: 8, copias: 1 },
        { id: 9, copias: 1 },
      ]);
    });

    it('si el artículo ya estaba suma una copia (CU-03) y no pasa de 999', () => {
      guardarEtiquetas('etiquetas:1:7', [
        { id: 5, copias: 3 },
        { id: 6, copias: 999 },
      ]);

      const total = sumarEtiquetas('etiquetas:1:7', [5, 6]);

      expect(total).toBe(2);
      expect(leerEtiquetas('etiquetas:1:7')).toEqual([
        { id: 5, copias: 4 },
        { id: 6, copias: 999 },
      ]);
    });

    it('con la lista vacía o rota arranca de cero, y un id repetido cuenta una vez', () => {
      localStorage.setItem('etiquetas:1:7', '{roto');

      expect(sumarEtiquetas('etiquetas:1:7', [4, 4])).toBe(1);
      expect(leerEtiquetas('etiquetas:1:7')).toEqual([{ id: 4, copias: 1 }]);
    });
  });
});
