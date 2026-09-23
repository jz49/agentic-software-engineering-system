'use strict';

/**
 * Regression tests for where durable data lives.
 *
 * An installed plugin runs from a versioned cache directory that is replaced
 * wholesale on update. Defaulting the durable store there silently destroys
 * every generated project and audit record on a routine upgrade, so the
 * resolution rules below are load-bearing rather than cosmetic.
 */

const test = require('node:test');
const assert = require('node:assert');
const path = require('node:path');

const paths = require('../lib/paths');

function withEnv(overrides, fn) {
  const saved = {};
  for (const [k, v] of Object.entries(overrides)) {
    saved[k] = process.env[k];
    if (v === undefined) delete process.env[k];
    else process.env[k] = v;
  }
  try {
    return fn();
  } finally {
    for (const [k, v] of Object.entries(saved)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  }
}

test('SDLC_HOME env var wins', () => {
  withEnv({ SDLC_HOME: 'C:/Workspace/my-sdlc' }, () => {
    assert.equal(paths.sdlcHome(), path.resolve('C:/Workspace/my-sdlc'));
  });
});

test('sdlcHome comes from explicit configuration, never from the plugin location', () => {
  withEnv({ SDLC_HOME: undefined }, () => {
    const configured = paths.userConfig().sdlcHome;
    const home = paths.sdlcHome();

    if (configured) {
      // It must trace to the user's config. Note this can legitimately equal
      // PLUGIN_ROOT when running from the source repo, so comparing against
      // PLUGIN_ROOT proves nothing — provenance is what matters.
      assert.equal(home, path.resolve(configured));
    } else {
      assert.equal(home, null, 'unconfigured must yield null, not a guessed location');
    }
  });
});

test('an unconfigured home yields null rather than the plugin directory', () => {
  // Proves the absence of a PLUGIN_ROOT fallback without depending on whether
  // this machine happens to have a config file.
  const src = require('node:fs').readFileSync(require('node:path').join(__dirname, '..', 'lib', 'paths.js'), 'utf8');
  const fn = /function sdlcHome\(\)[\s\S]*?\n}/.exec(src);
  assert.ok(fn, 'could not locate sdlcHome()');
  assert.ok(!/return PLUGIN_ROOT/.test(fn[0]), 'sdlcHome must never fall back to PLUGIN_ROOT');
  assert.ok(/return null/.test(fn[0]), 'sdlcHome must return null when unconfigured');
});

test('durable paths fail loudly when home is unconfigured', () => {
  withEnv({ SDLC_HOME: undefined }, () => {
    if (paths.sdlcHome()) return; // a real home is configured on this machine; nothing to assert
    assert.throws(() => paths.artifactsDir('demo', 'r-1'), /set-home/);
    assert.throws(() => paths.projectsRoot(), /set-home/);
  });
});

test('protectedRoots always covers the running plugin', () => {
  const roots = paths.protectedRoots();
  assert.ok(roots.includes(paths.PLUGIN_ROOT), 'the running plugin must protect its own files');
});

test('protectedRoots covers the source repo separately once installed', () => {
  withEnv({ SDLC_HOME: 'C:/Workspace/agentic-software-engineering-system' }, () => {
    const roots = paths.protectedRoots();
    assert.ok(roots.length >= 1);
    if (paths.PLUGIN_ROOT !== path.resolve('C:/Workspace/agentic-software-engineering-system')) {
      assert.equal(roots.length, 2, 'plugin install and source repo are both protected');
    }
  });
});

test('hot state is kept out of the plugin install too', () => {
  withEnv({ CLAUDE_PLUGIN_DATA: undefined }, () => {
    assert.ok(
      !paths.isSubPath(paths.PLUGIN_ROOT, paths.pluginData()),
      'run state would not survive a plugin update if it lived inside it'
    );
  });
});

test('isSubPath does not treat a sibling as contained', () => {
  assert.equal(paths.isSubPath('C:/a/project', 'C:/a/project/src/File.java'), true);
  assert.equal(paths.isSubPath('C:/a/project', 'C:/a/project-other/src/File.java'), false);
  assert.equal(paths.isSubPath('C:/a/project', 'C:/a'), false);
});

test('derived slugs are stable and filesystem-safe', () => {
  const a = paths.derivedSlug('C:/Workspace/My Repo');
  const b = paths.derivedSlug('C:/Workspace/My Repo');
  assert.equal(a, b, 'the same path must always produce the same slug');
  assert.match(a, /^[a-z0-9._-]+$/, 'slugs become directory names');
  assert.notEqual(a, paths.derivedSlug('C:/Other/My Repo'), 'same basename, different path');
});
