import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { descargar } from '../../core/archivos/descarga';
import { formatearFecha } from '../../core/formato/fecha-hora';
import { Avisos } from '../../core/avisos/avisos';
import { MontoPipe, formatearCosto } from '../../core/formato/monto';
import { cantidadConUnidad } from '../../core/formato/unidad';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { FilasElegibles } from '../../shared/filas-elegibles';
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
  protected readonly auth = inject(AuthService);

  protected readonly alicuotaLabels = ALICUOTA_IVA_LABELS;
  protected readonly cantidadConUnidad = cantidadConUnidad;
  protected readonly formatearCosto = formatearCosto;
  protected readonly formatearFecha = formatearFecha;

  protected readonly pagina = signal<PageResponse<Articulo> | null>(null);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);
  /** Aviso de éxito (guardado en el formulario, reactivación, ajuste de stock). */
  protected readonly aviso = signal<string | null>(this.avisos.tomar());
  /** Búsqueda ya aplicada: la que se manda al servidor, no lo que se está tipeando. */
  protected readonly busquedaAplicada = signal('');
  protected readonly incluirInactivos = signal(false);
  /** Ofertas (S3): solo los que hoy tienen una oferta que baja el precio. */
  protected readonly soloEnOferta = signal(false);
  /** El CSV se está bajando: Exportar queda deshabilitado. */
  protected readonly exportando = signal(false);
  /** Fila elegida en la página (solo el Dueño, que es quien tiene acciones): la barra actúa sobre ella; Enter edita. */
  protected readonly filas = new FilasElegibles(
    computed(() => this.pagina()?.items ?? []),
    () => this.auth.esDueno(),
    (a) => this.editar(a),
  );
  /** Artículo que se está por dar de baja (diálogo de confirmación abierto). */
  protected readonly aDarDeBaja = signal<Articulo | null>(null);
  /** Artículo cuyo stock se está ajustando (panel abierto). */
  protected readonly aAjustar = signal<Articulo | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({ q: [''] });
  /** Se vuelve de editar un artículo: la primera carga le pone el foco a su fila. */
  private enfocarElegidaAlCargar = false;

  ngOnInit(): void {
    // Al volver de Editar la dirección trae la búsqueda, la página, los inactivos y el artículo editado:
    // se retoma donde estaba, con esa fila elegida y con el foco.
    const params = this.route.snapshot.queryParamMap;
    const q = (params.get('q') ?? '').trim();
    const pagina = Number(params.get('pagina'));
    const elegido = Number(params.get('elegido'));
    this.busquedaAplicada.set(q);
    this.form.setValue({ q });
    this.incluirInactivos.set(params.get('inactivos') === '1');
    this.soloEnOferta.set(params.get('oferta') === '1');
    if (Number.isInteger(elegido) && elegido > 0) {
      this.filas.elegidoId.set(elegido);
      this.enfocarElegidaAlCargar = true;
    }
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

  protected cambiarSoloEnOferta(solo: boolean): void {
    this.soloEnOferta.set(solo);
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

  /**
   * Editar (barra, Enter o doble clic): la dirección del listado viaja al formulario para poder volver, con el
   * artículo como `elegido` para que al volver siga elegido.
   */
  protected editar(articulo: Articulo | null = this.filas.elegido()): void {
    if (!articulo || !this.auth.esDueno()) return;
    void this.router.navigate(['/articulos', articulo.id], {
      queryParams: { elegido: articulo.id },
      queryParamsHandling: 'merge',
    });
  }

  protected pedirBaja(): void {
    this.aDarDeBaja.set(this.filas.elegido());
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
    const articulo = this.filas.elegido();
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

  /** G29: los movimientos de stock del elegido, también de uno dado de baja (su historia sigue ahí). */
  protected verMovimientos(): void {
    const articulo = this.filas.elegido();
    if (!articulo || !this.auth.esDueno()) return;
    void this.router.navigate(['/articulos', articulo.id, 'movimientos']);
  }

  /** G29: los clientes que compraron el elegido (el Ctrl+A de GDS), también de uno dado de baja. */
  protected verClientes(): void {
    const articulo = this.filas.elegido();
    if (!articulo || !this.auth.esDueno()) return;
    void this.router.navigate(['/articulos', articulo.id, 'clientes']);
  }

  /** Baja en CSV lo que muestra la pantalla: la búsqueda aplicada y los inactivos, todas las páginas (GDS-19). */
  protected exportar(): void {
    if (!this.auth.esDueno() || this.exportando()) return;
    this.limpiarMensajes();
    this.exportando.set(true);
    this.service.exportar(this.busquedaAplicada(), this.incluirInactivos()).subscribe({
      next: (respuesta) => {
        this.exportando.set(false);
        descargar(respuesta, 'articulos.csv');
      },
      error: () => {
        this.exportando.set(false);
        this.error.set('No se pudieron exportar los artículos.');
      },
    });
  }

  protected ajustarStock(): void {
    const articulo = this.filas.elegido();
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
      .buscar(this.busquedaAplicada(), page, TAMANO_PAGINA, this.incluirInactivos(), this.soloEnOferta())
      .subscribe({
        next: (resultado) => {
          if (retrocederSiVacia && resultado.items.length === 0 && page > 0) {
            this.cargar(page - 1);
            return;
          }
          this.pagina.set(resultado);
          if (this.enfocarElegidaAlCargar) {
            this.enfocarElegidaAlCargar = false;
            this.filas.enfocarElegida();
          }
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
    if (this.soloEnOferta()) queryParams['oferta'] = 1;
    void this.router.navigate([], { relativeTo: this.route, queryParams, replaceUrl: true });
  }
}

function mensajeDe(err: unknown, porDefecto: string): string {
  return err instanceof HttpErrorResponse && err.error?.message ? err.error.message : porDefecto;
}
