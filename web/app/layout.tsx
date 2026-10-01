import type { Metadata } from "next";
import InlineConstraintValidation from "../components/InlineConstraintValidation";
import WeaknessAnalysisProvider from "../components/WeaknessAnalysisProvider";
import "./styles/globals.css";

export const metadata: Metadata = {
  title: "智面 · AI 面试训练平台",
  description: "智面 · AI 面试训练平台，提供智能模拟面试、逐题复盘与薄弱点训练，让每一次练习成为下一次的底气。",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>
        <InlineConstraintValidation />
        <WeaknessAnalysisProvider>{children}</WeaknessAnalysisProvider>
      </body>
    </html>
  );
}
