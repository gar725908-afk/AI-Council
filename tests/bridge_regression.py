"""Regressions in real QtWebEngine pages; fixtures simulate DOM and streaming."""
from __future__ import annotations
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import main
from PySide6.QtCore import QEventLoop, QTimer, QUrl, Qt
from PySide6.QtWidgets import QApplication, QWidget
from PySide6.QtWebEngineCore import QWebEnginePage
from ui.service_browser import ServiceBrowser, SERVICES
from core.council import CouncilSession, PARTICIPANTS
from core.request_policy import clean_response, response_complete
from core.web_council import CouncilWebOrchestrator

APP = QApplication.instance() or QApplication([])
APP.setOrganizationName("AI Council Tests")
APP.setApplicationName("AI Council Tests")


def wait_until(predicate, seconds=15):
    until = time.monotonic() + seconds
    while not predicate() and time.monotonic() < until:
        loop = QEventLoop()
        QTimer.singleShot(25, loop.quit)
        loop.exec()
    return predicate()


class PolicyTests(unittest.TestCase):
    def test_localized_tool_status_and_skip_control(self):
        self.assertEqual(clean_response('Работа для 1s\nГотово.'), 'Готово.')
        self.assertEqual(clean_response('Reading sources…\nSkip'), '')
        self.assertEqual(clean_response('Skip'), 'Skip')
        self.assertEqual(clean_response('Reading sources is useful.'), 'Reading sources is useful.')

    def test_short_and_content_with_working(self):
        for word in ["ГОТОВО", "OK", "Yes", "No", "Да", "Нет", "2"]:
            self.assertEqual(clean_response(word), word)
        self.assertEqual(clean_response("Working for 1s\nГотово."), "Готово.")
        self.assertEqual(clean_response("This working example is useful."), "This working example is useful.")

    def test_pause_is_not_completion(self):
        self.assertFalse(response_complete("Готово.", 10, 15, True, True))
        self.assertTrue(response_complete("ГОТОВО", 3, 1.5, False, True))
        self.assertFalse(response_complete("Часть ответа", 3, 1, False, False))

    def test_roles_dedup_and_identical_new_turn(self):
        session = CouncilSession()
        session.add("DeepSeek", "ГОТОВО", sender_type="user", request_id="a")
        session.add("DeepSeek", "ГОТОВО", request_id="a")
        session.add("DeepSeek", "ГОТОВО", request_id="b")
        self.assertEqual(len(session.messages), 2)
        self.assertTrue(all(m.role == "assistant" and m.sender_type == "ai" for m in session.messages))
        self.assertEqual(len(CouncilSession.from_dict(session.to_dict()).messages), 2)

    def test_history_bound_and_roundtrip(self):
        session = CouncilSession()
        for i in range(65):
            session.add("Ты", str(i), sender_type="user")
        self.assertEqual(len(session.messages), 50)
        self.assertEqual(CouncilSession.from_dict(session.to_dict()).to_dict(), session.to_dict())

    def test_context_prompt(self):
        packet = CouncilWebOrchestrator._build_compact_prompt("Продолжи", '{"author":"Claude","text":"Ответ."}')
        self.assertIn("Claude", packet)
        self.assertTrue(packet.endswith("Продолжи"))


def fixture(name, long=False, shadow=False, hang=False):
    answer = "Первая строка.\n\nВторая строка завершена." if long else "ГОТОВО"
    classes = {"Claude":"standard-markdown", "DeepSeek":"ds-markdown",
               "Kimi":"markdown-container", "Qwen":"response-message-content phase-answer"}
    response_class = classes.get(name, "markdown prose")
    editable = name in {"Claude", "ChatGPT", "Kimi", "Алиса", "Grok"}
    editor = '<div id="prompt-textarea" class="chat-input-editor ql-editor" role="textbox" contenteditable="true"></div>' if editable else '<textarea class="message-input-textarea" placeholder="Message DeepSeek"></textarea>'
    css='<style>div,textarea{min-height:28px;min-width:300px}button{min-height:25px}main div{display:block}</style>'
    shell = css + editor + '<button aria-label="Send message" data-testid="chat-submit">Send</button><button aria-label="Stop generating" style="display:none">Stop</button><main></main>'
    setup = 'const host=document.createElement("x-chat");document.body.appendChild(host);const root=host.attachShadow({mode:"open"});' if shadow else 'const root=document.body;'
    return '<!doctype html><body><script>' + setup + "root.innerHTML=" + json.dumps(shell) + """
    const input=root.querySelector('[contenteditable],textarea');
    const send=root.querySelector('[aria-label="Send message"]'), stop=root.querySelector('[aria-label="Stop generating"]'), chat=root.querySelector('main');
    let count=0;
    send.addEventListener('click',()=>{
      count++;document.body.dataset.submits=String(count);
      const user=document.createElement('div');user.dataset.messageAuthorRole='user';user.dataset.messageId='u'+count;
      user.textContent=input.value??input.innerText;chat.appendChild(user);
      if ('value' in input) input.value='';else input.textContent='';
      stop.style.display='inline-block';
      const answer=document.createElement('div');answer.dataset.messageAuthorRole='assistant';answer.dataset.messageId='a'+count;
      answer.className=""" + json.dumps(response_class) + """;
      chat.appendChild(answer);
      setTimeout(()=>answer.innerText='Working for 1s',150);
      setTimeout(()=>answer.innerText=""" + json.dumps("Первая строка." if long else "ГОТОВО") + """,450);
      """ + ("setTimeout(()=>answer.innerText=" + json.dumps(answer) + ",2300);setTimeout(()=>stop.style.display='none',2500);" if not hang else "") + """
    });
    </script></body>"""


class QtBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="council-test-")
        cls.browser = ServiceBrowser(auto_open=False, profile_root=Path(cls.temp.name))
        cls.host = QWidget()
        cls.host.setAttribute(Qt.WA_DontShowOnScreen, True)
        cls.host.resize(1280,800)
        cls.host.show()
        cls.browser.enable_background_rendering(cls.host)

    def load(self, name, **options):
        page = self.browser._page_for(self.browser._service(name))
        done = []
        handler=done.append
        page.loadFinished.connect(handler)
        page.setHtml(fixture(name, **options), QUrl("https://fixture.local/" + name))
        self.assertTrue(wait_until(lambda: bool(done), 10))
        page.loadFinished.disconnect(handler)
        self.browser._set_availability(name, "online")

    def run_request(self, name, timeout=15_000):
        received=[]
        self.browser.start_service_request(name, "Ответь одним словом: ГОТОВО", lambda ok,text:received.append((ok,text)),timeout_ms=timeout)
        return received

    def test_all_seven_and_repeat_same_answer(self):
        for service in SERVICES:
            self.load(service.name)
        results={name:self.run_request(name) for name in PARTICIPANTS}
        self.assertTrue(wait_until(lambda: all(results.values()), 18))
        self.assertTrue(all(items == [(True,"ГОТОВО")] for items in results.values()), results)
        self.assertEqual(set(self.browser.service_names()), set(PARTICIPANTS))
        for snapshot in self.browser._completed_requests.values():
            if snapshot["success"]:
                self.assertEqual(snapshot["publish_count"], 1)
                self.assertEqual(snapshot["last_error"], "")
                self.assertTrue(all(not value for key,value in snapshot.items() if key.endswith("_pending")))
        repeated=self.run_request("DeepSeek")
        self.assertTrue(wait_until(lambda: bool(repeated), 15))
        self.assertEqual(repeated, [(True,"ГОТОВО")])

    def test_long_stream_keeps_newlines_and_waits_for_stop(self):
        self.load("Claude",long=True)
        received=self.run_request("Claude")
        self.assertFalse(wait_until(lambda: bool(received), 1.8))
        self.assertTrue(wait_until(lambda: bool(received), 15))
        self.assertEqual(received, [(True,"Первая строка.\n\nВторая строка завершена.")])

    def test_shadow_dom(self):
        self.load("Kimi",shadow=True)
        received=self.run_request("Kimi")
        self.assertTrue(wait_until(lambda: bool(received), 15))
        self.assertEqual(received, [(True,"ГОТОВО")])

    def test_partial_at_timeout_is_marked(self):
        self.load("Grok",hang=True)
        received=self.run_request("Grok",timeout=5_000)
        self.assertTrue(wait_until(lambda: bool(received), 8))
        self.assertEqual(received, [(True,"ГОТОВО\n[обрезано]")])

    def test_duplicate_scheduled_fill_and_send_are_ignored(self):
        self.load("Qwen")
        received=self.run_request("Qwen")
        self.assertTrue(wait_until(lambda: any(s["submitted"] for s in self.browser._active_requests.values()),5))
        state=next(s for s in self.browser._active_requests.values() if s["service"].name=="Qwen")
        for _ in range(5):
            self.browser._fill_request(state)
            self.browser._wait_for_send(state)
        self.assertTrue(wait_until(lambda:bool(received),15))
        js=[]
        self.browser._run_js_json(state["page"], "({count:Number(document.body.dataset.submits)})",js.append)
        self.assertTrue(wait_until(lambda:bool(js),5))
        self.assertEqual(js[0]["count"],1)
        self.assertEqual(len(received),1)

    def test_kimi_excludes_user_actions_and_reasoning(self):
        self.load("Kimi")
        page=self.browser._page_for(self.browser._service("Kimi"))
        markup="""<main><div class="chat-content-item chat-content-item-user" data-conversation-turn-id="user-77"><div class="segment segment-user"><span class="user-content__text">Текущий вопрос</span><button>Edit</button><button>Copy</button></div></div><div class="chat-content-item chat-content-item-assistant" data-archer-id="answer-77"><div class="toolcall-content"><div class="markdown-container toolcall-content-text">Thinking complete. Internal planning.</div></div><div class="markdown-container">ГОТОВО</div></div></main>"""
        rows=[]
        self.browser._run_js_json(page, "(() => {document.querySelector('main').outerHTML="+json.dumps(markup)+";return {ok:true};})()", rows.append)
        self.assertTrue(wait_until(lambda: bool(rows),5))
        inspection=[]
        self.browser._run_js_json(page,self.browser._inspect_js("Kimi"),inspection.append)
        self.assertTrue(wait_until(lambda: bool(inspection),5))
        self.assertEqual(inspection[0]["user_texts"], ["Текущий вопрос"])
        self.assertEqual(inspection[0]["responses"], ["ГОТОВО"])
        self.assertEqual(inspection[0]["response_ids"], ["data-archer-id:answer-77"])

    def test_chatgpt_does_not_copy_paired_user_turn(self):
        self.load("ChatGPT")
        page=self.browser._page_for(self.browser._service("ChatGPT"))
        markup="""<main><section data-turn-key="pair-55"><div data-chatgpt-search-unit-key="pair-55:user" data-user-message-bubble="true">User question</div><div class="agent-turn" data-chatgpt-search-unit-key="pair-55:assistant"><div class="markdown prose">ГОТОВО</div></div></section></main>"""
        rows=[]
        self.browser._run_js_json(page,"(() => {document.querySelector('main').outerHTML="+json.dumps(markup)+";return {ok:true};})()",rows.append)
        self.assertTrue(wait_until(lambda:bool(rows),5))
        answers=[]
        self.browser._run_js_json(page,self.browser._extract_js("ChatGPT"),answers.append)
        self.assertTrue(wait_until(lambda:bool(answers),5))
        self.assertEqual(answers[0]["texts"], ["ГОТОВО"])

    def test_callback_exception_is_not_retried(self):
        calls=[]
        def callback(payload):
            calls.append(payload)
            raise ValueError("deliberate consumer failure")
        page=self.browser._page_for(self.browser._service("DeepSeek"))
        self.browser._run_js_json(page, "({ok:true})",callback)
        self.assertTrue(wait_until(lambda:bool(calls),5))
        self.assertEqual(len(calls),1)

    def test_queued_request_reads_context_after_first_answer(self):
        self.load('DeepSeek')
        first=[]
        second=[]
        context=[]
        self.browser.start_service_request('DeepSeek','Первый вопрос',
            lambda ok,text:(first.append((ok,text)),context.append(text)))
        self.browser.start_service_request('DeepSeek','',lambda ok,text:second.append((ok,text)),
            queued_prompt_factory=lambda:'Продолжи; предыдущий ответ: '+context[-1])
        self.assertEqual(len(self.browser._queues['DeepSeek']),1)
        self.assertTrue(wait_until(lambda:bool(first) and bool(second),25))
        self.assertEqual(first,[(True,'ГОТОВО')])
        self.assertEqual(second,[(True,'ГОТОВО')])
        page=self.browser._page_for(self.browser._service('DeepSeek'))
        rows=[]
        self.browser._run_js_json(page,"({texts:[...document.querySelectorAll('[data-message-author-role=user]')].map(e=>e.innerText)})",rows.append)
        self.assertTrue(wait_until(lambda:bool(rows),5))
        self.assertEqual(rows[0]['texts'],['Первый вопрос','Продолжи; предыдущий ответ: ГОТОВО'])
        self.assertEqual(self.browser._queues['DeepSeek'],[])

    def test_login_button_and_captcha_discussion_do_not_block_chat(self):
        self.load('Qwen')
        page = self.browser._page_for(self.browser._service('Qwen'))
        rows = []
        self.browser._run_js_json(page, "(() => {document.body.insertAdjacentHTML('beforeend', '<button>Sign in</button><p>We discussed captcha yesterday.</p>');return {ok:true};})()", rows.append)
        self.assertTrue(wait_until(lambda: bool(rows), 5))
        info = []
        self.browser._run_js_json(page, self.browser._inspect_js('Qwen'), info.append)
        self.assertTrue(wait_until(lambda: bool(info), 5))
        self.assertTrue(info[0]['input_found'])
        self.assertFalse(info[0]['login_required'])
        self.assertEqual(info[0]['blocked_reason'], '')
        changed = []
        self.browser._run_js_json(page, "(() => {document.body.innerHTML='<div role=dialog>Sign in to continue</div>';return {ok:true};})()", changed.append)
        self.assertTrue(wait_until(lambda: bool(changed), 5))
        blocked = []
        self.browser._run_js_json(page, self.browser._inspect_js('Qwen'), blocked.append)
        self.assertTrue(wait_until(lambda: bool(blocked), 5))
        self.assertTrue(blocked[0]['login_required'])

    def test_composer_clear_confirms_send_before_slow_opaque_turn(self):
        page = self.browser._page_for(self.browser._service('DeepSeek'))
        html = '''<!doctype html><style>textarea,button,div{min-height:30px;min-width:300px}</style>
        <textarea placeholder="Message DeepSeek"></textarea><button aria-label="Send message">Send</button><main></main>
        <script>document.querySelector('button').onclick=()=>{
          document.querySelector('textarea').value='';
          const answer=document.createElement('div');answer.dataset.messageAuthorRole='assistant';
          answer.className='ds-markdown';document.querySelector('main').append(answer);
          setTimeout(()=>answer.innerText='ГОТОВО',14000);
        };</script>'''
        loaded = []
        handler = loaded.append
        page.loadFinished.connect(handler)
        page.setHtml(html, QUrl('https://fixture.local/opaque'))
        self.assertTrue(wait_until(lambda: bool(loaded), 10))
        page.loadFinished.disconnect(handler)
        self.browser._set_availability('DeepSeek', 'online')
        received = self.run_request('DeepSeek', timeout=25_000)
        self.assertTrue(wait_until(lambda: any(s['service'].name == 'DeepSeek' and s['submitted'] for s in self.browser._active_requests.values()), 3))
        self.assertEqual(received, [])
        self.assertTrue(wait_until(lambda: bool(received), 20))
        self.assertEqual(received, [(True, 'ГОТОВО')])

    def test_tool_status_is_not_an_answer(self):
        self.load('Qwen')
        page = self.browser._page_for(self.browser._service('Qwen'))
        changed = []
        markup = '<div data-message-author-role="assistant" class="response-message-content phase-answer">Reading sources…\nSkip</div>'
        self.browser._run_js_json(page, "(() => {document.querySelector('main').innerHTML=" + json.dumps(markup) + ";return {ok:true};})()", changed.append)
        self.assertTrue(wait_until(lambda: bool(changed), 5))
        answers = []
        self.browser._run_js_json(page, self.browser._extract_js('Qwen'), answers.append)
        self.assertTrue(wait_until(lambda: bool(answers), 5))
        self.assertEqual(answers[0]['texts'], [])

    def test_idle_discard_resumes_same_profile_and_keeps_draft(self):
        service = self.browser._service('DeepSeek')
        page = self.browser._page_for(service)
        file = Path(self.temp.name) / 'resume.html'
        file.write_text(fixture('DeepSeek'), encoding='utf-8')
        loaded = []
        handler = loaded.append
        page.loadFinished.connect(handler)
        page.setUrl(QUrl.fromLocalFile(str(file)))
        self.assertTrue(wait_until(lambda: bool(loaded), 10))
        page.loadFinished.disconnect(handler)
        self.browser._set_availability('DeepSeek', 'online')
        profile = page.profile()
        self.browser._last_page_used['DeepSeek'] = 0
        self.browser._release_idle_pages()
        self.assertTrue(wait_until(lambda: page.lifecycleState() == QWebEnginePage.LifecycleState.Discarded, 5))
        resumed = []
        handler = resumed.append
        page.loadFinished.connect(handler)
        self.assertIs(self.browser._page_for(service), page)
        self.assertTrue(wait_until(lambda: bool(resumed), 10))
        page.loadFinished.disconnect(handler)
        self.assertIs(page.profile(), profile)
        received = self.run_request('DeepSeek')
        self.assertTrue(wait_until(lambda: bool(received), 15))
        self.assertEqual(received, [(True, 'ГОТОВО')])
        changed = []
        self.browser._run_js_json(page, "(() => {document.querySelector('textarea').value='Неотправленный черновик';return {ok:true};})()", changed.append)
        self.assertTrue(wait_until(lambda: bool(changed), 5))
        self.browser._last_page_used['DeepSeek'] = 0
        self.browser._release_idle_pages()
        self.assertTrue(wait_until(lambda: 'DeepSeek' not in self.browser._discard_pending, 5))
        self.assertEqual(page.lifecycleState(), QWebEnginePage.LifecycleState.Active)
        inspected = []
        self.browser._run_js_json(page, self.browser._inspect_js('DeepSeek'), inspected.append)
        self.assertTrue(wait_until(lambda: bool(inspected), 5))
        self.assertEqual(inspected[0]['input_text'], 'Неотправленный черновик')

    def test_request_wakes_discarded_page_and_waits_for_reload(self):
        page = self.browser._page_for(self.browser._service('DeepSeek'))
        file = Path(self.temp.name) / 'request-resume.html'
        file.write_text(fixture('DeepSeek'), encoding='utf-8')
        loaded = []
        handler = loaded.append
        page.loadFinished.connect(handler)
        page.setUrl(QUrl.fromLocalFile(str(file)))
        self.assertTrue(wait_until(lambda: bool(loaded), 10))
        page.loadFinished.disconnect(handler)
        self.browser._set_availability('DeepSeek', 'online')
        self.browser._last_page_used['DeepSeek'] = 0
        self.browser._release_idle_pages()
        self.assertTrue(wait_until(lambda: page.lifecycleState() == QWebEnginePage.LifecycleState.Discarded, 5))
        received = self.run_request('DeepSeek')
        self.assertTrue(wait_until(lambda: bool(received), 15))
        self.assertEqual(received, [(True, 'ГОТОВО')])
        self.assertNotIn('DeepSeek', self.browser._resuming_pages)

    def test_age_discussion_in_active_chat_is_not_a_dialog(self):
        self.load('Qwen')
        page = self.browser._page_for(self.browser._service('Qwen'))
        changed = []
        self.browser._run_js_json(page, "(() => {document.body.insertAdjacentHTML('beforeend','<p>We discussed confirm your age and what year were you born.</p>');return {ok:true};})()", changed.append)
        self.assertTrue(wait_until(lambda: bool(changed), 5))
        info = []
        self.browser._run_js_json(page, self.browser._inspect_js('Qwen'), info.append)
        self.assertTrue(wait_until(lambda: bool(info), 5))
        self.assertEqual(info[0]['blocked_reason'], '')

    def test_age_is_detected_inside_its_own_dialog_only(self):
        self.load('Qwen')
        page = self.browser._page_for(self.browser._service('Qwen'))
        changed = []
        script = "(() => {document.body.insertAdjacentHTML('beforeend','<p>We discussed confirm your age.</p><div role=dialog>How satisfied are you with Qwen?</div>');return {ok:true};})()"
        self.browser._run_js_json(page, script, changed.append)
        self.assertTrue(wait_until(lambda: bool(changed), 5))
        info = []
        self.browser._run_js_json(page, self.browser._inspect_js('Qwen'), info.append)
        self.assertTrue(wait_until(lambda: bool(info), 5))
        self.assertEqual(info[0]['blocked_reason'], '')
        replaced = []
        self.browser._run_js_json(page, "(() => {document.querySelector('[role=dialog]').innerText='Confirm your age to continue. What year were you born?';return {ok:true};})()", replaced.append)
        self.assertTrue(wait_until(lambda: bool(replaced), 5))
        blocked = []
        self.browser._run_js_json(page, self.browser._inspect_js('Qwen'), blocked.append)
        self.assertTrue(wait_until(lambda: bool(blocked), 5))
        self.assertEqual(blocked[0]['blocked_reason'], 'age_confirmation')

    def test_grok_quota_banner_excludes_messages_and_drafts(self):
        self.load('Grok')
        page = self.browser._page_for(self.browser._service('Grok'))
        changed = []
        script = """(() => {
          const text='4 часа 3 минуты до сброса лимита';
          document.querySelector('[contenteditable]').innerText=text;
          document.querySelector('main').innerHTML='<div data-message-author-role="assistant">'+text+'</div>';
          document.body.insertAdjacentHTML('beforeend','<div id="quota">'+text+'</div>');
          return {ok:true};
        })()"""
        self.browser._run_js_json(page, script, changed.append)
        self.assertTrue(wait_until(lambda: bool(changed), 5))
        info = []
        self.browser._run_js_json(page, self.browser._inspect_js('Grok'), info.append)
        self.assertTrue(wait_until(lambda: bool(info), 5))
        self.assertEqual(info[0]['service_error'], 'Лимит Grok исчерпан: 4 часа 3 минуты до сброса лимита')
        removed = []
        self.browser._run_js_json(page, "(() => {document.querySelector('#quota').remove();return {ok:true};})()", removed.append)
        self.assertTrue(wait_until(lambda: bool(removed), 5))
        clean = []
        self.browser._run_js_json(page, self.browser._inspect_js('Grok'), clean.append)
        self.assertTrue(wait_until(lambda: bool(clean), 5))
        self.assertEqual(clean[0]['service_error'], '')
        armed = []
        script = """(() => {
          const input=document.querySelector('[contenteditable]');
          input.innerText='';
          input.addEventListener('input',()=>document.body.insertAdjacentHTML('beforeend',
            '<div>4 часа 3 минуты до сброса лимита</div>'),{once:true});
          return {ok:true};
        })()"""
        self.browser._run_js_json(page, script, armed.append)
        self.assertTrue(wait_until(lambda: bool(armed), 5))
        received = self.run_request('Grok', timeout=5000)
        self.assertTrue(wait_until(lambda: bool(received), 4))
        self.assertEqual(received, [(False, 'Лимит Grok исчерпан: 4 часа 3 минуты до сброса лимита')])

    def test_disabled_send_does_not_fall_back_to_enter(self):
        self.load('Grok')
        page = self.browser._page_for(self.browser._service('Grok'))
        changed = []
        script = """(() => {
          const input=document.querySelector('[contenteditable]');
          input.innerText='ГОТОВО';
          input.addEventListener('keydown',()=>document.body.dataset.enter='yes');
          document.querySelector('[data-testid="chat-submit"]').disabled=true;
          return {ok:true};
        })()"""
        self.browser._run_js_json(page, script, changed.append)
        self.assertTrue(wait_until(lambda: bool(changed), 5))
        sent = []
        self.browser._run_js_json(page, self.browser._send_js('Grok', True, 'disabled-test', 'ГОТОВО'), sent.append)
        self.assertTrue(wait_until(lambda: bool(sent), 5))
        self.assertFalse(sent[0]['ok'])
        self.assertEqual(sent[0]['reason'], 'send_not_ready')
        counts = []
        self.browser._run_js_json(page, "({enter:document.body.dataset.enter||'',submits:Number(document.body.dataset.submits||0)})", counts.append)
        self.assertTrue(wait_until(lambda: bool(counts), 5))
        self.assertEqual(counts[0], {'enter':'', 'submits':0})

    @classmethod
    def tearDownClass(cls):
        cls.browser.close_profiles()
        cls.host.close()
        cls.browser.deleteLater()
        APP.processEvents()


if __name__ == "__main__":
    unittest.main(verbosity=2)
