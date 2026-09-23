'use strict';

/**
 * Structural validation of the plugin itself. A malformed frontmatter block or a
 * hook pointing at a file that does not exist fails silently at load time, long
 * after the mistake was made — these checks surface it here instead.
 */

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');

function readJson(rel) {
  return JSON.parse(fs.readFileSync(path.join(ROOT, rel), 'utf8'));
}

/** Minimal frontmatter reader: enough for the flat key/value blocks used here. */
function frontmatter(file) {
  const text = fs.readFileSync(file, 'utf8');
  const match = /^---\r?\n([\s\S]*?)\r?\n---/.exec(text);
  if (!match) return null;

  const fields = {};
  for (const line of match[1].split(/\r?\n/)) {
    const kv = /^([A-Za-z0-9_-]+):\s*(.*)$/.exec(line);
    if (kv) fields[kv[1]] = kv[2].trim();
  }
  return fields;
}

const AGENT_DIR = path.join(ROOT, 'agents');
const SKILL_DIR = path.join(ROOT, 'skills');

const agentFiles = fs.readdirSync(AGENT_DIR).filter((f) => f.endsWith('.md'));
const skillDirs = fs.readdirSync(SKILL_DIR).filter((d) => fs.statSync(path.join(SKILL_DIR, d)).isDirectory());

// ------------------------------------------------------------------ JSON files

for (const rel of [
  '.claude-plugin/plugin.json',
  '.claude-plugin/marketplace.json',
  '.mcp.json',
  'settings.json',
  'hooks/hooks.json',
  'config/policy.default.json',
  'config/profiles.json',
  'config/projects.json',
  'schemas/run-state.schema.json',
  'schemas/event.schema.json',
  'package.json'
]) {
  test(`${rel} is valid JSON`, () => {
    assert.doesNotThrow(() => readJson(rel));
  });
}

test('plugin manifest has the fields the loader needs', () => {
  const manifest = readJson('.claude-plugin/plugin.json');
  assert.ok(manifest.name, 'plugin needs a name');
  assert.ok(manifest.version, 'plugin needs a version');
  assert.match(manifest.name, /^[a-z0-9-]+$/, 'name is used in skill namespacing, so keep it simple');
});

test('marketplace lists this plugin', () => {
  const marketplace = readJson('.claude-plugin/marketplace.json');
  const manifest = readJson('.claude-plugin/plugin.json');
  assert.ok(Array.isArray(marketplace.plugins) && marketplace.plugins.length > 0);
  assert.ok(
    marketplace.plugins.some((p) => p.name === manifest.name),
    'the marketplace must list the plugin it ships'
  );
});

// ---------------------------------------------------------------------- agents

test('every agent has valid frontmatter with a matching name', () => {
  assert.ok(agentFiles.length >= 9, `expected the full roster, found ${agentFiles.length}`);

  for (const file of agentFiles) {
    const fm = frontmatter(path.join(AGENT_DIR, file));
    assert.ok(fm, `${file}: no frontmatter block`);
    assert.ok(fm.name, `${file}: missing "name"`);
    assert.ok(fm.description, `${file}: missing "description" — this is how Claude decides to delegate`);
    assert.equal(fm.name, path.basename(file, '.md'), `${file}: name must match the filename`);
  }
});

test('read-only agents cannot write', () => {
  // These run before or instead of implementation. If any of them could write,
  // the "nothing is edited before the impact report" guarantee is gone.
  const readOnly = ['requirements-analyst', 'impact-analyst', 'planner', 'gate-verifier', 'security-reviewer'];
  const writeTools = ['Write', 'Edit', 'MultiEdit', 'NotebookEdit'];

  for (const name of readOnly) {
    const fm = frontmatter(path.join(AGENT_DIR, `${name}.md`));
    assert.ok(fm.tools, `${name}: must declare an explicit tools allowlist`);
    const tools = fm.tools.split(',').map((t) => t.trim());
    for (const banned of writeTools) {
      assert.ok(!tools.includes(banned), `${name} must not have the ${banned} tool`);
    }
  }
});

test('agents declaring a model use a known one', () => {
  for (const file of agentFiles) {
    const fm = frontmatter(path.join(AGENT_DIR, file));
    if (!fm.model) continue;
    assert.ok(
      ['opus', 'sonnet', 'haiku', 'fable'].includes(fm.model),
      `${file}: unexpected model "${fm.model}"`
    );
  }
});

// ---------------------------------------------------------------------- skills

test('every skill has valid frontmatter with a matching name', () => {
  for (const dir of skillDirs) {
    const skillFile = path.join(SKILL_DIR, dir, 'SKILL.md');
    assert.ok(fs.existsSync(skillFile), `skills/${dir}: no SKILL.md`);

    const fm = frontmatter(skillFile);
    assert.ok(fm, `skills/${dir}: no frontmatter block`);
    assert.ok(fm.description, `skills/${dir}: missing "description"`);
    if (fm.name) assert.equal(fm.name, dir, `skills/${dir}: name must match the directory`);
  }
});

test('SKILL.md files stay small enough for progressive disclosure', () => {
  for (const dir of skillDirs) {
    const lines = fs.readFileSync(path.join(SKILL_DIR, dir, 'SKILL.md'), 'utf8').split('\n').length;
    assert.ok(lines < 500, `skills/${dir}/SKILL.md is ${lines} lines; move detail into references/`);
  }
});

test('every referenced file actually exists', () => {
  for (const dir of skillDirs) {
    const skillPath = path.join(SKILL_DIR, dir, 'SKILL.md');
    const text = fs.readFileSync(skillPath, 'utf8');
    for (const m of text.matchAll(/references\/([a-z0-9-]+\.md)/g)) {
      const ref = path.join(SKILL_DIR, dir, 'references', m[1]);
      assert.ok(fs.existsSync(ref), `skills/${dir}/SKILL.md references missing ${m[1]}`);
    }
  }
});

test('every agent named in a skill exists', () => {
  const known = new Set(agentFiles.map((f) => path.basename(f, '.md')));
  for (const dir of skillDirs) {
    const text = fs.readFileSync(path.join(SKILL_DIR, dir, 'SKILL.md'), 'utf8');
    for (const m of text.matchAll(/\*\*([a-z-]+)\*\* agent/g)) {
      assert.ok(known.has(m[1]), `skills/${dir} dispatches unknown agent "${m[1]}"`);
    }
  }
});

// ----------------------------------------------------------------------- hooks

test('every hook command points at a file that exists', () => {
  const hooks = readJson('hooks/hooks.json');
  let checked = 0;

  for (const entries of Object.values(hooks.hooks)) {
    for (const entry of entries) {
      for (const hook of entry.hooks) {
        const m = /\$\{CLAUDE_PLUGIN_ROOT\}\/([^"]+)/.exec(hook.command);
        assert.ok(m, `hook command should resolve via \${CLAUDE_PLUGIN_ROOT}: ${hook.command}`);
        assert.ok(fs.existsSync(path.join(ROOT, m[1])), `hook points at missing file: ${m[1]}`);
        checked++;
      }
    }
  }
  assert.ok(checked >= 4, 'expected gate, observe and session hooks to be wired');
});

test('the gate hook is wired to every mutating tool', () => {
  const hooks = readJson('hooks/hooks.json');
  const matchers = hooks.hooks.PreToolUse.map((e) => e.matcher).join('|');
  for (const tool of ['Write', 'Edit', 'Bash']) {
    assert.ok(matchers.includes(tool), `PreToolUse must match ${tool} or the gate is bypassable`);
  }
});

// ---------------------------------------------------------------------- config

test('profiles define the gates each track requires', () => {
  const { profiles, default: fallback } = readJson('config/profiles.json');
  assert.ok(profiles[fallback], 'the default track must exist');

  for (const [name, profile] of Object.entries(profiles)) {
    assert.ok(Array.isArray(profile.humanGates), `${name}: humanGates must be an array`);
    assert.ok(Array.isArray(profile.stages), `${name}: stages must be an array`);
  }
  assert.ok(
    profiles.standard.humanGates.length === 3,
    'standard is documented as three core gates; change the docs too if this changes'
  );
});

// ------------------------------------------------------------------------ docs

function docFiles() {
  const files = [];
  for (const rel of ['README.md', 'CLAUDE.md']) {
    if (fs.existsSync(path.join(ROOT, rel))) files.push(rel);
  }
  const docsDir = path.join(ROOT, 'docs');
  if (!fs.existsSync(docsDir)) return files;

  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.md')) files.push(path.relative(ROOT, full).replace(/\\/g, '/'));
    }
  };
  walk(docsDir);
  return files;
}

test('every relative link in the docs resolves', () => {
  for (const rel of docFiles()) {
    const text = fs.readFileSync(path.join(ROOT, rel), 'utf8');
    const from = path.dirname(path.join(ROOT, rel));

    for (const m of text.matchAll(/\[[^\]]*\]\(([^)]+)\)/g)) {
      const target = m[1];
      if (/^(https?:|#|mailto:)/.test(target)) continue;
      const clean = target.split('#')[0];
      if (!clean) continue;
      assert.ok(fs.existsSync(path.resolve(from, clean)), `${rel} links to missing ${target}`);
    }
  }
});

test('docs do not promise CLI commands that do not exist', () => {
  // The halt message once told users to run `sdlc rollback`, which was never
  // built — precisely when they were most stuck. Catch that class mechanically.
  const cli = fs.readFileSync(path.join(ROOT, 'bin', 'sdlc.js'), 'utf8');
  const commands = new Set();
  const block = /const COMMANDS = \{([\s\S]*?)\n\};/.exec(cli);
  assert.ok(block, 'could not locate the COMMANDS table');
  for (const m of block[1].matchAll(/^\s*'?([a-z-]+)'?\s*:/gm)) commands.add(m[1]);

  const sources = [...docFiles(), 'bin/sdlc.js', 'lib/policy.js'];
  for (const dir of skillDirs) sources.push(`skills/${dir}/SKILL.md`);
  for (const ref of ['gates', 'graph-spec', 'profiles']) {
    const p = `skills/run/references/${ref}.md`;
    if (fs.existsSync(path.join(ROOT, p))) sources.push(p);
  }

  for (const rel of sources) {
    const text = fs.readFileSync(path.join(ROOT, rel), 'utf8');
    for (const m of text.matchAll(/(?:sdlc |SDLC |\/sdlc:)([a-z][a-z-]{2,})/g)) {
      const word = m[1];
      // Only flag words that look like a command invocation we claim to support.
      if (!commands.has(word) && ['rollback', 'replan', 'metrics', 'summarize'].includes(word)) {
        assert.fail(`${rel} references \`${word}\`, which is not a registered command`);
      }
    }
  }
});

test('agents and skills do not cite rules files that do not exist', () => {
  // An agent told to consult a missing standards file finds nothing and
  // proceeds unguided, which is worse than having no instruction at all.
  const sources = [
    ...agentFiles.map((f) => `agents/${f}`),
    ...skillDirs.map((d) => `skills/${d}/SKILL.md`)
  ];

  for (const rel of sources) {
    const text = fs.readFileSync(path.join(ROOT, rel), 'utf8');
    for (const m of text.matchAll(/`?rules\/([a-z0-9-]+\.md)`?/g)) {
      assert.ok(
        fs.existsSync(path.join(ROOT, 'rules', m[1])),
        `${rel} cites rules/${m[1]}, which does not exist`
      );
    }
  }
});

test('policy protects the system from editing itself', () => {
  const policy = readJson('config/policy.default.json');
  for (const dir of ['hooks/**', 'lib/**', 'bin/**', 'config/**']) {
    assert.ok(policy.protectedZone.includes(dir), `protectedZone must cover ${dir}`);
  }
});
