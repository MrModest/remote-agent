package terminal

import "bytes"

// ringSize is how much recent output a shell keeps for clients that attach.
const ringSize = 256 << 10

// ring keeps the last ringSize bytes of a shell's output. Offsets count every
// byte the shell has written, so a client can say where it left off.
type ring struct {
	data []byte // output from offset base; up to twice ringSize before it's trimmed
	base uint64

	modes modes  // the terminal modes in effect at offset fed
	fed   uint64 // modes has seen the output before this offset
}

func (r *ring) end() uint64 { return r.base + uint64(len(r.data)) }

// start is the offset of the oldest byte kept.
func (r *ring) start() uint64 {
	if e := r.end(); e-r.base > ringSize {
		return e - ringSize
	}
	return r.base
}

func (r *ring) append(p []byte) {
	r.data = append(r.data, p...)
	if len(r.data) > 2*ringSize {
		r.advance()
		s := r.start()
		r.data = append(make([]byte, 0, 2*ringSize+len(p)), r.data[s-r.base:]...)
		r.base = s
	}
}

// advance brings modes up to the start of the ring.
func (r *ring) advance() {
	if s := r.start(); r.fed < s {
		r.modes.feed(r.data[r.fed-r.base : s-r.base])
		r.fed = s
	}
}

// slice copies the output in [from, to).
func (r *ring) slice(from, to uint64) []byte {
	return bytes.Clone(r.data[from-r.base : to-r.base])
}

// fresh is where a client that starts afresh begins: an offset at a clean
// boundary near the start of the ring, and the sequences that restore the
// terminal modes in effect there, to send before the output from it.
func (r *ring) fresh() (uint64, []byte) {
	s := r.start()
	if s == 0 {
		return 0, nil
	}
	r.advance()
	data := r.data[s-r.base:]
	cut := cleanStart(data)
	m := r.modes
	m.feed(data[:cut])
	return s + uint64(cut), m.restore()
}

// cleanStart is where a replay that would start at data[0] begins instead:
// after the first newline in its first 4 KiB, else at an escape, else past
// any UTF-8 continuation bytes, so it doesn't start inside a line, an escape
// sequence or a character.
func cleanStart(data []byte) int {
	head := data[:min(len(data), 4<<10)]
	if i := bytes.IndexByte(head, '\n'); i >= 0 {
		return i + 1
	}
	if i := bytes.IndexByte(head, 0x1b); i >= 0 {
		return i
	}
	i := 0
	for i < len(head) && head[i]&0xc0 == 0x80 {
		i++
	}
	return i
}
