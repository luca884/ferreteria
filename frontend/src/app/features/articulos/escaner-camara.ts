import {
  Component,
  ElementRef,
  Injector,
  OnDestroy,
  afterNextRender,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { Subscription } from 'rxjs';
import { MontoPipe } from '../../core/formato/monto';
import { cantidadConUnidad, porUnidad } from '../../core/formato/unidad';
import { TrampaFoco } from '../../shared/trampa-foco';
import { Articulo } from './articulo.models';
import { ArticulosService } from './articulos.service';
import { CamaraEscaner, CausaFalla, SesionCamara, causaDeFalla } from './camara-escaner';

/** Cada cuánto mira el lector un cuadro del video. */
const CADA_MS = 250;
const MAX_FALLAS_SEGUIDAS = 5;
const CONSULTA_CELULAR = '(max-width: 639.98px)';

type FallaEscaner = CausaFalla | 'lectura';

/** Los textos de cada falla (los artboards no diseñan los estados de permiso). */
const TEXTOS_FALLA: Record<FallaEscaner, string> = {
  denegada:
    'No pudimos usar la cámara. Si la bloqueaste, habilitala desde los permisos del navegador y volvé a intentar.',
  'sin-camara': 'No encontramos una cámara en este dispositivo. Escribí el código a mano.',
  'sin-lector': 'Este navegador no puede escanear códigos de barras. Escribí el código a mano.',
  lectura: 'No pudimos leer el código de barras. Probá de nuevo o escribilo a mano.',
  otra: 'No pudimos abrir la cámara. Puede que otra aplicación la esté usando. Probá de nuevo o escribí el código a mano.',
};

type Estado =
  | { tipo: 'abriendo' }
  | { tipo: 'mirando' }
  | { tipo: 'falla'; causa: FallaEscaner }
  | { tipo: 'buscando'; codigo: string }
  | { tipo: 'leido'; codigo: string; articulo: Articulo }
  | { tipo: 'sin-articulo'; codigo: string }
  | { tipo: 'error-busqueda'; codigo: string };

/** Un UPC-A (12 dígitos) es un EAN-13 con un 0 adelante: los dos códigos son el mismo producto. */
function mismoCodigo(leido: string, guardado: string | null): boolean {
  if (guardado === null) return false;
  if (leido === guardado) return true;
  return /^\d+$/.test(leido) && /^\d+$/.test(guardado) && leido.replace(/^0+/, '') === guardado.replace(/^0+/, '');
}

/**
 * movil-sectores T7 (S1; L18, L19): el escáner de código de barras por cámara del buscador de Artículos, a pantalla
 * completa y sólo en el celular. Pide la cámara cada vez que se abre (un permiso negado no se recuerda), lee con el
 * `BarcodeDetector` nativo, busca el código con el servicio de artículos que ya existe y muestra el artículo leído.
 * Si no hay permiso, cámara u otra cosa falla, lo explica y deja escribir el código a mano. La cámara se apaga al
 * leer, al cerrar, al salir de la pantalla y al destruirse. Esc cierra; `appTrampaFoco` maneja el foco.
 */
@Component({
  selector: 'app-escaner-camara',
  imports: [TrampaFoco, MontoPipe, NgTemplateOutlet],
  host: { '(document:keydown)': 'alTeclear($event)' },
  templateUrl: './escaner-camara.html',
  styleUrl: './escaner-camara.css',
})
export class EscanerCamara implements OnDestroy {
  /** La ✕ o Esc, o dejar de ser la pantalla del celular. */
  readonly cerrar = output<void>();
  /** «Escribir el código a mano»: con el código leído para completar el buscador, o null si la cámara ni abrió. */
  readonly escribirAMano = output<string | null>();
  /** «Abrir ficha» del artículo leído. */
  readonly abrirFicha = output<Articulo>();

  private readonly camara = inject(CamaraEscaner);
  private readonly articulos = inject(ArticulosService);
  private readonly injector = inject(Injector);
  private readonly pantalla = viewChild.required<ElementRef<HTMLElement>>('pantalla');
  private readonly video = viewChild.required<ElementRef<HTMLVideoElement>>('video');
  private readonly trampa = viewChild.required(TrampaFoco);

  protected readonly estado = signal<Estado>({ tipo: 'abriendo' });
  protected readonly tieneLinterna = signal(false);
  protected readonly linternaEncendida = signal(false);
  protected readonly textosFalla = TEXTOS_FALLA;
  protected readonly cantidadConUnidad = cantidadConUnidad;
  protected readonly porUnidad = porUnidad;

  private sesion: SesionCamara | null = null;
  private temporizador: ReturnType<typeof setInterval> | null = null;
  private busqueda: Subscription | null = null;
  private leyendo = false;
  private fallasSeguidas = 0;
  /** Cada apertura o cierre lo sube: un permiso que llega tarde (ya se cerró o se reintentó) apaga su cámara. */
  private generacion = 0;
  private readonly consultaCelular =
    typeof window.matchMedia === 'function' ? window.matchMedia(CONSULTA_CELULAR) : null;
  private readonly alCambiarPantalla = () => {
    if (!this.consultaCelular?.matches) this.cerrar.emit();
  };

  constructor() {
    this.consultaCelular?.addEventListener?.('change', this.alCambiarPantalla);
    afterNextRender(() => {
      this.pantalla().nativeElement.focus();
      void this.iniciar();
    });
  }

  ngOnDestroy(): void {
    this.consultaCelular?.removeEventListener?.('change', this.alCambiarPantalla);
    this.apagar();
  }

  /** Esc cierra, salvo que sea de otro diálogo que esté encima. */
  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key !== 'Escape' || evento.defaultPrevented || !this.trampa().esLaDeArriba()) return;
    evento.preventDefault();
    this.cerrar.emit();
  }

  /** Pide la cámara —siempre de nuevo— y empieza a leer. También es «Intentar de nuevo» y «Escanear de nuevo». */
  protected async iniciar(): Promise<void> {
    this.apagar();
    const mia = this.generacion;
    this.poner({ tipo: 'abriendo' });
    let sesion: SesionCamara;
    try {
      sesion = await this.camara.abrir();
    } catch (error) {
      if (mia === this.generacion) this.poner({ tipo: 'falla', causa: causaDeFalla(error) });
      return;
    }
    if (mia !== this.generacion) {
      sesion.detener();
      return;
    }
    this.sesion = sesion;
    this.tieneLinterna.set(sesion.tieneLinterna);
    this.linternaEncendida.set(false);
    const video = this.video().nativeElement;
    video.srcObject = sesion.stream;
    try {
      // En algunos navegadores play() rechaza si se la interrumpe (cerrar enseguida): no es una falla.
      await Promise.resolve(video.play()).catch(() => undefined);
    } catch {
      /* sin reproducción (pruebas): el lector igual espera a que haya imagen */
    }
    if (mia !== this.generacion) return;
    this.poner({ tipo: 'mirando' });
    this.temporizador = setInterval(() => void this.leer(sesion, mia), CADA_MS);
  }

  private async leer(sesion: SesionCamara, generacion: number): Promise<void> {
    if (this.leyendo || generacion !== this.generacion) return;
    this.leyendo = true;
    let codigo: string | null;
    try {
      codigo = await sesion.leer(this.video().nativeElement);
    } catch {
      if (generacion === this.generacion && ++this.fallasSeguidas >= MAX_FALLAS_SEGUIDAS) {
        this.apagar();
        this.poner({ tipo: 'falla', causa: 'lectura' });
      }
      return;
    } finally {
      if (generacion === this.generacion) this.leyendo = false;
    }
    if (generacion !== this.generacion) return;
    this.fallasSeguidas = 0; // Un cuadro sin código también es una lectura exitosa.
    if (codigo) this.alLeer(codigo);
  }

  /** La primera lectura: apaga la cámara y busca el código con el servicio de siempre. */
  private alLeer(codigo: string): void {
    this.apagar();
    this.poner({ tipo: 'buscando', codigo });
    // Con los dados de baja: si el código es de uno, se muestra marcado y no como «no existe».
    this.busqueda = this.articulos.buscar(codigo, 0, 20, true).subscribe({
      next: (pagina) => {
        const iguales = pagina.items.filter((a) => mismoCodigo(codigo, a.codigoBarras));
        const articulo = iguales.find((a) => a.activo) ?? iguales[0];
        this.poner(articulo ? { tipo: 'leido', codigo, articulo } : { tipo: 'sin-articulo', codigo });
      },
      error: () => this.poner({ tipo: 'error-busqueda', codigo }),
    });
  }

  protected async alternarLinterna(): Promise<void> {
    const sesion = this.sesion;
    if (!sesion) return;
    const encender = !this.linternaEncendida();
    try {
      await sesion.linterna(encender);
      this.linternaEncendida.set(encender);
    } catch {
      /* la linterna no se pudo cambiar: se sigue escaneando */
    }
  }

  /** El código leído, si hay uno, para completar el buscador a mano. */
  protected aMano(): void {
    const actual = this.estado();
    this.escribirAMano.emit('codigo' in actual ? actual.codigo : null);
  }

  /** Apaga la cámara y todo lo pendiente (lector, búsqueda, permiso que todavía no llegó). */
  private apagar(): void {
    this.generacion++;
    if (this.temporizador !== null) clearInterval(this.temporizador);
    this.temporizador = null;
    this.busqueda?.unsubscribe();
    this.busqueda = null;
    this.leyendo = false;
    this.fallasSeguidas = 0;
    this.sesion?.detener();
    this.sesion = null;
    this.linternaEncendida.set(false);
    this.tieneLinterna.set(false);
    try {
      this.video().nativeElement.srcObject = null;
    } catch {
      /* la vista ya no está (se destruyó el componente): no hay video que soltar */
    }
  }

  /** Cambia de estado y, si lo que tenía el foco desaparece, lo lleva a la acción principal de lo nuevo. */
  private poner(estado: Estado): void {
    this.estado.set(estado);
    afterNextRender(
      () => {
        const pantalla = this.pantalla().nativeElement;
        if (pantalla.contains(document.activeElement) && document.activeElement !== pantalla) return;
        (pantalla.querySelector<HTMLElement>('.acciones button') ?? pantalla).focus();
      },
      { injector: this.injector },
    );
  }
}
