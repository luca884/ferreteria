import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { FilasElegibles } from '../../shared/filas-elegibles';
import { Rubro, RubroRequest, VistaPreviaMargen } from './rubro.models';
import { RubrosService } from './rubros.service';

const MARGEN_INICIAL = 30;
const ENTERO = new Intl.NumberFormat('es-AR');
const porNombre = (a: Rubro, b: Rubro) => a.nombre.localeCompare(b.nombre);
const porcentaje = (valor: number) => new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 }).format(valor);

/**
 * Cambio de margen pendiente de confirmar, con los artículos que cambian de precio (null si no se pudo traer la vista
 * previa: igual se avisa, con un texto general).
 */
interface CambioDeMargen {
  rubro: Rubro;
  request: RubroRequest;
  vista: VistaPreviaMargen | null;
}

@Component({
  selector: 'app-rubros',
  imports: [ReactiveFormsModule, DecimalPipe, MontoPipe, Confirmacion, BarraAcciones],
  templateUrl: './rubros.html',
  styleUrl: './rubros.css',
})
export class Rubros implements OnInit {
  private readonly service = inject(RubrosService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  protected readonly auth = inject(AuthService);

  protected readonly rubros = signal<Rubro[]>([]);
  /** Fila elegida en la grilla (solo el Dueño, que es quien tiene acciones): la barra actúa sobre ella; Enter edita. */
  protected readonly filas = new FilasElegibles(this.rubros, () => this.auth.esDueno(), (r) => this.editar(r));
  protected readonly cargando = signal(true);
  /** La lista no se pudo traer: se avisa con Reintentar en lugar de «Todavía no hay rubros». */
  protected readonly cargaFallida = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
  /** Artículos que conservaron su precio al guardar el margen porque sus ajustes lo dejarían en 0 o menos. */
  protected readonly avisosSalteados = signal<string[]>([]);
  protected readonly guardando = signal(false);
  /** Rubro que se está editando; null mientras se da de alta uno nuevo. */
  protected readonly editando = signal<Rubro | null>(null);
  protected readonly cambioDeMargen = signal<CambioDeMargen | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    nombre: ['', [Validators.required, Validators.maxLength(80), Validators.pattern(/\S/)]],
    margenPorcentaje: [
      MARGEN_INICIAL as number | null,
      [Validators.required, Validators.min(0), Validators.max(1000)],
    ],
  });

  constructor() {
    // Al escribir se va el aviso de error del backend: ya no corresponde a lo que hay en el formulario.
    this.form.valueChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(() => this.error.set(null));
  }

  ngOnInit(): void {
    this.cargar();
  }

  protected guardar(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.enfocar('form.alta [aria-invalid="true"]');
      return;
    }
    const { nombre, margenPorcentaje } = this.form.getRawValue();
    const request: RubroRequest = { nombre: nombre.trim(), margenPorcentaje: margenPorcentaje ?? 0 };
    const rubro = this.editando();
    this.error.set(null);
    this.aviso.set(null);
    this.avisosSalteados.set([]);
    if (rubro === null) {
      this.enviar(this.service.crear(request), null);
      return;
    }
    if (rubro.margenPorcentaje === request.margenPorcentaje) {
      this.enviar(this.service.actualizar(rubro.id, request), null);
      return;
    }
    // Cambia el margen: se recalculan los precios de los artículos que lo heredan. Antes se muestran los que cambian.
    this.guardando.set(true);
    this.service.vistaPreviaMargen(rubro.id, request.margenPorcentaje).subscribe({
      next: (vista) => {
        this.guardando.set(false);
        if (vista.cantidad === 0) this.enviar(this.service.actualizar(rubro.id, request), 0);
        else this.cambioDeMargen.set({ rubro, request, vista });
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        // Sin cotización del dólar el guardado también daría 409: se muestra el motivo y no se ofrece guardar.
        if (err instanceof HttpErrorResponse && err.status === 409 && err.error?.message) {
          this.error.set(err.error.message);
          return;
        }
        this.cambioDeMargen.set({ rubro, request, vista: null });
      },
    });
  }

  protected confirmarCambioDeMargen(): void {
    const cambio = this.cambioDeMargen();
    this.cambioDeMargen.set(null);
    if (cambio) this.enviar(this.service.actualizar(cambio.rubro.id, cambio.request), cambio.vista?.cantidad ?? null);
  }

  /** Con el nombre que se está guardando: si cambian el nombre y el margen juntos, ya va el nuevo. */
  protected tituloCambio(cambio: CambioDeMargen): string {
    return `¿Cambiar el margen de ${cambio.request.nombre} de ${porcentaje(cambio.rubro.margenPorcentaje)} % a ${porcentaje(cambio.request.margenPorcentaje)} %?`;
  }

  protected mensajeCambio(cambio: CambioDeMargen): string {
    if (cambio.vista === null) return 'Se recalcula el precio de los artículos que usan el margen del rubro.';
    const cantidad = cambio.vista.cantidad;
    return cantidad === 1 ? '1 artículo cambia de precio.' : `${ENTERO.format(cantidad)} artículos cambian de precio.`;
  }

  /** La lista llega hasta 1.000: si hay más, se dice cuántos se ven de cuántos. */
  protected avisoRecorte(vista: VistaPreviaMargen): string {
    return `Se muestran los primeros ${ENTERO.format(vista.articulos.length)} de ${ENTERO.format(vista.cantidad)}.`;
  }

  /** Editar (barra, Enter o doble clic): precarga el rubro en el formulario de al lado. */
  protected editar(rubro: Rubro | null = this.filas.elegido()): void {
    if (!rubro || !this.auth.esDueno()) return;
    this.aviso.set(null);
    this.avisosSalteados.set([]);
    this.filas.elegidoId.set(rubro.id);
    this.editando.set(rubro);
    this.form.reset({ nombre: rubro.nombre, margenPorcentaje: rubro.margenPorcentaje });
    this.error.set(null);
  }

  protected cancelar(): void {
    this.vaciarFormulario();
    this.error.set(null);
  }

  /** Nuevo: el formulario vuelve al alta y queda listo para escribir. */
  protected nuevo(): void {
    this.aviso.set(null);
    this.avisosSalteados.set([]);
    this.cancelar();
    this.enfocar('#nombre');
  }

  protected invalido(campo: 'nombre' | 'margenPorcentaje'): boolean {
    const control = this.form.controls[campo];
    return control.touched && control.invalid;
  }

  /** Después de pintar, enfoca el primer elemento que coincida. */
  private enfocar(selector: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }

  /** Crea o actualiza; `recalculados` es para el aviso (null: no se sabe cuántos). */
  private enviar(llamada: ReturnType<RubrosService['crear']>, recalculados: number | null): void {
    const habiaMargenNuevo = this.editando() !== null && recalculados !== 0;
    this.guardando.set(true);
    llamada.subscribe({
      next: (rubro) => {
        this.rubros.update((lista) => [...lista.filter((r) => r.id !== rubro.id), rubro].sort(porNombre));
        this.vaciarFormulario();
        this.guardando.set(false);
        // El formulario estuvo inerte mientras guardaba: queda listo para el próximo alta, como con Nuevo.
        this.enfocar('#nombre');
        const recalculo =
          habiaMargenNuevo && recalculados !== null && recalculados > 0
            ? ` Se recalcularon ${recalculados} ${recalculados === 1 ? 'precio' : 'precios'}.`
            : '';
        this.aviso.set(`Se guardó «${rubro.nombre}».${recalculo}`);
        this.avisosSalteados.set(rubro.avisos ?? []);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        this.error.set(
          err instanceof HttpErrorResponse && err.error?.message
            ? err.error.message
            : 'No se pudo guardar el rubro.',
        );
      },
    });
  }

  private vaciarFormulario(): void {
    this.editando.set(null);
    this.form.reset({ nombre: '', margenPorcentaje: MARGEN_INICIAL });
  }

  /** Carga la lista; también la usa Reintentar después de una carga fallida. */
  protected cargar(): void {
    this.cargando.set(true);
    this.cargaFallida.set(false);
    this.service.listar().subscribe({
      next: (lista) => {
        this.rubros.set(lista);
        this.cargando.set(false);
      },
      error: () => {
        this.rubros.set([]);
        this.cargando.set(false);
        this.cargaFallida.set(true);
      },
    });
  }
}
