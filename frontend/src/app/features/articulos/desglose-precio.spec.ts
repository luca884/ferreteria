import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, FormGroup } from '@angular/forms';
import { DesglosePrecio } from './articulo.models';
import {
  DesglosePrecioVista,
  GrupoAjustes,
  cadenaDelDesglose,
  errorDeAjuste,
} from './desglose-precio';

const MOTIVO =
  'Este artículo no tiene lista de proveedor. Los ajustes se usan solo con artículos que vienen de una lista.';

/** El artículo del ejemplo de R-209: lista 1.000,00, bonificación 10, ajustes -5 y +3, margen 30, IVA 21, múltiplo 10. */
const desgloseBase = (cambios: Partial<DesglosePrecio> = {}): DesglosePrecio => ({
  articuloId: 5,
  tieneLista: true,
  motivoSinLista: null,
  moneda: 'ARS',
  precioLista: 1000,
  listaIncluyeIva: false,
  alicuotaIva: 'IVA_21',
  bonificacion1: 10,
  bonificacion2: null,
  bonificacion3: null,
  ajuste1: -5,
  ajuste2: 3,
  ajuste3: null,
  pasos: [],
  costoDeLista: 880.65,
  costoGuardado: 880.65,
  coincide: true,
  margen: 30,
  origenMargen: 'PROPIO',
  cotizacion: null,
  cotizacionCongelada: false,
  multiplo: 10,
  precioFinal: 1390,
  ...cambios,
});

const sinLista = (cambios: Partial<DesglosePrecio> = {}): DesglosePrecio =>
  desgloseBase({
    tieneLista: false,
    motivoSinLista: MOTIVO,
    precioLista: null,
    listaIncluyeIva: null,
    bonificacion1: null,
    ajuste1: null,
    ajuste2: null,
    costoDeLista: null,
    coincide: null,
    costoGuardado: 500,
    precioFinal: 790,
    ...cambios,
  });

const grupoDe = (a1: number | null, a2: number | null, a3: number | null): GrupoAjustes =>
  new FormGroup({
    ajuste1: new FormControl<number | null>(a1),
    ajuste2: new FormControl<number | null>(a2),
    ajuste3: new FormControl<number | null>(a3),
  });

describe('DesglosePrecioVista', () => {
  let fixture: ComponentFixture<DesglosePrecioVista>;
  let grupo: GrupoAjustes;

  async function crear(
    desglose: DesglosePrecio,
    opciones: { dueno?: boolean; ajustes?: GrupoAjustes; cotizacionDesde?: string | null } = {},
  ) {
    const {
      dueno = true,
      ajustes = grupoDe(desglose.ajuste1, desglose.ajuste2, desglose.ajuste3),
    } = opciones;
    grupo = ajustes;
    await TestBed.configureTestingModule({ imports: [DesglosePrecioVista] }).compileComponents();
    fixture = TestBed.createComponent(DesglosePrecioVista);
    fixture.componentRef.setInput('desglose', desglose);
    fixture.componentRef.setInput('ajustes', ajustes);
    fixture.componentRef.setInput('esDueno', dueno);
    fixture.componentRef.setInput('cotizacionDesde', opciones.cotizacionDesde ?? null);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  /** Sin espacios duplicados ni no separables: así se compara el texto como se lee. */
  const limpio = (texto: string | null | undefined) => (texto ?? '').replace(/[\s ]+/g, ' ').trim();
  const textos = (selector: string) =>
    Array.from(el().querySelectorAll(selector)).map((n) => limpio(n.textContent));
  const campo = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
  const describedby = (id: string) =>
    (campo(id).getAttribute('aria-describedby') ?? '')
      .split(' ')
      .filter(Boolean)
      .map((d) => limpio(el().querySelector(`[id="${d}"]`)?.textContent));

  async function escribir(id: string, valor: string) {
    campo(id).value = valor;
    campo(id).dispatchEvent(new Event('input'));
    await refrescar();
  }

  describe('la cadena (R-209)', () => {
    it('muestra lista → bonificación → ajustes → costo → margen → IVA → redondeo → precio final en formato argentino', async () => {
      await crear(desgloseBase());

      expect(textos('.paso-importe')).toEqual([
        '$ 1.000,00',
        '$ 900,00',
        '$ 855,00',
        '$ 880,65',
        '$ 880,65',
        '$ 1.144,845',
        '$ 1.385,2625',
        '$ 1.390,00',
      ]);
      expect(textos('.paso-nombre')).toEqual([
        'Precio de lista',
        'Bonificación del proveedor, descuenta 10 %',
        'Descuento propio de 5 %',
        'Recargo propio de 3 %',
        'Costo',
        'Margen de 30 %',
        'IVA de 21 %',
        'Redondeo hacia arriba al múltiplo de 10',
        'Precio final',
      ]);
    });

    it('con la lista con IVA incluido muestra el paso «sin IVA»', async () => {
      await crear(
        desgloseBase({
          precioLista: 1210,
          listaIncluyeIva: true,
          bonificacion1: null,
          ajuste1: 10,
          ajuste2: null,
          costoDeLista: 1100,
          costoGuardado: 1100,
          precioFinal: 1740,
        }),
      );

      expect(textos('.paso-nombre').slice(0, 4)).toEqual([
        'Precio de lista',
        'Sin el IVA de la lista (21 %)',
        'Recargo propio de 10 %',
        'Costo',
      ]);
      expect(textos('.paso-importe').slice(0, 4)).toEqual([
        '$ 1.210,00',
        '$ 1.000,00',
        '$ 1.100,00',
        '$ 1.100,00',
      ]);
      expect(textos('.paso-importe').at(-1)).toBe('$ 1.740,00');
    });

    it('un artículo en dólares usa «US$» hasta el costo y «$» desde la cotización, y muestra el dólar y su fecha', async () => {
      await crear(
        desgloseBase({
          moneda: 'USD',
          precioLista: 10,
          bonificacion1: 10,
          ajuste1: -5,
          ajuste2: 3,
          costoDeLista: 8.81,
          costoGuardado: 8.81,
          cotizacion: 1000,
          precioFinal: 13860,
        }),
        { cotizacionDesde: '2026-10-16T11:00:00Z' },
      );

      expect(textos('.paso-importe')).toEqual([
        'US$ 10,00',
        'US$ 9,00',
        'US$ 8,55',
        'US$ 8,8065',
        'US$ 8,81',
        '$ 8.810,00',
        '$ 11.453,00',
        '$ 13.858,13',
        '$ 13.860,00',
      ]);
      expect(textos('.paso-nombre')).toContain('Pasado a pesos con el dólar a 1.000,00');
      const dolar = limpio(el().querySelector('.cotizacion')?.textContent);
      expect(dolar).toContain('1.000,00');
      expect(dolar).toContain('16/10 08:00');
    });

    it('con la cotización apagada lo dice', async () => {
      await crear(
        desgloseBase({
          moneda: 'USD',
          precioLista: 10,
          cotizacion: 1000,
          cotizacionCongelada: true,
        }),
      );

      expect(limpio(el().querySelector('.cotizacion')?.textContent)).toContain(
        'La cotización está apagada: se usa el último dólar guardado.',
      );
    });

    it('en pesos no hay cotización ni paso de cotización', async () => {
      await crear(desgloseBase());

      expect(el().querySelector('.cotizacion')).toBeNull();
      expect(textos('.paso-nombre').join('|')).not.toContain('dólar');
    });

    it('un artículo en dólares sin ninguna cotización termina en el costo y lo explica', async () => {
      await crear(desgloseBase({ moneda: 'USD', precioLista: 10, cotizacion: null }));

      expect(textos('.paso-nombre').at(-1)).toBe('Costo');
      expect(limpio(el().querySelector('.cotizacion')?.textContent)).toContain(
        'Todavía no hay cotización del dólar.',
      );
    });
  });

  describe('bonificaciones y ajustes (R-218)', () => {
    it('las bonificaciones guardadas van en solo lectura con su propia etiqueta', async () => {
      await crear(desgloseBase({ bonificacion2: 5, bonificacion3: 2.5 }));

      const etiqueta = limpio(el().querySelector('.bonificaciones dt')?.textContent);
      expect(etiqueta).toBe('Bonificación del proveedor (positivo descuenta)');
      expect(limpio(el().querySelector('.bonificaciones dd')?.textContent)).toBe(
        '10 %, 5 % y 2,5 %',
      );
      expect(el().querySelector('.bonificaciones input')).toBeNull();
    });

    it('sin bonificaciones dice «Ninguna»', async () => {
      await crear(desgloseBase({ bonificacion1: null }));

      expect(limpio(el().querySelector('.bonificaciones dd')?.textContent)).toBe('Ninguna');
    });

    it('los tres ajustes del Dueño llevan «Negativo es descuento, positivo es recargo.» y arrancan con lo guardado', async () => {
      await crear(desgloseBase());

      expect(el().querySelectorAll('.ajustes input').length).toBe(3);
      expect(campo('ajuste1').value).toBe('-5');
      expect(campo('ajuste2').value).toBe('3');
      expect(campo('ajuste3').value).toBe('');
      expect(limpio(el().querySelector('#ajustes-pista')?.textContent)).toBe(
        'Negativo es descuento, positivo es recargo.',
      );
      for (const id of ['ajuste1', 'ajuste2', 'ajuste3']) {
        expect(describedby(id)).toContain('Negativo es descuento, positivo es recargo.');
      }
    });

    it('los campos se llaman «Ajuste 1», «Ajuste 2» y «Ajuste 3», en porcentaje', async () => {
      await crear(desgloseBase());

      expect(textos('.ajustes label')).toEqual(['Ajuste 1 (%)', 'Ajuste 2 (%)', 'Ajuste 3 (%)']);
      expect(campo('ajuste1').getAttribute('inputmode')).toBe('decimal');
      expect(campo('ajuste1').getAttribute('step')).toBe('0.01');
    });
  });

  describe('costo de la lista contra costo guardado (R-210)', () => {
    it('si coincide no muestra la comparación', async () => {
      await crear(desgloseBase());

      expect(el().querySelector('.no-coincide')).toBeNull();
    });

    it('si no coincide muestra las dos cifras y que el guardado se cambió a mano o por un masivo', async () => {
      await crear(desgloseBase({ costoDeLista: 880.65, costoGuardado: 990, coincide: false }));

      const aviso = limpio(el().querySelector('.no-coincide')?.textContent);
      expect(aviso).toContain('Costo de la lista $ 880,65');
      expect(aviso).toContain('Costo guardado $ 990,00');
      expect(aviso).toContain('El costo guardado se cambió a mano o por una actualización masiva.');
    });

    it('en dólares las dos cifras llevan «US$»', async () => {
      await crear(
        desgloseBase({
          moneda: 'USD',
          precioLista: 10,
          costoDeLista: 9,
          costoGuardado: 9.5,
          coincide: false,
          cotizacion: 1000,
        }),
      );

      const aviso = limpio(el().querySelector('.no-coincide')?.textContent);
      expect(aviso).toContain('Costo de la lista US$ 9,00');
      expect(aviso).toContain('Costo guardado US$ 9,50');
    });

    it('el precio sale del costo guardado, no del de la lista', async () => {
      await crear(
        desgloseBase({
          bonificacion1: 10,
          ajuste1: null,
          ajuste2: null,
          costoDeLista: 900,
          costoGuardado: 950,
          coincide: false,
          precioFinal: 1500,
        }),
      );

      expect(textos('.paso-importe').at(-1)).toBe('$ 1.500,00');
    });
  });

  describe('sin lista (R-213, decisión 46)', () => {
    it('los campos de ajuste quedan deshabilitados con el texto del servidor, enlazado a cada campo', async () => {
      await crear(sinLista());

      for (const id of ['ajuste1', 'ajuste2', 'ajuste3']) {
        expect(campo(id).disabled).toBe(true);
        expect(describedby(id)).toContain(MOTIVO);
      }
      expect(limpio(el().querySelector('#motivo-sin-lista')?.textContent)).toBe(MOTIVO);
    });

    it('la cadena arranca en el costo guardado', async () => {
      await crear(sinLista());

      expect(textos('.paso-nombre')).toEqual([
        'Costo',
        'Margen de 30 %',
        'IVA de 21 %',
        'Redondeo hacia arriba al múltiplo de 10',
        'Precio final',
      ]);
      expect(textos('.paso-importe')[0]).toBe('$ 500,00');
      expect(el().querySelector('.bonificaciones')).toBeNull();
    });

    it('con lista los campos están habilitados y no hay texto de motivo', async () => {
      await crear(desgloseBase());

      expect(campo('ajuste1').disabled).toBe(false);
      expect(el().querySelector('#motivo-sin-lista')).toBeNull();
    });
  });

  describe('Vendedor (decisión 44)', () => {
    it('ve el desglose y los ajustes guardados sin campos editables', async () => {
      await crear(desgloseBase(), { dueno: false });

      expect(textos('.paso-importe').at(-1)).toBe('$ 1.390,00');
      expect(el().querySelectorAll('input').length).toBe(0);
      expect(limpio(el().querySelector('.ajustes-lectura dd')?.textContent)).toBe('-5 % y 3 %');
      expect(limpio(el().querySelector('.ajustes-lectura')?.textContent)).toContain(
        'Negativo es descuento, positivo es recargo.',
      );
    });

    it('sin lista también ve el motivo, sin campos', async () => {
      await crear(sinLista(), { dueno: false });

      expect(limpio(el().querySelector('#motivo-sin-lista')?.textContent)).toBe(MOTIVO);
      expect(el().querySelectorAll('input').length).toBe(0);
    });
  });

  describe('en vivo (R-218, R-219)', () => {
    it('al borrar el +3 la cadena muestra costo 855,00 y precio $ 1.350,00 antes de guardar', async () => {
      await crear(desgloseBase());

      await escribir('ajuste2', '');

      expect(textos('.paso-importe')).toEqual([
        '$ 1.000,00',
        '$ 900,00',
        '$ 855,00',
        '$ 855,00',
        '$ 1.111,50',
        '$ 1.344,915',
        '$ 1.350,00',
      ]);
      expect(grupo.getRawValue().ajuste2).toBeNull();
    });

    it('avisa que lo que se ve todavía no se guardó, y solo cuando hay un cambio', async () => {
      await crear(desgloseBase());
      expect(el().querySelector('.sin-guardar')).toBeNull();

      await escribir('ajuste2', '');
      expect(limpio(el().querySelector('.sin-guardar')?.textContent)).toBe(
        'Estos importes salen de los ajustes que escribiste. Todavía no se guardaron.',
      );

      await escribir('ajuste2', '3');
      expect(el().querySelector('.sin-guardar')).toBeNull();
    });

    it('un ajuste nuevo recalcula el costo desde la lista', async () => {
      await crear(
        desgloseBase({
          ajuste1: null,
          ajuste2: null,
          bonificacion1: 10,
          costoDeLista: 900,
          costoGuardado: 900,
          precioFinal: 1420,
        }),
      );

      await escribir('ajuste1', '-5');

      expect(textos('.paso-importe').slice(0, 4)).toEqual([
        '$ 1.000,00',
        '$ 900,00',
        '$ 855,00',
        '$ 855,00',
      ]);
      expect(textos('.paso-importe').at(-1)).toBe('$ 1.350,00');
    });

    it('un ajuste con decimales entra exacto, sin error de punto flotante', async () => {
      await crear(
        desgloseBase({
          bonificacion1: null,
          ajuste1: null,
          ajuste2: null,
          precioLista: 200,
          costoDeLista: 200,
          costoGuardado: 200,
          precioFinal: 320,
        }),
      );

      await escribir('ajuste1', '3.5');

      expect(textos('.paso-importe').slice(0, 3)).toEqual(['$ 200,00', '$ 207,00', '$ 207,00']);
    });
  });

  describe('validación de los ajustes (R-215)', () => {
    it('fuera de rango marca el campo y enlaza el mensaje', async () => {
      await crear(desgloseBase());

      await escribir('ajuste1', '-100');

      expect(campo('ajuste1').getAttribute('aria-invalid')).toBe('true');
      expect(describedby('ajuste1')).toContain(
        'Cada descuento o recargo tiene que estar entre -99,99 y 1.000.',
      );
      expect(campo('ajuste2').hasAttribute('aria-invalid')).toBe(false);
    });

    it('más de 2 decimales marca el campo', async () => {
      await crear(desgloseBase());

      await escribir('ajuste3', '3.555');

      expect(campo('ajuste3').getAttribute('aria-invalid')).toBe('true');
      expect(describedby('ajuste3')).toContain(
        'Los descuentos y recargos admiten hasta 2 decimales.',
      );
    });

    it('un ajuste inválido no mueve la cadena', async () => {
      await crear(desgloseBase());
      const antes = textos('.paso-importe');

      await escribir('ajuste3', '2000');

      expect(textos('.paso-importe')).toEqual(antes);
    });

    it('al corregirlo el error desaparece', async () => {
      await crear(desgloseBase());
      await escribir('ajuste1', '-100');

      await escribir('ajuste1', '-99.99');

      expect(campo('ajuste1').hasAttribute('aria-invalid')).toBe(false);
      expect(describedby('ajuste1')).toEqual(['Negativo es descuento, positivo es recargo.']);
    });

    it('errorDeAjuste da el mensaje de rango, el de decimales o nada', () => {
      expect(errorDeAjuste(null)).toBeNull();
      expect(errorDeAjuste(0)).toBeNull();
      expect(errorDeAjuste(-99.99)).toBeNull();
      expect(errorDeAjuste(1000)).toBeNull();
      expect(errorDeAjuste(-5.5)).toBeNull();
      expect(errorDeAjuste(-100)).toBe(
        'Cada descuento o recargo tiene que estar entre -99,99 y 1.000.',
      );
      expect(errorDeAjuste(1000.01)).toBe(
        'Cada descuento o recargo tiene que estar entre -99,99 y 1.000.',
      );
      expect(errorDeAjuste(3.555)).toBe('Los descuentos y recargos admiten hasta 2 decimales.');
    });
  });

  describe('el precio final del desglose es el guardado (R-209)', () => {
    it('sin cambios en los ajustes muestra el precio guardado aunque el recalculado difiera', async () => {
      await crear(desgloseBase({ precioFinal: 1400 }));

      expect(textos('.paso-importe').at(-1)).toBe('$ 1.400,00');
    });

    it('con ajustes escritos distintos muestra el precio que saldría (el recalculado)', async () => {
      await crear(desgloseBase({ precioFinal: 1400 }));

      await escribir('ajuste2', '');

      expect(textos('.paso-importe').at(-1)).toBe('$ 1.350,00');
    });
  });

  describe('un ajuste inválido escrito', () => {
    it('avisa que no se tiene en cuenta en los importes', async () => {
      await crear(desgloseBase());
      expect(el().querySelector('.ajuste-ignorado')).toBeNull();

      await escribir('ajuste1', '-100');

      expect(limpio(el().querySelector('.ajuste-ignorado')?.textContent)).toBe(
        'Hay un ajuste inválido: no se tiene en cuenta en estos importes.',
      );
    });
  });

  describe('cadenaDelDesglose', () => {
    it('sin cambios en los ajustes parte del costo guardado, que puede no ser el de la lista', () => {
      const cadena = cadenaDelDesglose(
        desgloseBase({
          ajuste1: null,
          ajuste2: null,
          costoDeLista: 900,
          costoGuardado: 990,
          coincide: false,
          precioFinal: 1560,
        }),
        [null, null, null],
      );

      expect(cadena.costoDeLista).toBe('900.00');
      expect(cadena.pasos.find((p) => p.tipo === 'COSTO')?.importe).toBe('990.00');
      expect(cadena.precio).toBe('1560.00');
    });

    it('con ajustes distintos de los guardados el costo es el que saldría de la lista', () => {
      const cadena = cadenaDelDesglose(
        desgloseBase({
          ajuste1: null,
          ajuste2: null,
          costoDeLista: 900,
          costoGuardado: 990,
          coincide: false,
        }),
        [-5, null, null],
      );

      expect(cadena.costoDeLista).toBe('855.00');
      expect(cadena.pasos.find((p) => p.tipo === 'COSTO')?.importe).toBe('855.00');
      expect(cadena.precio).toBe('1350.00');
    });
  });
});
