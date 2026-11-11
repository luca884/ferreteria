#!/usr/bin/env node
/**
 * Genera `odd/tasks/INDEX.md` a partir de los documentos de `odd/tasks/`.
 *
 *   node odd/indice-tareas.mjs
 *
 * Sin dependencias: solo `node:*`. El índice es un mapa derivado; el detalle de
 * cada tarea vive en su archivo. Si una tarea cambia de estado, se edita su
 * documento y se vuelve a correr esto; el índice no se edita a mano.
 *
 * De dónde sale cada dato (todo del contenido de los documentos, nada fijo acá):
 *
 * - Objetivo: línea `Objetivo:` (con o sin guion), recortada antes de ` · Por qué:`,
 *   sin `**` ni backticks y cortada a 150 caracteres, como el índice original.
 * - Rama: primer backtick de la línea `Rama:`.
 * - Fecha: la del heading `Pedido (fecha)` o, si no está, la del primer `L1 (fecha)`
 *   del `## Log`, que es donde cada documento registra el pedido. Sin ninguno: `—`.
 * - Estado: `- [x]` y `- [ ]` de la sección `## Tasks`; un documento sin esa sección
 *   cuenta las casillas de todo el archivo y se rotula `sin §Tasks`.
 * - Cerrada / abierta: cerrada solo si tiene `## Tasks` y no le queda ninguna `- [ ]`.
 * - KB: tamaño en disco redondeado a KiB (1024 bytes).
 * - Total de la carpeta: bytes ocupados en bloques de 4 KiB, redondeado a 1 decimal
 *   en MiB, igual que `du -sh`.
 * - Tareas por caso de uso: los documentos cuyo texto menciona `CU-NN`, de CU-01 a CU-16.
 *
 * Orden: las abiertas alfabéticas por archivo; las cerradas por fecha del pedido
 * (la más reciente arriba) y, a igual fecha, alfabéticas.
 */
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const AQUI = dirname(fileURLToPath(import.meta.url));
const DIR_TAREAS = join(AQUI, 'tasks');
const RUTA_INDICE = join(DIR_TAREAS, 'INDEX.md');
const NOMBRE_INDICE = 'INDEX.md';

/** Largo del recorte de `Objetivo:` que usa el índice desde que se escribió a mano. */
const LARGO_OBJETIVO = 150;

/** Bloque de asignación en disco con el que se cuenta el total (`du -sh`). */
const BLOQUE = 4096;

/** Último caso de uso de la spec: CU-01 a CU-16. */
const CASOS_DE_USO = 16;

const SIN_FECHA = '—';

/**
 * Valor de un campo `Etiqueta:` al principio de una línea, con o sin guion de lista.
 * Devuelve `null` si el documento no lo tiene.
 */
function campo(lineas, etiqueta) {
  const patron = new RegExp(`^(?:- )?${etiqueta}:[ \\t]*(.*)$`);
  for (const linea of lineas) {
    const encontrado = linea.match(patron);
    if (encontrado) return encontrado[1];
  }
  return null;
}

const sinMarcas = (texto) => texto.replace(/\*\*/g, '').replace(/`/g, '');

function objetivo(lineas) {
  const crudo = campo(lineas, 'Objetivo');
  if (crudo === null) return SIN_FECHA;
  const soloObjetivo = crudo.split(' · Por qué:')[0];
  return sinMarcas(soloObjetivo).slice(0, LARGO_OBJETIVO);
}

function rama(lineas) {
  const crudo = campo(lineas, 'Rama');
  if (crudo === null) return SIN_FECHA;
  const ramaConBackticks = crudo.match(/`([^`]+)`/);
  return ramaConBackticks ? `\`${ramaConBackticks[1]}\`` : crudo.split(' · ')[0];
}

function fecha(texto) {
  const pedido = texto.match(/^(?:#+ )?(?:- )?Pedido \((\d{4}-\d{2}-\d{2})\)/m);
  if (pedido) return pedido[1];
  const log = texto.match(/^(?:- )?L1 \((\d{4}-\d{2}-\d{2})\)/m);
  return log ? log[1] : SIN_FECHA;
}

/** Cuerpo del `## Tasks`, o `null` si el documento no tiene esa sección. */
function seccionTareas(texto) {
  const lineas = texto.split('\n');
  const inicio = lineas.findIndex((linea) => linea.trim() === '## Tasks');
  if (inicio === -1) return null;
  const cuerpo = [];
  for (const linea of lineas.slice(inicio + 1)) {
    if (/^## /.test(linea)) break;
    cuerpo.push(linea);
  }
  return cuerpo.join('\n');
}

function contarCasillas(texto) {
  const marcas = texto.match(/^[ \t]*- \[[xX ]\]/gm) ?? [];
  const pendientes = marcas.filter((marca) => marca.includes('[ ]')).length;
  return { hechas: marcas.length - pendientes, pendientes };
}

function estado(documento) {
  const { hechas, pendientes } = contarCasillas(
    documento.seccionTareas ?? documento.texto,
  );
  const prefijo = documento.seccionTareas === null ? 'sin §Tasks' : 'abierta';
  return `${prefijo} (${hechas}✓/${pendientes}✗)`;
}

function leerDocumentos() {
  const archivos = readdirSync(DIR_TAREAS)
    .filter((archivo) => archivo.endsWith('.md') && archivo !== NOMBRE_INDICE)
    .sort((a, b) => a.localeCompare(b, 'es'));

  return archivos.map((archivo) => {
    const ruta = join(DIR_TAREAS, archivo);
    const texto = readFileSync(ruta, 'utf8');
    const lineas = texto.split('\n');
    const seccion = seccionTareas(texto);
    const { pendientes } = contarCasillas(seccion ?? texto);
    return {
      archivo,
      texto,
      lineas,
      bytes: statSync(ruta).size,
      seccionTareas: seccion,
      nombre: archivo.replace(/\.md$/, ''),
      objetivo: objetivo(lineas),
      rama: rama(lineas),
      fecha: fecha(texto),
      cerrada: seccion !== null && pendientes === 0,
    };
  });
}

/** El valor de una celda no puede romper la tabla. */
const celda = (texto) => texto.replace(/\|/g, '\\|');

function filaAbierta(documento) {
  return `| [\`${documento.archivo}\`](${documento.archivo}) | ${estado(documento)} | ${documento.fecha} | ${celda(documento.objetivo)} | ${documento.rama} | ${Math.round(documento.bytes / 1024)} |`;
}

function filaCerrada(documento) {
  return `| [\`${documento.archivo}\`](${documento.archivo}) | ${documento.fecha} | ${celda(documento.objetivo)} | ${Math.round(documento.bytes / 1024)} |`;
}

/** Las cerradas van por fecha del pedido, la más reciente arriba; a igual fecha, por archivo. */
function porFechaDelPedido(a, b) {
  if (a.fecha !== b.fecha) {
    if (a.fecha === SIN_FECHA) return 1;
    if (b.fecha === SIN_FECHA) return -1;
    return a.fecha < b.fecha ? 1 : -1;
  }
  return a.archivo.localeCompare(b.archivo, 'es');
}

function tablaDeCasosDeUso(documentos) {
  const filas = [];
  for (let numero = 1; numero <= CASOS_DE_USO; numero += 1) {
    const caso = `CU-${String(numero).padStart(2, '0')}`;
    const nombres = documentos
      .filter((documento) => documento.texto.includes(caso))
      .map((documento) => `\`${documento.nombre}\``);
    if (nombres.length > 0) filas.push(`| ${caso} | ${nombres.join(', ')} |`);
  }
  return filas;
}

/** Bytes ocupados en bloques de 4 KiB, como los cuenta `du`. */
function bytesEnDisco(documentos) {
  return documentos.reduce(
    (total, documento) => total + Math.ceil(documento.bytes / BLOQUE) * BLOQUE,
    0,
  );
}

function hoyEnIso() {
  const hoy = new Date();
  const mes = String(hoy.getMonth() + 1).padStart(2, '0');
  const dia = String(hoy.getDate()).padStart(2, '0');
  return `${hoy.getFullYear()}-${mes}-${dia}`;
}

function render(documentos, kbIndice) {
  const abiertas = documentos.filter((documento) => !documento.cerrada);
  const cerradas = documentos
    .filter((documento) => documento.cerrada)
    .sort(porFechaDelPedido);
  const totalMb = (bytesEnDisco(documentos) / 1024 / 1024)
    .toFixed(1)
    .replace('.', ',');

  return [
    '# Índice de tareas (`odd/tasks`)',
    '',
    `> Generado el ${hoyEnIso()} desde los ${documentos.length} documentos de esta carpeta. Es un **mapa**, no la fuente de verdad:`,
    '> el detalle, los specs `S#` y la evidencia de cada tarea viven en su archivo.',
    '>',
    '> Leé esta página primero y después **un solo** archivo, el de la tarea que te toca.',
    '>',
    `> Tamaño: este índice pesa ~${kbIndice} KB; los ${documentos.length} documentos suman ${totalMb} MB. Leerlos todos no entra en contexto.`,
    '',
    `- **${cerradas.length} cerradas · ${abiertas.length} abiertas**`,
    '',
    '## Abiertas',
    '',
    '| Tarea | Estado | Fecha | Objetivo | Rama | KB |',
    '|---|---|---|---|---|---|',
    ...abiertas.map(filaAbierta),
    '',
    '## Cerradas',
    '',
    'Ordenadas por fecha del pedido, la más reciente arriba.',
    '',
    '| Tarea | Fecha | Objetivo | KB |',
    '|---|---|---|---|',
    ...cerradas.map(filaCerrada),
    '',
    '## Tareas por caso de uso',
    '',
    'Para ir de una sección de la spec (`docs/spec/cu-NN-*.md`) a las tareas que ya la tocaron.',
    '',
    '| CU | Tareas |',
    '|---|---|',
    ...tablaDeCasosDeUso(documentos),
    '',
    '## Regenerar',
    '',
    'Este índice se deriva de los headings de cada documento (`Objetivo:`, `Rama:`, `Pedido (fecha)`, `- [x]` en `## Tasks`).',
    'Si una tarea cambia de estado, editá su archivo y volvé a correr el generador; no edites el índice a mano:',
    '',
    '```bash',
    'node odd/indice-tareas.mjs',
    '```',
    '',
    'Sale de `odd/indice-tareas.mjs`, sin dependencias. Verificación: `git diff --stat odd/tasks/INDEX.md` tiene que mostrar solo la línea de «Generado el …».',
    '',
  ].join('\n');
}

function main() {
  const documentos = leerDocumentos();

  // El índice se cita a sí mismo, así que su tamaño es el del archivo que escribe:
  // se parte del que hay y se rehace hasta que el número coincide con el generado.
  let kbIndice = 0;
  try {
    kbIndice = Math.round(statSync(RUTA_INDICE).size / 1024);
  } catch {
    kbIndice = 0;
  }

  let salida = render(documentos, kbIndice);
  for (let intento = 0; intento < 10; intento += 1) {
    const kb = Math.round(Buffer.byteLength(salida) / 1024);
    if (kb === kbIndice) break;
    kbIndice = kb;
    salida = render(documentos, kbIndice);
  }

  writeFileSync(RUTA_INDICE, salida);
  console.log(`odd/tasks/INDEX.md: ${documentos.length} documentos`);
}

main();
