import type { Metadata } from "next";
import "./styles/globals.css";

export const metadata: Metadata = {
  title: "面试助手",
  description: "面试准备与复盘助手",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
