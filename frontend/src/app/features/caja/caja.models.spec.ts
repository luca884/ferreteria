import {
  CajaVigente,
  avisoParaVender,
  diaDeCaja,
  fechaHoraLocal,
  textoDiferencia,
  tiposPara,
} from './caja.models';

describe('caja.models', () => {
  const vigente = (estado: CajaVigente['estado'], fechaApertura = '2026-10-16T08:30:00'): CajaVigente => ({
    estado,
    efectivoPropuesto: 0,
    caja:
      estado === 'SIN_CAJA'
        ? null
        : { id: 4, estado, fechaApertura, abiertaPor: 'Ana', efectivoInicial: 1000 },
  });

  it('el día de la caja sale del texto de la fecha local (sin pasar por la zona del navegador)', () => {
    expect(diaDeCaja('2026-10-05T23:59:59.123')).toBe('05/10');
  });

  it('fecha y hora local: «16/10 08:30»; vacío si falta', () => {
    expect(fechaHoraLocal('2026-10-16T08:30:12.5')).toBe('16/10 08:30');
    expect(fechaHoraLocal(null)).toBe('');
  });

  it('sin caja abierta avisa que hay que abrirla para vender', () => {
    expect(avisoParaVender(vigente('SIN_CAJA'))).toBe('Abrí la caja para vender.');
  });

  it('con la caja de otro día avisa que hay que cerrarla, con su fecha', () => {
    expect(avisoParaVender(vigente('DE_OTRO_DIA', '2026-10-15T09:00:00'))).toBe(
      'La caja del 15/10 sigue abierta. Cerrala antes de vender.',
    );
  });

  it('con la caja abierta hoy no hay aviso', () => {
    expect(avisoParaVender(vigente('ABIERTA'))).toBeNull();
  });

  it('el retiro solo lo ve el dueño', () => {
    expect(tiposPara(true)).toEqual(['INGRESO', 'GASTO', 'RETIRO']);
    expect(tiposPara(false)).toEqual(['INGRESO', 'GASTO']);
  });

  it('la diferencia del cierre dice si faltan o sobran pesos, o que no hay', () => {
    expect(textoDiferencia(-50)).toMatch(/^Faltan \$\s50,00$/);
    expect(textoDiferencia(100)).toMatch(/^Sobran \$\s100,00$/);
    expect(textoDiferencia(0)).toBe('Sin diferencia');
  });
});
