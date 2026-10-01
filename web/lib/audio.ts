const SAMPLE_RATE = 16_000;

export async function toWav(blob: Blob) {
  const context = new AudioContext();
  try {
    const source = await context.decodeAudioData(await blob.arrayBuffer());
    const offline = new OfflineAudioContext(1, Math.ceil(source.duration * SAMPLE_RATE), SAMPLE_RATE);
    const node = offline.createBufferSource();
    node.buffer = source;
    node.connect(offline.destination);
    node.start();
    const samples = (await offline.startRendering()).getChannelData(0);
    const bytes = new ArrayBuffer(44 + samples.length * 2);
    const view = new DataView(bytes);
    const write = (offset: number, value: string) => [...value].forEach((char, index) => view.setUint8(offset + index, char.charCodeAt(0)));
    write(0, "RIFF"); view.setUint32(4, 36 + samples.length * 2, true); write(8, "WAVEfmt ");
    view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
    view.setUint32(24, SAMPLE_RATE, true); view.setUint32(28, SAMPLE_RATE * 2, true);
    view.setUint16(32, 2, true); view.setUint16(34, 16, true); write(36, "data"); view.setUint32(40, samples.length * 2, true);
    for (let index = 0; index < samples.length; index++) {
      view.setInt16(44 + index * 2, Math.max(-1, Math.min(1, samples[index])) * 0x7fff, true);
    }
    return new Blob([bytes], { type: "audio/wav" });
  } finally { await context.close(); }
}
