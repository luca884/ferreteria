import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { FormaPago } from '../formas-pago/forma-pago.models';
import { FormasPagoService } from '../formas-pago/formas-pago.service';
import { Cliente, ClienteRequest } from './cliente.models';
import { Clientes } from './clientes';
import { ClientesService } from './clientes.service';

/**
 * movil-sectores T12a (S1, S2, S5, S7): la lista y la ficha de Clientes en el celular. El mismo componente, estado,
 * validación y pedidos que en la tablet y el escritorio; sólo cambia cómo se ve (tarjetas, «Nuevo» flotante, ficha a
 * pantalla completa con «Volver» y el pie fijo, ⋯ con Desactivar). Vitest no evalúa media queries ni lee `styles.css`:
 * acá va el comportamiento y la estructura que el CSS necesita; las medidas se hacen en el navegador a 390 px.
 */
describe('Clientes en el celular (T12a)', () => {
  const ana: Cliente = {
    id: 1,
    razonSocial: 'Ana Gómez',
    tipoDocumento: 'DNI',
    numeroDocumento: '12345678',
    condicionIva: 'CONSUMIDOR_FINAL',
    telefono: '2234567890',
    activo: true,
    cuentaCorrienteHabilitada: true,
    limiteCuentaCorriente: 5000,
    saldo: 1200,
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
    cuentaCorrienteHabilitada: true,
    limiteCuentaCorriente: 0,
    saldo: -5000,
  };
  const viejo: Cliente = {
    id: 3,
    razonSocial: 'Pedro Viejo',
    condicionIva: 'MONOTRIBUTISTA',
    activo: false,
    cuentaCorrienteHabilitada: false,
    saldo: 0,
  };
  /** Lo que recibe el Vendedor: la bandera sí, las cifras no. */
  const paraVendedor = (c: Cliente): Cliente => ({ ...c, limiteCuentaCorriente: undefined, saldo: undefined });

  const pagina = (items: Cliente[]): PageResponse<Cliente> => ({
    items,
    page: 0,
    size: 20,
    totalElements: items.length,
    totalPages: 1,
  });
  const FORMAS: FormaPago[] = [
    { id: 5, nombre: 'Cuenta corriente', medio: 'CUENTA_CORRIENTE', recargoPorcentaje: 0, descuentoPorcentaje: 0, activa: true, orden: 5 },
  ];

  const errorHttp = (status: number, message: string, errors?: Record<string, string>) =>
    new HttpErrorResponse({ status, error: { status, message, errors } });

  let fixture: ComponentFixture<Clientes>;
  let servicio: {
    buscar: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    actualizar: ReturnType<typeof vi.fn>;
    cambiarActivo: ReturnType<typeof vi.fn>;
    cambiarLimite: ReturnType<typeof vi.fn>;
  };
  let router: Router;

  /** celular: matchMedia da true sólo para el celular. tableta: sólo para la tablet (1023.98 px), así que no es celular. */
  const simular = (modo: 'celular' | 'tableta') =>
    vi.stubGlobal('matchMedia', (consulta: string) => ({
      matches: consulta.includes(modo === 'celular' ? '639.98px' : '1023.98px'),
      media: consulta,
    }));

  async function crear(modo: 'celular' | 'tableta', lista: Cliente[] = [ana, norte, viejo], dueno = true) {
    simular(modo);
    servicio = {
      buscar: vi.fn(() => of(pagina(lista))),
      crear: vi.fn((r: ClienteRequest) => of({ id: 99, activo: true, cuentaCorrienteHabilitada: false, ...r })),
      actualizar: vi.fn((id: number, r: ClienteRequest) => of({ id, activo: true, cuentaCorrienteHabilitada: false, ...r })),
      cambiarActivo: vi.fn((id: number, activo: boolean) => of({ ...([ana, norte, viejo].find((c) => c.id === id) as Cliente), activo })),
      cambiarLimite: vi.fn((_id: number, limite: number | null) =>
        of(limite === null ? { habilitada: false } : { habilitada: true, limite, saldo: 0, disponible: limite }),
      ),
    };
    await TestBed.configureTestingModule({
      imports: [Clientes],
      providers: [
        provideRouter([]),
        { provide: ClientesService, useValue: servicio },
        { provide: FormasPagoService, useValue: { listar: vi.fn(() => of(FORMAS)) } },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
      ],
    }).compileComponents();
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(Clientes);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => vi.unstubAllGlobals());

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || undefined;
  const filas = () => Array.from(el().querySelectorAll<HTMLTableRowElement>('tbody tr'));
  const celdas = (fila: HTMLTableRowElement) => Array.from(fila.querySelectorAll('td')).map((td) => limpio(td.textContent));
  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
  const select = (id: string) => el().querySelector<HTMLSelectElement>(`#${id}`)!;
  const barra = (selector: string) => el().querySelector<HTMLButtonElement>(`app-barra-acciones ${selector}`);
  const ficha = () => el().querySelector<HTMLFormElement>('form.alta')!;
  const flotante = () => el().querySelector<HTMLButtonElement>('button.boton-flotante');
  const botonesDelPie = () => Array.from(el().querySelectorAll('form.alta .ficha-pie button')).map((b) => limpio(b.textContent));

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
  async function enviar() {
    ficha().dispatchEvent(new Event('submit'));
    await refrescar();
  }
  async function pulsar(boton: HTMLElement | null | undefined) {
    boton!.click();
    await refrescar();
  }
  async function confirmar() {
    await pulsar(el().querySelector<HTMLButtonElement>('app-confirmacion button.confirmar'));
  }
  const alertas = () => Array.from(el().querySelectorAll('[role="alert"]')).map((a) => limpio(a.textContent));

  /** Abre el alta como lo hace cada pantalla: «Nuevo» flotante en el celular, el de la barra en la tablet. */
  async function abrirAlta(modo: 'celular' | 'tableta') {
    await pulsar(modo === 'celular' ? flotante() : barra('.nuevo'));
  }
  /** Abre la edición de la fila: tocarla en el celular; elegirla y «Editar» en la tablet. */
  async function abrirEdicion(modo: 'celular' | 'tableta', fila: number) {
    (filas()[fila].querySelector('td') as HTMLElement).click();
    await refrescar();
    if (modo === 'tableta') await pulsar(barra('.editar'));
  }
  /** Desactivar o reactivar al cliente que se está editando: el ⋯ de la ficha en el celular; la barra en la tablet. */
  async function pedirDesactivar(modo: 'celular' | 'tableta') {
    if (modo === 'tableta') return pulsar(barra('.desactivar'));
    await pulsar(el().querySelector('form.alta button.mas-ficha'));
    await pulsar(document.querySelector('app-hoja-inferior .accion-hoja.alternar'));
  }

  const MODOS: ('celular' | 'tableta')[] = ['celular', 'tableta'];

  describe.each(MODOS)('lo que no cambia con el celular: pedidos, textos y reglas (%s)', (modo) => {
    it('al abrir pide la primera página y cada fila dice lo mismo (con saldo, «a favor» y límite del Dueño)', async () => {
      await crear(modo);

      expect(servicio.buscar).toHaveBeenCalledExactlyOnceWith({ q: '', soloActivos: false, page: 0 });
      expect(filas().map(celdas)).toEqual([
        ['Ana Gómez', 'DNI 12.345.678', 'Consumidor final', 'Activo', '$ 1.200,00', '$ 5.000,00', '2234567890'],
        ['Ferretería Norte SRL', 'CUIT 30-71234567-1', 'Responsable inscripto', 'Activo', '$ 5.000,00 a favor', '$ 0,00', 'norte@correo.com'],
        ['Pedro Viejo', '', 'Monotributista', 'Inactivo', '$ 0,00', 'Sin habilitar', ''],
      ]);
      expect(texto('.barra-estado')).toBe('3 clientes');
    });

    it('el Vendedor no ve saldo ni límite: ni en la lista ni en la ficha', async () => {
      await crear(modo, [paraVendedor(ana), paraVendedor(norte)], false);
      await abrirEdicion(modo, 0);

      expect(Array.from(el().querySelectorAll('thead th')).map((th) => limpio(th.textContent))).toEqual([
        'Razón social',
        'Documento',
        'Condición de IVA',
        'Estado',
        'Contacto',
      ]);
      expect(filas().map(celdas)[0]).toEqual(['Ana Gómez', 'DNI 12.345.678', 'Consumidor final', 'Activo', '2234567890']);
      expect(el().querySelector('fieldset.cuenta-corriente')).toBeNull();
      expect(el().querySelector('.cuenta-ficha')).toBeNull();
      expect(el().querySelector('.mas-ficha')).toBeNull();
      expect(el().querySelector('.desactivar')).toBeNull();
    });

    it('buscar manda q recortado y «Solo activos»; Limpiar lo saca todo y vuelve a pedir', async () => {
      await crear(modo);
      await escribir('q', '  perez ');
      input('solo-activos').click();
      await refrescar();

      el().querySelector('form.filtros')!.dispatchEvent(new Event('submit'));
      await refrescar();
      expect(servicio.buscar).toHaveBeenLastCalledWith({ q: 'perez', soloActivos: true, page: 0 });

      const limpiar = Array.from(el().querySelectorAll<HTMLButtonElement>('form.filtros button')).find((b) => limpio(b.textContent) === 'Limpiar');
      await pulsar(limpiar);
      expect(servicio.buscar).toHaveBeenLastCalledWith({ q: '', soloActivos: false, page: 0 });
      expect(input('q').value).toBe('');
      expect(input('solo-activos').checked).toBe(false);
    });

    it('el alta manda sólo lo cargado, sin espacios de más, y después avisa y vuelve a pedir la lista', async () => {
      await crear(modo);
      await abrirAlta(modo);
      await escribir('razon-social', '  Juan Pérez  ');
      await escribir('telefono', ' 223 555 ');
      await elegirEn('tipo-documento', 'DNI');
      await escribir('numero-documento', '28554101');

      await enviar();

      expect(servicio.crear).toHaveBeenCalledExactlyOnceWith({
        razonSocial: 'Juan Pérez',
        tipoDocumento: 'DNI',
        numeroDocumento: '28554101',
        condicionIva: 'CONSUMIDOR_FINAL',
        telefono: '223 555',
      });
      expect(servicio.cambiarLimite).not.toHaveBeenCalled();
      expect(texto('.alerta.exito')).toBe('Se guardó «Juan Pérez».');
      expect(servicio.buscar).toHaveBeenCalledTimes(2);
      expect(input('razon-social').value).toBe('');
    });

    it('sin razón social, o con un Responsable inscripto sin CUIT ni domicilio, avisa con los mismos textos y no llama al servicio', async () => {
      await crear(modo);
      await abrirAlta(modo);

      await enviar();
      expect(texto('#razon-social-error')).toBe('La razón social es obligatoria.');

      await escribir('razon-social', 'Obras SRL');
      await elegirEn('condicion-iva', 'RESPONSABLE_INSCRIPTO');
      await enviar();
      expect(texto('#numero-documento-error')).toBe('Falta el CUIT: es obligatorio para Responsable Inscripto, Monotributista o Exento.');
      expect(texto('#domicilio-error')).toBe('Falta el domicilio: es obligatorio para Responsable Inscripto, Monotributista o Exento.');
      expect(servicio.crear).not.toHaveBeenCalled();
    });

    it('el correo inválido y el límite sin escribir se marcan con su texto', async () => {
      await crear(modo);
      await abrirAlta(modo);
      await escribir('razon-social', 'Ana');
      await escribir('email', 'sin-arroba');
      input('cuenta-habilitada').click();
      await refrescar();

      await enviar();

      expect(texto('#email-error')).toBe('El correo no es válido.');
      expect(texto('#limite-cuenta-error')).toBe('Escribí el límite de la cuenta corriente (puede ser 0).');
      expect(servicio.crear).not.toHaveBeenCalled();
    });

    it('un 409 muestra el mensaje del servidor (una sola alerta), conserva lo escrito y no cambia la lista', async () => {
      await crear(modo);
      await abrirAlta(modo);
      servicio.crear.mockReturnValue(throwError(() => errorHttp(409, 'Ya existe un cliente con ese tipo y número de documento.')));
      await escribir('razon-social', 'Ana');

      await enviar();

      expect(alertas()).toEqual(['Ya existe un cliente con ese tipo y número de documento.']);
      expect(input('razon-social').value).toBe('Ana');
      expect(servicio.buscar).toHaveBeenCalledTimes(1);
      expect(filas()).toHaveLength(3);
      expect(el().querySelector('.alerta.exito')).toBeNull();
    });

    it('un 400 con errores por campo: el documento y el domicilio debajo de su campo, el resto en «Revisá estos datos:»', async () => {
      await crear(modo);
      await abrirAlta(modo);
      servicio.crear.mockReturnValue(
        throwError(() =>
          errorHttp(400, 'Datos inválidos', {
            numeroDocumento: 'El CUIT no es válido: revisá los números, el último no coincide.',
            domicilio: 'Falta el domicilio.',
            telefono: 'El teléfono es demasiado largo.',
          }),
        ),
      );
      await escribir('razon-social', 'Ana');

      await enviar();

      expect(texto('#numero-documento-error')).toBe('El CUIT no es válido: revisá los números, el último no coincide.');
      expect(texto('#domicilio-error')).toBe('Falta el domicilio.');
      expect(texto('.errores-campos')).toBe('Revisá estos datos:El teléfono es demasiado largo.');
      expect(input('razon-social').value).toBe('Ana');
    });

    it('editar precarga al cliente; guardar llama a actualizar con su id y el límite nuevo va por su endpoint', async () => {
      await crear(modo);
      await abrirEdicion(modo, 1);

      expect(input('razon-social').value).toBe('Ferretería Norte SRL');
      expect(input('numero-documento').value).toBe('30712345671');
      expect(select('condicion-iva').value).toBe('RESPONSABLE_INSCRIPTO');
      expect(input('cuenta-habilitada').checked).toBe(true);
      fixture.componentInstance['form'].controls.limiteCuenta.setValue(8000);
      await escribir('telefono', '2235550000');

      await enviar();

      expect(servicio.actualizar).toHaveBeenCalledExactlyOnceWith(2, {
        razonSocial: 'Ferretería Norte SRL',
        tipoDocumento: 'CUIT',
        numeroDocumento: '30712345671',
        condicionIva: 'RESPONSABLE_INSCRIPTO',
        domicilio: 'Av. Colón 1234',
        telefono: '2235550000',
        email: 'norte@correo.com',
      });
      expect(servicio.cambiarLimite).toHaveBeenCalledExactlyOnceWith(2, 8000);
      expect(texto('.alerta.exito')).toBe('Se guardó «Ferretería Norte SRL».');
    });

    it('si el límite falla, el cliente ya quedó guardado y se avisa con el texto de siempre', async () => {
      await crear(modo);
      await abrirEdicion(modo, 0);
      servicio.cambiarLimite.mockReturnValue(throwError(() => errorHttp(500, 'Falló el servidor.')));
      fixture.componentInstance['form'].controls.limiteCuenta.setValue(9000);
      await refrescar();

      await enviar();

      expect(servicio.actualizar).toHaveBeenCalledTimes(1);
      expect(alertas()).toEqual([
        'Se guardó «Ana Gómez», pero no se pudo cambiar el límite de la cuenta corriente. Falló el servidor.',
      ]);
      expect(servicio.buscar).toHaveBeenCalledTimes(2);
    });

    it('desactivar pide confirmación con el texto de siempre y recién al confirmar llama a cambiarActivo; la fila pasa a Inactivo', async () => {
      await crear(modo, [{ ...ana, saldo: 0 }, norte, viejo]);
      await abrirEdicion(modo, 0);

      await pedirDesactivar(modo);
      expect(servicio.cambiarActivo).not.toHaveBeenCalled();
      expect(texto('app-confirmacion h2')).toBe('¿Desactivar a Ana Gómez?');
      expect(texto('app-confirmacion p')).toBe(
        'No se va a poder elegir en el mostrador; sus ventas y documentos se conservan. Lo podés reactivar destildando «Solo activos».',
      );
      await confirmar();

      expect(servicio.cambiarActivo).toHaveBeenCalledExactlyOnceWith(1, false);
      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(celdas(filas()[0])[3]).toBe('Inactivo');
    });

    it('un cliente inactivo se reactiva sin preguntar', async () => {
      await crear(modo);
      await abrirEdicion(modo, 2);

      await pedirDesactivar(modo);

      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(servicio.cambiarActivo).toHaveBeenCalledExactlyOnceWith(3, true);
    });

    it('con saldo no se desactiva: la acción explica por qué y no abre la confirmación ni llama al servicio', async () => {
      await crear(modo);
      await abrirEdicion(modo, 0);

      await pedirDesactivar(modo);

      expect(texto('#motivo-desactivar, #motivo-desactivar-ficha')).toBe('No se puede desactivar: el cliente tiene saldo en cuenta corriente.');
      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(servicio.cambiarActivo).not.toHaveBeenCalled();
      expect(celdas(filas()[0])[3]).toBe('Activo');
    });

    it('si el servidor falla al desactivar, avisa y la fila no cambia', async () => {
      await crear(modo, [{ ...ana, saldo: 0 }, norte, viejo]);
      servicio.cambiarActivo.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
      await abrirEdicion(modo, 0);

      await pedirDesactivar(modo);
      await confirmar();

      expect(texto('p.error')).toBe('No se pudo cambiar el estado del cliente.');
      expect(celdas(filas()[0])[3]).toBe('Activo');
    });

    it('«Cuenta corriente» lleva a la cuenta del cliente elegido', async () => {
      await crear(modo);
      const navegar = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      await abrirEdicion(modo, 0);

      await pulsar(modo === 'celular' ? el().querySelector('form.alta .cuenta-ficha') : barra('.cuenta'));

      expect(navegar).toHaveBeenCalledExactlyOnceWith(['/clientes', 1, 'cuenta']);
    });
  });

  describe('en el celular', () => {
    it('la barra no tiene botones (sin ellos no se ve) y «Nuevo» es el botón flotante de 44 px o más', async () => {
      await crear('celular');

      expect(el().querySelectorAll('app-barra-acciones button')).toHaveLength(0);
      expect(texto('app-barra-acciones h1')).toBe('Clientes');
      expect(flotante()!.textContent!.trim()).toBe('Nuevo');
    });

    it('la ficha empieza cerrada; «Nuevo» la abre en el alta con foco en la razón social, Agregar y Cancelar en el pie', async () => {
      await crear('celular');
      expect(ficha().classList).not.toContain('ficha-abierta');

      await pulsar(flotante());

      expect(ficha().classList).toContain('ficha-abierta');
      expect(texto('form.alta .ficha-barra h2')).toBe('Nuevo cliente');
      expect(botonesDelPie()).toEqual(['Agregar', 'Cancelar']);
      expect(document.activeElement).toBe(input('razon-social'));
      expect(el().querySelector('form.alta .mas-ficha')).toBeNull();
      expect(el().querySelector('form.alta .cuenta-ficha')).toBeNull();
    });

    it('la ficha tiene sus secciones: Datos del cliente, Contacto y, para el Dueño, Cuenta corriente', async () => {
      await crear('celular');

      expect(Array.from(el().querySelectorAll('form.alta h3.ficha-seccion')).map((h) => limpio(h.textContent))).toEqual([
        'Datos del cliente',
        'Contacto',
      ]);
      expect(texto('form.alta fieldset.cuenta-corriente legend')).toBe('Cuenta corriente');
    });

    it('tocar una fila abre su ficha con el foco en «Volver» (en un campo subiría el teclado)', async () => {
      await crear('celular');

      await abrirEdicion('celular', 1);

      expect(ficha().classList).toContain('ficha-abierta');
      expect(texto('form.alta .ficha-barra h2')).toBe('Editar cliente');
      expect(input('razon-social').value).toBe('Ferretería Norte SRL');
      expect(botonesDelPie()).toEqual(['Guardar cambios', 'Cancelar']);
      expect(document.activeElement).toBe(el().querySelector('form.alta .ficha-volver'));
    });

    it('editando, la ficha lleva el atajo «Cuenta corriente · saldo …» arriba (sólo el Dueño) y el ⋯ con Desactivar', async () => {
      await crear('celular');

      await abrirEdicion('celular', 0);
      expect(texto('form.alta .cuenta-ficha')).toBe('Cuenta corriente · saldo $ 1.200,00');
      expect(el().querySelector('form.alta .mas-ficha')!.getAttribute('aria-label')).toBe('Más opciones del cliente');

      await pulsar(el().querySelector('form.alta .ficha-volver'));
      await abrirEdicion('celular', 1);
      expect(texto('form.alta .cuenta-ficha')).toBe('Cuenta corriente · saldo $ 5.000,00 a favor');
    });

    it('el ⋯ abre la hoja con «Desactivar» en rojo; un inactivo dice «Reactivar» sin rojo', async () => {
      await crear('celular', [{ ...ana, saldo: 0 }, norte, viejo]);
      await abrirEdicion('celular', 0);

      await pulsar(el().querySelector('form.alta .mas-ficha'));
      const accion = document.querySelector<HTMLButtonElement>('app-hoja-inferior .accion-hoja.alternar')!;
      expect(limpio(accion.textContent)).toBe('Desactivar');
      expect(accion.classList).toContain('peligro');
      expect(accion.getAttribute('aria-disabled')).toBeNull();

      await pulsar(document.querySelector('app-hoja-inferior .hoja-inferior-fondo'));
      await pulsar(el().querySelector('form.alta .ficha-volver'));
      await abrirEdicion('celular', 2);
      await pulsar(el().querySelector('form.alta .mas-ficha'));
      const reactivar = document.querySelector<HTMLButtonElement>('app-hoja-inferior .accion-hoja.alternar')!;
      expect(limpio(reactivar.textContent)).toBe('Reactivar');
      expect(reactivar.classList).not.toContain('peligro');
    });

    it('con saldo, «Desactivar» queda apagado con su motivo escrito en la fila de la hoja y la hoja sigue abierta', async () => {
      await crear('celular');
      await abrirEdicion('celular', 0);
      await pulsar(el().querySelector('form.alta .mas-ficha'));

      const accion = document.querySelector<HTMLButtonElement>('app-hoja-inferior .accion-hoja.alternar')!;
      expect(accion.getAttribute('aria-disabled')).toBe('true');
      expect(accion.hasAttribute('disabled')).toBe(false);
      expect(limpio(accion.querySelector('small')?.textContent)).toBe('No se puede desactivar: el cliente tiene saldo en cuenta corriente.');
      expect(accion.getAttribute('aria-describedby')).toBe('motivo-desactivar-ficha');

      await pulsar(accion);
      expect(document.querySelector('app-hoja-inferior')).not.toBeNull();
      expect(servicio.cambiarActivo).not.toHaveBeenCalled();
    });

    it('al confirmar la desactivación la ficha se cierra y la lista muestra al cliente inactivo', async () => {
      await crear('celular', [{ ...ana, saldo: 0 }, norte, viejo]);
      await abrirEdicion('celular', 0);

      await pedirDesactivar('celular');
      expect(document.querySelector('app-hoja-inferior')).toBeNull();
      await confirmar();

      expect(ficha().classList).not.toContain('ficha-abierta');
      expect(celdas(filas()[0])[3]).toBe('Inactivo');
    });

    it('«Volver» descarta lo escrito, cierra la ficha y devuelve el foco al botón flotante, sin pedir nada', async () => {
      await crear('celular');
      await abrirEdicion('celular', 1);
      await escribir('razon-social', 'Otro nombre');

      const volver = el().querySelector<HTMLButtonElement>('form.alta button.ficha-volver')!;
      expect(volver.getAttribute('aria-label')).toBe('Volver');
      await pulsar(volver);

      expect(ficha().classList).not.toContain('ficha-abierta');
      expect(texto('form.alta .ficha-barra h2')).toBe('Nuevo cliente');
      expect(input('razon-social').value).toBe('');
      expect(servicio.actualizar).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(flotante());
    });

    it('«Cancelar» del pie hace lo mismo que «Volver»', async () => {
      await crear('celular');
      await abrirEdicion('celular', 1);

      await pulsar(el().querySelector('form.alta .ficha-pie .cancelar-ficha'));

      expect(ficha().classList).not.toContain('ficha-abierta');
      expect(input('razon-social').value).toBe('');
      expect(document.activeElement).toBe(flotante());
    });

    it('Esc cierra la ficha como «Volver»; con la hoja del ⋯ abierta, ese Esc es de la hoja', async () => {
      await crear('celular', [{ ...ana, saldo: 0 }, norte, viejo]);
      await abrirEdicion('celular', 0);

      await pulsar(el().querySelector('form.alta .mas-ficha'));
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
      await refrescar();
      expect(document.querySelector('app-hoja-inferior')).toBeNull();
      expect(ficha().classList).toContain('ficha-abierta');

      input('razon-social').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
      await refrescar();
      expect(ficha().classList).not.toContain('ficha-abierta');
      expect(servicio.actualizar).not.toHaveBeenCalled();
    });

    it('guardar bien cierra la ficha, la lista muestra el aviso y el foco va al botón flotante (no a un campo)', async () => {
      await crear('celular');
      await pulsar(flotante());
      await escribir('razon-social', 'Juan Pérez');

      await enviar();

      expect(ficha().classList).not.toContain('ficha-abierta');
      expect(texto('.alerta.exito')).toBe('Se guardó «Juan Pérez».');
      expect(document.activeElement).toBe(flotante());
    });

    it('un error al guardar deja la ficha abierta y lo muestra ADENTRO de la ficha (no tapado por ella)', async () => {
      await crear('celular');
      await pulsar(flotante());
      servicio.crear.mockReturnValue(throwError(() => errorHttp(409, 'Ya existe un cliente con ese tipo y número de documento.')));
      await escribir('razon-social', 'Ana');

      await enviar();

      expect(ficha().classList).toContain('ficha-abierta');
      expect(ficha().querySelector('[role="alert"]')!.textContent!.trim()).toBe('Ya existe un cliente con ese tipo y número de documento.');
      expect(el().querySelectorAll('[role="alert"]')).toHaveLength(1);
      expect(input('razon-social').value).toBe('Ana');
    });

    it('un fallo del límite cierra la ficha (el cliente ya quedó guardado) y el error se ve en la página', async () => {
      await crear('celular');
      await abrirEdicion('celular', 0);
      servicio.cambiarLimite.mockReturnValue(throwError(() => errorHttp(500, 'Falló el servidor.')));
      fixture.componentInstance['form'].controls.limiteCuenta.setValue(9000);
      await refrescar();

      await enviar();

      expect(ficha().classList).not.toContain('ficha-abierta');
      expect(ficha().querySelector('[role="alert"]')).toBeNull();
      expect(el().querySelector('.listado')!.parentElement!.querySelector(':scope > [role="alert"]')).not.toBeNull();
    });

    it('un campo inválido al guardar lleva el foco a ese campo (como hoy)', async () => {
      await crear('celular');
      await pulsar(flotante());

      await enviar();

      expect(document.activeElement).toBe(input('razon-social'));
    });

    it('un error de la lista no se oculta: «Reintentar» sigue ahí', async () => {
      await crear('celular');
      servicio.buscar.mockReturnValue(throwError(() => errorHttp(500, 'x')));
      el().querySelector('form.filtros')!.dispatchEvent(new Event('submit'));
      await refrescar();

      expect(texto('[role="alert"]')).toContain('No se pudieron cargar los clientes.');
      expect(filas()).toHaveLength(0);
    });

    it('la cuenta de clientes sigue en la página, para mostrarla arriba de la lista', async () => {
      await crear('celular');

      expect(texto('.barra-estado')).toBe('3 clientes');
    });
  });

  describe('escritorio y tablet: nada del celular en el DOM', () => {
    it('la barra trae sus botones de siempre y no hay botón flotante, «Volver», secciones ni hoja', async () => {
      await crear('tableta');

      expect(Array.from(el().querySelectorAll('app-barra-acciones button')).map((b) => limpio(b.textContent))).toEqual([
        'Nuevo',
        'Editar',
        'Desactivar',
        'Cuenta corriente',
      ]);
      expect(flotante()).toBeNull();
      expect(el().querySelector('.ficha-barra, .ficha-volver, .ficha-seccion, .cancelar-ficha, .mas-ficha, .cuenta-ficha, app-hoja-inferior')).toBeNull();
    });

    it('la ficha es el formulario de al lado de siempre: el título suelto, sin clases de pantalla completa ni pie fijo', async () => {
      await crear('tableta');
      await abrirEdicion('tableta', 1);

      expect(ficha().classList).not.toContain('ficha-abierta');
      expect(ficha().classList).not.toContain('ficha-pantalla');
      expect(Array.from(ficha().children).find((h) => h.tagName === 'H2')!.textContent!.trim()).toBe('Editar cliente');
      expect(ficha().querySelector('.acciones-alta')!.classList).not.toContain('ficha-pie');
      expect(Array.from(ficha().querySelectorAll('.acciones-alta button')).map((b) => limpio(b.textContent))).toEqual([
        'Guardar cambios',
        'Cancelar',
      ]);
    });

    it('tocar una fila sólo la elige; Esc no cierra nada', async () => {
      await crear('tableta');

      (filas()[1].querySelector('td') as HTMLElement).click();
      await refrescar();
      input('razon-social').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
      await refrescar();

      expect(filas()[1].getAttribute('aria-selected')).toBe('true');
      expect(input('razon-social').value).toBe('');
    });
  });
});
