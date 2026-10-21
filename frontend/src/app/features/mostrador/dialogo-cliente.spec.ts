import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { Cliente, ClienteRequest } from '../clientes/cliente.models';
import { ClientesService } from '../clientes/clientes.service';
import { DialogoCliente } from './dialogo-cliente';

describe('DialogoCliente (F9)', () => {
  const ana: Cliente = { id: 1, razonSocial: 'Ana Gómez', tipoDocumento: 'DNI', numeroDocumento: '12345678', condicionIva: 'CONSUMIDOR_FINAL', activo: true, cuentaCorrienteHabilitada: false };
  const norte: Cliente = { id: 2, razonSocial: 'Ferretería Norte SRL', tipoDocumento: 'CUIT', numeroDocumento: '30712345671', condicionIva: 'RESPONSABLE_INSCRIPTO', activo: true, cuentaCorrienteHabilitada: false };
  const pagina = (items: Cliente[]): PageResponse<Cliente> => ({ items, page: 0, size: 8, totalElements: items.length, totalPages: 1 });
  const errorHttp = (status: number, message: string, errors?: Record<string, string>) =>
    new HttpErrorResponse({ status, error: { status, message, errors } });

  let fixture: ComponentFixture<DialogoCliente>;
  let servicio: { buscar: ReturnType<typeof vi.fn>; crear: ReturnType<typeof vi.fn> };
  let elegidos: Cliente[];
  let quitados: number;
  let cancelados: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const campo = () => el().querySelector<HTMLInputElement>('input[aria-label="Buscar cliente"]')!;
  const opciones = () => Array.from(el().querySelectorAll<HTMLElement>('[role="option"]'));
  const resaltadas = () => opciones().filter((o) => o.getAttribute('aria-selected') === 'true').map((o) => o.textContent!.trim());
  const boton = (nombre: string) => Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent!.trim() === nombre);
  const texto = (selector: string) => el().querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();

  function tecla(key: string): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    (document.activeElement ?? el()).dispatchEvent(evento);
    return evento;
  }

  async function estabilizar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function escribir(valor: string) {
    campo().value = valor;
    campo().dispatchEvent(new Event('input'));
    await estabilizar();
  }

  async function abrir(lista: Cliente[] = [ana, norte], actual: string | null = null) {
    servicio = {
      buscar: vi.fn(() => of(pagina(lista))),
      crear: vi.fn((r: ClienteRequest) => of({ id: 99, activo: true, cuentaCorrienteHabilitada: false, ...r } as Cliente)),
    };
    await TestBed.configureTestingModule({
      imports: [DialogoCliente],
      providers: [{ provide: ClientesService, useValue: servicio }],
    }).compileComponents();
    fixture = TestBed.createComponent(DialogoCliente);
    elegidos = [];
    quitados = 0;
    cancelados = 0;
    fixture.componentRef.setInput('actual', actual);
    fixture.componentInstance.elegir.subscribe((c) => elegidos.push(c));
    fixture.componentInstance.quitar.subscribe(() => quitados++);
    fixture.componentInstance.cancelar.subscribe(() => cancelados++);
    await estabilizar();
  }

  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  describe('buscar', () => {
    it('abre con el foco en el campo y los primeros clientes activos ya listados', async () => {
      await abrir();

      expect(el().querySelector('[role="dialog"]')!.getAttribute('aria-modal')).toBe('true');
      expect(document.activeElement).toBe(campo());
      expect(servicio.buscar).toHaveBeenCalledWith({ q: '', soloActivos: true, page: 0, size: 8 });
      expect(opciones().map((o) => o.textContent!.replace(/\s+/g, ' ').trim())).toEqual([
        'Ana Gómez DNI 12.345.678',
        'Ferretería Norte SRL CUIT 30-71234567-1',
      ]);
    });

    it('escribir busca recién a los 250 ms, y varias teclas seguidas hacen una sola búsqueda', async () => {
      await abrir();
      servicio.buscar.mockClear();

      await escribir('n');
      await escribir('no');
      await escribir('nor');
      vi.advanceTimersByTime(249);
      expect(servicio.buscar).not.toHaveBeenCalled();
      vi.advanceTimersByTime(1);
      await estabilizar();

      expect(servicio.buscar).toHaveBeenCalledTimes(1);
      expect(servicio.buscar).toHaveBeenCalledWith({ q: 'nor', soloActivos: true, page: 0, size: 8 });
    });

    it('sin resultados lo dice y Enter no elige a nadie', async () => {
      await abrir([]);

      expect(texto('.sin-resultados')).toBe('No hay clientes con esa búsqueda.');
      tecla('Enter');
      expect(elegidos).toEqual([]);
    });

    it('si la búsqueda falla avisa', async () => {
      await abrir();
      servicio.buscar.mockReturnValue(throwError(() => errorHttp(500, 'x')));

      await escribir('zz');
      vi.advanceTimersByTime(250);
      await estabilizar();

      expect(texto('[role="alert"]')).toBe('No se pudo buscar clientes. Probá de nuevo.');
    });
  });

  describe('cuenta corriente (CC-007, CC-019)', () => {
    const conCuenta: Cliente = {
      ...ana,
      id: 5,
      razonSocial: 'Corralón Sur',
      cuentaCorrienteHabilitada: true,
      limiteCuentaCorriente: 5000,
      saldo: 1500,
    };
    /** Como lo recibe el Vendedor: la bandera sí, las cifras no. */
    const conCuentaParaVendedor: Cliente = { ...ana, id: 5, razonSocial: 'Corralón Sur', cuentaCorrienteHabilitada: true };
    const sinCuentaDueno: Cliente = { ...norte, saldo: 0 };

    const fila = (nombre: string) => opciones().find((o) => o.textContent!.includes(nombre))!;
    const dicho = (e: Element) => (e.textContent ?? '').replace(/\s+/g, ' ').trim();

    async function abrirComo(esDueno: boolean, lista: Cliente[]) {
      await abrir(lista);
      fixture.componentRef.setInput('esDueno', esDueno);
      await estabilizar();
    }

    it('un cliente con la cuenta corriente habilitada lleva el indicador «Cta. cte.» para el Dueño', async () => {
      await abrirComo(true, [conCuenta, norte]);

      expect(dicho(fila('Corralón Sur'))).toContain('Cta. cte.');
      expect(dicho(fila('Ferretería Norte'))).not.toContain('Cta. cte.');
    });

    it('y también para el Vendedor', async () => {
      await abrirComo(false, [conCuentaParaVendedor, norte]);

      expect(dicho(fila('Corralón Sur'))).toContain('Cta. cte.');
      expect(dicho(fila('Ferretería Norte'))).not.toContain('Cta. cte.');
    });

    it('el Dueño ve «Saldo $ X · Límite $ Y»', async () => {
      await abrirComo(true, [conCuenta]);

      expect(dicho(fila('Corralón Sur'))).toContain('Saldo $ 1.500,00 · Límite $ 5.000,00');
    });

    it('un límite 0 (habilitada sin crédito) se muestra como límite $ 0,00', async () => {
      await abrirComo(true, [{ ...conCuenta, limiteCuentaCorriente: 0, saldo: 0 }]);

      expect(dicho(fila('Corralón Sur'))).toContain('Saldo $ 0,00 · Límite $ 0,00');
    });

    it('el Vendedor no ve ninguna cifra: ni saldo ni límite en el DOM, aunque el cliente las trajera', async () => {
      await abrirComo(false, [{ ...conCuenta }]);

      expect(el().textContent).not.toContain('Saldo');
      expect(el().textContent).not.toContain('Límite');
      expect(el().textContent).not.toContain('1.500');
      expect(el().textContent).not.toContain('5.000');
      expect(dicho(fila('Corralón Sur'))).toContain('Cta. cte.');
    });

    it('un cliente sin cuenta corriente y sin saldo no muestra cifras al Dueño', async () => {
      await abrirComo(true, [sinCuentaDueno]);

      expect(dicho(fila('Ferretería Norte'))).not.toMatch(/Saldo|Límite|Cta\. cte\./);
    });

    it('un cliente con la cuenta deshabilitada pero con deuda muestra al Dueño solo el saldo', async () => {
      await abrirComo(true, [{ ...norte, saldo: 700 }]);

      expect(dicho(fila('Ferretería Norte'))).toContain('Saldo $ 700,00');
      expect(dicho(fila('Ferretería Norte'))).not.toContain('Límite');
      expect(dicho(fila('Ferretería Norte'))).not.toContain('Cta. cte.');
    });

    it('sin la entrada esDueno se comporta como Vendedor (sin cifras)', async () => {
      await abrir([conCuenta]);

      expect(el().textContent).not.toContain('Saldo');
    });

    it('el flujo no cambia: ↓ y Enter eligen al cliente con cuenta corriente y emiten el cliente completo', async () => {
      await abrirComo(true, [ana, conCuenta]);

      tecla('ArrowDown');
      tecla('Enter');

      expect(elegidos).toEqual([conCuenta]);
    });
  });

  describe('elegir', () => {
    it('el primero viene resaltado y Enter lo elige', async () => {
      await abrir();

      expect(resaltadas()).toEqual(['Ana Gómez DNI 12.345.678']);
      const evento = tecla('Enter');

      expect(elegidos).toEqual([ana]);
      expect(evento.defaultPrevented).toBe(true);
    });

    it('↓ y ↑ mueven el resaltado, sin pasarse de los extremos, y Enter elige el resaltado', async () => {
      await abrir();

      tecla('ArrowDown');
      tecla('ArrowDown');
      await estabilizar();
      expect(resaltadas()).toEqual(['Ferretería Norte SRL CUIT 30-71234567-1']);
      tecla('Enter');
      expect(elegidos).toEqual([norte]);

      tecla('ArrowUp');
      tecla('ArrowUp');
      await estabilizar();
      expect(resaltadas()).toEqual(['Ana Gómez DNI 12.345.678']);
    });

    it('un clic en un resultado lo elige', async () => {
      await abrir();

      opciones()[1].click();

      expect(elegidos).toEqual([norte]);
    });

    it('una búsqueda nueva vuelve a resaltar el primero', async () => {
      await abrir();
      tecla('ArrowDown');
      await estabilizar();

      await escribir('a');
      vi.advanceTimersByTime(250);
      await estabilizar();

      expect(resaltadas()).toEqual(['Ana Gómez DNI 12.345.678']);
    });

    it('Esc cancela', async () => {
      await abrir();

      const evento = tecla('Escape');

      expect(cancelados).toBe(1);
      expect(evento.defaultPrevented).toBe(true);
    });
  });

  describe('cliente actual', () => {
    it('sin cliente elegido no ofrece quitarlo', async () => {
      await abrir();

      expect(boton('Quitar cliente')).toBeUndefined();
      expect(el().querySelector('.actual')).toBeNull();
    });

    it('con cliente elegido lo muestra y «Quitar cliente» vuelve a Consumidor Final', async () => {
      await abrir([ana], 'Ferretería Norte SRL');

      expect(texto('.actual')).toBe('Cliente actual: Ferretería Norte SRL');
      boton('Quitar cliente')!.click();

      expect(quitados).toBe(1);
    });
  });

  describe('alta rápida', () => {
    const abrirAlta = async () => {
      boton('Nuevo cliente')!.click();
      await estabilizar();
    };
    const escribirAlta = async (id: string, valor: string) => {
      const i = el().querySelector<HTMLInputElement | HTMLSelectElement>(`#${id}`)!;
      i.value = valor;
      i.dispatchEvent(new Event(i instanceof HTMLSelectElement ? 'change' : 'input'));
      await estabilizar();
    };
    const guardar = async () => {
      el().querySelector('form.alta-rapida')!.dispatchEvent(new Event('submit'));
      await estabilizar();
    };

    it('«Nuevo cliente» muestra el formulario con el foco en la razón social', async () => {
      await abrir();

      await abrirAlta();

      expect(el().querySelector('form.alta-rapida')).not.toBeNull();
      expect(document.activeElement).toBe(el().querySelector('#nuevo-razon-social'));
    });

    it('lo que se había buscado pasa como razón social para no volver a escribirlo', async () => {
      await abrir();
      await escribir('Juan Pérez');

      await abrirAlta();

      expect((el().querySelector('#nuevo-razon-social') as HTMLInputElement).value).toBe('Juan Pérez');
    });

    it('crea con la razón social y elige al nuevo cliente', async () => {
      await abrir();
      await abrirAlta();
      await escribirAlta('nuevo-razon-social', '  Juan Pérez ');
      await escribirAlta('nuevo-telefono', '223 555');

      await guardar();

      expect(servicio.crear).toHaveBeenCalledWith({ razonSocial: 'Juan Pérez', condicionIva: 'CONSUMIDOR_FINAL', telefono: '223 555' });
      expect(elegidos).toHaveLength(1);
      expect(elegidos[0].id).toBe(99);
      expect(elegidos[0].razonSocial).toBe('Juan Pérez');
    });

    it('manda el documento con su tipo cuando se completa', async () => {
      await abrir();
      await abrirAlta();
      await escribirAlta('nuevo-razon-social', 'Ferretería Sur');
      await escribirAlta('nuevo-tipo', 'CUIT');
      await escribirAlta('nuevo-numero', '30-71234567-1');

      await guardar();

      expect(servicio.crear).toHaveBeenCalledWith({
        razonSocial: 'Ferretería Sur',
        tipoDocumento: 'CUIT',
        numeroDocumento: '30-71234567-1',
        condicionIva: 'CONSUMIDOR_FINAL',
      });
    });

    it('sin razón social no llama al servicio y lo dice', async () => {
      await abrir();
      await abrirAlta();

      await guardar();

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(texto('#nuevo-razon-social-error')).toBe('La razón social es obligatoria.');
    });

    it('el número sin tipo, o el tipo sin número, se marca antes de mandar', async () => {
      await abrir();
      await abrirAlta();
      await escribirAlta('nuevo-razon-social', 'Ana');
      await escribirAlta('nuevo-numero', '12345678');

      await guardar();
      expect(servicio.crear).not.toHaveBeenCalled();
      expect(texto('#nuevo-tipo-error')).toBe('Elegí el tipo de documento.');

      await escribirAlta('nuevo-numero', '');
      await escribirAlta('nuevo-tipo', 'DNI');
      await guardar();
      expect(servicio.crear).not.toHaveBeenCalled();
      expect(texto('#nuevo-numero-error')).toBe('Falta el número de documento.');
    });

    it('un error del servidor (documento repetido) se muestra y no elige a nadie', async () => {
      await abrir();
      servicio.crear.mockReturnValue(throwError(() => errorHttp(409, 'Ya existe un cliente con ese tipo y número de documento.')));
      await abrirAlta();
      await escribirAlta('nuevo-razon-social', 'Ana');

      await guardar();

      expect(texto('form.alta-rapida [role="alert"]')).toBe('Ya existe un cliente con ese tipo y número de documento.');
      expect(elegidos).toEqual([]);
    });

    it('los errores por campo del servidor (400) se listan', async () => {
      await abrir();
      servicio.crear.mockReturnValue(
        throwError(() => errorHttp(400, 'Datos inválidos', { numeroDocumento: 'El CUIT no es válido: revisá el dígito verificador.' })),
      );
      await abrirAlta();
      await escribirAlta('nuevo-razon-social', 'Ana');

      await guardar();

      expect(texto('form.alta-rapida [role="alert"]')).toContain('El CUIT no es válido: revisá el dígito verificador.');
    });

    it('«Volver a buscar» deja el formulario y vuelve a la búsqueda', async () => {
      await abrir();
      await abrirAlta();

      boton('Volver a buscar')!.click();
      await estabilizar();

      expect(el().querySelector('form.alta-rapida')).toBeNull();
      expect(campo()).not.toBeNull();
    });
  });
});
