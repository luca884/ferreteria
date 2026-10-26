const FORMATO_EMITIDO = new Intl.DateTimeFormat('es-AR', {
  timeZone: 'America/Argentina/Buenos_Aires',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** «23/10/2026 12:30»: el instante dicho en hora de Buenos Aires, que es la del negocio. Va en el pie de lo que se imprime. */
export function emitidoEl(ahora: Date): string {
  const p = Object.fromEntries(FORMATO_EMITIDO.formatToParts(ahora).map((parte) => [parte.type, parte.value]));
  return `${p['day']}/${p['month']}/${p['year']} ${p['hour']}:${p['minute']}`;
}
