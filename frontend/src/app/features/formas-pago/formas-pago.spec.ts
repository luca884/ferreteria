import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { FormaPago, FormaPagoRequest } from './forma-pago.models';
import { FormasPago } from './formas-pago';
import { FormasPagoService } from './formas-pago.service';

describe('FormasPago', () => {
  const efectivo: FormaPago = { id: 1, nombre: 'Efectivo', medio: 'EFECTIVO', recargoPorcentaje: 0, activa: true, orden: 1 };
  const debito: FormaPago = { id: 2, nombre: 'Débito', medio: 'DEBITO', recargoPorcentaje: 0, activa: true, orden: 2 };
  const visa6: FormaPago = { id: 3, nombre: 'Visa 6 cuotas', medio: 'CREDITO', recargoPorcentaje: 18.5, activa: true, orden: 3 };
  const mercadoPago: FormaPago = {
    id: 4,
    nombre: 'Mercado Pago',
    medio: 'TRANSFERENCIA',
    recargoPorcentaje: 5,
    activa: false,
    orden: 4,
  };

  let fixture: ComponentFixture<FormasPago>;
  let servicio: {
    listar: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    actualizar: ReturnType<typeof vi.fn>;
  };

  const errorHttp = (status: number, message: string, errors?: Record<string, string>) =>
    new HttpErrorResponse({ status, error: { status, message, errors } });

  async function crear(lista: FormaPago[], dueno = true, listar = vi.fn(() => of(lista))) {
    servicio = {
      listar,
      crear: vi.fn((r: FormaPagoRequest) => of({ id: 99, orden: 99, ...r })),
      actualizar: vi.fn((id: number, r: FormaPagoRequest) => of({ id, orden: 5, ...r })),
    };
    await TestBed.configureTestingModule({
      imports: [FormasPago],
      providers: [
        { provide: FormasPagoService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(FormasPago);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  /** El texto de un elemento con los espacios duros y los saltos como espacios simples. */
  const texto = (selector: string) => el().querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  const filas = () => Array.from(el().querySelectorAll<HTMLTableRowElement>('tbody tr'));
  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
  const select = (id: string) => el().querySelector<HTMLSelectElement>(`#${id}`)!;
  const barra = (selector: string) => el().querySelector<HTMLButtonElement>(`app-barra-acciones ${selector}`);

  async function escribir(id: string, valor: string) {
    input(id).value = valor;
    input(id).dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function elegirMedio(medio: string) {
    select('medio').value = medio;
    select('medio').dispatchEvent(new Event('change'));
    await refrescar();
  }

  async function tildarActiva(activa: boolean) {
    input('activa').checked = activa;
    input('activa').dispatchEvent(new Event('change'));
    await refrescar();
  }

  async function enviar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  async function elegir(fila: number) {
    (filas()[fila].querySelector('td') as HTMLElement).click();
    await refrescar();
  }

  async function editarFila(fila: number) {
    await elegir(fila);
    barra('button.editar')!.click();
    await refrescar();
  }

  it('el título va en la barra de acciones de arriba', async () => {
    await crear([efectivo]);

    expect(el().querySelector('app-barra-acciones h1')?.textContent?.trim()).toBe('Formas de pago');
  });

  it('el Vendedor ve el aviso, no se le piden las formas ni se le muestra el formulario', async () => {
    await crear([efectivo], false);

    expect(el().textContent).toContain('Solo el Dueño puede configurar las formas de pago.');
    expect(servicio.listar).not.toHaveBeenCalled();
    expect(el().querySelector('form')).toBeNull();
    expect(el().querySelector('table')).toBeNull();
  });

  it('lista cada forma con su medio, su recargo y su estado', async () => {
    await crear([efectivo, visa6, mercadoPago]);

    const celdas = filas().map((f) =>
      Array.from(f.querySelectorAll('td')).map((td) => (td.textContent ?? '').replace(/\s+/g, ' ').trim()),
    );
    expect(celdas).toEqual([
      ['Efectivo', 'Efectivo', 'Sin recargo', 'Activa'],
      ['Visa 6 cuotas', 'Crédito', '18,5 %', 'Activa'],
      ['Mercado Pago', 'Transferencia', '5 %', 'Inactiva'],
    ]);
  });

  it('una forma inactiva se marca en la fila', async () => {
    await crear([efectivo, mercadoPago]);

    expect(filas()[0].classList.contains('inactiva')).toBe(false);
    expect(filas()[1].classList.contains('inactiva')).toBe(true);
  });

  it('la barra de estado cuenta las formas', async () => {
    await crear([efectivo, debito]);
    expect(el().querySelector('.barra-estado')?.textContent?.trim()).toBe('2 formas de pago');

    TestBed.resetTestingModule();
    await crear([efectivo]);
    expect(el().querySelector('.barra-estado')?.textContent?.trim()).toBe('1 forma de pago');
  });

  it('si la lista no se puede traer avisa con Reintentar y reintenta', async () => {
    const listar = vi
      .fn()
      .mockReturnValueOnce(throwError(() => errorHttp(500, 'Error')))
      .mockReturnValueOnce(of([efectivo]));
    await crear([], true, listar);

    expect(el().textContent).toContain('No se pudieron cargar las formas de pago.');
    el().querySelector<HTMLButtonElement>('button.reintentar')!.click();
    await refrescar();

    expect(listar).toHaveBeenCalledTimes(2);
    expect(filas()).toHaveLength(1);
  });

  it('el formulario ofrece los cinco medios con su nombre y arranca en efectivo, sin recargo y activa', async () => {
    await crear([efectivo]);

    const opciones = Array.from(select('medio').options).map((o) => [o.value, o.textContent?.trim()]);
    expect(opciones).toEqual([
      ['EFECTIVO', 'Efectivo'],
      ['DEBITO', 'Débito'],
      ['CREDITO', 'Crédito'],
      ['TRANSFERENCIA', 'Transferencia'],
      ['CUENTA_CORRIENTE', 'Cuenta corriente'],
    ]);
    expect(select('medio').value).toBe('EFECTIVO');
    expect(input('recargo').value).toBe('0');
    expect(input('activa').checked).toBe(true);
  });

  it('da de alta una forma con su recargo, la agrega a la lista en su orden y avisa', async () => {
    await crear([efectivo, debito]);

    await escribir('nombre', '  Visa 6 cuotas ');
    await elegirMedio('CREDITO');
    await escribir('recargo', '18.5');
    await enviar();

    expect(servicio.crear).toHaveBeenCalledWith({
      nombre: 'Visa 6 cuotas',
      medio: 'CREDITO',
      recargoPorcentaje: 18.5,
      activa: true,
    });
    expect(filas()).toHaveLength(3);
    expect(filas()[2].textContent).toContain('Visa 6 cuotas');
    expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se guardó «Visa 6 cuotas».');
    expect(input('nombre').value).toBe('');
    expect(input('recargo').value).toBe('0');
  });

  it('mientras se guarda un alta los campos quedan bloqueados; al guardarse, el foco vuelve al nombre para el siguiente', async () => {
    await crear([efectivo]);
    const respuesta = new Subject<FormaPago>();
    servicio.crear.mockReturnValue(respuesta);
    await escribir('nombre', 'Débito');
    await elegirMedio('DEBITO');

    await enviar();

    expect(el().querySelector('form.alta')!.hasAttribute('inert')).toBe(true);

    respuesta.next(debito);
    respuesta.complete();
    await refrescar();

    expect(el().querySelector('form.alta')!.hasAttribute('inert')).toBe(false);
    expect(input('nombre').value).toBe('');
    expect(document.activeElement).toBe(input('nombre'));
  });

  it('si el alta falla los campos se desbloquean con lo escrito', async () => {
    await crear([efectivo]);
    servicio.crear.mockReturnValue(throwError(() => errorHttp(500, 'Algo falló')));
    await escribir('nombre', 'Débito');

    await enviar();

    expect(el().querySelector('form.alta')!.hasAttribute('inert')).toBe(false);
    expect(input('nombre').value).toBe('Débito');
  });

  it('no envía un nombre vacío y lo avisa en el campo', async () => {
    await crear([efectivo]);

    await escribir('nombre', '   ');
    await enviar();

    expect(servicio.crear).not.toHaveBeenCalled();
    expect(input('nombre').getAttribute('aria-invalid')).toBe('true');
    expect(el().querySelector('#nombre-error')?.textContent).toContain('El nombre es obligatorio.');
  });

  it('no envía un nombre de más de 60 caracteres', async () => {
    await crear([efectivo]);

    await escribir('nombre', 'x'.repeat(61));
    await enviar();

    expect(servicio.crear).not.toHaveBeenCalled();
    expect(el().querySelector('#nombre-error')?.textContent).toContain('El nombre no puede superar 60 caracteres.');
  });

  it('no envía un recargo vacío, negativo ni mayor a 200', async () => {
    await crear([efectivo]);
    await escribir('nombre', 'Algo');

    for (const [valor, mensaje] of [
      ['', 'El recargo es obligatorio.'],
      ['-1', 'El recargo tiene que estar entre 0 y 200 %.'],
      ['200.01', 'El recargo tiene que estar entre 0 y 200 %.'],
    ]) {
      await escribir('recargo', valor);
      await enviar();
      expect(texto('#recargo-error')).toBe(mensaje);
    }
    expect(servicio.crear).not.toHaveBeenCalled();
  });

  it('acepta 0 y 200 como recargo', async () => {
    await crear([efectivo]);
    await escribir('nombre', 'Algo');

    await escribir('recargo', '200');
    await enviar();

    expect(servicio.crear).toHaveBeenCalledWith(expect.objectContaining({ recargoPorcentaje: 200 }));
  });

  it('muestra el mensaje del backend si el nombre ya existe (409) y deja lo escrito', async () => {
    await crear([efectivo]);
    servicio.crear.mockReturnValue(throwError(() => errorHttp(409, 'Ya hay una forma de pago con ese nombre.')));

    await escribir('nombre', 'Efectivo');
    await enviar();

    expect(el().querySelector('.error')?.textContent).toContain('Ya hay una forma de pago con ese nombre.');
    expect(input('nombre').value).toBe('Efectivo');
    expect(filas()).toHaveLength(1);
  });

  it('lista los mensajes por campo de un 400 del backend', async () => {
    await crear([efectivo]);
    servicio.crear.mockReturnValue(
      throwError(() => errorHttp(400, 'Datos inválidos', { recargoPorcentaje: 'El recargo admite hasta 2 decimales.' })),
    );

    await escribir('nombre', 'Algo');
    await enviar();

    expect(el().querySelector('.error')?.textContent).toContain('El recargo admite hasta 2 decimales.');
  });

  it('si falla sin mensaje avisa que no se pudo guardar', async () => {
    await crear([efectivo]);
    servicio.crear.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    await escribir('nombre', 'Algo');
    await enviar();

    expect(el().querySelector('.error')?.textContent).toContain('No se pudo guardar la forma de pago.');
  });

  it('el error se va al escribir de nuevo', async () => {
    await crear([efectivo]);
    servicio.crear.mockReturnValue(throwError(() => errorHttp(409, 'Ya hay una forma de pago con ese nombre.')));
    await escribir('nombre', 'Efectivo');
    await enviar();

    await escribir('nombre', 'Efectivo 2');

    expect(el().querySelector('.error')).toBeNull();
  });

  it('editar precarga la forma elegida, con su estado, y cambia el título del formulario', async () => {
    await crear([efectivo, visa6, mercadoPago]);

    await editarFila(2);

    expect(el().querySelector('form h2')?.textContent).toContain('Editar forma de pago');
    expect(input('nombre').value).toBe('Mercado Pago');
    expect(select('medio').value).toBe('TRANSFERENCIA');
    expect(input('recargo').value).toBe('5');
    expect(input('activa').checked).toBe(false);
  });

  it('guarda los cambios con PUT, reemplaza la fila y avisa', async () => {
    await crear([efectivo, visa6]);

    await editarFila(1);
    await escribir('recargo', '25');
    await tildarActiva(false);
    await enviar();

    expect(servicio.actualizar).toHaveBeenCalledWith(3, {
      nombre: 'Visa 6 cuotas',
      medio: 'CREDITO',
      recargoPorcentaje: 25,
      activa: false,
    });
    expect(servicio.crear).not.toHaveBeenCalled();
    expect(filas()).toHaveLength(2);
    expect((filas()[1].textContent ?? '').replace(/\s+/g, ' ')).toContain('25 %');
    expect(filas()[1].textContent).toContain('Inactiva');
    expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se guardó «Visa 6 cuotas».');
    expect(el().querySelector('form h2')?.textContent).toContain('Nueva forma de pago');
  });

  describe('medio «Cuenta corriente» (CC-022, CC-023, CC-024)', () => {
    const cuentaCorriente: FormaPago = {
      id: 5,
      nombre: 'Cuenta corriente',
      medio: 'CUENTA_CORRIENTE',
      recargoPorcentaje: 0,
      activa: false,
      orden: 5,
    };

    it('la forma sembrada se lista inactiva con la pista para activarla', async () => {
      await crear([efectivo, cuentaCorriente]);

      expect(filas()[1].textContent).toContain('Cuenta corriente');
      expect(filas()[1].textContent).toContain('Inactiva');
      expect(filas()[1].textContent).toContain('Activala para vender a cuenta corriente');
      expect(filas()[0].textContent).not.toContain('Activala');
    });

    it('una forma de cuenta corriente activa no muestra la pista de activarla', async () => {
      await crear([efectivo, { ...cuentaCorriente, activa: true }]);

      expect(filas()[1].textContent).toContain('Activa');
      expect(filas()[1].textContent).not.toContain('Activala');
    });

    it('al elegir el medio el recargo queda en 0 y deshabilitado, con su pista', async () => {
      await crear([efectivo]);
      await escribir('recargo', '12');

      await elegirMedio('CUENTA_CORRIENTE');

      expect(input('recargo').value).toBe('0');
      expect(input('recargo').disabled).toBe(true);
      expect(texto('#recargo-pista')).toContain('siempre con recargo 0');
      expect(input('recargo').getAttribute('aria-describedby')).toContain('recargo-pista');
    });

    it('al volver a otro medio el recargo se puede editar de nuevo (regresión)', async () => {
      await crear([efectivo]);
      await elegirMedio('CUENTA_CORRIENTE');

      await elegirMedio('CREDITO');
      await escribir('recargo', '7');

      expect(input('recargo').disabled).toBe(false);
      expect(el().querySelector('#recargo-pista')).toBeNull();
      expect(input('recargo').value).toBe('7');
    });

    it('con otros medios el recargo sigue editable (regresión)', async () => {
      await crear([efectivo, visa6]);

      await editarFila(1);

      expect(input('recargo').disabled).toBe(false);
      expect(input('recargo').value).toBe('18.5');
    });

    it('editar la forma de cuenta corriente la carga con el recargo bloqueado y la pista de activarla', async () => {
      await crear([efectivo, cuentaCorriente]);

      await editarFila(1);

      expect(select('medio').value).toBe('CUENTA_CORRIENTE');
      expect(input('recargo').disabled).toBe(true);
      expect(input('activa').checked).toBe(false);
      expect(texto('#activa-pista')).toContain('Activala para vender a cuenta corriente');
    });

    it('activarla manda el PUT con activa true y recargo 0', async () => {
      await crear([efectivo, cuentaCorriente]);

      await editarFila(1);
      await tildarActiva(true);
      await enviar();

      expect(servicio.actualizar).toHaveBeenCalledWith(5, {
        nombre: 'Cuenta corriente',
        medio: 'CUENTA_CORRIENTE',
        recargoPorcentaje: 0,
        activa: true,
      });
      expect(filas()[1].textContent).toContain('Activa');
      expect(filas()[1].textContent).not.toContain('Activala');
    });

    it('crear una forma con el medio manda recargo 0 aunque antes se hubiera escrito otro', async () => {
      await crear([efectivo]);
      await escribir('nombre', 'Fiado');
      await escribir('recargo', '9');
      await elegirMedio('CUENTA_CORRIENTE');
      await enviar();

      expect(servicio.crear).toHaveBeenCalledWith({
        nombre: 'Fiado',
        medio: 'CUENTA_CORRIENTE',
        recargoPorcentaje: 0,
        activa: true,
      });
    });
  });

  it('muestra el aviso del backend si no se puede desactivar la ultima en efectivo (422)', async () => {
    await crear([efectivo]);
    servicio.actualizar.mockReturnValue(
      throwError(() => errorHttp(422, 'Tiene que quedar al menos una forma de pago en efectivo.')),
    );

    await editarFila(0);
    await tildarActiva(false);
    await enviar();

    expect(el().querySelector('.error')?.textContent).toContain(
      'Tiene que quedar al menos una forma de pago en efectivo.',
    );
    expect(filas()[0].textContent).toContain('Activa');
  });

  it('cancelar vuelve el formulario al alta', async () => {
    await crear([efectivo, visa6]);
    await editarFila(1);

    el().querySelector<HTMLButtonElement>('button.cancelar')!.click();
    await refrescar();

    expect(el().querySelector('form h2')?.textContent).toContain('Nueva forma de pago');
    expect(input('nombre').value).toBe('');
  });

  it('el botón Nuevo de la barra deja el formulario en el alta aunque se estuviera editando', async () => {
    await crear([efectivo, visa6]);
    await editarFila(1);

    barra('button.nuevo')!.click();
    await refrescar();

    expect(el().querySelector('form h2')?.textContent).toContain('Nueva forma de pago');
    expect(input('nombre').value).toBe('');
  });

  it('Editar de la barra está apagado sin fila elegida', async () => {
    await crear([efectivo]);

    expect(barra('button.editar')!.disabled).toBe(true);
    await elegir(0);
    expect(barra('button.editar')!.disabled).toBe(false);
  });

  it('doble clic en la fila la edita', async () => {
    await crear([efectivo, visa6]);

    filas()[1].dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    await refrescar();

    expect(input('nombre').value).toBe('Visa 6 cuotas');
  });

  it('Enter en la fila elegida la edita', async () => {
    await crear([efectivo, visa6]);
    await elegir(1);

    filas()[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    await refrescar();

    expect(input('nombre').value).toBe('Visa 6 cuotas');
  });
});
