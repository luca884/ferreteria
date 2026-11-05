import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { ArticuloMostrador } from '../mostrador/mostrador.models';
import { MostradorService } from '../mostrador/mostrador.service';
import { Barra, CodigoDeBarras, barras, codigoParaEtiqueta } from './codigo-barras';

const MAX_COPIAS = 999;

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
 * lista con cuántas copias quiere y los imprime en hoja A4 de 4 o 5 columnas. La lista no se guarda. Al imprimir solo
 * sale `.etiquetas-imprimible` (reglas `@media print` en styles.css).
 */
@Component({
  selector: 'app-etiquetas',
  imports: [BarraAcciones],
  templateUrl: './etiquetas.html',
  styleUrl: './etiquetas.css',
})
export class Etiquetas {
  private readonly mostrador = inject(MostradorService);
  protected readonly auth = inject(AuthService);

  protected readonly zonaLibre = ZONA_LIBRE;
  protected readonly busqueda = signal('');
  /** Lo que se buscó la última vez, para el «no se encontraron». */
  protected readonly buscado = signal<string | null>(null);
  protected readonly resultados = signal<ArticuloMostrador[]>([]);
  protected readonly buscando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly items = signal<ItemEtiqueta[]>([]);
  protected readonly columnas = signal<4 | 5>(4);
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

  protected buscar(evento: Event): void {
    evento.preventDefault();
    const q = this.busqueda().trim();
    if (!q || this.buscando()) return;
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
    this.items.update((lista) =>
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
    this.items.update((lista) => lista.map((i) => (i.articulo.id === id ? { ...i, copias } : i)));
  }

  protected quitar(id: number): void {
    this.items.update((lista) => lista.filter((i) => i.articulo.id !== id));
  }

  protected cambiarColumnas(valor: string): void {
    this.columnas.set(valor === '5' ? 5 : 4);
  }

  protected imprimir(): void {
    window.print();
  }
}
