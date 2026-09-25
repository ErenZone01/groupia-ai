import { initials } from './initials';
describe('initials', () => {
  it.each([
    ['Awa Diop', 'AD'],
    ['  Fatou  Ndiaye  ', 'FN'],
    ['Élodie', 'É'],
    ['', ''],
  ])('convertit %s', (value, expected) => expect(initials(value)).toBe(expected));
});
