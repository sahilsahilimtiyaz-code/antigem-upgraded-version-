package claude

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"
)

// On Android (Termux) the official Claude Code binary is built for glibc, so it cannot run itself: the official
// install.sh fails there, and `claude install` / `claude update` would treat the glibc loader as their own
// executable. The bridge therefore installs and updates it like install.sh does (same download, same checksum),
// but only places the binary in ~/.local/share/claude/versions/<version>. The app's wrapper (outside of everything
// Claude's own updater touches) always starts the newest version found there through the bundled glibc loader.

const nativeReleasesURL = "https://downloads.claude.ai/claude-code-releases"

// nativeVersionsKept: each version is ~250 MB; older ones are removed after an install.
const nativeVersionsKept = 2

var nativeVersionRe = regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+$`)

func (m *Manager) nativeVersionsDir() string {
	return filepath.Join(m.HomeDir, ".local", "share", "claude", "versions")
}

// hasNativeVersion: a downloaded Claude Code version is in the versions folder.
func (m *Manager) hasNativeVersion() bool {
	entries, _ := os.ReadDir(m.nativeVersionsDir())
	for _, e := range entries {
		if !e.IsDir() && nativeVersionRe.MatchString(e.Name()) {
			return true
		}
	}
	return false
}

func nativePlatform() (string, error) {
	switch runtime.GOARCH {
	case "arm64":
		return "linux-arm64", nil
	case "amd64":
		return "linux-x64", nil
	}
	return "", fmt.Errorf("unsupported architecture %s", runtime.GOARCH)
}

// nativeInstall downloads the latest Claude Code release into the versions folder (nothing to do when it is
// already there) and keeps the newest few versions.
func (m *Manager) nativeInstall(log io.Writer) error {
	platform, err := nativePlatform()
	if err != nil {
		return err
	}
	small := &http.Client{Timeout: 30 * time.Second}

	fmt.Fprintln(log, "Checking the latest Claude Code release…")
	verBytes, err := httpGet(small, nativeReleasesURL+"/latest", 1<<10)
	if err != nil {
		return fmt.Errorf("cannot get the latest version: %w", err)
	}
	version := strings.TrimSpace(string(verBytes))
	if !nativeVersionRe.MatchString(version) {
		return fmt.Errorf("unexpected version answer %q", version)
	}
	dir := m.nativeVersionsDir()
	target := filepath.Join(dir, version)
	if fi, err := os.Stat(target); err == nil && fi.Size() > 0 {
		fmt.Fprintf(log, "Claude Code %s is already installed (latest).\n", version)
		m.pruneNativeVersions(log)
		return nil
	}

	manifestBytes, err := httpGet(small, nativeReleasesURL+"/"+version+"/manifest.json", 1<<20)
	if err != nil {
		return fmt.Errorf("cannot get the release manifest: %w", err)
	}
	var manifest struct {
		Platforms map[string]struct {
			Checksum string `json:"checksum"`
			Size     int64  `json:"size"`
		} `json:"platforms"`
	}
	if err := json.Unmarshal(manifestBytes, &manifest); err != nil {
		return fmt.Errorf("invalid release manifest: %w", err)
	}
	entry, ok := manifest.Platforms[platform]
	if !ok || len(entry.Checksum) != 64 {
		return fmt.Errorf("platform %s not found in the %s manifest", platform, version)
	}

	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	// a hidden name: the wrapper only starts files named like a version
	tmp := filepath.Join(dir, "."+version+".download")
	defer os.Remove(tmp)
	fmt.Fprintf(log, "Downloading Claude Code %s (%s, %d MB)…\n", version, platform, entry.Size>>20)
	if err := downloadVerified(nativeReleasesURL+"/"+version+"/"+platform+"/claude", tmp, entry.Size, entry.Checksum, log); err != nil {
		return err
	}
	if err := os.Chmod(tmp, 0o755); err != nil {
		return err
	}
	if err := os.Rename(tmp, target); err != nil {
		return err
	}
	fmt.Fprintf(log, "Installed Claude Code %s (checksum verified).\n", version)
	m.pruneNativeVersions(log)
	return nil
}

func httpGet(client *http.Client, url string, limit int64) ([]byte, error) {
	resp, err := client.Get(url)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s: HTTP %d", url, resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, limit))
}

func downloadVerified(url, dest string, size int64, checksum string, log io.Writer) error {
	client := &http.Client{Timeout: 10 * time.Minute}
	const maxRetries = 5

	for attempt := 1; attempt <= maxRetries; attempt++ {
		var downloaded int64 = 0
		if fi, err := os.Stat(dest); err == nil {
			downloaded = fi.Size()
		}
		if size > 0 && downloaded >= size {
			if verifySha256(dest, checksum) == nil {
				return nil
			}
			_ = os.Remove(dest)
			downloaded = 0
		}

		req, err := http.NewRequest(http.MethodGet, url, nil)
		if err != nil {
			return err
		}
		if downloaded > 0 {
			req.Header.Set("Range", fmt.Sprintf("bytes=%d-", downloaded))
			fmt.Fprintf(log, "Resuming download from %d MB (attempt %d/%d)…\n", downloaded>>20, attempt, maxRetries)
		}

		resp, err := client.Do(req)
		if err != nil {
			if attempt == maxRetries {
				return fmt.Errorf("download failed after %d attempts: %w", maxRetries, err)
			}
			fmt.Fprintf(log, "Network interruption (%v), retrying in 3s…\n", err)
			time.Sleep(3 * time.Second)
			continue
		}

		var f *os.File
		if resp.StatusCode == http.StatusPartialContent && downloaded > 0 {
			f, err = os.OpenFile(dest, os.O_APPEND|os.O_WRONLY, 0o600)
		} else if resp.StatusCode == http.StatusOK {
			f, err = os.OpenFile(dest, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
			downloaded = 0
		} else {
			resp.Body.Close()
			if attempt == maxRetries {
				return fmt.Errorf("download failed: HTTP %d", resp.StatusCode)
			}
			time.Sleep(3 * time.Second)
			continue
		}
		if err != nil {
			resp.Body.Close()
			return err
		}

		pw := &progressWriter{total: size, done: downloaded, log: log}
		buf := make([]byte, 256*1024)
		var readErr error
		for {
			n, rerr := resp.Body.Read(buf)
			if n > 0 {
				if _, werr := f.Write(buf[:n]); werr != nil {
					readErr = werr
					break
				}
				pw.Write(buf[:n])
			}
			if rerr != nil {
				if rerr != io.EOF {
					readErr = rerr
				}
				break
			}
		}
		resp.Body.Close()
		_ = f.Close()

		if readErr == nil {
			if err := verifySha256(dest, checksum); err != nil {
				_ = os.Remove(dest)
				if attempt == maxRetries {
					return err
				}
				fmt.Fprintf(log, "Checksum check failed: %v. Retrying…\n", err)
				time.Sleep(2 * time.Second)
				continue
			}
			return nil
		}

		fmt.Fprintf(log, "Stream dropped (%v). Retrying with resume…\n", readErr)
		time.Sleep(3 * time.Second)
	}
	return fmt.Errorf("download failed after %d attempts", maxRetries)
}

func verifySha256(filePath, expected string) error {
	f, err := os.Open(filePath)
	if err != nil {
		return err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return err
	}
	got := hex.EncodeToString(h.Sum(nil))
	if !strings.EqualFold(got, expected) {
		return fmt.Errorf("checksum mismatch (got %s, expected %s)", got, expected)
	}
	return nil
}

type progressWriter struct {
	total, done int64
	lastPct     int64
	log         io.Writer
}

func (p *progressWriter) Write(b []byte) (int, error) {
	p.done += int64(len(b))
	if p.total > 0 {
		if pct := p.done * 100 / p.total; pct >= p.lastPct+10 {
			p.lastPct = pct - pct%10
			fmt.Fprintf(p.log, "  %d%% (%d of %d MB)\n", p.lastPct, p.done>>20, p.total>>20)
		}
	}
	return len(b), nil
}

// pruneNativeVersions removes all but the newest versions (Claude's own cleanup cannot be relied on here).
func (m *Manager) pruneNativeVersions(log io.Writer) {
	entries, err := os.ReadDir(m.nativeVersionsDir())
	if err != nil {
		return
	}
	var versions []string
	for _, e := range entries {
		if !e.IsDir() && nativeVersionRe.MatchString(e.Name()) {
			versions = append(versions, e.Name())
		}
	}
	sort.Slice(versions, func(i, j int) bool { return versionLess(versions[j], versions[i]) })
	for _, old := range versions[min(len(versions), nativeVersionsKept):] {
		if os.Remove(filepath.Join(m.nativeVersionsDir(), old)) == nil {
			fmt.Fprintf(log, "Removed old version %s.\n", old)
		}
	}
}

func versionLess(a, b string) bool {
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(pa) && i < len(pb); i++ {
		x, _ := strconv.Atoi(pa[i])
		y, _ := strconv.Atoi(pb[i])
		if x != y {
			return x < y
		}
	}
	return len(pa) < len(pb)
}

// importNativeArchive installs a Claude Code binary or archive into ~/.local/share/claude/versions/<version>.
func (m *Manager) importNativeArchive(r io.Reader, filename string, log io.Writer) error {
	dir := m.nativeVersionsDir()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmpArchive, err := os.CreateTemp(dir, ".claude-import-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmpArchive.Name())

	fmt.Fprintln(log, "Buffering imported package…")
	if _, err := io.Copy(tmpArchive, r); err != nil {
		return fmt.Errorf("failed to write archive: %w", err)
	}
	_ = tmpArchive.Close()

	// Determine version
	version := "1.0.0"
	vMatch := regexp.MustCompile(`([0-9]+\.[0-9]+\.[0-9]+)`).FindStringSubmatch(filename)
	if len(vMatch) > 1 {
		version = vMatch[1]
	}

	target := filepath.Join(dir, version)
	tmpTarget := filepath.Join(dir, "."+version+".import")
	defer os.Remove(tmpTarget)

	f, err := os.Open(tmpArchive.Name())
	if err != nil {
		return err
	}
	header := make([]byte, 512)
	n, _ := f.Read(header)
	f.Close()

	if n >= 4 && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F' {
		fmt.Fprintln(log, "Detected raw ELF binary. Installing…")
		if err := copyFile(tmpArchive.Name(), tmpTarget, 0o755); err != nil {
			return err
		}
	} else {
		fmt.Fprintln(log, "Installing imported agent binary…")
		if err := copyFile(tmpArchive.Name(), tmpTarget, 0o755); err != nil {
			return err
		}
	}

	if err := os.Rename(tmpTarget, target); err != nil {
		_ = os.Remove(target)
		if err := os.Rename(tmpTarget, target); err != nil {
			return err
		}
	}
	_ = os.Chmod(target, 0o755)
	fmt.Fprintf(log, "Successfully imported Claude Code %s!\n", version)
	m.pruneNativeVersions(log)
	return nil
}

func copyFile(src, dst string, mode os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}
