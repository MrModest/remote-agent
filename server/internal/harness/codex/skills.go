package codex

import (
	"context"
	"strings"
	"unicode"

	"remote-agent/internal/harness"
	"remote-agent/internal/model"
)

type skill struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Path        string `json:"path"`
	Scope       string `json:"scope"`
	Enabled     bool   `json:"enabled"`
	Interface   *struct {
		ShortDescription string `json:"shortDescription"`
	} `json:"interface"`
}

func (r *runtime) skills(ctx context.Context, refresh bool) ([]skill, error) {
	r.mu.Lock()
	if !refresh && r.skillsKnown {
		catalog := r.skillCatalog
		r.mu.Unlock()
		return catalog, nil
	}
	r.mu.Unlock()
	catalog, err := r.loadSkills(ctx, refresh)
	r.mu.Lock()
	// Even a failed lookup must not be repeated on every Send; reopening the picker retries it.
	r.skillsKnown = true
	if err == nil {
		r.skillCatalog = catalog
	}
	r.mu.Unlock()
	return catalog, err
}

func (r *runtime) loadSkills(ctx context.Context, refresh bool) ([]skill, error) {
	var res struct {
		Data []struct {
			Cwd    string  `json:"cwd"`
			Skills []skill `json:"skills"`
		} `json:"data"`
	}
	if err := r.conn.Call(ctx, "skills/list", map[string]any{"cwds": []string{r.cwd}, "forceReload": refresh}, &res); err != nil {
		return nil, err
	}
	var out []skill
	seen := map[string]bool{}
	for _, group := range res.Data {
		if group.Cwd != r.cwd {
			continue
		}
		for _, s := range group.Skills {
			if !s.Enabled || s.Name == "" || s.Path == "" || seen[s.Name] {
				continue
			}
			seen[s.Name] = true
			out = append(out, s)
		}
	}
	return out, nil
}

func (r *runtime) Commands(ctx context.Context) ([]model.Command, error) {
	skills, err := r.skills(ctx, true)
	if err != nil {
		return nil, err
	}
	out := []model.Command{harness.Compact}
	for _, s := range skills {
		description := s.Description
		if s.Interface != nil && s.Interface.ShortDescription != "" {
			description = s.Interface.ShortDescription
		}
		out = append(out, model.Command{Name: s.Name, Description: description, Kind: "skill", Scope: s.Scope})
	}
	return out, nil
}

// skillInput resolves only a leading explicit invocation, preserving arguments and images.
func skillInput(text string, skills []skill) (string, map[string]any) {
	trimmed := strings.TrimLeftFunc(text, unicode.IsSpace)
	for _, s := range skills {
		for _, prefix := range []string{"/", "$"} {
			rest, ok := strings.CutPrefix(trimmed, prefix+s.Name)
			if ok && (rest == "" || unicode.IsSpace([]rune(rest)[0])) {
				return "$" + s.Name + rest, map[string]any{"type": "skill", "name": s.Name, "path": s.Path}
			}
		}
	}
	return text, nil
}
