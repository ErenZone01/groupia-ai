import { Component, input } from '@angular/core';
import { Icon } from '../../shared/ui/icon';

@Component({
  selector: 'g-auth-showcase',
  imports: [Icon],
  templateUrl: './auth-showcase.html',
  styleUrl: './auth-showcase.scss',
})
export class AuthShowcase {
  readonly registerMode = input(false);
}
