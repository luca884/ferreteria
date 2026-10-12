import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import { erroresDeCampos } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { Proveedor, ProveedorRequest } from './proveedor.models';
import { ProveedoresService } from './proveedores.service';

@Component({
  selector: 'app-proveedores',
  imports: [ReactiveFormsModule],
  templateUrl: './proveedores.html',
  styleUrl: './proveedores.css',
})
export class Proveedores implements OnInit {
  private readonly service = inject(ProveedoresService);
  protected readonly auth = inject(AuthService);

  protected readonly proveedores = signal<Proveedor[]>([]);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);
  /** Mensajes por campo de un 400 de validación del backend; si hay, se muestran en vez de `error`. */
  protected readonly erroresCampos = signal<string[]>([]);
  protected readonly guardando = signal(false);
  /** Id del proveedor que se está editando; null mientras se da de alta uno nuevo. */
  protected readonly editandoId = signal<number | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    nombre: ['', [Validators.required, Validators.maxLength(100)]],
    cuit: ['', [Validators.pattern(/^\d{2}-\d{8}-\d$/)]],
  });

  ngOnInit(): void {
    this.cargar();
  }

  protected guardar(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { nombre, cuit } = this.form.getRawValue();
    const request: ProveedorRequest = {
      nombre: nombre.trim(),
      cuit: cuit.trim() === '' ? null : cuit.trim(),
      activo: true,
    };
    const id = this.editandoId();
    const llamada: Observable<Proveedor> =
      id === null ? this.service.crear(request) : this.service.actualizar(id, request);

    this.guardando.set(true);
    this.limpiarErrores();
    llamada.subscribe({
      next: (proveedor) => {
        this.proveedores.update((lista) =>
          [...lista.filter((p) => p.id !== proveedor.id), proveedor].sort((a, b) =>
            a.nombre.localeCompare(b.nombre),
          ),
        );
        this.vaciarFormulario();
        this.guardando.set(false);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        this.erroresCampos.set(erroresDeCampos(err));
        this.error.set(mensajeDe(err, 'No se pudo guardar el proveedor.'));
      },
    });
  }

  protected editar(proveedor: Proveedor): void {
    this.limpiarErrores();
    this.editandoId.set(proveedor.id);
    this.form.reset({ nombre: proveedor.nombre, cuit: proveedor.cuit ?? '' });
  }

  protected cancelar(): void {
    this.limpiarErrores();
    this.vaciarFormulario();
  }

  protected desactivar(proveedor: Proveedor): void {
    if (!confirm(`¿Dar de baja a ${proveedor.nombre}? Sus artículos conservan la referencia.`)) {
      return;
    }
    this.limpiarErrores();
    this.service.desactivar(proveedor.id).subscribe({
      next: () => {
        this.proveedores.update((lista) => lista.filter((p) => p.id !== proveedor.id));
        if (this.editandoId() === proveedor.id) this.vaciarFormulario();
      },
      error: (err: unknown) => {
        this.error.set(mensajeDe(err, 'No se pudo dar de baja el proveedor.'));
      },
    });
  }

  private limpiarErrores(): void {
    this.error.set(null);
    this.erroresCampos.set([]);
  }

  private vaciarFormulario(): void {
    this.editandoId.set(null);
    this.form.reset({ nombre: '', cuit: '' });
  }

  private cargar(): void {
    this.service.listar().subscribe({
      next: (lista) => {
        this.proveedores.set(lista);
        this.cargando.set(false);
      },
      error: () => {
        this.cargando.set(false);
        this.error.set('No se pudieron cargar los proveedores.');
      },
    });
  }
}

function mensajeDe(err: unknown, porDefecto: string): string {
  return err instanceof HttpErrorResponse && err.error?.message ? err.error.message : porDefecto;
}
