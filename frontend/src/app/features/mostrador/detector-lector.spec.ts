import { DetectorLector, GAP_MAXIMO_RAFAGA_MS, LARGO_MINIMO_RAFAGA } from './detector-lector';

describe('DetectorLector', () => {
  let detector: DetectorLector;

  /** Teclea cada carácter de `texto` con `gap` ms entre teclas; devuelve el instante de la última. */
  function teclear(texto: string, inicio: number, gap: number): number {
    let ahora = inicio;
    for (const caracter of texto) {
      detector.tecla(caracter, ahora);
      ahora += gap;
    }
    return ahora - gap;
  }

  beforeEach(() => (detector = new DetectorLector()));

  it('usa los umbrales acordados: 30 ms entre teclas y al menos 4 caracteres', () => {
    expect(GAP_MAXIMO_RAFAGA_MS).toBe(30);
    expect(LARGO_MINIMO_RAFAGA).toBe(4);
  });

  it('una ráfaga de teclas rápidas terminada en Enter devuelve solo el contenido de la ráfaga', () => {
    const ultima = teclear('7790001', 1000, 10);

    expect(detector.alEnter(ultima + 10)).toBe('7790001');
  });

  it('tipeo lento (gaps mayores al umbral) no es ráfaga', () => {
    const ultima = teclear('7790001', 1000, 120);

    expect(detector.alEnter(ultima + 120)).toBeNull();
  });

  it('una ráfaga corta (menos del mínimo) no cuenta, aunque sea rápida', () => {
    const ultima = teclear('777', 1000, 10);

    expect(detector.alEnter(ultima + 10)).toBeNull();
  });

  it('justo en el mínimo de caracteres ya es ráfaga', () => {
    const ultima = teclear('7790', 1000, 10);

    expect(detector.alEnter(ultima + 10)).toBe('7790');
  });

  it('un gap justo igual al umbral ya no es ráfaga; uno apenas menor sí', () => {
    const lento = teclear('7790001', 1000, GAP_MAXIMO_RAFAGA_MS);
    expect(detector.alEnter(lento + GAP_MAXIMO_RAFAGA_MS)).toBeNull();

    const rapido = teclear('7790001', 5000, GAP_MAXIMO_RAFAGA_MS - 1);
    expect(detector.alEnter(rapido + GAP_MAXIMO_RAFAGA_MS - 1)).toBe('7790001');
  });

  it('tipeo humano previo seguido de una ráfaga: devuelve solo la ráfaga', () => {
    const humano = teclear('tor', 1000, 150);

    const ultima = teclear('7790001', humano + 400, 10);

    expect(detector.alEnter(ultima + 10)).toBe('7790001');
  });

  it('una ráfaga después de una pausa larga no arrastra la anterior', () => {
    const primera = teclear('1111111', 1000, 10);
    expect(detector.alEnter(primera + 10)).toBe('1111111');

    const ultima = teclear('2222222', primera + 5000, 10);

    expect(detector.alEnter(ultima + 10)).toBe('2222222');
  });

  it('una ráfaga seguida de tipeo humano antes del Enter no cuenta (el Enter lo dio una persona)', () => {
    const rapida = teclear('7790001', 1000, 10);

    expect(detector.alEnter(rapida + 800)).toBeNull();
  });

  it('un Enter sin teclas previas no es ráfaga', () => {
    expect(detector.alEnter(1000)).toBeNull();
  });

  it('el Enter cierra la ráfaga: un segundo Enter seguido no devuelve nada', () => {
    const ultima = teclear('7790001', 1000, 10);
    detector.alEnter(ultima + 10);

    expect(detector.alEnter(ultima + 20)).toBeNull();
  });

  it('reiniciar() descarta la ráfaga en curso (por ejemplo tras un Backspace)', () => {
    const ultima = teclear('7790001', 1000, 10);

    detector.reiniciar();

    expect(detector.alEnter(ultima + 10)).toBeNull();
  });
});
