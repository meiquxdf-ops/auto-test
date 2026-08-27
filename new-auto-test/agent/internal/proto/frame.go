// Package proto implements the new-auto-test agent wire protocol:
// `[4 byte big endian length N][N bytes UTF-8 JSON]`, single frame <= 1MiB.
package proto

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

const (
	// Version is the envelope version ("v" field).
	Version = 1
	// MaxFrame is the hard limit for a single frame payload.
	MaxFrame = 1 << 20
	// headerLen is the size of the big endian length prefix.
	headerLen = 4
)

// ErrFrameTooLarge is returned when a frame exceeds MaxFrame.
var ErrFrameTooLarge = errors.New("proto: frame exceeds 1MiB limit")

// FrameReader reads length prefixed frames from a stream.
type FrameReader struct {
	r   *bufio.Reader
	hdr [headerLen]byte
}

// NewFrameReader wraps r with the buffering needed for frame decoding.
func NewFrameReader(r io.Reader) *FrameReader {
	return &FrameReader{r: bufio.NewReaderSize(r, 64<<10)}
}

// ReadFrame returns the next payload. The returned slice is owned by the caller.
func (fr *FrameReader) ReadFrame() ([]byte, error) {
	if _, err := io.ReadFull(fr.r, fr.hdr[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(fr.hdr[:])
	if n == 0 {
		return []byte{}, nil
	}
	if n > MaxFrame {
		return nil, fmt.Errorf("%w: %d bytes", ErrFrameTooLarge, n)
	}
	payload := make([]byte, n)
	if _, err := io.ReadFull(fr.r, payload); err != nil {
		return nil, err
	}
	return payload, nil
}

// WriteFrame writes one length prefixed payload. Header and body go out in a
// single Write so a frame is never split across two syscalls.
func WriteFrame(w io.Writer, payload []byte) error {
	if len(payload) > MaxFrame {
		return fmt.Errorf("%w: %d bytes", ErrFrameTooLarge, len(payload))
	}
	buf := make([]byte, headerLen+len(payload))
	binary.BigEndian.PutUint32(buf[:headerLen], uint32(len(payload)))
	copy(buf[headerLen:], payload)
	_, err := w.Write(buf)
	return err
}
