import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, Subject, catchError, forkJoin, map, of, switchMap, throwError, timer } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { MotivoDeAccion, Motivos, ariaDe } from '../../shared/motivo-de-accion';
import { ArticulosService } from '../articulos/articulos.service';
import { ArticuloMostrador, comoArticuloMostrador } from '../mostrador/mostrador.models';
import { MostradorService } from '../mostrador/mostrador.service';
import { Barra, CodigoDeBarras, barras, codigoParaEtiqueta } from './codigo-barras';
import { claveDeEtiquetas, guardarEtiquetas, leerEtiquetas } from './etiquetas.storage';

const MAX_COPIAS = 999;

/** S5: lo que se explica al intentar una acción de la barra que no se puede usar. */
const MOTIVO_SIN_ARTICULOS = 'No hay artículos en la lista de etiquetas.';
const MOTIVO_SIN_ETIQUETAS = 'Agregá al menos un artículo para imprimir las etiquetas.';
/** S5: lo que se explica al intentar sumar un resultado que está dado de baja. */
const MOTIVO_DADO_DE_BAJA = 'Ese artículo está dado de baja. Reactivalo para agregarlo.';

const ENTERO = new Intl.NumberFormat('es-AR');

/** Como el mostrador: se busca solo cuando se deja de escribir este tiempo. */
const ESPERA_MS = 250;

/** Margen en blanco a cada lado de las barras, en módulos (el lector lo necesita para encontrar el código). */
const ZONA_LIBRE = 10;

interface ItemEtiqueta {
  articulo: ArticuloMostrador;
  copias: number;
  codigo: CodigoDeBarras | null;
}

interface Etiqueta {
  /** Clave única de la etiqueta en la hoja: id del artículo y número de copia. */
  clave: string;
  descripcion: string;
  texto: string;
  ancho: number;
  barras: Barra[] | null;
}

/**
 * Etiquetas con código de barras (GDS-31), como las plantillas de GDS (G85): el Dueño busca artículos, los suma a la
 * lista con cuántas copias quiere y los imprime en hoja A4 de 4 o 5 columnas. La lista queda guardada para ese usuario
 * (`etiquetas.storage.ts`) hasta «Vaciar» o el cierre de la sesión; al volver se traen los datos actuales de cada
 * artículo. Al imprimir solo sale `.etiquetas-imprimible` (reglas `@media print` en styles.css).
 */
@Component({
  selector: 'app-etiquetas',
  imports: [BarraAcciones, Confirmacion],
  templateUrl: './etiquetas.html',
  styleUrl: './etiquetas.css',
})
export class Etiquetas {
  private readonly mostrador = inject(MostradorService);
  private readonly articulos = inject(ArticulosService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly auth = inject(AuthService);
  private readonly clave = claveDeEtiquetas(this.auth.sesion());

  protected readonly zonaLibre = ZONA_LIBRE;
  protected readonly busqueda = signal('');
  /** Lo que se buscó la última vez, para el «no se encontraron». */
  protected readonly buscado = signal<string | null>(null);
  protected readonly resultados = signal<ArticuloMostrador[]>([]);
  protected readonly buscando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly items = signal<ItemEtiqueta[]>([]);
  /** El resultado que suma el Enter (↑↓ lo mueven). */
  protected readonly resaltado = signal(0);
  /** Lo que se escribe; null cancela la búsqueda que estaba por salir. */
  private readonly sugerencias = new Subject<string | null>();
  protected readonly columnas = signal<4 | 5>(4);
  protected readonly cargando = signal(false);
  protected readonly errorDeCarga = signal(false);
  /** Lo que salió de la lista guardada al traerla (dados de baja o que ya no existen). */
  protected readonly avisoLista = signal<string | null>(null);
  /** «Vaciar» pregunta antes (`app-confirmacion`), como «Vaciar» del Mostrador. */
  protected readonly confirmandoVaciar = signal(false);
  /** El resultado cuyo «Agregar» se intentó sin poder: el motivo se va cuando ese artículo deja de estar de baja (S5). */
  private readonly sinAgregar = signal<ArticuloMostrador | null>(null);
  /**
   * Por qué cada acción no se puede usar: null en la que sí se puede (S5). Es la misma tabla que apaga el botón con
   * `aria-disabled` y la que se explica al intentar.
   */
  protected readonly motivos = computed<Motivos<'vaciar' | 'imprimir' | 'agregar'>>(() => ({
    vaciar: this.items().length === 0 ? MOTIVO_SIN_ARTICULOS : null,
    imprimir: this.etiquetas().length === 0 ? MOTIVO_SIN_ETIQUETAS : null,
    agregar: this.sinAgregar()?.activo === false ? MOTIVO_DADO_DE_BAJA : null,
  }));
  /** El motivo a la vista de la última acción que no se pudo usar, o null. Se anuncia con role="status" (S5). */
  protected readonly motivo = new MotivoDeAccion(() => this.motivos());
  /** Para la plantilla: 'true' apaga el botón, null lo deja prendido. */
  protected readonly ariaDe = ariaDe;
  /** S5: el motivo de un resultado dado de baja, para el `aria-disabled` de su botón «Agregar». */
  protected readonly motivoDadoDeBaja = MOTIVO_DADO_DE_BAJA;
  /** Mientras la lista guardada no se cargó, lo guardado no se pisa. */
  private guardable = true;
  private readonly campo = viewChild<ElementRef<HTMLInputElement>>('campo');

  protected readonly etiquetas = computed<Etiqueta[]>(() =>
    this.items().flatMap((item) => {
      const etiqueta = {
        descripcion: item.articulo.descripcion,
        texto: item.codigo?.texto ?? item.articulo.codigoInterno,
        ancho: (item.codigo?.modulos.length ?? 0) + 2 * ZONA_LIBRE,
        barras: item.codigo ? barras(item.codigo.modulos) : null,
      };
      return Array.from({ length: item.copias }, (_, i) => ({ ...etiqueta, clave: `${item.articulo.id}-${i}` }));
    }),
  );

  protected readonly total = computed(() => {
    const n = this.etiquetas().length;
    return n === 1 ? '1 etiqueta' : `${n} etiquetas`;
  });

  constructor() {
    if (this.auth.esDueno()) this.cargarGuardada();
    // Como el mostrador (#25): mientras escribís busca solo, la última búsqueda gana; un código exacto no se suma solo,
    // queda resaltado para el Enter.
    this.sugerencias
      .pipe(
        switchMap((q) =>
          q === null
            ? EMPTY
            : timer(ESPERA_MS).pipe(
                switchMap(() =>
                  this.mostrador.buscar(q).pipe(
                    map((respuesta) => ({ q, respuesta })),
                    // El error se resuelve acá adentro para no cortar las sugerencias: la próxima tecla vuelve a buscar.
                    catchError(() => {
                      this.error.set('No se pudo buscar el artículo.');
                      return EMPTY;
                    }),
                  ),
                ),
              ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(({ q, respuesta }) => {
        this.error.set(null);
        this.resultados.set(respuesta.items);
        this.buscado.set(q);
        this.resaltado.set(0);
      });
  }

  protected alEscribir(texto: string): void {
    this.busqueda.set(texto);
    const q = texto.trim();
    if (q === '') {
      this.resultados.set([]);
      this.buscado.set(null);
    }
    this.sugerencias.next(q === '' ? null : q);
  }

  /** ↑↓ mueven el resaltado dentro de los resultados, sin salirse. */
  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key !== 'ArrowDown' && evento.key !== 'ArrowUp') return;
    const n = this.resultados().length;
    if (n === 0) return;
    evento.preventDefault();
    const paso = evento.key === 'ArrowDown' ? 1 : -1;
    this.resaltado.update((i) => Math.min(Math.max(i + paso, 0), n - 1));
  }

  /**
   * Trae los datos actuales de cada artículo guardado. Uno dado de baja o que ya no existe (404) sale de la lista con un
   * aviso. Si falla otra cosa, lo guardado queda como estaba y se puede reintentar; lo que se sume mientras tanto se
   * junta al final cuando la lista carga.
   */
  protected cargarGuardada(): void {
    if (!this.clave) return;
    const guardadas = leerEtiquetas(this.clave);
    if (guardadas.length === 0) return;
    this.guardable = false;
    this.cargando.set(true);
    this.errorDeCarga.set(false);
    forkJoin(
      guardadas.map((g) =>
        this.articulos.obtener(g.id).pipe(
          map((a) => ({ copias: g.copias, articulo: comoArticuloMostrador(a) as ArticuloMostrador | null })),
          catchError((err: unknown) =>
            err instanceof HttpErrorResponse && err.status === 404
              ? of({ copias: g.copias, articulo: null })
              : throwError(() => err),
          ),
        ),
      ),
    )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (cargados) => {
          const bajas = cargados.flatMap((c) => (c.articulo && !c.articulo.activo ? [c.articulo.descripcion] : []));
          const noExisten = cargados.filter((c) => !c.articulo).length;
          const items = cargados.flatMap((c) =>
            c.articulo?.activo ? [{ articulo: c.articulo, copias: c.copias, codigo: codigoParaEtiqueta(c.articulo) }] : [],
          );
          this.cargando.set(false);
          this.guardable = true;
          this.avisoLista.set(avisoDeLoQueSalio(bajas, noExisten));
          this.cambiarItems((actuales) => [
            ...items,
            ...actuales.filter((a) => !items.some((i) => i.articulo.id === a.articulo.id)),
          ]);
        },
        error: () => {
          this.cargando.set(false);
          this.errorDeCarga.set(true);
        },
      });
  }

  /** Cambia la lista y la guarda (si la guardada ya cargó). */
  private cambiarItems(cambio: (lista: ItemEtiqueta[]) => ItemEtiqueta[]): void {
    this.items.update(cambio);
    if (this.clave && this.guardable) {
      guardarEtiquetas(
        this.clave,
        this.items().map((i) => ({ id: i.articulo.id, copias: i.copias })),
      );
    }
  }

  protected buscar(evento: Event): void {
    evento.preventDefault();
    const q = this.busqueda().trim();
    if (!q || this.buscando()) return;
    // El Enter le gana a la sugerencia que estaba por salir: el lector tipea y manda Enter de una.
    this.sugerencias.next(null);
    const resultados = this.resultados();
    if (q === this.buscado() && resultados.length > 0) {
      this.agregar(resultados[Math.min(this.resaltado(), resultados.length - 1)]);
      return;
    }
    this.buscando.set(true);
    this.error.set(null);
    this.mostrador.buscar(q).subscribe({
      next: (respuesta) => {
        this.buscando.set(false);
        if (respuesta.tipo === 'EXACTO') {
          // Como en el mostrador: un código escaneado se suma directo y el buscador queda listo para el siguiente.
          this.resultados.set([]);
          this.buscado.set(null);
          this.agregar(respuesta.items[0]);
          this.limpiarBuscador();
          return;
        }
        this.resultados.set(respuesta.items);
        this.buscado.set(q);
        this.resaltado.set(0);
        this.seleccionarBuscador();
      },
      error: () => {
        this.buscando.set(false);
        this.error.set('No se pudo buscar el artículo.');
      },
    });
  }

  /** Suma el artículo a la lista; si ya estaba, le suma una copia. Los resultados quedan para sumar otros. */
  protected agregar(articulo: ArticuloMostrador): void {
    if (!articulo.activo) {
      this.error.set(`«${articulo.descripcion}» está dado de baja.`);
      return;
    }
    this.error.set(null);
    this.cambiarItems((lista) =>
      lista.some((i) => i.articulo.id === articulo.id)
        ? lista.map((i) => (i.articulo.id === articulo.id ? { ...i, copias: Math.min(i.copias + 1, MAX_COPIAS) } : i))
        : [...lista, { articulo, copias: 1, codigo: codigoParaEtiqueta(articulo) }],
    );
    this.seleccionarBuscador();
  }

  /**
   * Tras una búsqueda por descripción (o al sumar uno de sus resultados) el texto queda a la vista y seleccionado: lo
   * próximo que se escribe lo reemplaza en vez de pegarse.
   */
  private seleccionarBuscador(): void {
    const campo = this.campo()?.nativeElement;
    if (campo) {
      campo.focus();
      campo.select();
    }
  }

  /** El campo se limpia también a mano: el lector tipea y manda Enter antes de que la vista se actualice. */
  private limpiarBuscador(): void {
    this.busqueda.set('');
    const campo = this.campo()?.nativeElement;
    if (campo) {
      campo.value = '';
      campo.focus();
    }
  }

  /** Copias entre 1 y 999; lo que no es un número de 1 o más vuelve a 1. */
  protected cambiarCopias(id: number, valor: string, input: HTMLInputElement): void {
    const n = Math.trunc(Number(valor));
    const copias = Number.isFinite(n) && n >= 1 ? Math.min(n, MAX_COPIAS) : 1;
    input.value = String(copias);
    this.cambiarItems((lista) => lista.map((i) => (i.articulo.id === id ? { ...i, copias } : i)));
  }

  protected quitar(id: number): void {
    this.cambiarItems((lista) => lista.filter((i) => i.articulo.id !== id));
  }

  protected cambiarColumnas(valor: string): void {
    this.columnas.set(valor === '5' ? 5 : 4);
  }

  /** Lo que se pierde al vaciar, como en el Mostrador. */
  protected mensajeVaciar(): string {
    const n = this.items().length;
    return n === 1 ? 'Se saca 1 artículo.' : `Se sacan ${ENTERO.format(n)} artículos.`;
  }

  /** Saca todo, también lo guardado (aunque la lista guardada no se haya podido cargar). Se llega confirmando. */
  protected vaciar(): void {
    this.confirmandoVaciar.set(false);
    this.guardable = true;
    this.errorDeCarga.set(false);
    this.avisoLista.set(null);
    this.cambiarItems(() => []);
  }

  /** Imprimir no vacía la lista: si la impresora se traba, se vuelve a imprimir. Sin etiquetas explica el motivo (S5). */
  protected imprimir(): void {
    if (this.motivo.mostrar('imprimir')) return;
    this.motivo.limpiar();
    window.print();
  }

  /** «Vaciar» sin artículos explica el motivo en vez de abrir la confirmación (S5). */
  protected pedirVaciar(): void {
    if (this.motivo.mostrar('vaciar')) return;
    this.motivo.limpiar();
    this.confirmandoVaciar.set(true);
  }

  /**
   * «Agregar» de un resultado: si el artículo está dado de baja explica el motivo en vez de quedar apagado (S5). El Enter
   * y el código exacto siguen entrando por {@link agregar}, que avisa con el error de siempre.
   */
  protected pedirAgregar(articulo: ArticuloMostrador): void {
    this.sinAgregar.set(articulo.activo ? null : articulo);
    if (this.motivo.mostrar('agregar')) return;
    this.motivo.limpiar();
    this.agregar(articulo);
  }
}

function avisoDeLoQueSalio(bajas: string[], noExisten: number): string | null {
  const partes: string[] = [];
  if (bajas.length > 0) {
    const nombres = bajas.map((d) => `«${d}»`).join(', ');
    partes.push(
      bajas.length === 1
        ? `Salió de la lista por estar dado de baja: ${nombres}.`
        : `Salieron de la lista por estar dados de baja: ${nombres}.`,
    );
  }
  if (noExisten > 0) {
    partes.push(
      noExisten === 1
        ? 'Salió de la lista 1 artículo que ya no existe.'
        : `Salieron de la lista ${noExisten} artículos que ya no existen.`,
    );
  }
  return partes.length > 0 ? partes.join(' ') : null;
}
