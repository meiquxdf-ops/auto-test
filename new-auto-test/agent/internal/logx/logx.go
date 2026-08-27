// Package logx is a tiny leveled logger for the agent's own diagnostics.
// Execution output never goes here - that belongs to the journal.
package logx

import (
	"fmt"
	"io"
	"log"
	"strings"
	"sync"
)

// Level ranks a message.
type Level int

// Levels in increasing severity.
const (
	Debug Level = iota
	Info
	Warn
	Error
)

// ParseLevel maps a name onto a Level, defaulting to Info.
func ParseLevel(s string) Level {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "debug":
		return Debug
	case "warn", "warning":
		return Warn
	case "error":
		return Error
	default:
		return Info
	}
}

func (l Level) String() string {
	switch l {
	case Debug:
		return "DEBUG"
	case Warn:
		return "WARN"
	case Error:
		return "ERROR"
	default:
		return "INFO"
	}
}

// Logger writes leveled lines to one destination.
type Logger struct {
	mu    sync.Mutex
	level Level
	out   *log.Logger
}

// New builds a logger; systemd adds its own timestamps, but keeping ours makes
// the log usable when it is redirected to a plain file.
func New(level string, w io.Writer) *Logger {
	return &Logger{level: ParseLevel(level), out: log.New(w, "", log.LstdFlags|log.Lmicroseconds)}
}

// Debugf logs at debug level.
func (l *Logger) Debugf(format string, args ...any) { l.logf(Debug, format, args...) }

// Infof logs at info level.
func (l *Logger) Infof(format string, args ...any) { l.logf(Info, format, args...) }

// Warnf logs at warn level.
func (l *Logger) Warnf(format string, args ...any) { l.logf(Warn, format, args...) }

// Errorf logs at error level.
func (l *Logger) Errorf(format string, args ...any) { l.logf(Error, format, args...) }

func (l *Logger) logf(level Level, format string, args ...any) {
	if level < l.level {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	l.out.Printf("%-5s %s", level.String(), fmt.Sprintf(format, args...))
}
