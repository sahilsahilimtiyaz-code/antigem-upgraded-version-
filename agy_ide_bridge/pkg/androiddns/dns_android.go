//go:build linux || android

package androiddns

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"
)

// Android has no /etc/resolv.conf and no /etc/ssl/certs/ca-certificates.crt. When the bridge is cross-compiled with
// GOOS=linux and CGO_ENABLED=0, Go's built-in resolver falls back to [::1]:53 and Go's crypto/x509 only looks in
// desktop Linux certificate paths. We configure both DNS and TLS RootCAs at startup so every HTTPS download
// (OpenCode, Antigravity CLI, Claude Code, Codex, plugins) succeeds reliably on Android.
func init() {
	initAndroidTLS()
	initAndroidDNS()
}

func initAndroidTLS() {
	// If standard Linux CA bundle already exists (e.g. inside PRoot Ubuntu or desktop Linux), nothing to do.
	if fi, err := os.Stat("/etc/ssl/certs/ca-certificates.crt"); err == nil && !fi.IsDir() {
		return
	}

	var certFiles []string
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		certFiles = append(certFiles,
			filepath.Join(prefix, "etc", "tls", "cert.pem"),
			filepath.Join(prefix, "etc", "ssl", "certs", "ca-certificates.crt"),
		)
	}
	if home := os.Getenv("HOME"); home != "" {
		usrDir := filepath.Join(filepath.Dir(home), "usr")
		certFiles = append(certFiles,
			filepath.Join(usrDir, "etc", "tls", "cert.pem"),
			filepath.Join(usrDir, "etc", "ssl", "certs", "ca-certificates.crt"),
		)
	}
	certFiles = append(certFiles,
		"/data/data/com.termux/files/usr/etc/tls/cert.pem",
		"/data/data/com.termux/files/usr/etc/ssl/certs/ca-certificates.crt",
		"/data/data/com.antigem/files/usr/etc/tls/cert.pem",
	)

	if os.Getenv("SSL_CERT_FILE") == "" {
		for _, cf := range certFiles {
			if fi, err := os.Stat(cf); err == nil && !fi.IsDir() && fi.Size() > 0 {
				_ = os.Setenv("SSL_CERT_FILE", cf)
				break
			}
		}
	}

	certDirs := []string{
		"/system/etc/security/cacerts",
		"/apex/com.android.conscrypt/cacerts",
		"/data/misc/keychain/cacerts-added",
	}
	var existingDirs []string
	for _, dir := range certDirs {
		if fi, err := os.Stat(dir); err == nil && fi.IsDir() {
			existingDirs = append(existingDirs, dir)
		}
	}
	if os.Getenv("SSL_CERT_DIR") == "" && len(existingDirs) > 0 {
		_ = os.Setenv("SSL_CERT_DIR", strings.Join(existingDirs, ":"))
	}

	// Also build an explicit x509.CertPool from Termux cert.pem + Android system cacerts and wire it into
	// http.DefaultTransport so any http.Client created before or after env setup has full root CAs.
	pool := x509.NewCertPool()
	loaded := false
	for _, cf := range certFiles {
		if pemBytes, err := os.ReadFile(cf); err == nil && len(pemBytes) > 0 {
			if pool.AppendCertsFromPEM(pemBytes) {
				loaded = true
				break
			}
		}
	}
	for _, dir := range existingDirs {
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, e := range entries {
			if e.IsDir() {
				continue
			}
			if pemBytes, err := os.ReadFile(filepath.Join(dir, e.Name())); err == nil && len(pemBytes) > 0 {
				if pool.AppendCertsFromPEM(pemBytes) {
					loaded = true
				}
			}
		}
	}
	if loaded {
		if tr, ok := http.DefaultTransport.(*http.Transport); ok {
			if tr.TLSClientConfig == nil {
				tr.TLSClientConfig = &tls.Config{RootCAs: pool}
			} else if tr.TLSClientConfig.RootCAs == nil {
				tr.TLSClientConfig.RootCAs = pool
			}
		}
	}
}

func initAndroidDNS() {
	if _, err := os.Stat("/etc/resolv.conf"); err == nil {
		return
	}
	var files []string
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		files = append(files, filepath.Join(prefix, "etc", "resolv.conf"))
	}
	files = append(files,
		"/data/data/com.termux/files/usr/etc/resolv.conf",
		"/data/data/com.antigem/files/usr/etc/resolv.conf",
	)
	var servers []string
	for _, f := range files {
		if servers = readNameservers(f); len(servers) > 0 {
			break
		}
	}
	if len(servers) == 0 {
		servers = []string{"8.8.8.8:53", "1.1.1.1:53"}
	}
	var next uint32
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 5 * time.Second}
			start := int(atomic.AddUint32(&next, 1))
			var lastErr error
			for i := range servers {
				c, err := d.DialContext(ctx, network, servers[(start+i)%len(servers)])
				if err == nil {
					return c, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}

func readNameservers(path string) []string {
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) >= 2 && fields[0] == "nameserver" && net.ParseIP(fields[1]) != nil {
			out = append(out, net.JoinHostPort(fields[1], "53"))
		}
	}
	return out
}
