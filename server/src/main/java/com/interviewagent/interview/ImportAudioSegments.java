package com.interviewagent.interview;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.*;

/** Disk-backed PCM; only a single <=120 second window is held in memory. */
final class ImportAudioSegments {
    record Segment(Path path, long offsetMs) {}
    static List<Segment> split(Path pcm, Path directory) throws Exception {
        List<Segment> result = new ArrayList<>();
        try (RandomAccessFile input = new RandomAccessFile(pcm.toFile(), "r")) {
            long offset = 0, total = input.length();
            while (offset < total) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("PCM splitting interrupted");
                byte[] window = new byte[(int)Math.min(120L * 32000, total - offset)];
                input.seek(offset); input.readFully(window);
                int end = window.length;
                // ponytail: search the last 10 seconds for 200 ms of quiet; overlap only when speech has no quiet boundary.
                boolean quiet = false;
                if (offset + end < total) {
                    for (int pos = end - 6400; pos >= end - 320000; pos -= 640) {
                        long energy = 0;
                        for (int i = pos; i < pos + 6400; i += 2) { int sample = (short)((window[i] & 255) | (window[i+1] << 8)); energy += (long)sample * sample; }
                        if (energy / 3200 < 200L * 200) { end = pos + 3200; quiet = true; break; }
                    }
                }
                Path file = directory.resolve(String.format("part-%04d.wav", result.size()));
                AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
                try (AudioInputStream stream = new AudioInputStream(new java.io.ByteArrayInputStream(window, 0, end), format, end / 2)) { AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile()); }
                if (Files.size(file) > 5_000_000) throw new IllegalStateException("PCM 分段超过 5,000,000 字节。");
                result.add(new Segment(file, offset / 32));
                offset += end;
                if (!quiet && offset < total) offset -= 64000; // real 2 second overlap; offsets remain absolute.
            }
        }
        return result;
    }
}
