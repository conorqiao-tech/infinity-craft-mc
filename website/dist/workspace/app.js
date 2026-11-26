const $ = id => document.getElementById(id);
const encoder = new TextEncoder();
const maxFileBytes = 80_000;
const maxRequestBytes = 100_000;
const filePicker = $('file');
const messageInput = $('message');
const keyInput = $('key');
const submitButton = $('send');

let selectedFile;
let proposal;
let selection = 0;
let revision = 0;
let reading = false;
let submitting = false;

function updateSize() {
  const fileBytes = selectedFile ? encoder.encode(selectedFile.content).length : 0;
  const total = encoder.encode(messageInput.value.trim()).length + fileBytes;
  $('request-size').textContent = `${total.toLocaleString()} of 100,000 bytes · request and file`;
  return total;
}

function updateSubmitButton() {
  submitButton.disabled = reading || submitting;
}

function clearResult() {
  revision++;
  proposal = null;
  $('result').hidden = true;
  $('download').hidden = true;
  $('original').hidden = true;
  $('original').open = false;
  $('original-text').textContent = '';
  $('output').textContent = '';
  $('notice').textContent = '';
}

filePicker.addEventListener('change', async () => {
  clearResult();
  const file = filePicker.files[0];
  const currentSelection = ++selection;
  selectedFile = null;
  updateSize();
  $('clear-file').hidden = !file;
  reading = Boolean(file);
  updateSubmitButton();
  if (!file) {
    $('file-info').textContent = 'No file selected';
    return;
  }
  $('file-info').textContent = 'Reading file…';
  try {
    if (file.size > maxFileBytes) throw new Error('File exceeds the 80 KB limit.');
    let content;
    try {
      content = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
    } catch {
      throw new Error('Choose a readable UTF-8 text file.');
    }
    if (currentSelection !== selection) return;
    if (content.includes('\0')) throw new Error('Choose a UTF-8 text file.');
    selectedFile = { name: file.name, content };
    $('file-info').textContent = `${file.name} · ${file.size.toLocaleString()} bytes`;
  } catch (error) {
    if (currentSelection !== selection) return;
    $('file-info').textContent = error.message;
    filePicker.value = '';
    $('clear-file').hidden = true;
  } finally {
    if (currentSelection === selection) {
      reading = false;
      updateSubmitButton();
      updateSize();
    }
  }
});

$('clear-file').onclick = () => {
  selection++;
  reading = false;
  selectedFile = null;
  filePicker.value = '';
  $('file-info').textContent = 'No file selected';
  $('clear-file').hidden = true;
  clearResult();
  updateSize();
  updateSubmitButton();
};

for (const button of document.querySelectorAll('[data-prompt]')) {
  button.onclick = () => {
    messageInput.value = button.dataset.prompt;
    messageInput.dispatchEvent(new Event('input'));
    messageInput.focus();
  };
}
messageInput.addEventListener('input', () => { clearResult(); updateSize(); });
keyInput.addEventListener('input', clearResult);
$('mode').addEventListener('change', clearResult);
$('clear-key').onclick = () => {
  keyInput.value = '';
  clearResult();
  $('notice').textContent = 'API key cleared from this page.';
};
messageInput.addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault();
    submitButton.click();
  }
});
updateSize();

submitButton.onclick = async () => {
  if (reading || submitting) return;
  const key = keyInput.value.trim();
  const message = messageInput.value.trim();
  const mode = $('mode').value;
  const file = selectedFile;
  if (!key || !message || (mode === 'edit' && !file)) {
    $('notice').textContent = 'Enter an API key and a request. To propose an edit, select a file.';
    return;
  }
  if (updateSize() > maxRequestBytes) {
    $('notice').textContent = 'Shorten the request or select a smaller file.';
    return;
  }

  clearResult();
  const submittedRevision = revision;
  submitting = true;
  updateSubmitButton();
  $('notice').textContent = 'Working on your request…';
  try {
    const response = await fetch('/api/workspace', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key, message, mode, file }),
    });
    const data = await response.json();
    if (revision !== submittedRevision) return;
    if (!response.ok || typeof data.output !== 'string') {
      throw new Error(data.error || 'Request failed.');
    }
    $('output').textContent = data.output;
    $('result-title').textContent = mode === 'edit' ? 'Proposed file content' : 'Response';
    $('result').hidden = false;
    if (mode === 'edit') {
      $('original-text').textContent = file.content;
      $('original').hidden = false;
      proposal = { name: file.name, content: data.output };
      $('download').hidden = false;
    }
    $('notice').textContent = mode === 'edit' ? 'Review the proposal before downloading.' : 'Response ready.';
  } catch (error) {
    if (revision === submittedRevision) $('notice').textContent = error.message || 'Request failed.';
  } finally {
    submitting = false;
    updateSubmitButton();
  }
};

$('copy').onclick = async () => {
  const copiedRevision = revision;
  try {
    await navigator.clipboard.writeText($('output').textContent);
    if (revision === copiedRevision) $('notice').textContent = 'Response copied.';
  } catch {
    if (revision === copiedRevision) $('notice').textContent = 'Select the response text and copy it manually.';
  }
};
$('download').onclick = () => {
  if (!proposal) return;
  const url = URL.createObjectURL(new Blob([proposal.content], { type: 'text/plain;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = proposal.name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
