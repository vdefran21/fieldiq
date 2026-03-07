import crypto from 'crypto';

/**
 * Tests for the agent layer's token decryption.
 *
 * Verifies that the TypeScript decryption matches the Kotlin backend's
 * AES-256-GCM encryption format: Base64(IV[12] + ciphertext + authTag[16]).
 */

// Import the function under test
import { decryptToken } from '../encryption';

// Mock config to use a known test key
jest.mock('../config', () => ({
  config: {
    encryption: {
      tokenKey: 'test-encryption-key-exactly-32b!',
    },
  },
}));

describe('encryption', () => {
  /**
   * Helper: encrypt a string using the same AES-256-GCM format as the backend.
   * This simulates what TokenEncryptionConverter.encrypt() produces.
   */
  function encryptLikeBackend(plaintext: string, keyString: string): string {
    const keyBytes = Buffer.from(keyString, 'utf8').subarray(0, 32);
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', keyBytes, iv);
    const encrypted = Buffer.concat([
      cipher.update(plaintext, 'utf8'),
      cipher.final(),
    ]);
    const authTag = cipher.getAuthTag();
    // Backend format: IV + ciphertext + authTag (then Base64 the whole thing)
    const combined = Buffer.concat([iv, encrypted, authTag]);
    return combined.toString('base64');
  }

  describe('decryptToken', () => {
    it('decrypts a token encrypted in the backend format', () => {
      const original = 'ya29.a0AfH6SMBx-test-access-token';
      const encrypted = encryptLikeBackend(
        original,
        'test-encryption-key-exactly-32b!',
      );

      const decrypted = decryptToken(encrypted);
      expect(decrypted).toBe(original);
    });

    it('decrypts empty strings', () => {
      const encrypted = encryptLikeBackend(
        '',
        'test-encryption-key-exactly-32b!',
      );
      const decrypted = decryptToken(encrypted);
      expect(decrypted).toBe('');
    });

    it('decrypts long tokens', () => {
      const original = 'a'.repeat(1000);
      const encrypted = encryptLikeBackend(
        original,
        'test-encryption-key-exactly-32b!',
      );
      const decrypted = decryptToken(encrypted);
      expect(decrypted).toBe(original);
    });

    it('throws on tampered ciphertext', () => {
      const encrypted = encryptLikeBackend(
        'test-token',
        'test-encryption-key-exactly-32b!',
      );
      // Flip a character in the Base64 string
      const chars = encrypted.split('');
      const mid = Math.floor(chars.length / 2);
      chars[mid] = chars[mid] === 'A' ? 'B' : 'A';
      const tampered = chars.join('');

      expect(() => decryptToken(tampered)).toThrow();
    });

    it('throws on truncated ciphertext', () => {
      expect(() => decryptToken('dG9vLXNob3J0')).toThrow();
    });

    it('produces different ciphertexts for same plaintext (random IV)', () => {
      const token = 'test-token';
      const enc1 = encryptLikeBackend(
        token,
        'test-encryption-key-exactly-32b!',
      );
      const enc2 = encryptLikeBackend(
        token,
        'test-encryption-key-exactly-32b!',
      );
      expect(enc1).not.toBe(enc2);
      // But both should decrypt to the same value
      expect(decryptToken(enc1)).toBe(token);
      expect(decryptToken(enc2)).toBe(token);
    });
  });
});
