// noble-wrapper.js - Sets up noble libraries as globals for WASM access
import * as secp256k1 from '@noble/secp256k1';
import { sha256 } from '@noble/hashes/sha256';

// Expose to global scope for WASM access
globalThis.nobleSecp256k1 = secp256k1;
globalThis.nobleSha256 = sha256;

// Export for direct imports
export { secp256k1, sha256 };
