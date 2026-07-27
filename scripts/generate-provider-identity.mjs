import { generateKeyPairSync } from "node:crypto";
import { open } from "node:fs/promises";

const output = process.argv[2];
if (!output?.startsWith("/")) {
  throw new Error("absolute output path is required");
}

const alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
function base58(bytes) {
  const digits = [0];
  for (const byte of bytes) {
    let carry = byte;
    for (let i = 0; i < digits.length; i += 1) {
      const value = digits[i] * 256 + carry;
      digits[i] = value % 58;
      carry = Math.floor(value / 58);
    }
    while (carry > 0) {
      digits.push(carry % 58);
      carry = Math.floor(carry / 58);
    }
  }
  const leading = bytes.findIndex((byte) => byte !== 0);
  const zeroes = leading < 0 ? bytes.length : leading;
  return "1".repeat(zeroes) + digits.reverse().map((digit) => alphabet[digit]).join("");
}

const { privateKey, publicKey } = generateKeyPairSync("ed25519");
const privateJwk = privateKey.export({ format: "jwk" });
const publicJwk = publicKey.export({ format: "jwk" });
const publicBytes = Buffer.from(publicJwk.x, "base64url");
const did = `did:key:z${base58(Buffer.concat([Buffer.from([0xed, 0x01]), publicBytes]))}`;
const seed = Buffer.from(privateJwk.d, "base64url").toString("base64");
const file = await open(output, "wx", 0o600);
try {
  await file.writeFile(
    `COMMITMENT_LEDGER_ACTOR_DID=${did}\nCOMMITMENT_LEDGER_ACTOR_SEED=${seed}\n`,
    { encoding: "utf8" },
  );
} finally {
  await file.close();
}
console.log(did);
