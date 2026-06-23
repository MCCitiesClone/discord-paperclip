# Discord Paperclip Frontend

Hosted Next.js editor for `/paperclip editor` sessions.

## Development

```sh
npm install
npm run dev
```

The editor needs the same bytebin and bytesocks services configured in the plugin.
You can provide them in the UI or set:

```sh
NEXT_PUBLIC_BYTEBIN_URL=https://bytebin.example.com
NEXT_PUBLIC_BYTESOCKS_URL=wss://bytesocks.example.com
```

Open the editor URL printed by `/paperclip editor`. If the browser key has not been
trusted, copy the displayed `/paperclip editor trust <nonce>` command into Minecraft.
