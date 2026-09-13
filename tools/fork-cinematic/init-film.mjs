import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const brief = JSON.parse(fs.readFileSync(path.join(process.env.LOCALAPPDATA, 'FORK-Tools/fork-build-20260913/film-brief.json'), 'utf8').replace(/^\uFEFF/, ''));
if (brief.revision !== 'film-2' || brief.shots.length !== 23) throw Error('Expected accepted Film 2 with 23 shots');
const write = (name, value) => {
  const dest = path.join(root, name);
  fs.mkdirSync(path.dirname(dest), {recursive: true});
  fs.writeFileSync(dest, value, {flag: 'wx'});
};
const manifest = {
  schema: 'fork-film-2', status: 'INTERNAL EDIT TEMPLATE - NOT FOOTAGE',
  mediaLeaseDeadlineUtc: '2026-09-13T05:10:00Z',
  output: {width: 1920, height: 1080, fps: 30, frames: 2700},
  acceptance: {acceptedByMain: false, releaseCommit: null, packageSha256: null, worldSha256: null, mode: null, rewindMethod: null, cameraBindingSha256: null, actualCoverage: null, newDevelopmentCredit: null, developmentEvidence: null, fixtureApprovedByLucas: false},
  repository: 'https://github.com/aGamingGod1234/fork-minecraft',
  music: {decision: 'captured-game-ambient-only', reason: 'No external music needed; no generated music or voice.'},
  credits: [],
  shots: brief.shots.map((shot, i) => ({id: i + 1, startFrame: shot.startFrame, frames: shot.endFrameExclusive - shot.startFrame, title: shot.title, requiredPicture: shot.picture, soundDirection: shot.sound, evidence: null, reviewed: false, segments: []})),
  voice: brief.narration.map((line, i) => ({id: i + 1, at: line.start, windowEnd: i === 7 ? 68 : line.end, text: line.text, source: null, sha256: null, in: null, out: null, humanConfirmed: false, transcriptConfirmed: false})),
  checks: {sameModeThroughout: false, actualOutcomesMatchNarration: false, threeRestoresEvidence: null, locatorAttributionAccepted: false, sourcePrivacyReviewed: false, comparisonEqualSixRounds: false, comparisonFinalSixStill: false},
  playback: {localFullViewing: null, hostedFullViewing: null}
};
write('media/edit/film-edit.json', JSON.stringify(manifest, null, 2) + '\n');
write('media/edit/cards/title.txt', 'FORK\nChoose a future.\n');
write('media/edit/cards/end.txt', 'FORK\nChoose a future.\ngithub.com/aGamingGod1234/fork-minecraft\nRequires Minecraft Java Edition\nUnofficial project. Not approved by Mojang or Microsoft.\n');
write('media/edit/cards/foundation.txt', 'REUSED FOUNDATION\nAgent Arena\nBodies, lifecycle, console, camera paths\nand approved provider transport\n');
const script = ['# Human narration, Film 2', '', '120 words. Base wording is conditional on the recorded outcomes. Cinematic selects alternatives before the final voice take. The comparison voice must finish by 68 seconds; leave 68–70 seconds clear.', '', ...brief.narration.flatMap((line, i) => [`${i + 1}. **${line.start}–${i === 7 ? 68 : line.end}s:** ${line.text}`, '']), '## Alternatives', '', ...brief.alternatives.flatMap(line => [`**${line.when}**`, '', line.text, '']), 'Record separate lines with a quiet second on each side. No music in the voice recording. Cinematic selects, times, captions and mixes the final takes.', ''];
write('docs/fork-cinematic/narration.md', script.join('\n'));
write('docs/fork-cinematic/shot-ingest.md', ['# Film 2 source ingest', '', 'The 23 rows are fixed editorial slots, not claims that footage exists. First capture the complete A/rewind/B sequence, then scenic inserts. Record source filename, SHA256, actual seconds in/out, mode and acceptance evidence in film-edit.json. Never trim away a failed action and imply success.', '', '| # | Film time | Frames | Picture / evidence required |', '|---|---|---|---|', ...brief.shots.map((s, i) => `| ${i + 1} | ${s.start}–${s.end}s | ${s.startFrame}–${s.endFrameExclusive - 1} | ${s.picture.replaceAll('|', '/')} |`), '', 'Shot 19: use a real comparison for 10 seconds. Its final 6 seconds must be motionless; a freeze from that same accepted comparison is allowed. No narration after 68 seconds until shot 20. Shot 21 contains three 2-second clips unless the accepted facade effect replaces the entire 6 seconds. Shot 22 contains two separate 3-second cards with actual new development evidence and the reused foundation.', ''].join('\n'));
console.log('Created 23-shot template, 120-word script with alternatives, and three typographic cards. No film rendered.');
