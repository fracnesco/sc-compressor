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
// Compressor is built on ParaEQ (../sc EQ) - it is an FxUnit, its
// sidechain eq is a ParaEQ, and its window is made of that repository's
// pieces - so ParaEQ has to be installed too. If it is sitting next to
// this folder, this script links it as well.

(
var link, src, dst, eqSrc, eqDst;

src = PathName(thisProcess.nowExecutingPath).pathOnly;
if (src.endsWith("/")) { src = src.drop(-1) };			// ln -s dislikes it
dst = Platform.userExtensionDir +/+ "Compressor";

eqSrc = PathName(src).parentPath +/+ "sc EQ";
if (eqSrc.endsWith("/")) { eqSrc = eqSrc.drop(-1) };
eqDst = Platform.userExtensionDir +/+ "ParaEQ";

link = { |from, to, what, done|
	case
	{ File.exists(to) } {
		("Compressor: % is already installed at %.".format(what, to)).postln;
		done.value;
	}
	{ File.exists(from).not } {
		("Compressor: % is not installed, and there is no copy of it at %."
			"\n  Compressor is an FxUnit and its sidechain eq is a ParaEQ,"
			"\n  so the class library will not compile without ParaEQ."
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

// ParaEQ first - without it the class library will not compile
link.value(eqSrc, eqDst, "ParaEQ", {
	link.value(src, dst, "Compressor", {
		"".postln;
		"Now recompile the class library: ctrl/cmd + shift + L".postln;
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
		"\n  post window for compile errors - a missing ParaEQ is the usual reason."
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
