// Package version carries build identity for the agent binary.
package version

import (
	"fmt"
	"runtime"
)

// Overridable at build time:
//
//	go build -ldflags "-X github.com/atest/atagent/internal/version.Version=1.2.3 \
//	                   -X github.com/atest/atagent/internal/version.Commit=$(git rev-parse --short HEAD) \
//	                   -X github.com/atest/atagent/internal/version.BuildTime=$(date -u +%FT%TZ)"
var (
	Version   = "0.1.0"
	Commit    = "dev"
	BuildTime = "unknown"
)

// String is the value reported to the server in the hello frame.
func String() string {
	return fmt.Sprintf("%s+%s", Version, Commit)
}

// Long is the multi-line form printed by `atagent version`.
func Long() string {
	return fmt.Sprintf("atagent %s\ncommit:  %s\nbuilt:   %s\ngo:      %s\nplatform:%s/%s",
		Version, Commit, BuildTime, runtime.Version(), runtime.GOOS, runtime.GOARCH)
}

// Info is the machine readable form printed by `atagent version -json`.
type Info struct {
	Version   string `json:"version"`
	Commit    string `json:"commit"`
	BuildTime string `json:"buildTime"`
	Go        string `json:"go"`
	OS        string `json:"os"`
	Arch      string `json:"arch"`
}

// Current returns the build identity of this binary.
func Current() Info {
	return Info{
		Version:   Version,
		Commit:    Commit,
		BuildTime: BuildTime,
		Go:        runtime.Version(),
		OS:        runtime.GOOS,
		Arch:      runtime.GOARCH,
	}
}
