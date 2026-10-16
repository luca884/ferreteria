import { NgTemplateOutlet } from '@angular/common';
import { Component, input } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MONEDAS, MONEDA_LABELS } from '../articulos/articulo.models';
import { CampoDeColumna, CampoDeMapeo, FormMapeo } from './mapeo-lista';

/** Una columna del archivo que se puede elegir: su número (base 0) y cómo se la rotula, por ejemplo «C · Precio». */
export interface OpcionColumna {
  indice: number;
  etiqueta: string;
}

interface Selector {
  control: CampoDeColumna;
  etiqueta: string;
  /** Lo que dice la opción de «ninguna columna». */
  vacio: string;
}

interface GrupoDeCampos {
  leyenda: string;
  selectores: Selector[];
}

const OPCIONAL = 'No usar';

/** Los grupos de la pantalla. La lista de campos es cerrada: no hay forma de mapear el stock ni la marca. */
const GRUPO_DATOS: GrupoDeCampos = {
  leyenda: 'Datos del artículo',
  selectores: [
    { control: 'columnaCodigo', etiqueta: 'Columna del código del proveedor', vacio: 'Elegí la columna' },
    { control: 'columnaDescripcion', etiqueta: 'Columna de la descripción (opcional)', vacio: 'Sin descripción' },
    { control: 'columnaCosto', etiqueta: 'Columna del costo', vacio: 'Elegí la columna' },
  ],
};

const GRUPO_MONEDA: Selector = {
  control: 'columnaMoneda',
  etiqueta: 'Columna de la moneda (opcional)',
  vacio: OPCIONAL,
};

const GRUPO_IVA: GrupoDeCampos = {
  leyenda: 'IVA',
  selectores: [
    { control: 'columnaConIva', etiqueta: 'Columna del precio con IVA (opcional)', vacio: OPCIONAL },
    {
      control: 'columnaSinIva',
      etiqueta: 'Columna del precio sin IVA (opcional, solo para calcular el IVA)',
      vacio: OPCIONAL,
    },
    { control: 'columnaIva', etiqueta: 'Columna del % de IVA (opcional)', vacio: OPCIONAL },
  ],
};

const GRUPO_AJUSTES: GrupoDeCampos = {
  leyenda: 'Descuentos y recargos',
  selectores: [1, 2, 3].map((n) => ({
    control: `columnaAjuste${n}` as CampoDeColumna,
    etiqueta: `Columna del descuento o recargo ${n} (opcional)`,
    vacio: OPCIONAL,
  })),
};

const GRUPO_OTROS: GrupoDeCampos = {
  leyenda: 'Otros datos de los artículos nuevos',
  selectores: [
    { control: 'columnaCodigoBarras', etiqueta: 'Columna del código de barras (opcional)', vacio: OPCIONAL },
    { control: 'columnaUnidad', etiqueta: 'Columna de la unidad (opcional)', vacio: OPCIONAL },
    { control: 'columnaRubro', etiqueta: 'Columna del rubro (opcional)', vacio: OPCIONAL },
  ],
};

/**
 * Asignar cada dato de la lista a una columna del archivo (presentacional): el formulario del mapeo, las columnas que
 * se pueden elegir y cuáles reconoció el servidor. No sabe del archivo ni del servicio; escribe en el formulario que
 * recibe.
 */
@Component({
  selector: 'app-mapeo-columnas',
  imports: [ReactiveFormsModule, NgTemplateOutlet],
  templateUrl: './mapeo-columnas.html',
  styleUrl: './mapeo-columnas.css',
})
export class MapeoColumnas {
  readonly grupo = input.required<FormMapeo>();
  readonly opciones = input.required<OpcionColumna[]>();
  /** Los campos cuyo valor sigue siendo lo que el servidor reconoció del archivo. */
  readonly reconocidos = input<ReadonlySet<CampoDeMapeo>>(new Set());

  protected readonly datos = GRUPO_DATOS;
  protected readonly moneda = GRUPO_MONEDA;
  protected readonly iva = GRUPO_IVA;
  protected readonly ajustes = GRUPO_AJUSTES;
  protected readonly otros = GRUPO_OTROS;
  protected readonly monedas = MONEDAS.map((m) => ({ valor: m, etiqueta: MONEDA_LABELS[m] }));

  protected control(campo: CampoDeColumna): FormControl<number | null> {
    return this.grupo().controls[campo];
  }

  protected esReconocido(campo: CampoDeMapeo): boolean {
    return this.reconocidos().has(campo);
  }

  /** Los ids que describen al campo: la marca «Reconocida», la explicación del grupo y el error del par de IVA. */
  protected descripcion(campo: CampoDeMapeo): string | null {
    const ids: string[] = [];
    if (this.esReconocido(campo)) ids.push(`${campo}-reconocida`);
    if (campo.startsWith('columnaAjuste')) ids.push('ajustes-ayuda');
    if (this.ivaIncompleto() && (campo === 'columnaSinIva' || campo === 'columnaConIva')) ids.push('iva-par-error');
    return ids.length > 0 ? ids.join(' ') : null;
  }

  /** Una columna obligatoria sin elegir, después de intentar seguir; y el par de IVA a medias. */
  protected invalido(campo: CampoDeColumna): boolean {
    if (campo === 'columnaSinIva' || campo === 'columnaConIva') return this.ivaIncompleto();
    const control = this.grupo().controls[campo];
    return control.touched && control.invalid;
  }

  protected ivaIncompleto(): boolean {
    return this.grupo().hasError('ivaEnPar');
  }

  protected filaDesdeInvalida(): boolean {
    return this.grupo().controls.filaDesde.invalid;
  }

  protected descripcionDeFilaDesde(): string | null {
    const ids: string[] = [];
    if (this.esReconocido('filaDesde')) ids.push('filaDesde-reconocida');
    if (this.filaDesdeInvalida()) ids.push('filaDesde-error');
    return ids.length > 0 ? ids.join(' ') : null;
  }
}
