package terminal

import (
	"fmt"
	"strconv"
	"strings"
)

// trackedModes are the DEC private modes a replay restores. Programs set them
// once, when they start, so once the buffer has dropped those bytes a client
// starting afresh would draw a full-screen program on the wrong screen, or
// send it the wrong arrow keys or no mouse reports.
var trackedModes = [...]struct {
	n   int
	def bool
}{
	{1, false},    // application cursor keys
	{7, true},     // autowrap
	{25, true},    // cursor visible
	{1000, false}, // mouse reports
	{1002, false},
	{1003, false},
	{1004, false}, // focus reports
	{1006, false}, // SGR mouse encoding
	{2004, false}, // bracketed paste
}

const (
	ground = iota
	escape
	csi
	str    // OSC, DCS and the other string sequences, up to BEL or ST
	strEsc // an ESC inside a string: ST, or the start of a new sequence
)

// modes follows the terminal modes in a stream of output. Its zero value is
// a terminal's initial state, and it's a plain value, so a copy can be fed
// further without changing the original.
type modes struct {
	flip   [len(trackedModes)]bool // the mode differs from its default
	alt    int                     // 47, 1047 or 1049 while the alternate screen is on
	keypad bool                    // application keypad (ESC =)

	state int
	buf   [16]byte // a CSI's parameters
	n     int      // bytes in buf; more than len(buf) when it's one we don't track
}

func (m *modes) feed(p []byte) {
	for _, b := range p {
		m.step(b)
	}
}

func (m *modes) step(b byte) {
	switch m.state {
	case ground:
		if b == 0x1b {
			m.state = escape
		}
	case escape:
		m.state = ground
		switch b {
		case '[':
			m.state, m.n = csi, 0
		case ']', 'P', '_', '^', 'X':
			m.state = str
		case '=':
			m.keypad = true
		case '>':
			m.keypad = false
		case 'c': // full reset
			*m = modes{}
		case 0x1b:
			m.state = escape
		}
	case csi:
		switch {
		case b >= 0x30 && b <= 0x3f:
			if m.n < len(m.buf) {
				m.buf[m.n] = b
			}
			m.n++
		case b >= 0x20 && b <= 0x2f: // an intermediate byte: not a mode we track
			m.n = len(m.buf) + 1
		case b >= 0x40 && b <= 0x7e:
			if (b == 'h' || b == 'l') && m.n <= len(m.buf) {
				m.apply(string(m.buf[:m.n]), b == 'h')
			}
			m.state = ground
		case b == 0x1b:
			m.state = escape
		case b == 0x18 || b == 0x1a:
			m.state = ground
		}
	case str:
		switch b {
		case 0x07, 0x18, 0x1a:
			m.state = ground
		case 0x1b:
			m.state = strEsc
		}
	case strEsc:
		if b == '\\' {
			m.state = ground
		} else {
			m.state = escape
			m.step(b)
		}
	}
}

func (m *modes) apply(params string, on bool) {
	list, ok := strings.CutPrefix(params, "?")
	if !ok {
		return
	}
	for f := range strings.SplitSeq(list, ";") {
		n, err := strconv.Atoi(f)
		if err != nil {
			continue
		}
		switch n {
		case 47, 1047, 1049:
			if on {
				m.alt = n
			} else {
				m.alt = 0
			}
		default:
			for i, t := range trackedModes {
				if t.n == n {
					m.flip[i] = on != t.def
				}
			}
		}
	}
}

// restore is what puts a terminal in its initial state into these modes.
func (m *modes) restore() []byte {
	var b []byte
	if m.alt != 0 {
		b = fmt.Appendf(b, "\x1b[?%dh", m.alt)
	}
	for i, t := range trackedModes {
		if m.flip[i] {
			c := 'h'
			if t.def {
				c = 'l'
			}
			b = fmt.Appendf(b, "\x1b[?%d%c", t.n, c)
		}
	}
	if m.keypad {
		b = append(b, "\x1b="...)
	}
	return b
}
