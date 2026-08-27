// Compressor - a walkthrough, one block at a time
//
// Evaluate each ( ... ) block in turn with the cursor inside it. This is
// an editor document, not a file to .load.

// ---- 1. a bass and a kick, and a compressor on the bass driven from its Ndef
(
s.boot;
)

(
~kick = Bus.audio(s, 2);
Ndef(\kick, {
	var env = EnvGen.ar(Env.perc(0.001, 0.25), Impulse.ar(2));
	var sig = SinOsc.ar(XLine.ar(180, 45, 0.08)) * env * 0.5;
	Out.ar(~kick, sig ! 2);				// onto the sidechain bus as well
	sig ! 2
}).play;
Ndef(\bass, {
	var sig = LFSaw.ar([55, 55.3]) * 0.2;
	sig = RLPF.ar(sig, 600, 0.3);
	Compressor.ar(sig, \comp, In.ar(~kick, 2))	// the second signal is the sidechain
}).play;
)

(
~comp = Compressor(Ndef(\bass), \comp);
~comp.gui;								// the curve, the history, the meters, the knobs
)

~comp.threshold = -24; ~comp.ratio = 4; ~comp.attack = 0.005; ~comp.release = 0.12;
~comp.knee = 6;
~comp.preset = \glue;
~comp.preset = \pump;

// ---- 2. ducking: listen to the kick instead of the bass, through an eq
~comp.sidechain = true;					// the signal handed to Compressor.ar
~comp.preset = 'sidechain duck';		// low ratio, quick, with a high shelf cut on the detector
~comp.eq.enabled = true;
~comp.eq.setBand(0, 100, 6, 0.8);		// the detector hears more of the kick's weight
~comp.audition = true;					// hear what the detector hears
~comp.audition = false;

// ---- 3. upward: quiet parts come up towards the threshold
~comp.sidechain = nil;
~comp.mode = \up;
~comp.threshold = -30; ~comp.ratio = 3; ~comp.range = 12;	// never more than 12 dB of lift
~comp.mode = \down; ~comp.range = 60;

// ---- 4. the same compressor with a node of its own, on the main out
(
~master = Compressor.onBus(0, 2);
~master.preset = \glue;
~master.autoGain = true;
~master.enabled = true;
~master.gui;
)

~master.sidechain = 16;					// or a BusManager name: \kick
~master.sidechain = nil;
~master.free;

// ---- 5. settings kept, and carried
~comp.saveAsPreset("demo bass");
~comp.copySettings;
FxChain.master.compressor.pasteSettings;	// onto the master chain's compressor

// ---- 6. tidy up
(
Compressor.removePreset("demo bass");
~comp.free;
Ndef(\bass).clear; Ndef(\kick).clear;
~kick.free;
)
