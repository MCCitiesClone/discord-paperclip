# Discord Paperclip

Discord Paperclip is a Paper plugin that keeps LuckPerms groups and Discord roles in sync.

The plugin is intentionally built for an externally hosted editor. `/paperclip editor`
uploads the current editable state to a bytebin-style service, joins a bytesocks-style
WebSocket relay channel, and prints an editor URL. No inbound Minecraft server ports are
required.

## Build

```sh
./gradlew build
```

The shaded plugin jar is produced in `build/libs/discord-paperclip-0.1.0.jar`.

## Runtime Requirements

- Paper 1.21.x
- LuckPerms
- A Discord bot with Manage Roles permission
- Discord Guild Members intent enabled for the bot

## Commands

- `/paperclip reload` reloads configuration and reconnects Discord.
- `/paperclip sync` runs a full reconciliation.
- `/paperclip editor` starts a hosted editor session.
- `/paperclip editor trust <nonce>` trusts an unknown browser/editor public key for a pending session.

## Hosted Editor Contract

The hosted editor follows the same broad shape as LuckPerms:

- `editor.bytebin-url` is the bytebin base URL. Payloads are uploaded with `POST {editor.bytebin-url}/post`; LuckPerms-style services return the content key in the `Location` response header.
- `GET {editor.bytebin-url}/{id}` returns a previously uploaded JSON payload.
- `editor.bytesocks-url/{channelId}` is a WebSocket relay channel shared by the plugin and browser.
- `editor.trusted-ca-certificates` can list PEM CA certificate files for private or
  self-signed bytebin/bytesocks TLS. Relative paths are resolved from the plugin data folder.

Example private CA configuration:

```yaml
editor:
  trusted-ca-certificates:
    - private-bytebin-ca.pem
```

For compatibility with simple bytebin clones, uploads also accept JSON response bodies with
`key`, `id`, or `location`, and fall back to `POST {editor.bytebin-url}` when `/post` is not
available.

Initial bytebin payloads include the WebSocket channel ID, the plugin public key, and the
current `group-role-map` and `linked-accounts`. Socket frames use this shape:

```json
{
  "msg": "{\"type\":\"ping\"}",
  "signature": "base64 SHA256withRSA signature of msg"
}
```

The browser sends `hello` with `nonce`, `sessionId`, `browser`, and `publicKey`. The plugin
verifies that hello signature with the supplied browser key. If the browser key fingerprint
is not already listed in `editor.trusted-editor-keys`, the admin must run
`/paperclip editor trust <nonce>`.

After trust, the browser can send `ping` and `request-changes`. For `request-changes`, the
browser uploads edited config to bytebin and sends its payload ID over the socket. The plugin
applies the changes, uploads refreshed state to bytebin, and replies with `changes-applied`
containing the refreshed payload ID.
