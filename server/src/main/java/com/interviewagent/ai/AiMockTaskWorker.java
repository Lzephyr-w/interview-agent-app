package com.interviewagent.ai;

import com.interviewagent.aimock.AiMockInterviewService;
import com.interviewagent.aimock.InterviewPackagePreparationService;
import com.interviewagent.mock.MockInterviewService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class AiMockTaskWorker {
    private final AiMockTaskService tasks;
    private final MockInterviewService text;
    private final AiMockInterviewService voice;
    private final InterviewPackagePreparationService preparations;
    private final ThreadPoolExecutor workers;

    public AiMockTaskWorker(AiMockTaskService tasks, MockInterviewService text, AiMockInterviewService voice, InterviewPackagePreparationService preparations,
                            @Value("${app.ai-mock-task.workers:4}") int workerCount) {
        this.tasks = tasks; this.text = text; this.voice = voice; this.preparations=preparations;
        // ponytail: same-session tasks stay serial; tune this pool only for independent sessions.
        workers = new ThreadPoolExecutor(Math.max(1, workerCount), Math.max(1, workerCount), 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Scheduled(fixedDelayString = "${app.ai-mock-task.poll-ms:1000}")
    public void run() {
        for (int i = 0; i < 4; i++) {
            AiMockTaskService.ClaimedTask task = tasks.claim();
            if (task == null) return;
            workers.execute(() -> process(task));
        }
    }

    private void process(AiMockTaskService.ClaimedTask task) {
        long started=System.nanoTime();
        try {
            switch (task.taskType()) {
                case InterviewPackagePreparationService.TASK_TYPE -> { preparations.processTask(task); voice.publishPreparation(task.userId(),task.resourceId()); }
                case "MOCK_CREATE", "MOCK_ANSWER", "MOCK_NEXT", "MOCK_FEEDBACK" -> text.processTask(task);
                case "AI_PLAN", "AI_FIRST", "AI_CREATE", "AI_NEXT", "AI_PREPARE_NEXT", "AI_FINALIZE_AUDIO", "AI_AUDIO", "AI_FEEDBACK" -> voice.processTask(task);
                default -> throw new IllegalStateException("后台任务类型无效，请稍后重试。");
            }
            tasks.complete(task);
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(AiMockTaskWorker.class).warn("simulation taskId={} sessionId={} operation={} code={} causeType={}",task.id(),task.resourceId(),task.taskType(),exception instanceof SimulationException e?e.code():"INTERNAL_ERROR",exception.getClass().getSimpleName());
            tasks.fail(task, exception);
        } finally {
            if(task.taskType().startsWith("AI_") || InterviewPackagePreparationService.TASK_TYPE.equals(task.taskType())) org.slf4j.LoggerFactory.getLogger(AiMockTaskWorker.class).info("ai_mock_timing stage=task taskId={} sessionId={} taskType={} elapsed_ms={}",task.id(),task.resourceId(),task.taskType(),(System.nanoTime()-started)/1_000_000);
        }
    }

    @PreDestroy
    void shutdown() { workers.shutdown(); }
}
