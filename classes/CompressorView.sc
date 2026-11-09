// =====================================================================
// The compressor's display: cause and effect on one dB ruler
//
//   +--------------------------------------------+ +----------------+ +--------+
//   | level · gain change      listens to input v| | curve · down  gain -6.2 dB| in gain out
//   |   the input filled, the output a line,     | | 0 -           .:' 2 4 8   | |  #  |
//   |   orchid where the detector is over the    | |-6 -  ---------*---------- | |  #  |
//   |   threshold line, the gain change hanging  | |   the transfer curve,     | |  #  |
//   |   from 0 dB                                | |   the dot is the detector | |     |
//   | -6 s        -4 s        -2 s           now | |  -48 -24 -12 -6 0     in  | |     |
//   +--------------------------------------------+ +----------------+ +--------+
//
// CompressorHistoryView is what happened: eight seconds of level, the
// threshold as a line across them, the stretch where the detector is over
// it tinted, and the gain change it caused hanging from 0 dB right under it
// (in up mode the tint goes under the line and the lift rises from the
// floor). CompressorCurveView is the rule: input along, output up, the
// curve the server's gain computer makes (Compressor.staticGain, without
// makeup), crossed by the same threshold line, with the detector riding it
// as a dot that lags the curve by the attack and the release. Both draw
// their dB on FxMeter's ruler - FxMeter.dbToY - so the history, the curve
// and the meters at their right read on the same lines, and 6 dB of gain
// change is 6 dB everywhere.
//
// Before any sound has come back, the history plays a test kick, every half
// second, through the current settings - the same gain computer and the
// same attack and release - so what the knobs do can be seen with no server.
//
//   drag the orchid line         the threshold, in either view (shift: fine)
//   double click it              back to -20 dB; the wheel on it: 0.5 dB steps
//   drag the curve past the bend the ratio (shift: fine); double click: 4:1
//   cmd + drag in the curve      the ratio, from anywhere (alt or ctrl too)
//   the fan's 2 4 8 20           that ratio, exactly
//   the wheel in the curve       the knee
//   double click the history     clear it (the test kick comes back)
//
// CompressorMeterView is FxMeter with its gain bar: in, the gain change,
// out. CompressorSidechainSource tells a ParaEQSpectrum where to read what
// the detector listens to, for the sidechain page.
// =====================================================================

CompressorHistoryView {
	classvar <>floorDb = -60;
	classvar <>seconds = 8;
	classvar <>ghostStep = 0.005;		// what the test kick is drawn in, seconds a point

	var <view, <comp, <>action, <>hoverAction, <>sourceAction, <hints, <>hot;
	var dets, ins, outs, grs, size, pos = 0, <filled = 0;
	var ghost, ghostKey;
	var drag, dragY = 0, dragFrom = 0, dragFine = false, <hover, pointer, sourceRect, lastPaint = 0;
	var bw = 1, bh = 1;

	*new { |comp, parent| ^super.new.init(comp, parent) }

	init { |argComp, parent|
		size = (seconds * Compressor.meterRate).asInteger.max(8);
		this.prClear;
		sourceRect = Rect(0, 0, 0, 0);
		view = UserView(parent).minHeight_(180).minWidth_(240);
		view.background_(FxPalette.waveBg);
		view.acceptsMouseOver = true;
		view.drawFunc = { this.prDraw };
		view.mouseDownAction = { |v, x, y, mod, button, count| this.prMouseDown(x, y, mod, count, button); true };
		view.mouseMoveAction = { |v, x, y, mod| this.prMouseMove(x, y, mod); true };
		view.mouseUpAction = { this.prMouseUp; true };
		view.mouseWheelAction = { |v, x, y, mod, xd, yd| this.prWheel(x, y, mod, yd); true };
		view.mouseOverAction = { |v, x, y| this.prHover(x, y); true };
		view.mouseLeaveAction = { this.prLeave; nil };
		comp = argComp;
		^this
	}

	comp_ { |c|
		comp = c;
		this.prClear;
		ghostKey = nil;
		this.refresh;
	}

	hints_ { |bar| hints = bar }

	// nothing of its own to let go of: the panel hands it the replies
	remove { }

	refresh { if (view.notNil and: { view.isClosed.not }) { view.refresh } }

	isLive { ^comp.notNil and: { comp.meter } and: { comp.isAlive } }

	// the panel calls this on every reply: one frame into the ring
	levelsArrived {
		if (comp.isNil) { ^this };
		dets[pos] = comp.detLevel.max(1e-6).ampdb.max(floorDb);
		ins[pos] = comp.inLevel.max(1e-6).ampdb.max(floorDb);
		outs[pos] = comp.outLevel.max(1e-6).ampdb.max(floorDb);
		grs[pos] = comp.reduction;
		pos = (pos + 1) % size;
		filled = (filled + 1).min(size);
	}

	clear { this.prClear; this.refresh }

	prClear {
		dets = Array.fill(size, { floorDb });
		ins = Array.fill(size, { floorDb });
		outs = Array.fill(size, { floorDb });
		grs = Array.fill(size, { 0 });
		pos = 0;
		filled = 0;
	}

	// ------------------------------------------------------------ geometry

	prMeasure {
		var b = view.bounds;
		bw = b.width.max(1);
		bh = b.height.max(1);
	}

	prY { |db| ^FxMeter.dbToY(db, bh) }

	prDbPerPixel { ^66 / (bh - FxMeter.topInset - FxMeter.bottomInset).max(1) }

	prNearLine { |y| ^comp.notNil and: { (y - this.prY(comp.threshold)).abs <= 8 } }

	// ---------------------------------------------------------- the ghost

	// What a kick every half second (-3 dBFS, falling 30 dB in 120 ms onto a
	// -36 dBFS floor) does through these settings: the gain computer and the
	// ballistics in numbers, a millisecond a step, one second of warm-up and
	// one second kept - as [det, in, out, gain] in dB, one value every
	// ghostStep. The same arithmetic as Compressor.ar, so a slow attack lets
	// the hit through here as it does there.
	*ghostFor { |comp|
		var dt = 0.001, period = 0.5, keep = 1000, warm = 1000, every;
		var thr = comp.threshold, ratio = comp.ratio, knee = comp.knee, mode = comp.mode;
		var range = comp.range, mix = comp.mix, mk = comp.makeup, rms = comp.detector == \rms;
		var cAtt = exp(dt.neg / comp.attack.max(1e-5)), cRel = exp(dt.neg / comp.release.max(1e-5));
		var la = (comp.lookahead / dt).round.asInteger;
		var level = { |i| max(-3 - (250 * ((i * dt) % period)), -36) };
		var g = 0, d, ii, o, gg, out = Array.fill(4, { [] });
		every = (this.ghostStep / dt).round.asInteger.max(1);
		d = Array.newClear(keep); ii = Array.newClear(keep); o = Array.newClear(keep); gg = Array.newClear(keep);
		(warm + keep).do { |i|
			var lv = level.(i), det = if (rms) { lv - 3 } { lv };
			var target = Compressor.staticGain(det, thr, ratio, knee, mode, range);
			g = if (target > g) { target + ((g - target) * cRel) } { target + ((g - target) * cAtt) };
			if (i >= warm) {
				var k = i - warm, a = level.(i - la).dbamp;
				d[k] = det; ii[k] = lv; gg[k] = g;
				o[k] = (a * ((mix * (g + mk).dbamp) + (1 - mix))).max(1e-6).ampdb.max(floorDb);
			};
		};
		// the loudest of each step - the gain change's extreme - as a reply would hold
		(keep / every).asInteger.do { |j|
			var a = j * every, b = a + every - 1;
			out[0] = out[0].add(d.copyRange(a, b).maxItem);
			out[1] = out[1].add(ii.copyRange(a, b).maxItem);
			out[2] = out[2].add(o.copyRange(a, b).maxItem);
			out[3] = out[3].add(if (mode == \up) { gg.copyRange(a, b).maxItem } { gg.copyRange(a, b).minItem });
		};
		^out
	}

	prGhost {
		var key = [comp.threshold, comp.ratio, comp.knee, comp.attack, comp.release, comp.lookahead,
			comp.range, comp.mix, comp.makeup, comp.mode, comp.detector];
		if (key != ghostKey) { ghost = this.class.ghostFor(comp); ghostKey = key };
		^ghost
	}

	// [xs, dets, ins, outs, grs]: the frames there are, newest at the right -
	// or, with none yet, eight seconds of the test kick
	prSeries {
		var n, xs, idx, step, g;
		if (filled > 0) {
			step = bw / (size - 1);
			idx = (0 .. filled - 1).collect { |k| (pos - filled + k) % size };
			xs = (0 .. filled - 1).collect { |k| bw - ((filled - 1 - k) * step) };
			^[xs] ++ [dets, ins, outs, grs].collect { |a| idx.collect { |j| a[j] } }
		};
		g = this.prGhost;
		n = g[0].size * seconds;
		xs = (0 .. n - 1) * (bw / (n - 1).max(1));
		^[xs] ++ g.collect { |a| a.wrapExtend(n) }
	}

	// --------------------------------------------------------------- mouse

	prMouseDown { |x, y, mod, count, button|
		if (comp.isNil or: { (button ? 0) != 0 }) { ^this };
		this.prMeasure;
		if (sourceRect.containsPoint(x @ y)) { ^sourceAction.value(this) };
		if (this.prNearLine(y)) {
			if (count == 2) {
				comp.threshold = Compressor.specs[\threshold].default;
				drag = nil;
			} {
				drag = \threshold;
				dragY = y;
				dragFrom = comp.threshold;
				dragFine = mod.isShift;
			};
			pointer = x @ y;
			this.prSayHint(x, y);
			^this.refresh
		};
		// a double click away from the line clears; a single one does nothing
		if (count == 2) { this.clear };
	}

	prMouseMove { |x, y, mod|
		var fine = mod.isShift;
		if (drag != \threshold or: { comp.isNil }) { ^this };
		// a change of speed mid drag carries on from where it is, no jump
		if (fine != dragFine) { dragY = y; dragFrom = comp.threshold; dragFine = fine };
		comp.threshold = (dragFrom + ((dragY - y) * this.prDbPerPixel * if (fine) { 0.1 } { 1 })).round(0.1);
		pointer = x @ y;
		this.prSayHint(x, y);
		this.refresh;
	}

	prMouseUp { drag = nil; this.refresh }

	prWheel { |x, y, mod, yd|
		if (comp.isNil or: { yd == 0 }) { ^this };
		this.prMeasure;
		if (this.prNearLine(y)) {
			comp.threshold = (comp.threshold + (yd.sign * if (mod.isShift) { 0.1 } { 0.5 })).round(0.1);
			this.prSayHint(x, y);
			this.refresh;
		};
	}

	prHover { |x, y|
		var h;
		this.prMeasure;
		pointer = x @ y;
		h = case
		{ comp.isNil } { nil }
		{ this.prNearLine(y) } { \threshold }
		{ sourceRect.containsPoint(x @ y) } { \source }
		{ true } { nil };
		if (h != hover) { hover = h; hoverAction.value(this, h); this.refresh };
		this.prSayHint(x, y);
		if (h == \threshold and: { (Main.elapsedTime - lastPaint) >= 0.04 }) { this.refresh };
	}

	prLeave {
		pointer = nil;
		if (hover.notNil) { hover = nil; hoverAction.value(this, nil) };
		this.refresh;
	}

	prSayHint { |x, y| hints !? { |bar| bar.string = this.hint(x, y) } }

	// what is under the pointer, for the hint bar
	hint { |x, y|
		if (comp.isNil) { ^"level and gain change over time - there is no compressor here yet" };
		this.prMeasure;
		^case
		{ this.prNearLine(y) or: { drag == \threshold } } { CompressorCurveView.thresholdHint(comp) }
		{ sourceRect.containsPoint(x @ y) } {
			"what the detector listens to - click: the input, a bus by name or by number"
		}
		{ true } {
			"the last % s: in filled, out as a line, the gain change in orchid - drag the line: threshold"
			" · double click: clear".format(seconds)
		}
	}

	// ------------------------------------------------------------- drawing

	prDraw {
		var w, h, up, off, la, ga, thr, knee, s, xs, dt, ii, oo, gg, yThr, yFloor, yZero, showDet, gainC, t;
		this.prMeasure;
		w = bw; h = bh;
		lastPaint = Main.elapsedTime;
		Pen.fillColor = FxPalette.waveBg;
		Pen.fillRect(Rect(0, 0, w, h));
		this.prGrid(w, h);
		if (comp.isNil) {
			Pen.stringLeftJustIn("level · gain change", Rect(6, 2, w - 12, 16), FxPalette.smallFont, FxPalette.dim);
			^this
		};
		up = comp.mode == \up;
		off = comp.enabled.not;
		la = if (filled > 0) { 1 } { 0.45 };		// the test kick is drawn fainter
		ga = la * if (off) { 0.5 } { 1 };			// and the gain's part halved when off
		gainC = FxPalette.gain;
		thr = comp.threshold;
		knee = comp.knee;
		yThr = this.prY(thr);
		yFloor = this.prY(floorDb);
		yZero = this.prY(0);
		s = this.prSeries;
		xs = s[0]; dt = s[1]; ii = s[2]; oo = s[3]; gg = s[4];
		showDet = comp.external or: { comp.eq.enabled } or: { comp.detector == \rms };

		// the knee, round the line
		Pen.fillColor = FxPalette.fade(gainC, 0.08 * ga);
		t = this.prY(thr + (knee * 0.5));
		Pen.fillRect(Rect(0, t, w, (this.prY(thr - (knee * 0.5)) - t).max(1)));

		if (xs.size > 1) {
			// the input, filled from the floor
			Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.16 * la);
			Pen.moveTo(xs[0] @ yFloor);
			xs.do { |x, i| Pen.lineTo(x @ this.prY(ii[i])) };
			Pen.lineTo(xs.last @ yFloor);
			Pen.fill;

			// the detector, when it is not simply the input
			if (showDet) {
				Pen.strokeColor = FxPalette.fade(FxPalette.text, 0.5 * la);
				Pen.width = 1;
				xs.do { |x, i| if (i == 0) { Pen.moveTo(x @ this.prY(dt[i])) } { Pen.lineTo(x @ this.prY(dt[i])) } };
				Pen.stroke;
			};

			// the tint: what is past the line, right where it is
			Pen.fillColor = FxPalette.fade(gainC, 0.28 * ga);
			Pen.moveTo(xs[0] @ yThr);
			xs.do { |x, i|
				var d = dt[i];
				var edge = if (up) { if (d <= (floorDb + 0.1)) { thr } { min(d, thr) } } { max(d, thr) };
				Pen.lineTo(x @ this.prY(edge));
			};
			Pen.lineTo(xs.last @ yThr);
			Pen.fill;

			// the gain change: hanging from 0 dB, or rising from the floor
			Pen.fillColor = FxPalette.fade(gainC, 0.25 * ga);
			Pen.moveTo(xs[0] @ if (up) { yFloor } { yZero });
			xs.do { |x, i| Pen.lineTo(x @ this.prGainY(gg[i], up)) };
			Pen.lineTo(xs.last @ if (up) { yFloor } { yZero });
			Pen.fill;
			Pen.strokeColor = FxPalette.fade(gainC, ga);
			Pen.width = 1.5;
			xs.do { |x, i| var p = x @ this.prGainY(gg[i], up); if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) } };
			Pen.stroke;
		};

		// the range: the furthest the gain goes
		if (comp.range < 48) {
			t = this.prY(if (up) { floorDb + comp.range } { comp.range.neg });
			Pen.strokeColor = FxPalette.fade(gainC, 0.6 * ga);
			Pen.width = 1;
			CompressorCurveView.dash(0 @ t, (w - 40) @ t);
			Pen.stroke;
			Pen.stringLeftJustIn("range", Rect(w - 36, t - 6, 34, 12), FxPalette.smallFont, FxPalette.fade(gainC, 0.8 * ga));
		};

		// the output, a line over it all
		if (xs.size > 1) {
			Pen.strokeColor = FxPalette.fade(FxPalette.wave, la);
			Pen.width = 1.5;
			xs.do { |x, i| var p = x @ this.prY(oo[i]); if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) } };
			Pen.stroke;
		};

		// the threshold, the line both views share
		Pen.strokeColor = FxPalette.fade(gainC, if (off) { 0.5 } { 1 });
		Pen.width = if ((hover == \threshold) or: { drag == \threshold } or: { hot == \threshold }) { 2.5 } { 1.5 };
		Pen.line(0 @ yThr, w @ yThr);
		Pen.stroke;
		if (pointer.notNil and: { (hover == \threshold) or: { drag == \threshold } }) {
			CompressorCurveView.chip(FxPalette.format(thr, "dB", nil, 1), pointer.x.clip(30, w - 30), yThr, off);
		};
		this.prCaption(w, h, showDet);
	}

	prGainY { |g, up| ^if (up) { this.prY(floorDb + g) } { this.prY(g) } }

	prGrid { |w, h|
		Pen.width = 1;
		FxMeter.ticks.do { |db|
			var y = this.prY(db);
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (db == 0) { 0.6 } { 0.35 });
			Pen.line(0 @ y, w @ y);
			Pen.stroke;
		};
		Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.2);
		(1 .. (seconds / 2).asInteger - 1).do { |k|
			var x = w - (w * k * 2 / seconds);
			Pen.line(x @ FxMeter.topInset, x @ (h - FxMeter.bottomInset));
			Pen.stroke;
			Pen.stringCenteredIn("-% s".format(k * 2), Rect(x - 20, h - 14, 40, 12), FxPalette.smallFont, FxPalette.dim);
		};
		Pen.stringRightJustIn("now", Rect(w - 40, h - 14, 36, 12), FxPalette.smallFont, FxPalette.dim);
	}

	prCaption { |w, h, showDet|
		var x = 6, f = FxPalette.smallFont, src, sw, status, statusColour;
		var swatch = { |name, kind, colour|
			case
			{ kind == \fill } { Pen.fillColor = FxPalette.fade(colour, 0.5); Pen.fillRect(Rect(x, 7, 8, 7)) }
			{ true } { Pen.fillColor = colour; Pen.fillRect(Rect(x, 10, 8, 2)) };
			x = x + 11;
			Pen.stringLeftJustIn(name, Rect(x, 2, 60, 16), f, FxPalette.dim);
			x = x + name.bounds(f).width + 10;
		};
		Pen.stringLeftJustIn("level · gain change", Rect(x, 2, 140, 16), f, FxPalette.text);
		x = x + "level · gain change".bounds(f).width + 14;
		swatch.("in", \fill, FxPalette.wave);
		swatch.("out", \line, FxPalette.wave);
		swatch.("gain", \fill, FxPalette.gain);
		if (showDet) { swatch.("detector", \line, FxPalette.fade(FxPalette.text, 0.6)) };

		// what it listens to - a menu
		src = "listens to " ++ if (comp.external) { comp.sidechainLabel } { "input" }
			++ if (comp.eq.enabled) { " · eq" } { "" };
		sw = src.bounds(f).width.ceil + 16;
		sourceRect = Rect(w - sw - 4, 2, sw, 16);
		Pen.stringLeftJustIn(src, Rect(sourceRect.left, 2, sw, 16), f,
			if (hover == \source) { FxPalette.text } { FxPalette.dim });
		Pen.fillColor = FxPalette.dim;
		Pen.moveTo((w - 13) @ 8); Pen.lineTo((w - 7) @ 8); Pen.lineTo((w - 10) @ 12); Pen.lineTo((w - 13) @ 8);
		Pen.fill;

		// one line of state, where the data does not reach: over +6 dB
		# status, statusColour = case
		{ comp.audition } { ["audition: you hear what it listens to, not the output", FxPalette.accent] }
		{ comp.enabled.not } { ["off - the gain shown is what it would do", FxPalette.dim] }
		{ filled == 0 } { ["no signal - a test kick through these settings", FxPalette.dim] }
		{ this.isLive.not } { ["no signal", FxPalette.dim] }
		{ true } { [nil, nil] };
		status !? { Pen.stringLeftJustIn(status, Rect(6, 22, w - 12, 13), f, statusColour) };
	}
}


// =====================================================================
// CompressorCurveView - the transfer curve, on the meters' ruler
// =====================================================================

CompressorCurveView {
	classvar <>floorDb = -60;
	classvar <>ratioPerPixel = 1.02;
	classvar <>fanRatios = #[2, 4, 8, 20];
	classvar <>labelWidth = 26;

	var <view, <comp, <>action, <>hoverAction, <hints, <>hot;
	var drag, dragY = 0, dragFrom = 0, dragFine = false, <hover, pointer, inside = false;
	var <fanRects, recent, recentPos = 0, lastPaint = 0;
	var bw = 1, bh = 1, plot;

	*new { |comp, parent| ^super.new.init(comp, parent) }

	init { |argComp, parent|
		comp = argComp;
		recent = Array.fill(Compressor.meterRate.asInteger.max(1), { 0 });
		fanRects = [];
		plot = Rect(labelWidth, 24, 100, 100);
		view = UserView(parent).fixedWidth_(310).minHeight_(180);
		view.background_(FxPalette.waveBg);
		view.acceptsMouseOver = true;
		view.drawFunc = { this.prDraw };
		view.mouseDownAction = { |v, x, y, mod, button, count| this.prMouseDown(x, y, mod, count, button); true };
		view.mouseMoveAction = { |v, x, y, mod| this.prMouseMove(x, y, mod); true };
		view.mouseUpAction = { this.prMouseUp; true };
		view.mouseWheelAction = { |v, x, y, mod, xd, yd| this.prWheel(x, y, mod, yd); true };
		view.mouseOverAction = { |v, x, y| this.prHover(x, y); true };
		view.mouseLeaveAction = { this.prLeave; nil };
		view.onResize = { this.prFit };
		^this
	}

	comp_ { |c| comp = c; recent = recent.collect { 0 }; this.refresh }

	hints_ { |bar| hints = bar }

	refresh { if (view.notNil and: { view.isClosed.not }) { view.refresh } }

	isLive { ^comp.notNil and: { comp.meter } and: { comp.isAlive } }

	// the panel calls this on every reply: the hero number is the most the
	// gain moved in the last second
	levelsArrived {
		comp !? { recent[recentPos] = comp.reduction; recentPos = (recentPos + 1) % recent.size };
	}

	// the most gain change of the last second, in dB, signed
	heroGain { ^recent.inject(0, { |a, b| if (b.abs > a.abs) { b } { a } }) }

	// a square plot: as wide as it is tall, give or take the label column
	prFit {
		var w = (view.bounds.height - 6).clip(200, 350).round;
		if ((w - view.bounds.width).abs > 2) { { if (view.isClosed.not) { view.fixedWidth = w } }.defer };
	}

	// ------------------------------------------------------------ geometry

	prMeasure {
		var b = view.bounds;
		bw = b.width.max(1);
		bh = b.height.max(1);
		plot = Rect(labelWidth, FxMeter.topInset, (bw - labelWidth - 8).max(10),
			(bh - FxMeter.topInset - FxMeter.bottomInset).max(10));
	}

	prX { |db| ^plot.left + db.linlin(floorDb, 6, 0, plot.width, \none) }
	prXToDb { |x| ^(x - plot.left).linlin(0, plot.width, floorDb, 6, \none) }
	prY { |db| ^FxMeter.dbToY(db, bh) }
	prDbPerPixel { ^66 / plot.height.max(1) }

	prNearLine { |y| ^comp.notNil and: { (y - this.prY(comp.threshold)).abs <= 8 } }

	// on the slope past the knee, within 10 px of the curve
	prOnSegment { |x, y|
		var db, half;
		if (comp.isNil or: { x < plot.left } or: { x > plot.right }) { ^false };
		db = this.prXToDb(x);
		half = comp.knee * 0.5;
		if (comp.mode == \up) {
			if (db >= (comp.threshold - half)) { ^false }
		} {
			if (db <= (comp.threshold + half)) { ^false }
		};
		^(y - this.prY(db + comp.staticGain(db))).abs <= 10
	}

	prFanAt { |x, y|
		fanRects.do { |pair| if (pair[1].containsPoint(x @ y)) { ^pair[0] } };
		^nil
	}

	prFanShown { ^inside or: { drag == \ratio } or: { drag == \ratioAny } or: { hot == \ratio } }

	// --------------------------------------------------------------- mouse

	prMouseDown { |x, y, mod, count, button|
		var r;
		if (comp.isNil or: { (button ? 0) != 0 }) { ^this };
		this.prMeasure;
		pointer = x @ y;
		r = if (this.prFanShown) { this.prFanAt(x, y) };
		if (r.notNil) { comp.ratio = r; this.prSayHint(x, y); ^this.refresh };
		if (mod.isCmd or: { mod.isCtrl } or: { mod.isAlt }) {
			drag = \ratioAny; dragY = y; dragFrom = comp.ratio; dragFine = mod.isShift;
			^this.refresh
		};
		if (this.prNearLine(y)) {
			if (count == 2) { comp.threshold = Compressor.specs[\threshold].default; drag = nil } {
				drag = \threshold; dragY = y; dragFrom = comp.threshold; dragFine = mod.isShift;
			};
			this.prSayHint(x, y);
			^this.refresh
		};
		if (this.prOnSegment(x, y)) {
			if (count == 2) { comp.ratio = Compressor.specs[\ratio].default; drag = nil } {
				drag = \ratio; dragY = y; dragFrom = comp.ratio; dragFine = mod.isShift;
			};
			this.prSayHint(x, y);
			^this.refresh
		};
	}

	prMouseMove { |x, y, mod|
		var fine = mod.isShift, f, dy;
		if (drag.isNil or: { comp.isNil }) { ^this };
		if (fine != dragFine) {
			dragY = y;
			dragFrom = if (drag == \threshold) { comp.threshold } { comp.ratio };
			dragFine = fine;
		};
		f = if (fine) { 0.1 } { 1 };
		dy = y - dragY;
		pointer = x @ y;
		switch (drag,
			\threshold, { comp.threshold = (dragFrom - (dy * this.prDbPerPixel * f)).round(0.1) },
			// the slope follows the pointer: down mode squashes harder as it goes
			// down, up mode lifts harder as it goes up
			\ratio, { comp.ratio = dragFrom * ratioPerPixel.pow(if (comp.mode == \up) { dy.neg } { dy } * f) },
			// from anywhere: up is more, as it always was
			\ratioAny, { comp.ratio = dragFrom * ratioPerPixel.pow(dy.neg * f) }
		);
		this.prSayHint(x, y);
		this.refresh;
	}

	prMouseUp { drag = nil; this.refresh }

	prWheel { |x, y, mod, yd|
		var step = if (mod.isShift) { 0.1 } { 0.5 };
		if (comp.isNil or: { yd == 0 }) { ^this };
		this.prMeasure;
		if (this.prNearLine(y)) {
			comp.threshold = (comp.threshold + (yd.sign * step)).round(0.1);
		} {
			comp.knee = (comp.knee + (yd.sign * step)).round(0.1);
		};
		this.prSayHint(x, y);
		this.refresh;
	}

	prHover { |x, y|
		var h, was = inside;
		this.prMeasure;
		pointer = x @ y;
		inside = true;
		h = case
		{ comp.isNil } { nil }
		{ this.prNearLine(y) } { \threshold }
		{ this.prOnSegment(x, y) } { \ratio }
		{ this.prFanAt(x, y).notNil } { \fan }
		{ true } { \knee };
		if (h != hover) { hover = h; hoverAction.value(this, h); this.refresh };
		if (was.not) { this.refresh };
		this.prSayHint(x, y);
		if ((Main.elapsedTime - lastPaint) >= 0.04) { this.refresh };
	}

	prLeave {
		pointer = nil;
		inside = false;
		if (hover.notNil) { hover = nil; hoverAction.value(this, nil) };
		this.refresh;
	}

	prSayHint { |x, y| hints !? { |bar| bar.string = this.hint(x, y) } }

	*thresholdHint { |comp|
		^"threshold % - drag up and down, double click: -20; % the gain comes %".format(
			FxPalette.format(comp.threshold, "dB", nil, 1),
			if (comp.mode == \up) { "under it" } { "over it" },
			if (comp.mode == \up) { "up" } { "down" })
	}

	hint { |x, y|
		var r;
		if (comp.isNil) { ^"the transfer curve - there is no compressor here yet" };
		this.prMeasure;
		if (drag == \ratio or: { drag == \ratioAny }) {
			^"ratio % - shift: fine".format(FxPalette.format(comp.ratio, ":1"))
		};
		^case
		{ this.prNearLine(y) or: { drag == \threshold } } { this.class.thresholdHint(comp) }
		{ this.prOnSegment(x, y) } {
			"ratio % - drag the slope past the bend, double click: 4:1 · or click 2, 4, 8, 20 on the fan"
				.format(FxPalette.format(comp.ratio, ":1"))
		}
		{ (r = this.prFanAt(x, y)).notNil } { "click for %".format(FxPalette.format(r, ":1")) }
		{ true } {
			"input along, output up · drag the orchid line: threshold · the slope: ratio · wheel: knee"
			" · cmd-drag: ratio"
		}
	}

	// ------------------------------------------------------------- drawing

	// a dashed line, by hand
	*dash { |p1, p2, on = 4, off = 3|
		var len = p1.dist(p2), d = 0, dir;
		if (len < 0.5) { ^this };
		dir = (p2 - p1) / len;
		while { d < len } {
			Pen.line(p1 + (dir * d), p1 + (dir * min(d + on, len)));
			d = d + on + off;
		};
	}

	// the threshold's value on a small orchid chip
	*chip { |str, x, y, off = false|
		var f = FxPalette.smallFont, w = str.bounds(f).width.ceil + 8, r = Rect(x - (w * 0.5), y - 7, w, 14);
		Pen.fillColor = FxPalette.fade(FxPalette.gain, if (off) { 0.5 } { 1 });
		Pen.addRoundedRect(r, 3, 3);
		Pen.fill;
		Pen.stringCenteredIn(str, r, f, FxPalette.bg);
	}

	prDraw {
		var w, h, up, off, ga, gainC, thr, half, f = FxPalette.smallFont, x0, x1, live, det, now, stat, p, q;
		this.prMeasure;
		w = bw; h = bh;
		lastPaint = Main.elapsedTime;
		Pen.fillColor = FxPalette.waveBg;
		Pen.fillRect(Rect(0, 0, w, h));
		// the grid: the meters' lines both ways
		Pen.width = 1;
		FxMeter.ticks.do { |db|
			var y = this.prY(db), x = this.prX(db);
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (db == 0) { 0.6 } { 0.35 });
			Pen.line(plot.left @ y, plot.right @ y);
			Pen.line(x @ plot.top, x @ plot.bottom);
			Pen.stroke;
			// the threshold's chip takes the column where it sits
			if (comp.isNil or: { (y - this.prY(comp.threshold)).abs > 10 }) {
				Pen.stringRightJustIn(db.asString, Rect(0, y - 6, labelWidth - 4, 12), f, FxPalette.dim);
			};
			Pen.stringCenteredIn(db.asString, Rect(x - 14, h - 14, 28, 12), f, FxPalette.dim);
		};
		Pen.stringRightJustIn("in", Rect(plot.right - 20, h - 14, 20, 12), f, FxPalette.waveDim);
		Pen.stringLeftJustIn("out", Rect(plot.left + 3, plot.top + 1, 24, 12), f, FxPalette.waveDim);
		if (comp.isNil) {
			Pen.stringLeftJustIn("curve", Rect(6, 2, w - 12, 16), f, FxPalette.dim);
			^this
		};
		up = comp.mode == \up;
		off = comp.enabled.not;
		ga = if (off) { 0.5 } { 1 };
		gainC = FxPalette.gain;
		thr = comp.threshold;
		half = comp.knee * 0.5;
		live = this.isLive;
		det = if (live) { comp.detLevel.max(1e-6).ampdb.clip(floorDb, 6) };

		Pen.use {
			Pen.addRect(plot);
			Pen.clip;
			// unity
			Pen.strokeColor = FxPalette.fade(FxPalette.dim, 0.6);
			Pen.width = 1;
			this.class.dash(this.prX(floorDb) @ this.prY(floorDb), this.prX(6) @ this.prY(6));
			Pen.stroke;
			// the knee, where the curve bends over
			x0 = this.prX(thr - half); x1 = this.prX(thr + half);
			Pen.fillColor = FxPalette.fade(gainC, 0.10 * ga);
			Pen.fillRect(Rect(x0, plot.top, (x1 - x0).max(1), plot.height));
			// the fan: what 2, 4, 8 and 20 to 1 would do from the bend
			fanRects = [];
			if (this.prFanShown) { this.prFan(thr, up, ga) };
			// the curve the server's computer makes - makeup left out
			Pen.width = 2;
			[[floorDb, det ? 6, 1], [det, 6, 0.7]].do { |seg|
				var from = seg[0], to = seg[1], n;
				if (from.notNil and: { to > from }) {
					n = ((this.prX(to) - this.prX(from)) / 2).ceil.asInteger.max(1);
					Pen.strokeColor = FxPalette.fade(gainC, seg[2] * ga);
					(n + 1).do { |i|
						var db = from + ((to - from) * i / n);
						var pt = this.prX(db) @ this.prY(db + comp.staticGain(db));
						if (i == 0) { Pen.moveTo(pt) } { Pen.lineTo(pt) };
					};
					Pen.stroke;
				};
			};
		};
		// the threshold, the line it shares with the history
		Pen.strokeColor = FxPalette.fade(gainC, ga);
		Pen.width = if ((hover == \threshold) or: { drag == \threshold } or: { hot == \threshold }) { 2.5 } { 1.5 };
		Pen.line(plot.left @ this.prY(thr), plot.right @ this.prY(thr));
		Pen.stroke;
		// where the detector is now: the static point, and the gain this moment
		if (live) {
			now = comp.reduction;
			stat = comp.staticGain(det);
			p = this.prX(det);
			Pen.fillColor = FxPalette.fade(gainC, 0.6 * ga);
			q = [this.prY(det), this.prY(det + now)];
			Pen.fillRect(Rect(p - 1.5, q.minItem, 3, (q[0] - q[1]).abs.max(1)));
			Pen.strokeColor = FxPalette.fade(gainC, ga);
			Pen.width = 1.5;
			Pen.strokeOval(Rect.aboutPoint(p @ this.prY(det + stat), 3, 3));
			Pen.fillColor = FxPalette.peak;
			Pen.fillOval(Rect.aboutPoint(p @ this.prY(det + now), 5, 5));
			Pen.fillColor = FxPalette.wave;
			Pen.fillOval(Rect.aboutPoint(p @ this.prY(det + now), 4, 4));
		};
		this.class.chip(FxPalette.fixed(thr, if (thr.frac.abs < 0.05) { 0 } { 1 }), labelWidth * 0.5 + 1,
			this.prY(thr), off);
		this.prCaption(w, h, up, off, live);
	}

	prFan { |thr, up, ga|
		var f = FxPalette.smallFont, lastY, pts;
		Pen.strokeColor = FxPalette.fade(FxPalette.gain, 0.3 * ga);
		Pen.width = 1;
		pts = fanRatios.collect { |r|
			var endIn = if (up) { floorDb } { 6 };
			var endOut = thr + ((endIn - thr) / r);
			var a = this.prX(thr) @ this.prY(thr), b = this.prX(endIn) @ this.prY(endOut);
			this.class.dash(a, b);
			[r, b]
		};
		Pen.stroke;
		// the labels at the plot's edge, kept 11 px apart
		pts.do { |pair|
			var r = pair[0], b = pair[1], y = b.y.clip(plot.top + 6, plot.bottom - 6), rect;
			// down: the steeper the ratio the lower its end; up: the higher
			if (lastY.notNil) { y = if (up) { min(y, lastY - 11) } { max(y, lastY + 11) } };
			lastY = y;
			rect = if (up) { Rect(plot.left + 2, y - 6, 18, 12) } { Rect(plot.right - 20, y - 6, 18, 12) };
			fanRects = fanRects.add([r, rect]);
			Pen.stringCenteredIn(r.asString, rect, f, FxPalette.fade(FxPalette.gain, 0.9 * ga));
		};
	}

	prCaption { |w, h, up, off, live|
		var f = FxPalette.smallFont, g = this.heroGain, str, sw;
		Pen.stringLeftJustIn("curve · " ++ comp.mode.asString, Rect(6, 2, 100, 16), f, FxPalette.text);
		str = case
		{ live.not } { "-" }
		{ g.abs < 0.05 } { "0.0 dB" }
		{ true } { FxPalette.format(g, "dB", nil, 1, true) };
		if (off) { str = str ++ " off" };
		sw = str.bounds(FxPalette.boldFont).width.ceil + 2;
		Pen.stringRightJustIn(str, Rect(w - sw - 6, 1, sw, 17), FxPalette.boldFont,
			FxPalette.fade(FxPalette.gain, if (off or: { live.not }) { 0.5 } { 1 }));
		Pen.stringRightJustIn("gain", Rect(w - sw - 44, 3, 34, 14), f, FxPalette.dim);
		if (up and: { comp.range >= 30 }) {
			// under the unity line at the right, where an up curve never goes
			Pen.stringRightJustIn("silence comes up by as much as the range",
				Rect(plot.left + 4, plot.bottom - 15, plot.width - 8, 12), f, FxPalette.dim);
		};
	}
}


// =====================================================================
// CompressorMeterView - in, the gain change, out: FxMeter with its gain bar
//
// It is its own unit for FxMeter, so levels that have stopped coming fall
// to nothing instead of standing at the last reply.
// =====================================================================

CompressorMeterView : FxMeter {
	var <comp;

	*new { |comp| ^super.new(nil).prInitComp(comp) }

	prInitComp { |c|
		this.unit = this;
		this.gain = { this.prGain };
		comp = c;
	}

	comp_ { |c| comp = c; this.refresh }

	isLive { ^comp.notNil and: { comp.meter } and: { comp.isAlive } }
	inLevel { ^if (this.isLive) { comp.inLevel } { 0 } }
	outLevel { ^if (this.isLive) { comp.outLevel } { 0 } }
	prGain { ^if (this.isLive) { comp.reduction } }
}


// =====================================================================
// CompressorSidechainSource - where the sidechain page's spectrum reads
//
// What ParaEQSpectrum asks an eq, answered for what the detector listens
// to, before its eq: the input (just before the compressor's own node, or
// at the end of a driven proxy's group - what leaves it, there being no
// way in), or the sidechain bus at the tail of the default group. A
// SuperDirtGUI orbit's busses are written inside SuperDirt's group, which
// sits before that tail. The graph's own sidechain (`sidechain = true`)
// has no bus to read: no picture, rather than a wrong one.
// =====================================================================

CompressorSidechainSource {
	var <>comp;

	*new { |comp| ^super.newCopyArgs(comp) }

	server { ^comp !? { comp.server ?? { comp.target.tryPerform(\server) } } }
	enabled { ^comp.notNil }

	analysisPlacement {
		var s;
		if (comp.isNil) { ^nil };
		if (comp.external) {
			if (comp.sidechain.isNil) { ^nil };
			s = this.server;
			^s !? { [s.defaultGroup, \addToTail] }
		};
		comp.synth !? { |sy| ^[sy, \addBefore] };
		^comp.analysisPlacement
	}

	analysisBusIndex {
		if (comp.isNil) { ^nil };
		^if (comp.external) { comp.sidechain } { comp.analysisBusIndex }
	}

	analysisChannels { ^comp !? { comp.analysisChannels } ? 2 }

	analysisAvailable { ^this.analysisPlacement.notNil and: { this.analysisBusIndex.notNil } }
}
