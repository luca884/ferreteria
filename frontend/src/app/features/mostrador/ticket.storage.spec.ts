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
});
