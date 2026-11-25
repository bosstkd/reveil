import { validateEmail, validatePassword } from '../auth/validation';

describe('validation', () => {
  it.each(['', 'alice', 'alice@', 'alice@example', 'a b@example.com'])('refuse l’e-mail « %s »', (e) => {
    expect(validateEmail(e)).not.toBeNull();
  });

  it('accepte un e-mail valide', () => {
    expect(validateEmail(' alice@example.com ')).toBeNull();
  });

  it('exige 8 caractères dont 1 chiffre', () => {
    expect(validatePassword('')).toMatch(/obligatoire/);
    expect(validatePassword('abc1234')).toMatch(/8 caractères/);
    expect(validatePassword('sanschiffre')).toMatch(/1 chiffre/);
    expect(validatePassword('abcdefg1')).toBeNull();
  });
});
