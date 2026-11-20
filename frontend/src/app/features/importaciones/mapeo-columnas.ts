import { NgTemplateOutlet } from '@angular/common';
import { Component, input, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { scan, switchMap } from 'rxjs';
import { esCelular } from '../../shared/es-celular';
import { HojaInferior } from '../../shared/hoja-inferior';
import { MONEDAS, MONEDA_LABELS } from '../articulos/articulo.models';
import { CampoDeColumna, CampoDeMapeo, FormMapeo, letraDeColumna } from './mapeo-lista';

/** Una columna del archivo que se puede elegir: su número (base 0) y cómo se la rotula, por ejemplo «C · Precio». */
export interface OpcionColumna {
  indice: number;
  etiqueta: string;
  /** El texto del encabezado de la columna, sin recortar (lo usa la hoja del celular). */
  encabezado?: string;
  /** Los primeros valores de las filas de datos de la columna (lo usa la hoja del celular). */
  ejemplos?: string[];
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

/** Qué grupos de campos dibuja el celular: los de siempre arriba, los de «Más ajustes», o todos. En otras pantallas no cuenta. */
export type CamposDelMapeo = 'todo' | 'datos' | 'ajustes';

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
 *
 * En el celular (movil-sectores T13b, `esCelular()` se lee una vez al crearlo) cada columna es una fila que abre una hoja
 * inferior con un radio por columna del archivo, y `modo` reparte los grupos entre la lista de arriba y «Más ajustes» del
 * padre. La fila y la hoja escriben en el mismo formulario que el `<select>`. En escritorio y tablet nada de eso entra
 * en el DOM y `modo` no cuenta.
 */
@Component({
  selector: 'app-mapeo-columnas',
  imports: [ReactiveFormsModule, NgTemplateOutlet, HojaInferior],
  templateUrl: './mapeo-columnas.html',
  styleUrl: './mapeo-columnas.css',
})
export class MapeoColumnas {
  readonly grupo = input.required<FormMapeo>();
  readonly opciones = input.required<OpcionColumna[]>();
  /** Los campos cuyo valor sigue siendo lo que el servidor reconoció del archivo. */
  readonly reconocidos = input<ReadonlySet<CampoDeMapeo>>(new Set());
  /** Sólo en el celular: qué grupos dibuja este componente. */
  readonly modo = input<CamposDelMapeo>('todo');

  protected readonly celular = esCelular();
  /**
   * Sube con cada cambio del formulario (valor, estado o «tocado»). Las filas del celular leen el formulario, que no es
   * una señal, y sin esto no se redibujarían si algo lo cambia de afuera. En escritorio los `<select>` ya lo siguen solos,
   * así que no se suscribe.
   */
  private readonly cambios = this.celular
    ? toSignal(
        toObservable(this.grupo).pipe(
          switchMap((grupo) => grupo.events),
          scan((n) => n + 1, 0),
        ),
        { initialValue: 0 },
      )
    : signal(0);
  /** El dato cuya hoja de columnas está abierta (celular), o null. */
  protected readonly hojaAbierta = signal<Selector | null>(null);

  protected readonly datos = GRUPO_DATOS;
  protected readonly moneda = GRUPO_MONEDA;
  protected readonly iva = GRUPO_IVA;
  protected readonly ajustes = GRUPO_AJUSTES;
  protected readonly otros = GRUPO_OTROS;
  protected readonly monedas = MONEDAS.map((m) => ({ valor: m, etiqueta: MONEDA_LABELS[m] }));

  protected readonly letra = letraDeColumna;

  /** El grupo de «Datos» se dibuja salvo que el celular reparta los campos y este sea el de «Más ajustes». */
  protected mostrarDatos(): boolean {
    return !this.celular || this.modo() !== 'ajustes';
  }

  /** Moneda, IVA, descuentos y otros se dibujan salvo que el celular reparta los campos y este sea el de los datos. */
  protected mostrarAjustes(): boolean {
    return !this.celular || this.modo() !== 'datos';
  }

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
    this.cambios();
    if (campo === 'columnaSinIva' || campo === 'columnaConIva') return this.ivaIncompleto();
    const control = this.grupo().controls[campo];
    return control.touched && control.invalid;
  }

  protected ivaIncompleto(): boolean {
    this.cambios();
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

  /** Los obligatorios (código y costo) no se pueden dejar sin columna. */
  protected esObligatorio(campo: CampoDeColumna): boolean {
    return this.control(campo).hasValidator(Validators.required);
  }

  /** Lo que dice la fila: la columna elegida con su letra y encabezado, o lo mismo que la primera opción del `<select>`. */
  protected valorDeFila(s: Selector): string {
    this.cambios();
    const columna = this.control(s.control).value;
    if (columna === null) return s.vacio;
    return this.opciones().find((o) => o.indice === columna)?.etiqueta ?? letraDeColumna(columna);
  }

  /** Los ejemplos de la columna elegida, en un renglón, o '' si no hay columna o no hay datos. */
  protected ejemplosDeFila(campo: CampoDeColumna): string {
    this.cambios();
    const columna = this.control(campo).value;
    if (columna === null) return '';
    return this.opciones().find((o) => o.indice === columna)?.ejemplos?.join(' · ') ?? '';
  }

  /** Abre la hoja del dato. El foco pasa antes a la fila: así `appTrampaFoco` sabe a quién devolverlo aunque el navegador no enfoque un botón al tocarlo. */
  protected abrirHoja(s: Selector, evento: Event): void {
    (evento.currentTarget as HTMLElement).focus();
    this.hojaAbierta.set(s);
  }

  protected cerrarHoja(): void {
    this.hojaAbierta.set(null);
  }

  /** Elegir una columna (o ninguna) escribe en el mismo control que el `<select>`, igual que un cambio a mano. */
  protected elegirColumna(s: Selector, columna: number | null): void {
    const control = this.control(s.control);
    control.setValue(columna);
    control.markAsDirty();
    control.markAsTouched();
    this.hojaAbierta.set(null);
  }
}
