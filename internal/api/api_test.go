package api

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"tracker/internal/store"
)

func setup(t *testing.T) *httptest.Server {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	srv := httptest.NewServer(Handler(st, "0.1.0", "2026-10-22", "test"))
	t.Cleanup(srv.Close)
	return srv
}

// call sends body (a string is sent verbatim, anything else as JSON) and
// decodes the response into out. It returns the status and the raw body.
func call(t *testing.T, srv *httptest.Server, method, path string, body any, out any) (int, string) {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		if s, ok := body.(string); ok {
			buf.WriteString(s)
		} else {
			json.NewEncoder(&buf).Encode(body)
		}
	}
	req, _ := http.NewRequest(method, srv.URL+path, &buf)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if ct := resp.Header.Get("Content-Type"); ct != "application/json" {
		t.Fatalf("%s %s: Content-Type %q", method, path, ct)
	}
	raw, _ := io.ReadAll(resp.Body)
	if out != nil && resp.StatusCode < 300 {
		if err := json.Unmarshal(raw, out); err != nil {
			t.Fatalf("decode %s %s: %v", method, path, err)
		}
	}
	return resp.StatusCode, string(raw)
}

func TestVersion(t *testing.T) {
	srv := setup(t)
	var v map[string]string
	if c, _ := call(t, srv, "GET", "/api/version", nil, &v); c != 200 {
		t.Fatalf("version: %d", c)
	}
	if v["version"] != "0.1.0" || v["date"] != "2026-10-22" || v["build"] != "test" {
		t.Fatalf("version: %v", v)
	}
	if v["app"] != "tracker" || len(v["server_id"]) != 36 {
		t.Fatalf("version should identify the server: %v", v)
	}
	// The id is stable across requests (and, by construction, restarts).
	var again map[string]string
	call(t, srv, "GET", "/api/version", nil, &again)
	if again["server_id"] != v["server_id"] {
		t.Fatalf("server_id changed: %s -> %s", v["server_id"], again["server_id"])
	}
}

func TestEntityIconAndColor(t *testing.T) {
	srv := setup(t)
	var e map[string]any
	call(t, srv, "PUT", "/api/entities/p", map[string]any{"name": "P"}, &e)
	if e["icon"] != "" || e["color"] != "" {
		t.Fatalf("defaults: %v", e)
	}
	// A deliberate icon change, without a name.
	if c, b := call(t, srv, "PUT", "/api/entities/p", map[string]any{"icon": "mx_shop_car", "color": "#FF8800"}, &e); c != 200 {
		t.Fatalf("set icon: %d %s", c, b)
	}
	if e["icon"] != "mx_shop_car" || e["color"] != "#ff8800" || e["name"] != "P" {
		t.Fatalf("icon set: %v", e)
	}
	// The routine name-only PUT must not touch them.
	call(t, srv, "PUT", "/api/entities/p", map[string]any{"name": "P2"}, &e)
	if e["icon"] != "mx_shop_car" || e["color"] != "#ff8800" || e["name"] != "P2" {
		t.Fatalf("name-only PUT changed icon/color: %v", e)
	}
	// An empty string clears; a no-op body is a 200 no-op.
	call(t, srv, "PUT", "/api/entities/p", map[string]any{"icon": "", "color": ""}, &e)
	if e["icon"] != "" || e["color"] != "" {
		t.Fatalf("clear: %v", e)
	}
	if c, _ := call(t, srv, "PUT", "/api/entities/p", map[string]any{}, &e); c != 200 {
		t.Fatalf("empty patch on existing entity: %d", c)
	}
	// Creation may carry them.
	call(t, srv, "PUT", "/api/entities/q", map[string]any{"name": "Q", "icon": "ic_action_bicycle_dark", "color": "#00aa00"}, &e)
	if e["icon"] != "ic_action_bicycle_dark" || e["color"] != "#00aa00" {
		t.Fatalf("create with icon: %v", e)
	}
	var list []map[string]any
	call(t, srv, "GET", "/api/entities", nil, &list)
	if len(list) != 2 || list[1]["icon"] != "ic_action_bicycle_dark" {
		t.Fatalf("list carries icons: %v", list)
	}
}

func TestEntityAndFixLifecycle(t *testing.T) {
	srv := setup(t)
	var e map[string]any
	c, _ := call(t, srv, "PUT", "/api/entities/phone-1", map[string]any{"name": "Evan", "source": "phone"}, &e)
	if c != 200 || e["id"] != "phone-1" || e["name"] != "Evan" || e["source"] != "phone" {
		t.Fatalf("put: %d %v", c, e)
	}
	if v, ok := e["latest"]; !ok || v != nil {
		t.Fatalf("latest should be an explicit null: %v", e)
	}
	// Empty list entry shape and the no-source default.
	var es []map[string]any
	call(t, srv, "PUT", "/api/entities/b", map[string]any{"name": "Bea"}, nil)
	if c, _ := call(t, srv, "GET", "/api/entities", nil, &es); c != 200 || len(es) != 2 || es[0]["name"] != "Bea" || es[1]["source"] != "phone" {
		t.Fatalf("list: %d %v", c, es)
	}

	// Post a batch; nullable fields present as null; unknown fields ignored.
	body := `[
		{"device_time":"2026-10-22T18:00:00Z","lat":45.5,"lon":-73.6,"accuracy_m":12.5,"altitude_m":40.0,
		 "speed_mps":1.2,"heading_deg":270.0,"battery_pct":83,"metadata":{"provider":"gps"},"extra":"ignored"},
		{"lat":45.51,"lon":-73.61}
	]`
	var res struct {
		Accepted int             `json:"accepted"`
		Latest   json.RawMessage `json:"latest"`
	}
	c, raw := call(t, srv, "POST", "/api/entities/phone-1/fixes", body, &res)
	if c != 201 || res.Accepted != 2 {
		t.Fatalf("post: %d %s", c, raw)
	}
	var latest map[string]any
	if err := json.Unmarshal(res.Latest, &latest); err != nil {
		t.Fatal(err)
	}
	if latest["id"].(float64) != 2 || latest["lat"] != 45.51 || latest["entity_id"] != "phone-1" {
		t.Fatalf("latest: %v", latest)
	}
	for _, k := range []string{"device_time", "accuracy_m", "altitude_m", "speed_mps", "heading_deg", "battery_pct", "metadata"} {
		if v, ok := latest[k]; !ok || v != nil {
			t.Fatalf("%s should be an explicit null in %s", k, res.Latest)
		}
	}
	if _, err := time.Parse(time.RFC3339Nano, latest["received_at"].(string)); err != nil {
		t.Fatal(err)
	}

	// The entity now carries it, in the list and alone.
	var got map[string]any
	call(t, srv, "GET", "/api/entities/phone-1", nil, &got)
	if l, _ := got["latest"].(map[string]any); l == nil || l["id"].(float64) != 2 {
		t.Fatalf("entity latest: %v", got)
	}
	call(t, srv, "GET", "/api/entities", nil, &es)
	if es[1]["latest"] == nil || es[0]["latest"] != nil {
		t.Fatalf("list latest: %v", es)
	}

	// History, oldest first, with the first fix's fields intact.
	var fixes []map[string]any
	if c, _ := call(t, srv, "GET", "/api/entities/phone-1/fixes", nil, &fixes); c != 200 || len(fixes) != 2 {
		t.Fatalf("history: %d %v", c, fixes)
	}
	f := fixes[0]
	if f["id"].(float64) != 1 || f["device_time"] != "2026-10-22T18:00:00Z" || f["battery_pct"].(float64) != 83 ||
		f["heading_deg"].(float64) != 270 || f["metadata"].(map[string]any)["provider"] != "gps" {
		t.Fatalf("first fix: %v", f)
	}

	// Rename propagates; source stays; updated_at moves.
	time.Sleep(2 * time.Millisecond)
	var re map[string]any
	call(t, srv, "PUT", "/api/entities/phone-1", map[string]any{"name": "Evan L"}, &re)
	if re["name"] != "Evan L" || re["source"] != "phone" || re["updated_at"] == e["updated_at"] || re["created_at"] != e["created_at"] {
		t.Fatalf("rename: %v", re)
	}
	if l, _ := re["latest"].(map[string]any); l == nil || l["id"].(float64) != 2 {
		t.Fatalf("rename should keep latest: %v", re)
	}
}

func TestErrors(t *testing.T) {
	srv := setup(t)
	call(t, srv, "PUT", "/api/entities/p", map[string]any{"name": "P"}, nil)
	cases := []struct {
		name         string
		method, path string
		body         any
		want         int
	}{
		{"unknown entity", "GET", "/api/entities/ghost", nil, 404},
		{"unknown entity fixes", "GET", "/api/entities/ghost/fixes", nil, 404},
		{"post to unknown entity", "POST", "/api/entities/ghost/fixes", `[{"lat":1,"lon":1}]`, 404},
		{"unknown path", "GET", "/api/nope", nil, 404},
		{"bad id", "PUT", "/api/entities/a%20b", map[string]any{"name": "x"}, 400},
		{"missing name on new entity", "PUT", "/api/entities/new", map[string]any{}, 400},
		{"bad icon", "PUT", "/api/entities/p", map[string]any{"icon": "Bad Icon"}, 400},
		{"bad color", "PUT", "/api/entities/p", map[string]any{"color": "red"}, 400},
		{"blank name", "PUT", "/api/entities/p", map[string]any{"name": "  "}, 400},
		{"bad source", "PUT", "/api/entities/p", map[string]any{"name": "x", "source": "Phone"}, 400},
		{"source change", "PUT", "/api/entities/p", map[string]any{"name": "x", "source": "aprs"}, 400},
		{"not json", "PUT", "/api/entities/p", `{"name":`, 400},
		{"empty batch", "POST", "/api/entities/p/fixes", `[]`, 400},
		{"null batch", "POST", "/api/entities/p/fixes", `null`, 400},
		{"object not array", "POST", "/api/entities/p/fixes", `{"lat":1,"lon":1}`, 400},
		{"missing lat", "POST", "/api/entities/p/fixes", `[{"lon":1}]`, 400},
		{"null lon", "POST", "/api/entities/p/fixes", `[{"lat":1,"lon":null}]`, 400},
		{"lat out of range", "POST", "/api/entities/p/fixes", `[{"lat":91,"lon":1}]`, 400},
		{"battery fraction", "POST", "/api/entities/p/fixes", `[{"lat":1,"lon":1,"battery_pct":83.5}]`, 400},
		{"battery string", "POST", "/api/entities/p/fixes", `[{"lat":1,"lon":1,"battery_pct":"83"}]`, 400},
		{"metadata array", "POST", "/api/entities/p/fixes", `[{"lat":1,"lon":1,"metadata":[1]}]`, 400},
		{"bad device_time", "POST", "/api/entities/p/fixes", `[{"lat":1,"lon":1,"device_time":"noon"}]`, 400},
		{"bad from", "GET", "/api/entities/p/fixes?from=yesterday", nil, 400},
		{"bad to", "GET", "/api/entities/p/fixes?to=2026-13-01T00:00:00Z", nil, 400},
		{"bad limit", "GET", "/api/entities/p/fixes?limit=x", nil, 400},
		{"zero limit", "GET", "/api/entities/p/fixes?limit=0", nil, 400},
		{"huge limit", "GET", "/api/entities/p/fixes?limit=10001", nil, 400},
		{"wrong method entities", "POST", "/api/entities", `{}`, 405},
		{"wrong method entity", "DELETE", "/api/entities/p", nil, 405},
		{"wrong method fixes", "PUT", "/api/entities/p/fixes", `[]`, 405},
		{"wrong method version", "POST", "/api/version", nil, 405},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			code, raw := call(t, srv, c.method, c.path, c.body, nil)
			if code != c.want {
				t.Fatalf("%s %s: got %d want %d (%s)", c.method, c.path, code, c.want, raw)
			}
			var e map[string]string
			if err := json.Unmarshal([]byte(raw), &e); err != nil || e["error"] == "" {
				t.Fatalf("error body: %s", raw)
			}
		})
	}
	// A rejected batch inserts nothing (all-or-nothing).
	c, raw := call(t, srv, "POST", "/api/entities/p/fixes", `[{"lat":1,"lon":1},{"lat":1,"lon":181}]`, nil)
	if c != 400 || !strings.Contains(raw, "fix 1") {
		t.Fatalf("mixed batch: %d %s", c, raw)
	}
	var fixes []map[string]any
	call(t, srv, "GET", "/api/entities/p/fixes", nil, &fixes)
	if len(fixes) != 0 {
		t.Fatalf("nothing should be inserted: %v", fixes)
	}
	// Too many fixes.
	big := "[" + strings.Repeat(`{"lat":1,"lon":1},`, store.MaxBatch) + `{"lat":1,"lon":1}]`
	if c, _ := call(t, srv, "POST", "/api/entities/p/fixes", big, nil); c != 400 {
		t.Fatalf("oversize batch: %d", c)
	}
}

func TestFixRangeQuery(t *testing.T) {
	srv := setup(t)
	call(t, srv, "PUT", "/api/entities/p", map[string]any{"name": "P"}, nil)
	var stamps []string
	for i := 0; i < 3; i++ {
		var res struct {
			Latest map[string]any `json:"latest"`
		}
		call(t, srv, "POST", "/api/entities/p/fixes", []map[string]any{{"lat": i, "lon": 0}}, &res)
		stamps = append(stamps, res.Latest["received_at"].(string))
		time.Sleep(2 * time.Millisecond)
	}
	var fixes []map[string]any
	path := "/api/entities/p/fixes?from=" + stamps[1] + "&to=" + stamps[2]
	if c, _ := call(t, srv, "GET", path, nil, &fixes); c != 200 || len(fixes) != 1 || fixes[0]["lat"].(float64) != 1 {
		t.Fatalf("range: %d %v", c, fixes)
	}
	// An offset timestamp is accepted and compared in UTC.
	t1, _ := time.Parse(time.RFC3339Nano, stamps[1])
	path = "/api/entities/p/fixes?from=" + strings.ReplaceAll(t1.In(time.FixedZone("x", -4*3600)).Format(time.RFC3339Nano), "+", "%2B")
	if c, _ := call(t, srv, "GET", path, nil, &fixes); c != 200 || len(fixes) != 2 {
		t.Fatalf("offset from: %d %v", c, fixes)
	}
	if c, _ := call(t, srv, "GET", "/api/entities/p/fixes?limit=2", nil, &fixes); c != 200 || len(fixes) != 2 || fixes[1]["lat"].(float64) != 1 {
		t.Fatalf("limit: %d %v", c, fixes)
	}
}
