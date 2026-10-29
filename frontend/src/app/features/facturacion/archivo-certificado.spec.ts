import { leerCertificado } from './archivo-certificado';

describe('leerCertificado', () => {
  it('un PEM va tal cual, como texto', async () => {
    const pem = '-----BEGIN CERTIFICATE-----\r\nMIIBszCCAVmgAwIBAgIU\r\n-----END CERTIFICATE-----\r\n';

    expect(await leerCertificado(new File([pem], 'ferreteria.crt'))).toBe(pem);
  });

  it('un DER (binario) va en Base64', async () => {
    const der = new Uint8Array([0x30, 0x82, 0x01, 0xb3, 0x00, 0xff, 0x80]);

    expect(await leerCertificado(new File([der], 'ferreteria.cer'))).toBe('MIIBswD/gA==');
  });
});
