# Compressor

**A feed forward compressor at audio rate — downward or upward, with a sidechain
and an eq on the sidechain — that runs anywhere a UGen does, driven by an object
with a window.** A SuperCollider extension.

Write `Compressor.ar` into a graph of your own and drive it from an object: the
transfer curve with the signal riding on it, the last eight seconds of level and
gain change, meters, a knob per parameter, presets, a clipboard. The same object
can own a synth on a bus instead, or sit in the master chain.

```supercollider
s.boot;
Ndef(\bass, { var sig = LFSaw.ar([55, 55.3]) * 0.2; Compressor.ar(sig, \comp) }).play;

c = Compressor(Ndef(\bass), \comp);   // drives that Ndef's \comp control
c.gui;
c.threshold = -24; c.ratio = 4; c.attack = 0.005; c.release = 0.12;
c.preset = \glue;
```

It is built on [ParaEQ](../sc%20EQ) — it is one of that repository's effects,
its sidechain eq is a `ParaEQ`, and its window is made of that repository's
pieces — so ParaEQ has to be installed too; the installer links it for you when
the two folders sit side by side. [BusManager](../sc%20bus%20manager) is optional
and lets the sidechain be a bus by name.

Written for SuperCollider 3.13 on macOS. Nothing in it is platform specific
apart from the symlink in `install.scd`, which Linux shares and Windows does not.

## Install

### As a quark

```supercollider
Quarks.install("https://github.com/fracnesco/sc-compressor.git");
thisProcess.recompile;
```

The manifest names `sc-eq` as a dependency, so `Quarks` fetches that first. The
quark goes by the name of the repository, `sc-compressor`; the class inside it
is `Compressor`.

### From a clone

```sh
git clone https://github.com/fracnesco/sc-eq.git
git clone https://github.com/fracnesco/sc-compressor.git
```

Open `install.scd` and evaluate it, then recompile the class library. It
symlinks `../sc EQ` as `ParaEQ` if that is not installed yet, then this folder
as `Compressor`, and tells you what it did.

On Windows there is no `ln -s`, so use the quark, or copy both folders into the
extensions folder by hand — `Platform.userExtensionDir.openOS` opens it.

Install it one way or the other, **not both**: two copies on the class path is a
duplicate class error at compile time.

### Check that it took

```supercollider
Compressor.presetNames;          // -> [default, vocal, drum bus, glue, brickwall, ...]
Compressor.onBus(0, 2).enabled_(true).gui;
```

Select `Compressor` and press cmd/ctrl + d for the help file, and see
[examples/compressor-demo.scd](examples/compressor-demo.scd) for a walkthrough
you can evaluate a block at a time.

## What it does, in order

```
sidechain (or the input) -> eq -> detector -> gain computer -> ballistics -> gain
input -> lookahead delay ------------------------------------------------> x gain -> mix -> out
```

- **detector** — the peak of the wave, or a 5 ms rms; the channels linked
  towards their loudest by `link`.
- **gain computer** — the level in dB against `threshold`, through a soft
  `knee`, at `ratio`; never further than `range`. In **downward** mode what goes
  over the threshold is squashed; in **upward** mode what falls under it is
  lifted towards the threshold — quiet parts come up — and `range` is what keeps
  silence from being lifted with them.
- **ballistics** — the gain in dB is smoothed, `attack` seconds when it moves
  away from 0 dB and `release` coming back, as time constants: 10 ms means what
  it means on a desk.
- **lookahead** — the signal waits up to 20 ms so the gain gets there first.
- **output** — gain plus makeup (`gain`, and `autoGain` adds back half of what
  the curve takes off 20 dB past the threshold), mixed with the dry signal by
  `mix` — less is parallel compression.

Everything is audio rate. No `Compander`, no control rate envelope: the gain is
a signal, computed sample by sample from the same arithmetic the window draws.

## In a graph of your own

`Compressor.ar(in, name, sidechain)` reads its settings off a control called
`name` and, around it, `name` + `On` (a crossfade to dry), `name` + `Eq`,
`EqCut` and `EqOn` (the sidechain eq's bands, its two cuts and its switch),
`name` + `Id` and `Meter` (for the replies a window reads). The third argument is a signal the detector can listen
to instead of the input:

```supercollider
Ndef(\bass, { |kick = 20| var sig = ...; Compressor.ar(sig, \comp, In.ar(kick, 2)) });

c = Compressor(Ndef(\bass), \comp);
c.sidechain = true;              // listen to the signal handed to *ar
c.sidechain = nil;               // the input again
```

The object drives whatever node it was given — an Ndef, a Synth, a Group. An
Ndef keeps what it was set in its nodeMap, so a rebuilt Ndef comes back with the
settings, and a second object made for it adopts them.

## On a bus, and in the chain

```supercollider
c = Compressor.onBus(0, 2);      // in place on the main out
c.enabled = true;
c.sidechain = 16;                // a bus index, a Bus, or a BusManager name
c.sidechain = \kick;             // followed when the bus behind the name moves
c.free;

FxChain.master.compressor;       // the one after the master eq - see ParaEQ
```

An external sidechain is read with `InFeedback`, so a bus written later in the
tree than the compressor still arrives — a block late, which a sidechain does
not mind.

## The sidechain eq

Every compressor carries a `ParaEQ` in front of its detector — five bands and
the two cuts, like any other. The signal itself is untouched; only what the
detector hears is shaped, so a kick can duck a bass without the hi-hat doing the
ducking:

```supercollider
c.eq.enabled = true;
c.eq.setBand(4, 3000, -24, 0.7);     // the detector stops hearing the top
c.eq.highPassOn = true;              // and nothing under 80 Hz sets it off
c.eq.highPassFreq = 80;
c.eq.preset = 'rumble cut';
c.audition = true;                   // hear what the detector hears, to aim it
c.audition = false;
```

It is the same eq as everywhere else: the same presets, the same clipboard, the
same panel — **show eq** on the sidechain row unfolds it.

## Presets, and a clipboard

```supercollider
Compressor.presetNames;
c.preset = \vocal;
c.presetName;                    // -> \vocal, or nil once you move a knob
c.saveAsPreset("my bass");       // joins every compressor's menu, in this session and the next
Compressor.removePreset("my bass");
Compressor.presetFile;           // compressor-presets.scd in the support folder

c.copySettings;
other.pasteSettings;             // everything, the sidechain eq included - not whether it is on
```

Where the sidechain comes from is not part of a preset: that belongs to the
patch, not to the sound.

## The window

`c.gui`, or the same panel inside the master chain.

```
 [x] comp on  preset [glue v] [name  ][save][delete] [copy][paste]     mode [down v] detector [rms v]
 +-----------------+ +-------------------------------+ +----+
 | transfer curve  | | in / out / gain, 8 seconds    | |bars|
 +-----------------+ +-------------------------------+ +----+
  thresh  ratio  knee  attack release  look  range  mix  gain [x] auto  link
  (o)     (o)    (o)   (o)    (o)      (o)   (o)    (o)  (o)            (o)
 sidechain [input v] bus [ 0 ][name v] [audition]           [x] sc eq [show eq]
 hover a control and this bar says what it does
```

- On the **curve**, drag for the threshold, cmd + drag for the ratio, the wheel
  for the knee. The dot is where the detector is now; the bar under it is how far
  the gain is from 0 dB at this moment.
- The **history** is the input filled, the output as a line, and the gain change
  hanging from the top on a scale that grows to fit. Click to clear.
- The **bars** are input, output and gain change, with a hold mark that falls back.
- The line along the bottom says what the pointer is on.

## Server restarts

A compressor with a node of its own, and the chain it sits in, come back with
the tree after cmd + period and after a boot; one driving an Ndef leaves that to
the Ndef. The meter replies only flow while a window is open.

## Files

| file | what it holds |
|------|---------------|
| [classes/Compressor.sc](classes/Compressor.sc) | the DSP as a UGen, the object, the static curve in numbers, presets, clipboard, sidechain, meters |
| [classes/CompressorView.sc](classes/CompressorView.sc) | `CompressorCurveView`, `CompressorHistoryView`, `CompressorMeterView` |
| [classes/CompressorGUI.sc](classes/CompressorGUI.sc) | `CompressorPanel`, the controls as one view, and `CompressorGUI`, the window |
| [HelpSource/Classes/](HelpSource/Classes/) | the help files, cmd/ctrl + d on a class name |
| [examples/compressor-demo.scd](examples/compressor-demo.scd) | a walkthrough you can evaluate a block at a time |
| [install.scd](install.scd) | symlinks ParaEQ and this repository into the extensions folder |
| [sc-compressor.quark](sc-compressor.quark) | the quark manifest, with `sc-eq` as its dependency |

## Issues

Bug reports and questions are welcome in
[Issues](https://github.com/fracnesco/sc-compressor/issues). If something
misbehaves, the post window usually has the half of the story I need.

## License

MIT — see [LICENSE](LICENSE).
