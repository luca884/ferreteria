import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Proveedor } from './proveedor.models';
import { Proveedores } from './proveedores';
import { ProveedoresService } from './proveedores.service';

describe('Proveedores', () => {
  const sinteplast: Proveedor = { id: 1, nombre: 'Sinteplast', cuit: null, activo: true };
  const tersuave: Proveedor = { id: 2, nombre: 'Tersuave', cuit: '30-12345678-9', activo: true };
  const viejo: Proveedor = { id: 9, nombre: 'Pinturas Viejas', cuit: null, activo: false };

  let fixture: ComponentFixture<Proveedores>;
  let esDueno: ReturnType<typeof signal<boolean>>;
  let servicio: {
    listar: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    actualizar: ReturnType<typeof vi.fn>;
    desactivar: ReturnType<typeof vi.fn>;
    reactivar: ReturnType<typeof vi.fn>;
  };

  const errorHttp = (status: number, message: string) =>
    new HttpErrorResponse({ status, error: { status, message } });

  async function crearComponente(dueno: boolean, lista: Proveedor[] = [sinteplast, tersuave], url?: string) {
    esDueno = signal(dueno);
    servicio = {
      listar: vi.fn((incluirInactivos = false) => of(incluirInactivos ? [...lista, viejo] : lista)),
      crear: vi.fn(),
      actualizar: vi.fn(),
      desactivar: vi.fn(),
      reactivar: vi.fn((id: number) => of({ ...viejo, id, activo: true })),
    };
    await TestBed.configureTestingModule({
      imports: [Proveedores],
      providers: [
        provideRouter([]),
        { provide: ProveedoresService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno } },
      ],
    }).compileComponents();
    if (url) await TestBed.inject(Router).navigateByUrl(url);
    fixture = TestBed.createComponent(Proveedores);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const filas = () => Array.from(el().querySelectorAll<HTMLTableRowElement>('tbody tr'));
  const textoFilas = () => filas().map((f) => f.textContent ?? '');
  const barra = (selector: string) => el().querySelector<HTMLButtonElement>(`app-barra-acciones ${selector}`);
  const elegidas = () => filas().filter((f) => f.getAttribute('aria-selected') === 'true');
  const indiceDe = (nombre: string) => filas().findIndex((f) => f.textContent?.includes(nombre));

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** Clic en la fila: la elige. */
  async function elegir(fila: number) {
    (filas()[fila].querySelector('td') as HTMLElement).click();
    await refrescar();
  }

  /** Elige la fila y toca el botón de la barra. */
  async function enBarra(fila: number, boton: string) {
    await elegir(fila);
    barra(`button.${boton}`)!.click();
    await refrescar();
  }

  async function teclaEnFila(fila: number, key: string) {
    filas()[fila].dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    await refrescar();
  }

  /** Los textos a los que apunta el aria-describedby del campo. */
  const descripciones = (selector: string) =>
    (el().querySelector(selector)!.getAttribute('aria-describedby') ?? '')
      .split(' ')
      .filter((ref) => ref !== '')
      .map((ref) => el().querySelector(`#${ref}`)?.textContent?.trim());

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

  it('el título va en la barra de acciones de arriba', async () => {
    await crearComponente(false);

    expect(el().querySelector('app-barra-acciones h1')?.textContent?.trim()).toBe('Proveedores');
  });

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

  // Bugs de la guía, S6: a 1280 px el CUIT se partía en sus guiones y el teléfono en su espacio (hasta 3 renglones).
  it('el CUIT, el teléfono y el WhatsApp no se parten en renglones', async () => {
    await crearComponente(true, [{ ...tersuave, telefono: '223 555-1234', whatsapp: '2235551234' }]);

    const celdas = filas()[0].querySelectorAll<HTMLElement>('td');
    const cuit = filas()[0].querySelector<HTMLElement>('td.cuit')!;
    const telefono = filas()[0].querySelector<HTMLElement>('td.telefono')!;
    const whatsapp = filas()[0].querySelector<HTMLElement>('td.whatsapp')!;

    expect(celdas).toHaveLength(6);
    expect(cuit.textContent!.trim()).toBe('30-12345678-9');
    expect(telefono.textContent!.trim()).toBe('223 555-1234');
    expect(whatsapp.textContent!.trim()).toBe('2235551234');
    for (const celda of [cuit, telefono, whatsapp]) {
      expect(getComputedStyle(celda).whiteSpace).toBe('nowrap');
    }
  });

  // Guía al día, tanda A: el `nowrap` de la tabla agarraba también el campo CUIT del alta, y el error del CUIT no se partía.
  it('el campo CUIT del alta sí se parte en renglones: el nowrap es solo de las celdas', async () => {
    await crearComponente(true, [tersuave]);

    const campo = el().querySelector<HTMLElement>('div.campo.cuit')!;

    expect(campo).toBeTruthy();
    expect(getComputedStyle(campo).whiteSpace).not.toBe('nowrap');
  });

  it('el correo sí se puede partir: con el CUIT y el teléfono enteros, sin esto la tabla se corría de costado a 1280 px', async () => {
    await crearComponente(true, [{ ...tersuave, email: 'ventas@distribuidora-del-sur.com.ar' }]);

    const correo = filas()[0].querySelector<HTMLElement>('td.correo')!;

    expect(correo.textContent!.trim()).toBe('ventas@distribuidora-del-sur.com.ar');
    expect(getComputedStyle(correo).overflowWrap).toBe('anywhere');
  });

  describe('buscar proveedor (mejoras-ferreteros-bloque-2, S3)', () => {
    const buscador = () => el().querySelector<HTMLInputElement>('input[type="search"]#buscar-proveedor')!;

    async function escribir(texto: string) {
      buscador().value = texto;
      buscador().dispatchEvent(new Event('input'));
      await refrescar();
    }

    it('escribir filtra la lista por nombre, sin distinguir mayúsculas ni tildes', async () => {
      await crearComponente(true);

      await escribir('TERS');

      expect(textoFilas()).toHaveLength(1);
      expect(textoFilas()[0]).toContain('Tersuave');
    });

    it('también encuentra por contacto', async () => {
      await crearComponente(true, [sinteplast, { ...tersuave, contacto: 'Ana Núñez' }]);

      await escribir('nunez');

      expect(textoFilas()).toHaveLength(1);
      expect(textoFilas()[0]).toContain('Tersuave');
    });

    it('con ?q= en la dirección abre filtrada y con el texto en el buscador (lo usa el buscador del menú)', async () => {
      await crearComponente(false, [sinteplast, tersuave], '/?q=Sinteplast');
      await refrescar();

      expect(buscador().value).toBe('Sinteplast');
      expect(textoFilas()).toHaveLength(1);
      expect(textoFilas()[0]).toContain('Sinteplast');
    });

    it('si nada coincide lo dice, y borrar el texto vuelve a mostrar todos', async () => {
      await crearComponente(true);

      await escribir('zzz');
      expect(filas()).toHaveLength(0);
      expect(el().textContent).toContain('Ningún proveedor coincide con «zzz».');

      await escribir('');
      expect(filas()).toHaveLength(2);
    });

    it('la barra de estado dice cuántos se ven de cuántos', async () => {
      await crearComponente(true);

      await escribir('sin');

      expect(el().querySelector('.barra-estado')?.textContent?.replace(/\s+/g, ' ')).toContain('1 de 2 proveedores');
    });
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
      reactivar: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [Proveedores],
      providers: [
        provideRouter([]),
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
    expect(el().textContent).not.toContain('Todavía no hay proveedores.');
  });

  it('Reintentar vuelve a pedir la lista después de una carga fallida', async () => {
    await crearComponente(true);
    servicio.listar.mockReturnValueOnce(throwError(() => errorHttp(500, 'Falló')));
    (el().querySelector('#inactivos') as HTMLInputElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    // La lista anterior no queda a la vista: no corresponde a la casilla marcada.
    expect(filas()).toHaveLength(0);
    expect(el().textContent).not.toContain('Todavía no hay proveedores.');

    (el().querySelector('button.reintentar') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(servicio.listar).toHaveBeenLastCalledWith(true);
    expect(el().textContent).not.toContain('No se pudieron cargar los proveedores.');
    expect(filas()).toHaveLength(3);
  });

  describe('permisos', () => {
    it('el Dueño ve el formulario y las acciones en la barra, ninguna en las filas', async () => {
      await crearComponente(true);

      expect(el().querySelector('form')).not.toBeNull();
      expect(barra('button.nuevo')).not.toBeNull();
      expect(barra('button.editar')).not.toBeNull();
      expect(barra('button.desactivar')).not.toBeNull();
      expect(el().querySelectorAll('tbody button')).toHaveLength(0);
    });

    it('el Vendedor solo ve la lista, sin formulario ni acciones, y no elige filas', async () => {
      await crearComponente(false);

      expect(textoFilas()).toHaveLength(2);
      expect(el().querySelector('form')).toBeNull();
      expect(el().querySelectorAll('button.editar')).toHaveLength(0);
      expect(el().querySelectorAll('button.desactivar')).toHaveLength(0);
      await elegir(0);
      expect(filas()[0].getAttribute('aria-selected')).toBeNull();
      expect(filas()[0].getAttribute('tabindex')).toBeNull();
    });
  });

  describe('barra de acciones sobre la fila elegida', () => {
    it('sin fila elegida, Editar y Dar de baja están deshabilitados', async () => {
      await crearComponente(true);

      expect(barra('button.editar')!.disabled).toBe(true);
      expect(barra('button.desactivar')!.disabled).toBe(true);
    });

    it('clic en una fila la elige, la marca y habilita las acciones', async () => {
      await crearComponente(true);

      await elegir(1);

      expect(elegidas()).toEqual([filas()[1]]);
      expect(barra('button.editar')!.disabled).toBe(false);
      expect(barra('button.desactivar')!.disabled).toBe(false);
      expect(el().querySelector('.elegido')?.textContent).toContain('Tersuave');
    });

    it('Enter o doble clic en la fila la editan', async () => {
      await crearComponente(true);

      await teclaEnFila(1, 'Enter');
      expect(el().querySelector<HTMLInputElement>('#nombre')!.value).toBe('Tersuave');

      filas()[0].dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
      await refrescar();
      expect(el().querySelector<HTMLInputElement>('#nombre')!.value).toBe('Sinteplast');
    });

    it('↓ y ↑ mueven la fila elegida y el foco', async () => {
      await crearComponente(true);
      expect(filas().map((f) => f.getAttribute('tabindex'))).toEqual(['0', '-1']);
      filas()[0].focus();
      await refrescar();

      await teclaEnFila(0, 'ArrowDown');
      expect(elegidas()).toEqual([filas()[1]]);
      expect(document.activeElement).toBe(filas()[1]);

      await teclaEnFila(1, 'ArrowUp');
      expect(elegidas()).toEqual([filas()[0]]);
    });

    it('Nuevo deja el formulario en alta y enfoca el nombre', async () => {
      await crearComponente(true);
      await enBarra(0, 'editar');

      barra('button.nuevo')!.click();
      await refrescar();

      expect(el().querySelector('form h2')?.textContent).toContain('Nuevo proveedor');
      expect(el().querySelector<HTMLInputElement>('#nombre')!.value).toBe('');
      expect(document.activeElement).toBe(el().querySelector('#nombre'));
    });

    it('«Mostrar dados de baja» está en la barra', async () => {
      await crearComponente(true);

      expect(el().querySelector('app-barra-acciones #inactivos')).not.toBeNull();
    });
  });

  describe('campos', () => {
    it('tienen nombre, no autocompletan ni corrigen y el ejemplo termina en «…»', async () => {
      await crearComponente(true);

      for (const id of ['nombre', 'cuit']) {
        const campo = el().querySelector<HTMLInputElement>(`#${id}`)!;
        expect(campo.name).toBe(id);
        expect(campo.getAttribute('autocomplete')).toBe('off');
        expect(campo.getAttribute('spellcheck')).toBe('false');
        expect(campo.placeholder.endsWith('…')).toBe(true);
      }
    });

    it('al enviar con errores marca los inválidos, enlaza sus mensajes y enfoca el primero', async () => {
      await crearComponente(true);
      await escribir('#cuit', '123');

      await enviarFormulario();

      const nombre = el().querySelector<HTMLInputElement>('#nombre')!;
      expect(nombre.getAttribute('aria-invalid')).toBe('true');
      expect(descripciones('#nombre')).toEqual(['El nombre es obligatorio.']);
      expect(el().querySelector('#cuit')!.getAttribute('aria-invalid')).toBe('true');
      expect(descripciones('#cuit')).toHaveLength(2);
      expect(descripciones('#cuit')[0]).toBe('Con o sin guiones.');
      expect(document.activeElement).toBe(nombre);
    });

    it('sin errores el CUIT solo se describe con su pista', async () => {
      await crearComponente(true);

      expect(descripciones('#cuit')).toEqual(['Con o sin guiones.']);
      expect(el().querySelector('#cuit')!.getAttribute('aria-invalid')).toBeNull();
    });
  });

  describe('contacto (mejoras-ferreteros-bloque-1, S4)', () => {
    it('el formulario tiene contacto, teléfono, WhatsApp y correo, opcionales, con su etiqueta', async () => {
      await crearComponente(true);

      for (const [id, etiqueta] of [
        ['contacto', 'Contacto (opcional)'],
        ['telefono', 'Teléfono (opcional)'],
        ['whatsapp', 'WhatsApp (opcional)'],
        ['email', 'Correo (opcional)'],
      ]) {
        expect(el().querySelector(`label[for="${id}"]`)?.textContent?.trim()).toBe(etiqueta);
        expect(el().querySelector<HTMLInputElement>(`#${id}`)!.name).toBe(id);
      }
      expect(el().querySelector<HTMLInputElement>('#telefono')!.type).toBe('tel');
      expect(el().querySelector<HTMLInputElement>('#email')!.type).toBe('email');
    });

    it('el alta manda el contacto sin espacios sobrantes', async () => {
      await crearComponente(true);
      servicio.crear.mockReturnValue(of({ id: 3, nombre: 'Alba', cuit: null, activo: true, contacto: 'Juan' }));

      await escribir('#nombre', 'Alba');
      await escribir('#contacto', ' Juan ');
      await escribir('#telefono', '223 555-1234');
      await escribir('#whatsapp', '2235551234');
      await escribir('#email', 'ventas@alba.com');
      await enviarFormulario();

      expect(servicio.crear).toHaveBeenCalledWith({
        nombre: 'Alba',
        cuit: null,
        activo: true,
        contacto: 'Juan',
        telefono: '223 555-1234',
        whatsapp: '2235551234',
        email: 'ventas@alba.com',
      });
    });

    it('editar precarga el contacto guardado', async () => {
      await crearComponente(true, [{ ...sinteplast, contacto: 'Ana', telefono: '111', whatsapp: null, email: 'a@b.com' }]);

      await enBarra(0, 'editar');

      expect(el().querySelector<HTMLInputElement>('#contacto')!.value).toBe('Ana');
      expect(el().querySelector<HTMLInputElement>('#telefono')!.value).toBe('111');
      expect(el().querySelector<HTMLInputElement>('#whatsapp')!.value).toBe('');
      expect(el().querySelector<HTMLInputElement>('#email')!.value).toBe('a@b.com');
    });

    it('la lista muestra cada dato de contacto en su columna, o «—» si no hay (arreglos-compras, S2)', async () => {
      await crearComponente(true, [
        { ...sinteplast, contacto: 'Ana', telefono: '223 555-1234', whatsapp: '223 15 555-0000', email: 'ana@sinteplast.com' },
        tersuave,
      ]);

      const textos = (fila: HTMLElement) => Array.from(fila.querySelectorAll('td')).map((td) => td.textContent?.trim());
      expect(Array.from(el().querySelectorAll('thead th')).map((th) => th.textContent?.trim())).toEqual([
        'Nombre',
        'CUIT',
        'Contacto',
        'Teléfono',
        'WhatsApp',
        'Correo',
      ]);
      expect(textos(filas()[0]).slice(2)).toEqual(['Ana', '223 555-1234', '223 15 555-0000', 'ana@sinteplast.com']);
      expect(textos(filas()[1]).slice(2)).toEqual(['—', '—', '—', '—']);
    });

    it('sin CUIT, el «—» no va en letra monoespaciada; un CUIT cargado sí (arreglos-compras, S1)', async () => {
      await crearComponente(true, [
        { ...sinteplast, cuit: '30-71234567-1' },
        { ...tersuave, cuit: null },
      ]);

      const cuit = (fila: HTMLElement) => fila.querySelectorAll('td')[1];
      expect(cuit(filas()[0]).textContent?.trim()).toBe('30-71234567-1');
      expect(cuit(filas()[0]).classList).toContain('mono');
      expect(cuit(filas()[1]).textContent?.trim()).toBe('—');
      expect(cuit(filas()[1]).classList).not.toContain('mono');
    });

    it('un correo inválido no se manda y lo explica', async () => {
      await crearComponente(true);

      await escribir('#nombre', 'Alba');
      await escribir('#email', 'no-es-correo');
      await enviarFormulario();

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(el().querySelector('#email')!.getAttribute('aria-invalid')).toBe('true');
      expect(el().querySelector('#email-error')?.textContent?.trim()).toBe('El correo no es válido.');
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

      expect(servicio.crear).toHaveBeenCalledWith({
        nombre: 'Alba',
        cuit: null,
        activo: true,
        contacto: null,
        telefono: null,
        whatsapp: null,
        email: null,
      });
      const textos = textoFilas();
      expect(textos).toHaveLength(3);
      expect(textos[0]).toContain('Alba');
    });

    it('mientras se guarda un alta los campos quedan bloqueados; al guardarse, el foco vuelve al nombre para el siguiente', async () => {
      await crearComponente(true);
      const respuesta = new Subject<Proveedor>();
      servicio.crear.mockReturnValue(respuesta);
      await escribir('#nombre', 'Alba');

      await enviarFormulario();

      expect(el().querySelector('form.alta')!.hasAttribute('inert')).toBe(true);

      respuesta.next({ id: 3, nombre: 'Alba', cuit: null, activo: true });
      respuesta.complete();
      await refrescar();

      const nombre = el().querySelector<HTMLInputElement>('#nombre')!;
      expect(el().querySelector('form.alta')!.hasAttribute('inert')).toBe(false);
      expect(nombre.value).toBe('');
      expect(document.activeElement).toBe(nombre);
    });

    it('si el alta falla los campos se desbloquean con lo escrito', async () => {
      await crearComponente(true);
      servicio.crear.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
      await escribir('#nombre', 'Alba');

      await enviarFormulario();

      expect(el().querySelector('form.alta')!.hasAttribute('inert')).toBe(false);
      expect(el().querySelector<HTMLInputElement>('#nombre')!.value).toBe('Alba');
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
      const editado: Proveedor = { id: 1, nombre: 'Sinteplast SA', cuit: '30-12345678-1', activo: true };
      servicio.actualizar.mockReturnValue(of(editado));

      await enBarra(0, 'editar');
      expect(el().querySelector<HTMLInputElement>('#nombre')!.value).toBe('Sinteplast');

      await escribir('#nombre', 'Sinteplast SA');
      await escribir('#cuit', '30-12345678-1');
      await enviarFormulario();

      expect(servicio.actualizar).toHaveBeenCalledWith(1, {
        nombre: 'Sinteplast SA',
        cuit: '30-12345678-1',
        activo: true,
        contacto: null,
        telefono: null,
        whatsapp: null,
        email: null,
      });
      expect(servicio.crear).not.toHaveBeenCalled();
      const textos = textoFilas();
      expect(textos[0]).toContain('Sinteplast SA');
      expect(textos).toHaveLength(2);
      expect(el().querySelector<HTMLInputElement>('#nombre')!.value).toBe('');
    });

    it('cancelar vuelve al alta sin llamar al servicio', async () => {
      await crearComponente(true);

      await enBarra(1, 'editar');
      await clickEn('button.cancelar');

      expect(el().querySelector<HTMLInputElement>('#nombre')!.value).toBe('');
      expect(servicio.actualizar).not.toHaveBeenCalled();
    });

    it('muestra el mensaje del backend si la edición falla', async () => {
      await crearComponente(true);
      servicio.actualizar.mockReturnValue(
        throwError(() => errorHttp(409, 'Ya existe un proveedor llamado Tersuave.')),
      );

      await enBarra(0, 'editar');
      await escribir('#nombre', 'Tersuave');
      await enviarFormulario();

      expect(el().querySelector('[role="alert"]')?.textContent).toContain(
        'Ya existe un proveedor llamado Tersuave.',
      );
    });
  });

  describe('baja', () => {
    async function darDeBaja(fila: number, boton: 'confirmar' | 'cancelar' = 'confirmar') {
      await enBarra(fila, 'desactivar');
      await clickEn(`app-confirmacion button.${boton}`);
    }

    it('desactiva el proveedor confirmado con el diálogo propio y lo saca de la lista', async () => {
      await crearComponente(true);
      const nativo = vi.spyOn(window, 'confirm');
      servicio.desactivar.mockReturnValue(of(undefined) as Observable<void>);

      await enBarra(0, 'desactivar');
      expect(el().querySelector('app-confirmacion h2')?.textContent).toContain('¿Dar de baja a Sinteplast?');
      await clickEn('app-confirmacion button.confirmar');

      expect(nativo).not.toHaveBeenCalled();
      expect(servicio.desactivar).toHaveBeenCalledWith(1);
      const textos = textoFilas();
      expect(textos).toHaveLength(1);
      expect(textos[0]).toContain('Tersuave');
      expect(el().querySelector('app-confirmacion')).toBeNull();
    });

    it('no hace nada si el Dueño cancela el diálogo', async () => {
      await crearComponente(true);

      await darDeBaja(0, 'cancelar');

      expect(servicio.desactivar).not.toHaveBeenCalled();
      expect(textoFilas()).toHaveLength(2);
    });

    it('con «Mostrar dados de baja» tildado, la fila queda marcada De baja en vez de desaparecer', async () => {
      await crearComponente(true);
      await clickEn('#inactivos');
      servicio.desactivar.mockReturnValue(of(undefined) as Observable<void>);

      const sinteplastIdx = () => filas().findIndex((f) => f.textContent?.includes('Sinteplast'));
      await darDeBaja(sinteplastIdx());

      expect(textoFilas()).toHaveLength(3);
      const fila = filas()[sinteplastIdx()];
      expect(fila.querySelector('.etiqueta.de-baja')).not.toBeNull();
      expect(fila.getAttribute('aria-selected')).toBe('true');
      expect(barra('button.reactivar')).not.toBeNull();
      expect(barra('button.desactivar')).toBeNull();
    });

    it('muestra el mensaje del backend si la baja falla', async () => {
      await crearComponente(true);
      servicio.desactivar.mockReturnValue(
        throwError(() => errorHttp(404, 'Proveedor no encontrado')),
      );

      await darDeBaja(0);

      expect(el().querySelector('[role="alert"]')?.textContent).toContain(
        'Proveedor no encontrado',
      );
      expect(textoFilas()).toHaveLength(2);
    });
  });

  describe('dados de baja', () => {
    it('«Mostrar dados de baja» los trae marcados y con Reactivar en vez de Dar de baja', async () => {
      await crearComponente(true);

      await clickEn('#inactivos');

      expect(servicio.listar).toHaveBeenLastCalledWith(true);
      const fila = filas().find((f) => f.textContent?.includes('Pinturas Viejas'))!;
      expect(fila.classList).toContain('inactivo');
      expect(fila.querySelector('.etiqueta.de-baja')?.textContent?.trim()).toBe('De baja');
      expect(el().querySelector('.barra-estado')?.textContent).toContain('1 de baja');

      await elegir(indiceDe('Pinturas Viejas'));
      expect(barra('button.reactivar')).not.toBeNull();
      expect(barra('button.desactivar')).toBeNull();
    });

    it('al destildar vuelve a pedir solo los activos', async () => {
      await crearComponente(true);
      await clickEn('#inactivos');

      await clickEn('#inactivos');

      expect(servicio.listar).toHaveBeenLastCalledWith(false);
      expect(textoFilas().some((t) => t.includes('Pinturas Viejas'))).toBe(false);
    });

    it('Reactivar lo vuelve a activo y avisa', async () => {
      await crearComponente(true);
      await clickEn('#inactivos');
      const indice = filas().findIndex((f) => f.textContent?.includes('Pinturas Viejas'));

      await enBarra(indice, 'reactivar');

      expect(servicio.reactivar).toHaveBeenCalledWith(9);
      expect(filas()[indice].querySelector('.etiqueta.de-baja')).toBeNull();
      expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se reactivó «Pinturas Viejas».');
    });

    it('el Vendedor no ve Reactivar', async () => {
      await crearComponente(false);
      await clickEn('#inactivos');

      expect(el().querySelector('button.reactivar')).toBeNull();
    });

    it('si el alta choca con uno dado de baja, ofrece reactivarlo desde el aviso', async () => {
      await crearComponente(true);
      servicio.crear.mockReturnValue(
        throwError(() =>
          errorHttp(
            409,
            'Ya existe un proveedor llamado Pinturas Viejas, pero está dado de baja. Reactivalo para volver a usarlo.',
          ),
        ),
      );
      await escribir('#nombre', 'pinturas viejas');

      await enviarFormulario();

      expect(el().querySelector('[role="alert"]')?.textContent).toContain('está dado de baja');
      const boton = el().querySelector<HTMLButtonElement>('button.reactivar-existente')!;
      expect(boton.textContent).toContain('Reactivar «Pinturas Viejas»');

      await clickEn('button.reactivar-existente');

      expect(servicio.reactivar).toHaveBeenCalledWith(9);
      expect(textoFilas().some((t) => t.includes('Pinturas Viejas'))).toBe(true);
      expect(el().querySelector('[role="alert"]')).toBeNull();
      expect(el().querySelector<HTMLInputElement>('#nombre')!.value).toBe('');
    });
  });

  describe('CUIT', () => {
    it('acepta el CUIT sin guiones y lo manda con guiones', async () => {
      await crearComponente(true);
      servicio.crear.mockReturnValue(of({ id: 3, nombre: 'Alba', cuit: '30-12345678-1', activo: true }));
      await escribir('#nombre', 'Alba');
      await escribir('#cuit', '30123456781');

      await enviarFormulario();

      expect(servicio.crear).toHaveBeenCalledWith(expect.objectContaining({ nombre: 'Alba', cuit: '30-12345678-1' }));
    });

    it('al salir del campo le pone los guiones', async () => {
      await crearComponente(true);
      await escribir('#cuit', '30123456781');

      el().querySelector('#cuit')!.dispatchEvent(new Event('blur'));
      fixture.detectChanges();

      expect(el().querySelector<HTMLInputElement>('#cuit')!.value).toBe('30-12345678-1');
    });

    it('con el dígito verificador mal no manda nada y lo explica junto al campo', async () => {
      await crearComponente(true);
      await escribir('#nombre', 'Alba');
      await escribir('#cuit', '30-12345678-9');

      await enviarFormulario();

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(el().querySelector('.cuit .campo-error')?.textContent).toContain(
        'El CUIT no es válido: revisá los números, el último no coincide.',
      );
    });

    it('el nombre vacío se marca junto al campo', async () => {
      await crearComponente(true);

      await enviarFormulario();

      expect(el().querySelector('.nombre-campo .campo-error')?.textContent).toContain('El nombre es obligatorio.');
    });

    // Bugs de la guía, S14: el alta vacía no avisa por pasar por el campo; avisa al intentar guardar.
    it('pasar por el nombre vacío no lo marca', async () => {
      await crearComponente(true);

      const nombre = el().querySelector<HTMLInputElement>('#nombre')!;
      nombre.dispatchEvent(new Event('focus'));
      nombre.dispatchEvent(new Event('blur'));
      fixture.detectChanges();

      expect(el().querySelector('.nombre-campo .campo-error')).toBeNull();
    });
  });

  describe('datos de la lista, de solo lectura (#44)', () => {
    const conLista: Proveedor = {
      ...tersuave,
      listaIncluyeIva: true,
      bonificacion1: 10,
      bonificacion2: 2.5,
      bonificacion3: null,
      mapeoLista: {
        hoja: 0,
        filaDesde: 5,
        columnaCodigo: 0,
        columnaDescripcion: 1,
        columnaCosto: 3,
        columnaAjuste1: 27,
        monedaFija: 'USD',
        encabezados: { codigo: 'COD', descripcion: 'DESCRIPCION', costo: 'PRECIO', ajuste1: 'DTO' },
      },
    };
    const seccion = () => el().querySelector<HTMLElement>('form .lista-proveedor');
    const dato = (titulo: string) => {
      const dt = Array.from(seccion()!.querySelectorAll('dt')).find((d) => d.textContent?.trim() === titulo);
      return dt?.nextElementSibling?.textContent?.replace(/\s+/g, ' ').trim();
    };

    it('al editar un proveedor con lista importada muestra el IVA, las bonificaciones, la moneda y las columnas', async () => {
      await crearComponente(true, [sinteplast, conLista]);

      await enBarra(1, 'editar');

      expect(seccion()?.querySelector('h3')?.textContent?.trim()).toBe('Lista de precios');
      expect(dato('La lista incluye IVA')).toBe('Sí');
      expect(dato('Bonificaciones')).toBe('10 % + 2,5 % (en cascada)');
      expect(dato('Moneda')).toBe('USD para toda la lista');
      expect(dato('Datos desde la fila')).toBe('5');
      expect(Array.from(seccion()!.querySelectorAll('.columnas li')).map((li) => li.textContent?.trim())).toEqual([
        'Código: A · COD',
        'Descripción: B · DESCRIPCION',
        'Costo: D · PRECIO',
        'Descuento o recargo 1: AB · DTO',
      ]);
      expect(seccion()!.querySelector('.nota-lista')?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
        'Los datos de la lista se fijan al confirmar una importación en Importar lista.',
      );
      expect(seccion()!.querySelector('a')?.getAttribute('href')).toBe('/importaciones');
      expect(seccion()!.querySelectorAll('input, select, textarea')).toHaveLength(0);
    });

    it('sin IVA, una sola bonificación y una columna sin encabezado', async () => {
      await crearComponente(true, [
        {
          ...conLista,
          listaIncluyeIva: false,
          bonificacion1: null,
          bonificacion2: 15,
          mapeoLista: { ...conLista.mapeoLista!, monedaFija: null, encabezados: null },
        },
      ]);

      await enBarra(0, 'editar');

      expect(dato('La lista incluye IVA')).toBe('No');
      expect(dato('Bonificaciones')).toBe('15 %');
      expect(dato('Moneda')).toBeUndefined();
      expect(Array.from(seccion()!.querySelectorAll('.columnas li')).map((li) => li.textContent?.trim())[0]).toBe(
        'Código: A',
      );
    });

    it('un proveedor sin lista importada lo dice, y muestra el IVA y las bonificaciones que tenga', async () => {
      await crearComponente(true, [sinteplast, { ...tersuave, listaIncluyeIva: true, bonificacion1: 40, bonificacion2: 10 }]);

      await enBarra(0, 'editar');
      expect(dato('La lista incluye IVA')).toBe('No');
      expect(dato('Bonificaciones')).toBe('Sin bonificaciones');

      await enBarra(1, 'editar');
      expect(dato('La lista incluye IVA')).toBe('Sí');
      expect(dato('Bonificaciones')).toBe('40 % + 10 % (en cascada)');
      expect(dato('Columnas')).toBeUndefined();
      expect(dato('Datos desde la fila')).toBeUndefined();
      expect(seccion()?.querySelector('.sin-lista')?.textContent?.trim()).toBe(
        'Todavía no se importó ninguna lista de este proveedor.',
      );
      expect(seccion()?.querySelector('.nota-lista a')?.getAttribute('href')).toBe('/importaciones');
    });

    it('en el alta no aparece', async () => {
      await crearComponente(true, [conLista]);

      expect(seccion()).toBeNull();
    });
  });
});
