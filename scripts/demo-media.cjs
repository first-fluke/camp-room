const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

function subtitleTime(milliseconds) {
  const value = Math.max(0, Math.round(milliseconds));
  const hours = Math.floor(value / 3600000);
  const minutes = Math.floor(value / 60000) % 60;
  const seconds = Math.floor(value / 1000) % 60;
  const millis = value % 1000;
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')},${String(millis).padStart(3, '0')}`;
}

function renderVideo(input, subtitles, output, fontDirectory) {
  const source = JSON.parse(execFileSync(process.env.FFPROBE_PATH || 'ffprobe', [
    '-v', 'error', '-show_entries', 'format=duration', '-of', 'json', input,
  ], { encoding: 'utf8' }));
  const sourceDurationSeconds = Number(source.format?.duration);
  if (!Number.isFinite(sourceDurationSeconds) || sourceDurationSeconds <= 0) {
    throw new Error('The source recording has no valid duration');
  }
  const playbackRate = Math.min(3, Math.max(1, Math.ceil(sourceDurationSeconds / 240)));
  const endHoldSeconds = 3;
  const outputEndMs = (sourceDurationSeconds / playbackRate + endHoldSeconds) * 1000;
  const escapeFilterPath = (value) => String(value).replaceAll('\\', '/').replaceAll(':', '\\:').replaceAll("'", "\\'");
  const timeline = JSON.parse(fs.readFileSync(path.join(path.dirname(subtitles), 'demo-timeline.json'), 'utf8'));
  // Always derive from the untouched source timeline, so rerenders never divide twice.
  const captions = timeline.captions.map((caption, index, all) => ({
    ...caption,
    start: caption.start / playbackRate,
    end: index === all.length - 1 ? Math.max(caption.end / playbackRate, outputEndMs) : caption.end / playbackRate,
  }));
  fs.writeFileSync(subtitles, captions.map((caption, index) =>
    `${index + 1}\n${subtitleTime(caption.start)} --> ${subtitleTime(caption.end)}\n${caption.title}\n${caption.text}\n`
  ).join('\n'));
  const assTime = (value) => subtitleTime(value).replace(/^(\d)0:/, '$1:').replace(',', '.').slice(0, -1);
  const escapeAss = (value) => value.replaceAll('\\', '\\\\').replaceAll('{', '(').replaceAll('}', ')').replaceAll('\n', ' ');
  const assPath = path.join(path.dirname(subtitles), 'Camp_Room_Demo.ko.ass');
  const ass = `[Script Info]\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\nWrapStyle: 0\nScaledBorderAndShadow: yes\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Main,Noto Sans CJK KR,30,&H00FFFFFF,&H00FFFFFF,&H00142720,&H00142720,0,0,0,0,100,100,0,0,1,0,0,2,40,40,20,1\nStyle: Speed,Noto Sans CJK KR,18,&H00FFFFFF,&H00FFFFFF,&H00142720,&H00142720,0,0,0,0,100,100,0,0,1,0,0,8,24,24,8,1\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n` + captions.map((caption) => `Dialogue: 0,${assTime(caption.start)},${assTime(caption.end)},Main,,0,0,0,,{\\fs23\\c&H9FC8B9&}${escapeAss(caption.title)}\\N{\\fs30\\c&HFFFFFF&}${escapeAss(caption.text)}`).join('\n')
    + (playbackRate > 1 ? `\nDialogue: 1,${assTime(0)},${assTime(outputEndMs)},Speed,,0,0,0,,실제 앱 화면 · ${playbackRate}배속` : '');
  fs.writeFileSync(assPath, ass);
  const fontOption = fontDirectory ? `:fontsdir='${escapeFilterPath(fontDirectory)}'` : '';
  // A small dedicated top margin keeps the speed disclosure off both the app and lower captions.
  const topMargin = playbackRate > 1 ? 36 : 0;
  const pictureHeight = 960 - topMargin;
  const filter = `setpts=(PTS-STARTPTS)/${playbackRate},scale=1920:${pictureHeight}:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:${topMargin}:color=0x142720,tpad=stop_mode=clone:stop_duration=${endHoldSeconds},subtitles='${escapeFilterPath(assPath)}'${fontOption},fps=30`;
  execFileSync(process.env.FFMPEG_PATH || 'ffmpeg', [
    '-hide_banner', '-loglevel', 'error', '-y', '-i', input,
    '-vf', filter, '-c:v', 'libx264', '-preset', 'fast', '-crf', '21',
    '-threads', '2', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-an', output,
  ], { stdio: 'inherit' });
  const metadata = JSON.parse(execFileSync(process.env.FFPROBE_PATH || 'ffprobe', [
    '-v', 'error', '-show_entries', 'format=duration,size:stream=width,height,codec_name', '-of', 'json', output,
  ], { encoding: 'utf8' }));
  return { ...metadata, sourceDurationSeconds, playbackRate };
}

module.exports = { renderVideo };
