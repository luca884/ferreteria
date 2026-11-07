import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  ElementRef,
  OnInit,
  afterRenderEffect,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { catchError, forkJoin, map, of, throwError } from 'rxjs';
import { mensajeDe } from '../../core/api/api-error';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { porcentajeDescuentoGeneral } from '../../shared/descuento-general';
import { DialogoDetalle } from '../../shared/dialogo-detalle';
import { DocumentoNoFiscalImprimible } from '../../shared/documento-no-fiscal/documento-no-fiscal-imprimible';
import { EmisorService } from '../../shared/documento-no-fiscal/emisor.service';
import { DatosFiscales } from '../datos-fiscales/datos-fiscales.service';
import { ArticulosService } from '../articulos/articulos.service';
import { fechaHoraLocal } from '../caja/caja.models';
import { ClientesService } from '../clientes/clientes.service';
import { ArticuloMostrador, comoArticuloMostrador } from '../mostrador/mostrador.models';
import { ticketDeRemito } from '../mostrador/ticket';
import { guardarTicket, ticketGuardado } from '../mostrador/ticket.storage';
import { GENERAR_CLAVE } from '../mostrador/ticket.store';
import {
  ESTADOS_REMITO,
  ETIQUETA_ESTADO_REMITO,
  EstadoRemito,
  FiltroRemitos,
  RemitoResponse,
  RemitoResumen,
  documentoDeRemito,
} from './remito.models';
import { RemitosService } from './remitos.service';

const MENSAJE_LISTA = 'No se pudieron cargar los remitos.';
const MENSAJE_DETALLE = 'No se pudo cargar el remito.';
/** Un remito que no existe o es de otra ferretería: el servidor da 404 en los dos casos (regla 3 de CLAUDE.md). */
const MENSAJE_NO_ENCONTRADO = 'No se encontró el remito.';
const MENSAJE_ANULAR = 'No se pudo anular el remito. Probá de nuevo.';
const MENSAJE_PRECIOS = 'No se pudieron leer los precios de hoy. Probá de nuevo.';
const MENSAJE_EMISOR = 'No se pudieron leer los datos de la ferretería. Probá de nuevo.';
const MENSAJE_NUMERO = 'El número tiene que ser un entero.';

/**
 * Remitos (G55): la lista con sus filtros, el detalle de uno y lo que se hace con él: pasarlo a venta o, el Dueño, anularlo.
 * Pasar a venta deja en el mostrador un ticket con las cantidades y los descuentos del remito y los precios de HOY
 * (RM-008); el remito recién queda facturado cuando se cobra ese ticket. Anular devuelve el stock que salió (RM-005).
 */
@Component({
  selector: 'app-remitos',
  imports: [BarraAcciones, DecimalPipe, DialogoDetalle, DocumentoNoFiscalImprimible, MontoPipe],
  templateUrl: './remitos.html',
  styleUrl: './remitos.css',
})
export class Remitos implements OnInit {
  private readonly api = inject(RemitosService);
  private readonly articulos = inject(ArticulosService);
  private readonly clientes = inject(ClientesService);
  private readonly auth = inject(AuthService);
  private readonly emisorApi = inject(EmisorService);
  private readonly router = inject(Router);
  private readonly generarClave = inject(GENERAR_CLAVE);

  protected readonly estados = ESTADOS_REMITO;
  protected readonly etiqueta = ETIQUETA_ESTADO_REMITO;
  protected readonly fechaHora = fechaHoraLocal;
  protected readonly porcentajeGeneral = porcentajeDescuentoGeneral;
  protected readonly esDueno = () => this.auth.esDueno();

  protected readonly pagina = signal<PageResponse<RemitoResumen> | null>(null);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  /** Lo que escribió el usuario en los filtros (todavía sin aplicar). */
  protected readonly estado = signal<EstadoRemito | ''>('');
  protected readonly numero = signal('');
  protected readonly desde = signal('');
  protected readonly hasta = signal('');
  protected readonly errorFiltros = signal<string | null>(null);
  /** Los filtros aplicados a la lista que se ve (sin `page`). */
  private aplicados: Omit<FiltroRemitos, 'page'> = {};
  protected readonly filtrada = signal(false);

  protected readonly elegido = signal<number | null>(null);
  protected readonly detalle = signal<RemitoResponse | null>(null);
  protected readonly errorAccion = signal<string | null>(null);
  protected readonly anulando = signal(false);
  protected readonly pasando = signal(false);
  /** Los datos del emisor con la hoja abierta (el remito se imprime); null con la hoja cerrada. */
  protected readonly emisor = signal<DatosFiscales | null>(null);
  protected readonly leyendoEmisor = signal(false);
  /** Hay que confirmar la anulación del remito abierto. */
  protected readonly confirmandoAnulacion = signal(false);
  /** Cuántos renglones tiene el ticket del mostrador que se reemplazaría; null si no hay nada por confirmar. */
  protected readonly reemplazo = signal<number | null>(null);

  /** El título del pop-up: de la fila mientras el detalle carga, para que se sepa qué remito se abrió. */
  protected readonly tituloDetalle = computed(() => {
    const id = this.elegido();
    const numero = this.detalle()?.numero ?? this.pagina()?.items.find((r) => r.remitoId === id)?.numero;
    return numero !== undefined ? `Remito N.º ${numero}` : 'Detalle del remito';
  });
  /** El remito cuyas acciones van al pie: ninguno mientras carga o mientras se pregunta algo (anular, reemplazar). */
  protected readonly enPie = computed(() =>
    this.confirmandoAnulacion() || this.reemplazo() !== null ? null : this.detalle(),
  );
  /** Anular y pasar a venta, solo un remito emitido; anular, solo el Dueño. */
  protected readonly puedePasar = computed(() => {
    const r = this.enPie();
    return r !== null && this.sePuedePasar(r);
  });
  protected readonly puedeAnular = computed(() => this.puedePasar() && this.esDueno());

  /** El «Cancelar» de la pregunta abierta (anular o reemplazar): toma el foco al aparecer, así queda a la vista. */
  private readonly cancelarPregunta = viewChild<ElementRef<HTMLButtonElement>>('cancelarPregunta');

  constructor() {
    afterRenderEffect(() => this.cancelarPregunta()?.nativeElement.focus());
  }

  ngOnInit(): void {
    this.cargar(0);
  }

  protected cargar(numeroDePagina: number): void {
    this.cargando.set(true);
    this.error.set(null);
    this.api.listar({ page: numeroDePagina, ...this.aplicados }).subscribe({
      next: (pagina) => {
        this.pagina.set(pagina);
        this.cargando.set(false);
        // Un detalle abierto de un remito que la lista nueva ya no muestra confunde: se cierra (no a mitad de una acción).
        const elegido = this.elegido();
        const ocupado = this.anulando() || this.pasando();
        if (elegido !== null && !ocupado && !pagina.items.some((x) => x.remitoId === elegido)) this.cerrarDetalle();
      },
      error: () => {
        this.cargando.set(false);
        this.error.set(MENSAJE_LISTA);
      },
    });
  }

  /** Aplica los filtros y vuelve a la primera página. Un número que no es un entero positivo no se manda. */
  protected buscar(evento: Event): void {
    evento.preventDefault();
    const texto = this.numero().trim();
    if (texto !== '' && !/^[1-9]\d{0,17}$/.test(texto)) {
      this.errorFiltros.set(MENSAJE_NUMERO);
      return;
    }
    this.errorFiltros.set(null);
    this.aplicados = {
      ...(this.estado() ? { estado: this.estado() as EstadoRemito } : {}),
      ...(texto ? { numero: Number(texto) } : {}),
      ...(this.desde() ? { desde: this.desde() } : {}),
      ...(this.hasta() ? { hasta: this.hasta() } : {}),
    };
    this.filtrada.set(Object.keys(this.aplicados).length > 0);
    this.cargar(0);
  }

  protected limpiar(): void {
    this.estado.set('');
    this.numero.set('');
    this.desde.set('');
    this.hasta.set('');
    this.errorFiltros.set(null);
    this.aplicados = {};
    this.filtrada.set(false);
    this.cargar(0);
  }

  protected cerrarDetalle(): void {
    this.emisor.set(null);
    this.elegido.set(null);
    this.detalle.set(null);
    this.errorAccion.set(null);
    this.reemplazo.set(null);
    this.confirmandoAnulacion.set(false);
  }

  protected elegir(fila: RemitoResumen): void {
    this.elegido.set(fila.remitoId);
    this.abrir(fila.remitoId);
  }

  private abrir(id: number): void {
    this.emisor.set(null);
    this.detalle.set(null);
    this.errorAccion.set(null);
    this.reemplazo.set(null);
    this.confirmandoAnulacion.set(false);
    this.api.obtener(id).subscribe({
      next: (remito) => {
        // Si mientras tanto se eligió otro, esta respuesta ya no importa.
        if (this.elegido() === id) this.detalle.set(remito);
      },
      error: (err: unknown) => {
        if (this.elegido() !== id) return;
        const noEsta = err instanceof HttpErrorResponse && err.status === 404;
        this.errorAccion.set(noEsta ? MENSAJE_NO_ENCONTRADO : MENSAJE_DETALLE);
      },
    });
  }

  /**
   * Imprimir (cu-07-remito-presupuesto-imprimible): lee los datos actuales del emisor y abre la hoja. Se leen en cada
   * impresión: el remito no los congela.
   */
  protected imprimir(): void {
    if (!this.detalle() || this.leyendoEmisor()) return;
    this.errorAccion.set(null);
    this.leyendoEmisor.set(true);
    this.emisorApi.obtener().subscribe({
      next: (datos) => {
        this.leyendoEmisor.set(false);
        this.emisor.set(datos);
      },
      error: () => {
        this.leyendoEmisor.set(false);
        this.errorAccion.set(MENSAJE_EMISOR);
      },
    });
  }

  protected cerrarImpresion(): void {
    this.emisor.set(null);
  }

  protected readonly documentoDe = documentoDeRemito;

  protected pedirAnulacion(): void {
    if (!this.detalle() || this.anulando() || this.pasando()) return;
    this.errorAccion.set(null);
    this.reemplazo.set(null);
    this.confirmandoAnulacion.set(true);
  }

  protected noAnular(): void {
    this.confirmandoAnulacion.set(false);
  }

  protected anular(): void {
    const actual = this.detalle();
    if (!actual || this.anulando()) return;
    this.anulando.set(true);
    this.errorAccion.set(null);
    this.api.anular(actual.remitoId).subscribe({
      next: (remito) => {
        this.anulando.set(false);
        this.confirmandoAnulacion.set(false);
        this.detalle.set(remito);
        this.actualizarFila(remito);
      },
      error: (err: unknown) => {
        this.anulando.set(false);
        this.confirmandoAnulacion.set(false);
        this.errorAccion.set(mensajeDe(err, MENSAJE_ANULAR));
        // Si ya se facturó o cambió, se vuelve a leer para mostrar cómo está de verdad.
        this.api.obtener(actual.remitoId).subscribe({
          next: (remito) => {
            if (this.elegido() === actual.remitoId) this.detalle.set(remito);
            this.actualizarFila(remito);
          },
          error: () => undefined,
        });
      },
    });
  }

  /** La fila del listado sigue al detalle sin volver a pedir toda la página. */
  private actualizarFila(r: RemitoResponse): void {
    this.pagina.update((pagina) =>
      pagina
        ? { ...pagina, items: pagina.items.map((f) => (f.remitoId === r.remitoId ? { ...f, estado: r.estado } : f)) }
        : pagina,
    );
  }

  /** Solo se factura un remito emitido: uno anulado o ya facturado no. */
  protected sePuedePasar(r: RemitoResponse): boolean {
    return r.estado === 'EMITIDO';
  }

  /** Pasar a venta: si el mostrador ya tiene un ticket con renglones, primero se pregunta si se reemplaza. */
  protected pasarAVenta(): void {
    const remito = this.detalle();
    if (!remito || this.pasando() || this.anulando()) return;
    this.errorAccion.set(null);
    this.confirmandoAnulacion.set(false);
    const enCurso = ticketGuardado(this.auth);
    if (enCurso && enCurso.renglones.length > 0) {
      this.reemplazo.set(enCurso.renglones.length);
      return;
    }
    this.armarTicket(remito);
  }

  protected reemplazar(): void {
    const remito = this.detalle();
    this.reemplazo.set(null);
    if (remito) this.armarTicket(remito);
  }

  protected noReemplazar(): void {
    this.reemplazo.set(null);
  }

  /** Esc en una pregunta (anular o reemplazar) la cancela a ella sola: el pop-up del detalle sigue abierto. */
  protected cancelarConEsc(evento: Event): void {
    evento.preventDefault();
    evento.stopPropagation();
    if (this.anulando()) return;
    this.noAnular();
    this.noReemplazar();
  }

  /**
   * Lee el precio de hoy de cada artículo (uno solo por artículo), arma el ticket y abre el mostrador. Un artículo que ya
   * no existe (404) queda marcado en el ticket; cualquier otro error corta todo sin tocar el ticket del mostrador.
   */
  private armarTicket(remito: RemitoResponse): void {
    const ids = [...new Set(remito.renglones.map((r) => r.articuloId))];
    this.pasando.set(true);
    // La condición de IVA del cliente de hoy: si no se puede leer, el ticket se arma igual y el mostrador lo reintenta.
    const condicion$ = remito.clienteId
      ? this.clientes.obtener(remito.clienteId).pipe(
          map((cliente) => cliente.condicionIva),
          catchError(() => of(undefined)),
        )
      : of(undefined);
    forkJoin([
      forkJoin(
        ids.map((id) =>
          this.articulos.obtener(id).pipe(
            map((articulo): [number, ArticuloMostrador | null] => [id, comoArticuloMostrador(articulo)]),
            catchError((err: unknown) =>
              err instanceof HttpErrorResponse && err.status === 404
                ? of<[number, ArticuloMostrador | null]>([id, null])
                : throwError(() => err),
            ),
          ),
        ),
      ),
      condicion$,
    ]).subscribe({
      next: ([leidos, condicion]) => {
        guardarTicket(this.auth, ticketDeRemito(remito, new Map(leidos), this.generarClave(), condicion));
        this.pasando.set(false);
        void this.router.navigateByUrl('/mostrador');
      },
      error: () => {
        this.pasando.set(false);
        this.errorAccion.set(MENSAJE_PRECIOS);
      },
    });
  }
}
