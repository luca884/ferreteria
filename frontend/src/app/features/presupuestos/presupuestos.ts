import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, forkJoin, map, of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { mensajeDe } from '../../core/api/api-error';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { porcentajeDescuentoGeneral } from '../../shared/descuento-general';
import { DocumentoNoFiscalImprimible } from '../../shared/documento-no-fiscal/documento-no-fiscal-imprimible';
import { EmisorService } from '../../shared/documento-no-fiscal/emisor.service';
import { DatosFiscales } from '../datos-fiscales/datos-fiscales.service';
import { ArticulosService } from '../articulos/articulos.service';
import { fechaHoraLocal } from '../caja/caja.models';
import { ClientesService } from '../clientes/clientes.service';
import { ArticuloMostrador } from '../mostrador/mostrador.models';
import { ticketDePresupuesto } from '../mostrador/ticket';
import { guardarTicket, ticketGuardado } from '../mostrador/ticket.storage';
import { GENERAR_CLAVE } from '../mostrador/ticket.store';
import {
  ESTADOS_PRESUPUESTO,
  ETIQUETA_ESTADO,
  EstadoManual,
  EstadoPresupuesto,
  FiltroPresupuestos,
  PresupuestoResponse,
  PresupuestoResumen,
  documentoDePresupuesto,
  fechaDia,
} from './presupuesto.models';
import { PresupuestosService } from './presupuestos.service';

const MENSAJE_LISTA = 'No se pudieron cargar los presupuestos.';
const MENSAJE_DETALLE = 'No se pudo cargar el presupuesto.';
const MENSAJE_ESTADO = 'No se pudo cambiar el estado. Probá de nuevo.';
const MENSAJE_PRECIOS = 'No se pudieron leer los precios de hoy. Probá de nuevo.';
const MENSAJE_EMISOR = 'No se pudieron leer los datos de la ferretería. Probá de nuevo.';
const MENSAJE_NUMERO = 'El número tiene que ser un entero.';

/**
 * Presupuestos (GDS-13): la lista con sus filtros, el detalle de uno y lo que se hace con él: aceptarlo, rechazarlo o
 * pasarlo a venta. Pasar a venta deja en el mostrador un ticket con las cantidades y los descuentos del presupuesto y los
 * precios de HOY (PP-008); el presupuesto recién queda facturado cuando se cobra ese ticket.
 */
@Component({
  selector: 'app-presupuestos',
  imports: [BarraAcciones, DecimalPipe, DocumentoNoFiscalImprimible, MontoPipe],
  templateUrl: './presupuestos.html',
  styleUrl: './presupuestos.css',
})
export class Presupuestos implements OnInit {
  private readonly api = inject(PresupuestosService);
  private readonly articulos = inject(ArticulosService);
  private readonly clientes = inject(ClientesService);
  private readonly auth = inject(AuthService);
  private readonly emisorApi = inject(EmisorService);
  private readonly router = inject(Router);
  private readonly generarClave = inject(GENERAR_CLAVE);

  protected readonly estados = ESTADOS_PRESUPUESTO;
  protected readonly esDueno = this.auth.esDueno;
  protected readonly etiqueta = ETIQUETA_ESTADO;
  protected readonly fechaHora = fechaHoraLocal;
  protected readonly porcentajeGeneral = porcentajeDescuentoGeneral;
  protected readonly dia = fechaDia;

  protected readonly pagina = signal<PageResponse<PresupuestoResumen> | null>(null);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  /** Lo que escribió el usuario en los filtros (todavía sin aplicar). */
  protected readonly estado = signal<EstadoPresupuesto | ''>('');
  protected readonly numero = signal('');
  protected readonly desde = signal('');
  protected readonly hasta = signal('');
  protected readonly errorFiltros = signal<string | null>(null);
  /** Los filtros aplicados a la lista que se ve (sin `page`). */
  private aplicados: Omit<FiltroPresupuestos, 'page'> = {};
  protected readonly filtrada = signal(false);

  protected readonly elegido = signal<number | null>(null);
  protected readonly detalle = signal<PresupuestoResponse | null>(null);
  protected readonly errorAccion = signal<string | null>(null);
  protected readonly cambiando = signal(false);
  protected readonly pasando = signal(false);
  /** Los datos del emisor con la hoja abierta (el presupuesto se imprime); null con la hoja cerrada. */
  protected readonly emisor = signal<DatosFiscales | null>(null);
  protected readonly leyendoEmisor = signal(false);
  /** Cuántos renglones tiene el ticket del mostrador que se reemplazaría; null si no hay nada por confirmar. */
  protected readonly reemplazo = signal<number | null>(null);

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
        // Un detalle abierto de un presupuesto que la lista nueva ya no muestra confunde: se cierra (no a mitad de una acción).
        const elegido = this.elegido();
        const ocupado = this.cambiando() || this.pasando();
        if (elegido !== null && !ocupado && !pagina.items.some((x) => x.presupuestoId === elegido)) this.cerrarDetalle();
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
      ...(this.estado() ? { estado: this.estado() as EstadoPresupuesto } : {}),
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

  private cerrarDetalle(): void {
    this.emisor.set(null);
    this.elegido.set(null);
    this.detalle.set(null);
    this.errorAccion.set(null);
    this.reemplazo.set(null);
  }

  protected elegir(fila: PresupuestoResumen): void {
    this.elegido.set(fila.presupuestoId);
    this.abrir(fila.presupuestoId);
  }

  private abrir(id: number): void {
    this.emisor.set(null);
    this.detalle.set(null);
    this.errorAccion.set(null);
    this.reemplazo.set(null);
    this.api.obtener(id).subscribe({
      next: (presupuesto) => {
        // Si mientras tanto se eligió otro, esta respuesta ya no importa.
        if (this.elegido() === id) this.detalle.set(presupuesto);
      },
      error: () => {
        if (this.elegido() === id) this.errorAccion.set(MENSAJE_DETALLE);
      },
    });
  }

  /**
   * Imprimir (cu-07-remito-presupuesto-imprimible): lee los datos actuales del emisor y abre la hoja. Se leen en cada
   * impresión: el presupuesto no los congela.
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

  protected readonly documentoDe = documentoDePresupuesto;

  protected cambiarEstado(nuevo: EstadoManual): void {
    const actual = this.detalle();
    if (!actual || this.cambiando()) return;
    this.cambiando.set(true);
    this.errorAccion.set(null);
    this.reemplazo.set(null);
    this.api.cambiarEstado(actual.presupuestoId, nuevo).subscribe({
      next: (presupuesto) => {
        this.cambiando.set(false);
        this.detalle.set(presupuesto);
        this.actualizarFila(presupuesto);
      },
      error: (err: unknown) => {
        this.cambiando.set(false);
        this.errorAccion.set(mensajeDe(err, MENSAJE_ESTADO));
        // Si ya se facturó o cambió, se vuelve a leer para mostrar cómo está de verdad.
        this.api.obtener(actual.presupuestoId).subscribe({
          next: (presupuesto) => {
            if (this.elegido() === actual.presupuestoId) this.detalle.set(presupuesto);
            this.actualizarFila(presupuesto);
          },
          error: () => undefined,
        });
      },
    });
  }

  /** La fila del listado sigue al detalle sin volver a pedir toda la página. */
  private actualizarFila(p: PresupuestoResponse): void {
    this.pagina.update((pagina) =>
      pagina
        ? {
            ...pagina,
            items: pagina.items.map((f) =>
              f.presupuestoId === p.presupuestoId ? { ...f, estado: p.estado, vencido: p.vencido } : f,
            ),
          }
        : pagina,
    );
  }

  /** Se puede cobrar un presupuesto pendiente o aceptado, aunque haya vencido (se cobra a los precios de hoy). */
  protected sePuedePasar(p: PresupuestoResponse): boolean {
    return p.estado === 'PENDIENTE' || p.estado === 'ACEPTADO';
  }

  /** Pasar a venta: si el mostrador ya tiene un ticket con renglones, primero se pregunta si se reemplaza. */
  protected pasarAVenta(): void {
    const presupuesto = this.detalle();
    if (!presupuesto || this.pasando() || this.cambiando()) return;
    this.errorAccion.set(null);
    const enCurso = ticketGuardado(this.auth);
    if (enCurso && enCurso.renglones.length > 0) {
      this.reemplazo.set(enCurso.renglones.length);
      return;
    }
    this.armarTicket(presupuesto);
  }

  protected reemplazar(): void {
    const presupuesto = this.detalle();
    this.reemplazo.set(null);
    if (presupuesto) this.armarTicket(presupuesto);
  }

  protected noReemplazar(): void {
    this.reemplazo.set(null);
  }

  /**
   * Lee el precio de hoy de cada artículo (uno solo por artículo), arma el ticket y abre el mostrador. Un artículo que ya
   * no existe (404) queda marcado en el ticket; cualquier otro error corta todo sin tocar el ticket del mostrador.
   */
  private armarTicket(presupuesto: PresupuestoResponse): void {
    const ids = [...new Set(presupuesto.renglones.map((r) => r.articuloId))];
    this.pasando.set(true);
    // La condición de IVA del cliente de hoy: si no se puede leer, el ticket se arma igual y el mostrador lo reintenta.
    const condicion$ = presupuesto.clienteId
      ? this.clientes.obtener(presupuesto.clienteId).pipe(
          map((cliente) => cliente.condicionIva),
          catchError(() => of(undefined)),
        )
      : of(undefined);
    forkJoin([
      forkJoin(
        ids.map((id) =>
          this.articulos.obtener(id).pipe(
            map((articulo): [number, ArticuloMostrador | null] => [id, articulo]),
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
        guardarTicket(this.auth, ticketDePresupuesto(presupuesto, new Map(leidos), this.generarClave(), condicion));
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
