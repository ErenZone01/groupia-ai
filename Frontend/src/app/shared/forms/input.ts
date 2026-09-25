import { Directive } from '@angular/core';
@Directive({ selector: 'input[gInput],textarea[gInput]', host: { class: 'g-input' } })
export class Input {}
