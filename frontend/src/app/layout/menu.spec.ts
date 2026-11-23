import { CATALOGO, DISPOSICION_ORIGINAL, Disposicion, MOSTRADOR, armarMenu, ubicar } from './menu';

/** El menú original tal como lo ve cada rol: la base contra la que se compara todo lo demás. */
const gruposPara = (dueno: boolean) => armarMenu(DISPOSICION_ORIGINAL, dueno);
const seccionDe = (url: string) => ubicar(url, gruposPara(true))?.tono ?? null;

// Regresiones del verificador del bloque 2 (S1, S2): rutas → sección y opciones por rol.
describe('menu (mejoras-ferreteros-bloque-2, S1)', () => {
  it('cada ruta de la app cae en su sección', () => {
    const casos: [string, string | null][] = [
      ['/mostrador', 'mostrador'],
      ['/presupuestos', 'ventas'],
      ['/remitos', 'ventas'],
      ['/clientes', 'ventas'],
      ['/clientes/5/cuenta', 'ventas'],
      ['/caja', 'ventas'],
      ['/caja/historial', 'ventas'],
      ['/ventas', 'ventas'],
      ['/area-ventas', 'ventas'],
      ['/reportes', 'ventas'],
      ['/articulos', 'articulos'],
      ['/articulos/nuevo', 'articulos'],
      ['/articulos/3/movimientos', 'articulos'],
      ['/articulos/3', 'articulos'],
      ['/rubros', 'articulos'],
      ['/stock-bajo-minimo', 'articulos'],
      ['/etiquetas', 'articulos'],
      ['/controles-de-datos', 'articulos'],
      ['/proveedores', 'compras'],
      ['/proveedores?q=Bulonera%20Sur', 'compras'],
      ['/compras', 'compras'],
      ['/compras/nueva', 'compras'],
      ['/importaciones', 'precios'],
      ['/actualizacion-masiva', 'precios'],
      ['/configuracion/precios', 'precios'],
      ['/configuracion/cotizacion', 'precios'],
      ['/configuracion/formas-pago', 'configuracion'],
      ['/configuracion/ventas', 'configuracion'],
      ['/configuracion/datos-fiscales', 'configuracion'],
      ['/configuracion/facturacion', 'configuracion'],
      ['/usuarios', 'configuracion'],
      ['/mi-contrasena', null],
      ['/admin/ferreterias', null],
      ['/ventasx', null],
      ['/articulos#x', 'articulos'],
    ];
    for (const [url, esperado] of casos) expect([url, seccionDe(url)]).toEqual([url, esperado]);
  });

  it('el vendedor ve exactamente sus opciones', () => {
    const vendedor = gruposPara(false).map((g) => [g.rotulo, g.opciones.map((o) => o.rotulo)]);
    expect(vendedor).toEqual([
      ['Ventas', ['Presupuestos', 'Remitos', 'Caja', 'Clientes']],
      ['Artículos', ['Artículos', 'Rubros']],
      ['Compras', ['Proveedores']],
    ]);
    expect(MOSTRADOR.soloDueno).toBeUndefined();
  });

  it('el dueño ve las 22 opciones, además del Mostrador', () => {
    expect(gruposPara(true).flatMap((g) => g.opciones).length).toBe(22);
    expect(CATALOGO.length).toBe(22);
  });
});

// CU-17 T1: el menú es un catálogo de pantallas más una disposición; sin personalizar, la disposición es la original.
describe('menú personalizable (CU-17, S5-S7)', () => {
  const rotulos = (d: Disposicion, dueno = true) =>
    armarMenu(d, dueno).map((g) => [g.rotulo, g.opciones.map((o) => o.rotulo)]);

  it('cada pantalla del catálogo tiene una clave propia y una sola vez en el menú original', () => {
    const claves = CATALOGO.map((o) => o.clave);
    expect(new Set(claves).size).toBe(claves.length);
    const ubicadas = DISPOSICION_ORIGINAL.grupos.flatMap((g) => g.claves);
    expect([...ubicadas].sort()).toEqual([...claves].sort());
  });

  it('mover y reordenar opciones y renombrar un grupo original', () => {
    const d: Disposicion = {
      grupos: DISPOSICION_ORIGINAL.grupos.map((g) =>
        g.id === 'ventas'
          ? { ...g, claves: ['caja', 'presupuestos', 'remitos', 'clientes', 'ventas', 'reportes'] }
          : g.id === 'precios'
            ? { ...g, rotulo: 'Listas', claves: [...g.claves, 'rubros'] }
            : { ...g, claves: g.claves.filter((c) => c !== 'rubros') },
      ),
      ocultas: [],
    };
    const menu = rotulos(d, false);
    expect(menu[0]).toEqual(['Ventas', ['Caja', 'Presupuestos', 'Remitos', 'Clientes']]);
    expect(menu).toContainEqual(['Artículos', ['Artículos']]);
    expect(armarMenu(d, true).find((g) => g.id === 'precios')?.rotulo).toBe('Listas');
  });

  it('un grupo propio lleva el tono que se le dio y se abre con su id', () => {
    const d: Disposicion = {
      grupos: [{ id: 'g-1', rotulo: 'Lo de todos los días', tono: 'compras', claves: ['caja', 'clientes'] }],
      ocultas: [],
    };
    const [propio] = armarMenu(d, true);
    expect(propio).toMatchObject({ id: 'g-1', tono: 'compras', rotulo: 'Lo de todos los días' });
    expect(ubicar('/caja/historial', armarMenu(d, true))).toEqual({ id: 'g-1', tono: 'compras' });
  });

  it('ocultar saca la opción del menú sin sacarla del catálogo', () => {
    const d: Disposicion = { ...DISPOSICION_ORIGINAL, ocultas: ['remitos', 'usuarios'] };
    const todas = armarMenu(d, true).flatMap((g) => g.opciones.map((o) => o.rotulo));
    expect(todas).not.toContain('Remitos');
    expect(todas).not.toContain('Usuarios');
    expect(todas.length).toBe(20);
    expect(CATALOGO.length).toBe(22);
  });

  it('una pantalla que la disposición no menciona no desaparece: vuelve a su grupo original', () => {
    const d: Disposicion = {
      grupos: DISPOSICION_ORIGINAL.grupos.map((g) => ({ ...g, claves: g.claves.filter((c) => c !== 'etiquetas') })),
      ocultas: [],
    };
    const articulos = armarMenu(d, true).find((g) => g.id === 'articulos');
    expect(articulos?.opciones.map((o) => o.clave)).toContain('etiquetas');
  });

  it('si su grupo original ya no existe, cae en «Otras» al final', () => {
    const d: Disposicion = {
      grupos: DISPOSICION_ORIGINAL.grupos.filter((g) => g.id !== 'compras'),
      ocultas: [],
    };
    const menu = armarMenu(d, true);
    expect(menu.at(-1)).toMatchObject({ id: 'otras', rotulo: 'Otras' });
    expect(menu.at(-1)?.opciones.map((o) => o.rotulo)).toEqual(['Proveedores', 'Compras']);
  });

  it('ignora una clave que el sistema no tiene y una repetida', () => {
    const d: Disposicion = {
      grupos: [
        { id: 'a', rotulo: 'A', tono: 'ventas', claves: ['caja', 'no-existe', 'caja'] },
        { id: 'b', rotulo: 'B', tono: 'precios', claves: ['caja', 'clientes'] },
      ],
      ocultas: ['no-existe-tampoco'],
    };
    const menu = armarMenu(d, true);
    expect(menu.find((g) => g.id === 'a')?.opciones.map((o) => o.clave)).toEqual(['caja']);
    expect(menu.find((g) => g.id === 'b')?.opciones.map((o) => o.clave)).toEqual(['clientes']);
  });

  it('el rol manda: el vendedor no recibe pantallas de solo Dueño aunque la disposición las ponga, y un grupo vacío no se muestra', () => {
    const d: Disposicion = {
      grupos: [
        { id: 'a', rotulo: 'A', tono: 'ventas', claves: ['usuarios', 'caja'] },
        { id: 'b', rotulo: 'B', tono: 'precios', claves: ['reportes'] },
      ],
      ocultas: [],
    };
    const menu = armarMenu(d, false);
    expect(menu.find((g) => g.id === 'a')?.opciones.map((o) => o.clave)).toEqual(['caja']);
    expect(menu.find((g) => g.id === 'b')).toBeUndefined();
  });
});
