import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, Injector, OnInit, afterNextRender, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import { erroresDeCampos } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { normalizarCuit, validarCuit } from '../../core/formato/cuit';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
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
  protected readonly auth = inject(AuthService);

  protected readonly proveedores = signal<Proveedor[]>([]);
  /** Fila elegida en la grilla: la barra actúa sobre ella. */
  protected readonly elegidoId = signal<number | null>(null);
  protected readonly elegido = computed(
    () => this.proveedores().find((p) => p.id === this.elegidoId()) ?? null,
  );
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
  });

  ngOnInit(): void {
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
    const { nombre, cuit } = this.form.getRawValue();
    const normalizado = normalizarCuit(cuit);
    const request: ProveedorRequest = {
      nombre: nombre.trim(),
      cuit: 'cuit' in normalizado ? normalizado.cuit : null,
      activo: true,
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
  protected editar(proveedor: Proveedor | null = this.elegido()): void {
    if (!proveedor || !this.auth.esDueno()) return;
    this.limpiarMensajes();
    this.elegidoId.set(proveedor.id);
    this.editandoId.set(proveedor.id);
    this.form.reset({ nombre: proveedor.nombre, cuit: proveedor.cuit ?? '' });
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
    this.aDarDeBaja.set(this.elegido());
  }

  /** Clic o foco en una fila: la elige (solo el Dueño, que es quien tiene acciones). */
  protected elegir(proveedor: Proveedor): void {
    if (this.auth.esDueno()) this.elegidoId.set(proveedor.id);
  }

  /** La fila que entra en el orden de Tab: la elegida o, si no hay, la primera. */
  protected esFilaTabulable(proveedor: Proveedor, indice: number): boolean {
    return this.elegido() ? proveedor.id === this.elegidoId() : indice === 0;
  }

  /** ↑/↓ mueven la elección y el foco; Enter edita. */
  protected alTeclearFila(evento: KeyboardEvent, proveedor: Proveedor, indice: number): void {
    if (!this.auth.esDueno()) return;
    if (evento.key === 'Enter') {
      evento.preventDefault();
      this.editar(proveedor);
      return;
    }
    const paso = evento.key === 'ArrowDown' ? 1 : evento.key === 'ArrowUp' ? -1 : 0;
    if (paso === 0) return;
    evento.preventDefault();
    const destino = Math.min(Math.max(indice + paso, 0), this.proveedores().length - 1);
    this.elegidoId.set(this.proveedores()[destino].id);
    this.enfocar(`tbody tr:nth-child(${destino + 1})`);
  }

  protected invalido(campo: 'nombre' | 'cuit'): boolean {
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

  protected reactivar(proveedor: Proveedor | null = this.elegido()): void {
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
    this.form.reset({ nombre: '', cuit: '' });
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

function mensajeDe(err: unknown, porDefecto: string): string {
  return err instanceof HttpErrorResponse && err.error?.message ? err.error.message : porDefecto;
}
