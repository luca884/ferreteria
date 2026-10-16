import { AbstractControl } from '@angular/forms';
import { MapeoLista, Muestra, PropuestaDeMapeo } from './importacion.models';
import {
  armarMapeo,
  camposReconocidos,
  crearFormMapeo,
  ivaEnPar,
  mapeoVacio,
  precargarMapeo,
} from './mapeo-lista';

const SIN_PROPUESTA: PropuestaDeMapeo = {
  hoja: null,
  filaDesde: null,
  columnaCodigo: null,
  columnaDescripcion: null,
  columnaCosto: null,
  columnaMoneda: null,
  columnaSinIva: null,
  columnaConIva: null,
  columnaIva: null,
  columnaAjuste1: null,
  columnaAjuste2: null,
  columnaAjuste3: null,
  columnaCodigoBarras: null,
  columnaUnidad: null,
  columnaRubro: null,
  hojas: [],
};

/** LISTA 336: código A, descripción B, moneda E, costo F, sin IVA H y con IVA I; tres hojas reconocidas. */
const PROPUESTA_336: PropuestaDeMapeo = {
  ...SIN_PROPUESTA,
  hoja: 0,
  filaDesde: 2,
  columnaCodigo: 0,
  columnaDescripcion: 1,
  columnaMoneda: 4,
  columnaCosto: 5,
  columnaSinIva: 7,
  columnaConIva: 8,
  hojas: [0, 1, 2],
};

function archivoDe(cantidadDeHojas: number, propuesta: PropuestaDeMapeo): Muestra {
  return {
    hojas: Array.from({ length: cantidadDeHojas }, (_, indice) => ({
      indice,
      nombre: `Hoja ${indice + 1}`,
      columnas: 9,
      filaEncabezado: 1,
      filas: [],
    })),
    propuesta,
  };
}

const GUARDADO_VIEJO: MapeoLista = {
  hoja: 1,
  filaDesde: 3,
  columnaCodigo: 1,
  columnaDescripcion: 0,
  columnaCosto: 2,
  encabezados: { codigo: 'Codigo', descripcion: 'Detalle', costo: 'Precio' },
};

describe('mapeo-lista', () => {
  describe('precargarMapeo', () => {
    it('usa el mapeo guardado si existe y sus hojas existen en el archivo', () => {
      const precarga = precargarMapeo(GUARDADO_VIEJO, archivoDe(3, PROPUESTA_336));

      expect(precarga.desdePropuesta).toBe(false);
      expect(precarga.valores.filaDesde).toBe(3);
      expect(precarga.valores.columnaCodigo).toBe(1);
      expect(precarga.valores.columnaDescripcion).toBe(0);
      expect(precarga.valores.columnaCosto).toBe(2);
      expect(precarga.valores.columnaMoneda).toBeNull();
      expect(precarga.hoja).toBe(1);
    });

    it('un mapeo viejo, sin `hojas`, equivale a leer solo su hoja', () => {
      const precarga = precargarMapeo(GUARDADO_VIEJO, archivoDe(3, PROPUESTA_336));

      expect(precarga.hojas).toEqual([1]);
    });

    it('un mapeo guardado con varias hojas y los campos nuevos los trae completos', () => {
      const guardado: MapeoLista = {
        ...GUARDADO_VIEJO,
        hoja: 0,
        hojas: [0, 2],
        columnaMoneda: 4,
        columnaSinIva: 7,
        columnaConIva: 8,
        columnaAjuste1: 9,
      };

      const precarga = precargarMapeo(guardado, archivoDe(3, SIN_PROPUESTA));

      expect(precarga.hojas).toEqual([0, 2]);
      expect(precarga.valores.columnaMoneda).toBe(4);
      expect(precarga.valores.columnaSinIva).toBe(7);
      expect(precarga.valores.columnaConIva).toBe(8);
      expect(precarga.valores.columnaAjuste1).toBe(9);
      expect(precarga.valores.columnaAjuste2).toBeNull();
      expect(precarga.valores.monedaFija).toBeNull();
    });

    it('la moneda fija guardada se vuelve a proponer', () => {
      const guardado: MapeoLista = { ...GUARDADO_VIEJO, hoja: 0, monedaFija: 'USD' };

      const precarga = precargarMapeo(guardado, archivoDe(1, SIN_PROPUESTA));

      expect(precarga.valores.monedaFija).toBe('USD');
      expect(precarga.valores.columnaMoneda).toBeNull();
    });

    it('si alguna hoja guardada no existe en el archivo, usa la propuesta', () => {
      const guardado: MapeoLista = { ...GUARDADO_VIEJO, hoja: 0, hojas: [0, 5] };

      const precarga = precargarMapeo(guardado, archivoDe(3, PROPUESTA_336));

      expect(precarga.desdePropuesta).toBe(true);
      expect(precarga.valores.columnaCosto).toBe(5);
      expect(precarga.hojas).toEqual([0, 1, 2]);
    });

    it('un mapeo viejo cuya hoja no existe en el archivo también cae en la propuesta', () => {
      const precarga = precargarMapeo(GUARDADO_VIEJO, archivoDe(1, PROPUESTA_336));

      expect(precarga.desdePropuesta).toBe(true);
      expect(precarga.valores.columnaCodigo).toBe(0);
    });

    it('sin mapeo guardado usa la propuesta del servidor', () => {
      const precarga = precargarMapeo(null, archivoDe(3, PROPUESTA_336));

      expect(precarga.desdePropuesta).toBe(true);
      expect(precarga.hoja).toBe(0);
      expect(precarga.hojas).toEqual([0, 1, 2]);
      expect(precarga.valores.filaDesde).toBe(2);
      expect(precarga.valores.columnaCodigo).toBe(0);
      expect(precarga.valores.columnaDescripcion).toBe(1);
      expect(precarga.valores.columnaMoneda).toBe(4);
      expect(precarga.valores.columnaCosto).toBe(5);
      expect(precarga.valores.columnaSinIva).toBe(7);
      expect(precarga.valores.columnaConIva).toBe(8);
    });

    it('un archivo sin encabezado reconocible deja todo para elegir a mano, sin propuesta', () => {
      const precarga = precargarMapeo(undefined, archivoDe(2, SIN_PROPUESTA));

      expect(precarga.desdePropuesta).toBe(false);
      expect(precarga.valores).toEqual(mapeoVacio());
      expect(precarga.hoja).toBe(0);
      expect(precarga.hojas).toEqual([0]);
      expect(mapeoVacio().filaDesde).toBe(1);
      expect(mapeoVacio().columnaCodigo).toBeNull();
    });
  });

  describe('camposReconocidos', () => {
    it('son los campos de la propuesta cuyo valor sigue siendo el reconocido', () => {
      const valores = precargarMapeo(null, archivoDe(3, PROPUESTA_336)).valores;

      const reconocidos = camposReconocidos(PROPUESTA_336, valores);

      expect([...reconocidos].sort()).toEqual(
        [
          'columnaCodigo',
          'columnaConIva',
          'columnaCosto',
          'columnaDescripcion',
          'columnaMoneda',
          'columnaSinIva',
          'filaDesde',
        ].sort(),
      );
    });

    it('un campo que el Dueño cambió ya no lleva la marca, y los demás la conservan', () => {
      const valores = { ...precargarMapeo(null, archivoDe(3, PROPUESTA_336)).valores, columnaCosto: 6 };

      const reconocidos = camposReconocidos(PROPUESTA_336, valores);

      expect(reconocidos.has('columnaCosto')).toBe(false);
      expect(reconocidos.has('columnaCodigo')).toBe(true);
    });

    it('sin propuesta no hay nada reconocido', () => {
      expect(camposReconocidos(null, mapeoVacio()).size).toBe(0);
      expect(camposReconocidos(SIN_PROPUESTA, mapeoVacio()).size).toBe(0);
    });
  });

  describe('armarMapeo', () => {
    it('manda solo lo mapeado: un mapeo simple conserva la forma de siempre más las hojas', () => {
      const valores = {
        ...mapeoVacio(),
        filaDesde: 2,
        columnaCodigo: 0,
        columnaDescripcion: 1,
        columnaCosto: 2,
      };

      const mapeo = armarMapeo(valores, 0, [0]);

      expect(mapeo).toEqual({
        hoja: 0,
        filaDesde: 2,
        columnaCodigo: 0,
        columnaDescripcion: 1,
        columnaCosto: 2,
        hojas: [0],
      });
      expect('columnaMoneda' in mapeo).toBe(false);
      expect('monedaFija' in mapeo).toBe(false);
    });

    it('incluye los campos nuevos que tienen columna y la moneda fija', () => {
      const valores = {
        ...mapeoVacio(),
        filaDesde: 2,
        columnaCodigo: 0,
        columnaCosto: 5,
        columnaMoneda: 4,
        columnaSinIva: 7,
        columnaConIva: 8,
        columnaIva: 9,
        columnaAjuste1: 10,
        columnaAjuste3: 12,
        columnaCodigoBarras: 13,
        columnaUnidad: 14,
        columnaRubro: 15,
      };

      const mapeo = armarMapeo(valores, 1, [1, 0]);

      expect(mapeo).toEqual({
        hoja: 1,
        filaDesde: 2,
        columnaCodigo: 0,
        columnaDescripcion: null,
        columnaCosto: 5,
        hojas: [0, 1],
        columnaMoneda: 4,
        columnaSinIva: 7,
        columnaConIva: 8,
        columnaIva: 9,
        columnaAjuste1: 10,
        columnaAjuste3: 12,
        columnaCodigoBarras: 13,
        columnaUnidad: 14,
        columnaRubro: 15,
      });
      expect(armarMapeo({ ...valores, columnaMoneda: null, monedaFija: 'USD' }, 1, [1]).monedaFija).toBe('USD');
    });

    it('si la hoja de referencia no está entre las elegidas, toma la primera elegida', () => {
      const valores = { ...mapeoVacio(), columnaCodigo: 0, columnaCosto: 2 };

      expect(armarMapeo(valores, 0, [2, 1]).hoja).toBe(1);
      expect(armarMapeo(valores, 2, [2, 1]).hoja).toBe(2);
    });

    it('las hojas van ordenadas', () => {
      const valores = { ...mapeoVacio(), columnaCodigo: 0, columnaCosto: 2 };

      expect(armarMapeo(valores, 0, [3, 0, 1]).hojas).toEqual([0, 1, 3]);
    });
  });

  describe('crearFormMapeo y ivaEnPar', () => {
    const grupo = (valores: Partial<ReturnType<typeof mapeoVacio>>) => {
      const form = crearFormMapeo();
      form.patchValue(valores);
      return form;
    };

    it('arranca con los valores vacíos y la primera fila en 1', () => {
      const form = crearFormMapeo();

      expect(form.getRawValue()).toEqual(mapeoVacio());
      expect(form.controls.columnaCodigo.invalid).toBe(true);
      expect(form.controls.columnaCosto.invalid).toBe(true);
      expect(form.controls.filaDesde.valid).toBe(true);
    });

    it('«precio sin IVA» y «con IVA» se piden juntas: una sola es un error del grupo', () => {
      expect(grupo({ columnaSinIva: 7 }).errors).toEqual({ ivaEnPar: true });
      expect(grupo({ columnaConIva: 8 }).errors).toEqual({ ivaEnPar: true });
    });

    it('las dos juntas o ninguna están bien', () => {
      expect(grupo({ columnaSinIva: 7, columnaConIva: 8 }).errors).toBeNull();
      expect(grupo({}).errors).toBeNull();
    });

    it('la columna de la moneda y la moneda fija son excluyentes: elegir una limpia la otra', () => {
      const form = crearFormMapeo();

      form.controls.monedaFija.setValue('USD');
      form.controls.columnaMoneda.setValue(4);
      expect(form.controls.monedaFija.value).toBeNull();
      expect(form.controls.columnaMoneda.value).toBe(4);

      form.controls.monedaFija.setValue('ARS');
      expect(form.controls.columnaMoneda.value).toBeNull();
      expect(form.controls.monedaFija.value).toBe('ARS');
    });

    it('la columna 0 de la moneda también limpia la moneda fija', () => {
      const form = crearFormMapeo();
      form.controls.monedaFija.setValue('USD');

      form.controls.columnaMoneda.setValue(0);

      expect(form.controls.monedaFija.value).toBeNull();
    });

    it('vaciar una de las dos no toca la otra', () => {
      const form = crearFormMapeo();
      form.controls.monedaFija.setValue('USD');

      form.controls.columnaMoneda.setValue(null);

      expect(form.controls.monedaFija.value).toBe('USD');
    });

    it('la columna 0 cuenta como elegida (no se confunde con vacía)', () => {
      const form = grupo({ columnaSinIva: 0 });

      expect(ivaEnPar(form as AbstractControl)).toEqual({ ivaEnPar: true });
      expect(grupo({ columnaSinIva: 0, columnaConIva: 1 }).errors).toBeNull();
    });
  });
});
