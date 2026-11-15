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
  const efectivo: FormaPago = { id: 1, nombre: 'Efectivo', medio: 'EFECTIVO', recargoPorcentaje: 0, descuentoPorcentaje: 0, activa: true, orden: 1 };
  const debito: FormaPago = { id: 2, nombre: 'Débito', medio: 'DEBITO', recargoPorcentaje: 0, descuentoPorcentaje: 0, activa: true, orden: 2 };
  const visa6: FormaPago = { id: 3, nombre: 'Visa 6 cuotas', medio: 'CREDITO', recargoPorcentaje: 18.5, descuentoPorcentaje: 0, activa: true, orden: 3 };
  const mercadoPago: FormaPago = {
    id: 4,
    nombre: 'Mercado Pago',
    medio: 'TRANSFERENCIA',
    recargoPorcentaje: 5,
    descuentoPorcentaje: 0,
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

  async function confirmar() {
    el().querySelector<HTMLButtonElement>('app-confirmacion button.confirmar')!.click();
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
      ['Efectivo', 'Efectivo', 'Sin recargo', 'Sin descuento', 'Activa'],
      ['Visa 6 cuotas', 'Crédito', '18,5 %', 'Sin descuento', 'Activa'],
      ['Mercado Pago', 'Transferencia', '5 %', 'Sin descuento', 'Inactiva'],
    ]);
  });

  describe('descuento en efectivo', () => {
    const contado: FormaPago = { ...efectivo, descuentoPorcentaje: 10.5 };

    it('la lista muestra el descuento de cada forma', async () => {
      await crear([contado, visa6]);

      const columna = filas().map((f) => (f.querySelectorAll('td')[3].textContent ?? '').replace(/\s+/g, ' ').trim());
      expect(el().querySelectorAll('th')[3].textContent?.trim()).toBe('Descuento');
      expect(columna).toEqual(['10,5 %', 'Sin descuento']);
    });

    it('el formulario arranca sin descuento y da de alta una forma con su descuento', async () => {
      await crear([efectivo]);
      expect(input('descuento').value).toBe('0');

      await escribir('nombre', 'Contado');
      await escribir('descuento', '10');
      await enviar();

      expect(servicio.crear).toHaveBeenCalledWith({
        nombre: 'Contado',
        medio: 'EFECTIVO',
        recargoPorcentaje: 0,
        descuentoPorcentaje: 10,
        activa: true,
      });
      expect(input('descuento').value).toBe('0');
    });

    it('no envía un descuento vacío, negativo ni de 100 o más; acepta 99,99', async () => {
      await crear([efectivo]);
      await escribir('nombre', 'Contado');

      for (const [valor, mensaje] of [
        ['', 'El descuento es obligatorio.'],
        ['-1', 'El descuento tiene que estar entre 0 y 99,99 %.'],
        ['100', 'El descuento tiene que estar entre 0 y 99,99 %.'],
      ]) {
        await escribir('descuento', valor);
        await enviar();
        expect(texto('#descuento-error')).toBe(mensaje);
        expect(input('descuento').getAttribute('aria-invalid')).toBe('true');
      }
      expect(servicio.crear).not.toHaveBeenCalled();

      await escribir('descuento', '99.99');
      await enviar();
      expect(servicio.crear).toHaveBeenCalledWith(expect.objectContaining({ descuentoPorcentaje: 99.99 }));
    });

    it('no envía recargo y descuento a la vez, y lo avisa en el descuento', async () => {
      await crear([efectivo]);
      await escribir('nombre', 'Rara');
      await escribir('recargo', '5');
      await escribir('descuento', '10');
      await enviar();

      expect(texto('#descuento-error')).toBe('Una forma de pago lleva recargo o descuento, no los dos.');
      expect(servicio.crear).not.toHaveBeenCalled();

      await escribir('recargo', '0');
      await enviar();
      expect(servicio.crear).toHaveBeenCalledWith(
        expect.objectContaining({ recargoPorcentaje: 0, descuentoPorcentaje: 10 }),
      );
    });

    it('editar precarga el descuento y el PUT lo manda', async () => {
      await crear([contado, visa6]);

      await editarFila(0);
      expect(input('descuento').value).toBe('10.5');
      await escribir('descuento', '12');
      await enviar();

      expect(servicio.actualizar).toHaveBeenCalledWith(1, expect.objectContaining({ descuentoPorcentaje: 12 }));
    });

    it('con cuenta corriente el descuento queda en 0 y bloqueado, y se manda 0', async () => {
      await crear([efectivo]);
      await escribir('nombre', 'Fiado');
      await escribir('descuento', '9');

      await elegirMedio('CUENTA_CORRIENTE');
      expect(input('descuento').value).toBe('0');
      expect(input('descuento').disabled).toBe(true);
      expect(texto('#descuento-pista')).toContain('sin descuento');
      await enviar();

      expect(servicio.crear).toHaveBeenCalledWith(expect.objectContaining({ descuentoPorcentaje: 0 }));

      await elegirMedio('EFECTIVO');
      expect(input('descuento').disabled).toBe(false);
    });
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

  it('el formulario ofrece los cinco medios con su nombre y arranca en efectivo, sin recargo y sin el checkbox «Activa»', async () => {
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
    expect(el().querySelector('#activa')).toBeNull();
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
      descuentoPorcentaje: 0,
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

  // Bugs de la guía, S14: el alta vacía mostraba «El nombre es obligatorio.» con solo pasar por el campo (cancelar
  // una confirmación, tocar una fila de la lista).
  it('el alta vacía no avisa que falta el nombre por pasar por el campo: avisa al intentar guardar', async () => {
    await crear([efectivo]);

    input('nombre').dispatchEvent(new Event('focus'));
    input('nombre').dispatchEvent(new Event('blur'));
    await refrescar();
    expect(el().querySelector('#nombre-error')).toBeNull();
    expect(input('nombre').getAttribute('aria-invalid')).toBeNull();

    await enviar();
    expect(el().querySelector('#nombre-error')?.textContent).toContain('El nombre es obligatorio.');
  });

  it('si se escribió el nombre y se borró, avisa al salir del campo, como siempre', async () => {
    await crear([efectivo]);

    await escribir('nombre', 'D');
    await escribir('nombre', '');
    input('nombre').dispatchEvent(new Event('blur'));
    await refrescar();

    expect(el().querySelector('#nombre-error')?.textContent).toContain('El nombre es obligatorio.');
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
    expect(el().querySelector('#activa')).toBeNull();
  });

  it('editar una forma inactiva y guardarla la deja inactiva', async () => {
    await crear([efectivo, mercadoPago]);

    await editarFila(1);
    await escribir('recargo', '6');
    await enviar();

    expect(servicio.actualizar).toHaveBeenCalledWith(4, {
      nombre: 'Mercado Pago',
      medio: 'TRANSFERENCIA',
      recargoPorcentaje: 6,
      descuentoPorcentaje: 0,
      activa: false,
    });
  });

  it('guarda los cambios con PUT, reemplaza la fila y avisa', async () => {
    await crear([efectivo, visa6]);

    await editarFila(1);
    await escribir('recargo', '25');
    await enviar();

    expect(servicio.actualizar).toHaveBeenCalledWith(3, {
      nombre: 'Visa 6 cuotas',
      medio: 'CREDITO',
      recargoPorcentaje: 25,
      descuentoPorcentaje: 0,
      activa: true,
    });
    expect(servicio.crear).not.toHaveBeenCalled();
    expect(filas()).toHaveLength(2);
    expect((filas()[1].textContent ?? '').replace(/\s+/g, ' ')).toContain('25 %');
    expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se guardó «Visa 6 cuotas».');
    expect(el().querySelector('form h2')?.textContent).toContain('Nueva forma de pago');
  });

  describe('medio «Cuenta corriente» (CC-022, CC-023, CC-024)', () => {
    const cuentaCorriente: FormaPago = {
      id: 5,
      nombre: 'Cuenta corriente',
      medio: 'CUENTA_CORRIENTE',
      recargoPorcentaje: 0,
      descuentoPorcentaje: 0,
      activa: false,
      orden: 5,
    };

    it('la forma sembrada se lista inactiva con la pista para activarla con el botón', async () => {
      await crear([efectivo, cuentaCorriente]);

      expect(filas()[1].textContent).toContain('Cuenta corriente');
      expect(filas()[1].textContent).toContain('Inactiva');
      expect(texto('tbody tr:nth-child(2) .pista-activar')).toBe('Activala con «Activar» para vender a cuenta corriente.');
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

    it('editar la forma de cuenta corriente la carga con el recargo bloqueado', async () => {
      await crear([efectivo, cuentaCorriente]);

      await editarFila(1);

      expect(select('medio').value).toBe('CUENTA_CORRIENTE');
      expect(input('recargo').disabled).toBe(true);
    });

    it('«Activar» de la barra la activa sin preguntar: PUT con activa true y recargo 0', async () => {
      await crear([efectivo, cuentaCorriente]);

      await elegir(1);
      expect(barra('button.desactivar')!.textContent?.trim()).toBe('Activar');
      expect(barra('button.desactivar')!.classList.contains('peligro')).toBe(false);
      barra('button.desactivar')!.click();
      await refrescar();

      expect(el().querySelector('app-confirmacion')).toBeNull();

      expect(servicio.actualizar).toHaveBeenCalledWith(5, {
        nombre: 'Cuenta corriente',
        medio: 'CUENTA_CORRIENTE',
        recargoPorcentaje: 0,
        descuentoPorcentaje: 0,
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
        descuentoPorcentaje: 0,
        activa: true,
      });
    });
  });

  it('muestra el aviso del backend si no se puede desactivar la ultima en efectivo (422)', async () => {
    await crear([efectivo]);
    servicio.actualizar.mockReturnValue(
      throwError(() => errorHttp(422, 'Tiene que quedar al menos una forma de pago en efectivo.')),
    );

    await elegir(0);
    barra('button.desactivar')!.click();
    await refrescar();
    await confirmar();

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

  it('Editar y Desactivar de la barra quedan apagados con aria-disabled sin fila elegida', async () => {
    await crear([efectivo]);

    expect(barra('button.editar')!.getAttribute('aria-disabled')).toBe('true');
    expect(barra('button.editar')!.hasAttribute('disabled')).toBe(false);
    expect(barra('button.desactivar')!.getAttribute('aria-disabled')).toBe('true');
    expect(barra('button.desactivar')!.hasAttribute('disabled')).toBe(false);
    await elegir(0);
    expect(barra('button.editar')!.getAttribute('aria-disabled')).toBeNull();
    expect(barra('button.desactivar')!.getAttribute('aria-disabled')).toBeNull();
  });

  it('intentar Editar o Desactivar sin fila elegida explica el motivo y no hace nada (S5)', async () => {
    await crear([efectivo]);
    expect(el().querySelector('.motivo-accion')).toBeNull();

    barra('button.editar')!.click();
    await refrescar();

    const motivo = el().querySelector('.motivo-accion')!;
    expect(motivo.getAttribute('role')).toBe('status');
    expect(motivo.textContent?.trim()).toBe('Elegí una forma de pago de la lista para editarla.');
    // El motivo no es una acción: el formulario sigue en el alta.
    expect(el().querySelector('form h2')?.textContent).toContain('Nueva forma de pago');
    expect(input('nombre').value).toBe('');

    barra('button.desactivar')!.click();
    await refrescar();

    expect(el().querySelector('.motivo-accion')!.textContent?.trim()).toBe(
      'Elegí una forma de pago de la lista para activarla o desactivarla.',
    );
    expect(el().querySelector('app-confirmacion')).toBeNull();
    expect(servicio.actualizar).not.toHaveBeenCalled();
  });

  describe('desactivar desde la barra', () => {
    it('el texto de la pantalla dice cómo se desactivan', async () => {
      await crear([efectivo]);

      expect(texto('p.ayuda')).toContain(
        'Las formas no se borran: se desactivan con «Desactivar» de la barra, y dejan de ofrecerse al cobrar.',
      );
    });

    it('«Desactivar» pide confirmación; al confirmar manda el PUT con activa false y marca la fila', async () => {
      await crear([efectivo, visa6]);

      await elegir(1);
      expect(barra('button.desactivar')!.textContent?.trim()).toBe('Desactivar');
      expect(barra('button.desactivar')!.classList.contains('peligro')).toBe(true);
      barra('button.desactivar')!.click();
      await refrescar();

      expect(servicio.actualizar).not.toHaveBeenCalled();
      expect(texto('app-confirmacion h2')).toBe('¿Desactivar «Visa 6 cuotas»?');
      expect(texto('app-confirmacion p')).toBe(
        'Deja de ofrecerse al cobrar; las ventas hechas con ella se conservan. La podés volver a activar con «Activar».',
      );
      await confirmar();

      expect(servicio.actualizar).toHaveBeenCalledExactlyOnceWith(3, {
        nombre: 'Visa 6 cuotas',
        medio: 'CREDITO',
        recargoPorcentaje: 18.5,
        descuentoPorcentaje: 0,
        activa: false,
      });
      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(filas()[1].textContent).toContain('Inactiva');
      expect(barra('button.desactivar')!.textContent?.trim()).toBe('Activar');
    });

    it.each(['cancelar', 'Escape'])('con %s no la desactiva', async (forma) => {
      await crear([efectivo, visa6]);

      await elegir(1);
      barra('button.desactivar')!.click();
      await refrescar();
      if (forma === 'cancelar') el().querySelector<HTMLButtonElement>('app-confirmacion button.cancelar')!.click();
      else el().querySelector('app-confirmacion .dialogo')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      await refrescar();

      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(servicio.actualizar).not.toHaveBeenCalled();
      expect(filas()[1].textContent).toContain('Activa');
    });

    it('si se desactiva la que se está editando, guardar la edición no la vuelve a activar', async () => {
      await crear([efectivo, visa6]);

      await editarFila(1);
      barra('button.desactivar')!.click();
      await refrescar();
      await confirmar();
      await escribir('recargo', '20');
      await enviar();

      expect(servicio.actualizar).toHaveBeenLastCalledWith(3, {
        nombre: 'Visa 6 cuotas',
        medio: 'CREDITO',
        recargoPorcentaje: 20,
        descuentoPorcentaje: 0,
        activa: false,
      });
    });

    it('el Vendedor no ve el botón', async () => {
      await crear([efectivo], false);

      expect(barra('button.desactivar')).toBeNull();
    });
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

  /*
   * Responsive, S2/S4/T8: en el celular cada forma de pago es una tarjeta apilada con el nombre adelante. Vitest no
   * evalúa media queries, así que el spec vigila el contrato de datos que la dibuja (el nombre de la columna en cada
   * celda) y que la regla de la tarjeta viva sólo en la pantalla del celular: la tabla ancha no cambia.
   */
  describe('la fila apilada del celular (T8)', () => {
    /**
     * Las reglas de la hoja de Formas de pago que tocan `selector`, con la consulta de medios que las envuelve ('' si
     * no tiene): así se afirma que una regla es sólo del celular y que la base —tablet y escritorio— no se movió.
     * Angular le agrega a cada selector el atributo del componente, por eso la comparación es por `includes`.
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
          // Angular deja espacios de más donde estaba el atributo: se normalizan para comparar el selector entero.
          const selectorText = (regla as CSSStyleRule).selectorText
            ?.replace(/\[_ng(content|host)-[^\]]+\]/g, '')
            .replace(/\s+/g, ' ');
          if (selectorText?.includes(selector)) encontradas.push({ consulta, estilo: (regla as CSSStyleRule).style });
        });
      };
      Array.from(document.styleSheets).forEach((hoja) => {
        try {
          recorrer(hoja.cssRules, '');
        } catch {
          // Una hoja que no se deja leer no es la de Formas de pago.
        }
      });
      return encontradas;
    }

    it('cada celda lleva el nombre de su columna en data-etiqueta y el nombre va primero', async () => {
      await crear([efectivo, visa6, mercadoPago]);

      const cabeceras = Array.from(el().querySelectorAll('thead th')).map((th) => th.textContent!.trim());
      expect(el().querySelector('table')!.classList).toContain('tabla-tarjetas');
      for (const fila of filas()) {
        expect(Array.from(fila.querySelectorAll('td')).map((td) => td.getAttribute('data-etiqueta'))).toEqual(cabeceras);
      }

      // Lo que el Dueño mira para decidir: cómo se llama, con qué medio cobra y cuánto suma o resta.
      const visa = filas()[1];
      expect(visa.querySelector('td')!.classList).toContain('nombre');
      expect(visa.querySelector('td.nombre')!.textContent!.trim()).toBe('Visa 6 cuotas');
      expect(visa.querySelector('td.medio')!.textContent!.trim()).toBe('Crédito');
      expect(visa.querySelectorAll('td.num')[0].textContent).toContain('18,5');
      expect(visa.querySelectorAll('td.num')[1].textContent!.trim()).toBe('Sin descuento');
      expect(visa.querySelector('td.estado')!.textContent!.trim()).toBe('Activa');
    });

    it('la tarjeta vive sólo en la pantalla del celular y el nombre es su título, sin repetir la columna', async () => {
      await crear([efectivo, mercadoPago]);

      const tarjeta = reglasDe('.tabla-tarjetas');
      expect(tarjeta.length).toBeGreaterThan(0);
      expect(tarjeta.some((regla) => regla.estilo.getPropertyValue('display') === 'grid')).toBe(true);
      for (const regla of tarjeta) {
        expect(regla.consulta).toContain('screen');
        expect(regla.consulta).toContain('max-width: 639.98px');
      }

      // El nombre es el título de la tarjeta y no repite el nombre de la columna.
      const titulo = reglasDe('td.nombre');
      expect(titulo.some((regla) => regla.estilo.getPropertyValue('grid-area') === 'nombre')).toBe(true);
      const sinEtiqueta = reglasDe('td.nombre::before');
      expect(sinEtiqueta).toHaveLength(1);
      expect(sinEtiqueta[0].estilo.getPropertyValue('content')).toBe('none');
    });
  });
});
