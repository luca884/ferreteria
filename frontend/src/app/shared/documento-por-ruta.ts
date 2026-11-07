import { ActivatedRoute } from '@angular/router';
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
