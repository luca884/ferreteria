import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList } from '@angular/cdk/drag-drop';
import { CdkScrollable } from '@angular/cdk/scrolling';
import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { mensajeDe } from '../../core/api/api-error';
import { Avisos } from '../../core/avisos/avisos';
import { DestinoMenu, MenuResuelto } from '../../core/menu/menu.models';
import { MenuService } from '../../core/menu/menu.service';
import { Disposicion, GrupoDisposicion } from '../../layout/menu';
import { MenuActual } from '../../layout/menu-actual';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { ROL_DE_FERRETERIA_LABELS, RolDeFerreteria, Usuario } from '../usuarios/usuario.models';
import { UsuariosService } from '../usuarios/usuarios.service';
import {
  ERROR_LIMITE, MAX_GRUPOS, TONOS, agregarGrupo, borrarGrupo, editarGrupo, esOriginal, moverOpcion,
  opcionesPara, prepararEditor, prepararGuardado, reordenarGrupos, sePuedeOcultar, validarGrupos,
} from './editor-menu';

type Objetivo = { rol: RolDeFerreteria; usuario?: Usuario };
type Pregunta = { titulo: string; mensaje: string; boton: string; confirmar: () => void };
type OpcionSoltada = Pick<CdkDragDrop<unknown>, 'isPointerOverContainer' | 'currentIndex'> & {
  item: { data: string };
  previousContainer: { data: string | null };
  container: { data: string | null };
};

const ROLES: readonly Objetivo[] = [{ rol: 'VENDEDOR' }, { rol: 'DUENO' }];
const rolConArticulo = (rol: RolDeFerreteria) => rol === 'VENDEDOR' ? 'de los Vendedores' : 'del Dueño';
const destinoDe = ({ rol, usuario }: Objetivo): DestinoMenu => usuario
  ? { ambito: 'usuarios', id: usuario.id }
  : { ambito: 'roles', rol };

@Component({
  selector: 'app-configuracion-menu',
  imports: [BarraAcciones, Confirmacion, CdkDrag, CdkDragHandle, CdkDropList, CdkScrollable, NgTemplateOutlet],
  templateUrl: './configuracion-menu.html',
  styleUrl: './configuracion-menu.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaginaConfiguracionMenu implements OnInit {
  private readonly menus = inject(MenuService);
  private readonly usuarios = inject(UsuariosService);
  private readonly menuActual = inject(MenuActual);
  private readonly avisos = inject(Avisos);
  private readonly destruir = inject(DestroyRef);
  private readonly listaUsuarios = signal<readonly Usuario[]>([]);
  private readonly inicial = signal<Disposicion | null>(null);
  private readonly origen = signal<MenuResuelto['origen']>('ORIGINAL');
  private readonly enviando = signal(false);

  protected readonly objetivo = signal<Objetivo>(ROLES[0]);
  protected readonly objetivos = computed(() => [
    ...ROLES, ...this.listaUsuarios().map((usuario): Objetivo => ({ rol: usuario.rol, usuario })),
  ]);
  protected readonly borrador = signal<Disposicion | null>(null);
  protected readonly cargando = signal(false);
  protected readonly pregunta = signal<Pregunta | null>(null);
  protected readonly bloqueado = computed(() => this.cargando() || this.enviando() || !!this.pregunta());
  protected readonly errorUsuarios = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
  protected readonly tonos = TONOS;
  protected readonly esOriginal = esOriginal;
  protected readonly sePuedeOcultar = sePuedeOcultar;

  protected readonly origenTexto = computed(() => {
    const { rol, usuario } = this.objetivo();
    if (this.origen() === 'ORIGINAL') return 'Usa el menú original';
    if (this.origen() === 'USUARIO' && usuario) return `Menú propio de ${usuario.nombre}`;
    return `${usuario ? 'Usa el menú' : 'Menú propio'} ${rolConArticulo(rol)}`;
  });
  protected readonly rotulos = computed(() => new Map(opcionesPara(this.objetivo().rol).map((o) => [o.clave, o.rotulo])));
  protected readonly listas = computed(() => [
    this.listaDe(null), ...(this.borrador()?.grupos.map((g) => this.listaDe(g.id)) ?? []),
  ]);
  protected readonly puedeAgregar = computed(() => (this.borrador()?.grupos.length ?? MAX_GRUPOS) < MAX_GRUPOS);
  protected readonly errorLimite = ERROR_LIMITE;
  protected readonly errorGrupos = computed(() => {
    const d = this.borrador();
    return d ? validarGrupos(d) : null;
  });
  protected readonly hayCambios = computed(() => JSON.stringify(this.borrador()) !== JSON.stringify(this.inicial()));
  protected readonly puedeGuardar = computed(() => {
    const d = this.borrador();
    return !!d && !this.errorGrupos() && this.hayCambios() &&
      JSON.stringify(prepararGuardado(d)) !== JSON.stringify(this.inicial());
  });

  ngOnInit(): void {
    this.cargarUsuarios();
    this.cargar();
  }

  protected cargarUsuarios(): void {
    this.errorUsuarios.set(null);
    this.usuarios.listar(true).pipe(takeUntilDestroyed(this.destruir)).subscribe({
      next: (usuarios) => this.listaUsuarios.set(usuarios),
      error: (err: unknown) => this.errorUsuarios.set(mensajeDe(err, 'No se pudo cargar la lista de usuarios.')),
    });
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.error.set(null);
    this.inicial.set(null);
    this.borrador.set(null);
    this.menus.leer(destinoDe(this.objetivo())).pipe(takeUntilDestroyed(this.destruir)).subscribe({
      next: (menu) => {
        this.aplicar(menu);
        this.cargando.set(false);
      },
      error: (err: unknown) => {
        this.error.set(mensajeDe(err, 'No se pudo cargar el menú.'));
        this.cargando.set(false);
      },
    });
  }

  protected claveDe({ rol, usuario }: Objetivo): string {
    return usuario ? `usuario-${usuario.id}` : rol;
  }

  protected rotuloDe({ rol, usuario }: Objetivo): string {
    if (!usuario) return rol === 'VENDEDOR' ? 'Vendedores' : 'Dueño';
    return `${usuario.nombre} — ${ROL_DE_FERRETERIA_LABELS[rol]}${usuario.activo ? '' : ' (inactivo)'}`;
  }

  protected cambiarObjetivo(select: HTMLSelectElement): void {
    const siguiente = this.objetivos().find((o) => this.claveDe(o) === select.value);
    const actual = this.claveDe(this.objetivo());
    select.value = actual;
    if (!siguiente || this.claveDe(siguiente) === actual || this.bloqueado()) return;
    const confirmar = () => {
      this.objetivo.set(siguiente);
      this.aviso.set(null);
      this.cargar();
    };
    if (this.hayCambios()) {
      this.pregunta.set({ titulo: '¿Descartar los cambios?', mensaje: 'Todavía no guardaste los cambios de este menú.',
        boton: 'Descartar', confirmar });
    } else {
      confirmar();
    }
  }

  protected guardar(): void {
    const d = this.borrador();
    if (!d || this.bloqueado() || !this.puedeGuardar()) return;
    this.iniciarEnvio();
    this.menus.guardar(destinoDe(this.objetivo()), prepararGuardado(d)).pipe(takeUntilDestroyed(this.destruir)).subscribe({
      next: (menu) => {
        this.aplicar(menu);
        this.finExitoso('Guardamos el menú.');
      },
      error: (err: unknown) => this.finFallido(err, 'No se pudo guardar el menú.'),
    });
  }

  protected descartar(): void {
    if (this.bloqueado()) return;
    this.borrador.set(this.inicial());
    this.error.set(null);
    this.aviso.set(null);
  }

  protected pedirRestaurar(): void {
    if (this.bloqueado() || !this.inicial()) return;
    this.pregunta.set({
      titulo: '¿Restaurar el menú?', boton: 'Restaurar',
      mensaje: this.objetivo().usuario
        ? 'Se borra el ajuste de este usuario. Va a usar el menú de su rol o el menú original.'
        : 'Se borra la base de este rol. El rol vuelve a usar el menú original.',
      confirmar: () => {
        this.iniciarEnvio();
        this.menus.restaurar(destinoDe(this.objetivo())).pipe(takeUntilDestroyed(this.destruir)).subscribe({
          next: () => {
            this.cargar();
            this.finExitoso('Volvió el menú original.');
          },
          error: (err: unknown) => this.finFallido(err, 'No se pudo restaurar el menú.'),
        });
      },
    });
  }

  protected confirmar(): void {
    const pregunta = this.pregunta();
    this.pregunta.set(null);
    pregunta?.confirmar();
  }

  protected agregar(): void {
    this.editar(agregarGrupo);
  }

  protected renombrar(id: string, rotulo: string): void {
    this.editar((d) => editarGrupo(d, id, { rotulo }));
  }

  protected cambiarTono(id: string, valor: string): void {
    const tono = TONOS.find((t) => t.valor === valor);
    if (tono) this.editar((d) => editarGrupo(d, id, { tono: tono.valor }));
  }

  protected pedirBorrar(grupo: GrupoDisposicion): void {
    if (this.bloqueado() || esOriginal(grupo.id)) return;
    this.pregunta.set({
      titulo: grupo.rotulo.trim() ? `¿Borrar «${grupo.rotulo.trim()}»?` : '¿Borrar este grupo?',
      mensaje: 'Las opciones del grupo vuelven a sus grupos originales.', boton: 'Borrar',
      confirmar: () => this.editar((d) => borrarGrupo(d, grupo.id, this.objetivo().rol)),
    });
  }

  protected listaDe(id: string | null): string {
    // El prefijo de un grupo no puede colisionar con el id de la lista de ocultas.
    return id === null ? 'lista-ocultas' : `opciones-${id}`;
  }

  protected readonly entrarEnOcultas = (drag: CdkDrag<string>) => sePuedeOcultar(drag.data);

  soltarOpcion(evento: OpcionSoltada): void {
    if (!evento.isPointerOverContainer) return;
    this.editar((d) => moverOpcion(d, evento.item.data, evento.previousContainer.data,
      evento.container.data, evento.currentIndex));
  }

  soltarGrupo(evento: CdkDragDrop<unknown>): void {
    if (!evento.isPointerOverContainer) return;
    this.editar((d) => reordenarGrupos(d, evento.previousIndex, evento.currentIndex));
  }

  private editar(operacion: (d: Disposicion) => Disposicion): void {
    const d = this.borrador();
    if (!d || this.bloqueado()) return;
    this.borrador.set(operacion(d));
    this.aviso.set(null);
    this.error.set(null);
  }

  private aplicar({ origen, disposicion }: MenuResuelto): void {
    const d = prepararEditor(disposicion, this.objetivo().rol);
    this.origen.set(origen);
    this.inicial.set(d);
    this.borrador.set(d);
  }

  private iniciarEnvio(): void {
    this.enviando.set(true);
    this.error.set(null);
    this.aviso.set(null);
  }

  private finExitoso(texto: string): void {
    this.menuActual.recargar();
    // El destino del aviso es esta misma página, sin navegación.
    this.avisos.dejar(texto);
    this.aviso.set(this.avisos.tomar()?.texto ?? null);
    this.enviando.set(false);
  }

  private finFallido(err: unknown, porDefecto: string): void {
    this.error.set(mensajeDe(err, porDefecto));
    this.enviando.set(false);
  }
}
