import { HttpErrorResponse } from '@angular/common/http';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../../core/auth/auth.service';
import { Extracto, FILTRO_VACIO, FiltroExtracto, LineaExtracto } from './extracto.models';
import { ExtractoService } from './extracto.service';
import { MovimientosCuenta } from './movimientos-cuenta';

registerLocaleData(localeEsAr);

describe('MovimientosCuenta (CC-074, CC-076, CC-078, CC-096)', () => {
  /** Una línea con el contrato real de LineaExtractoResponse; el más nuevo tiene el id más alto. */
  const linea = (id: number, cambios: Partial<LineaExtracto> = {}): LineaExtracto => ({
    id,
    fecha: '2026-10-20T10:11:12.5',
    tipo: 'VENTA',
    referencia: `Venta N.º ${id}`,
    debe: 100,
    haber: 0,
    saldo: id * 100,
    usuario: 'Ana',
    reciboId: null,
    comprobanteId: id,
    motivo: null,
    excedioLimite: false,
    autorizadoPor: null,
    anulado: false,
    ...cambios,
  });

  const extracto = (items: LineaExtracto[], cambios: Partial<Extracto> = {}, pagina: Partial<Extracto['movimientos']> = {}): Extracto => ({
    clienteId: 8,
    clienteNombre: 'Ana Gómez',
    saldoActual: 1200,
    desde: null,
    hasta: null,
    tipo: null,
    saldoInicial: 300,
    totalDebe: 1500,
    totalHaber: 600,
    saldoFinal: 1200,
    movimientos: { items, page: 0, size: 20, totalElements: items.length, totalPages: 1, ...pagina },
    truncado: false,
    ...cambios,
  });

  /** 25 movimientos: la página 0 trae los ids 25..6 y la 1 los ids 5..1 (más nuevo arriba). */
  const veinticinco = (page: number): Extracto => {
    const ids = page === 0 ? Array.from({ length: 20 }, (_, i) => 25 - i) : [5, 4, 3, 2, 1];
    return extracto(ids.map((id) => linea(id)), {}, { page, totalElements: 25, totalPages: 2 });
  };

  let fixture: ComponentFixture<MovimientosCuenta>;
  let servicio: { extracto: ReturnType<typeof vi.fn>; paraImprimir: ReturnType<typeof vi.fn> };
  let anuladas: LineaExtracto[];
  let recibosVistos: number[];

  interface Opciones {
    extracto?: (clienteId: number, filtro: FiltroExtracto, page: number) => Observable<Extracto>;
    clienteActivo?: boolean;
  }

  async function crear({ extracto: pedir, clienteActivo }: Opciones = {}) {
    servicio = {
      extracto: vi.fn(pedir ?? (() => of(extracto([linea(1)])))),
      paraImprimir: vi.fn(() => of(extracto([linea(1)]))),
    };
    await TestBed.configureTestingModule({
      imports: [MovimientosCuenta],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ExtractoService, useValue: servicio },
        { provide: AuthService, useValue: { sesion: signal({ ferreteriaNombre: 'Tornillo SA' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MovimientosCuenta);
    anuladas = [];
    recibosVistos = [];
    fixture.componentInstance.anular.subscribe((l) => anuladas.push(l));
    fixture.componentInstance.verRecibo.subscribe((id) => recibosVistos.push(id));
    fixture.componentRef.setInput('clienteId', 8);
    if (clienteActivo !== undefined) fixture.componentRef.setInput('clienteActivo', clienteActivo);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || null;
  const filas = () => Array.from(el().querySelectorAll<HTMLTableRowElement>('table.movimientos tbody tr'));
  const celdas = (fila: HTMLTableRowElement) => Array.from(fila.querySelectorAll('th, td')).map((c) => limpio(c.textContent));
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => limpio(b.textContent) === nombre);

  describe('carga y estados', () => {
    it('al abrir pide la página 0 del cliente con el filtro vacío', async () => {
      await crear();

      expect(servicio.extracto).toHaveBeenCalledTimes(1);
      expect(servicio.extracto).toHaveBeenCalledWith(8, FILTRO_VACIO, 0);
    });

    it('mientras espera muestra «Cargando…» y después la tabla', async () => {
      const respuesta = new Subject<Extracto>();
      await crear({ extracto: () => respuesta });

      expect(texto('.estado-vacio')).toBe('Cargando…');
      expect(el().querySelector('table.movimientos')).toBeNull();

      respuesta.next(extracto([linea(1)]));
      await refrescar();

      expect(texto('.estado-vacio')).toBeNull();
      expect(filas()).toHaveLength(1);
    });

    it('sin movimientos dice «Sin movimientos para ese filtro» y no dibuja la tabla', async () => {
      await crear({ extracto: () => of(extracto([], { totalDebe: 0, totalHaber: 0 })) });

      expect(texto('.estado-vacio')).toBe('Sin movimientos para ese filtro');
      expect(el().querySelector('table.movimientos')).toBeNull();
    });

    it('si falla la carga muestra el error con «Reintentar», que vuelve a pedir y muestra la tabla', async () => {
      let intento = 0;
      await crear({
        extracto: () =>
          ++intento === 1 ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(extracto([linea(1), linea(2)])),
      });

      expect(texto('.error')).toContain('No se pudieron cargar los movimientos.');
      expect(el().querySelector('table.movimientos')).toBeNull();

      boton('Reintentar')!.click();
      await refrescar();

      expect(servicio.extracto).toHaveBeenCalledTimes(2);
      expect(el().querySelector('.error')).toBeNull();
      expect(filas()).toHaveLength(2);
    });

    it('un 403 o un 404 muestran el message del servidor', async () => {
      await crear({
        extracto: () => throwError(() => new HttpErrorResponse({ status: 404, error: { message: 'Cliente no encontrado' } })),
      });

      expect(texto('.error')).toContain('Cliente no encontrado');
    });
  });

  describe('encabezado del período', () => {
    it('muestra saldo inicial, total debe, total haber y saldo final con 2 decimales', async () => {
      await crear({
        extracto: () =>
          of(extracto([linea(1)], { saldoInicial: 300, totalDebe: 1600, totalHaber: 600.5, saldoFinal: 1299.5 })),
      });

      expect(texto('.encabezado-periodo .saldo-inicial')).toContain('$ 300,00');
      expect(texto('.encabezado-periodo .total-debe')).toContain('$ 1.600,00');
      expect(texto('.encabezado-periodo .total-haber')).toContain('$ 600,50');
      expect(texto('.encabezado-periodo .saldo-final')).toContain('$ 1.299,50');
    });
  });

  describe('tabla', () => {
    it('lista el más nuevo arriba con fecha, comprobante, debe, haber, saldo y usuario', async () => {
      await crear({
        extracto: () =>
          of(
            extracto([
              linea(3, { fecha: '2026-10-20T23:59:59', tipo: 'COBRO', referencia: 'Recibo N.º 14', debe: 0, haber: 500, saldo: 700, reciboId: 14, comprobanteId: null }),
              linea(2, { fecha: '2026-10-19T08:05:00', referencia: 'Venta N.º 7', debe: 1200, haber: 0, saldo: 1200, usuario: null }),
            ]),
          ),
      });

      const [primera, segunda] = filas();
      // La fecha se lee del texto (hora de Buenos Aires): 23:59 no pasa a otro día por la zona del navegador.
      expect(celdas(primera).slice(0, 6)).toEqual(['20/10/2026 23:59', 'Recibo N.º 14', '', '$ 500,00', '$ 700,00', 'Ana']);
      expect(celdas(segunda).slice(0, 6)).toEqual(['19/10/2026 08:05', 'Venta N.º 7', '$ 1.200,00', '', '$ 1.200,00', '—']);
    });

    it('los importes que llegan como número entero se muestran siempre con 2 decimales', async () => {
      await crear({ extracto: () => of(extracto([linea(1, { debe: 1600, saldo: 1600 })])) });

      const [fila] = filas();
      expect(celdas(fila)[2]).toBe('$ 1.600,00');
      expect(celdas(fila)[4]).toBe('$ 1.600,00');
    });

    it('un COBRO anulado se marca «Anulado»; uno vigente no', async () => {
      await crear({
        extracto: () =>
          of(
            extracto([
              linea(2, { tipo: 'COBRO', referencia: 'Recibo N.º 14', debe: 0, haber: 500, reciboId: 14, comprobanteId: null, anulado: true }),
              linea(1, { tipo: 'COBRO', referencia: 'Recibo N.º 13', debe: 0, haber: 200, reciboId: 13, comprobanteId: null }),
            ]),
          ),
      });

      const [anulado, vigente] = filas();
      expect(limpio(anulado.querySelector('.etiqueta-anulado')?.textContent)).toBe('Anulado');
      expect(vigente.querySelector('.etiqueta-anulado')).toBeNull();
    });

    it('una ANULACION se distingue y muestra el motivo debajo de la referencia', async () => {
      await crear({
        extracto: () =>
          of(
            extracto([
              linea(2, { tipo: 'ANULACION', referencia: 'Anulación Recibo N.º 14', debe: 500, haber: 0, reciboId: 14, comprobanteId: null, motivo: 'Cliente equivocado' }),
              linea(1, { tipo: 'VENTA' }),
            ]),
          ),
      });

      const [anulacion, venta] = filas();
      expect(anulacion.classList.contains('anulacion')).toBe(true);
      expect(limpio(anulacion.querySelector('.motivo')?.textContent)).toBe('Motivo: Cliente equivocado');
      expect(venta.classList.contains('anulacion')).toBe(false);
      expect(venta.querySelector('.motivo')).toBeNull();
    });
  });

  describe('paginación (CC-096: 25 movimientos → 20 + 5)', () => {
    it('la primera página trae los 20 más nuevos y «Página 1 de 2»', async () => {
      await crear({ extracto: (_c, _f, page) => of(veinticinco(page)) });

      expect(filas()).toHaveLength(20);
      expect(celdas(filas()[0])[1]).toBe('Venta N.º 25');
      expect(celdas(filas()[19])[1]).toBe('Venta N.º 6');
      expect(texto('nav.paginas')).toContain('Página 1 de 2');
      expect((boton('Anterior') as HTMLButtonElement).disabled).toBe(true);
      expect((boton('Siguiente') as HTMLButtonElement).disabled).toBe(false);
    });

    it('«Siguiente» pide la página 1 y muestra los 5 restantes con su saldo real', async () => {
      await crear({ extracto: (_c, _f, page) => of(veinticinco(page)) });

      boton('Siguiente')!.click();
      await refrescar();

      expect(servicio.extracto).toHaveBeenLastCalledWith(8, FILTRO_VACIO, 1);
      expect(filas()).toHaveLength(5);
      expect(filas().map((f) => celdas(f)[4])).toEqual(['$ 500,00', '$ 400,00', '$ 300,00', '$ 200,00', '$ 100,00']);
      expect(texto('nav.paginas')).toContain('Página 2 de 2');
      expect((boton('Siguiente') as HTMLButtonElement).disabled).toBe(true);
    });

    it('«Anterior» vuelve a la página 0', async () => {
      await crear({ extracto: (_c, _f, page) => of(veinticinco(page)) });
      boton('Siguiente')!.click();
      await refrescar();

      boton('Anterior')!.click();
      await refrescar();

      expect(servicio.extracto).toHaveBeenLastCalledWith(8, FILTRO_VACIO, 0);
      expect(filas()).toHaveLength(20);
    });

    it('con una sola página no hay paginación', async () => {
      await crear();

      expect(el().querySelector('nav.paginas')).toBeNull();
    });
  });

  describe('filtros (CC-075, CC-096)', () => {
    const campo = (nombre: string) => el().querySelector<HTMLInputElement | HTMLSelectElement>(`form.filtros [name="${nombre}"]`)!;
    const formulario = () => el().querySelector<HTMLFormElement>('form.filtros')!;

    async function escribir(nombre: string, valor: string) {
      const c = campo(nombre);
      c.value = valor;
      c.dispatchEvent(new Event(c.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }));
      await refrescar();
    }

    async function enviarFormulario() {
      formulario().dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      await refrescar();
    }

    it('el formulario tiene Desde, Hasta (fechas), Tipo con «Todos» y los cinco tipos, Filtrar y Limpiar', async () => {
      await crear();

      expect(campo('desde').getAttribute('type')).toBe('date');
      expect(campo('hasta').getAttribute('type')).toBe('date');
      const opciones = Array.from((campo('tipo') as HTMLSelectElement).options).map((o) => [o.value, limpio(o.textContent)]);
      expect(opciones).toEqual([
        ['', 'Todos'],
        ['VENTA', 'Venta'],
        ['COBRO', 'Cobro'],
        ['ANULACION', 'Anulación'],
        ['AJUSTE', 'Ajuste'],
        ['NC', 'NC'],
      ]);
      expect(boton('Filtrar')).toBeDefined();
      expect(boton('Limpiar')).toBeDefined();
      expect(el().querySelector('label[for], label')).not.toBeNull();
    });

    it('escribir en los campos no pide nada hasta filtrar', async () => {
      await crear();

      await escribir('desde', '2026-10-01');
      await escribir('tipo', 'COBRO');

      expect(servicio.extracto).toHaveBeenCalledTimes(1);
    });

    it('Filtrar pide la página 0 con desde, hasta y tipo', async () => {
      await crear();
      await escribir('desde', '2026-10-01');
      await escribir('hasta', '2026-10-31');
      await escribir('tipo', 'COBRO');

      boton('Filtrar')!.click();
      await refrescar();

      expect(servicio.extracto).toHaveBeenLastCalledWith(8, { desde: '2026-10-01', hasta: '2026-10-31', tipo: 'COBRO' }, 0);
    });

    it('Enter filtra: el submit del formulario (lo que dispara Enter en un campo) también pide con el filtro', async () => {
      await crear();
      await escribir('desde', '2026-10-05');

      await enviarFormulario();

      expect(servicio.extracto).toHaveBeenLastCalledWith(8, { desde: '2026-10-05', hasta: '', tipo: '' }, 0);
    });

    it('cambiar el filtro desde la página 2 vuelve a la página 0', async () => {
      await crear({ extracto: (_c, _f, page) => of(veinticinco(page)) });
      boton('Siguiente')!.click();
      await refrescar();
      expect(servicio.extracto).toHaveBeenLastCalledWith(8, FILTRO_VACIO, 1);

      await escribir('tipo', 'VENTA');
      boton('Filtrar')!.click();
      await refrescar();

      expect(servicio.extracto).toHaveBeenLastCalledWith(8, { desde: '', hasta: '', tipo: 'VENTA' }, 0);
    });

    it('la paginación conserva el filtro aplicado', async () => {
      await crear({ extracto: (_c, _f, page) => of(veinticinco(page)) });
      await escribir('tipo', 'VENTA');
      boton('Filtrar')!.click();
      await refrescar();

      boton('Siguiente')!.click();
      await refrescar();

      expect(servicio.extracto).toHaveBeenLastCalledWith(8, { desde: '', hasta: '', tipo: 'VENTA' }, 1);
    });

    it('Limpiar borra los campos y pide la página 0 sin filtro', async () => {
      await crear();
      await escribir('desde', '2026-10-01');
      await escribir('tipo', 'COBRO');
      boton('Filtrar')!.click();
      await refrescar();

      boton('Limpiar')!.click();
      await refrescar();

      expect((campo('desde') as HTMLInputElement).value).toBe('');
      expect((campo('tipo') as HTMLSelectElement).value).toBe('');
      expect(servicio.extracto).toHaveBeenLastCalledWith(8, FILTRO_VACIO, 0);
    });

    it('con filtro por tipo la columna Saldo sigue mostrando el saldo REAL de cada línea (no el acumulado de las filtradas)', async () => {
      await crear({
        extracto: (_c, filtro) =>
          of(
            filtro.tipo === 'COBRO'
              ? extracto(
                  [
                    linea(9, { tipo: 'COBRO', referencia: 'Recibo N.º 3', debe: 0, haber: 200, saldo: 1000, reciboId: 3, comprobanteId: null }),
                    linea(5, { tipo: 'COBRO', referencia: 'Recibo N.º 2', debe: 0, haber: 500, saldo: 1200, reciboId: 2, comprobanteId: null }),
                  ],
                  { tipo: 'COBRO', saldoInicial: 0, totalDebe: 0, totalHaber: 700, saldoFinal: 1000 },
                )
              : extracto([linea(1)]),
          ),
      });
      await escribir('tipo', 'COBRO');
      boton('Filtrar')!.click();
      await refrescar();

      expect(filas().map((f) => celdas(f)[4])).toEqual(['$ 1.000,00', '$ 1.200,00']);
    });

    it('con tipo elegido avisa que los totales son del tipo; sin tipo no lo dice', async () => {
      await crear({ extracto: (_c, filtro) => of(extracto([linea(1)], { tipo: filtro.tipo === '' ? null : filtro.tipo })) });
      expect(texto('.nota-tipo')).toBeNull();

      await escribir('tipo', 'COBRO');
      boton('Filtrar')!.click();
      await refrescar();

      expect(texto('.nota-tipo')).toBe('Con filtro por tipo, los totales son del tipo elegido.');

      boton('Limpiar')!.click();
      await refrescar();
      expect(texto('.nota-tipo')).toBeNull();
    });

    it('desde > hasta: muestra el 400 de «desde» sin romper la tabla, y Limpiar vuelve a la normalidad', async () => {
      await crear({
        extracto: (_c, filtro) =>
          filtro.desde > filtro.hasta && filtro.hasta !== ''
            ? throwError(
                () =>
                  new HttpErrorResponse({
                    status: 400,
                    error: { status: 400, message: 'Datos inválidos', errors: { desde: 'La fecha desde no puede ser posterior a hasta.' } },
                  }),
              )
            : of(extracto([linea(1), linea(2)])),
      });
      await escribir('desde', '2026-10-10');
      await escribir('hasta', '2026-10-01');

      boton('Filtrar')!.click();
      await refrescar();

      expect(texto('.error-filtro')).toBe('La fecha desde no puede ser posterior a hasta.');
      expect(filas()).toHaveLength(2);
      expect(texto('.error.con-accion')).toBeNull();

      boton('Limpiar')!.click();
      await refrescar();
      expect(texto('.error-filtro')).toBeNull();
      expect(filas()).toHaveLength(2);
    });

    it('si el filtro falla por otra causa (500) y había datos, queda el error con Reintentar sobre el filtro pedido', async () => {
      let rompe = false;
      await crear({ extracto: () => (rompe ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(extracto([linea(1)]))) });
      rompe = true;
      await escribir('tipo', 'NC');

      boton('Filtrar')!.click();
      await refrescar();

      expect(texto('.error.con-accion')).toContain('No se pudieron cargar los movimientos.');
      rompe = false;
      boton('Reintentar')!.click();
      await refrescar();

      expect(servicio.extracto).toHaveBeenLastCalledWith(8, { desde: '', hasta: '', tipo: 'NC' }, 0);
      expect(filas()).toHaveLength(1);
    });
  });

  describe('acciones por fila (CC-097, CC-098)', () => {
    const cobro = (id: number, reciboId: number, cambios: Partial<LineaExtracto> = {}) =>
      linea(id, { tipo: 'COBRO', referencia: `Recibo N.º ${reciboId}`, debe: 0, haber: 500, reciboId, comprobanteId: null, ...cambios });
    const acciones = (fila: HTMLTableRowElement) =>
      Array.from(fila.querySelectorAll<HTMLButtonElement>('button')).map((b) => limpio(b.textContent));

    const surtido = () =>
      extracto([
        cobro(6, 14),
        cobro(5, 13, { anulado: true }),
        linea(4, { tipo: 'ANULACION', referencia: 'Anulación Recibo N.º 13', debe: 500, haber: 0, reciboId: 13, comprobanteId: null, motivo: 'Error' }),
        linea(3, { tipo: 'VENTA' }),
        linea(2, { tipo: 'AJUSTE', referencia: 'Ajuste', comprobanteId: null }),
        linea(1, { tipo: 'NC', referencia: 'NC', comprobanteId: null }),
      ]);

    it('COBRO vigente: «Anular» y «Reimprimir»; anulado: solo «Reimprimir»; ANULACION: solo «Reimprimir»; el resto, nada', async () => {
      await crear({ extracto: () => of(surtido()) });

      expect(filas().map(acciones)).toEqual([
        ['Anular', 'Reimprimir'],
        ['Reimprimir'],
        ['Reimprimir'],
        [],
        [],
        [],
      ]);
    });

    it('«Anular» emite la línea completa del COBRO y no hace ningún pedido por su cuenta', async () => {
      await crear({ extracto: () => of(surtido()) });

      boton('Anular')!.click();

      expect(anuladas).toHaveLength(1);
      expect(anuladas[0].id).toBe(6);
      expect(anuladas[0].reciboId).toBe(14);
      expect(servicio.extracto).toHaveBeenCalledTimes(1);
    });

    it('con varios cobros vigentes, cada «Anular» emite SU línea', async () => {
      await crear({ extracto: () => of(extracto([cobro(8, 20), cobro(7, 19)])) });

      const botones = Array.from(el().querySelectorAll<HTMLButtonElement>('button.anular'));
      expect(botones).toHaveLength(2);
      botones[1].click();

      expect(anuladas.map((l) => l.reciboId)).toEqual([19]);
    });

    it('«Reimprimir» emite el reciboId, también en la fila ANULACION y en un COBRO anulado', async () => {
      await crear({ extracto: () => of(surtido()) });

      const botones = Array.from(el().querySelectorAll<HTMLButtonElement>('button.reimprimir'));
      expect(botones).toHaveLength(3);
      botones.forEach((b) => b.click());

      expect(recibosVistos).toEqual([14, 13, 13]);
    });

    it('con el cliente desactivado «Anular» queda deshabilitado, con «Reactivá el cliente para anular», y no emite', async () => {
      await crear({ extracto: () => of(surtido()), clienteActivo: false });

      const anular = el().querySelector<HTMLButtonElement>('button.anular')!;
      expect(anular.disabled).toBe(true);
      expect(texto('.aviso-inactivo')).toBe('Reactivá el cliente para anular');
      expect(anular.getAttribute('aria-describedby')).toBe(el().querySelector('.aviso-inactivo')!.id);
      anular.click();
      expect(anuladas).toHaveLength(0);
      // «Reimprimir» sigue disponible: reimprimir no depende de que el cliente esté activo.
      expect(el().querySelector<HTMLButtonElement>('button.reimprimir')!.disabled).toBe(false);
    });

    it('con el cliente activo no hay aviso de reactivar', async () => {
      await crear({ extracto: () => of(surtido()) });

      expect(el().querySelector('.aviso-inactivo')).toBeNull();
      expect(el().querySelector<HTMLButtonElement>('button.anular')!.disabled).toBe(false);
    });

    it('si no hay ningún cobro vigente no hace falta el aviso de reactivar', async () => {
      await crear({ extracto: () => of(extracto([linea(1)])), clienteActivo: false });

      expect(el().querySelector('.aviso-inactivo')).toBeNull();
    });
  });

  describe('imprimir el extracto (CC-099)', () => {
    const imprimible = () => el().querySelector('app-extracto-imprimible');
    const imprimirBtn = () => el().querySelector<HTMLButtonElement>('button.imprimir-extracto')!;
    const cuarentaYCinco = () => extracto(Array.from({ length: 45 }, (_, i) => linea(45 - i)), {}, { size: 2000, totalElements: 45 });

    it('«Imprimir» pide TODO el período filtrado en UN pedido (paraImprimir) y abre el extracto con las 45 líneas', async () => {
      await crear();
      servicio.paraImprimir.mockReturnValue(of(cuarentaYCinco()));

      imprimirBtn().click();
      await refrescar();

      expect(servicio.paraImprimir).toHaveBeenCalledTimes(1);
      expect(servicio.paraImprimir).toHaveBeenCalledWith(8, FILTRO_VACIO);
      expect(imprimible()).not.toBeNull();
      expect(imprimible()!.querySelectorAll('table.lineas tbody tr')).toHaveLength(45);
      // No se pidieron más páginas de la pantalla.
      expect(servicio.extracto).toHaveBeenCalledTimes(1);
    });

    it('imprime con el filtro APLICADO (el de la tabla), no con lo que está a medio escribir', async () => {
      await crear();
      const tipo = el().querySelector<HTMLSelectElement>('select[name="tipo"]')!;
      tipo.value = 'VENTA';
      tipo.dispatchEvent(new Event('change', { bubbles: true }));
      await refrescar();
      boton('Filtrar')!.click();
      await refrescar();
      tipo.value = 'NC';
      tipo.dispatchEvent(new Event('change', { bubbles: true }));
      await refrescar();

      imprimirBtn().click();
      await refrescar();

      expect(servicio.paraImprimir).toHaveBeenCalledWith(8, { desde: '', hasta: '', tipo: 'VENTA' });
    });

    it('si el extracto vino truncado, el imprimible lleva el aviso de los primeros 2000', async () => {
      await crear();
      servicio.paraImprimir.mockReturnValue(of({ ...cuarentaYCinco(), truncado: true }));

      imprimirBtn().click();
      await refrescar();

      expect(limpio(imprimible()!.querySelector('.aviso-truncado')?.textContent)).toBe(
        'Se imprimen los primeros 2000 movimientos; acotá las fechas',
      );
    });

    it('mientras prepara el extracto no se pide dos veces; el botón queda aria-disabled (no disabled: no pierde el foco)', async () => {
      await crear();
      const respuesta = new Subject<Extracto>();
      servicio.paraImprimir.mockReturnValue(respuesta);

      imprimirBtn().click();
      await refrescar();
      expect(imprimirBtn().getAttribute('aria-disabled')).toBe('true');
      expect(imprimirBtn().disabled).toBe(false);
      imprimirBtn().click();
      expect(servicio.paraImprimir).toHaveBeenCalledTimes(1);

      respuesta.next(cuarentaYCinco());
      await refrescar();
      expect(imprimirBtn().getAttribute('aria-disabled')).toBeNull();
      expect(imprimible()).not.toBeNull();
    });

    it('si falla muestra el error y no abre el extracto', async () => {
      await crear();
      servicio.paraImprimir.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

      imprimirBtn().click();
      await refrescar();

      expect(texto('.error-imprimir')).toBe('No se pudo preparar el extracto para imprimir. Probá de nuevo.');
      expect(imprimible()).toBeNull();
      expect(imprimirBtn().getAttribute('aria-disabled')).toBeNull();
    });

    it('al cerrar el extracto (Esc) vuelve el foco a «Imprimir»', async () => {
      await crear();
      imprimirBtn().focus();
      imprimirBtn().click();
      await refrescar();
      expect(imprimible()).not.toBeNull();

      imprimible()!.querySelector('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
      await refrescar();

      expect(imprimible()).toBeNull();
      expect(document.activeElement).toBe(imprimirBtn());
    });

    it('sin movimientos no hay nada que imprimir: el botón queda deshabilitado', async () => {
      await crear({ extracto: () => of(extracto([])) });

      expect(imprimirBtn().disabled).toBe(true);
    });
  });

  describe('recarga', () => {
    it('al cambiar `version` (el padre anuló algo) vuelve a pedir la misma página y refresca la tabla', async () => {
      let sirve: Extracto = veinticinco(0);
      await crear({ extracto: (_c, _f, page) => of(page === 1 ? veinticinco(1) : sirve) });
      boton('Siguiente')!.click();
      await refrescar();
      expect(servicio.extracto).toHaveBeenCalledTimes(2);

      sirve = extracto([linea(99)]);
      fixture.componentRef.setInput('version', 1);
      await refrescar();

      expect(servicio.extracto).toHaveBeenCalledTimes(3);
      expect(servicio.extracto).toHaveBeenLastCalledWith(8, FILTRO_VACIO, 1);
    });

    it('al cambiar el cliente vuelve a la página 0 del cliente nuevo', async () => {
      await crear({ extracto: (_c, _f, page) => of(veinticinco(page)) });
      boton('Siguiente')!.click();
      await refrescar();

      fixture.componentRef.setInput('clienteId', 9);
      await refrescar();

      expect(servicio.extracto).toHaveBeenLastCalledWith(9, FILTRO_VACIO, 0);
    });

    it('una respuesta vieja que llega después de una recarga no pisa la nueva', async () => {
      const primera = new Subject<Extracto>();
      const segunda = new Subject<Extracto>();
      let n = 0;
      await crear({ extracto: () => (++n === 1 ? primera : segunda) });

      fixture.componentRef.setInput('version', 1);
      await refrescar();
      segunda.next(extracto([linea(2)]));
      primera.next(extracto([linea(1)]));
      await refrescar();

      expect(filas().map((f) => celdas(f)[1])).toEqual(['Venta N.º 2']);
    });
  });
});
