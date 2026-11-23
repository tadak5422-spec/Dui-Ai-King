// Package netutil holds the shared URL policy for user-configured endpoints in the Go bridge.
package netutil

import (
	"errors"
	"net"
	"net/url"
	"strings"
)

// LocalHost reports whether cleartext HTTP is acceptable for host: loopback, a private LAN range, a link-local
// address or a single-label /.local /.lan /.internal hostname. Public endpoints must use HTTPS.
func LocalHost(host string) bool {
	h := strings.ToLower(strings.Trim(host, "[]"))
	if h == "" || h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0.0.0.0" {
		return true
	}
	if strings.HasSuffix(h, ".local") || strings.HasSuffix(h, ".lan") || strings.HasSuffix(h, ".internal") {
		return true
	}
	if !strings.Contains(h, ".") && !strings.Contains(h, ":") {
		return true
	}
	if ip := net.ParseIP(h); ip != nil {
		return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast()
	}
	return false
}

// CheckEndpoint accepts an http(s) URL without embedded credentials and refuses cleartext to a public host.
func CheckEndpoint(raw string) error {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return errors.New("the URL must start with http:// or https://")
	}
	if u.User != nil {
		return errors.New("credentials in the URL are not allowed; configure the key in the provider settings")
	}
	if u.Scheme == "http" && !LocalHost(u.Hostname()) {
		return errors.New("a public endpoint must use https:// (http:// is only for this phone or the local network)")
	}
	return nil
}
