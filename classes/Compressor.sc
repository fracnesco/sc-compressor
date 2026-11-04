// =====================================================================
// Compressor - a dynamics processor, as a UGen and as an object
//
// Feed forward, audio rate all the way through, with the shape of a
// modern plugin compressor - threshold, ratio, soft knee, attack,
// release, lookahead, range, mix, makeup - and two things a plugin
// usually keeps for a bigger brother:
//
//   mode        \down squashes what goes over the threshold, the usual
//               thing; \up lifts what falls under it - quiet parts come
//               up towards the threshold, and `range` says by how much
//               at most
//   sidechain   the detector can listen to another bus instead of the
//               input, and either way it listens through a five band
//               eq of its own - see ParaEQ - so a kick can duck a bass
//               without the hi-hat doing the ducking
//
// The DSP is one pseudo-UGen, so it runs wherever a UGen does:
//
//   Ndef(\bass, { var sig = ...; Compressor.ar(sig, \comp) });
//   Ndef(\bass, { |kick = 0| Compressor.ar(sig, \comp, In.ar(kick, 2)) });
//
// and an object drives it, wherever it runs:
//
//   c = Compressor(Ndef(\bass), \comp);
//   c.threshold = -24; c.ratio = 4; c.attack = 0.005;
//   c.gui;
//
//   c = Compressor.onBus(0, 2);            // the main out, in place
//   c.sidechain = \kick;                    // a BusManager name, or an index
//   c.eq.enabled = true; c.eq.setBand(0, 120, -12, 0.8);
//   c.enabled = true;
//
// or sits in a chain - see FxChain. Settings can be kept as presets and
// carried from one compressor to another, the way an eq curve can.
//
// How the gain is worked out, in order:
//
//   detector    the sidechain (or the input) through the eq, then either
//               its absolute value or a 5 ms rms; the channels linked by
//               `link` towards their loudest
//   computer    the level in dB against the threshold, through a soft
//               knee `knee` dB wide, at `ratio`; then no further than
//               `range`. Upward mode mirrors this below the threshold.
//   ballistics  the gain in dB, smoothed: `attack` seconds when it is
//               moving away from 0 dB, `release` coming back - as time
//               constants, so 10 ms means what it means on a desk
//   lookahead   the signal is held back by up to 20 ms so the gain gets
//               there first
//   output      gain plus makeup, mixed with the dry signal by `mix`
//
// The same computer is written in numbers below, for the window's
// transfer curve, so what you see is the curve you get.
// =====================================================================

Compressor : FxUnit {
	classvar store, <clipboard;
	classvar <>meterRate = 25;			// replies a second while a window is open
	classvar <>maxLookahead = 0.02;		// what the delay line is sized for
	classvar <>rmsWindow = 0.005;		// the rms detector's window, in seconds
	classvar <>fade = 0.02;
	classvar <modes, <detectors;
	classvar <paramNames, <specs;
	classvar idCounter = 0;
	classvar <>replyName = '/compressor';

	var <threshold, <ratio, <knee, <attack, <release, <lookahead, <range;
	var <mix, <gain, <autoGain, <mode, <detector, <link, <audition;
	var <eq;								// the sidechain eq: a ParaEQ with no node of its own
	var <sidechain, <sidechainName;			// what the detector listens to, if not the input
	var external = false;					// whether it is listening to that
	var <id, responder, meterOn = false;
	var <inLevel = 0, <outLevel = 0, <detLevel = 0;	// amplitudes, from the last reply
	var <reductionMin = 0, <reductionMax = 0;		// dB of gain change over the last reply
	var <lastReply = 0, lostName = false;

	*initClass {
		modes = [\down, \up];
		detectors = [\peak, \rms];
		paramNames = [\threshold, \ratio, \knee, \attack, \release, \lookahead,
			\range, \mix, \gain, \autoGain, \mode, \detector, \link];
		specs = IdentityDictionary[
			\threshold -> ControlSpec(-60, 0, \lin, 0.1, -20, "dB"),
			\ratio -> ControlSpec(1, 20, \exp, 0.01, 4, ":1"),
			\knee -> ControlSpec(0, 24, \lin, 0.1, 6, "dB"),
			\attack -> ControlSpec(0.0001, 0.5, \exp, 0, 0.01, "s"),
			\release -> ControlSpec(0.005, 4, \exp, 0, 0.1, "s"),
			\lookahead -> ControlSpec(0, 0.02, \lin, 0, 0, "s"),
			\range -> ControlSpec(0, 60, \lin, 0.1, 60, "dB"),
			\mix -> ControlSpec(0, 1, \lin, 0.01, 1, ""),
			\gain -> ControlSpec(-24, 24, \lin, 0.1, 0, "dB"),
			\link -> ControlSpec(0, 1, \lin, 0.01, 1, "")
		];
	}

	*controlName { ^\comp }

	// --------------------------------------------------------------- the DSP

	// The compressor, as a UGen. `name` is the control the settings arrive
	// on - fourteen numbers, see controls - and around it: `name`Eq,
	// `name`EqCut, `name`EqSlope and `name`EqOn for the sidechain eq, its two
	// cuts, their slopes and its switch, `name`On to crossfade the whole thing to dry, `name`Id
	// and `name`Meter for the replies a window reads. `sidechain` is a signal the detector can listen to instead
	// of the input; the object decides which with `external`.
	//
	//   Compressor.ar(sig, \comp);
	//   Compressor.ar(sig, \comp, In.ar(kickBus, 2));
	//
	*ar { |in, name = \comp, sidechain, bypass = true|
		var p, on, id, meter, sig, sc, n, det, peak, rms, linked, x;
		var kneeHalf, over, under, t, u, grDown, grUp, gr, grS, k;
		var delayed, wet, out, trig;
		name = name.asSymbol;
		p = NamedControl.kr(name, this.defaultControls);
		id = NamedControl.kr(this.prSub(name, "Id"), -1);
		meter = NamedControl.kr(this.prSub(name, "Meter"), 0);

		sig = in.asArray;
		n = sig.size;

		// what the detector listens to: the input, or the other signal,
		// blended by the external flag so a switch does not click
		sc = if (sidechain.isNil) { sig } {
			(sig * (1 - p[12])) + (sidechain.asArray.wrapExtend(n) * p[12])
		};
		sc = ParaEQ.ar(sc, this.prSub(name, "Eq"));

		// peak or rms, then linked across the channels towards the loudest
		peak = sc.abs;
		rms = RunningSum.rms(sc, (SampleRate.ir * rmsWindow).max(2));
		det = (peak * (1 - p[11])) + (rms * p[11]);
		linked = det.reduce(\max);
		det = det.collect { |d| (d * (1 - p[13])) + (linked * p[13]) };
		x = det.max(1e-6).ampdb;					// -120 dB is silence

		// the gain computer, in dB - the same arithmetic as *staticGain
		kneeHalf = p[2] * 0.5;
		over = x - p[0];
		t = (over + kneeHalf).clip(0, p[2]);
		grDown = ((1 / p[1]) - 1) * ((t.squared / (2 * p[2]).max(1e-3)) + (over - kneeHalf).max(0));
		grDown = grDown.max(p[6].neg);
		under = over.neg;
		u = (under + kneeHalf).clip(0, p[2]);
		grUp = (1 - (1 / p[1])) * ((u.squared / (2 * p[2]).max(1e-3)) + (under - kneeHalf).max(0));
		grUp = grUp.min(p[6]);
		gr = (grDown * (1 - p[9])) + (grUp * p[9]);

		// attack when the gain moves away from 0 dB, release coming back:
		// in both modes that is down for attack and up for release. Lag
		// times are 60 dB convergence; a time constant is that over 6.9.
		k = 6.9078;
		grS = LagUD.ar(gr, p[4] * k, p[3] * k);

		// the signal waits for the detector, up to maxLookahead
		delayed = DelayN.ar(sig, maxLookahead, Lag.kr(p[5], 0.05).clip(0, maxLookahead));
		wet = delayed * (grS + p[8]).dbamp;
		out = (delayed * (1 - p[7])) + (wet * p[7]);
		// hear what the detector hears instead, to aim the sidechain eq
		out = (out * (1 - p[10])) + (sc * p[10]);
		if (bypass) {
			on = Lag.kr(NamedControl.kr(this.prSub(name, "On"), 1), this.fade);
			out = delayed + ((out - delayed) * on);
		};

		// what a window reads: the detector's peak, the input's, the
		// output's, and the most and least gain change - only while asked
		trig = Impulse.kr(meterRate) * meter;
		SendReply.kr(trig, replyName, [
			Peak.ar(linked, trig),
			Peak.ar(sig, trig).reduce(\max),
			Peak.ar(out, trig).reduce(\max),
			RunningMin.ar(grS.reduce(\min), trig),
			RunningMax.ar(grS.reduce(\max), trig)
		], id);
		^out
	}

	*prSub { |name, suffix| ^(name.asString ++ suffix).asSymbol }

	// the flat array the node reads - see controls - with every default
	*defaultControls {
		^[-20, 4, 6, 0.01, 0.1, 0, 60, 1, 0, 0, 0, 0, 0, 1]
	}

	*defName { |numChannels| ^("compressor" ++ numChannels.asInteger).asSymbol }

	// In place on a bus. The external sidechain is read with InFeedback,
	// so a bus written later in the tree than this node still arrives -
	// a block late, which a sidechain does not mind.
	*synthDef { |numChannels|
		var n = numChannels.asInteger;
		^SynthDef(this.defName(n), { |bus = 0, scBus = 0, gate = 1, fade = 0.02|
			var sig = Compressor.ar(In.ar(bus, n), \comp, InFeedback.ar(scBus, n), bypass: false);
			var env = EnvGen.kr(Env.asr(fade, 1, fade), gate, doneAction: Done.freeSelf);
			XOut.ar(bus, env, sig);
		})
	}

	*guiClass { ^CompressorGUI }
	*panelClass { ^CompressorPanel }

	// ----------------------------------------------------------- the curve

	// The gain the computer applies to a level, in dB, before the
	// ballistics: negative in downward mode, positive in upward. The same
	// arithmetic the server runs, so the window's curve is the real one.
	*staticGain { |inDb, threshold = -20, ratio = 4, knee = 6, mode = \down, range = 60|
		var kneeHalf = knee * 0.5, over = inDb - threshold, t, u, under, g;
		if (mode == \up or: { mode == 1 }) {
			under = over.neg;
			u = (under + kneeHalf).clip(0, knee);
			g = (1 - (1 / ratio)) * ((u.squared / (2 * knee).max(1e-3)) + (under - kneeHalf).max(0));
			^g.min(range)
		};
		t = (over + kneeHalf).clip(0, knee);
		g = ((1 / ratio) - 1) * ((t.squared / (2 * knee).max(1e-3)) + (over - kneeHalf).max(0));
		^g.max(range.neg)
	}

	staticGain { |inDb| ^Compressor.staticGain(inDb, threshold, ratio, knee, mode, range) }

	// The makeup auto gain adds: half the gain change the curve makes 20 dB
	// past the threshold - above it downward, below it upward - which is
	// about what a signal peaking there loses. A guess a fader can finish.
	autoGainDb {
		if (autoGain.not) { ^0 };
		^if (mode == \up) {
			this.staticGain(threshold - 20).neg * 0.5
		} {
			this.staticGain(threshold + 20).neg * 0.5
		}
	}

	makeup { ^gain + this.autoGainDb }

	// ------------------------------------------------------------ settings

	prInitSettings {
		threshold = -20; ratio = 4; knee = 6; attack = 0.01; release = 0.1;
		lookahead = 0; range = 60; mix = 1; gain = 0; autoGain = false;
		mode = \down; detector = \peak; link = 1; audition = false;
		idCounter = idCounter + 1;
		id = idCounter;
		eq = ParaEQ(nil, Compressor.prSub(this.class.controlName, "Eq"));
		eq.label = "sidechain eq";
		eq.action = { |e, what| this.prPushEq; this.prChanged(\eq) };
	}

	// the control name is settable per instance, and the eq's follows it
	init { |argTarget, argControlName|
		super.init(argTarget, argControlName);
		eq = ParaEQ(nil, Compressor.prSub(controlName, "Eq"));
		eq.label = "sidechain eq";
		eq.action = { |e, what| this.prPushEq; this.prChanged(\eq) };
		this.prPushAll;
		^this
	}

	threshold_ { |db| threshold = specs[\threshold].constrain(db); this.prSet(\threshold) }
	ratio_ { |r| ratio = specs[\ratio].constrain(r); this.prSet(\ratio) }
	knee_ { |db| knee = specs[\knee].constrain(db); this.prSet(\knee) }
	attack_ { |secs| attack = specs[\attack].constrain(secs); this.prSet(\attack) }
	release_ { |secs| release = specs[\release].constrain(secs); this.prSet(\release) }
	lookahead_ { |secs| lookahead = specs[\lookahead].constrain(secs); this.prSet(\lookahead) }
	range_ { |db| range = specs[\range].constrain(db); this.prSet(\range) }
	mix_ { |amount| mix = specs[\mix].constrain(amount); this.prSet(\mix) }
	gain_ { |db| gain = specs[\gain].constrain(db); this.prSet(\gain) }
	link_ { |amount| link = specs[\link].constrain(amount); this.prSet(\link) }
	autoGain_ { |bool| autoGain = bool == true; this.prSet(\autoGain) }

	// \down or \up - see the banner
	mode_ { |sym|
		sym = if (sym.isNumber) { modes[sym.asInteger.clip(0, 1)] } { sym.asSymbol };
		if (modes.includes(sym).not) {
			("Compressor: mode is one of %".format(modes)).warn;
			^this
		};
		mode = sym;
		this.prSet(\mode);
	}

	// \peak or \rms
	detector_ { |sym|
		sym = if (sym.isNumber) { detectors[sym.asInteger.clip(0, 1)] } { sym.asSymbol };
		if (detectors.includes(sym).not) {
			("Compressor: detector is one of %".format(detectors)).warn;
			^this
		};
		detector = sym;
		this.prSet(\detector);
	}

	// hear what the detector hears - the sidechain through its eq -
	// instead of the output, to aim the eq. Never saved.
	audition_ { |bool| audition = bool == true; this.prSet(\audition) }

	// a setter by name, for a window with a knob per parameter
	set { |key, value| ^this.perform(key.asSymbol.asSetter, value) }

	get { |key| ^this.perform(key.asSymbol) }

	prSet { |what|
		this.prPush;
		this.prChanged(what);
	}

	// ----------------------------------------------------------- sidechain

	// What the detector listens to instead of the input: a bus index, a
	// Bus, a BusManager name - which is followed when the bus behind it
	// moves - or nil for the input again. In a graph of your own the
	// signal is whatever you handed *ar, and `true` just switches to it.
	sidechain_ { |val|
		var reg, index, wanted;
		if (val.isNil or: { val == false }) {
			sidechain = nil;
			sidechainName = nil;
			external = false;
			this.prUpdateRegistryLink;
			this.prPushSidechain;
			^this.prChanged(\sidechain)
		};
		if (val == true) {
			external = true;
			this.prPushSidechain;
			^this.prChanged(\sidechain)
		};
		wanted = if (val.isKindOf(Symbol) or: { val.isKindOf(String) }) { val.asSymbol };
		index = FxUnit.prBusIndex(val);
		if (index.isNil) { ^this };			// a name nothing knows changes nothing
		sidechain = index;
		sidechainName = wanted;
		lostName = false;
		external = true;
		this.prUpdateRegistryLink;
		this.prPushSidechain;
		^this.prChanged(\sidechain)
	}

	external { ^external }

	// only a compressor following a name listens to the registry
	prUpdateRegistryLink {
		\BusManager.asClass !? { |reg|
			if (sidechainName.notNil) { reg.addDependant(this) } { reg.removeDependant(this) };
		};
	}

	// the registry changed: a sidechain following a name goes with it
	update { |theChanged, what|
		var reg, index;
		if (what != \buses or: { sidechainName.isNil }) { ^this };
		reg = \BusManager.asClass;
		index = reg !? { reg.index(sidechainName) };
		if (index.isNil) {
			if (lostName.not) {
				lostName = true;
				("Compressor: the bus % has gone - staying on bus % until it comes back"
					.format(sidechainName.asCompileString, sidechain)).postln;
			};
			^this
		};
		lostName = false;
		if (index != sidechain) {
			sidechain = index.asInteger;
			this.prPushSidechain;
			this.prChanged(\sidechain);
		};
	}

	prPushSidechain {
		synth !? { |sy| sy.set(\scBus, sidechain ? 0) };
		this.prPush;
	}

	sidechainLabel {
		^sidechainName !? (_.asString) ?? { sidechain !? { "bus " ++ sidechain } } ? "input"
	}

	// ------------------------------------------------------------ the node

	// what the node is given - see *ar for the order
	controls {
		^[threshold, ratio, knee, attack, release, lookahead, range, mix, this.makeup,
			modes.indexOf(mode), audition.binaryValue, detectors.indexOf(detector),
			external.binaryValue, link]
	}

	prAcceptControls { |array|
		var a = array.asArray;
		if (a.size != 14) { ^false };
		threshold = a[0]; ratio = a[1]; knee = a[2]; attack = a[3]; release = a[4];
		lookahead = a[5]; range = a[6]; mix = a[7]; gain = a[8];
		mode = modes[a[9].round.asInteger.clip(0, 1)];
		audition = a[10] > 0.5;
		detector = detectors[a[11].round.asInteger.clip(0, 1)];
		external = a[12] > 0.5;
		link = a[13];
		^true
	}

	prNodeArgs {
		^[\scBus, sidechain ? 0, Compressor.prSub(\comp, "Id"), id,
			Compressor.prSub(\comp, "Meter"), meterOn.binaryValue]
	}

	prPushAll {
		super.prPushAll;
		this.prPushEq;
		this.prPushMeta;
	}

	prPush {
		super.prPush;
		if (synth.notNil and: { eq.notNil }) { this.prPushEq };
	}

	// The sidechain eq travels through this node, not through one of its
	// own: its bands, its two cuts, their slopes and whether it is in, all on this
	// node's controls.
	prPushEq {
		var eqName, cutName, slopeName, onName;
		if (eq.isNil) { ^this };
		eqName = eq.controlName;
		cutName = eq.cutName;
		slopeName = eq.slopeName;
		onName = eq.onName;
		synth !? { |sy| sy.set(eqName, eq.controls, cutName, eq.cutControls,
			slopeName, eq.slopeControls, onName, eq.enabled.binaryValue) };
		target !? { |t| t.set(eqName, eq.controls, cutName, eq.cutControls,
			slopeName, eq.slopeControls, onName, eq.enabled.binaryValue) };
	}

	prPushMeta {
		var idName = Compressor.prSub(controlName, "Id");
		var meterName = Compressor.prSub(controlName, "Meter");
		synth !? { |sy| sy.set(idName, id, meterName, meterOn.binaryValue) };
		target !? { |t| t.set(idName, id, meterName, meterOn.binaryValue) };
	}

	prMakeNode { |tgt, addAction|
		super.prMakeNode(tgt, addAction);
		this.prPushEq;
		this.prPushMeta;
		if (meterOn) { this.prListen };
		^synth
	}

	prJoinChain { |aChain|
		super.prJoinChain(aChain);
		if (meterOn) { this.prListen };
	}

	target_ { |node|
		super.target_(node);
		eq !? { this.prPushEq; this.prPushMeta };
		if (meterOn) { this.prListen };
	}

	// --------------------------------------------------------------- meters

	// Whether the node reports levels. A window turns this on while it is
	// open; from code, `meter = true` and read the levels below.
	meter_ { |bool|
		bool = bool == true;
		if (bool == meterOn) { ^this };
		meterOn = bool;
		this.prPushMeta;
		if (meterOn) { this.prListen } { this.prUnlisten };
	}

	meter { ^meterOn }

	// the most gain change over the last reply, in dB: the number a meter
	// shows - negative downward, positive upward
	reduction {
		^if (mode == \up) { reductionMax } { reductionMin }
	}

	isAlive { ^(Main.elapsedTime - lastReply) < 1.0 }

	prListen {
		var srv = server ? Server.default;
		this.prUnlisten;
		responder = OSCFunc({ |msg|
			detLevel = msg[3] ? 0;
			inLevel = msg[4] ? 0;
			outLevel = msg[5] ? 0;
			reductionMin = msg[6] ? 0;
			reductionMax = msg[7] ? 0;
			lastReply = Main.elapsedTime;
			this.changed(\levels);
		}, replyName, srv.addr, argTemplate: [nil, id]);
	}

	prUnlisten {
		responder !? (_.free);
		responder = nil;
	}

	free {
		this.prUnlisten;
		\BusManager.asClass !? (_.removeDependant(this));
		super.free;
	}

	// ------------------------------------------------------------- presets

	// what a preset holds: every setting, the sidechain eq included -
	// but not where the sidechain comes from, which belongs to the patch
	settings {
		var d = ();
		paramNames.do { |key| d[key] = this.perform(key) };
		d[\eq] = eq.asDict;
		^d
	}

	settings_ { |d|
		if (d.isNil) { ^this };
		paramNames.do { |key|
			d[key] !? { |v| this.perform(key.asSetter, v) };
		};
		d[\eq] !? { |v| eq.fromDict(v) };
		this.prPush;
		this.prChanged(\settings);
	}

	*presetStore {
		^store ?? {
			store = FxPresetStore(Compressor, this.prBuiltinPresets,
				Platform.userAppSupportDir +/+ "compressor-presets.scd");
			store
		}
	}

	*prPreset { |threshold = -20, ratio = 4, knee = 6, attack = 0.01, release = 0.1,
		lookahead = 0, range = 60, mix = 1, gain = 0, autoGain = false,
		mode = \down, detector = \peak, link = 1, eqOn = false, eqBands|
		^(threshold: threshold, ratio: ratio, knee: knee, attack: attack, release: release,
			lookahead: lookahead, range: range, mix: mix, gain: gain, autoGain: autoGain,
			mode: mode, detector: detector, link: link,
			eq: (enabled: eqOn, bands: eqBands ?? { ParaEQ.defaultBands }))
	}

	*prBuiltinPresets {
		^[
			\default, this.prPreset,
			\vocal, this.prPreset(-24, 3, 8, 0.005, 0.08, 0, 60, 1, 0, true),
			'drum bus', this.prPreset(-16, 4, 3, 0.02, 0.12, 0, 60, 1, 0, true),
			\glue, this.prPreset(-22, 2, 12, 0.03, 0.3, 0, 60, 1, 0, true, \down, \rms),
			\brickwall, this.prPreset(-6, 20, 0, 0.0005, 0.05, 0.005, 60, 1, 0, false),
			\pump, this.prPreset(-18, 6, 2, 0.001, 0.06, 0, 60, 1, 0, true),
			'sidechain duck', this.prPreset(-30, 8, 2, 0.003, 0.15, 0, 60, 1, 0, false,
				\down, \peak, 1, true,
				[[60, 0, 0.8], [250, 0, 1], [1000, 0, 1], [3500, 0, 1], [3000, -24, 0.7]]),
			'upward lift', this.prPreset(-30, 3, 10, 0.02, 0.2, 0, 12, 1, 0, false, \up, \rms),
			\parallel, this.prPreset(-30, 8, 4, 0.002, 0.1, 0, 60, 0.4, 6, false)
		]
	}

	*presets { ^this.presetStore.builtins }
	*userPresets { ^this.presetStore.userPresets }
	*presetNames { ^this.presetStore.names }
	*presetSettings { |name| ^this.presetStore.at(name) }
	*isUserPreset { |name| ^this.presetStore.isUser(name) }
	*presetFile { ^this.presetStore.file }
	*presetFile_ { |path| this.presetStore.file = path }
	*addPreset { |name, settings| ^this.presetStore.add(name, settings) }
	*removePreset { |name| ^this.presetStore.remove(name) }
	*clearUserPresets { this.presetStore.clear }

	presetName { ^Compressor.presetStore.detect(this.settings) }

	preset_ { |name|
		var d = Compressor.presetSettings(name);
		if (d.isNil) {
			("Compressor: no preset called %".format(name.asCompileString)).warn;
			^this
		};
		this.settings_(d);
	}

	saveAsPreset { |name| ^Compressor.addPreset(name, this.settings) }

	// --------------------------------------------------- copy and paste

	copySettings {
		clipboard = this.settings;
		Compressor.changed(\clipboard);
		^clipboard
	}

	*hasClipboard { ^clipboard.notNil }

	pasteSettings {
		if (clipboard.isNil) { ^this };
		this.settings_(clipboard);
		^this
	}

	copyFrom { |other|
		if (other.isNil) { ^this };
		this.settings_(other.settings);
		^this
	}

	// --------------------------------------------------------------- state

	asDict { ^(enabled: enabled, settings: this.settings) }

	fromDict { |d|
		if (d.isNil) { ^this };
		d[\settings] !? { |v| this.settings_(v) };
		d[\enabled] !? { |v| this.enabled_(v) };
	}

	printOn { |stream|
		stream << "Compressor(" << label << ", " << threshold << " dB, " << ratio << ":1, "
			<< mode << ", " << if (enabled) { "on" } { "off" } << ")"
	}
}
