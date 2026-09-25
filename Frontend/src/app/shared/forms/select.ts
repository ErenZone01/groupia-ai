import { Directive } from '@angular/core';
@Directive({ selector: 'select[gSelect]', host: { class: 'g-input' } })
export class Select {}
