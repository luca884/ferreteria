import { ActivatedRoute, Router } from '@angular/router';
import { Observable, filter, map } from 'rxjs';

/**
 * El documento elegido en la dirección (`/ventas/:id`, `/presupuestos/:id`, `/remitos/:id`, informacion-y-links S2): cada
 * `:id` entero positivo que llega, también el de un link a otro documento con la pantalla ya abierta. Sin `:id`, nada.
 */
export function idsDeLaRuta(route: ActivatedRoute): Observable<number> {
  return route.paramMap.pipe(
    map((parametros) => Number(parametros.get('id'))),
    filter((id) => Number.isInteger(id) && id > 0),
  );
}

/**
 * El `state` de un link entre documentos: la dirección del documento donde está el link, para volver a él al cerrar el
 * que se abre (bugs-relevamiento-celular, S7).
 */
export function conOrigen(ruta: string): { origen: string } {
  return { origen: ruta };
}

/**
 * El documento de origen de la navegación en curso: el que puso {@link conOrigen} en el link. Null si el documento se
 * abrió por la dirección o desde su lista, que cierran como siempre. Solo se acepta una dirección propia (`/…`).
 */
export function origenDeLaNavegacion(router: Router): string | null {
  const origen = router.currentNavigation()?.extras.state?.['origen'];
  return typeof origen === 'string' && origen.startsWith('/') && !origen.startsWith('//') ? origen : null;
}
