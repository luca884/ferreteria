import { AbstractControl, FormControl, FormGroup, ValidationErrors, Validators } from '@angular/forms';
import { Moneda } from '../articulos/articulo.models';
import { MapeoLista, MapeoRequest, Muestra, PropuestaDeMapeo } from './importacion.models';

/**
 * Las columnas que se pueden mapear: la lista cerrada de la spec (R-109). No existe una para el stock ni para la
 * marca, ni por la API ni por la pantalla.
 */
export const CAMPOS_DE_COLUMNA = [
  'columnaCodigo',
  'columnaDescripcion',
  'columnaCosto',
  'columnaMoneda',
  'columnaConIva',
  'columnaSinIva',
  'columnaIva',
  'columnaAjuste1',
  'columnaAjuste2',
  'columnaAjuste3',
  'columnaCodigoBarras',
  'columnaUnidad',
  'columnaRubro',
] as const;
export type CampoDeColumna = (typeof CAMPOS_DE_COLUMNA)[number];

/** Cómo se llama cada columna del mapeo, para el resaltado de la grilla. */
export const TITULO_DE_COLUMNA: Record<CampoDeColumna, string> = {
  columnaCodigo: 'Columna del código',
  columnaDescripcion: 'Columna de la descripción',
  columnaCosto: 'Columna del costo',
  columnaMoneda: 'Columna de la moneda',
  columnaConIva: 'Columna del precio con IVA',
  columnaSinIva: 'Columna del precio sin IVA',
  columnaIva: 'Columna del % de IVA',
  columnaAjuste1: 'Columna del descuento o recargo 1',
  columnaAjuste2: 'Columna del descuento o recargo 2',
  columnaAjuste3: 'Columna del descuento o recargo 3',
  columnaCodigoBarras: 'Columna del código de barras',
  columnaUnidad: 'Columna de la unidad',
  columnaRubro: 'Columna del rubro',
};

/** Lo opcional que viaja solo si tiene columna. */
const CAMPOS_OPCIONALES = CAMPOS_DE_COLUMNA.filter(
  (campo) => campo !== 'columnaCodigo' && campo !== 'columnaDescripcion' && campo !== 'columnaCosto',
);

/** Los valores del formulario del mapeo: la primera fila de datos, una columna por campo y la moneda fija. */
export type ValoresDeMapeo = Record<CampoDeColumna, number | null> & {
  filaDesde: number | null;
  monedaFija: Moneda | null;
};

export type CampoDeMapeo = keyof ValoresDeMapeo;

export function mapeoVacio(): ValoresDeMapeo {
  return {
    filaDesde: 1,
    monedaFija: null,
    columnaCodigo: null,
    columnaDescripcion: null,
    columnaCosto: null,
    columnaMoneda: null,
    columnaConIva: null,
    columnaSinIva: null,
    columnaIva: null,
    columnaAjuste1: null,
    columnaAjuste2: null,
    columnaAjuste3: null,
    columnaCodigoBarras: null,
    columnaUnidad: null,
    columnaRubro: null,
  };
}

export type FormMapeo = FormGroup<{ [K in CampoDeMapeo]: FormControl<ValoresDeMapeo[K]> }>;

/** «Precio sin IVA» y «con IVA» se piden juntas: las dos o ninguna. */
export function ivaEnPar(grupo: AbstractControl): ValidationErrors | null {
  const sinIva = grupo.get('columnaSinIva')?.value as number | null | undefined;
  const conIva = grupo.get('columnaConIva')?.value as number | null | undefined;
  const hayUna = sinIva !== null && sinIva !== undefined;
  const hayOtra = conIva !== null && conIva !== undefined;
  return hayUna === hayOtra ? null : { ivaEnPar: true };
}

export function crearFormMapeo(): FormMapeo {
  const vacio = mapeoVacio();
  const control = <K extends CampoDeMapeo>(campo: K, validadores: ((c: AbstractControl) => ValidationErrors | null)[] = []) =>
    new FormControl<ValoresDeMapeo[K]>(vacio[campo], validadores);
  const form = new FormGroup(
    {
      filaDesde: control('filaDesde', [Validators.required, Validators.min(1)]),
      monedaFija: control('monedaFija'),
      columnaCodigo: control('columnaCodigo', [Validators.required]),
      columnaDescripcion: control('columnaDescripcion'),
      columnaCosto: control('columnaCosto', [Validators.required]),
      columnaMoneda: control('columnaMoneda'),
      columnaConIva: control('columnaConIva'),
      columnaSinIva: control('columnaSinIva'),
      columnaIva: control('columnaIva'),
      columnaAjuste1: control('columnaAjuste1'),
      columnaAjuste2: control('columnaAjuste2'),
      columnaAjuste3: control('columnaAjuste3'),
      columnaCodigoBarras: control('columnaCodigoBarras'),
      columnaUnidad: control('columnaUnidad'),
      columnaRubro: control('columnaRubro'),
    },
    { validators: [ivaEnPar] },
  );
  // La moneda se toma de una columna o es fija para todas las filas, nunca las dos: elegir una limpia la otra.
  form.controls.columnaMoneda.valueChanges.subscribe((columna) => {
    if (columna !== null) form.controls.monedaFija.setValue(null);
  });
  form.controls.monedaFija.valueChanges.subscribe((moneda) => {
    if (moneda !== null) form.controls.columnaMoneda.setValue(null);
  });
  return form;
}

/** Qué se carga en el formulario al elegir el archivo (o el proveedor) y de dónde salió. */
export interface Precarga {
  valores: ValoresDeMapeo;
  /** Hoja de referencia: de ahí salen el encabezado y la primera fila de datos. */
  hoja: number;
  /** Hojas marcadas para importar. */
  hojas: number[];
  /** true si los valores vienen de la propuesta del servidor y no del mapeo guardado del proveedor. */
  desdePropuesta: boolean;
}

function valoresDe(origen: Partial<Record<CampoDeColumna, number | null>> & { filaDesde?: number | null; monedaFija?: Moneda | null }): ValoresDeMapeo {
  const valores = mapeoVacio();
  valores.filaDesde = origen.filaDesde ?? 1;
  valores.monedaFija = origen.monedaFija ?? null;
  for (const campo of CAMPOS_DE_COLUMNA) valores[campo] = origen[campo] ?? null;
  return valores;
}

function tienePropuesta(propuesta: PropuestaDeMapeo | null | undefined): propuesta is PropuestaDeMapeo {
  return !!propuesta && CAMPOS_DE_COLUMNA.some((campo) => propuesta[campo] !== null);
}

/**
 * El mapeo que se propone al abrir el archivo: el guardado del proveedor, si existe y todas sus hojas existen en el
 * archivo; si no, la propuesta que el servidor reconoció del encabezado; y si tampoco hay, todo vacío para elegir a mano.
 */
export function precargarMapeo(guardado: MapeoLista | null | undefined, muestra: Muestra): Precarga {
  const cantidadDeHojas = muestra.hojas.length;
  if (guardado) {
    const hojas = guardado.hojas && guardado.hojas.length > 0 ? guardado.hojas : [guardado.hoja];
    if (hojas.every((h) => h >= 0 && h < cantidadDeHojas)) {
      return {
        valores: valoresDe(guardado),
        hoja: guardado.hoja,
        hojas: [...hojas],
        desdePropuesta: false,
      };
    }
  }
  const propuesta = muestra.propuesta;
  if (tienePropuesta(propuesta)) {
    const hojas = propuesta.hojas.length > 0 ? [...propuesta.hojas] : [propuesta.hoja ?? 0];
    return {
      valores: valoresDe(propuesta),
      hoja: propuesta.hoja ?? hojas[0],
      hojas,
      desdePropuesta: true,
    };
  }
  return { valores: mapeoVacio(), hoja: 0, hojas: [0], desdePropuesta: false };
}

/** Los campos que siguen valiendo lo que el servidor reconoció: son los que llevan la marca «Reconocida». */
export function camposReconocidos(
  propuesta: PropuestaDeMapeo | null | undefined,
  valores: ValoresDeMapeo,
): ReadonlySet<CampoDeMapeo> {
  const reconocidos = new Set<CampoDeMapeo>();
  if (!tienePropuesta(propuesta)) return reconocidos;
  for (const campo of CAMPOS_DE_COLUMNA) {
    if (propuesta[campo] !== null && propuesta[campo] === valores[campo]) reconocidos.add(campo);
  }
  if (propuesta.filaDesde !== null && propuesta.filaDesde === valores.filaDesde) reconocidos.add('filaDesde');
  return reconocidos;
}

/**
 * El mapeo que se manda al servidor. Solo viaja lo que tiene columna (o moneda fija), así un mapeo simple sigue
 * teniendo la forma de siempre. La hoja de referencia tiene que estar entre las elegidas; si no, es la primera.
 */
export function armarMapeo(valores: ValoresDeMapeo, hojaDeReferencia: number, hojasElegidas: readonly number[]): MapeoRequest {
  const hojas = [...hojasElegidas].sort((a, b) => a - b);
  const mapeo: MapeoRequest = {
    hoja: hojas.includes(hojaDeReferencia) ? hojaDeReferencia : hojas[0],
    filaDesde: valores.filaDesde!,
    columnaCodigo: valores.columnaCodigo!,
    columnaDescripcion: valores.columnaDescripcion,
    columnaCosto: valores.columnaCosto!,
    hojas,
  };
  for (const campo of CAMPOS_OPCIONALES) {
    const columna = valores[campo];
    if (columna !== null) mapeo[campo] = columna;
  }
  if (valores.monedaFija !== null) mapeo.monedaFija = valores.monedaFija;
  return mapeo;
}
