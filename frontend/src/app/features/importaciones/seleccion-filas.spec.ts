import {
  AltaPlaneada,
  CambioPlaneado,
  Conteos,
  FilaSinCambio,
  AvisoDeFila,
  VistaPrevia,
} from './importacion.models';
import {
  FilaDeVista,
  TIPOS_ELEGIBLES,
  claveDe,
  conteosConSeleccion,
  esElegible,
  estadoDeSeleccion,
  filasElegidas,
  filtrarFilas,
  marcarFila,
  marcarHoja,
  marcarNinguna,
  marcarTipo,
  marcarTodas,
  seleccionInicial,
  unirFilas,
} from './seleccion-filas';

const SIN_AJUSTES = { a1: null, a2: null, a3: null };

function cambio(hoja: number, fila: number, anterior = 100, nuevo = 120): CambioPlaneado {
  return {
    fila,
    hoja,
    articuloId: hoja * 100_000 + fila,
    codigoProveedor: `C-${hoja}-${fila}`,
    descripcion: `Artículo ${hoja}/${fila}`,
    precioLista: nuevo,
    costoAnterior: anterior,
    costoNuevo: nuevo,
    precioAnterior: anterior * 1.5,
    precioNuevo: nuevo * 1.5,
    activo: true,
    variacionFuerte: false,
    monedaAnterior: 'ARS',
    monedaNueva: 'ARS',
    alicuotaAnterior: 'IVA_21',
    alicuotaNueva: 'IVA_21',
    ajustesAnteriores: SIN_AJUSTES,
    ajustesNuevos: SIN_AJUSTES,
  };
}

function alta(hoja: number, fila: number): AltaPlaneada {
  return {
    fila,
    hoja,
    codigo: `N-${hoja}-${fila}`,
    descripcion: `Nuevo ${hoja}/${fila}`,
    precioLista: 20,
    costo: 18,
    moneda: 'ARS',
    alicuotaIva: 'IVA_21',
    ajustes: SIN_AJUSTES,
    unidad: 'UNIDAD',
    codigoBarras: null,
    rubroId: null,
    precioNuevo: 30,
  };
}

function sinCambio(hoja: number, fila: number): FilaSinCambio {
  return { id: hoja * 100_000 + fila, hoja, fila, codigo: `S-${hoja}-${fila}`, descripcion: 'Igual' };
}

function error(hoja: number | null, fila: number | null, mensaje = 'Moneda no reconocida'): AvisoDeFila {
  return { hoja, fila, codigo: 'X-1', mensaje };
}

const CONTEOS_VACIOS: Conteos = {
  suben: 0,
  bajan: 0,
  nuevos: 0,
  sinCambio: 0,
  noEstan: 0,
  conError: 0,
  cambianMoneda: 0,
  corrigen: 0,
  salteadas: 0,
};

function vistaPrevia(parcial: Partial<VistaPrevia>): VistaPrevia {
  return {
    conteos: CONTEOS_VACIOS,
    cambios: [],
    nuevos: [],
    errores: [],
    advertencias: [],
    noEstan: [],
    cambiosDeMoneda: [],
    correcciones: [],
    sinCambio: [],
    ...parcial,
  };
}

/** Una vista previa con una fila de cada tipo, repartidas en hojas distintas. */
function unaDeCadaTipo(): VistaPrevia {
  return vistaPrevia({
    cambios: [cambio(0, 2, 100, 120), cambio(1, 3, 200, 150)],
    cambiosDeMoneda: [
      { ...cambio(0, 4, 1000, 10), monedaAnterior: 'ARS', monedaNueva: 'USD' },
    ],
    correcciones: [{ ...cambio(2, 5, 100, 100), alicuotaNueva: 'IVA_10_5' }],
    nuevos: [alta(0, 6)],
    sinCambio: [sinCambio(1, 7)],
    errores: [error(0, 8)],
  });
}

describe('seleccion-filas', () => {
  describe('unirFilas', () => {
    it('une las listas en filas con su tipo, ordenadas por hoja y fila', () => {
      const filas = unirFilas(unaDeCadaTipo());

      expect(filas.map((f) => [f.hoja, f.fila, f.tipo])).toEqual([
        [0, 2, 'suben'],
        [0, 4, 'cambianMoneda'],
        [0, 6, 'nuevos'],
        [0, 8, 'conError'],
        [1, 3, 'bajan'],
        [1, 7, 'sinCambio'],
        [2, 5, 'corrigen'],
      ]);
    });

    it('la clave es "hoja:fila" y las filas llevan código y descripción', () => {
      const filas = unirFilas(unaDeCadaTipo());

      expect(filas[0].clave).toBe('0:2');
      expect(claveDe(12, 4139)).toBe('12:4139');
      expect(filas[0].codigo).toBe('C-0-2');
      expect(filas[0].descripcion).toBe('Artículo 0/2');
      expect(filas[2].codigo).toBe('N-0-6');
      expect(filas[5].codigo).toBe('S-1-7');
      expect(filas[3].mensaje).toBe('Moneda no reconocida');
    });

    it('un cambio con el costo mayor sube y con el menor baja', () => {
      const filas = unirFilas(vistaPrevia({ cambios: [cambio(0, 2, 100, 120), cambio(0, 3, 100, 80)] }));

      expect(filas.map((f) => f.tipo)).toEqual(['suben', 'bajan']);
    });

    it('un error sin hoja (lista de una sola hoja anterior) cae en la hoja 0', () => {
      const filas = unirFilas(vistaPrevia({ errores: [error(null, 9)] }));

      expect(filas).toHaveLength(1);
      expect(filas[0].hoja).toBe(0);
      expect(filas[0].clave).toBe('0:9');
    });

    it('una vista previa sin filas da una lista vacía', () => {
      expect(unirFilas(vistaPrevia({}))).toEqual([]);
    });
  });

  describe('selección al abrir', () => {
    it('están elegidas todas las filas elegibles y las de error no', () => {
      const filas = unirFilas(unaDeCadaTipo());

      const seleccion = seleccionInicial(filas);

      expect(seleccion.size).toBe(6);
      expect(seleccion.has('0:8')).toBe(false);
      for (const f of filas.filter((x) => x.tipo !== 'conError')) {
        expect(seleccion.has(f.clave), f.clave).toBe(true);
      }
    });

    it('los seis tipos son elegibles y el error no', () => {
      const filas = unirFilas(unaDeCadaTipo());

      expect([...TIPOS_ELEGIBLES]).toEqual([
        'suben',
        'bajan',
        'cambianMoneda',
        'corrigen',
        'nuevos',
        'sinCambio',
      ]);
      expect(filas.filter(esElegible).map((f) => f.tipo)).toEqual([
        'suben',
        'cambianMoneda',
        'nuevos',
        'bajan',
        'sinCambio',
        'corrigen',
      ]);
    });
  });

  describe('acciones', () => {
    const filas = unirFilas(unaDeCadaTipo());

    it('marca y desmarca una fila de a una, sin tocar las demás', () => {
      const inicial = seleccionInicial(filas);
      const sin = marcarFila(inicial, filas[0], false);

      expect(sin.has('0:2')).toBe(false);
      expect(sin.size).toBe(5);
      const con = marcarFila(sin, filas[0], true);
      expect(con.has('0:2')).toBe(true);
      expect(con.size).toBe(6);
    });

    it('no modifica la selección anterior (funciones puras)', () => {
      const inicial = seleccionInicial(filas);

      marcarFila(inicial, filas[0], false);
      marcarNinguna();

      expect(inicial.size).toBe(6);
    });

    it('«ninguna» y marcar una → 1 elegida', () => {
      const ninguna = marcarNinguna();
      expect(ninguna.size).toBe(0);

      const una = marcarFila(ninguna, filas[2], true);

      expect(una.size).toBe(1);
      expect(una.has('0:6')).toBe(true);
    });

    it('«todas» después de «ninguna» → todas las elegibles', () => {
      const ninguna = marcarNinguna();

      const todas = marcarTodas(filas);

      expect(ninguna.size).toBe(0);
      expect(todas.size).toBe(6);
      expect(todas.has('0:8')).toBe(false);
    });

    it('marcar una fila con error no la deja marcada y no falla', () => {
      const conError = filas.find((f) => f.tipo === 'conError')!;

      const resultado = marcarFila(marcarNinguna(), conError, true);

      expect(resultado.size).toBe(0);
      expect(resultado.has(conError.clave)).toBe(false);
    });
  });

  describe('por hoja y por tipo', () => {
    /** 70 hojas: la 3 («TORNILLOS») tiene 60 filas aplicables y cada una de las otras 69, una. */
    function libroDe70Hojas(): FilaDeVista[] {
      const cambios: CambioPlaneado[] = [];
      for (let hoja = 0; hoja < 70; hoja++) {
        const cantidad = hoja === 3 ? 60 : 1;
        for (let i = 0; i < cantidad; i++) cambios.push(cambio(hoja, 2 + i));
      }
      return unirFilas(vistaPrevia({ cambios }));
    }

    it('desmarcar una hoja de 60 filas deja 60 desmarcadas y las otras 69 hojas no cambian', () => {
      const filas = libroDe70Hojas();
      const inicial = seleccionInicial(filas);
      expect(inicial.size).toBe(129);

      const sin = marcarHoja(inicial, filas, 3, false);

      expect(sin.size).toBe(69);
      expect(filas.filter((f) => f.hoja === 3).every((f) => !sin.has(f.clave))).toBe(true);
      expect(filas.filter((f) => f.hoja !== 3).every((f) => sin.has(f.clave))).toBe(true);
    });

    it('volver a marcar la hoja la deja como estaba', () => {
      const filas = libroDe70Hojas();
      const sin = marcarHoja(seleccionInicial(filas), filas, 3, false);

      const con = marcarHoja(sin, filas, 3, true);

      expect(con.size).toBe(129);
    });

    it('desmarcar el tipo «bajan» con 12 filas en 5 hojas deja las 12 desmarcadas', () => {
      const bajan = Array.from({ length: 12 }, (_, i) => cambio(i % 5, 10 + i, 200, 100));
      const suben = [cambio(0, 100), cambio(4, 101), cambio(2, 102)];
      const filas = unirFilas(vistaPrevia({ cambios: [...bajan, ...suben] }));
      const inicial = seleccionInicial(filas);
      expect(inicial.size).toBe(15);

      const sin = marcarTipo(inicial, filas, 'bajan', false);

      expect(sin.size).toBe(3);
      expect(filas.filter((f) => f.tipo === 'bajan').every((f) => !sin.has(f.clave))).toBe(true);
      expect(filas.filter((f) => f.tipo === 'suben').every((f) => sin.has(f.clave))).toBe(true);
    });

    it('la selección es la unión de lo marcado: hoja desmarcada y después un tipo marcado', () => {
      const filas = unirFilas(unaDeCadaTipo());
      const sinHoja0 = marcarHoja(seleccionInicial(filas), filas, 0, false);
      expect([...sinHoja0].sort()).toEqual(['1:3', '1:7', '2:5']);

      const conNuevos = marcarTipo(sinHoja0, filas, 'nuevos', true);

      expect([...conNuevos].sort()).toEqual(['0:6', '1:3', '1:7', '2:5']);
    });

    it('marcar una hoja entera no marca sus filas con error', () => {
      const filas = unirFilas(unaDeCadaTipo());

      const sel = marcarHoja(marcarNinguna(), filas, 0, true);

      expect(sel.has('0:8')).toBe(false);
      expect([...sel].sort()).toEqual(['0:2', '0:4', '0:6']);
    });

    it('estadoDeSeleccion distingue todas, algunas y ninguna del grupo', () => {
      const filas = unirFilas(unaDeCadaTipo());
      const hoja0 = filas.filter((f) => f.hoja === 0);

      expect(estadoDeSeleccion(seleccionInicial(filas), hoja0)).toBe('todas');
      expect(estadoDeSeleccion(marcarNinguna(), hoja0)).toBe('ninguna');
      expect(estadoDeSeleccion(marcarFila(marcarNinguna(), hoja0[0], true), hoja0)).toBe('algunas');
    });

    it('un grupo sin filas elegibles figura como «ninguna»', () => {
      const filas = unirFilas(unaDeCadaTipo());
      const soloError = filas.filter((f) => f.tipo === 'conError');

      expect(estadoDeSeleccion(seleccionInicial(filas), soloError)).toBe('ninguna');
    });
  });

  describe('filtrarFilas', () => {
    const filas = unirFilas(unaDeCadaTipo());

    it('sin filtro devuelve todas', () => {
      expect(filtrarFilas(filas, {})).toHaveLength(7);
    });

    it('por hoja', () => {
      expect(filtrarFilas(filas, { hoja: 1 }).map((f) => f.clave)).toEqual(['1:3', '1:7']);
    });

    it('por tipo', () => {
      expect(filtrarFilas(filas, { tipo: 'conError' }).map((f) => f.clave)).toEqual(['0:8']);
    });

    it('por hoja y tipo a la vez', () => {
      expect(filtrarFilas(filas, { hoja: 0, tipo: 'nuevos' }).map((f) => f.clave)).toEqual(['0:6']);
      expect(filtrarFilas(filas, { hoja: 2, tipo: 'nuevos' })).toEqual([]);
    });
  });

  describe('filasElegidas (lo que viaja en aplicar)', () => {
    it('devuelve {hoja, fila} de lo marcado, ordenado por hoja y fila', () => {
      const filas = unirFilas(unaDeCadaTipo());
      const seleccion = marcarHoja(seleccionInicial(filas), filas, 1, false);

      expect(filasElegidas(seleccion, filas)).toEqual([
        { hoja: 0, fila: 2 },
        { hoja: 0, fila: 4 },
        { hoja: 0, fila: 6 },
        { hoja: 2, fila: 5 },
      ]);
    });

    it('sin nada marcado es una lista vacía', () => {
      const filas = unirFilas(unaDeCadaTipo());

      expect(filasElegidas(marcarNinguna(), filas)).toEqual([]);
    });
  });

  describe('conteosConSeleccion (C-109)', () => {
    const archivo: Conteos = {
      suben: 1,
      bajan: 1,
      nuevos: 1,
      sinCambio: 1,
      noEstan: 4,
      conError: 1,
      cambianMoneda: 1,
      corrigen: 1,
      salteadas: 42,
    };

    it('con todo marcado repite los contadores del archivo', () => {
      const filas = unirFilas(unaDeCadaTipo());

      const conteos = conteosConSeleccion(archivo, filas, seleccionInicial(filas));

      expect(conteos).toEqual(archivo);
    });

    it('3.000 elegidas con 200 suben, 2.700 nuevas y 100 sin cambio dan esos tres valores', () => {
      const cambios = Array.from({ length: 200 }, (_, i) => cambio(0, 2 + i));
      const nuevos = Array.from({ length: 2700 }, (_, i) => alta(1, 2 + i));
      const iguales = Array.from({ length: 100 }, (_, i) => sinCambio(2, 2 + i));
      const filas = unirFilas(vistaPrevia({ cambios, nuevos, sinCambio: iguales }));
      const delArchivo = { ...archivo, suben: 200, bajan: 0, nuevos: 2700, sinCambio: 100, cambianMoneda: 0, corrigen: 0 };

      const conteos = conteosConSeleccion(delArchivo, filas, seleccionInicial(filas));

      expect(filasElegidas(seleccionInicial(filas), filas)).toHaveLength(3000);
      expect([conteos.suben, conteos.nuevos, conteos.sinCambio]).toEqual([200, 2700, 100]);
      expect([conteos.bajan, conteos.cambianMoneda, conteos.corrigen]).toEqual([0, 0, 0]);
    });

    it('desmarcar una fila baja el contador de su tipo y solo ese', () => {
      const filas = unirFilas(unaDeCadaTipo());
      const nueva = filas.find((f) => f.tipo === 'nuevos')!;

      const conteos = conteosConSeleccion(archivo, filas, marcarFila(seleccionInicial(filas), nueva, false));

      expect(conteos.nuevos).toBe(0);
      expect(conteos.suben).toBe(1);
      expect(conteos.bajan).toBe(1);
      expect(conteos.sinCambio).toBe(1);
      expect(conteos.cambianMoneda).toBe(1);
      expect(conteos.corrigen).toBe(1);
    });

    it('«no están», con error y salteadas son los del archivo y no dependen de la selección', () => {
      const filas = unirFilas(unaDeCadaTipo());

      const conteos = conteosConSeleccion(archivo, filas, marcarNinguna());

      expect([conteos.suben, conteos.bajan, conteos.nuevos, conteos.sinCambio]).toEqual([0, 0, 0, 0]);
      expect([conteos.cambianMoneda, conteos.corrigen]).toEqual([0, 0]);
      expect([conteos.noEstan, conteos.conError, conteos.salteadas]).toEqual([4, 1, 42]);
    });
  });

  describe('con 20.000 filas', () => {
    it('elige, cuenta y desmarca una hoja sin demora apreciable', () => {
      const cambios: CambioPlaneado[] = [];
      for (let hoja = 0; hoja < 4; hoja++) {
        for (let i = 0; i < 5000; i++) cambios.push(cambio(hoja, 2 + i, 100, i % 2 === 0 ? 120 : 80));
      }
      const filas = unirFilas(vistaPrevia({ cambios }));
      const archivo = { ...CONTEOS_VACIOS, suben: 10_000, bajan: 10_000 };

      const inicio = performance.now();
      const inicial = seleccionInicial(filas);
      const sinHoja = marcarHoja(inicial, filas, 2, false);
      const conteos = conteosConSeleccion(archivo, filas, sinHoja);
      const duracion = performance.now() - inicio;

      expect(filas).toHaveLength(20_000);
      expect(inicial.size).toBe(20_000);
      expect(sinHoja.size).toBe(15_000);
      expect([conteos.suben, conteos.bajan]).toEqual([7500, 7500]);
      expect(duracion).toBeLessThan(1000);
    });
  });
});
