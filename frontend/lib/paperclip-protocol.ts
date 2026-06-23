export type EditableConfig = {
  groupRoleMap: Record<string, string>;
  roleGroupMap: Record<string, string>;
  linkedAccounts: Record<string, string>;
};

export type SessionPayload = {
  type: "paperclip-editor-session";
  createdAt: number;
  expiresAt: number;
  channelId: string;
  serverPublicKey: string;
  availableGroups?: string[];
  groups?: GroupInfo[];
  availableDiscordRoles?: DiscordRole[];
  config: EditableConfig;
};

/**
 * The payload the plugin re-uploads after applying changes. Mirrors the data half of
 * {@link SessionPayload} so the editor can refresh its tables and role-management page.
 */
export type RefreshedPayload = {
  config: EditableConfig;
  availableGroups?: string[];
  groups?: GroupInfo[];
  availableDiscordRoles?: DiscordRole[];
};

export type DiscordRole = {
  id: string;
  name: string;
  color?: number;
  position?: number;
};

export type GroupInfo = {
  name: string;
  displayName?: string;
  weight?: number;
};

/** A LuckPerms group the editor wants to exist, in priority order (first = highest weight). */
export type DesiredGroup = {
  name: string;
  displayName: string;
};

/** A Discord role the editor wants to exist, in display order (first = top). */
export type DesiredDiscordRole = {
  id: string | null;
  name: string;
  color: number;
};

export type SignedFrame = {
  msg: string;
  signature: string;
};

const editorKeyAlgorithm = {
  name: "RSASSA-PKCS1-v1_5",
  modulusLength: 2048,
  publicExponent: new Uint8Array([1, 0, 1]),
  hash: "SHA-256",
} as const;

type StoredEditorKeys = {
  publicKey: JsonWebKey;
  privateKey: JsonWebKey;
};

export async function generateEditorKeys() {
  return crypto.subtle.generateKey(
    editorKeyAlgorithm,
    true,
    ["sign", "verify"],
  );
}

export async function exportEditorKeys(keys: CryptoKeyPair) {
  return {
    publicKey: await crypto.subtle.exportKey("jwk", keys.publicKey),
    privateKey: await crypto.subtle.exportKey("jwk", keys.privateKey),
  };
}

export async function importEditorKeys(stored: StoredEditorKeys) {
  const publicKey = await crypto.subtle.importKey(
    "jwk",
    stored.publicKey,
    editorKeyAlgorithm,
    true,
    ["verify"],
  );
  const privateKey = await crypto.subtle.importKey(
    "jwk",
    stored.privateKey,
    editorKeyAlgorithm,
    true,
    ["sign"],
  );
  return { publicKey, privateKey };
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
  const response = await fetch(`${trimBytebinUrl(bytebinUrl)}/${encodeURIComponent(extractContentKey(payloadId))}`);
  if (!response.ok) {
    throw new Error(`bytebin returned HTTP ${response.status}`);
  }
  return (await response.json()) as T;
}

export async function uploadPayload(bytebinUrl: string, body: unknown) {
  const jsonBody = JSON.stringify(body);
  let response = await postBytebin(bytebinUrl, jsonBody, true).catch(() =>
    postBytebin(bytebinUrl, jsonBody, false),
  );
  if (response.status === 400 || response.status === 415 || response.status === 501) {
    response = await postBytebin(bytebinUrl, jsonBody, false);
  }
  if (response.status === 404 || response.status === 405) {
    return uploadPayloadLegacy(bytebinUrl, jsonBody);
  }
  if (!response.ok) {
    throw new Error(`bytebin returned HTTP ${response.status}`);
  }
  return parseContentKey(response);
}

export function websocketUrl(bytesocksUrl: string, channelId: string) {
  return `${normalizeWebSocketBaseUrl(bytesocksUrl)}/${encodeURIComponent(channelId)}`;
}

async function postBytebin(bytebinUrl: string, jsonBody: string, gzip: boolean) {
  const headers: HeadersInit = {
    "Content-Type": "application/json",
    Accept: "application/json",
  };
  let body: BodyInit = jsonBody;

  if (gzip && "CompressionStream" in globalThis) {
    headers["Content-Encoding"] = "gzip";
    body = new Blob([jsonBody])
      .stream()
      .pipeThrough(new CompressionStream("gzip"));
  }

  return fetch(`${trimBytebinUrl(bytebinUrl)}/post`, {
    method: "POST",
    headers,
    body,
  });
}

async function uploadPayloadLegacy(bytebinUrl: string, jsonBody: string) {
  const response = await fetch(trimBytebinUrl(bytebinUrl), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: jsonBody,
  });
  if (!response.ok) {
    throw new Error(`bytebin returned HTTP ${response.status}`);
  }
  return parseContentKey(response);
}

async function parseContentKey(response: Response) {
  const location = response.headers.get("Location");
  if (location) {
    return extractContentKey(location);
  }

  const text = await response.text();
  if (!text.trim()) {
    throw new Error("bytebin response did not include a Location header or response body");
  }

  const payload = JSON.parse(text) as { key?: string; id?: string; location?: string };
  const id = payload.key ?? payload.id ?? payload.location;
  if (!id?.trim()) {
    throw new Error("bytebin response did not include Location, key, or id");
  }
  return extractContentKey(id);
}

function trimBytebinUrl(bytebinUrl: string) {
  return bytebinUrl.trim().replace(/\/$/, "");
}

function normalizeWebSocketBaseUrl(bytesocksUrl: string) {
  const trimmed = bytesocksUrl.trim().replace(/\/$/, "");
  if (/^wss:\/\/https:\/\//i.test(trimmed)) {
    return `wss://${trimmed.replace(/^wss:\/\/https:\/\//i, "")}`;
  }
  if (/^ws:\/\/http:\/\//i.test(trimmed)) {
    return `ws://${trimmed.replace(/^ws:\/\/http:\/\//i, "")}`;
  }
  if (/^https:\/\//i.test(trimmed)) {
    return `wss://${trimmed.replace(/^https:\/\//i, "")}`;
  }
  if (/^http:\/\//i.test(trimmed)) {
    return `ws://${trimmed.replace(/^http:\/\//i, "")}`;
  }
  return trimmed;
}

function extractContentKey(value: string) {
  const trimmed = value.trim().replace(/\/$/, "");
  if (!trimmed.includes("/")) return trimmed;
  try {
    const url = new URL(trimmed);
    return url.pathname.replace(/^\/|\/$/g, "").split("/").pop() ?? trimmed;
  } catch {
    return trimmed.split("/").pop() ?? trimmed;
  }
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
