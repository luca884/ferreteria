import { DecimalPipe } from '@angular/common';
import { Component, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { from } from 'rxjs';
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
import { HojaInferior } from '../../shared/hoja-inferior';
import { MotivoDeAccion, Motivos, ariaDe } from '../../shared/motivo-de-accion';
import { claveDeEtiquetas, sumarEtiquetas } from '../etiquetas/etiquetas.storage';
import { RubrosService } from '../rubros/rubros.service';
import { ALICUOTA_IVA_LABELS, AjusteStockResponse, Articulo } from './articulo.models';
import { ArticulosService } from './articulos.service';
import { CamaraEscaner } from './camara-escaner';
import { DialogoAjusteStock } from './dialogo-ajuste-stock';
import { EscanerCamara } from './escaner-camara';

const TAMANO_PAGINA = 20;

/** La pantalla del celular (la misma consulta de todo el CSS móvil); sin `matchMedia` (pruebas, impresión) no lo es. */
const esCelular = (): boolean =>
  typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 639.98px)').matches;

/** movil-sectores T6: cuánto hay que mantener apretada una fila para entrar a la selección, y cuánto puede moverse el dedo. */
const PULSACION_LARGA_MS = 500;
const MOVIMIENTO_MAXIMO_PX = 10;
/** La pista de la selección se muestra hasta que se usa una vez (por dispositivo). */
const CLAVE_PISTA_SELECCION = 'ferreteria.pista-seleccion-articulos';

function pistaUsada(): boolean {
  try {
    return localStorage.getItem(CLAVE_PISTA_SELECCION) === '1';
  } catch {
    return false;
  }
}

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
    HojaInferior,
    EscanerCamara,
  ],
  templateUrl: './articulos.html',
  styleUrl: './articulos.css',
})
export class Articulos implements OnInit {
  private readonly service = inject(ArticulosService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly avisos = inject(Avisos);
  private readonly rubrosService = inject(RubrosService);
  protected readonly auth = inject(AuthService);
  private readonly lectorDisponible = toSignal(from(inject(CamaraEscaner).disponible()), { initialValue: false });
  private readonly buscadorMovil = viewChild<ElementRef<HTMLInputElement>>('qMovil');

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
  /**
   * movil-sectores T5 (5a): el celular muestra los dos filtros como chips de 44 px (mismo estado que los tildes de la
   * barra de escritorio) y las acciones que no son de un artículo detrás del ⋯ de la barra, en una hoja inferior.
   */
  protected readonly menuAbierto = signal(false);
  /**
   * movil-sectores T6: selección múltiple, sólo del Dueño en el celular. Mantener apretada una fila entra al modo; ahí
   * un toque suma o saca, y la única acción es «Agregar N a Etiquetas» (CU-03). Cambiar de página sale del modo.
   */
  protected readonly seleccionando = signal(false);
  protected readonly elegidos = signal<ReadonlySet<number>>(new Set());
  /** Cuántos artículos tiene la lista de Etiquetas tras agregar: muestra el atajo «Ir a Etiquetas». */
  protected readonly enListaDeEtiquetas = signal<number | null>(null);
  /** La pista «Mantené apretado…» se descarta sola la primera vez que se usa la selección. */
  protected readonly pistaVista = signal(!pistaUsada());
  private pulsacion: { temporizador: ReturnType<typeof setTimeout>; x: number; y: number } | null = null;
  /** La pulsación larga ya entró al modo: el toque que el navegador manda al soltar no tiene que contar. */
  private pulsacionLarga = false;
  /** movil-sectores T7: el escáner de cámara a pantalla completa está abierto (sólo en el celular). */
  protected readonly escanerAbierto = signal(false);
  /** Nombre de cada rubro por id, para la línea de detalle de la fila del celular; vacío si no se pudo leer. */
  private readonly rubros = signal<ReadonlyMap<number, string>>(new Map());
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
    // El listado trae sólo el id del rubro: el nombre sale de la lista de rubros, y sólo hace falta para la línea de
    // detalle del celular (el escritorio no pide nada de más). Si no llega, la línea no lo muestra.
    if (esCelular()) {
      this.rubrosService.listar().subscribe({
        next: (lista) => this.rubros.set(new Map(lista.map((rubro) => [rubro.id, rubro.nombre]))),
        error: () => undefined,
      });
    }
  }

  /** El rubro de la fila para la línea de detalle del celular; null si no tiene o todavía no se leyó. */
  protected rubroDe(articulo: Articulo): string | null {
    return articulo.rubroId === null ? null : (this.rubros().get(articulo.rubroId) ?? null);
  }

  /**
   * movil-sectores T7 (S1): el botón de cámara del buscador existe sólo en el celular y sólo si el navegador trae el
   * lector nativo compatible y la cámara; sin eso se escribe a mano. Para el Dueño y para el Vendedor.
   */
  protected puedeEscanear(): boolean {
    return esCelular() && this.lectorDisponible();
  }

  /** «Escribir el código a mano» del escáner: lo cierra, completa el buscador con el código leído (si hay) y lo enfoca. */
  protected escribirAMano(codigo: string | null): void {
    // Primero el foco: así el cierre del escáner no se lo lleva de vuelta al botón de cámara.
    this.buscadorMovil()?.nativeElement.focus();
    this.escanerAbierto.set(false);
    if (codigo === null) return;
    this.form.setValue({ q: codigo });
    this.buscar();
  }

  /** «Abrir ficha» del escáner: lo cierra y abre la ficha igual que un toque en la fila. */
  protected abrirFichaEscaneada(articulo: Articulo): void {
    this.escanerAbierto.set(false);
    this.abrirFicha(articulo);
  }

  /** «Nuevo artículo»: lo mismo que el enlace «Nuevo» de la barra, para el botón flotante del celular. */
  protected nuevo(): void {
    void this.router.navigate(['/articulos/nuevo'], { queryParamsHandling: 'preserve' });
  }

  /** Una acción del ⋯ que lleva a otra pantalla: cierra la hoja y va. */
  protected irA(ruta: string): void {
    this.menuAbierto.set(false);
    void this.router.navigate([ruta]);
  }

  /** «Exportar a CSV» del ⋯: cierra la hoja y baja lo mismo que el botón de la barra. */
  protected exportarDesdeMenu(): void {
    if (this.exportando()) return;
    this.menuAbierto.set(false);
    this.exportar();
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

  /**
   * movil-sectores T5 (5c), «Tocá la fila»: en el celular un toque en la fila abre su ficha, que ahora lleva las
   * acciones del artículo. En el escritorio un clic sólo elige la fila (el doble clic y Enter abren la ficha).
   */
  protected abrirFichaEnElCelular(articulo: Articulo): void {
    if (esCelular()) this.abrirFicha(articulo);
  }

  /** La selección sólo existe para quien tiene Etiquetas (el Dueño) y en la pantalla del celular. */
  protected puedeSeleccionar(): boolean {
    return this.auth.esDueno() && esCelular();
  }

  /** Un toque en la fila: en el modo selección suma o saca; fuera de él, lo de siempre. */
  protected alTocar(articulo: Articulo): void {
    if (this.pulsacionLarga) {
      this.pulsacionLarga = false;
      return;
    }
    if (this.seleccionando()) {
      this.alternar(articulo);
      return;
    }
    this.filas.elegir(articulo);
    this.abrirFichaEnElCelular(articulo);
  }

  protected alDobleToque(articulo: Articulo): void {
    if (!this.seleccionando()) this.abrirFicha(articulo);
  }

  /** Arranca la cuenta de la pulsación larga; un artículo dado de baja no se puede agregar, así que no cuenta. */
  protected alPulsar(evento: PointerEvent, articulo: Articulo): void {
    this.cancelarPulsacion();
    this.pulsacionLarga = false;
    if (!evento.isPrimary || this.seleccionando() || !articulo.activo || !this.puedeSeleccionar()) return;
    this.pulsacion = {
      x: evento.clientX,
      y: evento.clientY,
      temporizador: setTimeout(() => {
        this.pulsacion = null;
        this.pulsacionLarga = true;
        this.entrarASeleccion(articulo);
      }, PULSACION_LARGA_MS),
    };
  }

  /** Si el dedo se mueve es un scroll: la pulsación se cancela. */
  protected alMoverElDedo(evento: PointerEvent): void {
    const inicio = this.pulsacion;
    if (!inicio) return;
    if (Math.hypot(evento.clientX - inicio.x, evento.clientY - inicio.y) > MOVIMIENTO_MAXIMO_PX) {
      this.cancelarPulsacion();
    }
  }

  protected cancelarPulsacion(): void {
    if (this.pulsacion) clearTimeout(this.pulsacion.temporizador);
    this.pulsacion = null;
  }

  /** El menú del navegador al mantener apretado (copiar, compartir) taparía el modo selección. */
  protected alMenuContextual(evento: Event): void {
    if (this.puedeSeleccionar()) evento.preventDefault();
  }

  protected salirDeSeleccion(): void {
    this.cancelarPulsacion();
    this.seleccionando.set(false);
    this.elegidos.set(new Set());
  }

  protected elegirTodos(): void {
    const activos = (this.pagina()?.items ?? []).filter((a) => a.activo).map((a) => a.id);
    this.elegidos.set(new Set(activos));
  }

  /** «Agregar N a Etiquetas»: suma a la lista guardada de Etiquetas (una copia cada uno) y vuelve a la lista. */
  protected agregarAEtiquetas(): void {
    if (this.elegidos().size === 0) return;
    const clave = claveDeEtiquetas(this.auth.sesion());
    if (clave === null) {
      this.error.set('No se pudo guardar la lista de etiquetas.');
      return;
    }
    const ids = (this.pagina()?.items ?? []).filter((a) => this.elegidos().has(a.id)).map((a) => a.id);
    const total = sumarEtiquetas(clave, ids);
    this.salirDeSeleccion();
    this.limpiarMensajes();
    this.aviso.set(
      ids.length === 1
        ? 'Se agregó 1 artículo a la lista de etiquetas.'
        : `Se agregaron ${ids.length} artículos a la lista de etiquetas.`,
    );
    this.enListaDeEtiquetas.set(total);
  }

  private entrarASeleccion(articulo: Articulo): void {
    this.limpiarMensajes();
    this.seleccionando.set(true);
    this.elegidos.set(new Set([articulo.id]));
    this.pistaVista.set(false);
    try {
      localStorage.setItem(CLAVE_PISTA_SELECCION, '1');
    } catch {
      /* sin persistencia: la pista vuelve en la próxima visita */
    }
  }

  private alternar(articulo: Articulo): void {
    if (!articulo.activo) return;
    this.elegidos.update((actuales) => {
      const siguientes = new Set(actuales);
      if (!siguientes.delete(articulo.id)) siguientes.add(articulo.id);
      return siguientes;
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
    this.enListaDeEtiquetas.set(null);
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
    this.salirDeSeleccion();
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
