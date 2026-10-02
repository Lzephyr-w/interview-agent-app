"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import ConfirmDialog from "@/components/ConfirmDialog";
import Toast from "@/components/Toast";
import { api, ApiError } from "@/lib/api";
import { toWav } from "@/lib/audio";

type Package = {
  id: string;
  company: string;
  role: string;
  interviewRound: string;
};
type Audio = { status: string; transcriptError: string };
type AnswerStart = { questionId: string; answerExpiresAt: string };
type AudioUpload = {
  id: string;
  chunkSizeBytes: number;
  totalBytes: number;
  totalParts: number;
  receivedParts: number[];
  status: string;
};
type SavedAudioUpload = {
  key: string;
  sessionId: string;
  questionId: string;
  uploadId: string;
  sha256: string;
  blob: Blob;
};
type Question = {
  id: string;
  state: string;
  questionText: string;
  questionType: "FUNDAMENTAL" | "PROJECT" | "SCENARIO" | "BEHAVIORAL";
  competency: string | null;
  sortOrder: number;
  answerExpiresAt: string | null;
  audio: Audio | null;
  sourceTitle: string | null;
  sourceLocation: string | null;
};
type Session = {
  id: string;
  company: string;
  role: string;
  interviewRound: string;
  status: string;
  generationVersion: string;
  prepared: boolean;
  finalInterviewId: string | null;
  totalQuestions: number;
  completedQuestions: number;
  currentQuestion: Question | null;
  task: { id: string; taskType: string; status: "PENDING" | "PROCESSING" | "FAILED"; error: string } | null;
};
type NextPreview = { questionText: string; sortOrder: number; sourceTitle: string | null; sourceLocation: string | null };
const QUESTION_LIMIT = 10;
const MAX_AUDIO_BYTES = 10 * 1024 * 1024;
const AUDIO_CHUNK_BYTES = 1024 * 1024;
const DIRECT_AUDIO_UPLOAD_BYTES = 4 * 1024 * 1024;
const AUDIO_UPLOAD_CONCURRENCY = 3;
const questionTypeLabel = {
  FUNDAMENTAL: "技术基础",
  PROJECT: "项目实践",
  SCENARIO: "场景分析",
  BEHAVIORAL: "行为协作",
};
const errorText = (error: unknown, fallback: string) =>
  error instanceof Error ? error.message : fallback;

const audioUploadKey = (sessionId: string, questionId: string) =>
  `${sessionId}:${questionId}`;
const openAudioUploads = () =>
  new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open("ai-mock-audio-uploads", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("uploads", { keyPath: "key" });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
async function savedAudioUpload(key: string) {
  const db = await openAudioUploads();
  return await new Promise<SavedAudioUpload | undefined>((resolve, reject) => {
    const request = db.transaction("uploads").objectStore("uploads").get(key);
    request.onsuccess = () => { db.close(); resolve(request.result as SavedAudioUpload | undefined); };
    request.onerror = () => { db.close(); reject(request.error); };
  });
}
async function saveAudioUpload(value: SavedAudioUpload) {
  const db = await openAudioUploads();
  await new Promise<void>((resolve, reject) => {
    const request = db.transaction("uploads", "readwrite").objectStore("uploads").put(value);
    request.onsuccess = () => { db.close(); resolve(); };
    request.onerror = () => { db.close(); reject(request.error); };
  });
}
async function removeAudioUpload(key: string) {
  const db = await openAudioUploads();
  await new Promise<void>((resolve, reject) => {
    const request = db.transaction("uploads", "readwrite").objectStore("uploads").delete(key);
    request.onsuccess = () => { db.close(); resolve(); };
    request.onerror = () => { db.close(); reject(request.error); };
  });
}
async function sha256(blob: Blob) {
  const bytes = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
  return [...new Uint8Array(bytes)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

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

function RoomLoading({ label }: { label: string }) {
  return (
    <section className="ai-room-brief ai-room-result ai-room-waiting" role="status">
      <div className="ai-room-waiting-mark" aria-hidden="true"><i /><i /><i /></div>
      <h1>{label}</h1>
      <div className="ai-room-waiting-track" aria-hidden="true"><span /></div>
    </section>
  );
}

export default function AiMockInterviewRoomPage() {
  const [selected, setSelected] = useState<Package>();
  const [session, setSession] = useState<Session>();
  const [nextPreview, setNextPreview] = useState<(NextPreview & { afterQuestionId: string }) | null>(null);
  const [entered, setEntered] = useState(false);
  const [remaining, setRemaining] = useState<number | null>(null);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const [recording, setRecording] = useState(false);
  const [preparingRecording, setPreparingRecording] = useState(false);
  const [uploadPending, setUploadPending] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [countdownDeadline, setCountdownDeadline] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [finishDialog, setFinishDialog] = useState(false);
  const [exitDialog, setExitDialog] = useState(false);
  const [welcomeExitDialog, setWelcomeExitDialog] = useState(false);
  const recorder = useRef<MediaRecorder | null>(null);
  const microphone = useRef<MediaStream | null>(null);
  const chunks = useRef<Blob[]>([]);
  const discardRecording = useRef(false);
  const spokenQuestion = useRef("");
  const expiredQuestion = useRef("");
  const answerTiming = useRef<{ questionId: string; startedAt: number } | null>(null);
  const preparePromise = useRef<Promise<Session> | null>(null);
  const leavingWelcome = useRef(false);
  const setup = useRef<{ selection: string; body: { interviewPackageId: string; sourceMode: string; categoryIds: string[] } }>({ selection: "", body: { interviewPackageId: "", sourceMode: "STANDARD", categoryIds: [] } });
  const initialized = useRef(false);
  const roomOpenedAt = useRef(0);
  const startClickedAt = useRef(0);
  const firstReadyLogged = useRef(false);
  const firstShownLogged = useRef(false);
  const previewLoadedFor = useRef("");
  const openingReview = useRef(false);
  const startingInterview = useRef(false);
  const startingRecording = useRef(false);
  const uploadActionPending = useRef(false);
  const current = session?.status === "RUNNING" ? session.currentQuestion : undefined;
  const allQuestionsCompleted = Boolean(session && session.completedQuestions >= session.totalQuestions);
  const blocksQuestion = Boolean(
    session?.task &&
      session.task.taskType !== "AI_FEEDBACK" &&
      session.currentQuestion?.state !== "OPEN",
  );

  function stopMicrophone() {
    microphone.current?.getTracks().forEach((track) => track.stop());
    microphone.current = null;
  }

  useEffect(
    () => () => {
      discardRecording.current = true;
      recorder.current?.stop();
      stopMicrophone();
    },
    [],
  );

  useEffect(() => {
    if (initialized.current) return;
    initialized.current = true;
    roomOpenedAt.current = performance.now();
    const params = new URLSearchParams(window.location.search);
    const packageId = params.get(
      "packageId",
    );
    if (!packageId) return;
    const sourceMode = params.get("sourceMode") === "KNOWLEDGE" ? "KNOWLEDGE" : "STANDARD";
    const categoryIds = sourceMode === "KNOWLEDGE" ? (params.get("categoryIds") ?? "").split(",").filter(Boolean) : [];
    const selection = sourceMode === "STANDARD" ? packageId : `${packageId}:KNOWLEDGE:${[...categoryIds].sort().join(",")}`;
    setup.current = { selection, body: { interviewPackageId: packageId, sourceMode, categoryIds } };
    const startedKey = `ai-mock-session:${selection}`;
    const preparedKey = `ai-mock-prepared:${selection}`;
    void api<Package[]>("/api/v1/interview-packages")
      .then((items) => {
        if (!leavingWelcome.current) setSelected(items.find((value) => value.id === packageId));
      })
      .catch((caught: unknown) => {
        if (!leavingWelcome.current) setError(errorText(caught, "加载面试信息失败。"));
      });
    const startedId=window.sessionStorage.getItem(startedKey);
    const preparedId=window.sessionStorage.getItem(preparedKey);
    preparePromise.current=(async () => {
      if (startedId) {
        try {
          const existing=await api<Session>(`/api/v1/ai-mock-interviews/${startedId}`);
          if (!existing.prepared) {
            if (!leavingWelcome.current) { setEntered(true); setSession(existing); }
            return existing;
          }
          window.sessionStorage.removeItem(startedKey);
        } catch { window.sessionStorage.removeItem(startedKey); }
      }
      if (preparedId) {
        try {
          const existing=await api<Session>(`/api/v1/ai-mock-interviews/${preparedId}`);
          if (existing.status === "RUNNING" && existing.prepared && (sourceMode === "STANDARD" || existing.generationVersion === "KNOWLEDGE_INCREMENTAL_V1")) {
            if (!leavingWelcome.current) setSession(existing);
            return existing;
          }
          if (existing.prepared) await api(`/api/v1/ai-mock-interviews/${preparedId}`,{method:"DELETE"});
        } catch { /* expired prepared session is replaced below */ }
        window.sessionStorage.removeItem(preparedKey);
      }
      const prepared=await api<Session>("/api/v1/ai-mock-interviews/prepare", {
        method: "POST", body: JSON.stringify(setup.current.body),
      });
      window.sessionStorage.setItem(preparedKey,prepared.id);
      if (!leavingWelcome.current) setSession(prepared);
      return prepared;
    })();
    void preparePromise.current.catch((caught: unknown) => {
      preparePromise.current=null;
      if (!leavingWelcome.current) setError(errorText(caught,"准备第一题失败，请重试。"));
    });
  }, []);
  useEffect(() => {
    if (!session || session.task?.status === "FAILED" ||
        (!session.task && !(entered && session.status === "RUNNING" && !current && !allQuestionsCompleted))) return;
    const timer = window.setInterval(() => {
      void api<Session>(`/api/v1/ai-mock-interviews/${session.id}`).then(setSession).catch(() => undefined);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [session?.id, session?.task?.id, session?.task?.status, session?.status, entered, current?.id, allQuestionsCompleted]);
  useEffect(() => () => window.speechSynthesis?.cancel(), [current?.id]);
  useEffect(() => {
    const timing = answerTiming.current;
    if (!current || !timing || current.id === timing.questionId) return;
    console.info("[ai-mock-timing]", JSON.stringify({
      stage: "answer_to_next_question",
      sessionId: session?.id,
      questionId: timing.questionId,
      elapsed_ms: Math.round(performance.now() - timing.startedAt),
    }));
    answerTiming.current = null;
  }, [current?.id, session?.id]);
  useEffect(() => {
    if (!session?.id || !current?.id) return;
    void savedAudioUpload(audioUploadKey(session.id, current.id))
      .then((saved) => setUploadPending(Boolean(saved)))
      .catch(() => undefined);
  }, [current?.id, session?.id]);
  useEffect(() => {
    if (current?.sortOrder !== 0 || firstReadyLogged.current) return;
    firstReadyLogged.current=true;
    const selectedAt=Number(window.sessionStorage.getItem(`ai-mock-selected-at:${setup.current.selection}`));
    if (selectedAt > 0 && Date.now()-selectedAt <= 15*60_000) console.info("[ai-mock-timing]",JSON.stringify({
      stage:"first_question_ready_from_package_selection",sessionId:session?.id,
      elapsed_ms:Date.now()-selectedAt,
    }));
    console.info("[ai-mock-timing]",JSON.stringify({
      stage:"first_question_ready_from_room_open",sessionId:session?.id,
      elapsed_ms:Math.round(performance.now()-roomOpenedAt.current),
    }));
  }, [current?.id,session?.id,selected?.id]);
  useEffect(() => {
    if (!entered || starting || !startClickedAt.current || current?.sortOrder !== 0 || firstShownLogged.current) return;
    firstShownLogged.current=true;
    console.info("[ai-mock-timing]",JSON.stringify({
      stage:"first_question_visible_after_start",sessionId:session?.id,
      elapsed_ms:Math.round(performance.now()-startClickedAt.current),
    }));
  }, [entered,starting,current?.id,session?.id]);
  useEffect(() => {
    if (current && spokenQuestion.current !== current.id) {
      if (!entered || starting) return;
      spokenQuestion.current = current.id;
      speak();
      if (current.sortOrder === 0) setNotice("面试已开始，第一题正在朗读。");
    }
  }, [current?.id,entered,starting]);
  useEffect(() => {
    setCountdownDeadline(null);
    setRemaining(null);
  }, [current?.id,entered]);
  useEffect(() => {
    if (!current) {
      setRemaining(null);
      return;
    }
    const deadline = countdownDeadline ?? (current?.answerExpiresAt
      ? new Date(current.answerExpiresAt).getTime()
      : null);
    if (!deadline || Number.isNaN(deadline)) {
      setRemaining(null);
      return;
    }
    if (busy || preparingRecording) return;
    const update = () =>
      setRemaining(
        Math.max(
          0,
          Math.ceil(
            (deadline - Date.now()) / 1000,
          ),
        ),
      );
    update();
    const timer = window.setInterval(update, 1000);
    return () => clearInterval(timer);
  }, [busy, countdownDeadline, preparingRecording, current?.id, current?.answerExpiresAt]);
  useEffect(() => {
    if (
      !current ||
      !session ||
      remaining !== 0 ||
      busy ||
      preparingRecording ||
      blocksQuestion ||
      expiredQuestion.current === current.id
    )
      return;
    expiredQuestion.current = current.id;
    if (recording) {
      setNotice("时间到，正在自动提交回答。 ");
      stopRecording();
      return;
    }
    void api<Session>(`/api/v1/ai-mock-interviews/${session.id}/questions/${current.id}/expire`, { method: "POST" })
      .then(setSession)
      .catch((caught: unknown) =>
        setError(errorText(caught, "题目已超时，请刷新后继续。")),
      );
  }, [blocksQuestion, busy, current?.id, preparingRecording, recording, remaining, session?.id]);
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
    if (!selected || startingInterview.current) return;
    startClickedAt.current=performance.now();
    if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
      setError("当前浏览器不支持录音，请更换支持录音的浏览器。");
      return;
    }
    startingInterview.current = true;
    setBusy(true);
    setStarting(true);
    try {
      microphone.current = await navigator.mediaDevices.getUserMedia({ audio: true });
      const prepared=await (preparePromise.current ?? api<Session>("/api/v1/ai-mock-interviews/prepare", {
        method:"POST",body:JSON.stringify(setup.current.body),
      }));
      if (session?.id !== prepared.id) setSession(prepared);
      const begun=await api<Session>(`/api/v1/ai-mock-interviews/${prepared.id}/begin`,{method:"POST"});
      window.sessionStorage.setItem(`ai-mock-session:${setup.current.selection}`,begun.id);
      window.sessionStorage.removeItem(`ai-mock-prepared:${setup.current.selection}`);
      setSession(begun);
      setEntered(true);
    } catch (caught) {
      setEntered(false);
      stopMicrophone();
      if (caught instanceof ApiError && (caught.status === 404 || caught.message.includes("模拟已结束或超时"))) {
        preparePromise.current = null;
        window.sessionStorage.removeItem(`ai-mock-prepared:${setup.current.selection}`);
      }
      setError(
        caught instanceof DOMException
          ? "麦克风权限被拒绝或不可用。"
          : errorText(caught, "AI 模拟无法开始。"),
      );
    } finally {
      startingInterview.current = false;
      setStarting(false);
      setBusy(false);
    }
  }
  async function retryTask() {
    if (!session?.task) return;
    setBusy(true);
    try {
      await api(`/api/v1/ai-mock-tasks/${session.task.id}/retry`, { method: "POST" });
      setSession(await api<Session>(`/api/v1/ai-mock-interviews/${session.id}`));
    } catch (caught) {
      setError(errorText(caught, "重试失败，请稍后重试。"));
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
    if (startingRecording.current || recorder.current?.state === "recording" || preparingRecording || recording || busy || uploadPending) return;
    startingRecording.current = true;
    setCountdownDeadline(null);
    setRemaining(null);
    setPreparingRecording(true);
    try {
      let activeStream = microphone.current;
      if (!activeStream || activeStream.getTracks().every((track) => track.readyState === "ended")) {
        activeStream = await navigator.mediaDevices.getUserMedia({ audio: true });
        microphone.current = activeStream;
      }
      const started = await api<AnswerStart>(
        `/api/v1/ai-mock-interviews/${session?.id}/questions/${current.id}/start-answer`,
        { method: "POST" },
      );
      if (started.questionId !== current.id) return;
      setCountdownDeadline(new Date(started.answerExpiresAt).getTime());
      setSession((previous) => previous?.currentQuestion?.id === current.id
        ? { ...previous, currentQuestion: { ...previous.currentQuestion, answerExpiresAt: started.answerExpiresAt } }
        : previous);
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
        if (!discardRecording.current) {
          const recorded = new Blob(chunks.current, {
            type: value.mimeType || "audio/webm",
          });
          void (async () => {
            try {
              if (recorded.type.startsWith("audio/ogg")) await upload(recorded);
              else {
                // ponytail: Tencent's recording-file API does not accept WebM containers.
                await upload(await toWav(recorded));
              }
            } catch (caught) {
              setError(errorText(caught, "录音处理失败，请重新录音。"));
              setBusy(false);
            }
          })();
        }
      };
      value.start();
      setRecording(true);
    } catch (caught) {
      setError(
        caught instanceof DOMException
          ? "麦克风权限被拒绝或不可用。 "
          : errorText(caught, "无法开始回答，请重试。"),
      );
    } finally {
      startingRecording.current = false;
      setPreparingRecording(false);
    }
  }
  function stopRecording() {
    if (recorder.current?.state !== "recording") return;
    if (current && session) {
      answerTiming.current = { questionId: current.id, startedAt: performance.now() };
      void loadNextPreview(session.id, current.id, answerTiming.current.startedAt);
    }
    setBusy(true);
    recorder.current?.stop();
    setRecording(false);
  }
  function cancelRecording() {
    if (recorder.current?.state !== "recording") return;
    discardRecording.current = true;
    recorder.current?.stop();
    setRecording(false);
    setNotice("录音已取消，未提交。 ");
  }
  function speak() {
    if (!entered || starting || !current || !window.speechSynthesis) return;
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(current.questionText);
    utterance.lang = "zh-CN";
    window.speechSynthesis.speak(utterance);
  }
  async function upload(blob: Blob) {
    if (!session || !current) return;
    if (blob.size > MAX_AUDIO_BYTES)
      throw new Error("录音超过 10 MiB，请缩短回答后重新录音。");
    const uploadStartedAt = performance.now();
    setBusy(true);
    setError("");
    try {
      const key = audioUploadKey(session.id, current.id);
      const digest = await sha256(blob);
      let saved = await savedAudioUpload(key);
      if (!saved) {
        saved = { key, sessionId: session.id, questionId: current.id, uploadId: "", sha256: digest, blob };
        await saveAudioUpload(saved);
      }
      if (saved.sha256 !== digest)
        throw new Error("已有未完成录音，请继续上传或放弃后重新录音。");
      setUploadPending(true);
      let updated: Session | undefined;
      if (!saved.uploadId && blob.size <= DIRECT_AUDIO_UPLOAD_BYTES) {
        const body = new FormData();
        body.append("file", blob, "answer");
        try {
          updated = await api<Session>(
            `/api/v1/ai-mock-interviews/${session.id}/questions/${current.id}/audio`,
            { method: "POST", body },
          );
        } catch (caught) {
          // ponytail: a lost response may follow a successful Storage commit; check before chunk fallback.
          const latest=await api<Session>(`/api/v1/ai-mock-interviews/${session.id}`);
          if(latest.status==="RUNNING" && latest.currentQuestion?.id===current.id && latest.currentQuestion.audio)
            updated=latest;
          else if(latest.status==="RUNNING" && latest.currentQuestion?.id!==current.id)
            updated=await api<Session>(`/api/v1/ai-mock-interviews/${session.id}/questions/${current.id}/audio`,{method:"POST",body});
          else if(caught instanceof ApiError && caught.status<500 && ![408,413,429].includes(caught.status)) throw caught;
        }
        if(updated) setUploadProgress(1);
      }
      if (!updated) {
        const transfer = saved.uploadId
          ? await api<AudioUpload>(
              `/api/v1/ai-mock-interviews/${session.id}/questions/${current.id}/audio-uploads/${saved.uploadId}`,
            )
          : await api<AudioUpload>(
              `/api/v1/ai-mock-interviews/${session.id}/questions/${current.id}/audio-uploads`,
              { method: "POST", body: JSON.stringify({ totalBytes: blob.size, sha256: digest }) },
            );
        if (!saved.uploadId) {
          saved = { ...saved, uploadId: transfer.id };
          await saveAudioUpload(saved);
        }
        if (transfer.status !== "UPLOADING") throw new Error("录音上传已过期，请重新录音。");
        const received = new Set(transfer.receivedParts);
        setUploadProgress(received.size / transfer.totalParts);
        const missing = Array.from({ length: transfer.totalParts }, (_, partNo) => partNo)
          .filter((partNo) => !received.has(partNo));
        let next = 0;
        let failed = false;
        let failure: unknown;
        // ponytail: three workers bound network load; a failed part stops new work while in-flight parts finish.
        await Promise.all(Array.from({ length: Math.min(AUDIO_UPLOAD_CONCURRENCY, missing.length) }, async () => {
          while (next < missing.length && !failed) {
            const partNo = missing[next++];
            const start = partNo * transfer.chunkSizeBytes;
            const part = blob.slice(start, Math.min(blob.size, start + transfer.chunkSizeBytes));
            try {
              await uploadPart(session.id, current.id, transfer, partNo, part);
              received.add(partNo);
              setUploadProgress(received.size / transfer.totalParts);
            } catch (caught) { if (!failed) { failed = true; failure = caught; } }
          }
        }));
        if (failed) throw failure;
        updated = await api<Session>(
          `/api/v1/ai-mock-interviews/${session.id}/questions/${current.id}/audio-uploads/${transfer.id}/complete`,
          { method: "POST" },
        );
      }
      await removeAudioUpload(key);
      console.info("[ai-mock-timing]", JSON.stringify({
        stage: "audio_upload",
        sessionId: session.id,
        questionId: current.id,
        bytes: blob.size,
        elapsed_ms: Math.round(performance.now() - uploadStartedAt),
      }));
      setUploadPending(false);
      setUploadProgress(0);
      setSession(updated);
      if (updated.currentQuestion?.id === current.id && previewLoadedFor.current !== current.id)
        void loadNextPreview(session.id, current.id, answerTiming.current?.startedAt ?? uploadStartedAt);
      const failed =
        updated.currentQuestion?.audio?.status === "FAILED"
          ? updated.currentQuestion.audio.transcriptError
          : "";
      if (failed) setError(failed);
      else setNotice("回答已提交，继续下一题。");
    } catch (caught) {
      setUploadPending(true);
      setError(caught instanceof SyntaxError
        ? "服务响应中断，录音已保留，可继续上传。"
        : errorText(caught, "上传已暂停，可继续上传或放弃后重新录音。"));
    } finally {
      setBusy(false);
    }
  }
  async function loadNextPreview(sessionId: string, questionId: string, startedAt: number) {
    if (previewLoadedFor.current === questionId) return;
    try {
      const preview = await api<NextPreview | undefined>(`/api/v1/ai-mock-interviews/${sessionId}/questions/${questionId}/next-preview?optional=true`);
      if (!preview) return;
      if (previewLoadedFor.current === questionId) return;
      previewLoadedFor.current = questionId;
      setNextPreview({ ...preview, afterQuestionId: questionId });
      console.info("[ai-mock-timing]", JSON.stringify({
        stage: "next_question_preview_after_stop", sessionId, questionId,
        elapsed_ms: Math.round(performance.now() - startedAt),
      }));
    } catch { /* a draft may not be ready yet; the normal next-question path remains available */ }
  }
  async function uploadPart(sessionId: string, questionId: string, transfer: AudioUpload, partNo: number, part: Blob) {
    const digest = await sha256(part);
    const start = partNo * transfer.chunkSizeBytes;
    const end = start + part.size - 1;
    for (let attempt = 0; ; attempt += 1) {
      try {
        await api<void>(
          `/api/v1/ai-mock-interviews/${sessionId}/questions/${questionId}/audio-uploads/${transfer.id}/parts/${partNo}`,
          {
            method: "PUT",
            body: part,
            headers: {
              "Content-Type": "application/octet-stream",
              "Content-Range": `bytes ${start}-${end}/${transfer.totalBytes}`,
              "X-Chunk-SHA256": digest,
            },
          },
        );
        return;
      } catch (caught) {
        const retryable = !(caught instanceof ApiError) || [408, 429, 500, 502, 503, 504].includes(caught.status);
        if (!retryable || attempt === 2) throw caught;
        await new Promise((resolve) => window.setTimeout(resolve, 500 * 2 ** attempt));
      }
    }
  }
  async function resumeUpload() {
    if (!session || !current || uploadActionPending.current) return;
    uploadActionPending.current = true;
    setBusy(true);
    try {
      const saved = await savedAudioUpload(audioUploadKey(session.id, current.id));
      if (!saved) { setUploadPending(false); return; }
      await upload(saved.blob);
    } catch (caught) {
      setError(errorText(caught, "无法继续上传，请稍后重试。"));
    } finally {
      uploadActionPending.current = false;
      setBusy(false);
    }
  }
  async function abandonUpload() {
    if (!session || !current || uploadActionPending.current) return;
    uploadActionPending.current = true;
    setBusy(true);
    try {
      const key = audioUploadKey(session.id, current.id);
      const saved = await savedAudioUpload(key);
      if (saved?.uploadId) {
        setSession(await api<Session>(`/api/v1/ai-mock-interviews/${session.id}/questions/${current.id}/audio-uploads/${saved.uploadId}`, { method: "DELETE" }));
      }
      await removeAudioUpload(key);
      setUploadPending(false);
      setUploadProgress(0);
      setNotice("已放弃未完成上传，可以重新录音。 ");
    } catch (caught) {
      setError(errorText(caught, "无法放弃上传，请稍后重试。"));
    } finally {
      uploadActionPending.current = false;
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
      if (selected) window.sessionStorage.removeItem(`ai-mock-session:${setup.current.selection}`);
      window.speechSynthesis?.cancel();
      stopMicrophone();
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
      stopMicrophone();
      if (selected) window.sessionStorage.removeItem(`ai-mock-session:${setup.current.selection}`);
      window.location.assign("/ai-mock-interviews");
    } catch (caught) {
      setError(errorText(caught, "取消失败，请重试。"));
    } finally {
      setBusy(false);
    }
  }
  async function leaveWelcome() {
    leavingWelcome.current = true;
    stopMicrophone();
    try {
      const prepared=await preparePromise.current;
      if(prepared && !entered) await api(`/api/v1/ai-mock-interviews/${prepared.id}`,{method:"DELETE"});
    } catch { /* welcome exit still returns to the selection page */ }
    window.sessionStorage.removeItem(`ai-mock-prepared:${setup.current.selection}`);
    window.location.assign("/ai-mock-interviews");
  }

  const time =
    remaining === null
      ? ""
      : `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, "0")}`;
  return (
    <main className="ai-room ai-room-studio">
      <Toast
        error={error}
        notice={notice}
        onDismissError={() => setError("")}
        onDismissNotice={() => setNotice("")}
      />
      <div className="ai-room-topbar">
        {selected && !entered ? (
          <button
            type="button"
            className="ai-room-welcome-exit"
            aria-label="退出模拟"
            title="退出模拟"
            onClick={() => setWelcomeExitDialog(true)}
            disabled={busy}
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
          <div className="ai-room-brand">
            <img src="/images/ai-assistant-light.png" alt="" />
            <span>智面 <small>面试练习室</small></span>
          </div>
          {selected && (
            <div className="ai-room-meta">
              <span>{selected.company}</span>
              <span>{selected.role}</span>
              <span>面试轮次 · {selected.interviewRound}</span>
            </div>
          )}
        </header>
      </div>
      <div className="ai-room-workspace">
        <div className="ai-room-interviewer" aria-hidden="true" />
        <div className="ai-room-console">
      {!selected ? (
        <section className="ai-room-brief">
          <h1>未选择面试包</h1>
          <Link className="ai-room-primary" href="/ai-mock-interviews">
            返回选择
          </Link>
        </section>
      ) : starting ? (
        <RoomLoading label="正在启动面试…" />
      ) : !entered ? (
        <section className="ai-room-brief ai-room-start">
          <h1>准备好开始了吗？</h1>
          <dl className="ai-room-start-details">
            <div><dt>面试公司</dt><dd>{selected.company}</dd></div>
            <div><dt>应聘岗位</dt><dd>{selected.role}</dd></div>
            <div><dt>面试轮次</dt><dd>{selected.interviewRound}</dd></div>
          </dl>
          <p>本轮共 {QUESTION_LIMIT} 道问题，每题最多 5 分钟。开始时请允许麦克风访问。</p>
          <button
            className="ai-room-primary"
            disabled={busy}
            onClick={() => void start()}
          >
            <Icon name="play" />
            {busy ? "正在准备…" : "开始模拟面试"}
          </button>
        </section>
      ) : !session ? (
        <RoomLoading label="题目加载中…" />
      ) : session.status === "FINISHED" ? (
        <section className="ai-room-brief ai-room-result">
          <h1>本次模拟已保存</h1>
          <dl className="ai-room-start-details">
            <div><dt>面试公司</dt><dd>{session.company}</dd></div>
            <div><dt>应聘岗位</dt><dd>{session.role}</dd></div>
            <div><dt>面试轮次</dt><dd>{session.interviewRound}</dd></div>
          </dl>
          {session.finalInterviewId && (
            <Link
              className="ai-room-primary"
              href={`/interviews/${session.finalInterviewId}/review`}
              onClick={(event) => {
                if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                if (openingReview.current) event.preventDefault();
                else openingReview.current = true;
              }}
            >
              进入复盘
            </Link>
          )}
        </section>
      ) : nextPreview && current && nextPreview.afterQuestionId === current.id &&
          (busy || (current.state === "TRANSCRIBING" && !uploadPending)) && session.task?.status !== "FAILED" ? (
        <section className="ai-room-stage" role="status">
          <div className="ai-room-question">
            <div className="ai-room-question-top"><span>下一题预览 · {nextPreview.sortOrder + 1}/{session.totalQuestions}</span></div>
            <h1>{nextPreview.questionText}</h1>
            {nextPreview.sourceTitle && <p className="ai-room-source">来源：{nextPreview.sourceTitle} · {nextPreview.sourceLocation}</p>}
            <p className="ai-room-processing">上一题录音正在提交，保存成功后即可开始回答。</p>
            {uploadPending && <p className="ai-room-processing">正在上传录音 {Math.round(uploadProgress * 100)}%…</p>}
          </div>
        </section>
      ) : blocksQuestion && session.task?.status === "FAILED" ? (
        <section className="ai-room-brief ai-room-result">
          <h1>AI 处理失败</h1>
          <p className="ai-room-error">{session.task.error || "后台处理失败，请重试。"}</p>
          <button className="ai-room-primary" onClick={() => void retryTask()} disabled={busy}>重试</button>
          <button className="ai-room-primary" onClick={() => setFinishDialog(true)} disabled={busy}>结束并保存已答内容</button>
        </section>
      ) : blocksQuestion && session.task ? (
        <RoomLoading label={["AI_AUDIO", "AI_FINALIZE_AUDIO"].includes(session.task.taskType) &&
            !current && allQuestionsCompleted
            ? "面试结束中…" : "题目加载中…"} />
      ) : session.status === "TIME_EXPIRED" ? (
        <section className="ai-room-brief ai-room-result">
          <h1>本场模拟已超时</h1>
          <button className="ai-room-primary" onClick={() => setFinishDialog(true)} disabled={busy}>结束并保存已答内容</button>
        </section>
      ) : !current && !allQuestionsCompleted ? (
        <RoomLoading label="题目加载中…" />
      ) : current ? (
        <section className="ai-room-stage">
          <div className="ai-room-question">
            <progress className="ai-room-progress" value={current.sortOrder + 1} max={session.totalQuestions} aria-label="当前题目进度" />
            <div className="ai-room-question-top">
              <span>
                问题 {current.sortOrder + 1}/{session.totalQuestions}
              </span>
              <span className="ai-room-question-type">
                {questionTypeLabel[current.questionType]}
              </span>
              {time && <strong className="ai-room-timer" aria-label={`剩余作答时间 ${time}`}>{time}</strong>}
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
            {current.sourceTitle && <p className="ai-room-source">来源：{current.sourceTitle} · {current.sourceLocation}</p>}
            {session.task?.taskType === "AI_AUDIO" && session.task.status === "FAILED" && (
              <p className="ai-room-error">上一题转写失败，录音已保留。<button type="button" onClick={() => void retryTask()} disabled={busy}>重试转写</button></p>
            )}
            {preparingRecording || busy || blocksQuestion ? (
              <p className="ai-room-processing">
                {preparingRecording ? "正在准备回答…" : uploadPending ? `正在上传录音 ${Math.round(uploadProgress * 100)}%…` : "正在提交回答并准备下一题…"}
              </p>
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
            ) : uploadPending ? (
              <>
                <p className="ai-room-listening">录音上传已暂停（{Math.round(uploadProgress * 100)}%）</p>
                <div className="ai-room-actions">
                  <button type="button" className="ai-room-primary" onClick={() => void resumeUpload()}>继续上传</button>
                  <button type="button" className="ai-room-icon ai-room-cancel" aria-label="放弃录音" title="放弃录音" onClick={() => void abandonUpload()}><Icon name="close" /></button>
                </div>
              </>
            ) : (
              <>
                <p className="ai-room-listening">听完问题后，点击麦克风开始回答</p>
                {current.audio?.status === "FAILED" && (
                  <p className="ai-room-error">转写失败，请重新录音。</p>
                )}
                <div className="ai-room-actions">
                  <button
                    type="button"
                    className="ai-room-icon ai-room-mic"
                    disabled={preparingRecording}
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
          <dl className="ai-room-start-details">
            <div><dt>面试公司</dt><dd>{session.company}</dd></div>
            <div><dt>应聘岗位</dt><dd>{session.role}</dd></div>
            <div><dt>面试轮次</dt><dd>{session.interviewRound}</dd></div>
          </dl>
          {session.task?.taskType === "AI_AUDIO" && session.task.status !== "FAILED" && (
            <p className="ai-room-processing">录音正在后台转写，完成后即可保存面试。</p>
          )}
          {session.task?.taskType === "AI_AUDIO" && session.task.status === "FAILED" && (
            <p className="ai-room-error">录音转写失败，录音已保留。<button type="button" onClick={() => void retryTask()} disabled={busy}>重试转写</button></p>
          )}
          <button
            className="ai-room-primary"
            onClick={() => setFinishDialog(true)}
            disabled={session.task?.taskType === "AI_AUDIO" && session.task.status !== "FAILED"}
          >
            结束并保存
          </button>
        </section>
      )}
        </div>
      </div>
      <ConfirmDialog
        open={welcomeExitDialog}
        title="退出 AI 语音模拟？"
        description="当前尚未开始模拟，退出后将返回模拟面试页面。"
        confirmLabel="退出"
        cancelLabel="继续练习"
        onConfirm={() => void leaveWelcome()}
        onCancel={() => setWelcomeExitDialog(false)}
      />
      <ConfirmDialog
        open={finishDialog}
        title="结束 AI 模拟？"
        description="结束后会保存已确认的回答；仍在处理的录音不会计入复盘。"
        confirmLabel="结束并保存"
        cancelLabel="继续练习"
        busy={busy}
        onConfirm={() => void finish()}
        onCancel={() => setFinishDialog(false)}
      />
      <ConfirmDialog
        open={exitDialog}
        title="结束本次 AI 语音模拟？"
        description="结束会保存已确认的回答；仍在处理的录音不会计入复盘。取消则不保存本次记录。"
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
