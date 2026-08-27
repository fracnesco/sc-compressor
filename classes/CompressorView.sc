// =====================================================================
// CompressorCurveView - the transfer curve, and where the signal is on it
//
// Input level along the bottom, output level up the side, both in dB
// down to -60. The straight diagonal is what leaves as it came in; the
// drawn curve is what the computer makes of it - bent over at the
// threshold in downward mode, lifted under it in upward mode - with the
// knee shaded so its width can be seen. A dot rides the curve at the
// level the detector is reading now, and a bar under the diagonal shows
// how far down (or up) the gain is at this moment.
//
//   drag                  the threshold, left and right
//   cmd + drag up / down  the ratio - relative, so it never jumps
//   the wheel             the knee
//
// A CompressorHistoryView, below, is the same story over the last few
// seconds: the input and output levels as traces, and the gain change
// hanging from the top - which is what a compressor's display has
// looked like since the hardware ones, and still the quickest way to
// see whether the attack is catching the transients.
//
// CompressorMeterView is three bars: input, output, and gain change.
// =====================================================================

CompressorCurveView {
	classvar <>floorDb = -60;
	classvar <>ratioPerPixel = 1.02;

	var <view, <comp, <>action;
	var drag, dragY, dragRatio;
	var bw = 1, bh = 1;

	*new { |comp, parent, bounds| ^super.new.init(comp, parent, bounds) }

	init { |argComp, parent, bounds|
		comp = argComp;
		view = UserView(parent, bounds ? Rect(0, 0, 220, 160));
		view.minHeight_(120).minWidth_(140);
		view.background_(FxPalette.waveBg);
		view.drawFunc = { |v| this.prDraw(v) };
		view.mouseDownAction = { |v, x, y, mod| this.prMeasure; this.prMouseDown(x, y, mod) };
		view.mouseMoveAction = { |v, x, y, mod| this.prApply(x, y, mod) };
		view.mouseUpAction = { drag = nil };
		view.mouseWheelAction = { |v, x, y, mod, xd, yd| this.prWheel(yd) };
		^this
	}

	comp_ { |c| comp = c; this.refresh }

	refresh { view !? (_.refresh) }

	// ------------------------------------------------------------ geometry

	prMeasure {
		var b = view.bounds;
		bw = b.width.max(1);
		bh = b.height.max(1);
	}

	// the output axis goes up to the range in upward mode, so a lift can
	// be seen, and to 0 dB otherwise
	prTop { ^if (comp.notNil and: { comp.mode == \up }) { comp.range.min(24) } { 0 } }

	prDbToX { |db| ^db.linlin(floorDb, 0, 0, bw, \none) }
	prXToDb { |x| ^x.linlin(0, bw, floorDb, 0, \none) }
	prDbToY { |db| ^db.linlin(floorDb, this.prTop, bh, 0, \none) }

	// --------------------------------------------------------------- mouse

	prMouseDown { |x, y, mod|
		if (comp.isNil) { ^this };
		if (mod.isCmd or: { mod.isCtrl } or: { mod.isAlt }) {
			drag = \ratio;
			dragY = y;
			dragRatio = comp.ratio;
			^this
		};
		drag = \threshold;
		this.prApply(x, y, mod);
	}

	prApply { |x, y, mod|
		if (drag.isNil or: { comp.isNil }) { ^this };
		if (drag == \ratio) {
			comp.ratio = dragRatio * ratioPerPixel.pow(dragY - y);
			^this.refresh
		};
		comp.threshold = this.prXToDb(x).round(if (mod.isShift) { 1 } { 0.1 });
		this.refresh;
	}

	prWheel { |yd|
		if (comp.isNil) { ^this };
		comp.knee = comp.knee + yd.clip(-3, 3) * 0.5;
		this.refresh;
	}

	// ------------------------------------------------------------- drawing

	prDraw { |v|
		var w, h, n = 64, thr, half, inDb, y0, live, gr;
		this.prMeasure;
		w = bw; h = bh;
		Pen.fillColor = FxPalette.waveBg;
		Pen.fillRect(Rect(0, 0, w, h));
		if (comp.isNil) { ^this };

		// the grid, every 12 dB
		Pen.width = 1;
		Pen.strokeColor = FxPalette.waveDim.copy.alpha_(0.35);
		(floorDb + 12).forBy(this.prTop - 1, 12) { |db|
			Pen.line(Point(this.prDbToX(db), 0), Point(this.prDbToX(db), h));
			Pen.line(Point(0, this.prDbToY(db)), Point(w, this.prDbToY(db)));
		};
		Pen.stroke;

		// the knee: the region the curve bends over
		thr = comp.threshold;
		half = comp.knee * 0.5;
		Pen.fillColor = FxPalette.accent.copy.alpha_(0.08);
		Pen.fillRect(Rect(this.prDbToX(thr - half), 0,
			(this.prDbToX(thr + half) - this.prDbToX(thr - half)).max(1), h));

		// unity, dashed by hand
		Pen.strokeColor = FxPalette.dim.copy.alpha_(0.6);
		(0, 6 .. w).do { |x|
			Pen.line(Point(x, this.prDbToY(this.prXToDb(x))),
				Point((x + 3).min(w), this.prDbToY(this.prXToDb((x + 3).min(w)))));
		};
		Pen.stroke;

		// the threshold
		Pen.strokeColor = FxPalette.accent.copy.alpha_(0.7);
		Pen.line(Point(this.prDbToX(thr), 0), Point(this.prDbToX(thr), h));
		Pen.stroke;

		// the curve itself: in + gain, one point every few pixels
		Pen.strokeColor = FxPalette.text;
		Pen.width = 1.5;
		n.do { |i|
			var x = i * w / (n - 1);
			var db = this.prXToDb(x);
			var out = db + comp.staticGain(db);
			var p = Point(x, this.prDbToY(out).clip(-2, h + 2));
			if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
		};
		Pen.stroke;

		// where the detector is now, on the curve, and how far the gain is
		// from 0 dB at this moment
		if (comp.meter and: { comp.isAlive }) {
			inDb = comp.detLevel.max(1e-6).ampdb.clip(floorDb, 0);
			gr = comp.reduction;
			live = Point(this.prDbToX(inDb), this.prDbToY(inDb + comp.staticGain(inDb)));
			Pen.fillColor = FxPalette.accent;
			Pen.fillOval(Rect(live.x - 4, live.y - 4, 8, 8));
			y0 = this.prDbToY(inDb);
			Pen.fillColor = FxPalette.accent.copy.alpha_(0.5);
			Pen.fillRect(Rect(live.x - 1.5, y0.min(this.prDbToY(inDb + gr)),
				3, (y0 - this.prDbToY(inDb + gr)).abs.max(1)));
		};

		this.prReadout(w, h);
		Pen.strokeColor = FxPalette.panel;
		Pen.width = 1;
		Pen.strokeRect(Rect(0.5, 0.5, w - 1, h - 1));
	}

	prReadout { |w, h|
		var str = "% dB  %:1  knee % dB".format(
			comp.threshold.round(0.1), comp.ratio.round(0.01), comp.knee.round(0.1));
		if (comp.mode == \up) { str = "up   " ++ str };
		if (comp.enabled.not) { str = str ++ "   (off)" };
		Pen.fillColor = FxPalette.waveBg.copy.alpha_(0.72);
		Pen.fillRect(Rect(0, 0, w, 14));
		Pen.stringLeftJustIn(" " ++ str, Rect(3, 1, w - 6, 12), FxPalette.smallFont,
			if (comp.enabled) { FxPalette.accent } { FxPalette.dim });
		Pen.stringLeftJustIn("in", Rect(w - 16, h - 13, 14, 11), FxPalette.smallFont, FxPalette.waveDim);
		Pen.stringLeftJustIn("out", Rect(3, h - 13, 20, 11), FxPalette.smallFont, FxPalette.waveDim);
	}
}


// =====================================================================
// CompressorHistoryView - the last few seconds of level and gain change
// =====================================================================

CompressorHistoryView {
	classvar <>floorDb = -60;
	classvar <>seconds = 8;
	classvar <>grScaleMin = 6;			// the gain change axis never shows less than this

	var <view, <comp, <>action;
	var ins, outs, grs, size, pos = 0, filled = 0, ctrl, lastTaken = 0;
	var bw = 1, bh = 1;

	*new { |comp, parent, bounds| ^super.new.init(comp, parent, bounds) }

	init { |argComp, parent, bounds|
		size = (seconds * Compressor.meterRate).asInteger.max(8);
		this.prClear;
		view = UserView(parent, bounds ? Rect(0, 0, 320, 160));
		view.minHeight_(120).minWidth_(160);
		view.background_(FxPalette.waveBg);
		view.drawFunc = { |v| this.prDraw(v) };
		view.mouseDownAction = { this.prClear; this.refresh };
		this.comp_(argComp);
		^this
	}

	comp_ { |c|
		ctrl !? (_.remove);
		ctrl = nil;
		comp = c;
		this.prClear;
		comp !? {
			ctrl = SimpleController(comp).put(\levels, { this.prTake });
		};
		this.refresh;
	}

	remove {
		ctrl !? (_.remove);
		ctrl = nil;
	}

	refresh { view !? { if (view.isClosed.not) { view.refresh } } }

	prClear {
		ins = Array.fill(size, { floorDb });
		outs = Array.fill(size, { floorDb });
		grs = Array.fill(size, { 0 });
		pos = 0;
		filled = 0;
	}

	// one reply into the ring: the levels in dB, the gain change as the
	// most it moved during that reply
	prTake {
		ins[pos] = comp.inLevel.max(1e-6).ampdb.max(floorDb);
		outs[pos] = comp.outLevel.max(1e-6).ampdb.max(floorDb);
		grs[pos] = comp.reduction;
		pos = (pos + 1) % size;
		filled = (filled + 1).min(size);
		{ this.refresh }.defer;
	}

	prDraw { |v|
		var w, h, grScale, grH, xStep, start, path;
		var b = v.bounds;
		w = b.width.max(1); h = b.height.max(1);
		Pen.fillColor = FxPalette.waveBg;
		Pen.fillRect(Rect(0, 0, w, h));
		if (comp.isNil) { ^this };

		// the level grid
		Pen.width = 1;
		Pen.strokeColor = FxPalette.waveDim.copy.alpha_(0.35);
		[-48, -36, -24, -12].do { |db|
			var y = db.linlin(floorDb, 0, h, 0, \none);
			Pen.line(Point(0, y), Point(w, y));
		};
		Pen.stroke;

		xStep = w / (size - 1);
		start = if (filled < size) { 0 } { pos };

		// the input, filled and dim; the output as a line over it
		Pen.fillColor = FxPalette.wave.copy.alpha_(0.18);
		Pen.moveTo(Point(0, h));
		filled.do { |i|
			var j = (start + i) % size;
			Pen.lineTo(Point(i * xStep, ins[j].linlin(floorDb, 0, h, 0, \none)));
		};
		Pen.lineTo(Point((filled - 1).max(0) * xStep, h));
		Pen.fill;

		Pen.strokeColor = FxPalette.text.copy.alpha_(0.8);
		filled.do { |i|
			var j = (start + i) % size;
			var p = Point(i * xStep, outs[j].linlin(floorDb, 0, h, 0, \none));
			if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
		};
		Pen.stroke;

		// the gain change, hanging from the top on a scale of its own that
		// grows to fit the most it has done
		grScale = grs.abs.maxItem.max(grScaleMin).roundUp(6);
		grH = h * 0.5;
		Pen.fillColor = FxPalette.accent.copy.alpha_(0.35);
		Pen.moveTo(Point(0, 0));
		filled.do { |i|
			var j = (start + i) % size;
			Pen.lineTo(Point(i * xStep, grs[j].abs.linlin(0, grScale, 0, grH, \none)));
		};
		Pen.lineTo(Point((filled - 1).max(0) * xStep, 0));
		Pen.fill;
		Pen.strokeColor = FxPalette.accent;
		filled.do { |i|
			var j = (start + i) % size;
			var p = Point(i * xStep, grs[j].abs.linlin(0, grScale, 0, grH, \none));
			if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
		};
		Pen.stroke;

		Pen.stringLeftJustIn("gain % dB".format(grScale.neg), Rect(3, grH - 12, 70, 11),
			FxPalette.smallFont, FxPalette.accent.copy.alpha_(0.7));
		Pen.stringLeftJustIn("in / out, % s".format(seconds), Rect(3, h - 13, 90, 11),
			FxPalette.smallFont, FxPalette.waveDim);
		Pen.stringRightJustIn(
			if (comp.meter and: { comp.isAlive }) {
				"% dB".format(comp.reduction.round(0.1))
			} { "" },
			Rect(w - 60, 2, 56, 12), FxPalette.boldFont, FxPalette.accent);

		Pen.strokeColor = FxPalette.panel;
		Pen.width = 1;
		Pen.strokeRect(Rect(0.5, 0.5, w - 1, h - 1));
	}
}


// =====================================================================
// CompressorMeterView - in, out, and the gain change, as bars
// =====================================================================

CompressorMeterView {
	classvar <>floorDb = -60;

	var <view, <comp;
	var holdIn = 0, holdOut = 0, holdGr = 0, holdAge = 0;

	*new { |comp, parent, width = 54| ^super.new.init(comp, parent, width) }

	init { |argComp, parent, width|
		comp = argComp;
		view = UserView(parent).fixedWidth_(width).minHeight_(120);
		view.background_(FxPalette.waveBg);
		view.drawFunc = { |v| this.prDraw(v) };
		view.mouseDownAction = { holdIn = 0; holdOut = 0; holdGr = 0; this.refresh };
		^this
	}

	comp_ { |c| comp = c; this.refresh }

	refresh { view !? { if (view.isClosed.not) { view.refresh } } }

	prDraw { |v|
		var w = v.bounds.width, h = v.bounds.height;
		var barW = ((w - 8) / 3).max(4), top = 14, bottom = h - 14, span;
		var alive, inDb, outDb, gr, grScale, labels, values;
		Pen.fillColor = FxPalette.waveBg;
		Pen.fillRect(Rect(0, 0, w, h));
		if (comp.isNil) { ^this };
		span = (bottom - top).max(1);
		alive = comp.meter and: { comp.isAlive };
		inDb = if (alive) { comp.inLevel.max(1e-6).ampdb } { floorDb };
		outDb = if (alive) { comp.outLevel.max(1e-6).ampdb } { floorDb };
		gr = if (alive) { comp.reduction } { 0 };
		// the hold marks fall back on their own
		holdAge = holdAge + 1;
		if (inDb > holdIn or: { holdAge > 40 }) { holdIn = inDb };
		if (outDb > holdOut or: { holdAge > 40 }) { holdOut = outDb };
		if (gr.abs > holdGr or: { holdAge > 40 }) { holdGr = gr.abs };
		if (holdAge > 40) { holdAge = 0 };
		grScale = holdGr.max(6).roundUp(6);

		labels = ["in", "out", "gr"];
		values = [inDb, outDb, gr];
		3.do { |i|
			var x = 2 + (i * (barW + 2)), y, hh;
			Pen.fillColor = FxPalette.panel.copy.alpha_(0.5);
			Pen.fillRect(Rect(x, top, barW, span));
			if (i < 2) {
				hh = values[i].linlin(floorDb, 0, 0, span, \none);
				Pen.fillColor = if (values[i] >= -0.1) { FxPalette.clip } { FxPalette.wave };
				Pen.fillRect(Rect(x, bottom - hh, barW, hh));
				y = bottom - [holdIn, holdOut][i].linlin(floorDb, 0, 0, span, \none);
				Pen.fillColor = FxPalette.peak;
				Pen.fillRect(Rect(x, y.clip(top, bottom - 1), barW, 1));
			} {
				// the gain change hangs from the top, up or down alike
				hh = gr.abs.linlin(0, grScale, 0, span, \none);
				Pen.fillColor = FxPalette.accent;
				Pen.fillRect(Rect(x, top, barW, hh));
				y = top + holdGr.linlin(0, grScale, 0, span, \none);
				Pen.fillColor = FxPalette.peak;
				Pen.fillRect(Rect(x, y.clip(top, bottom - 1), barW, 1));
			};
			Pen.stringCenteredIn(labels[i], Rect(x - 2, bottom + 1, barW + 4, 12),
				FxPalette.smallFont, FxPalette.dim);
			Pen.stringCenteredIn(
				if (alive) { values[i].round(1).asInteger.asString } { "-" },
				Rect(x - 4, 1, barW + 8, 12), FxPalette.smallFont,
				if (i == 2) { FxPalette.accent } { FxPalette.text });
		};
	}
}
