package fake

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"remote-agent/internal/harness"
)

// OneShot names a session from the request in the prompt, between <request>
// and </request>: "Named: " and its first words, or a failure when it says
// "fail". "slow" takes two seconds.
func (Harness) OneShot(ctx context.Context, o harness.OneShotOptions) (string, error) {
	req := o.Prompt
	if _, after, ok := strings.Cut(req, "<request>"); ok {
		req, _, _ = strings.Cut(after, "</request>")
	}
	if strings.Contains(req, "fail") {
		return "", errors.New("fake: naming failed")
	}
	if strings.Contains(req, "slow") {
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(2 * time.Second):
		}
	}
	words := strings.Fields(req)
	words = words[:min(len(words), 4)]
	b, _ := json.Marshal(map[string]string{
		"title":  "Named: " + strings.Join(words, " "),
		"branch": "named-" + strings.ToLower(strings.Join(words, "-")),
	})
	return "Here you go:\n" + string(b), nil
}

// UpdateCommand pretends to download an update, with a progress line that
// redraws itself.
func (Harness) UpdateCommand() (harness.Command, bool) {
	return harness.Command{Args: []string{"/bin/sh", "-c", `echo "Checking for updates to Fake…"; sleep 0.5
printf "Downloading  50%%\r"; sleep 0.5; printf "Downloading 100%%\n"; echo "Fake is up to date."`}}, true
}

// LoginCommand prints a sign-in link and asks for a code: an empty one
// fails.
func (Harness) LoginCommand() (harness.Command, bool) {
	return harness.Command{Args: []string{"/bin/sh", "-c", `echo "Open this link to sign in: https://example.com/fake-login?session=42"
printf "Paste code here > "; read code
if [ -z "$code" ]; then echo "No code given."; exit 1; fi
echo "Signed in to Fake."`}}, true
}
