/** Sección del sistema: agrupa pantallas en el menú y le da a cada una su tono (mejoras-ferreteros-bloque-2, S1 y S2). */
export type Seccion = 'mostrador' | 'ventas' | 'articulos' | 'compras' | 'precios' | 'configuracion';

export type IconoMenu =
  | 'mostrador'
  | 'presupuestos'
  | 'remitos'
  | 'caja'
  | 'clientes'
  | 'ventas'
  | 'reportes'
  | 'articulos'
  | 'rubros'
  | 'stock-bajo-minimo'
  | 'etiquetas'
  | 'controles'
  | 'proveedores'
  | 'compras'
  | 'importar'
  | 'actualizar-precios'
  | 'margen'
  | 'cotizacion'
  | 'formas-pago'
  | 'consumidor-final'
  | 'datos-fiscales'
  | 'facturacion'
  | 'usuarios';

export interface OpcionMenu {
  readonly ruta: string;
  readonly rotulo: string;
  readonly icono: IconoMenu;
  /** Solo la ve el Dueño; el Vendedor que entra por URL ve el aviso de la pantalla y el servidor responde 403. */
  readonly soloDueno?: true;
}

export interface GrupoMenu {
  readonly seccion: Seccion;
  readonly rotulo: string;
  readonly opciones: readonly OpcionMenu[];
}

/** Fijo arriba, fuera de los grupos: es la pantalla principal. */
export const MOSTRADOR: OpcionMenu = { ruta: '/mostrador', rotulo: 'Mostrador', icono: 'mostrador' };

/**
 * El destino «Ventas» de la barra inferior del celular (movil-sectores T2, S3): no es una pantalla del menú sino la
 * entrada al grupo Ventas, con las opciones que el rol ya tiene. Los dos roles lo tienen: no da acceso a nada nuevo.
 */
export const HUB_VENTAS: OpcionMenu = { ruta: '/area-ventas', rotulo: 'Ventas', icono: 'ventas' };

export const GRUPOS: readonly GrupoMenu[] = [
  {
    seccion: 'ventas',
    rotulo: 'Ventas',
    opciones: [
      { ruta: '/presupuestos', rotulo: 'Presupuestos', icono: 'presupuestos' },
      { ruta: '/remitos', rotulo: 'Remitos', icono: 'remitos' },
      { ruta: '/caja', rotulo: 'Caja', icono: 'caja' },
      { ruta: '/clientes', rotulo: 'Clientes', icono: 'clientes' },
      { ruta: '/ventas', rotulo: 'Ventas', icono: 'ventas', soloDueno: true },
      { ruta: '/reportes', rotulo: 'Reportes', icono: 'reportes', soloDueno: true },
    ],
  },
  {
    seccion: 'articulos',
    rotulo: 'Artículos',
    opciones: [
      { ruta: '/articulos', rotulo: 'Artículos', icono: 'articulos' },
      { ruta: '/rubros', rotulo: 'Rubros', icono: 'rubros' },
      { ruta: '/stock-bajo-minimo', rotulo: 'Stock bajo mínimo', icono: 'stock-bajo-minimo', soloDueno: true },
      { ruta: '/etiquetas', rotulo: 'Etiquetas', icono: 'etiquetas', soloDueno: true },
      { ruta: '/controles-de-datos', rotulo: 'Controles de datos', icono: 'controles', soloDueno: true },
    ],
  },
  {
    seccion: 'compras',
    rotulo: 'Compras',
    opciones: [
      { ruta: '/proveedores', rotulo: 'Proveedores', icono: 'proveedores' },
      { ruta: '/compras', rotulo: 'Compras', icono: 'compras', soloDueno: true },
    ],
  },
  {
    seccion: 'precios',
    rotulo: 'Precios',
    opciones: [
      { ruta: '/importaciones', rotulo: 'Importar lista', icono: 'importar', soloDueno: true },
      { ruta: '/actualizacion-masiva', rotulo: 'Actualizar precios', icono: 'actualizar-precios', soloDueno: true },
      { ruta: '/configuracion/precios', rotulo: 'Margen y redondeo', icono: 'margen', soloDueno: true },
      { ruta: '/configuracion/cotizacion', rotulo: 'Cotización', icono: 'cotizacion', soloDueno: true },
    ],
  },
  {
    seccion: 'configuracion',
    rotulo: 'Configuración',
    opciones: [
      { ruta: '/configuracion/formas-pago', rotulo: 'Formas de pago', icono: 'formas-pago', soloDueno: true },
      { ruta: '/configuracion/ventas', rotulo: 'Consumidor Final', icono: 'consumidor-final', soloDueno: true },
      {
        ruta: '/configuracion/datos-fiscales',
        rotulo: 'Datos de la ferretería',
        icono: 'datos-fiscales',
        soloDueno: true,
      },
      { ruta: '/configuracion/facturacion', rotulo: 'Facturación', icono: 'facturacion', soloDueno: true },
      { ruta: '/usuarios', rotulo: 'Usuarios', icono: 'usuarios', soloDueno: true },
    ],
  },
];

/** Los grupos que ve un rol, con solo sus opciones; un grupo que queda vacío no se muestra. */
export function gruposPara(dueno: boolean): GrupoMenu[] {
  return GRUPOS.map((grupo) => ({ ...grupo, opciones: grupo.opciones.filter((o) => dueno || !o.soloDueno) })).filter(
    (grupo) => grupo.opciones.length > 0,
  );
}

/** «/clientes/5/cuenta?x=1» → «ventas»: la sección de la opción cuya ruta es la URL o la contiene como carpeta. */
export function seccionDe(url: string): Seccion | null {
  const ruta = url.split(/[?#]/)[0];
  const coincide = (opcion: OpcionMenu) => ruta === opcion.ruta || ruta.startsWith(`${opcion.ruta}/`);
  if (coincide(MOSTRADOR)) return 'mostrador';
  if (coincide(HUB_VENTAS)) return 'ventas';
  return GRUPOS.find((grupo) => grupo.opciones.some(coincide))?.seccion ?? null;
}
