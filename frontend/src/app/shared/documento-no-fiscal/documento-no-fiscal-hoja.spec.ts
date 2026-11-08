import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DatosFiscales } from '../../features/datos-fiscales/datos-fiscales.service';
import { DocumentoNoFiscalHoja } from './documento-no-fiscal-hoja';
import { DocumentoNoFiscal, LEYENDA_DOCUMENTO_NO_FISCAL } from './documento-no-fiscal.models';
import { documentoDePrueba, emisorDePrueba } from './documento-no-fiscal.testing';

registerLocaleData(localeEsAr);

describe('DocumentoNoFiscalHoja', () => {
  let fixture: ComponentFixture<DocumentoNoFiscalHoja>;

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || null;

  async function armar(
    documento: DocumentoNoFiscal = documentoDePrueba(),
    emisor: DatosFiscales = emisorDePrueba(),
  ) {
    await TestBed.configureTestingModule({
      imports: [DocumentoNoFiscalHoja],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(DocumentoNoFiscalHoja);
    fixture.componentRef.setInput('documento', documento);
    fixture.componentRef.setInput('emisor', emisor);
    fixture.detectChanges();
  }

  it('lleva el emisor con el CUIT con guiones, la X, la leyenda y «Remito N.º n» con su fecha', async () => {
    await armar();

    const hoja = texto('.encabezado')!;
    expect(texto('.razon-social')).toBe('Tornillo SA');
    expect(hoja).toContain('Domicilio comercial: Av. Luro 3200, Mar del Plata');
    expect(hoja).toContain('Condición frente al IVA: Responsable inscripto');
    expect(hoja).toContain('CUIT: 30-71234567-8');
    expect(hoja).toContain('Ingresos Brutos: 901-123456-7');
    expect(hoja).toContain('Inicio de actividades: 01/03/2015');
    expect(texto('.letra .grande')).toBe('X');
    expect(texto('.tipo')).toBe(LEYENDA_DOCUMENTO_NO_FISCAL);
    expect(LEYENDA_DOCUMENTO_NO_FISCAL).toBe('DOCUMENTO NO VALIDO COMO FACTURA');
    expect(texto('.numero-interno')).toBe('Remito N.º 12');
    expect(hoja).toContain('Fecha: 22/10/2026 16:05');
  });

  it('formatea el CUIT con guiones aunque llegue solo con dígitos', async () => {
    await armar(documentoDePrueba(), emisorDePrueba({ cuit: '30712345678' }));

    expect(texto('.encabezado')).toContain('CUIT: 30-71234567-8');
  });

  it('el cliente, los renglones, el descuento, el total y las observaciones', async () => {
    await armar();

    const hoja = texto('.receptor')!;
    expect(hoja).toContain('Construcciones Pérez');
    const filas = Array.from(el().querySelectorAll('tbody tr')).map((f) =>
      Array.from(f.querySelectorAll('td'))
        .map((c) => limpio(c.textContent))
        .filter((c) => c !== '')
        .join(' '),
    );
    expect(filas[0]).toBe('2 unidades Martillo $ 1.000,00 10 % $ 1.800,00');
    expect(filas[1]).toBe('1,5 kilos Clavos $ 400,00 / kg $ 600,00');
    const totales = texto('.totales')!;
    expect(totales).toContain('Subtotal $ 2.600,00');
    expect(totales).toContain('Descuento −$ 200,00');
    expect(texto('.total')).toBe('Total $ 2.400,00');
    expect(texto('.observaciones')).toContain('Entregar por la puerta de atrás');
  });

  // Bugs de la guía, S18: la hoja del presupuesto y del remito mostraba la cantidad sin la unidad («26,5» en vez de
  // «26,5 metros»).
  it('la cantidad sale con su unidad, en singular solo para 1, como en el resto de la app', async () => {
    const renglon = documentoDePrueba().renglones[0];
    await armar(
      documentoDePrueba({
        renglones: [
          { ...renglon, renglon: 1, descripcion: 'Cable', unidad: 'METRO', cantidad: 26.5 },
          { ...renglon, renglon: 2, descripcion: 'Martillo', unidad: 'UNIDAD', cantidad: 1 },
          { ...renglon, renglon: 3, descripcion: 'Pintura', unidad: 'LITRO', cantidad: 1250 },
        ],
      }),
    );

    const cantidades = Array.from(el().querySelectorAll('tbody tr')).map((f) =>
      limpio(f.querySelector('td')?.textContent),
    );
    expect(cantidades).toEqual(['26,5 metros', '1 unidad', '1.250 litros']);
  });

  it('el precio unitario dice por qué unidad es: «/ m», «/ l»; por pieza y el importe no llevan (#36)', async () => {
    const renglon = documentoDePrueba().renglones[0];
    await armar(
      documentoDePrueba({
        renglones: [
          { ...renglon, renglon: 1, descripcion: 'Cable', unidad: 'METRO' },
          { ...renglon, renglon: 2, descripcion: 'Martillo', unidad: 'UNIDAD' },
          { ...renglon, renglon: 3, descripcion: 'Pintura', unidad: 'LITRO' },
        ],
      }),
    );

    // Cantidad, descripción, precio unitario, descuento e importe.
    const precios = Array.from(el().querySelectorAll('tbody tr')).map((f) => {
      const celdas = f.querySelectorAll('td');
      return [limpio(celdas[2].textContent), limpio(celdas[4].textContent)];
    });
    expect(precios).toEqual([
      ['$ 1.000,00 / m', '$ 1.800,00'],
      ['$ 1.000,00', '$ 1.800,00'],
      ['$ 1.000,00 / l', '$ 1.800,00'],
    ]);
  });

  it('sin cliente, sin descuento y sin observaciones no muestra esas partes', async () => {
    await armar(
      documentoDePrueba({
        clienteNombre: undefined,
        observaciones: undefined,
        descuento: 0,
        subtotal: 2400,
      }),
    );

    expect(el().querySelector('.receptor')).toBeNull();
    expect(el().querySelector('.observaciones')).toBeNull();
    expect(texto('.totales')).not.toContain('Subtotal');
    expect(texto('.totales')).not.toContain('Descuento');
  });

  it('el descuento general dice su porcentaje si ningún renglón tiene descuento propio', async () => {
    await armar(
      documentoDePrueba({
        renglones: [
          {
            renglon: 1,
            descripcion: 'Martillo',
            unidad: 'UNIDAD',
            cantidad: 1,
            precioUnitario: 1000,
            descuentoPorcentaje: 0,
            total: 900,
          },
        ],
        subtotal: 1000,
        descuentoPorcentaje: 10,
        descuento: 100,
        total: 900,
      }),
    );

    expect(texto('.totales')).toContain('Descuento (10 %) −$ 100,00');
  });

  it('el presupuesto dice «Presupuesto N.º n» y «Válido hasta dd/MM/aaaa»', async () => {
    await armar(documentoDePrueba({ tipo: 'Presupuesto', numero: 5, validoHasta: '2026-11-06' }));

    expect(texto('.numero-interno')).toBe('Presupuesto N.º 5');
    expect(texto('.valido-hasta')).toBe('Válido hasta 06/11/2026');
  });

  it('el remito facturado dice en qué venta, y la factura si viene', async () => {
    await armar(
      documentoDePrueba({
        facturadoPor: { ventaId: 40, numero: 33, factura: 'Factura B 00001-00000012' },
      }),
    );

    expect(texto('.facturado-por')).toBe('Facturado en la venta N.º 33 · Factura B 00001-00000012');
  });

  it('el remito facturado sin factura válida dice solo la venta', async () => {
    await armar(documentoDePrueba({ facturadoPor: { ventaId: 40, numero: 33 } }));

    expect(texto('.facturado-por')).toBe('Facturado en la venta N.º 33');
  });

  it('un presupuesto pasado a venta dice «Vendido en la venta», nunca «Facturado» (arreglos-confusos, S5)', async () => {
    await armar(
      documentoDePrueba({
        tipo: 'Presupuesto',
        validoHasta: '2026-11-06',
        facturadoPor: { ventaId: 40, numero: 33, factura: 'Factura B 00001-00000012' },
      }),
    );

    expect(texto('.facturado-por')).toBe('Vendido en la venta N.º 33 · Factura B 00001-00000012');
    expect(limpio(el().textContent)).not.toContain('Facturado');
  });

  it('un remito que no está facturado ni es presupuesto no dice ni «Facturado» ni «Válido hasta»', async () => {
    await armar();

    expect(el().querySelector('.facturado-por')).toBeNull();
    expect(el().querySelector('.valido-hasta')).toBeNull();
  });

  it('nunca sale con letra A, B ni C, código, número fiscal, CAE ni QR', async () => {
    await armar(
      documentoDePrueba({
        facturadoPor: { ventaId: 40, numero: 33, factura: 'Factura B 00001-00000012' },
      }),
    );

    const hoja = limpio(el().textContent);
    expect(texto('.letra')).toBe('X');
    expect(hoja).not.toMatch(/COD\./);
    expect(hoja).not.toMatch(/CAE/);
    expect(hoja).not.toContain('Autorizado');
    expect(el().querySelector('svg, .qr, .pie-fiscal')).toBeNull();
  });
});
