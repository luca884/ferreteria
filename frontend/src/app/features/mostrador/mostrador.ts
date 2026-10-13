import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, Subject, catchError, map, of, switchMap, timer } from 'rxjs';
import { CajaBusqueda } from './caja-busqueda';
import { FichaArticulo } from './ficha-articulo';
import { LISTA_ID, ListaResultados, filaId } from './lista-resultados';
import { ArticuloMostrador, BusquedaMostrador } from './mostrador.models';
import { MostradorService } from './mostrador.service';

const ESPERA_MS = 250;
const MENSAJE_ERROR = 'No se pudo buscar. Probá de nuevo.';
const MENSAJE_DADO_DE_BAJA = 'Artículo dado de baja';
const MENSAJE_SIN_RESULTADOS = 'No encontramos ningún artículo con ese código o descripción.';

/** `inmediata`: Enter (o el lector). Si no, se espera a que deje de tipear. */
interface Pedido {
  q: string;
  inmediata: boolean;
}

/** Mostrador: consulta de precio, stock y ubicación. Solo lectura; CU-07 le suma el ticket. */
@Component({
  selector: 'app-mostrador',
  imports: [CajaBusqueda, ListaResultados, FichaArticulo],
  templateUrl: './mostrador.html',
  styleUrl: './mostrador.css',
  host: { '(document:keydown)': 'alTeclear($event)' },
})
export class Mostrador {
  private readonly service = inject(MostradorService);
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

  private readonly pedidos = new Subject<Pedido>();

  /** Atajos del mostrador: una sola tabla tecla → acción. CU-07 suma F4 y F6 acá. */
  private readonly atajos = new Map<string, () => void>([
    ['F2', () => this.caja().enfocar()],
    ['Escape', () => this.limpiar()],
  ]);

  constructor() {
    // Un pedido nuevo cancela tanto la espera del debounce como la llamada en vuelo.
    this.pedidos
      .pipe(
        switchMap((pedido) =>
          pedido.q === ''
            ? EMPTY
            : (pedido.inmediata ? of(pedido.q) : timer(ESPERA_MS).pipe(map(() => pedido.q))).pipe(
                switchMap((q) =>
                  this.service.buscar(q).pipe(
                    map((resultado) => ({ q, resultado, pedido })),
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
      .subscribe(({ q, resultado, pedido }) => {
        this.error.set(false);
        this.resultado.set(resultado);
        this.consultaMostrada.set(q);
        this.seleccionado.set(0);
        this.ficha.set(resultado.tipo === 'EXACTO' ? resultado.items[0] : null);
        // Tras escanear, el próximo código tiene que reemplazar al anterior, no sumarse.
        if (pedido.inmediata) this.caja().seleccionarTodo();
        this.caja().enfocar();
      });
  }

  protected alTeclear(evento: KeyboardEvent): void {
    const accion = this.atajos.get(evento.key);
    if (!accion) return;
    evento.preventDefault();
    accion();
  }

  protected alCambiarTexto(texto: string): void {
    this.texto.set(texto);
    const q = texto.trim();
    if (q === '') this.vaciar();
    this.pedidos.next({ q, inmediata: false });
  }

  protected alEnter(): void {
    const q = this.texto().trim();
    if (q === '') return;
    const actual = this.resultado();
    if (actual?.tipo === 'LISTA' && !this.ficha() && q === this.consultaMostrada()) {
      this.elegir(this.seleccionado());
      return;
    }
    this.pedidos.next({ q, inmediata: true });
  }

  protected alMover(direccion: 1 | -1): void {
    const actual = this.resultado();
    if (actual?.tipo !== 'LISTA' || this.ficha()) return;
    const ultimo = actual.items.length - 1;
    this.seleccionado.update((i) => Math.min(Math.max(i + direccion, 0), ultimo));
  }

  /** Abre la ficha de una fila de la lista. CU-07 engancha acá el "agregar al ticket". */
  protected elegir(indice: number): void {
    const articulo = this.resultado()?.items[indice];
    if (!articulo) return;
    this.ficha.set(articulo);
    this.caja().seleccionarTodo();
    this.caja().enfocar();
  }

  /** Esc: vuelve a la pantalla en blanco, cancelando lo que esté pendiente o en vuelo. */
  private limpiar(): void {
    this.pedidos.next({ q: '', inmediata: false });
    this.texto.set('');
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
    this.caja().enfocar();
  }
}
