package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func testConfig() config {
	return config{version: "canary", maxDelay: 2 * time.Second, random: func(n int) int { return n - 1 }}
}

func do(t *testing.T, h http.Handler, path string, header map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	r := httptest.NewRequest(http.MethodGet, path, nil)
	for k, v := range header {
		r.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestEcho_AnyPath_ReturnsMethodPathQueryHeadersAndVersion(t *testing.T) {
	// Arrange
	h := handler(testConfig())

	// Act
	w := do(t, h, "/orders/7?x=1", map[string]string{"X-Tenant-Id": "acme"})

	// Assert
	var got echoResponse
	if err := json.NewDecoder(w.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if w.Code != 200 || got.Path != "/orders/7" || got.Query != "x=1" || got.Version != "canary" {
		t.Fatalf("unexpected echo: %d %+v", w.Code, got)
	}
	if got.Headers["x-tenant-id"][0] != "acme" || w.Header().Get("X-Upstream-Version") != "canary" {
		t.Fatalf("headers not echoed or version missing: %+v", got.Headers)
	}
}

func TestDelay_WaitsThenEchoes_CappedAtMaxDelay(t *testing.T) {
	h := handler(testConfig())

	start := time.Now()
	w := do(t, h, "/delay/50", nil)
	if w.Code != 200 || time.Since(start) < 50*time.Millisecond {
		t.Fatalf("expected 200 after >= 50ms, got %d after %s", w.Code, time.Since(start))
	}

	c := testConfig()
	c.maxDelay = 10 * time.Millisecond
	start = time.Now()
	do(t, handler(c), "/delay/5000", nil)
	if time.Since(start) > time.Second {
		t.Fatalf("delay not capped: %s", time.Since(start))
	}
}

func TestDelay_CallerGoesAway_StopsWaiting(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	r := httptest.NewRequest(http.MethodGet, "/delay/1500", nil).WithContext(ctx)

	start := time.Now()
	handler(testConfig()).ServeHTTP(httptest.NewRecorder(), r)

	if time.Since(start) > time.Second {
		t.Fatalf("kept waiting after the caller left: %s", time.Since(start))
	}
}

func TestRandomDelay_WithinBounds_BadBoundsRejected(t *testing.T) {
	h := handler(testConfig()) // random returns n-1: always the maximum

	w := do(t, h, "/random-delay?min=10&max=30", nil)
	var got echoResponse
	_ = json.NewDecoder(w.Body).Decode(&got)
	if w.Code != 200 || got.DelayMs != 30 {
		t.Fatalf("expected the upper bound 30ms, got %d %dms", w.Code, got.DelayMs)
	}
	for _, q := range []string{"?min=30&max=10", "?min=-1&max=5", "?min=a&max=5", ""} {
		if w := do(t, h, "/random-delay"+q, nil); w.Code != 400 {
			t.Fatalf("%q: expected 400, got %d", q, w.Code)
		}
	}
}

func TestStatus_ReturnsTheCode_InvalidRejected(t *testing.T) {
	h := handler(testConfig())

	if w := do(t, h, "/status/503", nil); w.Code != 503 {
		t.Fatalf("expected 503, got %d", w.Code)
	}
	if w := do(t, h, "/status/42", nil); w.Code != 400 {
		t.Fatalf("expected 400, got %d", w.Code)
	}
}

func TestFailPct_InjectsFailures_ExceptHealthz(t *testing.T) {
	c := testConfig()
	c.failPct = 30
	c.random = func(n int) int { return 0 } // always below the threshold: fail
	h := handler(c)

	if w := do(t, h, "/orders/1", nil); w.Code != 503 || w.Header().Get("X-Upstream-Version") != "canary" {
		t.Fatalf("expected an injected 503 with the version header, got %d", w.Code)
	}
	if w := do(t, h, "/healthz", nil); w.Code != 200 {
		t.Fatalf("healthz must never fail, got %d", w.Code)
	}

	c.random = func(n int) int { return 99 } // above the threshold: pass
	if w := do(t, handler(c), "/orders/1", nil); w.Code != 200 {
		t.Fatalf("expected 200, got %d", w.Code)
	}
}

func TestConfigFromEnv_ValidatesFailPctAndMaxDelay(t *testing.T) {
	t.Setenv("FAIL_PCT", "101")
	if _, err := configFromEnv(); err == nil {
		t.Fatal("FAIL_PCT 101 accepted")
	}
	t.Setenv("FAIL_PCT", "20")
	t.Setenv("MAX_DELAY_MS", "-5")
	if _, err := configFromEnv(); err == nil {
		t.Fatal("negative MAX_DELAY_MS accepted")
	}
	t.Setenv("MAX_DELAY_MS", "1000")
	t.Setenv("VERSION", "canary")
	c, err := configFromEnv()
	if err != nil || c.failPct != 20 || c.maxDelay != time.Second || c.version != "canary" {
		t.Fatalf("unexpected config %+v, %v", c, err)
	}
}
