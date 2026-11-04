import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FacturaHoja } from './factura-hoja';
import { DocumentoFiscal, VentaParaFactura } from './factura.models';
import {
  LEYENDA_LEY_27618,
  MARCA_SIMULADO,
  MARCA_TICKET_PROVISORIO,
  QR_MODULOS,
  documentoAMonotributo,
  documentoARI,
  documentoB,
  documentoC,
  documentoX,
  documentoXDeA,
  ventaParaFactura,
} from './factura.testing';

registerLocaleData(localeEsAr);

describe('FacturaHoja (CU-07 paso 5, S5, S6, S12, S13)', () => {
  let fixture: ComponentFixture<FacturaHoja>;

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || null;
  const hoja = () => limpio(el().textContent);
  /** Cada renglón (`p`) de un bloque, como texto. */
  const lineas = (selector: string) =>
    Array.from(el().querySelectorAll(`${selector} p`)).map((p) => limpio(p.textContent));
  /** La letra grande y el código que va debajo, en su recuadro. */
  const letra = () => [texto('.letra .grande'), texto('.letra .codigo')];
  const filas = (selector: string) =>
    Array.from(el().querySelectorAll(`${selector} tbody tr`)).map((tr) =>
      Array.from(tr.querySelectorAll('td')).map((td) => limpio(td.textContent)),
    );

  async function crear(documento: DocumentoFiscal, venta: VentaParaFactura = ventaParaFactura(), clase?: string) {
    await TestBed.configureTestingModule({
      imports: [FacturaHoja],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(FacturaHoja);
    fixture.componentRef.setInput('documento', documento);
    fixture.componentRef.setInput('venta', venta);
    if (clase !== undefined) fixture.componentRef.setInput('clase', clase);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  /** Los datos del emisor que toda hoja lleva (RG 1415): razón social, domicilio, condición, CUIT, IIBB e inicio. */
  function esperarEmisor(condicion = 'IVA RESPONSABLE INSCRIPTO', razonSocial = 'El Tornillo SRL', cuit = '30-71234567-8') {
    const emisor = texto('.emisor')!;
    expect(emisor).toContain(razonSocial);
    expect(emisor).toContain('Domicilio comercial: Av. Luro 1234, Mar del Plata');
    expect(emisor).toContain(`Condición frente al IVA: ${condicion}`);
    const comprobante = texto('.comprobante')!;
    expect(comprobante).toContain(`CUIT: ${cuit}`);
    expect(comprobante).toContain('Ingresos Brutos: 30712345678');
    expect(comprobante).toContain('Inicio de actividades: 01/03/2015');
    expect(comprobante).toContain('Fecha de emisión: 28/10/2026');
  }

  /** El pie de la factura válida: QR a la izquierda, «Comprobante Autorizado», CAE y su vencimiento. */
  function esperarPieFiscal() {
    const pie = texto('.pie-fiscal')!;
    expect(pie).toContain('Comprobante Autorizado');
    expect(pie).toContain('CAE N.º: 70417054367476');
    expect(pie).toContain('Fecha de Vto. de CAE: 07/11/2026');
    expect(el().querySelector('.pie-fiscal svg.qr')).not.toBeNull();
  }

  it('B a Consumidor Final: emisor, letra y código, número, fecha, «A CONSUMIDOR FINAL», precios con IVA, transparencia y CAE', async () => {
    await crear(documentoB());

    esperarEmisor();
    expect(letra()).toEqual(['B', 'COD. 006']);
    expect(texto('.tipo')).toBe('FACTURA');
    expect(texto('.numero-fiscal')).toBe('N.º 00001-00000012');
    const receptor = texto('.receptor')!;
    expect(receptor).toContain('A CONSUMIDOR FINAL');
    expect(receptor).toContain('Condición frente al IVA: CONSUMIDOR FINAL');
    expect(filas('.renglones')).toEqual([['2', 'Martillo', '$ 605,00', '$ 1.210,00']]);
    expect(lineas('.totales')).toEqual(['Total $ 1.210,00']);
    const transparencia = texto('.transparencia')!;
    expect(transparencia).toContain('Régimen de Transparencia Fiscal al Consumidor (Ley 27.743)');
    expect(transparencia).toContain('IVA Contenido $ 210,00');
    expect(transparencia).toContain('Otros Impuestos Nacionales Indirectos $ 0,00');
    esperarPieFiscal();
    expect(hoja()).not.toContain('DOCUMENTO NO VALIDO COMO FACTURA');
    expect(el().querySelector('.leyenda-monotributo')).toBeNull();
  });

  it('B con descuento y recargo: muestra el subtotal, el descuento y el recargo de la venta, y el total del documento', async () => {
    await crear(documentoB(), ventaParaFactura({ subtotal: 1300, descuento: 130, recargo: 40 }));

    expect(lineas('.totales')).toEqual(['Subtotal $ 1.300,00', 'Descuento -$ 130,00', 'Recargo $ 40,00', 'Total $ 1.210,00']);
  });

  it('B con descuento de la forma (recargo negativo): subtotal y «Descuento por forma de pago», sin «Recargo»', async () => {
    await crear(documentoB(), ventaParaFactura({ subtotal: 1300, descuento: 0, recargo: -90 }));

    expect(lineas('.totales')).toEqual([
      'Subtotal $ 1.300,00',
      'Descuento por forma de pago -$ 90,00',
      'Total $ 1.210,00',
    ]);
  });

  it('B a un cliente Exento: su nombre, documento y «IVA EXENTO», sin «A CONSUMIDOR FINAL» ni transparencia', async () => {
    await crear(
      documentoB({
        receptor: {
          nombre: 'Cooperativa Puerto',
          condicionIva: 'IVA EXENTO',
          tipoDocumento: 'CUIT',
          numeroDocumento: '30744444445',
          domicilio: '12 de Octubre 3000',
          aConsumidorFinal: false,
        },
        transparencia: null,
      }),
    );

    const receptor = texto('.receptor')!;
    expect(receptor).toContain('Cooperativa Puerto');
    expect(receptor).toContain('CUIT 30-74444444-5');
    expect(receptor).toContain('Condición frente al IVA: IVA EXENTO');
    expect(receptor).toContain('Domicilio: 12 de Octubre 3000');
    expect(receptor).not.toContain('A CONSUMIDOR FINAL');
    expect(el().querySelector('.transparencia')).toBeNull();
  });

  it('A a un Responsable Inscripto: renglones sin IVA con alícuota y el neto, y el pie con el desglose guardado', async () => {
    await crear(documentoARI());

    esperarEmisor();
    expect(letra()).toEqual(['A', 'COD. 001']);
    expect(texto('.comprobante')).toContain('N.º 00001-00000005');
    const receptor = texto('.receptor')!;
    expect(receptor).toContain('Ferretería Norte SRL');
    expect(receptor).toContain('CUIT 30-71111111-2');
    expect(receptor).toContain('Condición frente al IVA: IVA RESPONSABLE INSCRIPTO');
    expect(receptor).toContain('Domicilio: Colón 2000, Mar del Plata');
    expect(receptor).not.toContain('A CONSUMIDOR FINAL');
    expect(filas('.renglones')).toEqual([['2', 'Martillo', '$ 500,00', '21 %', '$ 1.000,00']]);
    expect(lineas('.totales')).toEqual([
      'Importe neto gravado $ 1.000,00',
      'IVA 21 % $ 210,00',
      'Importe no gravado $ 0,00',
      'Importe exento $ 0,00',
      'Total $ 1.210,00',
    ]);
    esperarPieFiscal();
    expect(el().querySelector('.transparencia')).toBeNull();
    expect(el().querySelector('.leyenda-monotributo')).toBeNull();
  });

  it('A a un Monotributista: «RESPONSABLE MONOTRIBUTO» y la leyenda de la Ley 27.618, textual', async () => {
    await crear(documentoAMonotributo());

    expect(texto('.receptor')).toContain('Condición frente al IVA: RESPONSABLE MONOTRIBUTO');
    expect(texto('.leyenda-monotributo')).toBe(LEYENDA_LEY_27618);
    expect(LEYENDA_LEY_27618).toBe(
      'El crédito fiscal discriminado en el presente comprobante, sólo podrá ser computado a efectos del Régimen de Sostenimiento e Inclusión Fiscal para Pequeños Contribuyentes de la Ley Nº 27.618',
    );
    expect(letra()).toEqual(['A', 'COD. 001']);
    esperarPieFiscal();
  });

  it('C: sin IVA en los renglones ni en el pie', async () => {
    await crear(documentoC());

    esperarEmisor('RESPONSABLE MONOTRIBUTO', 'Pinturería Sol', '20-33333333-4');
    expect(letra()).toEqual(['C', 'COD. 011']);
    expect(texto('.comprobante')).toContain('N.º 00001-00000003');
    expect(filas('.renglones')).toEqual([['2', 'Martillo', '$ 605,00', '$ 1.210,00']]);
    expect(lineas('.totales')).toEqual(['Total $ 1.210,00']);
    expect(texto('.renglones')).not.toContain('%');
    expect(hoja()).not.toContain('IVA Contenido');
    expect(hoja()).not.toMatch(/neto|IVA \d/i);
    esperarPieFiscal();
  });

  it('X simulado: «X», «DOCUMENTO NO VALIDO COMO FACTURA», la marca y el número interno; sin QR, CAE, código ni número fiscal', async () => {
    await crear(documentoX(MARCA_SIMULADO));

    esperarEmisor();
    expect(letra()).toEqual(['X', null]);
    const comprobante = texto('.comprobante')!;
    expect(comprobante).toContain('DOCUMENTO NO VALIDO COMO FACTURA');
    expect(texto('.marca')).toBe('SIMULADO — sin validez fiscal');
    expect(comprobante).toContain('Venta N.º 7');
    expect(texto('.receptor')).toContain('A CONSUMIDOR FINAL');
    expect(filas('.renglones')).toEqual([['2', 'Martillo', '$ 605,00', '$ 1.210,00']]);
    expect(el().querySelector('svg.qr')).toBeNull();
    expect(el().querySelector('.pie-fiscal')).toBeNull();
    expect(hoja()).not.toContain('Comprobante Autorizado');
    expect(hoja()).not.toContain('CAE');
    expect(hoja()).not.toContain('COD.');
    expect(hoja()).not.toMatch(/\d{5}-\d{8}/);
    expect(texto('.tipo')).toBe('DOCUMENTO NO VALIDO COMO FACTURA');
  });

  it('nota de crédito B (CU-08, S8): «NOTA DE CRÉDITO» con la letra y el código 008, el número, el comprobante asociado, el CAE y el QR', async () => {
    await crear(
      documentoB({ codigo: 8, numero: 3, comprobanteAsociado: 'Factura B 00001-00000045' }),
      ventaParaFactura(),
      'NOTA_CREDITO',
    );

    expect(letra()).toEqual(['B', 'COD. 008']);
    expect(texto('.tipo')).toBe('NOTA DE CRÉDITO');
    expect(texto('.numero-fiscal')).toBe('N.º 00001-00000003');
    expect(texto('.comprobante-asociado')).toBe('Comprobante asociado: Factura B 00001-00000045');
    esperarPieFiscal();
  });

  it('nota de crédito simulada: la marca y el número interno de la nota, con el asociado del servidor y sin QR', async () => {
    await crear(
      documentoX(MARCA_SIMULADO, { comprobanteAsociado: `X · ${MARCA_SIMULADO}` }),
      ventaParaFactura({ numero: 9 }),
      'NOTA_CREDITO',
    );

    expect(letra()).toEqual(['X', null]);
    expect(texto('.tipo')).toBe('DOCUMENTO NO VALIDO COMO FACTURA');
    expect(texto('.marca')).toBe(MARCA_SIMULADO);
    expect(texto('.numero-interno')).toBe('Nota de crédito N.º 9');
    expect(texto('.comprobante-asociado')).toBe(`Comprobante asociado: X · ${MARCA_SIMULADO}`);
    expect(el().querySelector('svg.qr')).toBeNull();
  });

  it('ticket provisorio (producción sin CAE): la marca del caso y el resto como el X', async () => {
    await crear(documentoX(MARCA_TICKET_PROVISORIO));

    expect(letra()).toEqual(['X', null]);
    expect(texto('.comprobante')).toContain('DOCUMENTO NO VALIDO COMO FACTURA');
    expect(texto('.marca')).toBe('TICKET PROVISORIO — factura en trámite con ARCA');
    expect(texto('.comprobante')).toContain('Venta N.º 7');
    expect(el().querySelector('svg.qr')).toBeNull();
    expect(hoja()).not.toContain('CAE');
  });

  it('aunque el documento no válido traiga letra, código, número o CAE, la hoja no los muestra', async () => {
    await crear(
      documentoX(MARCA_SIMULADO, { letra: 'B', codigo: 6, puntoVenta: 1, numero: 12, cae: '70417054367476', qrModulos: [...QR_MODULOS] }),
    );

    expect(letra()).toEqual(['X', null]);
    expect(el().querySelector('svg.qr')).toBeNull();
    expect(hoja()).not.toContain('CAE');
    expect(hoja()).not.toContain('70417054367476');
    expect(hoja()).not.toMatch(/\d{5}-\d{8}/);
  });

  it('X de una A: el cuerpo de la A (renglones sin IVA con alícuota, neto e IVA por alícuota) con «X», la marca y el número interno; sin código, número fiscal, CAE ni QR', async () => {
    await crear(documentoXDeA(MARCA_SIMULADO));

    expect(letra()).toEqual(['X', null]);
    expect(texto('.tipo')).toBe('DOCUMENTO NO VALIDO COMO FACTURA');
    expect(texto('.marca')).toBe('SIMULADO — sin validez fiscal');
    expect(texto('.comprobante')).toContain('Venta N.º 7');
    expect(texto('.receptor')).toContain('Ferretería Norte SRL');
    expect(filas('.renglones')).toEqual([['2', 'Martillo', '$ 500,00', '21 %', '$ 1.000,00']]);
    expect(lineas('.totales')).toEqual([
      'Importe neto gravado $ 1.000,00',
      'IVA 21 % $ 210,00',
      'Importe no gravado $ 0,00',
      'Importe exento $ 0,00',
      'Total $ 1.210,00',
    ]);
    expect(el().querySelector('.pie-fiscal')).toBeNull();
    expect(el().querySelector('svg.qr')).toBeNull();
    expect(hoja()).not.toContain('Comprobante Autorizado');
    expect(hoja()).not.toContain('CAE');
    expect(hoja()).not.toContain('COD.');
    expect(hoja()).not.toMatch(/\d{5}-\d{8}/);
  });

  it('un X de una A que trae letra, código, número, CAE, vencimiento, QR y la leyenda no muestra nada fiscal, pero sí el neto y las alícuotas', async () => {
    await crear(
      documentoXDeA(MARCA_SIMULADO, {
        letra: 'A',
        codigo: 1,
        puntoVenta: 3,
        numero: 12,
        cae: '70417054367476',
        caeVencimiento: '2026-11-07',
        qr: 'https://www.arca.gob.ar/fe/qr/?p=eyJ2ZXIiOjF9',
        qrModulos: [...QR_MODULOS],
        leyendaMonotributo: LEYENDA_LEY_27618,
      }),
    );

    expect(letra()).toEqual(['X', null]);
    expect(texto('.tipo')).toBe('DOCUMENTO NO VALIDO COMO FACTURA');
    expect(el().querySelector('svg')).toBeNull();
    expect(el().querySelector('.pie-fiscal')).toBeNull();
    expect(el().querySelector('.numero-fiscal')).toBeNull();
    expect(hoja()).not.toContain('COD.');
    expect(hoja()).not.toContain('CAE');
    expect(hoja()).not.toContain('70417054367476');
    expect(hoja()).not.toContain('07/11/2026');
    expect(hoja()).not.toContain('Comprobante Autorizado');
    expect(hoja()).not.toMatch(/\d{5}-\d{8}/);
    expect(texto('.leyenda-monotributo')).toBe(LEYENDA_LEY_27618);
    expect(lineas('.totales')).toContain('Importe neto gravado $ 1.000,00');
    expect(lineas('.totales')).toContain('IVA 21 % $ 210,00');
  });

  it('con remito vinculado dice «Remito N.º 15»; sin remito no dice nada', async () => {
    await crear(documentoB(), ventaParaFactura({ remitoNumero: 15 }));
    expect(texto('.remito')).toBe('Remito N.º 15');

    TestBed.resetTestingModule();
    await crear(documentoB());
    expect(el().querySelector('.remito')).toBeNull();
  });

  it('dibuja el QR fila por fila con la zona de silencio, y sin `qrModulos` no hay QR', async () => {
    await crear(documentoB());

    const svg = el().querySelector('svg.qr')!;
    expect(svg.getAttribute('viewBox')).toBe('-4 -4 15 15');
    const rects = Array.from(svg.querySelectorAll('rect')).map((r) =>
      ['x', 'y', 'width'].map((a) => Number(r.getAttribute(a))),
    );
    // Fila 0 «1110111»: dos tiradas, (0,0,3) y (4,0,3); la fila 3 «0000000» no tiene ninguna.
    expect(rects.slice(0, 2)).toEqual([
      [0, 0, 3],
      [4, 0, 3],
    ]);
    expect(rects.some(([, y]) => y === 3)).toBe(false);

    TestBed.resetTestingModule();
    await crear(documentoB({ qrModulos: null }));
    expect(el().querySelector('svg.qr')).toBeNull();
    expect(texto('.pie-fiscal')).toContain('Comprobante Autorizado');
  });
});
