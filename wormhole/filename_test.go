package wormhole

import (
	"strings"
	"testing"
)

func TestSanitizeFilename(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"plain name unchanged", "report.txt", "report.txt"},
		{"spaces are allowed", "my photo.jpg", "my photo.jpg"},
		{"unicode is preserved", "café.png", "café.png"},
		{"colon replaced", "foo:bar.txt", "foo_bar.txt"},
		{"forward slash replaced", "a/b.txt", "a_b.txt"},
		{"backslash replaced", `a\b.txt`, "a_b.txt"},
		{"question mark replaced", "q?.txt", "q_.txt"},
		{"asterisk replaced", "star*.txt", "star_.txt"},
		{"double quote replaced", `quote".txt`, "quote_.txt"},
		{"angle brackets replaced", "a<b>c.txt", "a_b_c.txt"},
		{"pipe replaced", "a|b.txt", "a_b.txt"},
		{"control char replaced", "tab\tname", "tab_name"},
		{"del char replaced", "xy", "x_y"},
		{"all invalid", `:*?`, "___"},
		{"directory zip name", "weird:dir.zip", "weird_dir.zip"},
		{"path traversal is neutralized", "../../evil.sh", ".._.._evil.sh"},
		{"absolute path is neutralized", "/etc/passwd", "_etc_passwd"},
		{"empty is invalid", "", "(invalid)"},
		{"dot is invalid", ".", "(invalid)"},
		{"dotdot is invalid", "..", "(invalid)"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := sanitizeFilename(tc.in)
			if got != tc.want {
				t.Errorf("sanitizeFilename(%q) = %q, want %q", tc.in, got, tc.want)
			}
		})
	}
}

func TestSanitizeFilenameTrimsLength(t *testing.T) {
	// Short names are unchanged.
	if got := sanitizeFilename("short.txt"); got != "short.txt" {
		t.Errorf("short name changed: %q", got)
	}

	// Over-long names are trimmed to <= 255 bytes, keeping start + extension.
	long := strings.Repeat("a", 300) + ".txt"
	got := sanitizeFilename(long)
	if len(got) > maxFilenameBytes {
		t.Errorf("len(result) = %d, want <= %d", len(got), maxFilenameBytes)
	}
	if !strings.HasPrefix(got, "a") {
		t.Errorf("start of name not preserved: %q", got)
	}
	if !strings.HasSuffix(got, ".txt") {
		t.Errorf("extension not preserved: %q", got)
	}
	if !strings.Contains(got, "...") {
		t.Errorf("expected ellipsis marker in %q", got)
	}

	// A multi-byte (non-ASCII) over-long name must not be truncated mid-rune
	// and must still be valid UTF-8.
	longUnicode := strings.Repeat("é", 200) + ".png"
	gotU := sanitizeFilename(longUnicode)
	if len(gotU) > maxFilenameBytes {
		t.Errorf("unicode len(result) = %d, want <= %d", len(gotU), maxFilenameBytes)
	}
	if !strings.HasSuffix(gotU, ".png") {
		t.Errorf("unicode extension not preserved: %q", gotU)
	}
}

// A sanitized filename must itself be valid (idempotent and self-consistent).
func TestSanitizeFilenameIsIdempotent(t *testing.T) {
	inputs := []string{"foo:bar.txt", "a/b\\c.txt", "café.png", "plain.txt"}
	for _, in := range inputs {
		once := sanitizeFilename(in)
		twice := sanitizeFilename(once)
		if once != twice {
			t.Errorf("sanitizeFilename not idempotent for %q: %q -> %q", in, once, twice)
		}
		for _, r := range once {
			if !isValidFatFilenameChar(r) {
				t.Errorf("sanitizeFilename(%q) = %q still contains invalid char %q", in, once, r)
			}
		}
	}
}
