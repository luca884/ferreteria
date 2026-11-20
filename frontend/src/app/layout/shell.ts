import { Component, DestroyRef, ElementRef, afterNextRender, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { NavigationEnd, NavigationSkipped, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { EMPTY, catchError, filter, interval, merge, pairwise, startWith, switchMap } from 'rxjs';
import { ROL_LABELS } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';
import { Aviso, Avisos } from '../core/avisos/avisos';
import { Cotizacion } from '../core/cotizacion/cotizacion.models';
import { CotizacionService } from '../core/cotizacion/cotizacion.service';
import { AvisosALaVista } from '../shared/avisos-a-la-vista';
import { BuscadorMenu } from './buscador-menu';
import { CotizacionLateral } from './cotizacion-lateral';
import { IconoDelMenu } from './icono-menu';
import { HUB_VENTAS, MOSTRADOR, OpcionMenu, Seccion, gruposPara, seccionDe } from './menu';

/** Cada cuánto vuelve a leer la cotización del servidor. Solo lee la base: no llama a ninguna API externa. */
export const INTERVALO_COTIZACION_MS = 5 * 60_000;

/**
 * Los destinos de la barra inferior del celular, en orden (móvil-pulgar, T1): las pantallas del día, siempre a un pulgar
 * de distancia. «Más» no está acá: es el botón que abre el cajón con el resto del menú.
 */
const DESTINOS_AL_PULGAR = ['/mostrador', HUB_VENTAS.ruta, '/articulos'] as const;

/** «Juan Pérez» → «JP», «ana» → «A». */
export function iniciales(nombre: string): string {
  return nombre
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((parte) => parte.charAt(0).toLocaleUpperCase('es-AR'))
    .join('');
}

/**
 * Marco de la app logueada: menú lateral con la ferretería, las secciones y el usuario con su rol, y el
 * contenido. La barra superior la pone cada pantalla con `<app-barra-acciones>`. También es el contenedor de la
 * cotización del menú: la lee al abrir y cada 5 minutos, y se la pasa al bloque presentacional.
 *
 * El menú: «Mostrador» fijo arriba y los grupos de `menu.ts`, que se abren y cierran con un clic; el de la pantalla
 * abierta se abre solo. El shell lleva en `data-seccion` la sección de la pantalla, que le da su tono (bloque 2, S1-S2).
 *
 * Por debajo de 1024 px (S2) el menú pasa a ser un cajón que se abre con el botón «Menú» de un encabezado nuevo, que
 * además muestra el título de la pantalla. El corte y el cajón los resuelve el CSS: acá solo vive el estado abierto/cerrado
 * y el teclado (Esc y el foco, que no se escapa del cajón mientras está abierto). Desde 1024 px nada de esto se ve.
 *
 * En el celular, además, abajo queda la barra de destinos (móvil-pulgar, T1): Mostrador, Ventas y Artículos al pulgar y
 * «Más» para abrir el mismo cajón de siempre. El cajón no se toca: la barra es una forma más de llegar a él.
 *
 * También es donde se engancha el único observer de los avisos (S8): el que trae a la vista el aviso de una acción que
 * aparece dentro del contenido.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, CotizacionLateral, IconoDelMenu, BuscadorMenu],
  templateUrl: './shell.html',
  styleUrl: './shell.css',
  host: { '[attr.data-seccion]': 'seccion()', '(keydown)': 'alTeclear($event)' },
})
export class Shell {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly cotizaciones = inject(CotizacionService);
  protected readonly rolLabels = ROL_LABELS;
  protected readonly mostrador = MOSTRADOR;

  /** Lo último que se leyó; si un pedido falla se conserva, y null hasta la primera respuesta. */
  protected readonly cotizacion = signal<Cotizacion | null>(null);
  private readonly avisos = inject(Avisos);
  /**
   * El aviso que dejó la pantalla anterior y ninguna tomó al abrirse (por ejemplo, «Cambiaste tu contraseña.»: el Dueño
   * y el Vendedor llegan a pantallas distintas). Se toma al terminar cada navegación, así que la siguiente lo saca.
   */
  protected readonly avisoAlLlegar = signal<Aviso | null>(null);

  protected readonly grupos = computed(() => gruposPara(this.auth.esDueno()));
  /**
   * Los destinos de la barra inferior del celular (móvil-pulgar, T1), sacados del mismo menú que se renderiza y
   * filtrados por el rol: si el rol no puede abrir una pantalla, la barra no la ofrece, así no queda un enlace que
   * rebota. «Ventas» es el hub (`HUB_VENTAS`, movil-sectores T2), que tienen los dos roles y lista sólo lo que cada uno
   * puede abrir; el Vendedor no tiene la consulta de ventas, y por eso la barra nunca apunta a `/ventas`. Al super admin no le toca ninguna: el panel no es de una ferretería y la barra solo le
   * deja «Más» para abrir el menú.
   */
  protected readonly destinos = computed<readonly OpcionMenu[]>(() => {
    if (this.auth.esSuperAdmin()) return [];
    const opciones = [MOSTRADOR, HUB_VENTAS, ...this.grupos().flatMap((grupo) => grupo.opciones)];
    return DESTINOS_AL_PULGAR.map((ruta) => opciones.find((opcion) => opcion.ruta === ruta)).filter(
      (opcion): opcion is OpcionMenu => !!opcion,
    );
  });
  private readonly url = signal(this.router.url);
  protected readonly seccion = computed(() => seccionDe(this.url()));
  /** El destino de la barra de la pantalla abierta: el de su sección, así «Ventas» queda marcado en Caja o en Clientes. */
  protected esDestinoActual(destino: OpcionMenu): boolean {
    const seccion = this.seccion();
    return seccion !== null && seccion === seccionDe(destino.ruta);
  }
  private readonly abiertos = signal<ReadonlySet<Seccion>>(new Set());

  /** El cajón del menú (por debajo de 1024 px). Arranca cerrado y el corte lo resuelve el CSS. */
  protected readonly menuAbierto = signal(false);
  /** El título de la pantalla, leído del primer `h1` que ya pone cada una (en T6 queda solo para lectores de pantalla). */
  protected readonly tituloPantalla = signal('');

  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly destruccion = inject(DestroyRef);
  private readonly cajon = viewChild<ElementRef<HTMLElement>>('cajon');
  private readonly botonMenu = viewChild<ElementRef<HTMLButtonElement>>('botonMenu');
  /** El `main.contenido`: lo que scrollea y el contenedor de los avisos de todas las pantallas (S8). */
  private readonly contenido = viewChild<ElementRef<HTMLElement>>('contenido');
  private readonly avisosALaVista = new AvisosALaVista();

  constructor() {
    // El encabezado del cajón muestra el título sin tocar las pantallas: sigue al primer `h1` del contenido.
    const observador = new MutationObserver(() => this.leerTitulo());
    observador.observe(this.host.nativeElement, { childList: true, subtree: true, characterData: true });
    this.destruccion.onDestroy(() => observador.disconnect());
    this.leerTitulo();

    // Los avisos de acción se traen a la vista cuando aparecen dentro del contenido, sea cual sea la pantalla (S8). Se
    // engancha después de pintar y se suelta al destruir el shell, para no dejar el observer vivo.
    afterNextRender(() => {
      const contenido = this.contenido()?.nativeElement;
      if (contenido) this.avisosALaVista.conectar(contenido);
    });
    this.destruccion.onDestroy(() => this.avisosALaVista.desconectar());

    this.abrirElDe(this.router.url);
    this.router.events
      .pipe(
        filter((evento): evento is NavigationEnd => evento instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((evento) => {
        this.abrirElDe(evento.urlAfterRedirects);
        this.avisoAlLlegar.set(this.avisos.tomar());
        // Al navegar el cajón se cierra (S2): la pantalla nueva se ve entera sin un paso de más.
        if (this.menuAbierto()) this.cerrarMenu();
      });
    // Una redirección a la pantalla en la que ya se está (el Vendedor en el Mostrador que va a Ventas) no termina en
    // NavigationEnd: el aviso que dejó el guard se muestra acá mismo, en vez de quedar para la pantalla siguiente.
    this.router.events
      .pipe(
        filter((evento) => evento instanceof NavigationSkipped),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        const aviso = this.avisos.tomar();
        if (aviso) this.avisoAlLlegar.set(aviso);
        // Tocar en el cajón la opción de la pantalla en la que ya se está no navega: el cajón se cierra igual.
        if (this.menuAbierto()) this.cerrarMenu();
      });

    // Se lee al abrir, cada 5 minutos y cuando la configuración o un recálculo avisan que la cotización cambió.
    // El super admin no tiene ferretería: la cotización es un dato de ferretería y no se le pide. Con la contraseña
    // por cambiar tampoco (el servidor responde 403, hallazgo 5): se lee en cuanto el usuario la cambia.
    const contrasenaCambiada$ = toObservable(this.auth.debeCambiarContrasena).pipe(
      pairwise(),
      filter(([antes, ahora]) => antes && !ahora),
    );
    merge(interval(INTERVALO_COTIZACION_MS).pipe(startWith(0)), this.cotizaciones.refrescos$, contrasenaCambiada$)
      .pipe(
        filter(() => !this.auth.esSuperAdmin() && !this.auth.debeCambiarContrasena()),
        // Un pedido nuevo deja sin efecto a uno anterior que siga en camino.
        switchMap(() => this.cotizaciones.obtener().pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(),
      )
      .subscribe((cotizacion) => this.cotizacion.set(cotizacion));
  }

  protected readonly iniciales = computed(() => iniciales(this.auth.sesion()?.nombre ?? ''));

  protected abierto(seccion: Seccion): boolean {
    return this.abiertos().has(seccion);
  }

  protected alternar(seccion: Seccion): void {
    this.abiertos.update((abiertos) => {
      const nuevos = new Set(abiertos);
      if (!nuevos.delete(seccion)) nuevos.add(seccion);
      return nuevos;
    });
  }

  /** Al llegar a una pantalla se abre el grupo de su sección; los que el usuario abrió quedan abiertos. */
  private abrirElDe(url: string): void {
    this.url.set(url);
    const seccion = seccionDe(url);
    if (seccion && !this.abierto(seccion)) this.alternar(seccion);
  }

  /** Lo resuelve el enlace y no el navegador: un `#contenido` en la URL lo tomaría el router. */
  protected saltarAlContenido(evento: Event, contenido: HTMLElement): void {
    evento.preventDefault();
    contenido.focus();
  }

  protected alternarMenu(): void {
    if (this.menuAbierto()) {
      this.cerrarMenu();
      return;
    }
    this.menuAbierto.set(true);
    // El foco entra al cajón (en el cajón, no en un campo: no abre el teclado del celular) y Tab sigue desde adentro.
    this.cajon()?.nativeElement.focus();
  }

  /** Cierra el cajón y devuelve el foco al botón «Menú» (S2). */
  protected cerrarMenu(): void {
    this.menuAbierto.set(false);
    this.botonMenu()?.nativeElement.focus();
  }

  /**
   * Esc cierra y Tab da la vuelta dentro del cajón. Sin cajón abierto no se toca nada: en escritorio el menú es la columna
   * del grid y el teclado se comporta como siempre.
   */
  protected alTeclear(evento: Event): void {
    // Lo que un control de adentro ya resolvió (Esc en el buscador vacía la búsqueda) no se pisa desde acá.
    if (!(evento instanceof KeyboardEvent) || evento.defaultPrevented || !this.menuAbierto()) return;

    if (evento.key === 'Escape') {
      evento.preventDefault();
      this.cerrarMenu();
      return;
    }
    if (evento.key !== 'Tab') return;

    const controles = this.controlesDelCajon();
    const primero = controles[0];
    const ultimo = controles[controles.length - 1];
    if (!primero || !ultimo) return;

    const activo = document.activeElement as HTMLElement | null;
    // El propio cajón no cuenta como «adentro»: al abrir el foco queda en el `aside` (tabindex=-1) y desde ahí
    // Shift+Tab tiene que ir al último control, no salirse al botón «Menú», que queda detrás del velo (B1).
    const cajon = this.cajon()?.nativeElement;
    const adentro = !!cajon && activo !== cajon && cajon.contains(activo);
    if (evento.shiftKey) {
      if (!adentro || activo === primero) {
        evento.preventDefault();
        ultimo.focus();
      }
    } else if (!adentro || activo === ultimo) {
      evento.preventDefault();
      primero.focus();
    }
  }

  /** Los controles del cajón en el orden en que los recorre Tab. */
  private controlesDelCajon(): HTMLElement[] {
    const cajon = this.cajon()?.nativeElement;
    if (!cajon) return [];
    return Array.from(
      cajon.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ),
    ).filter((control) => control.getAttribute('aria-hidden') !== 'true');
  }

  /** El título del encabezado es el del primer `h1` de la pantalla abierta; sin `h1` queda vacío. */
  private leerTitulo(): void {
    const titulo = this.host.nativeElement.querySelector('h1')?.textContent?.trim() ?? '';
    if (titulo !== this.tituloPantalla()) this.tituloPantalla.set(titulo);
  }

  protected salir(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
