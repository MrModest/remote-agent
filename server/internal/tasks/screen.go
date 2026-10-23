package tasks

import (
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

// screen turns a command's terminal output into the text a terminal would
// show: carriage returns and cursor moves redraw lines, as progress bars and
// spinners do, and escape sequences are dropped. It keeps the last maxLines
// lines, and the last link a hyperlink sequence (OSC 8) carried.
type screen struct {
	lines    [][]rune
	row, col int
	rows     int // the terminal's height, for absolute cursor moves

	state   int
	seq     []byte // the escape sequence being read
	partial []byte // the start of a UTF-8 character split across writes
	link    string
}

const (
	maxLines = 300
	maxWidth = 1000
)

const (
	ground = iota
	escape
	csi
	osc     // or another string sequence: DCS, SOS, PM, APC
	oscEsc  // ESC inside one, which may start its terminator
	charset // ESC ( and friends take one more byte
)

func newScreen(rows int) *screen { return &screen{rows: rows} }

func (s *screen) Write(p []byte) (int, error) {
	n := len(p)
	if len(s.partial) > 0 {
		p = append(s.partial, p...)
		s.partial = nil
	}
	for len(p) > 0 {
		if s.state == ground && p[0] >= 0x80 {
			if !utf8.FullRune(p) {
				s.partial = append([]byte{}, p...)
				break
			}
			r, size := utf8.DecodeRune(p)
			s.put(r)
			p = p[size:]
			continue
		}
		s.byte(p[0])
		p = p[1:]
	}
	return n, nil
}

func (s *screen) byte(b byte) {
	switch s.state {
	case ground:
		switch {
		case b == 0x1b:
			s.state, s.seq = escape, s.seq[:0]
		case b == '\r':
			s.col = 0
		case b == '\n':
			s.moveTo(s.row+1, 0)
		case b == '\b':
			s.col = max(0, s.col-1)
		case b == '\t':
			s.col = min(maxWidth-1, (s.col/8+1)*8)
		case b < 0x20 || b == 0x7f:
		default:
			s.put(rune(b))
		}
	case escape:
		switch b {
		case '[':
			s.state = csi
		case ']', 'P', 'X', '^', '_':
			s.state = osc
		case '(', ')', '*', '+', '-', '.', '/', '#', '%':
			s.state = charset
		default:
			s.state = ground
		}
	case charset:
		s.state = ground
	case csi:
		if b >= 0x40 && b <= 0x7e {
			s.csi(b)
			s.state = ground
		} else if len(s.seq) < 64 {
			s.seq = append(s.seq, b)
		}
	case osc:
		switch {
		case b == 0x07:
			s.osc()
		case b == 0x1b:
			s.state = oscEsc
		case len(s.seq) < 4096:
			s.seq = append(s.seq, b)
		}
	case oscEsc:
		if b == '\\' {
			s.osc()
		} else {
			s.state = osc
		}
	}
}

// osc ends a string sequence. A hyperlink's is "8;params;URL".
func (s *screen) osc() {
	s.state = ground
	if rest, ok := strings.CutPrefix(string(s.seq), "8;"); ok {
		if _, url, ok := strings.Cut(rest, ";"); ok && urlPattern.MatchString(url) && strings.Index(url, "http") == 0 {
			s.link = trimURL(urlPattern.FindString(url))
		}
	}
}

func (s *screen) csi(final byte) {
	params := string(s.seq)
	if params != "" && strings.ContainsAny(params[:1], "?<=>") || strings.ContainsAny(params, " !\"#$%&'()*+,-./") {
		return // private modes and the like
	}
	var ns []int
	for p := range strings.SplitSeq(params, ";") {
		n, _ := strconv.Atoi(p)
		ns = append(ns, n)
	}
	arg := func(i, def int) int {
		if i < len(ns) && ns[i] > 0 {
			return ns[i]
		}
		return def
	}
	switch final {
	case 'A':
		s.moveTo(s.row-arg(0, 1), s.col)
	case 'B':
		s.moveTo(s.row+arg(0, 1), s.col)
	case 'C':
		s.col = min(maxWidth-1, s.col+arg(0, 1))
	case 'D':
		s.col = max(0, s.col-arg(0, 1))
	case 'E':
		s.moveTo(s.row+arg(0, 1), 0)
	case 'F':
		s.moveTo(s.row-arg(0, 1), 0)
	case 'G':
		s.col = min(maxWidth-1, arg(0, 1)-1)
	case 'H', 'f':
		top := max(0, len(s.lines)-s.rows)
		s.moveTo(top+arg(0, 1)-1, min(maxWidth-1, arg(1, 1)-1))
	case 'K':
		s.eraseLine(arg(0, 0))
	case 'J':
		switch arg(0, 0) {
		case 0:
			s.eraseLine(0)
			if s.row+1 < len(s.lines) {
				s.lines = s.lines[:s.row+1]
			}
		case 2, 3:
			s.lines, s.row, s.col = nil, 0, 0
		}
	}
}

func (s *screen) eraseLine(mode int) {
	if s.row >= len(s.lines) {
		return
	}
	line := s.lines[s.row]
	switch mode {
	case 0:
		if s.col < len(line) {
			s.lines[s.row] = line[:s.col]
		}
	case 1:
		for i := 0; i <= s.col && i < len(line); i++ {
			line[i] = ' '
		}
	case 2:
		s.lines[s.row] = line[:0]
	}
}

// moveTo puts the cursor on row, adding blank lines down to it, and drops
// the first lines beyond maxLines.
func (s *screen) moveTo(row, col int) {
	s.row, s.col = max(0, row), col
	for len(s.lines) < s.row {
		s.lines = append(s.lines, nil)
	}
	if excess := s.row + 1 - maxLines; excess > 0 {
		s.lines = s.lines[min(excess, len(s.lines)):]
		s.row -= excess
	}
}

func (s *screen) put(r rune) {
	for s.row >= len(s.lines) {
		s.lines = append(s.lines, nil)
	}
	if s.col >= maxWidth {
		return
	}
	line := s.lines[s.row]
	for len(line) < s.col {
		line = append(line, ' ')
	}
	if s.col < len(line) {
		line[s.col] = r
	} else {
		line = append(line, r)
	}
	s.lines[s.row] = line
	s.col++
}

// Println adds a line of rad's own below the output.
func (s *screen) Println(text string) {
	if s.col > 0 || s.row < len(s.lines) && len(s.lines[s.row]) > 0 {
		s.moveTo(s.row+1, 0)
	}
	for _, line := range strings.Split(text, "\n") {
		for _, r := range line {
			s.put(r)
		}
		s.moveTo(s.row+1, 0)
	}
}

// Text is what the screen shows, without trailing blanks.
func (s *screen) Text() string {
	var b strings.Builder
	for _, line := range s.lines {
		b.WriteString(strings.TrimRight(string(line), " "))
		b.WriteByte('\n')
	}
	return strings.TrimRight(b.String(), "\n ")
}

var urlPattern = regexp.MustCompile(`https?://[^\s<>"'` + "`" + `]+`)

// URL is the last web address the screen shows, else the last hyperlink.
func (s *screen) URL() string {
	all := urlPattern.FindAllString(s.Text(), -1)
	if len(all) == 0 {
		return s.link
	}
	return trimURL(all[len(all)-1])
}

// trimURL drops punctuation that ends the sentence around a URL, and closing
// brackets it doesn't open.
func trimURL(u string) string {
	for u != "" {
		last := u[len(u)-1]
		switch {
		case strings.IndexByte(".,;:!?'\"", last) >= 0:
		case last == ')' && strings.Count(u, "(") < strings.Count(u, ")"):
		case last == ']' && strings.Count(u, "[") < strings.Count(u, "]"):
		default:
			return u
		}
		u = u[:len(u)-1]
	}
	return u
}
