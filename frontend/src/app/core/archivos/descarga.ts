import { HttpResponse } from '@angular/common/http';

/** El nombre que manda el servidor en Content-Disposition, o `porDefecto` si no manda ninguno. */
export function nombreDeArchivo(respuesta: HttpResponse<Blob>, porDefecto: string): string {
  const disposicion = respuesta.headers.get('Content-Disposition') ?? '';
  const nombre = /filename="?([^";]+)"?/.exec(disposicion)?.[1];
  return nombre ?? porDefecto;
}

/** Baja el archivo de la respuesta con su nombre, sin salir de la pantalla. */
export function descargar(respuesta: HttpResponse<Blob>, porDefecto: string): void {
  const direccion = URL.createObjectURL(respuesta.body!);
  const enlace = document.createElement('a');
  enlace.href = direccion;
  enlace.download = nombreDeArchivo(respuesta, porDefecto);
  enlace.click();
  URL.revokeObjectURL(direccion);
}
