import {
  AfterViewInit, Component, DestroyRef, ElementRef, effect, inject, input, output,
  signal, viewChild,
} from '@angular/core';
import type { Map as MapLibreMap, Marker as MapLibreMarker } from 'maplibre-gl';

export interface DirectGeoPoint {
  latitude: number;
  longitude: number;
}

export function validDirectGeoPoint(point: DirectGeoPoint): boolean {
  return Number.isFinite(point.latitude) && point.latitude >= -90 && point.latitude <= 90
    && Number.isFinite(point.longitude) && point.longitude >= -180 && point.longitude <= 180;
}

/**
 * Lightweight, lazy-loaded map. No SDK or customer GPS is loaded until the buyer
 * opens the location picker; map tiles are requested from OpenFreeMap.
 * This is an address preview, NOT a server-verified delivery eligibility decision.
 */
@Component({
  selector: 'g-direct-location-map',
  template: `
    <div #canvas class="location-map" aria-label="Carte pour sélectionner un lieu de livraison"></div>
    @if (mapError()) {
      <p class="map-error" role="alert">{{ mapError() }}</p>
    } @else {
      <p class="map-help">Touchez la carte ou déplacez le repère pour choisir votre adresse.</p>
    }
    <small class="attribution">Carte : © OpenStreetMap contributors · OpenFreeMap</small>
  `,
  styles: `
    :host { display:block; min-width:0; }
    .location-map { height:310px; width:100%; border:1px solid var(--border); border-radius:12px;
      overflow:hidden; background:var(--surface-alt); }
    .map-error { color:var(--danger-text); font-size:13px; padding:12px; }
    .map-help,.attribution { display:block; color:var(--muted); font-size:12px; line-height:1.6; margin-top:9px; }
    @media(max-width:600px) { .location-map { height:260px; } }
  `,
})
export class DirectLocationMap implements AfterViewInit {
  readonly point = input<DirectGeoPoint | null>(null);
  readonly pointSelected = output<DirectGeoPoint>();
  readonly mapError = signal('');
  private readonly canvas = viewChild.required<ElementRef<HTMLDivElement>>('canvas');
  private readonly destroyRef = inject(DestroyRef);
  private map: MapLibreMap | null = null;
  private marker: MapLibreMarker | null = null;
  private disposed = false;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.disposed = true;
      this.marker?.remove();
      this.map?.remove();
      this.marker = null;
      this.map = null;
    });
    effect(() => {
      const point = this.point();
      if (point && validDirectGeoPoint(point)) this.updateMarker(point);
    });
  }

  ngAfterViewInit(): void {
    void this.initialize();
  }

  private async initialize() {
    try {
      const lib = await import('maplibre-gl');
      if (this.disposed) return;
      lib.setWorkerUrl(new URL('maplibre-gl-worker.mjs', document.baseURI).href);
      const center = this.point();
      this.map = new lib.Map({
        container: this.canvas().nativeElement,
        style: 'https://tiles.openfreemap.org/styles/positron',
        center: center && validDirectGeoPoint(center)
          ? [center.longitude, center.latitude] : [-17.4677, 14.7167],
        zoom: center ? 15 : 11,
        attributionControl: true,
      });
      this.map.addControl(new lib.NavigationControl({ showCompass: false }), 'top-right');
      this.map.on('click', (event) => {
        this.pointSelected.emit({
          latitude: Number(event.lngLat.lat.toFixed(6)),
          longitude: Number(event.lngLat.lng.toFixed(6)),
        });
      });
      this.map.on('error', () => {
        // Tiles or WebGL may be unavailable. The manual form remains accessible.
        this.mapError.set('La carte est indisponible. Vous pouvez saisir votre adresse manuellement.');
      });
      const point = this.point();
      if (point && validDirectGeoPoint(point)) this.updateMarker(point);
    } catch {
      if (!this.disposed) {
        this.mapError.set('La carte est indisponible. Vous pouvez saisir votre adresse manuellement.');
      }
    }
  }

  private async updateMarker(point: DirectGeoPoint) {
    if (!this.map || this.disposed) return;
    if (this.marker) {
      this.marker.setLngLat([point.longitude, point.latitude]);
    } else {
      const lib = await import('maplibre-gl');
      if (this.disposed || !this.map) return;
      this.marker = new lib.Marker({ draggable: true, color: '#126044' })
        .setLngLat([point.longitude, point.latitude]).addTo(this.map);
      this.marker.on('dragend', () => {
        const position = this.marker?.getLngLat();
        if (position) {
          this.pointSelected.emit({
            latitude: Number(position.lat.toFixed(6)),
            longitude: Number(position.lng.toFixed(6)),
          });
        }
      });
    }
    this.map.easeTo({ center: [point.longitude, point.latitude], duration: 250 });
  }
}
