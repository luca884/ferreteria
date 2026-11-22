import { CATALOGO, DISPOSICION_ORIGINAL } from '../../layout/menu';
import {
  agregarGrupo, borrarGrupo, editarGrupo, ERROR_NOMBRE_LARGO, MAX_GRUPOS, moverOpcion, opcionesPara,
  prepararEditor, prepararGuardado, reordenarGrupos, validarGrupos,
} from './editor-menu';

const base = () => prepararEditor(null, 'DUENO');
const ocultas = null;
const claves = (d: ReturnType<typeof base>, id: string) => d.grupos.find((g) => g.id === id)?.claves;

describe('editor de menú', () => {
  it('recupera lo que no fue colocado como el menú real, mantiene vacíos e ignora claves obsoletas y duplicadas', () => {
    const disposicion = prepararEditor({
      grupos: [
        { id: 'g-1', rotulo: 'Diario', tono: 'compras', claves: ['caja', 'borrada', 'caja', 'remitos'] },
        { id: 'ventas', rotulo: 'Pedidos', tono: 'ventas', claves: ['caja'] },
        { id: 'compras', rotulo: 'Compras', tono: 'compras', claves: [] },
        { id: 'g-vacio', rotulo: 'Vacío', tono: 'ventas', claves: [] },
      ],
      ocultas: ['remitos', 'proveedores', 'proveedores', 'compras', 'borrada', 'personalizar-menu'],
    }, 'DUENO');
    expect(disposicion.grupos.map((g) => g.id)).toEqual(['g-1', 'ventas', 'compras', 'g-vacio', 'otras']);
    expect(claves(disposicion, 'g-1')).toEqual(['caja']);
    expect(claves(disposicion, 'ventas')).toEqual(['presupuestos', 'clientes', 'ventas', 'reportes']);
    expect(claves(disposicion, 'compras')).toEqual([]);
    expect(claves(disposicion, 'g-vacio')).toEqual([]);
    expect(claves(disposicion, 'otras')).toContain('personalizar-menu');
    expect(disposicion.ocultas).toEqual(['remitos', 'proveedores', 'compras']);
  });

  it('filtra por rol tanto el catálogo como los grupos, las ocultas y el envío', () => {
    const permitidas = ['presupuestos', 'remitos', 'caja', 'clientes', 'articulos', 'rubros', 'proveedores'];
    expect(opcionesPara('VENDEDOR').map((o) => o.clave)).toEqual(permitidas);
    expect(opcionesPara('DUENO')).toEqual(CATALOGO);
    const vendedor = prepararEditor({ ...DISPOSICION_ORIGINAL, ocultas: ['usuarios', 'caja'] }, 'VENDEDOR');
    expect(vendedor.grupos.flatMap((g) => g.claves)).toEqual(permitidas.filter((c) => c !== 'caja'));
    expect(vendedor.ocultas).toEqual(['caja']);
    expect(prepararGuardado(vendedor)).toEqual(vendedor);
  });

  it('reordena, mueve, oculta y vuelve a mostrar sin duplicar claves ni mutar la disposición anterior', () => {
    const original = base();
    const reordenada = moverOpcion(original, 'caja', 'ventas', 'ventas', 0);
    expect(claves(reordenada, 'ventas')?.slice(0, 3)).toEqual(['caja', 'presupuestos', 'remitos']);
    const movida = moverOpcion(reordenada, 'caja', 'ventas', 'compras', 1);
    expect(claves(movida, 'ventas')).not.toContain('caja');
    expect(claves(movida, 'compras')).toEqual(['proveedores', 'caja', 'compras']);
    const escondida = moverOpcion(movida, 'caja', 'compras', ocultas, 0);
    expect(claves(escondida, 'compras')).toEqual(['proveedores', 'compras']);
    expect(escondida.ocultas).toEqual(['caja']);
    const dosOcultas = moverOpcion(escondida, 'remitos', 'ventas', ocultas, 0);
    const reordenadaOculta = moverOpcion(dosOcultas, 'caja', ocultas, ocultas, 0);
    expect(reordenadaOculta.ocultas).toEqual(['caja', 'remitos']);
    const visible = moverOpcion(reordenadaOculta, 'caja', ocultas, 'precios', 0);
    expect(visible.ocultas).toEqual(['remitos']);
    expect(claves(visible, 'precios')?.[0]).toBe('caja');
    const envio = prepararGuardado(visible);
    expect([...envio.grupos.flatMap((g) => g.claves), ...envio.ocultas].sort())
      .toEqual(CATALOGO.map((o) => o.clave).sort());
    expect(original).toEqual(DISPOSICION_ORIGINAL);
    expect(moverOpcion(original, 'caja', 'compras', 'ventas', 1)).toBe(original);
    expect(moverOpcion(original, 'caja', 'ventas', 'ya-no-existe', 1)).toBe(original);
  });

  it('permite mover el editor pero no ocultarlo, aunque aparezca en una disposición vieja', () => {
    const original = base();
    expect(moverOpcion(original, 'personalizar-menu', 'configuracion', ocultas, 0)).toBe(original);
    expect(claves(moverOpcion(original, 'personalizar-menu', 'configuracion', 'ventas', 0), 'ventas')?.[0])
      .toBe('personalizar-menu');
    expect(prepararEditor({ ...original, ocultas: ['personalizar-menu'] }, 'DUENO')).toEqual(original);
  });

  it('renombra y reordena originales sin cambiar ids; borra solo propios y devuelve sus opciones', () => {
    const original = base();
    const conGrupo = agregarGrupo(original);
    const { id } = conGrupo.grupos.at(-1)!;
    expect(id).toMatch(/^g-[a-z0-9]{8}$/);
    expect(conGrupo.grupos.at(-1)).toMatchObject({ tono: 'configuracion', claves: [] });
    const personalizada = editarGrupo(editarGrupo(conGrupo, id, {
      rotulo: '  Diario  ', tono: 'compras',
    }), 'ventas', { rotulo: '  Pedidos  ' });
    const ordenada = reordenarGrupos(personalizada, 0, 1);
    expect(prepararGuardado(ordenada).grupos.slice(0, 2).map(({ id, rotulo }) => [id, rotulo]))
      .toEqual([['articulos', 'Artículos'], ['ventas', 'Pedidos']]);
    const propio = ordenada.grupos.at(-1)!;
    expect(prepararGuardado(ordenada).grupos.at(-1)).toEqual({ ...propio, rotulo: 'Diario', tono: 'compras' });
    const movida = moverOpcion(ordenada, 'caja', 'ventas', propio.id, 0);
    for (const grupo of DISPOSICION_ORIGINAL.grupos) expect(borrarGrupo(movida, grupo.id, 'DUENO')).toBe(movida);
    const sinPropio = borrarGrupo(movida, propio.id, 'DUENO');
    expect(sinPropio.grupos.map((g) => g.id)).not.toContain(propio.id);
    expect(claves(sinPropio, 'ventas')).toEqual(['presupuestos', 'remitos', 'clientes', 'ventas', 'reportes', 'caja']);
  });

  it('devuelve las opciones al grupo original aunque falte en una disposición anterior', () => {
    const parcial = prepararEditor({ grupos: [{ id: 'propio', rotulo: 'Diario', tono: 'ventas', claves: ['caja'] }],
      ocultas: [] }, 'VENDEDOR');
    const sinPropio = borrarGrupo(parcial, 'propio', 'VENDEDOR');
    expect(claves(sinPropio, 'propio')).toBeUndefined();
    expect(claves(sinPropio, 'ventas')).toEqual(['caja']);
    expect(claves(sinPropio, 'otras')).not.toContain('caja');
    expect(borrarGrupo(sinPropio, 'no-existe', 'VENDEDOR')).toBe(sinPropio);
  });

  it('valida nombre vacío, repetido sin distinguir mayúsculas, 30/31 caracteres y el máximo de 10 grupos', () => {
    const original = base();
    expect(validarGrupos(editarGrupo(original, 'ventas', { rotulo: '  ' }))).toBe('Poné un nombre para el grupo.');
    expect(validarGrupos(editarGrupo(original, 'ventas', { rotulo: '  aRTÍCULOS ' })))
      .toBe('Ya hay un grupo con ese nombre.');
    expect(validarGrupos(editarGrupo(original, 'ventas', { rotulo: ' ' + 'x'.repeat(30) + ' ' }))).toBeNull();
    expect(validarGrupos(editarGrupo(original, 'ventas', { rotulo: 'x'.repeat(31) }))).toBe(ERROR_NOMBRE_LARGO);
    let maxima = original;
    for (let i = original.grupos.length; i < MAX_GRUPOS; ++i) {
      maxima = agregarGrupo(maxima);
      maxima = editarGrupo(maxima, maxima.grupos.at(-1)!.id, { rotulo: `Propio ${maxima.grupos.length}` });
    }
    expect(maxima.grupos).toHaveLength(10);
    expect(new Set(maxima.grupos.map((g) => g.id)).size).toBe(10);
    expect(validarGrupos(maxima)).toBeNull();
    expect(agregarGrupo(maxima)).toBe(maxima);
    expect(validarGrupos({ ...maxima, grupos: [...maxima.grupos, maxima.grupos[0]] })).toBe('Podés tener hasta 10 grupos.');
  });
});
