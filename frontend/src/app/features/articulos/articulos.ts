import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { Avisos } from '../../core/avisos/avisos';
import { MontoPipe } from '../../core/formato/monto';
import { cantidadConUnidad } from '../../core/formato/unidad';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { ALICUOTA_IVA_LABELS, AjusteStockResponse, Articulo } from './articulo.models';
import { ArticulosService } from './articulos.service';
import { DialogoAjusteStock } from './dialogo-ajuste-stock';

const TAMANO_PAGINA = 20;

@Component({
  selector: 'app-articulos',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DecimalPipe,
    MontoPipe,
    Confirmacion,
    DialogoAjusteStock,
    BarraAcciones,
  ],
  templateUrl: './articulos.html',
  styleUrl: './articulos.css',
})
export class Articulos implements OnInit {
  private readonly service = inject(ArticulosService);
  private readonly router = inject(Router);
  private readonly avisos = inject(Avisos);
  protected readonly auth = inject(AuthService);

  protected readonly alicuotaLabels = ALICUOTA_IVA_LABELS;
  protected readonly cantidadConUnidad = cantidadConUnidad;

  protected readonly pagina = signal<PageResponse<Articulo> | null>(null);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);
  /** Aviso de éxito (guardado en el formulario, reactivación, ajuste de stock). */
  protected readonly aviso = signal<string | null>(this.avisos.tomar());
  /** Búsqueda ya aplicada: la que se manda al servidor, no lo que se está tipeando. */
  protected readonly busquedaAplicada = signal('');
  protected readonly incluirInactivos = signal(false);
  /** Artículo que se está por dar de baja (diálogo de confirmación abierto). */
  protected readonly aDarDeBaja = signal<Articulo | null>(null);
  /** Artículo cuyo stock se está ajustando (diálogo abierto). */
  protected readonly aAjustar = signal<Articulo | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({ q: [''] });

  ngOnInit(): void {
    this.cargar(0);
  }

  /** Enter o el botón Buscar: aplica el texto y vuelve a la primera página. */
  protected buscar(): void {
    this.busquedaAplicada.set(this.form.getRawValue().q.trim());
    this.cargar(0);
  }

  /** La × del buscador (o borrar todo): si había una búsqueda aplicada, vuelve a mostrar todo. */
  protected alEscribirBusqueda(valor: string): void {
    if (valor.trim() === '' && this.busquedaAplicada() !== '') this.buscar();
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

  /** Clic o Enter en la fila: abre Editar (solo el Dueño). */
  protected abrir(articulo: Articulo): void {
    if (this.auth.esDueno()) void this.router.navigate(['/articulos', articulo.id]);
  }

  protected pedirBaja(articulo: Articulo): void {
    this.aDarDeBaja.set(articulo);
  }

  protected desactivar(): void {
    const articulo = this.aDarDeBaja();
    this.aDarDeBaja.set(null);
    if (!articulo) return;
    this.limpiarMensajes();
    this.service.desactivar(articulo.id).subscribe({
      next: () => this.cargar(this.pagina()?.page ?? 0, true),
      error: (err: unknown) => {
        this.error.set(mensajeDe(err, 'No se pudo dar de baja el artículo.'));
      },
    });
  }

  protected reactivar(articulo: Articulo): void {
    this.limpiarMensajes();
    this.service.reactivar(articulo.id).subscribe({
      next: () => {
        this.aviso.set(`Se reactivó «${articulo.descripcion}».`);
        this.cargar(this.pagina()?.page ?? 0);
      },
      error: (err: unknown) => {
        this.error.set(mensajeDe(err, 'No se pudo reactivar el artículo.'));
      },
    });
  }

  protected ajustarStock(articulo: Articulo): void {
    this.limpiarMensajes();
    this.aAjustar.set(articulo);
  }

  /** El diálogo confirmó: la fila muestra el stock nuevo sin recargar la página. */
  protected stockAjustado(ajuste: AjusteStockResponse): void {
    const articulo = this.aAjustar();
    this.aAjustar.set(null);
    this.pagina.update((p) =>
      p && {
        ...p,
        items: p.items.map((a) => (a.id === ajuste.articuloId ? { ...a, stockActual: ajuste.stockActual } : a)),
      },
    );
    if (articulo) {
      this.aviso.set(
        `Stock de «${articulo.descripcion}» ajustado: ahora hay ${cantidadConUnidad(ajuste.stockActual, articulo.unidad)}.`,
      );
    }
  }

  private limpiarMensajes(): void {
    this.error.set(null);
    this.aviso.set(null);
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
