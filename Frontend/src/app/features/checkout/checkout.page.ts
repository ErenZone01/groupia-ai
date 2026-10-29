import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { CheckoutStore, CheckoutContact, CustomerLocation } from './checkout.store';
import { PublicShell } from './public-shell';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { Icon } from '../../shared/ui/icon';
import { Select } from '../../shared/forms/select';
import { SENEGAL_REGIONS } from './senegal-localities';
import { publicShippingMessage } from '../public-groupage/public-order-info';
import { money } from '../../shared/utils/display';

@Component({
  selector: 'g-checkout-page',
  imports: [PublicShell, Button, Input, Icon, Select, FormsModule, RouterLink],
  templateUrl: './checkout.page.html',
  styleUrl: './checkout.page.scss',
})
export class CheckoutPage {
  readonly store = inject(CheckoutStore);
  private readonly router = inject(Router);
  readonly id =
    inject(ActivatedRoute).snapshot.paramMap.get('publicIdentifier') ??
    inject(ActivatedRoute).snapshot.paramMap.get('publicId')!;
  readonly money = money;
  readonly publicShippingMessage = publicShippingMessage;
  readonly regions = SENEGAL_REGIONS;
  readonly other = '__OTHER__';

  readonly location: CustomerLocation = {
    country: 'SN',
    region: '',
    city: '',
    commune: '',
    neighborhood: '',
    details: '',
  };
  contact: CheckoutContact = {
    firstName: '',
    lastName: '',
    phone: '',
    email: '',
    deliveryAddress: this.location,
  };
  cityChoice = '';
  communeChoice = '';

  get cities(): string[] {
    return this.regions.find((item) => item.name === this.location.region)?.departments ?? [];
  }

  get communes(): string[] {
    return this.regions.find((item) => item.name === this.location.region)?.communes ?? [];
  }

  constructor() {
    this.store.cart.select(this.id);
    this.store.error.set('');
  }

  changeRegion(region: string) {
    this.location.region = region;
    this.cityChoice = '';
    this.location.city = '';
    this.communeChoice = '';
    this.location.commune = '';
  }

  changeCity(value: string) {
    this.cityChoice = value;
    this.location.city = value === this.other ? '' : value;
    this.communeChoice = '';
    this.location.commune = '';
  }

  changeCommune(value: string) {
    this.communeChoice = value;
    this.location.commune = value === this.other ? '' : value;
  }

  async submit() {
    if (await this.store.submit(this.contact)) await this.router.navigateByUrl('/public/order');
  }
}
