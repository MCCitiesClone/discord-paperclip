"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertCircle,
  Check,
  ChevronDown,
  ChevronRight,
  Circle,
  Copy,
  Folder as FolderIcon,
  FolderPlus,
  GripVertical,
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
  ConfigFolder,
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

type Folder = {
  id: string;
  name: string;
  collapsed: boolean;
  color: number;
  parentId: string | null;
};

type GroupItem = {
  id: string;
  name: string;
  displayName: string;
  existing: boolean;
  folderId: string | null;
};

type RoleItem = {
  id: string;
  roleId: string;
  name: string;
  color: number;
  existing: boolean;
  folderId: string | null;
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
  const [lpFolders, setLpFolders] = useState<Folder[]>([]);
  const [discordRoleItems, setDiscordRoleItems] = useState<RoleItem[]>([]);
  const [roleFolders, setRoleFolders] = useState<Folder[]>([]);
  const [availableGroups, setAvailableGroups] = useState<string[]>([]);
  const [availableRoles, setAvailableRoles] = useState<DiscordRole[]>([]);
  const [nonce, setNonce] = useState("");
  const [lastApplied, setLastApplied] = useState("");
  const socketRef = useRef<WebSocket | null>(null);
  const privateKeyRef = useRef<CryptoKey | null>(null);
  const serverKeyRef = useRef<CryptoKey | null>(null);
  const autoConnectSessionRef = useRef("");
  const pingIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopHeartbeat = useCallback(() => {
    if (pingIntervalRef.current) {
      clearInterval(pingIntervalRef.current);
      pingIntervalRef.current = null;
    }
  }, []);

  useEffect(() => {
    return () => {
      stopHeartbeat();
      socketRef.current?.close();
    };
  }, [stopHeartbeat]);

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
      groups: reflow(lpGroups, lpFolders)
        .filter((group) => group.name.trim())
        .map((group) => ({ name: group.name.trim(), displayName: group.displayName.trim() })),
      discordRoles: reflow(discordRoleItems, roleFolders)
        .filter((role) => role.name.trim())
        .map((role) => ({
          id: role.roleId.trim() || null,
          name: role.name.trim(),
          color: effectiveRoleColor(role, roleFolders),
        })),
      groupFolders: foldersToConfig(lpFolders, lpGroups, (group) => group.name.trim()),
      roleFolders: foldersToConfig(roleFolders, discordRoleItems, (role) => role.roleId.trim()),
    }),
    [config, lpGroups, lpFolders, discordRoleItems, roleFolders],
  );

  const loadSessionData = useCallback(
    (data: {
      config: EditableConfig;
      availableGroups?: string[];
      groups?: GroupInfo[];
      availableDiscordRoles?: DiscordRole[];
      groupFolders?: ConfigFolder[];
      roleFolders?: ConfigFolder[];
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
      const groupFolders = configToFolders(data.groupFolders ?? []);
      const discordRoleFolders = configToFolders(data.roleFolders ?? []);
      setLpFolders(groupFolders);
      setRoleFolders(discordRoleFolders);
      setLpGroups(
        reflow(
          assignFolders(
            groupInfosToItems(data.groups ?? []),
            data.groupFolders ?? [],
            groupFolders,
            (group) => group.name,
            (group, folderId) => ({ ...group, folderId }),
          ),
          groupFolders,
        ),
      );
      setDiscordRoleItems(
        reflow(
          assignFolders(
            rolesToItems(data.availableDiscordRoles ?? []),
            data.roleFolders ?? [],
            discordRoleFolders,
            (role) => role.roleId,
            (role, folderId) => ({ ...role, folderId }),
          ),
          discordRoleFolders,
        ),
      );
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

    stopHeartbeat();
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
        stopHeartbeat();
        pingIntervalRef.current = setInterval(() => {
          const current = socketRef.current;
          if (!current || current.readyState !== WebSocket.OPEN) {
            return;
          }
          void sendPacket({ type: "ping" }).catch(() => {});
        }, 25000);
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
        stopHeartbeat();
        setState((current) => (current === "connected" ? "idle" : current));
        setNotice("Relay channel closed.");
      });

      socket.addEventListener("error", () => {
        stopHeartbeat();
        setState("error");
        setNotice("WebSocket relay failed.");
      });
    } catch (error) {
      setState("error");
      setNotice(error instanceof Error ? error.message : "Could not load editor session.");
    }
  }, [loadSessionData, sendPacket, sessionId, stopHeartbeat]);

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
              <GroupManager
                groups={lpGroups}
                folders={lpFolders}
                onGroupsChange={setLpGroups}
                onFoldersChange={setLpFolders}
              />
              <DiscordRoleManager
                roles={discordRoleItems}
                folders={roleFolders}
                onRolesChange={setDiscordRoleItems}
                onFoldersChange={setRoleFolders}
              />
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
  folders,
  onGroupsChange,
  onFoldersChange,
}: {
  groups: GroupItem[];
  folders: Folder[];
  onGroupsChange: (groups: GroupItem[]) => void;
  onFoldersChange: (folders: Folder[]) => void;
}) {
  return (
    <FolderedList
      title="LuckPerms Groups"
      description="Top of the list = highest weight. Folders are for organization only. New groups are created on apply."
      columnTemplate="1fr 1fr"
      headerCells={
        <>
          <div>Group name</div>
          <div>Display name</div>
        </>
      }
      emptyLabel="No groups."
      addToTop={false}
      items={groups}
      folders={folders}
      onItemsChange={onGroupsChange}
      onFoldersChange={onFoldersChange}
      makeItem={(folderId) => ({
        id: crypto.randomUUID(),
        name: "",
        displayName: "",
        existing: false,
        folderId,
      })}
      renderCells={(group, update) => (
        <>
          <Input
            value={group.name}
            placeholder="group-name"
            disabled={group.existing}
            onChange={(event) => update({ name: event.target.value })}
          />
          <Input
            value={group.displayName}
            placeholder="Optional display name"
            onChange={(event) => update({ displayName: event.target.value })}
          />
        </>
      )}
    />
  );
}

function DiscordRoleManager({
  roles,
  folders,
  onRolesChange,
  onFoldersChange,
}: {
  roles: RoleItem[];
  folders: Folder[];
  onRolesChange: (roles: RoleItem[]) => void;
  onFoldersChange: (folders: Folder[]) => void;
}) {
  return (
    <FolderedList
      title="Discord Roles"
      description="Top of the list = highest role. Folders are for organization only. New roles are created on apply."
      columnTemplate="140px 1fr"
      headerCells={
        <>
          <div>Color</div>
          <div>Role name</div>
        </>
      }
      emptyLabel="No roles."
      addToTop
      enableFolderColor
      items={roles}
      folders={folders}
      onItemsChange={onRolesChange}
      onFoldersChange={onFoldersChange}
      makeItem={(folderId) => ({
        id: crypto.randomUUID(),
        roleId: "",
        name: "",
        color: 0,
        existing: false,
        folderId,
      })}
      renderCells={(role, update) => {
        const inFolder = folders.some((folder) => folder.id === role.folderId);
        return (
          <>
            {inFolder ? (
              <InheritedColorField color={folderColorFor(role.folderId, folders)} />
            ) : (
              <ColorField color={role.color} onChange={(color) => update({ color })} />
            )}
            <Input
              value={role.name}
              placeholder="Role name"
              onChange={(event) => update({ name: event.target.value })}
            />
          </>
        );
      }}
    />
  );
}

type FolderedItem = { id: string; folderId: string | null };

function FolderedList<T extends FolderedItem>({
  title,
  description,
  columnTemplate,
  headerCells,
  emptyLabel,
  addToTop,
  enableFolderColor = false,
  items,
  folders,
  onItemsChange,
  onFoldersChange,
  makeItem,
  renderCells,
}: {
  title: string;
  description: string;
  columnTemplate: string;
  headerCells: React.ReactNode;
  emptyLabel: string;
  addToTop: boolean;
  enableFolderColor?: boolean;
  items: T[];
  folders: Folder[];
  onItemsChange: (items: T[]) => void;
  onFoldersChange: (folders: Folder[]) => void;
  makeItem: (folderId: string | null) => T;
  renderCells: (item: T, update: (patch: Partial<T>) => void) => React.ReactNode;
}) {
  const gridColumns = `48px ${columnTemplate} 48px`;

  const [dragId, setDragId] = useState<string | null>(null);
  const [dropHint, setDropHint] = useState<{
    id: string | null;
    folderId: string | null;
    position: "before" | "after" | "into";
  } | null>(null);
  const [folderDragId, setFolderDragId] = useState<string | null>(null);
  const [folderDropHint, setFolderDropHint] = useState<{
    id: string;
    position: "before" | "after" | "into";
  } | null>(null);

  const resetDrag = () => {
    setDragId(null);
    setDropHint(null);
    setFolderDragId(null);
    setFolderDropHint(null);
  };

  const moveItemTo = (
    id: string,
    folderId: string | null,
    targetId: string | null,
    position: "before" | "after",
  ) => {
    const dragged = items.find((item) => item.id === id);
    if (!dragged) return;
    const updated = { ...dragged, folderId };
    const rest = items.filter((item) => item.id !== id);
    if (targetId === null || targetId === id) {
      onItemsChange(reflow([...rest, updated], folders));
      return;
    }
    let target = rest.findIndex((item) => item.id === targetId);
    if (target < 0) {
      onItemsChange(reflow([...rest, updated], folders));
      return;
    }
    if (position === "after") target += 1;
    onItemsChange(reflow([...rest.slice(0, target), updated, ...rest.slice(target)], folders));
  };

  const zoneDragOver = (folderId: string | null) => (event: React.DragEvent) => {
    if (!dragId) return;
    event.preventDefault();
    setDropHint({ id: null, folderId, position: "into" });
  };

  const zoneDrop = (folderId: string | null) => (event: React.DragEvent) => {
    if (!dragId) return;
    event.preventDefault();
    moveItemTo(dragId, folderId, null, "after");
    resetDrag();
  };

  // Is `nodeId` inside the subtree rooted at `ancestorId` (inclusive)?
  const isDescendant = (nodeId: string, ancestorId: string) => {
    const seen = new Set<string>();
    let current: Folder | undefined = folders.find((folder) => folder.id === nodeId);
    while (current && !seen.has(current.id)) {
      if (current.id === ancestorId) return true;
      seen.add(current.id);
      current = current.parentId ? folders.find((folder) => folder.id === current!.parentId) : undefined;
    }
    return false;
  };

  const moveFolder = (id: string, targetId: string, position: "before" | "after" | "into") => {
    if (id === targetId || isDescendant(targetId, id)) return;
    const dragged = folders.find((folder) => folder.id === id);
    const target = folders.find((folder) => folder.id === targetId);
    if (!dragged || !target) return;
    const parentId = position === "into" ? target.id : target.parentId;
    const updated = { ...dragged, parentId };
    const rest = folders.filter((folder) => folder.id !== id);
    let index = rest.findIndex((folder) => folder.id === targetId);
    if (position !== "before") index += 1;
    const nextFolders = [...rest.slice(0, index), updated, ...rest.slice(index)];
    onFoldersChange(nextFolders);
    onItemsChange(reflow(items, nextFolders));
  };

  const folderDropPosition = (event: React.DragEvent): "before" | "after" | "into" => {
    const rect = event.currentTarget.getBoundingClientRect();
    const offset = (event.clientY - rect.top) / rect.height;
    if (offset < 0.25) return "before";
    if (offset > 0.75) return "after";
    return "into";
  };

  const headerDragOver = (folder: Folder) => (event: React.DragEvent) => {
    if (folderDragId) {
      if (folderDragId === folder.id || isDescendant(folder.id, folderDragId)) return;
      event.preventDefault();
      event.stopPropagation();
      setFolderDropHint({ id: folder.id, position: folderDropPosition(event) });
      return;
    }
    zoneDragOver(folder.id)(event);
  };

  const headerDrop = (folder: Folder) => (event: React.DragEvent) => {
    if (folderDragId) {
      event.preventDefault();
      event.stopPropagation();
      moveFolder(folderDragId, folder.id, folderDropPosition(event));
      resetDrag();
      return;
    }
    zoneDrop(folder.id)(event);
  };

  const addItem = (folderId: string | null) => {
    const created = makeItem(folderId);
    const next = addToTop ? [created, ...items] : [...items, created];
    onItemsChange(reflow(next, folders));
  };

  const updateItem = (id: string, patch: Partial<T>) => {
    onItemsChange(items.map((item) => (item.id === id ? { ...item, ...patch } : item)));
  };

  const removeItem = (id: string) => {
    onItemsChange(items.filter((item) => item.id !== id));
  };

  const addFolder = () => {
    onFoldersChange([
      ...folders,
      { id: crypto.randomUUID(), name: "New folder", collapsed: false, color: 0, parentId: null },
    ]);
  };

  const updateFolder = (id: string, patch: Partial<Folder>) => {
    onFoldersChange(folders.map((folder) => (folder.id === id ? { ...folder, ...patch } : folder)));
  };

  const removeFolder = (id: string) => {
    const parentId = folders.find((folder) => folder.id === id)?.parentId ?? null;
    const nextFolders = folders
      .filter((folder) => folder.id !== id)
      .map((folder) => (folder.parentId === id ? { ...folder, parentId } : folder));
    const nextItems = items.map((item) => (item.folderId === id ? { ...item, folderId: null } : item));
    onFoldersChange(nextFolders);
    onItemsChange(reflow(nextItems, nextFolders));
  };

  const indentPad = (depth: number) => 16 + depth * 20;

  const renderRows = (subset: T[], depth: number) =>
    subset.map((item) => {
      const dropBefore = dropHint?.id === item.id && dropHint.position === "before";
      const dropAfter = dropHint?.id === item.id && dropHint.position === "after";
      const rowDragOver = (event: React.DragEvent) => {
        if (!dragId || dragId === item.id) return;
        event.preventDefault();
        event.stopPropagation();
        const rect = event.currentTarget.getBoundingClientRect();
        const position = event.clientY - rect.top < rect.height / 2 ? "before" : "after";
        setDropHint({ id: item.id, folderId: item.folderId, position });
      };
      const rowDrop = (event: React.DragEvent) => {
        if (!dragId) return;
        event.preventDefault();
        event.stopPropagation();
        const rect = event.currentTarget.getBoundingClientRect();
        const position = event.clientY - rect.top < rect.height / 2 ? "before" : "after";
        moveItemTo(dragId, item.folderId, item.id, position);
        resetDrag();
      };
      return (
        <div
          key={item.id}
          className="relative grid min-w-[680px] items-center gap-3 border-b px-4 py-3 last:border-b-0"
          style={{ gridTemplateColumns: gridColumns, paddingLeft: indentPad(depth) }}
          onDragOver={rowDragOver}
          onDrop={rowDrop}
        >
          {dropBefore ? (
            <div className="pointer-events-none absolute inset-x-0 -top-px z-10 h-0.5 bg-primary" />
          ) : null}
          {dropAfter ? (
            <div className="pointer-events-none absolute inset-x-0 -bottom-px z-10 h-0.5 bg-primary" />
          ) : null}
          <span
            draggable
            onDragStart={(event) => {
              setDragId(item.id);
              event.dataTransfer.effectAllowed = "move";
              event.dataTransfer.setData("text/plain", item.id);
            }}
            onDragEnd={resetDrag}
            className="flex size-7 shrink-0 cursor-grab items-center justify-center text-muted-foreground active:cursor-grabbing"
            aria-label="Drag to reorder"
          >
            <GripVertical className="size-4" />
          </span>
          {renderCells(item, (patch) => updateItem(item.id, patch))}
          <Button
            variant="ghost"
            size="icon"
            aria-label="Remove"
            onClick={() => removeItem(item.id)}
          >
            <Trash2 />
          </Button>
        </div>
      );
    });

  const ungrouped = items.filter(
    (item) => item.folderId === null || !folders.some((folder) => folder.id === item.folderId),
  );

  const folderIds = new Set(folders.map((folder) => folder.id));
  const childFolders = (parentId: string | null) =>
    folders.filter((folder) =>
      parentId === null
        ? folder.parentId === null || !folderIds.has(folder.parentId)
        : folder.parentId === parentId,
    );

  const renderFolder = (folder: Folder, depth: number): React.ReactNode => {
    const folderItems = items.filter((item) => item.folderId === folder.id);
    const subFolders = childFolders(folder.id);
    const intoActive =
      (dropHint?.folderId === folder.id && dropHint.position === "into") ||
      (folderDropHint?.id === folder.id && folderDropHint.position === "into");
    return (
      <div key={folder.id}>
        <div
          className={`relative flex min-w-[680px] items-center gap-2 border-b bg-muted/50 py-2 pr-4 ${
            intoActive ? "ring-2 ring-inset ring-primary" : ""
          }`}
          style={{ paddingLeft: indentPad(depth) }}
          onDragOver={headerDragOver(folder)}
          onDrop={headerDrop(folder)}
        >
          {folderDropHint?.id === folder.id && folderDropHint.position === "before" ? (
            <div className="pointer-events-none absolute inset-x-0 -top-px z-10 h-0.5 bg-primary" />
          ) : null}
          {folderDropHint?.id === folder.id && folderDropHint.position === "after" ? (
            <div className="pointer-events-none absolute inset-x-0 -bottom-px z-10 h-0.5 bg-primary" />
          ) : null}
          <span
            draggable
            onDragStart={(event) => {
              setFolderDragId(folder.id);
              event.dataTransfer.effectAllowed = "move";
              event.dataTransfer.setData("text/plain", folder.id);
            }}
            onDragEnd={resetDrag}
            className="flex size-7 shrink-0 cursor-grab items-center justify-center text-muted-foreground active:cursor-grabbing"
            aria-label="Drag to reorder folder"
          >
            <GripVertical className="size-4" />
          </span>
          <Button
            variant="ghost"
            size="icon"
            className="size-7"
            aria-label={folder.collapsed ? "Expand folder" : "Collapse folder"}
            onClick={() => updateFolder(folder.id, { collapsed: !folder.collapsed })}
          >
            {folder.collapsed ? <ChevronRight /> : <ChevronDown />}
          </Button>
          <FolderIcon className="size-4 text-muted-foreground" />
          <Input
            value={folder.name}
            placeholder="Folder name"
            className="h-8 max-w-xs"
            onChange={(event) => updateFolder(folder.id, { name: event.target.value })}
          />
          {enableFolderColor ? (
            <ColorField color={folder.color} onChange={(color) => updateFolder(folder.id, { color })} />
          ) : null}
          <span className="text-xs text-muted-foreground">{folderItems.length}</span>
          <div className="ml-auto flex gap-1">
            <Button
              variant="ghost"
              size="icon"
              className="size-7"
              aria-label="Add to folder"
              onClick={() => addItem(folder.id)}
            >
              <Plus />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="size-7"
              aria-label="Delete folder"
              onClick={() => removeFolder(folder.id)}
            >
              <Trash2 />
            </Button>
          </div>
        </div>
        {folder.collapsed ? null : (
          <>
            {renderRows(folderItems, depth + 1)}
            {subFolders.map((child) => renderFolder(child, depth + 1))}
            {folderItems.length === 0 && subFolders.length === 0 ? (
              <div
                className="min-w-[680px] py-4 text-sm text-muted-foreground"
                style={{ paddingLeft: indentPad(depth + 1) + 28 }}
                onDragOver={zoneDragOver(folder.id)}
                onDrop={zoneDrop(folder.id)}
              >
                Empty folder.
              </div>
            ) : null}
          </>
        )}
      </div>
    );
  };

  return (
    <Card className="overflow-hidden">
      <CardHeader className="flex flex-row items-center justify-between space-y-0 border-b p-4">
        <div className="space-y-1">
          <CardTitle className="text-base">{title}</CardTitle>
          <CardDescription className="text-xs">{description}</CardDescription>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={addFolder}>
            <FolderPlus />
            Folder
          </Button>
          <Button variant="outline" size="sm" onClick={() => addItem(null)}>
            <Plus />
            Add
          </Button>
        </div>
      </CardHeader>
      <div className="overflow-x-auto">
        <div
          className="grid min-w-[680px] border-b bg-muted px-4 py-2 text-xs font-medium uppercase text-muted-foreground"
          style={{ gridTemplateColumns: gridColumns }}
        >
          <div />
          {headerCells}
          <div />
        </div>
        {items.length === 0 && folders.length === 0 ? (
          <div className="px-4 py-8 text-sm text-muted-foreground">{emptyLabel}</div>
        ) : (
          <>
            <div onDragOver={zoneDragOver(null)} onDrop={zoneDrop(null)}>
              {renderRows(ungrouped, 0)}
              {folders.length > 0 && ungrouped.length === 0 && dragId ? (
                <div className="min-w-[680px] border-b px-4 py-4 pl-12 text-sm text-muted-foreground">
                  Drop here to remove from folder.
                </div>
              ) : null}
            </div>
            {childFolders(null).map((folder) => renderFolder(folder, 0))}
          </>
        )}
      </div>
    </Card>
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

function InheritedColorField({ color }: { color: number }) {
  const colored = hasColor(color);
  return (
    <div className="flex items-center gap-2" title="Color is managed on the folder">
      <span
        aria-hidden="true"
        className="relative flex size-8 shrink-0 items-center justify-center rounded-md border"
        style={{ backgroundColor: colored ? colorToHex(color) : "transparent" }}
      >
        {colored ? null : <Palette className="size-4 text-muted-foreground" />}
      </span>
      <span className="text-xs text-muted-foreground">Folder</span>
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
    folderId: null,
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
      folderId: null,
    }));
}

function reflow<T extends FolderedItem>(items: T[], folders: Folder[]): T[] {
  const known = new Set(folders.map((folder) => folder.id));
  const isRoot = (folder: Folder) => folder.parentId === null || !known.has(folder.parentId);
  const result = items.filter((item) => item.folderId === null || !known.has(item.folderId));
  const visited = new Set<string>();
  const walk = (parentId: string | null) => {
    folders
      .filter((folder) => (parentId === null ? isRoot(folder) : folder.parentId === parentId))
      .forEach((folder) => {
        if (visited.has(folder.id)) return;
        visited.add(folder.id);
        result.push(...items.filter((item) => item.folderId === folder.id));
        walk(folder.id);
      });
  };
  walk(null);
  return result;
}

function configToFolders(folders: ConfigFolder[]): Folder[] {
  return folders.map((folder) => ({
    id: folder.id ?? crypto.randomUUID(),
    name: folder.name,
    collapsed: false,
    color: folder.color ?? 0,
    parentId: folder.parent ?? null,
  }));
}

function foldersToConfig<T extends FolderedItem>(
  folders: Folder[],
  items: T[],
  keyOf: (item: T) => string,
): ConfigFolder[] {
  return folders
    .filter((folder) => folder.name.trim())
    .map((folder) => ({
      id: folder.id,
      name: folder.name.trim(),
      members: items
        .filter((item) => item.folderId === folder.id)
        .map(keyOf)
        .filter(Boolean),
      ...(hasColor(folder.color) ? { color: folder.color } : {}),
      ...(folder.parentId ? { parent: folder.parentId } : {}),
    }));
}

function folderColorFor(folderId: string | null, folders: Folder[]): number {
  if (!folderId) return 0;
  return folders.find((folder) => folder.id === folderId)?.color ?? 0;
}

function effectiveRoleColor(role: RoleItem, folders: Folder[]): number {
  const folderColor = folderColorFor(role.folderId, folders);
  return hasColor(folderColor) ? folderColor : role.color;
}

function assignFolders<T>(
  items: T[],
  configFolders: ConfigFolder[],
  folders: Folder[],
  keyOf: (item: T) => string,
  withFolder: (item: T, folderId: string | null) => T,
): T[] {
  const folderByKey = new Map<string, string>();
  const rankByKey = new Map<string, number>();
  let rank = 0;
  configFolders.forEach((configFolder, index) => {
    const folderId = folders[index]?.id;
    if (!folderId) return;
    configFolder.members.forEach((member) => {
      folderByKey.set(member, folderId);
      rankByKey.set(member, rank);
      rank += 1;
    });
  });
  return items
    .map((item) => withFolder(item, folderByKey.get(keyOf(item)) ?? null))
    .sort((first, second) => {
      const firstRank = rankByKey.get(keyOf(first)) ?? Number.MAX_SAFE_INTEGER;
      const secondRank = rankByKey.get(keyOf(second)) ?? Number.MAX_SAFE_INTEGER;
      return firstRank - secondRank;
    });
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
