import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { apiMessage } from '../../core/http/api-error';
import { Brand } from '../../shared/ui/brand';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { Icon } from '../../shared/ui/icon';
import { Select } from '../../shared/forms/select';
import { ThemeService } from '../../core/theme/theme.service';
import { ToastService } from '../../shared/feedback/toast.service';
import { AuthShowcase } from './auth-showcase';

@Component({
  selector: 'g-auth-page',
  imports: [FormsModule, RouterLink, Brand, Button, Input, Icon, Select, AuthShowcase],
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
  readonly showPassword = signal(false);
  readonly showConfirmation = signal(false);
  firstName = '';
  lastName = '';
  birthDate = '';
  phoneNumber = '';
  password = '';
  passwordConfirmation = '';
  email = '';

  readonly minPasswordCharacters = 15;
  readonly maxPasswordBytes = 72;

  passwordIsValid(): boolean {
    return (
      Array.from(this.password).length >= this.minPasswordCharacters &&
      new TextEncoder().encode(this.password).length <= this.maxPasswordBytes
    );
  }

  birthDateIsValid(): boolean {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(this.birthDate)) return false;
    const parsed = new Date(this.birthDate + 'T00:00:00Z');
    return !Number.isNaN(parsed.valueOf()) && parsed.toISOString().startsWith(this.birthDate)
      && this.birthDate >= '1900-01-01' && this.birthDate < new Date().toISOString().slice(0, 10);
  }

  passwordsMatch(): boolean {
    return this.password.length > 0 && this.password === this.passwordConfirmation;
  }

  async submit() {
    if (this.busy() || !this.phoneNumber.trim() || !this.password) return;
    if (this.registerMode) {
      if (!this.firstName.trim() || !this.lastName.trim() || !this.birthDateIsValid()) {
        this.error.set('Renseignez votre prénom, votre nom et une date de naissance valide.');
        return;
      }
      if (!this.passwordIsValid()) {
        this.error.set('Choisissez au moins 15 caractères, dans la limite de 72 octets UTF-8.');
        return;
      }
      if (!this.passwordsMatch()) {
        this.error.set('Les deux mots de passe ne correspondent pas.');
        return;
      }
    }

    this.busy.set(true);
    this.error.set('');
    try {
      if (this.registerMode) {
        await this.auth.register({
          phoneNumber: this.phoneNumber.trim(),
          firstName: this.firstName.trim(),
          lastName: this.lastName.trim(),
          birthDate: this.birthDate,
          password: this.password,
          ...(this.email.trim() ? { email: this.email.trim() } : {}),
        });
        this.password = '';
        this.passwordConfirmation = '';
        this.toast.show('Demande prise en compte. Vous pouvez essayer de vous connecter.');
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
