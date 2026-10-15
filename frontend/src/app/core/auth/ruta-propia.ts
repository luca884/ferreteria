/** Una ruta de la app: empieza con una sola «/» (no «//», que sería otro sitio) y no es el login. */
export function esRutaPropia(url: string | null | undefined): url is string {
  return !!url && url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/login');
}
