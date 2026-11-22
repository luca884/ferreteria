import { CdkDragDrop, CdkDropList } from '@angular/cdk/drag-drop';
import { ScrollDispatcher } from '@angular/cdk/scrolling';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { vi } from 'vitest';
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
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: MenuActual, useValue: { recargar } }],
    });
    fixture = TestBed.createComponent(PaginaConfiguracionMenu);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/usuarios?incluirInactivos=true' }).flush([
      { id: 7, nombre: 'Ana', rol: 'VENDEDOR', activo: false },
      { id: 8, nombre: 'Juan', rol: 'DUENO', activo: true },
    ]);
    http.expectOne({ method: 'GET', url: vendedor }).flush(original);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

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

  it('muestra los dos roles y todos los usuarios, el origen y solo las opciones del rol elegido', () => {
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

  it('arrastra opciones y grupos y guarda el cuerpo exacto; no oculta «Personalizar menú»', () => {
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
});
