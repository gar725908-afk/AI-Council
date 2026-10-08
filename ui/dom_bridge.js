function councilBridge(config) {
  const {service, adapter, operation: op, args} = config;
  const store = window.__aicouncilBridge || (window.__aicouncilBridge = {
    nodes: new WeakMap(), sequence: 0, editors: {}, attempts: {}, observers: {},
    documentEpoch: Date.now().toString(36) + Math.random().toString(36).slice(2)
  });
  const roots = [document];
  for (let n = 0; n < roots.length; n++) {
    for (const el of roots[n].querySelectorAll('*')) {
      if (el.shadowRoot && !roots.includes(el.shadowRoot)) roots.push(el.shadowRoot);
    }
  }
  const query = selector => {
    const result = [];
    for (const root of roots) {
      try { result.push(...root.querySelectorAll(selector)); }
      catch (err) { store.lastSelectorError = String(err); }
    }
    return [...new Set(result)];
  };
  const visible = el => {
    if (!el || !el.isConnected) return false;
    const rect = el.getBoundingClientRect(), css = getComputedStyle(el);
    return rect.width > 0 && rect.height > 0 &&
      css.display !== 'none' && css.visibility !== 'hidden';
  };
  const disabled = el => !!el.disabled || el.getAttribute('aria-disabled') === 'true' ||
    /(^|\s)disabled(\s|$)|\bds-button--disabled\b/.test(String(el.className));
  const read = el => String('value' in el ? el.value : (el.innerText || el.textContent || ''))
    .replace(/\r\n/g, '\n').trim();
  const normalize = value => String(value || '').replace(/\s+/g, ' ').trim();
  const serviceError = () => {
    const body = document.body?.innerText || '';
    if (service === 'ChatGPT' && !findEditor() &&
        /we couldn[’']t load your account/i.test(body)) return 'ChatGPT не смог загрузить аккаунт';
    if (service === 'Grok' && /High Demand\s+Grok is under heavy usage/.test(body))
      return 'Высокая нагрузка на Grok (High Demand)';
    if (service === 'Grok') {
      const messageSelectors = [...adapter.user_selectors, ...adapter.response_selectors,
        '[data-message-author-role]', '[contenteditable]', 'textarea'];
      for (const el of query('p,span,div')) {
        if (!visible(el) || el.children.length || messageSelectors.some(selector => {
          try { return !!el.closest(selector); } catch (err) { return false; }
        })) continue;
        const text = normalize(el.innerText);
        if (/^\d[\d\sа-яё.,:]*до сброса лимита$/i.test(text))
          return 'Лимит Grok исчерпан: ' + text;
      }
    }
    return '';
  };
  const statusLine = /^(?:(?:working for|работа для) \d+(?:\.\d+)?\s*(?:ms|s|sec|seconds)?|(?:thinking|loading|generating|processing|please wait|reading sources|searching the web)[.\u2026]{0,3}|(?:думаю|генерирую|загрузка|обработка|читаю источники)[.\u2026]{0,3})(?:\s+(?:skip|пропустить))?$/i;
  const clean = value => {
    let afterStatus = false;
    return String(value || '').split('\n').filter(line => {
      const text = line.trim();
      if (statusLine.test(text)) { afterStatus = true; return false; }
      if (afterStatus && /^(skip|пропустить)$/i.test(text)) return false;
      if (text) afterStatus = false;
      return true;
    }).join('\n').trim();
  };
  const nodeKey = el => {
    if (!store.nodes.has(el)) store.nodes.set(el, 'node:' + (++store.sequence));
    return store.nodes.get(el);
  };
  const explicitId = el => {
    if (!el) return '';
    for (const attr of ['data-message-id', 'data-message-uuid', 'data-turn-id', 'data-turn-key',
      'data-conversation-turn-id', 'data-archer-id']) {
      if (el.getAttribute(attr)) return attr + ':' + el.getAttribute(attr);
    }
    return '';
  };
  const findEditor = () => {
    const cached = store.editors[service];
    if (cached && visible(cached.el) && !disabled(cached.el)) return cached;
    const fallback = ['[role="textbox"][contenteditable="true"]', '[contenteditable="true"]',
      'textarea[placeholder]', 'textarea'];
    for (const selector of [...adapter.input_selectors, ...fallback]) {
      for (let el of query(selector)) {
        if (el.tagName === 'RICH-TEXTAREA') {
          el = el.querySelector('[contenteditable="true"]') ||
            el.shadowRoot?.querySelector('[contenteditable="true"]') || el;
        }
        if (!visible(el) || disabled(el) || el.getAttribute('contenteditable') === 'false') continue;
        if (!el.isContentEditable && !['TEXTAREA', 'INPUT'].includes(el.tagName)) continue;
        const hit = {el, selector};
        store.editors[service] = hit;
        return hit;
      }
    }
    return null;
  };
  const userSelectors = [...new Set([...adapter.user_selectors,
    '[data-message-author-role="user"]', '[data-conversation-role="user"]',
    '[data-role="user"]', '[data-turn="user"]'])];
  const users = () => {
    const items = new Map();
    for (const selector of userSelectors) {
      for (const el of query(selector)) {
        const root = el.closest('[data-turn-key],article[data-turn],section[data-turn],.chat-content-item-user') || el;
        const content = root.querySelector('.user-content__text,.user-content,.whitespace-pre-wrap,.user-message-bubble-color') || el;
        const key = explicitId(root) || explicitId(el) || nodeKey(root);
        items.set(key, {key, text:read(content), el:root});
      }
    }
    return [...items.values()];
  };
  const generating = () => {
    for (const selector of adapter.completion_selectors) {
      if (query(selector).some(el => visible(el) && !disabled(el))) return true;
    }
    for (const el of query('button,[role="button"]')) {
      const label = [el.getAttribute('aria-label'), el.getAttribute('title'),
        el.getAttribute('data-testid'), el.innerText].filter(Boolean).join(' ');
      if (visible(el) && !disabled(el) &&
          /stop(?: answering| generating| response)?|остановить|прекратить генерацию/i.test(label)) return true;
    }
    return query('[data-is-streaming="true"],[data-streaming="true"]')
      .some(visible);
  };
  const messages = () => {
    const selectors = [...new Set([...adapter.response_selectors,
      '[data-message-author-role="assistant"]', '[data-conversation-role="assistant"]',
      '[data-role="assistant"]', '[data-turn="assistant"]', '[data-testid="assistant-message"]',
      '[role="assistant"]', '[class*="assistant-message"]', '[class*="assistant-response"]'])];
    const items = new Map();
    for (const selector of selectors) {
      for (const el of query(selector)) {
        if (!visible(el)) continue;
        if (service === 'Kimi' && el.closest('.toolcall-content,.toolcall-content-text')) continue;
        let isUser = userSelectors.some(sel => {
          try { return el.matches(sel) || !!el.closest(sel); }
          catch (err) { return false; }
        });
        if (isUser) continue;
        let root = el.closest('[data-turn-key],[data-testid^="conversation-turn-"],' +
          'article[data-turn],section[data-turn],model-response,.chat-content-item-assistant,' +
          '[data-testid="assistant-message"]') || el;
        if (userSelectors.some(sel => { try { return !!root.querySelector(sel); } catch (err) { return false; } })) root = el;
        if (userSelectors.some(sel => { try { return root.matches(sel); } catch (err) { return false; } })) continue;
        let content = root;
        const contentSelectors = ['.markdown.prose', '.markdown', '.standard-markdown',
          '.progressive-markdown', '.font-claude-response', '.ds-markdown',
          '.response-message-content.phase-answer', '.markdown-container', 'message-content'];
        for (const cs of contentSelectors) {
          if (root.matches(cs)) break;
          const nodes = [...root.querySelectorAll(cs)].filter(visible);
          if (nodes.length === 1) { content = nodes[0]; break; }
        }
        let answerText = read(content);
        if (service === 'Kimi') {
          const finals = [root, ...root.querySelectorAll('.markdown-container')]
            .filter(node => node.matches('.markdown-container') && visible(node) &&
              !node.closest('.toolcall-content,.toolcall-content-text'));
          if (!finals.length) continue;
          answerText = finals.map(read).join('\n\n');
        }
        let text = clean(answerText).replace(
          /^(chatgpt|claude|алиса|grok|qwen|kimi|deepseek|assistant)\s+said:?\s*/i, '').trim();
        if (!text) continue;
        const key = explicitId(root) || explicitId(el) || nodeKey(root);
        const item = {key, text, selector, el:root};
        const previous = items.get(key);
        if (!previous || text.length > previous.text.length) items.set(key, item);
      }
    }
    const out = [...items.values()];
    out.sort((a,b) => {
      if (a.el === b.el) return 0;
      const pos = a.el.compareDocumentPosition(b.el);
      if (pos & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
      if (pos & Node.DOCUMENT_POSITION_PRECEDING) return 1;
      return 0;
    });
    return out;
  };
  const publicItems = items => items.map(({key,text,selector}) => ({key,text,selector}));
  const inspect = () => {
    const hit = findEditor(), body = document.body?.innerText || '';
    const url = location.href.toLowerCase();
    const controls = query('button,a,[role="button"]').filter(visible)
      .map(el => normalize(el.innerText || el.getAttribute('aria-label')).toLowerCase());
    const loginUrl = adapter.login_url_fragments.some(p => url.includes(p.toLowerCase())) ||
      /accounts\.google\.com|auth\.openai\.com/.test(url);
    const loginControls = controls.some(t =>
      /^(sign in|log in|войти|вход|登录|login)$/.test(t));
    const captcha = !hit && /captcha|verify you are human|security verification|checking your browser|just a moment|проверка.*человек/i
      .test((document.title || '') + ' ' + body);
    const loginModal = query('[role="dialog"]').filter(visible).some(dialog =>
      /sign in|log in|войти|вход|登录/i.test(dialog.innerText || ''));
    const loginRequired = loginUrl || (!hit && loginControls) || loginModal ||
      (!hit && /captcha/i.test((document.title || '') + ' ' + body));
    const agePattern = /confirm your age|what year were you born|подтвердите.*возраст/i;
    const age = query('[role="dialog"]').filter(visible)
      .some(dialog => agePattern.test(dialog.innerText || '')) || (!hit && agePattern.test(body));
    const ready = ['interactive','complete'].includes(document.readyState) && !!document.body;
    const busy = generating();
    const m = messages(), u = users();
    return {ok:true,ready,url:location.href,title:document.title,
      document_epoch:store.documentEpoch,
      input_found:!!hit,input_selector:hit?.selector || '',login_required:loginRequired,
      blocked_reason:age?'age_confirmation':captcha?'browser_challenge':'',service_error:serviceError(),
      generating:busy, input_text:hit?read(hit.el):'',
      responses:m.map(x=>x.text),response_ids:m.map(x=>x.key),response_node_count:m.length,
      user_count:u.length,user_ids:u.map(x=>x.key),user_texts:u.map(x=>x.text)};
  };
  const extract = () => {
    const all = messages(), before = new Set(args.before_ids || []);
    const prompt = normalize(args.prompt);
    const turns = users();
    const currentUser = [...turns].reverse().find(u => normalize(u.text) === prompt);
    let fresh = all.filter(x => !before.has(x.key));
    if (currentUser) fresh = fresh.filter(x => {
      const pos = currentUser.el.compareDocumentPosition(x.el);
      return !!(pos & Node.DOCUMENT_POSITION_FOLLOWING);
    });
    // A recreated old DOM node must not be mistaken for a new answer.
    if (!currentUser) {
      const oldTexts = new Set((args.before_texts || []).map(normalize));
      fresh = fresh.filter(x => !oldTexts.has(normalize(x.text)) ||
        all.length > Number(args.before_node_count || 0));
    }
    const best = fresh[fresh.length - 1];
    return {ok:!!best,text:best?.text || '',message_id:best?.key || '',service_error:serviceError(),
      selector:best?.selector || '',node_count:all.length,generating:generating(),
      texts:all.map(x=>x.text)};
  };
  if (op === 'inspect') return inspect();
  if (op === 'focus') {
    const hit = findEditor();
    if (!hit) return {ok:false,reason:'input_not_found'};
    const current = read(hit.el);
    if (args.prompt && current && current !== args.prompt && current !== args.expected_draft)
      return {ok:false,reason:'user_draft_changed'};
    hit.el.focus();
    const rect = hit.el.getBoundingClientRect();
    return {ok:true,selector:hit.selector,x:Math.round(rect.left+rect.width/2),y:Math.round(rect.top+rect.height/2)};
  }
  if (op === 'extract') return extract();
  if (op === 'snapshot') {
    const m = messages();
    return {ok:true,texts:m.map(x=>x.text),node_count:m.length,items:publicItems(m)};
  }
  if (op === 'fill') {
    const hit = findEditor();
    if (!hit) return {ok:false,reason:'input_not_found'};
    const {el,selector} = hit, text = String(args.prompt || '').trim();
    const current = read(el);
    if (current === text) return {ok:true,skipped:true,selector,method:'unchanged'};
    if (current && current !== args.expected_draft) return {ok:false,reason:'user_draft_changed'};
    el.focus();
    let method = 'native_setter', emitted = false;
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(proto,'value')?.set;
      if (setter) setter.call(el,text); else el.value = text;
    } else {
      method = 'insertText';
      const selection = window.getSelection(), range = document.createRange();
      range.selectNodeContents(el); selection.removeAllRanges(); selection.addRange(range);
      let events = 0;
      const track = () => { events++; };
      el.addEventListener('input',track);
      try { document.execCommand('insertText',false,text); }
      catch (err) { store.lastFillError = String(err); }
      el.removeEventListener('input',track);
      emitted = events > 0;
      if (read(el) !== text) {
        // Paste is consumed by Lexical/ProseMirror even when direct insertion is blocked.
        const data = new DataTransfer(); data.setData('text/plain',text);
        el.dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));
        method = 'paste';
      }
      if (read(el) !== text) { el.textContent=text; emitted=false; method='textContent'; }
    }
    if (!emitted) el.dispatchEvent(new InputEvent('input',
      {bubbles:true,inputType:'insertText',data:text}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
    return {ok:read(el)===text,text:read(el),selector,method};
  }
  if (op === 'send') {
    const token = String(args.request_id || ''), hit = findEditor();
    if (token && store.attempts[token]) return {ok:true,triggered:true,method:'already_triggered'};
    if (!hit || !read(hit.el)) return {ok:false,reason:'input_empty'};
    if (args.prompt && normalize(read(hit.el)) !== normalize(args.prompt)) return {ok:false,reason:'user_draft_changed'};
    if (generating()) return {ok:false,reason:'service_busy'};
    const error = serviceError();
    if (error) return {ok:false,reason:'service_error',service_error:error};
    const send = (el,method,selector) => {
      if (token) store.attempts[token] = {at:Date.now(),method};
      el.click();
      return {ok:true,triggered:true,method,selector};
    };
    let blockedSend = false;
    for (const selector of adapter.send_selectors) {
      for (const el of query(selector)) {
        if (visible(el) && !disabled(el)) return send(el,'button',selector);
        if (visible(el) && disabled(el)) blockedSend = true;
      }
    }
    const form = hit.el.closest('form'), scope = form || hit.el.parentElement?.parentElement || hit.el.getRootNode();
    for (const el of scope.querySelectorAll('button,[role="button"]')) {
      const label = [el.getAttribute('aria-label'),el.getAttribute('title'),
        el.getAttribute('data-testid'),el.innerText].filter(Boolean).join(' ');
      if (visible(el) && /send|submit|отправить|发送/i.test(label)) {
        if (!disabled(el)) return send(el,'generic_button',label);
        blockedSend = true;
      }
    }
    if (blockedSend) return {ok:false,reason:'send_not_ready'};
    if (args.use_enter) {
      if (token) store.attempts[token] = {at:Date.now(),method:form?'form':'enter'};
      if (form?.requestSubmit) {
        form.requestSubmit();
        return {ok:true,triggered:true,method:'form'};
      }
      hit.el.focus();
      hit.el.dispatchEvent(new KeyboardEvent('keydown',
        {key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
      hit.el.dispatchEvent(new KeyboardEvent('keyup',
        {key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
      return {ok:true,triggered:true,method:'enter'};
    }
    return {ok:false,reason:'send_not_ready'};
  }
  if (op === 'confirm') {
    const info = inspect(), before = new Set(args.before_user_ids || []);
    const newUser = users().some(x => !before.has(x.key) && normalize(x.text) === normalize(args.prompt));
    const response = extract();
    const attempted = !!store.attempts[String(args.request_id || '')];
    const cleared = info.input_found && !info.input_text;
    const clearedAfterSend = attempted && cleared && !info.login_required &&
      !info.blocked_reason && !info.service_error;
    return {ok:true,accepted:newUser || clearedAfterSend ||
      (cleared && (info.generating || response.ok)),new_user:newUser,
      confirmation_method:newUser?'new_user':clearedAfterSend?'composer_cleared':'response',
      composer_empty:info.input_found&&!info.input_text,generating:info.generating,
      candidate:response,login_required:info.login_required,blocked_reason:info.blocked_reason};
  }
  if (op === 'observe') {
    const token = String(args.request_id);
    for (const entry of Object.values(store.observers)) entry.observer.disconnect();
    store.observers = {};
    const entry = {result:null};
    const update = () => { entry.result = extract(); };
    const observer = new MutationObserver(update);
    for (const root of roots) observer.observe(root,{subtree:true,childList:true,characterData:true});
    entry.observer=observer; store.observers[token]=entry; update();
    return {ok:true,request_id:token};
  }
  if (op === 'observer_read') return store.observers[String(args.request_id)]?.result ||
    {ok:false,text:''};
  if (op === 'observer_stop') {
    const token=String(args.request_id), entry=store.observers[token];
    if (entry) entry.observer.disconnect();
    delete store.observers[token]; delete store.attempts[token];
    return {ok:true};
  }
  throw new Error('Unknown bridge operation: '+op);
}
