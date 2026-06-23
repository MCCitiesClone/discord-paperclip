# Discord Paperclip Frontend

Hosted Next.js editor for `/paperclip editor` sessions.

## Development

```sh
npm install
npm run dev
```

The editor needs the same bytebin and bytesocks services configured in the plugin.
Bytebin should support LuckPerms-style `POST /post` uploads that return a content key in
the `Location` response header.
Configure both endpoints through environment variables before starting or building the app:

```sh
NEXT_PUBLIC_BYTEBIN_URL=https://bytebin.example.com
NEXT_PUBLIC_BYTESOCKS_URL=wss://bytesocks.example.com
```

Open the editor URL printed by `/paperclip editor`; the session ID is read from the
`session` query parameter and connected automatically. If the browser key has not
been trusted, copy the displayed `/paperclip editor trust <nonce>` command into
Minecraft.
