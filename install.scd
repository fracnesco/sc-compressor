// Symlink this repository into the SuperCollider extensions folder.
// Evaluate the block below, then recompile the class library
// (Language -> Recompile Class Library, or ctrl/cmd + shift + L).
//
// This is the route for a clone you want to keep editing. If you would
// rather have it installed and updated for you, do not use this file -
// install it as a quark instead, from a fresh sclang:
//
//   Quarks.install("https://github.com/fracnesco/sc-compressor.git");
//   thisProcess.recompile;
//
// One or the other, not both: two copies on the class path is a
// duplicate class error at compile time.
//
// ln -s is macOS and Linux. On Windows, use the quark, or copy this
// folder into Platform.userExtensionDir by hand - .openOS opens it.
//
// Compressor is an FxUnit, built on sc fx kit (../sc fx kit), and its
// sidechain eq is a ParaEQ (../sc EQ), so both have to be installed too. If
// they are sitting next to this folder, this script links them as well.
// Until November 2026 FxUnit lived in sc EQ: update both if you have old ones.

(
var link, src, dst, eqSrc, eqDst, kitSrc;

src = PathName(thisProcess.nowExecutingPath).pathOnly;
if (src.endsWith("/")) { src = src.drop(-1) };			// ln -s dislikes it
dst = Platform.userExtensionDir +/+ "Compressor";

eqSrc = PathName(src).parentPath +/+ "sc EQ";
if (eqSrc.endsWith("/")) { eqSrc = eqSrc.drop(-1) };
eqDst = Platform.userExtensionDir +/+ "ParaEQ";
kitSrc = PathName(src).parentPath +/+ "sc fx kit";
if (kitSrc.endsWith("/")) { kitSrc = kitSrc.drop(-1) };

// an sc EQ from before the framework moved holds it a second time
[eqSrc, eqDst].do { |dir|
	if (File.exists(dir +/+ "classes/FxUnit.sc")) {
		("Compressor: % is an older sc EQ that still holds FxUnit, which lives in"
			"\n  sc fx kit now. Update it (git pull) or the class library will not compile.".format(dir)).warn;
	};
};

link = { |from, to, what, done|
	case
	{ File.exists(to) } {
		("Compressor: % is already installed at %.".format(what, to)).postln;
		done.value;
	}
	{ File.exists(from).not } {
		("Compressor: % is not installed, and there is no copy of it at %."
			"\n  Compressor is an FxUnit (sc fx kit) and its sidechain eq is a"
			"\n  ParaEQ (sc EQ): it will not work without both."
			"\n  Install it first, then run this again."
			.format(what, from)).warn;
	}
	{ true } {
		File.mkdir(Platform.userExtensionDir);
		("ln -s" + from.shellQuote + to.shellQuote).unixCmd({ |exit|
			if (exit == 0) {
				"Compressor: linked % -> %".format(what, to).postln;
				done.value;
			} {
				"Compressor: linking % failed with exit code %".format(what, exit).warn;
			};
		});
	};
};

// what it is built on first
link.value(kitSrc, Platform.userExtensionDir +/+ "FxKit", "FxKit", {
	link.value(eqSrc, eqDst, "ParaEQ", {
		link.value(src, dst, "Compressor", {
			"".postln;
			"Now recompile the class library: ctrl/cmd + shift + L".postln;
		});
	});
});
)


// ---------------------------------------------------------------- verify
(
var missing = [\Compressor, \CompressorPanel, \CompressorGUI,
	\CompressorCurveView, \CompressorHistoryView, \CompressorMeterView]
	.select { |sym| sym.asClass.isNil };

if (missing.notEmpty) {
	("Compressor is not compiled - % is missing."
		"\n  Recompile the class library, ctrl/cmd + shift + L, and check the"
		"\n  post window for compile errors - a missing ParaEQ or FxKit is the usual reason."
		.format(missing.join(", "))).warn;
} {
	"Compressor is installed.".postln;
	"  presets: % - %".format(Compressor.presetNames.size, Compressor.presetNames.join(", ")).postln;
	"  in a graph:  Compressor.ar(sig, \\comp)      then  Compressor(Ndef(\\x), \\comp).gui".postln;
	"  on a bus:    Compressor.onBus(0, 2).enabled_(true).gui".postln;
	"  the master:  FxChain.master.compressor".postln;
	"  help:        Compressor, then cmd+d".postln;
};
)


// ------------------------------------------------------------- uninstall
(
var link = Platform.userExtensionDir +/+ "Compressor";
case
{ File.type(link) == \symlink } {
	File.delete(link);
	("Removed %."
		"\n  Recompile the class library to finish.".format(link)).postln;
}
{ File.type(link) == \not_found } {
	"Nothing to remove at %.".format(link).postln;
}
{ true } {
	"% is not a link, leaving it alone.".format(link).warn;
};
)
