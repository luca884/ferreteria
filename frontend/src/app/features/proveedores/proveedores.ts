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
import { ActivatedRoute } from '@angular/router';
import { Observable } from 'rxjs';
import { erroresDeCampos } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { normalizarCuit, validarCuit } from '../../core/formato/cuit';
import { contiene } from '../../core/formato/texto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { FilasElegibles } from '../../shared/filas-elegibles';
import { Proveedor, ProveedorRequest } from './proveedor.models';
import { ProveedoresService } from './proveedores.service';

const porNombre = (a: Proveedor, b: Proveedor) => a.nombre.localeCompare(b.nombre);
/** Igual que el backend (utf8mb4_0900_ai_ci): sin distinguir mayúsculas ni tildes. */
const mismoNombre = (a: string, b: string) => a.trim().localeCompare(b.trim(), 'es', { sensitivity: 'base' }) === 0;

@Component({
  selector: 'app-proveedores',
  imports: [ReactiveFormsModule, Confirmacion, BarraAcciones],
  templateUrl: './proveedores.html',
  styleUrl: './proveedores.css',
})
export class Proveedores implements OnInit {
  private readonly service = inject(ProveedoresService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly auth = inject(AuthService);

  protected readonly proveedores = signal<Proveedor[]>([]);
  /** Lo escrito en «Buscar proveedor» (o el `?q=` del buscador del menú, bloque 2 S3): filtra por nombre o contacto. */
  protected readonly filtro = signal('');
  protected readonly visibles = computed(() => {
    const filtro = this.filtro().trim();
    return filtro === ''
      ? this.proveedores()
      : this.proveedores().filter((p) => contiene(p.nombre, filtro) || contiene(p.contacto ?? '', filtro));
  });
  /** Fila elegida en la grilla (solo el Dueño, que es quien tiene acciones): la barra actúa sobre ella; Enter edita. */
  protected readonly filas = new FilasElegibles(this.visibles, () => this.auth.esDueno(), (p) => this.editar(p));
  protected readonly cargando = signal(true);
  /** La lista no se pudo traer: se avisa con Reintentar en lugar de «Todavía no hay proveedores». */
  protected readonly cargaFallida = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
  /** Mensajes por campo de un 400 de validación del backend; si hay, se muestran en vez de `error`. */
  protected readonly erroresCampos = signal<string[]>([]);
  protected readonly guardando = signal(false);
  /** Id del proveedor que se está editando; null mientras se da de alta uno nuevo. */
  protected readonly editandoId = signal<number | null>(null);
  protected readonly incluirInactivos = signal(false);
  protected readonly aDarDeBaja = signal<Proveedor | null>(null);
  /** El alta chocó con un proveedor dado de baja: se ofrece reactivarlo. */
  protected readonly reactivable = signal<Proveedor | null>(null);

  protected readonly deBaja = computed(() => this.proveedores().filter((p) => !p.activo).length);
  protected readonly activos = computed(() => this.proveedores().length - this.deBaja());

  protected readonly form = inject(FormBuilder).nonNullable.group({
    nombre: ['', [Validators.required, Validators.maxLength(100)]],
    cuit: ['', [validarCuit]],
    contacto: ['', [Validators.maxLength(100)]],
    telefono: ['', [Validators.maxLength(30)]],
    whatsapp: ['', [Validators.maxLength(30)]],
    email: ['', [Validators.email, Validators.maxLength(120)]],
  });

  ngOnInit(): void {
    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => this.filtro.set(params.get('q')?.trim() ?? ''));
    this.cargar();
  }

  protected cambiarInactivos(incluir: boolean): void {
    this.incluirInactivos.set(incluir);
    this.cargar();
  }

  /** Al salir del CUIT le pone los guiones si es válido. */
  protected formatearCuit(): void {
    const resultado = normalizarCuit(this.form.controls.cuit.value);
    if ('cuit' in resultado && resultado.cuit) this.form.controls.cuit.setValue(resultado.cuit);
  }

  protected guardar(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.enfocar('form.alta [aria-invalid="true"]');
      return;
    }
    const { nombre, cuit, contacto, telefono, whatsapp, email } = this.form.getRawValue();
    const normalizado = normalizarCuit(cuit);
    const request: ProveedorRequest = {
      nombre: nombre.trim(),
      cuit: 'cuit' in normalizado ? normalizado.cuit : null,
      activo: true,
      contacto: textoOpcional(contacto),
      telefono: textoOpcional(telefono),
      whatsapp: textoOpcional(whatsapp),
      email: textoOpcional(email),
    };
    const id = this.editandoId();
    const llamada: Observable<Proveedor> =
      id === null ? this.service.crear(request) : this.service.actualizar(id, request);

    this.guardando.set(true);
    this.limpiarMensajes();
    llamada.subscribe({
      next: (proveedor) => {
        this.ponerEnLista(proveedor);
        this.vaciarFormulario();
        this.guardando.set(false);
        // El formulario estuvo inerte mientras guardaba: queda listo para el próximo alta, como con Nuevo.
        this.enfocar('#nombre');
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        this.erroresCampos.set(erroresDeCampos(err));
        this.error.set(mensajeDe(err, 'No se pudo guardar el proveedor.'));
        if (id === null && err instanceof HttpErrorResponse && err.status === 409) {
          this.buscarReactivable(request.nombre);
        }
      },
    });
  }

  /** Editar (barra, Enter o doble clic): precarga el proveedor en el formulario de al lado. */
  protected editar(proveedor: Proveedor | null = this.filas.elegido()): void {
    if (!proveedor || !this.auth.esDueno()) return;
    this.limpiarMensajes();
    this.filas.elegidoId.set(proveedor.id);
    this.editandoId.set(proveedor.id);
    this.form.reset({
      nombre: proveedor.nombre,
      cuit: proveedor.cuit ?? '',
      contacto: proveedor.contacto ?? '',
      telefono: proveedor.telefono ?? '',
      whatsapp: proveedor.whatsapp ?? '',
      email: proveedor.email ?? '',
    });
  }

  protected cancelar(): void {
    this.limpiarMensajes();
    this.vaciarFormulario();
  }

  /** Nuevo: el formulario vuelve al alta y queda listo para escribir. */
  protected nuevo(): void {
    this.cancelar();
    this.enfocar('#nombre');
  }

  protected pedirBaja(): void {
    this.aDarDeBaja.set(this.filas.elegido());
  }

  /** «Ana · 223 555-1234», lo que haya de los dos; «—» si no hay ninguno. */
  protected contactoDe(proveedor: Proveedor): string {
    const partes = [proveedor.contacto, proveedor.telefono].filter((p): p is string => !!p);
    return partes.length > 0 ? partes.join(' · ') : '—';
  }

  protected invalido(campo: 'nombre' | 'cuit' | 'contacto' | 'telefono' | 'whatsapp' | 'email'): boolean {
    const control = this.form.controls[campo];
    return control.touched && control.invalid;
  }

  protected desactivar(): void {
    const proveedor = this.aDarDeBaja();
    this.aDarDeBaja.set(null);
    if (!proveedor) return;
    this.limpiarMensajes();
    this.service.desactivar(proveedor.id).subscribe({
      next: () => {
        if (this.incluirInactivos()) this.ponerEnLista({ ...proveedor, activo: false });
        else this.proveedores.update((lista) => lista.filter((p) => p.id !== proveedor.id));
        if (this.editandoId() === proveedor.id) this.vaciarFormulario();
      },
      error: (err: unknown) => {
        this.error.set(mensajeDe(err, 'No se pudo dar de baja el proveedor.'));
      },
    });
  }

  protected reactivar(proveedor: Proveedor | null = this.filas.elegido()): void {
    if (!proveedor) return;
    this.limpiarMensajes();
    this.service.reactivar(proveedor.id).subscribe({
      next: (reactivado) => {
        this.ponerEnLista(reactivado);
        this.vaciarFormulario();
        this.aviso.set(`Se reactivó «${reactivado.nombre}».`);
      },
      error: (err: unknown) => {
        this.error.set(mensajeDe(err, 'No se pudo reactivar el proveedor.'));
      },
    });
  }

  /** Después de un 409 en el alta: si el repetido está dado de baja, se ofrece reactivarlo. */
  private buscarReactivable(nombre: string): void {
    this.service.listar(true).subscribe({
      next: (todos) => {
        this.reactivable.set(todos.find((p) => !p.activo && mismoNombre(p.nombre, nombre)) ?? null);
      },
      error: () => this.reactivable.set(null),
    });
  }

  /** Después de pintar, enfoca el primer elemento que coincida. */
  private enfocar(selector: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }

  private ponerEnLista(proveedor: Proveedor): void {
    this.proveedores.update((lista) =>
      [...lista.filter((p) => p.id !== proveedor.id), proveedor].sort(porNombre),
    );
  }

  private limpiarMensajes(): void {
    this.error.set(null);
    this.aviso.set(null);
    this.erroresCampos.set([]);
    this.reactivable.set(null);
  }

  private vaciarFormulario(): void {
    this.editandoId.set(null);
    this.form.reset({ nombre: '', cuit: '', contacto: '', telefono: '', whatsapp: '', email: '' });
  }

  /** Carga la lista; también la usa Reintentar después de una carga fallida. */
  protected cargar(): void {
    this.cargando.set(true);
    this.cargaFallida.set(false);
    this.service.listar(this.incluirInactivos()).subscribe({
      next: (lista) => {
        this.proveedores.set(lista);
        this.cargando.set(false);
      },
      error: () => {
        // La lista anterior no corresponde a la casilla de dados de baja que se acaba de cambiar.
        this.proveedores.set([]);
        this.cargando.set(false);
        this.cargaFallida.set(true);
      },
    });
  }
}

function textoOpcional(valor: string): string | null {
  const limpio = valor.trim();
  return limpio === '' ? null : limpio;
}

function mensajeDe(err: unknown, porDefecto: string): string {
  return err instanceof HttpErrorResponse && err.error?.message ? err.error.message : porDefecto;
}
