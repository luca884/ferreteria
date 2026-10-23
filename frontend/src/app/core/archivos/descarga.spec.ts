import { HttpHeaders, HttpResponse } from '@angular/common/http';
import { vi } from 'vitest';
import { descargar, nombreDeArchivo } from './descarga';

describe('descarga', () => {
  const respuesta = (disposicion: string | null) =>
    new HttpResponse({
      body: new Blob(['a;b']),
      headers: disposicion ? new HttpHeaders({ 'Content-Disposition': disposicion }) : new HttpHeaders(),
    });

  it('toma el nombre del archivo que manda el servidor', () => {
    expect(nombreDeArchivo(respuesta('attachment; filename="articulos-2026-10-23.csv"'), 'otro.csv')).toBe(
      'articulos-2026-10-23.csv',
    );
    expect(nombreDeArchivo(respuesta('attachment; filename=lista.csv'), 'otro.csv')).toBe('lista.csv');
  });

  it('sin nombre del servidor usa el de reserva', () => {
    expect(nombreDeArchivo(respuesta(null), 'articulos.csv')).toBe('articulos.csv');
    expect(nombreDeArchivo(respuesta('attachment'), 'articulos.csv')).toBe('articulos.csv');
  });

  it('baja el contenido con ese nombre y libera la dirección temporal después del clic (Safari corta si es antes)', () => {
    vi.useFakeTimers();
    const crear = vi.fn(() => 'blob:temporal');
    const liberar = vi.fn();
    Object.assign(URL, { createObjectURL: crear, revokeObjectURL: liberar });
    let enlace: HTMLAnchorElement | undefined;
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      enlace = this;
    });

    const r = respuesta('attachment; filename="articulos.csv"');
    descargar(r, 'reserva.csv');

    expect(crear).toHaveBeenCalledWith(r.body);
    expect(enlace?.download).toBe('articulos.csv');
    expect(enlace?.href).toBe('blob:temporal');
    expect(enlace?.isConnected).toBe(false);
    expect(liberar).not.toHaveBeenCalled();
    vi.runAllTimers();
    expect(liberar).toHaveBeenCalledWith('blob:temporal');
    click.mockRestore();
    vi.useRealTimers();
  });
});
