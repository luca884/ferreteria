import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { Ventas } from './ventas';
import { FiscalConsultado, VentaResumen } from './ventas.models';
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
  };

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
  ) {
    api = {
      listar: vi.fn(() => of(pagina(items))),
      obtener: vi.fn((id: number) => of(ventaDePrueba({ ventaId: id, numero: id / 10 }))),
      reintentar: vi.fn(() => of({})),
      dejarSinFactura: vi.fn(() => of({})),
    };
    const sesion = signal({ userId: 7, nombre: 'Ana', email: 'a@x.com', rol, ferreteriaId: 3, ferreteriaNombre: 'Tornillo' });
    await TestBed.configureTestingModule({
      imports: [Ventas],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: VentasService, useValue: api },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly(), esDueno: () => rol === 'DUENO' } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } },
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
  const detalle = () => el().querySelector<HTMLElement>('section.detalle');
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
    expect(d).toContain('Descuento (10 %) -$ 300,00');
    expect(d).toContain('Recargo $ 270,00');
    expect(d).toContain('Total $ 2.970,00');
    expect(d).toContain('Crédito 1 pago $ 2.970,00');
    expect(d).toContain('Ana Vendedora');
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
    expect(totales).toContain('Descuento -$ 300,00');
    expect(totales).not.toContain('(0 %)');
  });

  it('con descuento general y por renglón el total no muestra el porcentaje general', async () => {
    await crear();
    const base = ventaDePrueba();
    api.obtener.mockReturnValue(of({ ...base, renglones: [{ ...base.renglones[0], descuentoPorcentaje: 5 }] }));

    filas()[0].click();
    await refrescar();

    const totales = normal(detalle()!.querySelector('.totales')?.textContent);
    expect(totales).toContain('Descuento -$ 300,00');
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
  });

  it('el detalle solo ofrece Imprimir: ni Anular ni Borrar (GDS-15)', async () => {
    await crear();
    filas()[0].click();
    await refrescar();

    const acciones = Array.from(detalle()!.querySelectorAll('button')).map((b) => normal(b.textContent));
    expect(acciones).toEqual(['Imprimir']);
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
});
