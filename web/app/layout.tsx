import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "tradecore OMS",
  description: "Order management — submit, cancel, and watch orders fill.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
