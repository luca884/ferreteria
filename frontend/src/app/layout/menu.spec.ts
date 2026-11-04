import { GRUPOS, MOSTRADOR, gruposPara, seccionDe } from './menu';

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
    expect(GRUPOS.flatMap((g) => g.opciones).length).toBe(22);
  });
});
