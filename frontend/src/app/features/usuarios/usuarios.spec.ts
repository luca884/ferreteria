import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Sesion } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { EdicionUsuarioRequest, Usuario, UsuarioRequest } from './usuario.models';
import { Usuarios } from './usuarios';
import { UsuariosService } from './usuarios.service';

describe('Usuarios', () => {
  const dueno: Usuario = { id: 1, nombre: 'Dueño El Tornillo', email: 'dueno@tornillo.demo', rol: 'DUENO', activo: true };
  const vendedor: Usuario = { id: 2, nombre: 'Vendedor Uno', email: 'vendedor@tornillo.demo', rol: 'VENDEDOR', activo: true };
  const exVendedor: Usuario = { id: 3, nombre: 'Ana Baja', email: 'ana@tornillo.demo', rol: 'VENDEDOR', activo: false };

  const sesion: Sesion = {
    userId: 1,
    nombre: 'Dueño El Tornillo',
    email: 'dueno@tornillo.demo',
    rol: 'DUENO',
    ferreteriaId: 1,
    ferreteriaNombre: 'Ferretería El Tornillo',
  };

  let fixture: ComponentFixture<Usuarios>;
  let servicio: {
    listar: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    actualizar: ReturnType<typeof vi.fn>;
    cambiarActivo: ReturnType<typeof vi.fn>;
    resetearContrasena: ReturnType<typeof vi.fn>;
  };
  let refrescarSesion: ReturnType<typeof vi.fn>;

  const errorHttp = (status: number, message: string, errors?: Record<string, string>) =>
    new HttpErrorResponse({ status, error: { status, message, errors } });

  async function crear(lista: Usuario[], listar = vi.fn((incluir: boolean) => of(incluir ? lista : lista.filter((u) => u.activo)))) {
    servicio = {
      listar,
      crear: vi.fn((r: UsuarioRequest) => of({ id: 99, activo: true, nombre: r.nombre, email: r.email, rol: r.rol })),
      actualizar: vi.fn((id: number, r: EdicionUsuarioRequest) => of({ id, activo: true, ...r })),
      cambiarActivo: vi.fn((id: number, activo: boolean) => of({ ...[dueno, vendedor, exVendedor].find((u) => u.id === id)!, activo })),
      resetearContrasena: vi.fn(() => of(undefined)),
    };
    refrescarSesion = vi.fn(() => of(sesion));
    await TestBed.configureTestingModule({
      imports: [Usuarios],
      providers: [
        { provide: UsuariosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(true), sesion: signal(sesion), refrescarSesion } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Usuarios);
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
  const celdas = (fila: HTMLTableRowElement) =>
    Array.from(fila.querySelectorAll('td')).map((td) => (td.textContent ?? '').replace(/\s+/g, ' ').trim());

  async function escribir(id: string, valor: string) {
    input(id).value = valor;
    input(id).dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function elegirRol(rol: string) {
    select('rol').value = rol;
    select('rol').dispatchEvent(new Event('change'));
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

  async function editarFila(fila: number) {
    await elegir(fila);
    barra('button.editar').click();
    await refrescar();
  }

  async function altaCompleta() {
    await escribir('nombre', '  Lucía Pérez ');
    await escribir('email', 'lucia@tornillo.demo');
    await elegirRol('VENDEDOR');
    await escribir('password', 'clave1234');
  }

  it('el título va en la barra de acciones', async () => {
    await crear([dueno]);

    expect(el().querySelector('app-barra-acciones h1')?.textContent?.trim()).toBe('Usuarios');
  });

  it('lista cada usuario con su rol y su estado', async () => {
    await crear([dueno, vendedor, exVendedor]);
    // El filtro arranca sin inactivos: se tilda para verlos.
    input('incluir-inactivos').click();
    await refrescar();

    expect(filas().map(celdas)).toEqual([
      ['Ana Baja', 'ana@tornillo.demo', 'Vendedor', 'Desactivado'],
      ['Dueño El Tornillo', 'dueno@tornillo.demo', 'Dueño', 'Activo'],
      ['Vendedor Uno', 'vendedor@tornillo.demo', 'Vendedor', 'Activo'],
    ]);
    expect(filas()[0].classList.contains('inactivo')).toBe(true);
  });

  it('arranca sin los desactivados y «Mostrar desactivados» los trae', async () => {
    await crear([dueno, vendedor, exVendedor]);

    expect(servicio.listar).toHaveBeenLastCalledWith(false);
    expect(filas()).toHaveLength(2);

    input('incluir-inactivos').click();
    await refrescar();

    expect(servicio.listar).toHaveBeenLastCalledWith(true);
    expect(filas()).toHaveLength(3);
  });

  it('el rol del formulario ofrece Dueño y Vendedor', async () => {
    await crear([dueno]);

    expect(Array.from(select('rol').options).map((o) => [o.value, o.textContent?.trim()])).toEqual([
      ['DUENO', 'Dueño'],
      ['VENDEDOR', 'Vendedor'],
    ]);
  });

  it('da de alta con la contraseña, agrega la fila por nombre y avisa', async () => {
    await crear([dueno, vendedor]);

    await altaCompleta();
    await enviar();

    expect(servicio.crear).toHaveBeenCalledWith({
      nombre: 'Lucía Pérez',
      email: 'lucia@tornillo.demo',
      rol: 'VENDEDOR',
      password: 'clave1234',
    });
    expect(filas().map((f) => celdas(f)[0])).toEqual(['Dueño El Tornillo', 'Lucía Pérez', 'Vendedor Uno']);
    expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se guardó «Lucía Pérez».');
    expect(input('nombre').value).toBe('');
    expect(input('password').value).toBe('');
  });

  it('no envía un alta incompleta o con la contraseña corta y lo avisa en el campo', async () => {
    await crear([dueno]);

    await escribir('nombre', '   ');
    await escribir('email', 'no-es-un-mail');
    await escribir('password', '1234567');
    await enviar();

    expect(servicio.crear).not.toHaveBeenCalled();
    expect(el().querySelector('#nombre-error')?.textContent).toContain('El nombre es obligatorio.');
    expect(el().querySelector('#email-error')?.textContent).toContain('El email no es válido.');
    expect(el().querySelector('#password-error')?.textContent).toContain('entre 8 y 72 caracteres');
  });

  it('no envía un alta con la contraseña de solo espacios y lo avisa en el campo', async () => {
    await crear([dueno]);

    await escribir('nombre', 'Ana');
    await escribir('email', 'ana@tornillo.demo');
    await escribir('password', ' '.repeat(8));
    await enviar();

    expect(servicio.crear).not.toHaveBeenCalled();
    expect(el().querySelector('#password-error')?.textContent).toContain('La contraseña no puede ser solo espacios.');
  });

  it('lista los mensajes por campo de un 400 y deja lo escrito', async () => {
    await crear([dueno]);
    servicio.crear.mockReturnValue(
      throwError(() => errorHttp(400, 'Datos inválidos', { email: 'El email no es válido.', rol: 'Elegí el rol.' })),
    );

    await altaCompleta();
    await enviar();

    const errores = el().querySelector('.errores-campos')?.textContent ?? '';
    expect(errores).toContain('El email no es válido.');
    expect(errores).toContain('Elegí el rol.');
    expect(input('email').value).toBe('lucia@tornillo.demo');
  });

  it('muestra en el formulario el 409 de un email en uso', async () => {
    await crear([dueno]);
    servicio.crear.mockReturnValue(throwError(() => errorHttp(409, 'Ese email ya está en uso.')));

    await altaCompleta();
    await enviar();

    expect(el().querySelector('form.alta .error')?.textContent).toContain('Ese email ya está en uso.');
    expect(filas()).toHaveLength(1);
  });

  it('muestra el 422 de una regla de negocio', async () => {
    await crear([dueno, vendedor]);
    servicio.actualizar.mockReturnValue(
      throwError(() => errorHttp(422, 'Tiene que quedar al menos un Dueño activo.')),
    );

    await editarFila(1);
    await enviar();

    expect(el().querySelector('.error')?.textContent).toContain('Tiene que quedar al menos un Dueño activo.');
  });

  it('el error se va al escribir de nuevo', async () => {
    await crear([dueno]);
    servicio.crear.mockReturnValue(throwError(() => errorHttp(409, 'Ese email ya está en uso.')));
    await altaCompleta();
    await enviar();

    await escribir('email', 'otra@tornillo.demo');

    expect(el().querySelector('form.alta .error')).toBeNull();
  });

  it('editar precarga el usuario y no pide la contraseña', async () => {
    await crear([dueno, vendedor]);

    await editarFila(1);

    expect(el().querySelector('form h2')?.textContent).toContain('Editar usuario');
    expect(input('nombre').value).toBe('Vendedor Uno');
    expect(input('email').value).toBe('vendedor@tornillo.demo');
    expect(select('rol').value).toBe('VENDEDOR');
    expect(el().querySelector('#password')).toBeNull();
  });

  it('guarda la edición con PUT, reemplaza la fila y avisa', async () => {
    await crear([dueno, vendedor]);

    await editarFila(1);
    await elegirRol('DUENO');
    await enviar();

    expect(servicio.actualizar).toHaveBeenCalledWith(2, {
      nombre: 'Vendedor Uno',
      email: 'vendedor@tornillo.demo',
      rol: 'DUENO',
    });
    expect(celdas(filas()[1])[2]).toBe('Dueño');
    expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se guardó «Vendedor Uno».');
  });

  describe('el usuario logueado', () => {
    it('no puede desactivarse: el botón queda deshabilitado en su fila y habilitado en las otras', async () => {
      await crear([dueno, vendedor]);

      await elegir(0);
      expect(barra('button.desactivar').disabled).toBe(true);

      await elegir(1);
      expect(barra('button.desactivar').disabled).toBe(false);
    });

    it('no puede cambiarse el rol: el selector queda deshabilitado al editarse', async () => {
      await crear([dueno, vendedor]);

      await editarFila(0);
      expect(select('rol').disabled).toBe(true);

      await enviar();
      expect(servicio.actualizar).toHaveBeenCalledWith(1, expect.objectContaining({ rol: 'DUENO' }));
    });

    it('en otro usuario el selector de rol está habilitado', async () => {
      await crear([dueno, vendedor]);

      await editarFila(1);

      expect(select('rol').disabled).toBe(false);
    });

    it('D9: al guardar su propia edición refresca la sesión', async () => {
      await crear([dueno, vendedor]);

      await editarFila(0);
      await escribir('nombre', 'Dueño Renombrado');
      await enviar();

      expect(servicio.actualizar).toHaveBeenCalledWith(1, expect.objectContaining({ nombre: 'Dueño Renombrado' }));
      expect(refrescarSesion).toHaveBeenCalledTimes(1);
    });

    it('D9: al guardar a otro usuario no refresca la sesión', async () => {
      await crear([dueno, vendedor]);

      await editarFila(1);
      await escribir('nombre', 'Vendedor Renombrado');
      await enviar();

      expect(refrescarSesion).not.toHaveBeenCalled();
    });

    it('D9: si refrescar la sesión falla, conserva el aviso de guardado', async () => {
      await crear([dueno, vendedor]);
      refrescarSesion.mockReturnValue(throwError(() => errorHttp(500, 'Error')));

      await editarFila(0);
      await escribir('nombre', 'Dueño Renombrado');
      await enviar();

      expect(refrescarSesion).toHaveBeenCalledTimes(1);
      expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se guardó «Dueño Renombrado».');
      expect(el().querySelector('.error')).toBeNull();
    });

    it('el 422 se muestra aunque se fuerce la desactivación', async () => {
      await crear([dueno, vendedor]);
      servicio.cambiarActivo.mockReturnValue(throwError(() => errorHttp(422, 'No podés desactivarte a vos mismo.')));

      await elegir(0);
      barra('button.desactivar').removeAttribute('disabled');
      barra('button.desactivar').click();
      await refrescar();

      expect(el().querySelector('.error')?.textContent).toContain('No podés desactivarte a vos mismo.');
    });
  });

  describe('desactivar y reactivar', () => {
    it('desactiva al elegido con PATCH y marca la fila', async () => {
      await crear([dueno, vendedor]);

      await elegir(1);
      expect(barra('button.desactivar').textContent?.trim()).toBe('Desactivar');
      barra('button.desactivar').click();
      await refrescar();

      expect(servicio.cambiarActivo).toHaveBeenCalledWith(2, false);
      expect(celdas(filas()[1])[3]).toBe('Desactivado');
    });

    it('un desactivado se reactiva', async () => {
      await crear([dueno, exVendedor]);
      input('incluir-inactivos').click();
      await refrescar();

      await elegir(0);
      expect(barra('button.desactivar').textContent?.trim()).toBe('Reactivar');
      barra('button.desactivar').click();
      await refrescar();

      expect(servicio.cambiarActivo).toHaveBeenCalledWith(3, true);
      expect(celdas(filas()[0])[3]).toBe('Activo');
    });
  });

  describe('si el servidor rechaza la lista', () => {
    it('muestra el mensaje del 403 y ninguna fila', async () => {
      await crear([], vi.fn(() => throwError(() => errorHttp(403, 'No tenés permiso para hacer esto.'))));

      expect(el().querySelector('.error')?.textContent).toContain('No tenés permiso para hacer esto.');
      expect(filas()).toHaveLength(0);
      expect(el().querySelector('table')).toBeNull();
    });

    it('sin mensaje avisa que no se pudo cargar y reintenta', async () => {
      const listar = vi
        .fn()
        .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })))
        .mockReturnValueOnce(of([dueno]));
      await crear([], listar);

      expect(el().querySelector('.error')?.textContent).toContain('No se pudieron cargar los usuarios.');
      el().querySelector<HTMLButtonElement>('button.reintentar')!.click();
      await refrescar();

      expect(listar).toHaveBeenCalledTimes(2);
      expect(filas()).toHaveLength(1);
    });
  });

  describe('resetear contraseña', () => {
    async function abrirReset(fila: number) {
      await elegir(fila);
      barra('button.resetear').click();
      await refrescar();
    }

    async function llenar(nueva: string, confirmacion = nueva) {
      await escribir('nueva', nueva);
      await escribir('confirmacion', confirmacion);
      el().querySelector('app-contrasena-form form')!.dispatchEvent(new Event('submit'));
      await refrescar();
    }

    it('abre el formulario de contraseña (sin pedir la actual) para el usuario elegido', async () => {
      await crear([dueno, vendedor]);

      await abrirReset(1);

      expect(el().querySelector('app-contrasena-form')).not.toBeNull();
      expect(el().querySelector('#actual')).toBeNull();
      expect(el().textContent).toContain('Resetear contraseña de Vendedor Uno');
    });

    it('envía la contraseña nueva, avisa y cierra el formulario', async () => {
      await crear([dueno, vendedor]);

      await abrirReset(1);
      await llenar('nueva1234');

      expect(servicio.resetearContrasena).toHaveBeenCalledWith(2, 'nueva1234');
      expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se cambió la contraseña de Vendedor Uno.');
      expect(el().querySelector('app-contrasena-form')).toBeNull();
    });

    it('con la confirmación distinta no llama al servicio', async () => {
      await crear([dueno, vendedor]);

      await abrirReset(1);
      await llenar('nueva1234', 'otra12345');

      expect(servicio.resetearContrasena).not.toHaveBeenCalled();
    });

    it('muestra el error del servidor dentro del formulario', async () => {
      await crear([dueno, vendedor]);
      servicio.resetearContrasena.mockReturnValue(throwError(() => errorHttp(404, 'Usuario no encontrado.')));

      await abrirReset(1);
      await llenar('nueva1234');

      expect(el().querySelector('app-contrasena-form .error')?.textContent).toContain('Usuario no encontrado.');
    });

    it('cancelar vuelve al formulario de usuario', async () => {
      await crear([dueno, vendedor]);

      await abrirReset(1);
      el().querySelector<HTMLButtonElement>('button.cancelar-reset')!.click();
      await refrescar();

      expect(el().querySelector('app-contrasena-form')).toBeNull();
      expect(el().querySelector('form.alta')).not.toBeNull();
    });
  });
});
