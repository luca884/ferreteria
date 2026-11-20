import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { CompraResponse, CompraResumen } from './compra.models';
import { Compras } from './compras';
import { ComprasService } from './compras.service';

registerLocaleData(localeEsAr);

describe('Lista y detalle de Compras (T15b)', () => {
  const compra: CompraResumen = {
    id: 7, fecha: '2026-11-12', tipo: 'FA', comprobante: 'Factura A 00001-00004512',
    proveedorId: 4, proveedorNombre: 'Bulonera Sur', moneda: 'USD', neto: 2200, renglones: 2,
  };
  const tornillos = {
    renglon: 1, articuloId: 11, codigoInterno: 'TOR-1', descripcion: 'Tornillo 1/4', unidad: 'UNIDAD' as const,
    cantidad: 20, costoAnterior: 100, costoUnitario: 110, precioAnterior: 157.3, precioNuevo: 173.03, subtotal: 2200,
  };
  const detalle: CompraResponse = {
    ...compra, puntoVenta: 1, numero: 4512, registrada: '2026-11-12T11:00:00',
    recargos: [{ nombre: 'Embalaje y carga', porcentaje: 4.44 }],
    renglones: [tornillos, { ...tornillos, renglon: 2, articuloId: 12, codigoInterno: 'CLA-2', descripcion: 'Clavos',
      unidad: 'KILO', cantidad: 20.5, costoAnterior: 101, costoUnitario: 111, precioAnterior: 158, precioNuevo: 174 }],
  };
  const pagina = (items: CompraResumen[] = [compra], page = 0) => ({ items, page, size: 20, totalElements: 21, totalPages: 2 });
  const agotado = (status: number) => throwError(() => new HttpErrorResponse({ status }));
  const normal = (texto: string | null | undefined) => (texto ?? '').replace(/\s+/g, ' ').trim();

  let fixture: ComponentFixture<Compras>;
  let api: Record<'listar' | 'obtener', ReturnType<typeof vi.fn>>;
  let proveedores: { listar: ReturnType<typeof vi.fn> };
  const el = () => fixture.nativeElement as HTMLElement;
  const q = (selector: string) => el().querySelector<HTMLElement>(selector);
  const texto = (selector: string) => normal(q(selector)?.textContent);
  const dialogoDetalle = () => q(':is(app-dialogo-detalle, app-detalle-pantalla) [role="dialog"]');
  const comprobante = () => q('.compra-tarjeta, table.compras .comprobante button');

  async function crear(
    modo: 'celular' | 'tableta',
    opciones: { vendedor?: boolean; origen?: string; listaInicial?: Subject<ReturnType<typeof pagina>> } = {},
  ) {
    vi.stubGlobal('matchMedia', (consulta: string) => ({
      matches: consulta.includes(modo === 'celular' ? '639.98px' : '1023.98px'), media: consulta,
    }));
    api = { listar: vi.fn(() => of(pagina())), obtener: vi.fn(() => of(detalle)) };
    if (opciones.listaInicial) api.listar.mockReturnValueOnce(opciones.listaInicial);
    proveedores = { listar: vi.fn(() => of([{ id: 4, nombre: 'Bulonera Sur', activo: false }])) };
    await TestBed.configureTestingModule({
      imports: [Compras],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(opciones.origen ? { id: '7' } : {}) } } },
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ComprasService, useValue: api },
        { provide: ProveedoresService, useValue: proveedores },
        { provide: AuthService, useValue: { esDueno: () => !opciones.vendedor } },
      ],
    }).compileComponents();
    if (opciones.origen) {
      vi.spyOn(TestBed.inject(Router), 'currentNavigation').mockReturnValue({ extras: { state: { origen: opciones.origen } } } as never);
    }
    fixture = TestBed.createComponent(Compras);
    await refrescar();
  }

  afterEach(() => vi.unstubAllGlobals());

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }
  async function tocar(elemento: HTMLElement | null) {
    elemento!.click();
    await refrescar();
  }
  async function abrirFiltros() {
    if (!q('form.filtros')) await tocar(q('.abrir-filtros-compras'));
  }
  function escribir(nombre: string, valor: string) {
    const campo = q(`[name="${nombre}"]`) as HTMLInputElement | HTMLSelectElement;
    campo.value = valor;
    campo.dispatchEvent(new Event(campo instanceof HTMLSelectElement ? 'change' : 'input'));
  }
  async function buscar() {
    q('form.filtros')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await refrescar();
  }
  async function cerrarDetalle() {
    await tocar(dialogoDetalle()!.querySelector('button:is([aria-label="Cerrar"], [aria-label="Volver"])'));
  }
  async function escape() {
    (document.activeElement ?? document.body).dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    await refrescar();
  }

  describe.each(['celular', 'tableta'] as const)('paridad (%s)', (modo) => {
    it('pide por página y por cada filtro, incluye proveedores inactivos y permite limpiar', async () => {
      await crear(modo);
      expect(proveedores.listar).toHaveBeenCalledExactlyOnceWith(true);
      expect(api.listar).toHaveBeenCalledExactlyOnceWith({ page: 0 });
      expect(texto('nav.paginas span')).toBe('Página 1 de 2');
      expect(q('nav.paginas button:disabled')?.textContent?.trim()).toBe('Anterior');
      await abrirFiltros();
      expect(texto('select option[value="4"]')).toBe('Bulonera Sur');
      escribir('proveedor', '4');
      await buscar();
      await abrirFiltros();
      expect((q('select[name="proveedor"]') as HTMLSelectElement).value).toBe('4');
      escribir('desde', '2026-11-01');
      await buscar();
      await abrirFiltros();
      escribir('hasta', '2026-11-12');
      await buscar();
      api.listar.mockReturnValueOnce(of(pagina([compra], 1)));
      await tocar(q('nav.paginas button:last-child'));
      expect(texto('nav.paginas span')).toBe('Página 2 de 2');
      expect(q('nav.paginas button:disabled')?.textContent?.trim()).toBe('Siguiente');
      await abrirFiltros();
      await tocar(q('form.filtros button[type="button"]'));
      expect(api.listar.mock.calls).toEqual([
        [{ page: 0 }], [{ page: 0, proveedorId: 4 }], [{ page: 0, proveedorId: 4, desde: '2026-11-01' }],
        [{ page: 0, proveedorId: 4, desde: '2026-11-01', hasta: '2026-11-12' }],
        [{ page: 1, proveedorId: 4, desde: '2026-11-01', hasta: '2026-11-12' }], [{ page: 0 }],
      ]);
    });

    it('el rango al revés no pide nada y enfoca Hasta; corregido, busca', async () => {
      await crear(modo);
      await abrirFiltros();
      escribir('desde', '2026-11-12');
      escribir('hasta', '2026-11-01');
      await buscar();
      expect(api.listar).toHaveBeenCalledTimes(1);
      expect(texto('[role="alert"]')).toBe('La fecha «Hasta» no puede ser anterior a «Desde».');
      expect(document.activeElement).toBe(q('input[name="hasta"]'));
      escribir('hasta', '2026-11-12');
      await buscar();
      expect(api.listar).toHaveBeenLastCalledWith({ page: 0, desde: '2026-11-12', hasta: '2026-11-12' });
      expect(q('[role="alert"]')).toBeNull();
    });

    it('mantiene los textos de carga, error, reintento y ambos vacíos', async () => {
      const listaInicial = new Subject<ReturnType<typeof pagina>>();
      await crear(modo, { listaInicial });
      expect(texto('.estado-vacio')).toBe('Cargando…');
      listaInicial.next(pagina());
      listaInicial.complete();
      await refrescar();
      await abrirFiltros();
      const pendiente = new Subject<ReturnType<typeof pagina>>();
      api.listar.mockReturnValueOnce(pendiente);
      api.obtener.mockReturnValueOnce(new Subject<CompraResponse>());
      await buscar();
      await tocar(comprobante());
      expect(normal(dialogoDetalle()!.textContent)).toContain('Cargando…');
      await cerrarDetalle();
      pendiente.error(new Error('red'));
      await refrescar();
      expect(texto('[role="alert"] span')).toBe('No se pudieron cargar las compras.');
      expect(texto('button.reintentar')).toBe('Reintentar');
      api.listar.mockReturnValue(of(pagina([])));
      await tocar(q('button.reintentar'));
      expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
      expect(texto('[role="status"]')).toBe('Todavía no hay compras.');
      await abrirFiltros();
      escribir('hasta', '2026-11-12');
      await buscar();
      expect(texto('[role="status"]')).toBe('No hay compras con esos filtros.');
    });

    it('lista y detalle mantienen los mismos datos, importes, unidades y recargos', async () => {
      await crear(modo);
      const lista = normal(comprobante()!.closest('tr, .compra-tarjeta')?.textContent);
      for (const valor of ['Factura A 00001-00004512', 'Bulonera Sur', '12/11/2026', '2', '$ 2.200,00 (USD)']) {
        expect(lista).toContain(valor);
      }
      expect(texto('a[href="/compras/nueva"]')).toBe('Nueva compra');
      await tocar(comprobante());
      expect(api.obtener).toHaveBeenCalledExactlyOnceWith(7);
      const cuerpo = normal(dialogoDetalle()!.textContent);
      for (const valor of ['Factura A 00001-00004512', 'Bulonera Sur', '12/11/2026', 'Neto $ 2.200,00 (USD)',
        'Embalaje y carga 4,44 %', 'TOR-1 · Tornillo 1/4', '20 unidades', 'Costo antes', 'Costo de la compra',
        'Precio antes', 'Precio después', '$ 100,00', '$ 110,00', '$ 157,30', '$ 173,03', 'CLA-2 · Clavos',
        '20,5 kilos', '$ 101,00 / kg', '$ 111,00 / kg', '$ 158,00 / kg', '$ 174,00 / kg']) {
        expect(cuerpo).toContain(valor);
      }
    });

    it('mantiene ambos errores de detalle y hace un pedido por apertura', async () => {
      await crear(modo);
      api.obtener.mockReturnValueOnce(agotado(404)).mockReturnValueOnce(agotado(500));
      await tocar(comprobante());
      expect(texto('[role="dialog"] [role="alert"]')).toBe('No se encontró la compra.');
      await cerrarDetalle();
      await tocar(comprobante());
      expect(texto('[role="dialog"] [role="alert"]')).toBe('No se pudo cargar la compra.');
      await cerrarDetalle();
      expect(api.obtener.mock.calls).toEqual([[7], [7]]);
    });

    it('al cerrar una compra abierta desde el artículo vuelve a su ficha', async () => {
      await crear(modo, { origen: '/articulos/15' });
      const navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      expect(api.obtener).toHaveBeenCalledExactlyOnceWith(7);
      await cerrarDetalle();
      expect(navegar).toHaveBeenCalledExactlyOnceWith('/articulos/15');
    });

    it('Vendedor sólo ve el aviso y no dispara pedidos, aunque llegue por id', async () => {
      await crear(modo, { vendedor: true, origen: '/articulos/15' });
      expect(texto('[role="status"]')).toBe('Solo el Dueño puede ver las compras.');
      expect(q('form, nav.paginas, [role="dialog"], a[href="/compras/nueva"], .abrir-filtros-compras')).toBeNull();
      expect(api.listar).not.toHaveBeenCalled();
      expect(api.obtener).not.toHaveBeenCalled();
      expect(proveedores.listar).not.toHaveBeenCalled();
    });
  });

  describe('estructura sólo del celular', () => {
    it('cada compra es un botón tarjeta con comprobante, metadatos y neto; el paginador sigue a la lista', async () => {
      await crear('celular');
      expect(q('table.compras')).toBeNull();
      expect(texto('.compra-tarjeta .compra-comprobante')).toBe('Factura A 00001-00004512');
      expect(texto('.compra-tarjeta .compra-meta')).toBe('Bulonera Sur · 12/11/2026 · 2 renglones');
      expect(texto('.compra-tarjeta .compra-neto')).toBe('$ 2.200,00 (USD)');
      expect(q('button.compra-tarjeta')!.compareDocumentPosition(q('nav.paginas')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

      vi.stubGlobal('matchMedia', (consulta: string) => ({ matches: consulta.includes('1023.98px'), media: consulta }));
      window.dispatchEvent(new Event('resize'));
      await refrescar();
      expect(q('.compras-celular, .abrir-filtros-compras')).toBeNull();
      expect(texto('table.compras td.comprobante')).toBe('Factura A 00001-00004512');
      expect(api.listar).toHaveBeenCalledExactlyOnceWith({ page: 0 });
    });

    it('«Filtros» abre una sola hoja con los tres campos, deja el error junto a Hasta y Esc devuelve el foco', async () => {
      await crear('celular');
      expect(q('form.filtros')).toBeNull();
      const gatillo = q('.abrir-filtros-compras')!;
      gatillo.focus();
      await tocar(gatillo);
      expect(q('app-hoja-inferior [role="dialog"] form.filtros')).not.toBeNull();
      expect(el().querySelectorAll('form.filtros')).toHaveLength(1);
      expect(texto('app-hoja-inferior h2')).toBe('Filtros');
      escribir('desde', '2026-11-12');
      escribir('hasta', '2026-11-01');
      await buscar();
      expect(q('input[name="hasta"]')!.getAttribute('aria-describedby')).toBe(q('form.filtros [role="alert"]')!.id);
      await escape();
      expect(q('app-hoja-inferior')).toBeNull();
      expect(document.activeElement).toBe(gatillo);
    });

    it('el detalle usa la pantalla completa y Volver/Esc cierran con foco de vuelta a la compra', async () => {
      await crear('celular');
      const tarjeta = comprobante()!;
      tarjeta.focus();
      await tocar(tarjeta);
      expect(q('app-dialogo-detalle')).toBeNull();
      const volver = q('app-detalle-pantalla .ficha-barra button[aria-label="Volver"]')!;
      expect(document.activeElement).toBe(volver);
      expect(texto('app-detalle-pantalla h2')).toBe('Factura A 00001-00004512');
      await tocar(volver);
      expect(dialogoDetalle()).toBeNull();
      expect(document.activeElement).toBe(tarjeta);
      await tocar(tarjeta);
      await escape();
      expect(dialogoDetalle()).toBeNull();
      expect(document.activeElement).toBe(tarjeta);
    });

    it('encabeza el detalle y muestra una tarjeta desplegable independiente por renglón, no una tabla', async () => {
      await crear('celular');
      await tocar(comprobante());
      expect(texto('app-detalle-pantalla .compra-encabezado .datos')).toBe('Bulonera Sur · 12/11/2026 · Neto $ 2.200,00 (USD)');
      expect(dialogoDetalle()!.querySelector('table')).toBeNull();
      const renglones = [...el().querySelectorAll<HTMLDetailsElement>('details.compra-renglon')];
      expect(renglones.map((r) => r.open)).toEqual([true, false]);
      const costos = renglones[0].querySelector('.compra-costos')!;
      expect([...costos.querySelectorAll('dt')].map((dt) => normal(dt.textContent))).toEqual(['Costo antes', 'Costo de la compra']);
      expect([...costos.querySelectorAll('dd')].map((dd) => normal(dd.firstChild?.textContent))).toEqual(['$ 100,00', '$ 110,00']);
      expect(costos.querySelector('[aria-hidden="true"]')?.textContent).toBe('→');
      const ultimo = renglones[1].querySelector('summary')!;
      await tocar(ultimo);
      expect(renglones.map((r) => r.open)).toEqual([true, true]);
      const volver = q('app-detalle-pantalla .ficha-volver')!;
      volver.focus();
      volver.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }));
      expect(document.activeElement).toBe(ultimo);
      ultimo.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }));
      expect(document.activeElement).toBe(volver);
    });
  });
});
