/**
 * 聊天核心逻辑：消息发送 / SSE 流式接收 / 渲染
 */
const decoder = new TextDecoder();
let activeStream = null;

document.addEventListener('DOMContentLoaded', () => {
    if (!localStorage.getItem('token')) {
        window.location.href = 'login.html';
        return;
    }

    const input = document.getElementById('messageInput');
    const sendBtn = document.getElementById('sendBtn');
    const stopBtn = document.getElementById('stopBtn');
    const uploadBtn = document.getElementById('uploadBtn');
    const knowledgeInput = document.getElementById('knowledgeInput');
    const stopHardwareBtn = document.getElementById('stopHardwareBtn');

    sendBtn.addEventListener('click', () => sendMessage());
    stopBtn.addEventListener('click', () => stopCurrentStream());
    input.addEventListener('keydown', e => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });
    input.addEventListener('input', () => {
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 180) + 'px';
    });

    document.querySelectorAll('.quick-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            input.value = btn.dataset.msg;
            sendMessage();
        });
    });

    uploadBtn.addEventListener('click', () => knowledgeInput.click());
    knowledgeInput.addEventListener('change', () => {
        if (knowledgeInput.files.length > 0) {
            handleKnowledgeUpload(knowledgeInput.files[0]);
            knowledgeInput.value = '';
        }
    });

    stopHardwareBtn.addEventListener('click', stopHardwareFromPanel);
    refreshHardwareStatus();
    window.setInterval(refreshHardwareStatus, 3000);

    setStreamingState(false);
});

window.stopCurrentStream = stopCurrentStream;

async function sendMessage() {
    const input = document.getElementById('messageInput');
    const message = input.value.trim();
    if (!message || activeStream) return;

    input.value = '';
    input.style.height = 'auto';

    if (!currentChatId) {
        currentChatId = createChatId();
    }

    removeWelcomeScreen();
    appendMessage('user', message);

    const aiBubble = appendMessage('assistant', '', {
        done: false,
        status: '准备中'
    });
    scrollToBottom();

    const streamState = {
        streamId: createStreamId(),
        chatId: currentChatId,
        bubble: aiBubble,
        rawText: '',
        stopped: false,
        stopping: false,
        abortController: new AbortController()
    };
    activeStream = streamState;
    setStreamingState(true);
    renderAssistantBubble(aiBubble, '', { done: false, status: '思考中' });

    try {
        const reader = await ssePost('chat', {
            message,
            chatId: currentChatId,
            streamId: streamState.streamId
        }, {
            signal: streamState.abortController.signal
        });

        let lineBuffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            lineBuffer += decoder.decode(value, { stream: true });
            lineBuffer = consumeSseBuffer(lineBuffer, streamState);
        }

        lineBuffer += decoder.decode();
        if (lineBuffer.trim()) {
            consumeSseBuffer(lineBuffer + '\n', streamState);
        }

        renderAssistantBubble(streamState.bubble, streamState.rawText, {
            done: true,
            stopped: streamState.stopped,
            status: streamState.stopped ? '已停止' : '已完成'
        });
        loadConversations();
    } catch (e) {
        if (streamState.stopped || e.name === 'AbortError') {
            renderAssistantBubble(streamState.bubble, streamState.rawText, {
                done: true,
                stopped: true,
                status: '已停止'
            });
        } else {
            renderErrorBubble(streamState.bubble, e.message);
        }
    } finally {
        if (activeStream && activeStream.streamId === streamState.streamId) {
            activeStream = null;
        }
        setStreamingState(false);
        input.focus();
    }
}

async function stopCurrentStream(options = {}) {
    if (!activeStream || activeStream.stopping) {
        return false;
    }

    const streamState = activeStream;
    streamState.stopping = true;
    streamState.stopped = true;

    if (!options.silent) {
        renderAssistantBubble(streamState.bubble, streamState.rawText, {
            done: false,
            stopped: true,
            status: '正在停止'
        });
    }

    try {
        await stopChatStream(streamState.streamId);
    } catch (e) {
        console.warn('停止流式回复失败，已回退到本地中断', e);
    } finally {
        streamState.abortController.abort();
    }

    return true;
}

function consumeSseBuffer(buffer, streamState) {
    let nextBuffer = buffer;
    while (nextBuffer.includes('\n')) {
        const idx = nextBuffer.indexOf('\n');
        const line = nextBuffer.substring(0, idx).trim();
        nextBuffer = nextBuffer.substring(idx + 1);

        if (!line.startsWith('data:')) {
            continue;
        }

        streamState.rawText += line.substring(5);
        renderAssistantBubble(streamState.bubble, streamState.rawText, {
            done: false,
            stopped: streamState.stopped,
            status: resolveAssistantStatus(streamState.rawText, false, streamState.stopped)
        });
        scrollToBottom();
    }
    return nextBuffer;
}

function appendMessage(role, content, options = {}) {
    const container = document.getElementById('chatMessages');
    const div = document.createElement('div');
    const isAssistant = role === 'assistant';

    div.className = `message ${role}`;
    div.innerHTML = `
        <div class="avatar">${isAssistant ? 'AI' : getUsername().charAt(0).toUpperCase()}</div>
        <div class="bubble ${isAssistant ? 'assistant-bubble' : 'user-bubble'}"></div>
    `;

    container.appendChild(div);
    const bubble = div.querySelector('.bubble');
    bubble.dataset.time = options.time || formatTime(new Date());

    if (isAssistant) {
        renderAssistantBubble(bubble, content, {
            done: options.done !== false,
            stopped: options.stopped === true,
            status: options.status
        });
    } else {
        bubble.innerHTML = `
            <div class="message-meta">
                <span class="message-role">你</span>
                <span class="message-time">${bubble.dataset.time}</span>
            </div>
            <div class="message-text">${formatRichText(content)}</div>
        `;
    }

    return bubble;
}

async function handleKnowledgeUpload(file) {
    removeWelcomeScreen();

    appendMessage('user', `上传知识文档：${file.name}`);
    const aiBubble = appendMessage('assistant', '', {
        done: false,
        status: '分析中'
    });
    scrollToBottom();

    try {
        const res = await uploadKnowledge(file);
        if (res.code !== 0) {
            renderErrorBubble(aiBubble, res.message);
            return;
        }

        const taskId = res.data.taskId;
        renderAssistantBubble(aiBubble, `已接收知识文档，任务 ID：\`${taskId}\`\n\n正在执行 Tika 解析、切片、关键词增强和向量化。`, {
            done: false,
            status: '排队中'
        });

        let result = null;
        for (let i = 0; i < 60; i++) {
            await sleep(2000);
            const poll = await getKnowledgeResult(taskId);
            if (poll.code === 0 && poll.data && poll.data.status === 'completed') {
                result = poll.data;
                break;
            }
            if (poll.code === 0 && poll.data && poll.data.status === 'error') {
                renderErrorBubble(aiBubble, poll.data.message || '知识文档入库失败');
                return;
            }
        }

        if (!result) {
            renderErrorBubble(aiBubble, '知识入库超时，请稍后使用任务 ID 查询结果。');
            return;
        }

        const report = buildKnowledgeReport(result);
        renderAssistantBubble(aiBubble, report, {
            done: true,
            status: '已完成'
        });
        scrollToBottom();
    } catch (e) {
        renderErrorBubble(aiBubble, e.message);
    }
}

function buildKnowledgeReport(result) {
    return [
        '# 知识文档入库完成',
        `**来源**：${result.source || '未命名文档'}`,
        `**切片数量**：${result.chunkCount || 0}`,
        result.message || '文档已写入 PGVector，可开始提问。'
    ].join('\n\n');
}

function renderAssistantBubble(bubble, rawText, options = {}) {
    const done = options.done === true;
    const stopped = options.stopped === true;
    const parts = splitAssistantContent(rawText);
    const status = options.status || resolveAssistantStatus(rawText, done, stopped);
    const statusClass = getStatusClass(status, done, stopped, parts.answer);

    let thinkingHtml = '';
    if (parts.thinking) {
        const stepsHtml = renderThinkingSteps(parts.thinking);
        thinkingHtml = done
            ? `
                <details class="assistant-section think-panel">
                    <summary>思考过程</summary>
                    <div class="think-steps">${stepsHtml}</div>
                </details>
            `
            : `
                <section class="assistant-section think-panel live">
                    <div class="section-title">思考过程</div>
                    <div class="think-steps">${stepsHtml}</div>
                </section>
            `;
    }

    let answerText = parts.answer;
    if (!answerText && done && stopped) {
        answerText = '已手动停止本次回复。';
    }

    const answerHtml = answerText
        ? formatRichText(answerText)
        : `<div class="typing-state">
                <span class="typing-indicator"></span>
                <span>${stopped ? '正在停止输出...' : '正在组织回答...'}</span>
           </div>`;

    bubble.innerHTML = `
        <div class="message-meta">
            <div class="meta-main">
                <span class="message-role">无线实验室智能体</span>
                <span class="message-time">${bubble.dataset.time || formatTime(new Date())}</span>
            </div>
            <span class="message-status ${statusClass}">${status}</span>
        </div>
        ${thinkingHtml}
        <section class="assistant-section answer-panel">
            <div class="section-title">回答</div>
            <div class="message-text">${answerHtml}</div>
        </section>
    `;
}

function renderThinkingSteps(thinking) {
    const steps = thinking
        .split(/\n{2,}/)
        .map(step => step.trim())
        .filter(Boolean);

    if (steps.length === 0) {
        return `
            <div class="think-step">
                <span class="step-index">1</span>
                <div class="step-body">正在拆解问题...</div>
            </div>
        `;
    }

    return steps.map((step, index) => `
        <div class="think-step">
            <span class="step-index">${index + 1}</span>
            <div class="step-body">${formatRichText(step)}</div>
        </div>
    `).join('');
}

function renderErrorBubble(bubble, message) {
    bubble.innerHTML = `
        <div class="message-meta">
            <div class="meta-main">
                <span class="message-role">无线实验室智能体</span>
                <span class="message-time">${bubble.dataset.time || formatTime(new Date())}</span>
            </div>
            <span class="message-status status-error">失败</span>
        </div>
        <section class="assistant-section answer-panel">
            <div class="section-title">错误信息</div>
            <div class="message-text"><p>${escapeHtml(message || '请求失败')}</p></div>
        </section>
    `;
}

function splitAssistantContent(text) {
    const thinkStart = text.indexOf('<think>');
    const thinkEnd = text.indexOf('</think>');

    if (thinkStart === -1) {
        return {
            thinking: '',
            answer: text.trim()
        };
    }

    if (thinkEnd === -1) {
        return {
            thinking: text.substring(thinkStart + 7).trim(),
            answer: text.substring(0, thinkStart).trim()
        };
    }

    return {
        thinking: text.substring(thinkStart + 7, thinkEnd).trim(),
        answer: (text.substring(0, thinkStart) + text.substring(thinkEnd + 8)).trim()
    };
}

function resolveAssistantStatus(rawText, done, stopped) {
    if (stopped) return done ? '已停止' : '正在停止';
    if (done) return '已完成';

    const parts = splitAssistantContent(rawText);
    if (parts.answer) return '回答中';
    if (parts.thinking) return '思考中';
    return '生成中';
}

function getStatusClass(status, done, stopped, answer) {
    if (stopped) return 'status-stopped';
    if (done) return 'status-done';
    if (status === '思考中') return 'status-thinking';
    if (status === '回答中' || answer) return 'status-answering';
    return 'status-working';
}

function formatRichText(text) {
    const normalized = String(text || '').replace(/\r\n/g, '\n').trim();
    if (!normalized) {
        return '';
    }

    const codeBlocks = [];
    const placeholderText = normalized.replace(/```([\w-]*)\n([\s\S]*?)```/g, (_, lang, code) => {
        const token = `__CODE_BLOCK_${codeBlocks.length}__`;
        codeBlocks.push({
            lang: escapeHtml(lang || ''),
            code: escapeHtml(code.trim())
        });
        return token;
    });

    const blocks = placeholderText.split(/\n{2,}/).map(block => block.trim()).filter(Boolean);

    return blocks.map(block => {
        if (/^__CODE_BLOCK_\d+__$/.test(block)) {
            const idx = Number(block.match(/\d+/)[0]);
            const codeBlock = codeBlocks[idx];
            return `
                <pre><code>${codeBlock.code}</code></pre>
            `;
        }

        const headingMatch = block.match(/^(#{1,3})\s+(.+)$/);
        if (headingMatch) {
            const level = Math.min(headingMatch[1].length + 1, 4);
            return `<h${level}>${formatInlineText(headingMatch[2])}</h${level}>`;
        }

        const lines = block.split('\n').map(line => line.trim()).filter(Boolean);
        if (lines.length > 0 && lines.every(line => /^\d+[.\u3001]\s+/.test(line))) {
            return `
                <ol class="rich-list ordered">
                    ${lines.map(line => `<li>${formatInlineText(line.replace(/^\d+[.\u3001]\s+/, ''))}</li>`).join('')}
                </ol>
            `;
        }

        if (lines.length > 0 && lines.every(line => /^[-*]\s+/.test(line))) {
            return `
                <ul class="rich-list">
                    ${lines.map(line => `<li>${formatInlineText(line.replace(/^[-*]\s+/, ''))}</li>`).join('')}
                </ul>
            `;
        }

        return `<p>${lines.map(formatInlineText).join('<br>')}</p>`;
    }).join('');
}

function formatInlineText(text) {
    return escapeHtml(text)
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        .replace(/`([^`]+)`/g, '<code>$1</code>');
}

function removeWelcomeScreen() {
    const welcome = document.querySelector('.welcome-screen');
    if (welcome) {
        welcome.remove();
    }
}

function setStreamingState(isStreaming) {
    const sendBtn = document.getElementById('sendBtn');
    const stopBtn = document.getElementById('stopBtn');
    const hint = document.getElementById('streamHint');

    sendBtn.disabled = isStreaming;
    stopBtn.classList.toggle('hidden', !isStreaming);
    stopBtn.disabled = !isStreaming;
    hint.textContent = isStreaming
        ? '正在生成回答，可点击“停止”中断输出'
        : 'Enter 发送，Shift + Enter 换行';
    document.body.classList.toggle('is-streaming', isStreaming);
}

function createChatId() {
    return 'conv-' + Date.now();
}

function createStreamId() {
    return 'stream-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8);
}

function formatTime(date) {
    return date.toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit'
    });
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function scrollToBottom() {
    const el = document.getElementById('chatMessages');
    el.scrollTop = el.scrollHeight;
}

async function refreshHardwareStatus() {
    const serviceStatus = document.getElementById('serviceStatus');
    try {
        const response = await getHardwareStatus();
        const data = response.data || {};
        if (response.code !== 0 || data.status === 'error' || data.connected === false) {
            renderHardwareOffline(data.message || response.message || 'Agent_SDR 未连接');
            return;
        }

        const diagnostics = data.diagnostics || {};
        const usrp = diagnostics['USRP-01'] || {};
        const online = usrp.status === 'online' || usrp.status === 'running' || usrp.status === 'busy';

        serviceStatus.textContent = '服务在线';
        serviceStatus.className = 'status-pill online';
        setTelemetryText('usrpStatus', usrp.status || 'unknown');
        setTelemetryText('usrpIp', data.usrp_01_ip || '--');
        setTelemetryText('centerFreq', usrp.center || '--');
        setTelemetryText('sampleRate', usrp.sample || '--');
        setTelemetryText('gainValue', usrp.gain || '--');
        setTelemetryText('modulationValue', usrp.modulation || '--');

        const taskState = data.visualization_active ? '可视化任务运行中' : '当前无可视化任务';
        const driverState = online ? 'USRP 已连接' : `USRP ${usrp.status || '离线'}`;
        setTelemetryText('diagnosticText', `${driverState}；${taskState}`);
    } catch (error) {
        renderHardwareOffline(error.message || '状态读取失败');
    }
}

function renderHardwareOffline(message) {
    const serviceStatus = document.getElementById('serviceStatus');
    serviceStatus.textContent = '服务离线';
    serviceStatus.className = 'status-pill offline';
    setTelemetryText('usrpStatus', 'offline');
    setTelemetryText('centerFreq', '--');
    setTelemetryText('sampleRate', '--');
    setTelemetryText('gainValue', '--');
    setTelemetryText('modulationValue', '--');
    setTelemetryText('diagnosticText', message);
}

async function stopHardwareFromPanel() {
    const button = document.getElementById('stopHardwareBtn');
    button.disabled = true;
    button.textContent = '正在停止...';
    try {
        const response = await stopHardwareTask();
        const data = response.data || {};
        if (data.status === 'error' || data.connected === false) {
            setTelemetryText('diagnosticText', data.message || '停止失败');
        } else {
            setTelemetryText('diagnosticText', '停止指令已提交，等待设备释放。');
        }
        await refreshHardwareStatus();
    } catch (error) {
        setTelemetryText('diagnosticText', error.message || '停止失败');
    } finally {
        button.disabled = false;
        button.textContent = '停止硬件任务';
    }
}

function setTelemetryText(id, value) {
    const element = document.getElementById(id);
    if (element) element.textContent = value;
}

function escapeHtml(text) {
    return String(text || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}
