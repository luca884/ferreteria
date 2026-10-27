import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ArticulosService } from '../articulos/articulos.service';

/**
 * S5: una ferretería nueva entra a un Mostrador vacío. Mientras no tenga ningún artículo, el Dueño ve por dónde empezar;
 * en cuanto hay uno, el panel desaparece solo. El Vendedor nunca lo ve. Pregunta por una página de un artículo (con los
 * dados de baja, que también cuentan) y mira el total: no hay endpoint propio. Si no se pudo saber, no se muestra.
 */
@Component({
  selector: 'app-primeros-pasos',
  imports: [RouterLink],
  templateUrl: './primeros-pasos.html',
  styleUrl: './primeros-pasos.css',
})
export class PrimerosPasos {
  private readonly auth = inject(AuthService);
  private readonly articulos = inject(ArticulosService);

  /** null mientras no se sabe cuántos artículos hay. */
  private readonly total = signal<number | null>(null);
  protected readonly visible = computed(() => this.auth.sesion()?.rol === 'DUENO' && this.total() === 0);

  constructor() {
    if (this.auth.sesion()?.rol !== 'DUENO') return;
    this.articulos
      .buscar('', 0, 1, true)
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe({ next: (pagina) => this.total.set(pagina.totalElements), error: () => undefined });
  }
}
