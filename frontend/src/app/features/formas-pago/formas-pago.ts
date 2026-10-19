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
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { FilasElegibles } from '../../shared/filas-elegibles';
import { MEDIOS_PAGO, MEDIO_PAGO_LABELS, MedioPago } from '../mostrador/venta.models';
import { FormaPago, FormaPagoRequest } from './forma-pago.models';
import { FormasPagoService } from './formas-pago.service';

const RECARGO_MAXIMO = 200;
const porOrden = (a: FormaPago, b: FormaPago) => a.orden - b.orden || a.nombre.localeCompare(b.nombre);
const porcentaje = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 });

/**
 * Configuración › Formas de pago (GDS-11, solo el Dueño). Cada forma ("Visa 6 cuotas", "Mercado Pago") pertenece a un
 * medio (el que suma la caja) y trae su recargo en %, que el mostrador suma al total de lo que se paga con ella. No se
 * borran: se desactivan, y las desactivadas dejan de ofrecerse al cobrar. Contenedor: habla con el servicio.
 */
@Component({
  selector: 'app-formas-pago',
  imports: [ReactiveFormsModule, BarraAcciones],
  templateUrl: './formas-pago.html',
  styleUrl: './formas-pago.css',
})
export class FormasPago implements OnInit {
  private readonly service = inject(FormasPagoService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  protected readonly auth = inject(AuthService);

  protected readonly medios = MEDIOS_PAGO;
  protected readonly etiquetaMedio = MEDIO_PAGO_LABELS;

  protected readonly formas = signal<FormaPago[]>([]);
  /** Fila elegida en la grilla: la barra actúa sobre ella; Enter edita. */
  protected readonly filas = new FilasElegibles(this.formas, () => this.auth.esDueno(), (f) => this.editar(f));
  protected readonly cargando = signal(true);
  /** La lista no se pudo traer: se avisa con Reintentar. */
  protected readonly cargaFallida = signal(false);
  protected readonly aviso = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Lo que el backend rechazó, un mensaje por campo (400 de validación). */
  protected readonly erroresCampos = signal<string[]>([]);
  protected readonly guardando = signal(false);
  /** La forma que se está editando; null mientras se da de alta una nueva. */
  protected readonly editando = signal<FormaPago | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    nombre: ['', [Validators.required, Validators.maxLength(60), Validators.pattern(/\S/)]],
    medio: ['EFECTIVO' as MedioPago],
    recargoPorcentaje: [
      0 as number | null,
      [Validators.required, Validators.min(0), Validators.max(RECARGO_MAXIMO)],
    ],
    activa: [true],
  });

  /** El medio y el estado elegidos en el formulario: la pista y el recargo bloqueado dependen de ellos. */
  protected readonly medioElegido = signal<MedioPago>('EFECTIVO');
  protected readonly activaElegida = signal(true);

  constructor() {
    // Cuenta corriente no admite recargo (CC-023): se fija en 0 y se bloquea; con otro medio vuelve a poder editarse.
    this.form.controls.medio.valueChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((medio) => {
      this.medioElegido.set(medio);
      const recargo = this.form.controls.recargoPorcentaje;
      if (medio === 'CUENTA_CORRIENTE') {
        recargo.setValue(0);
        recargo.disable();
      } else if (recargo.disabled) {
        recargo.enable();
      }
    });
    this.form.controls.activa.valueChanges
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((activa) => this.activaElegida.set(activa));
    // Al escribir se va el aviso de error del backend: ya no corresponde a lo que hay en el formulario.
    this.form.valueChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(() => this.limpiarErrores());
  }

  ngOnInit(): void {
    if (this.auth.esDueno()) this.cargar();
  }

  /** Carga la lista; también la usa Reintentar después de una carga fallida. */
  protected cargar(): void {
    this.cargando.set(true);
    this.cargaFallida.set(false);
    this.service.listar().subscribe({
      next: (lista) => {
        this.formas.set([...lista].sort(porOrden));
        this.cargando.set(false);
      },
      error: () => {
        this.formas.set([]);
        this.cargando.set(false);
        this.cargaFallida.set(true);
      },
    });
  }

  protected guardar(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.enfocar('form.alta [aria-invalid="true"]');
      return;
    }
    const { nombre, medio, recargoPorcentaje, activa } = this.form.getRawValue();
    const request: FormaPagoRequest = {
      nombre: nombre.trim(),
      medio,
      recargoPorcentaje: medio === 'CUENTA_CORRIENTE' ? 0 : (recargoPorcentaje ?? 0),
      activa,
    };
    const forma = this.editando();
    this.limpiarErrores();
    this.aviso.set(null);
    this.guardando.set(true);
    const llamada = forma === null ? this.service.crear(request) : this.service.actualizar(forma.id, request);
    llamada.subscribe({
      next: (guardada) => {
        this.formas.update((lista) => [...lista.filter((f) => f.id !== guardada.id), guardada].sort(porOrden));
        this.vaciarFormulario();
        this.guardando.set(false);
        this.aviso.set(`Se guardó «${guardada.nombre}».`);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        const porCampo = erroresDeCampos(err);
        if (porCampo.length > 0) this.erroresCampos.set(porCampo);
        else this.error.set(mensajeDe(err, 'No se pudo guardar la forma de pago.'));
      },
    });
  }

  /** Editar (barra, Enter o doble clic): precarga la forma en el formulario de al lado. */
  protected editar(forma: FormaPago | null = this.filas.elegido()): void {
    if (!forma || !this.auth.esDueno()) return;
    this.aviso.set(null);
    this.filas.elegidoId.set(forma.id);
    this.editando.set(forma);
    this.form.reset({
      nombre: forma.nombre,
      medio: forma.medio,
      recargoPorcentaje: forma.recargoPorcentaje,
      activa: forma.activa,
    });
    this.limpiarErrores();
  }

  protected cancelar(): void {
    this.vaciarFormulario();
    this.limpiarErrores();
  }

  /** Nuevo: el formulario vuelve al alta y queda listo para escribir. */
  protected nuevo(): void {
    this.aviso.set(null);
    this.cancelar();
    this.enfocar('#nombre');
  }

  /** La forma de cuenta corriente que todavía no se activó: hasta entonces no se ofrece al cobrar. */
  protected esperaActivacion(forma: FormaPago): boolean {
    return forma.medio === 'CUENTA_CORRIENTE' && !forma.activa;
  }

  protected recargoTexto(forma: FormaPago): string {
    return forma.recargoPorcentaje === 0 ? 'Sin recargo' : `${porcentaje.format(forma.recargoPorcentaje)}\u00a0%`;
  }

  protected invalido(campo: 'nombre' | 'recargoPorcentaje'): boolean {
    const control = this.form.controls[campo];
    return control.touched && control.invalid;
  }

  /** Después de pintar, enfoca el primer elemento que coincida. */
  private enfocar(selector: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }

  private limpiarErrores(): void {
    this.error.set(null);
    this.erroresCampos.set([]);
  }

  private vaciarFormulario(): void {
    this.editando.set(null);
    this.form.reset({ nombre: '', medio: 'EFECTIVO', recargoPorcentaje: 0, activa: true });
  }
}
