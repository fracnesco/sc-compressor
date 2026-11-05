// =====================================================================
// CompressorPanel - the compressor's controls as one view; CompressorGUI - as a window
//
//   [x] on  preset [vocal v] [name   ][save][delete] [copy][paste]   mode [down v] detector [peak v]
//   +------------------+ +------------------------------+ +----+
//   |  transfer curve  | |  in / out / gain, 8 seconds  | |bars|
//   +------------------+ +------------------------------+ +----+
//    thr    ratio  knee   attack release  look   range   mix   gain  [x] auto   link
//    (o)    (o)    (o)    (o)    (o)      (o)    (o)     (o)   (o)              (o)
//   [-20.0] [4.00] [6.0] [10.0] [100.0]  [0.0]  [60.0]  [100] [0.0]           [100]
//   sidechain [input v] bus [ 0 ][name v] [audition]   [x] sc eq  [show eq]
//   (the sidechain eq's panel, folded away until asked for)
//
// The panel is what every window that shows a compressor is built from:
// its own window, the master chain in ServerPanel. It follows one
// Compressor and can be pointed at another with `panel.comp = other`.
//
//   panel = CompressorPanel(comp);
//   window.layout = VLayout(panel.view, hints.view);
//   panel.hints = hints;
//   panel.running = true;         // meters and the sidechain eq's analysis
//   panel.remove;                 // in window.onClose
// =====================================================================

CompressorPanel {
	var <comp, <view, <curveView, <historyView, <meterView, <eqPanel;
	var onBox, presetMenu, nameField, saveBtn, delBtn, copyBtn, pasteBtn;
	var modeMenu, detMenu, knobs, boxes, autoBox;
	var scMenu, scBox, scNameMenu, auditionBtn, eqOnBox, eqShowBtn, eqView;
	var ctrl, classCtrl, bmCtrl, hints, running = false, busNames;
	var <>eqSubject;

	*new { |comp, parent| ^super.new.init(comp, parent) }

	init { |argComp, parent|
		busNames = [];
		knobs = IdentityDictionary.new;
		boxes = IdentityDictionary.new;

		curveView = CompressorCurveView(nil);
		historyView = CompressorHistoryView(nil);
		meterView = CompressorMeterView(nil);
		eqPanel = ParaEQPanel(nil, subject: "the sidechain", showOnBox: false);
		eqView = View().layout_(VLayout(eqPanel.view).margins_(0));
		eqView.visible = false;

		view = View(parent).layout_(
			VLayout(
				this.prTopRow,
				[HLayout(
					[curveView.view, stretch: 2],
					[historyView.view, stretch: 3],
					meterView.view
				).spacing_(4), \stretch, 1],
				this.prKnobRow,
				this.prSidechainRow,
				eqView
			).margins_(0).spacing_(6));

		classCtrl = SimpleController(Compressor)
			.put(\presets, { { this.refresh }.defer })
			.put(\clipboard, { { this.refresh }.defer });
		\BusManager.asClass !? { |bm|
			bmCtrl = SimpleController(bm).put(\buses, { { this.prRefreshBusMenu }.defer });
		};
		this.comp_(argComp);
		^this
	}

	comp_ { |c|
		ctrl !? (_.remove);
		ctrl = nil;
		comp = c;
		curveView.comp_(comp);
		historyView.comp_(comp);
		meterView.comp_(comp);
		eqPanel.eq_(comp !? (_.eq));
		comp !? {
			ctrl = SimpleController(comp)
				.put(\levels, { { curveView.refresh; meterView.refresh }.defer })
				.put(\enabled, { { this.refresh }.defer })
				.put(\settings, { { this.refresh }.defer })
				.put(\sidechain, { { this.refresh }.defer })
				.put(\eq, { { this.prRefreshEqRow }.defer })
				.put(\freed, { { this.comp_(nil) }.defer });
			Compressor.paramNames.do { |key|
				ctrl.put(key, { { this.refresh }.defer });
			};
			ctrl.put(\audition, { { this.refresh }.defer });
			if (running) { comp.meter = true };
		};
		this.refresh;
	}

	// the meters run - and the sidechain eq's analysis, when shown - while
	// the panel is on screen
	running_ { |bool|
		running = bool;
		comp !? { comp.meter = bool };
		eqPanel.running = bool and: { eqView.visible };
	}

	running { ^running }

	remove {
		ctrl !? (_.remove);
		ctrl = nil;
		classCtrl !? (_.remove);
		classCtrl = nil;
		bmCtrl !? (_.remove);
		bmCtrl = nil;
		historyView.remove;
		eqPanel.remove;
		comp !? { comp.meter = false };
	}

	// ---------------------------------------------------------------- hints

	hints_ { |bar|
		hints = bar;
		if (bar.isNil) { ^this };
		bar.attach(onBox, "switch the compressor on - off, it costs nothing");
		bar.attach(presetMenu,
			"ready made settings, then any you have saved - reads (custom) once you move something");
		bar.attach(nameField, "a name to keep the current settings under");
		bar.attach(saveBtn, "keep these settings under that name - in this session and the next");
		bar.attach(delBtn, "drop the preset now selected, if it is one of yours");
		bar.attach(copyBtn, "put these settings aside - they can be pasted onto any compressor");
		bar.attach(pasteBtn, "take the copied settings onto this compressor - not whether it is on");
		bar.attach(modeMenu,
			"down squashes what goes over the threshold; up lifts what falls under it, by range at most");
		bar.attach(detMenu,
			"what the detector measures: the peak of the wave, or a 5 ms rms - rms is gentler on transients");
		bar.attach(curveView.view,
			"input along, output up - drag for the threshold, cmd-drag for the ratio, the wheel for the knee;"
			" the dot is the detector now");
		bar.attach(historyView.view,
			"the last 8 seconds: input filled, output as a line, the gain change hanging from the top -"
			" click to clear");
		bar.attach(meterView.view, "input, output, and the gain change right now - click to drop the hold marks");
		this.prKnobHints(bar);
		bar.attach(autoBox,
			"add back half of what the curve takes off 20 dB past the threshold - a start a fader can finish");
		bar.attach(scMenu, "what the detector listens to: the input itself, or another bus");
		bar.attach(scBox, "the bus the detector listens to, by index - typing one stops following a name");
		scNameMenu !? { bar.attach(scNameMenu,
			"the bus the detector listens to, by BusManager name - followed when the bus moves") };
		bar.attach(auditionBtn,
			"hear what the detector hears - the sidechain through its eq - instead of the output");
		bar.attach(eqOnBox, "put the five band eq in front of the detector - the signal itself is untouched");
		bar.attach(eqShowBtn, "show or fold away the sidechain eq's curve and handles");
		eqPanel.hints = bar;
		^this
	}

	hints { ^hints }

	prKnobHints { |bar|
		var texts = IdentityDictionary[
			\threshold -> "the level the curve bends at, in dB",
			\ratio -> "how much of what goes past the threshold gets through: 4:1 lets a quarter of it through",
			\knee -> "how many dB around the threshold the curve bends over, rather than at a corner",
			\attack -> "how quickly the gain moves away from 0 dB when the signal gets louder - a time constant",
			\release -> "how quickly the gain comes back towards 0 dB when the signal gets quieter",
			\lookahead -> "how long the signal waits for the detector, so the gain gets there first - up to 20 ms of latency",
			\range -> "the most the gain is ever moved, in dB - what keeps upward mode from lifting silence",
			\mix -> "how much of the compressed signal goes out - less is parallel compression",
			\gain -> "makeup, in dB, after the gain change",
			\link -> "how far the channels share one gain: 1 follows the loudest, 0 lets each go its own way"
		];
		texts.keysValuesDo { |key, str|
			bar.attach([knobs[key], boxes[key]], str);
		};
	}

	// ----------------------------------------------------------------- rows

	prTopRow {
		onBox = CheckBox().string_("comp on")
			.action_({ |v| comp !? (_.enabled_(v.value)) });
		presetMenu = PopUpMenu().fixedWidth_(110)
			.items_(Compressor.presetNames.collect(_.asString) ++ ["(custom)"])
			.action_({ |v|
				var names = Compressor.presetNames;
				if (v.value < names.size) { comp !? (_.preset_(names[v.value])) };
			});
		nameField = TextField().fixedWidth_(92).string_("");
		nameField.action = { this.prSavePreset };
		saveBtn = FxPalette.button("save", 42, { this.prSavePreset });
		delBtn = FxPalette.button("delete", 50, {
			var name = comp !? (_.presetName);
			name !? { Compressor.removePreset(name) };
		});
		copyBtn = FxPalette.button("copy", 44, { comp !? (_.copySettings) });
		pasteBtn = FxPalette.button("paste", 46, { comp !? (_.pasteSettings) });
		modeMenu = PopUpMenu().fixedWidth_(64)
			.items_(Compressor.modes.collect(_.asString))
			.action_({ |v| comp !? (_.mode_(Compressor.modes[v.value ? 0])) });
		detMenu = PopUpMenu().fixedWidth_(60)
			.items_(Compressor.detectors.collect(_.asString))
			.action_({ |v| comp !? (_.detector_(Compressor.detectors[v.value ? 0])) });

		^HLayout(
			onBox,
			FxPalette.label("preset", 40), presetMenu,
			nameField, saveBtn, delBtn,
			copyBtn, pasteBtn,
			nil,
			FxPalette.label("mode", 30), modeMenu,
			FxPalette.label("detector", 44), detMenu
		).spacing_(6)
	}

	prSavePreset {
		var typed = nameField.string.stripWhiteSpace;
		var name = if (typed.isEmpty) { comp !? (_.presetName) } { typed };
		if (comp.isNil) { ^this };
		if (name.isNil) {
			"Compressor: type a name to keep these settings under".warn;
			^this
		};
		if (comp.saveAsPreset(name).notNil) { nameField.string = "" };
	}

	// one knob and one box per parameter, the box in the unit the reader
	// thinks in - milliseconds for the times, percent for mix and link
	prKnobRow {
		var items = [];
		var labels = IdentityDictionary[
			\threshold -> "thresh", \ratio -> "ratio", \knee -> "knee",
			\attack -> "attack", \release -> "release", \lookahead -> "look",
			\range -> "range", \mix -> "mix", \gain -> "gain", \link -> "link"];
		[\threshold, \ratio, \knee, \attack, \release, \lookahead, \range, \mix, \gain, \link].do { |key|
			var spec = Compressor.specs[key];
			var knob = Knob().fixedSize_(34@34).mode_(\vert)
				.action_({ |k| comp !? (_.set(key, spec.map(k.value))) });
			var box = NumberBox().fixedWidth_(50).align_(\center)
				.decimals_(this.prDecimals(key))
				.action_({ |b| comp !? (_.set(key, this.prFromBox(key, b.value))) });
			knobs[key] = knob;
			boxes[key] = box;
			items = items.add(
				VLayout(
					FxPalette.label(labels[key], nil, \center),
					[knob, align: \center],
					box
				).spacing_(1).margins_(0)
			);
			if (key == \gain) {
				autoBox = CheckBox().string_("auto")
					.action_({ |v| comp !? (_.autoGain_(v.value)) });
				items = items.add(VLayout(nil, autoBox, nil).spacing_(1).margins_(0));
			};
		};
		^HLayout(*(items ++ [nil])).spacing_(6)
	}

	prDecimals { |key|
		^switch (key,
			\ratio, 2,
			\attack, 1, \release, 1, \lookahead, 1,
			\mix, 0, \link, 0,
			1)
	}

	// the box shows ms and percent; the compressor thinks in seconds and 0..1
	prToBox { |key, v|
		^switch (key,
			\attack, v * 1000, \release, v * 1000, \lookahead, v * 1000,
			\mix, v * 100, \link, v * 100,
			v)
	}

	prFromBox { |key, v|
		^switch (key,
			\attack, v / 1000, \release, v / 1000, \lookahead, v / 1000,
			\mix, v / 100, \link, v / 100,
			v)
	}

	prSidechainRow {
		var items;
		scMenu = PopUpMenu().fixedWidth_(76).items_(["input", "external"])
			.action_({ |v|
				comp !? { |c|
					if (v.value == 0) { c.sidechain = nil } {
						c.sidechain = c.sidechain ? true;
						if (c.sidechain.isNil) { c.sidechain = scBox.value.asInteger };
					};
				};
			});
		scBox = NumberBox().fixedWidth_(46).decimals_(0).clipLo_(0).clipHi_(4095)
			.action_({ |v| comp !? (_.sidechain_(v.value.asInteger)) });
		// index 0 is "-", the way of saying this is a bare index
		scNameMenu = \BusManager.asClass !? {
			PopUpMenu().fixedWidth_(100)
				.action_({ |v|
					var i = v.value ? 0;
					if (i > 0) { comp !? (_.sidechain_(busNames[i - 1])) };
					this.prRefreshBusMenu;
				})
		};
		auditionBtn = Button().fixedWidth_(64).canFocus_(false)
			.states_([["audition", FxPalette.text, FxPalette.panel],
				["listening", Color.black, FxPalette.accent]])
			.action_({ |v| comp !? (_.audition_(v.value == 1)) });
		eqOnBox = CheckBox().string_("sc eq")
			.action_({ |v| comp !? { |c| c.eq.enabled_(v.value) } });
		eqShowBtn = Button().fixedWidth_(70).canFocus_(false)
			.states_([["show eq", FxPalette.text, FxPalette.panel],
				["hide eq", FxPalette.bg, FxPalette.sel]])
			.action_({ |v| this.prShowEq(v.value == 1) });

		items = [FxPalette.label("sidechain", 50), scMenu,
			FxPalette.label("bus", 20), scBox];
		scNameMenu !? { items = items.add(scNameMenu) };
		items = items ++ [auditionBtn, nil, eqOnBox, eqShowBtn];
		^HLayout(*items).spacing_(6)
	}

	prShowEq { |bool|
		eqView.visible = bool;
		eqShowBtn.value = bool.binaryValue;
		eqPanel.running = bool and: { running };
	}

	showEq { |bool = true| this.prShowEq(bool) }

	// -------------------------------------------------------------- refresh

	refresh {
		var c = comp, names, items, pre;
		if (view.isNil or: { view.isClosed }) { ^this };
		names = Compressor.presetNames;
		items = names.collect(_.asString) ++ ["(custom)"];
		if (presetMenu.items != items) { presetMenu.items = items };
		pasteBtn.enabled = Compressor.hasClipboard;
		if (c.isNil) {
			onBox.value = false;
			delBtn.enabled = false;
			^this
		};
		pre = c.presetName;
		onBox.value = c.enabled;
		presetMenu.value = (pre !? { names.indexOf(pre) }) ? names.size;
		delBtn.enabled = pre.notNil and: { Compressor.isUserPreset(pre) };
		modeMenu.value = Compressor.modes.indexOf(c.mode) ? 0;
		detMenu.value = Compressor.detectors.indexOf(c.detector) ? 0;
		knobs.keysValuesDo { |key, knob|
			var spec = Compressor.specs[key];
			var v = c.perform(key);
			knob.value = spec.unmap(v);
			boxes[key].value = this.prToBox(key, v);
		};
		autoBox.value = c.autoGain;
		boxes[\gain].toolTip = if (c.autoGain) {
			"auto adds % dB".format(c.autoGainDb.round(0.1))
		} { "makeup gain, in dB" };
		scMenu.value = c.external.binaryValue;
		scBox.value = c.sidechain ? 0;
		scBox.enabled = c.external;
		auditionBtn.value = c.audition.binaryValue;
		this.prRefreshBusMenu;
		this.prRefreshEqRow;
		curveView.refresh;
		meterView.refresh;
	}

	prRefreshEqRow {
		comp !? { |c| eqOnBox.value = c.eq.enabled };
	}

	prRefreshBusMenu {
		var bm = \BusManager.asClass, pick;
		if (scNameMenu.isNil or: { bm.isNil } or: { comp.isNil }) { ^this };
		busNames = bm.entries.select(_.isAudio).collect(_.name);
		scNameMenu.items = ["-"] ++ busNames.collect(_.asString);
		pick = if (comp.sidechainName.notNil and: { busNames.includes(comp.sidechainName) }) {
			comp.sidechainName
		} {
			comp.sidechain !? { |i| busNames.detect { |n| bm.index(n) == i } }
		};
		scNameMenu.value = pick !? { busNames.indexOf(pick) + 1 } ? 0;
		scNameMenu.enabled = comp.external;
	}
}


// =====================================================================
// CompressorGUI - one compressor in a window of its own
// =====================================================================

CompressorGUI {
	var <comp, <window, <panel, <hints;
	var statusText, ctrl;

	*new { |comp, x, y| ^super.new.init(comp, x, y) }

	init { |argComp, x, y|
		comp = argComp;
		window = Window("compressor · " ++ comp.label, Rect(x ? 100, y ? 100, 820, 420));
		FxPalette.darken(window);

		panel = CompressorPanel(comp);
		hints = FxHintBar();
		statusText = StaticText().font_(FxPalette.smallFont).stringColor_(FxPalette.dim);

		window.layout = VLayout(
			[panel.view, stretch: 1],
			HLayout(statusText, nil, hints.toggle).spacing_(4),
			hints.view
		).margins_(8).spacing_(6);

		hints.watch(window);
		panel.hints = hints;
		hints.attach(statusText, "where this compressor is running, and what it listens to");

		ctrl = SimpleController(comp)
			.put(\enabled, { { this.prRefreshStatus }.defer })
			.put(\node, { { this.prRefreshStatus }.defer })
			.put(\sidechain, { { this.prRefreshStatus }.defer })
			.put(\label, { { this.prRefreshStatus }.defer })
			.put(\freed, { { this.close }.defer });

		window.onClose = {
			ctrl.remove;
			panel.remove;
			comp.prGuiClosed;
		};
		panel.running = true;
		this.prRefreshStatus;
		window.front;
		^this
	}

	front { window !? { if (window.isClosed.not) { window.front } } }

	close { window !? { if (window.isClosed.not) { window.close } } }

	prRefreshStatus {
		var parts = [];
		if (window.isClosed) { ^this };
		window.name = "compressor · " ++ comp.label;
		case
		{ comp.chain.notNil } { parts = parts.add("in " ++ comp.chain.name) }
		{ comp.isDriving } { parts = parts.add("driving " ++ comp.target.asString) }
		{ comp.isOnBus } { parts = parts.add("bus " ++ comp.bus) }
		{ true } { parts = parts.add("not running anywhere yet") };
		comp.numChannels !? { |n| parts = parts.add(n.asString ++ " ch") };
		parts = parts.add("sidechain " ++ comp.sidechainLabel);
		parts = parts.add(if (comp.enabled) { "on" } { "off" });
		if (comp.isOnBus and: { comp.enabled } and: { comp.synth.isNil }) {
			parts = parts.add("waiting for the server");
		};
		statusText.string = parts.join(" · ");
	}
}
