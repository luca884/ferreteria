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
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { formatearMonto } from '../../core/formato/monto';
import { FilasElegibles } from '../../shared/filas-elegibles';
import { FormaPago } from '../formas-pago/forma-pago.models';
import { FormasPagoService } from '../formas-pago/formas-pago.service';
import {
  CONDICIONES_IVA,
  CONDICION_IVA_LABELS,
  Cliente,
  ClienteRequest,
  CondicionIva,
  TIPOS_DOCUMENTO,
  TipoDocumento,
  documentoLegible,
} from './cliente.models';
import { ClientesService } from './clientes.service';

const TAMANO_PAGINA = 20;

/** El tope del límite de cuenta corriente: el mismo que acepta el servidor (13 enteros y 2 decimales). */
const LIMITE_MAXIMO = 9_999_999_999_999.99;
const MOTIVO_NO_DESACTIVAR = 'No se puede desactivar: el cliente tiene saldo en cuenta corriente.';
/** CU-11 (D4): los mismos mensajes que da el servidor cuando a un RI, Monotributista o Exento le falta el CUIT o el domicilio. */
const FALTA_CUIT = 'Falta el CUIT: es obligatorio para Responsable Inscripto, Monotributista o Exento.';
const NO_ES_CUIT = 'Para Responsable Inscripto, Monotributista o Exento el documento tiene que ser el CUIT.';
const FALTA_DOMICILIO = 'Falta el domicilio: es obligatorio para Responsable Inscripto, Monotributista o Exento.';

/** El texto sin espacios de los bordes; undefined si queda vacío, así el pedido no manda campos en blanco. */
const sinVacios = (texto: string): string | undefined => texto.trim() || undefined;

/**
 * Clientes (CL-006). El Vendedor y el Dueño ven la lista, buscan por razón social o documento, dan de alta y editan; solo
 * el Dueño desactiva y reactiva. Un cliente desactivado no se puede elegir en el mostrador. Contenedor: habla con el
 * servicio.
 */
@Component({
  selector: 'app-clientes',
  imports: [ReactiveFormsModule, RouterLink, BarraAcciones],
  templateUrl: './clientes.html',
  styleUrl: './clientes.css',
})
export class Clientes implements OnInit {
  private readonly service = inject(ClientesService);
  private readonly formasPago = inject(FormasPagoService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);

  protected readonly tiposDocumento = TIPOS_DOCUMENTO;
  protected readonly condiciones = CONDICIONES_IVA;
  protected readonly etiquetaCondicion = CONDICION_IVA_LABELS;
  protected readonly documentoLegible = documentoLegible;
  protected readonly monto = formatearMonto;

  protected readonly clientes = signal<Cliente[]>([]);
  /** Fila elegida en la grilla: la barra actúa sobre ella; Enter edita. */
  protected readonly filas = new FilasElegibles(this.clientes, () => true, (c) => this.editar(c));
  protected readonly pagina = signal({ page: 0, totalPages: 1, totalElements: 0 });
  protected readonly cargando = signal(true);
  protected readonly cargaFallida = signal(false);

  /** Lo escrito en los filtros; se aplica al buscar. */
  protected readonly q = signal('');
  protected readonly soloActivos = signal(false);
  /** El filtro que se aplicó la última vez: las páginas siguientes lo conservan. */
  private readonly aplicado = signal({ q: '', soloActivos: false });
  protected readonly filtrada = signal(false);

  protected readonly aviso = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly erroresCampos = signal<string[]>([]);
  protected readonly errorTipo = signal<string | null>(null);
  protected readonly errorNumero = signal<string | null>(null);
  protected readonly errorDomicilio = signal<string | null>(null);
  protected readonly guardando = signal(false);
  protected readonly cambiando = signal(false);
  /** El cliente que se está editando; null mientras se da de alta uno nuevo. */
  protected readonly editando = signal<Cliente | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    razonSocial: ['', [Validators.required, Validators.maxLength(120), Validators.pattern(/\S/)]],
    tipoDocumento: ['' as TipoDocumento | ''],
    numeroDocumento: [''],
    condicionIva: ['CONSUMIDOR_FINAL' as CondicionIva],
    domicilio: ['', Validators.maxLength(200)],
    telefono: ['', Validators.maxLength(30)],
    whatsapp: ['', Validators.maxLength(30)],
    email: ['', [Validators.email, Validators.maxLength(120)]],
    // Cuenta corriente (CC-018, solo el Dueño): «Habilitar» + el límite en $; 0 es habilitada sin crédito. Se guardan aparte
    // del cliente, por el endpoint propio del límite.
    cuentaHabilitada: [false],
    limiteCuenta: [{ value: null as number | null, disabled: true }],
  });

  /** Por qué no se puede desactivar al cliente elegido (tiene saldo, a favor o en contra), o null si se puede. */
  protected readonly motivoNoDesactivar = computed(() => {
    const cliente = this.filas.elegido();
    return cliente?.activo && cliente.saldo !== undefined && cliente.saldo !== 0 ? MOTIVO_NO_DESACTIVAR : null;
  });
  protected readonly errorLimite = signal<string | null>(null);

  private readonly condicionElegida = toSignal(this.form.controls.condicionIva.valueChanges, {
    initialValue: this.form.controls.condicionIva.value,
  });
  /** CU-11 (D4): Responsable inscripto, Monotributista y Exento llevan CUIT y domicilio; Consumidor final, no. */
  protected readonly pideDatosFiscales = computed(() => this.condicionElegida() !== 'CONSUMIDOR_FINAL');

  /** Las formas de pago activas, para avisar si falta la de cuenta corriente. null mientras no se leyeron (o no se pudo). */
  private readonly formasActivas = signal<readonly FormaPago[] | null>(null);
  private readonly cuentaTildada = toSignal(this.form.controls.cuentaHabilitada.valueChanges, { initialValue: false });
  /**
   * S6: «Habilitar» no alcanza si la forma de pago «Cuenta corriente» está desactivada: el cobro no la ofrece. Solo se avisa
   * con las formas leídas; si no se pudieron leer no se sabe, y no se asusta al Dueño.
   */
  protected readonly formaCuentaDesactivada = computed(() => {
    const formas = this.formasActivas();
    return formas !== null && this.cuentaTildada() && !formas.some((f) => f.medio === 'CUENTA_CORRIENTE');
  });

  constructor() {
    // El límite solo se escribe con «Habilitar» tildado.
    this.form.controls.cuentaHabilitada.valueChanges
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((habilitada) => {
        const limite = this.form.controls.limiteCuenta;
        if (habilitada) limite.enable();
        else limite.disable();
      });
    // Al escribir se van los avisos del servidor y del documento: ya no corresponden a lo que hay en el formulario.
    this.form.valueChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(() => this.limpiarErrores());
  }

  ngOnInit(): void {
    this.cargar(0);
    // Solo el Dueño edita la cuenta corriente: el Vendedor no necesita las formas.
    if (this.auth.esDueno()) {
      this.formasPago.listar(true).subscribe({ next: (formas) => this.formasActivas.set(formas), error: () => undefined });
    }
  }

  /** Carga una página con el filtro aplicado; también la usa Reintentar después de una carga fallida. */
  protected cargar(page: number): void {
    this.cargando.set(true);
    this.cargaFallida.set(false);
    const { q, soloActivos } = this.aplicado();
    this.service.buscar({ q, soloActivos, page }).subscribe({
      next: (respuesta) => {
        this.clientes.set(respuesta.items);
        this.pagina.set({
          page: respuesta.page,
          totalPages: respuesta.totalPages,
          totalElements: respuesta.totalElements,
        });
        this.cargando.set(false);
      },
      error: () => {
        this.clientes.set([]);
        this.cargando.set(false);
        this.cargaFallida.set(true);
      },
    });
  }

  protected buscar(evento?: Event): void {
    evento?.preventDefault();
    this.aplicado.set({ q: this.q().trim(), soloActivos: this.soloActivos() });
    this.filtrada.set(this.aplicado().q !== '' || this.aplicado().soloActivos);
    this.cargar(0);
  }

  protected limpiar(): void {
    this.q.set('');
    this.soloActivos.set(false);
    this.buscar();
  }

  protected guardar(): void {
    this.limpiarErrores();
    const { tipoDocumento, numeroDocumento, domicilio } = this.form.getRawValue();
    const conTipo = tipoDocumento !== '';
    const conNumero = numeroDocumento.trim() !== '';
    if (conNumero && !conTipo) this.errorTipo.set('Elegí el tipo de documento.');
    if (conTipo && !conNumero) this.errorNumero.set('Falta el número de documento.');
    if (this.pideDatosFiscales()) {
      // Lo mismo que valida el servidor (CU-11, D4), para avisar en cada campo antes de mandar.
      if (!conTipo && !conNumero) this.errorNumero.set(FALTA_CUIT);
      else if (conTipo && tipoDocumento !== 'CUIT') this.errorTipo.set(NO_ES_CUIT);
      if (domicilio.trim() === '') this.errorDomicilio.set(FALTA_DOMICILIO);
    }
    if (this.auth.esDueno()) this.errorLimite.set(this.validarLimite());
    if (this.form.invalid || this.errorTipo() || this.errorNumero() || this.errorDomicilio() || this.errorLimite()) {
      this.form.markAllAsTouched();
      this.enfocar('form.alta [aria-invalid="true"]');
      return;
    }
    const request = this.aRequest();
    const cliente = this.editando();
    const limite = this.limiteElegido();
    // Solo el Dueño toca el límite, y solo si cambió respecto de lo que tenía el cliente (null = sin cuenta corriente).
    const cambiaLimite = this.auth.esDueno() && limite !== (cliente?.limiteCuentaCorriente ?? null);
    this.aviso.set(null);
    this.guardando.set(true);
    const llamada = cliente === null ? this.service.crear(request) : this.service.actualizar(cliente.id, request);
    llamada.subscribe({
      next: (guardado) => (cambiaLimite ? this.guardarLimite(guardado, limite) : this.terminarGuardado(guardado)),
      error: (err: unknown) => {
        this.guardando.set(false);
        const porCampo = erroresDeCampos(err);
        if (porCampo.length > 0) this.erroresCampos.set(porCampo);
        else this.error.set(mensajeDe(err, 'No se pudo guardar el cliente.'));
      },
    });
  }

  /** Después de guardar el cliente: el límite va por su endpoint. Si falla, el cliente ya quedó guardado y se avisa. */
  private guardarLimite(guardado: Cliente, limite: number | null): void {
    this.service.cambiarLimite(guardado.id, limite).subscribe({
      next: () => this.terminarGuardado(guardado),
      error: (err: unknown) => {
        this.vaciarFormulario();
        this.guardando.set(false);
        const detalle = erroresDeCampos(err).join(' ') || mensajeDe(err, 'Probá de nuevo.');
        this.error.set(
          `Se guardó «${guardado.razonSocial}», pero no se pudo cambiar el límite de la cuenta corriente. ${detalle}`,
        );
        this.cargar(this.pagina().page);
      },
    });
  }

  private terminarGuardado(guardado: Cliente): void {
    this.vaciarFormulario();
    this.guardando.set(false);
    this.aviso.set(`Se guardó «${guardado.razonSocial}».`);
    this.cargar(this.pagina().page);
  }

  /** El límite que quedó en el formulario: null si «Habilitar» está destildado (cuenta corriente deshabilitada). */
  private limiteElegido(): number | null {
    const { cuentaHabilitada, limiteCuenta } = this.form.getRawValue();
    return cuentaHabilitada ? limiteCuenta : null;
  }

  /** Lo mismo que valida el servidor, para avisar en el campo. Null si está bien o si no se habilita la cuenta. */
  private validarLimite(): string | null {
    if (!this.form.controls.cuentaHabilitada.value) return null;
    const limite = this.form.controls.limiteCuenta.value;
    if (limite === null || Number.isNaN(limite)) return 'Escribí el límite de la cuenta corriente (puede ser 0).';
    if (limite < 0) return 'El límite no puede ser negativo.';
    if (limite > LIMITE_MAXIMO) return 'El límite es demasiado grande.';
    if (Math.abs(limite * 100 - Math.round(limite * 100)) > 1e-6) return 'El límite admite hasta 2 decimales.';
    return null;
  }

  /** Editar (barra, Enter o doble clic): precarga el cliente en el formulario de al lado. */
  protected editar(cliente: Cliente | null = this.filas.elegido()): void {
    if (!cliente) return;
    this.aviso.set(null);
    this.filas.elegidoId.set(cliente.id);
    this.editando.set(cliente);
    this.form.reset({
      razonSocial: cliente.razonSocial,
      tipoDocumento: cliente.tipoDocumento ?? '',
      numeroDocumento: cliente.numeroDocumento ?? '',
      condicionIva: cliente.condicionIva,
      domicilio: cliente.domicilio ?? '',
      telefono: cliente.telefono ?? '',
      whatsapp: cliente.whatsapp ?? '',
      email: cliente.email ?? '',
      cuentaHabilitada: cliente.cuentaCorrienteHabilitada,
      limiteCuenta: cliente.limiteCuentaCorriente ?? null,
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
    this.enfocar('#razon-social');
  }

  /** Solo el Dueño: desactiva al elegido, o lo reactiva si ya está desactivado. */
  protected cambiarActivo(): void {
    const cliente = this.filas.elegido();
    // Con saldo no se desactiva (el botón explica por qué); el servidor lo vuelve a controlar.
    if (!cliente || this.cambiando() || this.motivoNoDesactivar()) return;
    this.aviso.set(null);
    this.error.set(null);
    this.cambiando.set(true);
    this.service.cambiarActivo(cliente.id, !cliente.activo).subscribe({
      next: (cambiado) => {
        this.clientes.update((lista) => lista.map((c) => (c.id === cambiado.id ? cambiado : c)));
        this.cambiando.set(false);
      },
      error: (err: unknown) => {
        this.cambiando.set(false);
        this.error.set(mensajeDe(err, 'No se pudo cambiar el estado del cliente.'));
      },
    });
  }

  /** Solo el Dueño: la pantalla de cuenta del cliente elegido (pendientes y cobranza). Sin atajo: Enter sigue editando. */
  protected verCuenta(): void {
    const cliente = this.filas.elegido();
    if (cliente) void this.router.navigate(['/clientes', cliente.id, 'cuenta']);
  }

  /** El saldo del Dueño: lo que debe; «a favor» si es negativo; vacío si el servidor no lo mandó. */
  protected saldoTexto(cliente: Cliente): string {
    if (cliente.saldo === undefined) return '';
    return cliente.saldo < 0 ? `${formatearMonto(-cliente.saldo)} a favor` : formatearMonto(cliente.saldo);
  }

  protected limiteTexto(cliente: Cliente): string {
    if (!cliente.cuentaCorrienteHabilitada || cliente.limiteCuentaCorriente === undefined) return 'Sin habilitar';
    return formatearMonto(cliente.limiteCuentaCorriente);
  }

  protected contacto(cliente: Cliente): string {
    return cliente.telefono ?? cliente.whatsapp ?? cliente.email ?? '';
  }

  protected invalido(campo: 'razonSocial' | 'email'): boolean {
    const control = this.form.controls[campo];
    return control.touched && control.invalid;
  }

  /** Lo que se manda: solo lo cargado y sin espacios de más. El documento lo valida y normaliza el servidor. */
  private aRequest(): ClienteRequest {
    const v = this.form.getRawValue();
    const numero = sinVacios(v.numeroDocumento);
    return {
      razonSocial: v.razonSocial.trim(),
      ...(v.tipoDocumento !== '' && numero ? { tipoDocumento: v.tipoDocumento, numeroDocumento: numero } : {}),
      condicionIva: v.condicionIva,
      ...(sinVacios(v.domicilio) ? { domicilio: sinVacios(v.domicilio) } : {}),
      ...(sinVacios(v.telefono) ? { telefono: sinVacios(v.telefono) } : {}),
      ...(sinVacios(v.whatsapp) ? { whatsapp: sinVacios(v.whatsapp) } : {}),
      ...(sinVacios(v.email) ? { email: sinVacios(v.email) } : {}),
    };
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
    this.errorTipo.set(null);
    this.errorNumero.set(null);
    this.errorDomicilio.set(null);
    this.errorLimite.set(null);
  }

  private vaciarFormulario(): void {
    this.editando.set(null);
    this.form.reset({
      razonSocial: '',
      tipoDocumento: '',
      numeroDocumento: '',
      condicionIva: 'CONSUMIDOR_FINAL',
      domicilio: '',
      telefono: '',
      whatsapp: '',
      email: '',
      cuentaHabilitada: false,
      limiteCuenta: null,
    });
  }
}
