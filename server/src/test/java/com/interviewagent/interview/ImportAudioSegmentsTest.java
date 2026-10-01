package com.interviewagent.interview;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ImportAudioSegmentsTest {
    @TempDir Path directory;
    @Test void multiSegmentPcmIsSortedSizeSafeAndOverlapOffsetsAreReal() throws Exception {
        Path pcm=directory.resolve("input.pcm");
        byte[] audio=new byte[250*32000];
        for(int i=0;i<audio.length;i+=2) { audio[i]=0; audio[i+1]=16; }
        Files.write(pcm,audio); var segments=ImportAudioSegments.split(pcm,directory);
        assertEquals(3,segments.size()); assertEquals(0,segments.get(0).offsetMs()); assertEquals(118000,segments.get(1).offsetMs()); assertEquals(236000,segments.get(2).offsetMs());
        for(var segment:segments) { assertTrue(Files.size(segment.path())<=5_000_000); assertEquals("RIFF",new String(Files.readAllBytes(segment.path()),0,4,java.nio.charset.StandardCharsets.US_ASCII)); }
        assertTrue(segments.get(0).path().compareTo(segments.get(1).path())<0);
    }
    @Test void silenceUsesContiguousBoundary() throws Exception {
        Path pcm=directory.resolve("quiet.pcm"); Files.write(pcm,new byte[125*32000]);
        var segments=ImportAudioSegments.split(pcm,directory);
        assertEquals(2,segments.size());
        assertEquals((Files.size(segments.getFirst().path())-44)/32,segments.get(1).offsetMs());
    }
    java.util.Set<Path> temporaryAudio() throws Exception {
        try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))) { return files.filter(p->p.getFileName().toString().startsWith("interview-audio-parts-")).collect(java.util.stream.Collectors.toSet()); }
    }
    @Test void ffmpegFailureAndInterruptionCleanTemporaryDirectory() throws Exception {
        Process check;
        try { check=new ProcessBuilder("ffmpeg","-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
        catch(java.io.IOException e) { org.junit.jupiter.api.Assumptions.abort("FFmpeg unavailable"); return; }
        org.junit.jupiter.api.Assumptions.assumeTrue(check.waitFor(10,java.util.concurrent.TimeUnit.SECONDS) && check.exitValue()==0);
        var before=temporaryAudio(); Path bad=directory.resolve("bad.wav"); Files.writeString(bad,"invalid audio");
        var service=new InterviewImportService(null,null,null,null,new com.fasterxml.jackson.databind.ObjectMapper(),"ffmpeg");
        assertThrows(IllegalStateException.class,()->service.segmentAudio(bad)); assertEquals(before,temporaryAudio());
        Thread.currentThread().interrupt();
        try { assertThrows(IllegalStateException.class,()->service.segmentAudio(bad)); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        assertEquals(before,temporaryAudio());
    }
}
