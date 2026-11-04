import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { erroresPorCampo, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { centavosDeImporte, textoDeImporte } from '../../core/formato/importe';
import { MontoPipe } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { ImporteInput } from '../../shared/importe-input';
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
const AVISO_CANTIDAD_NO_ENTERA = 'La cantidad no es entera: revisala contra la factura.';
const MENSAJE_REGISTRAR = 'No se pudo registrar la compra. Probá de nuevo: si ya se guardó, no se duplica.';
/** Lo que valida el backend: hasta 5 recargos del costo por compra. */
const MAXIMO_RECARGOS = 5;

/** Un renglón mientras se carga: la cantidad y el costo son el texto de los campos. */
interface RenglonEnCarga {
  articuloId: number;
  codigoInterno: string;
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
  imports: [BarraAcciones, DecimalPipe, ImporteInput, MontoPipe, RouterLink],
  templateUrl: './nueva-compra.html',
  styleUrl: './compras.css',
})
export class NuevaCompra implements OnInit {
  private readonly api = inject(ComprasService);
  private readonly articulos = inject(ArticulosService);
  private readonly proveedoresApi = inject(ProveedoresService);
  private readonly generarClave = inject(GENERAR_CLAVE);
  protected readonly auth = inject(AuthService);

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
  protected readonly buscando = signal(false);
  protected readonly renglones = signal<RenglonEnCarga[]>([]);

  protected readonly recargos = signal<RecargoEnCarga[]>([]);
  protected readonly errorRecargos = signal<string | null>(null);
  protected readonly maximoRecargos = MAXIMO_RECARGOS;

  protected readonly leyendo = signal(false);
  protected readonly lectura = signal<LecturaDeFactura | null>(null);
  protected readonly sinArticulo = signal<RenglonSinArticulo[]>([]);
  /** El renglón sin artículo para el que se está buscando uno: el próximo artículo elegido lo resuelve. */
  protected readonly resolviendo = signal<RenglonSinArticulo | null>(null);

  protected readonly plan = signal<PlanCompra | null>(null);
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
      },
      error: () => {
        this.buscando.set(false);
        this.errores.set([MENSAJE_BUSQUEDA]);
      },
    });
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
        descripcion: articulo.descripcion,
        moneda: articulo.moneda,
        cantidad: resuelto ? String(resuelto.cantidad) : '1',
        costo: textoDeImporte(resuelto ? resuelto.costoUnitario : articulo.costo),
        ...(resuelto?.cantidadNoEntera ? { aviso: AVISO_CANTIDAD_NO_ENTERA } : {}),
      },
    ]);
    if (resuelto) {
      this.sinArticulo.update((lista) => lista.filter((r) => r !== resuelto));
      this.resolviendo.set(null);
    }
    this.descartarVistaPrevia();
  }

  /** Lee la factura del proveedor en PDF y completa el formulario con el borrador; después se revisa y se sigue de siempre. */
  protected leerFactura(evento: Event): void {
    const campoArchivo = evento.target as HTMLInputElement;
    const archivo = campoArchivo.files?.[0];
    if (!archivo || this.leyendo()) return;
    campoArchivo.value = '';
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
        codigoInterno: r.codigo,
        descripcion: r.articuloDescripcion ?? r.descripcion,
        moneda: 'ARS',
        cantidad: String(r.cantidad),
        costo: textoDeImporte(r.costoUnitario),
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
    this.descartarVistaPrevia();
  }

  protected agregarRecargo(): void {
    if (this.recargos().length >= MAXIMO_RECARGOS) return;
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
    if (this.sinArticulo().length > 0) faltan.push('Resolvé los renglones sin artículo.');
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
