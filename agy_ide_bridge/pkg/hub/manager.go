package hub

import (
	"bufio"
	"context"
	"debug/elf"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"gemini-server/pkg/config"
	"gemini-server/pkg/security"
)

var (
	loginURLRegex   = regexp.MustCompile(`https://accounts\.google\.com/[^\s"'<>]+`)
	csrfJSONPattern = regexp.MustCompile(`"csrfToken":\s*"([^"]+)"`)
	csrfHTMLPattern = regexp.MustCompile(`(?i)(?:csrf[_-]?token|csrfToken)["']?\s*[:=]\s*["']([^"']+)["']`)
)

const (
	HubStatusIdle     = "idle"
	HubStatusStarting = "starting"
	HubStatusOnline   = "online"
	HubStatusError    = "error"
	HubStatusStopped  = "stopped"
)

// HubManager supervises the background `agy --hub` process on port 1235.
type HubManager struct {
	HubPort        string
	WorkspaceDir   string
	AppDataDir     string
	AgyBinPath     string
	SecurityToken  string
	OnLoginURL     func(url string)
	OnStatusChange func(status string, csrfToken string, errorMsg string, logs []string)
	// Disabled: Antigravity is turned off in the app (--no-hub). The hub port is never probed and the hub never
	// started; set before the manager is used and not changed afterwards.
	Disabled bool

	cmd         *exec.Cmd
	stdinPipe   io.WriteCloser
	cancel      context.CancelFunc
	status      string
	csrfToken   string
	lastError   string
	isRunning   bool
	isExternal  bool
	processDone chan struct{}
	monitorStop chan struct{}
	recentLogs  []string
	// for the app's diagnostics after a failed call: when this hub process started, how the last one ended, and
	// what a crash printed (kept apart from recentLogs, whose 50 lines a long goroutine dump would fill)
	startedAt    time.Time
	lastExitAt   time.Time
	lastExitErr  string
	crashLines   []string
	crashCapture int
	mu           sync.Mutex
	// agyBinShadow mirrors AgyBinPath so the status endpoints never wait for mu
	agyBinShadow atomic.Value
}

// IsLegacyAgyWrapper reports whether path is an old shell wrapper script written by earlier versions of the app
// before agy was actually installed.
func IsLegacyAgyWrapper(path string) bool {
	if path == "" {
		return false
	}
	fi, err := os.Stat(path)
	if err != nil || fi.IsDir() || fi.Size() > 8192 {
		return false
	}
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	buf := make([]byte, 4096)
	n, _ := f.Read(buf)
	content := string(buf[:n])
	return strings.Contains(content, "Antigravity (agy) launcher written by AntiGem")
}

// CleanupLegacyAgyWrappers removes any dummy wrapper scripts created by older app builds and restores ~/.local/bin/agy.real
// back to ~/.local/bin/agy if present.
func CleanupLegacyAgyWrappers() {
	home, _ := os.UserHomeDir()
	if home != "" {
		localBin := filepath.Join(home, ".local", "bin")
		realFile := filepath.Join(localBin, "agy.real")
		agyFile := filepath.Join(localBin, "agy")
		if fi, err := os.Stat(realFile); err == nil && !fi.IsDir() {
			if _, err2 := os.Stat(agyFile); err2 != nil || IsLegacyAgyWrapper(agyFile) {
				_ = os.Remove(agyFile)
				_ = os.Rename(realFile, agyFile)
				_ = os.Chmod(agyFile, 0o755)
			}
		}
	}
	var wrapperCandidates []string
	if home != "" {
		wrapperCandidates = append(wrapperCandidates,
			filepath.Join(home, ".local", "bin", "agy"),
			filepath.Join(home, "..", "usr", "bin", "agy"),
		)
	}
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		wrapperCandidates = append(wrapperCandidates, filepath.Join(prefix, "bin", "agy"))
	}
	wrapperCandidates = append(wrapperCandidates,
		"/data/data/com.termux/files/usr/bin/agy",
		"/data/data/com.antigem/files/usr/bin/agy",
	)
	for _, p := range wrapperCandidates {
		if IsLegacyAgyWrapper(p) {
			_ = os.Remove(p)
		}
	}
}

// GlibcInterp reports whether bin is an ELF executable whose PT_INTERP requests a glibc dynamic linker (ld-linux*).
// Native Android Bionic executables use /system/bin/linker64 and must NOT be launched via glibc's ld-linux.
func GlibcInterp(bin string) bool {
	f, err := elf.Open(bin)
	if err != nil {
		return false
	}
	defer f.Close()
	for _, p := range f.Progs {
		if p.Type == elf.PT_INTERP {
			buf := make([]byte, p.Filesz)
			if _, err := p.ReadAt(buf, 0); err == nil {
				return strings.Contains(string(buf), "ld-linux")
			}
		}
	}
	return false
}

// FindAllAgyBinaries discovers all existing AGY binaries in candidate paths and PATH.
func FindAllAgyBinaries() []string {
	CleanupLegacyAgyWrappers()
	home, _ := os.UserHomeDir()
	var rawCandidates []string
	if home != "" {
		rawCandidates = append(rawCandidates,
			filepath.Join(home, ".local", "bin", "agy"),
			filepath.Join(home, ".gemini", "bin", "agy"),
			filepath.Join(home, ".antigravity", "bin", "agy"),
			filepath.Join(home, "usr", "bin", "agy"),
			filepath.Join(home, "..", "usr", "bin", "agy"),
		)
	}
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		rawCandidates = append(rawCandidates, filepath.Join(prefix, "bin", "agy"))
	}
	rawCandidates = append(rawCandidates,
		"/root/.local/bin/agy",
		"/root/.gemini/bin/agy",
		"/root/.antigravity/bin/agy",
		"/antigem/bin/agy",
		"/usr/local/bin/agy",
		"/usr/bin/agy",
		"/data/data/com.termux/files/home/.local/bin/agy",
		"/data/data/com.termux/files/usr/bin/agy",
	)
	if p := config.SafeLookPath("agy"); p != "" {
		rawCandidates = append(rawCandidates, p)
	}

	seen := make(map[string]bool)
	var results []string

	for _, c := range rawCandidates {
		if c == "" {
			continue
		}
		absPath, err := filepath.Abs(c)
		if err != nil {
			absPath = c
		}
		absPath = filepath.Clean(absPath)
		if seen[absPath] {
			continue
		}
		if fi, err := os.Stat(absPath); err == nil && !fi.IsDir() && !IsLegacyAgyWrapper(absPath) {
			seen[absPath] = true
			results = append(results, absPath)
		}
	}
	return results
}

// ResolveAgyBinaryInteractive resolves the AGY binary path. If customPath is provided, it validates existence.
// If multiple binaries are found, it prompts interactively (or defaults to top priority if forceAuto).
func ResolveAgyBinaryInteractive(customPath string, forceAuto bool) (string, error) {
	if customPath != "" {
		expanded := config.ExpandHome(customPath)
		cleanPath, err := filepath.Abs(expanded)
		if err != nil {
			cleanPath = expanded
		}
		cleanPath = filepath.Clean(cleanPath)
		if fi, err := os.Stat(cleanPath); err != nil || fi.IsDir() {
			if bins := FindAllAgyBinaries(); len(bins) > 0 {
				return bins[0], nil
			}
			return "", fmt.Errorf("specified AGY binary not found at '%s' (resolved: '%s')", customPath, cleanPath)
		}
		return cleanPath, nil
	}

	bins := FindAllAgyBinaries()
	if len(bins) == 0 {
		return "", errors.New("no AGY binary found in ~/.local/bin, ~/.gemini/bin, ~/.antigravity/bin, ~/usr/bin, ~/../usr/bin, or PATH")
	}

	if len(bins) == 1 || forceAuto {
		return bins[0], nil
	}

	// Interactive selection
	fmt.Println("\n \033[1;33m⚡ Multiple AGY binaries detected:\033[0m")
	for idx, b := range bins {
		fmt.Printf("   \033[1m[%d]\033[0m %s\n", idx+1, b)
	}
	fmt.Printf(" \033[1;36m? Select AGY binary to run [1-%d] (default: 1): \033[0m", len(bins))

	reader := bufio.NewReader(os.Stdin)
	input, err := reader.ReadString('\n')
	if err != nil {
		return bins[0], nil
	}
	trimmed := strings.TrimSpace(input)
	if trimmed == "" {
		return bins[0], nil
	}
	choice, err := strconv.Atoi(trimmed)
	if err != nil || choice < 1 || choice > len(bins) {
		fmt.Printf(" \033[33mℹ Invalid selection '%s'. Defaulting to [1] %s\033[0m\n", trimmed, bins[0])
		return bins[0], nil
	}
	return bins[choice-1], nil
}

// ExpandAgyPath turns a configured agy path (`~/...`, relative) into the absolute path the hub uses, whether or not
// the file exists yet.
func ExpandAgyPath(p string) string {
	expanded := config.ExpandHome(strings.TrimSpace(p))
	if abs, err := filepath.Abs(expanded); err == nil {
		expanded = abs
	}
	return filepath.Clean(expanded)
}

// InstallerAgyPath is where Google's installer (antigravity.google/cli/install.sh) puts agy: ~/.local/bin/agy.
func InstallerAgyPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".local", "bin", "agy")
}

// ResolveAgyBinary is the default non-interactive fallback.
func ResolveAgyBinary() string {
	bins := FindAllAgyBinaries()
	if len(bins) > 0 {
		return bins[0]
	}
	return "agy"
}

// ResolvePatchTarget determines the target binary for CSRF header patching.
// If the selected binary is < 10 MB (wrapper script), it checks for companion ELF binaries (agy.va39, agy.real, etc.).
func ResolvePatchTarget(binPath string) string {
	if binPath == "" {
		return ""
	}
	if realPath, err := filepath.EvalSymlinks(binPath); err == nil && realPath != "" {
		if fi, err2 := os.Stat(realPath); err2 == nil && !fi.IsDir() && fi.Size() >= 10*1024*1024 {
			return realPath
		}
	}
	fi, err := os.Stat(binPath)
	if err == nil && fi.Size() >= 10*1024*1024 {
		return binPath
	}

	// If < 10 MB, search in same directory for companion binaries
	dir := filepath.Dir(binPath)
	for _, name := range []string{"agy.va39", "agy.real", ".agy-real", "agy-linux-arm64", "agy-linux-aarch64"} {
		companion := filepath.Join(dir, name)
		if fi2, err2 := os.Stat(companion); err2 == nil && !fi2.IsDir() && fi2.Size() >= 10*1024*1024 {
			return companion
		}
	}

	// Also check ~/.local/bin/agy.real or ~/.local/share/antigravity if binPath is a wrapper in $PREFIX/bin
	if home, err := os.UserHomeDir(); err == nil && home != "" {
		for _, cand := range []string{
			filepath.Join(home, ".local", "bin", "agy.real"),
			filepath.Join(home, ".local", "bin", "agy.va39"),
			filepath.Join(home, ".local", "bin", "agy"),
			filepath.Join(home, ".antigravity", "bin", "agy"),
		} {
			if fi2, err2 := os.Stat(cand); err2 == nil && !fi2.IsDir() && fi2.Size() >= 10*1024*1024 {
				return cand
			}
		}
	}

	// If binPath is a small wrapper script, do not attempt to binary-patch the shell script itself
	if err == nil && fi.Size() < 1024*1024 {
		return ""
	}

	return binPath
}

// NewHubManager initializes a supervisor for AGY Hub.
func NewHubManager(hubPort, workspaceDir, appDataDir, securityToken, agyBinPath string) *HubManager {
	if hubPort == "" {
		hubPort = "1235"
	}
	m := &HubManager{
		HubPort:       hubPort,
		WorkspaceDir:  workspaceDir,
		AppDataDir:    appDataDir,
		SecurityToken: securityToken,
		AgyBinPath:    agyBinPath,
		status:        HubStatusIdle,
		recentLogs:    make([]string, 0, 50),
		monitorStop:   make(chan struct{}),
	}
	m.agyBinShadow.Store(agyBinPath)
	return m
}

// isHubReady probes if port 1235 is active and accepting connections, logging probe details for debugging
func (m *HubManager) isHubReady() bool {
	if m.Disabled {
		return false
	}
	start := time.Now()
	conn, err := net.DialTimeout("tcp", "127.0.0.1:"+m.HubPort, 400*time.Millisecond)
	elapsed := time.Since(start)

	if err != nil {
		log.Printf("\033[90m[Hub Probe] 127.0.0.1:%s -> Not connected (%v in %v) | Assumed state: offline\033[0m", m.HubPort, err, elapsed)
		return false
	}
	_ = conn.Close()

	log.Printf("\033[32m[Hub Probe] 127.0.0.1:%s -> TCP Connected OK in %v | Assumed state: ONLINE\033[0m", m.HubPort, elapsed)
	return true
}

// FetchCsrfToken attempts to retrieve the active CSRF token directly from the Hub root endpoint.
func (m *HubManager) FetchCsrfToken() string {
	url := fmt.Sprintf("http://127.0.0.1:%s/", m.HubPort)
	client := &http.Client{
		Timeout: 1500 * time.Millisecond,
	}
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		log.Printf("[FetchCsrfToken] NewRequest error: %v", err)
		return ""
	}
	req.Header.Set("User-Agent", "antiGem-Go-Supervisor")

	resp, err := client.Do(req)
	if err != nil {
		log.Printf("[FetchCsrfToken] GET %s error: %v", url, err)
		return ""
	}
	defer resp.Body.Close()

	if m.SecurityToken != "" {
		framed := security.BuildFramedHeader(m.SecurityToken)
		if headerToken := strings.TrimSpace(resp.Header.Get(framed)); headerToken != "" {
			log.Printf("[FetchCsrfToken] Found token in framed header (%s): %s", framed, headerToken)
			return headerToken
		}
	}

	if headerToken := strings.TrimSpace(resp.Header.Get("x-codeium-csrf-token")); headerToken != "" {
		log.Printf("[FetchCsrfToken] Found token in header: %s", headerToken)
		return headerToken
	}

	bodyBytes, err := io.ReadAll(io.LimitReader(resp.Body, 128*1024))
	if err != nil {
		log.Printf("[FetchCsrfToken] Read body error: %v", err)
		return ""
	}
	bodyStr := string(bodyBytes)

	if match := csrfJSONPattern.FindStringSubmatch(bodyStr); len(match) > 1 {
		token := strings.TrimSpace(match[1])
		if token != "" {
			log.Printf("[FetchCsrfToken] Extracted token via JSON pattern: %s", token)
			return token
		}
	}

	if match := csrfHTMLPattern.FindStringSubmatch(bodyStr); len(match) > 1 {
		token := strings.TrimSpace(match[1])
		if token != "" {
			log.Printf("[FetchCsrfToken] Extracted token via HTML pattern: %s", token)
			return token
		}
	}

	log.Printf("[FetchCsrfToken] No CSRF token matched in body (%d bytes)", len(bodyStr))
	return ""
}

// GetStatusInfo returns the current status, csrf token, last error, and captured logs.
func (m *HubManager) GetStatusInfo() (string, string, string, []string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	logsCopy := make([]string, len(m.recentLogs))
	copy(logsCopy, m.recentLogs)
	return m.status, m.csrfToken, m.lastError, logsCopy
}

// SetStopped reports the hub as stopped (used when Antigravity is turned off).
func (m *HubManager) SetStopped() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.status = HubStatusStopped
}

func (m *HubManager) setStatus(status string, errorMsg string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.setStatusLocked(status, errorMsg)
}

func (m *HubManager) setStatusLocked(status string, errorMsg string) {
	oldStatus := m.status
	m.status = status
	m.lastError = errorMsg
	if status == HubStatusStopped || status == HubStatusError || status == HubStatusIdle {
		m.csrfToken = ""
	}
	csrfToken := m.csrfToken
	cb := m.OnStatusChange
	logsCopy := make([]string, len(m.recentLogs))
	copy(logsCopy, m.recentLogs)

	log.Printf("\033[1;36m[Hub State Update]\033[0m Transition: '%s' -> '%s' (csrf: %t, err: '%s')", oldStatus, status, csrfToken != "", errorMsg)

	if cb != nil {
		go cb(status, csrfToken, errorMsg, logsCopy)
	}
}

// StartContinuousMonitor actively checks AGY Hub state in the background and broadcasts updates.
// When online and CSRF token is present, it checks every 10 seconds; when offline, starting, or missing CSRF token, it checks every 1 second.
func (m *HubManager) StartContinuousMonitor() {
	go func() {
		for {
			interval := 1 * time.Second
			m.mu.Lock()
			if m.status == HubStatusOnline && m.csrfToken != "" {
				interval = 10 * time.Second
			}
			m.mu.Unlock()

			select {
			case <-m.monitorStop:
				return
			case <-time.After(interval):
				active := m.isHubReady()
				m.mu.Lock()
				currentStatus := m.status
				if active {
					tokenAcquired := false
					if m.csrfToken == "" {
						m.mu.Unlock()
						tok := m.FetchCsrfToken()
						m.mu.Lock()
						if tok != "" {
							m.csrfToken = tok
							tokenAcquired = true
						}
					}
					if currentStatus != HubStatusOnline || tokenAcquired {
						m.isRunning = true
						m.setStatusLocked(HubStatusOnline, "")
						log.Printf("\033[1;32m[Hub Monitor]\033[0m ✅ AGY Hub detected ONLINE on http://127.0.0.1:%s (CSRF token present: %t)", m.HubPort, m.csrfToken != "")
					}
				} else {
					if currentStatus == HubStatusOnline || currentStatus == HubStatusStarting {
						m.isRunning = false
						m.csrfToken = ""
						m.setStatusLocked(HubStatusStopped, "")
						log.Printf("\033[1;33m[Hub Monitor]\033[0m ⚠️ AGY Hub port %s became unreachable -> marked STOPPED", m.HubPort)
					}
				}
				m.mu.Unlock()
			}
		}
	}()
}

// GetAgyBin returns the agy binary the hub uses ("" if none was found yet).
func (m *HubManager) GetAgyBin() string {
	if v, ok := m.agyBinShadow.Load().(string); ok {
		return v
	}
	return ""
}

// SetAgyBin sets the agy binary to use (for example right after it was installed).
func (m *HubManager) SetAgyBin(path string) {
	m.agyBinShadow.Store(path)
	m.mu.Lock()
	m.AgyBinPath = path
	m.mu.Unlock()
}

// IsPortActive checks if the hub port is currently listening.
func (m *HubManager) IsPortActive() bool {
	return m.isHubReady()
}

// IsRunning reports whether the hub process is active.
func (m *HubManager) IsRunning() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.isRunning || m.isHubReady()
}

// Start launches agy --hub with interactive feedback, a spinner, and non-blocking update bypass.
func (m *HubManager) Start() error {
	if m.Disabled {
		return fmt.Errorf("Antigravity is turned off in the app")
	}
	m.mu.Lock()
	// 1. Check if already active and responding to RPC requests
	if m.isHubReady() {
		m.isRunning = true
		m.isExternal = true
		m.mu.Unlock()
		tok := m.FetchCsrfToken()
		m.mu.Lock()
		if tok != "" {
			m.csrfToken = tok
		}
		m.setStatusLocked(HubStatusOnline, "")
		m.mu.Unlock()
		fmt.Printf(" \033[32m[✓]\033[0m AGY Hub is already active and listening on http://127.0.0.1:%s\n", m.HubPort)
		return nil
	}

	m.setStatusLocked(HubStatusStarting, "")

	CleanupLegacyAgyWrappers()

	if m.AgyBinPath == "" {
		if bins := FindAllAgyBinaries(); len(bins) > 0 {
			m.AgyBinPath = bins[0]
			m.agyBinShadow.Store(bins[0])
		} else {
			err := fmt.Errorf("AGY executable binary not specified or found")
			m.setStatusLocked(HubStatusError, err.Error())
			m.mu.Unlock()
			return err
		}
	}
	// the app names the agy to use even before it is installed (Settings → Antigravity CLI → Install)
	if fi, statErr := os.Stat(m.AgyBinPath); statErr != nil || fi.IsDir() || IsLegacyAgyWrapper(m.AgyBinPath) {
		if bins := FindAllAgyBinaries(); len(bins) > 0 {
			log.Printf("[Hub] Configured AGY path %s not found; auto-switched to discovered binary %s", m.AgyBinPath, bins[0])
			m.AgyBinPath = bins[0]
			m.agyBinShadow.Store(bins[0])
		} else {
			err := fmt.Errorf("agy is not installed at %s (install it in the app: Settings → Antigravity CLI)", m.AgyBinPath)
			m.setStatusLocked(HubStatusError, err.Error())
			m.mu.Unlock()
			return err
		}
	}

	// In-place patch AGY binary with 20-byte framed security header when target binary is patchable
	if m.SecurityToken != "" {
		patchTarget := ResolvePatchTarget(m.AgyBinPath)
		if patchTarget != "" {
			patchedHeader, elapsed, err := security.PatchAgyHeader(patchTarget, m.SecurityToken)
			if err != nil {
				log.Printf("\033[1;33m[Security Warning]\033[0m Could not patch AGY binary (%s): %v (continuing with standard CSRF header fallback)", patchTarget, err)
			} else {
				log.Printf("\033[1;32m[Security]\033[0m In-place patched AGY binary (%s) in %v -> Active Header: %s", patchTarget, elapsed, patchedHeader)
			}
		}
	}

	args := []string{
		"--hub",
		"--hub-port=" + m.HubPort,
		"--app_data_dir=antigravity-cli",
	}

	wsDir := m.WorkspaceDir
	if wsDir == "" {
		if wd, err := os.Getwd(); err == nil {
			wsDir = wd
		}
	}
	if wsDir != "" {
		if fi, err := os.Stat(wsDir); err == nil && fi.IsDir() {
			args = append(args, "--add-dir="+wsDir)
		}
	}
	// files the app copied from the phone for its chats: readable even when outside-workspace access asks first
	if home, err := os.UserHomeDir(); err == nil && home != "" {
		if uploads := filepath.Join(home, ".antigem", "uploads"); os.MkdirAll(uploads, 0o700) == nil {
			args = append(args, "--add-dir="+uploads)
		}
	}

	ctx, cancel := context.WithCancel(context.Background())
	m.cancel = cancel

	home, _ := os.UserHomeDir()
	geminiBin := filepath.Join(home, ".gemini", "bin")
	localBin := filepath.Join(home, ".local", "bin")
	antigravityBin := filepath.Join(home, ".antigravity", "bin")
	usrBin := filepath.Join(home, "..", "usr", "bin")
	prefix := os.Getenv("PREFIX")
	if prefix != "" {
		usrBin = filepath.Join(prefix, "bin")
	}

	// On Termux (where /lib/ld-linux-aarch64.so.1 does not exist), only run AgyBinPath through the bundled glibc
	// loader if its ELF PT_INTERP actually asks for glibc (ld-linux*). Native Android Bionic builds of agy
	// (PT_INTERP = /system/bin/linker64) must be executed directly.
	var cmd *exec.Cmd
	usedGlibcLoader := false
	if _, sysLoaderErr := os.Stat("/lib/ld-linux-aarch64.so.1"); sysLoaderErr != nil && GlibcInterp(m.AgyBinPath) {
		glibcBase := filepath.Join(home, "..", "usr", "glibc", "lib")
		if prefix != "" {
			glibcBase = filepath.Join(prefix, "glibc", "lib")
		}
		glibcLoader := filepath.Join(glibcBase, "ld-linux-aarch64.so.1")
		if st, err := os.Stat(glibcLoader); err == nil && !st.IsDir() {
			loaderArgs := append([]string{"--library-path", glibcBase, m.AgyBinPath}, args...)
			cmd = exec.CommandContext(ctx, glibcLoader, loaderArgs...)
			usedGlibcLoader = true
			log.Printf("[Hub] Launching glibc ELF %s via Termux glibc loader %s", m.AgyBinPath, glibcLoader)
		}
	}

	if !usedGlibcLoader {
		shArgs := append([]string{"-c", `exec "$0" "$@"`, m.AgyBinPath}, args...)
		cmd = exec.CommandContext(ctx, "sh", shArgs...)
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	baseEnv := make([]string, 0, len(os.Environ())+14)
	hasTmp := false
	for _, kv := range os.Environ() {
		if usedGlibcLoader && strings.HasPrefix(kv, "LD_PRELOAD=") {
			continue // Never preload bionic libtermux-exec.so into a glibc process
		}
		if strings.HasPrefix(kv, "TMPDIR=") && len(kv) > len("TMPDIR=") {
			hasTmp = true
		}
		baseEnv = append(baseEnv, kv)
	}
	if !hasTmp {
		if tPrefix := config.TermuxPrefix(home); tPrefix != "" {
			tmpDir := filepath.Join(tPrefix, "tmp")
			_ = os.MkdirAll(tmpDir, 0o700)
			baseEnv = append(baseEnv, "TMPDIR="+tmpDir)
		}
	}

	cmd.Env = append(baseEnv,
		"HOME="+home,
		"USERPROFILE="+home,
		"PATH="+geminiBin+":"+localBin+":"+antigravityBin+":"+usrBin+":/antigem/bin:/root/.local/bin:/root/.gemini/bin:/root/.antigravity/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin:"+os.Getenv("PATH"),
		"AGY_ENABLE_HUB=1",
		"ANTIGRAVITY_VSCODE_HOST=1",
		"ANTIGRAVITY_AUTH_SUCCESS_APP=vscode",
		"CI=1",
		"AGY_NO_UPDATE_PROMPT=1",
		"DEBIAN_FRONTEND=noninteractive",
	)

	stdoutPipe, errOut := cmd.StdoutPipe()
	stderrPipe, errErr := cmd.StderrPipe()

	if err := cmd.Start(); err != nil {
		cancel()
		m.setStatusLocked(HubStatusError, err.Error())
		m.mu.Unlock()
		return fmt.Errorf("failed to start agy hub: %w", err)
	}

	m.cmd = cmd
	m.isRunning = true
	m.isExternal = false
	m.startedAt = time.Now()
	m.processDone = make(chan struct{})
	doneChan := m.processDone
	m.mu.Unlock()

	processExited := make(chan error, 1)
	var streamWg sync.WaitGroup

	// Stream stdout & stderr cleanly with [agy-hub] prefix and extract auth URLs
	if errOut == nil {
		streamWg.Add(1)
		go func() {
			defer streamWg.Done()
			scanner := bufio.NewScanner(stdoutPipe)
			for scanner.Scan() {
				m.handleHubLine(scanner.Text())
			}
		}()
	}
	if errErr == nil {
		streamWg.Add(1)
		go func() {
			defer streamWg.Done()
			scanner := bufio.NewScanner(stderrPipe)
			for scanner.Scan() {
				m.handleHubLine(scanner.Text())
			}
		}()
	}

	// Supervise process exit in background
	go func() {
		err := cmd.Wait()
		streamWg.Wait() // Ensure all remaining output is drained and processed
		m.mu.Lock()
		m.isRunning = false
		m.lastExitAt = time.Now()
		if err != nil {
			m.lastExitErr = err.Error()
		} else {
			m.lastExitErr = "exited normally"
		}
		if err != nil {
			m.setStatusLocked(HubStatusError, err.Error())
		} else {
			m.setStatusLocked(HubStatusStopped, "")
		}
		m.mu.Unlock()
		processExited <- err
		select {
		case <-doneChan:
		default:
			close(doneChan)
		}
	}()

	// Loading animation while waiting for port and RPC server readiness
	spinner := []string{"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"}
	spinIdx := 0
	ready := false
	startTime := time.Now()
	timeout := 5 * time.Minute

	for time.Since(startTime) < timeout {
		if !m.IsRunning() {
			return nil
		}

		select {
		case err := <-processExited:
			fmt.Printf("\r\033[K \033[31m[✗]\033[0m AGY Hub process terminated: %v\n", err)
			m.dumpRecentLogs()
			m.setStatus(HubStatusError, fmt.Sprintf("Process exited: %v", err))
			return fmt.Errorf("agy hub process exited unexpectedly: %w", err)
		default:
		}

		if m.isHubReady() {
			ready = true
			break
		}
		fmt.Printf("\r\033[K \033[36m[%s]\033[0m Starting AGY Hub on port %s...", spinner[spinIdx%len(spinner)], m.HubPort)
		spinIdx++
		time.Sleep(1 * time.Second)
	}

	if ready {
		tok := m.FetchCsrfToken()
		m.mu.Lock()
		if tok != "" {
			m.csrfToken = tok
		}
		m.setStatusLocked(HubStatusOnline, "")
		m.mu.Unlock()
		fmt.Printf("\r\033[K \033[32m[✓]\033[0m AGY Hub is online and listening on http://127.0.0.1:%s\n", m.HubPort)
	} else if m.IsRunning() {
		fmt.Printf("\r\033[K \033[33m[!]\033[0m AGY Hub started (PID %d), waiting for initialization on port %s...\n", cmd.Process.Pid, m.HubPort)
	}

	return nil
}

// Stop gracefully shuts down the agy --hub child process, its process group, and the continuous monitor.
func (m *HubManager) Stop() {
	m.mu.Lock()
	cmd := m.cmd
	cancel := m.cancel
	isRunning := m.isRunning
	isExternal := m.isExternal
	doneChan := m.processDone
	m.isRunning = false
	select {
	case <-m.monitorStop:
	default:
		close(m.monitorStop)
	}
	m.mu.Unlock()

	if !isRunning || isExternal {
		m.setStatus(HubStatusStopped, "")
		return
	}

	if cancel != nil {
		cancel()
	}

	if cmd != nil && cmd.Process != nil {
		pid := cmd.Process.Pid
		fmt.Printf("🛑 Stopping AGY Hub process tree (PID %d)...\n", pid)

		// Terminate the entire process group
		pgid, err := syscall.Getpgid(pid)
		if err == nil {
			_ = syscall.Kill(-pgid, syscall.SIGTERM)
		} else {
			_ = cmd.Process.Signal(syscall.SIGTERM)
		}

		stopped := false
		if doneChan != nil {
			select {
			case <-doneChan:
				stopped = true
				fmt.Println("✅ AGY Hub stopped cleanly.")
			case <-time.After(1000 * time.Millisecond):
			}
		}

		if !stopped {
			if pgid > 0 {
				_ = syscall.Kill(-pgid, syscall.SIGKILL)
			}
			_ = cmd.Process.Kill()
			_ = exec.Command("pkill", "-9", "-P", fmt.Sprintf("%d", pid)).Run()
			_ = exec.Command("pkill", "-9", "-f", "agy --hub").Run()
			fmt.Println("⚠️  AGY Hub force terminated.")
		}

		// Auto-unpatch binary back to standard header once process has exited and lock is released
		if m.AgyBinPath != "" {
			patchTarget := ResolvePatchTarget(m.AgyBinPath)
			if patchTarget != "" {
				restored, elapsed, err := security.UnpatchAgyHeader(patchTarget)
				if err == nil {
					log.Printf("\033[1;32m[Security]\033[0m Auto-unpatched AGY binary (%s) back to '%s' in %v", patchTarget, restored, elapsed)
				} else {
					log.Printf("\033[1;33m[Security Warning]\033[0m Could not auto-unpatch AGY binary: %v", err)
				}
			}
		}
	}

	m.setStatus(HubStatusStopped, "")
}

func (m *HubManager) handleHubLine(line string) {
	trimmed := strings.TrimSpace(line)
	if trimmed == "" {
		return
	}

	m.mu.Lock()
	if len(m.recentLogs) >= 50 {
		m.recentLogs = m.recentLogs[1:]
	}
	m.recentLogs = append(m.recentLogs, line)
	// a Go crash: its first lines say what happened (the goroutine dump after them can be thousands of lines)
	if strings.HasPrefix(trimmed, "panic:") || strings.HasPrefix(trimmed, "fatal error:") ||
		strings.HasPrefix(trimmed, "SIGSEGV") || strings.HasPrefix(trimmed, "SIGBUS") || strings.HasPrefix(trimmed, "SIGABRT") {
		m.crashCapture = 30
		m.crashLines = append(m.crashLines, "--- "+time.Now().Format("2006-01-02 15:04:05"))
	}
	if m.crashCapture > 0 {
		m.crashCapture--
		m.crashLines = append(m.crashLines, line)
		if len(m.crashLines) > 120 {
			m.crashLines = m.crashLines[len(m.crashLines)-120:]
		}
	}
	m.mu.Unlock()

	fmt.Printf("\r\033[K\033[90m[agy-hub]\033[0m %s\n", line)

	if m.OnLoginURL != nil && strings.Contains(trimmed, "accounts.google.com") {
		matches := loginURLRegex.FindAllString(trimmed, -1)
		for _, u := range matches {
			if strings.HasPrefix(u, "https://accounts.google.com") {
				fmt.Printf(" \033[1;32m[auth-url detected]\033[0m %s\n", u)
				m.OnLoginURL(u)
			}
		}
	}
}

// Diagnostics is the hub process's state for the app's diagnostics (a failed voice call shows it): whether it runs,
// when it started, how the previous process ended, what a crash printed and its last output lines.
type Diagnostics struct {
	Running      bool     `json:"running"`
	External     bool     `json:"external"`
	Status       string   `json:"status"`
	LastError    string   `json:"last_error"`
	StartedAtMs  int64    `json:"started_at_ms"`
	LastExitAtMs int64    `json:"last_exit_at_ms"`
	LastExitErr  string   `json:"last_exit_error"`
	CrashLines   []string `json:"crash_lines"`
	RecentOutput []string `json:"recent_output"`
}

func (m *HubManager) Diagnostics() Diagnostics {
	m.mu.Lock()
	d := Diagnostics{
		Running:      m.isRunning,
		External:     m.isExternal,
		Status:       m.status,
		LastError:    m.lastError,
		LastExitErr:  m.lastExitErr,
		CrashLines:   append([]string(nil), m.crashLines...),
		RecentOutput: append([]string(nil), m.recentLogs...),
	}
	if !m.startedAt.IsZero() {
		d.StartedAtMs = m.startedAt.UnixMilli()
	}
	if !m.lastExitAt.IsZero() {
		d.LastExitAtMs = m.lastExitAt.UnixMilli()
	}
	m.mu.Unlock()
	if !d.Running {
		d.Running = m.isHubReady()
	}
	return d
}

func (m *HubManager) dumpRecentLogs() {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.recentLogs) == 0 {
		fmt.Printf(" \033[33mℹ No output was captured from AGY binary.\033[0m Path: %s\n", m.AgyBinPath)
		return
	}
	fmt.Println(" \033[1;31m--- AGY Hub Output / Error Logs ---\033[0m")
	for _, l := range m.recentLogs {
		fmt.Printf("   \033[90m%s\033[0m\n", l)
	}
	fmt.Println(" \033[1;31m------------------------------------\033[0m")
}

