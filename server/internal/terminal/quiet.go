package terminal

import (
	"bytes"
	"strconv"
)

// quiet blanks the queries in output a client is shown after the fact: the
// sequences that ask the terminal for an answer, such as its version
// (CSI > q), its attributes (CSI c) or a mode's state (CSI ? 2026 $ p). The
// program that asked had its answer from the terminal attached then, or has
// stopped waiting for it, so a terminal answering again would type its
// answer into whatever reads the input now, often the shell's prompt. Each
// byte of a query becomes a NUL, which terminals ignore, so offsets don't
// change. A sequence p ends inside is left alone: it's still being written,
// and the client sees the rest of it live.
func quiet(p []byte) {
	for i := 0; i < len(p); i++ {
		if p[i] != 0x1b {
			continue
		}
		n, ask := sequence(p[i:])
		if ask {
			clear(p[i : i+n])
		}
		if n > 0 {
			i += n - 1
		}
	}
}

// sequence reads the escape sequence at the start of p: its length, and
// whether it's a query. The length is 0 when p ends first or something
// cancels it, so the scan goes on from the next byte.
func sequence(p []byte) (int, bool) {
	if len(p) < 2 {
		return 0, false
	}
	switch p[1] {
	case '[':
		j := 2
		for j < len(p) && p[j] >= 0x30 && p[j] <= 0x3f { // parameters, after any private marker (< = > ?)
			j++
		}
		k := j
		for k < len(p) && p[k] >= 0x20 && p[k] <= 0x2f { // intermediates
			k++
		}
		if k == len(p) || p[k] < 0x40 || p[k] > 0x7e {
			return 0, false
		}
		return k + 1, csiQuery(p[2:j], string(p[j:k]), p[k])
	case ']':
		end, n := stringEnd(p)
		return n, n > 0 && oscQuery(p[2:end])
	case 'P':
		j := 2
		for j < len(p) && p[j] >= 0x30 && p[j] <= 0x3f {
			j++
		}
		_, n := stringEnd(p)
		// DECRQSS (DCS $ q) and XTGETTCAP (DCS + q)
		return n, n > 0 && j+1 < n && (p[j] == '$' || p[j] == '+') && p[j+1] == 'q'
	case '_':
		_, n := stringEnd(p)
		// Kitty graphics, images and all: most commands answer, and which
		// don't depends on the transfer they belong to.
		return n, n > 0 && p[2] == 'G'
	case '^', 'X': // PM and SOS
		_, n := stringEnd(p)
		return n, false
	case 'Z': // DECID, an old DA1
		return 2, true
	}
	return 0, false
}

func csiQuery(params []byte, inter string, final byte) bool {
	key := inter + string(final)
	if len(params) > 0 && params[0] >= '<' {
		key = string(params[0]) + key
		params = params[1:]
	}
	switch key {
	case "c", ">c", "=c", // device attributes (DA1, DA2, DA3)
		"n", "?n", // status reports, the cursor's position among them
		">q",        // the terminal's name and version (XTVERSION)
		"?u",        // kitty keyboard flags
		"$p", "?$p", // a mode's state (DECRQM)
		"?m", "?S", "x", "*y", "$w", "$u", "&u", "#|", "'|": // xterm's rarer reports
		return true
	case "t": // window reports (XTWINOPS): its state, place, sizes and titles
		op, _, _ := bytes.Cut(params, []byte(";"))
		switch string(op) {
		case "11", "13", "14", "15", "16", "18", "19", "20", "21":
			return true
		}
	}
	return false
}

// oscQuery says whether an OSC asks for a colour (4, 5, 10–19) or the
// clipboard (52), with ? in place of the value.
func oscQuery(s []byte) bool {
	num, rest, _ := bytes.Cut(s, []byte(";"))
	n, err := strconv.Atoi(string(num))
	if err != nil || n != 4 && n != 5 && n != 52 && (n < 10 || n > 19) {
		return false
	}
	for f := range bytes.SplitSeq(rest, []byte(";")) {
		if string(f) == "?" {
			return true
		}
	}
	return false
}

// stringEnd finds the end of the string sequence (OSC, DCS, APC, PM, SOS) at
// the start of p: where its text ends, and its length with the BEL or ST
// that ends it. Both are 0 when p ends first or CAN, SUB or another escape
// sequence cancels it.
func stringEnd(p []byte) (int, int) {
	for j := 2; j < len(p); j++ {
		switch p[j] {
		case 0x07:
			return j, j + 1
		case 0x18, 0x1a:
			return 0, 0
		case 0x1b:
			if j+1 < len(p) && p[j+1] == '\\' {
				return j, j + 2
			}
			return 0, 0
		}
	}
	return 0, 0
}
