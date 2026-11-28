import { Component, input } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApplicationLayoutComponent } from '../../layout/application-layout/application-layout.component';
import { AuthBoundaryService } from '../user/services/auth-boundary.service';
import { AiHelpComponent } from './ai-help.component';
import { ChatMessage, ChatPage } from './ai-help-api.service';

@Component({ selector: 'app-application-layout', template: '<ng-content />' })
class LayoutStub {
  readonly libraryControls = input(false);
}

const first: ChatPage = {
  pageId: 11,
  name: 'Chat 1',
  description: null,
  revision: 0,
  createdAt: '2026-11-28T00:00:00Z',
  updatedAt: '2026-11-28T00:00:00Z',
};
const second: ChatPage = { ...first, pageId: 22, name: 'Chat 2' };
const message = (pageId = 11, messageId = 1, content = 'Saved context'): ChatMessage => ({
  pageId,
  messageId,
  content,
  senderRole: 'USER',
  createdAt: first.createdAt,
  messageOrder: messageId,
});

describe('Persistent full-page AI Help', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AiHelpComponent>;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.overrideComponent(AiHelpComponent, {
      remove: { imports: [ApplicationLayoutComponent] },
      add: { imports: [LayoutStub] },
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
    fixture?.destroy();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  async function tick(): Promise<void> {
    for (let index = 0; index < 8; index++) await Promise.resolve();
    fixture.detectChanges();
  }
  function windowResponse(
    pageId: number,
    messages: ChatMessage[] = [],
    hasOlder = false,
    revision = 0,
  ) {
    return {
      pageId,
      revision,
      messages,
      number: 0,
      size: 50,
      totalElements: hasOlder ? messages.length + 50 : messages.length,
      totalPages: hasOlder ? 2 : 1,
      hasOlder,
    };
  }
  async function setup(
    messages: ChatMessage[] = [],
    lastSelectedPageId = 11,
    hasOlder = false,
  ): Promise<AiHelpComponent> {
    fixture = TestBed.createComponent(AiHelpComponent);
    fixture.detectChanges();
    http.expectOne('/api/ai-help/pages').flush({ pages: [first, second], lastSelectedPageId });
    await tick();
    http
      .expectOne(`/api/ai-help/pages/${lastSelectedPageId}/messages?page=0&size=50`)
      .flush(windowResponse(lastSelectedPageId, messages, hasOlder));
    await tick();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.componentInstance;
  }
  async function switchTo(page: ChatPage, messages: ChatMessage[] = []): Promise<void> {
    void fixture.componentInstance.selectPage(page);
    fixture.detectChanges();
    const selection = http.expectOne('/api/ai-help/selection');
    expect(selection.request.method).toBe('PUT');
    expect(selection.request.body).toEqual({ pageId: page.pageId });
    selection.flush(page);
    await tick();
    http
      .expectOne(`/api/ai-help/pages/${page.pageId}/messages?page=0&size=50`)
      .flush(windowResponse(page.pageId, messages));
    await tick();
  }

  it('restores only the persisted selected chat, exposes a close route, and loads no other conversation', async () => {
    const component = await setup([message(22, 3, 'Only second chat')], 22);
    expect(component.selectedId()).toBe(22);
    expect(fixture.nativeElement.querySelector('[role="log"]').textContent).toContain(
      'Only second chat',
    );
    http.expectNone((request) => request.url.includes('/pages/11/messages'));
    expect(
      fixture.nativeElement.querySelector('a[aria-label^="Close AI Help"]').getAttribute('href'),
    ).toBe('/');
    expect(fixture.nativeElement.querySelector('.chat-workspace')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.help-trigger')).toBeNull();
  });

  it('initializes a server-named default only if no chats exist and plus creates an independent page', async () => {
    fixture = TestBed.createComponent(AiHelpComponent);
    fixture.detectChanges();
    http.expectOne('/api/ai-help/pages').flush({ pages: [], lastSelectedPageId: null });
    await tick();
    const create = http.expectOne('/api/ai-help/pages');
    expect(create.request.method).toBe('POST');
    create.flush(first);
    await tick();
    http.expectOne('/api/ai-help/pages/11/messages?page=0&size=50').flush(windowResponse(11));
    await tick();
    void fixture.componentInstance.create();
    http.expectOne('/api/ai-help/pages').flush(second);
    await tick();
    http.expectOne('/api/ai-help/pages/22/messages?page=0&size=50').flush(windowResponse(22));
    await tick();
    expect(fixture.componentInstance.pages().map((page) => page.name)).toEqual([
      'Chat 1',
      'Chat 2',
    ]);
    expect(fixture.componentInstance.selectedId()).toBe(22);
  });

  it('sends the actual raw text with selected ID and cleanup revision, blocks blank/duplicate sends, and clears only a saved draft', async () => {
    const component = await setup();
    component.setDraft('   ');
    await component.send();
    http.expectNone((request) => request.url.endsWith('/chat'));
    component.setDraft('hi');
    void component.send();
    void component.send();
    const request = http.expectOne('/api/ai-help/chat?pageId=11&revision=0');
    expect(request.request.body).toBe('hi');
    expect(request.request.headers.get('Content-Type')).toBe('text/plain; charset=UTF-8');
    expect(request.request.headers.get('X-Message-Id')).toMatch(/^[0-9a-f-]{36}$/);
    expect(component.draft()).toBe('hi');
    expect(component.messages()).toEqual([]);
    request.flush({
      pageId: 11,
      revision: 0,
      message: message(11, 1, 'hi'),
      acknowledgment: 'Message saved. AI generation is not connected.',
      assistantStatus: 'NOT_CONFIGURED',
    });
    await tick();
    expect(component.draft()).toBe('');
    expect(component.messages().map((item) => item.content)).toEqual(['hi']);
    expect(component.notice()).toContain('AI generation is not connected');
    expect(fixture.nativeElement.querySelectorAll('.chat-message')).toHaveLength(1);
  });

  it('retains failed text and the retry key, and does not erase a newer draft when an earlier send succeeds', async () => {
    const component = await setup();
    component.setDraft('first');
    void component.send();
    const failed = http.expectOne('/api/ai-help/chat?pageId=11&revision=0');
    const requestId = failed.request.headers.get('X-Message-Id');
    failed.flush({ detail: 'Try again safely.' }, { status: 503, statusText: 'Unavailable' });
    await tick();
    expect(component.draft()).toBe('first');
    expect(component.error()).toBe('Try again safely.');
    void component.send();
    const retry = http.expectOne('/api/ai-help/chat?pageId=11&revision=0');
    expect(retry.request.headers.get('X-Message-Id')).toBe(requestId);
    component.setDraft('a newer draft');
    retry.flush({
      pageId: 11,
      revision: 0,
      message: message(11, 1, 'first'),
      acknowledgment: 'Saved.',
      assistantStatus: 'NOT_CONFIGURED',
    });
    await tick();
    expect(component.draft()).toBe('a newer draft');
    expect(component.messages()[0].content).toBe('first');
  });

  it('supports Enter to send and Shift+Enter to retain multiline composition', async () => {
    const component = await setup();
    component.setDraft('line one\nline two');
    const composition = new KeyboardEvent('keydown', {
      key: 'Enter',
      isComposing: true,
      cancelable: true,
    });
    component.composerKey(composition);
    expect(composition.defaultPrevented).toBe(false);
    http.expectNone((request) => request.url.endsWith('/chat'));
    const shift = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, cancelable: true });
    component.composerKey(shift);
    expect(shift.defaultPrevented).toBe(false);
    http.expectNone((request) => request.url.endsWith('/chat'));
    const enter = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true });
    component.composerKey(enter);
    expect(enter.defaultPrevented).toBe(true);
    http.expectOne('/api/ai-help/chat?pageId=11&revision=0').flush({
      pageId: 11,
      revision: 0,
      message: message(11, 1, 'line one\nline two'),
      acknowledgment: 'Saved.',
      assistantStatus: 'NOT_CONFIGURED',
    });
    await tick();
  });

  it('keeps a late send and an older-page response out of the newly selected chat', async () => {
    const component = await setup([message()], 11, true);
    component.setDraft('late first chat');
    void component.send();
    const pending = http.expectOne('/api/ai-help/chat?pageId=11&revision=0');
    void component.loadOlder();
    const older = http.expectOne('/api/ai-help/pages/11/messages?page=1&size=50');
    await switchTo(second, [message(22, 3, 'second only')]);
    pending.flush({
      pageId: 11,
      revision: 0,
      message: message(11, 2, 'late first chat'),
      acknowledgment: 'Saved.',
      assistantStatus: 'NOT_CONFIGURED',
    });
    older.flush(windowResponse(11, [message(11, 4, 'old first chat')]));
    await tick();
    expect(component.messages().map((item) => item.content)).toEqual(['second only']);
    expect(component.loadingOlder()).toBe(false);
    expect(component.selectedId()).toBe(22);
  });

  it('loads older records for only the selected page and renames using its stable ID', async () => {
    const component = await setup([message(11, 9, 'newer')], 11, true);
    void component.loadOlder();
    http
      .expectOne('/api/ai-help/pages/11/messages?page=1&size=50')
      .flush(windowResponse(11, [message(11, 2, 'older')]));
    await tick();
    expect(component.messages().map((item) => item.content)).toEqual(['older', 'newer']);
    component.beginRename();
    component.renameText = 'नया नाम / Report';
    void component.rename();
    const rename = http.expectOne('/api/ai-help/pages/11');
    expect(rename.request.method).toBe('PATCH');
    expect(rename.request.body).toEqual({ name: 'नया नाम / Report' });
    rename.flush({ ...first, name: 'नया नाम / Report' });
    await tick();
    expect(component.selectedId()).toBe(11);
    expect(component.selected()?.name).toBe('नया नाम / Report');
    expect(component.messages()).toHaveLength(2);
  });

  it('requires separate confirmation for Clean, offers download first, and retains the page/name after success', async () => {
    const component = await setup([message()]);
    component.ask('clean');
    fixture.detectChanges();
    http.expectNone((request) => request.method === 'DELETE');
    expect(fixture.nativeElement.querySelector('[role="dialog"]').textContent).toContain(
      'cannot subsequently be downloaded',
    );
    expect(fixture.nativeElement.querySelector('[role="dialog"]').textContent).toContain(
      'Download PDF first',
    );
    component.cancel();
    http.expectNone((request) => request.method === 'DELETE');
    expect(component.messages()).toHaveLength(1);
    component.ask('clean');
    void component.confirm();
    const clean = http.expectOne('/api/ai-help/pages/11/messages?revision=0');
    expect(clean.request.method).toBe('DELETE');
    expect(component.messages()).toHaveLength(1);
    clean.flush({ ...first, revision: 1 });
    await tick();
    expect(component.messages()).toEqual([]);
    expect(component.selected()?.name).toBe('Chat 1');
    expect(component.selected()?.revision).toBe(1);
    expect(component.pages()).toHaveLength(2);
    component.setDraft('after clean');
    void component.send();
    http.expectOne('/api/ai-help/chat?pageId=11&revision=1').flush({
      pageId: 11,
      revision: 1,
      message: message(11, 2, 'after clean'),
      acknowledgment: 'Saved.',
      assistantStatus: 'NOT_CONFIGURED',
    });
    await tick();
  });

  it('retains conversation on failed cleanup and only deletes one confirmed page after backend success', async () => {
    const component = await setup([message()]);
    component.ask('delete');
    void component.confirm();
    http
      .expectOne('/api/ai-help/pages/11?revision=0')
      .flush({ detail: 'Database unavailable.' }, { status: 503, statusText: 'Unavailable' });
    await tick();
    expect(component.pages()).toHaveLength(2);
    expect(component.messages()).toHaveLength(1);
    expect(component.confirmation()?.kind).toBe('delete');
    expect(component.error()).toBe('Database unavailable.');
    void component.confirm();
    http.expectOne('/api/ai-help/pages/11?revision=0').flush({ lastSelectedPageId: 22 });
    await tick();
    http.expectOne('/api/ai-help/pages/22/messages?page=0&size=50').flush(windowResponse(22));
    await tick();
    expect(component.pages().map((page) => page.pageId)).toEqual([22]);
    expect(component.selectedId()).toBe(22);
    http.expectNone((request) => request.url.endsWith('/selection'));
  });

  it('requests a full selected-chat PDF by ID and keeps its current server filename when switching pages during export', async () => {
    const component = await setup([message()]);
    vi.stubGlobal(
      'URL',
      class extends URL {
        static override createObjectURL = vi.fn(() => 'blob:chat');
        static override revokeObjectURL = vi.fn();
      },
    );
    let saved = '';
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      saved = this.download;
    });
    void component.download();
    const pdf = http.expectOne('/api/ai-help/pages/11/pdf');
    expect(pdf.request.responseType).toBe('blob');
    expect(pdf.request.params.keys()).toEqual([]);
    await switchTo(second);
    pdf.flush(new Blob(['%PDF-full-conversation'], { type: 'application/pdf' }), {
      headers: {
        'Content-Disposition': "attachment; filename*=UTF-8''%E0%A4%A8%E0%A4%BE%E0%A4%AE.pdf",
      },
    });
    await tick();
    expect(saved).toBe('नाम.pdf');
    expect(component.selectedId()).toBe(22);
    expect(component.notice()).not.toContain('Download ready');
  });

  it('clears account state and cancels in-flight requests on logout without cleaning/deleting stored chats', async () => {
    const component = await setup([message()]);
    component.setDraft('private draft');
    void component.send();
    const request = http.expectOne('/api/ai-help/chat?pageId=11&revision=0');
    TestBed.inject(AuthBoundaryService).reset();
    await tick();
    expect(request.cancelled).toBe(true);
    expect(component.pages()).toEqual([]);
    expect(component.messages()).toEqual([]);
    expect(component.draft()).toBe('');
    http.expectNone((item) => item.method === 'DELETE');
  });

  it('opens a new server-named default after deleting the final page without reusing the deleted ID', async () => {
    fixture = TestBed.createComponent(AiHelpComponent);
    fixture.detectChanges();
    http.expectOne('/api/ai-help/pages').flush({ pages: [first], lastSelectedPageId: 11 });
    await tick();
    http
      .expectOne('/api/ai-help/pages/11/messages?page=0&size=50')
      .flush(windowResponse(11, [message()]));
    await tick();
    const component = fixture.componentInstance;
    component.ask('delete');
    void component.confirm();
    http.expectOne('/api/ai-help/pages/11?revision=0').flush({ lastSelectedPageId: null });
    await tick();
    const newPage = { ...first, pageId: 33, name: 'Chat 3' };
    http.expectOne('/api/ai-help/pages').flush(newPage);
    await tick();
    http.expectOne('/api/ai-help/pages/33/messages?page=0&size=50').flush(windowResponse(33));
    await tick();
    expect(component.selected()?.pageId).toBe(33);
    expect(component.selected()?.name).toBe('Chat 3');
    expect(component.messages()).toEqual([]);
    expect(component.pages()).toEqual([newPage]);
  });

  it('shows empty/export errors without downloading fabricated PDF data or changing the conversation', async () => {
    const component = await setup([message()]);
    vi.stubGlobal(
      'URL',
      class extends URL {
        static override createObjectURL = vi.fn(() => 'blob:chat');
        static override revokeObjectURL = vi.fn();
      },
    );
    void component.download();
    http
      .expectOne('/api/ai-help/pages/11/pdf')
      .flush(
        new Blob([JSON.stringify({ detail: 'This chat has no messages to export.' })], {
          type: 'application/json',
        }),
        { status: 409, statusText: 'Conflict' },
      );
    await tick();
    await tick();
    expect(component.error()).toBe('This chat has no messages to export.');
    expect(URL.createObjectURL).not.toHaveBeenCalled();
    expect(component.messages()).toHaveLength(1);
  });

  it('restores saved messages again on a fresh open after logout without clean/delete side effects', async () => {
    await setup([message(11, 8, 'Previously saved private message')]);
    TestBed.inject(AuthBoundaryService).reset();
    await tick();
    fixture.destroy();
    await setup([message(11, 8, 'Previously saved private message')]);
    expect(fixture.componentInstance.messages()[0].content).toBe(
      'Previously saved private message',
    );
    http.expectNone((request) => request.method === 'DELETE');
  });
});
