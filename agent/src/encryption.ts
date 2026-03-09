import crypto from 'crypto';
import { config } from './config';

/**
 * Decrypts AES-256-GCM encrypted tokens stored by the Kotlin backend.
 *
 * The encrypted format is Base64(IV[12 bytes] + ciphertext + auth_tag[16 bytes]),
 * matching the format produced by the backend's TokenEncryptionConverter.
 *
 * The agent only needs to DECRYPT tokens (to call Google APIs). It never
 * encrypts — that's the backend's responsibility during the OAuth flow.
 *
 * @param ciphertext Base64-encoded encrypted token from the database.
 * @returns The decrypted plaintext token.
 * @throws Error if decryption fails (wrong key, tampered data).
 */
export function decryptToken(ciphertext: string): string {
  const combined = Buffer.from(ciphertext, 'base64');

  if (combined.length <= 12) {
    throw new Error('Ciphertext too short to contain IV');
  }

  const iv = combined.subarray(0, 12);
  // GCM auth tag is the last 16 bytes of the ciphertext
  const authTag = combined.subarray(combined.length - 16);
  const encrypted = combined.subarray(12, combined.length - 16);

  const key = deriveKeyBytes(config.encryption.tokenKey);

  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAuthTag(authTag);

  const decrypted = Buffer.concat([
    decipher.update(encrypted),
    decipher.final(),
  ]);

  return decrypted.toString('utf8');
}

/**
 * Derives a 32-byte AES key from a config string.
 *
 * Matches the backend's key derivation logic exactly:
 * - Keys longer than 32 bytes are truncated.
 * - Keys shorter than 32 bytes are zero-padded.
 *
 * Keeping this behavior aligned is critical because the agent decrypts tokens
 * written by the backend and would otherwise silently fail with auth-tag errors.
 *
 * @param keyString Raw configured token-encryption secret.
 * @returns AES-256 key material suitable for `createDecipheriv`.
 */
function deriveKeyBytes(keyString: string): Buffer {
  const keyBytes = Buffer.from(keyString, 'utf8');
  if (keyBytes.length >= 32) {
    return keyBytes.subarray(0, 32);
  }
  const padded = Buffer.alloc(32);
  keyBytes.copy(padded);
  return padded;
}
