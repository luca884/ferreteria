import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Proveedor } from './proveedor.models';
import { Proveedores } from './proveedores';
import { ProveedoresService } from './proveedores.service';

/**
 * movil-sectores T15a (S1, S2, S5, S7): la lista y la ficha de Proveedores en el celular. El mismo componente, estado,
 * validación y pedidos que en la tablet y el escritorio; sólo cambia cómo se ve (tarjetas, «Nuevo» flotante, ficha a
 * pantalla completa con «Volver» y el pie fijo, ⋯ con Dar de baja o Reactivar). Vitest no evalúa media queries ni lee
 * `styles.css`: acá va el comportamiento y la estructura que el CSS necesita; las medidas se hacen en el navegador.
 */
describe('Proveedores en el celular (T15a)', () => {
  const bulonera: Proveedor = {
    id: 1,
    nombre: 'Bulonera Sur',
    cuit: '30-71234567-1',
    activo: true,
    contacto: 'Ricardo Sosa',
    telefono: '223 455-1020',
    whatsapp: '223 455-1020',
    email: 'ventas@buloneradelsur.com.ar',
    listaIncluyeIva: false,
    bonificacion1: 10,
    bonificacion2: 5,
    mapeoLista: { columnaCodigo: 0, columnaDescripcion: 1, columnaCosto: 3, filaDesde: 2 } as Proveedor['mapeoLista'],
  };
  const electro: Proveedor = { id: 2, nombre: 'Electro Norte', cuit: null, activo: true, contacto: 'Lucía Peña', telefono: '223 400-1188' };
  const sinte: Proveedor = { id: 9, nombre: 'Sinteplast', cuit: '30-50012345-6', activo: false, contacto: 'Pablo Díaz', telefono: '11 4555-2200' };

  const errorHttp = (status: number, message: string) => new HttpErrorResponse({ status, error: { status, message } });

  let fixture: ComponentFixture<Proveedores>;
  let servicio: {
    listar: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    actualizar: ReturnType<typeof vi.fn>;
    desactivar: ReturnType<typeof vi.fn>;
    reactivar: ReturnType<typeof vi.fn>;
  };

  type Modo = 'celular' | 'tableta' | 'escritorio';
  const simular = (modo: Modo) =>
    vi.stubGlobal('matchMedia', (consulta: string) => ({
      matches: modo !== 'escritorio' && consulta.includes(modo === 'celular' ? '639.98px' : '1023.98px'),
      media: consulta,
    }));

  async function crear(modo: Modo, dueno = true, lista: Proveedor[] = [bulonera, electro]) {
    simular(modo);
    servicio = {
      listar: vi.fn((incluirInactivos = false) => of(incluirInactivos ? [...lista, sinte] : lista)),
      crear: vi.fn((r) => of({ id: 99, ...r })),
      actualizar: vi.fn((id: number, r) => of({ id, ...r })),
      desactivar: vi.fn(() => of(undefined)),
      reactivar: vi.fn((id: number) => of({ ...sinte, id, activo: true })),
    };
    await TestBed.configureTestingModule({
      imports: [Proveedores],
      providers: [
        provideRouter([]),
        { provide: ProveedoresService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Proveedores);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  let contenido: HTMLElement | undefined;
  afterEach(() => {
    contenido?.remove();
    contenido = undefined;
    vi.unstubAllGlobals();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  function montarEnContenido(): HTMLElement {
    contenido = document.createElement('main');
    contenido.id = 'contenido';
    contenido.className = 'contenido';
    contenido.tabIndex = -1;
    el().before(contenido);
    contenido.append(el());
    return contenido;
  }
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || undefined;
  const filas = () => Array.from(el().querySelectorAll<HTMLTableRowElement>('tbody tr'));
  const celdas = (fila: HTMLTableRowElement) => Array.from(fila.querySelectorAll('td')).map((td) => limpio(td.textContent));
  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
  const barra = (selector: string) => el().querySelector<HTMLButtonElement>(`app-barra-acciones ${selector}`);
  const ficha = () => el().querySelector<HTMLFormElement>('form.alta')!;
  const flotante = () => el().querySelector<HTMLButtonElement>('button.boton-flotante');
  const botonesDelPie = () => Array.from(el().querySelectorAll('form.alta .ficha-pie button')).map((b) => limpio(b.textContent));
  const alertas = () => Array.from(el().querySelectorAll('[role="alert"]')).map((a) => limpio(a.textContent));

  async function escribir(id: string, valor: string) {
    input(id).value = valor;
    input(id).dispatchEvent(new Event('input'));
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
  // Sólo los tests de paridad aceptan los controles anteriores al correr contra la base.
  async function abrirAlta(modo: Modo, compararBase = false) {
    const anterior = barra('.nuevo');
    await pulsar(modo !== 'celular' || (compararBase && anterior) ? anterior : flotante());
  }
  async function abrirEdicion(modo: Modo, fila: number, compararBase = false) {
    (filas()[fila].querySelector('td') as HTMLElement).click();
    await refrescar();
    if (modo !== 'celular' || (compararBase && barra('.editar'))) await pulsar(barra('.editar'));
  }
  async function pedirBaja(modo: Modo, compararBase = false) {
    if (modo !== 'celular' || (compararBase && barra('.desactivar'))) return pulsar(barra('.desactivar'));
    await pulsar(el().querySelector('form.alta button.mas-ficha'));
    await pulsar(document.querySelector('app-hoja-inferior .accion-hoja.alternar'));
  }

  const MODOS: ('celular' | 'tableta')[] = ['celular', 'tableta'];

  describe.each(MODOS)('lo que no cambia con el celular: pedidos, textos y reglas (%s)', (modo) => {
    it('al abrir pide la lista sin dados de baja y cada fila trae los mismos datos', async () => {
      await crear(modo);

      expect(servicio.listar).toHaveBeenCalledExactlyOnceWith(false);
      expect(filas().map(celdas)).toEqual([
        ['Bulonera Sur', '30-71234567-1', 'Ricardo Sosa', '223 455-1020', '223 455-1020', 'ventas@buloneradelsur.com.ar'],
        ['Electro Norte', '—', 'Lucía Peña', '223 400-1188', '—', '—'],
      ]);
      expect(texto('.barra-estado')).toBe('2 proveedores');
    });

    it('«Mostrar dados de baja» vuelve a pedir la lista con los dados de baja y los marca «De baja»', async () => {
      await crear(modo);

      input('inactivos').click();
      await refrescar();

      expect(servicio.listar).toHaveBeenLastCalledWith(true);
      expect(texto('.barra-estado')).toBe('2 proveedores · 1 de baja');
      expect(texto('tr.inactivo .etiqueta.de-baja')).toBe('De baja');
    });

    it('buscar filtra por nombre o contacto y no pide nada al servidor; sin coincidencias dice «Ningún proveedor coincide…»', async () => {
      await crear(modo);

      await escribir('buscar-proveedor', 'ricardo');
      expect(filas().map((f) => celdas(f)[0])).toEqual(['Bulonera Sur']);
      expect(texto('.barra-estado')).toBe('1 de 2 proveedores');

      await escribir('buscar-proveedor', 'zzz');
      expect(texto('.estado-vacio')).toBe('Ningún proveedor coincide con «zzz».');
      expect(servicio.listar).toHaveBeenCalledTimes(1);
    });

    it('si la lista no carga, avisa con Reintentar y Reintentar vuelve a pedirla', async () => {
      simular(modo);
      let intento = 0;
      servicio = {
        listar: vi.fn(() => (intento++ === 0 ? throwError(() => errorHttp(500, 'x')) : of([bulonera]))),
        crear: vi.fn(),
        actualizar: vi.fn(),
        desactivar: vi.fn(),
        reactivar: vi.fn(),
      };
      await TestBed.configureTestingModule({
        imports: [Proveedores],
        providers: [
          provideRouter([]),
          { provide: ProveedoresService, useValue: servicio },
          { provide: AuthService, useValue: { esDueno: signal(true) } },
        ],
      }).compileComponents();
      fixture = TestBed.createComponent(Proveedores);
      await refrescar();

      expect(alertas()).toEqual(['No se pudieron cargar los proveedores.Reintentar']);
      await pulsar(el().querySelector('button.reintentar'));

      expect(servicio.listar).toHaveBeenCalledTimes(2);
      expect(filas()).toHaveLength(1);
    });

    it('el alta manda sólo lo cargado, sin espacios de más y con el CUIT con guiones, y después avisa en la lista', async () => {
      await crear(modo);
      await abrirAlta(modo, true);
      await escribir('nombre', '  Tersuave  ');
      await escribir('cuit', '30123456781');
      await escribir('telefono', ' 223 555 ');

      await enviar();

      expect(servicio.crear).toHaveBeenCalledExactlyOnceWith({
        nombre: 'Tersuave',
        cuit: '30-12345678-1',
        activo: true,
        contacto: null,
        telefono: '223 555',
        whatsapp: null,
        email: null,
      });
      expect(filas().map((f) => celdas(f)[0])).toEqual(['Bulonera Sur', 'Electro Norte', 'Tersuave']);
      expect(input('nombre').value).toBe('');
    });

    it('sin nombre, o con un correo o un CUIT inválidos, avisa con los mismos textos y no llama al servicio', async () => {
      await crear(modo);
      await abrirAlta(modo, true);

      await enviar();
      expect(texto('#nombre-error')).toBe('El nombre es obligatorio.');

      await escribir('nombre', 'Tersuave');
      await escribir('email', 'sin-arroba');
      await enviar();
      expect(texto('#email-error')).toBe('El correo no es válido.');
      await escribir('email', 'ventas@tersuave.com');
      await escribir('cuit', '30123456789');
      await enviar();
      expect(texto('#cuit-error')).toBe('El CUIT no es válido: revisá los números, el último no coincide.');
      expect(servicio.crear).not.toHaveBeenCalled();
    });

    it('editar precarga al proveedor; guardar llama a actualizar con su id', async () => {
      await crear(modo);
      await abrirEdicion(modo, 0, true);

      expect(input('nombre').value).toBe('Bulonera Sur');
      expect(input('cuit').value).toBe('30-71234567-1');
      expect(input('contacto').value).toBe('Ricardo Sosa');
      expect(input('email').value).toBe('ventas@buloneradelsur.com.ar');
      await escribir('telefono', '223 999-0000');

      await enviar();

      expect(servicio.actualizar).toHaveBeenCalledExactlyOnceWith(1, {
        nombre: 'Bulonera Sur',
        cuit: '30-71234567-1',
        activo: true,
        contacto: 'Ricardo Sosa',
        telefono: '223 999-0000',
        whatsapp: '223 455-1020',
        email: 'ventas@buloneradelsur.com.ar',
      });
    });

    it('un guardado rechazado muestra el mensaje del servidor (una sola alerta) y conserva lo escrito', async () => {
      await crear(modo);
      await abrirAlta(modo, true);
      servicio.crear.mockReturnValue(throwError(() => errorHttp(500, 'Falló el guardado.')));
      await escribir('nombre', 'Tersuave');

      await enviar();

      expect(alertas()).toEqual(['Falló el guardado.']);
      expect(input('nombre').value).toBe('Tersuave');
      expect(filas()).toHaveLength(2);
    });

    it('la ficha de un proveedor trae su lista de precios de sólo lectura, con el enlace a Importar lista', async () => {
      await crear(modo);
      await abrirEdicion(modo, 0, true);

      expect(texto('.lista-proveedor h3')).toBe('Lista de precios');
      expect(Array.from(el().querySelectorAll('.lista-proveedor dt')).map((d) => limpio(d.textContent))).toEqual([
        'La lista incluye IVA',
        'Bonificaciones',
        'Datos desde la fila',
        'Columnas',
      ]);
      expect(texto('.lista-proveedor dd:nth-of-type(2)')).toBe('10 % + 5 % (en cascada)');
      expect(el().querySelector('.lista-proveedor a')?.getAttribute('href')).toBe('/importaciones');
    });

    it('un proveedor sin lista importada lo dice, y el alta no muestra la sección', async () => {
      await crear(modo);
      await abrirEdicion(modo, 1, true);
      expect(texto('.sin-lista')).toBe('Todavía no se importó ninguna lista de este proveedor.');

      if (modo === 'celular' && flotante()) await pulsar(el().querySelector('.ficha-volver'));
      await abrirAlta(modo, true);
      expect(el().querySelector('.lista-proveedor')).toBeNull();
    });

    it('dar de baja pide confirmar con la frase de hoy y recién ahí llama al servicio; cancelar no hace nada', async () => {
      await crear(modo);
      await abrirEdicion(modo, 0, true);

      await pedirBaja(modo, true);
      expect(texto('app-confirmacion h2, app-confirmacion h3, app-confirmacion [role="alertdialog"] > :first-child')).toBeDefined();
      expect(texto('app-confirmacion')).toContain('¿Dar de baja a Bulonera Sur?');
      expect(texto('app-confirmacion')).toContain('Sus artículos conservan la referencia. Lo podés reactivar desde «Mostrar dados de baja».');
      expect(servicio.desactivar).not.toHaveBeenCalled();

      await pulsar(el().querySelector('app-confirmacion button.cancelar'));
      expect(servicio.desactivar).not.toHaveBeenCalled();
      expect(filas()).toHaveLength(2);

      await pedirBaja(modo, true);
      await confirmar();
      expect(servicio.desactivar).toHaveBeenCalledExactlyOnceWith(1);
      expect(filas().map((f) => celdas(f)[0])).toEqual(['Electro Norte']);
    });

    it('un error al dar de baja lo muestra con su texto y deja la lista como estaba', async () => {
      await crear(modo);
      servicio.desactivar.mockReturnValue(throwError(() => errorHttp(500, 'No hay conexión.')));
      await abrirEdicion(modo, 0, true);

      await pedirBaja(modo, true);
      await confirmar();

      expect(alertas()).toEqual(['No hay conexión.']);
      expect(filas()).toHaveLength(2);
    });

    it('reactivar a uno dado de baja llama al servicio con su id y avisa «Se reactivó «…».»', async () => {
      await crear(modo);
      input('inactivos').click();
      await refrescar();
      await abrirEdicion(modo, 2, true);

      if (modo === 'celular' && !barra('button.reactivar')) {
        await pulsar(el().querySelector('form.alta button.mas-ficha'));
        await pulsar(document.querySelector('app-hoja-inferior .accion-hoja.alternar'));
      } else {
        await pulsar(barra('button.reactivar'));
      }

      expect(servicio.reactivar).toHaveBeenCalledExactlyOnceWith(9);
      expect(texto('.alerta.exito')).toBe('Se reactivó «Sinteplast».');
    });

    it('si el alta choca con uno dado de baja, ofrece reactivarlo desde el aviso y lo reactiva', async () => {
      await crear(modo);
      servicio.crear.mockReturnValue(
        throwError(() => errorHttp(409, 'Ya existe un proveedor llamado Sinteplast, pero está dado de baja. Reactivalo para volver a usarlo.')),
      );
      await abrirAlta(modo, true);
      await escribir('nombre', 'sinteplast');

      await enviar();

      expect(alertas()).toHaveLength(1);
      expect(texto('button.reactivar-existente')).toBe('Reactivar «Sinteplast»');
      expect(servicio.listar).toHaveBeenLastCalledWith(true);

      await pulsar(el().querySelector('button.reactivar-existente'));

      expect(servicio.reactivar).toHaveBeenCalledExactlyOnceWith(9);
      expect(texto('.alerta.exito')).toBe('Se reactivó «Sinteplast».');
      expect(input('nombre').value).toBe('');
    });
  });

  describe.each<Modo>(['celular', 'tableta', 'escritorio'])('foco y scroll de la pantalla (%s)', (modo) => {
    it('tras el 409 del alta, sólo en celular recupera el foco que se perdió mientras guardaba', async () => {
      await crear(modo);
      const respuesta = new Subject<Proveedor>();
      servicio.crear.mockReturnValue(respuesta);
      await abrirAlta(modo);
      await escribir('nombre', 'sinteplast');
      input('nombre').focus();

      await enviar();
      expect(ficha().hasAttribute('inert')).toBe(true);
      // El DOM de tests no mueve el foco al body por inert como lo hace el navegador.
      input('nombre').blur();
      expect(document.activeElement).toBe(document.body);

      respuesta.error(errorHttp(409, 'Ya existe un proveedor llamado Sinteplast, pero está dado de baja.'));
      await refrescar();

      expect(ficha().hasAttribute('inert')).toBe(false);
      if (modo === 'celular') expect(ficha().classList.contains('ficha-abierta')).toBe(true);
      expect(input('nombre').value).toBe('sinteplast');
      expect(texto('button.reactivar-existente')).toBe('Reactivar «Sinteplast»');
      expect(document.activeElement).toBe(modo === 'celular' ? input('nombre') : document.body);
    });

    it.each(['Escape', 'Cancelar'])('al salir de la confirmación con %s devuelve el foco a la acción que la originó', async (salida) => {
      await crear(modo);
      montarEnContenido();
      await abrirEdicion(modo, 0);
      const origen = modo === 'celular' ? ficha().querySelector<HTMLButtonElement>('.mas-ficha')! : barra('.desactivar')!;
      origen.focus();
      if (modo === 'celular') {
        await pulsar(origen);
        const alternar = el().querySelector<HTMLButtonElement>('app-hoja-inferior .accion-hoja.alternar')!;
        alternar.focus();
        await pulsar(alternar);
      } else {
        await pulsar(origen);
      }
      const cancelar = el().querySelector<HTMLButtonElement>('app-confirmacion button.cancelar')!;
      expect(document.activeElement).toBe(cancelar);

      if (salida === 'Escape') {
        cancelar.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
        await refrescar();
      } else {
        await pulsar(cancelar);
      }

      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(servicio.desactivar).not.toHaveBeenCalled();
      if (modo === 'celular') expect(ficha().classList.contains('ficha-abierta')).toBe(true);
      expect(document.activeElement).toBe(origen);
    });

    it('al pintar el aviso de reactivación vuelve al tope de main.contenido sólo en celular', async () => {
      await crear(modo);
      const main = montarEnContenido();
      input('inactivos').click();
      await refrescar();
      await abrirEdicion(modo, 2);
      const respuesta = new Subject<Proveedor>();
      servicio.reactivar.mockReturnValue(respuesta);
      if (modo === 'celular') {
        await pulsar(ficha().querySelector('.mas-ficha'));
        await pulsar(el().querySelector('app-hoja-inferior .accion-hoja.alternar'));
      } else {
        await pulsar(barra('.reactivar'));
      }
      main.scrollTop = 320;
      expect(texto('.alerta.exito')).toBeUndefined();

      respuesta.next({ ...sinte, activo: true });
      respuesta.complete();
      await refrescar();

      expect(texto('.alerta.exito')).toBe('Se reactivó «Sinteplast».');
      expect(main.scrollTop).toBe(modo === 'celular' ? 0 : 320);
    });
  });

  describe('la lista y la ficha, sólo en el celular', () => {
    it('Nuevo es un botón flotante, y la barra de acciones queda sin Nuevo, Editar ni Dar de baja', async () => {
      await crear('celular');

      expect(flotante()?.textContent?.trim()).toBe('Nuevo');
      expect(barra('.nuevo')).toBeNull();
      expect(barra('.editar')).toBeNull();
      expect(barra(':is(.desactivar, .reactivar, .separador-barra)')).toBeNull();
      // El buscador y «Mostrar dados de baja» siguen en la barra: es lo que se ve arriba de la lista.
      expect(barra('input[type="search"]')).not.toBeNull();
      expect(barra('label.inactivos')).not.toBeNull();
    });

    it('en la tablet no hay botón flotante ni ficha a pantalla completa: la barra y el panel de siempre', async () => {
      await crear('tableta');

      expect(flotante()).toBeNull();
      expect(barra('.nuevo')).not.toBeNull();
      expect(ficha().classList.contains('ficha-pantalla')).toBe(false);
      expect(el().querySelector('.ficha-barra, .mas-ficha, .ficha-pie')).toBeNull();
    });

    it('el Vendedor no ve el botón flotante ni la ficha, y tocar una tarjeta no abre nada', async () => {
      await crear('celular', false);

      expect(flotante()).toBeNull();
      expect(el().querySelector('form.alta')).toBeNull();
      (filas()[0].querySelector('td') as HTMLElement).click();
      await refrescar();
      expect(el().querySelector('.ficha-abierta')).toBeNull();
      expect(filas().map((f) => celdas(f)[0])).toEqual(['Bulonera Sur', 'Electro Norte']);
    });

    it('tocar una tarjeta abre la ficha del proveedor con «Volver», su título, el ⋯ y los datos cargados', async () => {
      await crear('celular');
      expect(ficha().classList.contains('ficha-pantalla')).toBe(true);
      expect(ficha().classList.contains('ficha-abierta')).toBe(false);

      (filas()[0].querySelector('td') as HTMLElement).click();
      await refrescar();

      expect(ficha().classList.contains('ficha-abierta')).toBe(true);
      const volver = ficha().querySelector('.ficha-barra .ficha-volver');
      expect(volver?.getAttribute('aria-label')).toBe('Volver');
      expect(document.activeElement).toBe(volver);
      expect(texto('form.alta .ficha-barra h2')).toBe('Editar proveedor');
      expect(el().querySelector('form.alta .mas-ficha')?.getAttribute('aria-label')).toBe('Más opciones del proveedor');
      expect(Array.from(el().querySelectorAll('form.alta .ficha-seccion')).map((s) => limpio(s.textContent))).toEqual([
        'Datos del proveedor',
      ]);
      expect(input('nombre').value).toBe('Bulonera Sur');
    });

    it('«Nuevo» abre la ficha vacía sin ⋯, con «Nuevo proveedor» y el pie «Agregar | Cancelar»', async () => {
      await crear('celular');

      await pulsar(flotante());

      expect(ficha().classList.contains('ficha-abierta')).toBe(true);
      expect(texto('form.alta .ficha-barra h2')).toBe('Nuevo proveedor');
      expect(el().querySelector('.mas-ficha')).toBeNull();
      expect(botonesDelPie()).toEqual(['Agregar', 'Cancelar']);
      expect(input('nombre').value).toBe('');
      expect(document.activeElement).toBe(input('nombre'));
    });

    it('editando, el pie fijo es «Guardar cambios | Cancelar»', async () => {
      await crear('celular');
      await abrirEdicion('celular', 0);

      expect(botonesDelPie()).toEqual(['Guardar cambios', 'Cancelar']);
    });

    it('«Volver» y «Cancelar» descartan lo escrito, cierran la ficha y devuelven el foco a «Nuevo»; no se pide nada', async () => {
      await crear('celular');
      await abrirEdicion('celular', 0);
      await escribir('contacto', 'Otra persona');

      await pulsar(el().querySelector('form.alta .ficha-volver'));
      expect(ficha().classList.contains('ficha-abierta')).toBe(false);
      expect(input('contacto').value).toBe('');
      expect(document.activeElement).toBe(flotante());

      await abrirEdicion('celular', 1);
      await pulsar(el().querySelector('form.alta .cancelar-ficha'));
      expect(ficha().classList.contains('ficha-abierta')).toBe(false);
      expect(servicio.actualizar).not.toHaveBeenCalled();
      expect(servicio.crear).not.toHaveBeenCalled();
    });

    it('Esc en la ficha es «Volver»', async () => {
      await crear('celular');
      await abrirEdicion('celular', 0);

      expect(ficha().classList.contains('ficha-abierta')).toBe(true);
      ficha().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
      await refrescar();

      expect(ficha().classList.contains('ficha-abierta')).toBe(false);
      expect(document.activeElement).toBe(flotante());
    });

    it('guardar cierra la ficha, avisa en la lista y el foco va a «Nuevo», no a un campo', async () => {
      await crear('celular');
      await pulsar(flotante());
      await escribir('nombre', 'Tersuave');

      await enviar();

      expect(ficha().classList.contains('ficha-abierta')).toBe(false);
      expect(document.activeElement).toBe(flotante());
      expect(filas()).toHaveLength(3);
    });

    it('un guardado rechazado deja la ficha abierta y el error adentro, no en la página', async () => {
      await crear('celular');
      servicio.crear.mockReturnValue(throwError(() => errorHttp(500, 'Falló el guardado.')));
      await pulsar(flotante());
      await escribir('nombre', 'Tersuave');

      await enviar();

      expect(ficha().classList.contains('ficha-abierta')).toBe(true);
      expect(ficha().querySelector('[role="alert"]')?.textContent?.trim()).toBe('Falló el guardado.');
      expect(el().querySelectorAll('[role="alert"]')).toHaveLength(1);
    });

    it('con la ficha cerrada, el error de una baja se ve en la página', async () => {
      await crear('celular');
      servicio.desactivar.mockReturnValue(throwError(() => errorHttp(500, 'No hay conexión.')));
      await abrirEdicion('celular', 0);

      await pedirBaja('celular');
      await confirmar();

      expect(ficha().classList.contains('ficha-abierta')).toBe(false);
      expect(el().querySelector('form.alta [role="alert"]')).toBeNull();
      expect(alertas()).toEqual(['No hay conexión.']);
    });

    it('el ⋯ abre una hoja con «Dar de baja» (rojo) si está activo y «Reactivar» si está de baja', async () => {
      await crear('celular');
      await abrirEdicion('celular', 0);

      await pulsar(el().querySelector('form.alta .mas-ficha'));
      const fila = document.querySelector('app-hoja-inferior .accion-hoja.alternar')!;
      expect(limpio(fila.textContent)).toBe('Dar de baja');
      expect(fila.classList.contains('peligro')).toBe(true);
      expect(limpio(document.querySelector('app-hoja-inferior')?.textContent)).toContain('Bulonera Sur');
      await pulsar(el().querySelector('app-hoja-inferior .hoja-inferior-fondo'));
      expect(el().querySelector('app-hoja-inferior')).toBeNull();
      await pulsar(el().querySelector('form.alta .ficha-volver'));
      input('inactivos').click();
      await refrescar();
      await abrirEdicion('celular', 2);
      await pulsar(el().querySelector('form.alta .mas-ficha'));
      const reactivar = document.querySelector('app-hoja-inferior .accion-hoja.alternar')!;
      expect(limpio(reactivar.textContent)).toBe('Reactivar');
      expect(reactivar.classList.contains('peligro')).toBe(false);
      await pulsar(reactivar as HTMLElement);
      expect(ficha().classList.contains('ficha-abierta')).toBe(false);
      expect(document.activeElement).toBe(flotante());
    });

    it('dar de baja desde el ⋯ cierra la hoja, pide confirmar y al confirmar cierra la ficha', async () => {
      await crear('celular');
      await abrirEdicion('celular', 0);

      await pedirBaja('celular');

      expect(document.querySelector('app-hoja-inferior')).toBeNull();
      expect(ficha().classList.contains('ficha-abierta')).toBe(true);
      await confirmar();
      expect(ficha().classList.contains('ficha-abierta')).toBe(false);
      expect(document.activeElement).toBe(flotante());
      expect(servicio.desactivar).toHaveBeenCalledExactlyOnceWith(1);
    });

    it('el aviso «ya existe uno dado de baja» y su botón viven dentro de la ficha abierta', async () => {
      await crear('celular');
      servicio.crear.mockReturnValue(throwError(() => errorHttp(409, 'Ya existe un proveedor llamado Sinteplast, pero está dado de baja.')));
      await pulsar(flotante());
      await escribir('nombre', 'sinteplast');

      await enviar();

      expect(ficha().querySelector('[role="alert"] button.reactivar-existente')?.textContent?.trim()).toBe('Reactivar «Sinteplast»');
      await pulsar(el().querySelector('button.reactivar-existente'));
      expect(ficha().classList.contains('ficha-abierta')).toBe(false);
      expect(texto('.alerta.exito')).toBe('Se reactivó «Sinteplast».');
    });

    it('la tarjeta conserva las seis celdas del DOM, con su etiqueta, para que el CSS elija cuáles se ven', async () => {
      await crear('celular');

      const etiquetas = Array.from(filas()[0].querySelectorAll('td')).map((td) => td.getAttribute('data-etiqueta'));
      expect(etiquetas).toEqual(['Nombre', 'CUIT', 'Contacto', 'Teléfono', 'WhatsApp', 'Correo']);
    });
  });
});
