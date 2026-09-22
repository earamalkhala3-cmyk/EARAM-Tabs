import * as alphaTab from '@coderline/alphatab';
import './style.css';

const sheet = document.querySelector('#alphaTab');
const fileInput = document.querySelector('#fileInput');
const demoButton = document.querySelector('#demoButton');
const playButton = document.querySelector('#play');
const pauseButton = document.querySelector('#pause');
const stopButton = document.querySelector('#stop');

const titleEl = document.querySelector('#title');
const instrumentEl = document.querySelector('#instrument');
const tempoEl = document.querySelector('#tempo');
const statusEl = document.querySelector('#status');
const positionEl = document.querySelector('#position');
const progressBar = document.querySelector('#progressBar');

const api = new alphaTab.AlphaTabApi(sheet, {
  player: {
    enablePlayer: true,
    enableCursor: true
  },
  display: {
    // Guitar-Pro-like score pages: automatic systems, multiple measures per row,
    // and a continuous vertical stack of full-width score pages.
    layoutMode: alphaTab.LayoutMode.Page,
    barsPerRow: -1,
    barCount: -1,
    startBar: 1,
    staveProfile: alphaTab.StaveProfile.ScoreTab,
    padding: [48, 42, 48, 42],
    stretchForce: 1,
    scale: 1
  }
});

function setStatus(value) {
  statusEl.textContent = value;
}

function formatTime(milliseconds) {
  const total = Math.max(0, Math.floor(milliseconds / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = String(total % 60).padStart(2, '0');
  return minutes + ':' + seconds;
}

function updateButtons() {
  const ready = api.isReadyForPlayback;
  playButton.disabled = !ready;
  pauseButton.disabled = !ready;
  stopButton.disabled = !ready;
}

function updateScoreInfo(score) {
  titleEl.textContent = score.title || 'Untitled';
  const track = score.tracks?.[0];
  instrumentEl.textContent = track?.name || 'Unknown instrument';

  const tempo = Number(score.tempo);
  tempoEl.textContent = Number.isFinite(tempo) ? Math.round(tempo) + ' BPM' : '—';
}

api.scoreLoaded.on((score) => {
  updateScoreInfo(score);
  setStatus('Loaded');
});

api.renderStarted.on(() => setStatus('Rendering score pages…'));

api.postRenderFinished.on(() => {
  updateButtons();
  if (api.isReadyForPlayback) setStatus('Ready to play');
});

api.playerReady.on(() => {
  updateButtons();
  setStatus('Ready to play');
});

api.playerStateChanged.on((args) => {
  const state = String(args.state ?? '').toLowerCase();
  if (state.includes('playing')) setStatus('Playing');
  else if (state.includes('paused')) setStatus('Paused');
  else if (state.includes('stopped')) setStatus('Stopped');
});

api.playerPositionChanged.on((args) => {
  const current = Number(args.currentTime ?? 0);
  const end = Number(args.endTime ?? 0);
  positionEl.textContent = formatTime(current) + ' / ' + formatTime(end);
  progressBar.style.width = end > 0
    ? Math.min(100, current / end * 100) + '%'
    : '0%';
});

api.playerFinished.on(() => {
  setStatus('Finished');
  progressBar.style.width = '100%';
});

api.error.on((error) => {
  console.error(error);
  setStatus('Error');
});

playButton.addEventListener('click', () => api.play());
pauseButton.addEventListener('click', () => api.pause());
stopButton.addEventListener('click', () => api.stop());

fileInput.addEventListener('change', async (event) => {
  const file = event.target.files?.[0];
  if (!file) return;

  try {
    setStatus('Loading ' + file.name + '…');
    const buffer = await file.arrayBuffer();
    if (!api.load(buffer)) {
      throw new Error('Unsupported Guitar Pro format');
    }
  } catch (error) {
    console.error(error);
    setStatus('Could not open ' + file.name;
  } finally {
    fileInput.value = '';
  }
});

demoButton.addEventListener('click', () => {
  const demo = String.raw`\\title "EARAM Demo"
\\subtitle "Guitar Pro style score pages"
\\tempo 120
.
:4 0.6 2.5 3.5 2.4 | 0.6 2.5 3.5 5.4 | 5.4 3.5 2.4 0.5 | 0.6 2.5 3.5 2.4 |
:4 5.4 3.5 2.4 0.5 | 0.6 3.5 2.5 0.4 | 0.6 2.5 3.5 2.4 | 5.4 3.5 2.4 0.5 |
:4 0.6 2.5 3.5 2.4 | 0.6 2.5 3.5 5.4 | 5.4 3.5 2.4 0.5 | 0.6 3.5 2.5 0.4 |
:4 5.4 3.5 2.4 0.5 | 0.6 2.5 3.5 2.4 | 5.4 3.5 2.4 0.5 | 0.6 3.5 2.5 0.4 |
:4 0.6 2.5 3.5 2.4 | 5.4 3.5 2.4 0.5 | 0.6 2.5 3.5 5.4 | 5.4 3.5 2.4 0.5 |
`;
  setStatus('Loading multi-page demo…');
  api.tex(demo);
});

setStatus('Ready — open a Guitar Pro file or load the multi-page demo');
updateButtons();
