import { DecimalPipe } from '@angular/common';
import { MontoPipe } from '../../core/formato/monto';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { erroresDeCampos } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { Rubro } from '../rubros/rubro.models';
import { RubrosService } from '../rubros/rubros.service';
import {
  ALICUOTAS_IVA,
  ALICUOTA_IVA_LABELS,
  AlicuotaIva,
  Articulo,
  ArticuloRequest,
  UNIDADES,
  UNIDAD_LABELS,
  Unidad,
} from './articulo.models';
import { ArticulosService } from './articulos.service';

/** Un texto con solo espacios cuenta como vacío. */
function noVacio(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim() === '' ? { required: true } : null;
}

function textoONull(valor: string): string | null {
  const limpio = valor.trim();
  return limpio === '' ? null : limpio;
}

@Component({
  selector: 'app-articulo-form',
  imports: [ReactiveFormsModule, RouterLink, DecimalPipe, MontoPipe],
  templateUrl: './articulo-form.html',
  styleUrl: './articulo-form.css',
})
export class ArticuloForm implements OnInit {
  private readonly service = inject(ArticulosService);
  private readonly rubrosService = inject(RubrosService);
  private readonly proveedoresService = inject(ProveedoresService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);

  protected readonly alicuotas = ALICUOTAS_IVA;
  protected readonly alicuotaLabels = ALICUOTA_IVA_LABELS;
  protected readonly unidades = UNIDADES;
  protected readonly unidadLabels = UNIDAD_LABELS;

  protected readonly rubros = signal<Rubro[]>([]);
  protected readonly proveedores = signal<Proveedor[]>([]);
  /** El artículo tal como lo devolvió el backend; null en el alta. */
  protected readonly articulo = signal<Articulo | null>(null);
  protected readonly cargando = signal(true);
  protected readonly guardando = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Mensajes por campo de un 400 de validación del backend; si hay, se muestran en vez de `error`. */
  protected readonly erroresCampos = signal<string[]>([]);
  /** Si falló la carga del artículo no hay nada que editar. */
  protected readonly sinFormulario = signal(false);

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

  protected guardar(): void {
    if (!this.auth.esDueno()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.guardando.set(true);
    this.error.set(null);
    this.erroresCampos.set([]);
    const request = this.armarRequest();
    const guardado =
      this.id === null ? this.service.crear(request) : this.service.actualizar(this.id, request);
    guardado.subscribe({
      next: () => {
        this.guardando.set(false);
        void this.router.navigate(['/articulos']);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        this.erroresCampos.set(erroresDeCampos(err));
        this.error.set(mensajeDe(err, 'No se pudo guardar el artículo.'));
      },
    });
  }

  private cargar(): void {
    forkJoin({
      rubros: this.rubrosService.listar(),
      proveedores: this.proveedoresService.listar(),
      articulo: this.id === null ? of(null) : this.service.obtener(this.id),
    }).subscribe({
      next: ({ rubros, proveedores, articulo }) => {
        this.rubros.set(rubros);
        this.proveedores.set(proveedores.filter((p) => p.activo));
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
