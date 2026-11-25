import { Component, ElementRef, input, model, output, viewChild } from '@angular/core';
import {
  CLIENTE_MAXIMO,
  DIAS_VALIDEZ_POR_DEFECTO,
  OBSERVACIONES_MAXIMO,
} from '../presupuestos/presupuesto.models';
import { GuardaLector } from './guarda-lector';

type Campo = 'cliente' | 'validez' | 'observaciones';

/**
 * Los datos del presupuesto dentro del panel del mostrador (mostrador-simple S10): validez en días, observaciones y, si el
 * ticket no tiene cliente elegido, el cliente de texto libre. Presentacional: el valor de cada campo vive en el
 * contenedor (`model`), que valida y guarda. Enter guarda, Esc devuelve el foco al buscador y un escaneo que cae en un
 * campo se descarta, igual que en el diálogo.
 */
@Component({
  selector: 'app-campos-presupuesto',
  template: `
    <div class="campos" role="group" aria-label="Datos del presupuesto" (keydown)="alTeclear($event)">
      @if (clienteElegido() === null) {
        <label class="campo">
          <span>Cliente <small>(opcional)</small></span>
          <input
            name="cliente"
            type="text"
            autocomplete="off"
            spellcheck="false"
            [maxLength]="clienteMaximo"
            [value]="cliente()"
            [disabled]="bloqueado()"
            (input)="alEscribir('cliente', $event)"
          />
        </label>
      }
      <label class="campo corto">
        <span>Validez en días</span>
        <input
          #validezCampo
          name="validez"
          type="text"
          inputmode="numeric"
          autocomplete="off"
          spellcheck="false"
          [value]="validez()"
          [disabled]="bloqueado()"
          [attr.aria-invalid]="error() ? 'true' : null"
          [attr.aria-describedby]="error() ? 'campos-presupuesto-error' : null"
          (input)="alEscribir('validez', $event)"
        />
      </label>
      <label class="campo">
        <span>Observaciones <small>(opcional)</small></span>
        <input
          name="observaciones"
          type="text"
          autocomplete="off"
          spellcheck="false"
          [maxLength]="observacionesMaximo"
          [value]="observaciones()"
          [disabled]="bloqueado()"
          (input)="alEscribir('observaciones', $event)"
        />
      </label>
      @if (error(); as mensaje) {
        <p id="campos-presupuesto-error" class="error" role="alert">{{ mensaje }}</p>
      }
      @if (aviso(); as texto) {
        <p class="aviso" role="status">{{ texto }}</p>
      }
    </div>
  `,
  styles: `
    .campos {
      display: flex;
      flex-direction: column;
      gap: var(--e-2);
    }
    .campo {
      display: flex;
      flex-direction: column;
      gap: 4px;
      font-size: var(--texto-sm);
      font-weight: 600;
    }
    .campo small {
      color: var(--texto-tenue);
      font-weight: 500;
    }
    /* La validez es corta: el rótulo y el número van en una sola fila para no gastar alto del panel. */
    .corto {
      flex-direction: row;
      align-items: center;
      justify-content: space-between;
    }
    .corto input {
      width: 5rem;
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
    .error,
    .aviso {
      margin: 0;
      font-size: var(--texto-sm);
    }
    .aviso {
      padding: var(--e-2) var(--e-3);
      border: 1px solid var(--advertencia-borde);
      border-left: 4px solid var(--advertencia-marca);
      border-radius: var(--radio);
      background: var(--advertencia-fondo);
      font-weight: 600;
    }
  `,
})
export class CamposPresupuesto {
  readonly cliente = model('');
  readonly validez = model(String(DIAS_VALIDEZ_POR_DEFECTO));
  readonly observaciones = model('');
  /** El cliente que el ticket ya tiene: reemplaza al texto libre. */
  readonly clienteElegido = input<string | null>(null);
  /** El error de la validez, que marca el contenedor al intentar guardar. */
  readonly error = input<string | null>(null);
  /** Un mensaje del contenedor (un precio que cambió, el servidor ocupado, un escaneo descartado). */
  readonly aviso = input<string | null>(null);
  /** Hay un POST en curso: los campos no se tocan. */
  readonly bloqueado = input(false);

  readonly guardar = output<void>();
  readonly salir = output<void>();
  readonly escaneoDescartado = output<void>();
  /** La persona escribió: el contenedor saca el error y el aviso que ya no vienen al caso. */
  readonly editado = output<void>();

  protected readonly clienteMaximo = CLIENTE_MAXIMO;
  protected readonly observacionesMaximo = OBSERVACIONES_MAXIMO;

  private readonly validezCampo = viewChild<ElementRef<HTMLInputElement>>('validezCampo');
  private readonly guarda = new GuardaLector();

  enfocarValidez(): void {
    this.validezCampo()?.nativeElement.focus();
  }

  protected alEscribir(campo: Campo, evento: Event): void {
    this.poner(campo, (evento.target as HTMLInputElement).value);
    this.editado.emit();
  }

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key === 'Escape') {
      evento.preventDefault();
      evento.stopPropagation();
      this.salir.emit();
      return;
    }
    const eraEscaneo = this.guarda.alTeclear(evento);
    if (evento.key !== 'Enter') return;
    evento.preventDefault();
    evento.stopPropagation();
    if (eraEscaneo) {
      this.restaurar(evento.target as HTMLInputElement);
      this.escaneoDescartado.emit();
      return;
    }
    this.guardar.emit();
  }

  /** El escaneo escribió en un campo: vuelve a lo de antes (vacío, o los 15 días de la validez). */
  private restaurar(campoDeTexto: HTMLInputElement): void {
    const campo = campoDeTexto.name as Campo;
    const inicial = campo === 'validez' ? String(DIAS_VALIDEZ_POR_DEFECTO) : '';
    campoDeTexto.value = inicial;
    this.poner(campo, inicial);
    this.editado.emit();
  }

  private poner(campo: Campo, valor: string): void {
    if (campo === 'cliente') this.cliente.set(valor);
    else if (campo === 'validez') this.validez.set(valor);
    else this.observaciones.set(valor);
  }
}
