package config

import (
	"bufio"
	"bytes"
	"fmt"
	"strconv"
	"strings"
)

// value is one parsed YAML node: either a scalar, a string list or a nested
// map of scalars. That is the whole grammar atagent's config needs, which is
// why the agent ships without a YAML dependency.
type value struct {
	scalar string
	list   []string
	dict   map[string]string
	isDict bool
	isList bool
}

// parseYAML understands the flat subset of YAML used by config.yaml:
//
//	server: 127.0.0.1:9800     # scalar, inline comments allowed
//	env:                       # nested one level deep
//	  CI: "true"
//	aliases:                   # list of scalars
//	  - build-01
//
// Keys are normalised (lowercased, `-`/`_` removed) so server, data_dir and
// dataDir all resolve to the same option.
func parseYAML(data []byte) (map[string]value, error) {
	out := make(map[string]value)
	sc := bufio.NewScanner(bytes.NewReader(data))
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)

	var (
		lineNo     int
		parentKey  string
		parentInd  int
		parentDict map[string]string
		parentList []string
	)

	flushParent := func() {
		if parentKey == "" {
			return
		}
		v := value{}
		switch {
		case len(parentDict) > 0:
			v.dict, v.isDict = parentDict, true
		case len(parentList) > 0:
			v.list, v.isList = parentList, true
		default:
			// `key:` with nothing under it means "empty", keep it as such.
			v.dict, v.isDict = map[string]string{}, true
		}
		out[parentKey] = v
		parentKey, parentDict, parentList = "", nil, nil
	}

	for sc.Scan() {
		lineNo++
		raw := strings.TrimRight(expandTabs(sc.Text()), " \t")
		if strings.TrimSpace(raw) == "" || strings.HasPrefix(strings.TrimSpace(raw), "#") {
			continue
		}
		if strings.TrimSpace(raw) == "---" {
			continue
		}
		indent := len(raw) - len(strings.TrimLeft(raw, " "))
		line := strings.TrimSpace(raw)

		if parentKey != "" && indent > parentInd {
			if strings.HasPrefix(line, "- ") || line == "-" {
				parentList = append(parentList, unquote(stripComment(strings.TrimSpace(strings.TrimPrefix(line, "-")))))
				continue
			}
			k, v, ok := strings.Cut(line, ":")
			if !ok {
				return nil, fmt.Errorf("line %d: expected `key: value`, got %q", lineNo, line)
			}
			if parentDict == nil {
				parentDict = make(map[string]string)
			}
			parentDict[strings.TrimSpace(unquote(k))] = unquote(stripComment(strings.TrimSpace(v)))
			continue
		}
		flushParent()

		if strings.HasPrefix(line, "- ") {
			return nil, fmt.Errorf("line %d: unexpected list item at top level", lineNo)
		}
		k, v, ok := strings.Cut(line, ":")
		if !ok {
			return nil, fmt.Errorf("line %d: expected `key: value`, got %q", lineNo, line)
		}
		key := normalizeKey(k)
		if key == "" {
			return nil, fmt.Errorf("line %d: empty key", lineNo)
		}
		rest := stripComment(strings.TrimSpace(v))
		switch rest {
		case "":
			parentKey, parentInd = key, indent
		case "{}", "[]", "null", "~":
			out[key] = value{isDict: rest == "{}", dict: map[string]string{}}
		default:
			out[key] = value{scalar: unquote(rest)}
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	flushParent()
	return out, nil
}

func expandTabs(s string) string { return strings.ReplaceAll(s, "\t", "  ") }

func normalizeKey(s string) string {
	s = strings.ToLower(strings.TrimSpace(unquote(s)))
	s = strings.ReplaceAll(s, "_", "")
	s = strings.ReplaceAll(s, "-", "")
	return s
}

// stripComment removes a trailing `# ...` comment that is not inside quotes.
func stripComment(s string) string {
	var quote byte
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case quote != 0:
			if c == quote {
				quote = 0
			}
		case c == '\'' || c == '"':
			quote = c
		case c == '#' && (i == 0 || s[i-1] == ' '):
			return strings.TrimSpace(s[:i])
		}
	}
	return strings.TrimSpace(s)
}

func unquote(s string) string {
	s = strings.TrimSpace(s)
	if len(s) >= 2 {
		if (s[0] == '"' && s[len(s)-1] == '"') || (s[0] == '\'' && s[len(s)-1] == '\'') {
			inner := s[1 : len(s)-1]
			if s[0] == '"' {
				if unq, err := strconv.Unquote(s); err == nil {
					return unq
				}
			}
			return inner
		}
	}
	return s
}

func parseBool(s string) (bool, error) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "1", "y", "yes", "true", "on":
		return true, nil
	case "0", "n", "no", "false", "off":
		return false, nil
	}
	return false, fmt.Errorf("invalid boolean %q", s)
}
