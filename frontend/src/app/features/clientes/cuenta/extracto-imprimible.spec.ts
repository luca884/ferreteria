import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AuthService } from '../../../core/auth/auth.service';
import { Extracto, LineaExtracto } from './extracto.models';
import { ExtractoImprimible, LEYENDA_EXTRACTO } from './extracto-imprimible';

registerLocaleData(localeEsAr);

describe('ExtractoImprimible (CC-099)', () => {
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

  const extracto = (items: LineaExtracto[], cambios: Partial<Extracto> = {}): Extracto => ({
    clienteId: 8,
    clienteNombre: 'Ana Gómez',
    tipoDocumento: null,
    numeroDocumento: null,
    saldoActual: 1200,
    desde: '2026-10-01',
    hasta: '2026-10-31',
    tipo: null,
    saldoInicial: 300,
    totalDebe: 1500,
    totalHaber: 600,
    saldoFinal: 1200,
    movimientos: { items, page: 0, size: 2000, totalElements: items.length, totalPages: 1 },
    truncado: false,
    ...cambios,
  });

  let fixture: ComponentFixture<ExtractoImprimible>;
  let cerradas: number;
  let imprimir: ReturnType<typeof vi.spyOn>;

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || null;
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => limpio(b.textContent) === nombre)!;
  const filas = () => Array.from(el().querySelectorAll<HTMLTableRowElement>('table.lineas tbody tr'));
  const celdas = (fila: HTMLTableRowElement) => Array.from(fila.querySelectorAll('td')).map((c) => limpio(c.textContent));

  function tecla(key: string, destino: Element = el().querySelector('[role="dialog"]')!): KeyboardEvent {
    const evento = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    destino.dispatchEvent(evento);
    return evento;
  }

  async function abrir(e: Extracto = extracto([linea(2), linea(1)]), ferreteriaNombre: string | null = 'Tornillo SA') {
    imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);
    await TestBed.configureTestingModule({
      imports: [ExtractoImprimible],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: AuthService, useValue: { sesion: signal({ ferreteriaNombre }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ExtractoImprimible);
    cerradas = 0;
    fixture.componentRef.setInput('extracto', e);
    fixture.componentInstance.cerrar.subscribe(() => cerradas++);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => {
    // 21/10/2026 15:30 UTC = 12:30 en Buenos Aires.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-21T15:30:00Z'));
  });

  afterEach(() => {
    imprimir?.mockRestore();
    vi.useRealTimers();
  });

  it('lo que sale en la hoja (el artículo) está dentro del diálogo y los botones quedan afuera (contrato con @media print de styles.css, CC-099)', async () => {
    await abrir();

    const articulo = el().querySelector('article.extracto-imprimible')!;
    expect(articulo.closest('.dialogo-fondo > [role="dialog"]')).not.toBeNull();
    const botones = Array.from(el().querySelectorAll('button'));
    expect(botones.map((b) => limpio(b.textContent))).toEqual(['Cerrar', 'Imprimir']);
    expect(botones.every((b) => !articulo.contains(b))).toBe(true);
  });

  it('es un diálogo modal titulado «Extracto de cuenta corriente»', async () => {
    await abrir();

    const dialogo = el().querySelector('[role="dialog"]')!;
    expect(dialogo.getAttribute('aria-modal')).toBe('true');
    expect(texto('#extracto-titulo')).toBe('Extracto de cuenta corriente');
    expect(dialogo.getAttribute('aria-labelledby')).toBe('extracto-titulo');
  });

  it('el encabezado lleva la ferretería de la sesión, la razón social del cliente, el período y el tipo', async () => {
    await abrir(extracto([linea(1)], { tipo: 'COBRO' }));

    expect(el().querySelector('article.extracto-imprimible')).not.toBeNull();
    expect(texto('.ferreteria')).toBe('Tornillo SA');
    expect(texto('.cliente')).toContain('Ana Gómez');
    expect(texto('.periodo')).toContain('01/10/2026 al 31/10/2026');
    expect(texto('.tipo-filtro')).toContain('Cobro');
  });

  it('el impreso lleva la razón social del cliente y no su documento (CU-12.6, QA 05/11)', async () => {
    await abrir(extracto([linea(1)], { tipoDocumento: 'CUIT', numeroDocumento: '30712345671' }));

    expect(texto('.cliente')).toContain('Ana Gómez');
    expect(el().querySelector('.documento')).toBeNull();
    expect(el().textContent).not.toContain('30-71234567-1');
    expect(el().textContent).not.toContain('Documento');
  });

  it('sin fechas dice «todo el historial» y sin tipo dice «Todos»', async () => {
    await abrir(extracto([linea(1)], { desde: null, hasta: null, tipo: null }));

    expect(texto('.periodo')).toContain('todo el historial');
    expect(texto('.tipo-filtro')).toContain('Todos');
  });

  it('sin ferretería en la sesión no muestra el renglón', async () => {
    await abrir(extracto([linea(1)]), null);

    expect(el().querySelector('.ferreteria')).toBeNull();
  });

  it('muestra el saldo inicial, los totales y el saldo final con 2 decimales', async () => {
    await abrir(extracto([linea(1)], { saldoInicial: 300, totalDebe: 1600, totalHaber: 600.5, saldoFinal: 1299.5 }));

    expect(texto('.saldo-inicial')).toContain('$ 300,00');
    expect(texto('.total-debe')).toContain('$ 1.600,00');
    expect(texto('.total-haber')).toContain('$ 600,50');
    expect(texto('.saldo-final')).toContain('$ 1.299,50');
  });

  it('la tabla lista fecha, tipo, comprobante, debe, haber y saldo real, en el mismo orden de columnas que la pantalla (CC-096) y más nuevo arriba', async () => {
    await abrir(
      extracto([
        linea(3, { fecha: '2026-10-20T23:59:59', tipo: 'COBRO', referencia: 'Recibo N.º 14', debe: 0, haber: 500, saldo: 700, reciboId: 14, comprobanteId: null }),
        linea(2, { fecha: '2026-10-19T08:05:00', referencia: 'Venta N.º 7', debe: 1200, haber: 0, saldo: 1200 }),
      ]),
    );

    expect(filas().map(celdas)).toEqual([
      ['20/10/2026 23:59', 'Cobro', 'Recibo N.º 14', '', '$ 500,00', '$ 700,00'],
      ['19/10/2026 08:05', 'Venta', 'Venta N.º 7', '$ 1.200,00', '', '$ 1.200,00'],
    ]);
  });

  it('los encabezados del impreso van en el orden de la pantalla: Fecha, Tipo, Comprobante, Debe, Haber, Saldo', async () => {
    await abrir();

    const encabezados = Array.from(el().querySelectorAll('table.lineas thead th')).map((c) => limpio(c.textContent));
    expect(encabezados).toEqual(['Fecha', 'Tipo', 'Comprobante', 'Debe', 'Haber', 'Saldo']);
  });

  it('un cobro anulado se marca «ANULADO» (como la pantalla, CC-096) y la anulación muestra su motivo', async () => {
    await abrir(
      extracto([
        linea(2, { tipo: 'ANULACION', referencia: 'Anulación Recibo N.º 14', reciboId: 14, comprobanteId: null, motivo: 'Cliente equivocado' }),
        linea(1, { tipo: 'COBRO', referencia: 'Recibo N.º 14', debe: 0, haber: 500, reciboId: 14, comprobanteId: null, anulado: true }),
      ]),
    );

    const [anulacion, cobro] = filas();
    expect(limpio(anulacion.textContent)).toContain('Motivo: Cliente equivocado');
    expect(limpio(cobro.querySelector('.marca-anulado')?.textContent)).toBe('ANULADO');
    expect(anulacion.querySelector('.marca-anulado')).toBeNull();
  });

  it('un ajuste se imprime con su referencia y su motivo', async () => {
    await abrir(
      extracto([
        linea(1, { tipo: 'AJUSTE', referencia: 'Recargo N.º 3', debe: 500, haber: 0, reciboId: null, comprobanteId: null, motivo: 'Deuda vieja' }),
      ]),
    );

    const [ajuste] = filas();
    expect(limpio(ajuste.textContent)).toContain('Recargo N.º 3');
    expect(limpio(ajuste.textContent)).toContain('Motivo: Deuda vieja');
  });

  it('la nota de crédito se imprime como «Nota de crédito» con su referencia, al haber y sin marca de anulado', async () => {
    await abrir(
      extracto([linea(1, { tipo: 'NC', referencia: 'Nota de crédito N.º 9', debe: 0, haber: 1200, saldo: 0 })]),
    );

    const [nota] = filas();
    expect(celdas(nota).slice(1, 5)).toEqual(['Nota de crédito', 'Nota de crédito N.º 9', '', '$ 1.200,00']);
    expect(nota.querySelector('.marca-anulado')).toBeNull();
  });

  it('45 movimientos se imprimen los 45 (un solo extracto, sin paginar)', async () => {
    const todos = Array.from({ length: 45 }, (_, i) => linea(45 - i));
    await abrir(extracto(todos));

    expect(filas()).toHaveLength(45);
    expect(celdas(filas()[0])[2]).toBe('Venta N.º 45');
    expect(celdas(filas()[44])[2]).toBe('Venta N.º 1');
  });

  it('lleva «Emitido el …» en hora de Buenos Aires y la leyenda de resumen interno, no fiscal', async () => {
    await abrir();

    expect(texto('.emitido')).toBe('Emitido el 21/10/2026 12:30');
    expect(texto('.leyenda')).toBe('Resumen interno de cuenta, no válido como comprobante fiscal');
    expect(LEYENDA_EXTRACTO).toBe('Resumen interno de cuenta, no válido como comprobante fiscal');
  });

  describe('truncado', () => {
    it('avisa «Se imprimen los primeros 2000 movimientos; acotá las fechas» dentro del documento (sale en pantalla y en la impresión)', async () => {
      await abrir(extracto([linea(1)], { truncado: true }));

      expect(texto('article.extracto-imprimible .aviso-truncado')).toBe('Se imprimen los primeros 2000 movimientos; acotá las fechas');
    });

    it('sin truncar no hay aviso', async () => {
      await abrir(extracto([linea(1)]));

      expect(el().querySelector('.aviso-truncado')).toBeNull();
    });

    it('truncado igual se puede imprimir lo que hay (los primeros 2000): el botón sigue habilitado', async () => {
      await abrir(extracto([linea(1)], { truncado: true }));

      expect(boton('Imprimir').disabled).toBe(false);
      boton('Imprimir').click();
      expect(imprimir).toHaveBeenCalledTimes(1);
    });
  });

  describe('teclado y foco', () => {
    it('el foco arranca en Imprimir', async () => {
      await abrir();

      expect(document.activeElement).toBe(boton('Imprimir'));
    });

    it('Enter imprime con window.print()', async () => {
      await abrir();

      const evento = tecla('Enter');

      expect(imprimir).toHaveBeenCalledTimes(1);
      expect(evento.defaultPrevented).toBe(true);
    });

    it('Enter sobre «Cerrar» cierra y no imprime (en un botón Enter es su clic)', async () => {
      await abrir();

      tecla('Enter', boton('Cerrar'));

      expect(imprimir).not.toHaveBeenCalled();
    });

    it('Esc cierra sin imprimir', async () => {
      await abrir();

      const evento = tecla('Escape');

      expect(cerradas).toBe(1);
      expect(imprimir).not.toHaveBeenCalled();
      expect(evento.defaultPrevented).toBe(true);
    });

    it('los botones llevan su atajo (Esc para Cerrar, Enter para Imprimir) y Cerrar cierra', async () => {
      await abrir();

      expect(boton('Cerrar').getAttribute('data-tecla')).toBe('Esc');
      expect(boton('Imprimir').getAttribute('data-tecla')).toBe('Enter');
      boton('Cerrar').click();
      expect(cerradas).toBe(1);
    });
  });
});
