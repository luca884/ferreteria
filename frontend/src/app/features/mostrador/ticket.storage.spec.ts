import { signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { Sesion } from '../../core/auth/auth.models';
import { Ticket } from './ticket';
import { guardarTicket, ticketGuardado } from './ticket.storage';

describe('ticket.storage', () => {
  const sesion = (over: Partial<Sesion> = {}): Sesion => ({
    userId: 7,
    nombre: 'Ana',
    email: 'ana@x.com',
    rol: 'VENDEDOR',
    ferreteriaId: 3,
    ferreteriaNombre: 'Tornillo',
    ...over,
  });
  const auth = (s: Sesion | null = sesion()) => ({ sesion: signal(s).asReadonly() }) as unknown as AuthService;

  const ticket: Ticket = {
    v: 1,
    clave: 'clave-1',
    elegido: 0,
    presupuestoId: 8,
    presupuestoNumero: 12,
    renglones: [
      {
        articuloId: 1,
        codigoInterno: 'A',
        descripcion: 'Tornillo',
        unidad: 'UNIDAD',
        precioCentavos: 12000,
        cantidadMilesimas: 2000,
        stockActual: 5,
        stockMinimo: 1,
      },
    ],
  };

  beforeEach(() => localStorage.clear());

  it('sin nada guardado no hay ticket', () => {
    expect(ticketGuardado(auth())).toBeNull();
  });

  it('guardarTicket lo deja en ticket:{ferreteriaId}:{usuarioId} y ticketGuardado lo recupera igual', () => {
    guardarTicket(auth(), ticket);

    expect(localStorage.getItem('ticket:3:7')).not.toBeNull();
    expect(ticketGuardado(auth())).toEqual(ticket);
  });

  it('no mezcla usuarios', () => {
    guardarTicket(auth(), ticket);

    expect(ticketGuardado(auth(sesion({ userId: 8 })))).toBeNull();
  });

  it('sin sesión completa no guarda ni lee', () => {
    guardarTicket(auth(null), ticket);
    guardarTicket(auth(sesion({ ferreteriaId: null as unknown as number })), ticket);

    expect(localStorage.length).toBe(0);
    expect(ticketGuardado(auth(null))).toBeNull();
  });

  it('un ticket roto en el storage se lee como si no hubiera', () => {
    localStorage.setItem('ticket:3:7', '{no es json');

    expect(ticketGuardado(auth())).toBeNull();
  });

  it('la marca de un rechazo por el descuento se guarda y se lee con el renglón', () => {
    const marcado: Ticket = {
      ...ticket,
      renglones: [{ ...ticket.renglones[0], problema: 'Tu tope de descuento es 10 %.', problemaDeDescuento: true }],
    };

    guardarTicket(auth(), marcado);

    expect(ticketGuardado(auth())).toEqual(marcado);
  });

  it('una marca de descuento que no es true deja el ticket roto', () => {
    for (const mala of ['si', 'true', false, 1, null]) {
      const renglon = { ...ticket.renglones[0], problema: 'x', problemaDeDescuento: mala };
      localStorage.setItem('ticket:3:7', JSON.stringify({ ...ticket, renglones: [renglon] }));

      expect(ticketGuardado(auth())).toBeNull();
    }
  });

  describe('lo remitido de cada renglón (arreglos-confusos, S7)', () => {
    const delRemito: Ticket = {
      ...ticket,
      presupuestoId: undefined,
      presupuestoNumero: undefined,
      remitoId: 8,
      remitoNumero: 5,
      renglones: [{ ...ticket.renglones[0], remitidoMilesimas: 2000 }],
    };
    const sinUndefined = (t: Ticket) => JSON.parse(JSON.stringify(t)) as Ticket;

    it('se guarda y se lee con el renglón', () => {
      guardarTicket(auth(), delRemito);

      expect(ticketGuardado(auth())).toEqual(sinUndefined(delRemito));
    });

    it('un ticket guardado antes del cambio, sin el campo, sigue cargando igual', () => {
      const viejo = { ...delRemito, renglones: [ticket.renglones[0]] };
      localStorage.setItem('ticket:3:7', JSON.stringify(viejo));

      expect(ticketGuardado(auth())).toEqual(sinUndefined(viejo));
      expect('remitidoMilesimas' in ticketGuardado(auth())!.renglones[0]).toBe(false);
    });

    it('un valor que no es una cantidad en milésimas mayor que 0 deja el ticket roto', () => {
      for (const malo of [0, -1000, 1.5, '2000', null]) {
        const renglon = { ...ticket.renglones[0], remitidoMilesimas: malo };
        localStorage.setItem('ticket:3:7', JSON.stringify({ ...delRemito, renglones: [renglon] }));

        expect(ticketGuardado(auth())).toBeNull();
      }
    });
  });

  describe('cliente (CL-005)', () => {
    const guardarCrudo = (extra: Record<string, unknown>) =>
      localStorage.setItem('ticket:3:7', JSON.stringify({ ...ticket, presupuestoId: undefined, presupuestoNumero: undefined, ...extra }));

    it('el cliente elegido sobrevive: se guarda con el ticket y se lee igual', () => {
      const conCliente: Ticket = { ...ticket, clienteId: 7, clienteNombre: 'Ferretería Norte SRL' };

      guardarTicket(auth(), conCliente);

      expect(ticketGuardado(auth())).toEqual(conCliente);
    });

    it('la condición de IVA del cliente se guarda y se lee; una que no existe, o sin cliente, se descarta sin romper el ticket', () => {
      const conCondicion: Ticket = {
        ...ticket,
        clienteId: 7,
        clienteNombre: 'Ferretería Norte SRL',
        clienteCondicionIva: 'RESPONSABLE_INSCRIPTO',
      };
      guardarTicket(auth(), conCondicion);
      expect(ticketGuardado(auth())).toEqual(conCondicion);

      guardarCrudo({ clienteId: 7, clienteNombre: 'Ana', clienteCondicionIva: 'MARCIANO' });
      const rara = ticketGuardado(auth())!;
      expect(rara.clienteId).toBe(7);
      expect('clienteCondicionIva' in rara).toBe(false);

      guardarCrudo({ clienteCondicionIva: 'EXENTO' });
      const sinCliente = ticketGuardado(auth())!;
      expect(sinCliente.renglones).toHaveLength(1);
      expect('clienteCondicionIva' in sinCliente).toBe(false);
    });

    it('un ticket sin cliente se lee sin cliente', () => {
      guardarTicket(auth(), ticket);

      const leido = ticketGuardado(auth())!;
      expect('clienteId' in leido).toBe(false);
      expect('clienteNombre' in leido).toBe(false);
    });

    it('con el cliente a medias (solo id, o solo nombre) el ticket está roto', () => {
      guardarCrudo({ clienteId: 7 });
      expect(ticketGuardado(auth())).toBeNull();

      guardarCrudo({ clienteNombre: 'Ana' });
      expect(ticketGuardado(auth())).toBeNull();
    });

    it('con un id que no es un entero positivo, o un nombre vacío, el ticket está roto', () => {
      for (const roto of [
        { clienteId: 0, clienteNombre: 'Ana' },
        { clienteId: -3, clienteNombre: 'Ana' },
        { clienteId: 1.5, clienteNombre: 'Ana' },
        { clienteId: '7', clienteNombre: 'Ana' },
        { clienteId: 7, clienteNombre: '' },
        { clienteId: 7, clienteNombre: 5 },
      ]) {
        guardarCrudo(roto);
        expect(ticketGuardado(auth()), JSON.stringify(roto)).toBeNull();
      }
    });
  });
});
