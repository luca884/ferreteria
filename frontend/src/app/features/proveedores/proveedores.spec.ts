import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Observable, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Proveedor } from './proveedor.models';
import { Proveedores } from './proveedores';
import { ProveedoresService } from './proveedores.service';

describe('Proveedores', () => {
  const sinteplast: Proveedor = { id: 1, nombre: 'Sinteplast', cuit: null, activo: true };
  const tersuave: Proveedor = { id: 2, nombre: 'Tersuave', cuit: '30-12345678-9', activo: true };

  let fixture: ComponentFixture<Proveedores>;
  let esDueno: ReturnType<typeof signal<boolean>>;
  let servicio: {
    listar: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    actualizar: ReturnType<typeof vi.fn>;
    desactivar: ReturnType<typeof vi.fn>;
  };

  const errorHttp = (status: number, message: string) =>
    new HttpErrorResponse({ status, error: { status, message } });

  async function crearComponente(dueno: boolean, lista: Proveedor[] = [sinteplast, tersuave]) {
    esDueno = signal(dueno);
    servicio = {
      listar: vi.fn(() => of(lista)),
      crear: vi.fn(),
      actualizar: vi.fn(),
      desactivar: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [Proveedores],
      providers: [
        { provide: ProveedoresService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Proveedores);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const filas = () => Array.from(el().querySelectorAll('tbody tr'));
  const textoFilas = () => filas().map((f) => f.textContent ?? '');

  async function escribir(selector: string, valor: string) {
    const input = el().querySelector<HTMLInputElement>(selector)!;
    input.value = valor;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function enviarFormulario() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function clickEn(selector: string, indice = 0) {
    el().querySelectorAll<HTMLButtonElement>(selector)[indice].click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => vi.restoreAllMocks());

  it('lista los proveedores al iniciar', async () => {
    await crearComponente(true);

    expect(servicio.listar).toHaveBeenCalledTimes(1);
    const textos = textoFilas();
    expect(textos).toHaveLength(2);
    expect(textos[0]).toContain('Sinteplast');
    expect(textos[1]).toContain('Tersuave');
    expect(textos[1]).toContain('30-12345678-9');
  });

  it('la barra de estado cuenta los proveedores', async () => {
    await crearComponente(false);
    expect(el().querySelector('.barra-estado')?.textContent?.trim()).toBe('2 proveedores');

    TestBed.resetTestingModule();
    await crearComponente(false, [sinteplast]);
    expect(el().querySelector('.barra-estado')?.textContent?.trim()).toBe('1 proveedor');
  });

  it('muestra un aviso si no hay proveedores', async () => {
    await crearComponente(true, []);

    expect(el().textContent).toContain('Todavía no hay proveedores.');
  });

  it('muestra el error si no se pueden cargar', async () => {
    esDueno = signal(true);
    servicio = {
      listar: vi.fn(() => throwError(() => errorHttp(500, 'Falló'))),
      crear: vi.fn(),
      actualizar: vi.fn(),
      desactivar: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [Proveedores],
      providers: [
        { provide: ProveedoresService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Proveedores);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el().querySelector('[role="alert"]')?.textContent).toContain(
      'No se pudieron cargar los proveedores.',
    );
  });

  describe('permisos', () => {
    it('el Dueño ve el formulario y las acciones', async () => {
      await crearComponente(true);

      expect(el().querySelector('form')).not.toBeNull();
      expect(el().querySelectorAll('button.editar')).toHaveLength(2);
      expect(el().querySelectorAll('button.desactivar')).toHaveLength(2);
    });

    it('el Vendedor solo ve la lista, sin formulario ni acciones', async () => {
      await crearComponente(false);

      expect(textoFilas()).toHaveLength(2);
      expect(el().querySelector('form')).toBeNull();
      expect(el().querySelectorAll('button.editar')).toHaveLength(0);
      expect(el().querySelectorAll('button.desactivar')).toHaveLength(0);
    });
  });

  describe('alta', () => {
    it('crea el proveedor sin espacios sobrantes, sin CUIT vacío, y lo suma ordenado', async () => {
      await crearComponente(true);
      const nuevo: Proveedor = { id: 3, nombre: 'Alba', cuit: null, activo: true };
      servicio.crear.mockReturnValue(of(nuevo));

      await escribir('#nombre', '  Alba ');
      await escribir('#cuit', '');
      await enviarFormulario();

      expect(servicio.crear).toHaveBeenCalledWith({ nombre: 'Alba', cuit: null, activo: true });
      const textos = textoFilas();
      expect(textos).toHaveLength(3);
      expect(textos[0]).toContain('Alba');
    });

    it('no llama al servicio si el nombre está vacío', async () => {
      await crearComponente(true);

      await enviarFormulario();

      expect(servicio.crear).not.toHaveBeenCalled();
    });

    it('no llama al servicio si el CUIT tiene mal el formato', async () => {
      await crearComponente(true);

      await escribir('#nombre', 'Alba');
      await escribir('#cuit', '123');
      await enviarFormulario();

      expect(servicio.crear).not.toHaveBeenCalled();
    });

    it('muestra el mensaje del backend si el alta falla', async () => {
      await crearComponente(true);
      servicio.crear.mockReturnValue(
        throwError(() => errorHttp(409, 'Ya existe un proveedor llamado Sinteplast.')),
      );

      await escribir('#nombre', 'Sinteplast');
      await enviarFormulario();

      expect(el().querySelector('[role="alert"]')?.textContent).toContain(
        'Ya existe un proveedor llamado Sinteplast.',
      );
      expect(textoFilas()).toHaveLength(2);
    });

    it('muestra los errores de validación del backend por campo en vez del mensaje genérico', async () => {
      await crearComponente(true);
      servicio.crear.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: {
                status: 400,
                message: 'Datos inválidos',
                errors: { nombre: 'El nombre no puede superar 100 caracteres' },
              },
            }),
        ),
      );

      await escribir('#nombre', 'Sinteplast');
      await enviarFormulario();

      const alerta = el().querySelector('[role="alert"]')!;
      expect(alerta.textContent).toContain('El nombre no puede superar 100 caracteres');
      expect(alerta.textContent).not.toContain('Datos inválidos');
    });

    it('muestra un mensaje genérico si el error no trae mensaje', async () => {
      await crearComponente(true);
      servicio.crear.mockReturnValue(throwError(() => new Error('caído')));

      await escribir('#nombre', 'Alba');
      await enviarFormulario();

      expect(el().querySelector('[role="alert"]')?.textContent).toContain(
        'No se pudo guardar el proveedor.',
      );
    });
  });

  describe('edición', () => {
    it('precarga el formulario, guarda con PUT y reemplaza la fila', async () => {
      await crearComponente(true);
      const editado: Proveedor = { id: 1, nombre: 'Sinteplast SA', cuit: '30-11111111-1', activo: true };
      servicio.actualizar.mockReturnValue(of(editado));

      await clickEn('button.editar', 0);
      expect(el().querySelector<HTMLInputElement>('#nombre')!.value).toBe('Sinteplast');

      await escribir('#nombre', 'Sinteplast SA');
      await escribir('#cuit', '30-11111111-1');
      await enviarFormulario();

      expect(servicio.actualizar).toHaveBeenCalledWith(1, {
        nombre: 'Sinteplast SA',
        cuit: '30-11111111-1',
        activo: true,
      });
      expect(servicio.crear).not.toHaveBeenCalled();
      const textos = textoFilas();
      expect(textos[0]).toContain('Sinteplast SA');
      expect(textos).toHaveLength(2);
      expect(el().querySelector<HTMLInputElement>('#nombre')!.value).toBe('');
    });

    it('cancelar vuelve al alta sin llamar al servicio', async () => {
      await crearComponente(true);

      await clickEn('button.editar', 1);
      await clickEn('button.cancelar');

      expect(el().querySelector<HTMLInputElement>('#nombre')!.value).toBe('');
      expect(servicio.actualizar).not.toHaveBeenCalled();
    });

    it('muestra el mensaje del backend si la edición falla', async () => {
      await crearComponente(true);
      servicio.actualizar.mockReturnValue(
        throwError(() => errorHttp(409, 'Ya existe un proveedor llamado Tersuave.')),
      );

      await clickEn('button.editar', 0);
      await escribir('#nombre', 'Tersuave');
      await enviarFormulario();

      expect(el().querySelector('[role="alert"]')?.textContent).toContain(
        'Ya existe un proveedor llamado Tersuave.',
      );
    });
  });

  describe('baja', () => {
    it('desactiva el proveedor confirmado y lo saca de la lista', async () => {
      await crearComponente(true);
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      servicio.desactivar.mockReturnValue(of(undefined) as Observable<void>);

      await clickEn('button.desactivar', 0);

      expect(servicio.desactivar).toHaveBeenCalledWith(1);
      const textos = textoFilas();
      expect(textos).toHaveLength(1);
      expect(textos[0]).toContain('Tersuave');
    });

    it('no hace nada si el Dueño no confirma', async () => {
      await crearComponente(true);
      vi.spyOn(window, 'confirm').mockReturnValue(false);

      await clickEn('button.desactivar', 0);

      expect(servicio.desactivar).not.toHaveBeenCalled();
      expect(textoFilas()).toHaveLength(2);
    });

    it('muestra el mensaje del backend si la baja falla', async () => {
      await crearComponente(true);
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      servicio.desactivar.mockReturnValue(
        throwError(() => errorHttp(404, 'Proveedor no encontrado')),
      );

      await clickEn('button.desactivar', 0);

      expect(el().querySelector('[role="alert"]')?.textContent).toContain(
        'Proveedor no encontrado',
      );
      expect(textoFilas()).toHaveLength(2);
    });
  });
});
