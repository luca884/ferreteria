import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, ParamMap, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { Ventas } from './ventas';
import { FiscalConsultado, VentaConsultada, VentaResumen } from './ventas.models';
import { VentasService } from './ventas.service';
import { resumenDePrueba, ventaDePrueba } from './ventas.testing';
import { MARCA_SIMULADO, documentoB, documentoX, fiscalDe } from '../../shared/factura/factura.testing';
import { FiscalVenta, ResumenFiscal } from '../../shared/factura/factura.models';

registerLocaleData(localeEsAr);

describe('Ventas (consulta y reimpresión, G60)', () => {
  let fixture: ComponentFixture<Ventas>;
  let api: {
    listar: ReturnType<typeof vi.fn>;
    obtener: ReturnType<typeof vi.fn>;
    reintentar: ReturnType<typeof vi.fn>;
    dejarSinFactura: ReturnType<typeof vi.fn>;
    anular: ReturnType<typeof vi.fn>;
  };
  /** El `:id` de `/ventas/:id`: emitir otro es navegar a otra venta con la pantalla ya abierta. */
  let parametros: BehaviorSubject<ParamMap>;

  const pagina = (
    items: VentaResumen[],
    cambios: Partial<PageResponse<VentaResumen>> = {},
  ): PageResponse<VentaResumen> => ({
    items,
    page: 0,
    size: 20,
    totalElements: items.length,
    totalPages: 1,
    ...cambios,
  });

  async function crear(
    items: VentaResumen[] = [resumenDePrueba(5), resumenDePrueba(4)],
    rol = 'DUENO',
    queryParams: Record<string, string> = {},
    id: string | null = null,
  ) {
    parametros = new BehaviorSubject<ParamMap>(convertToParamMap(id === null ? {} : { id }));
    api = {
      listar: vi.fn(() => of(pagina(items))),
      obtener: vi.fn((id: number) => of(ventaDePrueba({ ventaId: id, numero: id / 10 }))),
      reintentar: vi.fn(() => of({})),
      dejarSinFactura: vi.fn(() => of({})),
      anular: vi.fn(() => of(ventaDePrueba({ ventaId: 140, numero: 14, tipo: 'NOTA_CREDITO' }))),
    };
    const sesion = signal({ userId: 7, nombre: 'Ana', email: 'a@x.com', rol, ferreteriaId: 3, ferreteriaNombre: 'Tornillo' });
    await TestBed.configureTestingModule({
      imports: [Ventas],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: VentasService, useValue: api },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly(), esDueno: () => rol === 'DUENO' } },
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) }, paramMap: parametros },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Ventas);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const filas = () => Array.from(el().querySelectorAll<HTMLElement>('table.ventas tbody tr'));
  const celdas = (fila: HTMLElement) => Array.from(fila.querySelectorAll('td')).map((c) => normal(c.textContent));
  /** El detalle de la venta elegida: el pop-up (detalle-en-popup). */
  const detalle = () => el().querySelector<HTMLElement>('app-dialogo-detalle [role="dialog"]');
  const escape = () =>
    (document.activeElement ?? document.body).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
  const boton = (texto: string, dentro: ParentNode = el()) =>
    Array.from(dentro.querySelectorAll<HTMLButtonElement>('button')).find((b) => normal(b.textContent) === texto);

  function campo(nombre: string, valor: string) {
    const input = el().querySelector<HTMLInputElement>(`[name="${nombre}"]`)!;
    input.value = valor;
    input.dispatchEvent(new Event('input'));
  }

  async function buscar() {
    el().querySelector<HTMLFormElement>('form.filtros')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await refrescar();
  }

  it('el Vendedor ve el aviso y no se pide nada', async () => {
    await crear(undefined, 'VENDEDOR');

    expect(normal(el().querySelector('[role="status"]')?.textContent)).toBe('La consulta de ventas es solo del dueño.');
    expect(api.listar).not.toHaveBeenCalled();
    expect(el().querySelector('form.filtros')).toBeNull();
  });

  it('lista las ventas con número, factura, fecha y hora, cliente, usuario y total; sin registro fiscal la factura va vacía', async () => {
    await crear([resumenDePrueba(5), resumenDePrueba(4, { clienteNombre: undefined, facturable: false })]);

    expect(api.listar).toHaveBeenCalledWith({ page: 0 });
    expect(filas()).toHaveLength(2);
    expect(celdas(filas()[0])).toEqual(['5', '', '22/10/2026 16:05', 'Cliente 5', 'Ana Vendedora', '$ 3.000,00']);
    expect(celdas(filas()[1])).toEqual([
      '4 No facturable',
      '',
      '22/10/2026 16:05',
      'Consumidor Final',
      'Ana Vendedora',
      '$ 3.000,00',
    ]);
  });

  it('la columna «Factura» dice la letra impresa y el número; el estado si no está autorizada y el ambiente si no es producción', async () => {
    await crear([
      resumenDePrueba(9, {
        fiscal: { letra: 'B', puntoVenta: 1, numero: 12, estado: 'AUTORIZADO', ambiente: 'PRODUCCION', validoComoFactura: true },
      }),
      resumenDePrueba(8, {
        fiscal: { letra: 'X', puntoVenta: 1, numero: 13, estado: 'AUTORIZADO', ambiente: 'SIMULADOR', validoComoFactura: false },
      }),
      resumenDePrueba(7, {
        fiscal: { letra: 'X', puntoVenta: 2, numero: null, estado: 'PENDIENTE', ambiente: 'PRODUCCION', validoComoFactura: false },
      }),
      resumenDePrueba(6, {
        fiscal: { letra: 'X', puntoVenta: 1, numero: 4, estado: 'RECHAZADO', ambiente: 'HOMOLOGACION', validoComoFactura: false },
      }),
    ]);

    expect(filas().map((f) => celdas(f)[1])).toEqual([
      'B 00001-00000012',
      'X 00001-00000013 Simulado',
      'X Pendiente',
      'X 00001-00000004 Rechazada · Homologación',
    ]);
    expect(normal(el().querySelector('table.ventas thead')?.textContent)).toContain('Factura');
  });

  it('el paginador va arriba de la tabla: no hay que bajar hasta el final para pasar de página', async () => {
    await crear();
    api.listar.mockReturnValue(of(pagina([resumenDePrueba(5)], { totalPages: 2 })));

    await buscar();

    const paginador = el().querySelector('nav.paginas')!;
    const tabla = el().querySelector('table.ventas')!;
    expect(paginador.compareDocumentPosition(tabla) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('filtra por número de factura con `numeroFiscal`; un formato que el servidor no acepta muestra su mensaje en los filtros', async () => {
    await crear();
    campo('numeroFiscal', ' 00001-00000012 ');
    api.listar.mockReturnValue(of(pagina([resumenDePrueba(5)], { totalPages: 2 })));

    await buscar();

    expect(api.listar).toHaveBeenLastCalledWith({ page: 0, numeroFiscal: '00001-00000012' });

    const mensaje = 'Escribí el número de la factura como 12 o 00001-00000012.';
    api.listar.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { status: 400, message: 'Datos inválidos', errors: { numeroFiscal: mensaje } },
          }),
      ),
    );
    campo('numeroFiscal', 'doce');
    await buscar();

    expect(normal(el().querySelector('[role="alert"]')?.textContent)).toBe(mensaje);
    expect(normal(el().textContent)).not.toContain('No se pudieron cargar las ventas.');
    expect(filas()).toHaveLength(1);

    // La lista sigue siendo la del filtro anterior: paginar no manda el número rechazado.
    api.listar.mockReturnValue(of(pagina([resumenDePrueba(5)], { totalPages: 2, page: 1 })));
    boton('Siguiente')!.click();
    await refrescar();
    expect(api.listar).toHaveBeenLastCalledWith({ page: 1, numeroFiscal: '00001-00000012' });
  });

  it('el detalle de una venta con factura sigue mostrando todo lo de CU-16 (pagos, quién cobró) y suma la línea fiscal; «Imprimir» imprime la factura', async () => {
    await crear();
    api.obtener.mockReturnValue(of(ventaDePrueba({ fiscal: fiscalDe(documentoB()), remitoNumero: 15 })));

    filas()[0].click();
    await refrescar();

    const d = normal(detalle()!.textContent);
    expect(detalle()!.querySelector('app-venta-hoja')).not.toBeNull();
    expect(d).toContain('Martillo');
    expect(d).toContain('Subtotal $ 3.000,00');
    expect(d).toContain('Total $ 2.970,00');
    expect(d).toContain('Crédito 1 pago $ 2.970,00');
    expect(d).toContain('Cobró');
    expect(d).toContain('Ana Vendedora');
    expect(d).toContain('Construcciones Pérez');
    expect(normal(detalle()!.querySelector('.linea-fiscal')?.textContent)).toBe('Factura B 00001-00000012 · Autorizada');

    boton('Imprimir', detalle()!)!.click();
    await refrescar();
    const hoja = el().querySelector('app-factura-imprimible article.factura-imprimible')!;
    expect(normal(hoja.querySelector('.numero-fiscal')?.textContent)).toBe('N.º 00001-00000012');
    expect(normal(hoja.querySelector('.remito')?.textContent)).toBe('Remito N.º 15');
    expect(el().querySelector('app-venta-imprimible')).toBeNull();

    boton('Cerrar')!.click();
    await refrescar();
    expect(el().querySelector('app-factura-imprimible')).toBeNull();
  });

  it('una venta con documento X: la línea fiscal dice «X» y la marca, y reimprime la X sin datos fiscales', async () => {
    await crear();
    api.obtener.mockReturnValue(
      of(ventaDePrueba({ fiscal: fiscalDe(documentoX(MARCA_SIMULADO), { ambiente: 'SIMULADOR', cae: '70417054367476' }) })),
    );

    filas()[0].click();
    await refrescar();
    expect(normal(detalle()!.querySelector('.linea-fiscal')?.textContent)).toBe('X · SIMULADO — sin validez fiscal');
    expect(normal(detalle()!.textContent)).not.toContain('70417054367476');
    boton('Imprimir', detalle()!)!.click();
    await refrescar();

    const hoja = normal(el().querySelector('article.factura-imprimible')?.textContent);
    expect(hoja).toContain('DOCUMENTO NO VALIDO COMO FACTURA');
    expect(hoja).toContain('SIMULADO — sin validez fiscal');
    expect(hoja).not.toContain('70417054367476');
  });

  it('una venta con el `fiscal` de la rama 3 pero sin `documento` se ve e imprime como hoy', async () => {
    await crear();
    const { documento: _documento, ...sinDocumento } = fiscalDe(documentoB());
    api.obtener.mockReturnValue(of(ventaDePrueba({ fiscal: sinDocumento as FiscalVenta })));

    filas()[0].click();
    await refrescar();

    expect(detalle()!.querySelector('app-venta-hoja')).not.toBeNull();
    expect(detalle()!.querySelector('.linea-fiscal')).toBeNull();
    boton('Imprimir', detalle()!)!.click();
    await refrescar();
    expect(el().querySelector('app-venta-imprimible article.venta-imprimible')).not.toBeNull();
    expect(el().querySelector('app-factura-imprimible')).toBeNull();
  });

  it('sin ventas lo dice', async () => {
    await crear([]);

    expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('Todavía no hay ventas.');
  });

  it('filtra por número, cliente y fechas; Limpiar vuelve a la lista completa', async () => {
    await crear();
    campo('numero', '15');
    campo('cliente', '  Pérez ');
    campo('desde', '2026-10-01');
    campo('hasta', '2026-10-22');
    api.listar.mockReturnValue(of(pagina([])));

    await buscar();

    expect(api.listar).toHaveBeenLastCalledWith({
      page: 0,
      numero: 15,
      cliente: 'Pérez',
      desde: '2026-10-01',
      hasta: '2026-10-22',
    });
    expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('No hay ventas con esos filtros.');

    boton('Limpiar')!.click();
    await refrescar();
    expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
  });

  it('un número que no es entero no se manda y avisa', async () => {
    await crear();
    campo('numero', '1,5');

    await buscar();

    expect(api.listar).toHaveBeenCalledTimes(1);
    expect(normal(el().querySelector('[role="alert"]')?.textContent)).toBe('El número tiene que ser un entero.');
  });

  it('pagina', async () => {
    await crear();
    api.listar.mockReturnValue(of(pagina([resumenDePrueba(5)], { totalPages: 3, page: 0 })));
    await buscar();

    boton('Siguiente')!.click();
    await refrescar();

    expect(api.listar).toHaveBeenLastCalledWith({ page: 1 });
  });

  it('si la lista no carga lo avisa y deja reintentar', async () => {
    await crear();
    api.listar.mockReturnValue(throwError(() => new Error('caída')));
    await buscar();

    expect(normal(el().querySelector('[role="alert"]')?.textContent)).toContain('No se pudieron cargar las ventas.');
    api.listar.mockReturnValue(of(pagina([resumenDePrueba(5)])));
    boton('Reintentar')!.click();
    await refrescar();
    expect(filas()).toHaveLength(1);
  });

  it('al elegir una venta muestra el detalle con renglones, totales, pagos y quién la cobró', async () => {
    await crear();

    filas()[0].click();
    await refrescar();

    expect(api.obtener).toHaveBeenCalledWith(50);
    const d = normal(detalle()!.textContent);
    expect(d).toContain('Venta N.º 5');
    expect(d).toContain('Martillo');
    expect(d).toContain('Subtotal $ 3.000,00');
    expect(d).toContain('Descuento (10 %) −$ 300,00');
    expect(d).toContain('Recargo $ 270,00');
    expect(d).toContain('Total $ 2.970,00');
    expect(d).toContain('Crédito 1 pago $ 2.970,00');
    expect(d).toContain('Ana Vendedora');
  });

  it('el detalle se abre en un pop-up encima de la lista, con el título arriba y el foco en la ✕ (#78)', async () => {
    await crear();

    filas()[0].click();
    await refrescar();

    const pop = detalle()!;
    expect(pop.getAttribute('aria-modal')).toBe('true');
    expect(pop.closest('.dialogo-fondo')).not.toBeNull();
    expect(normal(pop.querySelector('.dialogo-detalle-cabecera h2')?.textContent)).toBe('Venta N.º 5');
    expect(pop.querySelector('.dialogo-detalle-cuerpo app-venta-hoja')).not.toBeNull();
    expect(filas()).toHaveLength(2);
    expect(document.activeElement).toBe(pop.querySelector('button[aria-label="Cerrar"]'));
  });

  it('mientras el detalle carga, el pop-up ya se titula con la venta de la fila', async () => {
    await crear();
    api.obtener.mockReturnValue(new Subject<VentaConsultada>());

    filas()[1].click();
    await refrescar();

    expect(normal(detalle()!.querySelector('h2')?.textContent)).toBe('Venta N.º 4');
    expect(normal(detalle()!.querySelector('.dialogo-detalle-cuerpo')?.textContent)).toBe('Cargando…');
  });

  it('Esc y la ✕ cierran el pop-up; la lista queda como estaba', async () => {
    await crear();
    filas()[0].click();
    await refrescar();

    escape();
    await refrescar();
    expect(detalle()).toBeNull();
    expect(filas()[0].classList).not.toContain('elegida');

    filas()[1].click();
    await refrescar();
    detalle()!.querySelector<HTMLButtonElement>('button[aria-label="Cerrar"]')!.click();
    await refrescar();
    expect(detalle()).toBeNull();
    expect(filas()).toHaveLength(2);
    expect(api.listar).toHaveBeenCalledTimes(1);
  });

  it('con Anular abierto encima, Esc cierra solo ese y el detalle sigue abierto', async () => {
    await crear();
    filas()[0].click();
    await refrescar();
    boton('Anular', detalle()!)!.click();
    await refrescar();
    expect(el().querySelector('app-dialogo-anular')).not.toBeNull();

    escape();
    await refrescar();

    expect(el().querySelector('app-dialogo-anular')).toBeNull();
    expect(detalle()).not.toBeNull();
  });

  it('con la anulación en vuelo y el foco caído al body (el botón se deshabilitó), Esc no cierra el pop-up ni el diálogo de Anular', async () => {
    await crear();
    api.anular.mockReturnValue(new Subject<VentaConsultada>());
    api.obtener.mockReturnValue(
      of(ventaDePrueba({ ventaId: 50, numero: 5, fiscal: { ...fiscalDe(documentoB()), acciones: [], reimprimir: false }, anulacion: null })),
    );
    filas()[0].click();
    await refrescar();
    boton('Anular', detalle()!)!.click();
    await refrescar();
    const confirmar = el().querySelector<HTMLButtonElement>('app-dialogo-anular [role="alertdialog"] button.anular')!;
    confirmar.focus();
    confirmar.click();
    await refrescar();
    expect(api.anular).toHaveBeenCalled();

    // Chrome saca el foco del botón que se deshabilita y lo deja en el body; jsdom no, así que se hace a mano.
    (document.activeElement as HTMLElement | null)?.blur();
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    await refrescar();

    expect(el().querySelector('app-dialogo-anular')).not.toBeNull();
    expect(detalle()).not.toBeNull();
  });

  it('con descuento por renglón el total no muestra un porcentaje general que no es el del importe', async () => {
    await crear();
    const base = ventaDePrueba();
    api.obtener.mockReturnValue(
      of({ ...base, descuentoPorcentaje: 0, renglones: [{ ...base.renglones[0], descuentoPorcentaje: 10 }] }),
    );

    filas()[0].click();
    await refrescar();

    const totales = normal(detalle()!.querySelector('.totales')?.textContent);
    expect(totales).toContain('Descuento −$ 300,00');
    expect(totales).not.toContain('(0 %)');
  });

  it('con descuento general y por renglón el total no muestra el porcentaje general', async () => {
    await crear();
    const base = ventaDePrueba();
    api.obtener.mockReturnValue(of({ ...base, renglones: [{ ...base.renglones[0], descuentoPorcentaje: 5 }] }));

    filas()[0].click();
    await refrescar();

    const totales = normal(detalle()!.querySelector('.totales')?.textContent);
    expect(totales).toContain('Descuento −$ 300,00');
    expect(totales).not.toContain('(10 %)');
  });

  it('si el filtro deja afuera la venta elegida, cierra su detalle', async () => {
    await crear();
    filas()[0].click();
    await refrescar();
    expect(detalle()).not.toBeNull();

    api.listar.mockReturnValue(of(pagina([resumenDePrueba(4)])));
    campo('numero', '4');
    await buscar();

    expect(detalle()).toBeNull();
  });

  it('si la venta elegida sigue en la lista nueva, deja su detalle abierto', async () => {
    await crear();
    filas()[0].click();
    await refrescar();

    api.listar.mockReturnValue(of(pagina([resumenDePrueba(5)])));
    campo('numero', '5');
    await buscar();

    expect(normal(detalle()?.textContent)).toContain('Venta N.º 5');
  });

  it('el detalle dice si salió de un presupuesto o de un remito', async () => {
    await crear();
    api.obtener.mockReturnValue(of(ventaDePrueba({ remitoId: 4 })));

    filas()[0].click();
    await refrescar();

    expect(normal(detalle()!.textContent)).toContain('Salió de un remito');
    // En el detalle, el origen es un link al pop-up del remito (informacion-y-links, S2).
    api.obtener.mockReturnValue(of(ventaDePrueba({ remitoId: 4, remitoNumero: 2 })));
    filas()[1].click();
    await refrescar();
    expect(detalle()!.querySelector('.notas a')!.getAttribute('href')).toBe('/remitos/4');
  });

  it('el detalle ofrece Imprimir y, aparte, Anular (CU-08), sin atajo de teclado; nunca Borrar (GDS-15)', async () => {
    await crear();
    filas()[0].click();
    await refrescar();

    const acciones = Array.from(detalle()!.querySelectorAll('button')).map((b) => normal(b.textContent));
    expect(acciones).toEqual(['✕', 'Anular', 'Cerrar', 'Imprimir']);
    // Al pie: Anular solo a la izquierda; a la derecha Cerrar e Imprimir como principal.
    const anular = boton('Anular', detalle()!)!;
    expect(anular.closest('.pie-peligro')).not.toBeNull();
    expect(anular.closest('.pie-acciones')).toBeNull();
    expect(anular.classList).toContain('peligro');
    const imprimir = boton('Imprimir', detalle()!)!;
    expect(imprimir.closest('.pie-acciones')).not.toBeNull();
    expect(imprimir.classList).toContain('primario');
    expect(anular.getAttribute('aria-keyshortcuts')).toBeNull();
    expect(anular.getAttribute('data-tecla')).toBeNull();
  });

  it('Imprimir abre la venta imprimible y Cerrar la cierra', async () => {
    await crear();
    filas()[0].click();
    await refrescar();

    boton('Imprimir', detalle()!)!.click();
    await refrescar();
    expect(el().querySelector('app-venta-imprimible article.venta-imprimible')).not.toBeNull();
    expect(el().querySelector('app-factura-imprimible')).toBeNull();

    boton('Cerrar')!.click();
    await refrescar();
    expect(el().querySelector('app-venta-imprimible')).toBeNull();
  });

  it('si el detalle no carga lo avisa', async () => {
    await crear();
    api.obtener.mockReturnValue(throwError(() => new Error('caída')));

    filas()[0].click();
    await refrescar();

    expect(normal(detalle()!.querySelector('[role="alert"]')?.textContent)).toBe('No se pudo cargar la venta.');
  });

  it('con un id desconocido o de otra ferretería (404) dice que no se encontró la venta (QA 05/11)', async () => {
    await crear();
    api.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));

    filas()[0].click();
    await refrescar();

    expect(normal(detalle()!.querySelector('[role="alert"]')?.textContent)).toBe('No se encontró la venta.');
  });

  describe('bandeja de pendientes y rechazadas (CU-16 punto 7, C1, C2, C3)', () => {
    const MARCA_RECHAZADA = 'SIN FACTURA — ARCA no la autorizó';
    const MENSAJE_FECHA = 'La fecha de la venta ya no entra en ARCA. Confirmá que la factura salga con la fecha de hoy.';
    const ERROR_10015 = {
      codigo: 10015,
      mensaje: 'El campo DocNro es inválido.',
      explicacion: 'El documento del cliente no es válido para ARCA: corregilo en Clientes y reintentá.',
    };

    /** El `fiscal` de una venta rechazada por ARCA, con las dos acciones (C1). */
    const rechazada = (cambios: Partial<FiscalConsultado> = {}): FiscalConsultado => ({
      ...fiscalDe(documentoX(MARCA_RECHAZADA)),
      estado: 'RECHAZADO',
      numero: null,
      errores: [ERROR_10015],
      observaciones: [],
      motivoSinFactura: null,
      acciones: ['REINTENTAR', 'DEJAR_SIN_FACTURA'],
      fechaNuevaRequerida: false,
      reimprimir: false,
      ...cambios,
    });

    const error = (status: number, cuerpo: Record<string, unknown>) =>
      throwError(() => new HttpErrorResponse({ status, error: { status, ...cuerpo } }));

    /** Abre el detalle de la primera fila con el `fiscal` dado. */
    async function abrirCon(fiscal: FiscalConsultado) {
      await crear();
      api.obtener.mockReturnValue(of(ventaDePrueba({ ventaId: 50, numero: 5, fiscal })));
      filas()[0].click();
      await refrescar();
    }

    const confirmacion = () => el().querySelector<HTMLElement>('app-confirmacion [role="alertdialog"]');
    const dialogoMotivo = () => el().querySelector<HTMLElement>('app-dialogo-sin-factura [role="dialog"]');

    it('el filtro «Facturas» manda `fiscal` (Pendientes o Rechazadas) con los otros; Limpiar lo saca', async () => {
      await crear();
      const select = el().querySelector<HTMLSelectElement>('select[name="fiscal"]')!;
      expect(Array.from(select.options).map((o) => [o.value, normal(o.textContent)])).toEqual([
        ['', 'Todas'],
        ['PENDIENTES', 'Pendientes'],
        ['RECHAZADAS', 'Rechazadas'],
      ]);
      select.value = 'RECHAZADAS';
      select.dispatchEvent(new Event('change'));
      campo('cliente', 'Pérez');

      await buscar();
      expect(api.listar).toHaveBeenLastCalledWith({ page: 0, cliente: 'Pérez', fiscal: 'RECHAZADAS' });

      select.value = 'PENDIENTES';
      select.dispatchEvent(new Event('change'));
      await buscar();
      expect(api.listar).toHaveBeenLastCalledWith({ page: 0, cliente: 'Pérez', fiscal: 'PENDIENTES' });

      boton('Limpiar')!.click();
      await refrescar();
      expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
      expect(select.value).toBe('');
    });

    it('el enlace del mostrador (`?fiscal=RECHAZADAS`) abre la bandeja ya filtrada; otro valor se ignora', async () => {
      await crear(undefined, 'DUENO', { fiscal: 'RECHAZADAS' });
      expect(api.listar).toHaveBeenCalledWith({ page: 0, fiscal: 'RECHAZADAS' });
      expect(el().querySelector<HTMLSelectElement>('select[name="fiscal"]')!.value).toBe('RECHAZADAS');

      TestBed.resetTestingModule();
      await crear(undefined, 'DUENO', { fiscal: 'TODAS' });
      expect(api.listar).toHaveBeenCalledWith({ page: 0 });
    });

    it('la lista dice el estado fiscal, «En revisión» para las alertas, «Sin factura» y la marca «Reimprimir factura»', async () => {
      const resumen = (estado: ResumenFiscal['estado'], cambios: Partial<ResumenFiscal> = {}): ResumenFiscal => ({
        letra: 'X',
        puntoVenta: 1,
        numero: null,
        estado,
        ambiente: 'PRODUCCION',
        validoComoFactura: false,
        reimprimir: false,
        ...cambios,
      });
      await crear([
        resumenDePrueba(9, {
          fiscal: resumen('AUTORIZADO', { letra: 'B', numero: 45, validoComoFactura: true, reimprimir: true }),
        }),
        resumenDePrueba(8, { fiscal: resumen('CAE_NO_COINCIDE') }),
        resumenDePrueba(7, { fiscal: resumen('SERIE_DESINCRONIZADA') }),
        resumenDePrueba(6, { fiscal: resumen('SIN_FACTURA') }),
        resumenDePrueba(5, { fiscal: resumen('FUERA_DE_FECHA') }),
      ]);

      expect(filas().map((f) => celdas(f)[1])).toEqual([
        'B 00001-00000045 Reimprimir factura',
        'X En revisión',
        'X En revisión',
        'X Sin factura',
        'X Fuera de fecha',
      ]);
    });

    it('el detalle muestra cada error de ARCA con su código, el texto tal cual y la explicación si la hay', async () => {
      await abrirCon(
        rechazada({
          errores: [ERROR_10015, { codigo: 10242, mensaje: 'Campo Condicion Frente al IVA del receptor', explicacion: null }],
        }),
      );

      expect(normal(detalle()!.querySelector('.estado-factura')?.textContent)).toBe('Estado de la factura: Rechazada');
      const errores = Array.from(detalle()!.querySelectorAll('.errores-arca li'));
      expect(errores.map((e) => normal(e.querySelector('.codigo')?.textContent))).toEqual(['10015', '10242']);
      expect(errores.map((e) => normal(e.querySelector('.mensaje')?.textContent))).toEqual([
        'El campo DocNro es inválido.',
        'Campo Condicion Frente al IVA del receptor',
      ]);
      expect(normal(errores[0].querySelector('.explicacion')?.textContent)).toBe(ERROR_10015.explicacion);
      expect(errores[1].querySelector('.explicacion')).toBeNull();
      expect(normal(detalle()!.querySelector('.errores-arca h3')?.textContent)).toBe('Errores de ARCA');
    });

    it('una fuera de fecha sin errores de ARCA dice el motivo que manda el servidor; sin motivo, no muestra nada', async () => {
      const motivo =
        'ARCA ya no acepta la fecha de esta venta (28/10/2026): admite hasta 5 días antes de hoy. ' +
        'Reintentá con la fecha de hoy o dejala sin factura.';
      await abrirCon(rechazada({ estado: 'FUERA_DE_FECHA', errores: [], motivoFueraDeFecha: motivo }));

      expect(normal(detalle()!.querySelector('.estado-factura')?.textContent)).toBe('Estado de la factura: Fuera de fecha');
      expect(normal(detalle()!.querySelector('.motivo-fuera-de-fecha')?.textContent)).toBe(motivo);
      expect(detalle()!.querySelector('.errores-arca')).toBeNull();

      TestBed.resetTestingModule();
      await abrirCon(rechazada({ motivoFueraDeFecha: null }));
      expect(detalle()!.querySelector('.motivo-fuera-de-fecha')).toBeNull();
    });

    it('una autorizada muestra las observaciones de ARCA tal cual, sin acciones', async () => {
      await abrirCon({
        ...fiscalDe(documentoB()),
        errores: [],
        observaciones: [{ codigo: 10217, mensaje: 'El credito fiscal discriminado no podrá ser computado.' }],
        motivoSinFactura: null,
        acciones: [],
        fechaNuevaRequerida: false,
        reimprimir: false,
      });

      expect(detalle()!.querySelector('.estado-factura')).toBeNull();
      const observaciones = detalle()!.querySelector('.observaciones-arca')!;
      expect(normal(observaciones.querySelector('h3')?.textContent)).toBe('Observaciones de ARCA');
      expect(Array.from(observaciones.querySelectorAll('li')).map((li) => normal(li.textContent))).toEqual([
        '10217 El credito fiscal discriminado no podrá ser computado.',
      ]);
      expect(boton('Reintentar', detalle()!)).toBeUndefined();
      expect(boton('Dejar sin factura', detalle()!)).toBeUndefined();
    });

    it('«Sin factura» dice el motivo, quién y cuándo, y no ofrece acciones', async () => {
      await abrirCon(
        rechazada({
          estado: 'SIN_FACTURA',
          motivoSinFactura: 'El cliente no quiso factura',
          sinFacturaPor: 'Dueño de Prueba',
          sinFacturaEn: '2026-10-29T14:05:00',
          acciones: [],
        }),
      );

      expect(normal(detalle()!.querySelector('.estado-factura')?.textContent)).toBe('Estado de la factura: Sin factura');
      expect(normal(detalle()!.querySelector('.motivo-sin-factura')?.textContent)).toBe(
        'Motivo: El cliente no quiso factura · Dueño de Prueba, 29/10/2026 14:05',
      );
      expect(boton('Reintentar', detalle()!)).toBeUndefined();
      expect(boton('Dejar sin factura', detalle()!)).toBeUndefined();
    });

    it('«En revisión» (S13): explica que lo revisa soporte y no ofrece acciones', async () => {
      for (const estado of ['CAE_NO_COINCIDE', 'SERIE_DESINCRONIZADA'] as const) {
        TestBed.resetTestingModule();
        await abrirCon(rechazada({ estado, errores: [], acciones: [] }));

        expect(normal(detalle()!.querySelector('.estado-factura')?.textContent), estado).toBe(
          'Estado de la factura: En revisión',
        );
        expect(normal(detalle()!.querySelector('.en-revision')?.textContent)).toBe(
          'Hay una diferencia con ARCA que tiene que revisar soporte.',
        );
        expect(boton('Reintentar', detalle()!)).toBeUndefined();
        expect(boton('Dejar sin factura', detalle()!)).toBeUndefined();
      }
    });

    it('solo aparecen las acciones que vienen en `acciones`', async () => {
      await abrirCon(rechazada({ acciones: ['DEJAR_SIN_FACTURA'] }));

      expect(boton('Reintentar', detalle()!)).toBeUndefined();
      expect(boton('Dejar sin factura', detalle()!)).toBeDefined();
    });

    it('«Reintentar» y «Dejar sin factura» siguen en el cuerpo del pop-up, con lo de ARCA, y no en el pie', async () => {
      await abrirCon(rechazada());

      const cuerpo = detalle()!.querySelector('.dialogo-detalle-cuerpo')!;
      expect(boton('Reintentar', cuerpo)).toBeDefined();
      expect(boton('Dejar sin factura', cuerpo)).toBeDefined();
      expect(cuerpo.querySelector('.errores-arca')).not.toBeNull();
    });

    it('«Reintentar» manda `fechaDeHoy: false`, dice «Autorizando con ARCA…» mientras espera y después refresca el detalle y la lista', async () => {
      await abrirCon(rechazada());
      const respuesta = new Subject<unknown>();
      api.reintentar.mockReturnValue(respuesta);
      api.listar.mockClear();
      api.obtener.mockClear();

      boton('Reintentar', detalle()!)!.click();
      await refrescar();

      expect(api.reintentar).toHaveBeenCalledWith(50, false);
      expect(normal(detalle()!.querySelector('[role="status"]')?.textContent)).toBe('Autorizando con ARCA…');
      expect(boton('Reintentar', detalle()!)!.disabled).toBe(true);
      expect(boton('Dejar sin factura', detalle()!)!.disabled).toBe(true);

      api.obtener.mockReturnValue(of(ventaDePrueba({ ventaId: 50, numero: 5, fiscal: fiscalDe(documentoB()) })));
      respuesta.next({});
      respuesta.complete();
      await refrescar();

      expect(api.listar).toHaveBeenCalledWith({ page: 0 });
      expect(api.obtener).toHaveBeenCalledWith(50);
      expect(normal(detalle()!.querySelector('.linea-fiscal')?.textContent)).toBe('Factura B 00001-00000012 · Autorizada');
      expect(boton('Reintentar', detalle()!)).toBeUndefined();
      expect(normal(el().querySelector('.resultado-accion')?.textContent)).toBe('Se reintentó la factura de la venta N.º 5.');
      // Con el pop-up abierto el mensaje va adentro: el de la lista queda tapado por el velo.
      expect(detalle()!.querySelector('.dialogo-detalle-cuerpo .resultado-accion')).not.toBeNull();
      expect(el().querySelectorAll('.resultado-accion')).toHaveLength(1);
    });

    it('con `fechaNuevaRequerida` pide confirmar la fecha de hoy antes de mandar nada; Cancelar no manda', async () => {
      await abrirCon(rechazada({ estado: 'FUERA_DE_FECHA', fechaNuevaRequerida: true }));

      boton('Reintentar', detalle()!)!.click();
      await refrescar();

      expect(api.reintentar).not.toHaveBeenCalled();
      expect(normal(confirmacion()!.querySelector('h2')?.textContent)).toBe('¿Facturar con la fecha de hoy?');
      expect(normal(confirmacion()!.querySelector('p')?.textContent)).toBe(MENSAJE_FECHA);
      expect(document.activeElement).toBe(boton('Cancelar', confirmacion()!));

      boton('Cancelar', confirmacion()!)!.click();
      await refrescar();
      expect(confirmacion()).toBeNull();
      expect(api.reintentar).not.toHaveBeenCalled();

      boton('Reintentar', detalle()!)!.click();
      await refrescar();
      boton('Facturar con la fecha de hoy', confirmacion()!)!.click();
      await refrescar();

      expect(api.reintentar).toHaveBeenCalledTimes(1);
      expect(api.reintentar).toHaveBeenCalledWith(50, true);
      expect(confirmacion()).toBeNull();
    });

    it('ante el 409 FECHA_NUEVA_REQUERIDA pide la confirmación con el texto del servidor y reintenta con la fecha de hoy', async () => {
      await abrirCon(rechazada());
      api.reintentar.mockReturnValueOnce(error(409, { message: MENSAJE_FECHA, motivo: 'FECHA_NUEVA_REQUERIDA' }));

      boton('Reintentar', detalle()!)!.click();
      await refrescar();

      expect(normal(confirmacion()!.querySelector('p')?.textContent)).toBe(MENSAJE_FECHA);
      expect(detalle()!.querySelector('[role="alert"]')).toBeNull();

      boton('Facturar con la fecha de hoy', confirmacion()!)!.click();
      await refrescar();

      expect(api.reintentar.mock.calls).toEqual([
        [50, false],
        [50, true],
      ]);
    });

    it('los otros 409 y el 422 de «Reintentar» se muestran tal cual al lado de la acción, sin refrescar', async () => {
      const casos: [number, string, string][] = [
        [409, 'FACTURACION_FRENADA', 'La facturación está frenada: falta el certificado.'],
        [409, 'SIN_FACTURA_RECHAZADA', 'Esta venta no tiene una factura rechazada para reintentar.'],
        [422, 'CLIENTE_SIN_DATOS_FISCALES', 'Al cliente le falta el CUIT para facturarle.'],
      ];
      await abrirCon(rechazada());
      api.listar.mockClear();
      api.obtener.mockClear();

      for (const [status, motivo, mensaje] of casos) {
        api.reintentar.mockReturnValueOnce(error(status, { message: mensaje, motivo, articulos: [] }));
        boton('Reintentar', detalle()!)!.click();
        await refrescar();

        const alerta = detalle()!.querySelector('.acciones-factura .error-accion');
        expect(normal(alerta?.textContent), motivo).toBe(mensaje);
        expect(alerta?.getAttribute('role')).toBe('alert');
        expect(confirmacion()).toBeNull();
      }
      expect(api.listar).not.toHaveBeenCalled();
      expect(api.obtener).not.toHaveBeenCalled();
    });

    it('con «Dejar sin factura» en vuelo y el foco caído al body (el botón se deshabilitó), Esc no cierra el pop-up ni el diálogo', async () => {
      await abrirCon(rechazada());
      api.dejarSinFactura.mockReturnValue(new Subject<unknown>());
      boton('Dejar sin factura', detalle()!)!.click();
      await refrescar();
      const motivo = dialogoMotivo()!.querySelector<HTMLInputElement>('input')!;
      motivo.value = 'El cliente no quiso factura';
      motivo.dispatchEvent(new Event('input'));
      boton('Dejar sin factura', dialogoMotivo()!)!.click();
      await refrescar();
      expect(api.dejarSinFactura).toHaveBeenCalled();

      // Chrome saca el foco del botón que se deshabilita y lo deja en el body; jsdom no, así que se hace a mano.
      (document.activeElement as HTMLElement | null)?.blur();
      document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
      await refrescar();

      expect(dialogoMotivo()).not.toBeNull();
      expect(detalle()).not.toBeNull();
    });

    it('«Dejar sin factura» abre el diálogo del motivo, lo manda y después refresca el detalle y la lista', async () => {
      await abrirCon(rechazada());
      api.listar.mockClear();

      boton('Dejar sin factura', detalle()!)!.click();
      await refrescar();

      expect(normal(dialogoMotivo()!.querySelector('h2')?.textContent)).toBe('Dejar sin factura la venta N.º 5');
      const motivo = dialogoMotivo()!.querySelector<HTMLInputElement>('input')!;
      motivo.value = '  El cliente no quiso factura ';
      motivo.dispatchEvent(new Event('input'));
      const sinFactura = rechazada({ estado: 'SIN_FACTURA', motivoSinFactura: 'El cliente no quiso factura', acciones: [] });
      api.obtener.mockReturnValue(of(ventaDePrueba({ ventaId: 50, numero: 5, fiscal: sinFactura })));
      boton('Dejar sin factura', dialogoMotivo()!)!.click();
      await refrescar();

      expect(api.dejarSinFactura).toHaveBeenCalledWith(50, 'El cliente no quiso factura');
      expect(dialogoMotivo()).toBeNull();
      expect(api.listar).toHaveBeenCalledWith({ page: 0 });
      expect(normal(detalle()!.querySelector('.motivo-sin-factura')?.textContent)).toBe('Motivo: El cliente no quiso factura');
      expect(normal(el().querySelector('.resultado-accion')?.textContent)).toBe('La venta N.º 5 quedó sin factura.');
    });

    it('los 400 y 409 de «Dejar sin factura» se muestran tal cual en el diálogo, que sigue abierto con el motivo', async () => {
      await abrirCon(rechazada());
      boton('Dejar sin factura', detalle()!)!.click();
      await refrescar();
      const motivo = dialogoMotivo()!.querySelector<HTMLInputElement>('input')!;
      motivo.value = 'Otro motivo';
      motivo.dispatchEvent(new Event('input'));

      api.dejarSinFactura.mockReturnValueOnce(
        error(400, { message: 'Datos inválidos', errors: { motivo: 'El motivo tiene hasta 200 caracteres.' } }),
      );
      boton('Dejar sin factura', dialogoMotivo()!)!.click();
      await refrescar();
      expect(normal(dialogoMotivo()!.querySelector('[role="alert"]')?.textContent)).toBe('El motivo tiene hasta 200 caracteres.');

      api.dejarSinFactura.mockReturnValueOnce(
        error(409, { message: 'Esta venta no tiene una factura rechazada para reintentar.', motivo: 'SIN_FACTURA_RECHAZADA' }),
      );
      boton('Dejar sin factura', dialogoMotivo()!)!.click();
      await refrescar();
      expect(normal(dialogoMotivo()!.querySelector('[role="alert"]')?.textContent)).toBe(
        'Esta venta no tiene una factura rechazada para reintentar.',
      );
      expect(dialogoMotivo()!.querySelector<HTMLInputElement>('input')!.value).toBe('Otro motivo');
    });

    it('una venta que salió con ticket provisorio y ya tiene CAE ofrece «Reimprimir factura», que abre la hoja como «Imprimir»', async () => {
      await abrirCon({ ...fiscalDe(documentoB()), acciones: [], reimprimir: true });

      expect(normal(detalle()!.querySelector('.aviso-reimprimir')?.textContent)).toBe(
        'Salió con ticket provisorio y ARCA ya la autorizó: reimprimí la factura para el cliente.',
      );
      expect(boton('Imprimir', detalle()!)).toBeUndefined();
      boton('Reimprimir factura', detalle()!)!.click();
      await refrescar();

      const hoja = el().querySelector('app-factura-imprimible article.factura-imprimible')!;
      expect(normal(hoja.querySelector('.numero-fiscal')?.textContent)).toBe('N.º 00001-00000012');
    });
  });

  describe('anular una venta (CU-08)', () => {
    const CONFIRMACION =
      '¿Anular la venta N.º 5? Se emite una nota de crédito y vuelve el stock. La caja no cambia: si devolvés plata, cargá un egreso.';
    const CONFIRMACION_SIN_CAE =
      '¿Anular la venta N.º 5? La venta no tiene factura: queda anulada con el motivo y vuelve el stock. La caja no cambia: si devolvés plata, cargá un egreso.';
    const dialogo = () => el().querySelector<HTMLElement>('app-dialogo-anular [role="alertdialog"]');
    const textoDialogo = () =>
      `${normal(dialogo()!.querySelector('h2')?.textContent)} ${normal(dialogo()!.querySelector('h2 + p')?.textContent)}`;
    const motivo = () => dialogo()!.querySelector<HTMLInputElement>('#motivo-anulacion');
    const error = (status: number, cuerpo: Record<string, unknown>) =>
      throwError(() => new HttpErrorResponse({ status, error: { status, ...cuerpo } }));
    const autorizada = (): FiscalConsultado => ({ ...fiscalDe(documentoB()), acciones: [], reimprimir: false });
    const anulacion = {
      notaId: 140,
      numero: 14,
      interna: false,
      motivo: null,
      usuario: 'Dueño de Prueba',
      fecha: '2026-10-29T14:05:00',
    };
    /** La nota de crédito B con CAE de la venta N.º 7. */
    const notaB = (cambios: Partial<VentaConsultada> = {}) =>
      ventaDePrueba({
        ventaId: 140,
        numero: 14,
        tipo: 'NOTA_CREDITO',
        ventaAnulada: { id: 70, numero: 7 },
        anulacion: null,
        pagos: [],
        fiscal: {
          ...fiscalDe(documentoB({ codigo: 8, numero: 3, comprobanteAsociado: 'Factura B 00001-00000045' }), {
            clase: 'NOTA_CREDITO',
          }),
          acciones: [],
          reimprimir: false,
        },
        ...cambios,
      });

    async function abrirVenta(venta: VentaConsultada) {
      await crear();
      api.obtener.mockReturnValue(of(venta));
      filas()[0].click();
      await refrescar();
    }

    it('con la factura autorizada confirma con el texto de la spec, sin motivo; después refresca la lista y abre la nota', async () => {
      await abrirVenta(ventaDePrueba({ ventaId: 50, numero: 5, fiscal: autorizada(), anulacion: null }));
      api.listar.mockClear();

      boton('Anular', detalle()!)!.click();
      await refrescar();

      expect(textoDialogo()).toBe(CONFIRMACION);
      expect(motivo()).toBeNull();
      expect(api.anular).not.toHaveBeenCalled();

      api.anular.mockReturnValue(of(notaB()));
      api.obtener.mockReturnValue(of(notaB()));
      api.listar.mockReturnValue(of(pagina([resumenDePrueba(14, { tipo: 'NOTA_CREDITO' }), resumenDePrueba(5)])));
      boton('Anular', dialogo()!)!.click();
      await refrescar();

      expect(api.anular).toHaveBeenCalledWith(50, null);
      expect(dialogo()).toBeNull();
      expect(api.listar).toHaveBeenCalledWith({ page: 0 });
      expect(api.obtener).toHaveBeenLastCalledWith(140);
      expect(normal(detalle()!.querySelector('h2')?.textContent)).toBe('Nota de crédito N.º 14');
      expect(normal(el().querySelector('.resultado-accion')?.textContent)).toBe(
        'Se anuló la venta N.º 5 con la nota de crédito N.º 14.',
      );
    });

    it('una venta con un pago a cuenta corriente avisa en la confirmación que se descuenta de la deuda del cliente', async () => {
      const aCuenta = { formaPagoId: 5, formaPago: 'Cuenta corriente', medio: 'CUENTA_CORRIENTE' as const, recargoPorcentaje: 0, recargo: 0 };
      await abrirVenta(
        ventaDePrueba({
          ventaId: 50,
          numero: 5,
          fiscal: autorizada(),
          anulacion: null,
          pagos: [{ ...ventaDePrueba().pagos![0], monto: 970 }, { ...aCuenta, monto: 2000 }],
        }),
      );

      boton('Anular', detalle()!)!.click();
      await refrescar();

      expect(textoDialogo()).toBe(`${CONFIRMACION} La parte a cuenta corriente se descuenta de la deuda del cliente.`);
    });

    it('sin la factura autorizada pide el motivo y lo manda; los 400 del motivo y los 409 y 422 quedan en el diálogo', async () => {
      for (const fiscal of [undefined, { ...autorizada(), estado: 'RECHAZADO' as const }]) {
        TestBed.resetTestingModule();
        await abrirVenta(ventaDePrueba({ ventaId: 50, numero: 5, fiscal, anulacion: null }));
        boton('Anular', detalle()!)!.click();
        await refrescar();
        expect(textoDialogo()).toBe(CONFIRMACION_SIN_CAE);
        expect(motivo(), String(fiscal?.estado)).not.toBeNull();
      }

      motivo()!.value = 'El cliente devolvió todo';
      motivo()!.dispatchEvent(new Event('input'));
      const casos: [ReturnType<typeof error>, string][] = [
        [error(400, { message: 'Datos inválidos', errors: { motivo: 'El motivo tiene hasta 200 caracteres.' } }), 'El motivo tiene hasta 200 caracteres.'],
        [error(409, { message: 'Esperá el CAE de la factura para anularla.', motivo: 'ESPERAR_CAE' }), 'Esperá el CAE de la factura para anularla.'],
        [error(409, { message: 'La venta ya fue anulada por Dueño el 29/10/2026 14:05.', motivo: 'VENTA_YA_ANULADA' }), 'La venta ya fue anulada por Dueño el 29/10/2026 14:05.'],
        [error(422, { message: 'El cliente está desactivado.' }), 'El cliente está desactivado.'],
      ];
      for (const [respuesta, mensaje] of casos) {
        api.anular.mockReturnValueOnce(respuesta);
        boton('Anular', dialogo()!)!.click();
        await refrescar();
        expect(normal(dialogo()!.querySelector('[role="alert"]')?.textContent)).toBe(mensaje);
      }
      expect(api.anular).toHaveBeenCalledWith(50, 'El cliente devolvió todo');
      expect(motivo()!.value).toBe('El cliente devolvió todo');
    });

    it('una venta anulada dice cómo, quién y cuándo, y no ofrece Anular; la lista la marca «Anulada»', async () => {
      await crear([resumenDePrueba(5, { anulacion }), resumenDePrueba(4, { anulacion: null })]);
      expect(celdas(filas()[0])[0]).toBe('5 Anulada');
      expect(celdas(filas()[1])[0]).toBe('4');

      api.obtener.mockReturnValue(of(ventaDePrueba({ ventaId: 50, numero: 5, fiscal: autorizada(), anulacion })));
      filas()[0].click();
      await refrescar();
      expect(normal(detalle()!.querySelector('.anulacion')?.textContent)).toBe(
        'Anulada por la NC N.º 14 · Dueño de Prueba, 29/10/2026 14:05',
      );
      expect(boton('Anular', detalle()!)).toBeUndefined();

      const interna = { ...anulacion, interna: true, motivo: 'El cliente devolvió todo' };
      api.obtener.mockReturnValue(of(ventaDePrueba({ ventaId: 40, numero: 4, anulacion: interna })));
      filas()[1].click();
      await refrescar();
      expect(normal(detalle()!.querySelector('.anulacion')?.textContent)).toBe(
        'Anulada: El cliente devolvió todo · Dueño de Prueba, 29/10/2026 14:05',
      );
      expect(boton('Anular', detalle()!)).toBeUndefined();
    });

    it('la nota de crédito y la anulación son renglones propios, con el total en negativo y la venta que anulan', async () => {
      const resumenB: ResumenFiscal = {
        letra: 'B',
        puntoVenta: 1,
        numero: 3,
        estado: 'AUTORIZADO',
        ambiente: 'PRODUCCION',
        validoComoFactura: true,
      };
      await crear([
        resumenDePrueba(15, { tipo: 'NOTA_CREDITO', ventaAnulada: { id: 40, numero: 4 }, fiscal: null }),
        resumenDePrueba(14, { tipo: 'NOTA_CREDITO', ventaAnulada: { id: 70, numero: 7 }, fiscal: resumenB }),
      ]);

      expect(celdas(filas()[0])).toEqual([
        '15 Anulación · Venta N.º 4',
        '',
        '22/10/2026 16:05',
        'Cliente 15',
        'Ana Vendedora',
        '-$ 3.000,00',
      ]);
      expect(celdas(filas()[1])[0]).toBe('14 Nota de crédito · Venta N.º 7');
      expect(celdas(filas()[1])[1]).toBe('B 00001-00000003');
      // Bugs de la guía, S10: «Venta N.º 7» no se parte entre renglones (espacios duros).
      expect(filas()[1].querySelector('.marca.nota')!.textContent).toContain('Venta N.º 7');
    });

    it('la anulación interna no lleva la marca «No facturable»: esa es una decisión del cobro, no de la nota (QA 05/11)', async () => {
      await crear([
        resumenDePrueba(15, { tipo: 'NOTA_CREDITO', ventaAnulada: { id: 40, numero: 4 }, fiscal: null, facturable: false }),
        resumenDePrueba(4, { facturable: false }),
      ]);

      expect(celdas(filas()[0])[0]).toBe('15 Anulación · Venta N.º 4');
      expect(celdas(filas()[1])[0]).toBe('4 No facturable');
    });

    it('el detalle de la nota dice qué venta anula, no ofrece Anular y reimprime la nota de crédito', async () => {
      await abrirVenta(notaB());

      expect(normal(detalle()!.querySelector('h2')?.textContent)).toBe('Nota de crédito N.º 14');
      expect(normal(detalle()!.querySelector('.venta-anulada')?.textContent)).toBe('Anula la venta N.º 7');
      const datos = Array.from(detalle()!.querySelectorAll('dl.datos dt')).map((dt) => normal(dt.textContent));
      expect(datos).toEqual(['Fecha', 'Cliente', 'Anuló']);
      expect(normal(detalle()!.querySelector('.linea-fiscal')?.textContent)).toBe(
        'Nota de crédito B 00001-00000003 · Autorizada',
      );
      expect(boton('Anular', detalle()!)).toBeUndefined();

      boton('Imprimir', detalle()!)!.click();
      await refrescar();
      const hoja = el().querySelector('app-factura-imprimible article.factura-imprimible')!;
      expect(normal(hoja.querySelector('.tipo')?.textContent)).toBe('NOTA DE CRÉDITO');
      expect(normal(hoja.querySelector('.comprobante-asociado')?.textContent)).toBe(
        'Comprobante asociado: Factura B 00001-00000045',
      );
    });

    it('la anulación interna se titula «Anulación N.º n» y se imprime como comprobante interno', async () => {
      await abrirVenta(notaB({ fiscal: undefined, ventaAnulada: { id: 40, numero: 4 } }));

      expect(normal(detalle()!.querySelector('h2')?.textContent)).toBe('Anulación N.º 14');
      boton('Imprimir', detalle()!)!.click();
      await refrescar();
      expect(normal(el().querySelector('app-venta-imprimible h2')?.textContent)).toBe('Anulación N.º 14');
    });

    it('una nota de crédito rechazada se reintenta desde la bandeja, sin «Dejar sin factura»', async () => {
      await abrirVenta(
        notaB({
          fiscal: {
            ...fiscalDe(documentoX('SIN FACTURA — ARCA no la autorizó'), { clase: 'NOTA_CREDITO' }),
            estado: 'RECHAZADO',
            acciones: ['REINTENTAR'],
            reimprimir: false,
          },
        }),
      );

      expect(boton('Dejar sin factura', detalle()!)).toBeUndefined();
      boton('Reintentar', detalle()!)!.click();
      await refrescar();
      expect(api.reintentar).toHaveBeenCalledWith(140, false);
    });
  });

  describe('una venta elegida desde la dirección (informacion-y-links, S2)', () => {
    const navegar = () => vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    it('`/ventas/:id` abre esa venta aunque no esté en la página de la lista, y la lista no la cierra', async () => {
      await crear([resumenDePrueba(5)], 'DUENO', {}, '90');

      expect(api.obtener).toHaveBeenCalledWith(90);
      expect(detalle()).not.toBeNull();
      expect(normal(detalle()!.querySelector('h2')!.textContent)).toBe('Venta N.º 9');
      expect(filas()).toHaveLength(1);
    });

    it('cerrarla deja la lista sin la venta en la dirección', async () => {
      await crear([resumenDePrueba(5)], 'DUENO', {}, '90');
      const espia = navegar();

      escape();
      await refrescar();

      expect(detalle()).toBeNull();
      expect(espia).toHaveBeenCalledWith('/ventas');
    });

    it('abierta desde la lista, cerrarla no cambia la dirección', async () => {
      await crear();
      const espia = navegar();
      filas()[0].click();
      await refrescar();

      escape();
      await refrescar();

      expect(espia).not.toHaveBeenCalled();
    });

    it('una de otra ferretería o inexistente (404) da el aviso de siempre', async () => {
      await crear([resumenDePrueba(5)], 'DUENO', {}, '90');
      api.obtener.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
      parametros.next(convertToParamMap({ id: '77' }));
      await refrescar();

      expect(normal(detalle()!.querySelector('.error')!.textContent)).toBe('No se encontró la venta.');
    });

    it('otra venta en la dirección con la pantalla abierta (un link entre documentos) abre esa', async () => {
      await crear([resumenDePrueba(5)], 'DUENO', {}, '90');
      parametros.next(convertToParamMap({ id: '120' }));
      await refrescar();

      expect(api.obtener).toHaveBeenLastCalledWith(120);
      expect(normal(detalle()!.querySelector('h2')!.textContent)).toBe('Venta N.º 12');
    });
  });

  describe('links y datos del detalle (informacion-y-links, S2 y S4)', () => {
    it('«Anula la venta N.º n» de la nota de crédito es un link al pop-up de esa venta', async () => {
      await crear();
      api.obtener.mockReturnValue(of(ventaDePrueba({ ventaId: 140, numero: 14, tipo: 'NOTA_CREDITO', ventaAnulada: { id: 70, numero: 7 } })));
      filas()[0].click();
      await refrescar();

      const link = detalle()!.querySelector<HTMLAnchorElement>('.venta-anulada a')!;
      expect(normal(detalle()!.querySelector('.venta-anulada')!.textContent)).toBe('Anula la venta N.º 7');
      expect(link.getAttribute('href')).toBe('/ventas/70');
    });

    it('una X con número dice su número, como la lista: «X 00001-00000011 · <marca>»', async () => {
      await crear();
      api.obtener.mockReturnValue(
        of(ventaDePrueba({ fiscal: fiscalDe(documentoX(MARCA_SIMULADO), { ambiente: 'SIMULADOR', numero: 11 }) })),
      );
      filas()[0].click();
      await refrescar();

      expect(normal(detalle()!.querySelector('.linea-fiscal')?.textContent)).toBe(
        'X 00001-00000011 · SIMULADO — sin validez fiscal',
      );
    });
  });
});
