/** Lo más que pesa un .crt que se sube (S4): el servidor lo rechaza igual. */
export const TAMANO_MAXIMO_CERTIFICADO = 16 * 1024;

export const MENSAJE_CERTIFICADO_GRANDE = 'El certificado no puede pasar de 16 KB.';

/**
 * El .crt que bajó el Dueño de ARCA, como texto para el JSON (S4): un PEM va tal cual (el servidor normaliza los fines de
 * línea) y un DER binario va en Base64. Se lee en el navegador; nunca hay una clave privada en juego.
 */
export function leerCertificado(archivo: File): Promise<string> {
  return new Promise((resolver, rechazar) => {
    const lector = new FileReader();
    lector.onload = () => resolver(comoTexto(new Uint8Array(lector.result as ArrayBuffer)));
    lector.onerror = () => rechazar(lector.error);
    lector.readAsArrayBuffer(archivo);
  });
}

function comoTexto(bytes: Uint8Array): string {
  // Un byte por carácter: un PEM es ASCII, así que queda igual; un DER queda listo para btoa.
  let binario = '';
  for (const byte of bytes) binario += String.fromCharCode(byte);
  return binario.includes('-----BEGIN') ? binario : btoa(binario);
}
