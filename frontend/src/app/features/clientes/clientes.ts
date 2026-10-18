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

/** El texto sin espacios de los bordes; undefined si queda vacío, así el pedido no manda campos en blanco. */
const sinVacios = (texto: string): string | undefined => texto.trim() || undefined;

/**
 * Clientes (CL-006). El Vendedor y el Dueño ven la lista, buscan por razón social o documento, dan de alta y editan; solo
 * el Dueño desactiva y reactiva. Un cliente desactivado no se puede elegir en el mostrador. Contenedor: habla con el
 * servicio.
 */
@Component({
  selector: 'app-clientes',
  imports: [ReactiveFormsModule, BarraAcciones],
  templateUrl: './clientes.html',
  styleUrl: './clientes.css',
})
export class Clientes implements OnInit {
  private readonly service = inject(ClientesService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  protected readonly auth = inject(AuthService);

  protected readonly tiposDocumento = TIPOS_DOCUMENTO;
  protected readonly condiciones = CONDICIONES_IVA;
  protected readonly etiquetaCondicion = CONDICION_IVA_LABELS;
  protected readonly documentoLegible = documentoLegible;

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
  });

  constructor() {
    // Al escribir se van los avisos del servidor y del documento: ya no corresponden a lo que hay en el formulario.
    this.form.valueChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(() => this.limpiarErrores());
  }

  ngOnInit(): void {
    this.cargar(0);
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
    const { tipoDocumento, numeroDocumento } = this.form.getRawValue();
    const conTipo = tipoDocumento !== '';
    const conNumero = numeroDocumento.trim() !== '';
    if (conNumero && !conTipo) this.errorTipo.set('Elegí el tipo de documento.');
    if (conTipo && !conNumero) this.errorNumero.set('Falta el número de documento.');
    if (this.form.invalid || this.errorTipo() || this.errorNumero()) {
      this.form.markAllAsTouched();
      this.enfocar('form.alta [aria-invalid="true"]');
      return;
    }
    const request = this.aRequest();
    const cliente = this.editando();
    this.aviso.set(null);
    this.guardando.set(true);
    const llamada = cliente === null ? this.service.crear(request) : this.service.actualizar(cliente.id, request);
    llamada.subscribe({
      next: (guardado) => {
        this.vaciarFormulario();
        this.guardando.set(false);
        this.aviso.set(`Se guardó «${guardado.razonSocial}».`);
        this.cargar(this.pagina().page);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        const porCampo = erroresDeCampos(err);
        if (porCampo.length > 0) this.erroresCampos.set(porCampo);
        else this.error.set(mensajeDe(err, 'No se pudo guardar el cliente.'));
      },
    });
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
    if (!cliente || this.cambiando()) return;
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
    });
  }
}
