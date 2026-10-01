export default function WavingInterviewer() {
  return (
    <picture className="home-waving-robot">
      <source media="(prefers-reduced-motion: reduce)" srcSet="/images/home-robot-still.webp?v=2" />
      <img src="/images/home-robot-loop.webp?v=2" width="320" height="390" alt="" />
    </picture>
  );
}
