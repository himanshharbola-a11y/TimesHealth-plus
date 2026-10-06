/*
 * TimesHealth+ admin dashboard. No build step, no dependencies.
 *
 * Every list and form is generated from GET /admin/api/meta (the server's resource declarations
 * in admin/AdminResources.kt): a new editable table or column on the server appears here with no
 * change to this file. Special screens: Home layout, hand-picked videos, scheduling a run of live
 * classes, admins and the activity log.
 *
 * All text from the server is inserted with textContent (never innerHTML).
 */
'use strict';

const IST_OFFSET_MIN = 330;
const state = { me: null, meta: null, res: {}, kinds: {}, routing: false };

// ── DOM helpers ───────────────────────────────────────────────────────────────

function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'value') el.value = v;
    else if (k === 'checked') el.checked = !!v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

function mount(...nodes) {
  const app = document.getElementById('app');
  app.replaceChildren(...nodes);
}

let toastTimer;
function toast(message, bad) {
  const t = document.getElementById('toast');
  t.textContent = message;
  t.className = bad ? 'bad' : '';
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, bad ? 6000 : 2500);
}

// ── API ───────────────────────────────────────────────────────────────────────

async function api(method, path, body) {
  const headers = { 'X-Requested-With': 'th-admin' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch('/admin/api' + path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  let data = null;
  try { data = await res.json(); } catch (_) { /* empty body */ }
  if (res.status === 401 && path !== '/login') {
    state.me = null;
    renderLogin();
    throw Object.assign(new Error('Signed out'), { status: 401, silent: true });
  }
  if (!res.ok) {
    const err = new Error((data && data.message) || `Request failed (${res.status})`);
    err.status = res.status;
    err.fields = data && data.fields;
    throw err;
  }
  return data;
}

function fail(err) {
  if (err && err.silent) return;
  toast(err.message || 'Something went wrong', true);
}

// ── Time (the team works in IST) ──────────────────────────────────────────────

function isoToIstInput(iso) {
  if (!iso) return '';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '';
  return new Date(t + IST_OFFSET_MIN * 60000).toISOString().slice(0, 16);
}

function istInputToIso(v) {
  if (!v) return null;
  const t = Date.parse(v + ':00Z');
  return Number.isNaN(t) ? undefined : new Date(t - IST_OFFSET_MIN * 60000).toISOString();
}

function formatIst(iso) {
  if (!iso) return '';
  const local = isoToIstInput(iso);
  const [d, time] = local.split('T');
  const date = new Date(d + 'T00:00:00Z');
  return date.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }) + ' · ' + time + ' IST';
}

function rupees(paise) {
  if (paise === null || paise === undefined) return '';
  return '₹' + (paise / 100).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

// ── Boot, login, shell ────────────────────────────────────────────────────────

async function boot() {
  try {
    state.me = await api('GET', '/me');
  } catch (e) {
    if (e.status !== 401) fail(e);
    return;
  }
  state.meta = await api('GET', '/meta');
  state.res = Object.fromEntries(state.meta.resources.map((r) => [r.key, r]));
  state.kinds = Object.fromEntries(state.meta.sectionKinds.map((k) => [k.value, k]));
  if (!state.routing) {
    window.addEventListener('hashchange', route);
    state.routing = true;
  }
  route();
}

function renderLogin() {
  const err = h('div', { class: 'err' });
  const email = h('input', { type: 'email', autocomplete: 'username', required: true, id: 'email' });
  const password = h('input', { type: 'password', autocomplete: 'current-password', required: true, id: 'password' });
  const submit = h('button', { class: 'btn primary', type: 'submit', text: 'Sign in' });
  const form = h('form', {
    onsubmit: async (ev) => {
      ev.preventDefault();
      err.textContent = '';
      submit.disabled = true;
      try {
        await api('POST', '/login', { email: email.value, password: password.value });
        location.hash = '#/home';
        await boot();
      } catch (e) {
        err.textContent = e.message;
      } finally {
        submit.disabled = false;
      }
    },
  },
  h('div', { class: 'field' }, h('label', { for: 'email', text: 'Email' }), email),
  h('div', { class: 'field' }, h('label', { for: 'password', text: 'Password' }), password),
  err, submit);
  mount(h('div', { class: 'login' }, h('div', { class: 'card' },
    h('div', { class: 'brand' }, h('span', { class: 'mark', text: '♥' }), h('span', {}, 'TimesHealth+', h('small', { text: 'Admin dashboard' }))),
    h('p', { class: 'lede', text: 'Manage what the app shows: sections, videos, live classes, marathons and more.' }),
    form)));
  email.focus();
}

function shell(active, ...content) {
  const link = (hash, label) => h('a', { href: hash, class: active === hash ? 'active' : null, text: label });
  const groups = {};
  for (const r of state.meta.resources) {
    if (r.key === 'sections') continue;
    (groups[r.group] = groups[r.group] || []).push(r);
  }
  const nav = h('nav', { class: 'nav' },
    h('div', { class: 'nav-group', text: 'App pages' }),
    link('#/home', 'Home layout'),
    ...Object.entries(groups).map(([group, list]) => [
      h('div', { class: 'nav-group', text: group }),
      ...list.map((r) => link('#/r/' + r.key, r.label)),
      group === 'Live classes' ? link('#/schedule', 'Schedule a week') : null,
    ]),
    h('div', { class: 'nav-group', text: 'Team' }),
    state.me.canManageAdmins ? link('#/admins', 'Admins') : null,
    link('#/activity', 'Activity'));
  const signOut = h('button', {
    class: 'btn small',
    text: 'Sign out',
    onclick: async () => { await api('POST', '/logout').catch(() => {}); state.me = null; renderLogin(); },
  });
  return h('div', { class: 'shell' },
    h('aside', { class: 'side' },
      h('div', { class: 'brand' }, h('span', { class: 'mark', text: '♥' }), h('span', {}, 'TimesHealth+', h('small', { text: 'Admin' }))),
      nav),
    h('main', { class: 'main' },
      h('div', { class: 'topbar' }, h('span', { text: state.me.name }), h('span', { class: 'role', text: state.me.role }), signOut),
      ...content));
}

function route() {
  if (!state.me) return;
  const hash = location.hash || '#/home';
  const parts = hash.slice(2).split('?')[0].split('/');
  const query = new URLSearchParams(hash.split('?')[1] || '');
  const run = (p) => p.catch(fail);
  if (parts[0] === 'home' || parts[0] === '') return run(homeLayout());
  if (parts[0] === 'r' && parts[1] && !parts[2]) return run(listView(parts[1]));
  if (parts[0] === 'r' && parts[1] && parts[2]) return run(formView(parts[1], parts[2] === 'new' ? null : decodeURIComponent(parts[2]), query));
  if (parts[0] === 'sections' && parts[2] === 'items') return run(itemsView(decodeURIComponent(parts[1])));
  if (parts[0] === 'schedule') return run(scheduleView());
  if (parts[0] === 'admins') return run(adminsView());
  if (parts[0] === 'activity') return run(activityView());
  location.hash = '#/home';
}

// ── Drag to reorder ───────────────────────────────────────────────────────────

/** Makes [container]'s children draggable; calls onDrop(ids) with the new order. */
function sortable(container, onDrop) {
  let dragged = null;
  for (const el of container.children) {
    el.draggable = true;
    el.addEventListener('dragstart', (e) => {
      dragged = el;
      el.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
    });
    el.addEventListener('dragend', () => {
      el.classList.remove('dragging');
      if (dragged) onDrop([...container.children].map((c) => c.dataset.id));
      dragged = null;
    });
    el.addEventListener('dragover', (e) => {
      e.preventDefault();
      if (!dragged || dragged === el) return;
      const r = el.getBoundingClientRect();
      const after = e.clientY > r.top + r.height / 2;
      container.insertBefore(dragged, after ? el.nextSibling : el);
    });
  }
}

// ── Home layout ───────────────────────────────────────────────────────────────

async function homeLayout() {
  const data = await api('GET', '/r/sections?f.page=HOME&limit=200');
  const rows = h('div', { class: 'rows' });
  const canEdit = state.me.canEdit;
  for (const s of data.items) {
    const kind = state.kinds[s.kind];
    const badges = [h('span', { class: 'pill coral', text: kind ? kind.label : s.kind })];
    if (s.audience && s.audience !== 'ALL') badges.push(h('span', { class: 'pill', text: s.audience === 'MEMBERS' ? 'Members only' : 'Non-members only' }));
    if (s.startsAt || s.endsAt) badges.push(h('span', { class: 'pill warn', text: 'Scheduled' + (s.startsAt ? ' from ' + formatIst(s.startsAt) : '') + (s.endsAt ? ' until ' + formatIst(s.endsAt) : '') }));
    if (s.kind === 'CATEGORY_RAIL' && s.categoryId) badges.push(h('span', { class: 'pill', text: (data.refs.categoryId || {})[s.categoryId] || s.categoryId }));
    const toggle = h('input', {
      type: 'checkbox',
      checked: s.visible,
      disabled: !canEdit,
      'aria-label': 'Visible',
      onchange: async (e) => {
        try {
          await api('PATCH', '/r/sections/' + encodeURIComponent(s.id), { visible: e.target.checked });
          row.classList.toggle('hidden-section', !e.target.checked);
          toast(e.target.checked ? 'Shown in the app' : 'Hidden from the app');
        } catch (err) { e.target.checked = !e.target.checked; fail(err); }
      },
    });
    const row = h('div', { class: 'row' + (s.visible ? '' : ' hidden-section'), 'data-id': s.id },
      canEdit ? h('span', { class: 'handle', title: 'Drag to reorder', text: '⋮⋮' }) : null,
      h('div', { class: 'grow' }, h('div', { class: 'title', text: s.title }), h('div', { class: 'meta' }, badges)),
      kind && kind.itemType ? h('a', { class: 'btn small', href: '#/sections/' + encodeURIComponent(s.id) + '/items', text: 'Pick videos' }) : null,
      h('a', { class: 'btn small', href: '#/r/sections/' + encodeURIComponent(s.id), text: canEdit ? 'Edit' : 'View' }),
      h('label', { class: 'switch', title: 'Visible in the app' }, toggle, h('span', { class: 'track' })));
    rows.append(row);
  }
  if (canEdit) {
    sortable(rows, async (ids) => {
      try { await api('POST', '/r/sections/reorder', { ids }); toast('Order saved — live in the app within a minute'); } catch (e) { fail(e); }
    });
  }
  mount(shell('#/home',
    h('h1', { text: 'Home layout' }),
    h('p', { class: 'lede', text: 'The sections of the app\'s Home page, top to bottom. Drag to reorder, switch to hide, edit to rename or schedule. Empty sections are never shown. Changes reach the app within a minute.' }),
    h('div', { class: 'toolbar' }, h('div', { class: 'grow' }),
      canEdit ? h('a', { class: 'btn primary', href: '#/r/sections/new?page=HOME', text: '+ Add section' }) : null),
    data.items.length ? rows : h('div', { class: 'card empty', text: 'No sections yet: the app shows its default layout.' })));
}

// ── Generic list ──────────────────────────────────────────────────────────────

function cellValue(field, value, refs) {
  if (value === null || value === undefined || value === '') return h('span', { class: 'pill off', text: '—' });
  switch (field.type) {
    case 'BOOL': return h('span', { class: 'pill ' + (value ? 'on' : 'off'), text: value ? 'Yes' : 'No' });
    case 'IMAGE': return h('img', { class: 'thumb', src: value, alt: '', loading: 'lazy' });
    case 'DATETIME': return formatIst(value);
    case 'PAISE': return rupees(value);
    case 'REF': return (refs[field.name] || {})[value] || value;
    case 'SELECT': {
      const o = (field.options || []).find((x) => x.value === value);
      return o ? o.label : value;
    }
    case 'TAGS': return (value || []).join(', ');
    case 'LONGTEXT': return value.length > 90 ? value.slice(0, 90) + '…' : value;
    default: return String(value);
  }
}

async function listView(key) {
  const res = state.res[key];
  if (!res) { location.hash = '#/home'; return; }
  if (res.singleton) {
    const data = await api('GET', '/r/' + key);
    if (data.items[0]) { location.replace('#/r/' + key + '/' + encodeURIComponent(data.items[0].id)); return; }
  }
  const cols = res.fields.filter((f) => f.list);
  const filters = res.fields.filter((f) => f.filter && f.type === 'REF');
  const params = new URLSearchParams(location.hash.split('?')[1] || '');
  const q = params.get('q') || '';
  const filterValues = Object.fromEntries(filters.map((f) => [f.name, params.get('f.' + f.name) || '']));
  let offset = 0;
  const tbody = h('tbody');
  const more = h('div', { class: 'more' });
  const reorderable = res.sortable && !q && state.me.canEdit;

  async function load(append) {
    const qs = new URLSearchParams({ limit: '100', offset: String(offset) });
    if (q) qs.set('q', q);
    for (const [k, v] of Object.entries(filterValues)) if (v) qs.set('f.' + k, v);
    const data = await api('GET', '/r/' + key + '?' + qs);
    if (!append) tbody.replaceChildren();
    for (const item of data.items) {
      tbody.append(h('tr', { 'data-id': item.id, onclick: () => { location.hash = '#/r/' + key + '/' + encodeURIComponent(item.id); } },
        reorderable ? h('td', { class: 'handle', title: 'Drag to reorder', text: '⋮⋮', onclick: (e) => e.stopPropagation() }) : null,
        cols.map((f, i) => h('td', { class: i > 2 ? 'opt' : null }, cellValue(f, item[f.name], data.refs)))));
    }
    offset += data.items.length;
    more.replaceChildren(offset < data.total
      ? h('button', { class: 'btn', text: `Load more (${data.total - offset} left)`, onclick: () => load(true).catch(fail) })
      : h('span', { class: 'pill', text: `${data.total} total` }));
    if (reorderable) {
      sortable(tbody, async (ids) => {
        try { await api('POST', '/r/' + key + '/reorder', { ids }); toast('Order saved'); } catch (e) { fail(e); }
      });
    }
    if (!data.items.length && !append) tbody.append(h('tr', {}, h('td', { colspan: String(cols.length + 1), class: 'empty', text: 'Nothing here yet.' })));
  }

  const search = h('input', { type: 'search', class: 'search', placeholder: 'Search…', value: q });
  search.addEventListener('change', () => {
    const p = new URLSearchParams(location.hash.split('?')[1] || '');
    if (search.value) p.set('q', search.value); else p.delete('q');
    location.hash = '#/r/' + key + (p.toString() ? '?' + p : '');
  });
  const filterEls = await Promise.all(filters.map(async (f) => {
    const options = await api('GET', '/lookup/' + f.ref);
    const sel = h('select', { 'aria-label': f.label, onchange: () => {
      const p = new URLSearchParams(location.hash.split('?')[1] || '');
      if (sel.value) p.set('f.' + f.name, sel.value); else p.delete('f.' + f.name);
      location.hash = '#/r/' + key + (p.toString() ? '?' + p : '');
    } }, h('option', { value: '', text: 'Any ' + f.label.toLowerCase() }),
    options.map((o) => h('option', { value: o.id, text: o.label, selected: o.id === filterValues[f.name] ? 'selected' : null })));
    return sel;
  }));

  mount(shell('#/r/' + key,
    h('h1', { text: res.label }),
    h('p', { class: 'lede', text: res.description }),
    h('div', { class: 'toolbar' }, search, ...filterEls, h('div', { class: 'grow' }),
      res.canCreate && state.me.canEdit ? h('a', { class: 'btn primary', href: '#/r/' + key + '/new', text: '+ New' }) : null),
    h('div', { class: 'card' }, h('table', {},
      h('thead', {}, h('tr', {}, reorderable ? h('th', {}) : null, cols.map((f, i) => h('th', { class: i > 2 ? 'opt' : null, text: f.label })))),
      tbody)),
    more));
  await load(false);
}

// ── Generic form ──────────────────────────────────────────────────────────────

async function refOptions(refKey) {
  return api('GET', '/lookup/' + refKey);
}

/** Builds one input for [f]; returns {el, read(): value|undefined(invalid)}. */
async function input(f, value) {
  const id = 'f_' + f.name;
  const common = { id, name: f.name, disabled: !state.me.canEdit };
  switch (f.type) {
    case 'LONGTEXT': {
      const el = h('textarea', { ...common, maxlength: String(f.maxLength), value: value ?? '' });
      return { el, read: () => el.value };
    }
    case 'JSON': {
      const el = h('textarea', { ...common, class: 'code', value: value ? JSON.stringify(value, null, 2) : '' });
      return { el, read: () => { if (!el.value.trim()) return null; try { return JSON.parse(el.value); } catch (_) { return undefined; } } };
    }
    case 'BOOL': {
      const box = h('input', { ...common, type: 'checkbox', checked: !!value });
      return { el: h('label', { class: 'switch' }, box, h('span', { class: 'track' }), h('span', { text: 'Yes' })), read: () => box.checked };
    }
    case 'INT': case 'FLOAT': {
      const el = h('input', { ...common, type: 'number', step: f.type === 'INT' ? '1' : 'any', min: f.min ?? null, max: f.max ?? null, value: value ?? '' });
      return { el, read: () => (el.value === '' ? null : Number(el.value)) };
    }
    case 'PAISE': {
      const el = h('input', { ...common, type: 'number', step: '0.01', min: '0', value: value === null || value === undefined ? '' : String(value / 100) });
      return { el, read: () => (el.value === '' ? null : Math.round(Number(el.value) * 100)) };
    }
    case 'DATETIME': {
      const el = h('input', { ...common, type: 'datetime-local', value: isoToIstInput(value) });
      return { el, read: () => istInputToIso(el.value) };
    }
    case 'TIME': {
      const el = h('input', { ...common, type: 'time', value: value ?? '' });
      return { el, read: () => el.value || null };
    }
    case 'TAGS': {
      const el = h('input', { ...common, type: 'text', value: (value || []).join(', '), placeholder: 'Comma-separated' });
      return { el, read: () => el.value.split(',').map((s) => s.trim()).filter(Boolean) };
    }
    case 'SELECT': {
      const el = h('select', common,
        f.required ? null : h('option', { value: '', text: '—' }),
        (f.options || []).map((o) => h('option', { value: o.value, text: o.label, selected: o.value === value ? 'selected' : null })));
      if (value && !(f.options || []).some((o) => o.value === value)) el.append(h('option', { value, text: value, selected: 'selected' }));
      return { el, read: () => el.value || null };
    }
    case 'REF': {
      const options = await refOptions(f.ref);
      const el = h('select', common,
        h('option', { value: '', text: f.required ? 'Choose…' : '—' }),
        options.map((o) => h('option', { value: o.id, text: o.label, selected: o.id === value ? 'selected' : null })));
      return { el, read: () => el.value || null };
    }
    case 'IMAGE': {
      const preview = h('img', { class: 'preview', alt: '', src: value || null, hidden: !value });
      preview.addEventListener('error', () => { preview.hidden = true; });
      const el = h('input', { ...common, type: 'url', value: value ?? '', placeholder: 'https://…', oninput: () => {
        preview.hidden = !el.value; if (el.value) preview.src = el.value;
      } });
      return { el: h('div', { class: 'field' }, el, preview), read: () => el.value.trim() || null };
    }
    case 'URL': {
      const el = h('input', { ...common, type: 'url', value: value ?? '', placeholder: 'https://…' });
      return { el, read: () => el.value.trim() || null };
    }
    default: {
      const el = h('input', { ...common, type: 'text', maxlength: String(f.maxLength), value: value ?? '' });
      return { el, read: () => el.value.trim() || null };
    }
  }
}

function same(a, b) {
  return JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
}

async function formView(key, id, query) {
  const res = state.res[key];
  if (!res) { location.hash = '#/home'; return; }
  const creating = id === null;
  let item = {};
  if (!creating) item = (await api('GET', '/r/' + key + '/' + encodeURIComponent(id))).item;
  // New rows: field defaults, except the order (empty = the server appends it at the end).
  else for (const f of res.fields) if (f.default !== null && f.default !== undefined && f.name !== res.sortField) item[f.name] = f.default;
  if (creating) for (const [k, v] of query.entries()) if (res.fields.some((f) => f.name === k)) item[k] = v;

  const inputs = {};
  const fieldEls = {};
  const form = h('div', { class: 'form' });
  for (const f of res.fields) {
    const inp = await input(f, item[f.name]);
    inputs[f.name] = inp;
    const err = h('div', { class: 'err' });
    const wide = ['LONGTEXT', 'JSON', 'IMAGE', 'TAGS'].includes(f.type);
    const el = h('div', { class: 'field' + (wide ? ' wide' : '') },
      h('label', { for: 'f_' + f.name }, f.label, f.required ? h('span', { class: 'req', text: ' *' }) : null),
      inp.el, f.help ? h('div', { class: 'help', text: f.help }) : null, err);
    fieldEls[f.name] = { el, err };
    form.append(el);
  }

  // Sections: explain the chosen type, and show/hide the category picker.
  if (key === 'sections') {
    const help = h('div', { class: 'kind-help' });
    const kindSel = form.querySelector('#f_kind');
    const update = () => {
      const k = state.kinds[kindSel.value];
      help.textContent = k ? k.label + ': ' + k.help : '';
      fieldEls.categoryId.el.hidden = !(k && k.usesCategory);
    };
    kindSel.addEventListener('change', update);
    form.prepend(help);
    update();
  }

  const save = h('button', { class: 'btn primary', text: creating ? 'Create' : 'Save changes' });
  save.addEventListener('click', async () => {
    const body = {};
    let invalid = false;
    for (const f of res.fields) {
      fieldEls[f.name].el.classList.remove('invalid');
      fieldEls[f.name].err.textContent = '';
      const v = inputs[f.name].read();
      if (v === undefined) {
        invalid = true;
        fieldEls[f.name].el.classList.add('invalid');
        fieldEls[f.name].err.textContent = f.type === 'JSON' ? 'Not valid JSON' : 'Not valid';
        continue;
      }
      if (creating && f.name === res.sortField && v === null) continue;
      if (creating || !same(v, item[f.name])) body[f.name] = v;
    }
    if (invalid) return;
    if (!creating && !Object.keys(body).length) { toast('No changes'); return; }
    save.disabled = true;
    try {
      const out = creating
        ? await api('POST', '/r/' + key, body)
        : await api('PATCH', '/r/' + key + '/' + encodeURIComponent(id), body);
      toast(creating ? 'Created' : 'Saved — live in the app within a minute');
      const back = key === 'sections' ? '#/home' : '#/r/' + key;
      if (creating && key === 'sections' && state.kinds[out.item.kind] && state.kinds[out.item.kind].itemType) {
        location.hash = '#/sections/' + encodeURIComponent(out.item.id) + '/items';
      } else {
        location.hash = back;
      }
    } catch (e) {
      if (e.fields) {
        for (const [name, msgs] of Object.entries(e.fields)) {
          if (!fieldEls[name]) continue;
          fieldEls[name].el.classList.add('invalid');
          fieldEls[name].err.textContent = msgs.join(' · ');
        }
      }
      fail(e);
    } finally {
      save.disabled = false;
    }
  });

  const del = !creating && res.canDelete && state.me.canEdit ? h('button', {
    class: 'btn danger',
    text: 'Delete',
    onclick: async () => {
      if (!confirm('Delete this ' + res.label.toLowerCase() + ' entry? This can\'t be undone.')) return;
      try {
        await api('DELETE', '/r/' + key + '/' + encodeURIComponent(id));
        toast('Deleted');
        location.hash = key === 'sections' ? '#/home' : '#/r/' + key;
      } catch (e) { fail(e); }
    },
  }) : null;

  const backHref = key === 'sections' ? '#/home' : '#/r/' + key;
  const title = creating ? 'New: ' + res.label : (item[res.titleField] || res.label);
  const extra = !creating && key === 'sections' && state.kinds[item.kind] && state.kinds[item.kind].itemType
    ? h('a', { class: 'btn', href: '#/sections/' + encodeURIComponent(id) + '/items', text: 'Pick videos' }) : null;
  mount(shell(key === 'sections' ? '#/home' : '#/r/' + key,
    h('a', { class: 'back', href: backHref, text: '← ' + (key === 'sections' ? 'Home layout' : res.label) }),
    h('h1', { text: String(title).slice(0, 120) }),
    h('p', { class: 'lede', text: res.description }),
    h('div', { class: 'card' }, form,
      state.me.canEdit ? h('div', { class: 'actions' }, save, extra, h('div', { class: 'grow' }), del) : null)));
}

// ── Hand-picked videos for a section ──────────────────────────────────────────

async function itemsView(sectionId) {
  const section = (await api('GET', '/r/sections/' + encodeURIComponent(sectionId))).item;
  let chosen = (await api('GET', '/sections/' + encodeURIComponent(sectionId) + '/items')).items
    .map((i) => ({ id: i.refId, label: i.label }));
  const list = h('div');
  const results = h('div', { class: 'results' });

  function drawChosen() {
    list.replaceChildren(...chosen.map((c) => h('div', { class: 'pick', 'data-id': c.id },
      h('span', { class: 'handle', text: '⋮⋮' }),
      h('span', { class: 'grow', text: c.label }),
      h('button', { class: 'btn small', text: 'Remove', onclick: () => { chosen = chosen.filter((x) => x.id !== c.id); drawChosen(); } }))));
    if (!chosen.length) list.append(h('div', { class: 'empty', text: 'Nothing picked yet. Add videos from the right.' }));
    sortable(list, (ids) => {
      const byId = Object.fromEntries(chosen.map((c) => [c.id, c]));
      chosen = ids.filter((x) => byId[x]).map((x) => byId[x]);
    });
  }

  async function search(q) {
    const found = await api('GET', '/lookup/sessions' + (q ? '?q=' + encodeURIComponent(q) : ''));
    results.replaceChildren(...found.map((s) => h('div', {
      class: 'pick',
      onclick: () => {
        if (chosen.some((c) => c.id === s.id)) { toast('Already in the list'); return; }
        chosen.push({ id: s.id, label: s.label });
        drawChosen();
      },
    }, h('span', { class: 'grow', text: s.label }), h('span', { class: 'pill', text: 'Add' }))));
  }

  const q = h('input', { type: 'search', placeholder: 'Search videos…', oninput: () => search(q.value).catch(fail) });
  const save = h('button', {
    class: 'btn primary',
    text: 'Save order',
    onclick: async () => {
      try {
        await api('PUT', '/sections/' + encodeURIComponent(sectionId) + '/items', { items: chosen.map((c) => ({ refType: 'SESSION', refId: c.id })) });
        toast('Saved — live in the app within a minute');
      } catch (e) { fail(e); }
    },
  });
  mount(shell('#/home',
    h('a', { class: 'back', href: '#/home', text: '← Home layout' }),
    h('h1', { text: 'Pick videos: ' + section.title }),
    h('p', { class: 'lede', text: `Choose the videos for this section and drag them into order. The app shows up to ${section.maxItems}.` }),
    h('div', { class: 'picker' },
      h('div', { class: 'card' }, h('h3', { text: 'In this section' }), list, state.me.canEdit ? h('div', { class: 'toolbar' }, save) : null),
      h('div', { class: 'card' }, h('h3', { text: 'All videos' }), q, h('div', { style: null }), results))));
  drawChosen();
  await search('');
}

// ── Schedule a run of live classes ────────────────────────────────────────────

async function scheduleView() {
  const batches = await api('GET', '/lookup/batches');
  const today = isoToIstInput(new Date().toISOString()).slice(0, 10);
  const f = {
    batchId: h('select', { id: 's_batch' }, h('option', { value: '', text: 'Choose a batch…' }), batches.map((b) => h('option', { value: b.id, text: b.label }))),
    fromDate: h('input', { id: 's_from', type: 'date', value: today }),
    days: h('input', { id: 's_days', type: 'number', min: '1', max: '62', value: '7' }),
    title: h('input', { id: 's_title', type: 'text', placeholder: 'Defaults to the batch name' }),
    videoProvider: h('select', { id: 's_provider' }, h('option', { value: 'slike', text: 'Slike media id' }), h('option', { value: 'url', text: 'Stream URL' })),
    videoRef: h('input', { id: 's_ref', type: 'text', placeholder: 'Slike id or https://…' }),
    durationMinutes: h('input', { id: 's_minutes', type: 'number', min: '5', max: '300', value: '60' }),
  };
  const isFree = h('input', { type: 'checkbox' });
  const errs = {};
  const field = (name, label, el, help) => {
    errs[name] = h('div', { class: 'err' });
    return h('div', { class: 'field' }, h('label', { for: el.id, text: label }), el, help ? h('div', { class: 'help', text: help }) : null, errs[name]);
  };
  const go = h('button', {
    class: 'btn primary',
    text: 'Create classes',
    onclick: async () => {
      Object.values(errs).forEach((e) => { e.textContent = ''; });
      go.disabled = true;
      try {
        const out = await api('POST', '/live-classes/schedule', {
          batchId: f.batchId.value, fromDate: f.fromDate.value, days: Number(f.days.value), title: f.title.value || undefined,
          videoProvider: f.videoProvider.value, videoRef: f.videoRef.value, durationMinutes: Number(f.durationMinutes.value),
          isFree: isFree.checked,
        });
        toast(`Created ${out.created} classes` + (out.skipped ? ` (${out.skipped} days already had one)` : ''));
        location.hash = '#/r/live-classes';
      } catch (e) {
        if (e.fields) for (const [k, v] of Object.entries(e.fields)) if (errs[k]) errs[k].textContent = v.join(' · ');
        fail(e);
      } finally { go.disabled = false; }
    },
  });
  mount(shell('#/schedule',
    h('h1', { text: 'Schedule a week of live classes' }),
    h('p', { class: 'lede', text: 'Creates one live class per day at the batch\'s time (IST), all streaming the same pre-recorded video like a premiere. Days that already have a class for the batch are skipped, so running it twice is safe. Edit single days afterwards under Live classes.' }),
    h('div', { class: 'card' },
      h('div', { class: 'form' },
        field('batchId', 'Batch', f.batchId),
        field('fromDate', 'First day', f.fromDate),
        field('days', 'Number of days', f.days, '1 to 62'),
        field('durationMinutes', 'Minutes', f.durationMinutes),
        field('videoProvider', 'Video source', f.videoProvider),
        field('videoRef', 'Video', f.videoRef),
        field('title', 'Title (optional)', f.title),
        h('div', { class: 'field' }, h('label', { text: 'Free for everyone' }), h('label', { class: 'switch' }, isFree, h('span', { class: 'track' }), h('span', { text: 'Yes' })))),
      state.me.canEdit ? h('div', { class: 'actions' }, go) : null)));
}

// ── Admins (owners) ───────────────────────────────────────────────────────────

async function adminsView() {
  const admins = await api('GET', '/admins');
  const roleSelect = (value, onchange) => h('select', { onchange },
    ['OWNER', 'EDITOR', 'VIEWER'].map((r) => h('option', { value: r, text: r, selected: r === value ? 'selected' : null })));
  const rows = admins.map((a) => h('tr', {},
    h('td', { text: a.name }), h('td', { text: a.email }),
    h('td', {}, roleSelect(a.role, async (e) => {
      try { await api('PATCH', '/admins/' + a.id, { role: e.target.value }); toast('Role updated'); } catch (err) { fail(err); adminsView(); }
    })),
    h('td', { class: 'opt', text: a.lastLoginAt ? formatIst(a.lastLoginAt) : 'Never' }),
    h('td', {}, h('label', { class: 'switch' }, h('input', {
      type: 'checkbox', checked: a.active,
      onchange: async (e) => {
        try { await api('PATCH', '/admins/' + a.id, { active: e.target.checked }); toast(e.target.checked ? 'Activated' : 'Deactivated'); } catch (err) { fail(err); adminsView(); }
      },
    }), h('span', { class: 'track' }))),
    h('td', {}, h('button', {
      class: 'btn small', text: 'Reset password',
      onclick: async () => {
        const pw = prompt('New password for ' + a.email + ' (12+ characters):');
        if (!pw) return;
        try { await api('PATCH', '/admins/' + a.id, { password: pw }); toast('Password changed'); } catch (err) { fail(err); }
      },
    }))));
  const nf = {
    name: h('input', { type: 'text', id: 'a_name' }),
    email: h('input', { type: 'email', id: 'a_email' }),
    role: roleSelect('EDITOR'),
    password: h('input', { type: 'password', id: 'a_pw', autocomplete: 'new-password' }),
  };
  const add = h('button', {
    class: 'btn primary', text: 'Add admin',
    onclick: async () => {
      try {
        await api('POST', '/admins', { name: nf.name.value, email: nf.email.value, role: nf.role.value, password: nf.password.value });
        toast('Admin added. Share the password with them privately.');
        adminsView();
      } catch (e) { fail(e); }
    },
  });
  mount(shell('#/admins',
    h('h1', { text: 'Admins' }),
    h('p', { class: 'lede', text: 'Who can use this dashboard. Owners manage admins; editors change content; viewers can only look.' }),
    h('div', { class: 'card' }, h('table', {},
      h('thead', {}, h('tr', {}, ['Name', 'Email', 'Role'].map((t) => h('th', { text: t })), h('th', { class: 'opt', text: 'Last sign-in' }), h('th', { text: 'Active' }), h('th', {}))),
      h('tbody', {}, rows))),
    h('h1', { text: 'Add an admin', style: null }),
    h('div', { class: 'card' }, h('div', { class: 'form' },
      h('div', { class: 'field' }, h('label', { for: 'a_name', text: 'Name' }), nf.name),
      h('div', { class: 'field' }, h('label', { for: 'a_email', text: 'Email' }), nf.email),
      h('div', { class: 'field' }, h('label', { text: 'Role' }), nf.role),
      h('div', { class: 'field' }, h('label', { for: 'a_pw', text: 'Password (12+ characters)' }), nf.password)),
    h('div', { class: 'actions' }, add))));
}

// ── Activity ──────────────────────────────────────────────────────────────────

async function activityView() {
  const rows = await api('GET', '/audit?limit=200');
  const label = (key) => (state.res[key] ? state.res[key].label : key);
  mount(shell('#/activity',
    h('h1', { text: 'Activity' }),
    h('p', { class: 'lede', text: 'Every change made in this dashboard: who, what and when (newest first).' }),
    h('div', { class: 'card' }, h('table', {},
      h('thead', {}, h('tr', {}, ['When', 'Who', 'Action', 'What'].map((t) => h('th', { text: t })), h('th', { class: 'opt', text: 'Fields' }))),
      h('tbody', {}, rows.map((r) => h('tr', { style: null },
        h('td', { text: formatIst(r.createdAt) }),
        h('td', { text: r.adminEmail }),
        h('td', {}, h('span', { class: 'pill', text: r.action })),
        h('td', { text: label(r.resource) + (r.resourceId ? ' · ' + r.resourceId : '') }),
        h('td', { class: 'opt', text: r.summary && r.summary.fields ? r.summary.fields.join(', ') : '' }))))))));
}

boot();
