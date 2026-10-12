import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { ALICUOTA_IVA_LABELS, Articulo, UNIDAD_LABELS } from './articulo.models';
import { ArticulosService } from './articulos.service';

const TAMANO_PAGINA = 20;

@Component({
  selector: 'app-articulos',
  imports: [ReactiveFormsModule, RouterLink, DecimalPipe],
  templateUrl: './articulos.html',
  styleUrl: './articulos.css',
})
export class Articulos implements OnInit {
  private readonly service = inject(ArticulosService);
  protected readonly auth = inject(AuthService);

  protected readonly alicuotaLabels = ALICUOTA_IVA_LABELS;
  protected readonly unidadLabels = UNIDAD_LABELS;

  protected readonly pagina = signal<PageResponse<Articulo> | null>(null);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);
  /** Búsqueda ya aplicada: la que se manda al servidor, no lo que se está tipeando. */
  protected readonly busquedaAplicada = signal('');
  protected readonly incluirInactivos = signal(false);

  protected readonly form = inject(FormBuilder).nonNullable.group({ q: [''] });

  ngOnInit(): void {
    this.cargar(0);
  }

  /** Enter o el botón Buscar: aplica el texto y vuelve a la primera página. */
  protected buscar(): void {
    this.busquedaAplicada.set(this.form.getRawValue().q.trim());
    this.cargar(0);
  }

  protected cambiarInactivos(incluir: boolean): void {
    this.incluirInactivos.set(incluir);
    this.cargar(0);
  }

  protected anterior(): void {
    const actual = this.pagina();
    if (actual && actual.page > 0) this.cargar(actual.page - 1);
  }

  protected siguiente(): void {
    const actual = this.pagina();
    if (actual && actual.page + 1 < actual.totalPages) this.cargar(actual.page + 1);
  }

  protected desactivar(articulo: Articulo): void {
    if (
      !confirm(
        `¿Dar de baja ${articulo.descripcion}? Deja de aparecer en el listado, pero conserva su historial de precios.`,
      )
    ) {
      return;
    }
    this.error.set(null);
    this.service.desactivar(articulo.id).subscribe({
      next: () => this.cargar(this.pagina()?.page ?? 0, true),
      error: (err: unknown) => {
        this.error.set(mensajeDe(err, 'No se pudo dar de baja el artículo.'));
      },
    });
  }

  /** Con `retrocederSiVacia`, si la página quedó sin artículos (por una baja) trae la anterior. */
  private cargar(page: number, retrocederSiVacia = false): void {
    this.cargando.set(true);
    this.error.set(null);
    this.service
      .buscar(this.busquedaAplicada(), page, TAMANO_PAGINA, this.incluirInactivos())
      .subscribe({
        next: (resultado) => {
          if (retrocederSiVacia && resultado.items.length === 0 && page > 0) {
            this.cargar(page - 1);
            return;
          }
          this.pagina.set(resultado);
          this.cargando.set(false);
        },
        error: (err: unknown) => {
          this.cargando.set(false);
          this.error.set(mensajeDe(err, 'No se pudieron cargar los artículos.'));
        },
      });
  }
}

function mensajeDe(err: unknown, porDefecto: string): string {
  return err instanceof HttpErrorResponse && err.error?.message ? err.error.message : porDefecto;
}
