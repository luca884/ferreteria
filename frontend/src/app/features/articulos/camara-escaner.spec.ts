import { vi } from 'vitest';
import { CamaraEscaner, causaDeFalla } from './camara-escaner';

/**
 * movil-sectores T7: el envoltorio de las APIs del navegador (cámara y `BarcodeDetector`). La regla de Luca (L18): la
 * app NUNCA recuerda un permiso negado; cada `abrir()` vuelve a llamar a `getUserMedia`.
 */
describe('CamaraEscaner', () => {
  const pista = (capacidades: Record<string, unknown> = {}) => ({
    stop: vi.fn(),
    getCapabilities: vi.fn(() => capacidades),
    applyConstraints: vi.fn(async () => undefined),
  });
  const flujo = (pistas = [pista()]) => ({ getTracks: () => pistas, getVideoTracks: () => pistas }) as unknown as MediaStream;

  let getUserMedia: ReturnType<typeof vi.fn>;
  let detectar: ReturnType<typeof vi.fn>;
  let formatosDelDetector: string[] | undefined;

  function instalarDetector(soportados: string[] | null = null) {
    detectar = vi.fn(async () => [] as { rawValue: string }[]);
    formatosDelDetector = undefined;
    class FalsoDetector {
      constructor(opciones?: { formats?: string[] }) {
        formatosDelDetector = opciones?.formats;
      }
      detect = detectar;
      static getSupportedFormats = soportados === null ? undefined : async () => soportados;
    }
    vi.stubGlobal('BarcodeDetector', FalsoDetector);
  }
  function instalarCamara(resultado: () => Promise<MediaStream>) {
    getUserMedia = vi.fn(resultado);
    Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia }, configurable: true });
  }
  const video = (listo = true) => ({ readyState: listo ? 4 : 0 }) as unknown as HTMLVideoElement;

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    Reflect.deleteProperty(navigator, 'mediaDevices');
  });

  describe('disponible()', () => {
    it('es true sólo con BarcodeDetector y getUserMedia', async () => {
      instalarDetector();
      instalarCamara(async () => flujo());
      await expect(new CamaraEscaner().disponible()).resolves.toBe(true);
    });

    it('es false sin BarcodeDetector (iPhone/Safari, Firefox)', async () => {
      instalarCamara(async () => flujo());
      await expect(new CamaraEscaner().disponible()).resolves.toBe(false);
    });

    it('es false sin getUserMedia', async () => {
      instalarDetector();
      await expect(new CamaraEscaner().disponible()).resolves.toBe(false);
      Object.defineProperty(navigator, 'mediaDevices', { value: {}, configurable: true });
      await expect(new CamaraEscaner().disponible()).resolves.toBe(false);
    });
  });

  describe('abrir()', () => {
    it('pide la cámara trasera, sin audio', async () => {
      instalarDetector();
      instalarCamara(async () => flujo());

      await new CamaraEscaner().abrir();

      expect(getUserMedia).toHaveBeenCalledTimes(1);
      const pedido = getUserMedia.mock.calls[0][0] as MediaStreamConstraints;
      expect(pedido.audio).toBe(false);
      expect((pedido.video as MediaTrackConstraints).facingMode).toEqual({ ideal: 'environment' });
    });

    it('un permiso negado NO se recuerda: cada abrir() vuelve a llamar a getUserMedia y no guarda nada', async () => {
      instalarDetector();
      instalarCamara(async () => {
        throw new DOMException('Permission denied', 'NotAllowedError');
      });
      const guardar = vi.spyOn(Storage.prototype, 'setItem');
      const leer = vi.spyOn(Storage.prototype, 'getItem');
      const permisos = vi.fn();
      Object.defineProperty(navigator, 'permissions', { value: { query: permisos }, configurable: true });
      const camara = new CamaraEscaner();

      await expect(camara.abrir()).rejects.toMatchObject({ name: 'NotAllowedError' });
      await expect(camara.abrir()).rejects.toMatchObject({ name: 'NotAllowedError' });
      await expect(new CamaraEscaner().abrir()).rejects.toMatchObject({ name: 'NotAllowedError' });

      expect(getUserMedia).toHaveBeenCalledTimes(3);
      expect(guardar).not.toHaveBeenCalled();
      expect(leer).not.toHaveBeenCalled();
      expect(permisos).not.toHaveBeenCalled();
      Reflect.deleteProperty(navigator, 'permissions');
    });

    it('si antes se negó y ahora se permite, abre', async () => {
      instalarDetector();
      let intento = 0;
      instalarCamara(async () => {
        if (++intento === 1) throw new DOMException('x', 'NotAllowedError');
        return flujo();
      });
      const camara = new CamaraEscaner();
      await expect(camara.abrir()).rejects.toBeDefined();
      await expect(camara.abrir()).resolves.toBeDefined();
    });

    it('si no se puede armar el lector, apaga la cámara y falla', async () => {
      const pistas = [pista()];
      instalarCamara(async () => flujo(pistas));
      vi.stubGlobal(
        'BarcodeDetector',
        class {
          constructor() {
            throw new TypeError('formato no soportado');
          }
        },
      );

      await expect(new CamaraEscaner().abrir()).rejects.toBeInstanceOf(TypeError);
      expect(pistas[0].stop).toHaveBeenCalledTimes(1);
    });
  });

  describe('la sesión', () => {
    it('lee con los formatos de góndola que el navegador soporta', async () => {
      instalarDetector(['ean_13', 'qr_code', 'code_128', 'upc_a']);
      instalarCamara(async () => flujo());

      await new CamaraEscaner().abrir();

      expect(formatosDelDetector).toEqual(['ean_13', 'upc_a', 'code_128']);
    });

    it('sin getSupportedFormats pide los seis formatos', async () => {
      instalarDetector();
      instalarCamara(async () => flujo());

      await new CamaraEscaner().abrir();

      expect(formatosDelDetector).toEqual(['ean_13', 'ean_8', 'upc_a', 'upc_e', 'code_128', 'code_39']);
    });

    it('leer() devuelve el primer código, o null si no hay ninguno', async () => {
      instalarDetector();
      instalarCamara(async () => flujo());
      const sesion = await new CamaraEscaner().abrir();

      expect(await sesion.leer(video())).toBeNull();
      detectar.mockResolvedValueOnce([{ rawValue: '7790001000123' }, { rawValue: 'otro' }]);
      expect(await sesion.leer(video())).toBe('7790001000123');
    });

    it('leer() no consulta el detector hasta que el video tiene imagen', async () => {
      instalarDetector();
      instalarCamara(async () => flujo());
      const sesion = await new CamaraEscaner().abrir();

      expect(await sesion.leer(video(false))).toBeNull();
      expect(detectar).not.toHaveBeenCalled();
    });

    it('detener() apaga todas las pistas', async () => {
      const pistas = [pista(), pista()];
      instalarDetector();
      instalarCamara(async () => flujo(pistas));
      const sesion = await new CamaraEscaner().abrir();

      sesion.detener();

      expect(pistas[0].stop).toHaveBeenCalledTimes(1);
      expect(pistas[1].stop).toHaveBeenCalledTimes(1);
    });

    it('la linterna sólo existe si la pista la soporta, y se prende con applyConstraints', async () => {
      instalarDetector();
      const conLinterna = pista({ torch: true });
      instalarCamara(async () => flujo([conLinterna]));
      const sesion = await new CamaraEscaner().abrir();
      expect(sesion.tieneLinterna).toBe(true);

      await sesion.linterna(true);
      expect(conLinterna.applyConstraints).toHaveBeenCalledWith({ advanced: [{ torch: true }] });

      instalarCamara(async () => flujo([pista({})]));
      expect((await new CamaraEscaner().abrir()).tieneLinterna).toBe(false);
    });
  });

  describe('causaDeFalla()', () => {
    const falla = (nombre: string) => new DOMException('x', nombre);
    it('distingue permiso negado, sin cámara y cualquier otra falla', () => {
      expect(causaDeFalla(falla('NotAllowedError'))).toBe('denegada');
      expect(causaDeFalla(falla('SecurityError'))).toBe('denegada');
      expect(causaDeFalla(falla('NotFoundError'))).toBe('sin-camara');
      expect(causaDeFalla(falla('OverconstrainedError'))).toBe('sin-camara');
      expect(causaDeFalla(falla('NotReadableError'))).toBe('otra');
      expect(causaDeFalla(new Error('raro'))).toBe('otra');
      expect(causaDeFalla(undefined)).toBe('otra');
    });
  });
});
