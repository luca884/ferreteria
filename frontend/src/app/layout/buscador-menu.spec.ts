import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, signal } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../core/auth/auth.service';
import { Cliente } from '../features/clientes/cliente.models';
import { ClientesService } from '../features/clientes/clientes.service';
import { Proveedor } from '../features/proveedores/proveedor.models';
import { ProveedoresService } from '../features/proveedores/proveedores.service';
import { BuscadorMenu, ESPERA_CLIENTES_MS } from './buscador-menu';

@Component({ template: '' })
class Vacia {}

const ana: Cliente = {
  id: 1,
  razonSocial: 'Ana Pérez',
  condicionIva: 'CONSUMIDOR_FINAL',
  activo: true,
  cuentaCorrienteHabilitada: false,
};
const bulonera: Proveedor = { id: 7, nombre: 'Bulonera Sur', cuit: null, activo: true };
const pinturas: Proveedor = { id: 8, nombre: 'Pinturas Ana', cuit: null, activo: true };

describe('BuscadorMenu (mejoras-ferreteros-bloque-2, S3)', () => {
  let fixture: ComponentFixture<BuscadorMenu>;
  let clientes: { buscar: ReturnType<typeof vi.fn> };
  let proveedores: { listar: ReturnType<typeof vi.fn> };

  async function render(dueno = true, buscarClientes = vi.fn(() => of({ items: [ana], page: 0, size: 5, totalElements: 1, totalPages: 1 }))) {
    clientes = { buscar: buscarClientes };
    proveedores = { listar: vi.fn(() => of([bulonera, pinturas])) };
    await TestBed.configureTestingModule({
      imports: [BuscadorMenu],
      providers: [
        provideRouter([{ path: '**', component: Vacia }]),
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
        { provide: ClientesService, useValue: clientes },
        { provide: ProveedoresService, useValue: proveedores },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(BuscadorMenu);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  afterEach(() => vi.useRealTimers());

  const el = () => fixture.nativeElement as HTMLElement;
  const caja = () => el().querySelector<HTMLInputElement>('input[type="search"]')!;
  const resultados = () =>
    Array.from(el().querySelectorAll<HTMLAnchorElement>('.resultados a')).map((a) => ({
      texto: a.textContent?.replace(/\s+/g, ' ').trim(),
      href: a.getAttribute('href'),
    }));
  const titulos = () => Array.from(el().querySelectorAll('.resultados h2')).map((h) => h.textContent?.trim());

  async function escribir(texto: string) {
    caja().value = texto;
    caja().dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(ESPERA_CLIENTES_MS);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function tecla(key: string) {
    caja().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('tiene una caja de búsqueda con su rótulo', async () => {
    await render();

    expect(caja().getAttribute('aria-label')).toBe('Buscar opciones, clientes, proveedores y artículos');
    expect(caja().placeholder).toBe('Buscar…');
  });

  it('con menos de 2 letras no muestra nada', async () => {
    vi.useFakeTimers();
    await render();

    await escribir('p');

    expect(el().querySelector('.resultados')).toBeNull();
    expect(clientes.buscar).not.toHaveBeenCalled();
  });

  it('ofrece las opciones del menú que coinciden, sin distinguir tildes', async () => {
    vi.useFakeTimers();
    await render();

    await escribir('cotizacion');

    expect(resultados()).toContainEqual({ texto: 'Cotización', href: '/configuracion/cotizacion' });
    expect(titulos()[0]).toBe('Opciones');
  });

  it('al Vendedor no le ofrece opciones que no ve en el menú', async () => {
    vi.useFakeTimers();
    await render(false);

    await escribir('usuarios');

    expect(resultados().map((r) => r.texto)).not.toContain('Usuarios');
  });

  it('ofrece los clientes que coinciden y elegir uno abre Clientes buscándolo', async () => {
    vi.useFakeTimers();
    await render();

    await escribir('ana');

    expect(clientes.buscar).toHaveBeenCalledWith({ q: 'ana', size: 5 });
    expect(titulos()).toContain('Clientes');
    expect(resultados()).toContainEqual({ texto: 'Ana Pérez', href: '/clientes?q=Ana%20P%C3%A9rez' });
  });

  it('ofrece los proveedores que coinciden y elegir uno abre Proveedores buscándolo', async () => {
    vi.useFakeTimers();
    await render();

    await escribir('bulo');

    expect(titulos()).toContain('Proveedores');
    expect(resultados()).toContainEqual({ texto: 'Bulonera Sur', href: '/proveedores?q=Bulonera%20Sur' });
    expect(resultados().map((r) => r.texto)).not.toContain('Pinturas Ana');
  });

  it('pide la lista de proveedores una sola vez aunque se escriba varias veces', async () => {
    vi.useFakeTimers();
    await render();

    await escribir('bu');
    await escribir('bul');
    await escribir('bulo');

    expect(proveedores.listar).toHaveBeenCalledTimes(1);
  });

  it('siempre ofrece buscar el texto en Artículos', async () => {
    vi.useFakeTimers();
    await render(false);

    await escribir('tornillo 1/4');

    expect(resultados().at(-1)).toEqual({
      texto: 'Buscar «tornillo 1/4» en Artículos',
      href: '/articulos?q=tornillo%201%2F4',
    });
  });

  it('si fallan los clientes, igual muestra lo demás', async () => {
    vi.useFakeTimers();
    await render(true, vi.fn(() => throwError(() => new Error('sin red'))));

    await escribir('bulo');

    expect(resultados().map((r) => r.texto)).toContain('Bulonera Sur');
    expect(titulos()).not.toContain('Clientes');
  });

  it('Enter lleva al primer resultado y vacía la caja', async () => {
    vi.useFakeTimers();
    await render();
    await escribir('rubros');

    await tecla('Enter');
    vi.useRealTimers();
    await fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/rubros');
    expect(caja().value).toBe('');
    expect(el().querySelector('.resultados')).toBeNull();
  });

  it('Esc vacía la caja y cierra los resultados', async () => {
    vi.useFakeTimers();
    await render();
    await escribir('rubros');

    await tecla('Escape');

    expect(caja().value).toBe('');
    expect(el().querySelector('.resultados')).toBeNull();
  });

  it('elegir un resultado con el mouse también vacía la caja', async () => {
    vi.useFakeTimers();
    await render();
    await escribir('rubros');

    el().querySelector<HTMLAnchorElement>('.resultados a')!.click();
    fixture.detectChanges();

    expect(caja().value).toBe('');
  });
});
