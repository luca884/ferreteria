import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AdminFerreterias } from './admin-ferreterias';
import { AdminFerreteriasService } from './admin-ferreterias.service';
import { AltaFerreteriaRequest, EstadoGeneral, FerreteriaAdmin } from './ferreteria-admin.models';

describe('AdminFerreterias', () => {
  const arco: FerreteriaAdmin = {
    id: 1,
    razonSocial: 'Arco SA',
    cuit: '30-12345678-1',
    condicionIva: 'RESPONSABLE_INSCRIPTO',
    estado: 'ACTIVA',
    creadoEn: '2026-10-21T10:00:00',
    duenosActivos: 1,
    vendedoresActivos: 2,
    duenoId: 7,
    emailDueno: 'dueno@arco.demo',
  };
  const bulon: FerreteriaAdmin = {
    id: 2,
    razonSocial: 'Bulón SRL',
    cuit: '30-50000001-7',
    condicionIva: 'MONOTRIBUTISTA',
    estado: 'SUSPENDIDA',
    creadoEn: '2026-09-02T08:30:00',
    duenosActivos: 0,
    vendedoresActivos: 0,
    duenoId: null,
    emailDueno: null,
  };

  const CUIT_VALIDO = '30-71234567-1';

  let fixture: ComponentFixture<AdminFerreterias>;
  let servicio: {
    listar: ReturnType<typeof vi.fn>;
    alta: ReturnType<typeof vi.fn>;
    cambiarActivo: ReturnType<typeof vi.fn>;
    resetearContrasenaDueno: ReturnType<typeof vi.fn>;
  };

  const errorHttp = (status: number, message: string, errors?: Record<string, string>) =>
    new HttpErrorResponse({ status, error: { status, message, errors } });

  const estado = (...ferreterias: FerreteriaAdmin[]): EstadoGeneral => ({
    activas: ferreterias.filter((f) => f.estado === 'ACTIVA').length,
    suspendidas: ferreterias.filter((f) => f.estado === 'SUSPENDIDA').length,
    ferreterias,
  });

  async function crear(lista: FerreteriaAdmin[] = [arco, bulon], listar = vi.fn(() => of(estado(...lista)))) {
    servicio = {
      listar,
      alta: vi.fn((r: AltaFerreteriaRequest) =>
        of<FerreteriaAdmin>({
          id: 50,
          razonSocial: r.razonSocial,
          cuit: r.cuit,
          condicionIva: r.condicionIva,
          estado: 'ACTIVA',
          creadoEn: '2026-10-21T12:00:00',
          duenosActivos: 1,
          vendedoresActivos: 0,
          duenoId: 90,
          emailDueno: r.dueno.email,
        }),
      ),
      cambiarActivo: vi.fn((id: number, activo: boolean) =>
        of({ ...[arco, bulon].find((f) => f.id === id)!, estado: activo ? 'ACTIVA' : 'SUSPENDIDA' }),
      ),
      resetearContrasenaDueno: vi.fn(() => of(undefined)),
    };
    await TestBed.configureTestingModule({
      imports: [AdminFerreterias],
      providers: [{ provide: AdminFerreteriasService, useValue: servicio }],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminFerreterias);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const filas = () => Array.from(el().querySelectorAll<HTMLTableRowElement>('tbody tr'));
  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
  const select = (id: string) => el().querySelector<HTMLSelectElement>(`#${id}`)!;
  const barra = (selector: string) => el().querySelector<HTMLButtonElement>(`app-barra-acciones ${selector}`)!;
  const texto = (selector: string) => (el().querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const celdas = (fila: HTMLTableRowElement) =>
    Array.from(fila.querySelectorAll('td')).map((td) => (td.textContent ?? '').replace(/\s+/g, ' ').trim());

  async function escribir(id: string, valor: string) {
    input(id).value = valor;
    input(id).dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function elegirIva(valor: string) {
    select('condicionIva').value = valor;
    select('condicionIva').dispatchEvent(new Event('change'));
    await refrescar();
  }

  async function enviar() {
    el().querySelector('form.alta')!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  async function elegir(fila: number) {
    (filas()[fila].querySelector('td') as HTMLElement).click();
    await refrescar();
  }

  async function altaCompleta(cuit = CUIT_VALIDO) {
    await escribir('razonSocial', '  Nueva Ferretería SA ');
    await escribir('cuit', cuit);
    await elegirIva('RESPONSABLE_INSCRIPTO');
    await escribir('dueno-nombre', 'Ana Pérez');
    await escribir('dueno-email', 'ana@nueva.demo');
    await escribir('dueno-password', 'clave1234');
  }

  it('el título va en la barra de acciones', async () => {
    await crear();

    expect(texto('app-barra-acciones h1')).toBe('Ferreterías');
  });

  it('muestra los totales de activas y suspendidas', async () => {
    await crear([arco, bulon, { ...arco, id: 3, razonSocial: 'Clavo SA' }]);

    expect(texto('.total-activas')).toContain('2');
    expect(texto('.total-suspendidas')).toContain('1');
  });

  it('lista cada ferretería con CUIT, IVA, estado, alta, usuarios activos y email del Dueño', async () => {
    await crear();

    expect(filas().map(celdas)).toEqual([
      ['Arco SA', '30-12345678-1', 'Responsable inscripto', 'Activa', '21/10/2026', '1', '2', 'dueno@arco.demo'],
      ['Bulón SRL', '30-50000001-7', 'Monotributista', 'Suspendida', '02/09/2026', '0', '0', '—'],
    ]);
    expect(filas()[1].classList.contains('inactivo')).toBe(true);
  });

  it('no muestra ningún dato de ventas, stock, caja ni importes', async () => {
    await crear();

    expect(el().textContent?.toLowerCase()).not.toMatch(/venta|stock|caja|importe|saldo|\$/);
  });

  it('si no se pudo cargar avisa y deja reintentar', async () => {
    const listar = vi
      .fn()
      .mockReturnValueOnce(throwError(() => errorHttp(500, 'Falló')))
      .mockReturnValue(of(estado(arco)));
    await crear([arco], listar);

    expect(texto('.error')).toContain('Falló');
    expect(filas()).toHaveLength(0);

    el().querySelector<HTMLButtonElement>('.reintentar')!.click();
    await refrescar();

    expect(filas()).toHaveLength(1);
  });

  describe('alta', () => {
    it('el IVA ofrece Responsable inscripto, Monotributista y Exento', async () => {
      await crear();

      expect(Array.from(select('condicionIva').options).map((o) => o.value)).toEqual([
        'RESPONSABLE_INSCRIPTO',
        'MONOTRIBUTISTA',
        'EXENTO',
      ]);
    });

    it('da de alta con el CUIT con guiones y el Dueño anidado, agrega la fila y avisa', async () => {
      await crear();

      await altaCompleta('30712345671');
      await enviar();

      expect(servicio.alta).toHaveBeenCalledWith({
        razonSocial: 'Nueva Ferretería SA',
        cuit: CUIT_VALIDO,
        condicionIva: 'RESPONSABLE_INSCRIPTO',
        dueno: { nombre: 'Ana Pérez', email: 'ana@nueva.demo', password: 'clave1234' },
      });
      expect(filas()).toHaveLength(3);
      expect(filas().map((f) => celdas(f)[0])).toContain('Nueva Ferretería SA');
      expect(texto('.total-activas')).toContain('2');
      expect(texto('.alerta.exito')).toContain('Se creó «Nueva Ferretería SA».');
      expect(input('razonSocial').value).toBe('');
      expect(input('dueno-password').value).toBe('');
    });

    it('no envía un alta con datos malos y lo avisa en cada campo', async () => {
      await crear();

      await escribir('razonSocial', '   ');
      await escribir('cuit', '30-12345678-2');
      await escribir('dueno-nombre', ' ');
      await escribir('dueno-email', 'no-es-mail');
      await escribir('dueno-password', '1234567');
      await enviar();

      expect(servicio.alta).not.toHaveBeenCalled();
      expect(texto('#razonSocial-error')).toContain('La razón social es obligatoria.');
      expect(texto('#cuit-error')).toContain('El CUIT no es válido');
      expect(texto('#dueno-nombre-error')).toContain('El nombre es obligatorio.');
      expect(texto('#dueno-email-error')).toContain('El email no es válido.');
      expect(texto('#dueno-password-error')).toContain('entre 8 y 72 caracteres');
    });

    it('un CUIT vacío no se envía y dice que es obligatorio', async () => {
      await crear();

      await altaCompleta('');
      await enviar();

      expect(servicio.alta).not.toHaveBeenCalled();
      expect(texto('#cuit-error')).toContain('El CUIT es obligatorio.');
    });

    it('la contraseña de solo espacios no se envía', async () => {
      await crear();

      await altaCompleta();
      await escribir('dueno-password', ' '.repeat(8));
      await enviar();

      expect(servicio.alta).not.toHaveBeenCalled();
      expect(texto('#dueno-password-error')).toContain('La contraseña no puede ser solo espacios.');
    });

    it('un 400 con claves anidadas dueno.* pone cada mensaje en su campo y deja lo escrito', async () => {
      await crear();
      servicio.alta.mockReturnValue(
        throwError(() =>
          errorHttp(400, 'Datos inválidos', {
            razonSocial: 'La razón social admite hasta 150 caracteres.',
            'dueno.email': 'El email no es válido.',
            'dueno.password': 'La contraseña no puede ser solo espacios.',
          }),
        ),
      );

      await altaCompleta();
      await enviar();

      expect(texto('#razonSocial-error')).toContain('hasta 150 caracteres');
      expect(texto('#dueno-email-error')).toContain('El email no es válido.');
      expect(texto('#dueno-password-error')).toContain('solo espacios');
      expect(input('dueno-nombre').value).toBe('Ana Pérez');
      expect(filas()).toHaveLength(2);
    });

    it('un 409 de CUIT repetido se muestra en el campo cuit', async () => {
      await crear();
      servicio.alta.mockReturnValue(
        throwError(() => errorHttp(409, 'Ya hay una ferretería con ese CUIT.', { cuit: 'Ya hay una ferretería con ese CUIT.' })),
      );

      await altaCompleta();
      await enviar();

      expect(texto('#cuit-error')).toContain('Ya hay una ferretería con ese CUIT.');
      expect(el().querySelector('#dueno-email-error')).toBeNull();
    });

    it('un 409 de email repetido se muestra en el campo del email del Dueño', async () => {
      await crear();
      servicio.alta.mockReturnValue(
        throwError(() => errorHttp(409, 'Ese email ya está en uso.', { 'dueno.email': 'Ese email ya está en uso.' })),
      );

      await altaCompleta();
      await enviar();

      expect(texto('#dueno-email-error')).toContain('Ese email ya está en uso.');
      expect(el().querySelector('#cuit-error')).toBeNull();
    });

    it('un error sin campo (caída) se muestra arriba del formulario', async () => {
      await crear();
      servicio.alta.mockReturnValue(throwError(() => errorHttp(500, 'Algo falló')));

      await altaCompleta();
      await enviar();

      expect(texto('form.alta .error')).toContain('Algo falló');
    });

    it('al escribir se van los errores del servidor', async () => {
      await crear();
      servicio.alta.mockReturnValue(
        throwError(() => errorHttp(409, 'Ya hay una ferretería con ese CUIT.', { cuit: 'Ya hay una ferretería con ese CUIT.' })),
      );
      await altaCompleta();
      await enviar();

      await escribir('cuit', '30-71234567-5');

      expect(el().querySelector('#cuit-error')?.textContent ?? '').not.toContain('Ya hay una ferretería');
    });
  });

  describe('sin edición (CU-01: después del alta, los datos los edita el Dueño)', () => {
    const formularioDeAlta = () => {
      expect(texto('form.alta h2')).toBe('Nueva ferretería');
      expect(input('razonSocial').value).toBe('');
      expect(input('cuit').readOnly).toBe(false);
      expect(el().querySelector('#dueno-nombre')).not.toBeNull();
    };

    it('la barra no ofrece Editar', async () => {
      await crear();
      await elegir(0);

      expect(el().querySelector('app-barra-acciones button.editar')).toBeNull();
      expect(barra('button.suspender')).not.toBeNull();
      expect(barra('button.resetear')).not.toBeNull();
    });

    it('el doble clic y Enter sobre una fila no abren una edición: el formulario sigue siendo el alta', async () => {
      await crear();

      filas()[0].dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
      await refrescar();
      formularioDeAlta();

      await elegir(0);
      filas()[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await refrescar();
      formularioDeAlta();
    });

    it('la ayuda avisa que los datos los edita el Dueño', async () => {
      await crear();

      expect(texto('p.ayuda')).toContain('Después del alta, la razón social y la condición de IVA las edita el Dueño');
    });
  });

  describe('suspender y reactivar', () => {
    const confirmar = () => el().querySelector<HTMLButtonElement>('app-confirmacion .confirmar')!;

    it('el botón dice Suspender para una activa y Reactivar para una suspendida', async () => {
      await crear();

      await elegir(0);
      expect(barra('button.suspender').textContent?.trim()).toBe('Suspender');
      await elegir(1);
      expect(barra('button.suspender').textContent?.trim()).toBe('Reactivar');
    });

    it('sin una fila elegida no hay nada para suspender', async () => {
      await crear();

      expect(barra('button.suspender').disabled).toBe(true);
    });

    it('pide confirmación y no suspende hasta confirmar', async () => {
      await crear();
      await elegir(0);

      barra('button.suspender').click();
      await refrescar();

      expect(el().querySelector('app-confirmacion')).not.toBeNull();
      expect(texto('app-confirmacion')).toContain('Arco SA');
      expect(servicio.cambiarActivo).not.toHaveBeenCalled();

      confirmar().click();
      await refrescar();

      expect(servicio.cambiarActivo).toHaveBeenCalledWith(1, false);
      expect(celdas(filas()[0])[3]).toBe('Suspendida');
      expect(texto('.total-activas')).toContain('0');
      expect(texto('.total-suspendidas')).toContain('2');
      expect(el().querySelector('app-confirmacion')).toBeNull();
    });

    it('cancelar la confirmación no cambia nada', async () => {
      await crear();
      await elegir(0);
      barra('button.suspender').click();
      await refrescar();

      el().querySelector<HTMLButtonElement>('app-confirmacion .cancelar')!.click();
      await refrescar();

      expect(servicio.cambiarActivo).not.toHaveBeenCalled();
      expect(celdas(filas()[0])[3]).toBe('Activa');
      expect(el().querySelector('app-confirmacion')).toBeNull();
    });

    it('reactivar también pide confirmación', async () => {
      await crear();
      await elegir(1);

      barra('button.suspender').click();
      await refrescar();
      expect(servicio.cambiarActivo).not.toHaveBeenCalled();
      confirmar().click();
      await refrescar();

      expect(servicio.cambiarActivo).toHaveBeenCalledWith(2, true);
      expect(celdas(filas()[1])[3]).toBe('Activa');
    });

    it('si falla muestra el error y la fila queda como estaba', async () => {
      await crear();
      servicio.cambiarActivo.mockReturnValue(throwError(() => errorHttp(404, 'Ferretería no encontrada')));
      await elegir(0);

      barra('button.suspender').click();
      await refrescar();
      confirmar().click();
      await refrescar();

      expect(texto('.error')).toContain('Ferretería no encontrada');
      expect(celdas(filas()[0])[3]).toBe('Activa');
    });
  });

  describe('resetear la contraseña del Dueño', () => {
    async function abrirReset(fila: number) {
      await elegir(fila);
      barra('button.resetear').click();
      await refrescar();
    }

    async function cargarContrasenas(nueva: string, confirmacion = nueva) {
      await escribir('nueva', nueva);
      await escribir('confirmacion', confirmacion);
      el().querySelector('app-contrasena-form form')!.dispatchEvent(new Event('submit'));
      await refrescar();
    }

    it('sin Dueño activo no se puede resetear', async () => {
      await crear();

      await elegir(1);

      expect(barra('button.resetear').disabled).toBe(true);
    });

    it('abre el formulario sin pedir la contraseña actual', async () => {
      await crear();

      await abrirReset(0);

      expect(texto('#titulo-reset')).toContain('Arco SA');
      expect(el().querySelector('#actual')).toBeNull();
      expect(el().querySelector('#nueva')).not.toBeNull();
    });

    it('resetea la contraseña del Dueño de esa ferretería y avisa', async () => {
      await crear();
      await abrirReset(0);

      await cargarContrasenas('nueva-clave-1');

      expect(servicio.resetearContrasenaDueno).toHaveBeenCalledWith(1, 7, 'nueva-clave-1');
      expect(texto('.alerta.exito')).toContain('Se cambió la contraseña del Dueño de Arco SA.');
      expect(el().querySelector('#titulo-reset')).toBeNull();
    });

    it('una confirmación distinta no llama al API', async () => {
      await crear();
      await abrirReset(0);

      await cargarContrasenas('nueva-clave-1', 'otra-cosa-123');

      expect(servicio.resetearContrasenaDueno).not.toHaveBeenCalled();
    });

    it('un error del servidor se muestra en el formulario y lo deja abierto', async () => {
      await crear();
      servicio.resetearContrasenaDueno.mockReturnValue(
        throwError(() => errorHttp(404, 'Usuario no encontrado')),
      );
      await abrirReset(0);

      await cargarContrasenas('nueva-clave-1');

      expect(texto('app-contrasena-form .error')).toContain('Usuario no encontrado');
      expect(el().querySelector('#titulo-reset')).not.toBeNull();
    });

    it('Cancelar cierra el formulario', async () => {
      await crear();
      await abrirReset(0);

      el().querySelector<HTMLButtonElement>('.cancelar-reset')!.click();
      await refrescar();

      expect(el().querySelector('#titulo-reset')).toBeNull();
    });
  });
});
