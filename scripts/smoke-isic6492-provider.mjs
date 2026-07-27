import { generateKeyPairSync } from "node:crypto";
import http from "node:http";
import { spawn } from "node:child_process";

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

function listen(server, port = 0) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server.address().port));
  });
}

function close(server) {
  return new Promise((resolve) => server.close(resolve));
}

const { privateKey, publicKey } = generateKeyPairSync("ed25519");
const privateJwk = privateKey.export({ format: "jwk" });
const publicJwk = publicKey.export({ format: "jwk" });
const publicBytes = Buffer.from(publicJwk.x, "base64url");
const did = `did:key:z${base58(Buffer.concat([Buffer.from([0xed, 0x01]), publicBytes]))}`;
const seed = Buffer.from(privateJwk.d, "base64url").toString("base64");

let upstreamObserved = false;
const upstream = http.createServer((request, response) => {
  let body = "";
  request.setEncoding("utf8");
  request.on("data", (chunk) => { body += chunk; });
  request.on("end", () => {
    const authorization = request.headers.authorization ?? "";
    const parsed = JSON.parse(body);
    upstreamObserved = authorization.startsWith("CACAO ")
      && parsed["requested-principal"] === 100000
      && parsed.jurisdiction === "JPN";
    const result = JSON.stringify({ id: "smoke-intake" });
    response.writeHead(200, {
      "content-type": "application/json",
      "content-length": Buffer.byteLength(result),
    });
    response.end(result);
  });
});
const upstreamPort = await listen(upstream);

const reservation = http.createServer();
const providerPort = await listen(reservation);
await close(reservation);

const provider = spawn(process.execPath, ["dist/isic6492-provider.cjs"], {
  cwd: new URL("..", import.meta.url),
  env: {
    ...process.env,
    COMMITMENT_LEDGER_ACTOR_DID: did,
    COMMITMENT_LEDGER_ACTOR_SEED: seed,
    ISIC6492_BASE_URL: `http://127.0.0.1:${upstreamPort}`,
    ISIC6492_PROVIDER_PORT: String(providerPort),
  },
  stdio: ["ignore", "pipe", "inherit"],
});

try {
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("provider startup timed out")), 10000);
    provider.once("exit", (code) => reject(new Error(`provider exited ${code}`)));
    provider.stdout.on("data", (chunk) => {
      if (chunk.toString().includes("provider listening")) {
        clearTimeout(timeout);
        resolve();
      }
    });
  });
  const response = await fetch(`http://127.0.0.1:${providerPort}/api/loan/intake`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      "requested-principal": 100000,
      jurisdiction: "JPN",
      "borrower-org-repo": "smoke/test",
      purpose: "provider qualification",
    }),
  });
  const result = await response.json();
  if (!response.ok || !result["ok?"] || result.id !== "smoke-intake" || !upstreamObserved) {
    throw new Error("provider did not preserve the bounded authenticated request");
  }
  console.log("isic6492 provider smoke: ok");
} finally {
  provider.kill("SIGTERM");
  await close(upstream);
}
