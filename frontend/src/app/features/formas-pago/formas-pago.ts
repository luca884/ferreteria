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
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { FilasElegibles } from '../../shared/filas-elegibles';
import { MotivoDeAccion, ariaDe } from '../../shared/motivo-de-accion';
import { MEDIOS_PAGO, MEDIO_PAGO_LABELS, MedioPago } from '../mostrador/venta.models';
import { FormaPago, FormaPagoRequest } from './forma-pago.models';
import { FormasPagoService } from './formas-pago.service';

const RECARGO_MAXIMO = 200;
const DESCUENTO_MAXIMO = 99.99;
/** S5: lo que se explica al intentar Editar sin una fila elegida. */
const MOTIVO_SIN_ELEGIR = 'Elegí una forma de pago de la lista para editarla.';
/** S5: lo que se explica al intentar Activar o Desactivar sin una fila elegida. */
const MOTIVO_SIN_ELEGIR_ALTERNAR = 'Elegí una forma de pago de la lista para activarla o desactivarla.';
/** La pantalla del celular (la misma consulta de todo el CSS móvil); sin `matchMedia` (pruebas, impresión) no lo es. */
const enElCelular = (): boolean =>
  typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 639.98px)').matches;
const porOrden = (a: FormaPago, b: FormaPago) => a.orden - b.orden || a.nombre.localeCompare(b.nombre);
const porcentaje = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 });

/**
 * Configuración › Formas de pago (GDS-11, solo el Dueño). Cada forma ("Visa 6 cuotas", "Mercado Pago") pertenece a un
 * medio (el que suma la caja) y trae su recargo en %, que el mostrador suma al total de lo que se paga con ella, o su
 * descuento en % (descuento en efectivo), que le resta; nunca los dos. No se borran: se desactivan, y las desactivadas
 * dejan de ofrecerse al cobrar. Contenedor: habla con el servicio.
 */
@Component({
  selector: 'app-formas-pago',
  imports: [ReactiveFormsModule, BarraAcciones, Confirmacion],
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
  /**
   * La ficha a pantalla completa del celular (S7 de movil-sectores): `nuevo`, `editar` y tocar una fila la abren;
   * guardar, cancelar y «Volver» la cierran. Sólo la lee el CSS del celular: en el escritorio el formulario siempre
   * está al costado.
   */
  protected readonly fichaAbierta = signal(false);
  /** Activar o desactivar desde la barra, en curso. */
  protected readonly cambiando = signal(false);
  /** La forma que se está por desactivar: espera la confirmación. */
  protected readonly aDesactivar = signal<FormaPago | null>(null);
  /** Por qué las acciones de la barra no se pueden usar: null en la que sí se puede (S5). */
  protected readonly motivos = computed(() => ({
    editar: this.filas.elegido() ? null : MOTIVO_SIN_ELEGIR,
    alternar: this.filas.elegido() ? null : MOTIVO_SIN_ELEGIR_ALTERNAR,
  }));
  /** El motivo a la vista de la última acción que no se pudo usar, o null. Se anuncia con role="status" (S5). */
  protected readonly motivo = new MotivoDeAccion(() => this.motivos());
  /** Para la plantilla: 'true' apaga el botón, null lo deja prendido. */
  protected readonly ariaDe = ariaDe;

  protected readonly form = inject(FormBuilder).nonNullable.group(
    {
      nombre: ['', [Validators.required, Validators.maxLength(60), Validators.pattern(/\S/)]],
      medio: ['EFECTIVO' as MedioPago],
      recargoPorcentaje: [
        0 as number | null,
        [Validators.required, Validators.min(0), Validators.max(RECARGO_MAXIMO)],
      ],
      descuentoPorcentaje: [
        0 as number | null,
        [Validators.required, Validators.min(0), Validators.max(DESCUENTO_MAXIMO)],
      ],
    },
    { validators: recargoODescuento },
  );

  /** El medio elegido en el formulario: el recargo bloqueado depende de él. */
  protected readonly medioElegido = signal<MedioPago>('EFECTIVO');

  constructor() {
    // Cuenta corriente no admite recargo ni descuento (CC-023): se fijan en 0 y se bloquean; con otro medio vuelven a
    // poder editarse.
    this.form.controls.medio.valueChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((medio) => {
      this.medioElegido.set(medio);
      for (const control of [this.form.controls.recargoPorcentaje, this.form.controls.descuentoPorcentaje]) {
        if (medio === 'CUENTA_CORRIENTE') {
          control.setValue(0);
          control.disable();
        } else if (control.disabled) {
          control.enable();
        }
      }
    });
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
      this.form.markAllAsDirty();
      this.enfocar('form.alta [aria-invalid="true"]');
      return;
    }
    const { nombre, medio, recargoPorcentaje, descuentoPorcentaje } = this.form.getRawValue();
    const cuentaCorriente = medio === 'CUENTA_CORRIENTE';
    const forma = this.editando();
    // El estado no se edita acá: lo cambia «Desactivar» / «Activar» de la barra; un alta nace activa.
    const request: FormaPagoRequest = {
      nombre: nombre.trim(),
      medio,
      recargoPorcentaje: cuentaCorriente ? 0 : (recargoPorcentaje ?? 0),
      descuentoPorcentaje: cuentaCorriente ? 0 : (descuentoPorcentaje ?? 0),
      activa: forma?.activa ?? true,
    };
    this.limpiarErrores();
    this.aviso.set(null);
    this.guardando.set(true);
    const llamada = forma === null ? this.service.crear(request) : this.service.actualizar(forma.id, request);
    llamada.subscribe({
      next: (guardada) => {
        this.formas.update((lista) => [...lista.filter((f) => f.id !== guardada.id), guardada].sort(porOrden));
        this.vaciarFormulario();
        this.guardando.set(false);
        // El formulario estuvo inerte mientras guardaba: queda listo para el próximo alta, como con Nuevo.
        this.enfocar('#nombre');
        this.cerrarFicha();
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
    if (!this.auth.esDueno()) return;
    // S5: sin fila elegida el botón avisa el motivo en vez de quedar apagado sin explicación.
    if (!forma) {
      this.motivo.mostrar('editar');
      return;
    }
    this.motivo.limpiar();
    this.aviso.set(null);
    this.filas.elegidoId.set(forma.id);
    this.editando.set(forma);
    this.fichaAbierta.set(true);
    this.form.reset({
      nombre: forma.nombre,
      medio: forma.medio,
      recargoPorcentaje: forma.recargoPorcentaje,
      descuentoPorcentaje: forma.descuentoPorcentaje,
    });
    this.limpiarErrores();
  }

  protected cancelar(): void {
    this.vaciarFormulario();
    this.limpiarErrores();
  }

  /** En el celular, tocar una forma de la lista abre su ficha; en el escritorio sólo la elige (Enter o doble clic editan). */
  protected abrirFichaEnElCelular(forma: FormaPago): void {
    if (!enElCelular()) return;
    this.editar(forma);
    this.enfocar('form.alta .ficha-volver');
  }

  /** «Volver» y «Cancelar» de la ficha del celular: se descarta lo escrito y el foco vuelve al botón «Nuevo». */
  protected volverDeLaFicha(): void {
    this.cancelar();
    this.cerrarFicha();
  }

  /** Cierra la ficha y, en el celular, devuelve el foco a «Nuevo»; en el escritorio el foco se queda donde estaba. */
  private cerrarFicha(): void {
    this.fichaAbierta.set(false);
    if (enElCelular()) this.enfocar('.boton-flotante');
  }

  /** Nuevo: el formulario vuelve al alta y queda listo para escribir. */
  protected nuevo(): void {
    this.aviso.set(null);
    this.cancelar();
    this.fichaAbierta.set(true);
    this.enfocar('#nombre');
  }

  /** Desactivar pide confirmación; activar es directo. */
  protected alternarActiva(): void {
    const forma = this.filas.elegido();
    // S5: sin fila elegida el botón avisa el motivo; mientras cambia sigue apagado por el disabled transitorio.
    if (!forma) {
      this.motivo.mostrar('alternar');
      return;
    }
    if (this.cambiando()) return;
    this.motivo.limpiar();
    if (forma.activa) this.aDesactivar.set(forma);
    else this.cambiarActiva(forma, true);
  }

  protected confirmarDesactivar(): void {
    const forma = this.aDesactivar();
    this.aDesactivar.set(null);
    if (forma) this.cambiarActiva(forma, false);
  }

  /** Manda la forma como está guardada, con el estado nuevo (el PUT es del recurso entero). */
  private cambiarActiva(forma: FormaPago, activa: boolean): void {
    const { nombre, medio, recargoPorcentaje, descuentoPorcentaje } = forma;
    this.aviso.set(null);
    this.limpiarErrores();
    this.cambiando.set(true);
    this.service.actualizar(forma.id, { nombre, medio, recargoPorcentaje, descuentoPorcentaje, activa }).subscribe({
      next: (cambiada) => {
        this.formas.update((lista) => [...lista.filter((f) => f.id !== cambiada.id), cambiada].sort(porOrden));
        // Si es la que está en el formulario, guardar la edición no le vuelve a cambiar el estado.
        if (this.editando()?.id === cambiada.id) this.editando.set(cambiada);
        this.cambiando.set(false);
        // Desde la ficha del celular, activar o desactivar termina la tarea: se vuelve a la lista.
        this.cerrarFicha();
      },
      error: (err: unknown) => {
        this.cambiando.set(false);
        this.error.set(mensajeDe(err, 'No se pudo cambiar el estado de la forma de pago.'));
      },
    });
  }

  /** La forma de cuenta corriente que todavía no se activó: hasta entonces no se ofrece al cobrar. */
  protected esperaActivacion(forma: FormaPago): boolean {
    return forma.medio === 'CUENTA_CORRIENTE' && !forma.activa;
  }

  protected recargoTexto(forma: FormaPago): string {
    return forma.recargoPorcentaje === 0 ? 'Sin recargo' : `${porcentaje.format(forma.recargoPorcentaje)}\u00a0%`;
  }

  protected descuentoTexto(forma: FormaPago): string {
    return forma.descuentoPorcentaje === 0
      ? 'Sin descuento'
      : `${porcentaje.format(forma.descuentoPorcentaje)}\u00a0%`;
  }

  protected invalido(campo: 'nombre' | 'recargoPorcentaje' | 'descuentoPorcentaje'): boolean {
    const control = this.form.controls[campo];
    if (campo === 'descuentoPorcentaje' && control.touched && this.form.hasError('recargoYDescuento')) return true;
    // Sin escribir nada, avisa recién al intentar guardar, que lo marca modificado (bugs de la guía, S14).
    return control.touched && control.dirty && control.invalid;
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
    this.form.reset({ nombre: '', medio: 'EFECTIVO', recargoPorcentaje: 0, descuentoPorcentaje: 0 });
  }
}

/** Una forma lleva recargo o descuento, no los dos (lo mismo valida el backend con 422). */
function recargoODescuento(grupo: AbstractControl): ValidationErrors | null {
  const recargo = Number(grupo.get('recargoPorcentaje')?.value ?? 0);
  const descuento = Number(grupo.get('descuentoPorcentaje')?.value ?? 0);
  return recargo > 0 && descuento > 0 ? { recargoYDescuento: true } : null;
}
