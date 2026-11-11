package handlers

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"

	"gemini-server/pkg/config"
	"gemini-server/pkg/hub"
)

// Installing and inspecting the Antigravity CLI (`agy`) on the machine the bridge runs on, so the app can offer
// "Install" and show the installed version the way it does for Claude Code and OpenCode.
//
//	GET  /api/agy/cli          -> {installed, path, version, job}
//	POST /api/agy/cli/install  -> downloads and installs the official Antigravity CLI release for this platform
//	POST /api/agy/cli/update   -> updates `agy` to the latest release from Google's manifest

const (
	agyManifestBaseURL = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests"
	agyInstallCommand  = `export PATH="$HOME/.local/bin:$HOME/.gemini/bin:$HOME/.antigravity/bin:/antigem/bin:/usr/local/bin:/usr/bin:/bin:$PATH"; curl -fsSL https://antigravity.google/cli/install.sh | bash`
)

type agyCliJob struct {
	mu       sync.Mutex
	Kind     string
	Running  bool
	ExitCode *int
	log      bytes.Buffer
}

func (j *agyCliJob) Write(p []byte) (int, error) {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.log.Write(p)
	// keep the log small
	if j.log.Len() > 64*1024 {
		rest := append([]byte(nil), j.log.Bytes()[j.log.Len()-32*1024:]...)
		j.log.Reset()
		j.log.Write(rest)
	}
	return len(p), nil
}

func (j *agyCliJob) logf(format string, a ...interface{}) {
	_, _ = fmt.Fprintf(j, format+"\n", a...)
}

func (j *agyCliJob) snapshot() map[string]interface{} {
	j.mu.Lock()
	defer j.mu.Unlock()
	out := map[string]interface{}{"kind": j.Kind, "running": j.Running, "log": j.log.String()}
	if j.ExitCode != nil {
		out["exit_code"] = *j.ExitCode
	}
	return out
}

var (
	agyJobMu sync.Mutex
	agyJob   *agyCliJob

	agyVerMu    sync.Mutex
	agyVerPath  string
	agyVerStamp time.Time
	agyVerValue string
)

// agyPath is the agy the hub runs: the one the app named with --bin (installed or not yet), or, for a bridge started
// by hand without --bin, the one it found at startup. With neither, where Google's installer puts it.
func (h *Handler) agyPath() string {
	hub.CleanupLegacyAgyWrappers()
	if h.HubManager != nil {
		if p := h.HubManager.GetAgyBin(); p != "" && agyInstalled(p) {
			return p
		}
	}
	found := hub.FindAllAgyBinaries()
	if len(found) > 0 {
		if h.HubManager != nil && !agyInstalled(h.HubManager.GetAgyBin()) {
			h.HubManager.SetAgyBin(found[0])
		}
		return found[0]
	}
	if h.HubManager != nil {
		if p := h.HubManager.GetAgyBin(); p != "" && !hub.IsLegacyAgyWrapper(p) {
			return p
		}
	}
	return hub.InstallerAgyPath()
}

func agyInstalled(path string) bool {
	if path == "" || hub.IsLegacyAgyWrapper(path) {
		return false
	}
	fi, err := os.Stat(path)
	return err == nil && !fi.IsDir()
}

// agyVersion runs `agy --version` (cached until the file changes).
func agyVersion(bin string) string {
	if !agyInstalled(bin) {
		return ""
	}
	fi, err := os.Stat(bin)
	if err != nil {
		return ""
	}
	agyVerMu.Lock()
	defer agyVerMu.Unlock()
	if agyVerPath == bin && agyVerStamp.Equal(fi.ModTime()) && agyVerValue != "" {
		return agyVerValue
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, bin, "--version")
	cmd.Stdin = nil
	// stdout only: agy logs to stderr before it is set up (on Android "ERROR: logging before google.Init: I… Initialized
	// Android local timezone…"), which is not an error and not the version
	out, err := cmd.Output()
	if (err != nil || len(out) == 0) && hub.GlibcInterp(bin) {
		// Fallback for Termux glibc environment if /lib/ld-linux-aarch64.so.1 is missing and bin is a glibc ELF
		prefix := config.TermuxPrefix("")
		if prefix == "" {
			prefix = "/data/data/com.termux/files/usr"
		}
		ldLinux := filepath.Join(prefix, "glibc", "lib", "ld-linux-aarch64.so.1")
		glibcLib := filepath.Join(prefix, "glibc", "lib")
		if _, statErr := os.Stat(ldLinux); statErr == nil {
			glibcCmd := exec.CommandContext(ctx, ldLinux, "--library-path", glibcLib, bin, "--version")
			glibcCmd.Stdin = nil
			var cleanEnv []string
			for _, kv := range os.Environ() {
				if !strings.HasPrefix(kv, "LD_PRELOAD=") {
					cleanEnv = append(cleanEnv, kv)
				}
			}
			glibcCmd.Env = cleanEnv
			if glibcOut, glibcErr := glibcCmd.Output(); glibcErr == nil && len(glibcOut) > 0 {
				out = glibcOut
			}
		}
	}
	v := versionLine(string(out))
	if v != "" {
		agyVerPath, agyVerStamp, agyVerValue = bin, fi.ModTime(), v
	}
	return v
}

var agyVersionRe = regexp.MustCompile(`^v?\d+(\.\d+)+\S*$`)

// versionLine is the first line that looks like a version ("1.5.3"), else the first line.
func versionLine(s string) string {
	for _, l := range strings.Split(s, "\n") {
		if l = strings.TrimSpace(l); agyVersionRe.MatchString(l) {
			return l
		}
	}
	return firstLine(s)
}

func firstLine(s string) string {
	for _, l := range strings.Split(s, "\n") {
		if l = strings.TrimSpace(l); l != "" {
			return l
		}
	}
	return ""
}

// agyManifestTarget returns Google's release manifest name (e.g. "android_arm64", "linux_arm64", "linux_x64").
func agyManifestTarget() (string, error) {
	var arch string
	switch runtime.GOARCH {
	case "arm64":
		arch = "arm64"
	case "amd64":
		arch = "x64"
	default:
		return "", fmt.Errorf("Antigravity CLI has no release build for architecture %s", runtime.GOARCH)
	}
	if runtime.GOOS == "darwin" {
		return "darwin_" + arch, nil
	}
	// Native Android / Termux (not inside a PRoot distro that has /etc/os-release) gets Google's native Android Bionic build.
	if config.IsAndroid() {
		if _, err := os.Stat("/etc/os-release"); err != nil && arch == "arm64" {
			return "android_arm64", nil
		}
	}
	return "linux_" + arch, nil
}

// installAgyNative downloads the official Antigravity CLI release directly in Go (with SHA-512 verification and
// progress reporting) and installs it to destPath (defaulting to ~/.local/bin/agy).
func (h *Handler) installAgyNative(ctx context.Context, job *agyCliJob, destPath string, isUpdate bool) (string, error) {
	hub.CleanupLegacyAgyWrappers()
	target, err := agyManifestTarget()
	if err != nil {
		return "", err
	}
	job.logf("Platform: %s", target)

	manifestURL := fmt.Sprintf("%s/%s.json", agyManifestBaseURL, target)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, manifestURL, nil)
	if err != nil {
		return "", err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("fetching release manifest: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("manifest server returned HTTP %d", resp.StatusCode)
	}

	var manifest struct {
		Version string `json:"version"`
		URL     string `json:"url"`
		SHA512  string `json:"sha512"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(&manifest); err != nil {
		return "", fmt.Errorf("decoding release manifest: %w", err)
	}
	if manifest.URL == "" || manifest.Version == "" {
		return "", errors.New("release manifest is missing version or download URL")
	}

	if destPath == "" || hub.IsLegacyAgyWrapper(destPath) {
		destPath = hub.InstallerAgyPath()
	}
	beforeVer := ""
	if agyInstalled(destPath) {
		beforeVer = agyVersion(destPath)
	}
	if isUpdate && beforeVer != "" && strings.TrimPrefix(beforeVer, "v") == strings.TrimPrefix(manifest.Version, "v") {
		job.logf("agy %s is the latest version.", beforeVer)
		return destPath, nil
	}

	job.logf("Latest version: %s", manifest.Version)
	job.logf("Downloading %s", manifest.URL)

	installDir := filepath.Dir(destPath)
	if err := os.MkdirAll(installDir, 0o755); err != nil {
		return "", fmt.Errorf("creating install folder %s: %w", installDir, err)
	}

	tgzFile := filepath.Join(installDir, ".agy-download.tar.gz")
	defer os.Remove(tgzFile)

	var done int64
	total := int64(0)
	const maxRetries = 5

	for attempt := 1; attempt <= maxRetries; attempt++ {
		downloaded := int64(0)
		if fi, err := os.Stat(tgzFile); err == nil {
			downloaded = fi.Size()
		}

		dlReq, err := http.NewRequestWithContext(ctx, http.MethodGet, manifest.URL, nil)
		if err != nil {
			return "", err
		}
		if downloaded > 0 {
			dlReq.Header.Set("Range", fmt.Sprintf("bytes=%d-", downloaded))
			job.logf("Resuming agy download from %d MB (attempt %d/%d)…", downloaded>>20, attempt, maxRetries)
		}

		dlResp, err := http.DefaultClient.Do(dlReq)
		if err != nil {
			if attempt == maxRetries {
				return "", fmt.Errorf("downloading Antigravity CLI after %d attempts: %w", maxRetries, err)
			}
			job.logf("Network glitch (%v), retrying in 3s…", err)
			time.Sleep(3 * time.Second)
			continue
		}

		var tgz *os.File
		if dlResp.StatusCode == http.StatusPartialContent && downloaded > 0 {
			tgz, err = os.OpenFile(tgzFile, os.O_APPEND|os.O_WRONLY, 0600)
			if total == 0 && dlResp.ContentLength > 0 {
				total = downloaded + dlResp.ContentLength
			}
		} else if dlResp.StatusCode == http.StatusOK {
			tgz, err = os.OpenFile(tgzFile, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
			downloaded = 0
			total = dlResp.ContentLength
		} else {
			dlResp.Body.Close()
			if attempt == maxRetries {
				return "", fmt.Errorf("download server returned HTTP %d", dlResp.StatusCode)
			}
			time.Sleep(3 * time.Second)
			continue
		}
		if err != nil {
			dlResp.Body.Close()
			return "", err
		}

		done = downloaded
		buf := make([]byte, 256<<10)
		lastReport := time.Now()
		var readErr error
		for {
			n, rerr := dlResp.Body.Read(buf)
			if n > 0 {
				if _, werr := tgz.Write(buf[:n]); werr != nil {
					readErr = werr
					break
				}
				done += int64(n)
				if time.Since(lastReport) >= 1500*time.Millisecond {
					lastReport = time.Now()
					if total > 0 {
						pct := int((done * 100) / total)
						job.logf("  %d / %d MB (%d%%)", done>>20, total>>20, pct)
					} else {
						job.logf("  %d MB", done>>20)
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
		dlResp.Body.Close()
		_ = tgz.Close()

		if readErr == nil {
			break
		}
		job.logf("Stream dropped (%v). Retrying…", readErr)
		time.Sleep(3 * time.Second)
	}
	job.logf("Downloaded %d MB", done>>20)

	if want := strings.ToLower(strings.TrimSpace(manifest.SHA512)); want != "" {
		f, err := os.Open(tgzFile)
		if err != nil {
			return "", err
		}
		h := sha512.New()
		if _, err := io.Copy(h, f); err != nil {
			f.Close()
			return "", err
		}
		f.Close()
		got := hex.EncodeToString(h.Sum(nil))
		if got != want {
			return "", errors.New("SHA-512 checksum mismatch on downloaded archive")
		}
		job.logf("SHA-512 checksum verified.")
	}

	tgz, err := os.Open(tgzFile)
	if err != nil {
		return "", fmt.Errorf("opening downloaded archive: %w", err)
	}
	defer tgz.Close()
	gz, err := gzip.NewReader(tgz)
	if err != nil {
		return "", fmt.Errorf("opening gzip archive: %w", err)
	}
	defer gz.Close()

	tr := tar.NewReader(gz)
	tmpBin := destPath + ".new"
	extracted := false
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return "", fmt.Errorf("reading tar archive: %w", err)
		}
		if hdr.Typeflag != tar.TypeReg && hdr.Typeflag != tar.TypeRegA {
			continue
		}
		base := filepath.Base(hdr.Name)
		if base != "antigravity" && base != "agy" {
			continue
		}
		outFile, err := os.OpenFile(tmpBin, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o755)
		if err != nil {
			return "", fmt.Errorf("writing binary: %w", err)
		}
		if _, err := io.Copy(outFile, tr); err != nil {
			outFile.Close()
			os.Remove(tmpBin)
			return "", fmt.Errorf("extracting binary: %w", err)
		}
		outFile.Close()
		extracted = true
		break
	}
	if !extracted {
		return "", errors.New("archive did not contain the antigravity binary")
	}

	if err := os.Chmod(tmpBin, 0o755); err != nil {
		os.Remove(tmpBin)
		return "", err
	}
	if err := os.Rename(tmpBin, destPath); err != nil {
		os.Remove(tmpBin)
		return "", fmt.Errorf("installing binary to %s: %w", destPath, err)
	}

	// Also ensure ~/.local/bin/agy exists if destPath was elsewhere, or vice versa
	installerPath := hub.InstallerAgyPath()
	if installerPath != "" && filepath.Clean(installerPath) != filepath.Clean(destPath) {
		_ = os.MkdirAll(filepath.Dir(installerPath), 0o755)
		_ = os.Remove(installerPath)
		_ = os.Symlink(destPath, installerPath)
	}

	agyVerMu.Lock()
	agyVerValue = ""
	agyVerMu.Unlock()

	afterVer := agyVersion(destPath)
	if afterVer == "" {
		afterVer = manifest.Version
	}
	if isUpdate && beforeVer != "" && afterVer != beforeVer {
		// Keep exact format expected by the Android UI
		job.logf("\nUpdated agy %s -> %s.\nThe running hub keeps using %s until the server restarts.", beforeVer, afterVer, beforeVer)
	} else {
		job.logf("Installed Antigravity CLI %s at %s", afterVer, destPath)
	}
	return destPath, nil
}

func (h *Handler) AgyCliHandler(w http.ResponseWriter, r *http.Request) {
	bin := h.agyPath()
	installed := agyInstalled(bin)
	resp := map[string]interface{}{"success": true, "installed": installed, "path": bin, "version": ""}
	if installed {
		resp["version"] = agyVersion(bin)
	}
	agyJobMu.Lock()
	job := agyJob
	agyJobMu.Unlock()
	if job != nil {
		resp["job"] = job.snapshot()
	}
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) AgyCliInstallHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "POST only"})
		return
	}
	h.startAgyAsyncJob(w, "install", func(ctx context.Context, job *agyCliJob) int {
		dest := h.agyPath()
		installedPath, err := h.installAgyNative(ctx, job, dest, false)
		if err != nil {
			job.logf("Native installer note: %v — trying shell installer fallback…", err)
			cmd := exec.CommandContext(ctx, "bash", "-lc", agyInstallCommand)
			home, _ := os.UserHomeDir()
			cmd.Dir = home
			cmd.Env = os.Environ()
			cmd.Stdout = job
			cmd.Stderr = job
			if runErr := cmd.Run(); runErr != nil {
				job.logf("Error: %v", err)
				return 1
			}
			installedPath = h.agyPath()
		}
		if h.HubManager != nil {
			h.HubManager.SetAgyBin(installedPath)
			if !h.HubManager.Disabled && !h.HubManager.IsRunning() {
				go func() {
					_ = h.HubManager.Start()
				}()
			}
		}
		return 0
	})
}

// AgyCliUpdateHandler updates the agy binary to the latest release from Google's manifest.
func (h *Handler) AgyCliUpdateHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "POST only"})
		return
	}
	bin := h.agyPath()
	if !agyInstalled(bin) {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "agy is not installed at " + bin})
		return
	}
	h.startAgyAsyncJob(w, "update", func(ctx context.Context, job *agyCliJob) int {
		if _, err := h.installAgyNative(ctx, job, bin, true); err != nil {
			job.logf("Error: %v", err)
			return 1
		}
		return 0
	})
}

func (h *Handler) startAgyAsyncJob(w http.ResponseWriter, kind string, fn func(ctx context.Context, job *agyCliJob) int) {
	agyJobMu.Lock()
	if agyJob != nil {
		agyJob.mu.Lock()
		running := agyJob.Running
		runningKind := agyJob.Kind
		agyJob.mu.Unlock()
		if running {
			agyJobMu.Unlock()
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "an agy " + runningKind + " is already running"})
			return
		}
	}
	job := &agyCliJob{Kind: kind, Running: true}
	agyJob = job
	agyJobMu.Unlock()

	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Minute)
		defer cancel()
		code := fn(ctx, job)
		job.mu.Lock()
		job.Running = false
		job.ExitCode = &code
		job.mu.Unlock()
	}()
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": job.snapshot()})
}
