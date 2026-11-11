package opencode

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"crypto/sha512"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"gemini-server/pkg/config"
)

// Installing OpenCode from the app. The official v2 installer (https://opencode.ai/v2/install) downloads the npm
// package @opencode/cli-<target> and puts its binary in ~/.opencode/bin; this does the same in Go, so it also works
// on Termux (no bash script assumptions) and the sha512 of the download is checked against the registry's.

const latestURL = "https://opencode.ai/update/api/latest/cli/npm"

// installJob is the one install / update that can run at a time; the app polls it.
type installJob struct {
	mu       sync.Mutex
	Kind     string
	Running  bool
	ExitCode *int
	Log      strings.Builder
	Started  time.Time
}

func (j *installJob) logf(format string, a ...interface{}) {
	j.mu.Lock()
	defer j.mu.Unlock()
	fmt.Fprintf(&j.Log, format+"\n", a...)
}

func (j *installJob) snapshot() map[string]interface{} {
	j.mu.Lock()
	defer j.mu.Unlock()
	return map[string]interface{}{"kind": j.Kind, "running": j.Running, "exit_code": j.ExitCode, "log": j.Log.String(), "started": j.Started}
}

// installTarget is OpenCode's platform name (linux-arm64, linux-x64-baseline, linux-x64-musl…). Termux gets the glibc
// build: the bridge starts it through the app's bundled glibc loader.
func installTarget() (string, error) {
	var arch string
	switch runtime.GOARCH {
	case "arm64":
		arch = "arm64"
	case "amd64":
		arch = "x64"
	default:
		return "", fmt.Errorf("OpenCode has no build for %s", runtime.GOARCH)
	}
	switch runtime.GOOS {
	case "linux", "android":
	case "darwin":
		return "darwin-" + arch, nil
	default:
		return "", fmt.Errorf("installing OpenCode from the app is not supported on %s", runtime.GOOS)
	}
	target := "linux-" + arch
	if arch == "x64" {
		// the regular x64 build needs AVX2
		if data, err := os.ReadFile("/proc/cpuinfo"); err == nil && !strings.Contains(string(data), " avx2") {
			target += "-baseline"
		}
	}
	if runtime.GOOS == "linux" && !config.IsAndroid() {
		if _, err := os.Stat("/etc/alpine-release"); err == nil {
			target += "-musl"
		} else if out, err := exec.Command("ldd", "--version").CombinedOutput(); err == nil || len(out) > 0 {
			if strings.Contains(strings.ToLower(string(out)), "musl") {
				target += "-musl"
			}
		}
	}
	return target, nil
}

func (m *Manager) installDir() string { return filepath.Join(m.HomeDir, ".opencode", "bin") }

func getJSON(ctx context.Context, u string, out interface{}) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d from %s", resp.StatusCode, u)
	}
	return json.NewDecoder(io.LimitReader(resp.Body, 8<<20)).Decode(out)
}

// install downloads the latest OpenCode into ~/.opencode/bin (an update replaces the binary; a running server is
// restarted on the next request).
func (m *Manager) install(ctx context.Context, j *installJob, update bool) error {
	target, err := installTarget()
	if err != nil {
		return err
	}
	j.logf("Platform: %s", target)

	var latest struct {
		Version  string `json:"version"`
		Metadata struct {
			Package string `json:"package"`
		} `json:"metadata"`
		Package string `json:"package"`
	}
	if err := getJSON(ctx, latestURL, &latest); err != nil {
		return fmt.Errorf("could not get the latest version: %w", err)
	}
	pkg := latest.Metadata.Package
	if pkg == "" {
		pkg = latest.Package
	}
	if pkg == "" {
		pkg = "@opencode/cli"
	}
	if latest.Version == "" {
		return errors.New("the version service gave no version")
	}
	j.logf("Latest version: %s", latest.Version)

	if update {
		if bin, err := m.ResolveBinary(); err == nil {
			if cur := strings.TrimPrefix(m.Version(bin), "v"); cur == latest.Version {
				j.logf("OpenCode %s is already the latest version.", cur)
				return nil
			} else if cur != "" {
				j.logf("Installed: %s", cur)
			}
		}
	}

	name := strings.TrimSuffix(pkg, "/cli") + "/cli-" + target
	var meta struct {
		Dist struct {
			Tarball   string `json:"tarball"`
			Integrity string `json:"integrity"`
		} `json:"dist"`
	}
	metaURL := "https://registry.npmjs.org/" + strings.Replace(url.PathEscape(name), "%40", "@", 1) + "/" + latest.Version
	if err := getJSON(ctx, metaURL, &meta); err != nil {
		return fmt.Errorf("no OpenCode %s build for %s: %w", latest.Version, target, err)
	}
	if meta.Dist.Tarball == "" {
		return errors.New("the registry gave no download")
	}
	j.logf("Downloading %s", meta.Dist.Tarball)

	if err := os.MkdirAll(m.installDir(), 0700); err != nil {
		return err
	}
	tarballFile := filepath.Join(m.installDir(), ".download.tgz")
	defer os.Remove(tarballFile)

	var done int64
	total := int64(0)
	const maxRetries = 5

	for attempt := 1; attempt <= maxRetries; attempt++ {
		downloaded := int64(0)
		if fi, err := os.Stat(tarballFile); err == nil {
			downloaded = fi.Size()
		}

		req, err := http.NewRequestWithContext(ctx, http.MethodGet, meta.Dist.Tarball, nil)
		if err != nil {
			return err
		}
		if downloaded > 0 {
			req.Header.Set("Range", fmt.Sprintf("bytes=%d-", downloaded))
			j.logf("Resuming download from %d MB (attempt %d/%d)…", downloaded>>20, attempt, maxRetries)
		}

		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			if attempt == maxRetries {
				return fmt.Errorf("download failed after %d attempts: %w", maxRetries, err)
			}
			j.logf("Network drop (%v), retrying in 3s…", err)
			time.Sleep(3 * time.Second)
			continue
		}

		var tgz *os.File
		if resp.StatusCode == http.StatusPartialContent && downloaded > 0 {
			tgz, err = os.OpenFile(tarballFile, os.O_APPEND|os.O_WRONLY, 0600)
			if total == 0 && resp.ContentLength > 0 {
				total = downloaded + resp.ContentLength
			}
		} else if resp.StatusCode == http.StatusOK {
			tgz, err = os.OpenFile(tarballFile, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
			downloaded = 0
			total = resp.ContentLength
		} else {
			resp.Body.Close()
			if attempt == maxRetries {
				return fmt.Errorf("download: HTTP %d", resp.StatusCode)
			}
			time.Sleep(3 * time.Second)
			continue
		}
		if err != nil {
			resp.Body.Close()
			return err
		}

		done = downloaded
		buf := make([]byte, 256<<10)
		lastReport := time.Now()
		var readErr error
		for {
			n, rerr := resp.Body.Read(buf)
			if n > 0 {
				if _, werr := tgz.Write(buf[:n]); werr != nil {
					readErr = werr
					break
				}
				done += int64(n)
				if time.Since(lastReport) > 2*time.Second {
					lastReport = time.Now()
					if total > 0 {
						j.logf("  %d / %d MB (%d%%)", done>>20, total>>20, (done*100)/total)
					} else {
						j.logf("  %d MB", done>>20)
					}
				}
			}
			if rerr != nil {
				if rerr != io.EOF {
					readErr = rerr
				}
				break
			}
		}
		resp.Body.Close()
		_ = tgz.Close()

		if readErr == nil {
			break
		}
		j.logf("Connection glitch (%v). Resuming…", readErr)
		time.Sleep(3 * time.Second)
	}
	j.logf("Downloaded %d MB", done>>20)
	if want := meta.Dist.Integrity; strings.HasPrefix(want, "sha512-") {
		f, err := os.Open(tarballFile)
		if err != nil {
			return err
		}
		h := sha512.New()
		if _, err := io.Copy(h, f); err != nil {
			f.Close()
			return err
		}
		f.Close()
		if got := "sha512-" + base64.StdEncoding.EncodeToString(h.Sum(nil)); got != want {
			return errors.New("the download does not match the registry's checksum")
		}
		j.logf("Checksum OK")
	}

	tgz, err := os.Open(tarballFile)
	if err != nil {
		return err
	}
	defer tgz.Close()
	gz, err := gzip.NewReader(tgz)
	if err != nil {
		return err
	}
	tr := tar.NewReader(gz)
	dest := filepath.Join(m.installDir(), "opencode")
	tmp := dest + ".new"
	found := false
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		if hdr.Name != "package/bin/opencode" {
			continue
		}
		f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0755)
		if err != nil {
			return err
		}
		if _, err := io.Copy(f, tr); err != nil {
			f.Close()
			os.Remove(tmp)
			return err
		}
		f.Close()
		found = true
		break
	}
	if !found {
		return errors.New("the package has no opencode binary")
	}
	// a running server keeps its (old, now unlinked) binary until it restarts
	if err := os.Rename(tmp, dest); err != nil {
		os.Remove(tmp)
		return err
	}
	m.verMu.Lock()
	m.version = ""
	m.verMu.Unlock()
	v := m.Version(dest)
	if v == "" {
		return fmt.Errorf("installed %s, but it does not start on this device", dest)
	}
	j.logf("Installed OpenCode %s at %s", v, dest)
	if m.Running() {
		j.logf("Restarting the OpenCode server…")
		m.Stop()
	}
	return nil
}

// importArchive installs an imported OpenCode binary or archive.
func (m *Manager) importArchive(r io.Reader, filename string, j *installJob) error {
	if err := os.MkdirAll(m.installDir(), 0700); err != nil {
		return err
	}
	tmpFile, err := os.CreateTemp(m.installDir(), ".import-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(tmpFile.Name())
	j.logf("Buffering imported OpenCode package…")
	if _, err := io.Copy(tmpFile, r); err != nil {
		tmpFile.Close()
		return fmt.Errorf("failed to save archive: %w", err)
	}
	_ = tmpFile.Close()

	dest := filepath.Join(m.installDir(), "opencode")
	tmpDest := dest + ".new"
	defer os.Remove(tmpDest)

	f, err := os.Open(tmpFile.Name())
	if err != nil {
		return err
	}
	header := make([]byte, 512)
	n, _ := f.Read(header)
	f.Close()

	if n >= 4 && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F' {
		j.logf("Detected raw OpenCode ELF executable. Installing…")
		if err := copyFile(tmpFile.Name(), tmpDest, 0755); err != nil {
			return err
		}
	} else {
		j.logf("Extracting archive…")
		in, err := os.Open(tmpFile.Name())
		if err != nil {
			return err
		}
		defer in.Close()
		gz, err := gzip.NewReader(in)
		if err != nil {
			return fmt.Errorf("archive format error: %w", err)
		}
		defer gz.Close()
		tr := tar.NewReader(gz)
		found := false
		for {
			hdr, err := tr.Next()
			if err == io.EOF {
				break
			}
			if err != nil {
				return err
			}
			if hdr.Name == "package/bin/opencode" || filepath.Base(hdr.Name) == "opencode" {
				outFile, err := os.OpenFile(tmpDest, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0755)
				if err != nil {
					return err
				}
				if _, err := io.Copy(outFile, tr); err != nil {
					outFile.Close()
					return err
				}
				outFile.Close()
				found = true
				break
			}
		}
		if !found {
			return errors.New("archive did not contain an opencode binary")
		}
	}

	if err := os.Rename(tmpDest, dest); err != nil {
		_ = os.Remove(dest)
		if err := os.Rename(tmpDest, dest); err != nil {
			return err
		}
	}
	_ = os.Chmod(dest, 0755)

	m.verMu.Lock()
	m.version = ""
	m.verMu.Unlock()
	v := m.Version(dest)
	if v == "" {
		v = "latest"
	}
	j.logf("Successfully installed OpenCode %s at %s", v, dest)
	if m.Running() {
		j.logf("Restarting OpenCode server…")
		m.Stop()
	}
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

// handleInstall: GET /api/opencode/cli/job; POST /api/opencode/cli/{install|update|import}.
func (m *Manager) handleInstall(w http.ResponseWriter, r *http.Request, action string) {
	m.jobMu.Lock()
	job := m.job
	m.jobMu.Unlock()
	if r.Method == http.MethodGet {
		var snap interface{}
		if job != nil {
			snap = job.snapshot()
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": snap})
		return
	}
	if action != "install" && action != "update" && action != "import" {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown cli action"})
		return
	}
	if job != nil {
		job.mu.Lock()
		running := job.Running
		job.mu.Unlock()
		if running {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "an install or update is already running"})
			return
		}
	}
	nj := &installJob{Kind: action, Running: true, Started: time.Now()}
	m.jobMu.Lock()
	m.job = nj
	m.jobMu.Unlock()

	if action == "import" {
		go func() {
			defer r.Body.Close()
			code := 0
			if err := m.importArchive(r.Body, "opencode_archive", nj); err != nil {
				nj.logf("Error: %v", err)
				code = 1
			}
			nj.mu.Lock()
			nj.Running = false
			nj.ExitCode = &code
			nj.mu.Unlock()
		}()
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": nj.snapshot()})
		return
	}

	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Minute)
		defer cancel()
		code := 0
		if err := m.install(ctx, nj, action == "update"); err != nil {
			nj.logf("Error: %v", err)
			code = 1
		}
		nj.mu.Lock()
		nj.Running = false
		nj.ExitCode = &code
		nj.mu.Unlock()
	}()
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": nj.snapshot()})
}
