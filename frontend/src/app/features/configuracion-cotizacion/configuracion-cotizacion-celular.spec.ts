import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { CotizacionService } from '../../core/cotizacion/cotizacion.service';
import { PaginaConfiguracionCotizacion } from './configuracion-cotizacion';

const url = '/api/configuracion-cotizacion';
const recalculo = '/api/cotizacion/recalculo';
const previa = `${recalculo}/vista-previa`;
const guardada = {
  activa: true, dolarPrecios: 'USD:oficial', ladoPrecios: 'VENTA', visibles: ['USD:oficial'],
  horaRecalculo: '08:00', umbralDesvio: 5, vigente: 1000, vigenteDesde: null, aConfirmar: null,
};
const articulos = [
  { id: 1, codigoInterno: 'TAL-1', descripcion: 'Taladro', activo: true, precioActual: 15730, precioNuevo: 17310, diferencia: 1580 },
  { id: 2, codigoInterno: 'TOR-1', descripcion: 'Tornillo', activo: false, precioActual: 100, precioNuevo: 90, diferencia: -10 },
  { id: 3, codigoInterno: 'CLA-1', descripcion: 'Clavo', activo: true, precioActual: 60, precioNuevo: 60, diferencia: 0 },
];
const motivoCambios = 'Guardá los cambios antes de recalcular.';
const motivoApagada = 'La cotización está apagada. Activala y guardá para poder recalcular.';
type Modo = 'celular' | 'tablet';
let fixture: ComponentFixture<PaginaConfiguracionCotizacion>;
let http: HttpTestingController;

function crear(modo: Modo, config: object = guardada) {
  vi.stubGlobal('matchMedia', (media: string) => ({
    matches: media.includes(modo === 'celular' ? '639.98px' : '1023.98px'), media,
  }));
  TestBed.configureTestingModule({
    imports: [PaginaConfiguracionCotizacion],
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: AuthService, useValue: { esDueno: () => true } }],
  });
  http = TestBed.inject(HttpTestingController);
  fixture = TestBed.createComponent(PaginaConfiguracionCotizacion);
  fixture.detectChanges();
  http.expectOne({ method: 'GET', url }).flush(config);
  fixture.detectChanges();
}

afterEach(() => {
  vi.unstubAllGlobals();
  http?.verify();
});

const q = <T extends HTMLElement = HTMLElement>(s: string) => (fixture.nativeElement as HTMLElement).querySelector<T>(s)!;
const texto = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim();
const sinRecalculo = () => http.expectNone((r) => r.url === recalculo || r.url === previa);
function pulsar(selector: string) {
  q(selector).click();
  fixture.detectChanges();
}
function editar(selector: string, valor?: string, evento = 'input') {
  if (valor === undefined) return pulsar(selector);
  const input = q<HTMLInputElement>(selector);
  input.value = valor;
  input.dispatchEvent(new Event(evento, { bubbles: true }));
  fixture.detectChanges();
}
function abrir(superaUmbral: boolean, filas = articulos, selector = '.recalcular') {
  pulsar(selector);
  http.expectOne({ method: 'GET', url: previa }).flush({
    dolar: superaUmbral ? 14.5 : 1100, vigente: 1000, superaUmbral, aviso: null, articulos: filas,
    articulosQueCambian: filas.filter((a) => a.diferencia !== 0).length, avisos: [],
  });
  fixture.detectChanges();
  http.expectNone((r) => r.url === recalculo);
}
function pedidoAplicar(urlExacta: string) {
  const req = http.expectOne({ method: 'POST', url: urlExacta });
  expect(req.request.serializeBody()).toBeNull();
  return req;
}

const original = '{"activa":true,"dolarPrecios":"USD:oficial","ladoPrecios":"VENTA","visibles":["USD:oficial"],"horaRecalculo":"08:00","umbralDesvio":5}';
const aviso = { nuevo: 14.5, vigente: 1000, desvioPorcentaje: 98.55, mensaje: 'Revisá el dólar antes de aplicar.' };

describe.each<Modo>(['celular', 'tablet'])('Cotización: plata en %s', (modo) => {
  it.each([
    ['#activa', undefined, 'true', 'false'],
    ['#dolarPrecios', 'USD:blue', '"dolarPrecios":"USD:oficial"', '"dolarPrecios":"USD:blue"', 'change'],
    ['#lado-COMPRA', undefined, '"VENTA"', '"COMPRA"'],
    ['#horaRecalculo', '9:15', '"08:00"', '"09:15"'],
    ['#umbralDesvio', '7.25', '"umbralDesvio":5', '"umbralDesvio":7.25'],
    ['input[value="USD:blue"]', undefined, '["USD:oficial"]', '["USD:oficial","USD:blue"]'],
  ])('guardar %s manda el cuerpo literal y no recalcula', (selector, valor, antes, despues, evento = 'input') => {
    crear(modo);
    editar(selector, valor, evento);
    pulsar('button[type="submit"]');
    const cuerpo = original.replace(antes, despues);
    const req = http.expectOne({ method: 'PUT', url });
    expect(req.request.serializeBody()).toBe(cuerpo);
    sinRecalculo();
    req.flush({ ...guardada, ...JSON.parse(cuerpo) });
    fixture.detectChanges();
    expect(texto(q('.alerta.exito'))).toBe('Se guardó la configuración.');
    sinRecalculo();
  });

  it('Guardar no sale de la pantalla: sólo guarda y se queda en la configuración', () => {
    crear(modo);
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    editar('#horaRecalculo', '9:15');
    pulsar('button[type="submit"]');
    http.expectOne({ method: 'PUT', url }).flush({ ...guardada, horaRecalculo: '09:15' });
    fixture.detectChanges();
    expect(navegar).not.toHaveBeenCalled();
  });

  if (modo === 'tablet') {
    it('en tablet el formulario no trae el Cancelar del pie ni las clases móviles', () => {
      crear(modo);
      expect(Array.from(q('.acciones-form').querySelectorAll('button')).map((b) => texto(b))).toEqual(['Guardar']);
      expect(q('form').classList.contains('cotizacion-celular')).toBe(false);
      expect(q('.contenido').classList.contains('contenido-cotizacion-celular')).toBe(false);
    });
  }

  it.each([
    [false, 1100, 'Aplicar', '?dolar=1100', '.recalcular'],
    [true, 14.5, 'Confirmar y aplicar', '?confirmaDesvio=true&dolar=14.5', '.confirmar-dolar'],
  ])('aplica la vista con superaUmbral=%s y dólar %s', (supera, dolar, titulo, query, selector) => {
    crear(modo, supera ? { ...guardada, aConfirmar: aviso } : guardada);
    const spy = vi.spyOn(TestBed.inject(CotizacionService), 'recalcular');
    abrir(supera, articulos, selector);
    expect(texto(q('.aplicar-recalculo'))).toBe(titulo);
    pulsar('.aplicar-recalculo');
    expect(spy).toHaveBeenCalledExactlyOnceWith(supera, dolar);
    pedidoAplicar(recalculo + query).flush({ cotizacion: dolar, articulosRecalculados: 2 });
    http.expectOne({ method: 'GET', url }).flush({ ...guardada, vigente: dolar });
    fixture.detectChanges();
    expect(q('.vista-previa')).toBeNull();
    expect(texto(q('.resultado'))).toBe(supera
      ? 'Se recalcularon 2 artículos con el dólar a 14,50.'
      : 'Se recalcularon 2 artículos con el dólar a 1.100,00.');
  });

  it('el switch apagado sin guardar y luego guardado bloquea las dos entradas con el motivo de hoy', () => {
    crear(modo, { ...guardada, aConfirmar: aviso });
    pulsar('#activa');
    for (const motivo of [motivoCambios, motivoApagada]) {
      expect(texto(q('#motivo-sin-recalculo'))).toBe(motivo);
      for (const selector of ['.recalcular', '.confirmar-dolar']) {
        expect(q(selector).getAttribute('aria-disabled')).toBe('true');
        pulsar(selector);
        sinRecalculo();
        expect(texto(q('.motivo-accion[role="status"]'))).toBe(motivo);
      }
      if (motivo === motivoCambios) {
        pulsar('button[type="submit"]');
        http.expectOne({ method: 'PUT', url }).flush({ ...guardada, activa: false, aConfirmar: aviso });
        fixture.detectChanges();
        sinRecalculo();
      }
    }
  });

  it('con artículos sin diferencia, muestra el vacío y conserva Aplicar', () => {
    crear(modo);
    abrir(false, [articulos[2]]);
    expect(texto(q('.sin-cambios'))).toBe('No cambia ningún precio.');
    expect(texto(q('.aplicar-recalculo'))).toBe('Aplicar');
  });

  it('el guardado rechazado conserva la edición y muestra el error', () => {
    crear(modo);
    editar('#horaRecalculo', '09:15');
    pulsar('button[type="submit"]');
    http.expectOne({ method: 'PUT', url }).flush(null, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(q<HTMLInputElement>('#horaRecalculo').value).toBe('09:15');
    expect(texto(q('[role="alert"]'))).toBe('No se pudo guardar la configuración.');
    expect(texto(q('#motivo-sin-recalculo'))).toBe(motivoCambios);
    expect(texto(q('.recalculo-texto'))).toContain('todos los días a las 08:00');
    sinRecalculo();
  });

  it('el recálculo rechazado cierra la previa y conserva la pantalla con el error', () => {
    crear(modo);
    abrir(true);
    pulsar('.aplicar-recalculo');
    pedidoAplicar(`${recalculo}?confirmaDesvio=true&dolar=14.5`).flush(null, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(q('.vista-previa')).toBeNull();
    expect(q('#horaRecalculo')).not.toBeNull();
    expect(texto(q('.recalculo [role="alert"]'))).toBe('No se pudieron recalcular los precios.');
  });

  it('dos toques a Confirmar y aplicar antes de renderizar sólo mandan un pedido', () => {
    crear(modo);
    abrir(true);
    const aplicar = q<HTMLButtonElement>('.aplicar-recalculo');
    expect(texto(aplicar)).toBe('Confirmar y aplicar');
    aplicar.click();
    aplicar.click();
    const req = pedidoAplicar(`${recalculo}?confirmaDesvio=true&dolar=14.5`);
    fixture.detectChanges();
    expect(aplicar.disabled).toBe(true);
    req.flush(null, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
  });
});


describe('Cotización: estructura celular', () => {
  it.each(['.ficha-volver', 'form .ficha-pie button.secundario'])('%s descarta y navega como Margen y redondeo', (selector) => {
    crear('celular');
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    expect(texto(q('.ficha-barra h1'))).toBe('Cotización');
    expect(q('.ficha-volver').getAttribute('aria-label')).toBe('Volver');
    editar('#horaRecalculo', '09:15');
    pulsar(selector);
    expect(navegar).toHaveBeenCalledExactlyOnceWith('/articulos');
    http.expectNone({ method: 'PUT', url });
    sinRecalculo();
  });

  it('viste los controles nativos sin cambiar sus etiquetas, ids ni nombres', () => {
    crear('celular');
    const activa = q<HTMLInputElement>('label[for="activa"] #activa');
    expect(q('form').classList.contains('cotizacion-celular')).toBe(true);
    expect(activa.type).toBe('checkbox');
    expect(texto(activa.labels![0])).toBe('Cotización activada');
    expect(activa.getAttribute('aria-describedby')).toBe('activa-pista');
    expect(Array.from(q('fieldset.lado').querySelectorAll('input')).map((e) => [e.id, e.getAttribute('name')]))
      .toEqual([['lado-VENTA', 'ladoPrecios'], ['lado-COMPRA', 'ladoPrecios']]);
    expect(document.activeElement?.tagName).not.toBe('INPUT');
  });

  it('muestra las mismas filas como tarjetas y conserva las acciones de la previa', () => {
    crear('celular');
    abrir(true);
    const tarjetas = Array.from(fixture.nativeElement.querySelectorAll('.cotizacion-tarjeta') as NodeListOf<HTMLElement>);
    expect(tarjetas.map((tarjeta) => Array.from(tarjeta.querySelectorAll('small, strong, s, b, .cotizacion-antes-ahora > span, .cotizacion-diferencia')).map(texto)))
      .toEqual([
        ['TAL-1', 'Taladro', '$ 15.730,00', '→', '$ 17.310,00', '+$ 1.580,00'],
        ['TOR-1', 'Tornillo (inactivo)', '$ 100,00', '→', '$ 90,00', '-$ 10,00'],
      ]);
    expect(q('.vista-previa table')).toBeNull();
    expect(Array.from(q('.vista-previa .ficha-pie.en-pagina').querySelectorAll('button')).map(texto))
      .toEqual(['Cancelar', 'Confirmar y aplicar']);
    pulsar('.cancelar-recalculo');
    expect(q('.vista-previa')).toBeNull();
    expect(Array.from(q('form .ficha-pie.en-pagina').querySelectorAll('button')).map(texto))
      .toEqual(['Cancelar', 'Guardar']);
    sinRecalculo();
  });

  it('conserva barra y tabla originales en tablet', () => {
    crear('tablet');
    abrir(true);
    expect(q('.ficha-barra, .ficha-pie, .cotizacion-tarjeta')).toBeNull();
    expect(texto(q('app-barra-acciones h1'))).toBe('Cotización');
    expect(q('.vista-previa table')).not.toBeNull();
  });
});
