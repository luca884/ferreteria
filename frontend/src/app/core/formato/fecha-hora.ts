/** Las fechas del servidor son instantes UTC; el negocio las lee en hora de Buenos Aires. */
const ZONA = 'America/Argentina/Buenos_Aires';

const FORMATO = new Intl.DateTimeFormat('es-AR', {
  timeZone: ZONA,
  day: '2-digit',
  month: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** «16/10 08:00»: día, mes y hora de Buenos Aires de un instante ISO. Vacío si falta o no se puede leer. */
export function formatearFechaHora(iso: string | null | undefined): string {
  if (!iso) return '';
  const fecha = new Date(iso);
  if (Number.isNaN(fecha.getTime())) return '';
  const partes = Object.fromEntries(FORMATO.formatToParts(fecha).map((p) => [p.type, p.value.padStart(2, '0')]));
  return `${partes['day']}/${partes['month']} ${partes['hour']}:${partes['minute']}`;
}
