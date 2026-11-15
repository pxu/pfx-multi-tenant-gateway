// Command upstream is a small upstream service for demonstrating the gateway against something real:
// it echoes requests, waits as long as it is told, returns any status code, and can fail on purpose.
// Standard library only.
//
//	GET /delay/{ms}                       wait ms milliseconds (at most MAX_DELAY_MS), then echo
//	GET /random-delay?min=100&max=5000    wait a uniform random time in [min, max] ms, then echo
//	GET /status/{code}                    answer with that status code
//	GET /healthz                          200 "ok" (never fails, never waits)
//	anything else                         echo: method, path, query and headers as JSON
//
// Environment: PORT (8080), VERSION ("stable"; sent as X-Upstream-Version on every response),
// FAIL_PCT (0-100: that share of requests, except /healthz, gets 503 before anything else),
// MAX_DELAY_MS (30000).
package main

import (
	"encoding/json"
	"fmt"
	"log"
	"math/rand/v2"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

type config struct {
	version  string
	failPct  int
	maxDelay time.Duration
	random   func(n int) int // [0, n); replaced in tests
}

func configFromEnv() (config, error) {
	c := config{version: envOr("VERSION", "stable"), random: rand.IntN}
	var err error
	if c.failPct, err = strconv.Atoi(envOr("FAIL_PCT", "0")); err != nil || c.failPct < 0 || c.failPct > 100 {
		return c, fmt.Errorf("FAIL_PCT must be 0-100")
	}
	maxMs, err := strconv.Atoi(envOr("MAX_DELAY_MS", "30000"))
	if err != nil || maxMs < 0 {
		return c, fmt.Errorf("MAX_DELAY_MS must be >= 0")
	}
	c.maxDelay = time.Duration(maxMs) * time.Millisecond
	return c, nil
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// handler routes the endpoints above. The gateway appends the client's path to the upstream URL, so
// every path that is not one of the special ones is echoed.
func handler(c config) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		fmt.Fprintln(w, "ok")
	})
	mux.HandleFunc("/delay/{ms}", func(w http.ResponseWriter, r *http.Request) {
		ms, err := strconv.Atoi(r.PathValue("ms"))
		if err != nil || ms < 0 {
			http.Error(w, "delay must be a number of milliseconds", http.StatusBadRequest)
			return
		}
		wait(w, r, c, time.Duration(ms)*time.Millisecond)
	})
	mux.HandleFunc("/random-delay", func(w http.ResponseWriter, r *http.Request) {
		lo, err1 := strconv.Atoi(r.URL.Query().Get("min"))
		hi, err2 := strconv.Atoi(r.URL.Query().Get("max"))
		if err1 != nil || err2 != nil || lo < 0 || hi < lo {
			http.Error(w, "need 0 <= min <= max (milliseconds)", http.StatusBadRequest)
			return
		}
		wait(w, r, c, time.Duration(lo+c.random(hi-lo+1))*time.Millisecond)
	})
	mux.HandleFunc("/status/{code}", func(w http.ResponseWriter, r *http.Request) {
		code, err := strconv.Atoi(r.PathValue("code"))
		if err != nil || code < 200 || code > 599 {
			http.Error(w, "status must be 200-599", http.StatusBadRequest)
			return
		}
		w.WriteHeader(code)
		fmt.Fprintf(w, "status %d from %s\n", code, c.version)
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { echo(w, r, c, 0) })

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Upstream-Version", c.version)
		if r.URL.Path != "/healthz" && c.failPct > 0 && c.random(100) < c.failPct {
			http.Error(w, "injected failure ("+c.version+")", http.StatusServiceUnavailable)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

// wait sleeps for d (capped at maxDelay) unless the caller goes away first (e.g. a cancelled hedge).
func wait(w http.ResponseWriter, r *http.Request, c config, d time.Duration) {
	if d > c.maxDelay {
		d = c.maxDelay
	}
	select {
	case <-time.After(d):
		echo(w, r, c, d)
	case <-r.Context().Done():
	}
}

type echoResponse struct {
	Version string              `json:"version"`
	Method  string              `json:"method"`
	Path    string              `json:"path"`
	Query   string              `json:"query,omitempty"`
	DelayMs int64               `json:"delayMs"`
	Headers map[string][]string `json:"headers"`
}

func echo(w http.ResponseWriter, r *http.Request, c config, delay time.Duration) {
	headers := make(map[string][]string, len(r.Header))
	for k, v := range r.Header {
		headers[strings.ToLower(k)] = v
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(echoResponse{
		Version: c.version, Method: r.Method, Path: r.URL.Path, Query: r.URL.RawQuery,
		DelayMs: delay.Milliseconds(), Headers: headers,
	})
}

func main() {
	c, err := configFromEnv()
	if err != nil {
		log.Fatal(err)
	}
	srv := &http.Server{
		Addr:              ":" + envOr("PORT", "8080"),
		Handler:           handler(c),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       90 * time.Second, // longer than the gateway's 60s pool idle timeout
	}
	log.Printf("upstream %q on %s (fail %d%%, max delay %s)", c.version, srv.Addr, c.failPct, c.maxDelay)
	log.Fatal(srv.ListenAndServe())
}
