import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { Router, provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { FormaPago } from '../formas-pago/forma-pago.models';
import { FormasPagoService } from '../formas-pago/formas-pago.service';
import { Cliente, ClienteRequest } from './cliente.models';
import { Clientes } from './clientes';
import { ClientesService } from './clientes.service';

describe('Clientes', () => {
  const ana: Cliente = {
    id: 1,
    razonSocial: 'Ana Gómez',
    tipoDocumento: 'DNI',
    numeroDocumento: '12345678',
    condicionIva: 'CONSUMIDOR_FINAL',
    telefono: '2234567890',
    activo: true,
    cuentaCorrienteHabilitada: false,
  };
  const norte: Cliente = {
    id: 2,
    razonSocial: 'Ferretería Norte SRL',
    tipoDocumento: 'CUIT',
    numeroDocumento: '30712345671',
    condicionIva: 'RESPONSABLE_INSCRIPTO',
    domicilio: 'Av. Colón 1234',
    email: 'norte@correo.com',
    activo: true,
    cuentaCorrienteHabilitada: false,
  };
  const viejo: Cliente = {
    id: 3,
    razonSocial: 'Pedro Viejo',
    condicionIva: 'MONOTRIBUTISTA',
    activo: false,
    cuentaCorrienteHabilitada: false,
  };

  const pagina = (items: Cliente[], page = 0, totalPages = 1): PageResponse<Cliente> => ({
    items,
    page,
    size: 20,
    totalElements: items.length,
    totalPages,
  });

  /** Las formas activas de una ferretería que ya ofrece la cuenta corriente: el caso en que no hay nada que avisar. */
  const CON_CUENTA_CORRIENTE: FormaPago[] = [
    { id: 1, nombre: 'Efectivo', medio: 'EFECTIVO', recargoPorcentaje: 0, descuentoPorcentaje: 0, activa: true, orden: 1 },
    { id: 5, nombre: 'Cuenta corriente', medio: 'CUENTA_CORRIENTE', recargoPorcentaje: 0, descuentoPorcentaje: 0, activa: true, orden: 5 },
  ];

  let fixture: ComponentFixture<Clientes>;
  let formasApi: { listar: ReturnType<typeof vi.fn> };
  let servicio: {
    buscar: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    actualizar: ReturnType<typeof vi.fn>;
    cambiarActivo: ReturnType<typeof vi.fn>;
    cambiarLimite: ReturnType<typeof vi.fn>;
  };

  const errorHttp = (status: number, message: string, errors?: Record<string, string>) =>
    new HttpErrorResponse({ status, error: { status, message, errors } });

  async function crear(
    lista: Cliente[] = [ana, norte, viejo],
    dueno = true,
    buscar = vi.fn(() => of(pagina(lista))),
    formasActivas: FormaPago[] | Error = CON_CUENTA_CORRIENTE,
    url?: string,
  ) {
    formasApi = {
      listar: vi.fn(() => (formasActivas instanceof Error ? throwError(() => formasActivas) : of(formasActivas))),
    };
    servicio = {
      buscar,
      crear: vi.fn((r: ClienteRequest) => of({ id: 99, activo: true, cuentaCorrienteHabilitada: false, ...r })),
      actualizar: vi.fn((id: number, r: ClienteRequest) => of({ id, activo: true, cuentaCorrienteHabilitada: false, ...r })),
      cambiarActivo: vi.fn((id: number, activo: boolean) =>
        of({ ...([ana, norte, viejo].find((c) => c.id === id) as Cliente), activo }),
      ),
      cambiarLimite: vi.fn((_id: number, limite: number | null) =>
        of(limite === null ? { habilitada: false } : { habilitada: true, limite, saldo: 0, disponible: limite }),
      ),
    };
    await TestBed.configureTestingModule({
      imports: [Clientes],
      providers: [
        provideRouter([]),
        { provide: ClientesService, useValue: servicio },
        { provide: FormasPagoService, useValue: formasApi },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
      ],
    }).compileComponents();
    if (url) await TestBed.inject(Router).navigateByUrl(url);
    fixture = TestBed.createComponent(Clientes);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) => el().querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  const filas = () => Array.from(el().querySelectorAll<HTMLTableRowElement>('tbody tr'));
  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
  const select = (id: string) => el().querySelector<HTMLSelectElement>(`#${id}`)!;
  const barra = (selector: string) => el().querySelector<HTMLButtonElement>(`app-barra-acciones ${selector}`);
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent!.trim() === nombre);

  async function escribir(id: string, valor: string) {
    input(id).value = valor;
    input(id).dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function elegirEn(id: string, valor: string) {
    select(id).value = valor;
    select(id).dispatchEvent(new Event('change'));
    await refrescar();
  }

  async function enviar(formulario: string) {
    el().querySelector(formulario)!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  async function elegir(fila: number) {
    (filas()[fila].querySelector('td') as HTMLElement).click();
    await refrescar();
  }

  describe('lista', () => {
    it('al abrir trae la primera página y muestra razón social, documento, condición de IVA y estado', async () => {
      await crear();

      expect(servicio.buscar).toHaveBeenCalledWith({ q: '', soloActivos: false, page: 0 });
      expect(filas()).toHaveLength(3);
      expect(filas()[0].textContent).toContain('Ana Gómez');
      expect(filas()[0].textContent).toContain('DNI 12.345.678');
      expect(filas()[0].textContent).toContain('Consumidor final');
      expect(filas()[1].textContent).toContain('CUIT 30-71234567-1');
      expect(filas()[1].textContent).toContain('Responsable inscripto');
      expect(filas()[2].textContent).toContain('Inactivo');
    });

    it('sin clientes dice que todavía no hay', async () => {
      await crear([]);

      expect(texto('.estado-vacio')).toBe('Todavía no hay clientes.');
    });

    it('si la lista no se pudo traer avisa y Reintentar la vuelve a pedir', async () => {
      const buscar = vi.fn().mockReturnValueOnce(throwError(() => errorHttp(500, 'x'))).mockReturnValue(of(pagina([ana])));
      await crear([], true, buscar);
      expect(texto('[role="alert"]')).toContain('No se pudieron cargar los clientes.');

      (el().querySelector('.reintentar') as HTMLButtonElement).click();
      await refrescar();

      expect(filas()).toHaveLength(1);
    });
  });

  describe('búsqueda y páginas', () => {
    it('con ?q= en la dirección abre buscando ese texto, con el texto en el buscador (bloque 2, S3)', async () => {
      await crear([ana, norte, viejo], true, undefined, CON_CUENTA_CORRIENTE, '/?q=Norte');

      expect(servicio.buscar).toHaveBeenCalledTimes(1);
      expect(servicio.buscar).toHaveBeenCalledWith({ q: 'Norte', soloActivos: false, page: 0 });
      expect(input('q').value).toBe('Norte');
    });

    it('si el buscador del menú cambia ?q= con la pantalla abierta, vuelve a buscar', async () => {
      await crear();

      await TestBed.inject(Router).navigateByUrl('/?q=Ana');
      await refrescar();

      expect(servicio.buscar).toHaveBeenLastCalledWith({ q: 'Ana', soloActivos: false, page: 0 });
      expect(input('q').value).toBe('Ana');
    });

    it('buscar manda q y vuelve a la página 0', async () => {
      await crear();
      await escribir('q', '  perez ');

      await enviar('form.filtros');

      expect(servicio.buscar).toHaveBeenLastCalledWith({ q: 'perez', soloActivos: false, page: 0 });
    });

    it('«Solo activos» filtra por activos', async () => {
      await crear();
      input('solo-activos').click();
      await refrescar();

      await enviar('form.filtros');

      expect(servicio.buscar).toHaveBeenLastCalledWith({ q: '', soloActivos: true, page: 0 });
    });

    it('Limpiar saca el texto y el filtro y vuelve a pedir todo', async () => {
      await crear();
      await escribir('q', 'norte');
      input('solo-activos').click();
      await refrescar();

      boton('Limpiar')!.click();
      await refrescar();

      expect(servicio.buscar).toHaveBeenLastCalledWith({ q: '', soloActivos: false, page: 0 });
      expect(input('q').value).toBe('');
      expect(input('solo-activos').checked).toBe(false);
    });

    it('con más de una página muestra Anterior y Siguiente, y Siguiente pide la página 1 con el mismo filtro', async () => {
      const buscar = vi.fn(() => of(pagina([ana], 0, 3)));
      await crear([], true, buscar);
      await escribir('q', 'a');
      await enviar('form.filtros');

      boton('Siguiente')!.click();
      await refrescar();

      expect(buscar).toHaveBeenLastCalledWith({ q: 'a', soloActivos: false, page: 1 });
      expect(texto('nav.paginas')).toContain('Página 1 de 3');
      expect(boton('Anterior')!.disabled).toBe(true);
    });

    it('con una sola página no hay navegación', async () => {
      await crear();

      expect(el().querySelector('nav.paginas')).toBeNull();
    });
  });

  describe('alta y edición', () => {
    it('crea con la razón social obligatoria y manda solo lo cargado, sin espacios de más', async () => {
      await crear();
      await escribir('razon-social', '  Juan Pérez  ');
      await escribir('telefono', ' 223 555 ');

      await enviar('form.alta');

      expect(servicio.crear).toHaveBeenCalledWith({
        razonSocial: 'Juan Pérez',
        condicionIva: 'CONSUMIDOR_FINAL',
        telefono: '223 555',
      });
      expect(texto('.alerta.exito')).toBe('Se guardó «Juan Pérez».');
    });

    it('mientras se guarda un alta los campos quedan bloqueados; al guardarse, el foco vuelve a la razón social para el siguiente', async () => {
      await crear();
      const respuesta = new Subject<Cliente>();
      servicio.crear.mockReturnValue(respuesta);
      await escribir('razon-social', 'Juan Pérez');

      await enviar('form.alta');

      expect(el().querySelector('form.alta')!.hasAttribute('inert')).toBe(true);

      respuesta.next({ ...ana, id: 99, razonSocial: 'Juan Pérez' });
      respuesta.complete();
      await refrescar();

      expect(el().querySelector('form.alta')!.hasAttribute('inert')).toBe(false);
      expect(input('razon-social').value).toBe('');
      expect(document.activeElement).toBe(input('razon-social'));
    });

    it('si el alta falla los campos se desbloquean con lo escrito', async () => {
      await crear();
      servicio.crear.mockReturnValue(throwError(() => errorHttp(500, 'Algo falló')));
      await escribir('razon-social', 'Juan Pérez');

      await enviar('form.alta');

      expect(el().querySelector('form.alta')!.hasAttribute('inert')).toBe(false);
      expect(input('razon-social').value).toBe('Juan Pérez');
    });

    it('manda el documento completo con su tipo, y la condición de IVA elegida', async () => {
      await crear();
      await escribir('razon-social', 'Ferretería Sur');
      await elegirEn('tipo-documento', 'CUIT');
      await escribir('numero-documento', '30-71234567-1');
      await elegirEn('condicion-iva', 'RESPONSABLE_INSCRIPTO');
      // D4: Responsable inscripto lleva domicilio (antes este alta iba sin él)
      await escribir('domicilio', 'Av. Colón 1234');

      await enviar('form.alta');

      expect(servicio.crear).toHaveBeenCalledWith({
        razonSocial: 'Ferretería Sur',
        tipoDocumento: 'CUIT',
        numeroDocumento: '30-71234567-1',
        condicionIva: 'RESPONSABLE_INSCRIPTO',
        domicilio: 'Av. Colón 1234',
      });
    });

    it('sin razón social no llama al servicio y marca el campo', async () => {
      await crear();

      await enviar('form.alta');

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(input('razon-social').getAttribute('aria-invalid')).toBe('true');
      expect(texto('#razon-social-error')).toBe('La razón social es obligatoria.');
    });

    it('el número sin tipo, o el tipo sin número, se marca antes de mandar', async () => {
      await crear();
      await escribir('razon-social', 'Ana');
      await escribir('numero-documento', '12345678');

      await enviar('form.alta');

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(texto('#tipo-documento-error')).toBe('Elegí el tipo de documento.');
    });

    it('el tipo sin número pide el número', async () => {
      await crear();
      await escribir('razon-social', 'Ana');
      await elegirEn('tipo-documento', 'DNI');

      await enviar('form.alta');

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(texto('#numero-documento-error')).toBe('Falta el número de documento.');
    });

    it('un correo que no parece correo se marca', async () => {
      await crear();
      await escribir('razon-social', 'Ana');
      await escribir('email', 'no-es-correo');

      await enviar('form.alta');

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(texto('#email-error')).toBe('El correo no es válido.');
    });

    it('guardado el alta, el formulario queda vacío y la lista se vuelve a pedir', async () => {
      await crear();
      await escribir('razon-social', 'Juan');
      const pedidos = servicio.buscar.mock.calls.length;

      await enviar('form.alta');

      expect(input('razon-social').value).toBe('');
      expect(servicio.buscar.mock.calls.length).toBe(pedidos + 1);
    });

    it('el error del servidor (400) sobre el documento sale debajo del campo, no arriba', async () => {
      const mensaje = 'El CUIT no es válido: revisá los números, el último no coincide.';
      await crear();
      servicio.crear.mockReturnValue(throwError(() => errorHttp(400, 'Datos inválidos', { numeroDocumento: mensaje })));
      await escribir('razon-social', 'Ana');
      await elegirEn('tipo-documento', 'CUIT');
      await escribir('numero-documento', '20284567897');

      await enviar('form.alta');

      expect(texto('#numero-documento-error')).toBe(mensaje);
      expect(input('numero-documento').getAttribute('aria-invalid')).toBe('true');
      expect(el().querySelector('.errores-campos')).toBeNull();
      expect(input('numero-documento').value).toBe('20284567897');
    });

    it('los errores del servidor sobre el tipo y el domicilio también van a su campo; los demás se listan arriba', async () => {
      await crear();
      servicio.crear.mockReturnValue(
        throwError(() =>
          errorHttp(400, 'Datos inválidos', {
            tipoDocumento: 'Elegí el tipo de documento.',
            domicilio: 'Falta el domicilio.',
            telefono: 'El teléfono es demasiado largo.',
          }),
        ),
      );
      await escribir('razon-social', 'Ana');

      await enviar('form.alta');

      expect(texto('#tipo-documento-error')).toBe('Elegí el tipo de documento.');
      expect(texto('#domicilio-error')).toBe('Falta el domicilio.');
      expect(texto('.errores-campos')).toContain('El teléfono es demasiado largo.');
    });

    it('un documento repetido (409) muestra el mensaje del servidor y conserva lo escrito', async () => {
      await crear();
      servicio.crear.mockReturnValue(throwError(() => errorHttp(409, 'Ya existe un cliente con ese tipo y número de documento.')));
      await escribir('razon-social', 'Ana');

      await enviar('form.alta');

      expect(texto('p.error')).toBe('Ya existe un cliente con ese tipo y número de documento.');
      expect(input('razon-social').value).toBe('Ana');
    });

    it('Editar precarga el cliente, y guardar llama a actualizar con su id', async () => {
      await crear();
      await elegir(1);

      barra('.editar')!.click();
      await refrescar();

      expect(input('razon-social').value).toBe('Ferretería Norte SRL');
      expect(select('tipo-documento').value).toBe('CUIT');
      expect(input('numero-documento').value).toBe('30712345671');
      expect(select('condicion-iva').value).toBe('RESPONSABLE_INSCRIPTO');
      expect(input('email').value).toBe('norte@correo.com');
      expect(texto('form.alta h2')).toBe('Editar cliente');

      await escribir('razon-social', 'Ferretería Norte SA');
      await enviar('form.alta');

      expect(servicio.actualizar).toHaveBeenCalledWith(
        2,
        expect.objectContaining({ razonSocial: 'Ferretería Norte SA', tipoDocumento: 'CUIT', numeroDocumento: '30712345671' }),
      );
    });

    it('al editar se puede sacar el documento: no se manda ni tipo ni número', async () => {
      await crear();
      await elegir(0);
      barra('.editar')!.click();
      await refrescar();
      await elegirEn('tipo-documento', '');
      await escribir('numero-documento', '');

      await enviar('form.alta');

      const pedido = servicio.actualizar.mock.calls[0][1] as ClienteRequest;
      expect('tipoDocumento' in pedido).toBe(false);
      expect('numeroDocumento' in pedido).toBe(false);
    });

    it('Cancelar vuelve al alta y vacía el formulario', async () => {
      await crear();
      await elegir(0);
      barra('.editar')!.click();
      await refrescar();

      boton('Cancelar')!.click();
      await refrescar();

      expect(texto('form.alta h2')).toBe('Nuevo cliente');
      expect(input('razon-social').value).toBe('');
    });

    it('el Vendedor también crea y edita', async () => {
      await crear([ana], false);
      await escribir('razon-social', 'Juan');

      await enviar('form.alta');

      expect(servicio.crear).toHaveBeenCalled();
      expect(barra('.editar')).not.toBeNull();
    });
  });

  describe('datos fiscales (CU-11, D4)', () => {
    const FALTA_CUIT = 'Falta el CUIT: es obligatorio para Responsable Inscripto, Monotributista o Exento.';
    const NO_ES_CUIT = 'Para Responsable Inscripto, Monotributista o Exento el documento tiene que ser el CUIT.';
    const FALTA_DOMICILIO = 'Falta el domicilio: es obligatorio para Responsable Inscripto, Monotributista o Exento.';
    const label = (id: string) => texto(`label[for="${id}"]`);

    it.each(['RESPONSABLE_INSCRIPTO', 'MONOTRIBUTISTA', 'EXENTO'])(
      'con %s el CUIT y el domicilio se marcan obligatorios; al volver a Consumidor final, no',
      async (condicion) => {
        await crear();

        await elegirEn('condicion-iva', condicion);

        expect(label('numero-documento')).toBe('Número de CUIT (obligatorio)');
        expect(label('domicilio')).toBe('Domicilio (obligatorio)');
        expect(input('numero-documento').getAttribute('aria-required')).toBe('true');
        expect(input('domicilio').getAttribute('aria-required')).toBe('true');
        expect(select('tipo-documento').getAttribute('aria-required')).toBe('true');
        expect(texto('#condicion-iva-pista')).toBe(
          'Responsable inscripto, Monotributista y Exento llevan CUIT y domicilio: los pide la factura.',
        );

        await elegirEn('condicion-iva', 'CONSUMIDOR_FINAL');

        expect(label('numero-documento')).toBe('Número');
        expect(label('domicilio')).toBe('Domicilio');
        expect(input('numero-documento').getAttribute('aria-required')).toBeNull();
        expect(input('domicilio').getAttribute('aria-required')).toBeNull();
        expect(el().querySelector('#condicion-iva-pista')).toBeNull();
      },
    );

    it.each(['RESPONSABLE_INSCRIPTO', 'MONOTRIBUTISTA', 'EXENTO'])(
      'con %s, sin CUIT ni domicilio no llama al servicio y marca los dos campos',
      async (condicion) => {
        await crear();
        await escribir('razon-social', 'Juan Pérez');
        await elegirEn('condicion-iva', condicion);
        await escribir('domicilio', '   ');

        await enviar('form.alta');

        expect(servicio.crear).not.toHaveBeenCalled();
        expect(texto('#numero-documento-error')).toBe(FALTA_CUIT);
        expect(input('numero-documento').getAttribute('aria-invalid')).toBe('true');
        expect(texto('#domicilio-error')).toBe(FALTA_DOMICILIO);
        expect(input('domicilio').getAttribute('aria-invalid')).toBe('true');
      },
    );

    it('con un DNI en vez del CUIT no llama al servicio y marca el tipo de documento', async () => {
      await crear();
      await escribir('razon-social', 'Juan Pérez');
      await elegirEn('condicion-iva', 'MONOTRIBUTISTA');
      await elegirEn('tipo-documento', 'DNI');
      await escribir('numero-documento', '12345678');
      await escribir('domicilio', 'Av. Colón 1234');

      await enviar('form.alta');

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(texto('#tipo-documento-error')).toBe(NO_ES_CUIT);
      expect(select('tipo-documento').getAttribute('aria-invalid')).toBe('true');
      expect(el().querySelector('#domicilio-error')).toBeNull();
    });

    it('editar un Monotributista cargado sin CUIT ni domicilio pide los dos antes de guardar', async () => {
      await crear();
      await elegir(2);
      barra('.editar')!.click();
      await refrescar();

      await enviar('form.alta');

      expect(servicio.actualizar).not.toHaveBeenCalled();
      expect(texto('#numero-documento-error')).toBe(FALTA_CUIT);
      expect(texto('#domicilio-error')).toBe(FALTA_DOMICILIO);
    });

    it('los errores por campo del servidor (400) sobre el CUIT y el domicilio salen debajo de cada campo', async () => {
      await crear();
      servicio.crear.mockReturnValue(
        throwError(() => errorHttp(400, 'Datos inválidos', { numeroDocumento: FALTA_CUIT, domicilio: FALTA_DOMICILIO })),
      );
      await escribir('razon-social', 'Juan Pérez');

      await enviar('form.alta');

      expect(texto('#numero-documento-error')).toBe(FALTA_CUIT);
      expect(texto('#domicilio-error')).toBe(FALTA_DOMICILIO);
      expect(el().querySelector('.errores-campos')).toBeNull();
    });
  });

  describe('desactivar (solo el Dueño)', () => {
    it('el Dueño desactiva al elegido y la fila pasa a Inactivo', async () => {
      await crear();
      await elegir(0);

      barra('.desactivar')!.click();
      await refrescar();

      expect(servicio.cambiarActivo).toHaveBeenCalledWith(1, false);
      expect(filas()[0].textContent).toContain('Inactivo');
    });

    it('en un cliente inactivo el botón dice Reactivar y lo reactiva', async () => {
      await crear();
      await elegir(2);

      expect(barra('.desactivar')!.textContent!.trim()).toBe('Reactivar');
      barra('.desactivar')!.click();
      await refrescar();

      expect(servicio.cambiarActivo).toHaveBeenCalledWith(3, true);
    });

    it('el Vendedor no ve el botón de desactivar', async () => {
      await crear([ana], false);

      expect(barra('.desactivar')).toBeNull();
    });

    it('sin elegir a nadie, el botón está apagado', async () => {
      await crear();

      expect(barra('.desactivar')!.disabled).toBe(true);
    });

    it('si el servidor falla al desactivar, avisa y la fila no cambia', async () => {
      await crear();
      servicio.cambiarActivo.mockReturnValue(throwError(() => errorHttp(403, 'No permitido')));
      await elegir(0);

      barra('.desactivar')!.click();
      await refrescar();

      expect(texto('p.error')).toBe('No permitido');
      expect(filas()[0].textContent).toContain('Activo');
    });
  });
  describe('cuenta corriente (CC-018, CC-019, CC-020)', () => {
    const deudor: Cliente = {
      id: 7,
      razonSocial: 'Corralón Sur',
      tipoDocumento: 'CUIT',
      numeroDocumento: '30712345671',
      condicionIva: 'RESPONSABLE_INSCRIPTO',
      domicilio: 'Av. Luro 3200',
      activo: true,
      cuentaCorrienteHabilitada: true,
      limiteCuentaCorriente: 5000,
      saldo: 1500,
    };
    const aFavor: Cliente = { ...deudor, id: 8, razonSocial: 'Pinturería Este', limiteCuentaCorriente: 1000, saldo: -50 };
    const alDia: Cliente = { ...deudor, id: 9, razonSocial: 'Obra Norte', limiteCuentaCorriente: 0, saldo: 0 };
    const sinCuenta: Cliente = { ...ana, saldo: 0 };
    /** Lo que recibe el Vendedor: la bandera sí, las cifras no. */
    const paraVendedor = (c: Cliente): Cliente => ({ ...c, limiteCuentaCorriente: undefined, saldo: undefined });

    const dicho = (e: Element | null | undefined) => (e?.textContent ?? '').replace(/\s+/g, ' ').trim();
    const encabezados = () => Array.from(el().querySelectorAll('thead th')).map((th) => dicho(th));
    const seccion = () => el().querySelector('fieldset.cuenta-corriente');
    const habilitar = () => input('cuenta-habilitada');
    const limite = () => input('limite-cuenta');

    /** Lo que hace el navegador al teclear: cambia el valor, mueve el cursor y avisa con `input`. */
    async function teclear(id: string, texto: string) {
      const campo = input(id);
      for (const letra of texto) {
        const cursor = campo.selectionStart ?? campo.value.length;
        campo.value = campo.value.slice(0, cursor) + letra + campo.value.slice(campo.selectionEnd ?? cursor);
        campo.setSelectionRange(cursor + 1, cursor + 1);
        campo.dispatchEvent(new Event('input', { bubbles: true }));
      }
      await refrescar();
    }

    /** Pone el límite sin pasar por el teclado: la máscara no deja escribir un negativo ni más de 2 decimales. */
    async function ponerLimite(valor: number) {
      fixture.componentInstance['form'].controls.limiteCuenta.setValue(valor);
      await refrescar();
    }
    const desactivar = () => barra('.desactivar')!;

    async function tildar(id: string, valor: boolean) {
      input(id).checked = valor;
      input(id).dispatchEvent(new Event('change'));
      await refrescar();
    }

    async function editarFila(fila: number) {
      await elegir(fila);
      barra('.editar')!.click();
      await refrescar();
    }

    /** Un servicio con la cuenta corriente: cambiarLimite devuelve la vista del Dueño. */
    function conCambiarLimite() {
      servicio.cambiarLimite = vi.fn((_id: number, l: number | null) =>
        of(l === null ? { habilitada: false } : { habilitada: true, limite: l, saldo: 0, disponible: l }),
      );
    }

    describe('la lista', () => {
      it('el Dueño ve las columnas Saldo y Límite con las cifras de cada cliente', async () => {
        await crear([deudor, aFavor, alDia, sinCuenta]);

        expect(encabezados()).toContain('Saldo');
        expect(encabezados()).toContain('Límite');
        expect(dicho(filas()[0])).toContain('$ 1.500,00');
        expect(dicho(filas()[0])).toContain('$ 5.000,00');
        expect(dicho(filas()[1])).toContain('$ 50,00 a favor');
        expect(dicho(filas()[2])).toContain('$ 0,00');
        expect(dicho(filas()[3])).toContain('Sin habilitar');
      });

      it('el Vendedor no ve las columnas ni ninguna cifra', async () => {
        await crear([paraVendedor(deudor)], false);

        expect(encabezados()).not.toContain('Saldo');
        expect(encabezados()).not.toContain('Límite');
        expect(el().textContent).not.toMatch(/1\.500|5\.000|Sin habilitar/);
      });
    });

    describe('la sección «Cuenta corriente» del formulario', () => {
      it('el Dueño la ve al editar, con la casilla tildada y el límite cargado', async () => {
        await crear([deudor, ana]);

        await editarFila(0);

        expect(dicho(seccion()?.querySelector('legend'))).toBe('Cuenta corriente');
        expect(habilitar().checked).toBe(true);
        expect(limite().value).toBe('5.000');
        expect(limite().disabled).toBe(false);
      });

      it('el Dueño que edita un cliente sin cuenta corriente ve la casilla destildada y el límite bloqueado', async () => {
        await crear([sinCuenta]);

        await editarFila(0);

        expect(habilitar().checked).toBe(false);
        expect(limite().disabled).toBe(true);
        expect(limite().value).toBe('');
      });

      it('también está en el alta (Nuevo) para el Dueño', async () => {
        await crear([ana]);

        expect(seccion()).not.toBeNull();
        expect(habilitar().checked).toBe(false);
      });

      it('el Vendedor no la ve ni al editar ni en el alta', async () => {
        await crear([paraVendedor(deudor)], false);
        expect(seccion()).toBeNull();

        await editarFila(0);

        expect(seccion()).toBeNull();
        expect(el().querySelector('#cuenta-habilitada')).toBeNull();
      });

      it('tildar «Habilitar» desbloquea el límite y destildar lo bloquea', async () => {
        await crear([sinCuenta]);
        await editarFila(0);

        await tildar('cuenta-habilitada', true);
        expect(limite().disabled).toBe(false);

        await tildar('cuenta-habilitada', false);
        expect(limite().disabled).toBe(true);
      });

      it('al pasar de un cliente a otro la sección sigue al cliente editado', async () => {
        await crear([deudor, sinCuenta]);
        await editarFila(0);
        expect(habilitar().checked).toBe(true);

        await editarFila(1);

        expect(habilitar().checked).toBe(false);
        expect(limite().value).toBe('');
      });
    });

    describe('el aviso de la forma de pago «Cuenta corriente» (S6)', () => {
      const SIN_CUENTA_CORRIENTE: FormaPago[] = [CON_CUENTA_CORRIENTE[0]];
      const DESACTIVADA = 'La forma de pago «Cuenta corriente» está desactivada: el mostrador no ofrece vender a cuenta.';
      const aviso = () => el().querySelector('.aviso-forma-cuenta');

      it('en el alta: tildar «Habilitar» sin forma activa avisa y lleva a «Formas de pago»', async () => {
        await crear([ana], true, undefined, SIN_CUENTA_CORRIENTE);
        expect(aviso()).toBeNull();

        await tildar('cuenta-habilitada', true);

        expect(dicho(aviso()?.querySelector('span'))).toBe(DESACTIVADA);
        const enlace = aviso()!.querySelector('a')!;
        expect(dicho(enlace)).toBe('Activala en Formas de pago');
        expect(enlace.getAttribute('href')).toBe('/configuracion/formas-pago');
      });

      it('en la edición: un cliente con la cuenta habilitada ya lo muestra', async () => {
        await crear([deudor], true, undefined, SIN_CUENTA_CORRIENTE);

        await editarFila(0);

        expect(dicho(aviso()?.querySelector('span'))).toBe(DESACTIVADA);
      });

      it('con la forma activa no aparece', async () => {
        await crear([deudor], true, undefined, CON_CUENTA_CORRIENTE);

        await editarFila(0);

        expect(aviso()).toBeNull();
      });

      it('con la casilla destildada no aparece', async () => {
        await crear([sinCuenta], true, undefined, SIN_CUENTA_CORRIENTE);

        await editarFila(0);

        expect(aviso()).toBeNull();
      });

      it('si no se pudieron leer las formas no avisa: no se sabe si está desactivada', async () => {
        await crear([deudor], true, undefined, new Error('sin red'));

        await editarFila(0);

        expect(aviso()).toBeNull();
      });

      it('el Vendedor no edita la casilla: no ve el aviso y no se piden las formas', async () => {
        await crear([paraVendedor(deudor)], false, undefined, SIN_CUENTA_CORRIENTE);

        await editarFila(0);

        expect(aviso()).toBeNull();
        expect(formasApi.listar).not.toHaveBeenCalled();
      });
    });

    describe('guardar el límite', () => {
      it('sin cambiar el límite solo llama al PUT del cliente', async () => {
        await crear([deudor]);
        conCambiarLimite();
        await editarFila(0);
        await escribir('telefono', '2231112222');

        await enviar('form.alta');

        expect(servicio.actualizar).toHaveBeenCalledTimes(1);
        expect(servicio.cambiarLimite).not.toHaveBeenCalled();
      });

      it('si cambió el límite llama primero al PUT del cliente y después a cambiarLimite aparte', async () => {
        const orden: string[] = [];
        await crear([deudor]);
        conCambiarLimite();
        servicio.actualizar.mockImplementation((id: number, r: ClienteRequest) => {
          orden.push('cliente');
          return of({ ...deudor, id, ...r });
        });
        servicio.cambiarLimite.mockImplementation((_id: number, l: number | null) => {
          orden.push('limite');
          return of({ habilitada: true, limite: l ?? undefined });
        });
        await editarFila(0);
        await escribir('limite-cuenta', '8000');

        await enviar('form.alta');

        expect(orden).toEqual(['cliente', 'limite']);
        expect(servicio.cambiarLimite).toHaveBeenCalledWith(7, 8000);
        expect(texto('.alerta.exito')).toBe('Se guardó «Corralón Sur».');
      });

      it('el límite no viaja en el PUT del cliente (ClienteRequest no lo lleva)', async () => {
        await crear([deudor]);
        conCambiarLimite();
        await editarFila(0);
        await escribir('limite-cuenta', '8000');

        await enviar('form.alta');

        const request = servicio.actualizar.mock.calls[0][1] as Record<string, unknown>;
        expect('limiteCuentaCorriente' in request).toBe(false);
        expect('limite' in request).toBe(false);
        expect('saldo' in request).toBe(false);
      });

      it('destildar «Habilitar» deshabilita la cuenta: cambiarLimite con null', async () => {
        await crear([deudor]);
        conCambiarLimite();
        await editarFila(0);
        await tildar('cuenta-habilitada', false);

        await enviar('form.alta');

        expect(servicio.cambiarLimite).toHaveBeenCalledWith(7, null);
      });

      it('el límite lleva la máscara de importes: 50000 se ve 50.000 y viaja como 50000', async () => {
        await crear([sinCuenta]);
        conCambiarLimite();
        await editarFila(0);
        await tildar('cuenta-habilitada', true);

        await teclear('limite-cuenta', '50000');
        expect(limite().value).toBe('50.000');
        await enviar('form.alta');

        expect(servicio.cambiarLimite).toHaveBeenCalledWith(expect.any(Number), 50000);
      });

      it('un límite 0 está permitido: habilita la cuenta sin crédito', async () => {
        await crear([sinCuenta]);
        conCambiarLimite();
        await editarFila(0);
        await tildar('cuenta-habilitada', true);
        await escribir('limite-cuenta', '0');

        await enviar('form.alta');

        expect(servicio.cambiarLimite).toHaveBeenCalledWith(1, 0);
      });

      it('con «Habilitar» tildado y sin límite avisa en el campo y no llama a nada', async () => {
        await crear([sinCuenta]);
        conCambiarLimite();
        await editarFila(0);
        await tildar('cuenta-habilitada', true);

        await enviar('form.alta');

        expect(texto('#limite-cuenta-error')).toBe('Escribí el límite de la cuenta corriente (puede ser 0).');
        expect(limite().getAttribute('aria-invalid')).toBe('true');
        expect(servicio.actualizar).not.toHaveBeenCalled();
        expect(servicio.cambiarLimite).not.toHaveBeenCalled();
      });

      it('un límite negativo o con más de 2 decimales no se envía', async () => {
        await crear([sinCuenta]);
        conCambiarLimite();
        await editarFila(0);
        await tildar('cuenta-habilitada', true);

        await ponerLimite(-1);
        await enviar('form.alta');
        expect(texto('#limite-cuenta-error')).toBe('El límite no puede ser negativo.');

        await ponerLimite(10.001);
        await enviar('form.alta');
        expect(texto('#limite-cuenta-error')).toBe('El límite admite hasta 2 decimales.');

        expect(servicio.actualizar).not.toHaveBeenCalled();
        expect(servicio.cambiarLimite).not.toHaveBeenCalled();
      });

      it('un límite sobre el tope no se envía', async () => {
        await crear([sinCuenta]);
        conCambiarLimite();
        await editarFila(0);
        await tildar('cuenta-habilitada', true);
        await escribir('limite-cuenta', '10000000000000');

        await enviar('form.alta');

        expect(texto('#limite-cuenta-error')).toBe('El límite es demasiado grande.');
        expect(servicio.cambiarLimite).not.toHaveBeenCalled();
      });

      it('en el alta con «Habilitar» crea el cliente y fija el límite del recién creado', async () => {
        await crear([ana]);
        conCambiarLimite();
        await escribir('razon-social', 'Obra Oeste');
        await tildar('cuenta-habilitada', true);
        await escribir('limite-cuenta', '3000');

        await enviar('form.alta');

        expect(servicio.crear).toHaveBeenCalledTimes(1);
        expect(servicio.cambiarLimite).toHaveBeenCalledWith(99, 3000);
      });

      it('en el alta sin tildar «Habilitar» no llama a cambiarLimite', async () => {
        await crear([ana]);
        conCambiarLimite();
        await escribir('razon-social', 'Obra Oeste');

        await enviar('form.alta');

        expect(servicio.crear).toHaveBeenCalledTimes(1);
        expect(servicio.cambiarLimite).not.toHaveBeenCalled();
      });

      it('si falla el PUT del cliente no se toca el límite', async () => {
        await crear([deudor]);
        conCambiarLimite();
        servicio.actualizar.mockReturnValue(throwError(() => errorHttp(409, 'Ya existe un cliente con ese documento.')));
        await editarFila(0);
        await escribir('limite-cuenta', '8000');

        await enviar('form.alta');

        expect(servicio.cambiarLimite).not.toHaveBeenCalled();
        expect(texto('p.error')).toBe('Ya existe un cliente con ese documento.');
      });

      it('si falla la segunda llamada el cliente queda guardado y se avisa del límite', async () => {
        await crear([deudor]);
        conCambiarLimite();
        servicio.cambiarLimite.mockReturnValue(throwError(() => errorHttp(400, 'Datos inválidos', { limite: 'El límite es demasiado grande.' })));
        await editarFila(0);
        await escribir('limite-cuenta', '8000');
        servicio.buscar.mockClear();

        await enviar('form.alta');

        expect(servicio.actualizar).toHaveBeenCalledTimes(1);
        expect(texto('[role="alert"]')).toContain('Se guardó «Corralón Sur», pero no se pudo cambiar el límite de la cuenta corriente.');
        expect(texto('[role="alert"]')).toContain('El límite es demasiado grande.');
        expect(el().querySelector('.alerta.exito')).toBeNull();
        expect(el().querySelector('form.alta h2')?.textContent).toContain('Nuevo cliente');
        expect(servicio.buscar).toHaveBeenCalledTimes(1);
      });

      it('si falla la segunda llamada sin mensaje por campo usa el mensaje del servidor', async () => {
        await crear([deudor]);
        conCambiarLimite();
        servicio.cambiarLimite.mockReturnValue(throwError(() => errorHttp(403, 'Acceso denegado')));
        await editarFila(0);
        await escribir('limite-cuenta', '8000');

        await enviar('form.alta');

        expect(texto('[role="alert"]')).toContain('Acceso denegado');
      });

      it('después de guardar el límite recarga la lista para mostrar el saldo y el límite vigentes', async () => {
        await crear([deudor]);
        conCambiarLimite();
        await editarFila(0);
        await escribir('limite-cuenta', '8000');
        servicio.buscar.mockClear();

        await enviar('form.alta');

        expect(servicio.buscar).toHaveBeenCalledTimes(1);
      });
    });

    describe('desactivar con saldo (lección G77: deshabilitar con razón, no ocultar)', () => {
      const RAZON = 'No se puede desactivar: el cliente tiene saldo en cuenta corriente.';

      it('con saldo que debe el botón queda deshabilitado con la razón a la vista y enlazada', async () => {
        await crear([deudor]);

        await elegir(0);

        expect(desactivar().getAttribute('aria-disabled')).toBe('true');
        const id = desactivar().getAttribute('aria-describedby')!;
        expect(texto(`#${id}`)).toBe(RAZON);
      });

      it('la razón NO va dentro de la barra de acciones (no le quita ancho a los botones) sino en una línea fija justo debajo', async () => {
        await crear([deudor]);

        await elegir(0);

        const barra = el().querySelector('app-barra-acciones')!;
        const razon = el().querySelector('#motivo-desactivar')!;
        expect(barra.contains(razon)).toBe(false);
        expect(razon.parentElement!.classList.contains('motivo-linea')).toBe(true);
        expect(barra.nextElementSibling).toBe(razon.parentElement);
        expect(razon.textContent!.trim()).toBe(RAZON);
        expect(razon.hasAttribute('hidden')).toBe(false);
      });

      it('la línea de la razón existe siempre (reserva su altura) y está vacía si no hay razón: elegir un deudor no mueve la grilla', async () => {
        await crear([deudor, alDia]);

        const linea = () => el().querySelector('.motivo-linea')!;
        expect(linea()).not.toBeNull();
        expect(linea().textContent!.trim()).toBe('');
        const antes = linea();

        await elegir(0);
        expect(linea()).toBe(antes);
        expect(linea().textContent!.trim()).toBe(RAZON);

        await elegir(1);
        expect(linea()).toBe(antes);
        expect(linea().textContent!.trim()).toBe('');
      });

      it('con saldo a favor también', async () => {
        await crear([aFavor]);

        await elegir(0);

        expect(desactivar().getAttribute('aria-disabled')).toBe('true');
        expect(texto('#motivo-desactivar')).toBe(RAZON);
      });

      it('el clic en el botón con saldo no llama al servidor', async () => {
        await crear([deudor]);
        await elegir(0);

        desactivar().click();
        await refrescar();

        expect(servicio.cambiarActivo).not.toHaveBeenCalled();
      });

      it('con saldo 0 el botón funciona y no hay razón', async () => {
        await crear([alDia]);
        await elegir(0);

        expect(desactivar().hasAttribute('aria-disabled')).toBe(false);
        expect(el().querySelector('#motivo-desactivar')).toBeNull();
        desactivar().click();
        await refrescar();

        expect(servicio.cambiarActivo).toHaveBeenCalledWith(9, false);
      });

      it('un cliente sin cuenta corriente se desactiva como siempre (regresión)', async () => {
        await crear([sinCuenta]);
        await elegir(0);

        expect(desactivar().hasAttribute('aria-disabled')).toBe(false);
      });

      it('reactivar un cliente desactivado con saldo siempre se puede', async () => {
        await crear([{ ...deudor, activo: false }]);
        await elegir(0);

        expect(desactivar().textContent!.trim()).toBe('Reactivar');
        expect(desactivar().hasAttribute('aria-disabled')).toBe(false);
        desactivar().click();
        await refrescar();

        expect(servicio.cambiarActivo).toHaveBeenCalledWith(7, true);
      });

      it('el 422 del servidor igual se muestra (el saldo pudo cambiar desde que se cargó la lista)', async () => {
        await crear([alDia]);
        servicio.cambiarActivo.mockReturnValue(throwError(() => errorHttp(422, RAZON)));
        await elegir(0);

        desactivar().click();
        await refrescar();

        expect(texto('p.error')).toBe(RAZON);
        expect(filas()[0].textContent).toContain('Activo');
      });
    });
  });

  describe('botón «Cuenta corriente» (CC-028, CC-043)', () => {
    const cuenta = () => barra('.cuenta');
    const teclaEnFila = (i: number, key: string) =>
      filas()[i].dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));

    it('el Dueño lo ve en la barra, apagado hasta elegir una fila', async () => {
      await crear();

      expect(cuenta()).not.toBeNull();
      expect(cuenta()!.textContent!.trim()).toBe('Cuenta corriente');
      expect(cuenta()!.disabled).toBe(true);

      await elegir(1);

      expect(cuenta()!.disabled).toBe(false);
    });

    it('navega a la pantalla de cuenta del cliente elegido', async () => {
      await crear();
      const navegar = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      await elegir(1);
      cuenta()!.click();

      expect(navegar).toHaveBeenCalledWith(['/clientes', 2, 'cuenta']);
    });

    it('el Vendedor no lo ve', async () => {
      await crear([ana, norte, viejo], false);

      expect(cuenta()).toBeNull();
      expect(boton('Cuenta corriente')).toBeUndefined();
    });

    it('sin fila elegida el clic no navega', async () => {
      await crear();
      const navegar = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      cuenta()!.click();

      expect(navegar).not.toHaveBeenCalled();
    });

    it('Enter en la grilla sigue editando (regresión) y no navega', async () => {
      await crear();
      const navegar = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      await elegir(0);
      teclaEnFila(0, 'Enter');
      await refrescar();

      expect(input('razon-social').value).toBe('Ana Gómez');
      expect(navegar).not.toHaveBeenCalled();
    });

    it('no hay atajo nuevo en la grilla: ni F4 ni C ni otra tecla llevan a la cuenta', async () => {
      await crear();
      const navegar = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      await elegir(0);
      for (const key of ['F4', 'F6', 'c', 'C']) teclaEnFila(0, key);
      await refrescar();

      expect(navegar).not.toHaveBeenCalled();
    });
  });
});
