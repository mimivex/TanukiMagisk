// Minimal window.ksu example.
//
// The manager injects the `ksu` bridge into this page only. Every callback
// is passed by name: the bridge evaluates `callbackName(code, stdout, stderr)`
// in the page's global scope when the command finishes.

const out = document.getElementById('out');

function show(text) {
  out.textContent = text;
}

window.onExecDone = function (code, stdout, stderr) {
  const cmd = document.getElementById('cmd').value;
  show('$ ' + cmd + '\n' + stdout + (stderr ? '\n' + stderr : '') + '\nexit ' + code);
};

document.getElementById('run').addEventListener('click', function () {
  if (!window.ksu) {
    show('window.ksu is not available: open this page from the module list');
    return;
  }
  ksu.exec(document.getElementById('cmd').value, '{}', 'onExecDone');
});

document.getElementById('info').addEventListener('click', function () {
  if (!window.ksu) {
    show('window.ksu is not available: open this page from the module list');
    return;
  }
  show(JSON.stringify(JSON.parse(ksu.moduleInfo()), null, 2));
});

document.getElementById('hello').addEventListener('click', function () {
  if (!window.ksu) {
    show('window.ksu is not available: open this page from the module list');
    return;
  }
  ksu.toast('Hello from the module WebUI');
});

// Show the module id on load.
if (window.ksu) {
  show(JSON.stringify(JSON.parse(ksu.moduleInfo()), null, 2));
}
