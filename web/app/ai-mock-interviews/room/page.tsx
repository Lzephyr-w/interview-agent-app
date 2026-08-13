"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import ConfirmDialog from "@/components/ConfirmDialog";
import Toast from "@/components/Toast";
import { api } from "@/lib/api";

type Package = {
  id: string;
  company: string;
  role: string;
  interviewRound: string;
};
type Audio = { status: string; transcriptError: string };
type Question = {
  id: string;
  questionText: string;
  questionType: "FUNDAMENTAL" | "PROJECT" | "SCENARIO" | "BEHAVIORAL";
  competency: string | null;
  sortOrder: number;
  answerExpiresAt: string | null;
  audio: Audio | null;
};
type Session = {
  id: string;
  company: string;
  role: string;
  interviewRound: string;
  status: string;
  finalInterviewId: string | null;
  totalQuestions: number;
  currentQuestion: Question | null;
};
const QUESTION_LIMIT = 10;
const MAX_AUDIO_BYTES = 10 * 1024 * 1024;
const TRANSCRIPTION_SAMPLE_RATE = 16_000;
const questionTypeLabel = {
  FUNDAMENTAL: "技术基础",
  PROJECT: "项目实践",
  SCENARIO: "场景分析",
  BEHAVIORAL: "行为协作",
};
const errorText = (error: unknown, fallback: string) =>
  error instanceof Error ? error.message : fallback;

function Icon({ name }: { name: "mic" | "stop" | "sound" | "close" | "play" }) {
  const paths = {
    mic: (
      <>
        <rect x="9" y="3" width="6" height="11" rx="3" />
        <path d="M6 11a6 6 0 0 0 12 0M12 17v4M8 21h8" />
      </>
    ),
    stop: <rect x="7" y="7" width="10" height="10" rx="1" />,
    sound: (
      <>
        <path d="M4 10v4h4l5 4V6L8 10z" />
        <path d="M16 9a4 4 0 0 1 0 6M18.5 6.5a7 7 0 0 1 0 11" />
      </>
    ),
    close: (
      <>
        <path d="m7 7 10 10M17 7 7 17" />
      </>
    ),
    play: <path d="m9 6 10 6-10 6z" fill="currentColor" stroke="none" />,
  };
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      {paths[name]}
    </svg>
  );
}

export default function AiMockInterviewRoomPage() {
  const [selected, setSelected] = useState<Package>();
  const [session, setSession] = useState<Session>();
  const [remaining, setRemaining] = useState<number | null>(null);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const [recording, setRecording] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [finishDialog, setFinishDialog] = useState(false);
  const [exitDialog, setExitDialog] = useState(false);
  const [welcomeExitDialog, setWelcomeExitDialog] = useState(false);
  const recorder = useRef<MediaRecorder | null>(null);
  const chunks = useRef<Blob[]>([]);
  const discardRecording = useRef(false);
  const spokenQuestion = useRef("");
  const expiredQuestion = useRef("");
  const current = session?.currentQuestion;

  useEffect(() => {
    const packageId = new URLSearchParams(window.location.search).get(
      "packageId",
    );
    if (!packageId) return;
    void api<Package[]>("/api/v1/interview-packages")
      .then((items) => setSelected(items.find((item) => item.id === packageId)))
      .catch((caught: unknown) =>
        setError(errorText(caught, "加载面试信息失败。")),
      );
  }, []);
  useEffect(() => () => window.speechSynthesis?.cancel(), [current?.id]);
  useEffect(() => {
    if (current && spokenQuestion.current !== current.id) {
      spokenQuestion.current = current.id;
      speak();
    }
  }, [current?.id]);
  useEffect(() => {
    if (!current?.answerExpiresAt) {
      setRemaining(null);
      return;
    }
    if (busy) return;
    const update = () =>
      setRemaining(
        Math.max(
          0,
          Math.ceil(
            (new Date(current.answerExpiresAt!).getTime() - Date.now()) / 1000,
          ),
        ),
      );
    update();
    const timer = window.setInterval(update, 1000);
    return () => clearInterval(timer);
  }, [busy, current?.id, current?.answerExpiresAt]);
  useEffect(() => {
    if (
      !current ||
      !session ||
      remaining !== 0 ||
      busy ||
      expiredQuestion.current === current.id
    )
      return;
    expiredQuestion.current = current.id;
    if (recording) {
      setNotice("时间到，正在自动提交回答。 ");
      stopRecording();
      return;
    }
    void api<Session>(`/api/v1/ai-mock-interviews/${session.id}`)
      .then(setSession)
      .catch((caught: unknown) =>
        setError(errorText(caught, "题目已超时，请刷新后继续。")),
      );
  }, [busy, current?.id, recording, remaining, session?.id]);
  useEffect(() => {
    if (!recording) return;
    setRecordingSeconds(0);
    const started = Date.now();
    const timer = window.setInterval(
      () => setRecordingSeconds(Math.floor((Date.now() - started) / 1000)),
      1000,
    );
    return () => clearInterval(timer);
  }, [recording]);
  useEffect(() => {
    const leave = (event: KeyboardEvent) => {
      if (
        event.key === "Escape" &&
        session &&
        !recording &&
        !busy &&
        !finishDialog &&
        !exitDialog
      )
        setExitDialog(true);
    };
    window.addEventListener("keydown", leave);
    return () => window.removeEventListener("keydown", leave);
  }, [session, recording, busy, finishDialog, exitDialog]);

  async function start() {
    if (!selected) return;
    setBusy(true);
    try {
      const created = await api<Session>("/api/v1/ai-mock-interviews", {
        method: "POST",
        body: JSON.stringify({ interviewPackageId: selected.id }),
      });
      setSession(created);
      setNotice("面试已开始，第一题正在朗读。");
    } catch (caught) {
      setError(errorText(caught, "AI 模拟无法开始。"));
    } finally {
      setBusy(false);
    }
  }
  async function startRecording() {
    if (
      !current ||
      !navigator.mediaDevices?.getUserMedia ||
      !window.MediaRecorder
    ) {
      setError("当前浏览器不支持录音，请更换支持录音的浏览器。");
      return;
    }
    window.speechSynthesis?.pause();
    let stream: MediaStream | undefined;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const activeStream = stream;
      const started = await api<Session>(
        `/api/v1/ai-mock-interviews/${session?.id}/questions/${current.id}/start-answer`,
        { method: "POST" },
      );
      if (started.currentQuestion?.id !== current.id) {
        stream.getTracks().forEach((track) => track.stop());
        setSession(started);
        return;
      }
      const mimeType = MediaRecorder.isTypeSupported("audio/ogg;codecs=opus")
        ? "audio/ogg;codecs=opus"
        : "";
      const value = mimeType
        ? new MediaRecorder(activeStream, { mimeType })
        : new MediaRecorder(activeStream);
      chunks.current = [];
      discardRecording.current = false;
      recorder.current = value;
      value.ondataavailable = (event) => chunks.current.push(event.data);
      value.onstop = () => {
        activeStream.getTracks().forEach((track) => track.stop());
        if (!discardRecording.current) {
          const recorded = new Blob(chunks.current, {
            type: value.mimeType || "audio/webm",
          });
          void (async () => {
            try {
              const audio = recorded.type.includes("webm")
                ? await toWav(recorded)
                : recorded;
              await upload(audio);
            } catch (caught) {
              setError(errorText(caught, "录音处理失败，请重新录音。"));
              setBusy(false);
            }
          })();
        }
      };
      value.start();
      setSession(started);
      setRecording(true);
    } catch (caught) {
      stream?.getTracks().forEach((track) => track.stop());
      setError(
        caught instanceof DOMException
          ? "麦克风权限被拒绝或不可用。 "
          : errorText(caught, "无法开始回答，请重试。"),
      );
    }
  }
  function stopRecording() {
    setBusy(true);
    recorder.current?.stop();
    setRecording(false);
  }
  function cancelRecording() {
    discardRecording.current = true;
    recorder.current?.stop();
    setRecording(false);
    setNotice("录音已取消，未提交。 ");
  }
  async function toWav(blob: Blob) {
    const context = new AudioContext();
    try {
      const source = await context.decodeAudioData(await blob.arrayBuffer());
      const frameCount = Math.ceil(source.duration * TRANSCRIPTION_SAMPLE_RATE);
      const offline = new OfflineAudioContext(
        1,
        frameCount,
        TRANSCRIPTION_SAMPLE_RATE,
      );
      const node = offline.createBufferSource();
      node.buffer = source;
      node.connect(offline.destination);
      node.start();
      const samples = (await offline.startRendering()).getChannelData(0);
      const bytes = new ArrayBuffer(44 + samples.length * 2);
      const view = new DataView(bytes);
      const text = (offset: number, value: string) =>
        [...value].forEach((item, index) =>
          view.setUint8(offset + index, item.charCodeAt(0)),
        );
      text(0, "RIFF");
      view.setUint32(4, 36 + source.length * 2, true);
      text(8, "WAVEfmt ");
      view.setUint32(16, 16, true);
      view.setUint16(20, 1, true);
      view.setUint16(22, 1, true);
      view.setUint32(24, TRANSCRIPTION_SAMPLE_RATE, true);
      view.setUint32(28, TRANSCRIPTION_SAMPLE_RATE * 2, true);
      view.setUint16(32, 2, true);
      view.setUint16(34, 16, true);
      text(36, "data");
      view.setUint32(40, samples.length * 2, true);
      for (let index = 0; index < samples.length; index += 1) {
        view.setInt16(
          44 + index * 2,
          Math.max(-1, Math.min(1, samples[index])) * 0x7fff,
          true,
        );
      }
      return new Blob([bytes], { type: "audio/wav" });
    } finally {
      await context.close();
    }
  }
  function speak() {
    if (!current || !window.speechSynthesis) return;
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(current.questionText);
    utterance.lang = "zh-CN";
    window.speechSynthesis.speak(utterance);
  }
  async function upload(blob: Blob) {
    if (!session || !current) return;
    if (blob.size > MAX_AUDIO_BYTES)
      throw new Error("录音超过 10 MiB，请缩短回答后重新录音。");
    setBusy(true);
    try {
      const form = new FormData();
      form.append(
        "file",
        blob,
        blob.type === "audio/wav" ? "answer.wav" : "answer.ogg",
      );
      const updated = await api<Session>(
        `/api/v1/ai-mock-interviews/${session.id}/questions/${current.id}/audio`,
        { method: "POST", body: form },
      );
      setSession(updated);
      const failed =
        updated.currentQuestion?.audio?.status === "FAILED"
          ? updated.currentQuestion.audio.transcriptError
          : "";
      if (failed) setError(failed);
      else
        setNotice(
          updated.currentQuestion
            ? "回答已提交，正在进入下一题。"
            : "10 道题已完成，可以结束模拟。 ",
        );
    } catch (caught) {
      setError(errorText(caught, "提交回答失败，请重新录音。"));
    } finally {
      setBusy(false);
    }
  }
  async function finish() {
    if (!session) return;
    setBusy(true);
    try {
      const updated = await api<Session>(
        `/api/v1/ai-mock-interviews/${session.id}/finish`,
        { method: "POST" },
      );
      setSession(updated);
      setFinishDialog(false);
      setExitDialog(false);
      window.speechSynthesis?.cancel();
    } catch (caught) {
      setError(errorText(caught, "结束失败，请重试。"));
    } finally {
      setBusy(false);
    }
  }
  async function cancelWithoutSaving() {
    if (!session) return;
    setBusy(true);
    try {
      await api<void>(`/api/v1/ai-mock-interviews/${session.id}`, {
        method: "DELETE",
      });
      window.speechSynthesis?.cancel();
      window.location.assign("/ai-mock-interviews");
    } catch (caught) {
      setError(errorText(caught, "取消失败，请重试。"));
    } finally {
      setBusy(false);
    }
  }
  function leaveWelcome() {
    window.location.assign("/ai-mock-interviews");
  }

  const time =
    remaining === null
      ? ""
      : `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, "0")}`;
  return (
    <main className="ai-room">
      <Toast
        error={error}
        notice={notice}
        onDismissError={() => setError("")}
        onDismissNotice={() => setNotice("")}
      />
      <div className="ai-room-topbar">
        {selected && !session ? (
          <button
            type="button"
            className="ai-room-welcome-exit"
            aria-label="退出模拟"
            title="退出模拟"
            onClick={() => setWelcomeExitDialog(true)}
          >
            <Icon name="close" />
          </button>
        ) : (
          selected &&
          session?.status !== "FINISHED" && (
            <button
              type="button"
              className="ai-room-exit"
              aria-label="退出模拟"
              title="退出模拟"
              onClick={() => setExitDialog(true)}
              disabled={recording || busy}
            >
              <Icon name="close" />
            </button>
          )
        )}
        <header className="ai-room-header">
          <Link href="/ai-mock-interviews" className="ai-room-brand">
            面试练习室
          </Link>
          {selected && (
            <div className="ai-room-meta">
              <span>{selected.company}</span>
              <span>{selected.role}</span>
              <span>{selected.interviewRound}</span>
            </div>
          )}
        </header>
      </div>
      {!selected ? (
        <section className="ai-room-brief">
          <h1>未选择面试包</h1>
          <Link className="ai-room-primary" href="/ai-mock-interviews">
            返回选择
          </Link>
        </section>
      ) : !session ? (
        <section className="ai-room-brief ai-room-start">
          <p className="ai-room-kicker">
            {selected.company} · {selected.role}
          </p>
          <h1>准备好开始了吗？</h1>
          <p>
            本轮共 {QUESTION_LIMIT} 道问题，每题限时 5 分钟。录音仅用于本次转写与面试复盘。点击后生成第一题。
          </p>
          <button
            className="ai-room-primary"
            disabled={busy}
            onClick={() => void start()}
          >
            <Icon name="play" />
            {busy ? "正在准备…" : "开始模拟面试"}
          </button>
        </section>
      ) : session.status === "FINISHED" ? (
        <section className="ai-room-brief ai-room-result">
          <p className="ai-room-kicker">INTERVIEW COMPLETE</p>
          <h1>本次模拟已保存</h1>
          {session.finalInterviewId && (
            <Link
              className="ai-room-primary"
              href={`/interviews/${session.finalInterviewId}/review`}
            >
              进入复盘
            </Link>
          )}
        </section>
      ) : current ? (
        <section className="ai-room-stage">
          <div className="ai-room-question">
            <div className="ai-room-question-top">
              <span>
                问题 {current.sortOrder + 1}/{session.totalQuestions}
              </span>
              <span className="ai-room-question-type">
                {questionTypeLabel[current.questionType]}
              </span>
              {time && <strong>{time}</strong>}
            </div>
            {!recording && !busy && (
              <button
                type="button"
                className="ai-room-icon ai-room-replay"
                aria-label="重听问题"
                title="重听问题"
                onClick={speak}
              >
                <Icon name="sound" />
              </button>
            )}
            <h1>{current.questionText}</h1>
            {busy ? (
              <p className="ai-room-processing">正在提交回答并准备下一题…</p>
            ) : recording ? (
              <>
                <div className="ai-room-wave" aria-hidden="true">
                  {Array.from({ length: 30 }, (_, index) => (
                    <i key={index} />
                  ))}
                </div>
                <p className="ai-room-recording">
                  <b />{" "}
                  {String(Math.floor(recordingSeconds / 60)).padStart(2, "0")}:
                  {String(recordingSeconds % 60).padStart(2, "0")}
                </p>
                <div className="ai-room-actions">
                  <button
                    type="button"
                    className="ai-room-icon ai-room-stop"
                    aria-label="结束回答并提交"
                    title="结束回答并提交"
                    onClick={stopRecording}
                  >
                    <Icon name="stop" />
                  </button>
                  <button
                    type="button"
                    className="ai-room-icon ai-room-cancel"
                    aria-label="取消本次录音"
                    title="取消本次录音"
                    onClick={cancelRecording}
                  >
                    <Icon name="close" />
                  </button>
                </div>
              </>
            ) : (
              <>
                <p className="ai-room-listening">面试官正在提问</p>
                {current.audio?.status === "FAILED" && (
                  <p className="ai-room-error">转写失败，请重新录音。</p>
                )}
                <div className="ai-room-actions">
                  <button
                    type="button"
                    className="ai-room-icon ai-room-mic"
                    aria-label={
                      current.audio?.status === "FAILED"
                        ? "重新回答"
                        : "开始回答"
                    }
                    title={
                      current.audio?.status === "FAILED"
                        ? "重新回答"
                        : "开始回答"
                    }
                    onClick={() => void startRecording()}
                  >
                    <Icon name="mic" />
                  </button>
                </div>
              </>
            )}
          </div>
        </section>
      ) : (
        <section className="ai-room-brief ai-room-result">
          <p className="ai-room-kicker">
            {QUESTION_LIMIT} / {QUESTION_LIMIT} COMPLETE
          </p>
          <h1>已完成本轮问题</h1>
          <button
            className="ai-room-primary"
            onClick={() => setFinishDialog(true)}
          >
            结束并保存
          </button>
        </section>
      )}
      <ConfirmDialog
        open={welcomeExitDialog}
        title="退出 AI 语音模拟？"
        description="当前尚未开始模拟，退出后将返回模拟面试页面。"
        confirmLabel="退出"
        cancelLabel="继续练习"
        onConfirm={leaveWelcome}
        onCancel={() => setWelcomeExitDialog(false)}
      />
      <ConfirmDialog
        open={finishDialog}
        title="结束 AI 模拟？"
        description="按 Esc 或点击结束后，会保存本次模拟记录。"
        confirmLabel="结束并保存"
        cancelLabel="继续练习"
        busy={busy}
        onConfirm={() => void finish()}
        onCancel={() => setFinishDialog(false)}
      />
      <ConfirmDialog
        open={exitDialog}
        title="结束本次 AI 语音模拟？"
        description="你可以继续练习、取消且不保存，或结束并保存当前记录。"
        confirmLabel="结束并保存"
        cancelLabel="继续练习"
        alternativeLabel="取消不保存"
        alternativeTone="danger"
        busy={busy}
        onConfirm={() => void finish()}
        onAlternative={() => void cancelWithoutSaving()}
        onCancel={() => setExitDialog(false)}
      />
    </main>
  );
}
