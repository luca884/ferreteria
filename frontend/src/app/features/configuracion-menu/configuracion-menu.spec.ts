import { CdkDrag, CdkDragDrop, CdkDropList } from '@angular/cdk/drag-drop';
import { CdkScrollable, ScrollDispatcher } from '@angular/cdk/scrolling';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { DISPOSICION_ORIGINAL, Disposicion } from '../../layout/menu';
import { MenuActual } from '../../layout/menu-actual';
import { PaginaConfiguracionMenu } from './configuracion-menu';

const roles = '/api/menu/disposiciones/roles';
const usuarios = '/api/menu/disposiciones/usuarios';
const vendedor = `${roles}/VENDEDOR`;
const dueno = `${roles}/DUENO`;
const ana = `${usuarios}/7`;
const juan = `${usuarios}/8`;
const original = { origen: 'ORIGINAL', disposicion: null };

describe('Personalizar menú', () => {
  let fixture: ComponentFixture<PaginaConfiguracionMenu>;
  let http: HttpTestingController;
  const recargar = vi.fn();
  const sesionDueno = { userId: 99, rol: 'DUENO' as const };
  const sesion = signal(sesionDueno);
  const el = () => fixture.nativeElement as HTMLElement;
  const buscar = <T extends HTMLElement = HTMLElement>(selector: string) => el().querySelector<T>(selector)!;
  const texto = (selector: string) => buscar(selector).textContent?.trim();
  const select = () => buscar<HTMLSelectElement>('select#menu-de');
  const boton = (rotulo: string) => [...el().querySelectorAll('button')].find((b) => b.textContent?.trim() === rotulo)!;
  const input = (id: string) => buscar<HTMLInputElement>(`#nombre-${id}`);
  const opciones = (id: string) => [...el().querySelectorAll(`#${id === 'ocultas' ? 'lista-ocultas' : `opciones-${id}`} li [cdkdraghandle]`)]
    .map((item) => item.textContent?.trim());

  beforeEach(() => {
    recargar.mockClear();
    sesion.set(sesionDueno);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        { provide: MenuActual, useValue: { recargar } }, { provide: AuthService, useValue: { sesion } },
      ],
    });
    fixture = TestBed.createComponent(PaginaConfiguracionMenu);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function iniciarUsuarios(fallanUsuarios = false) {
    fixture.detectChanges();
    const peticion = http.expectOne({ method: 'GET', url: '/api/usuarios?incluirInactivos=true' });
    if (fallanUsuarios) {
      peticion.flush({}, { status: 503, statusText: 'Service Unavailable' });
    } else {
      peticion.flush([
        { id: 7, nombre: 'Ana', rol: 'VENDEDOR', activo: false },
        { id: 8, nombre: 'Juan', rol: 'DUENO', activo: true },
      ]);
    }
  }

  function iniciar(fallanUsuarios = false) {
    iniciarUsuarios(fallanUsuarios);
    http.expectOne({ method: 'GET', url: dueno }).flush(original);
    fixture.detectChanges();
  }

  function iniciarEnVendedores(fallanUsuarios = false) {
    iniciar(fallanUsuarios);
    elegir('Vendedores');
    http.expectOne({ method: 'GET', url: vendedor }).flush(original);
    fixture.detectChanges();
  }

  function elegir(texto: string) {
    select().value = [...select().options].find((o) => o.label.trim() === texto)!.value;
    select().dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function respuestaDueno() {
    elegir('Dueño');
    http.expectOne({ method: 'GET', url: dueno }).flush(original);
    fixture.detectChanges();
  }

  function renombrar(id: string, valor: string) {
    input(id).value = valor;
    input(id).dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function soltar(clave: string, desde: string | null, hasta: string | null, indice: number, adentro = true) {
    fixture.componentInstance.soltarOpcion({
      item: { data: clave }, previousContainer: { data: desde }, container: { data: hasta },
      currentIndex: indice, isPointerOverContainer: adentro,
    });
    fixture.detectChanges();
  }

  function click(rotulo: string) {
    boton(rotulo).click();
    fixture.detectChanges();
  }

  it('abre en el menú del Dueño', () => {
    iniciarUsuarios();
    const peticion = http.expectOne((req) => req.url.startsWith(`${roles}/`));
    peticion.flush(original);
    fixture.detectChanges();
    expect(peticion.request.method).toBe('GET');
    expect.soft(peticion.request.url).toBe(dueno);
    expect(select().value).toBe('DUENO');
    expect(texto('#origen-menu')).toBe('Usa el menú original');
    expect(opciones('ventas')).toEqual(['Presupuestos', 'Remitos', 'Caja', 'Clientes', 'Ventas', 'Reportes']);
    expect(buscar('#aviso-ajeno')).toBeNull();
  });

  it('avisa solo al editar un menú ajeno', () => {
    iniciar();
    const aviso = () => buscar('#origen-menu + p#aviso-ajeno.alerta.advertencia[role="status"]')?.textContent?.trim();

    elegir('Vendedores');
    http.expectOne({ method: 'GET', url: vendedor }).flush(original);
    fixture.detectChanges();
    expect.soft(aviso()).toBe('Estás editando el menú de los Vendedores. Tu menú no cambia.');

    elegir('Juan — Dueño');
    http.expectOne({ method: 'GET', url: juan }).flush(original);
    fixture.detectChanges();
    expect.soft(aviso()).toBe('Estás editando el menú de Juan. Tu menú no cambia.');

    respuestaDueno();
    expect(buscar('#aviso-ajeno')).toBeNull();

    sesion.set({ userId: 8, rol: 'DUENO' });
    elegir('Juan — Dueño');
    http.expectOne({ method: 'GET', url: juan }).flush(original);
    fixture.detectChanges();
    expect(buscar('#aviso-ajeno')).toBeNull();
  });

  it('muestra los dos roles y todos los usuarios, el origen y solo las opciones del rol elegido', () => {
    iniciarEnVendedores();
    expect(texto('app-barra-acciones h1')).toBe('Personalizar menú');
    expect(texto('label[for="menu-de"]')).toBe('Menú de');
    expect([...select().options].map((o) => o.label.trim())).toEqual(['Vendedores', 'Dueño', 'Ana — Vendedor (inactivo)', 'Juan — Dueño']);
    expect(texto('#origen-menu')).toBe('Usa el menú original');
    expect(texto('.aviso-escritorio')).toBe('Personalizá el menú desde la computadora.');
    expect(opciones('ventas')).toEqual(['Presupuestos', 'Remitos', 'Caja', 'Clientes']);
    expect(opciones('configuracion')).toEqual([]);
    expect(opciones('ocultas')).toEqual([]);
    expect(boton('Guardar').disabled).toBe(true);

    elegir('Dueño');
    http.expectOne({ method: 'GET', url: dueno }).flush({ origen: 'ROL', disposicion: DISPOSICION_ORIGINAL });
    fixture.detectChanges();
    expect(texto('#origen-menu')).toBe('Menú propio del Dueño');
    expect(opciones('configuracion').at(-1)).toBe('Personalizar menú');
    expect(texto('#opciones-configuracion li:last-child .ayuda')).toBe('No se puede ocultar');

    elegir('Ana — Vendedor (inactivo)');
    http.expectOne({ method: 'GET', url: ana }).flush({ origen: 'ROL', disposicion: {
      grupos: DISPOSICION_ORIGINAL.grupos, ocultas: ['remitos', 'usuarios'],
    } });
    fixture.detectChanges();
    expect(texto('#origen-menu')).toBe('Usa el menú de los Vendedores');
    expect(opciones('configuracion')).toEqual([]);
    expect(opciones('ocultas')).toEqual(['Remitos']);

    elegir('Juan — Dueño');
    http.expectOne({ method: 'GET', url: juan }).flush({ origen: 'USUARIO', disposicion: DISPOSICION_ORIGINAL });
    fixture.detectChanges();
    expect(texto('#origen-menu')).toBe('Menú propio de Juan');
    expect(opciones('configuracion').at(-1)).toBe('Personalizar menú');
  });

  it('usa un paso de auto-scroll de 14 en todas las listas de arrastre', () => {
    iniciarEnVendedores();
    const listas = fixture.debugElement.queryAll(By.directive(CdkDropList));
    expect(listas.map((lista) => lista.injector.get(CdkDropList).autoScrollStep))
      .toEqual(Array(DISPOSICION_ORIGINAL.grupos.length + 2).fill(14));
  });

  it('registra ambos paneles desplazables como ancestros de sus listas', () => {
    iniciarEnVendedores();
    const scroll = TestBed.inject(ScrollDispatcher);
    for (const lista of fixture.debugElement.queryAll(By.directive(CdkDropList))) {
      const panel = lista.nativeElement.closest('.panel');
      const directiva = fixture.debugElement.query(By.css(lista.nativeElement.closest('.ocultas')
        ? '.panel.ocultas' : '.panel:not(.ocultas)')).injector.get(CdkScrollable);
      expect(directiva.getElementRef().nativeElement).toBe(panel);
      expect(scroll.getAncestorScrollContainers(lista.injector.get(CdkDropList).element)).toContain(directiva);
    }
  });

  it('aplica la clase de vista previa solo a las opciones, incluidas las ocultas', () => {
    iniciarEnVendedores();
    soltar('remitos', 'ventas', null, 0);
    const arrastres = fixture.debugElement.queryAll(By.directive(CdkDrag));
    expect(opciones('ocultas')).toEqual(['Remitos']);
    for (const arrastre of arrastres) {
      const drag = arrastre.injector.get(CdkDrag);
      if (arrastre.nativeElement.matches('.opcion')) {
        expect(drag.previewClass).toBe('opcion-preview');
        expect(arrastre.nativeElement.querySelector('.opcion-rotulo')?.textContent.trim()).not.toBeFalsy();
      } else {
        expect(drag.previewClass).toBeUndefined();
      }
    }
  });

  it('mantiene la misma alerta de validación vacía en la barra y la llena sin habilitar Guardar', () => {
    iniciarEnVendedores();
    const alerta = buscar('#errores-grupos');
    expect(alerta).not.toBeNull();
    expect(alerta.closest('.acciones-menu')).toBe(boton('Guardar').closest('.acciones-menu'));
    expect(alerta.getAttribute('role')).toBe('alert');
    expect(alerta.textContent?.trim()).toBe('');

    click('Agregar grupo');
    expect(buscar('#errores-grupos')).toBe(alerta);
    expect(alerta.getAttribute('role')).toBe('alert');
    expect(alerta.textContent?.trim()).toBe('Poné un nombre para el grupo.');
    expect(boton('Guardar').disabled).toBe(true);
    click('Guardar');
    http.expectNone((req) => req.method === 'PUT');
    expect(recargar).not.toHaveBeenCalled();

    click('Descartar cambios');
    expect(buscar('#errores-grupos')).toBe(alerta);
    expect(alerta.textContent?.trim()).toBe('');
  });

  it('arrastra opciones y grupos y guarda el cuerpo exacto; no oculta «Personalizar menú»', () => {
    iniciarEnVendedores();
    respuestaDueno();
    const listas = fixture.debugElement.queryAll(By.directive(CdkDropList)).map((e) => e.injector.get(CdkDropList));
    const config = listas.find((l) => l.data === 'configuracion')!;
    const ocultas = listas.find((l) => l.data === null)!;
    const editor = config.getSortedItems().find((item) => item.data === 'personalizar-menu')!;
    expect(editor.dropContainer).toBe(config);
    expect(ocultas.enterPredicate(editor, ocultas)).toBe(false);
    const scroll = TestBed.inject(ScrollDispatcher);
    expect(scroll.getAncestorScrollContainers(config.element)).toHaveLength(1);
    expect(scroll.getAncestorScrollContainers(ocultas.element)).toHaveLength(1);
    soltar('personalizar-menu', 'configuracion', null, 0);
    soltar('remitos', 'ventas', null, 0, false);
    expect(opciones('ocultas')).toEqual([]);
    expect(boton('Guardar').disabled).toBe(true);

    soltar('caja', 'ventas', 'compras', 1);
    soltar('remitos', 'ventas', null, 0);
    fixture.componentInstance.soltarGrupo({ previousIndex: 0, currentIndex: 1, isPointerOverContainer: true } as CdkDragDrop<unknown>);
    renombrar('ventas', '  Pedidos  ');
    expect(opciones('compras')).toEqual(['Proveedores', 'Caja', 'Compras']);
    expect(opciones('ocultas')).toEqual(['Remitos']);
    click('Guardar');

    const peticion = http.expectOne({ method: 'PUT', url: dueno });
    const [ventas, articulos, compras, precios, configuracion] = DISPOSICION_ORIGINAL.grupos;
    const disposicion: Disposicion = { grupos: [
      articulos, { ...ventas, rotulo: 'Pedidos', claves: ['presupuestos', 'clientes', 'ventas', 'reportes'] },
      { ...compras, claves: ['proveedores', 'caja', 'compras'] }, precios, configuracion,
    ], ocultas: ['remitos'] };
    expect(peticion.request.body).toEqual(disposicion);
    expect(recargar).not.toHaveBeenCalled();
    peticion.flush({ origen: 'ROL', disposicion });
    fixture.detectChanges();
    expect(texto('.alerta.exito')).toBe('Guardamos el menú.');
    expect(texto('#origen-menu')).toBe('Menú propio del Dueño');
    expect(boton('Guardar').disabled).toBe(true);
    expect(recargar).toHaveBeenCalledTimes(1);
  });

  it('restaura el usuario solo después de confirmar y vuelve a leer lo que hereda', () => {
    iniciarEnVendedores();
    elegir('Ana — Vendedor (inactivo)');
    http.expectOne({ method: 'GET', url: ana }).flush({ origen: 'USUARIO', disposicion: DISPOSICION_ORIGINAL });
    fixture.detectChanges();
    click('Restaurar');
    expect(buscar('[role="alertdialog"]')).not.toBeNull();
    http.expectNone({ method: 'DELETE', url: ana });
    click('Cancelar');
    http.expectNone({ method: 'DELETE', url: ana });
    expect(texto('#origen-menu')).toBe('Menú propio de Ana');

    click('Restaurar');
    buscar<HTMLButtonElement>('app-confirmacion .confirmar').click();
    http.expectOne({ method: 'DELETE', url: ana }).flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne({ method: 'GET', url: ana }).flush({ origen: 'ROL', disposicion: DISPOSICION_ORIGINAL });
    fixture.detectChanges();
    expect(texto('.alerta.exito')).toBe('Volvió el menú original.');
    expect(texto('#origen-menu')).toBe('Usa el menú de los Vendedores');
    expect(recargar).toHaveBeenCalledTimes(1);
  });

  it('muestra el mensaje del 400 y permite corregir sin perder el borrador', () => {
    iniciarEnVendedores();
    renombrar('ventas', 'Pedidos');
    click('Guardar');
    http.expectOne({ method: 'PUT', url: vendedor }).flush({ message: 'El identificador del grupo no es válido.' },
      { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(texto('.error[role="alert"]')).toBe('El identificador del grupo no es válido.');
    expect(input('ventas').value).toBe('Pedidos');
    expect(boton('Guardar').disabled).toBe(false);
    expect(recargar).not.toHaveBeenCalled();
  });

  it('no abandona el borrador al elegir otro menú sin confirmar y permite descartarlo desde la barra', () => {
    iniciarEnVendedores();
    renombrar('ventas', 'Pedidos');
    elegir('Dueño');
    expect(select().selectedOptions[0].label.trim()).toBe('Vendedores');
    expect(texto('app-confirmacion h2')).toBe('¿Descartar los cambios?');
    http.expectNone(dueno);
    click('Cancelar');
    expect(input('ventas').value).toBe('Pedidos');

    click('Descartar cambios');
    expect(input('ventas').value).toBe('Ventas');
    expect(boton('Guardar').disabled).toBe(true);

    renombrar('ventas', 'Pedidos');
    elegir('Dueño');
    buscar<HTMLButtonElement>('app-confirmacion .confirmar').click();
    http.expectOne({ method: 'GET', url: dueno }).flush(original);
    fixture.detectChanges();
    expect(select().selectedOptions[0].label.trim()).toBe('Dueño');
    expect(input('ventas').value).toBe('Ventas');
    expect(boton('Guardar').disabled).toBe(true);
  });

  it('deshabilita Guardar cuando el nombre no es válido y confirma antes de borrar un grupo propio', () => {
    iniciarEnVendedores();
    click('Agregar grupo');
    const id = el().querySelector<HTMLInputElement>('input[id^="nombre-g-"]')!.id.replace('nombre-', '');
    expect(texto('#errores-grupos')).toBe('Poné un nombre para el grupo.');
    expect(boton('Guardar').disabled).toBe(true);
    renombrar(id, ' vENTAS ');
    expect(texto('#errores-grupos')).toBe('Ya hay un grupo con ese nombre.');
    expect(boton('Guardar').disabled).toBe(true);
    renombrar(id, 'x'.repeat(31));
    expect(texto('#errores-grupos')).toBe('El nombre del grupo puede tener hasta 30 caracteres.');
    expect(boton('Guardar').disabled).toBe(true);
    renombrar(id, 'Diario');
    soltar('caja', 'ventas', id, 0);
    expect(opciones(id)).toEqual(['Caja']);
    click('Borrar grupo');
    expect(opciones(id)).toEqual(['Caja']);
    buscar<HTMLButtonElement>('app-confirmacion .confirmar').click();
    fixture.detectChanges();
    expect(input(id)).toBeNull();
    expect(opciones('ventas').at(-1)).toBe('Caja');
  });

  it('envía un solo PUT aunque se pulse Guardar dos veces mientras está en vuelo', () => {
    iniciarEnVendedores();
    renombrar('ventas', 'Pedidos');
    const guardar = boton('Guardar');
    guardar.click();
    guardar.click();
    fixture.detectChanges();
    expect(guardar.disabled).toBe(true);
    const peticion = http.expectOne((req) => req.method === 'PUT');
    expect(peticion.request.url).toBe(vendedor);
    expect(recargar).not.toHaveBeenCalled();

    peticion.flush({ origen: 'ROL', disposicion: peticion.request.body });
    fixture.detectChanges();
    expect(texto('.alerta.exito')).toBe('Guardamos el menú.');
    expect(recargar).toHaveBeenCalledTimes(1);
  });

  it('envía un solo DELETE y no vuelve a abrir la confirmación de Restaurar mientras está en vuelo', () => {
    iniciarEnVendedores();
    click('Restaurar');
    const confirmar = buscar<HTMLButtonElement>('app-confirmacion .confirmar');
    confirmar.click();
    confirmar.click();
    fixture.detectChanges();
    expect(boton('Restaurar').disabled).toBe(true);
    click('Restaurar');
    expect(buscar('[role="alertdialog"]')).toBeNull();
    const peticion = http.expectOne((req) => req.method === 'DELETE');
    expect(peticion.request.url).toBe(vendedor);
    http.expectNone({ method: 'GET', url: vendedor });
    expect(recargar).not.toHaveBeenCalled();

    peticion.flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne({ method: 'GET', url: vendedor }).flush(original);
    fixture.detectChanges();
    expect(texto('.alerta.exito')).toBe('Volvió el menú original.');
    expect(recargar).toHaveBeenCalledTimes(1);
  });

  it('si falla el GET del menú elegido, bloquea Guardar y Restaurar hasta reintentar con éxito', () => {
    iniciarEnVendedores();
    elegir('Dueño');
    http.expectOne({ method: 'GET', url: dueno }).flush({}, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();
    expect(texto('.error[role="alert"] span')).toBe('No se pudo cargar el menú.');
    expect(texto('.error[role="alert"] button')).toBe('Reintentar menú');
    expect(input('ventas')).toBeNull();
    expect(boton('Guardar').disabled).toBe(true);
    expect(boton('Restaurar').disabled).toBe(true);
    click('Guardar');
    click('Restaurar');
    http.expectNone((req) => req.method === 'PUT' || req.method === 'DELETE');
    expect(buscar('[role="alertdialog"]')).toBeNull();

    click('Reintentar menú');
    http.expectOne({ method: 'GET', url: dueno }).flush({ origen: 'ROL', disposicion: DISPOSICION_ORIGINAL });
    fixture.detectChanges();
    expect(buscar('.error[role="alert"]')).toBeNull();
    expect(texto('#origen-menu')).toBe('Menú propio del Dueño');
    expect(boton('Restaurar').disabled).toBe(false);
    renombrar('ventas', 'Pedidos');
    expect(boton('Guardar').disabled).toBe(false);
  });

  it('si falla la lista de usuarios, ofrece reintentar y permite editar los dos roles', () => {
    iniciarEnVendedores(true);
    expect(texto('.error[role="alert"] span')).toBe('No se pudo cargar la lista de usuarios.');
    expect(texto('.error[role="alert"] button')).toBe('Reintentar usuarios');
    expect([...select().options].map((o) => o.label.trim())).toEqual(['Vendedores', 'Dueño']);
    expect(select().value).toBe('VENDEDOR');
    expect(select().disabled).toBe(false);
    renombrar('ventas', 'Pedidos');
    expect(boton('Guardar').disabled).toBe(false);
    click('Descartar cambios');

    respuestaDueno();
    expect(select().value).toBe('DUENO');
    expect(opciones('configuracion')).toContain('Personalizar menú');
    expect(texto('.error[role="alert"] span')).toBe('No se pudo cargar la lista de usuarios.');
    renombrar('ventas', 'Pedidos');
    expect(boton('Guardar').disabled).toBe(false);
  });

  it('si falla el DELETE, muestra el mensaje del servidor y conserva el borrador', () => {
    iniciarEnVendedores();
    elegir('Dueño');
    http.expectOne({ method: 'GET', url: dueno }).flush({ origen: 'ROL', disposicion: DISPOSICION_ORIGINAL });
    fixture.detectChanges();
    renombrar('ventas', 'Pedidos');
    click('Restaurar');
    buscar<HTMLButtonElement>('app-confirmacion .confirmar').click();
    http.expectOne({ method: 'DELETE', url: dueno }).flush({ message: 'El sistema está ocupado, reintentá.' },
      { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();
    http.expectNone({ method: 'GET', url: dueno });
    expect(texto('.error[role="alert"] span')).toBe('El sistema está ocupado, reintentá.');
    expect(buscar('.alerta.exito')).toBeNull();
    expect(texto('#origen-menu')).toBe('Menú propio del Dueño');
    expect(input('ventas').value).toBe('Pedidos');
    expect(boton('Guardar').disabled).toBe(false);
    expect(boton('Restaurar').disabled).toBe(false);
    expect(recargar).not.toHaveBeenCalled();
  });

  it('explica qué menú se hereda al restaurar un rol o un usuario', () => {
    iniciarEnVendedores();
    click('Restaurar');
    expect(texto('app-confirmacion h2')).toBe('¿Restaurar el menú?');
    expect(texto('app-confirmacion p')).toBe('Se borra la base de este rol. El rol vuelve a usar el menú original.');
    click('Cancelar');

    elegir('Ana — Vendedor (inactivo)');
    http.expectOne({ method: 'GET', url: ana }).flush({ origen: 'USUARIO', disposicion: DISPOSICION_ORIGINAL });
    fixture.detectChanges();
    click('Restaurar');
    expect(texto('app-confirmacion h2')).toBe('¿Restaurar el menú?');
    expect(texto('app-confirmacion p')).toBe('Se borra el ajuste de este usuario. Va a usar el menú de su rol o el menú original.');
    http.expectNone((req) => req.method === 'DELETE');
  });

  it('ignora el cambio de objetivo mientras Guardar está en vuelo', () => {
    iniciarEnVendedores();
    renombrar('ventas', 'Pedidos');
    click('Guardar');
    const peticion = http.expectOne({ method: 'PUT', url: vendedor });
    expect(select().disabled).toBe(true);

    elegir('Dueño');
    expect(select().value).toBe('VENDEDOR');
    expect(buscar('[role="alertdialog"]')).toBeNull();
    http.expectNone((req) => req.method === 'GET');
    expect(input('ventas').value).toBe('Pedidos');

    peticion.flush({ origen: 'ROL', disposicion: peticion.request.body });
    fixture.detectChanges();
    expect(select().disabled).toBe(false);
    expect(select().value).toBe('VENDEDOR');
    expect(texto('#origen-menu')).toBe('Menú propio de los Vendedores');
  });
});
