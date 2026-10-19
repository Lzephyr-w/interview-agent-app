"use client";

import { useEffect, useRef, useState, type MouseEvent } from "react";
import { useRouter } from "next/navigation";
import LibraryPage from "../library/page";
import MockInterviewsPage from "../mock-interviews/page";
import InterviewsPage from "../interviews/page";
import WeaknessesPage from "../weaknesses/page";
import WeaknessAnalysisProvider from "@/components/WeaknessAnalysisProvider";
import { GUIDE_EVENT, guideExample, resetGuideDemo, type GuideAction } from "@/lib/guide-demo";

type Page = "library" | "interviews" | "interview-new" | "interview-detail" | "interview-questions" | "mock" | "weaknesses";
type Step = { phase: number; title: string; description: string; target: string; mode: "read" | "input" | "click" | "action"; example?: string; expected?: string; action?: GuideAction; page?: Page };
const target = (name: string) => `[data-guide="${name}"]`;
const phases = ["准备资料", "真实面试", "模拟作答", "查看复盘", "定向训练"];
const interviewPaths: Partial<Record<Page, string>> = {
  interviews: "/interviews", "interview-new": "/interviews/new", "interview-detail": "/interviews/guide-real", "interview-questions": "/interviews/guide-real/questions",
};
const steps: Step[] = [
  { phase: 0, title: "第一站，资料库", description: "点击左侧「资料库」。简历、岗位 JD 和面试包都在这里管理。", target: '[data-guide-nav="/library"]', mode: "click", page: "library" },
  { phase: 0, title: "简历已经准备好了", description: "已为你准备一份示例简历。正式使用时，在左侧选择 PDF 或 Word 文件并上传，等待正文解析完成。", target: `${target("resume")} > div`, mode: "read" },
  { phase: 0, title: "告诉平台，你想面试什么岗位", description: "点击「岗位 JD」，录入目标岗位的要求。", target: target("tab-job-descriptions"), mode: "click" },
  { phase: 0, title: "填写目标公司", description: "在高亮输入框填写公司名称。你可以自己输入，也可以填入示例。", target: target("jd-company"), mode: "input", example: guideExample.company },
  { phase: 0, title: "填写目标岗位", description: "输入岗位名称，例如「前端开发工程师」。", target: target("jd-role"), mode: "input", example: guideExample.role },
  { phase: 0, title: "粘贴岗位要求", description: "JD 让后续问题更贴近岗位要求。输入一段岗位描述，或使用下面的示例。", target: target("jd-content"), mode: "input", example: guideExample.jd },
  { phase: 0, title: "保存你的第一份 JD", description: "点击「保存 JD」。保存成功后，才能把它加入面试包。", target: target("jd-save"), mode: "action", action: "jd" },
  { phase: 0, title: "把资料组合成面试包", description: "点击「面试包」。一个面试包将简历、JD 和可选的项目证据卡组合起来。", target: target("tab-interview-packages"), mode: "click" },
  { phase: 0, title: "填写面试轮次", description: "输入「技术一面」，方便区分不同轮次的训练。", target: target("package-round"), mode: "input", example: guideExample.round },
  { phase: 0, title: "选择示例简历", description: "展开下拉框，选择已准备的「示例简历-前端开发.pdf」。", target: target("package-resume"), mode: "input", expected: "guide-resume" },
  { phase: 0, title: "关联刚才的 JD", description: "选择刚保存的岗位 JD。公司和岗位会随之关联；项目证据卡可以之后再补充。", target: target("package-jd"), mode: "input", expected: "guide-jd" },
  { phase: 0, title: "资料准备完成", description: "点击「创建面试包」，接下来就能用它开始训练了。", target: target("package-save"), mode: "action", action: "package" },
  { phase: 1, title: "记录一场真实面试", description: "点击左侧「面试记录」。这里既能记录实际参加过的面试，也能查看已保存的 AI 模拟；本次用示例演练真实面试。", target: '[data-guide-nav="/interviews"]', mode: "click", page: "interviews" },
  { phase: 1, title: "新建面试记录", description: "点击「创建面试」，先保存这场面试的基本信息，再整理面试官的问题和自己的回答。", target: target("interview-create"), mode: "click", page: "interview-new" },
  { phase: 1, title: "关联本次面试资料", description: "选择刚创建的面试包，公司、岗位和轮次会自动带入。正式使用时，可以按实际面试修改。", target: target("interview-package"), mode: "input", expected: "guide-package" },
  { phase: 1, title: "填写实际面试时间", description: "选择面试发生的日期和时间，或先填入示例。这个时间会显示在面试历史和详情中。", target: target("interview-time"), mode: "input", example: guideExample.interviewTime },
  { phase: 1, title: "区分复盘状态和面试结果", description: "先保留「待复盘」和「未知」。复盘状态用于整理进度，面试结果按实际反馈填写，不把练习表现当作录用结果。", target: target("interview-outcome"), mode: "read" },
  { phase: 1, title: "补充现场备注", description: "备注可选，用来记面试形式、印象或待补充内容。本次先填入一段示例，正式使用时按真实情况记录。", target: target("interview-notes"), mode: "input", example: guideExample.notes },
  { phase: 1, title: "保存这场真实面试", description: "点击「创建面试」。创建成功后进入面试概览；这一步只创建基本记录，还没有添加题目。", target: target("interview-save"), mode: "action", action: "real-interview", page: "interview-detail" },
  { phase: 1, title: "基本记录和题目分开保存", description: "概览显示面试时间、问答数量和复盘次数。现在问答为 0 道，接下来需要把现场问题和自己的回答加进来。", target: target("interview-overview"), mode: "read" },
  { phase: 1, title: "进入问答记录", description: "点击「进入问答记录」，开始整理面试官问了什么，以及你当时怎么回答。", target: target("interview-questions"), mode: "click", page: "interview-questions" },
  { phase: 1, title: "三种添加题目的方式", description: "手动添加适合逐题补录；粘贴文本可分段加入或先让 AI 识别并预览；录音导入需上传有权记录的音频，核对识别结果后确认加入。本次亲手体验前两种。", target: ".qa-methods", mode: "read" },
  { phase: 1, title: "先手动添加一道题", description: "点击「手动添加」，把记得最清楚的一道问题先记录下来。", target: target("question-manual"), mode: "click" },
  { phase: 1, title: "写下面试官的问题", description: "在「问题」中填写当时被问到的内容。可以填入示例，也可以按你记得的真实问题输入。", target: target("question-title"), mode: "input", example: guideExample.question },
  { phase: 1, title: "记录你当时的回答", description: "填写当时实际说过的回答，不用在这里改成理想答案；没答上的题可以在自评中标记「没答上」。本次先使用示例回答。", target: target("question-answer"), mode: "input", example: guideExample.answer },
  { phase: 1, title: "为这道题选择自评", description: "选择「答得好」「不确定」或「没答上」。本次可以保留「不确定」，以后根据逐题复盘继续改进。", target: target("question-assessment"), mode: "input" },
  { phase: 1, title: "把这道问答加入记录", description: "点击「添加问答」。只有添加成功，问题和回答才会出现在下方的本场问答中。", target: target("question-add"), mode: "action", action: "question" },
  { phase: 1, title: "展开刚保存的问题", description: "点击下方的问题标题，查看已保存的回答和自评。正式使用时，也可以在展开区域修改并保存，或删除这道题。", target: ".question-list summary", mode: "click" },
  { phase: 1, title: "确认题目和回答已经保存", description: "这里展示刚才输入的问题、回答与自评。它们属于这场真实面试，与 AI 模拟的原始问答分开保存。", target: ".question-list .question-editor", mode: "read" },
  { phase: 1, title: "收起已经核对的题目", description: "再次点击问题标题收起详情，回到题目列表。接下来再添加两组文本问答，便于一起检查。", target: ".question-list summary", mode: "click" },
  { phase: 1, title: "再试试批量添加", description: "点击「粘贴文本」。手上已有文字笔记或转写时，可以一次整理多道题，不必逐道输入。", target: target("question-text"), mode: "click" },
  { phase: 1, title: "用空行分开每组问答", description: "填入示例：每组第一行写问题，后面写回答，组与组之间留一个空行。请先核对文本，再点击分段加入。", target: target("question-transcript"), mode: "input", example: guideExample.transcript },
  { phase: 1, title: "分段并加入本场问答", description: "点击「分段并加入问答」。这条路径直接按空行拆分，不调用 AI；「AI 识别并预览」则需要核对预览后再确认。", target: target("question-segment"), mode: "action", action: "segment" },
  { phase: 1, title: "真实面试记录整理完成", description: "新增问题已加入列表，原有手动问答仍然保留。正式使用时可逐题展开检查，完成后通过「去看复盘」发起 AI 复盘。下面继续体验 AI 文本模拟。", target: target("question-list"), mode: "read" },
  { phase: 2, title: "进入 AI 文本模拟", description: "点击左侧「AI 文本模拟」。先用文字梳理回答，熟悉后也可以尝试语音模拟。", target: '[data-guide-nav="/mock-interviews"]', mode: "click", page: "mock" },
  { phase: 2, title: "选择本次训练资料", description: "选择刚刚创建的面试包。公司、岗位和轮次会自动填入。", target: target("mock-package"), mode: "input", expected: "guide-package" },
  { phase: 2, title: "先练一道题", description: "把主问题数量改成「1」。正式训练可以选择 1–10 道，本次先体验一题。", target: "#mock-main-count", mode: "input", expected: "1" },
  { phase: 2, title: "开始这场练习", description: "点击「开始 AI 文本模拟」。体验区会立即提供教学示例题目，无需等待 AI。", target: target("mock-start"), mode: "action", action: "start" },
  { phase: 2, title: "亲手完成一次回答", description: "读题后输入你的回答，按「定位问题 → 选择方案 → 验证结果」组织。也可以填入示例再修改。", target: "#mock-answer", mode: "input", example: guideExample.answer },
  { phase: 2, title: "给自己的回答做个判断", description: "选择「答得好」「不确定」或「没答上」。自评帮助你标记需要关注的内容。", target: target("mock-assessment"), mode: "input" },
  { phase: 2, title: "提交，而不是只看示例", description: "点击「提交回答」。只有实际提交后，回答才会进入本场训练记录。", target: target("mock-submit"), mode: "action", action: "answer" },
  { phase: 3, title: "打开逐题反馈", description: "点击已提交的题目，展开自己的回答和教学示例反馈。", target: ".mock-history summary", mode: "click" },
  { phase: 3, title: "把反馈变成改进方向", description: "这里同时展示你的回答和反馈。本次教学反馈提醒你补充测量条件、前后对比数据和个人贡献；正式使用时，AI 会根据实际回答生成反馈。", target: ".mock-history-detail", mode: "read" },
  { phase: 3, title: "结束并保留本场内容", description: "点击「结束并保存」。正式使用时，训练会形成一条可继续复盘的面试记录。", target: target("mock-finish"), mode: "click" },
  { phase: 3, title: "确认保存", description: "点击「保存为面试记录」。本次只保存到体验区，退出后会清除。", target: ".confirm-dialog .dialog-confirm-button.primary", mode: "action", action: "finish" },
  { phase: 3, title: "完成第一次复盘", description: "本场问题、你的回答和逐题反馈集中保存在这里。正式使用时，还可以进入面试记录查看完整复盘。", target: target("mock-review"), mode: "read" },
  { phase: 4, title: "找到下一步该练什么", description: "点击左侧「薄弱点」，从面试回答与复盘中找到具体练习方向。", target: '[data-guide-nav="/weaknesses"]', mode: "click", page: "weaknesses" },
  { phase: 4, title: "主动发起分析", description: "点击「开始 AI 分析」。正式平台只在你主动发起时分析；本次显示预设教学结果。", target: target("analyze"), mode: "action", action: "analysis" },
  { phase: 4, title: "查看分析结果", description: "分析完成了，点击「前往查看」继续。", target: ".confirm-dialog .dialog-confirm-button.primary", mode: "click" },
  { phase: 4, title: "查看具体薄弱点", description: "点击有标记的「成果量化」，查看诊断、建议动作和关联问题。", target: '.weakness-dimension-chips button[data-identified="true"]', mode: "click" },
  { phase: 4, title: "知道原因，也知道怎么练", description: "分析详情说明为什么需要改进，以及下一步怎么练。建议应当回到真实经历，不编造项目数据。", target: ".analysis-details", mode: "read" },
  { phase: 4, title: "创建一个有来源的训练任务", description: "点击「创建关联训练」，自动带入建议动作，并关联这次练习的问题。", target: ".weakness-report-item-heading .primary-button", mode: "click" },
  { phase: 4, title: "确认你的练习内容", description: "这里已经带入建议动作。可以修改成自己的练习计划，再继续保存。", target: "#training-task-editor textarea", mode: "input" },
  { phase: 4, title: "把建议保存成任务", description: "点击「创建任务」。以后可以在训练任务里管理练习安排和完成状态。", target: "#training-task-editor form .primary-button", mode: "action", action: "task" },
  { phase: 4, title: "最后一步，标记练习完成", description: "把任务状态切换为「已完成」，亲手走完准备、作答、复盘和改进的闭环。", target: ".training-task-status", mode: "action", action: "completed" },
];
type Rect = { top: number; left: number; right: number; bottom: number };
const focusable = 'button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), a[href], summary, [tabindex="0"]';

export default function OperationGuidePage() {
  const router = useRouter();
  const [index, setIndex] = useState(-1);
  const [page, setPage] = useState<Page>("library");
  const [run, setRun] = useState(0);
  const [ready, setReady] = useState(false);
  const [returnTo, setReturnTo] = useState("/library");
  const [rect, setRect] = useState<Rect | null>(null);
  const [position, setPosition] = useState({ top: 16, left: 16 });
  const [docked, setDocked] = useState(false);
  const [canContinue, setCanContinue] = useState(false);
  const panelRef = useRef<HTMLElement>(null);
  const targetRef = useRef<HTMLElement | null>(null);
  const step = steps[index];
  const complete = index === steps.length;

  function clearTab() {
    const url = new URL(window.location.href);
    url.searchParams.delete("tab"); url.searchParams.delete("interviewPath"); url.hash = "";
    window.history.replaceState(null, "", url);
  }

  function visitPage(next: Page) {
    const url = new URL(window.location.href);
    url.searchParams.delete("tab"); url.hash = "";
    if (interviewPaths[next]) url.searchParams.set("interviewPath", interviewPaths[next]);
    else url.searchParams.delete("interviewPath");
    window.history.replaceState(null, "", url);
    setPage(next);
  }

  useEffect(() => {
    const previous = new URLSearchParams(window.location.search).get("returnTo");
    if (previous?.startsWith("/")) {
      try {
        const url = new URL(previous, window.location.origin);
        if (url.origin === window.location.origin && url.pathname !== "/guide" && !url.pathname.startsWith("/guide/")) setReturnTo(url.pathname + url.search + url.hash);
      } catch { /* An invalid return path falls back to the library. */ }
    }
    clearTab(); resetGuideDemo(); setReady(true);
    return () => resetGuideDemo();
  }, []);

  function restart() {
    clearTab(); resetGuideDemo(); setIndex(-1); setPage("library"); setRun((value) => value + 1);
  }

  function leave(path = returnTo) {
    router.replace(path);
  }

  useEffect(() => {
    const action = (event: Event) => {
      if ((event as CustomEvent<GuideAction>).detail === step?.action) {
        if (step.page) visitPage(step.page);
        setIndex((value) => value === index ? value + 1 : value);
      }
    };
    window.addEventListener(GUIDE_EVENT, action);
    return () => window.removeEventListener(GUIDE_EVENT, action);
  }, [step, index]);

  useEffect(() => {
    setRect(null); setCanContinue(false); targetRef.current = null;
    if (!step) { panelRef.current?.focus(); return; }
    let frame = 0;
    let current: HTMLElement | null = null;
    const measure = () => {
      const element = document.querySelector(".operation-guide-stage")?.querySelector<HTMLElement>(step.target);
      if (!element) { targetRef.current = null; setRect(null); return; }
      const initial = element.getBoundingClientRect();
      const panelWidth = panelRef.current?.offsetWidth ?? 350;
      const dock = window.innerWidth <= 700 || (initial.right + panelWidth + 26 >= window.innerWidth && initial.left <= panelWidth + 26);
      setDocked(dock);
      if (current !== element) {
        current = element; targetRef.current = element;
        element.scrollIntoView({ block: "center", inline: "nearest" });
        if (element.matches(focusable)) element.focus({ preventScroll: true });
        else panelRef.current?.focus({ preventScroll: true });
      }
      if (dock && !element.closest(".confirm-dialog")) {
        const box = element.getBoundingClientRect();
        const available = window.innerHeight - (panelRef.current?.offsetHeight ?? 350) - 24;
        if (box.top < 80 || box.bottom > available) {
          window.scrollBy({ top: box.top - Math.max(80, (available - box.height) / 2), behavior: "instant" });
        }
      }
      const box = element.getBoundingClientRect();
      const height = window.innerHeight, width = window.innerWidth;
      const next = { left: Math.max(6, box.left - 6), top: Math.max(6, box.top - 6), right: Math.min(width - 6, box.right + 6), bottom: Math.min(height - 6, box.bottom + 6) };
      setRect(next);
      const panel = panelRef.current;
      const pw = panel?.offsetWidth ?? 350, ph = panel?.offsetHeight ?? 330;
      const left = next.right + pw + 20 < width ? next.right + 14 : Math.max(12, next.left - pw - 14);
      const top = Math.max(12, Math.min(next.top, height - ph - 12));
      setPosition({ top, left });
      const value = (element as HTMLInputElement).value;
      setCanContinue(step.mode === "read" || (step.mode === "input" && typeof value === "string" && (step.expected ? value === step.expected : Boolean(value.trim()))));
    };
    const schedule = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(measure); };
    const observer = new MutationObserver(schedule);
    observer.observe(document.body, { childList: true, subtree: true });
    const resize = new ResizeObserver(() => { current = null; schedule(); });
    if (panelRef.current) resize.observe(panelRef.current);
    window.addEventListener("resize", schedule);
    window.addEventListener("scroll", schedule, true);
    document.addEventListener("input", schedule);
    document.addEventListener("change", schedule);
    schedule();
    return () => {
      cancelAnimationFrame(frame); observer.disconnect(); resize.disconnect();
      window.removeEventListener("resize", schedule); window.removeEventListener("scroll", schedule, true);
      document.removeEventListener("input", schedule); document.removeEventListener("change", schedule);
    };
  }, [step, ready, run]);

  useEffect(() => {
    function keyboard(event: KeyboardEvent) {
      if (event.key === "Escape") { event.preventDefault(); event.stopImmediatePropagation(); leave(); return; }
      // Input steps advance explicitly; Enter must not submit a partly completed form.
      if (event.key === "Enter" && step?.mode === "input" && event.target instanceof HTMLInputElement) event.preventDefault();
      if (event.key !== "Tab") return;
      const panel = panelRef.current, active = targetRef.current;
      const items = [...(active && step?.mode !== "read" ? [active, ...active.querySelectorAll<HTMLElement>(focusable)].filter((item) => item.matches(focusable)) : []), ...Array.from(panel?.querySelectorAll<HTMLElement>(focusable) ?? [])].filter((item) => item.getClientRects().length);
      if (!items.length) return;
      event.preventDefault(); event.stopImmediatePropagation();
      const at = items.indexOf(document.activeElement as HTMLElement);
      items[(at + (event.shiftKey ? -1 : 1) + items.length) % items.length].focus();
    }
    document.addEventListener("keydown", keyboard, true);
    return () => document.removeEventListener("keydown", keyboard, true);
  });

  function click(event: MouseEvent<HTMLDivElement>) {
    const node = event.target as HTMLElement;
    if (panelRef.current?.contains(node)) return;
    const active = targetRef.current;
    if (!active?.contains(node) || step?.mode === "read") { event.preventDefault(); event.stopPropagation(); return; }
    const anchor = node.closest<HTMLAnchorElement>("a");
    if (anchor) {
      event.preventDefault(); event.stopPropagation();
      if (step?.page) { visitPage(step.page); setIndex((value) => value + 1); }
    } else if (step?.mode === "click") {
      // Let the actual page handle the click before locating the next control.
      setTimeout(() => setIndex((value) => value === index ? value + 1 : value), 0);
    }
  }

  function fillExample() {
    const element = step ? document.querySelector(".operation-guide-stage")?.querySelector(step.target) : null;
    if (!(element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) || !step?.example) return;
    const prototype = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, "value")?.set?.call(element, step.example);
    element.dispatchEvent(new Event("input", { bubbles: true }));
    element.focus();
  }

  return <div className="operation-guide" data-page={page} role="dialog" aria-modal="true" aria-label="平台操作体验" onClickCapture={click}>
    {ready && <div key={`${run}-${page}`} className="operation-guide-stage">
      {page === "library" ? <LibraryPage /> : interviewPaths[page] ? <InterviewsPage /> : page === "mock" ? <MockInterviewsPage /> : <WeaknessAnalysisProvider><WeaknessesPage /></WeaknessAnalysisProvider>}
    </div>}
    {step && rect ? <div className="operation-guide-mask" aria-hidden="true">
      <div style={{ top: 0, left: 0, right: 0, height: rect.top }} />
      <div style={{ top: rect.top, left: 0, width: rect.left, height: rect.bottom - rect.top }} />
      <div style={{ top: rect.top, left: rect.right, right: 0, height: rect.bottom - rect.top }} />
      <div style={{ top: rect.bottom, left: 0, right: 0, bottom: 0 }} />
      <span className="operation-guide-spotlight" style={{ top: rect.top, left: rect.left, width: rect.right - rect.left, height: rect.bottom - rect.top }} />
    </div> : <div className="operation-guide-curtain" aria-hidden="true" />}
    <aside className={`operation-guide-panel${!step ? " centered" : docked ? ` docked${step.target.includes(".confirm-dialog") ? " above" : ""}` : ""}`} ref={panelRef} style={step ? position : undefined} tabIndex={-1} aria-labelledby="operation-guide-title" aria-describedby="operation-guide-description">
      <div className="operation-guide-top"><span>✦ 操作指引 <small>体验区</small></span><button type="button" onClick={() => leave()} aria-label="退出操作指引">退出体验 ×</button></div>
      {step && <>
        <div className="operation-guide-phases" aria-label="体验阶段">{phases.map((phase, i) => <span key={phase} data-state={i < step.phase ? "done" : i === step.phase ? "active" : "pending"}>{i < step.phase ? "✓" : i + 1} {phase}</span>)}</div>
        <div className="operation-guide-progress"><progress value={index} max={steps.length} aria-label="操作指引进度" /><span>{index + 1} / {steps.length}</span></div>
      </>}
      <div aria-live="polite" aria-atomic="true">
        <span className="operation-guide-mark" aria-hidden="true">{complete ? "✓" : step ? String(index + 1).padStart(2, "0") : "↗"}</span>
        <h2 id="operation-guide-title">{complete ? "你已走完第一次练习！" : step?.title ?? "亲手试一次，就会用了"}</h2>
        <p id="operation-guide-description">{complete ? "从准备资料、记录真实面试和添加题目，到模拟作答、查看复盘及完成训练任务，你已经掌握了平台的主要流程。" : step?.description ?? "像完成游戏任务一样，跟着高亮一步步操作。用约 8 分钟，体验准备资料、记录真实面试、添加题目和复盘训练。"}</p>
      </div>
      {!step ? <>
        <div className="operation-guide-recap">{phases.map((phase, i) => <span key={phase}>{complete ? "✓" : `0${i + 1}`} {phase}</span>)}</div>
        <p className="operation-guide-note">{complete ? "本次示例数据会在退出后清除。随时可在使用手册中重新体验。" : "已备好示例简历，可自行输入或填入示例。体验不会修改正式数据，也不会调用真实 AI；刷新后从头开始。"}</p>
        <button className="primary-button" type="button" disabled={!ready} onClick={() => complete ? leave(returnTo === "/login" ? "/login" : "/library") : setIndex(0)}>{complete ? returnTo === "/login" ? "登录并开始使用" : "去资料库，开始自己的练习" : "开始体验 →"}</button>
        {complete && <button className="operation-guide-text-button" type="button" onClick={restart}>再体验一次</button>}
      </> : <>
        {step.example && <div className="operation-guide-example"><span>示例内容</span><p>{step.example}</p><button type="button" disabled={!rect} onClick={fillExample}>填入示例 ↗</button></div>}
        {!rect ? <p className="operation-guide-note" role="status">正在定位操作区域…可点击下方重新开始。</p> : step.mode === "read" || step.mode === "input" ? <button className="primary-button" type="button" disabled={!canContinue} onClick={() => { if (canContinue) setIndex((value) => value + 1); }}>{step.mode === "read" ? "了解了，继续 →" : canContinue ? "已填写，继续 →" : "完成高亮处的输入后继续"}</button> : <p className="operation-guide-instruction">{step.action === "completed" ? "↑ 在高亮处选择「已完成」" : "↑ 点击高亮处，完成后自动继续"}</p>}
        <div className="operation-guide-bottom"><span>示例体验 · 随时可退出</span><button type="button" onClick={restart}>重新开始</button></div>
      </>}
    </aside>
  </div>;
}
