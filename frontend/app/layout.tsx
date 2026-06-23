import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Discord Paperclip Editor",
  description: "Hosted config editor for Discord Paperclip.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
