package wormhole

import "strings"

// maxFilenameBytes is the maximum filename length on Android storage (vfat and
// ext4 both cap at 255 bytes), matching the limit used by
// android.os.FileUtils.trimFilename.
const maxFilenameBytes = 255

// sanitizeFilename returns a filename that is safe to write to Android's
// external storage (the public Downloads folder is FAT/exFAT-backed and is
// also surfaced through MediaStore, both of which reject certain characters).
//
// It replicates android.os.FileUtils.buildValidFatFilename: every character
// that is invalid in a FAT filename is replaced with '_', then the result is
// trimmed to 255 bytes. The invalid set is the control characters (0x00-0x1f),
// 0x7f (DEL), and " * / : < > ? \ |. Empty, "." and ".." are not usable
// filenames and map to a placeholder.
//
// Without this, a sender offering a file whose name contains e.g. ':' would
// stage to internal storage (ext4 allows it) but then fail to copy to the
// public Downloads folder.
func sanitizeFilename(name string) string {
	if name == "" || name == "." || name == ".." {
		return "(invalid)"
	}

	var b strings.Builder
	b.Grow(len(name))
	for _, r := range name {
		if isValidFatFilenameChar(r) {
			b.WriteRune(r)
		} else {
			b.WriteByte('_')
		}
	}
	return trimFilename(b.String())
}

// trimFilename shortens name to at most maxFilenameBytes, replicating
// android.os.FileUtils.trimFilename: runes are removed from the middle and an
// ellipsis is inserted, which keeps both the start of the name and its
// extension intact.
func trimFilename(name string) string {
	if len(name) <= maxFilenameBytes {
		return name
	}

	runes := []rune(name)
	budget := maxFilenameBytes - len("...")
	for len(string(runes)) > budget {
		mid := len(runes) / 2
		runes = append(runes[:mid], runes[mid+1:]...)
	}
	mid := len(runes) / 2
	return string(runes[:mid]) + "..." + string(runes[mid:])
}

// isValidFatFilenameChar reports whether r is allowed in a FAT filename,
// matching android.os.FileUtils.isValidFatFilenameChar.
func isValidFatFilenameChar(r rune) bool {
	if r >= 0x00 && r <= 0x1f {
		return false
	}
	switch r {
	case '"', '*', '/', ':', '<', '>', '?', '\\', '|', 0x7f:
		return false
	default:
		return true
	}
}
