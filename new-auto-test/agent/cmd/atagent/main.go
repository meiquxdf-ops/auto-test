// Command atagent is the new-auto-test execution agent: one static binary that
// keeps a TCP session to the server, runs dispatched shell commands and streams
// their logs back.
//
// Usage:
//
//	atagent [run] [flags]   connect to the server and serve dispatches
//	atagent status [flags]  print the local agent state
//	atagent version         print build information
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strings"

	"github.com/atest/atagent/internal/version"
)

func main() {
	if err := dispatch(os.Args[1:]); err != nil {
		fmt.Fprintf(os.Stderr, "atagent: %v\n", err)
		os.Exit(1)
	}
}

func dispatch(argv []string) error {
	cmd := "run"
	if len(argv) > 0 && !strings.HasPrefix(argv[0], "-") {
		cmd, argv = argv[0], argv[1:]
	}
	switch cmd {
	case "run":
		return cmdRun(argv)
	case "status":
		return cmdStatus(argv)
	case "version":
		return cmdVersion(argv)
	case "help":
		usage(os.Stdout)
		return nil
	default:
		usage(os.Stderr)
		return fmt.Errorf("unknown command %q", cmd)
	}
}

func cmdVersion(argv []string) error {
	fs := flag.NewFlagSet("atagent version", flag.ContinueOnError)
	asJSON := fs.Bool("json", false, "print build information as JSON")
	if err := fs.Parse(argv); err != nil {
		return err
	}
	if *asJSON {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(version.Current())
	}
	fmt.Println(version.Long())
	return nil
}

func usage(w *os.File) {
	fmt.Fprint(w, `atagent - new-auto-test execution agent

commands:
  run       connect to the server and serve dispatches (default)
  status    print the local agent state
  version   print build information

configuration precedence: defaults < /etc/atagent/config.yaml < environment < flags

environment:
  ATEST_SERVER      server address, default 127.0.0.1:9800
  ATEST_TAG         display tag for this machine
  ATEST_CONFIG      config file path
  ATEST_DATA_DIR    data directory, default /var/lib/atagent
  ATEST_CONCURRENCY 1..4 parallel executions, default 1

run `+"`atagent run -h`"+` or `+"`atagent status -h`"+` for the full flag list.
`)
}
