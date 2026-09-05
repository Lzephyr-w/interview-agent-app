package com.interviewagent.ai;

import com.interviewagent.aimock.AiMockInterviewService;
import com.interviewagent.mock.MockInterviewService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiMockTaskWorker {
    private final AiMockTaskService tasks;
    private final MockInterviewService text;
    private final AiMockInterviewService voice;

    public AiMockTaskWorker(AiMockTaskService tasks, MockInterviewService text, AiMockInterviewService voice) {
        this.tasks = tasks; this.text = text; this.voice = voice;
    }

    @Scheduled(fixedDelayString = "${app.ai-mock-task.poll-ms:1000}")
    public void run() {
        for (int i = 0; i < 4; i++) {
            AiMockTaskService.ClaimedTask task = tasks.claim();
            if (task == null) return;
            try {
                switch (task.taskType()) {
                    case "MOCK_CREATE", "MOCK_ANSWER", "MOCK_NEXT" -> text.processTask(task);
                    case "AI_CREATE", "AI_NEXT", "AI_AUDIO", "AI_FEEDBACK" -> voice.processTask(task);
                    default -> throw new IllegalStateException("后台任务类型无效，请稍后重试。");
                }
                tasks.complete(task);
            } catch (RuntimeException exception) {
                org.slf4j.LoggerFactory.getLogger(AiMockTaskWorker.class).warn("simulation taskId={} sessionId={} operation={} code={} causeType={}",task.id(),task.resourceId(),task.taskType(),exception instanceof SimulationException e?e.code():"INTERNAL_ERROR",exception.getClass().getSimpleName());
                tasks.fail(task, exception);
            }
        }
    }
}
