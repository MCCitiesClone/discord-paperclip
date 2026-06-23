export type EditableConfig = {
  groupRoleMap: Record<string, string>;
  linkedAccounts: Record<string, string>;
};

export type SessionPayload = {
  type: "paperclip-editor-session";
  createdAt: number;
  expiresAt: number;
  channelId: string;
  serverPublicKey: string;
  config: EditableConfig;
};

export type SignedFrame = {
  msg: string;
  signature: string;
};

export async function generateEditorKeys() {
  return crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
}

export async function exportPublicKey(publicKey: CryptoKey) {
  const bytes = await crypto.subtle.exportKey("spki", publicKey);
  return arrayBufferToBase64(bytes);
}

export async function importServerPublicKey(encoded: string) {
  return crypto.subtle.importKey(
    "spki",
    base64ToArrayBuffer(encoded),
    {
      name: "RSASSA-PKCS1-v1_5",
      hash: "SHA-256",
    },
    false,
    ["verify"],
  );
}

export async function signFrame(message: string, privateKey: CryptoKey) {
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    privateKey,
    new TextEncoder().encode(message),
  );
  return arrayBufferToBase64(signature);
}

export async function verifyFrame(
  frame: SignedFrame,
  publicKey: CryptoKey,
) {
  return crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    publicKey,
    base64ToArrayBuffer(frame.signature),
    new TextEncoder().encode(frame.msg),
  );
}

export function makeNonce() {
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  return Array.from(bytes)
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

export async function signedPacket(
  packet: Record<string, unknown>,
  privateKey: CryptoKey,
) {
  const msg = JSON.stringify(packet);
  return JSON.stringify({
    msg,
    signature: await signFrame(msg, privateKey),
  });
}

export async function fetchPayload<T>(bytebinUrl: string, payloadId: string) {
  const response = await fetch(`${bytebinUrl.replace(/\/$/, "")}/${payloadId}`);
  if (!response.ok) {
    throw new Error(`bytebin returned HTTP ${response.status}`);
  }
  return (await response.json()) as T;
}

export async function uploadPayload(bytebinUrl: string, body: unknown) {
  const response = await fetch(bytebinUrl.replace(/\/$/, ""), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`bytebin returned HTTP ${response.status}`);
  }
  const payload = (await response.json()) as { key?: string; id?: string };
  const id = payload.key ?? payload.id;
  if (!id) {
    throw new Error("bytebin response did not include key or id");
  }
  return id;
}

function arrayBufferToBase64(buffer: ArrayBuffer) {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

function base64ToArrayBuffer(value: string) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes.buffer;
}
