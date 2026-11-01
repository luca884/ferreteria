import { centavosDeImporte, normalizarImporte, posicionTrasNormalizar, textoDeImporte } from './importe';

describe('normalizarImporte', () => {
  it.each([
    ['', ''],
    ['5', '5'],
    ['5000', '5.000'],
    ['50000', '50.000'],
    ['1234567', '1.234.567'],
    ['1234,5', '1.234,5'],
    ['1234,56', '1.234,56'],
    ['0,5', '0,5'],
    ['0', '0'],
  ])('«%s» se ve como «%s»', (escrito, visto) => {
    expect(normalizarImporte(escrito)).toBe(visto);
  });

  it('el punto nunca se escribe: «50.000» es cincuenta mil, no cincuenta', () => {
    expect(normalizarImporte('50.000')).toBe('50.000');
    expect(normalizarImporte('1.5')).toBe('15');
  });

  it('descarta letras, signos y espacios, como al pegar «$ 1.234,56»', () => {
    expect(normalizarImporte('$ 1.234,56')).toBe('1.234,56');
    expect(normalizarImporte('-12a3')).toBe('123');
  });

  it('acepta una sola coma y corta los decimales de más', () => {
    expect(normalizarImporte('12,3,4')).toBe('12,34');
    expect(normalizarImporte('12,345')).toBe('12,34');
    expect(normalizarImporte('12,')).toBe('12,');
  });

  it('con 0 decimales la coma no entra', () => {
    expect(normalizarImporte('12,5', 0)).toBe('125');
  });

  it('el menos se acepta solo si el campo admite negativos, y solo adelante', () => {
    expect(normalizarImporte('-1500', 2, true)).toBe('-1.500');
    expect(normalizarImporte('-', 2, true)).toBe('-');
    expect(normalizarImporte('15-00', 2, true)).toBe('1.500');
    expect(normalizarImporte('-1500')).toBe('1.500');
  });

  it('una coma al principio arranca en 0 y los ceros a la izquierda se van', () => {
    expect(normalizarImporte(',5')).toBe('0,5');
    expect(normalizarImporte('0005')).toBe('5');
    expect(normalizarImporte('00,5')).toBe('0,5');
  });
});

describe('centavosDeImporte', () => {
  it.each([
    ['50.000', 5_000_000],
    ['1.234,56', 123_456],
    ['1.234,5', 123_450],
    ['12,', 1_200],
    ['0', 0],
  ])('«%s» son %d centavos', (texto, centavos) => {
    expect(centavosDeImporte(texto)).toBe(centavos);
  });

  it('vacío o sin dígitos es null', () => {
    expect(centavosDeImporte('')).toBeNull();
    expect(centavosDeImporte('  ')).toBeNull();
    expect(centavosDeImporte(',')).toBeNull();
  });

  it('lee también lo que no pasó por la máscara, sin tomar el punto como decimal', () => {
    expect(centavosDeImporte('50000')).toBe(5_000_000);
    expect(centavosDeImporte('1.500')).toBe(150_000);
  });

  it('más de dos decimales o algo que no es un importe es null', () => {
    expect(centavosDeImporte('1,234')).toBeNull();
    expect(centavosDeImporte('12a')).toBeNull();
    expect(centavosDeImporte('-5')).toBeNull();
  });
});

describe('textoDeImporte', () => {
  it.each([
    [null, ''],
    [0, '0'],
    [50000, '50.000'],
    [35.06, '35,06'],
    [1234.5, '1.234,50'],
    [-1234.5, '-1.234,50'],
    [-5, '-5'],
  ])('%s se escribe «%s»', (valor, texto) => {
    expect(textoDeImporte(valor)).toBe(texto);
  });
});

describe('posicionTrasNormalizar', () => {
  it('el cursor sigue al dígito que se acaba de escribir aunque aparezcan puntos', () => {
    // «5000|» → «5.000|»
    expect(posicionTrasNormalizar('5000', 4, '5.000')).toBe(5);
    // «12|345» (se escribió el 2 en el medio) → «12|.345»
    expect(posicionTrasNormalizar('12345', 2, '12.345')).toBe(2);
    // «1.2|34» → «1.234» sin el punto viejo: el cursor queda después del 2
    expect(posicionTrasNormalizar('1.234', 3, '1.234')).toBe(3);
  });

  it('un punto tecleado que se descarta no corre el cursor', () => {
    // «12.|» → «12|»
    expect(posicionTrasNormalizar('12.', 3, '12')).toBe(2);
  });

  it('nunca pasa del final', () => {
    expect(posicionTrasNormalizar('12,345', 6, '12,34')).toBe(5);
  });
});
