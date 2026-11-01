import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
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
import { CompraRequest, CompraResponse, ETIQUETA_TIPO_COMPRA, PlanCompra, TIPOS_COMPRA, TipoCompra } from './compra.models';
import { ComprasService } from './compras.service';

const RESULTADOS_POR_BUSQUEDA = 8;
const MENSAJE_PROVEEDORES = 'No se pudieron cargar los proveedores.';
const MENSAJE_BUSQUEDA = 'No se pudo buscar. Probá de nuevo.';
const MENSAJE_VISTA_PREVIA = 'No se pudo armar la vista previa. Probá de nuevo.';
const MENSAJE_REGISTRAR = 'No se pudo registrar la compra. Probá de nuevo: si ya se guardó, no se duplica.';

/** Un renglón mientras se carga: la cantidad y el costo son el texto de los campos. */
interface RenglonEnCarga {
  articuloId: number;
  codigoInterno: string;
  descripcion: string;
  moneda: string;
  cantidad: string;
  costo: string;
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
    this.renglones.update((lista) => [
      ...lista,
      {
        articuloId: articulo.id,
        codigoInterno: articulo.codigoInterno,
        descripcion: articulo.descripcion,
        moneda: articulo.moneda,
        cantidad: '1',
        costo: textoDeImporte(articulo.costo),
      },
    ]);
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
    if (this.renglones().length === 0) faltan.push('Agregá al menos un artículo.');
    const renglones = this.renglones().map((r) => ({
      articuloId: r.articuloId,
      cantidad: decimal(r.cantidad),
      costoUnitario: pesos(r.costo),
    }));
    if (renglones.some((r) => Number.isNaN(r.cantidad) || Number.isNaN(r.costoUnitario))) {
      faltan.push('Revisá la cantidad y el costo de cada renglón.');
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
    };
  }

  private mostrarError(err: unknown, porDefecto: string): void {
    const campos = erroresDeCampos(err);
    this.errores.set(campos.length > 0 ? campos : [mensajeDe(err, porDefecto)]);
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

/** Un costo escrito con la máscara de importes («1.234,5»), en pesos (o dólares); NaN si está vacío o no se entiende. */
function pesos(texto: string): number {
  const centavos = centavosDeImporte(texto);
  return centavos === null ? Number.NaN : centavos / 100;
}
