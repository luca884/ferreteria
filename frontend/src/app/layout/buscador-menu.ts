import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Params, Router, RouterLink } from '@angular/router';
import { Subject, catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { contiene } from '../core/formato/texto';
import { Cliente } from '../features/clientes/cliente.models';
import { ClientesService } from '../features/clientes/clientes.service';
import { Proveedor } from '../features/proveedores/proveedor.models';
import { ProveedoresService } from '../features/proveedores/proveedores.service';
import { MenuActual } from './menu-actual';
import { MOSTRADOR } from './menu';

/** Cuánto se espera después de la última tecla antes de pedirle los clientes al servidor. */
export const ESPERA_CLIENTES_MS = 250;
const LETRAS_MINIMAS = 2;
const TOPE_POR_BLOQUE = 5;

interface Resultado {
  readonly texto: string;
  readonly ruta: string;
  readonly queryParams?: Params;
}

interface Bloque {
  readonly titulo: string;
  readonly resultados: readonly Resultado[];
}

/**
 * Buscador del menú lateral (mejoras-ferreteros-bloque-2, S3): con 2 letras o más ofrece las opciones del menú que ve el
 * rol, los clientes (al servidor, hasta 5), los proveedores (la lista se pide una vez) y buscar el texto en Artículos.
 * Un cliente o proveedor abre su pantalla buscándolo por nombre (`?q=`). Enter va al primer resultado; Esc vacía.
 */
@Component({
  selector: 'app-buscador-menu',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="caja" role="search">
      <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="7" cy="7" r="4.5" /><path d="M10.5 10.5L14 14" /></svg>
      <input
        type="search"
        placeholder="Buscar…"
        aria-label="Buscar opciones, clientes, proveedores y artículos"
        autocomplete="off"
        [value]="texto()"
        (input)="escribir($any($event.target).value)"
        (keydown)="alTeclear($event)"
      />
    </div>
    @if (activa()) {
      <div class="resultados">
        @for (bloque of bloques(); track bloque.titulo) {
          <h2>{{ bloque.titulo }}</h2>
          @for (resultado of bloque.resultados; track $index) {
            <a [routerLink]="resultado.ruta" [queryParams]="resultado.queryParams" (click)="limpiar()">{{ resultado.texto }}</a>
          }
        }
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
      margin-bottom: 6px;
    }
    .caja {
      position: relative;
    }
    .caja svg {
      position: absolute;
      top: 50%;
      left: 10px;
      width: 15px;
      height: 15px;
      fill: none;
      stroke: var(--texto-tenue);
      stroke-width: 1.6;
      transform: translateY(-50%);
      pointer-events: none;
    }
    input {
      width: 100%;
      min-height: 34px;
      padding-left: 32px;
      font-size: var(--texto-sm);
    }
    .resultados {
      display: flex;
      flex-direction: column;
      gap: 1px;
      margin-top: 6px;
      padding: 4px;
      border: 1px solid var(--borde);
      border-radius: var(--radio);
      background: var(--superficie);
      box-shadow: var(--sombra-1);
    }
    h2 {
      margin: 6px 6px 2px;
      color: var(--texto-tenue);
      font-size: var(--texto-2xs);
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
    }
    a {
      padding: 6px 8px;
      border-radius: var(--radio-sm);
      color: var(--texto-menu);
      font-size: var(--texto-sm);
      text-decoration: none;
      overflow-wrap: anywhere;
    }
    a:hover,
    a:focus-visible {
      background: var(--superficie-3);
      color: var(--texto);
    }
  `,
})
export class BuscadorMenu {
  private readonly menu = inject(MenuActual);
  private readonly router = inject(Router);
  private readonly clientesService = inject(ClientesService);
  private readonly proveedoresService = inject(ProveedoresService);

  protected readonly texto = signal('');
  private readonly consulta = computed(() => this.texto().trim());
  protected readonly activa = computed(() => this.consulta().length >= LETRAS_MINIMAS);

  /** Los clientes que trajo el servidor y para qué texto: solo se muestran si es el que está escrito. */
  private readonly clientes = signal<{ consulta: string; items: readonly Cliente[] }>({ consulta: '', items: [] });
  private readonly proveedores = signal<readonly Proveedor[]>([]);
  private proveedoresPedidos = false;
  private readonly consultas$ = new Subject<string>();

  constructor() {
    this.consultas$
      .pipe(
        debounceTime(ESPERA_CLIENTES_MS),
        distinctUntilChanged(),
        switchMap((consulta) =>
          consulta.length < LETRAS_MINIMAS
            ? of({ consulta, items: [] })
            : this.clientesService.buscar({ q: consulta, size: TOPE_POR_BLOQUE }).pipe(
                map((pagina) => ({ consulta, items: pagina.items })),
                catchError(() => of({ consulta, items: [] })),
              ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((clientes) => this.clientes.set(clientes));
  }

  protected readonly bloques = computed<Bloque[]>(() => {
    const consulta = this.consulta();
    const opciones = [MOSTRADOR, ...this.menu.grupos().flatMap((grupo) => grupo.opciones)]
      .filter((opcion) => contiene(opcion.rotulo, consulta))
      .map((opcion) => ({ texto: opcion.rotulo, ruta: opcion.ruta }));
    const { consulta: deClientes, items } = this.clientes();
    const clientes = deClientes === consulta ? items : [];
    const proveedores = this.proveedores()
      .filter((proveedor) => contiene(proveedor.nombre, consulta))
      .slice(0, TOPE_POR_BLOQUE);
    const bloques: Bloque[] = [
      { titulo: 'Opciones', resultados: opciones },
      {
        titulo: 'Clientes',
        resultados: clientes.map((c) => ({ texto: c.razonSocial, ruta: '/clientes', queryParams: { q: c.razonSocial } })),
      },
      {
        titulo: 'Proveedores',
        resultados: proveedores.map((p) => ({ texto: p.nombre, ruta: '/proveedores', queryParams: { q: p.nombre } })),
      },
      {
        titulo: 'Artículos',
        resultados: [{ texto: `Buscar «${consulta}» en Artículos`, ruta: '/articulos', queryParams: { q: consulta } }],
      },
    ];
    return bloques.filter((bloque) => bloque.resultados.length > 0);
  });

  protected escribir(texto: string): void {
    this.texto.set(texto);
    this.consultas$.next(this.consulta());
    if (this.activa()) this.pedirProveedores();
  }

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key === 'Escape') {
      evento.preventDefault();
      this.limpiar();
    } else if (evento.key === 'Enter' && this.activa()) {
      evento.preventDefault();
      const primero = this.bloques()[0]?.resultados[0];
      if (primero) void this.router.navigate([primero.ruta], { queryParams: primero.queryParams });
      this.limpiar();
    }
  }

  protected limpiar(): void {
    this.texto.set('');
    this.consultas$.next('');
  }

  /** La lista de proveedores es corta y se filtra acá: se pide la primera vez que hace falta. */
  private pedirProveedores(): void {
    if (this.proveedoresPedidos) return;
    this.proveedoresPedidos = true;
    this.proveedoresService.listar().subscribe({
      next: (lista) => this.proveedores.set(lista),
      error: () => (this.proveedoresPedidos = false),
    });
  }
}
