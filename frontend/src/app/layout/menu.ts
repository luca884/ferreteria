/**
 * El tono de un grupo del menú y de las pantallas que contiene: lo que `data-seccion` le pasa al CSS
 * (mejoras-ferreteros-bloque-2, S1 y S2). Un grupo propio elige uno; «mostrador» es solo de la pantalla fija.
 */
export type Tono = 'mostrador' | 'ventas' | 'articulos' | 'compras' | 'precios' | 'configuracion';

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
  /** Identificador estable de la pantalla: es lo que guarda una disposición, nunca la ruta ni el rótulo. */
  readonly clave: string;
  readonly ruta: string;
  readonly rotulo: string;
  readonly icono: IconoMenu;
  /** Solo la ve el Dueño; el Vendedor que entra por URL ve el aviso de la pantalla y el servidor responde 403. */
  readonly soloDueno?: true;
}

/** Un grupo ya armado para mostrar: sus opciones son las que el rol puede abrir y la disposición no oculta. */
export interface GrupoMenu {
  readonly id: string;
  readonly tono: Tono;
  readonly rotulo: string;
  readonly opciones: readonly OpcionMenu[];
}

/** Un grupo tal como se guarda: sus opciones son claves del catálogo, en orden. */
export interface GrupoDisposicion {
  readonly id: string;
  readonly rotulo: string;
  readonly tono: Tono;
  readonly claves: readonly string[];
}

/** Cómo ordena el menú quien lo personaliza (CU-17): los grupos con sus claves y las pantallas que oculta. */
export interface Disposicion {
  readonly grupos: readonly GrupoDisposicion[];
  readonly ocultas: readonly string[];
}

/** Fijo arriba, fuera de los grupos: es la pantalla principal. */
export const MOSTRADOR: OpcionMenu = { clave: 'mostrador', ruta: '/mostrador', rotulo: 'Mostrador', icono: 'mostrador' };

/**
 * El destino «Ventas» de la barra inferior del celular (movil-sectores T2, S3): no es una pantalla del menú sino la
 * entrada al grupo Ventas, con las opciones que el rol ya tiene. Los dos roles lo tienen: no da acceso a nada nuevo.
 */
export const HUB_VENTAS: OpcionMenu = { clave: 'hub-ventas', ruta: '/area-ventas', rotulo: 'Ventas', icono: 'ventas' };

/** Las pantallas del sistema que pueden estar en el menú. Se agregan acá; la disposición solo las ordena. */
export const CATALOGO: readonly OpcionMenu[] = [
  { clave: 'presupuestos', ruta: '/presupuestos', rotulo: 'Presupuestos', icono: 'presupuestos' },
  { clave: 'remitos', ruta: '/remitos', rotulo: 'Remitos', icono: 'remitos' },
  { clave: 'caja', ruta: '/caja', rotulo: 'Caja', icono: 'caja' },
  { clave: 'clientes', ruta: '/clientes', rotulo: 'Clientes', icono: 'clientes' },
  { clave: 'ventas', ruta: '/ventas', rotulo: 'Ventas', icono: 'ventas', soloDueno: true },
  { clave: 'reportes', ruta: '/reportes', rotulo: 'Reportes', icono: 'reportes', soloDueno: true },
  { clave: 'articulos', ruta: '/articulos', rotulo: 'Artículos', icono: 'articulos' },
  { clave: 'rubros', ruta: '/rubros', rotulo: 'Rubros', icono: 'rubros' },
  { clave: 'stock-bajo-minimo', ruta: '/stock-bajo-minimo', rotulo: 'Stock bajo mínimo', icono: 'stock-bajo-minimo', soloDueno: true },
  { clave: 'etiquetas', ruta: '/etiquetas', rotulo: 'Etiquetas', icono: 'etiquetas', soloDueno: true },
  { clave: 'controles-de-datos', ruta: '/controles-de-datos', rotulo: 'Controles de datos', icono: 'controles', soloDueno: true },
  { clave: 'proveedores', ruta: '/proveedores', rotulo: 'Proveedores', icono: 'proveedores' },
  { clave: 'compras', ruta: '/compras', rotulo: 'Compras', icono: 'compras', soloDueno: true },
  { clave: 'importaciones', ruta: '/importaciones', rotulo: 'Importar lista', icono: 'importar', soloDueno: true },
  { clave: 'actualizacion-masiva', ruta: '/actualizacion-masiva', rotulo: 'Actualizar precios', icono: 'actualizar-precios', soloDueno: true },
  { clave: 'margen-y-redondeo', ruta: '/configuracion/precios', rotulo: 'Margen y redondeo', icono: 'margen', soloDueno: true },
  { clave: 'cotizacion', ruta: '/configuracion/cotizacion', rotulo: 'Cotización', icono: 'cotizacion', soloDueno: true },
  { clave: 'formas-de-pago', ruta: '/configuracion/formas-pago', rotulo: 'Formas de pago', icono: 'formas-pago', soloDueno: true },
  { clave: 'consumidor-final', ruta: '/configuracion/ventas', rotulo: 'Consumidor Final', icono: 'consumidor-final', soloDueno: true },
  { clave: 'datos-de-la-ferreteria', ruta: '/configuracion/datos-fiscales', rotulo: 'Datos de la ferretería', icono: 'datos-fiscales', soloDueno: true },
  { clave: 'facturacion', ruta: '/configuracion/facturacion', rotulo: 'Facturación', icono: 'facturacion', soloDueno: true },
  { clave: 'usuarios', ruta: '/usuarios', rotulo: 'Usuarios', icono: 'usuarios', soloDueno: true },
];

/** El menú que trae el sistema: lo que ve un usuario cuando nadie lo personalizó. */
export const DISPOSICION_ORIGINAL: Disposicion = {
  grupos: [
    {
      id: 'ventas',
      rotulo: 'Ventas',
      tono: 'ventas',
      claves: ['presupuestos', 'remitos', 'caja', 'clientes', 'ventas', 'reportes'],
    },
    {
      id: 'articulos',
      rotulo: 'Artículos',
      tono: 'articulos',
      claves: ['articulos', 'rubros', 'stock-bajo-minimo', 'etiquetas', 'controles-de-datos'],
    },
    { id: 'compras', rotulo: 'Compras', tono: 'compras', claves: ['proveedores', 'compras'] },
    {
      id: 'precios',
      rotulo: 'Precios',
      tono: 'precios',
      claves: ['importaciones', 'actualizacion-masiva', 'margen-y-redondeo', 'cotizacion'],
    },
    {
      id: 'configuracion',
      rotulo: 'Configuración',
      tono: 'configuracion',
      claves: ['formas-de-pago', 'consumidor-final', 'datos-de-la-ferreteria', 'facturacion', 'usuarios'],
    },
  ],
  ocultas: [],
};

/** El grupo al que vuelve una pantalla que la disposición no menciona, y el que se crea si ese ya no está. */
const GRUPO_OTRAS: GrupoDisposicion = { id: 'otras', rotulo: 'Otras', tono: 'configuracion', claves: [] };

/**
 * Arma los grupos que ve un rol a partir de una disposición (CU-17). El rol manda: las pantallas de solo Dueño no
 * llegan al Vendedor aunque la disposición las ponga. Una clave que el catálogo no tiene se ignora y una repetida vale
 * solo la primera vez. Las pantallas que la disposición no menciona ni oculta no desaparecen: vuelven a su grupo
 * original o, si ese grupo ya no existe, a «Otras». Un grupo sin opciones no se muestra.
 */
export function armarMenu(disposicion: Disposicion, dueno: boolean): GrupoMenu[] {
  const porClave = new Map(CATALOGO.map((opcion) => [opcion.clave, opcion]));
  const ocultas = new Set(disposicion.ocultas);
  const usadas = new Set<string>();

  const claves = disposicion.grupos.map((grupo) => ({ grupo, claves: [...grupo.claves] }));
  const sinUbicar = CATALOGO.filter(
    (opcion) => !ocultas.has(opcion.clave) && !disposicion.grupos.some((g) => g.claves.includes(opcion.clave)),
  );
  for (const opcion of sinUbicar) {
    const original = DISPOSICION_ORIGINAL.grupos.find((g) => g.claves.includes(opcion.clave));
    let destino = claves.find((c) => c.grupo.id === original?.id);
    if (!destino) {
      destino = claves.find((c) => c.grupo.id === GRUPO_OTRAS.id);
      if (!destino) claves.push((destino = { grupo: GRUPO_OTRAS, claves: [] }));
    }
    destino.claves.push(opcion.clave);
  }

  return claves
    .map(({ grupo, claves: deseadas }) => ({
      id: grupo.id,
      tono: grupo.tono,
      rotulo: grupo.rotulo,
      opciones: deseadas.flatMap((clave) => {
        const opcion = porClave.get(clave);
        if (!opcion || ocultas.has(clave) || usadas.has(clave) || (opcion.soloDueno && !dueno)) return [];
        usadas.add(clave);
        return [opcion];
      }),
    }))
    .filter((grupo) => grupo.opciones.length > 0);
}

/** Dónde cae una pantalla: el grupo (por id) y el tono que le da su color. */
export interface Ubicacion {
  readonly id: string;
  readonly tono: Tono;
}

/**
 * «/clientes/5/cuenta?x=1» → el grupo que tiene la opción cuya ruta es la URL o la contiene como carpeta. «Mostrador»
 * es fijo y el hub «Ventas» del celular pertenece al grupo de id `ventas`. Una pantalla fuera del menú no tiene lugar.
 */
export function ubicar(url: string, grupos: readonly GrupoMenu[]): Ubicacion | null {
  const ruta = url.split(/[?#]/)[0];
  const coincide = (opcion: OpcionMenu) => ruta === opcion.ruta || ruta.startsWith(`${opcion.ruta}/`);
  if (coincide(MOSTRADOR)) return { id: 'mostrador', tono: 'mostrador' };
  const grupo = coincide(HUB_VENTAS)
    ? grupos.find((g) => g.id === 'ventas')
    : grupos.find((g) => g.opciones.some(coincide));
  return grupo ? { id: grupo.id, tono: grupo.tono } : null;
}
