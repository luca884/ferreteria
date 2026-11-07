import { EncabezadosGuardados } from '../importaciones/importacion.models';
import { CAMPOS_DE_COLUMNA, CampoDeColumna, TITULO_DE_COLUMNA, letraDeColumna } from '../importaciones/mapeo-lista';
import { Proveedor } from './proveedor.models';

/**
 * Lo que la ficha del proveedor muestra de su lista (#44), de solo lectura: lo fija la importación (CU-04). El IVA y las
 * bonificaciones van siempre (sin importación quedan en «No» y «Sin bonificaciones»); lo del mapeo, solo si lo tiene.
 */
export interface ResumenDeLista {
  incluyeIva: boolean;
  /** «10 % + 2,5 % (en cascada)», o «Sin bonificaciones». */
  bonificaciones: string;
  /** null si todavía no se importó ninguna lista. */
  mapeo: ResumenDeMapeo | null;
}

export interface ResumenDeMapeo {
  /** «USD para toda la lista» si la lista no trae columna de moneda; null si no tiene moneda fija. */
  moneda: string | null;
  filaDesde: number;
  /** «Código: A · COD»: el dato, la letra de la planilla y el encabezado que se leyó, si lo hay. */
  columnas: string[];
}

export function resumenDeLista(proveedor: Proveedor): ResumenDeLista {
  return {
    incluyeIva: proveedor.listaIncluyeIva === true,
    bonificaciones: textoDeBonificaciones([proveedor.bonificacion1, proveedor.bonificacion2, proveedor.bonificacion3]),
    mapeo: resumenDeMapeo(proveedor),
  };
}

function resumenDeMapeo(proveedor: Proveedor): ResumenDeMapeo | null {
  const mapeo = proveedor.mapeoLista;
  if (!mapeo) return null;
  const columnas = CAMPOS_DE_COLUMNA.flatMap((campo) => {
    const indice = mapeo[campo];
    if (indice == null) return [];
    const encabezado = mapeo.encabezados?.[claveDeEncabezado(campo)]?.trim();
    const columna = encabezado ? `${letraDeColumna(indice)} · ${encabezado}` : letraDeColumna(indice);
    return [`${nombreDelDato(campo)}: ${columna}`];
  });
  return {
    moneda: mapeo.monedaFija ? `${mapeo.monedaFija} para toda la lista` : null,
    filaDesde: mapeo.filaDesde,
    columnas,
  };
}

function textoDeBonificaciones(valores: Array<number | null | undefined>): string {
  const hay = valores.filter((v): v is number => v != null && v !== 0);
  if (hay.length === 0) return 'Sin bonificaciones';
  const texto = hay.map((v) => `${String(v).replace('.', ',')} %`).join(' + ');
  return hay.length > 1 ? `${texto} (en cascada)` : texto;
}

/** `columnaSinIva` → `sinIva`: la clave del encabezado guardado. */
function claveDeEncabezado(campo: CampoDeColumna): keyof EncabezadosGuardados {
  const resto = campo.slice('columna'.length);
  return (resto.charAt(0).toLowerCase() + resto.slice(1)) as keyof EncabezadosGuardados;
}

/** «Columna del código» → «Código». */
function nombreDelDato(campo: CampoDeColumna): string {
  const nombre = TITULO_DE_COLUMNA[campo].replace(/^Columna (del |de la )/, '');
  return nombre.charAt(0).toUpperCase() + nombre.slice(1);
}
