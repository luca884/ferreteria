import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { erroresDeCampos } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { Avisos } from '../../core/avisos/avisos';
import { MontoPipe } from '../../core/formato/monto';
import { margenEfectivo, precioFinal } from '../../core/formato/precio-final';
import { cantidadConUnidad } from '../../core/formato/unidad';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { Rubro } from '../rubros/rubro.models';
import { RubrosService } from '../rubros/rubros.service';
import {
  ALICUOTAS_IVA,
  ALICUOTA_IVA_LABELS,
  AjusteStockResponse,
  AlicuotaIva,
  Articulo,
  ArticuloRequest,
  ConfiguracionPrecios,
  UNIDADES,
  UNIDAD_LABELS,
  Unidad,
} from './articulo.models';
import { ArticulosService } from './articulos.service';
import { DialogoAjusteStock } from './dialogo-ajuste-stock';

/** Un texto con solo espacios cuenta como vacío. */
function noVacio(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim() === '' ? { required: true } : null;
}

function textoONull(valor: string): string | null {
  const limpio = valor.trim();
  return limpio === '' ? null : limpio;
}

/** Campos del formulario que pueden traer un error del backend (los mismos nombres que el JSON). */
type CampoArticulo =
  | 'codigoInterno'
  | 'descripcion'
  | 'unidad'
  | 'costo'
  | 'codigoBarras'
  | 'codigoProveedor'
  | 'ubicacion'
  | 'rubroId'
  | 'proveedorId'
  | 'margenPorcentaje'
  | 'alicuotaIva'
  | 'stockMinimo';

@Component({
  selector: 'app-articulo-form',
  imports: [ReactiveFormsModule, MontoPipe, Confirmacion, DialogoAjusteStock, BarraAcciones],
  templateUrl: './articulo-form.html',
  styleUrl: './articulo-form.css',
})
export class ArticuloForm implements OnInit {
  private readonly service = inject(ArticulosService);
  private readonly rubrosService = inject(RubrosService);
  private readonly proveedoresService = inject(ProveedoresService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly avisos = inject(Avisos);
  protected readonly auth = inject(AuthService);

  protected readonly alicuotas = ALICUOTAS_IVA;
  protected readonly alicuotaLabels = ALICUOTA_IVA_LABELS;
  protected readonly unidades = UNIDADES;
  protected readonly unidadLabels = UNIDAD_LABELS;
  protected readonly cantidadConUnidad = cantidadConUnidad;

  protected readonly rubros = signal<Rubro[]>([]);
  protected readonly proveedores = signal<Proveedor[]>([]);
  /** Margen por defecto y múltiplo de la ferretería; null si no se pudo leer (no hay precio en vivo). */
  protected readonly configuracion = signal<ConfiguracionPrecios | null>(null);
  /** El artículo tal como lo devolvió el backend; null en el alta. */
  protected readonly articulo = signal<Articulo | null>(null);
  protected readonly cargando = signal(true);
  protected readonly guardando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
  /** Mensajes por campo de un 400/409 del backend; si hay, el banner los lista en vez de `error`. */
  protected readonly erroresServidor = signal<Partial<Record<CampoArticulo, string>>>({});
  protected readonly erroresCampos = computed(() => Object.values(this.erroresServidor()));
  /** Si falló la carga del artículo no hay nada que editar. */
  protected readonly sinFormulario = signal(false);
  protected readonly confirmandoDescarte = signal(false);
  protected readonly ajustandoStock = signal(false);

  /** null en el alta; el id de la ruta en la edición. */
  private id: number | null = null;

  protected readonly form = inject(FormBuilder).nonNullable.group({
    codigoInterno: ['', [noVacio, Validators.maxLength(30)]],
    descripcion: ['', [noVacio, Validators.maxLength(200)]],
    unidad: ['UNIDAD' as Unidad, [Validators.required]],
    costo: [null as number | null, [Validators.required, Validators.min(0)]],
    codigoBarras: ['', [Validators.maxLength(30)]],
    codigoProveedor: ['', [Validators.maxLength(40)]],
    ubicacion: ['', [Validators.maxLength(60)]],
    rubroId: [null as number | null],
    proveedorId: [null as number | null],
    /** Vacío significa que hereda el margen del rubro o el de la ferretería. */
    margenPorcentaje: [null as number | null, [Validators.min(0), Validators.max(1000)]],
    alicuotaIva: ['IVA_21' as AlicuotaIva, [Validators.required]],
    stockMinimo: [0 as number | null, [Validators.min(0)]],
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
    return precioFinal(v.costo, margen, v.alicuotaIva, config.redondeoMultiplo);
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

  protected errorServidor(campo: CampoArticulo): string | null {
    return this.erroresServidor()[campo] ?? null;
  }

  /** Al escribir en un campo se borra su error del backend. */
  protected limpiarErrorDe(campo: CampoArticulo): void {
    if (!this.erroresServidor()[campo]) return;
    this.erroresServidor.update(({ [campo]: _, ...resto }) => resto);
  }

  protected guardar(): void {
    if (!this.auth.esDueno()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.guardando.set(true);
    this.error.set(null);
    this.aviso.set(null);
    this.erroresServidor.set({});
    const request = this.armarRequest();
    const guardado =
      this.id === null ? this.service.crear(request) : this.service.actualizar(this.id, request);
    guardado.subscribe({
      next: () => {
        this.guardando.set(false);
        this.avisos.dejar(`Se guardó «${request.descripcion}».`);
        void this.router.navigate(['/articulos']);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        this.erroresServidor.set(erroresPorCampo(err));
        this.error.set(mensajeDe(err, 'No se pudo guardar el artículo.'));
      },
    });
  }

  /** Cancelar: con cambios sin guardar pregunta con el diálogo propio; si no, vuelve al listado. */
  protected cancelar(evento: Event): void {
    evento.preventDefault();
    if (this.auth.esDueno() && this.form.dirty) {
      this.confirmandoDescarte.set(true);
      return;
    }
    void this.router.navigate(['/articulos']);
  }

  protected descartar(): void {
    this.confirmandoDescarte.set(false);
    void this.router.navigate(['/articulos']);
  }

  protected reactivar(): void {
    if (this.id === null) return;
    this.error.set(null);
    this.service.reactivar(this.id).subscribe({
      next: (articulo) => {
        this.articulo.update((a) => a && { ...a, activo: articulo.activo });
        this.aviso.set('Se reactivó el artículo.');
      },
      error: (err: unknown) => this.error.set(mensajeDe(err, 'No se pudo reactivar el artículo.')),
    });
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
      // Sin configuración no hay precio en vivo, pero el formulario sirve igual.
      configuracion: this.service.configuracionPrecios().pipe(catchError(() => of(null))),
    }).subscribe({
      next: ({ rubros, proveedores, articulo, configuracion }) => {
        this.rubros.set(rubros);
        this.proveedores.set(proveedores.filter((p) => p.activo));
        this.configuracion.set(configuracion);
        if (articulo) {
          this.articulo.set(articulo);
          this.form.patchValue({
            codigoInterno: articulo.codigoInterno,
            descripcion: articulo.descripcion,
            unidad: articulo.unidad,
            costo: articulo.costo,
            codigoBarras: articulo.codigoBarras ?? '',
            codigoProveedor: articulo.codigoProveedor ?? '',
            ubicacion: articulo.ubicacion ?? '',
            rubroId: articulo.rubroId,
            proveedorId: articulo.proveedorId,
            margenPorcentaje: articulo.margenPorcentaje,
            alicuotaIva: articulo.alicuotaIva,
            stockMinimo: articulo.stockMinimo,
          });
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

  private fallarCarga(mensaje: string): void {
    this.cargando.set(false);
    this.sinFormulario.set(true);
    this.error.set(mensaje);
  }

  /** Solo lo editable: stock actual y precio final los resuelve el backend. */
  private armarRequest(): ArticuloRequest {
    const v = this.form.getRawValue();
    return {
      codigoInterno: v.codigoInterno.trim(),
      codigoBarras: textoONull(v.codigoBarras),
      codigoProveedor: textoONull(v.codigoProveedor),
      descripcion: v.descripcion.trim(),
      rubroId: v.rubroId,
      proveedorId: v.proveedorId,
      costo: v.costo ?? 0,
      margenPorcentaje: v.margenPorcentaje,
      alicuotaIva: v.alicuotaIva,
      unidad: v.unidad,
      stockMinimo: v.stockMinimo ?? 0,
      ubicacion: textoONull(v.ubicacion),
      // El formulario no reactiva ni da de baja: conserva el estado que ya tenía.
      activo: this.articulo()?.activo ?? true,
    };
  }
}

function mensajeDe(err: unknown, porDefecto: string): string {
  return err instanceof HttpErrorResponse && err.error?.message ? err.error.message : porDefecto;
}

/** Los `errors` de un 400/409 del backend (campo → mensaje); vacío si no trae. */
function erroresPorCampo(err: unknown): Partial<Record<CampoArticulo, string>> {
  if (erroresDeCampos(err).length === 0) return {};
  return (err as HttpErrorResponse).error.errors as Partial<Record<CampoArticulo, string>>;
}

function formatearPorcentaje(valor: number): string {
  return new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 }).format(valor);
}
