import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../../core/auth/auth.service';
import { Sesion } from '../../core/auth/auth.models';
import { ArticuloMostrador } from './mostrador.models';
import { GENERAR_CLAVE, TicketStore } from './ticket.store';

function art(id = 1, over: Partial<ArticuloMostrador> = {}): ArticuloMostrador {
  return {
    id,
    codigoInterno: `COD-${id}`,
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: `Artículo ${id}`,
    precioFinal: 170,
    unidad: 'UNIDAD',
    stockActual: 12,
    stockMinimo: 5,
    ubicacion: null,
    activo: true,
    ...over,
  };
}

function sesion(over: Partial<Sesion> = {}): Sesion {
  return {
    userId: 7,
    nombre: 'Ana',
    email: 'ana@x.com',
    rol: 'VENDEDOR',
    ferreteriaId: 3,
    ferreteriaNombre: 'Tornillo',
    ...over,
  };
}

describe('TicketStore', () => {
  let n: number;

  function crear(s: Sesion | null = sesion()): TicketStore {
    TestBed.resetTestingModule();
    n = 0;
    TestBed.configureTestingModule({
      providers: [
        TicketStore,
        { provide: AuthService, useValue: { sesion: signal(s).asReadonly() } },
        { provide: GENERAR_CLAVE, useValue: () => `clave-${++n}` },
      ],
    });
    return TestBed.inject(TicketStore);
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('arranca vacío y sin clave', () => {
    const s = crear();
    expect(s.ticket().renglones).toEqual([]);
    expect(s.total()).toBe(0);
    expect(s.ticket().clave).toBe('');
  });

  it('agregar suma y calcula el total en centavos', () => {
    const s = crear();
    s.agregar(art(1));
    s.agregar(art(1));
    expect(s.ticket().renglones[0].cantidadMilesimas).toBe(2000);
    expect(s.total()).toBe(34000);
    expect(s.elegido()).toBe(0);
  });

  it('persiste en ticket:{ferreteriaId}:{usuarioId} y se recupera tras un F5', () => {
    const a = crear();
    a.agregar(art(1));
    a.agregar(art(2));
    TestBed.tick();
    expect(localStorage.getItem('ticket:3:7')).not.toBeNull();

    const b = crear();
    expect(b.ticket().renglones.map((r) => r.articuloId)).toEqual([1, 2]);
    expect(b.ticket().clave).toBe('clave-1'); // viene del almacenado, no se genera otra
    expect(b.total()).toBe(34000);
    expect(b.elegido()).toBe(1);
  });

  it('la clave nace con el primer renglón y se conserva al agregar más', () => {
    const s = crear();
    s.agregar(art(1));
    const clave = s.ticket().clave;
    s.agregar(art(2));
    s.cambiarCantidad(0, 3000);
    s.actualizarPrecios([{ articuloId: 1, precioActual: 180 }]);
    s.marcarProblemas([]);
    expect(clave).toBe('clave-1');
    expect(s.ticket().clave).toBe('clave-1');
    expect(n).toBe(1);
  });

  it('cambiarCantidad devuelve el error y no toca el ticket', () => {
    const s = crear();
    s.agregar(art(1));
    expect(s.cambiarCantidad(0, 2500)).toBe('Este artículo se vende por unidad: usá un número entero.');
    expect(s.ticket().renglones[0].cantidadMilesimas).toBe(1000);
    expect(s.cambiarCantidad(0, 2000)).toBeNull();
    expect(s.ticket().renglones[0].cantidadMilesimas).toBe(2000);
  });

  it('quitar y moverElegido actualizan el ticket', () => {
    const s = crear();
    s.agregar(art(1));
    s.agregar(art(2));
    s.moverElegido(-1);
    expect(s.elegido()).toBe(0);
    s.quitar(0);
    expect(s.ticket().renglones.map((r) => r.articuloId)).toEqual([2]);
  });

  it('vaciar deja el ticket vacío, borra la clave de storage y renueva la clave en el próximo renglón', () => {
    const s = crear();
    s.agregar(art(1));
    TestBed.tick();
    s.vaciar();
    TestBed.tick();
    expect(s.ticket().renglones).toEqual([]);
    expect(localStorage.getItem('ticket:3:7')).toBeNull();
    s.agregar(art(1));
    expect(s.ticket().clave).toBe('clave-2');
  });

  it('quitar el último renglón también borra la clave de storage', () => {
    const s = crear();
    s.agregar(art(1));
    TestBed.tick();
    s.quitar(0);
    TestBed.tick();
    expect(localStorage.getItem('ticket:3:7')).toBeNull();
  });

  it('con un ticket recuperado, los reintentos mantienen la clave', () => {
    const a = crear();
    a.agregar(art(1));
    TestBed.tick();
    const b = crear();
    b.agregar(art(2));
    expect(b.ticket().clave).toBe('clave-1');
  });

  describe('descuentos (GDS-12)', () => {
    it('aplicarDescuentoRenglon y aplicarDescuentoGlobal bajan el total y devuelven null si salió bien', () => {
      const s = crear();
      s.agregar(art(1));

      expect(s.aplicarDescuentoRenglon(0, 1000)).toBeNull();
      expect(s.total()).toBe(15300);
      expect(s.aplicarDescuentoGlobal(1000)).toBeNull();
      expect(s.total()).toBe(13770);
    });

    it('un descuento inválido devuelve el mensaje y no toca el ticket', () => {
      const s = crear();
      s.agregar(art(1));

      expect(s.aplicarDescuentoRenglon(0, 10000)).toBe('El descuento tiene que estar entre 0 y 99,99 %.');
      expect(s.aplicarDescuentoGlobal(-1)).toBe('El descuento tiene que estar entre 0 y 99,99 %.');
      expect(s.total()).toBe(17000);
    });

    it('los descuentos sobreviven a un F5', () => {
      const a = crear();
      a.agregar(art(1));
      a.aplicarDescuentoRenglon(0, 1050);
      a.aplicarDescuentoGlobal(500);
      TestBed.tick();

      const b = crear();

      expect(b.ticket().renglones[0].descuentoBp).toBe(1050);
      expect(b.ticket().descuentoGlobalBp).toBe(500);
      expect(b.total()).toBe(a.total());
    });

    it('un ticket guardado antes de los descuentos se lee sin ellos', () => {
      const viejo = {
        articuloId: 1, codigoInterno: 'A', descripcion: 'A', unidad: 'UNIDAD', precioCentavos: 100,
        cantidadMilesimas: 1000, stockActual: 1, stockMinimo: 0,
      };
      localStorage.setItem('ticket:3:7', JSON.stringify({ v: 1, clave: 'x', elegido: 0, renglones: [viejo] }));

      const s = crear();

      expect(s.ticket().renglones).toHaveLength(1);
      expect(s.total()).toBe(100);
    });

    it('un descuento guardado fuera de rango descarta el ticket', () => {
      const malo = {
        articuloId: 1, codigoInterno: 'A', descripcion: 'A', unidad: 'UNIDAD', precioCentavos: 100,
        cantidadMilesimas: 1000, stockActual: 1, stockMinimo: 0, descuentoBp: 12000,
      };
      localStorage.setItem('ticket:3:7', JSON.stringify({ v: 1, clave: 'x', elegido: 0, renglones: [malo] }));
      expect(crear().ticket().renglones).toEqual([]);

      const bueno = { ...malo, descuentoBp: 0 };
      localStorage.setItem(
        'ticket:3:7',
        JSON.stringify({ v: 1, clave: 'x', elegido: 0, renglones: [bueno], descuentoGlobalBp: 10000 }),
      );
      expect(crear().ticket().renglones).toEqual([]);
    });
  });

  describe('lectura tolerante', () => {
    function conAlmacenado(valor: string): TicketStore {
      localStorage.setItem('ticket:3:7', valor);
      return crear();
    }

    it('versión desconocida arranca vacío', () => {
      const s = conAlmacenado(JSON.stringify({ v: 2, clave: 'x', renglones: [], elegido: null }));
      expect(s.ticket().renglones).toEqual([]);
    });

    it('JSON roto arranca vacío', () => {
      expect(conAlmacenado('{no es json').ticket().renglones).toEqual([]);
    });

    it('forma inesperada arranca vacío', () => {
      expect(conAlmacenado(JSON.stringify({ v: 1, clave: 'x', renglones: 'nada' })).ticket().renglones).toEqual([]);
      expect(conAlmacenado(JSON.stringify(null)).ticket().renglones).toEqual([]);
      expect(conAlmacenado(JSON.stringify([1, 2])).ticket().renglones).toEqual([]);
      expect(
        conAlmacenado(
          JSON.stringify({ v: 1, clave: 'x', elegido: null, renglones: [{ articuloId: 'uno' }] }),
        ).ticket().renglones,
      ).toEqual([]);
    });

    it('un elegido fuera de rango arranca vacío', () => {
      const bueno = {
        articuloId: 1, codigoInterno: 'A', descripcion: 'A', unidad: 'UNIDAD', precioCentavos: 100,
        cantidadMilesimas: 1000, stockActual: 1, stockMinimo: 0,
      };
      const s = conAlmacenado(JSON.stringify({ v: 1, clave: 'x', elegido: 5, renglones: [bueno] }));
      expect(s.ticket().renglones).toEqual([]);
    });
  });

  describe('storage que falla', () => {
    it('lanzar al escribir no rompe', () => {
      const s = crear();
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('cuota');
      });
      s.agregar(art(1));
      expect(() => TestBed.tick()).not.toThrow();
      expect(s.ticket().renglones).toHaveLength(1);
    });

    it('lanzar al leer arranca vacío', () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new Error('bloqueado');
      });
      expect(crear().ticket().renglones).toEqual([]);
    });

    it('lanzar al borrar no rompe', () => {
      const s = crear();
      vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
        throw new Error('bloqueado');
      });
      expect(() => TestBed.tick()).not.toThrow();
      expect(s.ticket().renglones).toEqual([]);
    });
  });

  describe('aislamiento', () => {
    it('no mezcla usuarios', () => {
      const a = crear(sesion({ userId: 7 }));
      a.agregar(art(1));
      TestBed.tick();
      expect(crear(sesion({ userId: 8 })).ticket().renglones).toEqual([]);
    });

    it('no mezcla ferreterías', () => {
      const a = crear(sesion({ ferreteriaId: 3 }));
      a.agregar(art(1));
      TestBed.tick();
      expect(crear(sesion({ ferreteriaId: 4 })).ticket().renglones).toEqual([]);
    });

    it('sin ferreteriaId no persiste', () => {
      const s = crear(sesion({ ferreteriaId: null }));
      s.agregar(art(1));
      TestBed.tick();
      expect(localStorage.length).toBe(0);
      expect(s.ticket().renglones).toHaveLength(1);
    });

    it('sin sesión no persiste', () => {
      const s = crear(null);
      s.agregar(art(1));
      TestBed.tick();
      expect(localStorage.length).toBe(0);
    });
  });

  describe('venta desde un presupuesto (GDS-13)', () => {
    const renglon = {
      articuloId: 1, codigoInterno: 'A', descripcion: 'A', unidad: 'UNIDAD', precioCentavos: 100,
      cantidadMilesimas: 1000, stockActual: 1, stockMinimo: 0,
    };
    const guardar = (extra: Record<string, unknown>) =>
      localStorage.setItem('ticket:3:7', JSON.stringify({ v: 1, clave: 'x', elegido: 0, renglones: [renglon], ...extra }));

    it('el presupuesto del que sale el ticket sobrevive a un F5', () => {
      guardar({ presupuestoId: 8, presupuestoNumero: 12 });

      const s = crear();

      expect(s.ticket().presupuestoId).toBe(8);
      expect(s.ticket().presupuestoNumero).toBe(12);
    });

    it('un ticket sin presupuesto se lee sin presupuesto', () => {
      guardar({});

      const s = crear();

      expect(s.ticket().presupuestoId).toBeUndefined();
      expect(s.ticket().presupuestoNumero).toBeUndefined();
    });

    it.each([
      { presupuestoId: 0, presupuestoNumero: 1 },
      { presupuestoId: -3, presupuestoNumero: 1 },
      { presupuestoId: '8', presupuestoNumero: 1 },
      { presupuestoId: 8 },
      { presupuestoNumero: 12 },
      { presupuestoId: 8, presupuestoNumero: 1.5 },
    ])('un presupuesto guardado mal formado (%j) descarta el ticket', (extra) => {
      guardar(extra);

      const s = crear();

      expect(s.ticket().renglones).toEqual([]);
    });

    it('soltarPresupuesto lo saca del ticket, lo persiste y deja los renglones y la clave', () => {
      guardar({ presupuestoId: 8, presupuestoNumero: 12 });
      const a = crear();

      a.soltarPresupuesto();
      TestBed.tick();

      expect(a.ticket().presupuestoId).toBeUndefined();
      expect(a.ticket().renglones).toHaveLength(1);
      expect(a.ticket().clave).toBe('x');
      const b = crear();
      expect(b.ticket().presupuestoId).toBeUndefined();
      expect(b.ticket().renglones).toHaveLength(1);
    });

    it('vaciar y cobrar (lo que sobra) no arrastran el presupuesto', () => {
      guardar({ presupuestoId: 8, presupuestoNumero: 12 });
      const a = crear();

      a.vaciar();
      a.agregar(art(2));

      expect(a.ticket().presupuestoId).toBeUndefined();
    });
  });
});
