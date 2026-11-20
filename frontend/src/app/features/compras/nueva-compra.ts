import { DecimalPipe } from '@angular/common';
import { Component, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { erroresPorCampo, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { centavosDeImporte, textoDeImporte, textoDePlata } from '../../core/formato/importe';
import { MontoPipe, formatearCosto } from '../../core/formato/monto';
import { cantidadConUnidad, porUnidad } from '../../core/formato/unidad';
import { BarraAcciones } from '../../shared/barra-acciones';
import { CampoMoneda } from '../../shared/campo-moneda';
import { esCelular } from '../../shared/es-celular';
import { ImporteInput } from '../../shared/importe-input';
import { MotivoDeAccion, Motivos, ariaDe } from '../../shared/motivo-de-accion';
import { SelectorArchivo } from '../../shared/selector-archivo';
import { Articulo } from '../articulos/articulo.models';
import { ArticulosService } from '../articulos/articulos.service';
import { hoyEnBuenosAires } from '../clientes/cuenta/cobranza.models';
import { GENERAR_CLAVE } from '../mostrador/ticket.store';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import {
  BorradorDeFactura,
  CompraRequest,
  CompraResponse,
  ETIQUETA_TIPO_COMPRA,
  PlanCompra,
  TIPOS_COMPRA,
  TipoCompra,
} from './compra.models';
import { ComprasService } from './compras.service';

const RESULTADOS_POR_BUSQUEDA = 8;
const MENSAJE_PROVEEDORES = 'No se pudieron cargar los proveedores.';
const MENSAJE_BUSQUEDA = 'No se pudo buscar. Probá de nuevo.';
const MENSAJE_VISTA_PREVIA = 'No se pudo armar la vista previa. Probá de nuevo.';
const MENSAJE_LEER_FACTURA = 'No se pudo leer la factura. Probá de nuevo o cargá la compra a mano.';
const MENSAJE_SIN_ARTICULO = 'Resolvé los renglones sin artículo.';
const AVISO_CANTIDAD_NO_ENTERA = 'La cantidad no es entera: revisala contra la factura.';
const MENSAJE_REGISTRAR = 'No se pudo registrar la compra. Probá de nuevo: si ya se guardó, no se duplica.';
/** Lo que valida el backend: hasta 5 recargos del costo por compra. */
const MAXIMO_RECARGOS = 5;
/** S5: lo que se explica al intentar agregar un recargo con la lista llena. */
const MOTIVO_TOPE_RECARGOS = `Ya cargaste el máximo de recargos (${MAXIMO_RECARGOS}).`;

type PasoDeCompra = 'comprobante' | 'articulos' | 'recargos';

/** Un renglón mientras se carga: la cantidad y el costo son el texto de los campos. */
interface RenglonEnCarga {
  articuloId: number;
  codigoInterno: string;
  /** El código con el que lo factura el proveedor; se muestra chico si no es el interno. */
  codigoProveedor: string | null;
  descripcion: string;
  moneda: string;
  cantidad: string;
  costo: string;
  /** Una advertencia de la lectura de la factura (por ejemplo, una cantidad con decimales). */
  aviso?: string;
}

/** Un recargo del costo mientras se carga: el porcentaje es el texto del campo («4,44», «-2,5»). */
interface RecargoEnCarga {
  nombre: string;
  porcentaje: string;
}

/** Un renglón de la factura cuyo código no tiene artículo: el Dueño elige el artículo o lo quita. */
interface RenglonSinArticulo {
  codigo: string;
  descripcion: string;
  cantidad: number;
  costoUnitario: number;
  cantidadNoEntera: boolean;
}

/** Lo que dejó la última lectura de una factura en PDF, para avisar debajo del selector. */
interface LecturaDeFactura {
  control: BorradorDeFactura['control'];
  sumaNetos: number;
  /** Los CUIT leídos cuando ninguno es de un proveedor; null si el proveedor se encontró. */
  cuitsSinProveedor: string[] | null;
}

/**
 * Cargar una compra del proveedor (CU-13): encabezado del comprobante, renglones con cantidad y costo sin IVA, vista
 * previa del costo y el precio de cada artículo, y confirmar. La compra suma el stock y pisa el costo (último costo), y el
 * precio se recalcula. Cualquier cambio descarta la vista previa y la clave: se confirma siempre lo que se vio.
 */
@Component({
  selector: 'app-nueva-compra',
  imports: [BarraAcciones, DecimalPipe, CampoMoneda, ImporteInput, MontoPipe, RouterLink, SelectorArchivo],
  templateUrl: './nueva-compra.html',
  styleUrl: './compras.css',
  host: { '(window:resize)': 'ajustarPresentacion()' },
})
export class NuevaCompra implements OnInit {
  protected readonly cantidadConUnidad = cantidadConUnidad;
  protected readonly porUnidad = porUnidad;
  private readonly api = inject(ComprasService);
  private readonly articulos = inject(ArticulosService);
  private readonly proveedoresApi = inject(ProveedoresService);
  private readonly generarClave = inject(GENERAR_CLAVE);
  protected readonly auth = inject(AuthService);

  protected readonly celular = signal(esCelular());
  protected readonly pasosAbiertos = signal<ReadonlySet<PasoDeCompra>>(new Set(['comprobante']));

  protected readonly tipos = TIPOS_COMPRA;
  protected readonly etiqueta = ETIQUETA_TIPO_COMPRA;

  protected readonly proveedores = signal<Proveedor[]>([]);
  protected readonly proveedorId = signal('');
  protected readonly tipo = signal<TipoCompra>('FA');
  protected readonly puntoVenta = signal('');
  protected readonly numero = signal('');
  protected readonly fecha = signal(hoyEnBuenosAires(new Date()));

  protected readonly busqueda = signal('');
  protected readonly resultados = signal<Articulo[]>([]);
  /** Cuántos artículos encontró la búsqueda: se ven hasta {@link RESULTADOS_POR_BUSQUEDA}. */
  protected readonly totalResultados = signal(0);
  /** La opción marcada: tras buscar, la primera, y después la de las flechas; -1 si ninguna (Enter busca). */
  protected readonly activo = signal(-1);
  /** El texto que originó los resultados a la vista: si sigue igual, Enter agrega la opción marcada y no busca. */
  private readonly consultaMostrada = signal('');
  protected readonly opcionActiva = computed(() =>
    this.activo() >= 0 && this.activo() < this.resultados().length ? `resultado-compra-${this.activo()}` : null,
  );
  protected readonly buscando = signal(false);
  protected readonly renglones = signal<RenglonEnCarga[]>([]);

  protected readonly recargos = signal<RecargoEnCarga[]>([]);
  protected readonly errorRecargos = signal<string | null>(null);
  protected readonly maximoRecargos = MAXIMO_RECARGOS;
  /**
   * Por qué la acción no se puede usar: null si se puede (S5). Es la misma tabla que apaga el botón con `aria-disabled`
   * y la que se explica al intentar.
   */
  protected readonly motivos = computed<Motivos<'recargo'>>(() => ({
    recargo: this.recargos().length >= MAXIMO_RECARGOS ? MOTIVO_TOPE_RECARGOS : null,
  }));
  /** El motivo a la vista de la última acción que no se pudo usar, o null. Se anuncia con role="status" (S5). */
  protected readonly motivo = new MotivoDeAccion(() => this.motivos());
  /** Para la plantilla: 'true' apaga el botón, null lo deja prendido. */
  protected readonly ariaDe = ariaDe;

  protected readonly leyendo = signal(false);
  /** El PDF elegido, para que el selector diga cuál se leyó. */
  protected readonly archivoFactura = signal<File | null>(null);
  protected readonly lectura = signal<LecturaDeFactura | null>(null);
  /**
   * La lectura no encontró al proveedor: el aviso va debajo del select, que se enfoca, y se va al elegirlo. Sin ningún
   * CUIT en la factura no se muestra uno vacío (bugs-relevamiento-celular, S1 y S12).
   */
  protected readonly avisoProveedor = computed(() => {
    const cuits = this.lectura()?.cuitsSinProveedor;
    if (!cuits || this.proveedorId() !== '') return null;
    return cuits.length === 0
      ? 'No encontramos el CUIT del proveedor en la factura. Elegilo de la lista.'
      : `No encontramos un proveedor con el CUIT ${cuits.join(', ')}. Elegilo de la lista.`;
  });
  private readonly proveedorSelect = viewChild<ElementRef<HTMLSelectElement>>('proveedorSelect');
  protected readonly sinArticulo = signal<RenglonSinArticulo[]>([]);
  /** El renglón sin artículo para el que se está buscando uno: el próximo artículo elegido lo resuelve. */
  protected readonly resolviendo = signal<RenglonSinArticulo | null>(null);

  protected readonly plan = signal<PlanCompra | null>(null);
  private readonly renglonesAbiertos = signal<{ plan: PlanCompra | null; ids: ReadonlySet<number> }>({
    plan: null,
    ids: new Set(),
  });
  protected readonly trabajando = signal(false);
  protected readonly errores = signal<string[]>([]);
  protected readonly aviso = signal<string | null>(null);
  protected readonly registrada = signal<CompraResponse | null>(null);

  /** La clave de la vista previa que se está viendo: reintentar confirmar la reusa y la compra no se duplica. */
  private clave = '';
  /** Sube con cada edición: una vista previa pedida antes de la última edición ya no describe lo que se ve. */
  private version = 0;

  ngOnInit(): void {
    if (!this.auth.esDueno()) return;
    this.proveedoresApi.listar().subscribe({
      next: (lista) => this.proveedores.set(lista),
      error: () => this.errores.set([MENSAJE_PROVEEDORES]),
    });
  }

  protected ajustarPresentacion(): void {
    this.celular.set(esCelular());
  }

  /** Abierta por el usuario, o porque su error del servidor sólo se muestra ahí adentro (el de los recargos). */
  protected pasoAbierto(paso: PasoDeCompra): boolean {
    return this.pasosAbiertos().has(paso) || (paso === 'recargos' && this.errorRecargos() !== null);
  }

  protected alternarPaso(paso: PasoDeCompra): void {
    this.pasosAbiertos.update((abiertos) => {
      const siguientes = new Set(abiertos);
      if (siguientes.has(paso)) siguientes.delete(paso);
      else siguientes.add(paso);
      return siguientes;
    });
  }

  protected comprobante(): string {
    return `${this.etiqueta[this.tipo()]} ${this.puntoVenta().padStart(5, '0')}-${this.numero().padStart(8, '0')}`;
  }

  protected renglonPreviaAbierto(articuloId: number): boolean {
    const { plan, ids } = this.renglonesAbiertos();
    return plan === this.plan() && ids.has(articuloId);
  }

  protected alternarRenglonPrevia(articuloId: number): void {
    const plan = this.plan();
    if (!plan) return;
    this.renglonesAbiertos.update((abiertos) => {
      const ids = new Set(abiertos.plan === plan ? abiertos.ids : undefined);
      if (ids.has(articuloId)) ids.delete(articuloId);
      else ids.add(articuloId);
      return { plan, ids };
    });
  }

  protected volverDeLaPrevia(): void {
    this.descartarVistaPrevia();
  }

  protected cambiar<T>(campo: { set(valor: T): void }, valor: T): void {
    campo.set(valor);
    this.descartarVistaPrevia();
  }

  protected buscar(evento: Event): void {
    evento.preventDefault();
    const termino = this.busqueda().trim();
    if (termino === '' || this.buscando()) return;
    this.buscando.set(true);
    this.articulos.buscar(termino, 0, RESULTADOS_POR_BUSQUEDA, false).subscribe({
      next: (pagina) => {
        this.buscando.set(false);
        // El lector escribe el código y manda Enter: con una coincidencia exacta se agrega sin elegir.
        const exacto = coincidenciaExacta(pagina.items, termino);
        if (exacto) {
          this.escanear(exacto);
          return;
        }
        this.resultados.set(pagina.items);
        this.totalResultados.set(pagina.totalElements);
        // Como en el Mostrador: queda marcada la primera y Enter la agrega sin apretar ↓.
        this.consultaMostrada.set(termino);
        this.activo.set(0);
      },
      error: () => {
        this.buscando.set(false);
        this.errores.set([MENSAJE_BUSQUEDA]);
      },
    });
  }

  /** Escribir otra cosa deja sin opción activa: el próximo Enter busca. */
  protected escribirBusqueda(texto: string): void {
    this.busqueda.set(texto);
    this.activo.set(-1);
  }

  /**
   * Flechas para moverse por los resultados y Enter para agregar el marcado, como en el Mostrador. Si el texto cambió
   * desde la última búsqueda, los resultados son de otra cosa: Enter sigue de largo y el formulario busca.
   */
  protected alTeclear(evento: KeyboardEvent): void {
    const cantidad = this.resultados().length;
    if (cantidad === 0) return;
    if (evento.key === 'ArrowDown') {
      evento.preventDefault();
      this.activo.update((i) => Math.min(i + 1, cantidad - 1));
    } else if (evento.key === 'ArrowUp') {
      evento.preventDefault();
      this.activo.update((i) => Math.max(i - 1, 0));
    } else if (
      evento.key === 'Enter' &&
      this.opcionActiva() !== null &&
      this.busqueda().trim() === this.consultaMostrada()
    ) {
      evento.preventDefault();
      this.agregar(this.resultados()[this.activo()]);
    }
  }

  protected costoDe(articulo: Articulo): string {
    return formatearCosto(articulo.costo, articulo.moneda === 'USD' ? 'USD' : 'ARS');
  }

  /** Agrega el artículo con cantidad 1 y su costo de hoy; si ya está, solo avisa. */
  protected agregar(articulo: Articulo): void {
    if (this.renglones().some((r) => r.articuloId === articulo.id)) {
      this.aviso.set(`${articulo.descripcion} ya está en la compra.`);
      return;
    }
    this.aviso.set(null);
    // Si se estaba resolviendo un renglón de la factura, el artículo toma su cantidad y su costo.
    const resuelto = this.resolviendo();
    this.renglones.update((lista) => [
      ...lista,
      {
        articuloId: articulo.id,
        codigoInterno: articulo.codigoInterno,
        // Un renglón de la factura muestra el código de la factura, para cotejarlo con el papel.
        codigoProveedor: resuelto ? resuelto.codigo : (articulo.codigoProveedor ?? null),
        descripcion: articulo.descripcion,
        moneda: articulo.moneda,
        cantidad: resuelto ? String(resuelto.cantidad) : '1',
        costo: textoDePlata(resuelto ? resuelto.costoUnitario : articulo.costo),
        ...(resuelto?.cantidadNoEntera ? { aviso: AVISO_CANTIDAD_NO_ENTERA } : {}),
      },
    ]);
    if (resuelto) {
      this.sinArticulo.update((lista) => lista.filter((r) => r !== resuelto));
      this.resolviendo.set(null);
      this.olvidarCartelSinArticulo();
    }
    this.descartarVistaPrevia();
  }

  /** Lee la factura del proveedor en PDF y completa el formulario con el borrador; después se revisa y se sigue de siempre. */
  protected leerFactura(archivo: File | null): void {
    if (!archivo || this.leyendo()) return;
    this.archivoFactura.set(archivo);
    this.leyendo.set(true);
    this.errores.set([]);
    this.api.leerFactura(archivo).subscribe({
      next: (borrador) => {
        this.leyendo.set(false);
        this.completarCon(borrador);
      },
      error: (err: unknown) => {
        this.leyendo.set(false);
        this.mostrarError(err, MENSAJE_LEER_FACTURA);
      },
    });
  }

  /** Reemplaza lo cargado por lo leído. Los renglones sin artículo (o con uno ya cargado) quedan aparte. */
  private completarCon(borrador: BorradorDeFactura): void {
    if (borrador.proveedorId !== null) this.proveedorId.set(String(borrador.proveedorId));
    if (borrador.tipo !== null) this.tipo.set(borrador.tipo);
    if (borrador.puntoVenta !== null) this.puntoVenta.set(String(borrador.puntoVenta));
    if (borrador.numero !== null) this.numero.set(String(borrador.numero));
    if (borrador.fecha !== null) this.fecha.set(borrador.fecha);
    const encontrados: RenglonEnCarga[] = [];
    const aparte: RenglonSinArticulo[] = [];
    for (const r of borrador.renglones) {
      if (r.articuloId === null || encontrados.some((e) => e.articuloId === r.articuloId)) {
        aparte.push({
          codigo: r.codigo,
          descripcion: r.descripcion,
          cantidad: r.cantidad,
          costoUnitario: r.costoUnitario,
          cantidadNoEntera: r.cantidadNoEntera,
        });
        continue;
      }
      encontrados.push({
        articuloId: r.articuloId,
        codigoInterno: r.articuloCodigoInterno ?? r.codigo,
        codigoProveedor: r.codigo,
        descripcion: r.articuloDescripcion ?? r.descripcion,
        moneda: r.articuloMoneda ?? 'ARS',
        cantidad: String(r.cantidad),
        costo: textoDePlata(r.costoUnitario),
        ...(r.cantidadNoEntera ? { aviso: AVISO_CANTIDAD_NO_ENTERA } : {}),
      });
    }
    this.renglones.set(encontrados);
    this.sinArticulo.set(aparte);
    this.resolviendo.set(null);
    this.aviso.set(null);
    this.errorRecargos.set(null);
    this.recargos.set(borrador.recargos.map((r) => ({ nombre: r.nombre, porcentaje: textoDeImporte(r.porcentaje) })));
    this.lectura.set({
      control: borrador.control,
      sumaNetos: borrador.sumaNetos,
      cuitsSinProveedor: borrador.proveedorId === null ? borrador.cuits : null,
    });
    this.descartarVistaPrevia();
    if (borrador.proveedorId === null) this.proveedorSelect()?.nativeElement.focus();
  }

  /** Para este renglón de la factura se busca un artículo en el buscador de arriba. */
  protected elegirArticulo(renglon: RenglonSinArticulo): void {
    this.resolviendo.set(renglon);
    this.aviso.set(`Buscá y elegí el artículo para ${renglon.codigo} — ${renglon.descripcion}.`);
  }

  protected quitarSinArticulo(renglon: RenglonSinArticulo): void {
    this.sinArticulo.update((lista) => lista.filter((r) => r !== renglon));
    if (this.resolviendo() === renglon) {
      this.resolviendo.set(null);
      this.aviso.set(null);
    }
    this.olvidarCartelSinArticulo();
    this.descartarVistaPrevia();
  }

  /**
   * Abre el alta de artículos en otra pestaña (así no se pierde la compra) con lo que dice la factura: descripción,
   * código del proveedor, el proveedor de la compra (si ya se eligió) y el costo.
   */
  protected crearArticulo(renglon: RenglonSinArticulo): void {
    const parametros = new URLSearchParams({
      descripcion: renglon.descripcion,
      codigoProveedor: renglon.codigo,
    });
    if (this.proveedorId() !== '') parametros.set('proveedorId', this.proveedorId());
    parametros.set('costo', String(renglon.costoUnitario));
    for (const [clave, valor] of [...parametros]) if (valor === '') parametros.delete(clave);
    window.open(`/articulos/nuevo?${parametros}`, '_blank', 'noopener');
  }

  /** Cuando ya no queda ningún renglón sin artículo, el cartel de «Resolvé los renglones» es un error viejo. */
  private olvidarCartelSinArticulo(): void {
    if (this.sinArticulo().length === 0) {
      this.errores.update((lista) => lista.filter((e) => e !== MENSAJE_SIN_ARTICULO));
    }
  }

  protected agregarRecargo(): void {
    // S5: con la lista llena el botón avisa el motivo en vez de quedar apagado sin explicación.
    if (this.motivo.mostrar('recargo')) return;
    this.motivo.limpiar();
    this.recargos.update((lista) => [...lista, { nombre: '', porcentaje: '' }]);
    this.editoRecargos();
  }

  protected editarRecargo(indice: number, campo: keyof RecargoEnCarga, valor: string): void {
    this.recargos.update((lista) => lista.map((r, i) => (i === indice ? { ...r, [campo]: valor } : r)));
    this.editoRecargos();
  }

  protected quitarRecargo(indice: number): void {
    this.recargos.update((lista) => lista.filter((_, i) => i !== indice));
    this.editoRecargos();
  }

  /** Tocar un recargo borra el error del servidor (depende de toda la lista) y descarta la vista previa. */
  private editoRecargos(): void {
    this.errorRecargos.set(null);
    this.descartarVistaPrevia();
  }

  /** Un artículo escaneado: si ya está, suma 1 a su cantidad (como en el mostrador); si no, lo agrega. */
  private escanear(articulo: Articulo): void {
    this.busqueda.set('');
    this.resultados.set([]);
    const indice = this.renglones().findIndex((r) => r.articuloId === articulo.id);
    if (indice === -1) {
      this.agregar(articulo);
      return;
    }
    const cantidad = decimal(this.renglones()[indice].cantidad);
    if (Number.isNaN(cantidad)) {
      this.aviso.set(`${articulo.descripcion} ya está en la compra.`);
      return;
    }
    this.aviso.set(null);
    this.editar(indice, 'cantidad', String(cantidad + 1));
  }

  protected editar(indice: number, campo: 'cantidad' | 'costo', valor: string): void {
    this.renglones.update((lista) => lista.map((r, i) => (i === indice ? { ...r, [campo]: valor } : r)));
    this.descartarVistaPrevia();
  }

  protected quitar(indice: number): void {
    this.renglones.update((lista) => lista.filter((_, i) => i !== indice));
    this.descartarVistaPrevia();
  }

  protected verVistaPrevia(): void {
    const pedido = this.pedido();
    if (!pedido || this.trabajando()) return;
    this.trabajando.set(true);
    this.errores.set([]);
    this.errorRecargos.set(null);
    this.registrada.set(null);
    const pedida = this.version;
    this.api.vistaPrevia(pedido).subscribe({
      next: (plan) => {
        this.trabajando.set(false);
        if (pedida !== this.version) return;
        this.clave = this.generarClave();
        this.plan.set(plan);
      },
      error: (err: unknown) => {
        this.trabajando.set(false);
        this.mostrarError(err, MENSAJE_VISTA_PREVIA);
      },
    });
  }

  protected confirmar(): void {
    const pedido = this.pedido();
    if (!pedido || !this.plan() || this.trabajando()) return;
    this.trabajando.set(true);
    this.errores.set([]);
    this.errorRecargos.set(null);
    this.api.registrar({ ...pedido, clave: this.clave }).subscribe({
      next: ({ compra }) => {
        this.trabajando.set(false);
        this.registrada.set(compra);
        this.limpiar();
      },
      error: (err: unknown) => {
        this.trabajando.set(false);
        this.mostrarError(err, MENSAJE_REGISTRAR);
      },
    });
  }

  /** Deja el formulario como recién abierto (el proveedor, el tipo y la fecha quedan: suelen repetirse). */
  private limpiar(): void {
    this.numero.set('');
    this.renglones.set([]);
    this.resultados.set([]);
    this.busqueda.set('');
    this.aviso.set(null);
    this.plan.set(null);
    this.recargos.set([]);
    this.errorRecargos.set(null);
    this.sinArticulo.set([]);
    this.resolviendo.set(null);
    this.lectura.set(null);
    this.archivoFactura.set(null);
  }

  /** Lo que se vio en la vista previa deja de valer: para confirmar hay que volver a verla (y nace otra clave). */
  private descartarVistaPrevia(): void {
    this.version++;
    this.plan.set(null);
    this.registrada.set(null);
  }

  /** El pedido armado desde los campos, o null con los mensajes de lo que falta. */
  private pedido(): CompraRequest | null {
    const faltan: string[] = [];
    if (this.proveedorId() === '') faltan.push('Elegí el proveedor.');
    const puntoVenta = entero(this.puntoVenta());
    if (puntoVenta === null) faltan.push('El punto de venta tiene que ser un número entero.');
    const numero = entero(this.numero());
    if (numero === null) faltan.push('El número tiene que ser un número entero.');
    if (this.fecha() === '') faltan.push('Falta la fecha.');
    if (this.sinArticulo().length > 0) faltan.push(MENSAJE_SIN_ARTICULO);
    else if (this.renglones().length === 0) faltan.push('Agregá al menos un artículo.');
    const renglones = this.renglones().map((r) => ({
      articuloId: r.articuloId,
      cantidad: decimal(r.cantidad),
      costoUnitario: pesos(r.costo),
    }));
    if (renglones.some((r) => Number.isNaN(r.cantidad) || Number.isNaN(r.costoUnitario))) {
      faltan.push('Revisá la cantidad y el costo de cada renglón.');
    }
    const recargos = this.recargos().map((r) => ({ nombre: r.nombre.trim(), porcentaje: porcentaje(r.porcentaje) }));
    if (recargos.some((r) => r.nombre === '' || Number.isNaN(r.porcentaje))) {
      faltan.push('Revisá el nombre y el porcentaje de cada recargo.');
    }
    if (faltan.length > 0) {
      this.errores.set(faltan);
      return null;
    }
    return {
      proveedorId: Number(this.proveedorId()),
      tipo: this.tipo(),
      puntoVenta: puntoVenta!,
      numero: numero!,
      fecha: this.fecha(),
      renglones,
      // Sin recargos no viaja la clave: el pedido de una compra a mano queda como siempre.
      ...(recargos.length > 0 ? { recargos } : {}),
    };
  }

  private mostrarError(err: unknown, porDefecto: string): void {
    const campos = erroresPorCampo(err);
    // El error de los recargos va junto a su editor; el resto, en el aviso de siempre.
    this.errorRecargos.set(campos['recargosCosto'] ?? null);
    const otros = Object.entries(campos)
      .filter(([campo]) => campo !== 'recargosCosto')
      .map(([, mensaje]) => mensaje);
    this.errores.set(otros.length > 0 || this.errorRecargos() ? otros : [mensajeDe(err, porDefecto)]);
  }
}

function entero(texto: string): number | null {
  const limpio = texto.trim();
  return /^\d{1,9}$/.test(limpio) ? Number(limpio) : null;
}

/** Acepta coma o punto decimal; NaN si no es un número. */
/** El único artículo cuyo código interno, de barras o del proveedor es el término, sin mirar mayúsculas. */
function coincidenciaExacta(articulos: readonly Articulo[], termino: string): Articulo | null {
  const buscado = termino.toLowerCase();
  const exactos = articulos.filter((a) =>
    [a.codigoInterno, a.codigoBarras, a.codigoProveedor].some((codigo) => codigo?.trim().toLowerCase() === buscado),
  );
  return exactos.length === 1 ? exactos[0] : null;
}

function decimal(texto: string): number {
  const limpio = texto.trim().replace(',', '.');
  return /^\d+(\.\d+)?$/.test(limpio) ? Number(limpio) : Number.NaN;
}

/** Un porcentaje escrito con la máscara de importes (con menos adelante si es negativo); NaN si está vacío o no se entiende. */
function porcentaje(texto: string): number {
  const limpio = texto.trim();
  const negativo = limpio.startsWith('-');
  const centavos = centavosDeImporte(negativo ? limpio.slice(1) : limpio);
  return centavos === null ? Number.NaN : (negativo ? -centavos : centavos) / 100;
}

/** Un costo escrito con la máscara de importes («1.234,5»), en pesos (o dólares); NaN si está vacío o no se entiende. */
function pesos(texto: string): number {
  const centavos = centavosDeImporte(texto);
  return centavos === null ? Number.NaN : centavos / 100;
}
