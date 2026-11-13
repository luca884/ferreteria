import { DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { mensajeDe } from '../../core/api/api-error';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { descargar } from '../../core/archivos/descarga';
import { formatearFecha } from '../../core/formato/fecha-hora';
import { Avisos } from '../../core/avisos/avisos';
import { MontoPipe, formatearCosto } from '../../core/formato/monto';
import { cantidadConUnidad, porUnidad } from '../../core/formato/unidad';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { FilasElegibles } from '../../shared/filas-elegibles';
import { MotivoDeAccion, Motivos, ariaDe } from '../../shared/motivo-de-accion';
import { ALICUOTA_IVA_LABELS, AjusteStockResponse, Articulo } from './articulo.models';
import { ArticulosService } from './articulos.service';
import { DialogoAjusteStock } from './dialogo-ajuste-stock';

const TAMANO_PAGINA = 20;

/** S5: lo que se explica al intentar una acción de la barra sin un artículo elegido. */
const MOTIVO_SIN_ELEGIR_EDITAR = 'Elegí un artículo de la lista para editarlo.';
const MOTIVO_SIN_ELEGIR_FICHA = 'Elegí un artículo de la lista para ver su ficha.';
const MOTIVO_SIN_ELEGIR_AJUSTE = 'Elegí un artículo de la lista para ajustar el stock.';
const MOTIVO_SIN_ELEGIR_MOVIMIENTOS = 'Elegí un artículo de la lista para ver sus movimientos.';
const MOTIVO_SIN_ELEGIR_CLIENTES = 'Elegí un artículo de la lista para ver qué clientes lo compraron.';
const MOTIVO_SIN_ELEGIR_BAJA = 'Elegí un artículo de la lista para darlo de baja.';

/** Las acciones de la barra que pueden estar apagadas por una regla de negocio (S5). */
type Accion = 'ficha' | 'ajuste' | 'movimientos' | 'clientes' | 'baja';

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
  protected readonly porUnidad = porUnidad;
  protected readonly formatearCosto = formatearCosto;
  protected readonly formatearFecha = formatearFecha;

  protected readonly pagina = signal<PageResponse<Articulo> | null>(null);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);
  /** Aviso de éxito (guardado en el formulario, reactivación, ajuste de stock). */
  protected readonly aviso = signal<string | null>(this.avisos.tomar()?.texto ?? null);
  /** Búsqueda ya aplicada: la que se manda al servidor, no lo que se está tipeando. */
  protected readonly busquedaAplicada = signal('');
  protected readonly incluirInactivos = signal(false);
  /** Ofertas (S3): solo los que hoy tienen una oferta que baja el precio. */
  protected readonly soloEnOferta = signal(false);
  /** El CSV se está bajando: Exportar queda deshabilitado. */
  protected readonly exportando = signal(false);
  /**
   * Fila elegida en la página: la barra actúa sobre ella y Enter abre la ficha. El Dueño la edita; el Vendedor la ve
   * de solo lectura, sin costos (H07; bugs de la guía, S20).
   */
  protected readonly filas = new FilasElegibles(
    computed(() => this.pagina()?.items ?? []),
    () => true,
    (a) => this.abrirFicha(a),
  );
  /** Artículo que se está por dar de baja (diálogo de confirmación abierto). */
  protected readonly aDarDeBaja = signal<Articulo | null>(null);
  /** Artículo cuyo stock se está ajustando (panel abierto). */
  protected readonly aAjustar = signal<Articulo | null>(null);
  /** El motivo a la vista de la última acción que no se pudo usar, o null. Se anuncia con role="status" (S5). */
  protected readonly motivo = new MotivoDeAccion(() => this.motivos());
  /** Por qué cada acción de la barra no se puede usar: null en la que sí se puede (S5). */
  protected readonly motivos = computed<Motivos<Accion>>(() => {
    const articulo = this.filas.elegido();
    return {
      // El Dueño edita y el Vendedor ve la ficha: el mismo botón con dos textos.
      ficha: articulo ? null : this.motivoSinFicha(),
      ajuste: articulo ? (articulo.activo ? null : this.textoBajaSinAjuste(articulo)) : MOTIVO_SIN_ELEGIR_AJUSTE,
      movimientos: articulo ? null : MOTIVO_SIN_ELEGIR_MOVIMIENTOS,
      clientes: articulo ? null : MOTIVO_SIN_ELEGIR_CLIENTES,
      baja: articulo ? null : MOTIVO_SIN_ELEGIR_BAJA,
    };
  });
  /** Para la plantilla: 'true' apaga el botón, null lo deja prendido. */
  protected readonly ariaDe = ariaDe;

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
   * Editar del Dueño o «Ver ficha» del Vendedor (barra, Enter o doble clic): la misma ruta, que al Vendedor le muestra
   * la ficha de solo lectura. La dirección del listado viaja a la ficha para poder volver, con el artículo como
   * `elegido` para que al volver siga elegido.
   */
  protected abrirFicha(articulo: Articulo | null = this.filas.elegido()): void {
    // S5: sin fila elegida el botón avisa el motivo en vez de quedar apagado sin explicación. Enter y doble clic
    // traen su propio artículo, así que la acción mira el argumento y no la tabla.
    if (!articulo) {
      this.motivo.mostrar('ficha');
      return;
    }
    void this.router.navigate(['/articulos', articulo.id], {
      queryParams: { elegido: articulo.id },
      queryParamsHandling: 'merge',
    });
  }

  protected pedirBaja(): void {
    // S5: sin fila elegida el botón avisa el motivo en vez de quedar apagado sin explicación.
    const articulo = this.filas.elegido();
    if (this.motivo.mostrar('baja') || articulo === null) return;
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
    if (!this.auth.esDueno()) return;
    // S5: sin fila elegida el botón avisa el motivo en vez de quedar apagado sin explicación.
    const articulo = this.filas.elegido();
    if (this.motivo.mostrar('movimientos') || articulo === null) return;
    void this.router.navigate(['/articulos', articulo.id, 'movimientos']);
  }

  /** G29: los clientes que compraron el elegido (el Ctrl+A de GDS), también de uno dado de baja. */
  protected verClientes(): void {
    if (!this.auth.esDueno()) return;
    // S5: sin fila elegida el botón avisa el motivo en vez de quedar apagado sin explicación.
    const articulo = this.filas.elegido();
    if (this.motivo.mostrar('clientes') || articulo === null) return;
    void this.router.navigate(['/articulos', articulo.id, 'clientes']);
  }

  /**
   * Baja en CSV lo que muestra la pantalla: la búsqueda aplicada, los inactivos y «Solo en oferta», todas las páginas
   * (GDS-19; bugs de la guía, S19).
   */
  protected exportar(): void {
    if (!this.auth.esDueno() || this.exportando()) return;
    this.limpiarMensajes();
    this.exportando.set(true);
    this.service.exportar(this.busquedaAplicada(), this.incluirInactivos(), this.soloEnOferta()).subscribe({
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
    // S5: sin fila elegida, o con un artículo dado de baja, el botón avisa el motivo en vez de quedar apagado sin
    // explicación (el mismo texto que la línea `#motivo-sin-ajuste`).
    const articulo = this.filas.elegido();
    if (this.motivo.mostrar('ajuste') || articulo === null) return;
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

  /** Lo que se explica al intentar abrir la ficha sin una fila elegida: el Dueño edita y el Vendedor la ve. */
  private motivoSinFicha(): string {
    return this.auth.esDueno() ? MOTIVO_SIN_ELEGIR_EDITAR : MOTIVO_SIN_ELEGIR_FICHA;
  }

  /** El motivo de Ajustar stock con el artículo dado de baja: el mismo texto que muestra la barra. */
  protected textoBajaSinAjuste(articulo: Articulo): string {
    return `«${articulo.descripcion}» está dado de baja. Reactivalo para ajustar el stock.`;
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
