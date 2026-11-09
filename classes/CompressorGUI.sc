// =====================================================================
// CompressorPanel - the compressor's controls as one view; CompressorGUI - as a window
//
//   (o) COMPRESSOR   < glue *           v >  save  ...
//   +--------------------------------------+ +------------------+ +-------+
//   | level · gain change listens to input v| | curve · down gain -6.2 dB| in gain out
//   |  the last 8 s on the meters' ruler,  | |  the transfer curve,     | |  #  |
//   |  the threshold a line through both   | |  the dot the detector   | |  #  |
//   +--------------------------------------+ +------------------+ +-------+
//   [dynamics][sidechain .]
//   | curve                              | | time                       | | output          |
//   | mode   threshold  ratio knee range | | detector attack release la | | makeup auto mix |
//
// The frame every effect window shares (sc-fx-ui) - the header, the display
// with its meters at the right, titled cards, the knobs and their gestures,
// the hint bar, the output card last - and a compressor's face: cause and
// effect on one dB ruler. At the left what happened (CompressorHistoryView),
// in the middle the rule (CompressorCurveView), at the right in, the gain
// change and out (CompressorMeterView). The threshold is one orchid line
// through the history and the curve; orchid - FxPalette.gain - is everything
// the compressor does to the level, blue everything that is sound. Threshold
// and ratio are the two big knobs, in the curve's colour; the threshold's
// knob carries a ring of where the detector is now.
//
// The sidechain page swaps the history for the sidechain eq (a ParaEQView,
// with the spectrum of what the detector listens to behind it) and the
// cards for what it listens to and the eq's selected dot. The curve, the
// meters and the output card stay. The tab wears a dot while the detector
// hears anything but its own plain input.
//
// The panel is what every window that shows a compressor is built from: its
// own window (CompressorGUI), the master chain in ServerPanel, FxChainPanel.
// It follows one Compressor and can be pointed at another with
// `panel.comp = other`.
//
//   panel = CompressorPanel(comp);
//   window.layout = VLayout(panel.view, hints.view);
//   panel.hints = hints;
//   panel.running = true;         // meters, and the sidechain's spectrum while it shows
//   panel.page = 1;               // the sidechain page (showEq does the same)
//   panel.remove;                 // in window.onClose
// =====================================================================

CompressorPanel {
	var <comp, <view, <header, <curveView, <historyView, <meterView, <eqView, <spectrum;
	var <tabs, displayStack, pageStack, eqTitle, source;
	var knobs, modeSwitch, detSwitch, autoSwitch, sourceChoice, auditionSwitch;
	var eqSwitch, bandSelector, flatBtn, slopeChoice, eqFreq, eqGain, widthStack, bellWidth, shelfWidth, cutRes;
	var curveCard, timeCard, listenCard, eqCard, outCard;
	var ctrl, classCtrl, bmCtrl, hints, running = false, watcher, sourceItems;
	var <selected = 0;

	*new { |comp, parent| ^super.new.init(comp, parent) }

	init { |argComp, parent|
		var displayRow, eqRegion, row, dynPage, scPage;
		knobs = IdentityDictionary.new;
		sourceItems = [["input", nil]];

		header = FxHeader(nil, "compressor", FxPalette.gain);
		header.defaultsFunc = { |u| u.settings_(Compressor.prPreset) };

		historyView = CompressorHistoryView(nil);
		historyView.hoverAction = { |v, what| this.prHot(what) };
		historyView.sourceAction = { this.prSourceMenu };
		curveView = CompressorCurveView(nil);
		curveView.hoverAction = { |v, what| this.prHot(what) };
		meterView = CompressorMeterView(nil);

		// the sidechain eq and, behind it, what the detector listens to
		eqView = ParaEQView(nil);
		eqView.action = { |v, what| if (what == \select) { this.prSelectHandle(v.selected) } };
		eqView.hoverAction = { |v, i| bandSelector.hot = i; this.prEqHot(i) };
		eqView.editAction = { |v, i| eqView.selected = i; eqFreq.prEdit };
		spectrum = ParaEQSpectrum(nil);
		spectrum.action = { { eqView.refresh }.defer };
		eqView.spectrum = spectrum;
		eqTitle = UserView().fixedHeight_(18).background_(FxPalette.waveBg);
		eqTitle.drawFunc = { |v| this.prDrawEqTitle(v) };
		eqRegion = View().background_(FxPalette.waveBg)
			.layout_(VLayout(eqTitle, [eqView.view, stretch: 1]).margins_(0).spacing_(0));

		displayStack = StackLayout(historyView.view, eqRegion);
		displayRow = HLayout(
			[View().layout_(displayStack), stretch: 1],
			curveView.view,
			meterView.view
		).spacing_(8).margins_(0);

		this.prMakeControls;
		dynPage = View().layout_(HLayout(curveCard.view, timeCard.view).margins_(0).spacing_(10));
		scPage = View().layout_(HLayout(listenCard.view, eqCard.view).margins_(0).spacing_(10));
		pageStack = StackLayout(dynPage, scPage);
		// both pages as tall as the taller, so the display keeps its size on a tab
		[dynPage, scPage].do(_.fixedHeight_([curveCard, timeCard, listenCard, eqCard, outCard]
			.collect { |c| c.view.sizeHint.height }.maxItem));
		tabs = FxSwitch(["dynamics", "sidechain"]).action_({ |t| this.prPage(t.value) });
		row = HLayout([View().layout_(pageStack), stretch: 1], outCard.view).spacing_(10).margins_(0);

		view = View(parent).layout_(VLayout(
			header.view,
			[displayRow, stretch: 1],
			HLayout(tabs.view, nil).margins_(0),
			row
		).margins_(0).spacing_(8));

		classCtrl = SimpleController(Compressor)
			.put(\presets, { { this.refresh }.defer })
			.put(\clipboard, { { this.refresh }.defer });
		\BusManager.asClass !? { |bm|
			bmCtrl = SimpleController(bm).put(\buses, { { this.prRefreshSource }.defer });
		};
		this.comp_(argComp);
		^this
	}

	// Which compressor the panel shows. The header, the views, the meter, the
	// sidechain eq and its spectrum and every control follow; nil leaves an
	// empty panel.
	comp_ { |c|
		ctrl !? (_.remove);
		ctrl = nil;
		if (running) { comp !? { |old| old.meter = false } };
		comp = c;
		header.unit = c;
		curveView.comp = c;
		historyView.comp = c;
		meterView.comp = c;
		eqView.eq = c !? (_.eq);
		source = CompressorSidechainSource(c);
		spectrum.eq = source;
		c !? {
			ctrl = SimpleController(c)
				.put(\levels, { { this.prLevels }.defer })
				.put(\enabled, { { this.refresh }.defer })
				.put(\settings, { { this.refresh }.defer })
				.put(\sidechain, { { spectrum.prSync; this.refresh }.defer })
				.put(\node, { { spectrum.prSync; this.refresh }.defer })
				.put(\eq, { { this.refresh }.defer })
				.put(\audition, { { this.refresh }.defer })
				.put(\freed, { { this.comp_(nil) }.defer });
			Compressor.paramNames.do { |key| ctrl.put(key, { { this.refresh }.defer }) };
			if (running) { c.meter = true };
		};
		this.refresh;
	}

	// the meters run - and the sidechain's spectrum, while its page shows -
	// while the panel is on screen
	running_ { |bool|
		running = bool == true;
		comp !? { comp.meter = running };
		spectrum.running = running and: { this.page == 1 };
		this.prWatch;
	}

	running { ^running }

	// replies that stop - switched off, freed, paused - would leave the meters
	// and the dot standing: this lets them fall, and the history say so
	prWatch {
		var was = false;
		watcher !? (_.stop);
		watcher = nil;
		if (running.not) { ^this };
		watcher = SkipJack({
			var alive = comp.notNil and: { comp.meter } and: { comp.isAlive };
			if (alive.not and: { was or: { historyView.filled > 0 } }) {
				meterView.refresh; curveView.refresh; historyView.refresh;
				knobs[\threshold].level = nil;
			};
			was = alive;
		}, 0.25, { view.isNil or: { view.isClosed } or: { running.not } }, "CompressorPanel meter", AppClock);
	}

	remove {
		ctrl !? (_.remove);
		ctrl = nil;
		classCtrl !? (_.remove);
		classCtrl = nil;
		bmCtrl !? (_.remove);
		bmCtrl = nil;
		watcher !? (_.stop);
		watcher = nil;
		historyView.remove;
		spectrum !? (_.free);
		comp !? { comp.meter = false };
		running = false;
	}

	isClosed { ^view.isNil or: { view.isClosed } }

	// --------------------------------------------------------------- pages

	page { ^tabs !? (_.value) ? 0 }
	page_ { |i| tabs.valueAction = i.asInteger.clip(0, 1) }

	// the sidechain page, as the old 'show eq' button had it
	showEq { |bool = true| this.page = bool.binaryValue }

	prPage { |i|
		displayStack.index = i;
		pageStack.index = i;
		spectrum.running = running and: { i == 1 };
		this.refresh;
	}

	// every control the panel draws, for a check or a picture
	widgets {
		^knobs.values.asArray ++ [modeSwitch, detSwitch, autoSwitch, sourceChoice, auditionSwitch,
			eqSwitch, bandSelector, flatBtn, slopeChoice, eqFreq, eqGain, bellWidth, shelfWidth, cutRes]
	}

	knobs { ^knobs }

	// ---------------------------------------------------------------- cards

	prKnob { |key, label, size, colour, bipolar = false|
		var spec = Compressor.specs[key];
		var k = FxKnob(label, spec, size, bipolar, colour);
		k.default = spec.default;
		k.action = { |kn| comp !? { |c| c.set(key, kn.mapped) } };
		knobs[key] = k;
		^k
	}

	// a toggle sits on the knobs' label line, a caption's height down
	prLow { |w| ^VLayout(View().fixedHeight_(16), w.view, nil).margins_(0).spacing_(0) }

	// cells side by side, top aligned, spread when the card is given room
	prRow { |cells|
		var items = [];
		cells.do { |c, n|
			items = items.add([c.isKindOf(Layout).if({ View().layout_(c) }, { c.asView }), align: \top]);
			if (n < (cells.size - 1)) { items = items.add(nil) };
		};
		^HLayout(*items).margins_(0).spacing_(6)
	}

	prCard { |title, cells, colour, extras|
		var card = FxCard(title, this.prRow(cells), colour, extras);
		// a card grows to share the width, but not so far its controls drift apart
		card.view.maxWidth = (card.view.sizeHint.width * 1.6).ceil;
		^card
	}

	prMakeControls {
		var gain = FxPalette.gain, slopes = ParaEQ.slopes;

		// dynamics: the curve, in orchid, then time
		modeSwitch = FxSwitch(["down", "up"], false, nil, "mode")
			.action_({ |s| comp !? (_.mode_(Compressor.modes[s.value])) });
		this.prKnob(\threshold, "threshold", \big, gain).dec_(1)
			.hint_("the level the curve bends at - the ring is the detector now, orchid when over it");
		this.prKnob(\ratio, "ratio", \big, gain)
			.menuItems_([["2:1", 2], ["4:1", 4], ["8:1", 8], ["20:1", 20]])
			.hint_("how much of what passes the threshold gets through: 4:1 lets a quarter through");
		this.prKnob(\knee, "knee", \small, gain).dec_(0).floorText_("hard")
			.hint_("how many dB round the threshold the curve bends over - hard is a corner");
		this.prKnob(\range, "range", \small, gain).dec_(0)
			.hint_("the most the gain ever moves - what keeps up mode from lifting silence");
		curveCard = this.prCard("curve", [modeSwitch, knobs[\threshold], knobs[\ratio], knobs[\knee], knobs[\range]], gain);

		detSwitch = FxSwitch(["peak", "rms"], false, nil, "detector")
			.action_({ |s| comp !? (_.detector_(Compressor.detectors[s.value])) });
		this.prKnob(\attack, "attack", \medium)
			.hint_("how quickly the gain moves away from 0 dB as the sound gets louder - a time constant");
		this.prKnob(\release, "release", \medium)
			.hint_("how quickly the gain comes back to 0 dB as the sound gets quieter");
		this.prKnob(\lookahead, "lookahead", \small).floorText_("off")
			.hint_("the sound waits for the detector, so the gain is there first - up to 20 ms of latency");
		timeCard = this.prCard("time", [detSwitch, knobs[\attack], knobs[\release], knobs[\lookahead]]);

		// the output, on every page
		this.prKnob(\gain, "makeup", \medium, nil, true).dec_(1)
			.hint_("gain after the compression, before the mix");
		autoSwitch = FxSwitch(["auto"], true).action_({ |s| comp !? (_.autoGain_(s.value)) });
		this.prKnob(\mix, "mix", \medium).box_(\pct)
			.hint_("how much of the compressed sound goes out - less is parallel compression");
		outCard = this.prCard("output", [knobs[\gain], this.prLow(autoSwitch), knobs[\mix]]);

		// sidechain: what the detector listens to, and the eq in front of it
		sourceChoice = FxChoice("source", ["input"], 150).action_({ |c| this.prPickSource(c.value) });
		auditionSwitch = FxSwitch(["audition"], true).action_({ |s| comp !? (_.audition_(s.value)) });
		this.prKnob(\link, "link", \small).box_(\pct)
			.hint_("how far the channels share one gain: 100 % follows the loudest, 0 each its own");
		listenCard = this.prCard("listens to", [sourceChoice, this.prLow(auditionSwitch), knobs[\link]]);

		eqSwitch = FxSwitch(["eq"], true).action_({ |s| comp !? { |c| c.eq.enabled = s.value } });
		bandSelector = FxSwitch(ParaEQ.numHandles.collect { |i| ParaEQView.handleLabel(i) })
			.itemColours_(ParaEQ.numHandles.collect { |i| ParaEQ.handleColour(i) })
			.action_({ |s| eqView.selected = s.value });
		flatBtn = FxButton("flat", { comp !? { |c| c.eq.flat } });
		eqFreq = FxKnob("freq", ControlSpec(ParaEQ.minFreq, ParaEQ.maxFreq, \exp, 0, 1000, "Hz"), \medium)
			.hint_("where the dot sits - type Hz or a note: a5 is 440 Hz")
			.action_({ |k| comp !? { |c| c.eq.freqAt_(selected, k.mapped) } });
		eqGain = FxKnob("gain", ControlSpec(ParaEQ.dbRange.neg, ParaEQ.dbRange, \lin, 0, 0, "dB"), \medium, true)
			.dec_(1).hint_("how far the band lifts or cuts what the detector hears")
			.action_({ |k| comp !? { |c| c.eq.dbAt_(selected, k.mapped) } });
		bellWidth = FxKnob("width", ControlSpec(0.05, 8, \exp, 0, 1, "oct"), \small).dec_(2)
			.hint_("how wide the bell is, in octaves")
			.action_({ |k| comp !? { |c| c.eq.widthAt_(selected, k.mapped) } });
		shelfWidth = FxKnob("width", ControlSpec(ParaEQ.qLo(0), ParaEQ.qHi, \exp, 0, 1), \small).box_(\pct)
			.hint_("how gently the shelf rises: 100 % is the steepest with no bump")
			.action_({ |k| comp !? { |c| c.eq.widthAt_(selected, k.mapped) } });
		cutRes = FxKnob("res", ControlSpec(-1, 1, \lin, 0, 0), \small, true).box_(\pct)
			.hint_("how the cut's corner rings: 0 is flat")
			.action_({ |k| comp !? { |c| c.eq.resAt_(selected, k.mapped) } });
		widthStack = StackLayout(bellWidth.view, shelfWidth.view, cutRes.view);
		slopeChoice = FxChoice("slope", ["out"] ++ slopes.collect { |s| "% dB/oct".format(s) })
			.action_({ |c| this.prPickSlope(c.value) });
		eqCard = this.prCard("sidechain eq", [this.prLow(eqSwitch), eqFreq, eqGain,
			View().fixedSize_(Size(FxKnob.cellWidth(\small), FxKnob.cellHeight(\small))).layout_(widthStack),
			slopeChoice], ParaEQ.handleColour(0), [bandSelector.view, flatBtn.view]);

		[knobs[\threshold], knobs[\ratio]].do { |k|
			var what = if (k === knobs[\threshold]) { \threshold } { \ratio };
			k.view.mouseEnterAction = { curveView.hot = what; historyView.hot = what; curveView.refresh; historyView.refresh; nil };
			k.view.mouseLeaveAction = { curveView.hot = nil; historyView.hot = nil; curveView.refresh; historyView.refresh; nil };
		};
		[eqFreq, eqGain, bellWidth, shelfWidth, cutRes].do { |k|
			k.view.mouseEnterAction = { eqView.hot = selected; nil };
			k.view.mouseLeaveAction = { eqView.hot = nil; nil };
		};
	}

	// a handle in a display is under the pointer: its knob's name lights
	prHot { |what|
		knobs[\threshold].highlight = what == \threshold;
		knobs[\ratio].highlight = (what == \ratio) or: { what == \fan };
		knobs[\knee].highlight = what == \knee;
	}

	prEqHot { |i|
		[eqFreq, eqGain, bellWidth, shelfWidth, cutRes].do { |k| k.highlight = i.notNil and: { i == selected } };
	}

	// ---------------------------------------------------------- the source

	// input, the BusManager's audio busses by name, a bus by number, and -
	// driving a graph of your own - the signal handed to Compressor.ar
	prSourceItems {
		var items = [["input", nil]], bm = \BusManager.asClass, t = comp !? (_.target);
		bm !? { bm.entries.select(_.isAudio).do { |e| items = items.add([e.name.asString, e.name]) } };
		items = items.add(["bus number...", \number]);
		if (t.notNil and: { t.isKindOf(NodeProxy) or: { t.isKindOf(Node) } }) {
			items = items.add(["the graph's sidechain", true]);
		};
		^items
	}

	prRefreshSource {
		var c = comp, i, bm = \BusManager.asClass, over;
		sourceItems = this.prSourceItems;
		sourceChoice.items = sourceItems.collect(_[0]);
		if (c.isNil) { ^this };
		i = case
		{ c.external.not } { 0 }
		{ c.sidechainName.notNil } { sourceItems.detectIndex { |it| it[1] == c.sidechainName } }
		{ c.sidechain.notNil and: { bm.notNil } } {
			sourceItems.detectIndex { |it| it[1].isKindOf(Symbol) and: { it[1] != \number }
				and: { bm.index(it[1]) == c.sidechain } }
		}
		{ c.sidechain.isNil } { sourceItems.detectIndex { |it| it[1] == true } };
		if (i.isNil) {
			i = sourceItems.detectIndex { |it| it[1] == \number } ? 0;
			over = c.sidechainLabel;
		};
		sourceChoice.value = i;
		sourceChoice.override = over;
		sourceChoice.refresh;
	}

	prPickSource { |i|
		var v = sourceItems[i] !? (_[1]);
		if (comp.isNil) { ^this };
		case
		{ i == 0 } { comp.sidechain = nil }
		{ v == \number } { this.prBusNumberDialog }
		{ v == true } { comp.sidechain = true }
		{ true } { comp.sidechain = v };
		this.prRefreshSource;
	}

	// the same menu, from the history's caption
	prSourceMenu {
		this.prRefreshSource;
		Menu(*sourceItems.collect { |it, i|
			MenuAction(it[0], { this.prPickSource(i) }).checkable_(true).checked_(i == sourceChoice.value)
		}).front;
	}

	prBusNumberDialog {
		var w, field, take;
		w = Window("the detector listens to bus...", Rect(300, 400, 300, 76), resizable: false);
		FxPalette.darken(w);
		field = TextField().font_(FxPalette.font).string_((comp.sidechain.isKindOf(Integer).if { comp.sidechain } { "" }).asString);
		take = {
			var n = field.string.stripWhiteSpace.asInteger;
			if (field.string.stripWhiteSpace.notEmpty and: { comp.notNil }) { comp.sidechain = n.max(0) };
			w.close;
		};
		field.action = take;
		w.layout = VLayout(
			StaticText().string_("a bus number - typing one stops following a name")
				.font_(FxPalette.smallFont).stringColor_(FxPalette.dim),
			HLayout(field, FxButton("listen", take, 56).view).spacing_(6)
		).margins_(8).spacing_(6);
		w.front;
		field.focus(true);
	}

	// ------------------------------------------------------ the sidechain eq

	// the dot the card works on lives in the eq view; the card follows it
	prSelectHandle { |i|
		selected = i;
		this.prRefreshEq;
	}

	prPickSlope { |v|
		var e = comp !? (_.eq);
		if (e.isNil or: { ParaEQ.isCut(selected).not }) { ^this };
		if (v == 0) { e.cutOn_(selected, false) } {
			e.slopeAt_(selected, ParaEQ.slopes[v - 1]);
			e.cutOn_(selected, true);
		};
	}

	prRefreshEq {
		var e = comp !? (_.eq), i = selected, cut = ParaEQ.isCut(i), off, in;
		if (this.isClosed) { ^this };
		bandSelector.value = i;
		eqCard.colour = ParaEQ.handleColour(i);
		eqCard.title = "sidechain eq · " ++ if (cut) { ParaEQ.cutWord(i) } { "%  %".format(i + 1, ParaEQ.shapeWord(i)) };
		widthStack.index = case { cut } { 2 } { ParaEQ.isShelf(i) } { 1 } { true } { 0 };
		if (e.isNil) { ^this };
		off = e.enabled.not;
		in = e.cutOn(i);
		eqSwitch.value = e.enabled;
		this.prSet(eqFreq, e.freqAt(i));
		this.prSet(eqGain, e.dbAt(i));
		case
		{ cut } { this.prSet(cutRes, e.resAt(i)) }
		{ ParaEQ.isShelf(i) } { this.prSet(shelfWidth, e.widthAt(i)) }
		{ true } {
			this.prSet(bellWidth, e.widthAt(i));
			bellWidth.default = ParaEQ.bellOctaves(1, e.freqAt(i), e.sampleRate);
		};
		slopeChoice.value = if (cut and: { in }) { (ParaEQ.slopes.indexOf(e.slopeAt(i)) ? 1) + 1 } { 0 };
		bandSelector.inactive = off;
		flatBtn.inactive = off;
		eqFreq.inactive = off or: { cut and: { in.not } };
		eqGain.inactive = off or: { cut };
		[bellWidth, shelfWidth].do { |k| k.inactive = off };
		cutRes.inactive = off or: { in.not } or: { (e.slopeAt(i) ? 12) <= ParaEQ.slopeLo };
		slopeChoice.inactive = off or: { cut.not };
		eqView.refresh;
		eqTitle.refresh;
	}

	prDrawEqTitle { |v|
		var w = v.bounds.width, f = FxPalette.smallFont, str;
		if (comp.isNil) { ^this };
		if (comp.audition) {
			^Pen.stringLeftJustIn("audition: you hear what it listens to, not the output",
				Rect(6, 2, w - 12, 15), f, FxPalette.accent)
		};
		str = "sidechain eq · listens to " ++ if (comp.external) { comp.sidechainLabel } { "input" }
			++ if (comp.eq.enabled) { "" } { " · off" };
		Pen.stringLeftJustIn(str, Rect(6, 2, w - 12, 15), f, FxPalette.text);
		if (spectrum.visible.not and: { running }) {
			Pen.stringRightJustIn(if (source.analysisAvailable) { "" } { "no spectrum: nothing to read here" },
				Rect(6, 2, w - 12, 15), f, FxPalette.dim);
		};
	}

	// ---------------------------------------------------------------- hints

	hints_ { |bar|
		hints = bar;
		historyView.hints = bar;
		curveView.hints = bar;
		eqView.hints = bar;
		if (bar.isNil) { ^this };
		header.hints = bar;
		bar.attach(header.power.view, "switch the compressor on - off, it costs nothing");
		bar.attach(tabs.view, "dynamics: the curve and its timing · sidechain: what the detector hears - a dot when not its plain input");
		bar.attach(meterView.view, "in, the gain change, out - on the display's dB · click to drop the held numbers and clip lights");
		bar.attach(modeSwitch.view, "down squashes what goes over the threshold; up lifts what falls under it, by range at most");
		bar.attach(detSwitch.view, "what the detector measures: the peak of the wave, or a 5 ms rms - gentler on transients");
		bar.attach(autoSwitch.view, {
			"add back half of what the curve takes 20 dB past the threshold - now % · makeup adds to it"
				.format(comp !? { FxPalette.format(comp.autoGainDb.max(0) * if (comp.autoGain) { 1 } { 0 }, "dB", nil, 1, true) } ? "")
		});
		bar.attach(sourceChoice.view, "what the detector listens to: the input, a bus by name - followed when it moves - or by number");
		bar.attach(auditionSwitch.view, "hear what the detector hears - the source through its eq - instead of the output");
		bar.attach(eqSwitch.view, "the five band eq in front of the detector - the sound itself is untouched");
		bar.attach(bandSelector.view, "which dot of the sidechain eq the card works on - or click it in the display");
		bar.attach(flatBtn.view, "every band back to 0 dB, both cuts out");
		bar.attach(slopeChoice.view, "a cut's slope, or out - picking a slope puts the cut in");
		bar.attach(eqTitle, "the sidechain eq: what the detector hears, behind it the spectrum of what it listens to");
		(knobs.values.asArray ++ [eqFreq, eqGain, bellWidth, shelfWidth, cutRes]).do { |k|
			k.hints = bar;
			bar.attach(k.view, { "%  %   -   %".format(k.label, k.text, this.prWhyDim(k) ? this.prKnobHint(k)) });
		};
		^this
	}

	hints { ^hints }

	prKnobHint { |k|
		if (k === knobs[\gain] and: { comp.notNil } and: { comp.autoGain }) {
			^k.hint ++ " - auto adds % under it".format(FxPalette.format(comp.autoGainDb, "dB", nil, 1, true))
		};
		^k.hint
	}

	// what a dimmed control is waiting for, in place of its hint; nil if it is lit
	prWhyDim { |k|
		if (k.inactive.not or: { comp.isNil }) { ^nil };
		^case
		{ [\threshold, \knee, \range, \attack, \release].any { |key| knobs[key] === k } } {
			"at 1:1 the gain never moves: raise the ratio"
		}
		{ k === knobs[\link] } { "one channel: there is nothing to link" }
		{ [\gain, \mix].any { |key| knobs[key] === k } } { "audition is on: you hear the detector, not the output" }
		{ comp.eq.enabled.not } { "the sidechain eq is off: switch it in" }
		{ k === eqGain } { "a cut has no gain - only a slope" }
		{ k === cutRes } { "at 6 dB/oct, or out, a cut has no corner to ring" }
		{ k === eqFreq } { "the cut is out: pick a slope" }
		{ true } { nil }
	}

	// -------------------------------------------------------------- refresh

	refresh {
		var c = comp, off, still, chans, aud, gain = FxPalette.gain;
		if (this.isClosed) { ^this };
		header.refresh;
		this.prRefreshSource;
		if (c.isNil) {
			this.widgets.do { |w| w.tryPerform(\inactive_, true) };
			[historyView, curveView, meterView, eqView].do(_.refresh);
			^this
		};
		off = c.enabled.not;
		this.widgets.do { |w| w.tryPerform(\muted_, off) };
		knobs.keysValuesDo { |key, k| this.prSet(k, c.perform(key)) };
		modeSwitch.value = Compressor.modes.indexOf(c.mode) ? 0;
		detSwitch.value = Compressor.detectors.indexOf(c.detector) ? 0;
		autoSwitch.value = c.autoGain;
		auditionSwitch.value = c.audition;

		// what does nothing now looks it
		still = c.ratio <= 1.001;
		chans = c.numChannels ?? { c.target.tryPerform(\numChannels) } ? 2;
		aud = c.audition;
		[\threshold, \knee, \range, \attack, \release].do { |key| knobs[key].inactive = still };
		knobs[\link].inactive = chans <= 1;
		[knobs[\gain], knobs[\mix], autoSwitch].do { |w| w.inactive = aud };
		knobs[\threshold].levelMirror = c.mode == \up;
		meterView.gainColour = FxPalette.fade(gain, if (off) { 0.5 } { 1 });

		// a dot on the sidechain tab while it is not the plain input
		tabs.marks = if (c.external or: { c.eq.enabled } or: { c.link < 0.999 } or: { c.audition }) { [1] } { [] };
		this.prRefreshEq;
		[historyView, curveView, meterView].do(_.refresh);
	}

	// every reply: a frame for the history, the hero number, the meters, the ring
	prLevels {
		var alive;
		if (this.isClosed or: { comp.isNil }) { ^this };
		alive = comp.meter and: { comp.isAlive };
		historyView.levelsArrived;
		curveView.levelsArrived;
		knobs[\threshold].level = if (alive) {
			Compressor.specs[\threshold].unmap(comp.detLevel.max(1e-6).ampdb.clip(-60, 0))
		};
		if (this.page == 0) { historyView.refresh };
		curveView.refresh;
		meterView.refresh;
	}

	// a knob told only when it would move, so a drag repaints the one that changed
	prSet { |k, v|
		var u;
		if (v.isNil) { ^this };
		u = k.spec.unmap(k.spec.constrain(v)).clip(0, 1);
		if ((k.value - u).abs > 1e-6) { k.value = u };
	}
}


// =====================================================================
// CompressorGUI - one compressor in a window of its own
//
//   c = Compressor.onBus(0, 2).enabled_(true);
//   c.gui;                      // or CompressorGUI(c)
//
// The panel above in FxDeviceGUI, the window every effect has: the hint at
// the left of one line along the bottom, where the compressor runs at its
// right. Closing the window stops the meters and the spectrum; the
// compressor itself is untouched.
// =====================================================================

CompressorGUI : FxDeviceGUI {
	comp { ^unit }
}
