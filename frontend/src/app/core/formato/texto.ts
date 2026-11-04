/** «Ferretería Núñez» → «ferreteria nunez»: para comparar sin mayúsculas ni tildes, como la base (utf8mb4_0900_ai_ci). */
export function normalizar(texto: string): string {
  return texto.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLocaleLowerCase('es-AR').trim();
}

/** Si `texto` contiene a `buscado`, sin distinguir mayúsculas ni tildes. */
export function contiene(texto: string, buscado: string): boolean {
  return normalizar(texto).includes(normalizar(buscado));
}
