import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, Injector, OnInit, afterNextRender, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
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
  private readonly route = inject(ActivatedRoute);
  private readonly avisos = inject(Avisos);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
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
  /** Fila elegida (por id, para que siga elegida si la página se recarga y el artículo sigue ahí). */
  protected readonly elegidoId = signal<number | null>(null);
  protected readonly elegido = computed(
    () => this.pagina()?.items.find((a) => a.id === this.elegidoId()) ?? null,
  );
  /** Artículo que se está por dar de baja (diálogo de confirmación abierto). */
  protected readonly aDarDeBaja = signal<Articulo | null>(null);
  /** Artículo cuyo stock se está ajustando (panel abierto). */
  protected readonly aAjustar = signal<Articulo | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({ q: [''] });

  ngOnInit(): void {
    // Al volver de Editar la dirección trae la búsqueda, la página y los inactivos: se retoma donde estaba.
    const params = this.route.snapshot.queryParamMap;
    const q = (params.get('q') ?? '').trim();
    const pagina = Number(params.get('pagina'));
    this.busquedaAplicada.set(q);
    this.form.setValue({ q });
    this.incluirInactivos.set(params.get('inactivos') === '1');
    this.cargar(Number.isInteger(pagina) && pagina > 1 ? pagina - 1 : 0);
  }

  /** Enter en el buscador: aplica el texto y vuelve a la primera página. */
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

  /** Clic o foco en una fila: la elige (solo el Dueño, que es quien tiene acciones). */
  protected elegir(articulo: Articulo): void {
    if (this.auth.esDueno()) this.elegidoId.set(articulo.id);
  }

  /** La fila que entra en el orden de Tab: la elegida o, si no hay, la primera. */
  protected esFilaTabulable(articulo: Articulo, indice: number): boolean {
    return this.elegido() ? articulo.id === this.elegidoId() : indice === 0;
  }

  /** ↑/↓ mueven la elección y el foco; Enter abre Editar. */
  protected alTeclearFila(evento: KeyboardEvent, articulo: Articulo, indice: number): void {
    if (!this.auth.esDueno()) return;
    if (evento.key === 'Enter') {
      evento.preventDefault();
      this.editar(articulo);
      return;
    }
    const paso = evento.key === 'ArrowDown' ? 1 : evento.key === 'ArrowUp' ? -1 : 0;
    if (paso === 0) return;
    evento.preventDefault();
    const items = this.pagina()?.items ?? [];
    const destino = Math.min(Math.max(indice + paso, 0), items.length - 1);
    this.elegidoId.set(items[destino].id);
    afterNextRender(
      () => this.host.nativeElement.querySelectorAll<HTMLElement>('tbody tr')[destino]?.focus(),
      { injector: this.injector },
    );
  }

  /** Editar (barra, Enter o doble clic): la dirección del listado viaja al formulario para poder volver. */
  protected editar(articulo: Articulo | null = this.elegido()): void {
    if (!articulo || !this.auth.esDueno()) return;
    void this.router.navigate(['/articulos', articulo.id], { queryParamsHandling: 'preserve' });
  }

  protected pedirBaja(): void {
    this.aDarDeBaja.set(this.elegido());
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

  protected reactivar(): void {
    const articulo = this.elegido();
    if (!articulo) return;
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

  protected ajustarStock(): void {
    const articulo = this.elegido();
    if (!articulo?.activo) return;
    this.limpiarMensajes();
    this.aAjustar.set(articulo);
  }

  /** El panel confirmó: la fila muestra el stock nuevo sin recargar la página. */
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
          this.guardarEnLaDireccion(resultado.page);
        },
        error: (err: unknown) => {
          this.cargando.set(false);
          this.error.set(mensajeDe(err, 'No se pudieron cargar los artículos.'));
        },
      });
  }

  /** Búsqueda, página (desde 1) e inactivos en la dirección, solo los que no son los de entrada. */
  private guardarEnLaDireccion(page: number): void {
    const queryParams: Params = {};
    if (this.busquedaAplicada() !== '') queryParams['q'] = this.busquedaAplicada();
    if (page > 0) queryParams['pagina'] = page + 1;
    if (this.incluirInactivos()) queryParams['inactivos'] = 1;
    void this.router.navigate([], { relativeTo: this.route, queryParams, replaceUrl: true });
  }
}

function mensajeDe(err: unknown, porDefecto: string): string {
  return err instanceof HttpErrorResponse && err.error?.message ? err.error.message : porDefecto;
}
