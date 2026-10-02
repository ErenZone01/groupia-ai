import { TestBed } from '@angular/core/testing';
import { LogisticsDetailsEditor } from './logistics-details-editor';
describe('Édition du dossier logistique', () => {
  function setup(mode: 'dossier' | 'eta' | 'fact') {
    const f = TestBed.createComponent(LogisticsDetailsEditor);
    f.componentRef.setInput('mode', mode);
    f.componentRef.setInput('version', 7);
    f.detectChanges();
    const save = vi.fn();
    f.componentInstance.save.subscribe(save);
    return { f, c: f.componentInstance, save };
  }
  it('prestataire entièrement facultatif', () => {
    const { f, c, save } = setup('dossier');
    c.submit();
    expect(save).toHaveBeenCalledWith({
      action: 'dossier',
      body: {
        version: 7,
        source: 'UNKNOWN',
        note: undefined,
        providerName: undefined,
        providerContact: undefined,
        trackingReference: undefined,
      },
    });
    f.destroy();
  });
  it('ETA vide signifie retrait de l’estimation, sans date inventée', () => {
    const { f, c, save } = setup('eta');
    c.submit();
    expect(save.mock.calls[0][0].body.expectedArrivalAt).toBeUndefined();
    f.destroy();
  });
  it('information reçue sans date réelle conserve source et date absente', () => {
    const { f, c, save } = setup('fact');
    c.type = 'FORWARDER_RECEIVED';
    c.source = 'SUPPLIER';
    c.submit();
    expect(save.mock.calls[0][0].body).toMatchObject({
      source: 'SUPPLIER',
      type: 'FORWARDER_RECEIVED',
    });
    expect(save.mock.calls[0][0].body.occurredAt).toBeUndefined();
    f.destroy();
  });
  it('correction exige un texte et conserve le texte saisi', () => {
    const { f, c, save } = setup('fact');
    c.submit();
    expect(save).not.toHaveBeenCalled();
    c.note = ' Correction de la référence précédente ';
    c.submit();
    expect(save.mock.calls[0][0].body.note).toBe('Correction de la référence précédente');
    f.destroy();
  });
  it('date future refusée', () => {
    const { f, c, save } = setup('fact');
    c.type = 'CUSTOMS_STARTED';
    c.occurred = '2999-01-01T00:00';
    c.submit();
    expect(save).not.toHaveBeenCalled();
    expect(c.error).toContain('future');
    f.destroy();
  });
  it('formulaire désactivé ne soumet rien', () => {
    const { f, c, save } = setup('dossier');
    f.componentRef.setInput('disabled', true);
    c.submit();
    expect(save).not.toHaveBeenCalled();
    f.destroy();
  });
});
