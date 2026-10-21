import {
  CajaAbierta,
  CajaVigente,
  DetalleCaja,
  DetalleCajaRespuesta,
  avisoParaVender,
  conCobranzas,
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

  describe('cobranzas del detalle (CC-053)', () => {
    const sinCobranzas: DetalleCajaRespuesta = {
      id: 4,
      estado: 'CERRADA',
      fechaApertura: '2026-10-16T08:00:00',
      abiertaPor: 'Ana',
      efectivoInicial: 5000,
      efectivoCierreAnterior: null,
      diferenciaApertura: null,
      fechaCierre: '2026-10-16T20:00:00',
      cerradaPor: 'Ana',
      ventas: { EFECTIVO: 1000, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 },
      totalVentas: 1000,
      ingresos: 0,
      gastos: 0,
      retiros: 0,
      efectivoEsperado: 6700,
      efectivoContado: 6700,
      diferencia: 0,
      movimientos: [],
    };

    it('conserva las cobranzas por medio y el total que manda el servidor', () => {
      const detalle: DetalleCaja = conCobranzas({
        ...sinCobranzas,
        cobranzas: { EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 300, CUENTA_CORRIENTE: 0 },
        totalCobranzas: 1000,
      });

      expect(detalle.cobranzas).toEqual({ EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 300, CUENTA_CORRIENTE: 0 });
      expect(detalle.totalCobranzas).toBe(1000);
    });

    it('una respuesta sin el campo (caja vieja) se lee con 0 cobrado en cada medio', () => {
      const detalle = conCobranzas(sinCobranzas);

      expect(detalle.cobranzas).toEqual({ EFECTIVO: 0, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 });
      expect(detalle.totalCobranzas).toBe(0);
    });

    it('un medio que falta en el mapa se completa con 0 y lo demás se conserva', () => {
      const detalle = conCobranzas({ ...sinCobranzas, cobranzas: { EFECTIVO: 250 } as DetalleCaja['cobranzas'] });

      expect(detalle.cobranzas.EFECTIVO).toBe(250);
      expect(detalle.cobranzas.TRANSFERENCIA).toBe(0);
    });

    it('no toca el resto del detalle', () => {
      const detalle = conCobranzas(sinCobranzas);

      expect(detalle.efectivoEsperado).toBe(6700);
      expect(detalle.ventas).toEqual(sinCobranzas.ventas);
      expect(detalle.totalVentas).toBe(1000);
    });

    describe('anulaciones del detalle (CC-081, CC-100)', () => {
      it('conserva las anulaciones por medio y el total que manda el servidor', () => {
        const detalle = conCobranzas({
          ...sinCobranzas,
          anulaciones: { EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 300, CUENTA_CORRIENTE: 0 },
          totalAnulaciones: 1000,
        });

        expect(detalle.anulaciones).toEqual({ EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 300, CUENTA_CORRIENTE: 0 });
        expect(detalle.totalAnulaciones).toBe(1000);
      });

      it('con la clave presente, un medio que falta se completa con 0 y el total que falta también', () => {
        const detalle = conCobranzas({ ...sinCobranzas, anulaciones: { EFECTIVO: 250 } as DetalleCaja['cobranzas'] });

        expect(detalle.anulaciones).toEqual({ EFECTIVO: 250, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 });
        expect(detalle.totalAnulaciones).toBe(0);
      });

      it('con la clave ausente (backend viejo) NO la inventa: queda undefined y no falla', () => {
        const detalle = conCobranzas(sinCobranzas);

        expect(detalle.anulaciones).toBeUndefined();
        expect(detalle.totalAnulaciones).toBeUndefined();
        expect('anulaciones' in detalle).toBe(false);
        // Lo demás se completa igual que antes.
        expect(detalle.cobranzas.EFECTIVO).toBe(0);
        expect(detalle.efectivoEsperado).toBe(6700);
      });

      it('las anulaciones no tocan las cobranzas ni el efectivo esperado que manda el servidor', () => {
        const detalle = conCobranzas({
          ...sinCobranzas,
          cobranzas: { EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 },
          totalCobranzas: 700,
          anulaciones: { EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 },
          totalAnulaciones: 700,
        });

        expect(detalle.cobranzas.EFECTIVO).toBe(700);
        expect(detalle.efectivoEsperado).toBe(6700);
      });

      it('CajaAbierta y CajaVigente no llevan anulaciones (cierre ciego)', () => {
        const abierta: CajaAbierta = {
          id: 4,
          estado: 'ABIERTA',
          fechaApertura: '2026-10-16T08:00:00',
          abiertaPor: 'Ana',
          efectivoInicial: 1000,
          // @ts-expect-error la caja abierta que ve cualquier rol no tiene anulaciones
          anulaciones: { EFECTIVO: 1 },
        };
        const vigente: CajaVigente = {
          estado: 'SIN_CAJA',
          caja: null,
          efectivoPropuesto: 0,
          // @ts-expect-error la caja vigente tampoco
          totalAnulaciones: 1,
        };

        // La verificación es de tipos (@ts-expect-error): si CajaAbierta o CajaVigente ganaran esos campos, no compila.
        expect([abierta.efectivoInicial, vigente.efectivoPropuesto]).toEqual([1000, 0]);
      });
    });

    it('CajaAbierta y CajaVigente no llevan cifras de cobranzas (cierre ciego)', () => {
      const abierta: CajaAbierta = {
        id: 4,
        estado: 'ABIERTA',
        fechaApertura: '2026-10-16T08:00:00',
        abiertaPor: 'Ana',
        efectivoInicial: 1000,
        // @ts-expect-error la caja abierta que ve cualquier rol no tiene cobranzas ni totales
        cobranzas: { EFECTIVO: 1 },
      };
      const vigente: CajaVigente = {
        estado: 'SIN_CAJA',
        caja: null,
        efectivoPropuesto: 0,
        // @ts-expect-error la caja vigente tampoco
        totalCobranzas: 1,
      };

      expect(Object.keys(abierta)).not.toContain('totalCobranzas');
      expect(Object.keys(vigente)).not.toContain('cobranzas');
    });
  });
});
