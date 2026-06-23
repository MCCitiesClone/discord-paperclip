"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertCircle,
  Check,
  Circle,
  Copy,
  Plus,
  RefreshCw,
  Save,
  Trash2,
  Wifi,
  WifiOff,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  DiscordRole,
  EditableConfig,
  SessionPayload,
  SignedFrame,
  fetchPayload,
  generateEditorKeys,
  importServerPublicKey,
  makeNonce,
  signedPacket,
  uploadPayload,
  verifyFrame,
  websocketUrl,
} from "@/lib/paperclip-protocol";

type ConnectionState =
  | "idle"
  | "loading"
  | "connecting"
  | "trust-required"
  | "connected"
  | "applying"
  | "error";

type Row = {
  id: string;
  left: string;
  right: string;
};

const bytebinUrl = process.env.NEXT_PUBLIC_BYTEBIN_URL ?? "";
const bytesocksUrl = process.env.NEXT_PUBLIC_BYTESOCKS_URL ?? "";

export default function Home() {
  const [sessionId, setSessionId] = useState("");
  const [state, setState] = useState<ConnectionState>("idle");
  const [notice, setNotice] = useState("Open the editor URL from /paperclip editor.");
  const [payload, setPayload] = useState<SessionPayload | null>(null);
  const [groupRows, setGroupRows] = useState<Row[]>([]);
  const [accountRows, setAccountRows] = useState<Row[]>([]);
  const [availableGroups, setAvailableGroups] = useState<string[]>([]);
  const [availableRoles, setAvailableRoles] = useState<DiscordRole[]>([]);
  const [nonce, setNonce] = useState("");
  const [lastApplied, setLastApplied] = useState("");
  const socketRef = useRef<WebSocket | null>(null);
  const privateKeyRef = useRef<CryptoKey | null>(null);
  const serverKeyRef = useRef<CryptoKey | null>(null);
  const autoConnectSessionRef = useRef("");

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const querySession = params.get("session") ?? "";
    setSessionId(querySession);
    if (!querySession) {
      setNotice("Missing session in editor URL.");
    }
  }, []);

  const expiresText = useMemo(() => {
    if (!payload) return "No session";
    return new Date(payload.expiresAt * 1000).toLocaleString();
  }, [payload]);

  const config = useMemo<EditableConfig>(
    () => ({
      groupRoleMap: rowsToMap(groupRows),
      linkedAccounts: rowsToMap(accountRows),
    }),
    [groupRows, accountRows],
  );

  const sendPacket = useCallback(async (packet: Record<string, unknown>) => {
    const socket = socketRef.current;
    const privateKey = privateKeyRef.current;
    if (!socket || socket.readyState !== WebSocket.OPEN || !privateKey) {
      throw new Error("socket is not connected");
    }
    socket.send(await signedPacket(packet, privateKey));
  }, []);

  const connect = useCallback(async () => {
    if (!sessionId.trim()) {
      setState("error");
      setNotice("Missing session in editor URL.");
      return;
    }
    if (!bytebinUrl.trim() || !bytesocksUrl.trim()) {
      setState("error");
      setNotice("NEXT_PUBLIC_BYTEBIN_URL and NEXT_PUBLIC_BYTESOCKS_URL are required.");
      return;
    }

    socketRef.current?.close();
    setState("loading");
    setNotice("Loading editor payload...");

    try {
      const loaded = await fetchPayload<SessionPayload>(bytebinUrl, sessionId.trim());
      if (loaded.type !== "paperclip-editor-session") {
        throw new Error("payload is not a Paperclip editor session");
      }

      setPayload(loaded);
      setGroupRows(mapToRows(loaded.config.groupRoleMap));
      setAccountRows(mapToRows(loaded.config.linkedAccounts));
      setAvailableGroups(uniqueSorted([...(loaded.availableGroups ?? []), ...Object.keys(loaded.config.groupRoleMap)]));
      setAvailableRoles(uniqueRoles([...(loaded.availableDiscordRoles ?? []), ...unknownRoles(loaded.config.groupRoleMap)]));

      const keys = await generateEditorKeys();
      privateKeyRef.current = keys.privateKey;
      serverKeyRef.current = await importServerPublicKey(loaded.serverPublicKey);
      const browserPublicKey = await crypto.subtle.exportKey("spki", keys.publicKey);
      const browserPublicKeyText = btoa(
        String.fromCharCode(...new Uint8Array(browserPublicKey)),
      );
      const nextNonce = makeNonce();
      setNonce(nextNonce);
      setState("connecting");
      setNotice("Opening relay channel...");

      const socket = new WebSocket(websocketUrl(bytesocksUrl, loaded.channelId));
      socketRef.current = socket;

      socket.addEventListener("open", async () => {
        setNotice("Sending signed hello...");
        await sendPacket({
          type: "hello",
          nonce: nextNonce,
          sessionId: sessionId.trim(),
          browser: "discord-paperclip-next",
          publicKey: browserPublicKeyText,
        });
      });

      socket.addEventListener("message", async (event) => {
        try {
          const frame = JSON.parse(String(event.data)) as SignedFrame;
          const serverKey = serverKeyRef.current;
          if (!serverKey || !(await verifyFrame(frame, serverKey))) {
            throw new Error("server signature verification failed");
          }
          const packet = JSON.parse(frame.msg) as Record<string, unknown>;
          if (packet.type === "hello-reply") {
            if (packet.trustRequired) {
              setState("trust-required");
              setNotice(`Run /paperclip editor trust ${nextNonce}`);
            } else if (packet.accepted) {
              setState("connected");
              setNotice("Connected.");
            }
          }
          if (packet.type === "pong") {
            setNotice(packet.disconnecting ? "Server closed the session." : "Connected.");
          }
          if (packet.type === "changes-rejected") {
            setState("connected");
            setNotice(`Changes rejected: ${String(packet.error ?? "unknown error")}`);
          }
          if (packet.type === "changes-applied") {
            const refreshedId = String(packet.payloadId ?? "");
            if (refreshedId) {
              const refreshed = await fetchPayload<EditableConfig>(bytebinUrl, refreshedId);
              setGroupRows(mapToRows(refreshed.groupRoleMap ?? {}));
              setAccountRows(mapToRows(refreshed.linkedAccounts ?? {}));
              setLastApplied(new Date().toLocaleTimeString());
            }
            setState("connected");
            setNotice("Changes applied by the plugin.");
          }
        } catch (error) {
          setState("error");
          setNotice(error instanceof Error ? error.message : "Could not process socket frame.");
        }
      });

      socket.addEventListener("close", () => {
        setState((current) => (current === "connected" ? "idle" : current));
        setNotice("Relay channel closed.");
      });

      socket.addEventListener("error", () => {
        setState("error");
        setNotice("WebSocket relay failed.");
      });
    } catch (error) {
      setState("error");
      setNotice(error instanceof Error ? error.message : "Could not load editor session.");
    }
  }, [sendPacket, sessionId]);

  useEffect(() => {
    const trimmedSession = sessionId.trim();
    if (!trimmedSession || autoConnectSessionRef.current === trimmedSession) {
      return;
    }
    autoConnectSessionRef.current = trimmedSession;
    void connect();
  }, [connect, sessionId]);

  const applyChanges = useCallback(async () => {
    setState("applying");
    setNotice("Uploading changes...");
    try {
      const payloadId = await uploadPayload(bytebinUrl, { config });
      await sendPacket({ type: "request-changes", payloadId });
      setNotice("Waiting for plugin confirmation...");
    } catch (error) {
      setState("connected");
      setNotice(error instanceof Error ? error.message : "Could not apply changes.");
    }
  }, [config, sendPacket]);

  return (
    <main className="min-h-screen">
      <section className="border-b bg-card">
        <div className="mx-auto flex max-w-7xl flex-col gap-4 px-4 py-5 sm:px-6 lg:px-8">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <h1 className="text-2xl font-semibold tracking-normal">Discord Paperclip Editor</h1>
              <p className="mt-1 text-sm text-muted-foreground">
                Edit LuckPerms group mappings and linked Discord accounts for a live plugin session.
              </p>
            </div>
            <StatusBadge state={state} />
          </div>
        </div>
      </section>

      <section className="mx-auto grid max-w-7xl gap-4 px-4 py-5 sm:px-6 lg:grid-cols-[1fr_320px] lg:px-8">
        <div className="grid gap-4">
          <EditorTable
            title="Group Role Map"
            leftLabel="LuckPerms group"
            rightLabel="Discord role"
            rows={groupRows}
            leftOptions={availableGroups}
            rightRoleOptions={availableRoles}
            onChange={setGroupRows}
          />
          <EditorTable
            title="Linked Accounts"
            leftLabel="Minecraft UUID"
            rightLabel="Discord user ID"
            rows={accountRows}
            onChange={setAccountRows}
          />
        </div>

        <aside className="flex flex-col gap-4">
          <div className="rounded-md border bg-card p-4">
            <div className="flex items-center gap-2 text-sm font-medium">
              {state === "connected" || state === "applying" ? <Wifi className="size-4" /> : <WifiOff className="size-4" />}
              Session
            </div>
            <dl className="mt-4 grid gap-3 text-sm">
              <InfoRow label="Payload" value={sessionId || "Not loaded"} />
              <InfoRow label="Expires" value={expiresText} />
              <InfoRow label="Channel" value={payload?.channelId ?? "Not loaded"} />
              <InfoRow label="Last applied" value={lastApplied || "None"} />
            </dl>
            <div className="mt-4 rounded-md border bg-muted p-3 text-sm text-muted-foreground">
              {notice}
            </div>
            {state === "trust-required" ? (
              <div className="mt-3 flex gap-2">
                <Input readOnly value={`/paperclip editor trust ${nonce}`} />
                <Button
                  variant="outline"
                  size="icon"
                  aria-label="Copy trust command"
                  onClick={() => navigator.clipboard.writeText(`/paperclip editor trust ${nonce}`)}
                >
                  <Copy />
                </Button>
              </div>
            ) : null}
          </div>

          <div className="rounded-md border bg-card p-4">
            <div className="text-sm font-medium">Raw Preview</div>
            <Textarea
              readOnly
              className="mt-3 min-h-56 font-mono text-xs"
              value={JSON.stringify({ config }, null, 2)}
            />
          </div>

          <Button
            className="h-11"
            onClick={applyChanges}
            disabled={state !== "connected"}
          >
            <Save />
            Apply Changes
          </Button>
        </aside>
      </section>
    </main>
  );
}

function StatusBadge({ state }: { state: ConnectionState }) {
  const data: Record<ConnectionState, { label: string; variant: "default" | "secondary" | "warning" | "destructive" | "outline"; icon: React.ReactNode }> = {
    idle: { label: "Idle", variant: "secondary", icon: <Circle /> },
    loading: { label: "Loading", variant: "warning", icon: <RefreshCw className="animate-spin" /> },
    connecting: { label: "Connecting", variant: "warning", icon: <RefreshCw className="animate-spin" /> },
    "trust-required": { label: "Trust Required", variant: "warning", icon: <AlertCircle /> },
    connected: { label: "Connected", variant: "default", icon: <Check /> },
    applying: { label: "Applying", variant: "warning", icon: <RefreshCw className="animate-spin" /> },
    error: { label: "Error", variant: "destructive", icon: <AlertCircle /> },
  };
  const selected = data[state];
  return (
    <Badge variant={selected.variant} className="[&_svg]:size-3.5 flex w-fit gap-1.5">
      {selected.icon}
      {selected.label}
    </Badge>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid gap-1">
      <dt className="text-xs uppercase text-muted-foreground">{label}</dt>
      <dd className="break-all font-mono text-xs">{value}</dd>
    </div>
  );
}

function EditorTable({
  title,
  leftLabel,
  rightLabel,
  rows,
  leftOptions,
  rightRoleOptions,
  onChange,
}: {
  title: string;
  leftLabel: string;
  rightLabel: string;
  rows: Row[];
  leftOptions?: string[];
  rightRoleOptions?: DiscordRole[];
  onChange: (rows: Row[]) => void;
}) {
  const updateRow = (id: string, patch: Partial<Row>) => {
    onChange(rows.map((row) => (row.id === id ? { ...row, ...patch } : row)));
  };

  return (
    <div className="rounded-md border bg-card">
      <div className="flex items-center justify-between border-b px-4 py-3">
        <h2 className="text-base font-semibold">{title}</h2>
        <Button
          variant="outline"
          size="sm"
          onClick={() => onChange([...rows, { id: crypto.randomUUID(), left: "", right: "" }])}
        >
          <Plus />
          Add
        </Button>
      </div>
      <div className="overflow-x-auto">
        <div className="grid min-w-[620px] grid-cols-[1fr_1fr_48px] border-b bg-muted px-4 py-2 text-xs font-medium uppercase text-muted-foreground">
          <div>{leftLabel}</div>
          <div>{rightLabel}</div>
          <div />
        </div>
        {rows.length === 0 ? (
          <div className="px-4 py-8 text-sm text-muted-foreground">No entries.</div>
        ) : (
          rows.map((row) => (
            <div key={row.id} className="grid min-w-[620px] grid-cols-[1fr_1fr_48px] gap-3 border-b px-4 py-3 last:border-b-0">
              {leftOptions ? (
                <GroupSelect
                  value={row.left}
                  options={leftOptions}
                  onChange={(value) => updateRow(row.id, { left: value })}
                />
              ) : (
                <Input value={row.left} onChange={(event) => updateRow(row.id, { left: event.target.value })} />
              )}
              {rightRoleOptions ? (
                <RoleSelect
                  value={row.right}
                  options={rightRoleOptions}
                  onChange={(value) => updateRow(row.id, { right: value })}
                />
              ) : (
                <Input value={row.right} onChange={(event) => updateRow(row.id, { right: event.target.value })} />
              )}
              <Button
                variant="ghost"
                size="icon"
                aria-label="Remove row"
                onClick={() => onChange(rows.filter((candidate) => candidate.id !== row.id))}
              >
                <Trash2 />
              </Button>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

function GroupSelect({
  value,
  options,
  onChange,
}: {
  value: string;
  options: string[];
  onChange: (value: string) => void;
}) {
  const selectOptions = uniqueSorted(value ? [value, ...options] : options);

  return (
    <select
      value={value}
      onChange={(event) => onChange(event.target.value)}
      className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
    >
      <option value="" disabled>
        Select a group
      </option>
      {selectOptions.map((group) => (
        <option key={group} value={group}>
          {group}
        </option>
      ))}
    </select>
  );
}

function RoleSelect({
  value,
  options,
  onChange,
}: {
  value: string;
  options: DiscordRole[];
  onChange: (value: string) => void;
}) {
  const selectOptions = uniqueRoles(value ? [...options, { id: value, name: `Unknown role (${value})` }] : options);
  const selectedRole = selectOptions.find((role) => role.id === value);

  return (
    <div className="relative">
      {selectedRole ? (
        <span
          aria-hidden="true"
          className="pointer-events-none absolute left-3 top-1/2 size-3 -translate-y-1/2 rounded-full border"
          style={{ backgroundColor: roleColor(selectedRole) }}
        />
      ) : null}
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="flex h-10 w-full rounded-md border border-input bg-background py-2 pl-8 pr-3 text-sm outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
      >
        <option value="" disabled>
          Select a Discord role
        </option>
        {selectOptions.map((role) => (
          <option key={role.id} value={role.id}>
            {role.name}
          </option>
        ))}
      </select>
    </div>
  );
}

function mapToRows(map: Record<string, string>) {
  return Object.entries(map).map(([left, right]) => ({
    id: crypto.randomUUID(),
    left,
    right,
  }));
}

function rowsToMap(rows: Row[]) {
  return rows.reduce<Record<string, string>>((next, row) => {
    const left = row.left.trim();
    const right = row.right.trim();
    if (left && right) {
      next[left] = right;
    }
    return next;
  }, {});
}

function uniqueSorted(values: string[]) {
  return Array.from(new Set(values.map((value) => value.trim()).filter(Boolean))).sort((first, second) =>
    first.localeCompare(second),
  );
}

function uniqueRoles(roles: DiscordRole[]) {
  const byId = new Map<string, DiscordRole>();
  roles.forEach((role) => {
    const id = role.id.trim();
    const name = role.name.trim();
    if (id && name && !byId.has(id)) {
      byId.set(id, { ...role, id, name });
    }
  });
  return Array.from(byId.values()).sort((first, second) =>
    first.name.localeCompare(second.name) || first.id.localeCompare(second.id),
  );
}

function unknownRoles(groupRoleMap: Record<string, string>) {
  return Object.values(groupRoleMap).map((id) => ({ id, name: `Unknown role (${id})` }));
}

function roleColor(role: DiscordRole) {
  if (!role.color || role.color < 0 || role.color > 0xffffff) {
    return "transparent";
  }
  return `#${role.color.toString(16).padStart(6, "0")}`;
}
