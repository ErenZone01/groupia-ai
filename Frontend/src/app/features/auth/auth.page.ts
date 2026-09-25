import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { apiMessage } from '../../core/http/api-error';
import { Brand } from '../../shared/ui/brand';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
import { ThemeService } from '../../core/theme/theme.service';
import { ToastService } from '../../shared/feedback/toast.service';
@Component({
  selector: 'g-auth-page',
  imports: [FormsModule, RouterLink, Brand, Button, Input, Select],
  templateUrl: './auth.page.html',
  styleUrl: './auth.page.scss',
})
export class AuthPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);
  readonly theme = inject(ThemeService);
  readonly registerMode = this.route.snapshot.data['register'] === true;
  readonly expired = this.route.snapshot.queryParamMap.has('expired');
  readonly busy = signal(false);
  readonly error = signal('');
  phoneNumber = '';
  password = '';
  email = '';
  async submit() {
    if (this.busy()) return;
    if (
      this.registerMode &&
      (Array.from(this.password).length < 15 || new TextEncoder().encode(this.password).length > 72)
    ) {
      this.error.set('Choisissez au moins 15 caractères, dans la limite de 72 octets UTF-8.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    try {
      if (this.registerMode) {
        await this.auth.register({
          phoneNumber: this.phoneNumber.trim(),
          password: this.password,
          ...(this.email.trim() ? { email: this.email.trim() } : {}),
        });
        this.password = '';
        this.toast.show('Votre compte est créé. Vous pouvez vous connecter.');
        await this.router.navigateByUrl('/connexion');
      } else {
        await this.auth.login({ phoneNumber: this.phoneNumber.trim(), password: this.password });
        this.password = '';
        const target = this.route.snapshot.queryParamMap.get('returnUrl');
        await this.router.navigateByUrl(target?.startsWith('/app/') ? target : '/app/accueil');
      }
    } catch (e) {
      this.error.set(apiMessage(e));
    } finally {
      this.busy.set(false);
    }
  }
}
