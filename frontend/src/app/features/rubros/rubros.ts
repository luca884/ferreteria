import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import { Rubro } from './rubro.models';
import { RubrosService } from './rubros.service';

@Component({
  selector: 'app-rubros',
  imports: [ReactiveFormsModule, DecimalPipe],
  templateUrl: './rubros.html',
  styleUrl: './rubros.css',
})
export class Rubros implements OnInit {
  private readonly service = inject(RubrosService);
  protected readonly auth = inject(AuthService);

  protected readonly rubros = signal<Rubro[]>([]);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly guardando = signal(false);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    nombre: ['', [Validators.required, Validators.maxLength(80)]],
    margenPorcentaje: [30, [Validators.required, Validators.min(0), Validators.max(1000)]],
  });

  ngOnInit(): void {
    this.cargar();
  }

  protected crear(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.guardando.set(true);
    this.error.set(null);
    this.service.crear(this.form.getRawValue()).subscribe({
      next: (rubro) => {
        this.rubros.update((lista) =>
          [...lista, rubro].sort((a, b) => a.nombre.localeCompare(b.nombre)),
        );
        this.form.reset({ nombre: '', margenPorcentaje: 30 });
        this.guardando.set(false);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        this.error.set(
          err instanceof HttpErrorResponse && err.error?.message
            ? err.error.message
            : 'No se pudo guardar el rubro.',
        );
      },
    });
  }

  private cargar(): void {
    this.service.listar().subscribe({
      next: (lista) => {
        this.rubros.set(lista);
        this.cargando.set(false);
      },
      error: () => {
        this.cargando.set(false);
        this.error.set('No se pudieron cargar los rubros.');
      },
    });
  }
}
