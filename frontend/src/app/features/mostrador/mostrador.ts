import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, Subject, catchError, concatMap, map, switchMap, timer } from 'rxjs';
import { AccionBotonera, Botonera } from './botonera';
import { CajaBusqueda } from './caja-busqueda';
import { DialogoCantidad } from './dialogo-cantidad';
import { ConfirmacionCobro, DialogoCobro } from './dialogo-cobro';
import { DialogoConfirmacion } from './dialogo-confirmacion';
import { FichaArticulo } from './ficha-articulo';
import { LISTA_ID, ListaResultados, filaId } from './lista-resultados';
import { ArticuloMostrador, BusquedaMostrador } from './mostrador.models';
import { accionDeTecla } from './mostrador.atajos';
import { MostradorService } from './mostrador.service';
import { AVISO_ESCANEO_CON_DIALOGO } from './guarda-lector';
import { MontoPipe, formatearMonto } from '../../core/formato/monto';
import { aCentavos } from './centavos';
import { aRequest } from './ticket';
import { TicketLista } from './ticket-lista';
import { TicketStore } from './ticket.store';
import { TotalesTicket } from './totales-ticket';
import { ConfirmarVentaRequest, VentaCobrada, VentaInvalida, VentaRechazada } from './venta.models';
import { VentaService, invalidaDe, rechazoDe } from './venta.service';

const ESPERA_MS = 250;
/** Cuánto queda a la vista el aviso de venta cobrada. */
const AVISO_COBRADA_MS = 5000;
const MENSAJE_ERROR = 'No se pudo buscar. Probá de nuevo.';
const MENSAJE_DADO_DE_BAJA = 'Artículo dado de baja';
const MENSAJE_SIN_RESULTADOS = 'No encontramos ningún artículo con ese código o descripción.';
const MENSAJE_TICKET_VACIO = 'El ticket está vacío.';
const MENSAJE_COBRO_FALLIDO = 'No se pudo cobrar. Probá de nuevo.';
const PROBLEMA_DADO_DE_BAJA = 'Artículo dado de baja';
const PROBLEMA_INEXISTENTE = 'Ya no está disponible';

/** El diálogo abierto, si hay uno. `indice` es el renglón del ticket sobre el que actúa. */
type Dialogo =
  | { tipo: 'cantidad'; indice: number }
  | { tipo: 'quitar'; indice: number }
  | { tipo: 'vaciar' }
  | { tipo: 'cobro' };

/**
 * Aviso del contenedor, aparte del mensaje de la búsqueda. `vacio` y `persistente` se van con el próximo
 * renglón (o con Esc); `otro` se queda hasta que lo reemplace otro aviso o venza su plazo.
 */
interface Aviso {
  texto: string;
  grave: boolean;
  tipo: 'vacio' | 'persistente' | 'otro';
  /** Vuelto de la venta en efectivo, en centavos: se muestra grande al lado del número de venta. */
  vueltoCentavos?: number;
  /** Advertencia aparte (sin stock): en color de aviso, no de éxito. */
  advertencia?: string;
}

/** Un escaneo (Enter): `seq` dice si sigue siendo lo último que hizo el vendedor. */
interface Escaneo {
  q: string;
  seq: number;
}

/** Mostrador: consulta de precio, stock y ubicación, y el ticket de la venta en curso (CU-07). */
@Component({
  selector: 'app-mostrador',
  imports: [
    Botonera,
    MontoPipe,
    CajaBusqueda,
    DialogoCantidad,
    DialogoCobro,
    DialogoConfirmacion,
    ListaResultados,
    FichaArticulo,
    TicketLista,
    TotalesTicket,
  ],
  templateUrl: './mostrador.html',
  styleUrl: './mostrador.css',
  providers: [TicketStore],
  host: {
    '(document:keydown)': 'alTeclear($event)',
    '(window:beforeunload)': 'alSalir($event)',
  },
})
export class Mostrador {
  private readonly service = inject(MostradorService);
  private readonly ventas = inject(VentaService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly ticket = inject(TicketStore);
  private readonly caja = viewChild.required(CajaBusqueda);

  protected readonly texto = signal('');
  protected readonly resultado = signal<BusquedaMostrador | null>(null);
  /** La consulta que originó `resultado`: si el texto sigue igual, Enter abre la fila en vez de buscar. */
  private readonly consultaMostrada = signal('');
  protected readonly seleccionado = signal(0);
  protected readonly ficha = signal<ArticuloMostrador | null>(null);
  protected readonly error = signal(false);

  /** Lo que se le avisa al vendedor cuando no hay nada para mostrar. `grave` va en rojo. */
  protected readonly mensaje = computed(() => {
    if (this.error()) return { texto: MENSAJE_ERROR, grave: true };
    switch (this.resultado()?.tipo) {
      case 'DADO_DE_BAJA':
        return { texto: MENSAJE_DADO_DE_BAJA, grave: true };
      case 'SIN_RESULTADOS':
        return { texto: MENSAJE_SIN_RESULTADOS, grave: false };
      default:
        return null;
    }
  });

  /** La lista visible es la que el campo controla (aria-controls / aria-activedescendant). */
  protected readonly listaVisible = computed(
    () => this.resultado()?.tipo === 'LISTA' && !this.ficha(),
  );
  protected readonly listaId = LISTA_ID;
  protected readonly filaId = filaId;

  /** Texto tipeado: espera 250 ms; un texto nuevo (o `null`) cancela la espera y la llamada en vuelo. */
  private readonly busquedas = new Subject<string | null>();
  /** Escaneos (Enter): se atienden de a uno y en orden, ninguno cancela a otro. */
  private readonly escaneos = new Subject<Escaneo>();
  /** Número del último gesto del vendedor (tipear, Enter, Esc): decide quién puede tocar la pantalla. */
  private seq = 0;
  /** Temporizador que oculta el aviso de venta cobrada; cada aviso nuevo arranca el suyo. */
  private ocultarAviso: ReturnType<typeof setTimeout> | undefined;

  protected readonly dialogo = signal<Dialogo | null>(null);
  protected readonly aviso = signal<Aviso | null>(null);
  /** Hay un POST de venta en curso: el diálogo de cobro ignora Enter y no se cierra. */
  protected readonly enVuelo = signal(false);
  /** Mensaje dentro del diálogo de cobro (precio cambiado, error de red, escaneo descartado). */
  protected readonly avisoCobro = signal<string | null>(null);
  /** Lo recibido en el POST en curso (solo efectivo): el backend no guarda el vuelto, se calcula acá. */
  private recibidoEnVuelo: number | null = null;

  /** Lo que la botonera puede disparar ahora: nada con un diálogo abierto; sin renglones, solo buscar. */
  protected readonly habilitadas = computed(() => {
    const abierto = this.dialogo() !== null;
    const conRenglones = this.ticket.ticket().renglones.length > 0 && !abierto;
    return {
      buscar: !abierto,
      cantidad: conRenglones,
      cobrar: conRenglones,
      quitar: conRenglones,
      vaciar: conRenglones,
    };
  });

  constructor() {
    this.destroyRef.onDestroy(() => clearTimeout(this.ocultarAviso));
    this.busquedas
      .pipe(
        switchMap((q) =>
          q === null
            ? EMPTY
            : timer(ESPERA_MS).pipe(
                switchMap(() =>
                  this.service.buscar(q).pipe(
                    map((resultado) => ({ q, resultado })),
                    // El error se resuelve acá adentro para no matar el stream: la próxima búsqueda anda.
                    catchError((err: unknown) => {
                      this.alFallar(err);
                      return EMPTY;
                    }),
                  ),
                ),
              ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ q, resultado }) => this.mostrar(q, resultado, false));

    this.escaneos
      .pipe(
        concatMap((escaneo) =>
          this.service.buscar(escaneo.q).pipe(
            map((resultado) => ({ escaneo, resultado })),
            catchError((err: unknown) => {
              this.alFallar(err);
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ escaneo, resultado }) => {
        // El escaneo nunca se pierde: se agrega aunque el vendedor ya haya seguido con otra cosa.
        if (resultado.tipo === 'EXACTO') this.agregar(resultado.items[0]);
        // La pantalla, en cambio, es de lo último que hizo.
        if (resultado.tipo !== 'EXACTO' || escaneo.seq === this.seq) {
          this.mostrar(escaneo.q, resultado, true);
        }
      });
  }

  /** Todo cambio del aviso pasa por acá: un aviso nuevo (o ninguno) cancela el plazo del anterior. */
  private avisar(aviso: Aviso | null, ocultarEnMs?: number): void {
    clearTimeout(this.ocultarAviso);
    this.ocultarAviso = undefined;
    this.aviso.set(aviso);
    if (aviso && ocultarEnMs !== undefined) {
      this.ocultarAviso = setTimeout(() => this.avisar(null), ocultarEnMs);
    }
  }

  private agregar(articulo: ArticuloMostrador): void {
    this.ticket.agregar(articulo);
    const tipo = this.aviso()?.tipo;
    if (tipo === 'vacio' || tipo === 'persistente') this.avisar(null);
  }

  private mostrar(q: string, resultado: BusquedaMostrador, inmediata: boolean): void {
    this.error.set(false);
    this.resultado.set(resultado);
    this.consultaMostrada.set(q);
    this.seleccionado.set(0);
    this.ficha.set(resultado.tipo === 'EXACTO' ? resultado.items[0] : null);
    // Tras escanear, el próximo código tiene que reemplazar al anterior, no sumarse.
    if (inmediata) this.caja().seleccionarTodo();
    this.enfocarSinDialogo();
  }

  /** Con un diálogo abierto el buscador no le roba el foco (R-039). */
  private enfocarSinDialogo(): void {
    if (!this.dialogo()) this.caja().enfocar();
  }

  /**
   * Atajos (mapa en `mostrador.atajos.ts`). Todos anulan la acción del navegador. Con un diálogo abierto
   * ninguno actúa salvo Esc, que lo cierra.
   */
  protected alTeclear(evento: KeyboardEvent): void {
    const accion = accionDeTecla(evento.key);
    if (!accion) return;
    evento.preventDefault();
    if (this.dialogo()) {
      if (accion === 'cerrar') this.cerrarDialogo();
      return;
    }
    if (accion === 'cerrar') this.limpiar();
    else this.ejecutar(accion);
  }

  protected alBotonera(accion: AccionBotonera): void {
    if (!this.dialogo()) this.ejecutar(accion);
  }

  private ejecutar(accion: AccionBotonera): void {
    const renglones = this.ticket.ticket().renglones;
    switch (accion) {
      case 'buscar':
        this.caja().enfocar();
        return;
      case 'cobrar':
        if (renglones.length === 0) {
          this.avisar({ texto: MENSAJE_TICKET_VACIO, grave: false, tipo: 'vacio' });
          return;
        }
        this.avisar(null);
        this.dialogo.set({ tipo: 'cobro' });
        return;
      case 'vaciar':
        if (renglones.length === 0) return;
        this.avisar(null);
        this.dialogo.set({ tipo: 'vaciar' });
        return;
      case 'cantidad':
      case 'quitar': {
        // Sin renglón elegido, el último agregado.
        const indice = this.ticket.ticket().elegido ?? renglones.length - 1;
        if (!renglones[indice]) return;
        this.avisar(null);
        this.dialogo.set({ tipo: accion, indice });
        return;
      }
    }
  }

  protected cerrarDialogo(): void {
    // No se cierra el cobro mientras viaja la venta: se perdería el resultado.
    if (this.enVuelo()) return;
    this.dialogo.set(null);
    this.avisoCobro.set(null);
    this.caja().enfocar();
  }

  protected cambiarCantidad(indice: number, milesimas: number): void {
    const error = this.ticket.cambiarCantidad(indice, milesimas);
    if (error) this.avisar({ texto: error, grave: true, tipo: 'otro' });
    this.cerrarDialogo();
  }

  protected quitar(indice: number): void {
    this.ticket.quitar(indice);
    this.cerrarDialogo();
  }

  protected vaciarTicket(): void {
    this.ticket.vaciar();
    this.cerrarDialogo();
  }

  /** Un escaneo llegó con un diálogo abierto: el diálogo lo descartó y acá se avisa. */
  protected escaneoDescartado(): void {
    if (this.dialogo()?.tipo === 'cobro') this.avisoCobro.set(AVISO_ESCANEO_CON_DIALOGO);
    else this.avisar({ texto: AVISO_ESCANEO_CON_DIALOGO, grave: false, tipo: 'otro' });
  }

  /** Enter en el diálogo de cobro: manda la venta con la clave del ticket (la misma en cada reintento). */
  protected cobrar(confirmacion: ConfirmacionCobro): void {
    if (this.enVuelo()) return;
    const pedido = aRequest(this.ticket.ticket(), confirmacion.medioPago);
    this.recibidoEnVuelo = confirmacion.recibidoCentavos;
    this.enVuelo.set(true);
    this.avisoCobro.set(null);
    this.ventas
      .cobrar(pedido)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (cobrada) => this.alCobrar(cobrada, pedido),
        error: (err: unknown) => this.alFallarCobro(err),
      });
  }

  private alCobrar({ venta }: VentaCobrada, pedido: ConfirmarVentaRequest): void {
    this.enVuelo.set(false);
    // Solo sale lo que viajó en el POST: un escaneo que volvió en el medio queda para la próxima venta.
    this.ticket.descontarVendido(pedido);
    const sinStock = venta.sinStock
      .map((s) => `"${s.descripcion}" quedó en ${String(s.stockActual).replace('.', ',')}`)
      .join('; ');
    const vuelto = this.recibidoEnVuelo === null ? 0 : this.recibidoEnVuelo - aCentavos(venta.total);
    this.recibidoEnVuelo = null;
    const aviso: Aviso =
      vuelto > 0
        ? {
            texto: `Venta N.º ${venta.numero} cobrada`,
            grave: false,
            tipo: 'persistente',
            vueltoCentavos: vuelto,
          }
        : {
            texto: `Venta N.º ${venta.numero} cobrada: ${formatearMonto(venta.total)}`,
            grave: false,
            tipo: 'otro',
          };
    if (sinStock) {
      aviso.advertencia = `Sin stock: ${sinStock}.`;
      aviso.tipo = 'persistente';
    }
    // El vuelto y el sin stock se necesitan después del plazo habitual: quedan hasta el próximo renglón.
    this.avisar(aviso, aviso.tipo === 'persistente' ? undefined : AVISO_COBRADA_MS);
    this.cerrarDialogo();
  }

  private alFallarCobro(err: unknown): void {
    this.enVuelo.set(false);
    const rechazo = rechazoDe(err);
    if (!rechazo) {
      const invalida = invalidaDe(err);
      if (invalida) this.alVentaInvalida(invalida);
      else this.avisoCobro.set(MENSAJE_COBRO_FALLIDO);
      return;
    }
    switch (rechazo.motivo) {
      case 'PRECIOS_CAMBIADOS':
        this.ticket.actualizarPrecios(
          rechazo.articulos.flatMap((a) =>
            a.precioActual === undefined
              ? []
              : [{ articuloId: a.articuloId, precioActual: a.precioActual }],
          ),
        );
        this.avisoCobro.set(this.textoPrecios(rechazo));
        return;
      case 'ARTICULO_DADO_DE_BAJA':
        this.ticket.marcarProblemas(
          rechazo.articulos.map((a) => ({ articuloId: a.articuloId, mensaje: PROBLEMA_DADO_DE_BAJA })),
        );
        this.rechazarTicket(rechazo.message);
        return;
      case 'ARTICULO_INEXISTENTE':
        this.ticket.marcarProblemas(
          rechazo.articulos.map((a) => ({ articuloId: a.articuloId, mensaje: PROBLEMA_INEXISTENTE })),
        );
        this.rechazarTicket(this.textoInexistentes(rechazo));
        return;
      case 'CLAVE_CON_OTRO_CONTENIDO':
        this.rechazarTicket(rechazo.message);
        return;
    }
  }

  /**
   * Un 400: reintentar no sirve porque los datos son inválidos. Se muestran los mensajes del servidor; si
   * alguno es de un renglón (`renglones[i].…`) se marca ese renglón para corregirlo o quitarlo.
   */
  private alVentaInvalida(invalida: VentaInvalida): void {
    const entradas = Object.entries(invalida.errors ?? {});
    const texto = entradas.length > 0 ? [...new Set(entradas.map(([, m]) => m))].join(' ') : invalida.message;
    const renglones = this.ticket.ticket().renglones;
    const problemas = entradas.flatMap(([campo, mensaje]) => {
      const indice = /^renglones\[(\d+)\]\./.exec(campo)?.[1];
      const renglon = indice === undefined ? undefined : renglones[Number(indice)];
      return renglon ? [{ articuloId: renglon.articuloId, mensaje }] : [];
    });
    if (problemas.length === 0) {
      this.avisoCobro.set(texto);
      return;
    }
    this.ticket.marcarProblemas(problemas);
    this.rechazarTicket(texto);
  }

  /** El ticket no se vende como está: se cierra el cobro, se conserva el ticket y se explica por qué. */
  private rechazarTicket(texto: string): void {
    this.avisar({ texto, grave: true, tipo: 'otro' });
    this.cerrarDialogo();
  }

  private descripcionDe(articuloId: number): string {
    const r = this.ticket.ticket().renglones.find((x) => x.articuloId === articuloId);
    return r ? r.descripcion : 'Un artículo';
  }

  private textoPrecios(rechazo: VentaRechazada): string {
    const cambios = rechazo.articulos.filter((a) => a.precioActual !== undefined);
    if (cambios.length === 1) {
      const c = cambios[0];
      return `Cambió el precio de "${this.descripcionDe(c.articuloId)}": ahora ${formatearMonto(c.precioActual)}.`;
    }
    if (cambios.length > 1) {
      return `Cambiaron los precios de ${cambios.length} artículos. Revisá el total y confirmá de nuevo.`;
    }
    return rechazo.message;
  }

  /** El 404 solo trae ids: se nombran con la descripción que ya tiene el ticket. */
  private textoInexistentes(rechazo: VentaRechazada): string {
    const nombres = rechazo.articulos.map((a) => `"${this.descripcionDe(a.articuloId)}"`);
    if (nombres.length === 1) return `${nombres[0]} ya no está disponible. Sacalo del ticket para seguir.`;
    if (nombres.length > 1) {
      return `Hay artículos que ya no están disponibles: ${nombres.join(', ')}. Sacalos del ticket para seguir.`;
    }
    return rechazo.message;
  }

  /** Cerrar o recargar la página con un ticket a medias pide confirmación del navegador. */
  protected alSalir(evento: BeforeUnloadEvent): void {
    if (this.ticket.ticket().renglones.length === 0) return;
    evento.preventDefault();
    evento.returnValue = '';
  }

  protected renglonDe(indice: number) {
    return this.ticket.ticket().renglones[indice];
  }

  protected alCambiarTexto(texto: string): void {
    this.texto.set(texto);
    const q = texto.trim();
    if (q === '') this.vaciar();
    this.seq++;
    this.busquedas.next(q === '' ? null : q);
  }

  protected alEnter(): void {
    const q = this.texto().trim();
    if (q === '') return;
    const actual = this.resultado();
    if (actual?.tipo === 'LISTA' && !this.ficha() && q === this.consultaMostrada()) {
      this.elegir(this.seleccionado());
      return;
    }
    // Un escaneo cancela el debounce pendiente: el Enter ya hizo la búsqueda.
    this.seq++;
    this.busquedas.next(null);
    this.escaneos.next({ q, seq: this.seq });
  }

  protected alMover(direccion: 1 | -1): void {
    const actual = this.resultado();
    // Sin lista a la vista, las flechas eligen el renglón del ticket.
    if (actual?.tipo !== 'LISTA' || this.ficha()) {
      this.ticket.moverElegido(direccion);
      return;
    }
    const ultimo = actual.items.length - 1;
    this.seleccionado.update((i) => Math.min(Math.max(i + direccion, 0), ultimo));
  }

  /** Agrega 1 unidad de una fila de la lista al ticket y abre su ficha. */
  protected elegir(indice: number): void {
    const articulo = this.resultado()?.items[indice];
    if (!articulo) return;
    this.agregar(articulo);
    this.ficha.set(articulo);
    this.caja().seleccionarTodo();
    this.caja().enfocar();
  }

  /** Esc: vuelve a la pantalla en blanco, cancelando lo que esté pendiente o en vuelo. */
  private limpiar(): void {
    this.seq++;
    this.busquedas.next(null);
    this.texto.set('');
    if (this.aviso()?.tipo === 'persistente') this.avisar(null);
    this.vaciar();
    this.caja().enfocar();
  }

  private vaciar(): void {
    this.resultado.set(null);
    this.consultaMostrada.set('');
    this.seleccionado.set(0);
    this.ficha.set(null);
    this.error.set(false);
  }

  private alFallar(err: unknown): void {
    this.vaciar();
    // El 401 lo trata el interceptor (manda al login): acá no se avisa nada.
    this.error.set(!(err instanceof HttpErrorResponse && err.status === 401));
    this.enfocarSinDialogo();
  }
}
