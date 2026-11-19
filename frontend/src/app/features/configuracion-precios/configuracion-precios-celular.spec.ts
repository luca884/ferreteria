import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { PaginaConfiguracionPrecios } from './configuracion-precios';

const url = '/api/configuracion-precios';
const guardada = { margenDefectoPorcentaje: 30, redondeoMultiplo: 0 };
const ejemplos = [
  { codigo: 'TOR-001', descripcion: 'Tornillo 1/4', precioActual: 909788.6, precioNuevo: 909790 },
  { codigo: 'CLA-002', descripcion: 'Clavo 2', precioActual: 1200, precioNuevo: 1210.5 },
];
const sinCotizacion = 'Todavía no hay cotización del dólar. Probá en unos minutos';

type Modo = 'celular' | 'tablet';
let fixture: ComponentFixture<PaginaConfiguracionPrecios>;
let http: HttpTestingController;

async function crear(modo: Modo, dueno = true) {
  vi.stubGlobal('matchMedia', (media: string) => ({
    matches: media.includes(modo === 'celular' ? '639.98px' : '1023.98px'), media,
  }));
  TestBed.configureTestingModule({
    imports: [PaginaConfiguracionPrecios],
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: AuthService, useValue: { esDueno: () => dueno } }],
  });
  http = TestBed.inject(HttpTestingController);
  fixture = TestBed.createComponent(PaginaConfiguracionPrecios);
  fixture.detectChanges();
  if (dueno) http.expectOne({ method: 'GET', url }).flush(guardada);
  fixture.detectChanges();
}

afterEach(() => {
  vi.unstubAllGlobals();
  http.verify();
});

const q = <T extends HTMLElement = HTMLElement>(s: string) => (fixture.nativeElement as HTMLElement).querySelector<T>(s);
const texto = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim();
const dialogo = () => q('app-confirmacion, app-hoja-inferior');
const boton = (nombre: string, raiz: ParentNode = fixture.nativeElement) =>
  Array.from(raiz.querySelectorAll('button')).find((b) => texto(b) === nombre)!;
const sinGuardar = () => http.expectNone({ method: 'PUT', url });
const sinVistaPrevia = () => http.expectNone((r) => r.url === `${url}/articulos-que-cambian`);

function escribir(id: string, valor: string) {
  const input = q<HTMLInputElement>(`#${id}`)!;
  input.value = valor;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

function pulsar(nombre: string, raiz?: ParentNode) {
  boton(nombre, raiz).click();
  fixture.detectChanges();
}

function vistaPrevia(margen: string, redondeo: string, cantidad = 4142) {
  const previa = http.expectOne((r) => r.url === `${url}/articulos-que-cambian` && r.method === 'GET');
  expect(previa.request.params.keys()).toEqual(['margenDefectoPorcentaje', 'redondeoMultiplo']);
  expect(previa.request.params.get('margenDefectoPorcentaje')).toBe(margen);
  expect(previa.request.params.get('redondeoMultiplo')).toBe(redondeo);
  previa.flush({ cantidad, ejemplos: cantidad ? ejemplos : [] });
  fixture.detectChanges();
}

function pedido(cuerpo: string) {
  const req = http.expectOne({ method: 'PUT', url });
  expect(req.request.serializeBody()).toBe(cuerpo);
  return req;
}

const cuerpoAmbos = '{"margenDefectoPorcentaje":35.5,"redondeoMultiplo":0.5}';

describe.each<Modo>(['celular', 'tablet'])('Margen y redondeo: plata en %s', (modo) => {
  it.each([
    ['solo margen', '35.5', '0', '0', '{"margenDefectoPorcentaje":35.5,"redondeoMultiplo":0}'],
    ['solo redondeo', '30', '0,5', '0.5', '{"margenDefectoPorcentaje":30,"redondeoMultiplo":0.5}'],
    ['ambos', '35.5', '0,5', '0.5', cuerpoAmbos],
  ])('%s: envía el cuerpo exacto y sólo después de confirmar', async (_caso, margen, redondeo, param, cuerpo) => {
    await crear(modo);
    escribir('margen-defecto', margen);
    escribir('redondeo', redondeo);
    pulsar('Guardar');
    vistaPrevia(margen, param);
    sinGuardar();
    // Ni una edición posterior a la vista previa cambia lo que el Dueño está confirmando.
    escribir('margen-defecto', '99');
    pulsar('Guardar y recalcular', dialogo()!);
    pedido(cuerpo).flush({ ...JSON.parse(cuerpo), articulosRecalculados: 5, avisos: ['No se cambió el precio de A1.'] });
    fixture.detectChanges();
    expect(dialogo()).toBeNull();
    expect(texto(q('.alerta.exito'))).toBe('Se guardó la configuración. Se recalcularon 5 precios.');
    expect(texto(q('.avisos-salteados li'))).toBe('No se cambió el precio de A1.');
  });

  it('sin cambios guarda los mismos valores, una vez y sin vista previa ni confirmación', async () => {
    await crear(modo);
    pulsar('Guardar');
    sinVistaPrevia();
    expect(dialogo()).toBeNull();
    pedido('{"margenDefectoPorcentaje":30,"redondeoMultiplo":0}').flush({ ...guardada, articulosRecalculados: 0 });
    fixture.detectChanges();
    expect(texto(q('.alerta.exito'))).toBe('Se guardó la configuración.');
  });

  it('cantidad cero guarda directamente sin confirmar', async () => {
    await crear(modo);
    escribir('margen-defecto', '35');
    pulsar('Guardar');
    vistaPrevia('35', '0', 0);
    expect(dialogo()).toBeNull();
    pedido('{"margenDefectoPorcentaje":35,"redondeoMultiplo":0}').flush({ ...guardada, margenDefectoPorcentaje: 35, articulosRecalculados: 0 });
  });

  it('muestra el conteo y ambos ejemplos, Cancelar no guarda', async () => {
    await crear(modo);
    escribir('margen-defecto', '35');
    pulsar('Guardar');
    vistaPrevia('35', '0');
    const confirmacion = dialogo()!;
    const mensaje = texto(confirmacion);
    for (const literal of ['Se recalcula el precio de 4.142 artículos.', 'Por ejemplo', 'TOR-001', 'Tornillo 1/4',
      '$ 909.788,60', '$ 909.790,00', 'CLA-002', 'Clavo 2', '$ 1.200,00', '$ 1.210,50']) {
      expect(mensaje).toContain(literal);
    }
    pulsar('Cancelar', confirmacion);
    expect(dialogo()).toBeNull();
    expect(q<HTMLInputElement>('#margen-defecto')?.value).toBe('35');
    sinGuardar();
  });

  it.each([
    [409, { message: sinCotizacion }, sinCotizacion],
    [500, {}, 'No se pudo guardar la configuración.'],
  ])('un rechazo %s conserva los campos editados y muestra el error', async (status, error, mensaje) => {
    await crear(modo);
    escribir('margen-defecto', '35.5');
    escribir('redondeo', '0,5');
    pulsar('Guardar');
    vistaPrevia('35.5', '0.5');
    pulsar('Guardar y recalcular', dialogo()!);
    pedido(cuerpoAmbos).flush(error, { status, statusText: 'Error' });
    fixture.detectChanges();
    expect(dialogo()).toBeNull();
    expect(texto(q('.error[role="alert"]'))).toBe(mensaje);
    expect(q('.alerta.exito')).toBeNull();
    expect(q<HTMLInputElement>('#margen-defecto')?.value).toBe('35.5');
    expect(q<HTMLInputElement>('#redondeo')?.value).toBe('0,5');
    expect(boton('Guardar').disabled).toBe(false);
  });

  it('un doble toque no duplica la vista previa ni el guardado en vuelo', async () => {
    await crear(modo);
    escribir('margen-defecto', '35.5');
    escribir('redondeo', '0,5');
    const guardar = boton('Guardar');
    guardar.click();
    guardar.click();
    fixture.detectChanges();
    expect(guardar.disabled).toBe(true);
    vistaPrevia('35.5', '0.5');
    sinGuardar();
    const confirmar = boton('Guardar y recalcular', dialogo()!);
    confirmar.click();
    confirmar.click();
    fixture.detectChanges();
    expect(guardar.disabled).toBe(true);
    // Un submit durante el pedido tampoco debe iniciar otra vista previa.
    q('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    sinVistaPrevia();
    pedido(cuerpoAmbos).flush({ ...guardada, articulosRecalculados: 5 });
  });

  it('los motivos de validación dejan intacta la configuración y no hacen pedidos', async () => {
    await crear(modo);
    escribir('margen-defecto', '1000.01');
    escribir('redondeo', '');
    pulsar('Guardar');
    expect(texto(q('#margen-defecto-error'))).toBe('El margen no puede superar 1000 %.');
    expect(texto(q('#redondeo-error'))).toBe('El redondeo es obligatorio.');
    expect(dialogo()).toBeNull();
    sinVistaPrevia();
    sinGuardar();
  });

  it('el Vendedor no tiene formulario ni hace pedidos', async () => {
    await crear(modo, false);
    expect(texto(q('.estado-vacio'))).toBe('Solo el Dueño puede configurar el margen y el redondeo.');
    expect(q('form')).toBeNull();
    http.expectNone(() => true);
  });
});

describe('Margen y redondeo: estructura', () => {
  it('presenta una barra, una tarjeta con los dos campos y un solo Guardar en el pie fijo', async () => {
    await crear('celular');
    expect(texto(q('.ficha-barra h1'))).toBe('Margen y redondeo');
    expect(q('.ficha-barra .ficha-volver')?.getAttribute('aria-label')).toBe('Volver');
    expect(q('fieldset.seccion #margen-defecto')).not.toBeNull();
    expect(q('fieldset.seccion #redondeo')).not.toBeNull();
    const pie = q('.ficha-pie.en-pagina')!;
    expect(Array.from(pie.querySelectorAll('button'), texto)).toEqual(['Cancelar', 'Guardar']);
    expect(q('form')!.querySelectorAll('button[type="submit"]')).toHaveLength(1);
    escribir('redondeo', '100');
    expect(texto(q('#redondeo-ejemplo'))).toBe(
      'Con 100, un precio de $ 909.788,60 queda en $ 909.800,00. Siempre hacia arriba, así no perdés margen.',
    );
  });

  it.each(['Cancelar', 'Volver'])('%s vuelve a Artículos sin guardar ni pedir vista previa', async (salida) => {
    await crear('celular');
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    escribir('margen-defecto', '35');
    (salida === 'Volver' ? q<HTMLButtonElement>('.ficha-volver')! : boton('Cancelar')).click();
    expect(navegar).toHaveBeenCalledExactlyOnceWith('/articulos');
    sinVistaPrevia();
    sinGuardar();
  });

  it('usa sólo la hoja en celular, con cuenta, precios antes → ahora y foco de regreso a Guardar', async () => {
    await crear('celular');
    escribir('margen-defecto', '35');
    pulsar('Guardar');
    vistaPrevia('35', '0');
    await fixture.whenStable();
    const hoja = q('app-hoja-inferior [role="dialog"]')!;
    expect(texto(hoja?.querySelector('h2'))).toBe('Se recalcula el precio de 4.142 artículos.');
    expect(texto(hoja.querySelector('li'))).toContain('$ 909.788,60 → $ 909.790,00');
    expect(q('app-confirmacion')).toBeNull();
    expect(document.activeElement).toBe(hoja);
    hoja.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    fixture.detectChanges();
    expect(dialogo()).toBeNull();
    expect(document.activeElement).toBe(boton('Guardar'));
    sinGuardar();
  });

  it('explica el bloqueo del pie durante la vista previa y no permite salir ni repetir el pedido', async () => {
    await crear('celular');
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    escribir('margen-defecto', '35');
    pulsar('Guardar');
    const guardar = boton('Guardar');
    const motivo = texto(q(`#${guardar.getAttribute('aria-describedby')}`));
    const cancelar = q<HTMLButtonElement>('.pie-margen .secundario');
    const volver = q<HTMLButtonElement>('.ficha-volver');
    const estadosEnVuelo = [guardar.disabled, cancelar?.disabled, volver?.disabled];
    guardar.click();
    cancelar?.click();
    volver?.click();
    vistaPrevia('35', '0');
    expect(motivo).toBe('Hay una operación en curso.');
    expect(estadosEnVuelo).toEqual([true, true, true]);
    expect(navegar).not.toHaveBeenCalled();
    sinGuardar();
  });

  it('conserva la barra, el botón y el diálogo compartido en tablet', async () => {
    await crear('tablet');
    expect(texto(q('app-barra-acciones h1'))).toBe('Margen y redondeo');
    expect(q('.ficha-pie, .ficha-barra')).toBeNull();
    escribir('margen-defecto', '35');
    pulsar('Guardar');
    vistaPrevia('35', '0');
    expect(q('app-confirmacion [role="alertdialog"]')).not.toBeNull();
    expect(q('app-hoja-inferior')).toBeNull();
    sinGuardar();
  });
});
