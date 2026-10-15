import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { Rubro, RubroRequest } from './rubro.models';
import { RubrosService } from './rubros.service';

const MARGEN_INICIAL = 30;
const porNombre = (a: Rubro, b: Rubro) => a.nombre.localeCompare(b.nombre);
const porcentaje = (valor: number) => new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 }).format(valor);

/** Cambio de margen pendiente de confirmar: cuántos artículos cambian de precio (null si no se pudo contar). */
interface CambioDeMargen {
  rubro: Rubro;
  request: RubroRequest;
  articulos: number | null;
}

@Component({
  selector: 'app-rubros',
  imports: [ReactiveFormsModule, DecimalPipe, Confirmacion, BarraAcciones],
  templateUrl: './rubros.html',
  styleUrl: './rubros.css',
})
export class Rubros implements OnInit {
  private readonly service = inject(RubrosService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  protected readonly auth = inject(AuthService);

  protected readonly rubros = signal<Rubro[]>([]);
  /** Fila elegida en la grilla: la barra actúa sobre ella. */
  protected readonly elegidoId = signal<number | null>(null);
  protected readonly elegido = computed(() => this.rubros().find((r) => r.id === this.elegidoId()) ?? null);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
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
    if (rubro === null) {
      this.enviar(this.service.crear(request), null);
      return;
    }
    if (rubro.margenPorcentaje === request.margenPorcentaje) {
      this.enviar(this.service.actualizar(rubro.id, request), null);
      return;
    }
    // Cambia el margen: se recalculan los precios de los artículos que lo heredan. Se avisa cuántos antes.
    this.guardando.set(true);
    this.service.articulosQueHeredan(rubro.id).subscribe({
      next: (articulos) => {
        this.guardando.set(false);
        if (articulos === 0) this.enviar(this.service.actualizar(rubro.id, request), 0);
        else this.cambioDeMargen.set({ rubro, request, articulos });
      },
      error: () => {
        this.guardando.set(false);
        this.cambioDeMargen.set({ rubro, request, articulos: null });
      },
    });
  }

  protected confirmarCambioDeMargen(): void {
    const cambio = this.cambioDeMargen();
    this.cambioDeMargen.set(null);
    if (cambio) this.enviar(this.service.actualizar(cambio.rubro.id, cambio.request), cambio.articulos);
  }

  protected tituloCambio(cambio: CambioDeMargen): string {
    return `¿Cambiar el margen de ${cambio.rubro.nombre} de ${porcentaje(cambio.rubro.margenPorcentaje)} % a ${porcentaje(cambio.request.margenPorcentaje)} %?`;
  }

  protected mensajeCambio(cambio: CambioDeMargen): string {
    if (cambio.articulos === null) return 'Se recalcula el precio de los artículos que usan el margen del rubro.';
    return cambio.articulos === 1
      ? 'Se recalcula el precio de 1 artículo que usa el margen del rubro.'
      : `Se recalcula el precio de ${cambio.articulos} artículos que usan el margen del rubro.`;
  }

  /** Editar (barra, Enter o doble clic): precarga el rubro en el formulario de al lado. */
  protected editar(rubro: Rubro | null = this.elegido()): void {
    if (!rubro || !this.auth.esDueno()) return;
    this.aviso.set(null);
    this.elegidoId.set(rubro.id);
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
    this.cancelar();
    this.enfocar('#nombre');
  }

  /** Clic o foco en una fila: la elige (solo el Dueño, que es quien tiene acciones). */
  protected elegir(rubro: Rubro): void {
    if (this.auth.esDueno()) this.elegidoId.set(rubro.id);
  }

  /** La fila que entra en el orden de Tab: la elegida o, si no hay, la primera. */
  protected esFilaTabulable(rubro: Rubro, indice: number): boolean {
    return this.elegido() ? rubro.id === this.elegidoId() : indice === 0;
  }

  /** ↑/↓ mueven la elección y el foco; Enter edita. */
  protected alTeclearFila(evento: KeyboardEvent, rubro: Rubro, indice: number): void {
    if (!this.auth.esDueno()) return;
    if (evento.key === 'Enter') {
      evento.preventDefault();
      this.editar(rubro);
      return;
    }
    const paso = evento.key === 'ArrowDown' ? 1 : evento.key === 'ArrowUp' ? -1 : 0;
    if (paso === 0) return;
    evento.preventDefault();
    const destino = Math.min(Math.max(indice + paso, 0), this.rubros().length - 1);
    this.elegidoId.set(this.rubros()[destino].id);
    this.enfocar(`tbody tr:nth-child(${destino + 1})`);
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
        const recalculo =
          habiaMargenNuevo && recalculados !== null && recalculados > 0
            ? ` Se recalcularon ${recalculados} ${recalculados === 1 ? 'precio' : 'precios'}.`
            : '';
        this.aviso.set(`Se guardó «${rubro.nombre}».${recalculo}`);
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

  private cargar(): void {
    this.service.listar().subscribe({
      next: (lista) => {
        this.rubros.set(lista);
        this.cargando.set(false);
      },
      error: () => {
        this.cargando.set(false);
        this.error.set('No se pudieron cargar los rubros.');
      },
    });
  }
}
