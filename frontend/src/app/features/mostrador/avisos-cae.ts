import { Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, catchError, interval, startWith, switchMap } from 'rxjs';
import { mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { VentaFacturada } from '../../shared/factura/factura.models';
import { VentasService } from '../ventas/ventas.service';
import { INTERVALO_AVISOS_MS } from './avisos-facturacion.models';
import { ProvisoriaAutorizada } from './venta.models';
import { VentaService } from './venta.service';

const MENSAJE_SIN_HOJA = 'No se pudo abrir la factura. Probá de nuevo.';

/**
 * Los avisos descartados de quien vende, en localStorage (`cae-descartados:{ferreteriaId}:{usuarioId}`): un aviso cerrado o
 * reimpreso no vuelve aunque se salga y se vuelva al mostrador mientras la caja siga abierta (S14).
 */
function claveDescartados(auth: AuthService): string | null {
  const s = auth.sesion();
  if (!s || s.ferreteriaId == null || s.userId == null) return null;
  return `cae-descartados:${s.ferreteriaId}:${s.userId}`;
}

function leerDescartados(clave: string | null): ReadonlySet<number> {
  if (!clave) return new Set();
  try {
    const guardado: unknown = JSON.parse(localStorage.getItem(clave) ?? '[]');
    return new Set(Array.isArray(guardado) ? guardado.filter((id): id is number => Number.isSafeInteger(id)) : []);
  } catch {
    return new Set();
  }
}

function escribirDescartados(clave: string | null, ids: ReadonlySet<number>): void {
  if (!clave) return;
  try {
    if (ids.size === 0) localStorage.removeItem(clave);
    else localStorage.setItem(clave, JSON.stringify([...ids]));
  } catch {
    // Sin storage (modo privado lleno): el descarte vale mientras dure la pantalla.
  }
}

/**
 * «Llegó el CAE de la venta N.º <n>» (CU-07 «Reimprimir», C7, S14): las ventas que quien vende cobró en la caja abierta,
 * que salieron con ticket provisorio y ya tienen CAE. Se pregunta al abrir y cada minuto; el mostrador lo pone solo con la
 * caja abierta. Cada aviso ofrece «Reimprimir factura», que pide la hoja fiscal de esa venta y se la da al mostrador para
 * abrirla como la del cobro, y se descarta al reimprimir o al cerrarlo. No lleva a Ventas: el Vendedor no la tiene.
 */
@Component({
  selector: 'app-avisos-cae',
  templateUrl: './avisos-cae.html',
  styleUrl: './avisos-cae.css',
})
export class AvisosCae {
  private readonly ventas = inject(VentaService);
  private readonly consulta = inject(VentasService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly clave = claveDescartados(inject(AuthService));

  /**
   * Se consulta cuando llega la hoja, no al hacer clic: si mientras tanto se abrió un diálogo (un cobro), la factura no lo
   * pisa y el aviso queda. Es una función para leer el estado de ese instante.
   */
  readonly puedeAbrir = input<() => boolean>(() => true);
  /** La venta con su hoja fiscal, lista para abrir la factura. */
  readonly abrir = output<VentaFacturada>();
  /** Se cerró un aviso con su botón: el mostrador devuelve el foco al buscador. */
  readonly cerrado = output<void>();

  /** Lo último que respondió el servidor. */
  private readonly llegadas = signal<readonly ProvisoriaAutorizada[]>([]);
  private readonly descartados = signal<ReadonlySet<number>>(leerDescartados(this.clave));
  protected readonly avisos = computed(() => this.llegadas().filter((l) => !this.descartados().has(l.ventaId)));
  /** La venta cuya hoja se está pidiendo: mientras tanto no se pide otra. */
  protected readonly abriendo = signal<number | null>(null);
  protected readonly error = signal<{ ventaId: number; texto: string } | null>(null);

  constructor() {
    interval(INTERVALO_AVISOS_MS)
      .pipe(
        startWith(0),
        // Una lectura nueva deja sin efecto a la anterior que siga en camino; un error no corta las siguientes.
        switchMap(() => this.ventas.provisoriasAutorizadas().pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(),
      )
      .subscribe((llegadas) => {
        this.llegadas.set(llegadas);
        // Lo descartado de una venta que el servidor ya no manda (otra caja) no hace falta recordarlo.
        const vigentes = new Set(llegadas.map((l) => l.ventaId));
        this.guardar(new Set([...this.descartados()].filter((id) => vigentes.has(id))));
      });
  }

  protected reimprimir(llegada: ProvisoriaAutorizada): void {
    if (this.abriendo() !== null) return;
    const ventaId = llegada.ventaId;
    this.abriendo.set(ventaId);
    this.error.set(null);
    this.consulta
      .factura(ventaId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (venta) => {
          this.abriendo.set(null);
          const fiscal = venta.fiscal;
          if (!fiscal?.documento) {
            this.error.set({ ventaId, texto: MENSAJE_SIN_HOJA });
            return;
          }
          if (!this.puedeAbrir()()) return;
          this.abrir.emit({ ...venta, fiscal });
          this.descartar(ventaId);
        },
        error: (err: unknown) => {
          this.abriendo.set(null);
          this.error.set({ ventaId, texto: mensajeDe(err, MENSAJE_SIN_HOJA) });
        },
      });
  }

  protected cerrar(llegada: ProvisoriaAutorizada): void {
    this.descartar(llegada.ventaId);
    this.cerrado.emit();
  }

  private descartar(ventaId: number): void {
    if (this.error()?.ventaId === ventaId) this.error.set(null);
    this.guardar(new Set([...this.descartados(), ventaId]));
  }

  private guardar(ids: ReadonlySet<number>): void {
    this.descartados.set(ids);
    escribirDescartados(this.clave, ids);
  }
}
