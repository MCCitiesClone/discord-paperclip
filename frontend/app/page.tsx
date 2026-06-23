"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertCircle,
  Check,
  ChevronDown,
  ChevronUp,
  Circle,
  Copy,
  Palette,
  Plus,
  RefreshCw,
  Save,
  Trash2,
  Wifi,
  WifiOff,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import {
  DiscordRole,
  EditableConfig,
  GroupInfo,
  RefreshedPayload,
  SessionPayload,
  SignedFrame,
  exportEditorKeys,
  exportPublicKey,
  fetchPayload,
  generateEditorKeys,
  importEditorKeys,
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

type Tab = "mappings" | "roles";

type Row = {
  id: string;
  left: string;
  right: string;
};

type GroupItem = {
  id: string;
  name: string;
  displayName: string;
  existing: boolean;
};

type RoleItem = {
  id: string;
  roleId: string;
  name: string;
  color: number;
  existing: boolean;
};

const bytebinUrl = process.env.NEXT_PUBLIC_BYTEBIN_URL ?? "";
const bytesocksUrl = process.env.NEXT_PUBLIC_BYTESOCKS_URL ?? "";
const editorKeysStorageKey = "discord-paperclip-editor-keys-v1";

export default function Home() {
  const [sessionId, setSessionId] = useState("");
  const [state, setState] = useState<ConnectionState>("idle");
  const [tab, setTab] = useState<Tab>("mappings");
  const [notice, setNotice] = useState("Open the editor URL from /paperclip editor.");
  const [payload, setPayload] = useState<SessionPayload | null>(null);
  const [groupRows, setGroupRows] = useState<Row[]>([]);
  const [roleRows, setRoleRows] = useState<Row[]>([]);
  const [accountRows, setAccountRows] = useState<Row[]>([]);
  const [lpGroups, setLpGroups] = useState<GroupItem[]>([]);
  const [discordRoleItems, setDiscordRoleItems] = useState<RoleItem[]>([]);
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
      roleGroupMap: rowsToMap(roleRows),
      linkedAccounts: rowsToMap(accountRows),
    }),
    [groupRows, roleRows, accountRows],
  );

  const requestBody = useMemo(
    () => ({
      config,
      groups: lpGroups
        .filter((group) => group.name.trim())
        .map((group) => ({ name: group.name.trim(), displayName: group.displayName.trim() })),
      discordRoles: discordRoleItems
        .filter((role) => role.name.trim())
        .map((role) => ({
          id: role.roleId.trim() || null,
          name: role.name.trim(),
          color: role.color,
        })),
    }),
    [config, lpGroups, discordRoleItems],
  );

  const loadSessionData = useCallback(
    (data: {
      config: EditableConfig;
      availableGroups?: string[];
      groups?: GroupInfo[];
      availableDiscordRoles?: DiscordRole[];
    }) => {
      setGroupRows(mapToRows(data.config.groupRoleMap ?? {}));
      setRoleRows(mapToRows(data.config.roleGroupMap ?? {}));
      setAccountRows(mapToRows(data.config.linkedAccounts ?? {}));
      setAvailableGroups(
        uniqueSorted([
          ...(data.availableGroups ?? []),
          ...(data.groups ?? []).map((group) => group.name),
          ...Object.keys(data.config.groupRoleMap ?? {}),
          ...Object.values(data.config.roleGroupMap ?? {}),
        ]),
      );
      setAvailableRoles(
        uniqueRoles([
          ...(data.availableDiscordRoles ?? []),
          ...unknownRoles(data.config.groupRoleMap ?? {}),
          ...unknownRoleIds(Object.keys(data.config.roleGroupMap ?? {})),
        ]),
      );
      setLpGroups(groupInfosToItems(data.groups ?? []));
      setDiscordRoleItems(rolesToItems(data.availableDiscordRoles ?? []));
    },
    [],
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
      loadSessionData(loaded);

      const keys = await loadEditorKeys();
      privateKeyRef.current = keys.privateKey;
      serverKeyRef.current = await importServerPublicKey(loaded.serverPublicKey);
      const browserPublicKeyText = await exportPublicKey(keys.publicKey);
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
              const refreshed = await fetchPayload<RefreshedPayload>(bytebinUrl, refreshedId);
              loadSessionData(refreshed);
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
  }, [loadSessionData, sendPacket, sessionId]);

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
      const payloadId = await uploadPayload(bytebinUrl, requestBody);
      await sendPacket({ type: "request-changes", payloadId });
      setNotice("Waiting for plugin confirmation...");
    } catch (error) {
      setState("connected");
      setNotice(error instanceof Error ? error.message : "Could not apply changes.");
    }
  }, [requestBody, sendPacket]);

  return (
    <Tabs value={tab} onValueChange={(value) => setTab(value as Tab)} asChild>
      <main className="min-h-screen">
        <section className="border-b bg-card">
          <div className="mx-auto flex max-w-7xl flex-col gap-4 px-4 py-5 sm:px-6 lg:px-8">
            <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
              <div>
                <h1 className="text-2xl font-semibold tracking-normal">Discord Paperclip Editor</h1>
                <p className="mt-1 text-sm text-muted-foreground">
                  Manage LuckPerms groups and Discord roles, then map them together for a live plugin session.
                </p>
              </div>
              <StatusBadge state={state} />
            </div>
            <TabsList>
              <TabsTrigger value="mappings">Mappings</TabsTrigger>
              <TabsTrigger value="roles">Roles &amp; Groups</TabsTrigger>
            </TabsList>
          </div>
        </section>

        <section className="mx-auto grid max-w-7xl gap-4 px-4 py-5 sm:px-6 lg:grid-cols-[1fr_320px] lg:px-8">
          <div>
            <TabsContent value="mappings" className="mt-0 grid gap-4">
              <EditorTable
                title="Minecraft Groups To Discord Roles"
                leftLabel="LuckPerms group"
                rightLabel="Discord role"
                rows={groupRows}
                leftOptions={availableGroups}
                rightRoleOptions={availableRoles}
                onChange={setGroupRows}
              />
              <EditorTable
                title="Discord Roles To Minecraft Groups"
                leftLabel="Discord role"
                rightLabel="LuckPerms group"
                rows={roleRows}
                leftRoleOptions={availableRoles}
                rightOptions={availableGroups}
                onChange={setRoleRows}
              />
              <EditorTable
                title="Linked Accounts"
                leftLabel="Minecraft UUID"
                rightLabel="Discord user ID"
                rows={accountRows}
                onChange={setAccountRows}
              />
            </TabsContent>
            <TabsContent value="roles" className="mt-0 grid gap-4">
              <GroupManager groups={lpGroups} onChange={setLpGroups} />
              <DiscordRoleManager roles={discordRoleItems} onChange={setDiscordRoleItems} />
            </TabsContent>
          </div>

          <aside className="flex flex-col gap-4">
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2 text-sm">
                  {state === "connected" || state === "applying" ? <Wifi className="size-4" /> : <WifiOff className="size-4" />}
                  Session
                </CardTitle>
              </CardHeader>
              <CardContent>
                <dl className="grid gap-3 text-sm">
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
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle className="text-sm">Raw Preview</CardTitle>
              </CardHeader>
              <CardContent>
                <Textarea
                  readOnly
                  className="min-h-56 font-mono text-xs"
                  value={JSON.stringify(requestBody, null, 2)}
                />
              </CardContent>
            </Card>

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
    </Tabs>
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

function GroupManager({
  groups,
  onChange,
}: {
  groups: GroupItem[];
  onChange: (groups: GroupItem[]) => void;
}) {
  const updateGroup = (id: string, patch: Partial<GroupItem>) => {
    onChange(groups.map((group) => (group.id === id ? { ...group, ...patch } : group)));
  };

  return (
    <Card className="overflow-hidden">
      <CardHeader className="flex flex-row items-center justify-between space-y-0 border-b p-4">
        <div className="space-y-1">
          <CardTitle className="text-base">LuckPerms Groups</CardTitle>
          <CardDescription className="text-xs">
            Top of the list = highest weight. New groups are created on apply.
          </CardDescription>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() =>
            onChange([
              ...groups,
              { id: crypto.randomUUID(), name: "", displayName: "", existing: false },
            ])
          }
        >
          <Plus />
          Add
        </Button>
      </CardHeader>
      <div className="overflow-x-auto">
        <div className="grid min-w-[620px] grid-cols-[64px_1fr_1fr_48px] border-b bg-muted px-4 py-2 text-xs font-medium uppercase text-muted-foreground">
          <div>Order</div>
          <div>Group name</div>
          <div>Display name</div>
          <div />
        </div>
        {groups.length === 0 ? (
          <div className="px-4 py-8 text-sm text-muted-foreground">No groups.</div>
        ) : (
          groups.map((group, index) => (
            <div
              key={group.id}
              className="grid min-w-[620px] grid-cols-[64px_1fr_1fr_48px] items-center gap-3 border-b px-4 py-3 last:border-b-0"
            >
              <OrderButtons
                index={index}
                count={groups.length}
                onMove={(direction) => onChange(move(groups, index, direction))}
              />
              <Input
                value={group.name}
                placeholder="group-name"
                disabled={group.existing}
                onChange={(event) => updateGroup(group.id, { name: event.target.value })}
              />
              <Input
                value={group.displayName}
                placeholder="Optional display name"
                onChange={(event) => updateGroup(group.id, { displayName: event.target.value })}
              />
              <Button
                variant="ghost"
                size="icon"
                aria-label="Stop managing group"
                onClick={() => onChange(groups.filter((candidate) => candidate.id !== group.id))}
              >
                <Trash2 />
              </Button>
            </div>
          ))
        )}
      </div>
    </Card>
  );
}

function DiscordRoleManager({
  roles,
  onChange,
}: {
  roles: RoleItem[];
  onChange: (roles: RoleItem[]) => void;
}) {
  const updateRole = (id: string, patch: Partial<RoleItem>) => {
    onChange(roles.map((role) => (role.id === id ? { ...role, ...patch } : role)));
  };

  return (
    <Card className="overflow-hidden">
      <CardHeader className="flex flex-row items-center justify-between space-y-0 border-b p-4">
        <div className="space-y-1">
          <CardTitle className="text-base">Discord Roles</CardTitle>
          <CardDescription className="text-xs">
            Top of the list = highest role. New roles are created on apply.
          </CardDescription>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() =>
            onChange([
              { id: crypto.randomUUID(), roleId: "", name: "", color: 0, existing: false },
              ...roles,
            ])
          }
        >
          <Plus />
          Add
        </Button>
      </CardHeader>
      <div className="overflow-x-auto">
        <div className="grid min-w-[620px] grid-cols-[64px_140px_1fr_48px] border-b bg-muted px-4 py-2 text-xs font-medium uppercase text-muted-foreground">
          <div>Order</div>
          <div>Color</div>
          <div>Role name</div>
          <div />
        </div>
        {roles.length === 0 ? (
          <div className="px-4 py-8 text-sm text-muted-foreground">No roles.</div>
        ) : (
          roles.map((role, index) => (
            <div
              key={role.id}
              className="grid min-w-[620px] grid-cols-[64px_140px_1fr_48px] items-center gap-3 border-b px-4 py-3 last:border-b-0"
            >
              <OrderButtons
                index={index}
                count={roles.length}
                onMove={(direction) => onChange(move(roles, index, direction))}
              />
              <ColorField
                color={role.color}
                onChange={(color) => updateRole(role.id, { color })}
              />
              <Input
                value={role.name}
                placeholder="Role name"
                onChange={(event) => updateRole(role.id, { name: event.target.value })}
              />
              <Button
                variant="ghost"
                size="icon"
                aria-label="Stop managing role"
                onClick={() => onChange(roles.filter((candidate) => candidate.id !== role.id))}
              >
                <Trash2 />
              </Button>
            </div>
          ))
        )}
      </div>
    </Card>
  );
}

function OrderButtons({
  index,
  count,
  onMove,
}: {
  index: number;
  count: number;
  onMove: (direction: -1 | 1) => void;
}) {
  return (
    <div className="flex gap-1">
      <Button
        variant="ghost"
        size="icon"
        className="size-7"
        aria-label="Move up"
        disabled={index === 0}
        onClick={() => onMove(-1)}
      >
        <ChevronUp />
      </Button>
      <Button
        variant="ghost"
        size="icon"
        className="size-7"
        aria-label="Move down"
        disabled={index === count - 1}
        onClick={() => onMove(1)}
      >
        <ChevronDown />
      </Button>
    </div>
  );
}

function ColorField({
  color,
  onChange,
}: {
  color: number;
  onChange: (color: number) => void;
}) {
  const colored = hasColor(color);
  return (
    <div className="flex items-center gap-2">
      <label
        className="relative size-8 shrink-0 cursor-pointer rounded-md border"
        style={{ backgroundColor: colored ? colorToHex(color) : "transparent" }}
        title="Pick role color"
      >
        {colored ? null : <Palette className="absolute inset-0 m-auto size-4 text-muted-foreground" />}
        <input
          type="color"
          className="absolute inset-0 size-full cursor-pointer opacity-0"
          value={colorToHex(color)}
          onChange={(event) => onChange(hexToColor(event.target.value))}
        />
      </label>
      <Button
        variant="ghost"
        size="sm"
        className="h-8 px-2 text-xs"
        disabled={!colored}
        onClick={() => onChange(0)}
      >
        Clear
      </Button>
    </div>
  );
}

function EditorTable({
  title,
  leftLabel,
  rightLabel,
  rows,
  leftOptions,
  leftRoleOptions,
  rightRoleOptions,
  rightOptions,
  onChange,
}: {
  title: string;
  leftLabel: string;
  rightLabel: string;
  rows: Row[];
  leftOptions?: string[];
  leftRoleOptions?: DiscordRole[];
  rightRoleOptions?: DiscordRole[];
  rightOptions?: string[];
  onChange: (rows: Row[]) => void;
}) {
  const updateRow = (id: string, patch: Partial<Row>) => {
    onChange(rows.map((row) => (row.id === id ? { ...row, ...patch } : row)));
  };

  return (
    <Card className="overflow-hidden">
      <CardHeader className="flex flex-row items-center justify-between space-y-0 border-b p-4">
        <CardTitle className="text-base">{title}</CardTitle>
        <Button
          variant="outline"
          size="sm"
          onClick={() => onChange([...rows, { id: crypto.randomUUID(), left: "", right: "" }])}
        >
          <Plus />
          Add
        </Button>
      </CardHeader>
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
              {leftRoleOptions ? (
                <RoleSelect
                  value={row.left}
                  options={leftRoleOptions}
                  onChange={(value) => updateRow(row.id, { left: value })}
                />
              ) : leftOptions ? (
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
              ) : rightOptions ? (
                <GroupSelect
                  value={row.right}
                  options={rightOptions}
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
    </Card>
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

function groupInfosToItems(groups: GroupInfo[]): GroupItem[] {
  return groups.map((group) => ({
    id: crypto.randomUUID(),
    name: group.name,
    displayName: group.displayName ?? "",
    existing: true,
  }));
}

function rolesToItems(roles: DiscordRole[]): RoleItem[] {
  return [...roles]
    .sort(
      (first, second) =>
        (second.position ?? 0) - (first.position ?? 0) || first.name.localeCompare(second.name),
    )
    .map((role) => ({
      id: crypto.randomUUID(),
      roleId: role.id,
      name: role.name,
      color: role.color ?? 0,
      existing: true,
    }));
}

function move<T>(list: T[], index: number, direction: -1 | 1): T[] {
  const target = index + direction;
  if (target < 0 || target >= list.length) {
    return list;
  }
  const next = [...list];
  [next[index], next[target]] = [next[target], next[index]];
  return next;
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

function unknownRoleIds(roleIds: string[]) {
  return roleIds.map((id) => ({ id, name: `Unknown role (${id})` }));
}

function hasColor(color: number) {
  return Number.isFinite(color) && color > 0 && color <= 0xffffff;
}

function colorToHex(color: number) {
  if (!hasColor(color)) {
    return "#000000";
  }
  return `#${color.toString(16).padStart(6, "0")}`;
}

function hexToColor(hex: string) {
  const parsed = Number.parseInt(hex.replace(/^#/, ""), 16);
  return Number.isNaN(parsed) ? 0 : parsed & 0xffffff;
}

function roleColor(role: DiscordRole) {
  if (!role.color || role.color < 0 || role.color > 0xffffff) {
    return "transparent";
  }
  return `#${role.color.toString(16).padStart(6, "0")}`;
}

async function loadEditorKeys() {
  const stored = localStorage.getItem(editorKeysStorageKey);
  if (stored) {
    try {
      return await importEditorKeys(JSON.parse(stored));
    } catch {
      localStorage.removeItem(editorKeysStorageKey);
    }
  }

  const keys = await generateEditorKeys();
  localStorage.setItem(editorKeysStorageKey, JSON.stringify(await exportEditorKeys(keys)));
  return keys;
}
