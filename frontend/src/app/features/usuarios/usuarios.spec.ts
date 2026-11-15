import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
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
      ['Ana Baja', 'ana@tornillo.demo', 'Vendedor', 'Desactivado', 'Sin tope', '—'],
      ['Dueño El Tornillo', 'dueno@tornillo.demo', 'Dueño', 'Activo', '—', '—'],
      ['Vendedor Uno', 'vendedor@tornillo.demo', 'Vendedor', 'Activo', 'Sin tope', '—'],
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
      topeDescuento: null,
      telefono: null,
    });
    expect(filas().map((f) => celdas(f)[0])).toEqual(['Dueño El Tornillo', 'Lucía Pérez', 'Vendedor Uno']);
    expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se guardó «Lucía Pérez».');
    expect(input('nombre').value).toBe('');
    expect(input('password').value).toBe('');
  });

  it('mientras se guarda un alta los campos quedan bloqueados; al guardarse, el foco vuelve al nombre para el siguiente', async () => {
    await crear([dueno, vendedor]);
    const respuesta = new Subject<Usuario>();
    servicio.crear.mockReturnValue(respuesta);
    await altaCompleta();

    await enviar();

    expect(el().querySelector('form.alta')!.hasAttribute('inert')).toBe(true);

    respuesta.next({ id: 99, activo: true, nombre: 'Lucía Pérez', email: 'lucia@tornillo.demo', rol: 'VENDEDOR' });
    respuesta.complete();
    await refrescar();

    expect(el().querySelector('form.alta')!.hasAttribute('inert')).toBe(false);
    expect(input('nombre').value).toBe('');
    expect(document.activeElement).toBe(input('nombre'));
  });

  it('si el alta falla los campos se desbloquean con lo escrito', async () => {
    await crear([dueno, vendedor]);
    servicio.crear.mockReturnValue(throwError(() => errorHttp(409, 'Ya existe un usuario con ese email.')));
    await altaCompleta();

    await enviar();

    expect(el().querySelector('form.alta')!.hasAttribute('inert')).toBe(false);
    expect(input('nombre').value.trim()).toBe('Lucía Pérez');
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

  // Bugs de la guía, S14: como en Formas de pago, el alta vacía no avisa por pasar por el campo.
  describe('el aviso de nombre obligatorio', () => {
    it('no sale por pasar por el campo vacío: sale al intentar guardar', async () => {
      await crear([dueno, vendedor]);

      input('nombre').dispatchEvent(new Event('focus'));
      input('nombre').dispatchEvent(new Event('blur'));
      await refrescar();
      expect(el().querySelector('#nombre-error')).toBeNull();

      await enviar();
      expect(el().querySelector('#nombre-error')?.textContent).toContain('obligatorio');
    });

    it('si se escribió y se borró, sale al dejar el campo, como siempre', async () => {
      await crear([dueno, vendedor]);

      await escribir('nombre', 'A');
      await escribir('nombre', '');
      input('nombre').dispatchEvent(new Event('blur'));
      await refrescar();

      expect(el().querySelector('#nombre-error')?.textContent).toContain('obligatorio');
    });
  });

  describe('teléfono (mejoras-ferreteros-bloque-1, S5)', () => {
    it('el alta manda el teléfono sin espacios sobrantes y la lista lo muestra', async () => {
      await crear([dueno, vendedor]);
      servicio.crear.mockReturnValue(
        of({ id: 9, nombre: 'Lucía Pérez', email: 'lucia@tornillo.demo', rol: 'VENDEDOR', activo: true, telefono: '223 555-1234' }),
      );

      await altaCompleta();
      await escribir('telefono', ' 223 555-1234 ');
      await enviar();

      expect(servicio.crear).toHaveBeenCalledWith(expect.objectContaining({ telefono: '223 555-1234' }));
      expect(el().querySelector('label[for="telefono"]')?.textContent?.trim()).toBe('Teléfono (opcional)');
      expect(input('telefono').type).toBe('tel');
      const lucia = filas().find((f) => celdas(f)[0] === 'Lucía Pérez')!;
      expect(lucia.querySelector('.telefono')?.textContent?.trim()).toBe('223 555-1234');
    });

    it('editar precarga el teléfono, y sin teléfono la lista dice «—»', async () => {
      await crear([dueno, { ...vendedor, telefono: '111' }]);

      expect(filas()[0].querySelector('.telefono')?.textContent?.trim()).toBe('—');
      await editarFila(1);

      expect(input('telefono').value).toBe('111');
    });

    it('un teléfono de más de 30 caracteres no se manda y lo explica', async () => {
      await crear([dueno, vendedor]);

      await altaCompleta();
      await escribir('telefono', '1'.repeat(31));
      await enviar();

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(el().querySelector('#telefono-error')?.textContent?.trim()).toBe('El teléfono admite hasta 30 caracteres.');
    });
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
      topeDescuento: null,
      telefono: null,
    });
    expect(celdas(filas()[1])[2]).toBe('Dueño');
    expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se guardó «Vendedor Uno».');
  });

  describe('tope de descuento (CU-07)', () => {
    const conTope: Usuario = { ...vendedor, topeDescuento: 10 };
    const decimal: Usuario = { id: 4, nombre: 'Zoe Decimal', email: 'zoe@tornillo.demo', rol: 'VENDEDOR', activo: true, topeDescuento: 12.5 };

    it('la lista muestra el tope: con coma, «Sin tope» o «—» para el Dueño', async () => {
      await crear([{ ...dueno, topeDescuento: null }, conTope, decimal, { ...exVendedor, topeDescuento: null }]);
      await refrescar();

      expect(filas().map((f) => celdas(f)[4])).toEqual(['—', '10 %', '12,5 %']);
    });

    it('el alta de un Vendedor manda el tope escrito con coma', async () => {
      await crear([dueno]);

      await altaCompleta();
      await escribir('topeDescuento', '12,5');
      await enviar();

      expect(servicio.crear).toHaveBeenCalledWith(expect.objectContaining({ rol: 'VENDEDOR', topeDescuento: 12.5 }));
    });

    it('con rol Dueño el campo no aparece y se manda sin tope', async () => {
      await crear([dueno]);

      await altaCompleta();
      await escribir('topeDescuento', '10');
      await elegirRol('DUENO');
      expect(el().querySelector('#topeDescuento')).toBeNull();
      await enviar();

      expect(servicio.crear).toHaveBeenCalledWith(expect.objectContaining({ rol: 'DUENO', topeDescuento: null }));
    });

    it('editar precarga el tope y guardarlo lo manda', async () => {
      await crear([dueno, conTope]);

      await editarFila(1);
      expect(input('topeDescuento').value).toBe('10');
      await escribir('topeDescuento', '');
      await enviar();

      expect(servicio.actualizar).toHaveBeenCalledWith(2, expect.objectContaining({ topeDescuento: null }));
    });

    it('un tope fuera de 0 a 99,99 o con 3 decimales no se envía y se avisa en el campo', async () => {
      await crear([dueno]);
      await altaCompleta();

      for (const valor of ['100', '-1', '10,555', 'diez']) {
        await escribir('topeDescuento', valor);
        await enviar();
        expect(el().querySelector('#topeDescuento-error')?.textContent).toContain(
          'El tope va de 0 a 99,99 %, con hasta 2 decimales.',
        );
      }
      expect(servicio.crear).not.toHaveBeenCalled();
    });
  });

  describe('el usuario logueado', () => {
    it('no puede desactivarse: el botón queda apagado con aria-disabled en su fila y prendido en las otras', async () => {
      await crear([dueno, vendedor]);

      await elegir(0);
      expect(barra('button.desactivar').getAttribute('aria-disabled')).toBe('true');
      expect(barra('button.desactivar').hasAttribute('disabled')).toBe(false);

      await elegir(1);
      expect(barra('button.desactivar').hasAttribute('aria-disabled')).toBe(false);
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

    it('el clic en Desactivar sobre uno mismo no abre la confirmación ni llama al servidor', async () => {
      await crear([dueno, vendedor]);

      await elegir(0);
      barra('button.desactivar').click();
      await refrescar();

      // La razón ya está a la vista, enlazada con aria-describedby: no se repite en el motivo de la acción.
      expect(el().querySelector('#motivo-desactivar')?.textContent?.trim()).toBe(
        'No podés desactivarte ni cambiarte el rol a vos mismo.',
      );
      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(servicio.cambiarActivo).not.toHaveBeenCalled();
    });

    it('el 422 del servidor se muestra como error de la pantalla', async () => {
      await crear([dueno, vendedor]);
      servicio.cambiarActivo.mockReturnValue(throwError(() => errorHttp(422, 'No se puede desactivar a este usuario.')));

      await elegir(1);
      barra('button.desactivar').click();
      await refrescar();
      el().querySelector<HTMLButtonElement>('app-confirmacion button.confirmar')!.click();
      await refrescar();

      expect(el().querySelector('.error')?.textContent).toContain('No se puede desactivar a este usuario.');
    });
  });

  describe('los motivos de la barra (S5)', () => {
    const motivoDeLaPantalla = () => el().querySelector('.motivo-accion');

    it('intentar Editar sin usuario elegido explica el motivo y no precarga el formulario', async () => {
      await crear([dueno, vendedor]);
      expect(motivoDeLaPantalla()).toBeNull();

      barra('button.editar').click();
      await refrescar();

      const motivo = motivoDeLaPantalla()!;
      expect(motivo.getAttribute('role')).toBe('status');
      expect(motivo.textContent?.trim()).toBe('Elegí un usuario de la lista para editarlo.');
      expect(el().querySelector('form h2')?.textContent?.trim()).toBe('Nuevo usuario');
      expect(input('nombre').value).toBe('');
    });

    it('intentar Desactivar sin usuario elegido explica el motivo y no abre la confirmación', async () => {
      await crear([dueno, vendedor]);

      barra('button.desactivar').click();
      await refrescar();

      expect(motivoDeLaPantalla()?.textContent?.trim()).toBe(
        'Elegí un usuario de la lista para desactivarlo o reactivarlo.',
      );
      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(servicio.cambiarActivo).not.toHaveBeenCalled();
    });

    it('intentar Resetear contraseña sin usuario elegido explica el motivo y no abre el formulario', async () => {
      await crear([dueno, vendedor]);

      barra('button.resetear').click();
      await refrescar();

      expect(motivoDeLaPantalla()?.textContent?.trim()).toBe(
        'Elegí un usuario de la lista para resetearle la contraseña.',
      );
      expect(el().querySelector('app-contrasena-form')).toBeNull();
    });

    it('con el motivo a la vista, elegir un usuario lo apaga solo y deja la acción usable', async () => {
      await crear([dueno, vendedor]);
      barra('button.editar').click();
      await refrescar();
      expect(motivoDeLaPantalla()).not.toBeNull();

      await elegir(1);

      // Nadie volvió a tocar el botón: el motivo se va porque la regla ya no rige.
      expect(motivoDeLaPantalla()).toBeNull();
      expect(barra('button.editar').hasAttribute('aria-disabled')).toBe(false);
      expect(barra('button.resetear').hasAttribute('aria-disabled')).toBe(false);
      expect(barra('button.desactivar').hasAttribute('aria-disabled')).toBe(false);

      await editarFila(1);
      expect(input('nombre').value).toBe('Vendedor Uno');
    });
  });

  describe('desactivar y reactivar', () => {
    it('desactivar pide confirmación; al confirmar, PATCH y marca la fila', async () => {
      await crear([dueno, vendedor]);

      await elegir(1);
      expect(barra('button.desactivar').textContent?.trim()).toBe('Desactivar');
      expect(barra('button.desactivar').classList.contains('peligro')).toBe(true);
      barra('button.desactivar').click();
      await refrescar();

      expect(servicio.cambiarActivo).not.toHaveBeenCalled();
      expect(el().querySelector('app-confirmacion h2')?.textContent).toBe('¿Desactivar a Vendedor Uno?');
      expect(el().querySelector('app-confirmacion p')?.textContent).toBe(
        'Pierde el acceso en el acto; sus ventas se conservan. Lo podés reactivar desde «Mostrar desactivados».',
      );
      el().querySelector<HTMLButtonElement>('app-confirmacion button.confirmar')!.click();
      await refrescar();

      expect(servicio.cambiarActivo).toHaveBeenCalledExactlyOnceWith(2, false);
      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(celdas(filas()[1])[3]).toBe('Desactivado');
    });

    it.each(['cancelar', 'Escape'])('con %s no desactiva', async (forma) => {
      await crear([dueno, vendedor]);

      await elegir(1);
      barra('button.desactivar').click();
      await refrescar();
      if (forma === 'cancelar') el().querySelector<HTMLButtonElement>('app-confirmacion button.cancelar')!.click();
      else el().querySelector('app-confirmacion .dialogo')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      await refrescar();

      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(servicio.cambiarActivo).not.toHaveBeenCalled();
      expect(celdas(filas()[1])[3]).toBe('Activo');
    });

    it('un desactivado se reactiva', async () => {
      await crear([dueno, exVendedor]);
      input('incluir-inactivos').click();
      await refrescar();

      await elegir(0);
      expect(barra('button.desactivar').textContent?.trim()).toBe('Reactivar');
      expect(barra('button.desactivar').classList.contains('peligro')).toBe(false);
      barra('button.desactivar').click();
      await refrescar();

      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(servicio.cambiarActivo).toHaveBeenCalledWith(3, true);
      expect(celdas(filas()[0])[3]).toBe('Activo');
    });

    it('mientras cambia, el botón sigue apagado por el disabled transitorio', async () => {
      await crear([dueno, vendedor]);
      const pendiente = new Subject<Usuario>();
      servicio.cambiarActivo.mockReturnValue(pendiente);

      await elegir(1);
      barra('button.desactivar').click();
      await refrescar();
      el().querySelector<HTMLButtonElement>('app-confirmacion button.confirmar')!.click();
      await refrescar();

      expect(barra('button.desactivar').disabled).toBe(true);
      expect(barra('button.desactivar').hasAttribute('aria-disabled')).toBe(false);

      pendiente.next({ ...vendedor, activo: false });
      pendiente.complete();
      await refrescar();

      expect(barra('button.desactivar').disabled).toBe(false);
    });
  });

  describe('si el servidor rechaza la lista', () => {
    it('con el 403 dice qué es solo del Dueño, como las demás pantallas, y ninguna fila', async () => {
      await crear([], vi.fn(() => throwError(() => errorHttp(403, 'No tenés permiso para hacer esto.'))));

      expect(el().querySelector('.error')?.textContent).toContain('Solo el Dueño puede administrar los usuarios.');
      expect(el().textContent).not.toContain('No tenés permiso para hacer esto.');
      expect(filas()).toHaveLength(0);
      expect(el().querySelector('table')).toBeNull();
    });

    it('con el 403 no ofrece acciones ni la explicación de la pantalla', async () => {
      await crear([], vi.fn(() => throwError(() => errorHttp(403, 'No tenés permiso para hacer esto.'))));

      expect(el().querySelectorAll('button.accion-barra')).toHaveLength(0);
      expect(el().textContent).not.toContain('Los usuarios de tu ferretería.');
      expect(el().querySelector('h1')?.textContent).toContain('Usuarios');
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

    it('un 400 con la clave `password` se muestra debajo de la contraseña nueva, no como línea general', async () => {
      await crear([dueno, vendedor]);
      servicio.resetearContrasena.mockReturnValue(
        throwError(() =>
          errorHttp(400, 'Datos inválidos', { password: 'Elegí una contraseña distinta de la que te dieron.' }),
        ),
      );

      await abrirReset(1);
      await llenar('nueva1234');

      expect(el().querySelector('#nueva-error')?.textContent).toContain(
        'Elegí una contraseña distinta de la que te dieron.',
      );
      expect(el().querySelector('app-contrasena-form [role="alert"]')).toBeNull();
      expect(el().textContent).toContain('Resetear contraseña de Vendedor Uno');
    });

    it('tipear en la contraseña nueva limpia el 400 que dejó el servidor', async () => {
      await crear([dueno, vendedor]);
      servicio.resetearContrasena.mockReturnValue(
        throwError(() =>
          errorHttp(400, 'Datos inválidos', { password: 'Elegí una contraseña distinta de la que te dieron.' }),
        ),
      );
      await abrirReset(1);
      await llenar('nueva1234');
      expect(el().querySelector('#nueva-error')).not.toBeNull();

      // El mensaje describe la contraseña anterior: si el usuario escribe otra cosa, ya no corresponde mostrarlo.
      await escribir('nueva', 'otra-clave-99');
      await refrescar();

      expect(el().querySelector('#nueva-error')).toBeNull();
    });

    it('el error de la contraseña nueva no queda al cerrar y volver a abrir el reset', async () => {
      await crear([dueno, vendedor]);
      servicio.resetearContrasena.mockReturnValue(
        throwError(() => errorHttp(400, 'Datos inválidos', { password: 'La contraseña no puede ser solo espacios.' })),
      );
      await abrirReset(1);
      await llenar('nueva1234');
      expect(el().querySelector('#nueva-error')).not.toBeNull();

      el().querySelector<HTMLButtonElement>('button.cancelar-reset')!.click();
      await refrescar();
      await abrirReset(1);

      expect(el().querySelector('#nueva-error')).toBeNull();
      expect(el().querySelector('app-contrasena-form [role="alert"]')).toBeNull();
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

  /*
   * Responsive, S2/S4/T7: en el celular cada usuario es una tarjeta apilada con el nombre adelante. Vitest no evalúa
   * media queries, así que el spec vigila el contrato de datos que la dibuja (el nombre de la columna en cada celda) y
   * que la regla de la tarjeta viva sólo en la pantalla del celular: la tabla ancha no cambia.
   */
  describe('la fila apilada del celular (T7)', () => {
    /**
     * Las reglas del documento que tocan `selector`, con la consulta de medios que las envuelve ('' si no tiene): así se
     * puede afirmar que una regla es sólo del celular y que la base —tablet y escritorio— no se movió. Angular le agrega
     * a cada selector el atributo del componente: se lo quita antes de comparar.
     */
    function reglasDe(selector: string): { consulta: string; estilo: CSSStyleDeclaration }[] {
      const encontradas: { consulta: string; estilo: CSSStyleDeclaration }[] = [];
      const recorrer = (reglas: CSSRuleList, consulta: string) => {
        Array.from(reglas).forEach((regla) => {
          const media = (regla as CSSMediaRule).media;
          const hijas = (regla as CSSMediaRule).cssRules;
          if (media && hijas) {
            recorrer(hijas, [consulta, media.mediaText].filter(Boolean).join(' '));
            return;
          }
          const selectorText = (regla as CSSStyleRule).selectorText?.replace(/\[_ng(content|host)-[^\]]+\]/g, '');
          if (selectorText?.includes(selector)) encontradas.push({ consulta, estilo: (regla as CSSStyleRule).style });
        });
      };
      Array.from(document.styleSheets).forEach((hoja) => {
        try {
          recorrer(hoja.cssRules, '');
        } catch {
          // Una hoja que no se deja leer no es la de Usuarios.
        }
      });
      return encontradas;
    }

    it('cada celda lleva el nombre de su columna en data-etiqueta y el nombre va primero', async () => {
      await crear([dueno, vendedor]);

      const cabeceras = Array.from(el().querySelectorAll('thead th')).map((th) => (th.textContent ?? '').replace(/\s+/g, ' ').trim());
      expect(el().querySelector('table[role="grid"]')!.classList).toContain('tabla-tarjetas');
      for (const fila of filas()) {
        expect(Array.from(fila.querySelectorAll('td')).map((td) => td.getAttribute('data-etiqueta'))).toEqual(cabeceras);
      }

      // Lo que el Dueño mira para decidir: quién es, con qué email entra, su rol, si está activo y su tope.
      const primera = filas()[0];
      expect(primera.querySelector('td')!.classList).toContain('nombre');
      expect(celdas(primera)).toEqual(['Dueño El Tornillo', 'dueno@tornillo.demo', 'Dueño', 'Activo', '—', '—']);
    });

    it('la tarjeta vive sólo en la pantalla del celular y el nombre es su título, sin repetir la columna', async () => {
      await crear([dueno, vendedor]);

      const tarjeta = reglasDe('.tabla-tarjetas');
      expect(tarjeta.length).toBeGreaterThan(0);
      expect(tarjeta.some((regla) => regla.estilo.getPropertyValue('display') === 'grid')).toBe(true);
      // Ninguna regla de la tarjeta queda fuera del celular: la tablet y el escritorio siguen siendo la grilla.
      for (const regla of tarjeta) {
        expect(regla.consulta).toContain('screen');
        expect(regla.consulta).toContain('max-width: 639.98px');
      }

      // El nombre ocupa el ancho de la tarjeta y no repite el nombre de la columna; el email va entero abajo.
      const titulo = reglasDe('td.nombre');
      expect(titulo.length).toBeGreaterThan(0);
      expect(titulo.every((regla) => regla.consulta.includes('max-width: 639.98px'))).toBe(true);
      expect(titulo.some((regla) => regla.estilo.getPropertyValue('grid-column') === '1 / -1')).toBe(true);
      const email = reglasDe('td.email');
      expect(email.every((regla) => regla.consulta.includes('max-width: 639.98px'))).toBe(true);
      expect(email.some((regla) => regla.estilo.getPropertyValue('grid-column') === '1 / -1')).toBe(true);
      const sinEtiqueta = reglasDe('td.nombre::before');
      expect(sinEtiqueta).toHaveLength(1);
      expect(sinEtiqueta[0].estilo.getPropertyValue('content')).toBe('none');
    });

    // S5: el botón de desactivar sigue apagado con su motivo enlazado. No está en la tarjeta sino en la barra: la fila
    // apilada no lo esconde ni lo achica.
    it('el «Desactivar» apagado sigue explicando el motivo en la tarjeta del celular', async () => {
      await crear([dueno, vendedor]);

      await elegir(0);

      const desactivar = barra('button.desactivar');
      expect(el().querySelector('tbody tr button')).toBeNull();
      expect(desactivar.getAttribute('aria-disabled')).toBe('true');
      expect(desactivar.hasAttribute('disabled')).toBe(false);
      const motivo = el().querySelector(`#${desactivar.getAttribute('aria-describedby')}`)!;
      expect(motivo.textContent!.trim()).toBe('No podés desactivarte ni cambiarte el rol a vos mismo.');
    });
  });
});
