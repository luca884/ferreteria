import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, Injector, OnInit, afterNextRender, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormArray,
  FormBuilder,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { erroresPorCampo, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { Avisos } from '../../core/avisos/avisos';
import { Cotizacion } from '../../core/cotizacion/cotizacion.models';
import { CotizacionService } from '../../core/cotizacion/cotizacion.service';
import { MontoPipe, formatearCotizacion, formatearTexto } from '../../core/formato/monto';
import { igualesPorValor } from '../../core/formato/cadena-de-precio';
import { RecargoDeCosto, costoRealExacto, exacto, margenEfectivo, precioFinal } from '../../core/formato/precio-final';
import { cantidadConUnidad } from '../../core/formato/unidad';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { ImporteInput } from '../../shared/importe-input';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { Rubro } from '../rubros/rubro.models';
import { RubrosService } from '../rubros/rubros.service';
import {
  ALICUOTAS_IVA,
  ALICUOTA_IVA_LABELS,
  AjustePrecio,
  RecargoCosto,
  AjusteStockResponse,
  AlicuotaIva,
  Articulo,
  ArticuloRequest,
  ConfiguracionPrecios,
  DesglosePrecio,
  MENSAJE_ARTICULO_MODIFICADO,
  MENSAJE_FALTA_VERSION,
  MONEDAS,
  MONEDA_LABELS,
  Moneda,
  TIPOS_AJUSTE_PRECIO,
  TIPO_AJUSTE_PRECIO_LABELS,
  TipoAjustePrecio,
  UNIDADES,
  UNIDAD_LABELS,
  Unidad,
  errorDeValorDeAjustePrecio,
  monedaDe,
} from './articulo.models';
import { ArticulosService } from './articulos.service';
import { DialogoAjusteStock } from './dialogo-ajuste-stock';
import { OfertaArticulo } from './oferta-articulo';
import {
  DesglosePrecioVista,
  GrupoAjustes,
  ajustesEfectivos,
  cadenaDelDesglose,
  errorDeAjuste,
  valoresDe,
} from './desglose-precio';

/** Un texto con solo espacios cuenta como vacío. */
function noVacio(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim() === '' ? { required: true } : null;
}

function textoONull(valor: string): string | null {
  const limpio = valor.trim();
  return limpio === '' ? null : limpio;
}

/** El tope del importe del backend (`TopeDeImporte`): 13 dígitos enteros. */
const PRECIO_MAXIMO_EXCLUSIVO = 10_000_000_000_000;
const NOMBRE_AJUSTE_MAXIMO = 40;
const NOMBRE_RECARGO_MAXIMO = 40;
/** Lo que valida el backend de los recargos del costo (CU-13): hasta 5, nombre de 1 a 40, % de -99,99 a 99,99 sin 0. */
export const MAXIMO_RECARGOS = 5;
export const MENSAJE_NOMBRE_RECARGO = 'El nombre del recargo es obligatorio (hasta 40 caracteres).';
export const MENSAJE_PORCENTAJE_RECARGO = 'El porcentaje del recargo debe estar entre -99,99 y 99,99, y no puede ser 0.';
export const MENSAJE_DECIMALES_RECARGO = 'El porcentaje del recargo tiene hasta 2 decimales.';
export const MENSAJE_PRECIO_MANUAL = 'El precio tiene que ser mayor a 0.';
export const MENSAJE_PRECIO_GRANDE = 'El precio es demasiado grande.';

/** El precio manual: mayor a 0 y dentro del tope. Con la casilla destildada el control está deshabilitado y no corre. */
function precioManualValido(control: AbstractControl<number | null>): ValidationErrors | null {
  const valor = control.value;
  if (valor === null || valor === undefined || valor <= 0) return { precio: MENSAJE_PRECIO_MANUAL };
  return valor >= PRECIO_MAXIMO_EXCLUSIVO ? { precio: MENSAJE_PRECIO_GRANDE } : null;
}

/** El valor de un ajuste: depende del tipo de su fila (el rango de -99,99 a 1.000 es solo del %). */
function valorDeAjusteValido(control: AbstractControl<number | null>): ValidationErrors | null {
  const tipo = (control.parent?.get('tipo')?.value as TipoAjustePrecio | undefined) ?? null;
  const error = errorDeValorDeAjustePrecio(tipo, control.value);
  return error === null ? null : { valorAjuste: error };
}

type FilaAjustePrecio = FormGroup<{
  nombre: FormControl<string>;
  tipo: FormControl<TipoAjustePrecio>;
  valor: FormControl<number | null>;
}>;

function filaDeAjuste(ajuste?: AjustePrecio): FilaAjustePrecio {
  return new FormGroup({
    nombre: new FormControl(ajuste?.nombre ?? '', {
      nonNullable: true,
      validators: [Validators.maxLength(NOMBRE_AJUSTE_MAXIMO)],
    }),
    tipo: new FormControl<TipoAjustePrecio>(ajuste?.tipo ?? 'PORCENTAJE', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    valor: new FormControl<number | null>(ajuste?.valor ?? null, { validators: [valorDeAjusteValido] }),
  });
}

/** El % de un recargo del costo: de -99,99 a 99,99, distinto de 0 y con hasta 2 decimales. */
function porcentajeDeRecargoValido(control: AbstractControl<number | null>): ValidationErrors | null {
  const valor = control.value;
  if (valor === null || valor === undefined || valor === 0 || valor < -99.99 || valor > 99.99) {
    return { porcentajeRecargo: MENSAJE_PORCENTAJE_RECARGO };
  }
  const decimal = exacto(valor);
  return decimal && decimal.escala > 2 ? { porcentajeRecargo: MENSAJE_DECIMALES_RECARGO } : null;
}

type FilaRecargoCosto = FormGroup<{
  nombre: FormControl<string>;
  porcentaje: FormControl<number | null>;
}>;

function filaDeRecargo(recargo?: RecargoCosto): FilaRecargoCosto {
  return new FormGroup({
    nombre: new FormControl(recargo?.nombre ?? '', {
      nonNullable: true,
      validators: [noVacio, Validators.maxLength(NOMBRE_RECARGO_MAXIMO)],
    }),
    porcentaje: new FormControl<number | null>(recargo?.porcentaje ?? null, {
      validators: [porcentajeDeRecargoValido],
    }),
  });
}

/** Campos del formulario que pueden traer un error del backend (los mismos nombres que el JSON). */
type CampoArticulo =
  | 'codigoInterno'
  | 'descripcion'
  | 'unidad'
  | 'costo'
  | 'moneda'
  | 'codigoBarras'
  | 'codigoProveedor'
  | 'ubicacion'
  | 'rubroId'
  | 'proveedorId'
  | 'margenPorcentaje'
  | 'alicuotaIva'
  | 'stockMinimo';

/** Campos del precio de CU-14 que también pueden traer un error del backend. */
type CampoPrecio = 'precioFinal' | 'ajustesPrecio' | 'recargosCosto';
type CampoDeRecargo = 'nombre' | 'porcentaje';
type CampoDeAjuste = 'nombre' | 'tipo' | 'valor';

/** Al guardar o cancelar se vuelve al listado con la búsqueda, la página y los inactivos de la dirección. */
const VOLVER_AL_LISTADO = { queryParamsHandling: 'preserve' } as const;

@Component({
  selector: 'app-articulo-form',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MontoPipe,
    Confirmacion,
    DialogoAjusteStock,
    BarraAcciones,
    DesglosePrecioVista,
    ImporteInput,
    OfertaArticulo,
  ],
  templateUrl: './articulo-form.html',
  styleUrl: './articulo-form.css',
})
export class ArticuloForm implements OnInit {
  private readonly service = inject(ArticulosService);
  private readonly rubrosService = inject(RubrosService);
  private readonly proveedoresService = inject(ProveedoresService);
  private readonly cotizaciones = inject(CotizacionService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly avisos = inject(Avisos);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  protected readonly auth = inject(AuthService);

  protected readonly alicuotas = ALICUOTAS_IVA;
  protected readonly alicuotaLabels = ALICUOTA_IVA_LABELS;
  protected readonly unidades = UNIDADES;
  protected readonly unidadLabels = UNIDAD_LABELS;
  protected readonly monedaLabels = MONEDA_LABELS;
  protected readonly tiposAjuste = TIPOS_AJUSTE_PRECIO;
  protected readonly tipoAjusteLabels = TIPO_AJUSTE_PRECIO_LABELS;
  protected readonly cantidadConUnidad = cantidadConUnidad;

  protected readonly rubros = signal<Rubro[]>([]);
  protected readonly proveedores = signal<Proveedor[]>([]);
  /** Margen por defecto y múltiplo de la ferretería; null si no se pudo leer (no hay precio en vivo). */
  protected readonly configuracion = signal<ConfiguracionPrecios | null>(null);
  /** La cotización de la ferretería; null si no se pudo leer (el backend valida igual al guardar). */
  protected readonly cotizacion = signal<Cotizacion | null>(null);
  /** El artículo tal como lo devolvió el backend; null en el alta. */
  protected readonly articulo = signal<Articulo | null>(null);
  protected readonly cargando = signal(true);
  /** El código interno con el que arrancó el alta (último cargado más uno); null si no hubo sugerencia. */
  protected readonly codigoSugerido = signal<string | null>(null);
  protected readonly guardando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
  /** Mensajes por campo de un 400/409 del backend; si hay, el banner los lista en vez de `error`. */
  protected readonly erroresServidor = signal<Record<string, string>>({});
  protected readonly erroresCampos = computed(() => Object.values(this.erroresServidor()));
  /** Si falló la carga del artículo no hay nada que editar. */
  protected readonly sinFormulario = signal(false);
  protected readonly confirmandoDescarte = signal(false);
  protected readonly ajustandoStock = signal(false);
  /** El desglose del precio (solo en la edición); null en el alta o si no se pudo leer. */
  protected readonly desglose = signal<DesglosePrecio | null>(null);
  protected readonly guardandoAjustes = signal(false);
  /** El texto del aviso de pisar el costo mientras espera que el Dueño confirme o cancele; null si no hay. */
  protected readonly confirmandoPisar = signal<{ texto: string; ajustes: (number | null)[] } | null>(null);
  /**
   * Después de guardar ajustes no se pudo releer el artículo o el desglose: lo que hay en pantalla puede estar
   * viejo. Guardar el artículo queda bloqueado hasta «Recargar».
   */
  protected readonly bloqueado = signal(false);
  /** El servidor dijo que otro cambio modificó el artículo (409 de versión): hay que recargar. */
  protected readonly versionVencida = signal(false);

  /** null en el alta; el id de la ruta en la edición. */
  private id: number | null = null;

  protected readonly form = inject(FormBuilder).nonNullable.group({
    codigoInterno: ['', [noVacio, Validators.maxLength(30)]],
    descripcion: ['', [noVacio, Validators.maxLength(200)]],
    unidad: ['UNIDAD' as Unidad, [Validators.required]],
    // Mayor que cero: un costo 0 deja el precio en $0 (H12). Admite 2 decimales, el mínimo es 0,01.
    costo: [null as number | null, [Validators.required, Validators.min(0.01)]],
    moneda: ['ARS' as Moneda, [Validators.required]],
    codigoBarras: ['', [Validators.maxLength(30)]],
    codigoProveedor: ['', [Validators.maxLength(40)]],
    ubicacion: ['', [Validators.maxLength(60)]],
    rubroId: [null as number | null],
    proveedorId: [null as number | null],
    /** Vacío significa que hereda el margen del rubro o el de la ferretería. */
    margenPorcentaje: [null as number | null, [Validators.min(0), Validators.max(1000)]],
    alicuotaIva: ['IVA_21' as AlicuotaIva, [Validators.required]],
    stockMinimo: [0 as number | null, [Validators.min(0)]],
    /** CU-14: tildada, el precio lo escribe el Dueño y no sale de la fórmula. */
    precioManual: [false],
    /** Solo se habilita (y valida) con la casilla tildada. */
    precioFinal: new FormControl<number | null>({ value: null, disabled: true }, [precioManualValido]),
    /** CU-14: los ajustes sobre el precio final, sin límite; el formulario manda siempre la lista entera. */
    ajustesPrecio: new FormArray<FilaAjustePrecio>([]),
    /** CU-13: los recargos del costo (hasta 5); el formulario manda siempre la lista entera. */
    recargosCosto: new FormArray<FilaRecargoCosto>([]),
  });

  /**
   * Los tres ajustes, con su propio grupo y fuera de `form`: `guardar` y el PUT del artículo nunca los mandan
   * (decisión 43). Se guardan con su propia acción, hacia `PUT /articulos/{id}/ajustes`.
   */
  protected readonly ajustes: GrupoAjustes = new FormGroup({
    ajuste1: new FormControl<number | null>(null),
    ajuste2: new FormControl<number | null>(null),
    ajuste3: new FormControl<number | null>(null),
  });
  private readonly ajustesEscritos = toSignal(this.ajustes.valueChanges.pipe(map(() => valoresDe(this.ajustes))), {
    initialValue: valoresDe(this.ajustes),
  });

  /** Se puede guardar: Dueño, con lista, con ajustes distintos de los guardados y todos válidos. */
  protected readonly puedeGuardarAjustes = computed(() => {
    const d = this.desglose();
    const escritos = this.ajustesEscritos();
    return (
      this.auth.esDueno() &&
      d !== null &&
      d.tieneLista &&
      !this.guardandoAjustes() &&
      escritos.every((a) => errorDeAjuste(a) === null) &&
      ajustesEfectivos(d, escritos).cambiaron
    );
  });

  /** Por qué «Guardar ajustes» está apagado, para decirlo al lado del botón; null si se puede guardar. */
  protected readonly motivoSinGuardarAjustes = computed(() => {
    const d = this.desglose();
    if (!this.auth.esDueno() || d === null || !d.tieneLista || this.guardandoAjustes()) return null;
    const escritos = this.ajustesEscritos();
    const invalido = escritos.findIndex((a) => errorDeAjuste(a) !== null);
    if (invalido >= 0) return `Corregí el ajuste ${invalido + 1}`;
    return ajustesEfectivos(d, escritos).cambiaron ? null : 'Cambiá un ajuste para guardar';
  });

  /**
   * Con ajustes escritos distintos de los guardados, el costo y el precio que saldrían (los de la cadena del
   * desglose, R-218); null si no hay cambios y rige el precio del costo del formulario.
   */
  protected readonly vivoDeAjustes = computed(() => {
    const d = this.desglose();
    if (d === null || !d.tieneLista) return null;
    const escritos = this.ajustesEscritos();
    if (!ajustesEfectivos(d, escritos).cambiaron) return null;
    const cadena = cadenaDelDesglose(d, escritos);
    const costo = cadena.pasos.find((p) => p.tipo === 'COSTO')?.importe ?? null;
    return {
      costo: costo === null ? null : formatearTexto(costo, d.moneda),
      precio: cadena.precio === null ? null : formatearTexto(cadena.precio, 'ARS'),
    };
  });

  /** Los valores del formulario como signal (incluso deshabilitado, para el Vendedor). */
  private readonly valores = toSignal(this.form.valueChanges.pipe(map(() => this.form.getRawValue())), {
    initialValue: this.form.getRawValue(),
  });

  /** De dónde sale el margen cuando el campo está vacío: «Hereda 35 % de Bulonería». */
  protected readonly herencia = computed(() => {
    const config = this.configuracion();
    const v = this.valores();
    if (!config || v.margenPorcentaje !== null) return null;
    const rubro = this.rubros().find((r) => r.id === v.rubroId);
    return rubro
      ? `Hereda ${formatearPorcentaje(rubro.margenPorcentaje)} % de ${rubro.nombre}`
      : `Hereda ${formatearPorcentaje(config.margenDefectoPorcentaje)} % de la ferretería`;
  });

  protected readonly enDolares = computed(() => this.valores().moneda === 'USD');

  /**
   * USD se ofrece si la cotización está activada (o si no se pudo leer: el backend lo valida al guardar). Un artículo
   * que ya está en dólares lo conserva aunque la cotización se apague: se puede seguir editando.
   */
  protected readonly dolaresDisponibles = computed(
    () => this.cotizacion()?.activa !== false || monedaDe(this.articulo()) === 'USD',
  );
  protected readonly monedasOfrecidas = computed<readonly Moneda[]>(() =>
    this.dolaresDisponibles() ? MONEDAS : ['ARS'],
  );

  /** El dólar con el que el backend calcula los precios en USD (el vigente); null si todavía no hay ninguno. */
  private readonly dolarDePrecios = computed(() => this.cotizacion()?.precios.valor ?? null);
  protected readonly dolarUsado = computed(() => formatearCotizacion(this.dolarDePrecios()));

  /** En dólares, el precio en vivo necesita el dólar vigente: sin él no hay cuenta que hacer. */
  protected readonly sinDolar = computed(() => this.enDolares() && this.dolarDePrecios() === null);

  /** Hay ajustes sobre el precio final: el precio de abajo es el calculado, sin ellos. */
  protected readonly hayAjustesPrecio = computed(() => this.valores().ajustesPrecio.length > 0);
  protected readonly esPrecioManual = computed(() => this.valores().precioManual);

  /** Los recargos del costo que ya se pueden contar: una fila a medio escribir o inválida no entra en la cuenta. */
  private readonly recargosEnVivo = computed<RecargoDeCosto[]>(() => {
    const filas = this.form.controls.recargosCosto.controls;
    return this.valores()
      .recargosCosto.map((r, i) => ({ nombre: r.nombre.trim(), porcentaje: r.porcentaje, fila: filas[i] }))
      .filter((r) => r.nombre !== '' && r.fila?.controls.porcentaje.valid)
      .map(({ nombre, porcentaje }) => ({ nombre, porcentaje }));
  });

  protected readonly hayRecargosCosto = computed(() => this.valores().recargosCosto.length > 0);
  protected readonly puedeAgregarRecargo = computed(() => this.valores().recargosCosto.length < MAXIMO_RECARGOS);

  /** «Costo real» con los recargos escritos (el costo más la suma de sus %); null sin recargos o sin costo. */
  protected readonly costoRealEnVivo = computed(() => {
    const v = this.valores();
    const recargos = this.recargosEnVivo();
    if (recargos.length === 0 || v.costo === null) return null;
    const real = costoRealExacto(v.costo, recargos);
    if (!real) return null;
    const centavos = real.entero.toString().padStart(3, '0');
    return formatearTexto(`${centavos.slice(0, -2)}.${centavos.slice(-2)}`, v.moneda);
  });

  /** Precio final con lo que hay cargado, con la misma cuenta y redondeo que el backend. */
  protected readonly precioEnVivo = computed(() => {
    const config = this.configuracion();
    const v = this.valores();
    if (!config || v.costo === null) return null;
    const rubro = this.rubros().find((r) => r.id === v.rubroId);
    const { margen } = margenEfectivo(
      v.margenPorcentaje,
      rubro?.margenPorcentaje ?? null,
      config.margenDefectoPorcentaje,
    );
    // En pesos la cotización vale 1; en dólares es el vigente (null = sin precio).
    const cotizacion = v.moneda === 'USD' ? this.dolarDePrecios() : undefined;
    return precioFinal(
      v.costo,
      margen,
      v.alicuotaIva,
      config.redondeoMultiplo,
      cotizacion,
      this.recargosEnVivo(),
    );
  });

  ngOnInit(): void {
    const crudo = this.route.snapshot.paramMap.get('id');
    if (crudo !== null) {
      this.id = Number(crudo);
      if (!Number.isInteger(this.id)) {
        this.fallarCarga('Artículo no encontrado.');
        return;
      }
    }
    this.cargar();
  }

  protected get esEdicion(): boolean {
    return this.id !== null;
  }

  protected errorServidor(campo: CampoArticulo | CampoPrecio): string | null {
    return this.erroresServidor()[campo] ?? null;
  }

  /** Al escribir en un campo se borra su error del backend. */
  protected limpiarErrorDe(campo: CampoArticulo | CampoPrecio): void {
    if (!this.erroresServidor()[campo]) return;
    this.erroresServidor.update(({ [campo]: _, ...resto }) => resto);
  }

  protected filasAjuste(): FilaAjustePrecio[] {
    return this.form.controls.ajustesPrecio.controls;
  }

  /** El error del backend de un campo de una fila de ajustes (`ajustesPrecio[i].valor`). */
  protected errorServidorDeFila(indice: number, campo: CampoDeAjuste): string | null {
    return this.erroresServidor()[`ajustesPrecio[${indice}].${campo}`] ?? null;
  }

  /** Escribir en una fila borra los errores del backend de ese campo y el de la lista (que depende de todas). */
  protected limpiarErrorDeFila(indice: number, campo: CampoDeAjuste): void {
    this.limpiarErroresDe((clave) => clave === `ajustesPrecio[${indice}].${campo}` || clave === 'ajustesPrecio');
  }

  private limpiarErroresDe(coincide: (clave: string) => boolean): void {
    if (!Object.keys(this.erroresServidor()).some(coincide)) return;
    this.erroresServidor.update((errores) =>
      Object.fromEntries(Object.entries(errores).filter(([clave]) => !coincide(clave))),
    );
  }

  /** El error de validación propio de un campo de una fila (ya tocado); null si no hay. */
  protected errorLocalDeFila(indice: number, campo: CampoDeAjuste): string | null {
    const control = this.filasAjuste()[indice]?.controls[campo];
    if (!control?.touched || !control.invalid) return null;
    if (campo === 'nombre') return 'El nombre del ajuste tiene hasta 40 caracteres.';
    if (campo === 'tipo') return 'Elegí si el ajuste es en % o en $.';
    return (control.errors?.['valorAjuste'] as string | undefined) ?? null;
  }

  protected errorLocalDelPrecio(): string | null {
    const control = this.form.controls.precioFinal;
    return control.touched && control.invalid ? ((control.errors?.['precio'] as string | undefined) ?? null) : null;
  }

  /** Ids para aria-describedby de un campo de una fila: su error propio y el del backend, si se ven. */
  protected describirFila(indice: number, campo: CampoDeAjuste): string | null {
    const base = `ajuste-precio-${campo}-${indice}`;
    const ids: string[] = [];
    if (this.errorLocalDeFila(indice, campo)) ids.push(`${base}-error`);
    if (this.errorServidorDeFila(indice, campo)) ids.push(`${base}-error-servidor`);
    return ids.length > 0 ? ids.join(' ') : null;
  }

  protected describirPrecioManual(): string | null {
    const ids: string[] = [];
    if (this.errorLocalDelPrecio()) ids.push('precioFinalManual-error');
    if (this.errorServidor('precioFinal')) ids.push('precioFinalManual-error-servidor');
    return ids.length > 0 ? ids.join(' ') : null;
  }

  /**
   * La casilla «Precio manual»: tildada habilita el campo del precio, que arranca con el precio actual; destildada lo
   * apaga y lo borra (el backend vuelve a calcular el precio).
   */
  protected alCambiarPrecioManual(): void {
    this.aplicarPrecioManual(this.form.controls.precioManual.value, this.precioParaArrancar());
    this.limpiarErrorDe('precioFinal');
  }

  private aplicarPrecioManual(manual: boolean, arrancarCon: number | null): void {
    const precio = this.form.controls.precioFinal;
    if (manual) {
      precio.enable({ emitEvent: false });
      if (precio.value === null) precio.setValue(arrancarCon);
    } else {
      precio.reset(null, { emitEvent: false });
      precio.disable({ emitEvent: false });
    }
  }

  /** El precio actual: el guardado en la edición; en el alta, el que sale de la fórmula con lo cargado. */
  private precioParaArrancar(): number | null {
    return this.articulo()?.precioFinal ?? this.precioEnVivo();
  }

  protected agregarAjustePrecio(): void {
    const fila = filaDeAjuste();
    this.form.controls.ajustesPrecio.push(fila);
    // El valor depende del tipo de su fila, que recién ahora tiene padre.
    fila.controls.valor.updateValueAndValidity();
    this.form.markAsDirty();
    this.limpiarErroresDe((clave) => clave.startsWith('ajustesPrecio'));
  }

  protected quitarAjustePrecio(indice: number): void {
    this.form.controls.ajustesPrecio.removeAt(indice);
    this.form.markAsDirty();
    // Los índices de los errores del backend ya no coinciden con las filas.
    this.limpiarErroresDe((clave) => clave.startsWith('ajustesPrecio'));
  }

  /** Cambiar el tipo cambia lo que vale el valor (el rango de -99,99 a 1.000 es solo del %). */
  protected alCambiarTipoDeAjuste(indice: number): void {
    this.filasAjuste()[indice]?.controls.valor.updateValueAndValidity();
    this.limpiarErrorDeFila(indice, 'tipo');
    this.limpiarErrorDeFila(indice, 'valor');
  }

  protected filasRecargo(): FilaRecargoCosto[] {
    return this.form.controls.recargosCosto.controls;
  }

  /** El error del backend de los recargos: es uno solo (`recargosCosto`), sin posición. */
  protected errorServidorDeRecargos(): string | null {
    return this.erroresServidor()['recargosCosto'] ?? null;
  }

  /** Escribir en un recargo borra el error del backend de la lista, que depende de todas las filas. */
  protected limpiarErrorDeRecargos(): void {
    this.limpiarErroresDe((clave) => clave === 'recargosCosto');
  }

  /** El error de validación propio de un campo de una fila de recargos (ya tocado); null si no hay. */
  protected errorLocalDeRecargo(indice: number, campo: CampoDeRecargo): string | null {
    const control = this.filasRecargo()[indice]?.controls[campo];
    if (!control?.touched || !control.invalid) return null;
    if (campo === 'nombre') return MENSAJE_NOMBRE_RECARGO;
    return (control.errors?.['porcentajeRecargo'] as string | undefined) ?? null;
  }

  /** Ids para aria-describedby de un campo de una fila de recargos: su error propio y el del backend, si se ven. */
  protected describirRecargo(indice: number, campo: CampoDeRecargo): string | null {
    const ids: string[] = [];
    if (this.errorLocalDeRecargo(indice, campo)) ids.push(`recargo-costo-${campo}-${indice}-error`);
    if (this.errorServidorDeRecargos()) ids.push('recargosCosto-error-servidor');
    return ids.length > 0 ? ids.join(' ') : null;
  }

  protected agregarRecargo(): void {
    if (!this.puedeAgregarRecargo()) return;
    this.form.controls.recargosCosto.push(filaDeRecargo());
    this.form.markAsDirty();
    this.limpiarErrorDeRecargos();
  }

  protected quitarRecargo(indice: number): void {
    this.form.controls.recargosCosto.removeAt(indice);
    this.form.markAsDirty();
    this.limpiarErrorDeRecargos();
  }

  /** Error de validación propio visible (el campo ya se tocó). */
  protected errorLocal(campo: CampoArticulo): boolean {
    const control = this.form.controls[campo];
    return control.touched && control.invalid;
  }

  protected invalido(campo: CampoArticulo): boolean {
    return this.errorLocal(campo) || this.errorServidor(campo) !== null;
  }

  /** Ids para aria-describedby: las pistas del campo y sus errores visibles (propio y del backend). */
  protected describir(campo: CampoArticulo, ...pistas: string[]): string | null {
    const ids = [...pistas];
    if (this.errorLocal(campo)) ids.push(`${campo}-error`);
    if (this.errorServidor(campo)) ids.push(`${campo}-error-servidor`);
    return ids.length > 0 ? ids.join(' ') : null;
  }

  /** Cambiar la moneda borra el costo: hay que cargarlo de nuevo en la moneda nueva; no se convierte. */
  protected alCambiarMoneda(): void {
    this.form.controls.costo.reset(null);
    this.limpiarErrorDe('costo');
    this.limpiarErrorDe('moneda');
  }

  /** Ids para aria-describedby del selector de moneda: la pista y, con la cotización apagada, el motivo. */
  protected describirMoneda(): string | null {
    return this.describir('moneda', 'moneda-pista', ...(this.dolaresDisponibles() ? [] : ['moneda-apagada']));
  }

  protected guardar(): void {
    if (!this.auth.esDueno() || this.guardando()) return;
    if (this.bloqueado()) {
      if (!this.error()) {
        this.error.set('Recargá el artículo antes de guardar: lo que ves puede estar desactualizado.');
        this.versionVencida.set(true);
      }
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.enfocarPrimerInvalido();
      return;
    }
    this.guardando.set(true);
    this.limpiarMensajes();
    const request = this.armarRequest();
    const guardado =
      this.id === null ? this.service.crear(request) : this.service.actualizar(this.id, request);
    guardado.subscribe({
      next: (respuesta) => {
        this.guardando.set(false);
        // La respuesta trae la versión nueva: la que hay que mandar en el próximo guardado.
        if (this.id !== null) this.articulo.set(respuesta);
        this.avisos.dejar(`Se guardó «${request.descripcion}».`);
        void this.router.navigate(['/articulos'], VOLVER_AL_LISTADO);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        this.erroresServidor.set(erroresPorCampo(err));
        this.error.set(mensajeDe(err, 'No se pudo guardar el artículo.'));
        this.versionVencida.set(esVersionVencida(err));
        if (this.versionVencida()) this.enfocarAvisoDeAjustes();
        else this.enfocarPrimerInvalido();
      },
    });
  }

  /**
   * «Guardar ajustes» es una acción propia. Si guardar pisaría un costo cargado a mano (o movido por una actualización
   * masiva), el aviso sale ANTES de enviar y sin pedido de prueba (R-214): lo calcula con el desglose y lo escrito.
   */
  protected guardarAjustes(): void {
    const d = this.desglose();
    if (this.id === null || d === null || !this.puedeGuardarAjustes()) return;
    const aviso = this.avisoDePisar(d);
    if (aviso !== null) {
      // Se congela lo que se vio en el aviso: es lo que se envía al confirmar.
      this.confirmandoPisar.set({ texto: aviso, ajustes: [...this.ajustesEscritos()] });
      return;
    }
    this.enviarAjustes(false, this.ajustesEscritos());
  }

  protected confirmarPisar(): void {
    const congelado = this.confirmandoPisar();
    this.confirmandoPisar.set(null);
    if (congelado && this.auth.esDueno() && !this.guardandoAjustes()) this.enviarAjustes(true, congelado.ajustes);
  }

  private avisoDePisar(d: DesglosePrecio): string | null {
    const costoNuevo = cadenaDelDesglose(d, this.ajustesEscritos()).costoDeLista;
    if (d.coincide !== false || d.costoDeLista === null || costoNuevo === null) return null;
    if (igualesPorValor(costoNuevo, d.costoGuardado)) return null; // el costo no cambia: no hay nada que pisar
    const en = (valor: string) => formatearTexto(valor, d.moneda);
    return (
      `El costo guardado (${en(String(d.costoGuardado))}) no coincide con el que sale de la lista (${en(String(d.costoDeLista))}). ` +
      `Si guardás, el costo pasa a ${en(costoNuevo)}.`
    );
  }

  private enviarAjustes(confirmaPisarCosto: boolean, valores: readonly (number | null)[]): void {
    const id = this.id;
    if (id === null) return;
    const [ajuste1, ajuste2, ajuste3] = valores;
    this.guardandoAjustes.set(true);
    this.limpiarMensajes();
    const version = this.articulo()?.version;
    this.service.guardarAjustes(id, { ajuste1, ajuste2, ajuste3, confirmaPisarCosto, version }).subscribe({
      next: (guardado) => {
        // La respuesta ya trae costo, precio, ajustes y versión nuevos: se usa YA, aunque después no se pueda releer.
        this.articulo.set(guardado);
        this.cargarAjustes(guardado, null);
        this.traerCostoDelServidor(guardado);
        this.recargarTrasAjustes(id);
      },
      error: (err: unknown) => {
        this.guardandoAjustes.set(false);
        this.error.set(mensajeDe(err, 'No se pudieron guardar los ajustes.'));
        this.versionVencida.set(esVersionVencida(err));
        this.enfocarAvisoDeAjustes();
      },
    });
  }

  /** Después de guardar, lo que manda es el servidor: se vuelven a pedir el artículo y el desglose. */
  private recargarTrasAjustes(id: number): void {
    forkJoin({
      articulo: this.service.obtener(id),
      desglose: this.service.desglose(id).pipe(catchError(() => of(null))),
    }).subscribe({
      next: ({ articulo, desglose }) => {
        this.guardandoAjustes.set(false);
        this.articulo.set(articulo);
        this.desglose.set(desglose);
        this.cargarAjustes(articulo, desglose);
        this.traerCostoDelServidor(articulo);
        if (desglose === null) {
          this.fallaLaRelectura('Se guardaron los ajustes, pero no se pudo actualizar el desglose.');
          return;
        }
        this.aviso.set('Se guardaron los ajustes.');
        this.enfocarAvisoDeAjustes();
      },
      error: () => {
        this.guardandoAjustes.set(false);
        this.fallaLaRelectura('Se guardaron los ajustes, pero no se pudo releer el artículo.');
      },
    });
  }

  /**
   * El costo cambió con los ajustes: el campo del formulario no puede quedar con el viejo, que se mandaría en el
   * próximo guardado como si fuera un costo a mano. Si el Dueño lo editó a mano en esta pantalla, se respeta.
   */
  private traerCostoDelServidor(articulo: Articulo): void {
    if (!this.form.controls.costo.dirty) this.form.controls.costo.setValue(articulo.costo);
  }

  /** La relectura falló: lo que se ve puede estar viejo, así que guardar el artículo espera a «Recargar». */
  private fallaLaRelectura(mensaje: string): void {
    this.bloqueado.set(true);
    this.versionVencida.set(true);
    this.error.set(`${mensaje} Tocá «Recargar» antes de seguir.`);
    this.enfocarAvisoDeAjustes();
  }

  /** Los ajustes del servidor (los del desglose, o los del artículo si no hay desglose) pasan a los tres campos. */
  private cargarAjustes(articulo: Articulo, desglose: DesglosePrecio | null): void {
    const fuente = desglose ?? articulo;
    this.ajustes.setValue({
      ajuste1: fuente.ajuste1 ?? null,
      ajuste2: fuente.ajuste2 ?? null,
      ajuste3: fuente.ajuste3 ?? null,
    });
  }

  /** El resultado de una acción de ajustes recibe el foco: el botón está abajo y el aviso arriba. */
  private enfocarAvisoDeAjustes(): void {
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('[data-foco-ajustes]')?.focus(),
      { injector: this.injector },
    );
  }

  /**
   * «Recargar» tras un 409 de versión: vuelve a pedir el artículo y el desglose y deja el formulario con lo que hay
   * en el servidor. Hasta que se apreta, lo escrito se conserva.
   */
  protected recargar(): void {
    const id = this.id;
    if (id === null || this.guardando() || this.guardandoAjustes()) return;
    this.limpiarMensajes();
    forkJoin({
      articulo: this.service.obtener(id),
      desglose: this.service.desglose(id).pipe(catchError(() => of(null))),
    }).subscribe({
      next: ({ articulo, desglose }) => {
        this.articulo.set(articulo);
        this.desglose.set(desglose);
        this.llenarFormulario(articulo);
        this.cargarAjustes(articulo, desglose);
        this.form.markAsPristine();
        this.bloqueado.set(false);
        this.aviso.set('Se recargó el artículo con los últimos cambios.');
        this.enfocarAvisoDeAjustes();
      },
      error: () => {
        this.error.set('No se pudo recargar el artículo.');
        this.versionVencida.set(true);
        this.enfocarAvisoDeAjustes();
      },
    });
  }

  private limpiarMensajes(): void {
    this.versionVencida.set(false);
    this.error.set(null);
    this.aviso.set(null);
    this.erroresServidor.set({});
  }

  /** Cancelar: con cambios sin guardar pregunta con el diálogo propio; si no, vuelve al listado. */
  protected cancelar(evento: Event): void {
    evento.preventDefault();
    if (this.auth.esDueno() && this.form.dirty) {
      this.confirmandoDescarte.set(true);
      return;
    }
    void this.router.navigate(['/articulos'], VOLVER_AL_LISTADO);
  }

  protected descartar(): void {
    this.confirmandoDescarte.set(false);
    void this.router.navigate(['/articulos'], VOLVER_AL_LISTADO);
  }

  /** Después de pintar los errores, el foco va al primer campo marcado (en el orden del formulario). */
  private enfocarPrimerInvalido(): void {
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('form.articulo [aria-invalid="true"]')?.focus(),
      { injector: this.injector },
    );
  }

  protected reactivar(): void {
    if (this.id === null) return;
    this.error.set(null);
    this.service.reactivar(this.id).subscribe({
      next: (articulo) => {
        // La reactivación también sube la versión: se toma la respuesta entera para que el próximo guardado no choque.
        this.articulo.set(articulo);
        this.aviso.set('Se reactivó el artículo.');
      },
      error: (err: unknown) => this.error.set(mensajeDe(err, 'No se pudo reactivar el artículo.')),
    });
  }

  /**
   * Ofertas (S1): la sección guardó con su propio pedido. Se toma la versión nueva (y la oferta) de la respuesta para
   * que el próximo guardado del artículo no dé 409; lo escrito en el formulario queda como está.
   */
  protected ofertaCambiada(articulo: Articulo): void {
    const actual = this.articulo();
    if (!actual) return;
    this.articulo.set({ ...actual, version: articulo.version, oferta: articulo.oferta ?? null });
    this.aviso.set(articulo.oferta ? 'Oferta guardada.' : 'Oferta sacada.');
  }

  protected stockAjustado(ajuste: AjusteStockResponse): void {
    this.ajustandoStock.set(false);
    const actual = this.articulo();
    if (!actual) return;
    this.articulo.set({ ...actual, stockActual: ajuste.stockActual });
    this.aviso.set(`Stock ajustado: ahora hay ${cantidadConUnidad(ajuste.stockActual, actual.unidad)}.`);
  }

  private cargar(): void {
    forkJoin({
      rubros: this.rubrosService.listar(),
      proveedores: this.proveedoresService.listar(),
      articulo: this.id === null ? of(null) : this.service.obtener(this.id),
      // Sin configuración no hay precio en vivo, pero el formulario sirve igual. El desglose y la configuración
      // muestran costo y margen: son solo del Dueño y para el Vendedor ni se piden (H07).
      desglose:
        this.id === null || !this.auth.esDueno()
          ? of(null)
          : this.service.desglose(this.id).pipe(catchError(() => of(null))),
      configuracion: this.auth.esDueno()
        ? this.service.configuracionPrecios().pipe(catchError(() => of(null)))
        : of(null),
      // Sin cotización tampoco: el precio en dólares no se calcula y el backend valida al guardar.
      cotizacion: this.cotizaciones.obtener().pipe(catchError(() => of(null))),
      // Solo en el alta del Dueño: el código siguiente al último cargado. Si falla, el alta sigue con el campo vacío.
      codigoSugerido:
        this.id === null && this.auth.esDueno()
          ? this.service.codigoSiguiente().pipe(catchError(() => of(null)))
          : of(null),
    }).subscribe({
      next: ({ rubros, proveedores, articulo, desglose, configuracion, cotizacion, codigoSugerido }) => {
        this.rubros.set(rubros);
        this.proveedores.set(proveedores.filter((p) => p.activo));
        this.configuracion.set(configuracion);
        this.cotizacion.set(cotizacion);
        if (articulo) {
          this.articulo.set(articulo);
          this.llenarFormulario(articulo);
          this.desglose.set(desglose);
          this.cargarAjustes(articulo, desglose);
        }
        if (codigoSugerido) {
          this.codigoSugerido.set(codigoSugerido);
          this.form.controls.codigoInterno.setValue(codigoSugerido);
        }
        if (!this.auth.esDueno()) this.form.disable();
        this.cargando.set(false);
      },
      error: (err: unknown) => {
        this.fallarCarga(
          mensajeDe(
            err,
            this.id === null ? 'No se pudo cargar el formulario.' : 'No se pudo cargar el artículo.',
          ),
        );
      },
    });
  }

  /** Los campos del formulario con lo que devolvió el servidor. */
  private llenarFormulario(articulo: Articulo): void {
    this.form.patchValue({
      codigoInterno: articulo.codigoInterno,
      descripcion: articulo.descripcion,
      unidad: articulo.unidad,
      costo: articulo.costo,
      moneda: monedaDe(articulo),
      codigoBarras: articulo.codigoBarras ?? '',
      codigoProveedor: articulo.codigoProveedor ?? '',
      ubicacion: articulo.ubicacion ?? '',
      rubroId: articulo.rubroId,
      proveedorId: articulo.proveedorId,
      margenPorcentaje: articulo.margenPorcentaje,
      alicuotaIva: articulo.alicuotaIva,
      stockMinimo: articulo.stockMinimo,
      precioManual: articulo.precioManual === true,
    });
    // Lo que había escrito se pierde: manda lo del servidor.
    this.form.controls.precioFinal.reset(null, { emitEvent: false });
    this.aplicarPrecioManual(articulo.precioManual === true, articulo.precioFinal);
    const filas = this.form.controls.ajustesPrecio;
    filas.clear();
    for (const ajuste of articulo.ajustesPrecio ?? []) filas.push(filaDeAjuste(ajuste));
    filas.controls.forEach((fila) => fila.controls.valor.updateValueAndValidity());
    const recargos = this.form.controls.recargosCosto;
    recargos.clear();
    for (const recargo of articulo.recargosCosto ?? []) recargos.push(filaDeRecargo(recargo));
  }

  private fallarCarga(mensaje: string): void {
    this.cargando.set(false);
    this.sinFormulario.set(true);
    this.error.set(mensaje);
  }

  /**
   * Tildada manda la marca y el precio; destildada manda `precioManual: false` solo si el artículo lo era (para que
   * el backend lo recalcule). Un artículo que nunca fue manual y no se tocó no manda nada.
   */
  private pedidoDePrecioManual(v: { precioManual: boolean; precioFinal: number | null }): Partial<ArticuloRequest> {
    if (v.precioManual) return { precioManual: true, precioFinal: v.precioFinal };
    return this.articulo()?.precioManual === true ? { precioManual: false } : {};
  }

  /** Solo lo editable: stock actual lo resuelve el backend, y el precio final solo se manda con precio manual. */
  private armarRequest(): ArticuloRequest {
    const v = this.form.getRawValue();
    const version = this.articulo()?.version;
    return {
      codigoInterno: v.codigoInterno.trim(),
      codigoBarras: textoONull(v.codigoBarras),
      codigoProveedor: textoONull(v.codigoProveedor),
      descripcion: v.descripcion.trim(),
      rubroId: v.rubroId,
      proveedorId: v.proveedorId,
      costo: v.costo ?? 0,
      moneda: v.moneda,
      margenPorcentaje: v.margenPorcentaje,
      alicuotaIva: v.alicuotaIva,
      unidad: v.unidad,
      stockMinimo: v.stockMinimo ?? 0,
      ubicacion: textoONull(v.ubicacion),
      // El formulario no reactiva ni da de baja: conserva el estado que ya tenía.
      activo: this.articulo()?.activo ?? true,
      // Solo en la edición, y solo si el servidor la mandó: el alta no tiene versión que comparar.
      ...(this.id !== null && version !== undefined ? { version } : {}),
      ...this.pedidoDePrecioManual(v),
      // Siempre la lista del formulario: una lista vacía borra los ajustes.
      ajustesPrecio: v.ajustesPrecio.map((a) => ({
        nombre: textoONull(a.nombre),
        tipo: a.tipo,
        valor: a.valor ?? 0,
      })),
      // Siempre la lista del formulario (CU-13): una lista vacía borra los recargos.
      recargosCosto: v.recargosCosto.map((r) => ({ nombre: r.nombre.trim(), porcentaje: r.porcentaje ?? 0 })),
    };
  }
}

/** El 409 en que otro cambio modificó el artículo después de cargarlo. */
function esVersionVencida(err: unknown): boolean {
  if (!(err instanceof HttpErrorResponse)) return false;
  const mensaje: unknown = err.error?.message;
  return (
    (err.status === 409 && mensaje === MENSAJE_ARTICULO_MODIFICADO) ||
    (err.status === 400 && mensaje === MENSAJE_FALTA_VERSION)
  );
}

function formatearPorcentaje(valor: number): string {
  return new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 }).format(valor);
}
