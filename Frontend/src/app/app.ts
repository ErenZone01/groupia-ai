import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/theme/theme.service';
import { Toast } from './shared/feedback/toast';
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, Toast],
  template: '<router-outlet /><g-toast />',
})
export class App {
  readonly theme = inject(ThemeService);
}
