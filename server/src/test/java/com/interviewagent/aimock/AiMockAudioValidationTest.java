package com.interviewagent.aimock;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AiMockAudioValidationTest {
    @Test
    void rejectsOversizedAudioAndExplainsStorageFailures() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> AiMockInterviewService.validateAudio(new byte[10 * 1024 * 1024 + 1]));
        assertEquals("录音超过 10 MiB，请缩短回答后重新录音。", error.getMessage());
        assertTrue(AiMockStorage.storageError(413).contains("过大"));
        assertTrue(AiMockStorage.storageError(503).contains("HTTP 503"));
    }
}
